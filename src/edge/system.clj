;; Copyright © 2016, JUXT LTD.

(ns edge.system
  "Components and their dependency relationships"
  (:require
   [aero.core :as aero]
   [clojure.java.io :as io]
   [com.stuartsierra.component :refer [system-map system-using]]
   [edge.selmer :refer [new-selmer]]
   [edge.web-server :refer [new-web-server]]
   [net.thegeez.wiki.datomic :as datomic]
   [net.thegeez.wiki.migrator :as migrator]
   [net.thegeez.wiki.migrations :as migrations]
   [net.thegeez.wiki.fixtures :as fixtures]
   [net.thegeez.wiki.fs2 :as fs2]))

(defn config
  "Read EDN config, with the given profile. See Aero docs at
  https://github.com/juxt/aero for details."
  [profile]
  (aero/read-config (io/resource "config.edn") {:profile profile}))

(defn configure-components
  "Merge configuration to its corresponding component (prior to the
  system starting). This is a pattern described in
  https://juxt.pro/blog/posts/aero.html"
  [system config]
  (merge-with merge system config))

(defn new-system-map
  "Create the system. See https://github.com/stuartsierra/component"
  [config]
  (apply system-map
         (cond-> [:web-server (new-web-server)
                  :fs2 (fs2/new-storage-server)
                  :selmer (new-selmer)
                  :datomic (datomic/component)
                  :migrator (migrator/component migrations/migrations)]
           (:fixtures (:datomic config))
           (into [:fixtures (fixtures/component)]))))

(defn new-dependency-map
  "Declare the dependency relationships between components. See
  https://github.com/stuartsierra/component"
  []
  {})

(defn new-system
  "Construct a new system, configured with the given profile"
  [profile]
  (let [config (config profile)]
    (-> (new-system-map config)
        (configure-components config)
        (system-using (new-dependency-map)))))
