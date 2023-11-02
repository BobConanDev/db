(ns fluree.db.query.exec.where
  (:require [fluree.db.query.range :as query-range]
            [clojure.core.async :as async :refer [<! >! go take! put!]]
            [fluree.db.flake :as flake]
            [fluree.db.fuel :as fuel]
            [fluree.db.index :as index]
            [fluree.db.util.async :refer [<?]]
            [fluree.db.util.core :as util :refer [try* catch*]]
            [fluree.db.util.log :as log :include-macros true]
            [fluree.db.datatype :as datatype]
            [fluree.db.query.dataset :as dataset]
            [fluree.db.dbproto :as dbproto]
            [fluree.db.constants :as const]
            [fluree.db.json-ld.ledger :as ledger])
  #?(:clj (:import (clojure.lang MapEntry))))

#?(:clj (set! *warn-on-reflection* true))

(def unmatched
  {})

(defn unmatched-var
  [var-sym]
  (assoc unmatched ::var var-sym))

(defn match-value
  ([mch x dt]
   (assoc mch
     ::val x
     ::datatype dt))
  ([mch x dt m]
   (-> mch
       (match-value x dt)
       (assoc ::meta m))))

(defn match-iri
  ([iri]
   (match-iri unmatched iri))
  ([mch iri]
   (assoc mch ::iri iri)))

(defn get-iri
  [match]
  (-> match ::iri))

(defn matched-iri?
  [match]
  (-> match ::iri some?))

(defn matched-value?
  [match]
  (-> match ::val some?))

(defn match-sid
  [iri-mch db-alias sid]
  (update iri-mch ::sids assoc db-alias sid))

(defn matched-sid?
  [mch]
  (contains? mch ::sids))

(defn get-sid
  [iri-mch db-alias]
  (get-in iri-mch [::sids db-alias]))

(defn get-datatype
  [mch]
  (if (or (matched-iri? mch)
          (matched-sid? mch))
    const/$xsd:anyURI
    (::datatype mch)))

(defn matched?
  [match]
  (or (matched-value? match)
      (matched-iri? match)
      (matched-sid? match)))

(def unmatched?
  "Returns true if the triple pattern component `match` represents a variable
  without an associated value."
  (complement matched?))

(defn anonymous-value
  "Build a pattern that already matches an explicit value."
  ([v]
   (let [dt (datatype/infer v)]
     (anonymous-value v dt)))
  ([v dt]
   (match-value unmatched v dt))
  ([v dt m]
   (match-value unmatched v dt m)))

(defn unmatched-var?
  [match]
  (and (contains? match ::var)
       (unmatched? match)))

(defn get-value
  [match]
  (::val match))

(defn sanitize-match
  [match]
  (select-keys match [::iri ::val ::datatype ::sids]))

(defn ->pattern
  "Build a new non-tuple match pattern of type `typ`."
  [typ data]
  #?(:clj  (MapEntry/create typ data)
     :cljs (MapEntry. typ data nil)))

(defn ->iri-ref
  [x]
  {::iri x})

(defn variable?
  [sym]
  (and (symbol? sym)
       (-> sym
           name
           first
           (= \?))))

(defn ->var-filter
  "Build a query function specification for the variable `var` out of the
  parsed function `f`."
  [var f]
  (-> var
      unmatched-var
      (assoc ::fn f)))

(defn lang-matcher
  "Return a function that returns true if the language metadata of a matched
  pattern equals the supplied language code `lang`."
  [lang]
  (fn [mch]
    (-> mch ::meta :lang (= lang))))

(defn ->val-filter
  "Build a query function specification for the explicit value `val` out of the
  boolean function `f`. `f` should accept a single flake where-match map."
  [val f]
  (-> val
      anonymous-value
      (assoc ::fn f)))

(defn ->predicate
  "Build a pattern that already matches the explicit predicate value `value`."
  ([iri]
   (->iri-ref iri))
  ([iri recur-n]
   (-> iri
       ->predicate
       (assoc ::recur recur-n))))

