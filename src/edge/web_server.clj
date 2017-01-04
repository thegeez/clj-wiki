;; Copyright © 2016, JUXT LTD.

(ns edge.web-server
  (:require
   [bidi.bidi :refer [tag]]
   [bidi.vhosts :refer [make-handler vhosts-model]]
   [clojure.tools.logging :refer :all]
   [com.stuartsierra.component :refer [Lifecycle using]]
   [clojure.java.io :as io]
   [net.thegeez.wiki.wiki :as wiki]
   [schema.core :as s]
   [selmer.parser :as selmer]
   [yada.resources.webjar-resource :refer [new-webjar-resource]]
   [yada.yada :refer [resource] :as yada]
   [yada.handler :as handler]
   [clojure.walk :as walk]))

(defn routes
  "Create the URI route structure for our application."
  [config]
  [""
   [["/" (yada/redirect ::wiki/wiki-index)]

    (wiki/wiki-routes)

    ["/wiki.css"
     (-> (yada/as-resource (io/resource "public/wiki.css"))
         (assoc :id ::stylesheet))]

    ;; This is a backstop. Always produce a 404 if we ge there. This
    ;; ensures we never pass nil back to Aleph.
    [true (yada/handler nil)]]])

(s/defrecord WebServer [host :- s/Str
                        port :- s/Int
                        fs2
                        listener
                        datomic]
  Lifecycle
  (start [component]
    (if listener
      component                         ; idempotence
      (let [fs2-host (:url fs2)
            routes (routes {:port port})
            routes (walk/postwalk
                    (fn [node]
                      (if (instance? yada.resource.Resource node)
                        ;; used to be only prepend our interceptor, but since 0.1.46 the resource will not have any interceptors added yet at this point :(
                        (assoc node :interceptor-chain
                               (into [(fn [ctx]
                                        (assoc ctx
                                               :conn ((:get-conn datomic))
                                               :fs2-host fs2-host))]
                                     yada/default-interceptor-chain))
                        node))
                    routes)
            vhosts-model
            (vhosts-model
             [{:scheme :http :host host}
              routes])
            listener (yada/listener vhosts-model {:port port})]
        (infof "Started web-server on port %s" (:port listener))
        (assoc component :listener listener))))

  (stop [component]
    (when-let [close (get-in component [:listener :close])]
      (close))
    (assoc component :listener nil)))

(defn new-web-server []
  (using
   (map->WebServer {})
   [:fs2 :datomic :migrator]))
