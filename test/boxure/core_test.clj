;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns boxure.core-test
  (:require [clojure.test :refer :all]
            [boxure.core :refer :all]))

(deftest a-test
  (testing "Leaking"
    (doseq [i (range 10)]
      (println "Starting and stopping box nr" (inc i))
      (boxure-stop (boxure {:resolve-dependencies true}
                           (.getClassLoader clojure.lang.RT)
                           "dev-resources/modules/module.jar"))
      (System/gc)
      (Thread/sleep 1000))))
