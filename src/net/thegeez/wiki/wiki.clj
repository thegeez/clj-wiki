(ns net.thegeez.wiki.wiki
  (:require
   [bidi.bidi :as bidi]
   [clojure.tools.logging :refer :all]
   [clojure.string :as str]
   [selmer.parser :as selmer]
   [schema.core :as s]
   [yada.yada :as yada]
   [net.thegeez.wiki.diff :as diff]
   [markdown.core :as md]
   [markdown.transformers :as mdt]
   [datomic.client :as client]
   [clojure.core.async :as async]
   [manifold.deferred :as d]
   [manifold.stream :as stream]
   [aleph.http :as http]
   [byte-streams :as bs]))

(defn slug->title [slug]
  (str/replace slug "_" " "))

(defn next-rev-str [slug]
  (str slug "_" (.getTime (java.util.Date.)) "_" (java.util.UUID/randomUUID)))

(defn with-article-links [ctx attr-map]
  (assoc attr-map
         :article/title (slug->title (:article/slug attr-map))
         :wiki/links
         {:article/show (yada/url-for ctx ::article
                                      {:route-params
                                       {:slug (:article/slug attr-map)}})
          :article/edit (yada/url-for ctx ::article-edit
                                      {:route-params
                                       {:slug (:article/slug attr-map)}})
          :article/history (yada/url-for ctx ::article-history
                                      {:route-params
                                       {:slug (:article/slug attr-map)}})}))

(defn async->manifold [c]
  ;; core async into manifold
  (let [d (d/deferred)]
    (async/take! c
                 (fn [item]
                   (d/success! d item)))
    d))

(defn async-log-remove-error [c]
  (async/pipe c
              (async/chan 1
                          (halt-when
                           (fn [i]
                             (when (client/error? i)
                               (info "Error in datomic result: " i)
                               true))))))

(defn md-escape-html [text state]
  ;; https://github.com/yogthos/markdown-clj/issues/36
  [(clojure.string/escape text
                           {\& "&amp;"
                            \< "&lt;"
                            \> "&gt;"
                            \" "&quot;"
                            \' "&#39;"})
   state])

(defn md->html [md-str]
  (md/md-to-html-string md-str
                        :replacement-transformers
                        (cons md-escape-html mdt/transformer-vector)))

(defn get-articles [ctx]
  (d/chain
   (let [conn (:conn ctx)
         db (client/db conn)]
     (-> (client/q conn {:query
                         '{:find [(pull ?e [:*])]
                           :where [[?e :article/slug]]}
                         :limit -1
                         :args [db]})
         async-log-remove-error
         (async/pipe (async/chan 1 (comp
                                    cat ;; q results are "paged"/chuncked
                                    (map first) ;; unwrap datomic result tuples from [{..pull map..}]
                                    )))
         (->> (async/into []))
         async->manifold))
   (fn [articles]
     (map
      (partial with-article-links ctx)
      articles))))

(defn get-article-by-slug [ctx slug]
  (d/chain
   (let [conn (:conn ctx)
         db (client/db conn)]
     (-> (client/q conn {:query
                         '{:find [(pull ?e [:*])]
                           :in [$ ?slug]
                           :where [[?e :article/slug ?slug]]}
                         :args [db slug]})
         async-log-remove-error
         (async/pipe (async/chan 1 (comp
                                    cat ;; q results are "paged"/chuncked
                                    (map first) ;; unwrap datomic result tuples from [{..pull map..} rev]
                                    )))
         async->manifold))
   (fn [article]
     (when article
       (with-article-links ctx article)))))

(defn get-article-history [ctx slug]
  (d/chain
   (let [conn (:conn ctx)
         db (client/db conn)
         db-hist (client/history db)]
     (-> (client/q conn
                   {:query
                    '{:find [?tx
                             ?rev
                             ?tx-instant
                             ?tx-doc]
                      :in [$ $hist ?slug]
                      :where [[$ ?e :article/slug ?slug]
                              [$hist ?e :article/rev ?rev ?tx true]
                              [$ ?tx :db/txInstant ?tx-instant]
                              [$ ?tx :db/doc ?tx-doc]]}
                    :limit -1
                    :args [db db-hist slug]})
         async-log-remove-error
         (async/pipe (async/chan 1
                                 (comp
                                  cat ;; q results are "paged"/chuncked
                                  (map (fn [[tx rev tx-instant tx-doc txep]]
                                         {:tx tx
                                          :rev rev
                                          :tx-instant tx-instant
                                          :comment tx-doc
                                          :link (yada/url-for ctx
                                                              ::article-rev
                                                              {:route-params
                                                               {:slug slug
                                                                :rev rev}})})))))
         (->> (async/into []))
         async->manifold))
   (fn [history]
     (when (seq history)
       (sort-by :tx > history)))))