(defn ->where-clause
  "Build a pattern that matches all the patterns in the supplied `patterns`
  collection and filters any matches for variables appearing as a key in the
  supplied `filters` map with the filter specification found in the value of the
  filters map for that variable, if the `filters` map is provided."
  ([patterns]
   {::patterns patterns})
  ([patterns filters]
   (cond-> (->where-clause patterns)
           (seq filters) (assoc ::filters filters))))

(defn pattern-type
  [pattern]
  (if (map-entry? pattern)
    (key pattern)
    :tuple))

(defmulti match-pattern
  "Return a channel that will contain all pattern match solutions from flakes in
   `db` that are compatible with the initial solution `solution` and matches the
   additional where-clause pattern `pattern`."
  (fn [ds _fuel-tracker _solution pattern _filters _error-ch]
    (let [typ (pattern-type pattern)]
      (case typ
        :tuple (if (dataset/dataset? ds)
                 :tuple-ds :tuple)
        :class (if (dataset/dataset? ds)
                 :class-ds :class)
        typ))))

(defn assign-matched-values
  "Assigns the value of any variables within the supplied `triple-pattern` that
  were previously matched in the supplied solution map `solution` to their
  values from `solution`. If a variable in `triple-pattern` does not have a
  match in `solution`, but does appear as a key in the filter specification map
  `filters`, the variable's match filter function within `triple-pattern` is set
  to the value associated with that variable from the `filter` specification
  map."
  [triple-pattern solution filters]
  (mapv (fn [component]
          (if-let [variable (::var component)]
            (if-let [match (get solution variable)]
              match
              (let [filter-fn (some->> (get filters variable)
                                       (map ::fn)
                                       (map (fn [f]
                                              (partial f solution)))
                                       (apply every-pred))]
                (assoc component ::fn filter-fn)))
            component))
        triple-pattern))

(defn match-subject
  "Matches the subject of the supplied `flake` to the triple subject pattern
  component `s-match`, and marks the matched pattern component as a URI data
  type."
  [s-match db-alias flake]
  (match-sid s-match db-alias (flake/s flake)))

(defn match-predicate
  "Matches the predicate of the supplied `flake` to the triple predicate pattern
  component `p-match`, and marks the matched pattern component as a URI data
  type."
  [p-match db-alias flake]
  (match-sid p-match db-alias (flake/p flake)))

