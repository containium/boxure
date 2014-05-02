;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns boxure.core
  (:refer-clojure :exclude (eval))
  (:require [clojure.edn :as edn]
            [clojure.main] ; For lein compile :all
            [clojure.java.io :refer (as-url file)]
            [leiningen.core.project :refer (init-profiles project-with-profiles)]
            [leiningen.core.classpath :refer (resolve-dependencies)]
            [classlojure.core :refer (invoke-in with-classloader eval-in*)])
  (:import [boxure BoxureClassLoader]
           [java.io File]
           [java.net URL]
           [java.util.concurrent LinkedBlockingQueue TimeUnit]
           [java.util.jar JarFile]
           [clojure.lang DynamicClassLoader])
  (:gen-class))


;;; Remove memoize from leiningen.core.classpath/get-dependencies.

(with-redefs [memoize identity]
  (require 'leiningen.core.classpath :reload))


;;; Helper methods.

(defn- error
  [message]
  (throw (AssertionError. message)))


(defn- read-from-jar
  "Reads a jar file entry contents into a String."
  [^File file inner-path]
  (try
    (with-open [jar (JarFile. file)]
      (if-let [entry (.getJarEntry jar inner-path)]
        (slurp (.getInputStream jar entry))
        (error (str "Could not find file '" inner-path "' in: " file))))
    (catch Exception ex
      (error (str "Could not find or read JAR file: " file "\nReason: " ex)))))


(defn- read-project-str
  [project-str file profiles]
  ;; Adapted from leiningen.core.project/read.
  (locking (var read-project-str)
   (binding [*ns* (find-ns 'leiningen.core.project)]
     (try (clojure.core/eval (read-string project-str))
          (catch Exception e
            (error (str "Could not read project map in '" file "': "
                        (.getMessage e))))))
   (let [project (resolve 'leiningen.core.project/project)]
     (when-not project
       (error (str "The project.clj must define a project map in: " file)))
     (ns-unmap 'leiningen.core.project 'project)
     (init-profiles (project-with-profiles @project) profiles))))


(defn- jar-project
  "Returns the project.clj map from a JAR file."
  [file profiles]
  (read-project-str (read-from-jar file "project.clj") file profiles))


(defn- dir-project
  "Returns the project.clj map from a directory."
  [^File dir profiles]
  (let [project-file (file dir "project.clj")]
    (read-project-str (slurp project-file) project-file profiles)))


(defn file-project
  "Returns the project.clj map from the specified file. The file may
  point to a JAR file or to a directory. One can specify a vector of
  profiles to apply, which defaults to [:default]."
  ([^File file]
     (file-project file [:default]))
  ([^File file profiles]
     (if (.isDirectory file)
       (dir-project file profiles)
       (jar-project file profiles))))

(defn ensure-prefix [prefix ^String string]
  (if (.startsWith string prefix) string (str prefix string)))

(defn- file-classpath
  "Given a file and the project.clj map, returns the classpath entries
  for this file. The file may point to a directory or a JAR file."
  [^File file project]
  (if (.isDirectory file)
    (let [bad-root (:root project)
          good-root (.getAbsolutePath file)
          replace-root (fn [path]
                         (str good-root (ensure-prefix "/" (subs path (count bad-root))) "/"))]
      (concat (map replace-root (:source-paths project))
              (map replace-root (:resource-paths project))
              [(replace-root (:compile-path project))]
              (map #(.getAbsolutePath ^File %) (.listFiles (File. (str good-root "/lib"))))))
    [(.getAbsolutePath file)]))


;;; Boxure implementation.

(defn- log
  [options message]
  (when (:debug? options)
    (println message)))


(defn- boxure-thread-fn
  [^BoxureClassLoader box-cl classpath ^LinkedBlockingQueue command-q options name loaded]
  (fn []
    (.loadClass box-cl "clojure.lang.RT")
    (.setContextClassLoader (Thread/currentThread) box-cl)
    (clojure.core/push-thread-bindings {#'clojure.core/*ns-root* box-cl, #'clojure.core/*loaded-libs* loaded, clojure.lang.Compiler/LOADER box-cl})
    (require 'clojure.main) ; This is mostly to setup the loaded namespace and compiler state in this Thread, and allow (ns ..) evals.
    (log options (str "[Boxure " name " ready for commands]"))
    (loop []
      (if-let [command (.poll command-q 60 TimeUnit/SECONDS)]
        (if-not (= command :stop)
          (let [[form promise] command
                form-pr (pr-str form)
                _ (log options (str "[Boxure " name " received evaluation command: "
                                (if (> (count form-pr) 30)
                                  (str (subs (pr-str form) 0 30) "...")
                                  form-pr)
                                "]"))
                result (try (clojure.main/with-bindings (clojure.lang.Compiler/eval form))
                            (catch Throwable e e))]
            (when promise (deliver promise result))
            (recur))
          (log options (str "[Boxure " name " received stop command]")))
        (recur)))))


;;; Boxure library API.

(defrecord Boxure [name command-q thread box-cl project start-thread loaded])

(defn boxure
  "Creat a new box, based on a parent classloader and a File to the
  JAR or directory one wants to load. The JAR or directory must
  contain a project.clj. Whenever a directory is loaded, the
  :source-paths, :resource-paths and :compile-path from the project
  map are added to the classpath of the box, in that order.

  One can also supply an options map. The following options are
  available:

  :resolve-dependencies - When truthful, the dependencies as specified
  in the project.clj are resolved and added to the classpath of the
  box. Defaults to false.

  :isolates - A sequence of regular expression (String) matching class
  names that should be loaded in isolation in the box. Note that all
  Clojure code that was loaded in the parent classloader should be
  isolated! For example, if `clojure.pprint` was loaded in the
  application, one would have an isolates sequence like
  [\"clojure\\.pprint.*\"]. Classes loaded due to the Boxure library
  do not need to be specified in here. Defaults to an emply sequence.

  :debug? - A boolean indicating whether to print debug messages.
  Defaults to false.

  :profiles - A vector of profile keywords, which is used when reading
  the project map from the project.clj file. Defaults to [:default]."
  [options parent-cl file]
  (let [project (file-project file (or (:profiles options) [:default]))
        dependencies (when (:resolve-dependencies options)
                       (map #(.getAbsolutePath ^File %)
                            (resolve-dependencies :dependencies project)))
        classpath (concat (file-classpath file project) dependencies)
        urls (into-array URL (map (comp as-url (partial str "file:")) classpath))
        _ (when (:debug? options)
            (println "Classpath URLs for box:")
            (doseq [url urls] (print url)))
        command-q (new LinkedBlockingQueue)
        box-cl (BoxureClassLoader. urls parent-cl
                                   (apply str (interpose "|" (:isolates options)))
                                   (boolean (:debug? options)))
        loaded (ref (sorted-set))
        thread (Thread. ^Runnable (boxure-thread-fn box-cl classpath command-q options
                                                    (:name project) loaded)
                        (str (:name project) "-BOX"))]
    (.start thread)
    (Boxure. (:name project) command-q thread box-cl project (Thread/currentThread) loaded)))


(defn eval
  "Queue a form to be evaluated in the box, in the box's thread. At
  least all of the EDN data sturctures can be send in the form. A
  promise is returned, in which the result of the evaluated form will
  be delivered. If a Throwable was raised during evaluation, it is
  caught by the box and delivered through the promise as well. If an
  Error was raised, it may be best to kill the box."
  [box form]
  (let [answer (promise)]
    (.offer ^LinkedBlockingQueue (:command-q box) [form answer])
    answer))


(defn stop
  "Queue the stop command to a box, which will close the box's Thread.
  A future is returned, which will be delivered when the thread has
  stopped. Forms queued after the stop command will not be evaluated
  anymore."
  [box]
  (.offer ^LinkedBlockingQueue (:command-q box) :stop)
  (future (while (.isAlive ^Thread (:thread box))
            (Thread/sleep 20))
          :stopped))

(defn is-loaded-by-classloader? [classloader object]
  (when object
    (loop [cl (.getClassLoader (if (class? object) ^Class object #_else (class object)))]
      (or (= cl classloader)
          (if-let [cl (when cl (.getParent cl))] (recur cl) #_else false)))))

(defn remove-classloaded-methods! [classloader ^clojure.lang.MultiFn multifn]
  (let [is-loaded-by-classloader? (partial is-loaded-by-classloader? classloader)]
    (->> (prefers multifn)
         (remove (comp is-loaded-by-classloader? key))
         (map (fn [[k v]] [k (set (remove is-loaded-by-classloader? v))]))
         (into (hash-map))
         (.setPreferTable multifn))
    (doseq [[k v] (methods multifn)]
      (when (is-loaded-by-classloader? k)
        (remove-method multifn k))
      (when (is-loaded-by-classloader? v)
        (remove-method multifn k)))))

(defn clean-and-stop
  "The same as `stop`, with some added calls to clean up the Clojure
  runtime inside the box. This prevents memory leaking boxes."
  [box]
  @(stop box)
  (eval box '(shutdown-agents))
  (eval box '(clojure.lang.Var/resetThreadBindingFrame nil))
  (remove-classloaded-methods! (:box-cl box) clojure.core/print-method)
  (remove-classloaded-methods! (:box-cl box) clojure.core/print-dup)
  (.remove (clojure.lang.Namespace/namespaces) (:box-cl box))
  (BoxureClassLoader/cleanThreadLocals (:thread box))
  (when-let [start-thread (:start-thread box)]
    (when (not= (Thread/currentThread) start-thread)
      (BoxureClassLoader/cleanThreadLocals start-thread))))


(defn call-in-box
  "Expirimental, as this might cause leaks?"
  [box f & args]
  (try
    (with-classloader (:box-cl box)
      #_(prn (:name box) "loaded:" (:loaded box) (Thread/currentThread) (:thread box) " == " (= (Thread/currentThread) (:thread box))
         (.getContextClassLoader (Thread/currentThread)) (.getContextClassLoader (:thread box)) " == "
         (= (.getContextClassLoader (Thread/currentThread)) (.getContextClassLoader (:thread box))))
      (clojure.core/push-thread-bindings {#'clojure.core/*ns-root* (:box-cl box), #'clojure.core/*loaded-libs* (:loaded box), clojure.lang.Compiler/LOADER (:box-cl box)})
      (apply f args))
    (finally (clojure.core/pop-thread-bindings))))
