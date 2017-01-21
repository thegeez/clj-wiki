;; Copyright © 2016, JUXT LTD.

;; A complete development environment for websites in Clojure and
;; ClojureScript.

;; Most users will use 'boot dev' from the command-line or via an IDE
;; (e.g. CIDER).

;; See README.md for more details.

(require '[clojure.java.shell :as sh])

(defn next-version [version]
  (when version
    (let [[a b] (next (re-matches #"(.*?)([\d]+)" version))]
      (when (and a b)
        (str a (inc (Long/parseLong b)))))))

(defn deduce-version-from-git
  "Avoid another decade of pointless, unnecessary and error-prone
  fiddling with version labels in source code."
  []
  (let [[version commits hash dirty?]
        (next (re-matches #"(.*?)-(.*?)-(.*?)(-dirty)?\n"
                          (:out (sh/sh "git" "describe" "--dirty" "--long" "--tags" "--match" "[0-9].*"))))]
    (cond
      dirty? (str (next-version version) "-" hash "-dirty")
      (pos? (Long/parseLong commits)) (str (next-version version) "-" hash)
      :otherwise version)))

(def project "edge")
(def version (deduce-version-from-git))

(set-env!
 :source-paths #{"src"}
 :resource-paths #{"resources"
                   "src" ;; add sources to uberjar
                   }
 :dependencies
 '[[reloaded.repl "0.2.1" :scope "test"]

   [org.clojure/clojure "1.9.0-alpha14"]

   [org.clojure/tools.nrepl "0.2.12"]

   ;; Server deps
   [aero "1.0.1"]
   [bidi "2.0.14"]
   [com.stuartsierra/component "0.3.1"]
   [org.clojure/tools.namespace "0.2.11"]
   ;;[prismatic/schema "1.0.4"]
   [selmer "1.0.4"]
   [yada "1.2.0" :exclusions [aleph manifold]]

   [aleph "0.4.2-alpha8"]
   [manifold "0.1.6-alpha1"]

   [org.bitbucket.cowwoc/diff-match-patch "1.1"]
   [markdown-clj "0.9.91"]
   [com.datomic/clj-client "0.8.606"]

   ;; Logging
   [org.clojure/tools.logging "0.3.1"]
   [org.slf4j/jcl-over-slf4j "1.7.21"]
   [org.slf4j/jul-to-slf4j "1.7.21"]
   [org.slf4j/log4j-over-slf4j "1.7.21"]
   [ch.qos.logback/logback-classic "1.1.5"
    :exclusions [org.slf4j/slf4j-api]]])

(require '[com.stuartsierra.component :as component]
         'clojure.tools.namespace.repl
         '[edge.system :refer [new-system]])

(def repl-port 5600)

(task-options!
 pom {:project (symbol project)
      :version version
      :description "A complete Clojure project you can leap from"
      :license {"The MIT License (MIT)" "http://opensource.org/licenses/mit-license.php"}}
 aot {:namespace #{'edge.entrypoint}
      }
 jar {:main 'edge.entrypoint
      :file (str project "-app.jar")})

(deftask dev-system
  "Develop the server backend. The system is automatically started in
  the dev profile."
  []
  (require 'reloaded.repl)
  (let [go (resolve 'reloaded.repl/go)]
    (try
      (require 'user)
      (go)
      (catch Exception e
        (boot.util/fail "Exception while starting the system\n")
        (boot.util/print-ex e))))
  identity)

(deftask dev
  "This is the main development entry point."
  []
  (set-env! :dependencies #(vec (concat % '[[reloaded.repl "0.2.1"]])))
  (set-env! :source-paths #(conj % "dev"))

  ;; Needed by tools.namespace to know where the source files are
  (apply clojure.tools.namespace.repl/set-refresh-dirs (get-env :directories))

  (comp
   (watch)
   (repl :server true
         :port repl-port
         :init-ns 'user)
   (dev-system)
   (target)))

(deftask build
  []
  (target :dir #{"static"}))

(defn- run-system [profile]
  (println "Running system with profile" profile)
  (let [system (new-system profile)]
    (component/start system)
    (intern 'user 'system system)
    (with-pre-wrap fileset
      (assoc fileset :system system))))

(deftask run [p profile VAL kw "Profile"]
  (comp
   (repl :server true
         :port (case profile :prod 5601 :beta 5602 5600)
         :init-ns 'user)
   (run-system (or profile :prod))
   (wait)))

(deftask uberjar
  "Build an uberjar"
  []
  (println "Building uberjar")
  (comp
   (aot)
   (pom)
   (uber)
   (jar)
   (target)))

(deftask show-version "Show version" [] (println version))
