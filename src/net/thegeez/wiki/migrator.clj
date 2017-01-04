(ns net.thegeez.wiki.migrator
  (:require [clojure.tools.logging :as log]
            [datomic.client :as client]
            [clojure.core.async :as async]
            [com.stuartsierra.component :as component]
            [clojure.spec :as s]))

(defn increasing-versions? [v+m]
  (->> v+m
       :versions
       (map :version)
       (apply <)))

(s/def ::schema-attr-map map?)
(s/def ::version+maps
  (s/spec (s/cat :version long
                 :changes (s/spec (s/+ ::schema-attr-map)))))
(s/def ::attribute-for-version qualified-keyword?)
(s/def ::migrations
  (s/& (s/cat :ident ::attribute-for-version
              :versions (s/* ::version+maps))
       increasing-versions?))

(def version-attr-tx [{:db/ident :wiki.migrations/ident
                       :db/valueType :db.type/keyword
                       :db/unique :db.unique/identity
                       :db/cardinality :db.cardinality/one}
                      {:db/ident :wiki.migrations/version
                       :db/valueType :db.type/long
                       :db/cardinality :db.cardinality/one}])

(defrecord Migrator [datomic migrations]
  component/Lifecycle
  (start [component]
    (let [migrations (s/conform ::migrations migrations)
          _ (when (= migrations ::s/invalid)
              (throw (ex-info "migration definition fails spec"
                              {:data (s/explain-data ::migrations migrations)
                               :value migrations})))
          migration-ident (:ident migrations)
          conn ((:get-conn datomic))
          db (client/db conn)
          version-attr (async/<!! (client/transact conn
                                                   {:tx-data version-attr-tx}))

          existing-version (async/<!!
                            (client/q conn {:query
                                            '{:find [?v]
                                              :in [$ ?mk]
                                              :where [[?e :wiki.migrations/ident ?mk]
                                                      [?e :wiki.migrations/version ?v]]}
                                            :args [db migration-ident]}))
          existing-version (if (client/error? existing-version)
                             -1
                             (if-let [ev (ffirst existing-version)]
                               ev
                               -1))
          steps (drop-while (fn [{:keys [version]}]
                              (<= version existing-version))
                            (:versions migrations))]
      (if (not (seq steps))
        (log/info "Skipping migrations, already in db")

        (let [new-version (apply max (map :version steps))
              tx-data (into [{:wiki.migrations/ident migration-ident
                              :wiki.migrations/version new-version}]
                            (mapcat :changes steps))
              res (async/<!! (client/transact conn
                                              {:tx-data tx-data}))]
          (log/info "Added migrations: " res)))
      component))

  (stop [component]
    component))

(defn component [migrations]
  (component/using
   (map->Migrator {:migrations migrations})
   [:datomic]))
