;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns boxure.core
  (:require [clojure.edn :as edn]
            [clojure.java.io :refer (as-url)]
            [leiningen.core.project :refer (init-profiles project-with-profiles)]
            [leiningen.core.classpath :refer (resolve-dependencies)]
            [classlojure.core :refer (classlojure eval-in)])
  (:import [java.util.jar JarFile]
           [clojure.lang RT Compiler DynamicClassLoader])
  (:gen-class))


(defn error
  [message]
  (throw (AssertionError. message)))


(defmacro asserts
  [test-message-pairs & body]
  (if-let [[test message] test-message-pairs]
    `(if-not ~test
       (error ~message)
       (asserts ~(next test-message-pairs) ~@body))
    `(do ~@body)))


(defn resolve-file-path
  "Given a root file name and a path, this function returns the path to
  the file, as resolved from the specified root. If the resulting path
  is at or deeper than the current working directory, that relative path is
  returned. Otherwise, an absolute path is returned."
  ;; Adapted from aroemers/gluer.main.
  [root path]
  (let [root-uri (.toURI (java.io.File. root))
        pwd-uri (.toURI (java.io.File. "."))]
    (.getPath (.relativize pwd-uri (.resolve root-uri path)))))


(defn read-from-jar [jar-path inner-path]
  (if-let [jar (try (JarFile. jar-path) (catch Exception ex))]
    (if-let [entry (.getJarEntry jar inner-path)]
      (slurp (.getInputStream jar entry))
      (error (str "Could not find file '" inner-path "' in: " jar-path)))
    (error (str "Could not find or read JAR file: " jar-path))))


(defn- read-project-string
  [path project-str]
  ;; Adapted from leiningen.core.project/read.
  (binding [*ns* (find-ns 'leiningen.core.project)]
    (try (eval (read-string project-str))
         (catch Exception e
           (error (str "Could not read project map in '" path "': "
                       (.getMessage e))))))
  (let [project (resolve 'leiningen.core.project/project)]
    (when-not project
      (error (str "The project.clj must define a project map in: " path)))
    (ns-unmap 'leiningen.core.project 'project)
    (init-profiles (project-with-profiles @project) [:default])))


(defn- module-spec
  [path]
  (let [project (read-project-string path (read-from-jar path "project.clj"))]
    (let [{{:keys [start stop] :as boxure} :boxure} project]
      (asserts [boxure (str "Could not find :boxure key in project.clj: " path)
                start (str "Missing :start in :boxure for: " path)
                stop (str "Missing :stop in :boxure for: " path)]
        (assoc boxure :project project)))))


(defn- cl-hierarchy-str
  [cl]
  (loop [acc [cl]
         parent (.getParent cl)]
    (if parent
      (recur (conj acc parent) (.getParent parent))
      (apply str (interpose " => " acc)))))


(defn- run-box
  [spec-path]
  (let [{:keys [name module-paths config] :as spec}
        (try (edn/read-string (slurp spec-path)) (catch Exception ex))]
    (asserts [spec (str "Could not find or read: " spec-path)
              name (str "Missing :name in box spec: " spec-path)]
      (let [module-paths (map (partial resolve-file-path spec-path) module-paths)
            module-specs (for [path module-paths] (module-spec path))
            classpath (for [spec module-specs
                            dep (resolve-dependencies :dependencies (:project spec))]
                        (.getAbsolutePath dep))]
        (println "Classpath:" classpath)
        (doseq [i (range 100)]
          (let [box-cl (java.lang.ref.WeakReference. (#'classlojure.core/url-classloader (map (partial str "file:") classpath)
                                                            classlojure.core/ext-classloader))
                ;; box-cl (java.lang.ref.WeakReference. (DynamicClassLoader. classlojure.core/ext-classloader))
                box-cl (do
                         #_(doseq [cp classpath]
                           (.addURL (.get box-cl) (as-url (str "file:" cp))))

                         (println "Number" i)
                         ;; (classlojure.core/eval-in* box-cl '(println *clojure-version*))
                         (println "Root RT baseloader" (cl-hierarchy-str (RT/baseLoader)))
                         (println "Root RT classloader" (cl-hierarchy-str (.getClassLoader RT)))
                         (println "Root context CL" (binding [*use-context-classloader* true]
                                                      (cl-hierarchy-str (RT/baseLoader))))
                         (classlojure.core/with-classloader (.get box-cl)
                           (println "Box RT baseloader" (cl-hierarchy-str (classlojure.core/invoke-in (.get box-cl) clojure.lang.RT/baseLoader [])))
                           (println "Box Var classloader" (cl-hierarchy-str (.. (classlojure.core/invoke-in (.get box-cl) clojure.lang.RT/var [String String] "foo" "bar")
                                                                                getClass
                                                                                getClassLoader))))
                         (println "------------------------------------------------")
                         (classlojure.core/with-classloader (.get box-cl)
                           (println (->> (let [s (pr-str '(do (let [a 5]
                                                                (prn (+ 37 a))
                                                                (shutdown-agents))))]
                                           (println s) s) ; flabbergasted. (or) works, (+) does not..
                                         (classlojure.core/invoke-in (.get box-cl) clojure.lang.RT/readString [String])
                                         (classlojure.core/invoke-in (.get box-cl) clojure.lang.Compiler/eval [Object])
                                         (classlojure.core/invoke-in (.get box-cl) clojure.lang.RT/printString [Object]))
                                    (-> (.get box-cl)
                                        (.loadClass "clojure.lang.Var")
                                        (.getDeclaredMethod "resetThreadBindingFrame" (into-array Class [Object]))
                                        (.invoke (.loadClass (.get box-cl) "clojure.lang.Var") (into-array Object [nil])))
                                    nil))
                         nil)])
          (System/gc)
          (Thread/sleep 5000)
          ;; (let [box-cl (apply classlojure (map (partial str "file:") classpath))]
          ;;   (println (eval-in box-cl '*clojure-version*) "- deploy number" i)
          ;;   (eval-in box-cl '(clojure.core/shutdown-agents))
          ;;   (System/gc)
          ;;   (Thread/sleep 1000))
          ;; (let [box-cl (apply classlojure (map (partial str "file:") classpath))
          ;;       handler-var-expr (eval-in box-cl '(do (ns webapp)
          ;;                                             (defn app [req]
          ;;                                               (assoc req :handled true))))]
          ;;   (println (eval-in box-cl
          ;;                     `(fn [~'req] (~handler-var-expr (read-string ~'req)))
          ;;                     (pr-str {:request-method :get :uri "/"})))
          ;;   (System/gc)
          ;;   (Thread/sleep 2000))
          )))))


;;--- TODO: Make it a library, if possible.

(defn -main
  "I do a whole lot."
  [& args]
  (if-let [spec-path (first args)]
    (run-box spec-path)
    (println "Please specify the root box specification as an argument.")))
