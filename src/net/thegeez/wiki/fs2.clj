(ns net.thegeez.wiki.fs2
  (:require
   [bidi.vhosts :refer [make-handler vhosts-model]]
   [clojure.tools.logging :refer :all]
   [com.stuartsierra.component :as component]
   [yada.yada :as yada]
   [yada.consume :as consume]
   [clojure.java.io :as io]))

(defn routes [private-path]
  ["/"
   ;; doesn't work without upload prefix :/
   [[["upload/" :file-name]
       (yada/resource
        {:parameters {:path {:file-name String}}
         :methods
         {:post
          {:consumes "application/octet-stream"
           :consumer (fn [ctx _ body-stream]
                       (let [file-name (get-in ctx [:parameters :path :file-name])
                             f (io/file private-path file-name)]
                         (infof "Saving to file: %s" f)
                         (consume/save-to-file
                          ctx body-stream
                          f)))
           :response (fn [ctx]
                       (let [file-name (get-in ctx [:parameters :path :file-name])
                             uri (str file-name)]
                         (java.net.URI. uri))
                       )}}})]
    ["" (-> (yada/as-resource (io/file private-path))
            (assoc :id ::files))]

    ["" (yada/handler nil)]]])

(defrecord FlimsyStorageServer [host
                                port
                                private-path
                                listener]
  component/Lifecycle
  (start [component]
    (let [_ (when (not (.exists (io/file private-path)))
              (throw (ex-info "Storage directory for fs2 does not exist"
                              {:path private-path})))
          routes (routes private-path)
          vhosts-model
          (vhosts-model
           [{:scheme :http :host (str host ":" port)}
            routes])
          listener (yada/listener vhosts-model {:port port})]
      (infof "Started flimsy storage server on port %s" (:port listener))
      (assoc component
             :listener listener
             :url (str "http://" host ":" port))))

  (stop [component]
    (when-let [close (get-in component [:listener :close])]
      (close))
    (assoc component :listener nil :host nil)))

(defn new-storage-server []
  (map->FlimsyStorageServer {}))
