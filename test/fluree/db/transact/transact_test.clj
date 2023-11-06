(ns fluree.db.transact.transact-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [fluree.db.did :as did]
            [fluree.db.json-ld.api :as fluree]
            [fluree.db.test-utils :as test-utils]
            [fluree.db.util.core :as util]
            [jsonista.core :as json]
            [test-with-files.tools :refer [with-tmp-dir]]))

(deftest ^:integration staging-data
  (testing "Disallow staging invalid transactions"
    (let [conn             (test-utils/create-conn)
          ledger           @(fluree/create conn "tx/disallow" {:defaultContext ["" {:ex "http://example.org/ns/"}]})

          stage-id-only    (try
                             @(fluree/stage
                               (fluree/db ledger)
                               {:id :ex/alice})
                             (catch Exception e e))
          stage-empty-txn  (try
                             @(fluree/stage
                               (fluree/db ledger)
                               {})
                             (catch Exception e e))
          stage-empty-node (try
                             @(fluree/stage
                               (fluree/db ledger)
                               [{:id         :ex/alice
                                 :schema/age 42}
                                {}])
                             (catch Exception e e))
          db-ok            @(fluree/stage
                             (fluree/db ledger)
                             {:id         :ex/alice
                              :schema/age 42})]
      (is (util/exception? stage-id-only))
      (is (str/starts-with? (ex-message stage-id-only)
                            "Invalid transaction, transaction node contains no properties for @id:"))
      (is (util/exception? stage-empty-txn))
      (is (= (ex-message stage-empty-txn)
             "Invalid transaction, transaction node contains no properties."))
      (is (util/exception? stage-empty-node))
      (is (= (ex-message stage-empty-node)
             "Invalid transaction, transaction node contains no properties."))
      (is (= [[:ex/alice :id "http://example.org/ns/alice"]
              [:ex/alice :schema/age 42]
              [:schema/age :id "http://schema.org/age"]
              [:rdfs/Class :id "http://www.w3.org/2000/01/rdf-schema#Class"]
              [:type :id "@type"]
              [:id :id "@id"]]
             @(fluree/query db-ok '{:select [?s ?p ?o]
                                    :where  {:id ?s
                                             ?p ?o}})))))

  (testing "Allow transacting `false` values"
    (let [conn    (test-utils/create-conn)
          ledger  @(fluree/create conn "tx/bools"
                                  {:defaultContext
                                   ["" {:ex "http://example.org/ns/"}]})
          db-bool @(fluree/stage
                    (fluree/db ledger)
                    {:id        :ex/alice
                     :ex/isCool false})]
      (is (= [[:ex/alice :id "http://example.org/ns/alice"]
              [:ex/alice :ex/isCool false]
              [:ex/isCool :id "http://example.org/ns/isCool"]
              [:rdfs/Class :id "http://www.w3.org/2000/01/rdf-schema#Class"]
              [:type :id "@type"]
              [:id :id "@id"]]
             @(fluree/query db-bool '{:select [?s ?p ?o]
                                      :where  {:id ?s, ?p ?o}})))))

  (testing "mixed data types (ref & string) are handled correctly"
    (let [conn   (test-utils/create-conn)
          ledger @(fluree/create conn "tx/mixed-dts"
                                 {:defaultContext
                                  ["" {:ex "http://example.org/ns/"}]})
          db     @(fluree/stage (fluree/db ledger)
                                {:id               :ex/brian
                                 :ex/favCoffeeShop [:wiki/Q37158
                                                    "Clemmons Coffee"]})
          _db    @(fluree/commit! ledger db)
          loaded (test-utils/retry-load conn "tx/mixed-dts" 100)
          db     (fluree/db loaded)
          query  '{:select {:ex/brian [:*]}}]
      (is (= [{:id               :ex/brian
               :ex/favCoffeeShop [{:id :wiki/Q37158} "Clemmons Coffee"]}]
             @(fluree/query db query)))))

  (testing "mixed data types (num & string) are handled correctly"
    (let [conn   (test-utils/create-conn)
          ledger @(fluree/create conn "tx/mixed-dts"
                                 {:defaultContext
                                  ["" {:ex "http://example.org/ns/"}]})
          db     @(fluree/stage (fluree/db ledger)
                                {:id :ex/wes
                                 :ex/aFewOfMyFavoriteThings
                                 {"@list" [2011 "jabalí"]}})
          _db    @(fluree/commit! ledger db)
          loaded (test-utils/retry-load conn "tx/mixed-dts" 100)
          db     (fluree/db loaded)
          query  '{:select {:ex/wes [:*]}}]
      (is (= [{:id                        :ex/wes
               :ex/aFewOfMyFavoriteThings [2011 "jabalí"]}]
             @(fluree/query db query)))))

  (testing "mixed data types (ref & string) are handled correctly"
    (let [conn   (test-utils/create-conn)
          ledger @(fluree/create conn "tx/mixed-dts"
                                 {:defaultContext
                                  ["" {:ex "http://example.org/ns/"}]})
          db     @(fluree/stage (fluree/db ledger)
                                {:id               :ex/brian
                                 :ex/favCoffeeShop [:wiki/Q37158
                                                    "Clemmons Coffee"]})
          _db    @(fluree/commit! ledger db)
          loaded (test-utils/retry-load conn "tx/mixed-dts" 100)
          db     (fluree/db loaded)
          query  '{:select {:ex/brian [:*]}}]
      (is (= [{:id               :ex/brian
               :ex/favCoffeeShop [{:id :wiki/Q37158} "Clemmons Coffee"]}]
             @(fluree/query db query)))))

  (testing "mixed data types (num & string) are handled correctly"
    (let [conn   (test-utils/create-conn)
          ledger @(fluree/create conn "tx/mixed-dts"
                                 {:defaultContext
                                  ["" {:ex "http://example.org/ns/"}]})
          db     @(fluree/stage (fluree/db ledger)
                                {:id :ex/wes
                                 :ex/aFewOfMyFavoriteThings
                                 {"@list" [2011 "jabalí"]}})
          _db    @(fluree/commit! ledger db)
          loaded (test-utils/retry-load conn "tx/mixed-dts" 100)
          db     (fluree/db loaded)
          query  '{:select {:ex/wes [:*]}}]
      (is (= [{:id                        :ex/wes
               :ex/aFewOfMyFavoriteThings [2011 "jabalí"]}]
             @(fluree/query db query))))))

(deftest policy-ordering-test
  (testing "transaction order does not affect query results"
    (let [conn            (test-utils/create-conn)
          ledger          @(fluree/create conn "tx/policy-order" {:defaultContext ["" {:ex "http://example.org/ns/"}]})
          alice-did       (:id (did/private->did-map "c0459840c334ca9f20c257bed971da88bd9b1b5d4fca69d4e3f4b8504f981c07"))
          data            [{:id          :ex/alice,
                            :type        :ex/User,
                            :schema/name "Alice"}
                           {:id          :ex/john,
                            :type        :ex/User,
                            :schema/name "John"}
                           {:id      alice-did
                            :ex/user :ex/alice
                            :f/role  :ex/userRole}]
          policy          [{:id            :ex/UserPolicy,
                            :type          [:f/Policy],
                            :f/targetClass :ex/User
                            :f/allow       [{:id           :ex/globalViewAllow
                                             :f/targetRole :ex/userRole
                                             :f/action     [:f/view]}]}]
          db-data-first   @(fluree/stage
                            (fluree/db ledger)
                            (into data policy))
          db-policy-first @(fluree/stage
                            (fluree/db ledger)
                            (into policy data))
          user-query      '{:select {?s [:*]}
                            :where  {:id ?s, :type :ex/User}}]
      (let [users [{:id :ex/john, :type :ex/User, :schema/name "John"}
                   {:id :ex/alice, :type :ex/User, :schema/name "Alice"}]]
        (is (= users
               @(fluree/query db-data-first user-query)))
        (is (= users
               @(fluree/query db-policy-first user-query)))))))

(deftest ^:integration transact-large-dataset-test
  (with-tmp-dir storage-path
    (testing "can transact a big movies dataset w/ SHACL constraints"
      (let [shacl   (-> "movies2-schema.json" io/resource json/read-value)
            movies  (-> "movies2.json" io/resource json/read-value)
            ;; TODO: Once :method :memory supports indexing, switch to that.
            conn    @(fluree/connect {:method       :file
                                      :storage-path storage-path
                                      :defaults
                                      {:context test-utils/default-str-context}})
            ledger  @(fluree/create conn "movies2"
                                    {:defaultContext
                                     ["" {"ex" "https://example.com/"}]})
            db0     @(fluree/stage (fluree/db ledger) shacl)
            _       (assert (not (util/exception? db0)))
            db1     @(fluree/commit! ledger db0)
            _       (assert (not (util/exception? db1)))
            db2     @(fluree/stage db0 movies)
            _       (assert (not (util/exception? db2)))
            query   {"select" "?title"
                     "where"  {"@id"      "?m"
                               "type"     "ex:Movie"
                               "ex:title" "?title"}}
            results @(fluree/query db2 query)]
        (is (= 100 (count results)))
        (is (every? (set results)
                    ["Interstellar" "Wreck-It Ralph" "The Jungle Book" "WALL·E"
                     "Iron Man" "Avatar"]))))))

(deftest ^:integration transact-api-test
  (let [conn        (test-utils/create-conn {:context
                                             (dissoc test-utils/default-context
                                                     :f)})
        ledger-name "example-ledger"
        ledger      @(fluree/create conn ledger-name
                                    {:defaultContext
                                     ["" {:ex "http://example.org/ns/"}]})
        ;; can't `transact!` until ledger can be loaded (ie has at least one commit)
        db          @(fluree/stage (fluree/db ledger)
                                   {:id   :ex/firstTransaction
                                    :type :ex/Nothing})
        _           @(fluree/commit! ledger db)
        user-query  '{:select {?s [:*]}
                      :where  {:id ?s, :type :ex/User}}]
    (testing "Top-level context is used for transaction nodes"
      (let [txn {:f/ledger ledger-name
                 :context  {:f     "https://ns.flur.ee/ledger#"
                            :foo   "http://foo.com/"
                            :id    "@id"
                            :graph "@graph"}
                 :graph    [{:id          :ex/alice
                             :type        :ex/User
                             :foo/bar     "foo"
                             :schema/name "Alice"}
                            {:id          :ex/bob
                             :type        :ex/User
                             :foo/baz     "baz"
                             :schema/name "Bob"}]}
            db  @(fluree/transact! conn txn nil)]
        (is (= [{:id          :ex/bob,
                 :type        :ex/User,
                 :schema/name "Bob",
                 :foo/baz     "baz"}
                {:id          :ex/alice,
                 :type        :ex/User,
                 :foo/bar     "foo",
                 :schema/name "Alice"}]
               @(fluree/query db (assoc user-query
                                   :context ["" {:foo "http://foo.com/"}]))))))
    (testing "Aliased @id, @graph are correctly identified"
      (let [txn {:context     {:id-alias    "@id"
                               :graph-alias "@graph"
                               :f           "https://ns.flur.ee/ledger#"}
                 :f/ledger    ledger-name
                 :graph-alias {:id-alias         :ex/alice
                               :schema/givenName "Alicia"}}
            db  @(fluree/transact! conn txn nil)]
        (is (= [{:id          :ex/bob,
                 :type        :ex/User,
                 :schema/name "Bob",
                 :foo/baz     "baz"}
                {:id               :ex/alice,
                 :type             :ex/User,
                 :schema/name      "Alice",
                 :foo/bar          "foo",
                 :schema/givenName "Alicia"}]
               @(fluree/query db (assoc user-query
                                   :context ["" {:foo "http://foo.com/"
                                                 :bar "http://bar.com/"}]))))))
    (testing "@context inside node is correctly handled"
      (let [txn {:context  {:f "https://ns.flur.ee/ledger#"}
                 :f/ledger ledger-name
                 "@graph"  [{:context    {:quux "http://quux.com/"}
                             :id         :ex/alice
                             :quux/corge "grault"}]}
            db  @(fluree/transact! conn txn nil)]
        (is (= [{:id          :ex/bob
                 :type        :ex/User
                 :schema/name "Bob"
                 :foo/baz     "baz"}
                {:id               :ex/alice
                 :type             :ex/User
                 :schema/name      "Alice"
                 :schema/givenName "Alicia"
                 :quux/corge       "grault"
                 :foo/bar          "foo"}]
               @(fluree/query db (assoc user-query
                                   :context ["" {:foo  "http://foo.com/"
                                                 :bar  "http://bar.com/"
                                                 :quux "http://quux.com/"}]))))))
    (testing "conn default context is inherited when requested (and not o/w)"
      (let [txn1 {:context  ["" {:f "https://ns.flur.ee/ledger#"}]
                  :f/ledger ledger-name
                  :graph    [{:id      :ex/foo
                              :type    :ex/Person
                              :ex/name "Foo"}]}
            txn2 {:context  {:f "https://ns.flur.ee/ledger#"}
                  :f/ledger ledger-name
                  :graph    [{:id      :ex/bar
                              :type    :ex/Person
                              :ex/name "Bar"}]}
            db1  @(fluree/transact! conn txn1 nil)]
        (is (= [{:id :ex/foo, :type :ex/Person, :ex/name "Foo"}]
               @(fluree/query db1 '{:select {?p [:*]}
                                    :where  {:id ?p, :type :ex/Person}})))
        (is (thrown-with-msg? Exception #"Invalid compact-iri"
                              @(fluree/transact! conn txn2 nil)))))
    (testing "Throws on invalid txn"
      (let [txn {"@graph" [{:context    {:quux "http://quux.com/"}
                            :id         :ex/cam
                            :quux/corge "grault"}]}]
        (is (thrown-with-msg? Exception
                              #"Invalid transaction, missing required keys: .+ledger"
                              @(fluree/transact! conn txn nil)))))))

