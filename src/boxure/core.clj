;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns boxure.core
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


(defmacro asserts
  [test-message-pairs & body]
  (if-let [[test message] test-message-pairs]
    `(if-not ~test
       (error ~message)
       (asserts ~(next test-message-pairs) ~@body))
    `(do ~@body)))


(defn- resolve-file-path
  "Given a root file name and a path, this function returns the path to
  the file, as resolved from the specified root. If the resulting path
  is at or deeper than the current working directory, that relative path is
  returned. Otherwise, an absolute path is returned."
  ;; Adapted from aroemers/gluer.main.
  [root path]
  (let [root-uri (.toURI (java.io.File. root))
        pwd-uri (.toURI (java.io.File. "."))]
    (.getPath (.relativize pwd-uri (.resolve root-uri path)))))


(defn- read-from-jar [jar-path inner-path]
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


;;; Boxure implementation.

(defn- module-spec
  [path]
  (let [project (read-project-string path (read-from-jar path "project.clj"))]
    (let [{{:keys [start stop] :as boxure} :boxure} project]
      (asserts [boxure (str "Could not find :boxure key in project.clj: " path)
                start (str "Missing :start in :boxure for: " path)
                stop (str "Missing :stop in :boxure for: " path)]
        (assoc boxure :project project)))))


(defn- eval-in-boxure
  [box-cl form]
  (let [bound-form `(clojure.main/with-bindings (eval '~form))]
    (with-classloader box-cl
      (invoke-in box-cl clojure.lang.Compiler/eval [Object] bound-form))))


(defn- boxure-thread-fn
  [box-cl classpath command-q options name]
  (fn []
    (.loadClass box-cl "clojure.lang.RT")
    (eval-in* box-cl '(require 'clojure.main))
    (println (str "[Boxure " name " ready for commands]"))
    (loop []
      (if-let [command (.poll command-q 10 TimeUnit/SECONDS)]
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
        (do
          (println "[Boxure" name "idle]")
          (recur))))))


;;; Boxure library API.

(defrecord Boxure [name command-q thread box-cl])

(defn boxure
  [options parent-cl jar-path]
  (let [spec (module-spec jar-path)
        dependencies (when (:resolve-dependencies options)
                       (map #(.getAbsolutePath %)
                            (resolve-dependencies :dependencies (:project spec))))
        classpath (cons jar-path dependencies)
        command-q (new LinkedBlockingQueue)
        box-cl (BoxureClassLoader. (into-array URL (map (comp as-url (partial str "file:"))
                                                        classpath))
                                   parent-cl)
        thread (Thread. (boxure-thread-fn box-cl classpath command-q options
                                          (:name (:project spec))))]
    (.start thread)
    (Boxure. (:name (:project spec)) command-q thread box-cl)))


(defn boxure-eval
  [box form]
  (let [answer (promise)]
    (.offer (:command-q box) [form answer])
    answer))


(defn boxure-eval-and-wait
  [box form]
  (deref (boxure-eval box form)))


(defn boxure-stop
  [box]
  (.offer (:command-q box) :stop)
  (future (while (.isAlive (:thread box))) :stopped))


(defn call-in-boxure
  [box f & args]
  (with-classloader (:box-cl box)
    (apply f args)))
