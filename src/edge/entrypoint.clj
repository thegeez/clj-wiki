(ns edge.entrypoint
  "Entrypoint for production Uberjars"
  (:gen-class))

(def system nil)

(defn -main
  [& args]
  (require 'edge.main)
  (apply (resolve 'edge.main/-main) args))

