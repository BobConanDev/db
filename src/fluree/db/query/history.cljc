(ns fluree.db.query.history
  (:require [clojure.core.async :as async :refer [go >! <!]]
            [clojure.string :as str]
            [malli.core :as m]
            [fluree.json-ld :as json-ld]
            [fluree.db.constants :as const]
            [fluree.db.datatype :as datatype]
            [fluree.db.dbproto :as dbproto]
            [fluree.db.flake :as flake]
            [fluree.db.index :as index]
            [fluree.db.query.json-ld.response :as json-ld-resp]
            [fluree.db.util.async :refer [<? go-try]]
            [fluree.db.util.core :as util #?(:clj :refer :cljs :refer-macros) [try* catch*]]
            [fluree.db.util.log :as log]
            [fluree.db.query.range :as query-range]
            [fluree.db.db.json-ld :as jld-db]
            [fluree.db.validation :as v]
            [malli.error :as me]
            [malli.transform :as mt]))

(defn history-query-schema
  "Returns schema for history queries, with any extra key/value pairs `extra-kvs`
  added to the query map.
  This allows eg http-api-gateway to amend the schema with required key/value pairs
  it wants to require, which are not required/supported here in the db library."
  [extra-kvs]
  [:and
     [:map-of ::json-ld-keyword :any]
   (into
     [:map
      [:history {:optional true}
       [:orn
        [:subject ::iri]
        [:flake
         [:or
          [:catn
           [:s ::iri]]
          [:catn
           [:s [:maybe ::iri]]
           [:p ::iri]]
          [:catn
           [:s [:maybe ::iri]]
           [:p ::iri]
           [:o [:not :nil]]]]]]]
      [:commit-details {:optional true} :boolean]
      [:context {:optional true} ::context]
      [:opts {:optional true} [:map-of :keyword :any]]
      [:t
       [:and
        [:map-of :keyword :any]
        [:map
         [:from {:optional true} [:or
                                  [:= {:error/message "the key \"latest\""}:latest]
                                  [:int {:min 0
                                         :error/message "an integer > 0"} ]
                                  [:re {:error/message "an iso-8601 datetime value"}
                                   datatype/iso8601-datetime-re]]]
         [:to {:optional true} [:or
                                [:= {:error/message "the key \"latest\""} :latest]
                                [:int {:min 0
                                       :error/message "an integer > 0"}]
                                [:re {:error/message "an iso-8601 datetime value"}
                                 datatype/iso8601-datetime-re]]]
         [:at {:optional true} [:or
                                  [:= {:error/message "the key \"latest\""}:latest]
                                  [:int {:min 0
                                         :error/message "an integer > 0"} ]
                                  [:re {:error/message "an iso-8601 datetime value"}
                                   datatype/iso8601-datetime-re]]]]
        ;;TODO reword, does not explain that "at" is also an option.
        [:fn {:error/message "either \"from\" or \"to\" `t` keys must be provided."}
         (fn [{:keys [from to at]}]
           ;; if you have :at, you cannot have :from or :to
           (if at
             (not (or from to))
             (or from to)))]
        [:fn {:error/message "\"from\" value must be less than or equal to \"to\" value."}
         (fn [{:keys [from to]}] (if (and (number? from) (number? to))
                                   (<= from to)
                                   true))]]]]
     extra-kvs)
     [:fn {:error/message "Must supply a value for either \"history\" or \"commit-details\"."}
      (fn [{:keys [history commit-details t]}]
        (or (string? history) (keyword? history) (seq history) commit-details))]])


(def registry
  (merge
   (m/base-schemas)
   (m/type-schemas)
   (m/predicate-schemas)
   (m/comparator-schemas)
   (m/sequence-schemas)
   v/registry
   {::iri             ::v/iri
    ::json-ld-keyword ::v/json-ld-keyword
    ::context         ::v/context
    ::history-query   (history-query-schema [])}))

(def coerce-history-query
  "Provide a time range :t and either :history or :commit-details, or both.

  :history - either a subject iri or a vector in the pattern [s p o] with either the
  s or the p is required. If the o is supplied it must not be nil.

  :context or \"@context\" - json-ld context to use in expanding the :history iris.

  :commit-details - if true, each result will have a :commit key with the commit map as a value.

  :t  - a map containing either:
  - :at
  - either :from or :to

  accepted values for t maps:
       - positive t-value
       - datetime string
       - :latest keyword"
  (m/coercer ::history-query (mt/transformer {:name :fql})
             {:registry registry}))


(defn coalesce-disjunction-error-map
  [error-map]
  (into [] (map (fn [[k v]]
                  (str "Error in value of key \"" (name k) "\": must be one of: " (str/join ", " v) ".")))
        error-map))

(defn coalesce-for-key
  [error k]
  (when-let [errs (get error k)]
    (if (map? errs)
      (coalesce-disjunction-error-map errs)
      (str "Error in value of key \"" (name k) "\": " (str/join " " errs)))))

(defn massage-history-errors
  [humanized-error]
  ;;TODO could have errors from more than one place.
  (str/join "" (or (get humanized-error :malli/error)
                   (coalesce-for-key humanized-error :t)
                   (coalesce-for-key humanized-error :commit-details))))


(def explain-error
  (m/explainer ::history-query {:registry registry}))

(def parse-history-query
  (m/parser ::history-query {:registry registry}))

(defn s-flakes->json-ld
  "Build a subject map out a set of flakes with the same subject.

  {:id :ex/foo :ex/x 1 :ex/y 2}"
  [db cache context compact fuel error-ch s-flakes]
  (go
    (try*
     (let [json-chan (json-ld-resp/flakes->res db cache context compact fuel 1000000
                                               {:wildcard? true, :depth 0}
                                               0 s-flakes)]
       (-> (<? json-chan)
           ;; add the id in case the iri flake isn't present in s-flakes
           (assoc :id (json-ld/compact (<? (dbproto/-iri db (flake/s (first s-flakes)))) compact))))
     (catch* e
       (log/error e "Error transforming s-flakes.")
       (>! error-ch e)))))

(defn t-flakes->json-ld
  "Build a collection of subject maps out of a set of flakes with the same t.

  [{:id :ex/foo :ex/x 1 :ex/y 2}...]
  "
  [db context compact cache fuel error-ch t-flakes]
  (let [s-flakes-ch (->> t-flakes
                         (group-by flake/s)
                         (vals)
                         (async/to-chan!))

        s-out-ch    (async/chan)]
    (async/pipeline-async
     2
     s-out-ch
     (fn [assert-flakes ch]
       (-> db
           (s-flakes->json-ld cache context compact fuel error-ch assert-flakes)
           (async/pipe ch)))
     s-flakes-ch)
    s-out-ch))

(defn history-flakes->json-ld
  "Build a collection of maps for each t that contains the t along with the asserted and
  retracted subject maps.

  [{:id :ex/foo :f/assert [{},,,} :f/retract [{},,,]]}]
  "
  [db context error-ch flakes]
  (let [fuel        (volatile! 0)
        cache       (volatile! {})

        compact     (json-ld/compact-fn context)

        out-ch      (async/chan)

        t-flakes-ch (->> flakes
                         (sort-by flake/t >)
                         (group-by flake/t)
                         (vals)
                         (async/to-chan!))
        t-key       (json-ld/compact const/iri-t compact)
        assert-key  (json-ld/compact const/iri-assert compact)
        retract-key (json-ld/compact const/iri-retract compact)]

    (async/pipeline-async
     2
     out-ch
     (fn [t-flakes ch]
       (-> (go
             (try*
              (let [{assert-flakes  true
                     retract-flakes false} (group-by flake/op t-flakes)

                    t        (- (flake/t (first t-flakes)))

                    asserts  (->> assert-flakes
                                  (t-flakes->json-ld db context compact cache
                                   fuel error-ch)
                                  (async/into [])
                                  <!)

                    retracts (->> retract-flakes
                                  (t-flakes->json-ld db context compact cache
                                   fuel error-ch)
                                  (async/into [])
                                  <!)]
                {t-key       t
                 assert-key  asserts
                 retract-key retracts})
              (catch* e
                (log/error e "Error converting history flakes.")
                (>! error-ch e))))

           (async/pipe ch)))
     t-flakes-ch)
    out-ch))

