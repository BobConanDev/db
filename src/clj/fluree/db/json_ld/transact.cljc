(ns fluree.db.json-ld.transact
  (:require [clojure.core.async :as async :refer [alts! go]]
            [fluree.db.util.log :as log]
            [fluree.db.connection :as connection]
            [fluree.db.constants :as const]
            [fluree.db.fuel :as fuel]
            [fluree.db.json-ld.policy :as perm]
            [fluree.db.dbproto :as dbproto]
            [fluree.db.flake :as flake]
            [fluree.db.json-ld.branch :as branch]
            [fluree.db.json-ld.commit-data :as commit-data]
            [fluree.db.json-ld.shacl :as shacl]
            [fluree.db.json-ld.vocab :as vocab]
            [fluree.db.ledger :as ledger]
            [fluree.db.policy.enforce-tx :as policy]
            [fluree.db.query.fql.parse :as q-parse]
            [fluree.db.query.exec.update :as update]
            [fluree.db.query.exec.where :as where]
            [fluree.db.query.range :as query-range]
            [fluree.db.util.async :refer [<? go-try]]
            [fluree.db.util.core :as util]))

#?(:clj (set! *warn-on-reflection* true))

(defn validate
  [{:keys [db-after db-before mods context] :as staged-map}]
  (def sm staged-map)
  (go-try
    (let [root-db (dbproto/-rootdb db-after)]
      (<? (shacl/validate! db-before root-db (vals mods) context))
      staged-map)))

;; TODO - can use transient! below
(defn stage-update-novelty
  "If a db is staged more than once, any retractions in a previous stage will
  get completely removed from novelty. This returns flakes that must be added and removed
  from novelty."
  [novelty-flakes new-flakes]
  (loop [[f & r] new-flakes
         adds    new-flakes
         removes (empty new-flakes)]
    (if f
      (if (true? (flake/op f))
        (recur r adds removes)
        (let [flipped (flake/flip-flake f)]
          (if (contains? novelty-flakes flipped)
            (recur r (disj adds f) (conj removes flipped))
            (recur r adds removes))))
      [(not-empty adds) (not-empty removes)])))

(defn ->tx-state
  "Generates a state map for transaction processing.
  When optional reasoned-from-IRI is provided, will mark
  any new flakes as reasoned from the provided value
  in the flake's metadata (.-m) as :reasoned key."
  ([db context txn-id author-did] (->tx-state db context txn-id author-did nil))
  ([db context txn-id author-did reasoned-from-IRI]
   (let [{:keys [branch ledger policy], db-t :t} db
         commit-t  (-> (ledger/-status ledger branch) branch/latest-commit-t)
         t         (inc commit-t)
         db-before (dbproto/-rootdb db)]
     {:db-before     db-before
      :context       context
      :txn-id        txn-id
      :author-did    author-did
      :policy        policy
      :stage-update? (= t db-t)         ; if a previously staged db is getting updated
                                        ; again before committed
      :t             t
      :reasoner-max  10 ;; maximum number of reasoner iterations before exception
      :reasoned      reasoned-from-IRI})))

(defn into-flakeset
  [fuel-tracker error-ch flake-ch]
  (let [flakeset (flake/sorted-set-by flake/cmp-flakes-spot)
        error-xf (halt-when util/exception?)
        flake-xf (if fuel-tracker
                   (let [track-fuel (fuel/track fuel-tracker error-ch)]
                     (comp error-xf track-fuel))
                   error-xf)]
    (async/transduce flake-xf (completing conj) flakeset flake-ch)))

(defn generate-flakes
  [db fuel-tracker parsed-txn tx-state]
  (go
    (let [error-ch  (async/chan)
          db-vol    (volatile! db)
          update-ch (->> (where/search db parsed-txn fuel-tracker error-ch)
                         (update/modify db-vol parsed-txn tx-state fuel-tracker error-ch)
                         (into-flakeset fuel-tracker error-ch))]
      (async/alt!
        error-ch ([e] e)
        update-ch ([result]
                   (if (util/exception? result)
                     result
                     [@db-vol result]))))))

(defn modified-subjects
  "Returns a map of sid to s-flakes for each modified subject."
  [db flakes]
  (go-try
    (loop [[s-flakes & r] (partition-by flake/s flakes)
           sid->s-flakes  {}]
      (if s-flakes
        (let [sid             (some-> s-flakes first flake/s)
              existing-flakes (<? (query-range/index-range db :spot = [sid]))]
          (recur r (assoc sid->s-flakes sid (into (set s-flakes) existing-flakes))))
        sid->s-flakes))))

(defn final-db
  "Returns map of all elements for a stage transaction required to create an
  updated db."
  [db new-flakes {:keys [stage-update? policy t txn-id author-did db-before context] :as _tx-state}]
  (go-try
    (let [[add remove] (if stage-update?
                         (stage-update-novelty (get-in db [:novelty :spot]) new-flakes)
                         [new-flakes nil])

          mods (<? (modified-subjects db add))

          db-after  (-> db
                        (update :txns (fnil conj []) [txn-id author-did])
                        (assoc :policy policy) ;; re-apply policy to db-after
                        (assoc :t t)
                        (commit-data/update-novelty add remove)
                        (commit-data/add-tt-id)
                        (vocab/hydrate-schema add mods))]
      {:add add :remove remove :db-after db-after :db-before db-before :mods mods :context context})))

(defn flakes->final-db
  "Takes final set of proposed staged flakes and turns them into a new db value
  along with performing any final validation and policy enforcement."
  [fuel-tracker tx-state [db flakes]]
  (go-try
    (-> (final-db db flakes tx-state)
        <?
        validate
        <?
        (policy/allowed?)
        <?
        dbproto/-rootdb)))

(defn stage
  ([db txn parsed-opts]
   (stage db nil txn parsed-opts))
  ([{:keys [conn ledger] :as db} fuel-tracker txn parsed-opts]
   (go-try
     (let [{:keys [context raw-txn did]} parsed-opts

           parsed-txn (q-parse/parse-txn txn context)
           db*        (if-let [policy-identity (perm/parse-policy-identity parsed-opts context)]
                        (<? (perm/wrap-policy db policy-identity))
                        db)

           {txn-id :address}
           (<? (connection/-txn-write conn ledger raw-txn))

           tx-state (->tx-state db* context txn-id did)
           flakes   (<? (generate-flakes db fuel-tracker parsed-txn tx-state))]
       (<? (flakes->final-db fuel-tracker tx-state flakes))))))
