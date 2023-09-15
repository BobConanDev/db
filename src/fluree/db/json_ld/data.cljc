(ns fluree.db.json-ld.data
  (:require
   [fluree.json-ld :as json-ld]
   [fluree.db.json-ld.reify :as jld-reify]
   [fluree.db.flake :as flake]
   [fluree.db.constants :as const]
   [fluree.db.util.async :refer [<? go-try]]
   [fluree.db.util.core :as util :refer [try* catch*]]
   [clojure.core.async :as async]
   [fluree.db.util.log :as log]
   [fluree.db.datatype :as datatype]
   [fluree.json-ld.processor.api :as jld-processor]
   [fluree.db.json-ld.shacl :as shacl]))

(defn create-id-flake
  [sid iri t]
  (flake/create sid const/$xsd:anyURI iri const/$xsd:string t true nil))

(defn lookup-iri
  [{:keys [db-before iri-cache flakes] :as tx-state} iri]
  (go-try
    (or (<? (jld-reify/get-iri-sid iri db-before iri-cache))
        (some->> flakes
                 (filter (fn [f]
                           (and (flake/op f)
                                (= const/$xsd:anyURI (flake/p f))
                                (= iri (flake/o f)))))
                 (first)
                 (flake/s)))))

(defn bnode-id
  [sid]
  (str "_:" sid))

(declare insert-sid)
(defn insert-flake
  [sid pid m {:keys [db-before iri-cache next-sid t] :as tx-state}
   {:keys [value id type language list] :as v-map}]
  (go-try
    (cond list
          (loop [[[i list-item :as item] & r] (map vector (range) list)
                 tx-state                     tx-state]
            (if item
              (recur r (<? (insert-flake sid pid {:i i} tx-state list-item)))
              tx-state))

          ;; literal
          (some? value)
          (let [existing-dt  (when type (<? (lookup-iri tx-state type)))
                dt           (cond existing-dt existing-dt
                                   type        (next-sid)
                                   :else       (datatype/infer value))
                m*            (cond-> m
                                language (assoc :lang language))
                new-dt-flake (when (and type (not existing-dt)) (create-id-flake dt type t))
                new-flake    (flake/create sid pid value dt t true m*)]
            (-> tx-state
                (update :flakes into (remove nil?) [new-dt-flake new-flake])))

          ;; ref
          :else
          (let [bnode-sid (when-not id (next-sid))
                bnode-iri (when-not id (bnode-id bnode-sid))

                v-map*    (cond-> v-map
                            bnode-iri (assoc :id bnode-iri))

                tx-state  (cond-> tx-state
                            bnode-iri (update :flakes conj (create-id-flake bnode-sid bnode-iri t)))

                tx-state* (<? (insert-sid tx-state v-map*))

                ref-sid   (if id
                            ;; sid was generated/found by `insert-sid`
                            (<? (lookup-iri tx-state* id))
                            bnode-sid)
                ref-flake (flake/create sid pid ref-sid const/$xsd:anyURI t true m)]
            (-> tx-state*
                (update :flakes conj ref-flake))))))

(defn insert-pid
  [sid {:keys [db-before iri-cache next-pid t shapes] :as tx-state} [predicate values]]
  (go-try
    (let [existing-pid        (<? (lookup-iri tx-state predicate))
          pid                 (if existing-pid existing-pid (next-pid))]
      (loop [[v-map & r] values
             tx-state    (cond-> tx-state
                           (not existing-pid) (update :flakes conj (create-id-flake pid predicate t)))]
        (if v-map
          (recur r (<? (insert-flake sid pid nil tx-state v-map)))
          tx-state)))))

(defn insert-sid
  [{:keys [db-before iri-cache next-sid t] :as tx-state} {:keys [id] :as subject}]
  (go-try
    (let [existing-sid     (when id (<? (lookup-iri tx-state id)))
          target-node-sids (when existing-sid
                             (<? (shacl/shape-target-sids db-before const/$sh:targetNode existing-sid)))
          [sid iri]        (if (nil? id)
                             (let [bnode-sid (next-sid)]
                               [bnode-sid (bnode-id bnode-sid)])
                             ;; TODO: not handling pid generation
                             [(or existing-sid (next-sid)) id])]
      (loop [[entry & r] (dissoc subject :id :idx)
             tx-state    (cond-> (update-in tx-state [:shapes :node] into target-node-sids)
                           (not existing-sid) (update :flakes conj (create-id-flake sid iri t)))]
        (if entry
          (recur r (<? (insert-pid sid tx-state entry)))
          tx-state)))))

(defn insert-flakes
  [{:keys [default-ctx] :as tx-state} data]
  (go-try
    (loop [[subject & r] (when data (util/sequential (json-ld/expand data default-ctx)))
           tx-state tx-state]
      (if subject
        (recur r (<? (insert-sid tx-state subject)))
        tx-state))))
