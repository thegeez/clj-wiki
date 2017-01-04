(ns net.thegeez.wiki.fixtures
  (:require [clojure.tools.logging :as log]
            [datomic.client :as client]
            [clojure.core.async :as async]
            [com.stuartsierra.component :as component]
            [aleph.http :as http]
            [net.thegeez.wiki.wiki :as wiki]
            [net.thegeez.wiki.diff :as diff]))

(def fixtures [{:slug "About_this_wiki"
                :comment "Hello world"
                :text "This is a wiki build with Clojure"
                :diffs
                [{:comment "Markdown"
                  :patch "@@ -12,13 +12,12 @@
 iki 
-build
+made
  wit

@@ -25,8 +25,43 @@
  Clojure
+%0A%0AIt supports **Markdown** *markup*"}
                 {:comment "Libraries"
                  :patch "@@ -60,8 +60,145 @@
 *markup*
+%0A%0AThe wiki is made with the following libraries:%0A* %5BYada%5D(/wiki/Yada) web library%0A* %5BDatomic Client%5D(/wiki/Datomic_Client) database%0A* Tea"}
                 {:comment "Tea is not a library"
                  :patch "@@ -91,25 +91,20 @@
  the
- following librar
+se technolog
 ies:

@@ -190,10 +190,4 @@
 base
-%0A* Tea
"}
                 {:comment "Awesome helper libraries"
                  :patch "@@ -186,8 +186,213 @@
 database
+%0A%0AWith some help from:%0A* %5BSimpleMDE%5D(https://simplemde.com/) SimpleMDE Markdown Editor%0A* %5Bgoogle-diff-patch-match%5D(https://bitbucket.org/cowwoc/google-diff-match-patch/wiki/Home) for edit diffs and patches"}
                 {:comment "Conflict feature"
                  :patch "@@ -391,8 +391,99 @@
  patches
+%0A%0AEdit conflicts lead to a conflict resolution page where the latest differences are shown.
"}
                 {:comment "History feature"
                  :patch "@@ -482,8 +482,134 @@
 e shown.
+%0A%0ABrowsing through an article's %5Bhistory%5D(About_thiswiki/history) will show the differences between the versions through time.
"}
                 {:comment "Fix history url"
                  :patch "@@ -524,16 +524,22 @@
 istory%5D(
+/wiki/
 About_th

@@ -540,16 +540,17 @@
 out_this
+_
 wiki/his
"}]}

               {:slug "Demo_Article"
                :comment "First article"
                :text "# Intro
Go ahead, play around with the editor! Be sure to check out **bold** and *italic* styling, or even [links](https://google.com). You can type the Markdown syntax, use the toolbar, or use shortcuts like `cmd-b` or `ctrl-b`.

## Lists
Unordered lists can be started using the toolbar or by typing `* `, `- `, or `+ `. Ordered lists can be started by typing `1. `.

#### Unordered
* Lists are a piece of cake
* They even auto continue as you type
* A double enter will end them
* Tabs and shift-tabs work too

#### Ordered
1. Numbered lists...
2. ...work too!

## What about images?
![Yes](https://i.imgur.com/sZlktY7.png)"
                :diffs
                [{:comment "Add item to list"
                  :patch "@@ -505,16 +505,42 @@
 work too
+%0A* With an extra list item
 %0A%0A#### O"}
                 {:comment "Cake"
                :patch "@@ -394,23 +394,42 @@
 are 
-a piece of cake
+easy to add, as cake one might say
 %0A* T"}
               {:comment "Insert & Delete"
                :patch "@@ -9,16 +9,29 @@
 Go ahead
+ and have fun
 , play a

@@ -412,37 +412,15 @@
 asy 
-to add, as cake one might say
+as cake
 %0A* T
"}]}
               
               {:slug "Yada"
                :comment "Yada"
                :text "[Yada](https://github.com/juxt/yada) is a powerful Clojure web library made by [Juxt](https://juxt.pro)

This wiki is based on the [Edge](https://github.com/juxt/edge) example application, also made by Juxt.

Yada is currently at version 1.1.45"
                :diffs [{:comment "Version bump"
                         :patch "@@ -237,8 +237,7 @@
 n 1.
-1.45
+2.0
"}]}
               {:slug "Datomic_Client"
                :comment "Datomic Client"
                :text "[Datomic](http://www.datomic.com/) supports:
* Clojure API
* Java API
* REST API"
                :diffs
                [{:comment "Add Client API"
                  :patch "@@ -69,12 +69,109 @@
 I%0A* REST API
+%0A* %5BClojure Client API%5D(http://blog.datomic.com/2016/11/datomic-update-client-api-unlimited.html)
"}]}])

(defn load-fixtures [ctx]
  (doseq [f fixtures]
    (let [{:keys [slug comment text]} f
          title (wiki/slug->title slug)
          rev (wiki/next-rev-str slug)]
      (when @(wiki/create-article ctx slug title comment rev)
        @(wiki/upload-revision ctx rev text)
        (reduce
         (fn [[text rev] {:keys [comment patch]}]
           (let [next-rev (wiki/next-rev-str slug)
                 text (diff/apply-patch text patch)]
             @(wiki/upload-revision ctx next-rev text)
             @(wiki/update-article ctx slug comment rev next-rev)
             [text next-rev]))
         [text rev]
         (:diffs f))))))

(defrecord Fixtures [datomic
                     fs2]
  component/Lifecycle
  (start [component]
    (let [ctx {:fs2-host (:url fs2)
               :conn ((:get-conn datomic))}]
      (load-fixtures ctx)
      component))

  (stop [component]
    component))

(defn component []
  (component/using
   (map->Fixtures {})
   [:datomic :fs2 :migrator]))
