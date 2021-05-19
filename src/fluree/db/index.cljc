(ns fluree.db.index
  (:require [clojure.data.avl :as avl]
            [fluree.db.dbproto :as dbproto]
            [fluree.db.flake :as flake]
            #?(:clj  [clojure.core.async :refer [go <!] :as async]
               :cljs [cljs.core.async :refer [go <!] :as async])
            [fluree.db.util.async :refer [<? go-try]]
            [fluree.db.util.log :as log]))

(def default-comparators
  "Map of default index comparators for the five index types"
  {:spot flake/cmp-flakes-spot-novelty
   :psot flake/cmp-flakes-psot-novelty
   :post flake/cmp-flakes-post-novelty
   :opst flake/cmp-flakes-opst-novelty
   :tspo flake/cmp-flakes-block})

(def types
  "The five possible index orderings based on the subject, predicate, object,
  and transaction flake attributes"
  (-> default-comparators keys set))

(defn node?
  [x]
  (not (-> x :leaf nil?)))

(defn leaf?
  [node]
  (-> node :leaf true?))

(defn branch?
  [node]
  (-> node :leaf false?))

(defn resolved?
  [node]
  (cond
    (leaf? node)   (not (nil? (:flakes node)))
    (branch? node) (not (nil? (:children node)))))

(defn lookup
  [branch flake]
  (when (and (branch? branch)
             (resolved? branch))
    (let [{:keys [children]} branch]
      (-> children
          (avl/nearest <= flake)
          (or (first children))
          val))))

(defn lookup-after
  [branch flake]
  (when (and (branch? branch)
             (resolved? branch))
    (let [{:keys [children]} branch]
      (-> children
          (avl/nearest > flake)
          (or (last children))
          val))))

(defn lookup-leaf
  [branch flake]
  (go-try
   (when (and (branch? branch)
              (resolved? branch))
     (loop [child (lookup branch flake)]
       (if (leaf? child)
         child
         (recur (<? (resolve child))))))))

(defn lookup-leaf-after
  [branch flake]
  (go-try
   (when (and (branch? branch)
              (resolved? branch))
     (loop [child (lookup-after branch flake)]
       (if (leaf? child)
         child
         (recur (<? (resolve child)))))
     (ex-info (str "lookup-leaf is only supported on resolved branch nodes.")
              {:status 500, :error :db/unexpected-error,
               ::branch branch}))))

(defn empty-leaf
  [network dbid cmp]
  {:comparator cmp
   :network network
   :dbid dbid
   :id :empty
   :leaf true
   :floor flake/maximum
   :ciel nil
   :size 0
   :block 0
   :t 0
   :leftmost? true})

(defn child-entry
  [{:keys [floor] :as node}]
  [floor node])

(defn child-map
  [cmp & child-nodes]
  (->> child-nodes
       (mapcat child-entry)
       (apply avl/sorted-map-by cmp)))

(defn empty-branch
  [conn network dbid cmp]
  (let [child-node (empty-leaf network dbid cmp)
        children   (child-map cmp child-node)]
    {:comparator cmp
     :network network
     :dbid dbid
     :id :empty
     :leaf false
     :floor flake/maximum
     :ciel nil
     :children children
     :size 0
     :block 0
     :t 0
     :leftmost? true}))

(defn after-t?
  [t flake]
  (-> flake flake/t (< t)))

(defn filter-after
  [t flakes]
  (filter (partial after-t? t) flakes))

(defn flakes-through
  [t flakes]
  (->> flakes
       (filter-after t)
       (flake/disj-all flakes)))

(defn remove-latest
  [[floor & other-flakes]]
  (last (reduce (fn [[latest rest] flake]
                  (if (pos? (flake/cmp-tx (flake/t latest) (flake/t flake)))
                    [latest (conj rest flake)]
                    [flake (conj rest latest)]))
                [floor #{}]
                other-flakes)))

(defn flakes-before
  [t flakes]
  (->> flakes
       (group-by (fn [f]
                   [flake/s flake/p flake/o]))
       vals
       (mapcat #(->> %
                     (filter (complement (partial after-t? t)))
                     remove-latest))))

(defn tx-range
  [from-t to-t flakes]
  (let [out-of-range (concat (flakes-before from-t flakes)
                             (filter-after to-t flakes))]
    (flake/disj-all flakes out-of-range)))

(defn as-of
  [t flakes]
  (tx-range t t flakes))

(defn novelty-subrange
  [{:keys [floor ciel leftmost?] :as node} through-t novelty]
  (let [subrange (cond
                   ;; standard case.. both left and right boundaries
                   (and ciel (not leftmost?))
                   (avl/subrange novelty > floor <= ciel)

                   ;; right only boundary
                   (and ciel leftmost?)
                   (avl/subrange novelty <= ciel)

                   ;; left only boundary
                   (and (nil? ciel) (not leftmost?))
                   (avl/subrange novelty > floor)

                   ;; no boundary
                   (and (nil? ciel) leftmost?)
                   novelty)
        after-t  (filter-after through-t subrange)]
    (flake/disj-all subrange after-t)))

(defn novelty-flakes-before
  [{:keys [floor ciel leftmost? flakes], node-t :t, :as node} t idx-novelty remove-preds]
  (let [subrange-through-t (novelty-subrange node t idx-novelty)]
    (filter (fn [f]
              (and (true? (flake/op f))
                   (not (contains? remove-preds (flake/p f)))))
            subrange-through-t)))

(defn value-at-t
  "Find the value of `leaf` at transaction `t` by adding new flakes from
  `idx-novelty` to `leaf` if `t` is newer than `leaf`, or removing flakes later
  than `t` from `leaf` if `t` is older than `leaf`."
  [{:keys [ciel leftmost? flakes], leaf-t :t, :as leaf} t idx-novelty remove-preds]
  (if (= leaf-t t)
    leaf
    (cond-> leaf
      (> leaf-t t)
      (update :flakes flake/conj-all (novelty-flakes-before leaf t idx-novelty remove-preds))

      (< leaf-t t)
      (update :flakes flake/disj-all (filter-after t flakes))

      :finally
      (assoc :t t))))