(defn history-pattern
  "Given a parsed query, convert the iris from the query
  to subject ids and return the best index to query against."
  [db context query]
  (go-try
    (let [;; parses to [:subject <:id>] or [:flake {:s <> :p <> :o <>}]}
          [query-type parsed-query] query

          {:keys [s p o]} (if (= :subject query-type)
                            {:s parsed-query}
                            parsed-query)

          [s p o] [(when s (<? (dbproto/-subid db (jld-db/expand-iri db s context) true)))
                   (when p (<? (dbproto/-subid db (jld-db/expand-iri db p context) true)))
                   (when o (jld-db/expand-iri db o context))]

          idx     (index/for-components s p o nil)
          pattern (case idx
                    :spot [s p o]
                    :post [p o s]
                    :opst [o p s])]
      [pattern idx])))

(defn commit-wrapper-flake?
  "Returns `true` for a flake that represents
  data from the outer wrapper of a commit
  (eg commit message, time, v)"
  [f]
  (= (flake/s f) (flake/t f)))

(defn commit-metadata-flake?
  "Returns `true` if a flake is part of commit metadata.

  These are flakes that we insert which describe
  the data, but are not part of the data asserted
  by the user.

  Any :f/address flakes whose sids match filtered-sid will be ignored. This was
  added to handle defaultContext :f/address flakes."
  [filtered-sid f]
  (let [pred (flake/p f)]
    (or (#{const/$_commitdata:t
           const/$_commitdata:size
           const/$_previous
           const/$_commitdata:flakes} pred)
        (and (= const/$_address pred)
             (not= filtered-sid (flake/s f))))))