(deftest ^:integration base-and-vocab-test
  (testing "@base & @vocab work w/ stage"
    (let [conn        @(fluree/connect {:method :memory})
          ctx         {"@base"  "http://example.org/"
                       "@vocab" "http://example.org/terms/"
                       "f"      "https://ns.flur.ee/ledger#"}
          ledger-name "cookbook/base"
          txn         {"@context" ctx
                       "f:ledger" ledger-name
                       "@graph"   [{"@id"     "nessie"
                                    "@type"   "SeaMonster"
                                    "isScary" false}]}
          ledger      @(fluree/create conn ledger-name)
          db0         (fluree/db ledger)
          db1         @(fluree/stage db0 txn)]
      (is (= [{"@id"                              "http://example.org/nessie"
               "@type"                            "http://example.org/terms/SeaMonster"
               "http://example.org/terms/isScary" false}]
             @(fluree/query db1 {"@context" nil
                                 "select"   '{?m ["*"]}
                                 "where"    '{"@id" ?m
                                              "@type" "http://example.org/terms/SeaMonster"}})))))
  (testing "@base & @vocab work w/ stage2"
    (let [conn        @(fluree/connect {:method :memory})
          ctx         ["https://ns.flur.ee"
                       {"@base"  "http://example.org/"
                        "@vocab" "http://example.org/terms/"}]
          ledger-name "cookbook/base"
          txn         {"@context" ctx
                       "ledger"   ledger-name
                       "insert"   {"@id"     "nessie"
                                   "@type"   "SeaMonster"
                                   "isScary" false}}
          ledger      @(fluree/create conn ledger-name)
          db0         (fluree/db ledger)
          db1         @(fluree/stage2 db0 txn)]
      (is (= [{"@id"                              "http://example.org/nessie"
               "@type"                            "http://example.org/terms/SeaMonster"
               "http://example.org/terms/isScary" false}]

             @(fluree/query db1 '{"@context" nil
                                  "select"   {?m ["*"]}
                                  "where"    {"@id" ?m
                                              "@type" "http://example.org/terms/SeaMonster"}}))))))

