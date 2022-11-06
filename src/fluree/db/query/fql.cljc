(ns fluree.db.query.fql
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [clojure.walk :refer [keywordize-keys]]
            [fluree.db.util.core #?(:clj :refer :cljs :refer-macros) [try* catch*]
             :as util]
            [fluree.db.util.log :as log :include-macros true]
            [fluree.db.util.async :refer [<? go-try]]
            [fluree.db.util.core :refer [vswap!]]
            [fluree.db.query.analytical-parse :as q-parse]
            [fluree.db.dbproto :as db-proto]
            [fluree.db.query.subject-crawl.core :refer [simple-subject-crawl]]
            [fluree.db.query.compound :as compound]
            [fluree.db.query.range :as query-range]
            [fluree.db.query.json-ld.response :as json-ld-resp]
            [fluree.db.dbproto :as db-proto]
            [fluree.db.constants :as const])
  (:refer-clojure :exclude [var? vswap!])
  #?(:cljs (:require-macros [clojure.core])))

#?(:clj (set! *warn-on-reflection* true))

(declare query)

(defn var?
  [x]
  (-> x name first (= \?)))

(defn fn-string?
  [x]
  (boolean (re-matches #"^\(.+\)$" x)))

(s/def ::filter (s/coll-of (s/and string? fn-string?)))

(defn desc?
  [x]
  (boolean (#{'desc "desc"} x)))

(defn string-or-symbol?
  [x]
  (or (string? x)
      (symbol? x)))

(s/def ::orderBy (s/coll-of (s/or :asc  string-or-symbol?
                                  :desc (s/cat :direction desc?
                                               :field     string-or-symbol?))))

(s/def ::groupBy (s/coll-of (s/and string-or-symbol? var?)))

(s/def ::limit pos-int?)

(s/def ::offset nat-int?)

(s/def ::fuel number?)

(s/def ::depth nat-int?)

(s/def ::prettyPrint boolean?)

(s/def ::parseJSON boolean?)

(s/def ::opts (s/keys :opt-un [::parseJSON ::prettyPrint]))

(s/def ::query-map
  (s/keys :req-un [::limit ::offset ::depth ::fuel]
          :opt-un [::filter ::orderBy ::groupBy ::opts ::prettyPrint]))

(defn update-if-set
  [m k f]
  (if (contains? m k)
    (update m k f)
    m))

(defn with-default
  [x default]
  (or x default))

(defn normalize
  [qry]
  (-> qry
      (update :limit with-default util/max-integer)
      (update :offset with-default 0)
      (update :fuel with-default util/max-integer)
      (update :depth with-default 0)
      (update-if-set :opts keywordize-keys)
      (update-if-set :orderBy (fn [ob]
                                (if (vector? ob)
                                  ob
                                  [ob])))
      (update-if-set :groupBy (fn [grp]
                                (if (sequential? grp)
                                  grp
                                  [grp])))))

(defn validate
  [qry]
  (let [qry' (s/conform ::query-map qry)]
    (if (s/invalid? qry')
      (throw (ex-info "Invalid Query"
                      {:status 400
                       :error  :db/invalid-query
                       :reason (s/explain-data ::query-map qry)}))
      (s/unform ::query-map qry'))))

(def read-fn-str #?(:clj  read-string
                    :cljs cljs.reader/read-string))

(defn read-fn-safe
  [fn-str]
  (try*
   (read-fn-str fn-str)
   (catch* e
           (log/error "Failed parsing:" fn-str "with error message: " (ex-message e))
           (throw (ex-info (str "Invalid query function: " fn-str)
                           {:status 400
                            :error :db/invalid-query})))))

(defn transform
  [qry]
  (-> qry
      (update-if-set :filter (fn [fns]
                               (map read-fn-safe fns)))))

(defn process-where-item
  [db cache compact-fn fuel-vol fuel where-item spec inVector?]
  (go-try
    (loop [[spec-item & r'] spec
           result-item []]
      (if spec-item
        (let [{:keys [selection in-n iri? o-var? grouped?]} spec-item
              value  (nth where-item in-n)
              value* (cond
                       ;; there is a sub-selection (graph crawl)
                       selection
                       (let [flakes (<? (query-range/index-range db :spot = [value]))]
                         (<? (json-ld-resp/flakes->res db cache compact-fn fuel-vol fuel (:spec spec-item) 0 flakes)))

                       grouped?
                       (if o-var?
                         (mapv first value)
                         value)

                       ;; subject id coming it, we know it is an IRI so resolve here
                       iri?
                       (or (get @cache value)
                           (let [c-iri (<? (db-proto/-iri db value compact-fn))]
                             (vswap! cache assoc value c-iri)
                             c-iri))

                       o-var?
                       (let [[val datatype] value]
                         (if (= const/$xsd:anyURI datatype)
                           (or (get @cache val)
                               (let [c-iri (<? (db-proto/-iri db val compact-fn))]
                                 (vswap! cache assoc val c-iri)
                                 c-iri))
                           val))

                       :else
                       value)]
          (recur r' (conj result-item value*)))
        (if inVector?
          result-item
          (first result-item))))))


(defn process-select-results
  "Processes where results into final shape of specified select statement."
  [db out-ch where-ch error-ch {:keys [select fuel compact-fn group-by] :as _parsed-query}]
  (go-try
    (let [{:keys [spec inVector?]} select
          cache    (volatile! {})
          fuel-vol (volatile! 0)
          {:keys [group-finish-fn]} group-by]
      (loop []
        (let [where-items (async/alt!
                            error-ch ([e]
                                      (throw e))
                            where-ch ([result-chunk]
                                      result-chunk))]
          (if where-items
            (do
              (loop [[where-item & r] (if group-finish-fn   ;; note - this could be added to the chan as a transducer - however as all results are in one big, sorted chunk I don't expect any performance benefit
                                        (map group-finish-fn where-items)
                                        where-items)]
                (if where-item
                  (let [where-result (<? (process-where-item db cache compact-fn fuel-vol fuel where-item spec inVector?))]
                    (async/>! out-ch where-result)
                    (recur r))))
              (recur))
            (async/close! out-ch)))))))


(defn- ad-hoc-query
  "Legacy ad-hoc query processor"
  [db {:keys [fuel] :as parsed-query}]
  (let [out-ch (async/chan)]
    (let [max-fuel fuel
          fuel     (volatile! 0)
          error-ch (async/chan)
          where-ch (compound/where parsed-query error-ch fuel max-fuel db)]
      (process-select-results db out-ch where-ch error-ch parsed-query))
    out-ch))


(defn cache-query
  "Returns already cached query from cache if available, else
  executes and stores query into cache."
  [{:keys [network ledger-id block auth conn] :as db} query-map]
  ;; TODO - if a cache value exists, should max-fuel still be checked and throw if not enough?
  (let [oc        (:object-cache conn)
        query*    (update query-map :opts dissoc :fuel :max-fuel)
        cache-key [:query network ledger-id block auth query*]]
    ;; object cache takes (a) key and (b) fn to retrieve value if null
    (oc cache-key
        (fn [_]
          (let [pc (async/promise-chan)]
            (async/go
              (let [res (async/<! (query db (assoc-in query-map [:opts :cache]
                                                      false)))]
                (async/put! pc res)))
            pc)))))


(defn cache?
  "Returns true if query was requested to run from the cache."
  [{:keys [opts] :as _query-map}]
  #?(:clj (:cache opts) :cljs false))


(defn first-async
  "Returns first result of a sequence returned from an async channel."
  [ch]
  (go-try
    (let [res (<? ch)]
      (first res))))

(defn query
  "Returns core async channel with results or exception"
  [db query-map]
  (log/debug "Running query:" query-map)
  (if (cache? query-map)
    (cache-query db query-map)
    (let [parsed-query (q-parse/parse db (-> query-map normalize validate transform))
          db*          (assoc db :ctx-cache (volatile! {}))] ;; allow caching of some functions when available
      (if (= :simple-subject-crawl (:strategy parsed-query))
        (simple-subject-crawl db* parsed-query)
        (cond-> (async/into [] (ad-hoc-query db* parsed-query))
          (:selectOne? parsed-query) (first-async))))))
