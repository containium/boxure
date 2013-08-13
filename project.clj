(defproject boxure "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Mozilla Public License 2.0"
            :url "http://mozilla.org/MPL/2.0/"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [leiningen-core "2.2.0"]
                 [classlojure "0.6.6"]]
  :jvm-opts ["-XX:+UseConcMarkSweepGC"
             "-XX:+CMSClassUnloadingEnabled"
             "-XX:MaxPermSize=512m"
             ;; "-XX:+TraceClassLoading"
             "-XX:+TraceClassUnloading"]
  :main boxure.core)
