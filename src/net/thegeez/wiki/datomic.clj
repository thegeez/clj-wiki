(ns net.thegeez.wiki.datomic
  (:require [clojure.tools.logging :as log]
            [datomic.client :as client]
            [clojure.core.async :as async]
            [com.stuartsierra.component :as component]))

(defn make-conn [args-map]
  (let [conn (async/<!! (client/connect args-map))]
    (if (client/error? conn)
      (throw (ex-info "Can't create connection to datomic peer-server"
                      (merge conn
                             args-map)))
      (let [db (client/db conn)
            _ (log/info "poking datomic peer-server connection")
            poke (async/<!!
                  (client/q conn
                            {:query
                             '{:find [?e]
                               :where [[?e :db/ident :db/doc]]}
                             :args [db]}))]
        (log/info (str "poked datomic peer-server connection: " poke))
        (if (client/error? poke)
          ;; assume the peer-server has been restarted, can't keep connection to restarted peer-server running 'mem' database
          (if (= (:attempts-left args-map) 0)
            (throw (ex-info "Exhausted connection attempts to datomic peer server, is it running?" args-map))
            (make-conn (-> args-map
                           (assoc :cache-bust (java.util.UUID/randomUUID))
                           (update :attempts-left (fnil dec 5)))))
          conn
          )))))

(defrecord Datomic [args-map]
  component/Lifecycle
  (start [component]
    (let [args-map (assoc args-map
                          :account-id client/PRO_ACCOUNT
                          :region "none"
                          :service "peer-server")
          _ (log/info "First connection to Datomic peer-server")]
      (assoc component :get-conn (fn []
                                   (make-conn args-map)))))

  (stop [component]
    (assoc component :get-conn nil)))

(defn component []
  (map->Datomic {}))