(defn get-article-revision [ctx rev]
  (let [conn (:conn ctx)
        db (client/db conn)
        db-hist (client/history db)]
    (-> (client/q conn {:query
                        '{:find [?rev ?comment]
                          :in [$hist ?rev]
                          :where [[$hist ?e :article/rev ?rev ?tx-rev true]
                                  [$hist ?tx-rev :db/doc ?comment ?tx-rev true]]}
                        :args [db-hist rev]})
        async-log-remove-error
        (async/pipe (async/chan 1 (map
                                   (comp
                                    (fn [[rev comment]]
                                      {:rev rev
                                       :comment comment})
                                    first))))
        async->manifold)))

(defn get-article-revision-relative [ctx rev query]
  (let [conn (:conn ctx)
        db (client/db conn)
        db-hist (client/history db)]
    (-> (client/q conn {:query query
                        :args [db-hist rev]})
        async-log-remove-error
        (async/pipe (async/chan 1 (comp
                                   (map first)
                                   (halt-when nil?)
                                   (map
                                    (fn [[tx-rel rev-rel doc-rel :as res]]
                                      (when res
                                        {:tx tx-rel
                                         :rev rev-rel
                                         :comment doc-rel}))))))
        async->manifold)))

(defn get-article-revision-before [ctx rev]
  (get-article-revision-relative
   ctx rev
   '{:find [?tx-rel ?rev-rel ?doc-rel]
    :in [$hist ?rev]
    :where [[$hist ?e :article/rev ?rev ?tx-rev true]
            [$hist ?e :article/rev ?rev-rel ?tx-rev false]
            [$hist ?e :article/rev ?rev-rel ?tx-rel true]
            [(< ?tx-rel ?tx-rev)]
            [$hist ?tx-rel :db/doc ?doc-rel ?tx-rel true]]}))

(defn get-article-revision-after [ctx rev]
  (get-article-revision-relative
   ctx rev
   '{:find [?tx-rel ?rev-rel ?doc-rel]
     :in [$hist ?rev]
     :where [[$hist ?e :article/rev ?rev ?tx-rev true]
             [$hist ?e :article/rev ?rev ?tx-rel false]
             [$hist ?e :article/rev ?rev-rel ?tx-rel true]
             [(< ?tx-rev ?tx-rel)]
             [$hist ?tx-rel :db/doc ?doc-rel ?tx-rel true]]}
   ))

(defn update-article [ctx slug comment base-rev next-rev]
  (let [conn (:conn ctx)
        db (client/db conn)]
    (-> (client/transact conn
                         {:tx-data [[:db.fn/cas [:article/slug slug] :article/rev base-rev next-rev]
                                    [:db/add "datomic.tx" :db/doc comment]]})

        async-log-remove-error
        async->manifold
        )))

(defn create-article [ctx slug title comment next-rev]
  (let [conn (:conn ctx)
        db (client/db conn)]
    (-> (client/transact conn
                         {:tx-data [{:db/id "tempid"
                                     :article/slug slug
                                     :article/title title
                                     :article/rev next-rev}
                                    [:db/add "datomic.tx" :db/doc comment]]})
        async-log-remove-error
        async->manifold
        )))

(defn upload-revision [ctx rev text]
  (let [upload-blob-uri (java.net.URI. (str (:fs2-host ctx) "/upload/" rev))]
    (-> (http/post (str upload-blob-uri)
                   {:headers {"Content-Type" "application/octet-stream"}
                    :body text})
        (d/catch (fn [x]
                   (info "Upload error" x)
                   x)))))


(defn body-for-article [ctx article]
  (-> (d/chain (let [url (str (:fs2-host ctx) "/" (:article/rev article))]
                 (http/get url
                           {:headers {"Accept" "*/*"}}))
               (fn [resp]
                 (when (= (:status resp) 200)
                   (let [body (-> resp
                                  :body
                                  bs/to-string)]
                     (assoc article :article/body body)))))
      (d/catch (fn [err]
                 article))))

