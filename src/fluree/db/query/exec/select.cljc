(ns fluree.db.query.exec.select
  "Format and display solutions consisting of pattern matches found in where
  searches."
  (:refer-clojure :exclude [format])
  (:require [clojure.core.async :as async :refer [<! >! chan go go-loop]]
            [fluree.db.constants :as const]
            [fluree.db.dbproto :as dbproto]
            [fluree.db.query.exec.eval :as-alias eval]
            [fluree.db.query.exec.where :as where]
            [fluree.db.query.json-ld.response :as json-ld-resp]
            [fluree.db.query.range :as query-range]
            [fluree.db.util.async :refer [<?]]
            [fluree.db.util.core :refer [catch* try*]]
            [fluree.db.util.log :as log :include-macros true]
            [fluree.json-ld :as json-ld]
            [fluree.db.datatype :as datatype]))

#?(:clj (set! *warn-on-reflection* true))

(defmulti display
  "Format a where-pattern match for presentation based on the match's datatype.
  Return an async channel that will eventually contain the formatted match."
  (fn [match db iri-cache compact error-ch]
    (::where/datatype match)))

(defmethod display :default
  [match _ _ _ _]
  (go (where/get-value match)))

(defmethod display const/$xsd:anyURI
  [match db iri-cache compact error-ch]
  (go
    (let [v (where/get-value match)]
      (if-let [cached (-> @iri-cache (get v) :as)]
        cached
        (try* (let [iri (<? (dbproto/-iri db v compact))]
                (vswap! iri-cache assoc v {:as iri})
                iri)
              (catch* e
                (log/error e "Error displaying iri:" v)
                (>! error-ch e)))))))

(defprotocol ValueSelector
  (implicit-grouping? [this]
   "Returns true if this selector should have its values grouped together.")
  (format-value [fmt db iri-cache context compact error-ch solution]
   "Formats a where search solution (map of pattern matches) by extracting and displaying relevant pattern matches."))

;; This exists because many different types of data structures in :select
;; clauses get implicit-grouping? called on them. So this defaults them to false.
(extend-type #?(:clj Object :cljs object) ; https://cljs.github.io/api/cljs.core/extend-type
  ValueSelector
  (implicit-grouping? [_] false)
  (format-value [_ _ _ _ _ _ _] nil))

(defprotocol SolutionModifier
  (update-solution [this solution]))

(defrecord VariableSelector [var]
  ValueSelector
  (implicit-grouping? [_] false)
  (format-value
    [_ db iri-cache _context compact error-ch solution]
    (log/trace "VariableSelector format-value var:" var "solution:" solution)
    (-> solution
        (get var)
        (display db iri-cache compact error-ch))))

(defn variable-selector
  "Returns a selector that extracts and formats a value bound to the specified
  `variable` in where search solutions for presentation."
  [variable]
  (->VariableSelector variable))

(defrecord WildcardSelector []
  ValueSelector
  (implicit-grouping? [_] false)
  (format-value
   [_ db iri-cache _context compact error-ch solution]
   (go-loop [ks        (keys solution)
             formatted {}]
     (let [k          (first ks)
           fv         (-> solution
                          (get k)
                          (display db iri-cache compact error-ch)
                          <!)
           formatted' (assoc formatted k fv)
           next-ks    (rest ks)]
         (if (seq next-ks)
           (recur next-ks formatted')
           formatted')))))

(def wildcard-selector
  "Returns a selector that extracts and formats every bound value bound in the
  where clause."
  (->WildcardSelector))

(defrecord AggregateSelector [agg-fn]
  ValueSelector
  (implicit-grouping? [_] true)
  (format-value
    [_ _ _ _ _ error-ch solution]
    (go (try* (agg-fn solution)
              (catch* e
                (log/error e "Error applying aggregate selector")
                (>! error-ch e))))))

(defn aggregate-selector
  "Returns a selector that extracts the grouped values bound to the specified
  variables referenced in the supplied `agg-function` from a where solution,
  formats each item in the group, and processes the formatted group with the
  supplied `agg-function` to generate the final aggregated result for display."
  [agg-function]
  (->AggregateSelector agg-function))

(defrecord AsSelector [as-fn bind-var aggregate?]
  SolutionModifier
  (update-solution
    [_ solution]
    (log/trace "AsSelector update-solution solution:" solution)
    (let [result (as-fn solution)
          dt     (datatype/infer result)]
      (log/trace "AsSelector update-solution result:" result)
      (assoc solution bind-var (-> bind-var
                                   where/unmatched
                                   (where/match-value result dt)))))
  ValueSelector
  (implicit-grouping? [_] aggregate?)
  (format-value
    [_ _ _ _ _ _ solution]
    (log/trace "AsSelector format-value solution:" solution)
    (go (let [match (get solution bind-var)]
          (where/get-value match)))))

(defn as-selector
  [as-fn bind-var aggregate?]
  (->AsSelector as-fn bind-var aggregate?))

(defrecord SubgraphSelector [var selection depth spec]
  ValueSelector
  (implicit-grouping? [_] false)
  (format-value
    [_ db iri-cache context compact error-ch solution]
    (go
      (let [sid (-> solution
                    (get var)
                    where/get-value)]
        (try*
         (let [flakes (<? (query-range/index-range db :spot = [sid]))]
           ;; TODO: Replace these nils with fuel values when we turn fuel back on
           (<? (json-ld-resp/flakes->res db iri-cache context compact nil nil spec 0 flakes)))
         (catch* e
           (log/error e "Error formatting subgraph for subject:" sid)
           (>! error-ch e)))))))

(defn subgraph-selector
  "Returns a selector that extracts the subject id bound to the supplied
  `variable` within a where solution and extracts the subgraph containing
  attributes and values associated with that subject specified by `selection`
  from a database value."
  [variable selection depth spec]
  (->SubgraphSelector variable selection depth spec))

(defn format-values
  "Formats the values from the specified where search solution `solution`
  according to the selector or collection of selectors specified by `selectors`"
  [selectors db iri-cache context compact error-ch solution]
  (if (sequential? selectors)
    (go-loop [selectors selectors
              values []]
      (if-let [selector (first selectors)]
        (let [value (<! (format-value selector db iri-cache context compact error-ch solution))]
          (recur (rest selectors)
                 (conj values value)))
        values))
    (format-value selectors db iri-cache context compact error-ch solution)))

(defn format
  "Formats each solution within the stream of solutions in `solution-ch` according
  to the selectors within the select clause of the supplied parsed query `q`."
  [db q error-ch solution-ch]
  (let [context             (:context q)
        compact             (json-ld/compact-fn context)
        selectors           (or (:select q)
                                (:select-one q)
                                (:select-distinct q))
        iri-cache           (volatile! {})
        format-ch           (if (contains? q :select-distinct)
                              (chan 1 (distinct))
                              (chan))
        modifying-selectors (filter #(satisfies? SolutionModifier %) selectors)
        ;; TODO: Figure out the order of operations among parsing into a
        ;;       selector, this, & format-value
        mods-xf             (map (fn [solution]
                                   (reduce
                                    (fn [sol sel]
                                      (log/trace "Updating solution:" sol)
                                      (update-solution sel sol))
                                    solution modifying-selectors)))
        in-ch               (chan 1 mods-xf)]
    (async/pipe solution-ch in-ch)
    (async/pipeline-async 1
                          format-ch
                          (fn [solution ch]
                            (log/trace "select/format solution:" solution)
                            (-> (format-values selectors db iri-cache context compact error-ch solution)
                                (async/pipe ch)))
                          in-ch)
    format-ch))
