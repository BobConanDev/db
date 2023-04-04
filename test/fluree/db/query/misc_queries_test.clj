(ns fluree.db.query.misc-queries-test
  (:require [clojure.test :refer :all]
            [fluree.db.test-utils :as test-utils]
            [fluree.db.json-ld.api :as fluree]
            [fluree.db.util.core :as util]))

(deftest ^:integration select-sid
  (testing "Select index's subject id in query using special keyword"
    (let [conn   (test-utils/create-conn)
          ledger @(fluree/create conn "query/subid" {:default-context ["" {:ex "http://example.org/ns/"}]})
          db     @(fluree/stage
                    (fluree/db ledger)
                    {:graph [{:id          :ex/alice,
                              :type        :ex/User,
                              :schema/name "Alice"}
                             {:id           :ex/bob,
                              :type         :ex/User,
                              :schema/name  "Bob"
                              :ex/favArtist {:id          :ex/picasso
                                             :schema/name "Picasso"}}]})]
      (is (= [{:_id          211106232532993,
               :id           :ex/bob,
               :rdf/type     [:ex/User],
               :schema/name  "Bob",
               :ex/favArtist {:_id         211106232532994
                              :schema/name "Picasso"}}
              {:_id         211106232532992,
               :id          :ex/alice,
               :rdf/type    [:ex/User],
               :schema/name "Alice"}]
             @(fluree/query db {:select {'?s [:_id :* {:ex/favArtist [:_id :schema/name]}]}
                                :where  [['?s :type :ex/User]]}))))))

(deftest ^:integration result-formatting
  (let [conn   (test-utils/create-conn)
        ledger @(fluree/create conn "query-context" {:default-context ["" {:ex "http://example.org/ns/"}]})
        db     @(test-utils/transact ledger [{:id   :ex/dan
                                              :ex/x 1}])]
    (is (= [{:id    :foo/dan
             :foo/x 1}]
           @(fluree/query db {"@context" ["" {:foo "http://example.org/ns/"}]
                              :where     [['?s :id :foo/dan]]
                              :select    {'?s [:*]}}))
        "default unwrapped objects")
    (is (= [{:id    :foo/dan
             :foo/x [1]}]
           @(fluree/query db {"@context" ["" {:foo   "http://example.org/ns/"
                                              :foo/x {:container :set}}]
                              :where     [['?s :id :foo/dan]]
                              :select    {'?s [:*]}}))
        "override unwrapping with :set")
    (is (= [{:id     :ex/dan
             "foo:x" [1]}]
           @(fluree/query db {"@context" ["" {"foo"   "http://example.org/ns/"
                                              "foo:x" {"@container" "@list"}}]
                              :where     [['?s "@id" "foo:dan"]]
                              :select    {'?s ["*"]}}))
        "override unwrapping with @list")))

(deftest ^:integration s+p+o-full-db-queries
  (with-redefs [fluree.db.util.core/current-time-iso (fn [] "1970-01-01T00:12:00.00000Z")]
    (let [conn   (test-utils/create-conn)
          ledger @(fluree/create conn "query/everything" {:default-context ["" {:ex "http://example.org/ns/"}]})
          db     @(fluree/stage
                   (fluree/db ledger)
                   {:graph [{:id           :ex/alice,
                             :type         :ex/User,
                             :schema/name  "Alice"
                             :schema/email "alice@flur.ee"
                             :schema/age   42}
                            {:id          :ex/bob,
                             :type        :ex/User,
                             :schema/name "Bob"
                             :schema/age  22}
                            {:id           :ex/jane,
                             :type         :ex/User,
                             :schema/name  "Jane"
                             :schema/email "jane@flur.ee"
                             :schema/age   30}]})]
      (testing "Query that pulls entire database."
        (is (= [[:ex/jane :id "http://example.org/ns/jane"]
                [:ex/jane :rdf/type :ex/User]
                [:ex/jane :schema/name "Jane"]
                [:ex/jane :schema/email "jane@flur.ee"]
                [:ex/jane :schema/age 30]
                [:ex/bob :id "http://example.org/ns/bob"]
                [:ex/bob :rdf/type :ex/User]
                [:ex/bob :schema/name "Bob"]
                [:ex/bob :schema/age 22]
                [:ex/alice :id "http://example.org/ns/alice"]
                [:ex/alice :rdf/type :ex/User]
                [:ex/alice :schema/name "Alice"]
                [:ex/alice :schema/email "alice@flur.ee"]
                [:ex/alice :schema/age 42]
                [:schema/age :id "http://schema.org/age"]
                [:schema/email :id "http://schema.org/email"]
                [:schema/name :id "http://schema.org/name"]
                [:ex/User :id "http://example.org/ns/User"]
                [:ex/User :rdf/type :rdfs/Class]
                [:rdfs/Class :id "http://www.w3.org/2000/01/rdf-schema#Class"]
                [:rdf/type :id "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"]
                [:id :id "@id"]]
               @(fluree/query db {:select ['?s '?p '?o]
                                  :where  [['?s '?p '?o]]}))
            "Entire database should be pulled.")
        (is (= [{:id :ex/jane,
                 :rdf/type [:ex/User],
                 :schema/name "Jane",
                 :schema/email "jane@flur.ee",
                 :schema/age 30}
                {:id :ex/jane,
                 :rdf/type [:ex/User],
                 :schema/name "Jane",
                 :schema/email "jane@flur.ee",
                 :schema/age 30}
                {:id :ex/jane,
                 :rdf/type [:ex/User],
                 :schema/name "Jane",
                 :schema/email "jane@flur.ee",
                 :schema/age 30}
                {:id :ex/jane,
                 :rdf/type [:ex/User],
                 :schema/name "Jane",
                 :schema/email "jane@flur.ee",
                 :schema/age 30}
                {:id :ex/jane,
                 :rdf/type [:ex/User],
                 :schema/name "Jane",
                 :schema/email "jane@flur.ee",
                 :schema/age 30}
                {:id :ex/bob,
                 :rdf/type [:ex/User],
                 :schema/name "Bob",
                 :schema/age 22}
                {:id :ex/bob,
                 :rdf/type [:ex/User],
                 :schema/name "Bob",
                 :schema/age 22}
                {:id :ex/bob,
                 :rdf/type [:ex/User],
                 :schema/name "Bob",
                 :schema/age 22}
                {:id :ex/bob,
                 :rdf/type [:ex/User],
                 :schema/name "Bob",
                 :schema/age 22}
                {:id :ex/alice,
                 :rdf/type [:ex/User],
                 :schema/name "Alice",
                 :schema/email "alice@flur.ee",
                 :schema/age 42}
                {:id :ex/alice,
                 :rdf/type [:ex/User],
                 :schema/name "Alice",
                 :schema/email "alice@flur.ee",
                 :schema/age 42}
                {:id :ex/alice,
                 :rdf/type [:ex/User],
                 :schema/name "Alice",
                 :schema/email "alice@flur.ee",
                 :schema/age 42}
                {:id :ex/alice,
                 :rdf/type [:ex/User],
                 :schema/name "Alice",
                 :schema/email "alice@flur.ee",
                 :schema/age 42}
                {:id :ex/alice,
                 :rdf/type [:ex/User],
                 :schema/name "Alice",
                 :schema/email "alice@flur.ee",
                 :schema/age 42}
                {:id :schema/age}
                {:id :schema/email}
                {:id :schema/name}
                {:id :ex/User, :rdf/type [:rdfs/Class]}
                {:id :ex/User, :rdf/type [:rdfs/Class]}
                {:id :rdfs/Class}
                {:id :rdf/type}
                {:id :id}]
               @(fluree/query db {:select {'?s ["*"]}
                                  :where  [['?s '?p '?o]]}))
            "Every triple should be returned.")
        (let [db* @(fluree/commit! ledger db)]
          (is (= [[:ex/jane :id "http://example.org/ns/jane"]
                  [:ex/jane :rdf/type :ex/User]
                  [:ex/jane :schema/name "Jane"]
                  [:ex/jane :schema/email "jane@flur.ee"]
                  [:ex/jane :schema/age 30]
                  [:ex/bob :id "http://example.org/ns/bob"]
                  [:ex/bob :rdf/type :ex/User]
                  [:ex/bob :schema/name "Bob"]
                  [:ex/bob :schema/age 22]
                  [:ex/alice :id "http://example.org/ns/alice"]
                  [:ex/alice :rdf/type :ex/User]
                  [:ex/alice :schema/name "Alice"]
                  [:ex/alice :schema/email "alice@flur.ee"]
                  [:ex/alice :schema/age 42]
                  ["did:fluree:TfCzWTrXqF16hvKGjcYiLxRoYJ1B8a6UMH6" :id "did:fluree:TfCzWTrXqF16hvKGjcYiLxRoYJ1B8a6UMH6"]
                  ["fluree:db:sha256:beh3as6tvb6shyvxs5w4zdt4gbpaa7xterlvqe42sc7r6u625ank" :id "fluree:db:sha256:beh3as6tvb6shyvxs5w4zdt4gbpaa7xterlvqe42sc7r6u625ank"]
                  ["fluree:db:sha256:beh3as6tvb6shyvxs5w4zdt4gbpaa7xterlvqe42sc7r6u625ank" :f/address "fluree:memory://2921503e8e62f47b598d4504f990a25bc7f7b841c11367599d87884d4bf5f0f8"]
                  ["fluree:db:sha256:beh3as6tvb6shyvxs5w4zdt4gbpaa7xterlvqe42sc7r6u625ank" :f/flakes 25]
                  ["fluree:db:sha256:beh3as6tvb6shyvxs5w4zdt4gbpaa7xterlvqe42sc7r6u625ank" :f/size 1888]
                  ["fluree:db:sha256:beh3as6tvb6shyvxs5w4zdt4gbpaa7xterlvqe42sc7r6u625ank" :f/t 1]
                  [:schema/age :id "http://schema.org/age"]
                  [:schema/email :id "http://schema.org/email"]
                  [:schema/name :id "http://schema.org/name"]
                  [:ex/User :id "http://example.org/ns/User"]
                  [:ex/User :rdf/type :rdfs/Class]
                  [:rdfs/Class :id "http://www.w3.org/2000/01/rdf-schema#Class"]
                  [:rdf/type :id "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"]
                  [:f/t :id "https://ns.flur.ee/ledger#t"]
                  [:f/size :id "https://ns.flur.ee/ledger#size"]
                  [:f/flakes :id "https://ns.flur.ee/ledger#flakes"]
                  [:f/context :id "https://ns.flur.ee/ledger#context"]
                  [:f/branch :id "https://ns.flur.ee/ledger#branch"]
                  [:f/alias :id "https://ns.flur.ee/ledger#alias"]
                  [:f/data :id "https://ns.flur.ee/ledger#data"]
                  [:f/address :id "https://ns.flur.ee/ledger#address"]
                  [:f/v :id "https://ns.flur.ee/ledger#v"]
                  ["https://www.w3.org/2018/credentials#issuer" :id "https://www.w3.org/2018/credentials#issuer"]
                  [:f/time :id "https://ns.flur.ee/ledger#time"]
                  [:f/message :id "https://ns.flur.ee/ledger#message"]
                  [:f/previous :id "https://ns.flur.ee/ledger#previous"]
                  [:id :id "@id"]
                  ["fluree:commit:sha256:bbjjp52yfifntgso6dtpbzynb3qdyjxb3kwiykksiwei45izv2up3" :id "fluree:commit:sha256:bbjjp52yfifntgso6dtpbzynb3qdyjxb3kwiykksiwei45izv2up3"]
                  ["fluree:commit:sha256:bbjjp52yfifntgso6dtpbzynb3qdyjxb3kwiykksiwei45izv2up3" :f/time 720000]
                  ["fluree:commit:sha256:bbjjp52yfifntgso6dtpbzynb3qdyjxb3kwiykksiwei45izv2up3" "https://www.w3.org/2018/credentials#issuer" "did:fluree:TfCzWTrXqF16hvKGjcYiLxRoYJ1B8a6UMH6"]
                  ["fluree:commit:sha256:bbjjp52yfifntgso6dtpbzynb3qdyjxb3kwiykksiwei45izv2up3" :f/v 0]
                  ["fluree:commit:sha256:bbjjp52yfifntgso6dtpbzynb3qdyjxb3kwiykksiwei45izv2up3" :f/address "fluree:memory://1b766e9d594d0afb754aeb7b9bc62158ff319273552a0b7fda9bb77b001e8acb"]
                  ["fluree:commit:sha256:bbjjp52yfifntgso6dtpbzynb3qdyjxb3kwiykksiwei45izv2up3" :f/data "fluree:db:sha256:beh3as6tvb6shyvxs5w4zdt4gbpaa7xterlvqe42sc7r6u625ank"]
                  ["fluree:commit:sha256:bbjjp52yfifntgso6dtpbzynb3qdyjxb3kwiykksiwei45izv2up3" :f/alias "query/everything"]
                  ["fluree:commit:sha256:bbjjp52yfifntgso6dtpbzynb3qdyjxb3kwiykksiwei45izv2up3" :f/branch "main"]
                  ["fluree:commit:sha256:bbjjp52yfifntgso6dtpbzynb3qdyjxb3kwiykksiwei45izv2up3" :f/context "fluree:memory://b6dcf8968183239ecc7a664025f247de5b7859ac18cdeaace89aafc421eeddee"]]
                 @(fluree/query db* {:select ['?s '?p '?o]
                                     :where  [['?s '?p '?o]]}))))))))

(deftest ^:integration illegal-reference-test
  (testing "Illegal reference queries"
    (let [conn   (test-utils/create-conn)
          people (test-utils/load-people conn)
          db     (fluree/db people)]
      (testing "with non-string objects"
        (let [test-subject @(fluree/query db {:select ['?s '?p]
                                              :where [['?s '?p 22]]})]
          (is (util/exception? test-subject)
              "return errors")
          (is (= :db/invalid-query
                 (-> test-subject ex-data :error))
              "have 'invalid query' error codes")))
      (testing "with string objects"
        (let [test-subject @(fluree/query db {:select ['?s '?p]
                                              :where [['?s '?p "Bob"]]})]
          (is (util/exception? test-subject)
              "return errors")
          (is (= :db/invalid-query
                 (-> test-subject ex-data :error))
              "have 'invalid query' error codes"))))))

(deftest ^:integration class-queries
  (let [conn   (test-utils/create-conn)
        ledger @(fluree/create conn "query/class" {:default-context ["" {:ex "http://example.org/ns/"}]})
        db     @(fluree/stage
                  (fluree/db ledger)
                  [{:id           :ex/alice,
                    :type         :ex/User,
                    :schema/name  "Alice"
                    :schema/email "alice@flur.ee"
                    :schema/age   42}
                   {:id          :ex/bob,
                    :type        :ex/User,
                    :schema/name "Bob"
                    :schema/age  22}
                   {:id           :ex/jane,
                    :type         :ex/User,
                    :schema/name  "Jane"
                    :schema/email "jane@flur.ee"
                    :schema/age   30}
                   {:id          :ex/dave
                    :type        :ex/nonUser
                    :schema/name "Dave"}])]
    (testing "rdf/type"
      (is (= [[:ex/User]]
             @(fluree/query db '{:select [?class]
                                 :where  [[:ex/jane :rdf/type ?class]]})))
      (is (= [[:ex/dave :ex/nonUser]
              [:ex/jane :ex/User]
              [:ex/bob :ex/User]
              [:ex/alice :ex/User]
              [:ex/nonUser :rdfs/Class]
              [:ex/User :rdfs/Class]]
             @(fluree/query db '{:select [?s ?class]
                                 :where  [[?s :rdf/type ?class]]}))))
    (testing "shacl targetClass"
      (let [shacl-db @(fluree/stage
                        (fluree/db ledger)
                        {:context        {:ex "http://example.org/ns/"}
                         :id             :ex/UserShape,
                         :type           [:sh/NodeShape],
                         :sh/targetClass :ex/User
                         :sh/property    [{:sh/path     :schema/name
                                           :sh/datatype :xsd/string}]})]
        (is (= [[:ex/User]]
               @(fluree/query shacl-db '{:select [?class]
                                         :where  [[:ex/UserShape :sh/targetClass ?class]]})))))))
