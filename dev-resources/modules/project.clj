;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(defproject module "1.0"
  :dependencies [;; [org.clojure/clojure "1.5.1"]
                 [org.clojars.tcrawley/clojure "1.6.0-clearthreadlocals"]
                 ]
  :exclusions [org.clojure/clojure]
  :boxure {:start module.core/start
           :stop module.core/stop
           :ring-var module.core/app
           :config {:module/connect "localhost:123"}})