(defn redirect [ctx url]
  (assoc (:response ctx)
         :status 303
         :headers {"location" url}))

(def index
  (yada/resource
   {:id ::wiki-index
    :properties (fn [ctx]
                  (d/chain (get-articles ctx)
                           (fn [articles]
                             {:exists? true
                              ::articles articles})))
    :methods
    {:get
     {:produces
      {:media-type "text/html"}
      :response
      (fn [ctx]
        (let [articles (-> ctx :properties ::articles)]
          (selmer/render-file
           "wiki/index.html"
           {:index (yada/url-for ctx ::wiki-index)
            :articles articles})))}}}))

(def article
  (yada/resource
   {:id ::article
    :produces "text/html"
    :parameters {:path {:slug String}}
    :properties (fn [ctx]
                  (let [slug (get-in ctx [:parameters :path :slug])]
                    (d/chain (get-article-by-slug ctx slug)
                             (fn [article]
                               (when article
                                 (body-for-article ctx article)))
                             (fn [article]
                               {:exists? true
                                ::slug slug
                                ::article article}))))
    :methods
    {:get {:response
           (fn [ctx]
             (if-let [article (get-in ctx [:properties ::article])]
               (selmer/render-file
                "wiki/show.html"
                {:index (yada/url-for ctx ::wiki-index)
                 :title (:article/title article)
                 :article (assoc article :article/md-body
                                 (md->html (:article/body article)))})
               ;; article not found
               (redirect ctx (yada/url-for ctx ::article-edit
                                           {:route-params
                                            {:slug (get-in ctx [:properties ::slug])}}))))}
     :post {:parameters {:form {:text String
                                :comment String
                                (s/optional-key :rev) String}}
            :consumes #{"application/x-www-form-urlencoded"}
            :response (fn [ctx]
                        (let [slug (get-in ctx [:parameters :path :slug])
                              comment (get-in ctx [:parameters :form :comment] "")
                              next-text (-> (get-in ctx [:parameters :form :text] "")
                                            (str/replace "\r\n" "\n"))
                              base-rev (get-in ctx [:parameters :form :rev])]
                          (if (not (and slug next-text))
                            (throw (ex-info "" {:status 400
                                                :error (str next-text)}))
                            (let [next-rev (next-rev-str slug)]
                              (d/let-flow [upload (upload-revision ctx next-rev next-text)
                                           success? (if-not base-rev
                                                      (let [title (slug->title slug)]
                                                        (create-article ctx slug title comment next-rev))
                                                      (update-article ctx slug comment base-rev next-rev))]
                                (if success?
                                  (redirect ctx
                                            (java.net.URI. ;; redirect
                                             (yada/url-for ctx ::article-rev
                                                           {:route-params {:slug slug
                                                                           :rev next-rev}})))

                                  ;; resolve conflict screen
                                  (d/let-flow [article (d/chain (get-article-by-slug ctx slug)
                                                                (fn [article]
                                                                  (body-for-article ctx article)))]
                                    (selmer/render-file
                                     "wiki/conflict.html"
                                     {:index (yada/url-for ctx ::wiki-index)
                                      :title (:article/title article)
                                      :article article
                                      :comment comment
                                      :next-text next-text
                                      :diff-trs (let [diffs (diff/diffs (:article/body article)
                                                                        next-text)]
                                                  (-> diffs
                                                      diff/diff-blocks
                                                      diff/diff-trs-str))})))))))
                        )}}
    :responses {400 {:produces #{"text/html"}
                     :response (fn [ctx]
                                 (str "Something is wrong, here is the text you submitted: \n" (:error (ex-data (:error ctx)))))}
                }}))

