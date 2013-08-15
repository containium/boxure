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
          (let [thread (Thread. (fn [] (let [box-cl (apply classlojure (map (partial str "file:") classpath))]
                                         (eval-in box-cl
                                                  '(do
                                                     (println *clojure-version*)
                                                     ;; Bind non-dynamic stuff.
                                                     (ns web)
                                                     ;; Have a global definition.
                                                     (defn app [req]
                                                       (assoc req :handled true))
                                                     ;; Test futures (using agents).
                                                     (println (deref (future (app {:request "/"}))))
                                                     ;; Test transactions.
                                                     (let [r (ref 35)]
                                                       (dosync (alter r + 7))
                                                       (println @r))
                                                     ;; Shutdown needed.
                                                     (shutdown-agents))))))]
            (.start thread)
            (while (.isAlive thread) (Thread/sleep 200)))
          (System/gc))))))


;;--- TODO: Make it a library, if possible.

(defn -main
  "I do a whole lot."
  [& args]
  (if-let [spec-path (first args)]
    (run-box spec-path)
    (println "Please specify the root box specification as an argument.")))