(deftest json-objects
  (testing "Allow transacting `json` values"
    (let [conn   @(fluree/connect {:method :memory})
          ledger @(fluree/create conn "jsonpls" {:defaultContext [test-utils/default-str-context
                                                                  {"ex" "http://example.org/ns/"}]})
          db0    (fluree/db ledger)
          db1    @(fluree/stage2
                    db0
                    {"@context" "https://ns.flur.ee"
                     "insert"
                     [{"@id"     "ex:alice"
                       "@type"   "ex:Person"
                       "ex:json" {"@type"  "@json"
                                  "@value" {"json" "data"
                                            "is"   ["cool" "right?" 1 false 1.0]}}}
                      {"@id"     "ex:bob"
                       "@type"   "ex:Person"
                       "ex:json" {"@type"  "@json"
                                  "@value" {:edn "data"
                                            :is  ["cool" "right?" 1 false 1.0]}}}]})]
      (is (= #{{"id"     "ex:bob",
                "type"   "ex:Person",
                "ex:json" {":edn" "data", ":is" ["cool" "right?" 1 false 1]}}
               {"id"     "ex:alice",
                "type"   "ex:Person",
                "ex:json" {"json" "data", "is" ["cool" "right?" 1 false 1]}}}
             (into #{} @(fluree/query db1 {"where"  {"@id" "?s" "@type" "ex:Person"}
                                           "select" {"?s" ["*"]}})))
          "comes out as data from subject crawl")
      (is (= #{{":edn" "data", ":is" ["cool" "right?" 1 false 1]}
               {"json" "data", "is" ["cool" "right?" 1 false 1]}}
             (into #{} @(fluree/query db1 {"@context" {"ex" "http://example.org/ns/"}
                                           "select"   "?json"
                                           "where"  {"@id" "?s" "ex:json" "?json"}})))
          "comes out as data from select clause"))))

