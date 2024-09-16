(ns fluree.db.flake.flake-db
  (:refer-clojure :exclude [load vswap!])
  (:require [#?(:clj clojure.pprint, :cljs cljs.pprint) :as pprint :refer [pprint]]
            [clojure.core.async :as async :refer [go]]
            [clojure.set :refer [map-invert]]
            [fluree.db.connection :as connection]
            [fluree.db.datatype :as datatype]
            [fluree.db.dbproto :as dbproto]
            [fluree.db.json-ld.iri :as iri]
            [fluree.db.query.exec.where :as where]
            [fluree.db.time-travel :refer [TimeTravel]]
            [fluree.db.query.history :refer [AuditLog]]
            [fluree.db.flake.history :as history]
            [fluree.db.flake.format :as jld-format]
            [fluree.db.flake.match :as match]
            [fluree.db.constants :as const]
            [fluree.db.reasoner :as reasoner]
            [fluree.db.flake :as flake]
            [fluree.db.flake.reasoner :as flake.reasoner]
            [fluree.db.flake.transact :as flake.transact]
            [fluree.db.util.core :as util :refer [get-first get-first-value
                                                  get-first-id vswap!]]
            [fluree.db.flake.index :as index]
            [fluree.db.indexer :as indexer]
            [fluree.db.flake.index.novelty :as novelty]
            [fluree.db.query.fql :as fql]
            [fluree.db.flake.index.storage :as index-storage]
            [fluree.db.json-ld.commit-data :as commit-data]
            [fluree.db.json-ld.policy :as policy]
            [fluree.db.json-ld.policy.query :as qpolicy]
            [fluree.db.json-ld.policy.rules :as policy-rules]
            [fluree.db.json-ld.reify :as reify]
            [fluree.db.transact :as transact]
            [fluree.db.json-ld.vocab :as vocab]
            [fluree.db.query.exec.select.subject :as subject]
            [fluree.db.query.range :as query-range]
            [fluree.db.serde.json :as serde-json]
            [fluree.db.util.async :refer [<? go-try]]
            [fluree.db.util.log :as log]
            [fluree.json-ld :as json-ld])
  #?(:clj (:import (java.io Writer))))

#?(:clj (set! *warn-on-reflection* true))

(def data-version 0)

;; ================ Jsonld record support fns ================================

(defn empty-all-novelty
  [db]
  (let [cleared (reduce (fn [db* idx]
                          (update-in db* [:novelty idx] empty))
                        db index/types)]
    (assoc-in cleared [:novelty :size] 0)))

(defn empty-novelty
  "Empties novelty @ t value and earlier. If t is null, empties all novelty."
  [db t]
  (cond
    (or (nil? t)
        (= t (:t db)))
    (empty-all-novelty db)

    (flake/t-before? t (:t db))
    (let [cleared (reduce (fn [db* idx]
                            (update-in db* [:novelty idx]
                                       (fn [flakes]
                                         (index/flakes-after t flakes))))
                          db index/types)
          size    (flake/size-bytes (get-in cleared [:novelty :spot]))]
      (assoc-in cleared [:novelty :size] size))

    :else
    (throw (ex-info (str "Request to empty novelty at t value: " t
                         ", however provided db is only at t value: " (:t db))
                    {:status 500 :error :db/indexing}))))

(defn newer-index?
  [commit {data-map :data, :as _commit-index}]
  (if data-map
    (let [commit-index-t (commit-data/index-t commit)
          index-t        (:t data-map)]
      (or (nil? commit-index-t)
          (flake/t-after? index-t commit-index-t)))
    false))

(defn index-update
  "If provided commit-index is newer than db's commit index, updates db by cleaning novelty.
  If it is not newer, returns original db."
  [{:keys [commit] :as db} {data-map :data, :keys [spot post opst tspo] :as commit-index}]
  (if (newer-index? commit commit-index)
    (let [index-t (:t data-map)
          commit* (assoc commit :index commit-index)]
      (-> db
          (empty-novelty index-t)
          (assoc :commit commit*
                 :spot spot
                 :post post
                 :opst opst
                 :tspo tspo)
          (assoc-in [:stats :indexed] index-t)))
    db))

(defn read-db
  [conn db-address]
  (go-try
    (let [file-data (<? (connection/-c-read conn db-address))
          db        (assoc file-data "f:address" db-address)]
      (json-ld/expand db))))

(defn with-namespaces
  [{:keys [namespaces max-namespace-code] :as db} new-namespaces]
  (let [new-ns-map          (into namespaces
                                  (map-indexed (fn [i ns]
                                                 (let [ns-code (+ (inc i)
                                                                  max-namespace-code)]
                                                   [ns ns-code])))
                                  new-namespaces)
        new-ns-codes        (map-invert new-ns-map)
        max-namespace-code* (iri/get-max-namespace-code new-ns-codes)]
    (assoc db
           :namespaces new-ns-map
           :namespace-codes new-ns-codes
           :max-namespace-code max-namespace-code*)))

(defn enrich-values
  [id->node values]
  (mapv (fn [{:keys [id type] :as v-map}]
          (if id
            (merge (get id->node id)
                   (cond-> v-map
                     (nil? type) (dissoc :type)))
            v-map))
        values))

(defn enrich-node
  [id->node node]
  (reduce-kv
   (fn [updated-node k v]
     (assoc updated-node k (cond (= :id k) v
                                 (:list (first v)) [{:list (enrich-values id->node (:list (first v)))}]
                                 :else (enrich-values id->node v))))
   {}
   node))

(defn enrich-assertion-values
  "`asserts` is a json-ld flattened (ish) sequence of nodes. In order to properly generate
  sids (or pids) for these nodes, we need the full node additional context for ref objects. This
  function traverses the asserts and builds a map of node-id->node, then traverses the
  asserts again and merges each ref object into the ref's node.

  example input:
  [{:id \"foo:bar\"
    \"ex:key1\" {:id \"foo:ref-id\"}}
  {:id \"foo:ref-id\"
   :type \"some:type\"}]

  example output:
  [{:id \"foo:bar\"
    \"ex:key1\" {:id \"foo:ref-id\"
                 :type \"some:type\"}}
  {:id \"foo:ref-id\"
   :type \"some:type\"}]
  "
  [asserts]
  (let [id->node (reduce (fn [id->node {:keys [id] :as node}] (assoc id->node id node))
                         {}
                         asserts)]
    (mapv (partial enrich-node id->node)
          asserts)))

(defn db-assert
  [db-data]
  (let [commit-assert (get db-data const/iri-assert)]
    ;; TODO - any basic validation required
    (enrich-assertion-values commit-assert)))

(defn db-retract
  [db-data]
  (let [commit-retract (get db-data const/iri-retract)]
    ;; TODO - any basic validation required
    commit-retract))

(defn commit-error
  [message commit-data]
  (throw
   (ex-info message
            {:status 400, :error :db/invalid-commit, :commit commit-data})))

(defn db-t
  "Returns 't' value from commit data."
  [db-data]
  (let [t (get-first-value db-data const/iri-fluree-t)]
    (when-not (pos-int? t)
      (commit-error
       (str "Invalid, or non existent 't' value inside commit: " t) db-data))
    t))

(defn add-list-meta
  [list-val]
  (let [m {:i (-> list-val :idx last)}]
    (assoc list-val ::meta m)))

(defn list-value?
  "returns true if json-ld value is a list object."
  [v]
  (and (map? v)
       (= :list (-> v first key))))

(defn node?
  "Returns true if a nested value is itself another node in the graph.
  Only need to test maps that have :id - and if they have other properties they
  are defining then we know it is a node and have additional data to include."
  [mapx]
  (cond
    (contains? mapx :value)
    false

    (list-value? mapx)
    false

    (and
     (contains? mapx :set)
     (= #{:set :idx} (set (keys mapx))))
    false

    :else
    true))

(defn value-map->flake
  [assert? db sid pid t v-map]
  (let [ref-id (:id v-map)
        meta   (::meta v-map)]
    (if (and ref-id (node? v-map))
      (let [ref-sid (iri/encode-iri db ref-id)]
        (flake/create sid pid ref-sid const/$id t assert? meta))
      (let [[value dt] (datatype/from-expanded db v-map)]
        (flake/create sid pid value dt t assert? meta)))))


(defn property->flake
  [assert? db sid pid t value]
  (let [v-maps (util/sequential value)]
    (mapcat (fn [v-map]
              (if (list-value? v-map)
                (let [list-vals (:list v-map)]
                  (into []
                        (comp (map add-list-meta)
                              (map (partial value-map->flake assert? db sid pid t)))
                        list-vals))
                [(value-map->flake assert? db sid pid t v-map)]))
            v-maps)))

(defn- get-type-flakes
  [assert? db t sid type]
  (into []
        (map (fn [type-item]
               (let [type-sid (iri/encode-iri db type-item)]
                 (flake/create sid const/$rdf:type type-sid
                               const/$id t assert? nil))))
        type))

(defn node->flakes
  [assert? db t node]
  (log/trace "node->flakes:" node "assert?" assert?)
  (let [{:keys [id type]} node
        sid             (if assert?
                          (iri/encode-iri db id)
                          (or (iri/encode-iri db id)
                              (throw
                               (ex-info
                                "Cannot retract subject IRI with unknown namespace."
                                {:status 400
                                 :error  :db/invalid-retraction
                                 :iri    id}))))
        type-assertions (if (seq type)
                          (get-type-flakes assert? db t sid type)
                          [])]
    (into type-assertions
          (comp (remove #(-> % key keyword?))
                (mapcat
                 (fn [[prop value]]
                   (let [pid (if assert?
                               (iri/encode-iri db prop)
                               (or (iri/encode-iri db prop)
                                   (throw
                                    (ex-info
                                     "Cannot retract property IRI with unknown namespace."
                                     {:status 400
                                      :error  :db/invalid-retraction
                                      :iri    prop}))))]
                     (property->flake assert? db sid pid t value)))))
          node)))

(defn create-flakes
  [assert? db t assertions]
  (into []
        (mapcat (partial node->flakes assert? db t))
        assertions))

(defn merge-flakes
  "Returns updated db with merged flakes."
  [db t flakes]
  (-> db
      (assoc :t t)
      (commit-data/update-novelty flakes)
      (vocab/hydrate-schema flakes)))

(defn merge-commit
  "Process a new commit map, converts commit into flakes, updates respective
  indexes and returns updated db"
  [conn db [commit _proof]]
  (go-try
    (let [db-address         (-> commit
                                 (get-first const/iri-data)
                                 (get-first-value const/iri-address))
          db-data            (<? (read-db conn db-address))
          t-new              (db-t db-data)
          assert             (db-assert db-data)
          nses               (map :value
                                  (get db-data const/iri-namespaces))
          _                  (log/trace "merge-commit new namespaces:" nses)
          _                  (log/trace "db max-namespace-code:"
                                        (:max-namespace-code db))
          db*                (with-namespaces db nses)
          asserted-flakes    (create-flakes true db* t-new assert)
          retract            (db-retract db-data)
          retracted-flakes   (create-flakes false db* t-new retract)

          {:keys [previous issuer message data] :as commit-metadata}
          (commit-data/json-ld->map commit db*)

          commit-id          (:id commit-metadata)
          commit-sid         (iri/encode-iri db* commit-id)
          [prev-commit _] (some->> previous :address (reify/read-commit conn) <?)
          db-sid             (iri/encode-iri db* (:id data))
          metadata-flakes    (commit-data/commit-metadata-flakes commit-metadata
                                                                 t-new commit-sid db-sid)
          previous-id        (when prev-commit (:id prev-commit))
          prev-commit-flakes (when previous-id
                               (commit-data/prev-commit-flakes db* t-new commit-sid
                                                               previous-id))
          prev-data-id       (get-first-id prev-commit const/iri-data)
          prev-db-flakes     (when prev-data-id
                               (commit-data/prev-data-flakes db* db-sid t-new
                                                             prev-data-id))
          issuer-flakes      (when-let [issuer-iri (:id issuer)]
                               (commit-data/issuer-flakes db* t-new commit-sid issuer-iri))
          message-flakes     (when message
                               (commit-data/message-flakes t-new commit-sid message))
          all-flakes         (-> db*
                                 (get-in [:novelty :spot])
                                 empty
                                 (into metadata-flakes)
                                 (into retracted-flakes)
                                 (into asserted-flakes)
                                 (cond-> prev-commit-flakes (into prev-commit-flakes)
                                         prev-db-flakes (into prev-db-flakes)
                                         issuer-flakes (into issuer-flakes)
                                         message-flakes (into message-flakes)))]
      (when (empty? all-flakes)
        (commit-error "Commit has neither assertions or retractions!"
                      commit-metadata))
      (-> db*
          (merge-flakes t-new all-flakes)
          (assoc :commit commit-metadata)))))

;; ================ end Jsonld record support fns ============================

(defrecord FlakeDB [conn alias branch commit t tt-id stats spot post opst tspo
                    schema comparators staged novelty policy namespaces namespace-codes
                    max-namespace-code reindex-min-bytes reindex-max-bytes max-old-indexes]
  dbproto/IFlureeDb
  (-query [this query-map] (fql/query this query-map))
  (-class-ids [this subject] (match/class-ids this subject))
  (-index-update [db commit-index] (index-update db commit-index))

  iri/IRICodec
  (encode-iri [_ iri]
    (iri/iri->sid iri namespaces))
  (decode-sid [_ sid]
    (iri/sid->iri sid namespace-codes))

  where/Matcher
  (-match-id [db fuel-tracker solution s-mch error-ch]
    (match/match-id db fuel-tracker solution s-mch error-ch))

  (-match-triple [db fuel-tracker solution s-mch error-ch]
    (match/match-triple db fuel-tracker solution s-mch error-ch))

  (-match-class [db fuel-tracker solution s-mch error-ch]
    (match/match-class db fuel-tracker solution s-mch error-ch))

  (-activate-alias [db alias']
    (when (= alias alias')
      db))

  (-aliases [_]
    [alias])

  transact/Transactable
  (-stage-txn [db fuel-tracker context identity annotation raw-txn parsed-txn]
    (flake.transact/stage db fuel-tracker context identity annotation raw-txn parsed-txn))
  (-merge-commit [db new-commit proof] (merge-commit conn db [new-commit proof]))
  (-merge-commit [db new-commit] (merge-commit conn db [new-commit]))

  subject/SubjectFormatter
  (-forward-properties [db iri spec context compact-fn cache fuel-tracker error-ch]
    (jld-format/forward-properties db iri spec context compact-fn cache fuel-tracker error-ch))

  (-reverse-property [db iri reverse-spec compact-fn cache fuel-tracker error-ch]
    (jld-format/reverse-property db iri reverse-spec compact-fn cache fuel-tracker error-ch))

  (-iri-visible? [db iri]
    (qpolicy/allow-iri? db iri))

  indexer/Indexable
  (index [db changes-ch]
    (if (novelty/novelty-min? db reindex-min-bytes)
      (novelty/refresh db changes-ch max-old-indexes)
      (go)))

  TimeTravel
  (datetime->t [db datetime]
    (go-try
      (log/debug "datetime->t db:" (pr-str db))
      (let [epoch-datetime (util/str->epoch-ms datetime)
            current-time   (util/current-time-millis)
            [start end] (if (< epoch-datetime current-time)
                          [epoch-datetime current-time]
                          [current-time epoch-datetime])
            flakes         (-> db
                               policy/root
                               (query-range/index-range
                                :post
                                > [const/$_commit:time start]
                                < [const/$_commit:time end])
                               <?)]
        (log/debug "datetime->t index-range:" (pr-str flakes))
        (if (empty? flakes)
          (:t db)
          (let [t (-> flakes first flake/t flake/prev-t)]
            (if (zero? t)
              (throw (ex-info (str "There is no data as of " datetime)
                              {:status 400, :error :db/invalid-query}))
              t))))))

  (latest-t [_]
    t)

  (-as-of [db t]
    (assoc db :t t))

  AuditLog
  (-history [db context from-t to-t commit-details? include error-ch history-q]
    (history/query-history db context from-t to-t commit-details? include error-ch history-q))
  (-commits [db context from-t to-t include error-ch]
    (history/query-commits db context from-t to-t include error-ch))

  policy/Restrictable
  (wrap-policy [db policy values-map]
    (policy-rules/wrap-policy db policy values-map))
  (root [db]
    (policy/root-db db))

  reasoner/Reasoner
  (-reason [db methods rule-sources fuel-tracker reasoner-max]
    (flake.reasoner/reason db methods rule-sources fuel-tracker reasoner-max))
  (-reasoned-facts [db]
    (flake.reasoner/reasoned-facts db)))

(defn db?
  [x]
  (instance? FlakeDB x))

(def ^String label "#fluree/FlakeDB ")

(defn display
  [db]
  (select-keys db [:alias :branch :t :stats :policy]))

#?(:cljs
   (extend-type FlakeDB
     IPrintWithWriter
     (-pr-writer [db w _opts]
       (-write w label)
       (-write w (-> db display pr)))))

#?(:clj
   (defmethod print-method FlakeDB [^FlakeDB db, ^Writer w]
     (.write w label)
     (binding [*out* w]
       (-> db display pr))))

(defmethod pprint/simple-dispatch FlakeDB
  [db]
  (print label)
  (-> db display pprint))

(defn new-novelty-map
  [comparators]
  (reduce
   (fn [m idx]
     (assoc m idx (-> comparators
                      (get idx)
                      flake/sorted-set-by)))
   {:size 0} index/types))

(defn genesis-root-map
  [ledger-alias]
  (let [{spot-cmp :spot, post-cmp :post, opst-cmp :opst, tspo-cmp :tspo}
        index/comparators]
    {:t               0
     :spot            (index/empty-branch ledger-alias spot-cmp)
     :post            (index/empty-branch ledger-alias post-cmp)
     :opst            (index/empty-branch ledger-alias opst-cmp)
     :tspo            (index/empty-branch ledger-alias tspo-cmp)
     :stats           {:flakes 0, :size 0, :indexed 0}
     :namespaces      iri/default-namespaces
     :namespace-codes iri/default-namespace-codes
     :schema          (vocab/base-schema)}))

(defn load-novelty
  [conn indexed-db index-t commit-jsonld]
  (go-try
    (loop [[commit-tuple & r] (<? (reify/trace-commits conn [commit-jsonld nil] (inc index-t)))
           db indexed-db]
      (if commit-tuple
        (let [new-db (<? (merge-commit conn db commit-tuple))]
          (recur r new-db))
        db))))

(defn add-reindex-thresholds
  "Adds reindexing thresholds to the root map.

  Gives preference to indexing-opts param, which is passed in
  when creating a new ledger.

  If no indexing opts are present, looks for latest setting
  written at latest index root and uses that.

  Else, uses default values."
  [{:keys [config] :as root-map} indexing-opts]
  (let [reindex-min-bytes (or (:reindex-min-bytes indexing-opts)
                              (:reindex-min-bytes config)
                              100000) ; 100 kb
        reindex-max-bytes (or (:reindex-max-bytes indexing-opts)
                              (:reindex-max-bytes config)
                              1000000) ; 1mb
        max-old-indexes (or (:max-old-indexes indexing-opts)
                            (:max-old-indexes config)
                            3)] ;; default of 3 maximum old indexes not garbage collected
    (when-not (and (int? max-old-indexes)
                   (>= max-old-indexes 0))
      (throw (ex-info (str "Invalid max-old-indexes value. Must be a non-negative integer.")
                      {:status 400, :error :db/invalid-config})))
    (assoc root-map :reindex-min-bytes reindex-min-bytes
                    :reindex-max-bytes reindex-max-bytes
                    :max-old-indexes max-old-indexes)))

(defn load
  ([conn ledger-alias branch commit-pair]
   (load conn ledger-alias branch commit-pair {}))
  ([conn ledger-alias branch [commit-jsonld commit-map] indexing-opts]
   (go-try
     (let [root-map    (if-let [{:keys [address]} (:index commit-map)]
                         (<? (index-storage/read-db-root conn address))
                         (genesis-root-map ledger-alias))
           max-ns-code (-> root-map :namespace-codes iri/get-max-namespace-code)
           indexed-db  (-> root-map
                           (add-reindex-thresholds indexing-opts)
                           (assoc :conn conn
                                  :alias ledger-alias
                                  :branch branch
                                  :commit commit-map
                                  :tt-id nil
                                  :comparators index/comparators
                                  :staged []
                                  :novelty (new-novelty-map index/comparators)
                                  :max-namespace-code max-ns-code)
                           map->FlakeDB
                           policy/root)
           indexed-db* (if (nil? (:schema root-map)) ;; needed for legacy (v0) root index map
                         (<? (vocab/load-schema indexed-db (:preds root-map)))
                         indexed-db)
           commit-t    (-> commit-jsonld
                           (get-first const/iri-data)
                           (get-first-value const/iri-fluree-t))
           index-t     (:t indexed-db*)]
       (if (= commit-t index-t)
         indexed-db*
         (<? (load-novelty conn indexed-db* index-t commit-jsonld)))))))

(defn get-s-iri
  "Returns a compact IRI from a subject id (sid)."
  [db sid compact-fn]
  (compact-fn (iri/decode-sid db sid)))

(defn- serialize-obj
  [flake db compact-fn]
  (let [pdt (flake/dt flake)]
    (cond
      (= const/$id pdt) ;; ref to another node
      (if (= const/$rdf:type (flake/p flake))
        (get-s-iri db (flake/o flake) compact-fn) ;; @type values don't need to be in an @id map
        {"@id" (get-s-iri db (flake/o flake) compact-fn)})

      (datatype/inferable? pdt)
      (serde-json/serialize-object (flake/o flake) pdt)

      :else
      {"@value" (serde-json/serialize-object (flake/o flake) pdt)
       "@type"  (get-s-iri db pdt compact-fn)})))

(defn- add-obj-list-meta
  [obj-ser flake]
  (let [list-i (-> flake flake/m :i)]
    (if (map? obj-ser)
      (assoc obj-ser :i list-i)
      {"@value" obj-ser
       :i       list-i})))

(defn- subject-block-pred
  [db compact-fn list? p-flakes]
  (loop [[p-flake & r] p-flakes
         acc nil]
    (let [obj-ser (cond-> (serialize-obj p-flake db compact-fn)
                          list? (add-obj-list-meta p-flake))
          acc'    (conj acc obj-ser)]
      (if (seq r)
        (recur r acc')
        acc'))))

(defn- handle-list-values
  [objs]
  {"@list" (->> objs (sort-by :i) (map #(dissoc % :i)))})

(defn- subject-block
  [s-flakes db ^clojure.lang.Volatile ctx compact-fn]
  (loop [[p-flakes & r] (partition-by flake/p s-flakes)
         acc nil]
    (let [fflake (first p-flakes)
          list?  (-> fflake flake/m :i)
          pid    (flake/p fflake)
          p-iri  (get-s-iri db pid compact-fn)
          objs   (subject-block-pred db compact-fn list?
                                     p-flakes)
          objs*  (cond-> objs
                         list? handle-list-values
                         (= 1 (count objs)) first)
          acc'   (assoc acc p-iri objs*)]
      (if (seq r)
        (recur r acc')
        acc'))))

(defn commit-flakes
  "Returns commit flakes from novelty based on 't' value."
  [{:keys [novelty t] :as _db}]
  (-> novelty
      :tspo
      (flake/match-tspo t)
      not-empty))

(defn generate-commit
  "Generates assertion and retraction flakes for a given set of flakes
  which is assumed to be for a single (t) transaction.

  Returns a map of
  :assert - assertion flakes
  :retract - retraction flakes
  :refs-ctx - context that must be included with final context, for refs (@id) values
  :flakes - all considered flakes, for any downstream processes that need it"
  [{:keys [reasoner] :as db} {:keys [compact-fn id-key type-key] :as _opts}]
  (when-let [flakes (cond-> (commit-flakes db)
                      reasoner flake.reasoner/non-reasoned-flakes)]
    (log/trace "generate-commit flakes:" flakes)
    (let [ctx (volatile! {})]
      (loop [[s-flakes & r] (partition-by flake/s flakes)
             assert  []
             retract []]
        (if s-flakes
          (let [sid   (flake/s (first s-flakes))
                s-iri (get-s-iri db sid compact-fn)
                [assert* retract*]
                (if (and (= 1 (count s-flakes))
                         (= const/$rdfs:Class (->> s-flakes first flake/o))
                         (= const/$rdf:type (->> s-flakes first flake/p)))
                  ;; we don't output auto-generated rdfs:Class definitions for classes
                  ;; (they are implied when used in rdf:type statements)
                  [assert retract]
                  (let [{assert-flakes  true
                         retract-flakes false}
                        (group-by flake/op s-flakes)

                        s-assert  (when assert-flakes
                                    (-> (subject-block assert-flakes db ctx compact-fn)
                                        (assoc id-key s-iri)))
                        s-retract (when retract-flakes
                                    (-> (subject-block retract-flakes db ctx compact-fn)
                                        (assoc id-key s-iri)))]
                    [(cond-> assert
                       s-assert (conj s-assert))
                     (cond-> retract
                       s-retract (conj s-retract))]))]
            (recur r assert* retract*))
          {:refs-ctx (dissoc @ctx type-key) ; @type will be marked as @type: @id, which is implied
           :assert   assert
           :retract  retract
           :flakes   flakes})))))

(defn new-namespaces
  [{:keys [max-namespace-code namespace-codes] :as _db}]
  (->> namespace-codes
       (filter (fn [[k _v]]
                 (> k max-namespace-code)))
       (sort-by key)
       (mapv val)))

(defn db->jsonld
  "Creates the JSON-LD map containing a new ledger update"
  [{:keys [t commit stats staged] :as db}
   {:keys [type-key compact ctx-used-atom id-key] :as commit-opts}]
  (let [prev-dbid (commit-data/data-id commit)

        {:keys [assert retract refs-ctx]}
        (generate-commit db commit-opts)

        prev-db-key (compact const/iri-previous)
        assert-key  (compact const/iri-assert)
        retract-key (compact const/iri-retract)
        refs-ctx*   (cond-> refs-ctx
                      prev-dbid     (assoc-in [prev-db-key "@type"] "@id")
                      (seq assert)  (assoc-in [assert-key "@container"] "@graph")
                      (seq retract) (assoc-in [retract-key "@container"] "@graph"))
        nses        (new-namespaces db)
        db-json     (cond-> {id-key                nil ;; comes from hash later
                             type-key              [(compact const/iri-DB)]
                             (compact const/iri-fluree-t) t
                             (compact const/iri-v) data-version}
                      prev-dbid       (assoc prev-db-key prev-dbid)
                      (seq assert)    (assoc assert-key assert)
                      (seq retract)   (assoc retract-key retract)
                      (seq nses)      (assoc (compact const/iri-namespaces) nses)
                      (:flakes stats) (assoc (compact const/iri-flakes) (:flakes stats))
                      (:size stats)   (assoc (compact const/iri-size) (:size stats)))
        ;; TODO - this is re-normalized below, can try to do it just once
        dbid        (commit-data/db-json->db-id db-json)
        db-json*    (-> db-json
                        (assoc id-key dbid)
                        (assoc "@context" (merge-with merge @ctx-used-atom refs-ctx*)))]
    {:dbid        dbid
     :db-jsonld   db-json*
     :staged-txns staged}))
