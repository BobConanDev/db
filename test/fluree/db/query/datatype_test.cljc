(ns fluree.db.query.datatype-test
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [fluree.db.test-utils :as test-utils]
            [fluree.db.json-ld.api :as fluree]
            [fluree.db.util.log :as log]))

(def default-context
  {:id     "@id"
   :type   "@type"
   :schema "http://schema.org/"
   :ex     "http://example.org/ns/"
   :rdfs   "http://www.w3.org/2000/01/rdf-schema#"
   :rdf    "http://www.w3.org/1999/02/22-rdf-syntax-ns#"})

(deftest ^:integration datatype-test
  (let [conn     (test-utils/create-conn)
        ledger   @(fluree/create conn "ledger/datatype")]
    (testing "Querying predicates with mixed datatypes"
      (let [mixed-db  @(fluree/stage ledger [{:context     default-context
                                              :id          :ex/coco
                                              :type        :schema/Person
                                              :schema/name "Coco"}
                                             {:context     default-context
                                              :id          :ex/halie
                                              :type        :schema/Person
                                              :schema/name "Halie"}
                                             {:context     default-context
                                              :id          :ex/john
                                              :type        :schema/Person
                                              :schema/name 3}])]
        (is (= [{:id          :ex/halie
                 :rdf/type    [:schema/Person]
                 :schema/name "Halie"}]
               @(fluree/query mixed-db
                              {:context default-context
                               :select {'?u [:*]}
                               :where [['?u :schema/name "Halie"]]}))
            "only returns the data type queried")
        (is (= []
               @(fluree/query mixed-db
                              {:context default-context
                               :select {'?u [:*]}
                               :where  [['?u :schema/name "a"]]}))
            "does not return results without matching subjects")
        (is (= [{:id :ex/john
                 :rdf/type [:schema/Person]
                 :schema/name 3}]
               @(fluree/query mixed-db
                              {:context default-context
                               :select  {'?u [:*]}
                               :where   [['?u :schema/name 3]]}))
            "only returns the data type queried")))))