(deftest ^:integration no-where-solutions
  (let [conn   @(fluree/connect {:method :memory})
        ledger @(fluree/create conn "insert-delete")
        db0    (fluree/db ledger)

        db1 @(fluree/stage2 db0 {"@context" "https://ns.flur.ee"
                                 "insert"   [{"@id" "ex:andrew" "schema:name" "Andrew"}]})

        db2 @(fluree/stage2 db1 {"@context" "https://ns.flur.ee"
                                 "where"    {"@id"                "ex:andrew"
                                             "schema:description" "?o"}
                                 "delete"   {"@id"                "ex:andrew"
                                             "schema:description" "?o"}
                                 "insert"   {"@id"                "ex:andrew"
                                             "schema:description" "He's great!"}})]
    (is (= {"@id"                "ex:andrew"
            "schema:name"        "Andrew"
            "schema:description" "He's great!"}
           @(fluree/query db2 {"selectOne" {"ex:andrew" ["*"]}})))))

(deftest ^:integration shacl-datatype-coercion
  (let [conn   @(fluree/connect {:method :memory})
        ledger-id "sh-datatype"
        ledger @(fluree/create conn ledger-id {:defaultContext
                                               {"ex" "http://example.org/",
                                                "f" "https://ns.flur.ee/ledger#",
                                                "rdf" "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
                                                "rdfs" "http://www.w3.org/2000/01/rdf-schema#",
                                                "schema" "http://schema.org/",
                                                "sh" "http://www.w3.org/ns/shacl#",
                                                "xsd" "http://www.w3.org/2001/XMLSchema#"}})

        db0 (fluree/db ledger)
        db1 @(fluree/stage2 db0 {"@context" "https://ns.flur.ee",
                                 "ledger"   ledger-id
                                 "insert"   {"@id"            "ex:NodeShape/Yeti",
                                             "@type"          "sh:NodeShape",
                                             "sh:targetClass" {"@id" "ex:Yeti"},
                                             "sh:property"    [{"@id"         "ex:PropertyShape/age",
                                                                "sh:path"     {"@id" "schema:age"},
                                                                "sh:datatype" {"@id" "xsd:integer"}}]}})


        db2 @(fluree/stage2 db1 {"@context" "https://ns.flur.ee",
                                 "ledger"   ledger-id
                                 "insert"   {"@id"         "ex:freddy",
                                             "@type"       "ex:Yeti",
                                             "schema:name" "Freddy",
                                             "schema:age"  8}})

        _ @(fluree/commit! ledger db2)
        loaded @(fluree/load conn ledger-id)

        db3 @(fluree/stage2 (fluree/db loaded) {"@context" "https://ns.flur.ee",
                                                "ledger" ledger-id
                                                "insert" {"@id" "ex:letti",
                                                          "@type" "ex:Yeti",
                                                          "schema:name" "Letti",
                                                          "schema:age" "alot"}})]
    (is (= {"schema:age" 8}
           @(fluree/query db2 {"selectOne" {"ex:freddy" ["schema:age"]}}))
        "8 is converted from a long to an int.")
    (is (= "Value alot cannot be coerced to provided datatype: 7."
           (ex-message db3))
        "datatype constraint is restored after a load")))
