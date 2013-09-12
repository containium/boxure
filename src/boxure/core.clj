;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns boxure.core
  (:refer-clojure :exclude (eval))
  (:require [clojure.edn :as edn]
            [clojure.java.io :refer (as-url)]
            [leiningen.core.project :refer (init-profiles project-with-profiles)]
            [leiningen.core.classpath :refer (resolve-dependencies)]
            [classlojure.core :refer (invoke-in with-classloader eval-in*)])
  (:import [boxure BoxureClassLoader]
           [java.net URL]
           [java.util.concurrent LinkedBlockingQueue TimeUnit]
           [java.util.jar JarFile]
           [clojure.lang DynamicClassLoader])
  (:gen-class))


;;; Helper methods.

(defn- error
  [message]
  (throw (AssertionError. message)))


(defn- read-from-jar
  "Reads a jar file entry contents into a String."
  [file inner-path]
  (try
    (with-open [jar (JarFile. file)]
      (if-let [entry (.getJarEntry jar inner-path)]
        (slurp (.getInputStream jar entry))
        (error (str "Could not find file '" inner-path "' in: " file))))
    (catch Exception ex
      (error (str "Could not find or read JAR file: " file "\nReason: " ex)))))


(defn- read-project-str
  [project-str file]
  ;; Adapted from leiningen.core.project/read.
  (binding [*ns* (find-ns 'leiningen.core.project)]
    (try (clojure.core/eval (read-string project-str))
         (catch Exception e
           (error (str "Could not read project map in '" file "': "
                       (.getMessage e))))))
  (let [project (resolve 'leiningen.core.project/project)]
    (when-not project
      (error (str "The project.clj must define a project map in: " file)))
    (ns-unmap 'leiningen.core.project 'project)
    (init-profiles (project-with-profiles @project) [:default])))


(defn jar-project
  [file]
  (read-project-str (read-from-jar file "project.clj") file))


;;; Boxure implementation.

(defn- eval-in-boxure
  [box-cl form]
  (let [bound-form `(clojure.main/with-bindings (clojure.core/eval '~form))]
    (with-classloader box-cl
      (invoke-in box-cl clojure.lang.Compiler/eval [Object] bound-form))))


(defn- boxure-thread-fn
  [box-cl classpath command-q options name]
  (fn []
    (.loadClass box-cl "clojure.lang.RT")
    (eval-in* box-cl '(require 'clojure.main))
    (println (str "[Boxure " name " ready for commands]"))
    (loop []
      (if-let [command (.poll command-q 60 TimeUnit/SECONDS)]
        (if-not (= command :stop)
          (let [[form promise] command
                form-pr (pr-str form)
                _ (println (str "[Boxure " name " received evaluation command: "
                                (if (> (count form-pr) 30)
                                  (str (subs (pr-str form) 0 30) "...")
                                  form-pr)
                                "]"))
                result (try (eval-in-boxure box-cl form)
                            (catch Exception e e))]
            (when promise (deliver promise result))
            (recur))
          (println (str "[Boxure " name " received stop command]")))
        (recur)))))


;;; Boxure library API.

(defrecord Boxure [name command-q thread box-cl project])

(defn boxure
  "Creat a new box, based on a parent classloader and a File to the
  JAR one wants to load. The JAR must contain a project.clj.

  One can also supply an options map. The following options are
  available:

  :resolve-dependencies - When truthful, the dependencies as specified
  in the project.clj of the JAR are resolved and added to the
  classpath of the box.

  :isolates - A sequence of regular expression (String) matching class
  names that should be loaded in isolation in the box. Note that all
  Clojure code that was loaded in the parent classloader should be
  isolated! For example, if `clojure.pprint` was loaded in the
  application, one would have an isolates sequence like
  [\"clojure\\.pprint.*\"]. Classes loaded due to the Boxure library
  do not need to be specified in here."
  [options parent-cl file]
  (let [project (jar-project file)
        dependencies (when (:resolve-dependencies options)
                       (map #(.getAbsolutePath %)
                            (resolve-dependencies :dependencies project)))
        classpath (cons (.getAbsolutePath file) dependencies)
        command-q (new LinkedBlockingQueue)
        box-cl (BoxureClassLoader. (into-array URL (map (comp as-url (partial str "file:"))
                                                        classpath))
                                   parent-cl
                                   (apply str (interpose "|" (:isolates options))))
        thread (Thread. (boxure-thread-fn box-cl classpath command-q options (:name project))
                        (str (:name project) "box"))]
    (.start thread)
    (Boxure. (:name project) command-q thread box-cl project)))


(defn eval
  "Queue a form to be evaluated in the box, in the box's thread. At
  least all of the EDN data sturctures can be send in the form. A
  promise is returned, in which the result of the evaluated form will
  be delivered. If an exception was raised during evaluation, it is
  caught by the box and delivered through the promise as well."
  [box form]
  (let [answer (promise)]
    (.offer (:command-q box) [form answer])
    answer))


(defn stop
  "Queue the stop command to a box, which will close the box's Thread.
  A future is returned, which will be delivered when the thread has
  stopped. Forms queued after the stop command will not be evaluated
  anymore."
  [box]
  (.offer (:command-q box) :stop)
  (future (while (.isAlive (:thread box))
            (Thread/sleep 200))
          :stopped))


(defn clean-and-stop
  "The same as `stop`, with some added calls to clean up the Clojure
  runtime inside the box. This prevents memory leaking boxes."
  [box]
  (eval box '(shutdown-agents))
  (eval box '(clojure.lang.Var/resetThreadBindingFrame nil))
  (stop box))


(defn call-in-box
  "Expirimental, as this might cause leaks?"
  [box f & args]
  (with-classloader (:box-cl box)
    (apply f args)))