(defn match-object
  "Matches the object, data type, and metadata of the supplied `flake` to the
  triple object pattern component `o-match`."
  [o-match db-alias flake]
  (let [dt (flake/dt flake)]
    (if (#{const/$xsd:anyURI} dt)
      (match-sid o-match db-alias (flake/o flake))
      (match-value o-match (flake/o flake) dt (flake/m flake)))))

(defn match-flake
  "Assigns the unmatched variables within the supplied `triple-pattern` to their
  corresponding values from `flake` in the supplied match `solution`."
  [solution triple-pattern db-alias flake]
  (let [[s p o] triple-pattern]
    (cond-> solution
      (unmatched-var? s) (assoc (::var s) (match-subject s db-alias flake))
      (unmatched-var? p) (assoc (::var p) (match-predicate p db-alias flake))
      (unmatched-var? o) (assoc (::var o) (match-object o db-alias flake)))))

(defn match-subject-iri
  [db matched error-ch]
  (go
    (try* (let [sid (get-sid matched (:alias db))]
            (if-let [iri (<? (dbproto/-iri db sid))]
              (match-iri matched iri)
              matched))
          (catch* e
                  (log/error e "Error looking up iri")
                  (>! error-ch e)))))

(defn match-predicate-iri
  [db matched]
  (let [pid (get-sid matched (:alias db))]
    (if-let [iri (dbproto/-p-prop db :iri pid)]
      (match-iri matched iri)
      matched)))

(defn match-flake-iris
  [db solution [s p o] flake error-ch]
  (go
    (let [db-alias (:alias db)
          s*       (when (unmatched-var? s)
                     (let [matched (match-subject s db-alias flake)]
                       (<! (match-subject-iri db matched error-ch))))
          p*       (when (unmatched-var? p)
                     (let [matched (match-predicate p db-alias flake)
                           p-iri   (match-predicate-iri db matched)]
                       (if (matched-iri? p-iri) ; check if sid falls outside of
                                                ; predicate range
                         p-iri
                         (<! (match-subject-iri db matched error-ch)))))
          o*       (when (unmatched-var? o)
                     (let [matched (match-object o db-alias flake)]
                       (if (= const/$xsd:anyURI (flake/dt flake))
                         (<! (match-subject-iri db matched error-ch))
                         matched)))]
      (cond-> solution
        (some? s*) (assoc (::var s*) s*)
        (some? p*) (assoc (::var p*) p*)
        (some? o*) (assoc (::var o*) o*)))))

(defn augment-object-fn
  "Returns a pair consisting of an object value and boolean function that will
  return false when applied to object values whose flake should be filtered out
  of query results. This function augments the original object function supplied
  in an object pattern under the `::fn` key (if any) by also checking if a
  prospective flake object is equal to the supplied `o` value if and only if the
  `:spot` index is used, the `p` value is `nil`, and the `s` and `o` values are
  not `nil`. In this case, the new object value returned by this function will
  be changed to `nil`. This ensures that all necessary flakes are considered
  from the spot index when scanned, and this is necessary because the `p` value
  is `nil`."
  [idx s p o o-fn alias]
  (if (and (#{:spot} idx)
           (nil? p)
           (and s o))
    (let [f (if o-fn
              (fn [mch]
                (and (#{o} (or (get-value mch)
                               (get-sid mch alias)))
                     (o-fn mch)))
              (fn [mch]
                (#{o} (get-value mch))))]
      [nil f])
    [o o-fn]))

(defn resolve-flake-range
  ([db fuel-tracker error-ch components]
   (resolve-flake-range db fuel-tracker nil error-ch components))

  ([{:keys [alias conn t] :as db} fuel-tracker flake-xf error-ch [s-mch p-mch o-mch]]
   (let [s    (get-sid s-mch alias)
         s-fn (::fn s-mch)
         p    (get-sid p-mch alias)
         p-fn (::fn p-mch)
         o    (or (get-value o-mch)
                  (get-sid o-mch alias))
         o-fn (::fn o-mch)
         o-dt (get-datatype o-mch)

         idx         (try* (index/for-components s p o o-dt)
                           (catch* e
                                   (log/error e "Error resolving flake range")
                                   (async/put! error-ch e)))
         idx-root    (get db idx)
         novelty     (get-in db [:novelty idx])
         [o* o-fn*]  (augment-object-fn idx s p o o-fn alias)
         start-flake (flake/create s p o* o-dt nil nil util/min-integer)
         end-flake   (flake/create s p o* o-dt nil nil util/max-integer)
         track-fuel  (when fuel-tracker
                       (take! (:error-ch fuel-tracker)
                              #(put! error-ch %))
                       (fuel/track fuel-tracker))
         subj-filter (when s-fn
                       (filter (fn [f]
                                 (-> unmatched
                                     (match-subject alias f)
                                     s-fn))))
         pred-filter (when p-fn
                       (filter (fn [f]
                                 (-> unmatched
                                     (match-predicate alias f)
                                     p-fn))))
         obj-filter  (when o-fn*
                       (filter (fn [f]
                                 (-> unmatched
                                     (match-object alias f)
                                     o-fn*))))
         flake-xf*   (->> [subj-filter pred-filter obj-filter
                           flake-xf track-fuel]
                          (remove nil?)
                          (apply comp))
         opts        {:idx         idx
                      :from-t      t
                      :to-t        t
                      :start-test  >=
                      :start-flake start-flake
                      :end-test    <=
                      :end-flake   end-flake
                      :flake-xf    flake-xf*}]
     (-> (query-range/resolve-flake-slices conn idx-root novelty error-ch opts)
         (->> (query-range/filter-authorized db start-flake end-flake error-ch))))))


(defn resolve-subject-sid
  [db error-ch s-mch]
  (go (try*
        (let [db-alias (:alias db)
              s-iri    (::iri s-mch)]
          (when-let [sid (<? (dbproto/-subid db s-iri {:expand? false}))]
            (match-sid s-mch db-alias sid)))
        (catch* e
                (log/error e "Error resolving subject id")
                (>! error-ch e)))))

(defn resolve-predicate-sid
  [db p-mch]
  (let [db-alias (:alias db)
        p-iri    (::iri p-mch)]
    (when-let [pid (or (get ledger/predefined-properties p-iri)
                       (dbproto/-p-prop db :id p-iri))]
      (match-sid p-mch db-alias pid))))

(defn resolve-sids
  [db error-ch [s p o]]
  (go
    (let [db-alias (:alias db)
          s*       (if (and (matched-iri? s)
                            (not (get-sid s db-alias)))
                     (<! (resolve-subject-sid db error-ch s))
                     s)
          p*       (if (and (matched-iri? p)
                            (not (get-sid p db-alias)))
                     (resolve-predicate-sid db p)
                     p)
          o*       (if (and (matched-iri? o)
                            (not (get-sid o db-alias)))
                     (<! (resolve-subject-sid db error-ch o))
                     o)]
      (when (and (some? s*) (some? p*) (some? o*))
        [s* p* o*]))))


(defn get-equivalent-properties
  [db prop]
  (-> db
      (get-in [:schema :pred prop :equivalentProperty])
      not-empty))

(defn match-tuple
  [db fuel-tracker solution pattern filters error-ch flake-ch]
  (go
    (let [db-alias (:alias db)
          triple   (assign-matched-values pattern solution filters)]
      (if-let [[s p o] (<! (resolve-sids db error-ch triple))]
        (let [pid (get-sid p db-alias)]
          (if-let [props (and pid (get-equivalent-properties db pid))]
            (let [prop-ch (async/to-chan! (conj props pid))]
              (async/pipeline-async 2
                                    flake-ch
                                    (fn [prop ch]
                                      (let [p* (match-sid p db-alias prop)]
                                        (-> db
                                            (resolve-flake-range fuel-tracker error-ch [s p* o])
                                            (async/pipe ch))))
                                    prop-ch))

            (-> db
                (resolve-flake-range fuel-tracker error-ch [s p o])
                (async/pipe flake-ch))))
        (async/close! flake-ch)))))

(defmethod match-pattern :tuple
  [db fuel-tracker solution pattern filters error-ch]
  (let [db-alias (:alias db)
        match-ch (async/chan 2 (comp cat
                                     (map (fn [flake]
                                            (match-flake solution pattern db-alias flake)))))]
    (match-tuple db fuel-tracker solution pattern filters error-ch match-ch)
    match-ch))

(defn match-tuple-iris
  [db fuel-tracker solution pattern filters error-ch]
  (let [flake-ch (async/chan 2 cat)
        out-ch (async/chan 2)]
    (async/pipeline-async 2
                          out-ch
                          (fn [flake ch]
                            (-> (match-flake-iris db solution pattern flake error-ch)
                                (async/pipe ch)))
                          flake-ch)
    (match-tuple db fuel-tracker solution pattern filters error-ch flake-ch)
    out-ch))

(defmethod match-pattern :tuple-ds
  [ds fuel-tracker solution pattern filters error-ch]
  (let [active-graph (dataset/active ds)]
    (if (sequential? active-graph)
      (->> active-graph
           (map (fn [db]
                  (match-tuple-iris db fuel-tracker solution pattern filters
                                    error-ch)))
           async/merge)
      (match-tuple-iris active-graph fuel-tracker solution pattern filters error-ch))))

(defn with-distinct-subjects
  "Return a transducer that filters a stream of flakes by removing any flakes with
  subject ids repeated from previously processed flakes."
  []
  (fn [rf]
    (let [seen-sids (volatile! #{})]
      (fn
        ;; Initialization: do nothing but initialize the supplied reducing fn
        ([]
         (rf))

        ;; Iteration: keep track of subject ids seen; only pass flakes with new
        ;; subject ids through to the supplied reducing fn.
        ([result f]
         (let [sid (flake/s f)]
           (if (contains? @seen-sids sid)
             result
             (do (vswap! seen-sids conj sid)
                 (rf result f)))))

        ;; Termination: do nothing but terminate the supplied reducing fn
        ([result]
         (rf result))))))

(defn match-class
  [db fuel-tracker solution triple filters error-ch flake-ch]
  (go (let [db-alias   (:alias db)
            [s p o]    (assign-matched-values triple solution filters)
            s*         (if (and (matched-iri? s)
                                (not (get-sid s db-alias)))
                         (<! (resolve-subject-sid db error-ch s))
                         s)
            p*         (if (and (matched-iri? p)
                                (not (get-sid p db-alias)))
                         (resolve-predicate-sid db p)
                         p)
            o*         (if (and (matched-iri? o)
                                (not (get-sid o db-alias)))
                         (resolve-predicate-sid db o)
                         o)]
        (if (and (some? s*) (some? p*) (some? o*))
          (let
              [cls        (get-sid o* db-alias)
               sub-obj    (dissoc o* ::sids ::iri)
               class-objs (into [o*]
                                (comp (map (fn [cls]
                                             (match-sid sub-obj db-alias cls)))
                                      (remove nil?))
                                (dbproto/-class-prop db :subclasses cls))
               class-ch   (async/to-chan! class-objs)]
            (async/pipeline-async 2
                                  flake-ch
                                  (fn [class-obj ch]
                                    (-> (resolve-flake-range db fuel-tracker error-ch [s* p* class-obj])
                                        (async/pipe ch)))
                                  class-ch))
          (async/close! flake-ch)))))

(defmethod match-pattern :class
  [db fuel-tracker solution pattern filters error-ch]
  (let [db-alias (:alias db)
        triple   (val pattern)
        match-ch (async/chan 2 (comp cat
                                     (with-distinct-subjects)
                                     (map (fn [flake]
                                            (match-flake solution triple db-alias flake)))))]

    (match-class db fuel-tracker solution triple filters error-ch match-ch)

    match-ch))

(defn match-class-iris
  [db fuel-tracker solution triple filters error-ch]
  (let [flake-ch (async/chan 2 (comp cat
                                     (with-distinct-subjects)))
        out-ch   (async/chan 2)]
    (async/pipeline-async 2
                          out-ch
                          (fn [flake ch]
                            (-> (match-flake-iris db solution triple flake error-ch)
                                (async/pipe ch)))
                          flake-ch)
    (match-class db fuel-tracker solution triple filters error-ch flake-ch)
    out-ch))

(defmethod match-pattern :class-ds
  [ds fuel-tracker solution pattern filters error-ch]
  (let [triple (val pattern)
        active-graph (dataset/active ds)]
    (if (sequential? active-graph)
      (->> active-graph
           (map (fn [db]
                  (match-class-iris db fuel-tracker solution triple filters error-ch)))
           async/merge)
      (match-class-iris active-graph fuel-tracker solution triple filters error-ch))))

(defn with-constraint
  "Return a channel of all solutions from the data set `ds` that extend from the
  solutions in `solution-ch` and also match the where-clause pattern `pattern`."
  [ds fuel-tracker pattern filters error-ch solution-ch]
  (let [out-ch (async/chan 2)]
    (async/pipeline-async 2
                          out-ch
                          (fn [solution ch]
                            (-> (match-pattern ds fuel-tracker solution pattern filters error-ch)
                                (async/pipe ch)))
                          solution-ch)
    out-ch))

(defn match-clause
  "Returns a channel that will eventually contain all match solutions in the
  dataset `ds` extending from `solution` that also match all the patterns in the
  parsed where clause collection `clause`."
  [ds fuel-tracker solution clause error-ch]
  (let [initial-ch (async/to-chan! [solution])
        filters    (::filters clause)
        patterns   (::patterns clause)]
    (reduce (fn [solution-ch pattern]
              (with-constraint ds fuel-tracker pattern filters error-ch solution-ch))
            initial-ch patterns)))

(defn match-alias
  [ds alias fuel-tracker solution clause error-ch]
  (-> ds
      (dataset/activate alias)
      (match-clause fuel-tracker solution clause error-ch)))

(defmethod match-pattern :graph
  [ds fuel-tracker solution pattern _filters error-ch]
  (let [[g clause] (val pattern)]
    (if-let [v (::var g)]
      (if-let [v-match (get solution v)]
        (let [alias (or (::iri v-match)
                        (get-value v-match))]
          (match-alias ds alias fuel-tracker solution clause error-ch))
        (let [out-ch  (async/chan)
              name-ch (-> ds dataset/names async/to-chan!)]
          (async/pipeline-async 2
                                out-ch
                                (fn [alias ch]
                                  (let [solution* (update solution v match-iri alias)]
                                    (-> (match-alias ds alias fuel-tracker solution* clause error-ch)
                                        (async/pipe ch))))
                                name-ch)
          out-ch))
      (match-alias ds g fuel-tracker solution clause error-ch))))

(defmethod match-pattern :union
  [db fuel-tracker solution pattern _ error-ch]
  (let [clauses   (val pattern)
        clause-ch (async/to-chan! clauses)
        out-ch    (async/chan 2)]
    (async/pipeline-async 2
                          out-ch
                          (fn [clause ch]
                            (-> (match-clause db fuel-tracker solution clause error-ch)
                                (async/pipe ch)))
                          clause-ch)
    out-ch))

(defn with-default
  "Return a transducer that transforms an input stream of solutions to include the
  `default-solution` if and only if the stream was empty."
  [default-solution]
  (fn [rf]
    (let [solutions? (volatile! false)]
      (fn
        ;; Initialization: do nothing but initialize the supplied reducing fn.
        ([]
         (rf))

        ;; Iteration: mark that a solution was processed, and pass it to the supplied
        ;; reducing fn.
        ([result solution]
         (do (vreset! solutions? true)
             (rf result solution)))

        ;; Termination: if no other solutions were processed, then process the
        ;; default-solution with the supplied reducing fn before terminating it;
        ;; terminate as normal otherwise.
        ([result]
         (if @solutions?
           (rf result)
           (do (vreset! solutions? true) ; mark that a solution was processed in
                                         ; case the reducing fn is terminated
                                         ; again as can happen with buffers.
               (-> result
                   (rf default-solution)
                   rf))))))))

(defmethod match-pattern :optional
  [db fuel-tracker solution pattern _ error-ch]
  (let [clause (val pattern)
        opt-ch (async/chan 2 (with-default solution))]
    (-> (match-clause db fuel-tracker solution clause error-ch)
        (async/pipe opt-ch))))

(defn add-fn-result-to-solution
  [solution var-name result]
  (let [dt  (datatype/infer result)
        mch (-> var-name
                unmatched-var
                (match-value result dt))]
    (assoc solution var-name mch)))

(defmethod match-pattern :bind
  [_db _fuel-tracker solution pattern _ error-ch]
  (let [bind (val pattern)]
    (go
      (let [result
            (reduce (fn [solution* b]
                      (let [f        (::fn b)
                            var-name (::var b)]
                        (try*
                          (->> (f solution)
                               (add-fn-result-to-solution solution* var-name))
                          (catch* e (update solution* ::errors conj e)))))
                    solution (vals bind))]
        (when-let [errors (::errors result)]
          (async/onto-chan! error-ch errors))
        result))))

(def blank-solution {})

(defn search
  [ds q fuel-tracker error-ch]
  (let [out-ch (async/chan 2)
        initial-solution-ch (-> q
                                :values
                                not-empty
                                (or [blank-solution])
                                async/to-chan!)]
    (if-let [where-clause (:where q)]
      (async/pipeline-async 2
                            out-ch
                            (fn [initial-solution ch]
                              (-> (match-clause ds fuel-tracker initial-solution where-clause error-ch)
                                  (async/pipe ch)))
                            initial-solution-ch)
      (async/pipe initial-solution-ch out-ch))
    out-ch))
