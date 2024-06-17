(ns fluree.db.query.values-test
  (:require  [clojure.test :as t :refer [deftest testing is]]
             [fluree.db.json-ld.api :as fluree]
             [fluree.db.test-utils :as test-utils]))

(deftest values
  (let [conn    @(fluree/connect {:method :memory})

        context ["https://flur.ee"
                 test-utils/default-str-context
                 {"ex" "http://example.com/"}]
        db1     @(fluree/create-with-txn conn
                                         {"@context" context
                                          "ledger" "values-test"
                                          "insert" (into test-utils/people-strings
                                                         [{"@id" "ex:nikola"
                                                           "schema:name" "Nikola"
                                                           "ex:greeting" [{"@value" "Здраво" "@language" "sb"}
                                                                          {"@value" "Hello" "@language" "en"}]
                                                           "ex:birthday" {"@value" "2000-01-01"
                                                                          "@type" "xsd:datetime"}
                                                           "ex:cool" true}])})]
    (testing "clause"
      (testing "no where clause"
        (testing "multiple vars"
          (is (= [["foo1" "bar1"] ["foo2" "bar2"] ["foo3" "bar3"]]
                 @(fluree/query db1 {"select" ["?foo" "?bar"]
                                     "values" [["?foo" "?bar"]
                                               [["foo1" "bar1"]
                                                ["foo2" "bar2"]
                                                ["foo3" "bar3"]]]}))))
        (testing "single var"
          (is (= [["foo1"] ["foo2"] ["foo3"]]
                 @(fluree/query db1 {"select" ["?foo"]
                                     "values" ["?foo" ["foo1" "foo2" "foo3" ]]}))))))
    (testing "pattern"
      (testing "single var"
        (is (= [["Brian" "brian@example.org"]
                ["Cam" "cam@example.org"]]
               @(fluree/query db1 {"@context" context
                                   "select" ["?name" "?email"]
                                   "where" [{"@id" "?s" "schema:name" "?name"}
                                            {"@id" "?s" "schema:email" "?email"}
                                            ["values"
                                             ["?s" [{"@type" "xsd:anyURI" "@value" "ex:cam"}
                                                    {"@type" "xsd:anyURI" "@value" "ex:brian"}]]]]}))))
      (testing "nested scope"
        (is (= [["Nikola" nil true]]
               @(fluree/query db1 {"@context" context
                                   "select" ["?name" "?age" "?cool"]
                                   "where" [["optional"
                                             [{"@id" "?s" "schema:name" "?name"
                                               "ex:cool" "?cool"}
                                              ["values"
                                               ["?s" [{"@type" "xsd:anyURI" "@value" "ex:nikola"}]]]]]]}))))
      (testing "federated"
        (let [db3 @(fluree/create-with-txn conn
                                           {"@context" context
                                            "ledger" "other-ledger"
                                            "insert" [{"@id" "ex:khris"
                                                       "schema:name" "Khris"}]})]
          (is (= [["Khris"] ["Nikola"]]
                 @(fluree/query-connection conn
                                           {"@context" context
                                            :from ["values-test" "other-ledger"]
                                            "select" ["?name"]
                                            "where" [{"@id" "?s" "schema:name" "?name"}
                                                     ["values"
                                                      ["?s" [{"@type" "xsd:anyURI" "@value" "ex:nikola"}
                                                             {"@type" "xsd:anyURI" "@value" "ex:khris"}]]]]
                                            ;; federated queries async/merge solutions from different
                                            ;; graphs nondeterministically
                                            "orderBy" "?name"})))))
      (testing "match meta"
        (is (= ["ex:nikola"]
               @(fluree/query db1 {"@context" context
                                   "select" "?s"
                                   "where" [{"@id" "?s" "ex:greeting" "?greet"}
                                            ["values"
                                             ["?greet" [{"@value" "Здраво" "@language" "sb"}]]]]}))
            "language tag")))))