(defn extra-data-flake?
  [f]
  (or (= const/$xsd:anyURI (flake/p f))
      (= const/$rdfs:Class (flake/o f))))

(defn commit-t-flakes->json-ld
  "Build a commit maps given a set of all flakes with the same t."
  [db context compact cache fuel error-ch t-flakes]
  (go
    (try*
     (let [default-ctx-sid     (some #(when (= const/$_ledger:context (flake/p %))
                                        (flake/o %))
                                     t-flakes)
           commit-meta?        (partial commit-metadata-flake? default-ctx-sid)
           {commit-wrapper-flakes :commit-wrapper
            commit-meta-flakes    :commit-meta
            assert-flakes         :assert-flakes
            retract-flakes        :retract-flakes}
           (group-by (fn [f]
                       (cond
                         (commit-wrapper-flake? f) :commit-wrapper
                         (commit-meta? f) :commit-meta
                         (and (flake/op f) (not (extra-data-flake? f))
                              (not= default-ctx-sid (flake/s f))) :assert-flakes
                         (and (not (flake/op f)) (not (extra-data-flake? f))) :retract-flakes
                         :else :ignore-flakes))
                     t-flakes)
           commit-wrapper-chan (json-ld-resp/flakes->res db cache context compact fuel 1000000
                                                         {:wildcard? true, :depth 0}
                                                         0 commit-wrapper-flakes)

           commit-meta-chan    (json-ld-resp/flakes->res db cache context compact fuel 1000000
                                                         {:wildcard? true, :depth 0}
                                                         0 commit-meta-flakes)

           commit-wrapper      (<? commit-wrapper-chan)
           commit-meta         (<? commit-meta-chan)
           asserts             (->> assert-flakes
                                    (t-flakes->json-ld db context compact cache
                                                       fuel error-ch)
                                    (async/into [])
                                    <!)
           retracts            (->> retract-flakes
                                    (t-flakes->json-ld db context compact cache
                                                       fuel error-ch)
                                    (async/into [])
                                    <!)

           assert-key          (json-ld/compact const/iri-assert compact)
           retract-key         (json-ld/compact const/iri-retract compact)
           data-key            (json-ld/compact const/iri-data compact)
           commit-key          (json-ld/compact const/iri-commit compact)]
       (-> {commit-key commit-wrapper}
           (assoc-in [commit-key data-key] commit-meta)
           (assoc-in [commit-key data-key assert-key] asserts)
           (assoc-in [commit-key data-key retract-key] retracts)))
     (catch* e
       (log/error e "Error converting commit flakes.")
       (>! error-ch e)))))

(defn commit-flakes->json-ld
  "Create a collection of commit maps."
  [db context error-ch flake-slice-ch]
  (let [fuel        (volatile! 0)
        cache       (volatile! {})
        compact     (json-ld/compact-fn context)

        t-flakes-ch (async/chan 1 (comp cat (partition-by flake/t)))
        out-ch      (async/chan)]

    (async/pipe flake-slice-ch t-flakes-ch)
    (async/pipeline-async
     2
     out-ch
     (fn [t-flakes ch]
       (-> (commit-t-flakes->json-ld db context compact cache fuel error-ch
                                     t-flakes)
           (async/pipe ch)))
     t-flakes-ch)
    out-ch))

(defn with-consecutive-ts
  "Return a transducer that processes a stream of history results
  and chunk together results with consecutive `t`s. "
  [t-key]
  (let [last-t             (volatile! nil)
        last-partition-val (volatile! true)]
    (partition-by (fn [result]
                    (let [result-t     (get result t-key)
                          chunk-last-t @last-t]
                      (vreset! last-t result-t)
                      (if (or (nil? chunk-last-t)
                              (= chunk-last-t (dec result-t)))
                        ;;partition-by will not create a new paritition
                        ;;if returned value is the same as before
                        @last-partition-val
                        ;; partition-by will create a new partition
                        (vswap! last-partition-val not)))))))

(defn add-commit-details
  "Adds commit-details to history results from the history-results-ch.
  Chunks together history results with consecutive `t`s to reduce `time-range`
  calls. "
  [db context error-ch history-results-ch]
  (let [t-key      (json-ld/compact const/iri-t context)
        out-ch     (async/chan 2 cat)
        chunked-ch (async/chan 2 (with-consecutive-ts t-key))]
    (async/pipe history-results-ch chunked-ch)
    (async/pipeline-async
     2
     out-ch
     (fn [chunk ch]
       (async/pipe
        (go
          (let [to-t                       (- (-> chunk peek (get t-key)))
                from-t                     (- (-> chunk (nth 0) (get t-key)))
                flake-slices-ch            (query-range/time-range
                                            db :tspo = []
                                            {:from-t from-t, :to-t to-t})
                consecutive-commit-details (->> flake-slices-ch
                                                (commit-flakes->json-ld
                                                 db context error-ch)
                                                (async/into [])
                                                <?)]
            (map into chunk consecutive-commit-details)))
        ch))
     chunked-ch)
    out-ch))