(def edit
  (yada/resource
   {:id ::article-edit
    :parameters {:path {:slug String}}
    :produces "text/html"
    :properties (fn [ctx]
                  (let [slug (get-in ctx [:parameters :path :slug])]
                    (d/chain (get-article-by-slug ctx slug)
                             (fn [article]
                               (when article
                                 (body-for-article ctx article)))
                             (fn [article]
                               {:exists? true
                                ::slug slug
                                ::article article}))))
    :methods
    {:get {:response
           (fn [ctx]
             (let [article (::article (:properties ctx))]
               (if article
                 ;; edit existing
                 (selmer/render-file
                  "wiki/edit.html"
                  {:index (yada/url-for ctx ::wiki-index)
                   :title (:article/title article)
                   :article article})

                 ;; creating new article
                 (let [slug (get-in ctx [:properties ::slug])
                       title (slug->title slug)]
                   (selmer/render-file
                    "wiki/create.html"
                    {:index (yada/url-for ctx ::wiki-index)
                     :title title
                     :action (yada/url-for ctx ::article
                                           {:route-params
                                            {:slug slug}})}))
                 )))}}}))

(def history
  (yada/resource
   {:id ::article-history
    :parameters {:path {:slug String}}
    :properties (fn [ctx]
                  (let [slug (get-in ctx [:parameters :path :slug])]
                    (d/let-flow [article (get-article-by-slug ctx slug)
                                 history (get-article-history ctx slug)]
                      (if (and article history)
                        {:exists? true
                         ::article article
                         ::history history}
                        {:exists? false}))))
    :methods
    {:get {:produces "text/html"
           :response (fn [ctx]
                       (let [article (::article (:properties ctx))
                             history (::history (:properties ctx))]
                         (selmer/render-file
                          "wiki/history.html"
                          {:index (yada/url-for ctx ::wiki-index)
                           :title (str (:article/title article) " history")
                           :article article
                           :history history})))}}}))

(def revision
  (yada/resource
   {:id ::article-rev
    :parameters {:path {:slug String
                        :rev String}}
    :produces #{"text/html" "diff/patch"}
    :properties (fn [ctx]
                  (let [slug (get-in ctx [:parameters :path :slug])
                        revision (get-in ctx [:parameters :path :rev])]
                    (d/let-flow [article (get-article-by-slug ctx slug)
                                 rev (get-article-revision ctx revision)
                                 prev (get-article-revision-before ctx revision)
                                 nrev (get-article-revision-after ctx revision)
                                 rev-body (body-for-article ctx {:article/rev (:rev rev)})
                                 prev-body (when prev
                                             (body-for-article ctx {:article/rev (:rev prev)}))]
                      (if (and article
                               (:rev rev))
                        (cond->
                            {:exists? true
                             ::slug slug
                             ::article article
                             ::rev-body rev-body
                             ::comment (:comment rev)}
                            (:rev prev)
                            (assoc ::prev
                                   (yada/url-for ctx ::article-rev
                                                 {:route-params
                                                  {:slug slug
                                                   :rev (:rev prev)}}))
                            (:rev nrev)
                            (assoc ::nrev
                                   (yada/url-for ctx ::article-rev
                                                 {:route-params
                                                  {:slug slug
                                                   :rev (:rev nrev)}}))
                            prev-body
                            (assoc ::diff (diff/diffs (:article/body prev-body)
                                                      (:article/body rev-body)))
                            (= (get-in ctx [:request :headers "accept"]) "diff/patch")
                            (assoc ::patch (diff/patch (:article/body prev-body)
                                                       (:article/body rev-body))))
                        {:exists? false}))))
    :methods
    {:get {:response
           (fn [ctx]
             (let [slug (::slug (:properties ctx))
                   article (::article (:properties ctx))]
               (if-let [patch (-> ctx :properties ::patch)]
                 patch
                 (selmer/render-file
                  "wiki/revision.html"
                  {:index (yada/url-for ctx ::wiki-index)
                   :article (assoc article :article/md-body
                                   (md->html (:article/body article)))
                   :nav (let [props (:properties ctx)]
                          {:prev (::prev props)
                           :nrev (::nrev props)})
                   :rev-body (md->html (-> ctx :properties ::rev-body :article/body))
                   :comment (-> ctx :properties ::comment)
                   :diff-trs (when-let [diffs (-> ctx :properties ::diff)]
                               (-> diffs
                                   diff/diff-blocks
                                   diff/diff-trs-str))}))))}}}))

(defn wiki-routes []
  ["/wiki"
   [[""                       index]
    [["/" :slug]              article]
    [["/" :slug "/edit"]      edit]
    [["/" :slug "/history"]   history]
    [["/" :slug "/rev/" :rev] revision]]])

