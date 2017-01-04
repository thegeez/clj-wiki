(ns net.thegeez.wiki.diff
  (:require [clojure.string :as str])
  (:import (org.bitbucket.cowwoc.diffmatchpatch DiffMatchPatch
                                                DiffMatchPatch$Operation)))

;; google-diff-match-patch
(defn diffs [left right]
  (let [left (or left "")
        right (or right "")
        dmp (DiffMatchPatch.)
        diffs (. dmp (diffMain left right))
        _ (. dmp (diffCleanupSemantic diffs))]
    (for [diff diffs]
      [(condp = (.operation diff)
         DiffMatchPatch$Operation/INSERT
         :insert
         DiffMatchPatch$Operation/EQUAL
         :equal
         DiffMatchPatch$Operation/DELETE
         :delete)
       (.text diff)])))

(defn patch [left right]
  (let [left (or left "")
        right (or right "")
        left (str/replace left "\r\n" "\n")
        right (str/replace right "\r\n" "\n")
        dmp (DiffMatchPatch.)
        diffs (. dmp (diffMain left right))
        _ (. dmp (diffCleanupSemantic diffs))
        patch (. dmp (patchMake diffs))]
    (str/join "\n" patch)))

(defn html-diff [left right]
  (let [dmp (DiffMatchPatch.)
        diffs (. dmp (diffMain left right))
        _ (. dmp (diffCleanupSemantic diffs))
        html-diffs (. dmp (diffPrettyHtml diffs))]
    html-diffs))


(defn diff-lines-to-chars [dmp left right]
  (let [m (some (fn [x]
                  (when (.. x getName (equals "diffLinesToChars"))
                    x))
                (.. dmp getClass getDeclaredMethods))]
    (. m (setAccessible true))
    (. m (invoke dmp (object-array [left right])))))

(defn diff-chars-to-lines [dmp diff line-array]
  (let [m (some (fn [x]
                  (when (.. x getName (equals "diffCharsToLines"))
                    x))
                (.. dmp getClass getDeclaredMethods))]
    (. m (setAccessible true))
    (. m (invoke dmp (object-array [diff line-array])))))

(defn line-diff [left right]
  ;; I don't know why all the reflection is required...
  (let [dmp (DiffMatchPatch.)
        l (diff-lines-to-chars dmp left right)

        [c1 c2 la] (for [field-name ["chars1" "chars2" "lineArray"]]
                         (let [f (.. l getClass (getDeclaredField field-name))]
                           (. f (setAccessible true))
                           (. f (get l))))
        diffs (. dmp (diffMain c1 c2 false))
        _ (diff-chars-to-lines dmp diffs la)
        _ (. dmp (diffCleanupSemantic diffs))
        patch (. dmp (patchMake diffs))]
    patch))

(defn apply-patch [text patch-str]
  (let [dmp (DiffMatchPatch.)
        patch (. dmp (patchFromText patch-str))]
    (aget (. dmp (patchApply patch text)) 0)))

(defn split-newlines [s]
  ;; return [line :newline line :newline]
  (cond-> (into []
                (interpose :newline)
                (str/split-lines s))
    (str/ends-with? s "\n")
    (conj :newline)))

(defn upto-newline [xs]
  ;; post is everything after last newline, pre everything before
  (loop [xs xs
         post '()]
    (let [x (peek xs)]
      (if-not x
        [[] (vec post)]
        (if (= x :newline)
          [xs (vec post)]
          (recur (pop xs)
                 (conj post x)))))))

(defn diff-blocks [diffs]
  ;; put  diffs into only inserts and equal on the left, and only deletes and equals on the right
  ;; prefer to end a block on a new line, this turns inserts and deletes into line diff from their char diffs
  (let [res (rest
             (reduce
              (fn [blocks [op txt]]
                (case op
                  :equal
                  (let [[pl pr :as prev-block] (peek blocks)]
                    (if (= :newline (peek pl) (peek pr))
                      (let [lines (split-newlines txt)]
                        (conj blocks [lines lines]))
                      (let [[line nl & lines] (split-newlines txt)
                            pad (cond
                                  (and line nl) [line nl]
                                  line [line]
                                  :else [])]
                        (-> (pop blocks)
                            (conj [(into pl pad) (into pr pad)])
                            (cond->
                                (seq lines)
                              (conj [(vec lines) (vec lines)]))))))
                  :insert
                  (let [[pl pr :as prev-block] (peek blocks)
                        lines (split-newlines txt)
                        lines (-> [:ins-start]
                                  (into (replace {:newline :ins-newline})lines)
                                  (conj :ins-end))]
                    (if (= :newline (peek pl) (peek pr))
                      (conj blocks [lines nil])
                      (let [[pl-pre pl-post] (upto-newline pl)
                            [pr-pre pr-post] (upto-newline pr)]
                        (-> (pop blocks)
                            (cond->
                                (or (peek pl-pre) (peek pr-pre))
                              (conj [pl-pre pr-pre]))
                            (conj [(into pl-post lines)
                                   pr-post])))))
                  :delete
                  (let [[pl pr :as prev-block] (peek blocks)
                        lines (split-newlines txt)
                        lines (-> [:del-start]
                                  (into (replace {:newline :del-newline}) lines)
                                  (conj :del-end))]
                    (if (= :newline (peek pl) (peek pr))
                      (conj blocks [nil lines])
                      (let [[pl-pre pl-post] (upto-newline pl)
                            [pr-pre pr-post] (upto-newline pr)]
                        (-> (pop blocks)
                            (cond->
                                (or (peek pl-pre) (peek pr-pre))
                              (conj [pl-pre pr-pre]))
                            (conj [pl-post
                                   (into pr-post lines)])))))))
              [[[:newline] [:newline]]]
              diffs))]
    res))

(defn diff-trs-str [blocks]
  (let [to-cell (fn [cell]
                  (when-let [cell (seq cell)]
                    (let [sb (StringBuilder.)]
                      (doseq [s cell]
                        (case s
                          :ins-start
                          (.append sb "<ins>")
                          :ins-end
                          (.append sb "</ins>")
                          :del-start
                          (.append sb "<del>")
                          :del-end
                          (.append sb "</del>")
                          (:newline :ins-newline :del-newline)
                          (.append sb "&para;<br/>")
                          (doseq [c s]
                            (case c
                              \< (.append sb "&lt;")
                              \> (.append sb "&gt;")
                              \" (.append sb "&quot;")
                              \' (.append sb "&#39;")
                              \& (.append sb "&amp;")
                              (.append sb c)))))
                      (str sb))))]
    (map
     (fn [[l r]]
       {:left (to-cell l)
        :right (to-cell r)})
     blocks)))

