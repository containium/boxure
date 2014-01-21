(defproject boxure "0.1.0-SNAPSHOT"
  :description "A Clojure runtime isolating classloader."
  :url "http://github.com/containium/boxure"
  :license {:name "Mozilla Public License 2.0"
            :url "http://mozilla.org/MPL/2.0/"}
  :dependencies [[boxure/clojure "1.5.1"]

                 ;; Until Leiningen fixes it's plexus and wagon deps, we add and upgrade it explicitly:
                 [leiningen-core "2.3.4" :exclusions [org.clojure/clojure org.codehaus.plexus/plexus-utils org.apache.maven.wagon/wagon-provider-api]]
                 [org.codehaus.plexus/plexus-utils "3.0.16"]
                 [org.apache.maven.wagon/wagon-provider-api "2.4"]

                 [classlojure "0.6.6"]]
  :exclusions [org.clojure/clojure]
  :global-vars {*warn-on-reflection* true}
  :jvm-opts ["-XX:+UseConcMarkSweepGC"
             "-XX:+CMSClassUnloadingEnabled"
             "-XX:MaxPermSize=35m"
             ;; "-XX:+TraceClassLoading"
             ;; "-XX:+TraceClassUnloading"
             ;; "-XX:+HeapDumpOnOutOfMemoryError"
             ]
  :java-source-paths ["src-java"]
  :pom-plugins [[com.theoryinpractise/clojure-maven-plugin "1.3.15"
                 {:extensions "true"
                  :configuration ([:sourceDirectories [:sourceDirectory "src"]]
                                  [:temporaryOutputDirectory "true"])
                  :executions [:execution
                               [:id "compile-clojure"]
                               [:phase "compile"]
                               [:goals [:goal "compile"]]]}]])
