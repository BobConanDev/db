(ns fluree.db.json-ld.shacl
  (:require [fluree.db.util.async :refer [<? go-try]]
            #?(:clj  [fluree.db.util.clj-const :as uc]
               :cljs [fluree.db.util.cljs-const :as uc])
            [fluree.db.util.core :as util :refer [try* catch*]]
            [fluree.db.util.log :as log]
            [fluree.db.json-ld.iri :as iri]
            [fluree.db.query.range :as query-range]
            [fluree.db.constants :as const]
            [fluree.db.flake :as flake]
            [fluree.json-ld :as json-ld]
            [clojure.string :as str]
            [clojure.set :as set]
            [clojure.core.async :as async])
  #?(:clj (:import (java.util.regex Pattern))))

(comment
  ;; a raw SHACL shape looks something like this:
  {#fluree/SID [1 "id"] #fluree/SID [101 "UserShape"]
   #fluree/SID [3 "type"] [#fluree/SID [5 "NodeShape"]]
   #fluree/SID [5 "targetClass"] [#fluree/SID [101 "User"]]
   #fluree/SID [5 "property"]
   [{#fluree/SID [1 "id"] #fluree/SID [24 "fdb-2"]
     #fluree/SID [5 "datatype"] [#fluree/SID [2 "string"]]
     #fluree/SID [5 "maxCount"] [1]
     #fluree/SID [5 "minCount"] [1]
     #fluree/SID [5 "path"] [#fluree/SID [17 "name"]]}

    {#fluree/SID [1 "id"] #fluree/SID [24 "fdb-3"]
     #fluree/SID [5 "maxCount"] [1]
     #fluree/SID [5 "maxInclusive"] [130]
     #fluree/SID [5 "minCount"] [1]
     #fluree/SID [5 "minInclusive"] [0]
     #fluree/SID [5 "path"] [#fluree/SID [17 "age"]]}

    {#fluree/SID [1 "id"] #fluree/SID [24 "fdb-4"]
     #fluree/SID [5 "datatype"] [#fluree/SID [2 "string"]]
     #fluree/SID [5 "path"] [#fluree/SID [17 "email"]]}]}
  ,)

(def numeric-types
  #{const/$xsd:int
    const/$xsd:short
    const/$xsd:float
    const/$xsd:unsignedLong
    const/$xsd:unsignedInt
    const/$xsd:unsignedShort
    const/$xsd:positiveInteger
    const/$xsd:nonPositiveInteger
    const/$xsd:negativeInteger
    const/$xsd:nonNegativeInteger
    const/$xsd:decimal
    const/$xsd:double
    const/$xsd:integer
    const/$xsd:long})

(def time-types
  #{const/$xsd:date
    const/$xsd:dateTime
    const/$xsd:duration
    const/$xsd:gDay
    const/$xsd:gMonth
    const/$xsd:gMonthDay
    const/$xsd:gYear
    const/$xsd:gYearMonth
    const/$xsd:time})

(defn flake-value
  [flake]
  [(flake/o flake) (flake/dt flake)])

(defn coalesce-validation-results
  ([results] (coalesce-validation-results results nil))
  ([results logical-constraint]
   (log/trace "coalesce-validation-results results:" results)
   (let [results-map   (reduce (fn [acc [valid? err-msg]]
                                 (if err-msg
                                   (update acc (-> valid? str keyword) conj err-msg)
                                   acc))
                               {} results)
         short-circuit (if (= :not logical-constraint) :true :false)
         err-msgs      (get results-map short-circuit)]
     (if (empty? err-msgs)
       [true]
       [false (str/join "; " err-msgs)]))))


(defn validate-string-properties
  "String-based constraint components specify conditions on the string representation of values,
  as defined the SPARQL `str` function. See:

    - https://www.w3.org/TR/shacl/#core-components-string
    - https://www.w3.org/TR/sparql11-query/#func-str

  Therefore, we transform the value to a string (if it isn't one already)
  before performing validation."
  [{:keys [min-length max-length pattern flags logical-constraint] :as _p-shape} p-flakes]
  (let [results (for [flake p-flakes
                      :let [[val dt] (flake-value flake)
                            ref?    (and (number? val)
                                         (= const/$xsd:anyURI dt))
                            str-val (if (string? val)
                                      val
                                      (str val))]]
                  (let [str-length        (count str-val)
                        min-length-result (if (and min-length (or ref? (> min-length str-length)))
                                            [false (str "sh:minLength: value " str-val
                                                        " has string length smaller than minimum: " min-length
                                                        " or it is not a literal value")]
                                            [true (when min-length (str "sh:not sh:minLength: value " str-val
                                                                        " must have string length less than " min-length))])
                        max-length-result (if (and max-length (or ref? (< max-length str-length)))
                                            [false (str "sh:maxLength: value " str-val
                                                        " has string length larger than " max-length
                                                        " or it is not a literal value")]
                                            [true (when max-length (str "sh:not sh:maxLength: value " str-val
                                                                        " must have string length greater than " max-length))])
                        flag-msg          (when flags (str " with provided sh:flags: " flags))
                        pattern-result    (if (and pattern (or ref? (not (some? (re-find pattern str-val)))))
                                            [false (str "sh:pattern: value " str-val
                                                        " does not match pattern \"" pattern "\"" flag-msg
                                                        " or it is not a literal value")]
                                            [true (when pattern (str "sh:not sh:pattern: value " str-val
                                                                     " must not match pattern \"" pattern "\"" flag-msg))])
                        flake-results     [min-length-result max-length-result pattern-result]]
                    (coalesce-validation-results flake-results logical-constraint)))]
    (coalesce-validation-results results)))


(defn validate-count-properties
  [{:keys [min-count max-count logical-constraint] :as _p-shape} p-flakes]
  (let [n          (count p-flakes)
        min-result (if (and min-count (> min-count n))
                     [false (str "sh:minCount of " min-count " higher than actual count of " n)]
                     [true (when min-count (str "sh:not sh:minCount of " min-count " requires lower count but actual count was " n))])
        max-result (if (and max-count (> n max-count))
                     [false (str "sh:maxCount of " max-count " lower than actual count of " n)]
                     [true (when max-count (str "sh:not sh:maxCount of " max-count " requires higher count but actual count was " n))])
        results    [min-result max-result]]
    (coalesce-validation-results results logical-constraint)))

(defn validate-value-range-properties
  [{:keys [min-inclusive min-exclusive max-inclusive max-exclusive
           logical-constraint] :as _p-shape} p-flakes]
  (let [results (for [flake p-flakes
                      :let [[val dt] (flake-value flake)
                            non-numeric-val? (not (contains? numeric-types dt))]]
                  (let [flake-results
                        [(if (and min-inclusive (or non-numeric-val? (< val min-inclusive)))
                           [false (str "sh:minInclusive: value " val " is either non-numeric or lower than minimum of " min-inclusive)]
                           [true (when min-inclusive (str "sh:not sh:minInclusive: value " val " must be less than " min-inclusive))])

                         (if (and min-exclusive (or non-numeric-val? (<= val min-exclusive)))
                           [false (str "sh:minExclusive: value " val " is either non-numeric or lower than exclusive minimum of " min-exclusive)]
                           [true (when min-exclusive (str "sh:not sh:minExclusive: value " val " must be less than or equal to " min-exclusive))])

                         (if (and max-inclusive (or non-numeric-val? (> val max-inclusive)))
                           [false (str "sh:maxInclusive: value " val " is either non-numeric or higher than maximum of " max-inclusive)]
                           [true (when max-inclusive (str "sh:not sh:maxInclusive: value " val " must be greater than " max-inclusive))])

                         (if (and max-exclusive (or non-numeric-val? (>= val max-exclusive)))
                           [false (str "sh:maxExclusive: value " val " is either non-numeric or higher than exclusive maximum of " max-exclusive)]
                           [true (when max-exclusive (str "sh:not sh:maxExclusive: value " val " must be greater than or equal to " max-exclusive))])]]
                    (coalesce-validation-results flake-results logical-constraint)))]
    (coalesce-validation-results results)))

(defn validate-value-properties
  [db {:keys [in has-value datatype nodekind logical-constraint] :as _p-shape} p-flakes]
  (let [in-results (when in
                     (if (every? #(contains? (set in) (flake/o %)) p-flakes)
                       [true (str "sh:not sh:in: value must not be one of " in)]
                       [false (str "sh:in: value must be one of " in)]))
        has-value-results (when has-value
                            (if (some #(= (flake/o %) has-value) p-flakes)
                              [true (str "sh:not sh:hasValue: none of the values can be " has-value)]
                              [false (str "sh:hasValue: at least one value must be " has-value)]))
        datatype-results (when datatype
                           (if (every? #(= (flake/dt %) datatype) p-flakes)
                             [true (str "sh:not sh:datatype: every datatype must not be " (iri/decode-sid db datatype))]
                             [false (str "sh:datatype: every datatype must be " (iri/decode-sid db datatype))]))]
    (coalesce-validation-results [in-results has-value-results datatype-results] logical-constraint)))


(defn validate-nodekind-constraint
  [db {:keys [node-kind logical-constraint] :as _p-shape} p-flakes]
  (go-try
    (if (= node-kind const/sh_Literal)
      ;; don't need to do a lookup to check for literals
      (if (every? #(not= (flake/dt %) const/$xsd:anyURI) p-flakes)
        [true "sh:not sh:nodekind: every value must not be a literal"]
        [false "sh:nodekind: every value must be a literal"])

      (loop [[f & r] p-flakes
             res     []]
        (if f
          (let [literal? (not= (flake/dt f) const/$xsd:anyURI)
                bnode?   (-> f
                             flake/o
                             iri/get-namespace
                             (= "_:"))
                iri?     (not (or literal? bnode?))
                [valid? :as result]
                (condp = node-kind
                  const/sh_BlankNode
                  (if bnode?
                    [true "sh:not sh:nodekind: every value must not be a blank node identifier"]
                    [false "sh:nodekind: every value must be a blank node identifier"])
                  const/sh_IRI
                  (if bnode?
                    [true "sh:not sh:nodekind: every value must not be an IRI"]
                    [false "sh:nodekind: every value must be an IRI"])
                  const/sh_BlankNodeOrIRI
                  (if (or bnode? iri?)
                    [true "sh:not sh:nodekind: every value must not be a blank node identifier or an IRI"]
                    [false "sh:nodekind: every value must be a blank node identifier or an IRI"])
                  const/sh_IRIOrLiteral
                  (if (or iri? literal?)
                    [true "sh:not sh:nodekind: every value must not be an IRI or a literal"]
                    [false "sh:nodekind: every value must be an IRI or a literal"])
                  const/sh_BlankNodeOrLiteral
                  (if (or bnode? literal?)
                    [true "sh:not sh:nodekind: every value must not be a blank node identifier or a literal"]
                    [false "sh:nodekind: every value must be a blank node identifier or a literal"]))]
            (if valid?
              (recur r result)
              ;; short circuit if invalid
              result))
          res)))))

(declare build-node-shape)
(declare validate-shape)
(defn validate-node-constraint
  [db {:keys [node] :as _p-shape} p-flakes]
  (go-try
    (let [shape-flakes (<? (query-range/index-range db :spot = [node]))
          shape        (<? (build-node-shape db shape-flakes))]
      (loop [[f & r] p-flakes
             res []]
        (if f
          (let [sid           (flake/o f)
                s-flakes      (<? (query-range/index-range db :spot = [sid]))
                pid->p-flakes (group-by flake/p s-flakes)
                validation    (<? (validate-shape db shape sid s-flakes pid->p-flakes))]
            (recur r (conj res validation)))
          (coalesce-validation-results res))))))

(defn validate-class-properties
  [db {:keys [class] :as _p-shape} p-flakes]
  (go-try
    (log/trace "validate-class-properties class:" class)
    (log/trace "validate-class-properties p-flakes:" p-flakes)
    (loop [[f & r] p-flakes
           res []]
      (if f
        (let [type-flakes (<? (query-range/index-range
                               db :spot = [(flake/o f) const/$rdf:type]))
              type-set    (->> type-flakes (map flake/o) set)
              _           (log/trace "validate-class-properties type-set:"
                                     type-set)
              validation  (if (= class type-set)
                            [true (str "sh:not sh:class: class(es) "
                                       class " must not be same set as "
                                       type-set)]
                            [false (str "sh:class: class(es) "
                                        class " must be same set as "
                                        type-set)])]
          (recur r (conj res validation)))
        (coalesce-validation-results res)))))

(defn validate-simple-property-constraints
  "Validate property constraints that do not require any db lookups to verify."
  [db {:keys [min-count max-count
           min-inclusive min-exclusive max-inclusive max-exclusive
           min-length max-length pattern
           in has-value datatype] :as p-shape} p-flakes]
  (let [validation (if (or min-count max-count)
                     (validate-count-properties p-shape p-flakes)
                     [true])
        validation (if (and (first validation)
                            (or min-inclusive min-exclusive max-inclusive max-exclusive))
                     (validate-value-range-properties p-shape p-flakes)
                     validation)
        validation (if (and (first validation)
                            (or min-length max-length pattern))
                     (validate-string-properties p-shape p-flakes)
                     validation)
        validation (if (and (first validation)
                            (or in has-value datatype))
                     (validate-value-properties db p-shape p-flakes)
                     validation)]
    validation))

(defn validate-property-constraints
  "Validates a PropertyShape for a single predicate against a set of flakes.
  Returns a tuple of [valid? error-msg]."
  [db
   {:keys [min-count max-count min-inclusive min-exclusive max-inclusive node-kind
           max-exclusive min-length max-length pattern in has-value datatype node class] :as p-shape}
   p-flakes]
  (go-try
    (let [validation (validate-simple-property-constraints db p-shape p-flakes)
          validation (if (and (first validation) node)
                       (<? (validate-node-constraint db p-shape p-flakes))
                       validation)
          validation (if (and (first validation) class)
                       (<? (validate-class-properties db p-shape p-flakes))
                       validation)
          validation (if (and (first validation) node-kind)
                       (<? (validate-nodekind-constraint db p-shape p-flakes))
                       validation)]
      validation)))

(defn validate-pair-constraints
  "Validates a PropertyShape that compares values for a pair of predicates.
  Returns a tuple of [valid? error-msg]."
  [{:keys [pair-constraint logical-constraint] :as _p-shape} lhs-flakes rhs-flakes]
  (case pair-constraint

    (:equals :disjoint)
    (let [lhs-values (into #{} (map flake-value) lhs-flakes)
          rhs-values (into #{} (map flake-value) rhs-flakes)]
      (case pair-constraint
        :equals
        (if (not= lhs-values rhs-values)
          [(= :not logical-constraint)
           (str "sh:equals: "
                (mapv flake/o lhs-flakes)
                " not equal to "
                (mapv flake/o rhs-flakes))]
          [(not= :not logical-constraint)
           (str "sh:not sh:equals: "
                (mapv flake/o lhs-flakes)
                " is required to be not equal to "
                (mapv flake/o rhs-flakes))])
        :disjoint
        (if (seq (set/intersection lhs-values rhs-values))
          [(= :not logical-constraint)
           (str "sh:disjoint: "
                (mapv flake/o lhs-flakes)
                " not disjoint from "
                (mapv flake/o rhs-flakes))]
          [(not= :not logical-constraint)
           (str "sh:not sh:disjoint: "
                (mapv flake/o lhs-flakes)
                " is disjoint from "
                (mapv flake/o rhs-flakes))])))

    (:lessThan :lessThanOrEquals)
    (let [allowed-cmp-results (cond-> #{-1}
                                (= pair-constraint :lessThanOrEquals) (conj 0))
          valid-cmp-types     (into numeric-types time-types)
          results             (for [l-flake lhs-flakes
                                    r-flake rhs-flakes
                                    :let [[l-flake-o l-flake-dt] (flake-value l-flake)
                                          [r-flake-o r-flake-dt] (flake-value r-flake)]]
                                (if (or (not= l-flake-dt
                                              r-flake-dt)
                                        (not (contains? valid-cmp-types l-flake-dt))
                                        (not (contains? allowed-cmp-results
                                                        (flake/cmp-obj l-flake-o l-flake-dt r-flake-o r-flake-dt))))
                                  [false
                                   (str "sh" pair-constraint ": " l-flake-o " not less than "
                                        (when (= pair-constraint :lessThanOrEquals) "or equal to ")
                                        r-flake-o ", or values are not valid for comparison")]
                                  [true
                                   (str "sh:not sh" pair-constraint ": " l-flake-o " is less than "
                                        (when (= pair-constraint :lessThanOrEquals) "or equal to ")
                                        r-flake-o)]))]
      (coalesce-validation-results results logical-constraint))))

(defn resolve-path-flakes
  "Return the relevant flakes that are associated with the property shape's path."
  ([db sid path pid->p-flakes]
   (go-try
     (let [[[first-pid type] & r] path
           path-flakes (case type
                         :inverse (<? (query-range/index-range db :post = [first-pid [sid const/$xsd:anyURI]]))
                         :predicate (get pid->p-flakes first-pid)
                         (throw (ex-info "Unsupported property path." {:path-type type :path path})))]
       (<? (resolve-path-flakes db r path-flakes)))))
  ([db path p-flakes]
   (go-try
     (loop [[[pid type] & r] path
            path-flakes p-flakes]
       (if pid
         (let [path-flakes* (loop [[f & r] path-flakes
                                   res []]
                              (if f
                                (let [path-flakes*
                                      (case type
                                        :inverse (<? (query-range/index-range db :post = [pid [(flake/s f) const/$xsd:anyURI]]))
                                        :predicate (<? (query-range/index-range db :spot = [(flake/o f) pid]))
                                        (throw (ex-info "Unsupported property path." {:path-type type :path path})))]
                                  (recur r (into res path-flakes*)))
                                res))]
           (recur r path-flakes*))
         path-flakes)))))

(declare build-property-shape)
(defn validate-qualified-shape-constraints
  "Takes a property shape with a qualifiedValueShape constraint, builds the shape,
  validates it, and returns the shape with all conforming sids."
  [db {:keys [path qualified-value-shape qualified-min-count qualified-max-count qualified-value-shapes-disjoint] :as p-shape}
   p-flakes]
  (go-try
    (let [q-shape-flakes (<? (query-range/index-range db :spot = [qualified-value-shape]))
          node-shape?    (some (fn [f]
                                 (and (= (flake/p f) const/$rdf:type)
                                      (= (flake/o f) const/sh_NodeShape)))
                               q-shape-flakes)
          q-shape        (if node-shape?
                           (<? (build-node-shape db q-shape-flakes))
                           (<? (build-property-shape db const/sh_qualifiedValueShape q-shape-flakes)))]
      (loop [[f & r]    p-flakes
             conforming #{}]
        (if f
          (let [sid           (flake/o f)
                s-flakes      (<? (query-range/index-range db :spot = [sid]))
                pid->p-flakes (group-by flake/p s-flakes)

                [valid?] (if node-shape?
                           (<? (validate-shape db q-shape sid s-flakes pid->p-flakes))
                           (let [path-flakes (<? (resolve-path-flakes db sid (:path q-shape) pid->p-flakes))]
                             (<? (validate-property-constraints db q-shape path-flakes))))]
            (recur r (if valid?
                       (conj conforming sid)
                       conforming)))
          (assoc p-shape :conforming conforming))))))

(defn format-path
  [db path]
  (into []
        (map (fn [[pid type]]
               (let [p-iri (iri/decode-sid db pid)]
                 [p-iri type])))
        path))

(defn validate-qualified-cardinality-constraints
  [db {:keys [path conforming qualified-min-count qualified-max-count]}]
  (let [conforming-count (count conforming)]
    (cond (and qualified-min-count (< conforming-count qualified-min-count))
          [false (str "path " (format-path db path) " conformed to sh:qualifiedValueShape fewer than sh:qualifiedMinCount times")]

          (and qualified-max-count (> conforming-count qualified-max-count))
          [false (str "path " (format-path db path) " conformed to sh:qualifiedValueShape more than sh:qualifiedMaxCount times")]

          :else
          [true (str "sh:not conformed to sh:qualifiedValueShape between sh:qualifiedMinCount " qualified-min-count
                     " and sh:qualifiedMaxCount" qualified-max-count " times")])))

(defn remove-disjoint-conformers
  "Remove any conforming :disjoint sids from disjoint from supplied sibling q-shape."
  [disjoint-shape q-shape]
  (if (= q-shape disjoint-shape)
    q-shape
    (update q-shape :conforming set/difference (:conforming disjoint-shape))))

(defn validate-q-shapes
  [db q-shapes sid pid->p-flakes]
  (go-try
    (loop [[{:keys [path] :as q-shape} & r] q-shapes
           conforming-q-shapes []]
      (if q-shape
        (let [path-flakes (<? (resolve-path-flakes db sid path pid->p-flakes))
              conforming  (<? (validate-qualified-shape-constraints db q-shape path-flakes))]
          (recur r (conj conforming-q-shapes conforming)))

        (loop [[disjoint-shape & r] (filter :qualified-value-shapes-disjoint conforming-q-shapes)
               results conforming-q-shapes]
          (if disjoint-shape
            ;; remove any conforming :disjoint sids from all the other conforming sibling shapes
            (recur r (map (partial remove-disjoint-conformers disjoint-shape) conforming-q-shapes))
            ;; finally, validate the qualified cardinality constraints
            (->> results
                 (map (partial validate-qualified-cardinality-constraints db))
                 (coalesce-validation-results))))))))

(defn validate-closed-constraint
  [db {:keys [closed? ignored-properties] :as _shape} pid->p-flakes validated-properties]
  (let [unvalidated-properties (->> (keys pid->p-flakes)
                                    (remove (set/union ignored-properties validated-properties)))]
    (if (and closed? (not-empty unvalidated-properties))
      (let [prop-iris (into []
                            (map (partial iri/decode-sid db))
                            unvalidated-properties)]
        [false (str "SHACL shape is closed, extra properties not allowed: " prop-iris)])
      [true])))

(defn validate-shape
  "Check to see if each property shape is valid, then check node shape constraints."
  [db {:keys [property validated-properties] :as shape} sid s-flakes pid->p-flakes]
  (go-try
    (log/trace "validate-shape" sid shape )
    (loop [[{:keys [path rhs-property qualified-value-shape] :as p-shape} & r] property
           q-shapes             []
           validated-properties validated-properties
           results              []]
      (if p-shape
        ;; check property shape
        (let [path-flakes (<? (resolve-path-flakes db sid path pid->p-flakes))

              pid         (when (first path-flakes) (flake/p (first path-flakes)))
              res         (if rhs-property
                            (let [rhs-flakes (filter #(= rhs-property (flake/p %)) s-flakes)]
                              (validate-pair-constraints p-shape path-flakes rhs-flakes))
                            (<? (validate-property-constraints db p-shape path-flakes)))]

          (recur r
                 (if qualified-value-shape ; build up collection of q-shapes for further processing
                   (conj q-shapes p-shape)
                   q-shapes)
                 (if pid
                   (conj validated-properties pid)
                   validated-properties)
                 (conj results res)))

        (let [ ;; check qualifed shape constraints
              q-results (<? (validate-q-shapes db q-shapes sid pid->p-flakes))
              ;; check node shape
              closed-results (validate-closed-constraint db shape pid->p-flakes validated-properties)]
          (coalesce-validation-results (conj results q-results closed-results)))))))

(defn throw-shacl-exception
  [err-msg]
  (throw (ex-info (if (str/starts-with? err-msg "SHACL shape is closed")
                    err-msg
                    (str "SHACL PropertyShape exception - " err-msg "."))
                  {:status 400 :error :db/shacl-validation})))

(defn validate-target
  "Validate the data graph (s-flakes) with the provided shapes."
  [shapes db sid s-flakes]
  (go-try
    (let [pid->p-flakes (group-by flake/p s-flakes)]
      (doseq [shape shapes]
        (let [[valid? err-msg] (<? (validate-shape db shape sid s-flakes pid->p-flakes))]
          (when (not valid?)
            (throw-shacl-exception err-msg)))))))

(defn build-property-base-shape
  "Builds map out of values from a SHACL propertyShape (target of sh:property)"
  [db property-flakes]
  (let [pid (->> property-flakes first flake/s)
        iri (iri/decode-sid db pid)]
    (reduce
      (fn [acc property-flake]
        (let [o (flake/o property-flake)]
          (condp = (flake/p property-flake)
            const/sh_path
            (update acc :path (fnil conj []) o)

            ;; The datatype of all value nodes (e.g., xsd:integer).
            ;; A shape has at most one value for sh:datatype.
            const/sh_datatype
            (assoc acc :datatype o)

            const/sh_minCount
            (assoc acc :min-count o)

            const/sh_maxCount
            (assoc acc :max-count o)

            ;; values of sh:nodeKind in a shape are one of the following six instances of the
            ;; class sh:NodeKind: sh:BlankNode, sh:IRI, sh:Literal sh:BlankNodeOrIRI,
            ;; sh:BlankNodeOrLiteral and sh:IRIOrLiteral.
            ;; A shape has at most one value for sh:nodeKind.
            const/sh_nodeKind
            (assoc acc :node-kind o)

            ;; Note that multiple values for sh:class are interpreted as a conjunction,
            ;; i.e. the values need to be SHACL instances of all of them.
            const/sh_class
            (update acc :class (fnil conj #{}) o)

            const/sh_pattern
            (assoc acc :pattern o)

            const/sh_minLength
            (assoc acc :min-length o)

            const/sh_maxLength
            (assoc acc :max-length o)

            const/sh_flags
            (update acc :flags (fnil conj []) o)

            const/sh_languageIn
            (assoc acc :language-in o)

            const/sh_uniqueLang
            (assoc acc :unique-lang o)

            const/sh_hasValue
            (assoc acc :has-value o)

            const/sh_in
            (update acc :in (fnil conj []) o)

            const/sh_minExclusive
            (assoc acc :min-exclusive o)

            const/sh_minInclusive
            (assoc acc :min-inclusive o)

            const/sh_maxExclusive
            (assoc acc :max-exclusive o)

            const/sh_maxInclusive
            (assoc acc :max-inclusive o)

            const/sh_equals
            (assoc acc :pair-constraint :equals :rhs-property o)

            const/sh_disjoint
            (assoc acc :pair-constraint :disjoint :rhs-property o)

            const/sh_lessThan
            (assoc acc :pair-constraint :lessThan :rhs-property o)

            const/sh_lessThanOrEquals
            (assoc acc :pair-constraint :lessThanOrEquals :rhs-property o)

            const/sh_node
            (assoc acc :node o)

            const/sh_qualifiedValueShape
            (assoc acc :qualified-value-shape o)
            const/sh_qualifiedMinCount
            (assoc acc :qualified-min-count o)
            const/sh_qualifiedMaxCount
            (assoc acc :qualified-max-count o)
            const/sh_qualifiedValueShapesDisjoint
            (assoc acc :qualified-value-shapes-disjoint o)

            ;; else
            acc)))
      {:id iri}
      (sort-by (comp :i flake/m) property-flakes))))

;; TODO - pass along additional shape metadata to provided better error message.
(defn register-datatype
  "Optimization to elevate data types to top of shape for easy coersion when processing transactions"
  [{:keys [dt validate-fn] :as dt-map} {:keys [datatype path] :as property-shape}]
  (when (and dt
             (not= dt
                   datatype))
    (throw (ex-info (str "Conflicting SHACL shapes. Property " path
                         " has multiple conflicting datatype declarations of: "
                         dt " and " datatype ".")
                    {:status 400 :error :db/shacl-validation})))
  {:dt          datatype
   :validate-fn validate-fn})

(defn register-nodekind
  "Optimization to elevate node type designations"
  [{:keys [dt validate-fn] :as dt-map} {:keys [class node-kind path] :as property-shape}]
  (let [dt-map* (condp = node-kind

                  const/sh_BlankNode
                  {:dt          const/$xsd:anyURI
                   :class       class
                   :validate-fn (fn [x] (and (string? x)
                                             (str/starts-with? x "_:")))}

                  ;; common case, has to be an IRI
                  const/sh_IRI
                  {:dt          const/$xsd:anyURI
                   :class       class
                   :validate-fn (fn [x] (and (string? x)
                                             (not (str/starts-with? x "_:"))))}

                  const/sh_BlankNodeOrIRI
                  {:dt          const/$xsd:anyURI
                   :class       class
                   :validate-fn nil}

                  const/sh_IRIOrLiteral
                  {:dt          nil
                   :class       class
                   :validate-fn nil}

                  const/sh_BlankNodeOrLiteral
                  {:dt          nil
                   :class       class
                   :validate-fn nil}

                  ;; means it *cannot* be an IRI, but any literal is OK
                  const/sh_Literal
                  {:dt          nil
                   :validate-fn nil})]
    (when (and dt
               (not= dt (:dt dt-map*)))
      (throw (ex-info (str "Conflicting SHACL shapes. Property " path
                           " has multiple conflicting datatype declarations of: "
                           dt " and " (:dt dt-map*) ".")
                      {:status 400 :error :db/shacl-validation})))
    dt-map*))

(defn- merge-datatype
  "Merging functions for use with 'merge-with'.
  Ensures datatype merging values for each predicate are identical else throws."
  [{dt-result :dt, class-result :class, validate-result :validate-fn}
   {dt-latter :dt, class-latter :class, validate-latter :validate-fn}]
  (when (not= dt-result dt-latter)
    (throw (ex-info (str "Conflicting SHACL shapes. Property"
                         " has multiple conflicting datatype declarations of: "
                         dt-result " and " dt-latter ".")
                    {:status 400 :error :db/shacl-validation})))
  (when (not= dt-result dt-latter)
    (throw (ex-info (str "Conflicting SHACL shapes. Property"
                         " has multiple conflicting class declarations of: "
                         class-result " and " class-latter ".")
                    {:status 400 :error :db/shacl-validation})))
  {:dt          dt-result
   :class       class-result
   :validate-fn (cond
                  (and validate-result validate-latter)
                  (fn [x]
                    (and (validate-result x)
                         (validate-latter x)))

                  validate-result
                  validate-result

                  validate-latter
                  validate-latter)})

(defn get-regex-flag
  "Given an `sh:flag` value, returns the corresponding regex flag
  for the current platform. If the provided flag is not found,
  it will be ignored by validation.

  Note that js does not have support for `x` or `q` flag behavior."
  [flag]
  #?(:clj  (case flag
             "i" Pattern/CASE_INSENSITIVE
             "m" Pattern/MULTILINE
             "s" Pattern/DOTALL
             "q" Pattern/LITERAL
             "x" Pattern/COMMENTS
             0)
     :cljs (if (#{"i" "m" "s"} flag)
             flag
             "")))


(defn build-pattern
  "Builds regex pattern out of input string
  and any flags that were provided."
  [{:keys [:pattern :flags] :as p-shape}]
  (let [valid-flags (->> (map get-regex-flag flags)
                         #?(:clj  (apply +)
                            :cljs (apply str)))]
    (assoc p-shape :pattern #?(:clj  (Pattern/compile pattern (or valid-flags 0))
                               :cljs (js/RegExp. pattern (or valid-flags ""))))))

(defn resolve-path-type
  "Associate each property path object with its path type in order to govern path flake resolution during validation."
  [db path-pid]
  (go-try
    (if-let [path-flake (->> (<? (query-range/index-range db :spot = [path-pid]
                                                          {:flake-limit 1}))
                             first)]
      (let [o (flake/o path-flake)
            p (flake/p path-flake)]
        (uc/case p
          const/sh_inversePath [o :inverse]
          const/sh_alternativePath [o :alternative]
          const/sh_zeroOrMorePath [o :zero-plus]
          const/sh_oneOrMorePath [o :one-plus]
          const/sh_zeroOrOnePath [o :zero-one]
          [path-pid :predicate]))
      [path-pid :predicate])))

(defn resolve-path-types
  [{:keys [path] :as p-shape} db]
  (go-try
    (loop [[path-pid & r] path
           tagged-path []]
      (if path-pid
        (recur r (conj tagged-path (<? (resolve-path-type db path-pid))))
        (assoc p-shape :path tagged-path)))))

(defn build-property-shape
  [db p p-shape-flakes]
  (go-try
    (let [base     (build-property-base-shape db p-shape-flakes)
          base*    (<? (resolve-path-types base db))]
      (cond-> base*
        (:pattern base) (build-pattern)
        (= p const/sh_not) (assoc :logical-constraint :not)))))

(defn build-node-shape
  [db shape-flakes]
  (go-try
    (loop [[flake & r'] shape-flakes
           shape    {}
           p-shapes []]
      (if flake
        (let [p (flake/p flake)
              o (flake/o flake)]
          (if (#{const/sh_property const/sh_not} p)
            (let [p-shape-flakes (<? (query-range/index-range db :spot = [o]))
                  p-shape (<? (build-property-shape db p p-shape-flakes))]
              (recur r' shape (conj p-shapes p-shape)))
            (let [shape* (condp = p
                           const/sh_targetClass
                           (assoc shape :target-class o)

                           const/sh_closed
                           (if (true? o)
                             (assoc shape :closed? true)
                             shape)

                           const/sh_ignoredProperties
                           (update shape :ignored-properties (fnil conj #{}) o)

                           ;; else
                           shape)]
              (recur r' shape* p-shapes))))
        (let [pid->shacl-dt (->> p-shapes
                                 (filter :datatype)
                                 (map (fn [p-shape]
                                        [(-> p-shape :path last first)
                                         (:datatype p-shape)]))
                                 (into {}))]
          (assoc shape :property p-shapes :pid->shacl-dt pid->shacl-dt))))))

(defn build-shapes
  [db shape-sids]
  (go-try
    (when (seq shape-sids)
      (loop [[shape-sid & r] shape-sids
             shapes          []]
        (if shape-sid
          (let [shape-flakes (<? (query-range/index-range db :spot = [shape-sid]))
                shape        (<? (build-node-shape db shape-flakes))]
            (recur r (conj shapes shape)))
          shapes)))))

(defn build-class-shapes
  "Given a class SID, returns class shape"
  [db class-sid]
  (go-try
    (let [shape-sids (<? (query-range/index-range db :post = [const/sh_targetClass [class-sid const/$xsd:anyURI]]
                                                  {:flake-xf (map flake/s)}))]
      (map (fn [shape]
             (assoc shape :target-class class-sid))
           (<? (build-shapes db shape-sids))))))

(defn class-shapes
  "Takes a list of target classes and returns shapes that must pass validation,
  or nil if none exist."
  [{:keys [schema] :as db} class-sids]
  (go-try
    (let [shapes-cache (:shapes schema)]
      (loop [[class-sid & r] class-sids
             shapes          []]
        (if class-sid
          (let [class-shapes (if (contains? (:class @shapes-cache) class-sid)
                               (get-in @shapes-cache [:class class-sid])
                               (let [shapes (<? (build-class-shapes db class-sid))]
                                 (swap! shapes-cache assoc-in [:class class-sid] shapes)
                                 shapes))]
            (recur r (into shapes class-shapes)))
          shapes)))))

(defn build-targetobject-shapes
  "Given a pred SID, returns shape"
  [db pred-sid]
  (go-try
    (let [shape-sids (<? (query-range/index-range db :post = [const/sh_targetObjectsOf [pred-sid const/$xsd:anyURI]]
                                                  {:flake-xf (map flake/s)}))]
      (map (fn [shape]
             (assoc shape :target-objects-of pred-sid))
           (<? (build-shapes db shape-sids))))))

(defn targetobject-shapes
  "Takes a list of predicates and returns shapes that must pass validation,
  or nil if none exist."
  [{:keys [schema] :as db} pred-sids]
  (go-try
    (let [shapes-cache (:shapes schema)]
      (loop [[pred-sid & r] pred-sids
             shapes []]
        (if pred-sid
          (let [object-shapes (if (contains? (:target-objects-of @shapes-cache) pred-sid)
                                (get-in @shapes-cache [:target-objects-of pred-sid])
                                (let [shapes (<? (build-targetobject-shapes db pred-sid))]
                                  (swap! shapes-cache assoc-in [:target-objects-of pred-sid] shapes)
                                  shapes))]
            (recur r (into shapes object-shapes)))
          shapes)))))

(defn has-target-objects-of-rule?
  "Returns `true` if db currently has a rule that uses
  `sh:targetObjectsOf`. Used to avoid unnecessary lookups
  of shapes during transaction."
  [db]
  (-> db
      :schema
      :pred
      (contains? const/sh_targetObjectsOf)))

(defn property-shape?
  "Shapes are of two disjoint categories: sh:NodeShape and sh:PropertyShape. If a shape
  has the sh:path as a predicate, it is a sh:PropertyShape."
  [shape]
  (boolean (get shape const/sh_path)))

(defn qualified-value-shape?
  "A qualified value shape has one value for sh:qualifiedValueShape and either a
  sh:qualifiedMinCount or a sh:qualifiedMaxCount."
  [shape]
  (and (first (get shape const/sh_qualifiedValueShape))
       (or (first (get shape const/sh_qualifiedMinCount))
           (first (get shape const/sh_qualifiedMaxCount)))))

(defn build-shape-node
  ([db shape-sid]
   (build-shape-node db shape-sid #{shape-sid}))
  ([db shape-sid built-nodes]
   (go-try
     (let [flakes (<? (query-range/index-range db :spot = [shape-sid]))]
       (if (seq flakes)
         (loop [[f & r] (reverse flakes)
                node {const/$id shape-sid}]
           (if f
             (recur r (update node (flake/p f) (fnil conj [])
                              (if (flake/ref-flake? f)
                                (let [ref (flake/o f)]
                                  (if (contains? built-nodes ref)
                                    ref
                                    (<? (build-shape-node db ref (conj built-nodes ref)))))
                                (flake/o f))))
             node))
         shape-sid)))))

(defn build-shape
  [db shape-sid]
  (go-try
    (let [shapes-cache (-> db :schema :shapes)]
      (if-let [shape (get @shapes-cache shape-sid)]
        shape
        (let [shape (<? (build-shape-node db shape-sid))]
          (swap! shapes-cache assoc shape-sid shape)
          shape)))))

(defn build-sibling-shapes
  "Construct the sibling shapes of a shape with a sh:qualifiedValueShape. Siblings are
  other qualified value shape constraints in the same property constraint."
  [shape-db shape]
  (def shape-db shape-db)
  (def shape shape)
  (let [{shape-id const/$id
         [q-disjoint?] const/sh_qualifiedValueShapesDisjoint
         [{q-shape-id const/$id}] const/sh_qualifiedValueShape}
        shape]
    (if q-disjoint?
      (let [parent-shape-id
            (first (async/<!! (query-range/index-range shape-db :opst = [[shape-id const/$xsd:anyURI] const/sh_property]
                                                       {:flake-xf (map flake/s)})))
            sibling-sids
            (async/<!! (query-range/index-range shape-db :spot = [parent-shape-id const/sh_property]
                                                {:flake-xf (map flake/o)}))]
        (loop [[sib-sid & r] sibling-sids
               sib-q-shapes []]
          (if sib-sid
            (recur r (conj sib-q-shapes (async/<!! (build-shape shape-db sib-sid))))
            (->> sib-q-shapes
                 ;; only keep the qualified value shape of the sibling shape
                 (keep #(first (get % const/sh_qualifiedValueShape)))
                 ;; remove original q-shape
                 (remove #(= (get % const/$id) q-shape-id))))))
      [])))

(defmulti validate-constraint
  "A constraint whose focus nodes conform returns nil. A constraint that doesn't returns a
  sequence of result maps."
  (fn [v-ctx shape constraint focus-node value-nodes]
    (println "DEP validate-constraint dispatch" (pr-str constraint))
    constraint))

(defmethod validate-constraint :default [_ _ _ _ _] nil)

(defn validate-constraints
  [v-ctx shape focus-node value-nodes]
  (println "DEP validate-constraints")
  (loop [[[constraint] & r] shape
         results []]
    (if constraint
      (if-let [results* (validate-constraint v-ctx shape constraint focus-node value-nodes)]
        (recur r (into results results*))
        (recur r results))
      (not-empty results))))

(defn sid-node
  [sid]
  [sid const/$xsd:anyURI])

(defn subject-node
  [flake]
  (sid-node (flake/s flake)))

;; TODO: rename to object-node
(defn value-node
  "Take a flake and create a value node suitable for validation. A value node is a tuple of [value dt]."
  [flake]
  [(flake/o flake) (flake/dt flake)])

(defn resolve-predicate-path
  [data-db focus-node pred-path]
  (query-range/index-range data-db :spot = [focus-node pred-path] {:flake-xf (map value-node)}))

(defn resolve-inverse-path
  [data-db focus-node inverse-path]
  (query-range/index-range data-db :opst = [focus-node inverse-path] {:flake-xf (map subject-node)}))

(defn resolve-segment
  [data-db focus-node segment]
  (if (iri/sid? segment)
    (resolve-predicate-path data-db focus-node segment)
    (let [{[inverse-path] const/sh_inversePath} segment]
      (cond inverse-path (resolve-inverse-path data-db focus-node inverse-path)
            :else (throw (ex-info "Unsupported property path segment." {:segment segment}))))))

(defn resolve-value-nodes
  "Return the value nodes resolved via the path from the focus node."
  [data-db focus-node path]
  (println "DEP resolve-value-nodes" (pr-str focus-node) (pr-str path))
  (loop [[segment & segments] path
         focus-nodes [(sid-node focus-node)]
         value-nodes []]
    (if segment
      (let [vns (loop [[[sid :as f-node] & r] focus-nodes
                       v-nodes []]
                  (if f-node
                    (recur r (conj v-nodes (async/<!! (resolve-segment data-db sid segment))))
                    v-nodes))]
        (recur segments
               (apply concat vns)
               vns))
      value-nodes)))

(defn validate-property-shape
  "Returns a sequence of validation results if conforming fails, otherwise nil."
  [{:keys [data-db] :as v-ctx} {path const/sh_path :as shape} focus-node]
  (let [{path const/sh_path} shape]
    (loop [[value-nodes & r] (resolve-value-nodes data-db focus-node path)
           results []]
      (println "DEP property path value-nodes" (pr-str value-nodes))
      (if value-nodes
        (if-let [results* (validate-constraints v-ctx shape focus-node value-nodes)]
          (recur r (into results results*))
          (recur r results))
        (not-empty results)))))

(defn target-node-target?
  [shape s-flakes]
  (let [sid        (some-> s-flakes first flake/s)
        target-sids (->> (get shape const/sh_targetNode) (into #{}))]
    (println "DEP target-node-target?" (pr-str target-sids))
    (contains? target-sids sid)))

(defn target-class-target?
  [shape s-flakes]
  (let [target-class (first (get shape const/sh_targetClass))]
    (println "DEP target-class-target?" (pr-str target-class))
    (some (fn [f]
            (and (flake/class-flake? f)
                 (= (flake/o f) target-class)))
          s-flakes)))

(defn target-subjects-of-target?
  [shape s-flakes]
  (let [target-pid (first (get shape const/sh_targetSubjectsOf))]
    (println "DEP target-subjects-of-target?" (pr-str target-pid))
    (some (fn [f] (= (flake/p f) target-pid))
          s-flakes)))

(defn implicit-target?
  "If a sh:NodeShape has a class it implicitly targets that node."
  ;; https://www.w3.org/TR/shacl/#implicit-targetClass
  [shape s-flakes]
  (let [shape-classes (-> (get shape const/$rdf:type) (set) (disj const/sh_NodeShape))]
    (println "DEP implicit-target?" (pr-str shape-classes))
    (some (fn [f] (and (flake/class-flake? f)
                       (contains? shape-classes (flake/o f))))
          s-flakes)))

(defn target-objects-of-target?
  [shape]
  (first (get shape const/sh_targetObjectsOf)))

(defn target-objects-of-focus-nodes
  [db shape s-flakes]
  (go-try
    (let [target-pid (first (get shape const/sh_targetObjectsOf))]
      (println "DEP target-objects-of-target?" (pr-str target-pid))
      (let [sid             (some-> s-flakes first flake/s)
            referring-pids  (not-empty (<? (query-range/index-range db :opst = [[sid const/$xsd:anyURI]]
                                                                    {:flake-xf (map flake/p)})))
            p-flakes        (filterv (fn [f] (= (flake/p f) target-pid)) s-flakes)
            focus-nodes     (mapv value-node p-flakes)]
        ;; TODO: we don't know that these are sids, so we need to use a node layout for focus nodes
        (cond-> (mapv flake/o p-flakes)
          referring-pids (conj sid))))))

(defn resolve-focus-nodes
  "Evaluate the target declarations of a NodeShape to see if the provided s-flakes contain
  any focus nodes for the shape. Returns a sequence of focus nodes if targets are present."
  [data-db shape s-flakes]
  (go-try
    (let [sid (some-> s-flakes first flake/s)]
      (cond (target-node-target? shape s-flakes)        [sid]
            (target-class-target? shape s-flakes)       [sid]
            (target-subjects-of-target? shape s-flakes) [sid]
            (implicit-target? shape s-flakes)           [sid]
            (target-objects-of-target? shape)
            (<? (target-objects-of-focus-nodes data-db shape s-flakes))
            :else
            ;; no target declaration, no focus nodes
            []))))

(defn validate-node-shape
  ""
  ([{:keys [data-db] :as v-ctx} shape s-flakes]
   (loop [[focus-node & r] (async/<!! (resolve-focus-nodes data-db shape s-flakes))
          results []]
     (if focus-node
       (let [value-nodes (if (= (some-> s-flakes first flake/s) focus-node)
                           (mapv value-node s-flakes)
                           (async/<!! (query-range/index-range data-db :spot = [focus-node] {:flake-xf (map value-node)})))]
         (if-let [results* (validate-node-shape v-ctx shape focus-node value-nodes)]
           (recur r (into results results*))
           (recur r results)))
       (not-empty results))))
  ([v-ctx shape focus-node value-nodes]
   (validate-constraints v-ctx shape focus-node value-nodes)))

(defn base-result
  [{:keys [display] :as v-ctx} shape constraint focus-node]
  (let [{id const/$id
         path const/sh_path
         expect constraint} shape

        [single-expect :as pretty-expect]
        (mapv #(if (iri/sid? %) (display %) %) expect)]
    (cond-> {:subject (display focus-node)
             :constraint (display constraint)
             :shape (display id)
             :expect (if (> (count expect) 1)
                       pretty-expect
                       single-expect)}
      path (assoc :path (mapv (fn [segment]
                                (if (iri/sid? segment)
                                  (display segment)
                                  (let [[[k [v]]] (seq (dissoc segment const/$id))]
                                    {(display k) (display v)})))
                              path)))))

;; value type constraints
(defmethod validate-constraint const/sh_class [{:keys [display data-db] :as v-ctx} shape constraint focus-node value-nodes]
  (println "DEP validate-constraint " (pr-str constraint))
  (let [{expect constraint} shape

        expected-classes (into #{} expect)

        result (base-result v-ctx shape constraint focus-node)]
    (loop [[[o dt] & r] value-nodes
           results []]
      (if o
        (let [classes (if (iri/sid? o)
                        (->>
                          (async/<!! (query-range/index-range data-db :spot = [o const/$rdf:type]))
                          (into #{} (map flake/o)))
                        #{})
              missing-classes (set/difference expected-classes classes)]
          (recur r (into results
                         (mapv (fn [missing-class]
                                 (assoc result
                                        :value (mapv display classes)
                                        :message (str "missing required class " (display missing-class))))
                               missing-classes))))
        (not-empty results)))))
(defmethod validate-constraint const/sh_datatype [{:keys [display] :as v-ctx} shape constraint focus-node value-nodes]
  (println "DEP validate-constraint " (pr-str constraint))
  (let [{expect constraint} shape

        [datatype] expect
        violations (remove (fn [[_v dt]] (= dt datatype)) value-nodes)]
    (when (not-empty violations)
      [(-> (base-result v-ctx shape constraint focus-node)
           (assoc :value (mapv (comp display second) violations)
                  :message (str "the following values do not have expected datatype " (display datatype) ": "
                                (->> violations
                                     (mapv (fn [[v _dt]] (if (iri/sid? v) (display v) v)))
                                     (str/join ",")))))])))
(defmethod validate-constraint const/sh_nodeKind [{:keys [display] :as v-ctx} shape constraint focus-node value-nodes]
  (println "DEP validate-constraint " (pr-str constraint))
  (let [{expect constraint} shape

        [nodekind] expect
        result     (base-result v-ctx shape constraint focus-node)]
    (->> value-nodes
         (remove (fn [[v _dt]]
                   (let [iri?     (and (iri/sid? v) (not (iri/bnode? v)))
                         bnode?   (iri/bnode? v)
                         literal? (not (iri/sid? v))]
                     (condp = nodekind
                       const/sh_BlankNode          bnode?
                       const/sh_IRI                iri?
                       const/sh_BlankNodeOrIRI     (or iri? bnode?)
                       const/sh_IRIOrLiteral       (or iri? literal?)
                       const/sh_BlankNodeOrLiteral (or bnode? literal?)))))
         (mapv (fn [[v _dt]]
                 (let [value (if (iri/sid? v) (display v) v)]
                   (assoc result
                          :value value
                          :message (str "value " value " is is not of kind " (display nodekind)))))))))

;; cardinality constraints
(defmethod validate-constraint const/sh_minCount [{:keys [display] :as v-ctx} shape constraint focus-node value-nodes]
  (println "DEP validate-constraint " (pr-str constraint))
  (let [{expect constraint} shape

        [min] expect
        n     (count value-nodes)]
    (when (< n min)
      [(-> (base-result v-ctx shape constraint focus-node)
           (assoc :value n
                  :message (str "count " n " is less than minimum count of " min)))])))
(defmethod validate-constraint const/sh_maxCount [{:keys [display] :as v-ctx} shape constraint focus-node value-nodes]
  (println "DEP validate-constraint " (pr-str constraint) )
  (let [{expect constraint} shape

        [max] expect
        n     (count value-nodes)]
    (when (> n max)
      [(-> (base-result v-ctx shape constraint focus-node)
           (assoc :value n
                  :message (str "count " n " is greater than maximum count of " max)))])))

;; value range constraints
(defmethod validate-constraint const/sh_minExclusive [{:keys [display] :as v-ctx} shape constraint focus-node value-nodes]
  (println "DEP validate-constraint " (pr-str constraint))
  (let [{expect constraint} shape

        [min-ex] expect
        result   (base-result v-ctx shape constraint focus-node)]
    (->> value-nodes
         (remove (fn [[v dt]]
                   (and (contains? numeric-types dt)
                        (> v min-ex))))
         (mapv (fn [[v dt]]
                 (let [value (if (iri/sid? v) (display v) v)]
                   (assoc result
                          :value value
                          :message (str "value " value " is less than exclusive minimum " min-ex))))))))
(defmethod validate-constraint const/sh_maxExclusive [{:keys [display] :as v-ctx} shape constraint focus-node value-nodes]
  (println "DEP validate-constraint " (pr-str constraint))
  (let [{expect constraint} shape

        [max-ex] expect
        result  (base-result v-ctx shape constraint focus-node)]
    (->> value-nodes
         (remove (fn [[v dt]]
                   (and (contains? numeric-types dt)
                        (< v max-ex))))
         (mapv (fn [[v _dt]]
                 (let [value (if (iri/sid? v) (display v) v)]
                   (assoc result
                          :value value
                          :message (str "value " value " is greater than exclusive maximum " max-ex))))))))
(defmethod validate-constraint const/sh_minInclusive [{:keys [display] :as v-ctx} shape constraint focus-node value-nodes]
  (println "DEP validate-constraint " (pr-str constraint))
  (let [{expect constraint} shape

        [min-in] expect
        result   (base-result v-ctx shape constraint focus-node)]
    (->> value-nodes
         (remove (fn [[v dt]]
                   (and (contains? numeric-types dt)
                        (>= v min-in))))
         (mapv (fn [[v _dt]]
                 (let [value (if (iri/sid? v) (display v) v)]
                   (assoc result
                          :value value
                          :message (str "value " value " is less than inclusive minimum " min-in))))))))
(defmethod validate-constraint const/sh_maxInclusive [{:keys [display] :as v-ctx} shape constraint focus-node value-nodes]
  (println "DEP validate-constraint " (pr-str constraint))
  (let [{expect constraint} shape

        [max-in] expect
        result   (base-result v-ctx shape constraint focus-node)]
    (->> value-nodes
         (remove (fn [[v dt]]
                   (and (contains? numeric-types dt)
                        (<= v max-in))))
         (mapv (fn [[v _dt]]
                 (let [value (if (iri/sid? v) (display v) v)]
                   (assoc result
                          :value value
                          :message (str "value " value " is greater than inclusive maximum " max-in))))))))

;; string-based constraints
(defmethod validate-constraint const/sh_minLength [{:keys [display] :as v-ctx} shape constraint focus-node value-nodes]
  (println "DEP validate-constraint " (pr-str constraint))
  (let [{expect constraint} shape

        [min-length] expect
        result       (base-result v-ctx shape constraint focus-node)]
    (->> value-nodes
         (remove (fn [[v _dt]] (>= (count (str v)) min-length)))
         (mapv (fn [[v _dt]]
                 (if (iri/sid? v)
                   (let [value (if (iri/sid? v) (display v) (pr-str v))]
                     (assoc result
                            :value v
                            :message (str "value " value " is not a literal value")))
                   (let [value (pr-str (str v))]
                     (assoc result
                            :value v
                            :message (str "value " value " has string length less than minimum length " min-length)))))))))
(defmethod validate-constraint const/sh_maxLength [{:keys [display] :as v-ctx} shape constraint focus-node value-nodes]
  (println "DEP validate-constraint " (pr-str constraint))
  (let [{expect constraint} shape

        [max-length] expect
        result       (base-result v-ctx shape constraint focus-node)]
    (->> value-nodes
         (remove (fn [[v _dt]] (<= (count (str v)) max-length)))
         (mapv (fn [[v _dt]]
                 (if (iri/sid? v)
                   (let [value (if (iri/sid? v) (display v) (pr-str v))]
                     (assoc result
                            :value v
                            :message (str "value " value " is not a literal value")))
                   (let [value (pr-str (str v))]
                     (assoc result
                            :value v
                            :message (str "value " value " has string length greater than maximum length " max-length)))))))))
(defmethod validate-constraint const/sh_pattern [{:keys [display] :as v-ctx} shape constraint focus-node value-nodes]
  (println "DEP validate-constraint " (pr-str constraint))
  (let [{expect constraint flags const/sh_flags} shape

        [pattern-str] expect
        valid-flags   (mapv get-regex-flag flags)
        pattern       #?(:clj (Pattern/compile pattern-str (apply + valid-flags))
                         :cljs (js/RegExp. pattern-str (apply str valid-flags)))
        result        (base-result v-ctx shape constraint focus-node)]
    (println "DEP flags" (pr-str flags) (pr-str valid-flags))
    (->> value-nodes
         (remove (fn [[v dt]] (re-find pattern (str v))))
         (mapv (fn [[v _dt]]
                 (let [value (if (iri/sid? v) (display v) (pr-str (str v)))]
                   (assoc result
                          :value v
                          :message (str "value " value " does not match pattern " (pr-str pattern-str)
                                        (when (seq valid-flags)
                                          (str " with " (display const/sh_flags) " " (str/join ", " flags)))))))))))
#_(defmethod validate-constraint const/sh_languageIn [v-ctx constraint focus-flakes]) ; not supported
#_(defmethod validate-constraint const/sh_uniqueLang [v-ctx constraint focus-flakes]) ; not supported

;; property pair constraints
(defmethod validate-constraint const/sh_equals [{:keys [display data-db] :as v-ctx} shape constraint focus-node value-nodes]
  (println "DEP validate-constraint " (pr-str constraint))
  (let [{expect constraint} shape

        [equals]       expect
        equals-flakes  (async/<!! (query-range/index-range data-db :spot = [focus-node equals]))
        equals-objects (into #{} (map flake/o) equals-flakes)
        focus-objects  (into #{} (map first) value-nodes)]
    (when (not= equals-objects focus-objects)
      (let [result (base-result v-ctx shape constraint focus-node)
            iri-path (:path result)
            expect-vals  (sort (mapv (fn [v] (if (iri/sid? v) (display v) v)) equals-objects))
            values   (sort (mapv (fn [[v _dt]] (if (iri/sid? v) (display v) v)) value-nodes))]
        [(-> result
             (assoc :value values
                    :expect expect-vals
                    :message (str "path " iri-path " values " (str/join ", " values) " do not equal "
                                  (display equals) " values " (str/join ", " expect-vals))))]))))
(defmethod validate-constraint const/sh_disjoint [{:keys [data-db display] :as v-ctx} shape constraint focus-node value-nodes]
  (println "DEP validate-constraint " (pr-str constraint))
  (let [{expect constraint} shape

        [disjoint]       expect
        disjoint-flakes  (async/<!! (query-range/index-range data-db :spot = [focus-node disjoint]))
        disjoint-objects (into #{} (map flake/o) disjoint-flakes)
        focus-objects    (into #{} (map first) value-nodes)]
    (when (not-empty (set/intersection focus-objects disjoint-objects))
      (let [result      (base-result v-ctx shape constraint focus-node)
            iri-path    (:path result)
            expect-vals (sort (mapv (fn [v] (if (iri/sid? v) (display v) v)) disjoint-objects))
            values      (sort (mapv (fn [[v _dt]] (if (iri/sid? v) (display v) v)) value-nodes))]
        [(-> result
             (assoc :value values
                    :expect expect-vals
                    :message (str "path " iri-path " values " (str/join ", " values) " are not disjoint with "
                                  (display disjoint) " values " (str/join ", " expect-vals))))]))))
(defmethod validate-constraint const/sh_lessThan [{:keys [data-db display] :as v-ctx} shape constraint focus-node value-nodes]
  (println "DEP validate-constraint " (pr-str constraint))
  (let [{expect constraint} shape

        [less-than]       expect
        less-than-flakes  (async/<!! (query-range/index-range data-db :spot = [focus-node less-than]))
        less-than-objects (into #{} (map flake/o) less-than-flakes)
        focus-objects     (into #{} (map first) value-nodes)

        result      (base-result v-ctx shape constraint focus-node)
        iri-path    (:path result)
        expect-vals (sort (mapv (fn [v] (if (iri/sid? v) (display v) v)) less-than-objects))
        values      (sort (mapv (fn [[v _dt]] (if (iri/sid? v) (display v) v)) value-nodes))

        result (assoc result :value values :expect expect-vals)]
    (if (or (and (every? (fn [f] (contains? numeric-types (flake/dt f))) less-than-flakes)
                 (every? (fn [[v dt]] (contains? numeric-types dt)) value-nodes))
            (and (every? (fn [f] (contains? time-types (flake/dt f))) less-than-flakes)
                 (every? (fn [[v dt]] (contains? time-types dt)) value-nodes)))
      (when-not (every? (fn [o] (apply < o (sort less-than-objects))) focus-objects)
        [(assoc result :message (str "path " iri-path " values " (str/join ", " values) " are not all less than "
                                     (display less-than) " values " (str/join ", " expect-vals)))])
      [(assoc result :message (str "path " iri-path " values " (str/join ", " values) " are not all comparable with "
                                   (display less-than) " values " (str/join ", " expect-vals)))])))
(defmethod validate-constraint const/sh_lessThanOrEquals [{:keys [data-db display] :as v-ctx} shape constraint focus-node value-nodes]
  (println "DEP validate-constraint " (pr-str constraint))
  (let [{expect constraint} shape

        [less-than]       expect
        less-than-flakes  (async/<!! (query-range/index-range data-db :spot = [focus-node less-than]))
        less-than-objects (into #{} (map flake/o) less-than-flakes)
        focus-objects     (into #{} (map first) value-nodes)

        result      (base-result v-ctx shape constraint focus-node)
        iri-path    (:path result)
        expect-vals (sort (mapv (fn [v] (if (iri/sid? v) (display v) v)) less-than-objects))
        values      (sort (mapv (fn [[v _dt]] (if (iri/sid? v) (display v) v)) value-nodes))
        result      (assoc result :value values :expect expect-vals)]
    (if (or (and (every? (fn [f] (contains? numeric-types (flake/dt f))) less-than-flakes)
                 (every? (fn [[_ dt]] (contains? numeric-types dt)) value-nodes))
            (and (every? (fn [f] (contains? time-types (flake/dt f))) less-than-flakes)
                 (every? (fn [[_ dt]] (contains? time-types dt)) value-nodes)))
      (when-not (every? (fn [o] (apply <= o (sort less-than-objects))) focus-objects)
        [(assoc result :message (str "path " iri-path " values " (str/join ", " values) " are not all less than "
                                     (display less-than) " values " (str/join ", " expect-vals)))])
      [(assoc result :message (str "path " iri-path " values " (str/join ", " values) " are not all comparable with "
                                   (display less-than) " values " (str/join ", " expect-vals)))])))

;; logical constraints
(defmethod validate-constraint const/sh_not [{:keys [display] :as v-ctx} shape constraint focus-node value-nodes]
  (println "DEP validate-constraint " (pr-str constraint))
  (loop [[p-shape & r] (get shape const/sh_not)
         results []]
    (if p-shape
      (if-let [results* (validate-property-shape v-ctx p-shape focus-node)]
        (recur r results)
        (recur r (conj results (-> (base-result v-ctx shape constraint focus-node)
                                   (dissoc :expect)
                                   (assoc
                                     :value (display focus-node)
                                     :message (str (display focus-node) " conforms to shape " (display (get p-shape const/$id))))))))
      (not-empty results))))
(defmethod validate-constraint const/sh_and [v-ctx constraint focus-flakes]
  (println "DEP validate-constraint " (pr-str constraint)))
(defmethod validate-constraint const/sh_or [v-ctx constraint focus-flakes]
  (println "DEP validate-constraint " (pr-str constraint)))
(defmethod validate-constraint const/sh_xone [v-ctx constraint focus-flakes]
  (println "DEP validate-constraint " (pr-str constraint)))

;; shape-based constraints
(defmethod validate-constraint const/sh_node [{:keys [display data-db shape-db] :as v-ctx} shape constraint focus-node value-nodes]
  (println "DEP validate-constraint " (pr-str constraint))
  (let [{expect constraint} shape

        pretty-expect (->> expect
                           (mapv #(get % const/$id))
                           (mapv display))
        result (-> (base-result v-ctx shape constraint focus-node)
                   (assoc :expect pretty-expect))]
    (println "DEP sh:node expect" (pr-str expect))
    (loop [[[v dt] & r] value-nodes
           results []]
      (if (some? v)
        (if (iri/sid? v)
          (recur r (into results
                         (loop [[node-shape & r] expect
                                results []]
                           (if node-shape
                             (let [value-nodes (async/<!! (query-range/index-range data-db :spot = [v] {:flake-xf (map value-node)}))]
                               (println "DEP node shape" (pr-str shape))
                               (if-let [results* (validate-node-shape v-ctx node-shape v value-nodes)]
                                 (recur r (conj results (assoc result
                                                               :value (display v)
                                                               :message (str "node " (display v) " does not conform to shapes "
                                                                             pretty-expect))))
                                 (recur r results)))
                             results))))
          (recur r (conj results (assoc result
                                        :value v
                                        :message (str "value " v " does not conform to shapes "
                                                      pretty-expect)))))
        (not-empty results)))))
(defmethod validate-constraint const/sh_property [v-ctx shape constraint focus-node value-nodes]
  (println "DEP validate-constraint " (pr-str constraint))
  (loop [[p-shape & r] (get shape const/sh_property)
         results []]
    (if p-shape
      (if-let [results* (validate-property-shape v-ctx p-shape focus-node)]
        (recur r (into results results*))
        (recur r results))
      (not-empty results))))
(defmethod validate-constraint const/sh_qualifiedValueShape [{:keys [display data-db shape-db] :as v-ctx}
                                                             shape constraint focus-node value-nodes]
  (println "DEP validate-constraint " (pr-str constraint))
  (def v-ctx v-ctx)
  (let [{expect constraint
         [q-disjoint?] const/sh_qualifiedValueShapesDisjoint
         [q-min-count] const/sh_qualifiedMinCount
         [q-max-count] const/sh_qualifiedMaxCount} shape

        [q-shape] expect

        values (->> value-nodes
                    (mapv first)
                    (mapv #(if (iri/sid? %) (display %) %)))

        result (-> (base-result v-ctx shape constraint focus-node)
                   (assoc :expect (display (get q-shape const/$id)))
                   (assoc :value values))]
    (println "DEP q-shape" (pr-str q-shape))
    (loop [[[v _dt] & r] value-nodes
           conforming []]
      (println "DEP flake " (pr-str v) (pr-str conforming))
      (if (some? v)
        ;; build up conforming sids
        (let [focus-node* (if (iri/sid? v) v focus-node)
              value-nodes* (if (iri/sid? v)
                             (async/<!! (query-range/index-range data-db :spot = [v] {:flake-xf (map value-node)}))
                             value-nodes)
              result (if (property-shape? q-shape)
                       (validate-property-shape v-ctx q-shape focus-node*)
                       (validate-node-shape v-ctx q-shape focus-node* value-nodes*))]
          (println "DEP q-shape result" (pr-str result))
          (if result
            (recur r conforming)
            (recur r (conj conforming v))))

        (do
          (println "DEP end flake" (pr-str q-disjoint?) (pr-str conforming))
          (if q-disjoint?
            ;; disjoint requires subjects that conform to this q-shape cannot conform to any of the sibling q-shapes
            (let [sibling-q-shapes (build-sibling-shapes shape-db shape)]
              (loop [[conforming-sid & r] conforming
                     non-disjoint-conformers #{}]
                (println "DEP conforming-sid" (pr-str conforming-sid) (pr-str non-disjoint-conformers))
                (if conforming-sid
                  (recur r
                         (loop [[sib-q-shape & r] sibling-q-shapes
                                non-disjoint-conformers* []]
                           (println "DEP sib-q-shape" (pr-str sib-q-shape) (pr-str non-disjoint-conformers*))
                           (if sib-q-shape
                             (let [value-nodes (async/<!! (query-range/index-range data-db :spot = [conforming-sid]
                                                                                   {:flake-xf (map value-node)}))
                                   q-result (if (property-shape? sib-q-shape)
                                              (validate-property-shape v-ctx sib-q-shape conforming-sid)
                                              (validate-node-shape v-ctx sib-q-shape conforming-sid value-nodes))]
                               (println "DEP sib-q-shape result" (pr-str q-result) )
                               (if q-result
                                 (recur r non-disjoint-conformers*)
                                 (recur r (conj non-disjoint-conformers* conforming-sid))))
                             (do
                               (println "DEP sib-q-shape end" (pr-str non-disjoint-conformers*))
                               (into non-disjoint-conformers non-disjoint-conformers*)))))


                  (do
                    (println "DEP conforming-sid end" (pr-str non-disjoint-conformers))
                    (if (not-empty non-disjoint-conformers)
                      ;; each non-disjoint sid produces a validation result
                      (mapv
                        (fn [non-disjoint-sid]
                          (assoc result
                                 :value (display non-disjoint-sid)
                                 :message (str "value " (display non-disjoint-sid) " conformed to a sibling qualified value shape "
                                               (mapv #(display (get % const/$id)) sibling-q-shapes) " in violation of the "
                                               (display const/sh_qualifiedValueShapesDisjoint) " constraint")))

                        non-disjoint-conformers)

                      ;; no non-disjoint conformers, validate count constraints
                      (cond (and q-min-count (< (count conforming) q-min-count))
                            [(assoc result
                                    :message (str "values " values " conformed to " (display (get q-shape const/$id))
                                                  " less than " (display const/sh_qualifiedMinCount) " " q-min-count " times"))]
                            (and q-max-count (> (count conforming) q-max-count))
                            [(assoc result
                                    :message (str "values " values " conformed to " (display (get q-shape const/$id))
                                                  " more than " (display const/sh_qualifiedMaxCount) " " q-max-count " times"))]))))))
            ;; validate count constraints
            (do
              (println "DEP no disjoint check")
              (cond (and q-min-count (< (count conforming) q-min-count))
                    [(assoc result
                            :message (str "values " values " conformed to " (display (get q-shape const/$id))
                                          " less than " (display const/sh_qualifiedMinCount) " " q-min-count " times"))]
                    (and q-max-count (> (count conforming) q-max-count))
                    [(assoc result
                            :message (str "values " values " conformed to " (display (get q-shape const/$id))
                                          " more than " (display const/sh_qualifiedMaxCount) " " q-max-count " times"))]))))))))

;; other constraints
(defmethod validate-constraint const/sh_closed [{:keys [data-db display] :as v-ctx} shape constraint focus-node value-nodes]
  (println "DEP validate-constraint " (pr-str constraint))
  (let [{expect constraint ignored const/sh_ignoredProperties
         properties const/sh_property} shape

        [closed?]   expect
        s-flakes    (async/<!! (query-range/index-range data-db :spot = [focus-node]))
        constrained (into #{} (map #(-> % (get const/sh_path) first) properties))
        allowed     (into constrained ignored)
        present     (into #{} (map flake/p) s-flakes)
        not-allowed (set/difference present allowed)]
    (println "DEP closed" (pr-str allowed) (pr-str present) (pr-str not-allowed))
    (when (not-empty not-allowed)
      (let [pid->flakes (group-by flake/p s-flakes)]
        (mapv (fn [path]
                (let [values (->> (get pid->flakes path)
                                  (mapv #(if (flake/ref-flake? %)
                                           (display (flake/o %))
                                           (flake/o %))))]
                  (-> (base-result v-ctx shape constraint focus-node)
                      (assoc :value values
                             :expect (mapv display allowed)
                             :message (str "disallowed path " (display path) " with values " (str/join "," values))))))
              not-allowed)))))
(defmethod validate-constraint const/sh_hasValue [{:keys [display] :as v-ctx} shape constraint focus-node value-nodes]
  (println "DEP validate-constraint " (pr-str constraint))
  (let [{expect constraint} shape

        [term] expect]
    (when-not (some #(= term (first %)) value-nodes)
      (let [value (mapv (fn [[v _dt]] (if (iri/sid? v) (display v) v)) value-nodes)
            expect (if (iri/sid? term) (display term) term)]
        [(-> (base-result v-ctx shape constraint focus-node)
             (assoc :value value
                    :message (str "at least one value of " value " must be " expect)))]))))
(defmethod validate-constraint const/sh_in [{:keys [display] :as v-ctx} shape constraint focus-node value-nodes]
  (println "DEP validate-constraint " (pr-str constraint))
  (let [{expect constraint} shape

        expected (into #{} expect)
        result   (base-result v-ctx shape constraint focus-node)]
    (->> value-nodes
         (remove (fn [[v dt]] (contains? expected v)))
         (mapv (fn [[v dt]]
                 (let [value (if (iri/sid? v) (display v) (pr-str (str v)))]
                   (assoc result
                          :value v
                          :message (str "value " value " is not in " (:expect result)))))))))

(defn explain-result
  [{:keys [subject constraint shape path message]}]
  (str "Subject " subject (when path (str " path " path))
       " violates constraint " constraint " of shape " shape " - " message "."))

(defn throw-shacl-violation
  [{ns-codes :namespace-codes} context results]
  (println "DEP throw-shacl-violation" (count results))
  (def r results)
  (let [message (->> (mapv explain-result results)
                     (str/join "\n"))]
    (throw (ex-info message
                    {:status 400
                     :error  :shacl/violation
                     :report results}))))

(defn all-node-shape-ids
  [db]
  (def db db)
  (query-range/index-range db :post = [const/$rdf:type [const/sh_NodeShape const/$xsd:anyURI]]
                           {:flake-xf (map flake/s)}))

(defn sid->compact-iri
  [ns-codes context sid]
  (-> (iri/sid->iri sid ns-codes)
      (json-ld/compact context)))

(defn validate!
  "Will throw an exception if any of the modified subjects fails to conform to a shape that targets it.

  The `shape-db` is the db-before, since newly transacted shapes are not applied to the
  transaction they appear in. The `data-db` is the db after, and it has to conform to
  the shapes in the shape-db.

  `modified-subjects` is a sequence of s-flakes of modified subjects."
  [shape-db data-db modified-subjects context]
  (def sg shape-db)
  (def dg data-db)
  (def mods modified-subjects)
  (println "DEP validate!" (pr-str modified-subjects))
  (go-try
    (doseq [s-flakes modified-subjects]
      (doseq [shape-sid (<? (all-node-shape-ids shape-db))]
        (let [subject (-> s-flakes first flake/s)
              shape   (<? (build-shape shape-db shape-sid))
              ;; TODO: add sid? check to display
              v-ctx {:display (partial sid->compact-iri (:namespace-codes data-db) context)
                     :shape-db shape-db
                     :data-db data-db}]
          (println "DEP shape" (pr-str shape-sid))
          ;; only enforce activated shapes
          (when (not (get shape const/sh_deactivated))
            (let [results (validate-node-shape v-ctx shape s-flakes)]
              (println "DEP report" (pr-str results))
              (when results
                (throw-shacl-violation data-db context results)))))))))
