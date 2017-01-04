(ns net.thegeez.wiki.migrations)

(def migrations
  [:wiki/schema-migrations
   [1 [{:db/ident :article/slug
        :db/valueType :db.type/string
        :db/unique :db.unique/value
        :db/cardinality :db.cardinality/one}
       {:db/ident :article/title
        :db/valueType :db.type/string
        :db/cardinality :db.cardinality/one}]]
   [2 [{:db/ident :article/rev
        :db/valueType :db.type/string
        :db/cardinality :db.cardinality/one}]]])
