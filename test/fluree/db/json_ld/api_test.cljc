(ns fluree.db.json-ld.api-test
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing async]])
            #?@(:cljs [[clojure.core.async :refer [go <!]]
                       [clojure.core.async.interop :refer [<p!]]])
            [fluree.db.dbproto :as dbproto]
            [fluree.db.json-ld.api :as fluree]
            [fluree.db.query.range :as query-range]
            [fluree.db.test-utils :as test-utils]
            [fluree.db.util.async :refer [<?? <?]]
            [fluree.db.util.context :as ctx-util]
            [fluree.db.util.core :as util]
            #?(:clj  [test-with-files.tools :refer [with-tmp-dir]
                      :as twf]
               :cljs [test-with-files.tools :as-alias twf])))

(deftest exists?-test
  (testing "returns false before committing data to a ledger"
    #?(:clj
       (let [conn         (test-utils/create-conn)
             ledger-alias "testledger"
             check1       @(fluree/exists? conn ledger-alias)
             ledger       @(fluree/create conn ledger-alias)
             check2       @(fluree/exists? conn ledger-alias)
             _            @(fluree/stage (fluree/db ledger)
                                         [{:id           :f/me
                                           :type         :schema/Person
                                           :schema/fname "Me"}])
             check3       @(fluree/exists? conn ledger-alias)]
         (is (every? false? [check1 check2 check3])))))
  (testing "returns true after committing data to a ledger"
    #?(:clj
       (let [conn         (test-utils/create-conn)
             ledger-alias "testledger"
             ledger       @(fluree/create conn ledger-alias)
             db           @(fluree/stage (fluree/db ledger)
                                         [{:id           :f/me
                                           :type         :schema/Person
                                           :schema/fname "Me"}])]
         @(fluree/commit! ledger db)
         (is (test-utils/retry-exists? conn ledger-alias 100))
         (is (not @(fluree/exists? conn "notaledger"))))

       :cljs
       (async done
         (go
          (let [conn         (<! (test-utils/create-conn))
                ledger-alias "testledger"
                ledger       (<p! (fluree/create conn ledger-alias))
                db           (<p! (fluree/stage (fluree/db ledger)
                                                [{:id           :f/me
                                                  :type         :schema/Person
                                                  :schema/fname "Me"}]))]
            (<p! (fluree/commit! ledger db))
            (is (test-utils/retry-exists? conn ledger-alias 100))
            (is (not (<p! (fluree/exists? conn "notaledger"))))
            (done)))))))

(deftest create-test
  (testing "string ledger context gets correctly merged with keyword conn context"
    #?(:clj
       (let [conn           (test-utils/create-conn)
             ledger-alias   "testledger"
             ledger-context {"ex"  "http://example.com/"
                             "foo" "http://foobar.com/"}
             ledger         @(fluree/create conn ledger-alias
                                            {:context-type   :string
                                             :defaultContext ["" ledger-context]})
             merged-context (merge (util/stringify-keys test-utils/default-context)
                                   ledger-context)]
         (is (= merged-context (dbproto/-default-context (fluree/db ledger))))))))

#?(:clj
   (deftest load-from-file-test
     (testing "can load a file ledger with single cardinality predicates"
       (with-tmp-dir storage-path
         (let [conn         @(fluree/connect
                              {:method :file :storage-path storage-path
                               :defaults
                               {:context      test-utils/default-context
                                :context-type :keyword}})
               ledger-alias "load-from-file-test-single-card"
               ledger       @(fluree/create conn ledger-alias {:defaultContext ["" {:ex "http://example.org/ns/"}]})
               db           @(fluree/stage
                              (fluree/db ledger)
                              [{:id           :ex/brian
                                :type         :ex/User
                                :schema/name  "Brian"
                                :schema/email "brian@example.org"
                                :schema/age   50
                                :ex/favNums   7
                                :ex/height    6.2}

                               {:id           :ex/cam
                                :type         :ex/User
                                :schema/name  "Cam"
                                :schema/email "cam@example.org"
                                :schema/age   34
                                :ex/favNums   5
                                :ex/friend    :ex/brian}])
               db           @(fluree/commit! ledger db)
               db           @(fluree/stage
                              db
                              {:id         :ex/brian
                               :ex/favNums 7})
               db           @(fluree/commit! ledger db)
               target-t     (:t db)
               ;; TODO: Replace this w/ :syncTo equivalent once we have it
               loaded       (test-utils/load-to-t conn ledger-alias target-t 100)
               loaded-db    (fluree/db loaded)]
           (is (= target-t (:t loaded-db)))
           (is (= (:context ledger) (:context loaded))))))

     (testing "can load a file ledger with multi-cardinality predicates"
       (with-tmp-dir storage-path
         (let [conn         @(fluree/connect
                              {:method :file :storage-path storage-path
                               :defaults
                               {:context      test-utils/default-context
                                :context-type :keyword}})
               ledger-alias "load-from-file-test-multi-card"
               ledger       @(fluree/create conn ledger-alias {:defaultContext ["" {:ex "http://example.org/ns/"}]})
               db           @(fluree/stage
                              (fluree/db ledger)
                              [{:id           :ex/brian
                                :type         :ex/User
                                :schema/name  "Brian"
                                :schema/email "brian@example.org"
                                :schema/age   50
                                :ex/favNums   7}

                               {:id           :ex/alice
                                :type         :ex/User
                                :schema/name  "Alice"
                                :schema/email "alice@example.org"
                                :schema/age   50
                                :ex/favNums   [42 76 9]}

                               {:id           :ex/cam
                                :type         :ex/User
                                :schema/name  "Cam"
                                :schema/email "cam@example.org"
                                :schema/age   34
                                :ex/favNums   [5 10]
                                :ex/friend    [:ex/brian :ex/alice]}])
               db           @(fluree/commit! ledger db)
               db           @(fluree/stage
                              db
                              ;; test a multi-cardinality retraction
                              [{:id         :ex/alice
                                :ex/favNums [42 76 9]}])
               db           @(fluree/commit! ledger db)
               target-t     (:t db)
               ;; TODO: Replace this w/ :syncTo equivalent once we have it
               loaded       (test-utils/load-to-t conn ledger-alias target-t 100)
               loaded-db    (fluree/db loaded)]
           (is (= target-t (:t loaded-db)))
           (is (= (dbproto/-default-context db) (dbproto/-default-context loaded-db))))))

     (testing "can load a file ledger with its own context"
       (with-tmp-dir storage-path #_{::twf/delete-dir false}
         #_(println "storage path:" storage-path)
         (let [conn-context   {:id  "@id", :type "@type"
                               :xsd "http://www.w3.org/2001/XMLSchema#"}
               ledger-context {:ex     "http://example.com/"
                               :schema "http://schema.org/"}
               conn           @(fluree/connect
                                {:method   :file :storage-path storage-path
                                 :defaults {:context      conn-context
                                            :context-type :keyword}})
               ledger-alias   "load-from-file-with-context"
               ledger         @(fluree/create conn ledger-alias
                                              {:defaultContext ["" ledger-context]})
               db             @(fluree/stage
                                (fluree/db ledger)
                                [{:id             :ex/wes
                                  :type           :ex/User
                                  :schema/name    "Wes"
                                  :schema/email   "wes@example.org"
                                  :schema/age     42
                                  :schema/favNums [1 2 3]
                                  :ex/friend      {:id           :ex/jake
                                                   :type         :ex/User
                                                   :schema/name  "Jake"
                                                   :schema/email "jake@example.org"}}])
               db             @(fluree/commit! ledger db)
               target-t       (:t db)
               loaded         (test-utils/load-to-t conn ledger-alias target-t
                                                    100)
               loaded-db      (fluree/db loaded)
               merged-ctx     (merge (ctx-util/stringify-context conn-context)
                                     (ctx-util/stringify-context ledger-context))
               query          {:where  '[[?p :schema/email "wes@example.org"]]
                               :select '{?p [:*]}}
               results        @(fluree/query loaded-db query)]
           (is (= target-t (:t loaded-db)))
           (is (= merged-ctx (dbproto/-default-context loaded-db)))
           (is (= [{:type   :ex/User
                    :id             :ex/wes
                    :schema/age     42
                    :schema/email   "wes@example.org"
                    :schema/favNums [1 2 3]
                    :schema/name    "Wes"
                    :ex/friend      {:id :ex/jake}}]
                  results)))))

     (testing "query returns the correct results from a loaded ledger"
       (with-tmp-dir storage-path
         (let [conn-context   {:id "@id", :type "@type"}
               ledger-context {:ex     "http://example.com/"
                               :schema "http://schema.org/"}
               conn           @(fluree/connect
                                {:method   :file :storage-path storage-path
                                 :defaults {:context      conn-context
                                            :context-type :keyword}})
               ledger-alias   "load-from-file-query"
               ledger         @(fluree/create conn ledger-alias
                                              {:defaultContext ["" ledger-context]})
               db             @(fluree/stage
                                (fluree/db ledger)
                                [{:id          :ex/Andrew
                                  :type        :schema/Person
                                  :schema/name "Andrew"
                                  :ex/friend   {:id          :ex/Jonathan
                                                :type        :schema/Person
                                                :schema/name "Jonathan"}}])
               query          {:select '{?s [:*]}
                               :where  '[[?s :id :ex/Andrew]]}
               res1           @(fluree/query db query)
               _              @(fluree/commit! ledger db)
               loaded         (test-utils/retry-load conn ledger-alias 100)
               loaded-db      (fluree/db loaded)
               res2           @(fluree/query loaded-db query)]
           (is (= res1 res2)))))

     (testing "conn context is not merged into ledger's on load"
       (with-tmp-dir storage-path #_{::twf/delete-dir false}
         #_(println "storage path:" storage-path)
         (let [conn1-context  {:id  "@id", :type "@type"
                               :xsd "http://www.w3.org/2001/XMLSchema#"
                               :foo "http://foo.com/"}
               ledger-context {:ex     "http://example.com/"
                               :schema "http://schema.org/"}
               conn1          @(fluree/connect
                                {:method   :file, :storage-path storage-path
                                 :defaults {:context      conn1-context
                                            :context-type :keyword}})
               ledger-alias   "load-from-file-with-context"
               ledger         @(fluree/create conn1 ledger-alias
                                              {:defaultContext ["" ledger-context]})
               db             @(fluree/stage
                                (fluree/db ledger)
                                [{:id             :ex/wes
                                  :type           :ex/User
                                  :schema/name    "Wes"
                                  :schema/email   "wes@example.org"
                                  :schema/age     42
                                  :schema/favNums [1 2 3]
                                  :ex/friend      {:id           :ex/jake
                                                   :type         :ex/User
                                                   :schema/name  "Jake"
                                                   :schema/email "jake@example.org"}}])
               db             @(fluree/commit! ledger db)
               target-t       (:t db)
               conn2-context  {:id  "@id", :type "@type"
                               :xsd "http://www.w3.org/2001/XMLSchema#"
                               :foo "http://foobar.com/"
                               :baz "http://baz.org/"}
               conn2          @(fluree/connect
                                {:method   :file, :storage-path storage-path
                                 :defaults {:context      conn2-context
                                            :context-type :keyword}})
               loaded         (test-utils/load-to-t conn2 ledger-alias target-t
                                                    100)
               loaded-db      (fluree/db loaded)
               merged-ctx     (merge (ctx-util/stringify-context conn1-context)
                                     (ctx-util/stringify-context ledger-context))]
           (is (= target-t (:t loaded-db)))
           (is (= merged-ctx (dbproto/-default-context loaded-db))))))

     (testing "can load a ledger with `list` values"
       (with-tmp-dir storage-path
         (let [conn         @(fluree/connect
                              {:method       :file
                               :storage-path storage-path
                               :defaults
                               {:context      (merge test-utils/default-context
                                                     {:ex "http://example.org/ns/"})
                                :context-type :keyword}})
               ledger-alias "load-lists-test"
               ledger       @(fluree/create conn ledger-alias)
               db           @(fluree/stage
                              (fluree/db ledger)
                              [{:id         :ex/alice,
                                :type       :ex/User,
                                :ex/friends {:list [:ex/john :ex/cam]}}
                               {:id         :ex/cam,
                                :type       :ex/User
                                :ex/numList {:list [7 8 9 10]}}
                               {:id   :ex/john,
                                :type :ex/User}])
               db           @(fluree/commit! ledger db)
               target-t     (:t db)
               loaded       (test-utils/load-to-t conn ledger-alias target-t 100)
               loaded-db    (fluree/db loaded)]
           (is (= target-t (:t loaded-db)))
           (testing "query returns expected `list` values"
             (is (= [{:id         :ex/cam,
                      :type   :ex/User,
                      :ex/numList [7 8 9 10]}
                     {:id :ex/john, :type :ex/User}
                     {:id         :ex/alice,
                      :type   :ex/User,
                      :ex/friends [{:id :ex/john} {:id :ex/cam}]}]
                    @(fluree/query loaded-db '{:select {?s [:*]}
                                               :where  [[?s :type :ex/User]]}))))))

       (testing "can load with policies"
         (with-tmp-dir storage-path
           (let [conn         @(fluree/connect
                                {:method       :file
                                 :storage-path storage-path
                                 :defaults
                                 {:context      (merge test-utils/default-context
                                                       {:ex "http://example.org/ns/"})
                                  :context-type :keyword}})
                 ledger-alias "load-policy-test"
                 ledger       @(fluree/create conn ledger-alias)
                 db           @(fluree/stage
                                (fluree/db ledger)
                                [{:id          :ex/alice,
                                  :type        :ex/User,
                                  :schema/name "Alice"
                                  :schema/ssn  "111-11-1111"
                                  :ex/friend   :ex/john}
                                 {:id          :ex/john,
                                  :schema/name "John"
                                  :type        :ex/User,
                                  :schema/ssn  "888-88-8888"}
                                 {:id      "did:fluree:123"
                                  :ex/user :ex/alice
                                  :f/role  :ex/userRole}])
                 db+policy    @(fluree/stage
                                db
                                [{:id            :ex/UserPolicy,
                                  :type          :f/Policy,
                                  :f/targetClass :ex/User
                                  :f/allow
                                  [{:id           :ex/globalViewAllow
                                    :f/targetRole :ex/userRole
                                    :f/action     [:f/view]}]
                                  :f/property
                                  [{:f/path  :schema/ssn
                                    :f/allow
                                    [{:id           :ex/ssnViewRule
                                      :f/targetRole :ex/userRole
                                      :f/action     [:f/view]
                                      :f/equals
                                      {:list [:f/$identity :ex/user]}}]}]}])
                 db+policy    @(fluree/commit! ledger db+policy)
                 target-t     (:t db+policy)
                 loaded       (test-utils/load-to-t conn ledger-alias target-t
                                                    100)
                 loaded-db    (fluree/db loaded)]
             (is (= target-t (:t loaded-db)))
             (testing "query returns expected policy"
               (is (= [{:id            :ex/UserPolicy,
                        :type      :f/Policy,
                        :f/allow
                        {:id           :ex/globalViewAllow,
                         :f/action     {:id :f/view},
                         :f/targetRole {:_id 211106232532995}},
                        :f/property
                        {:id "_:f211106232532999",
                         :f/allow
                         {:id           :ex/ssnViewRule,
                          :f/action     {:id :f/view},
                          :f/targetRole {:_id 211106232532995},
                          :f/equals     [{:id :f/$identity} {:id :ex/user}]},
                         :f/path {:id :schema/ssn}},
                        :f/targetClass {:id :ex/User}}]
                      @(fluree/query loaded-db
                                     '{:select
                                       {?s [:*
                                            {:type [:_id]}
                                            {:f/allow [:* {:f/targetRole [:_id]}]}
                                            {:f/property
                                             [:* {:f/allow
                                                  [:* {:f/targetRole [:_id]}]}]}]}
                                       :where  [[?s :type :f/Policy]]}))))))))))

#?(:clj
   (deftest load-from-memory-test
     (testing "can load a memory ledger with single cardinality predicates"
       (let [conn         @(fluree/connect
                            {:method :memory
                             :defaults
                             {:context      test-utils/default-context
                              :context-type :keyword}})
             ledger-alias "load-from-memory-test-single-card"
             ledger       @(fluree/create conn ledger-alias {:defaultContext ["" {:ex "http://example.org/ns/"}]})
             db           @(fluree/stage
                            (fluree/db ledger)
                            [{:id           :ex/brian
                              :type         :ex/User
                              :schema/name  "Brian"
                              :schema/email "brian@example.org"
                              :schema/age   50
                              :ex/favNums   7}

                             {:id           :ex/cam
                              :type         :ex/User
                              :schema/name  "Cam"
                              :schema/email "cam@example.org"
                              :schema/age   34
                              :ex/favNums   5
                              :ex/friend    :ex/brian}])
             db           @(fluree/commit! ledger db)
             db           @(fluree/stage
                            db
                            {:id         :ex/brian
                             :ex/favNums 7})
             db            @(fluree/commit! ledger db)
             target-t      (:t db)
             ;; TODO: Replace this w/ :syncTo equivalent once we have it
             loaded       (test-utils/load-to-t conn ledger-alias target-t 100)
             loaded-db    (fluree/db loaded)]
         (is (= target-t (:t loaded-db)))
         (is (= (:context ledger) (:context loaded)))))

     (testing "can load a memory ledger with multi-cardinality predicates"
       (let [conn         @(fluree/connect
                            {:method :memory
                             :defaults
                             {:context      test-utils/default-context
                              :context-type :keyword}})
             ledger-alias "load-from-memory-test-multi-card"
             ledger       @(fluree/create conn ledger-alias {:defaultContext ["" {:ex "http://example.org/ns/"}]})
             db           @(fluree/stage
                            (fluree/db ledger)
                            [{:id           :ex/brian
                              :type         :ex/User
                              :schema/name  "Brian"
                              :schema/email "brian@example.org"
                              :schema/age   50
                              :ex/favNums   7}

                             {:id           :ex/alice
                              :type         :ex/User
                              :schema/name  "Alice"
                              :schema/email "alice@example.org"
                              :schema/age   50
                              :ex/favNums   [42 76 9]}

                             {:id           :ex/cam
                              :type         :ex/User
                              :schema/name  "Cam"
                              :schema/email "cam@example.org"
                              :schema/age   34
                              :ex/favNums   [5 10]
                              :ex/friend    [:ex/brian :ex/alice]}])
             db           @(fluree/commit! ledger db)
             db           @(fluree/stage
                            db
                            ;; test a multi-cardinality retraction
                            [{:id         :ex/alice
                              :ex/favNums [42 76 9]}])
             db            @(fluree/commit! ledger db)
             target-t      (:t db)
             ;; TODO: Replace this w/ :syncTo equivalent once we have it
             loaded       (test-utils/load-to-t conn ledger-alias target-t 100)
             loaded-db    (fluree/db loaded)]
         (is (= target-t (:t loaded-db)))
         (is (= (dbproto/-default-context db) (dbproto/-default-context loaded-db)))))

     (testing "can load a memory ledger with its own context"
       (let [conn-context   {:id  "@id", :type "@type"
                             :xsd "http://www.w3.org/2001/XMLSchema#"}
             ledger-context {:ex     "http://example.com/"
                             :schema "http://schema.org/"}
             conn           @(fluree/connect
                              {:method   :memory
                               :defaults {:context      conn-context
                                          :context-type :keyword}})
             ledger-alias   "load-from-memory-with-context"
             ledger         @(fluree/create conn ledger-alias
                                            {:defaultContext ["" ledger-context]})
             db             @(fluree/stage
                              (fluree/db ledger)
                              [{:id             :ex/wes
                                :type           :ex/User
                                :schema/name    "Wes"
                                :schema/email   "wes@example.org"
                                :schema/age     42
                                :schema/favNums [1 2 3]
                                :ex/friend      {:id           :ex/jake
                                                 :type         :ex/User
                                                 :schema/name  "Jake"
                                                 :schema/email "jake@example.org"}}])
             db             @(fluree/commit! ledger db)
             target-t       (:t db)
             loaded         (test-utils/load-to-t conn ledger-alias target-t 100)
             loaded-db      (fluree/db loaded)
             merged-ctx     (merge (ctx-util/stringify-context conn-context) (ctx-util/stringify-context ledger-context))
             query          {:where  '[[?p :schema/email "wes@example.org"]]
                             :select '{?p [:*]}}
             results        @(fluree/query loaded-db query)]
         (is (= target-t (:t loaded-db)))
         (is (= merged-ctx (dbproto/-default-context loaded-db)))
         (is (= [{:type   :ex/User
                  :id             :ex/wes
                  :schema/age     42
                  :schema/email   "wes@example.org"
                  :schema/favNums [1 2 3]
                  :schema/name    "Wes"
                  :ex/friend      {:id :ex/jake}}]
                results))))

     (testing "query returns the correct results from a loaded ledger"
       (let [conn-context   {:id "@id", :type "@type"}
             ledger-context {:ex     "http://example.com/"
                             :schema "http://schema.org/"}
             conn           @(fluree/connect
                              {:method   :memory
                               :defaults {:context      conn-context
                                          :context-type :keyword}})
             ledger-alias   "load-from-memory-query"
             ledger         @(fluree/create conn ledger-alias
                                            {:defaultContext ["" ledger-context]})
             db             @(fluree/stage
                              (fluree/db ledger)
                              [{:id          :ex/Andrew
                                :type        :schema/Person
                                :schema/name "Andrew"
                                :ex/friend   {:id          :ex/Jonathan
                                              :type        :schema/Person
                                              :schema/name "Jonathan"}}])
             query          {:select '{?s [:*]}
                             :where  '[[?s :id :ex/Andrew]]}
             res1           @(fluree/query db query)
             _              @(fluree/commit! ledger db)
             loaded         (test-utils/retry-load conn ledger-alias 100)
             loaded-db      (fluree/db loaded)
             res2           @(fluree/query loaded-db query)]
         (is (= res1 res2))))

     (testing "can load a ledger with `list` values"
       (let [conn         @(fluree/connect
                            {:method :memory
                             :defaults
                             {:context      (merge test-utils/default-context
                                                   {:ex "http://example.org/ns/"})
                              :context-type :keyword}})
             ledger-alias "load-lists-test"
             ledger       @(fluree/create conn ledger-alias)
             db           @(fluree/stage
                            (fluree/db ledger)
                            [{:id         :ex/alice,
                              :type       :ex/User,
                              :ex/friends {:list [:ex/john :ex/cam]}}
                             {:id         :ex/cam,
                              :type       :ex/User
                              :ex/numList {:list [7 8 9 10]}}
                             {:id   :ex/john,
                              :type :ex/User}])
             db           @(fluree/commit! ledger db)
             target-t     (:t db)
             loaded       (test-utils/load-to-t conn ledger-alias target-t 100)
             loaded-db    (fluree/db loaded)]
         (is (= target-t (:t loaded-db)))
         (testing "query returns expected `list` values"
           (is (= [{:id         :ex/cam,
                    :type   :ex/User,
                    :ex/numList [7 8 9 10]}
                   {:id :ex/john, :type :ex/User}
                   {:id         :ex/alice,
                    :type   :ex/User,
                    :ex/friends [{:id :ex/john} {:id :ex/cam}]}]
                  @(fluree/query loaded-db '{:select {?s [:*]}
                                             :where  [[?s :type :ex/User]]})))))

       (testing "can load with policies"
         (let [conn         @(fluree/connect
                              {:method :memory
                               :defaults
                               {:context      (merge test-utils/default-context
                                                     {:ex "http://example.org/ns/"})
                                :context-type :keyword}})
               ledger-alias "load-policy-test"
               ledger       @(fluree/create conn ledger-alias)
               db           @(fluree/stage
                              (fluree/db ledger)
                              [{:id          :ex/alice,
                                :type        :ex/User,
                                :schema/name "Alice"
                                :schema/ssn  "111-11-1111"
                                :ex/friend   :ex/john}
                               {:id          :ex/john,
                                :schema/name "John"
                                :type        :ex/User,
                                :schema/ssn  "888-88-8888"}
                               {:id      "did:fluree:123"
                                :ex/user :ex/alice
                                :f/role  :ex/userRole}])
               db+policy    @(fluree/stage
                              db
                              [{:id            :ex/UserPolicy,
                                :type          [:f/Policy],
                                :f/targetClass :ex/User
                                :f/allow       [{:id           :ex/globalViewAllow
                                                 :f/targetRole :ex/userRole
                                                 :f/action     [:f/view]}]
                                :f/property    [{:f/path  :schema/ssn
                                                 :f/allow [{:id           :ex/ssnViewRule
                                                            :f/targetRole :ex/userRole
                                                            :f/action     [:f/view]
                                                            :f/equals     {:list [:f/$identity :ex/user]}}]}]}])
               db+policy    @(fluree/commit! ledger db+policy)
               target-t     (:t db+policy)
               loaded       (test-utils/load-to-t conn ledger-alias target-t 100)
               loaded-db    (fluree/db loaded)]
           (is (= target-t (:t loaded-db)))
           (testing "query returns expected policy"
             (is (= [{:id            :ex/UserPolicy,
                      :type      :f/Policy,
                      :f/allow
                      {:id           :ex/globalViewAllow,
                       :f/action     {:id :f/view},
                       :f/targetRole {:_id 211106232532995}},
                      :f/property
                      {:id     "_:f211106232532999",
                       :f/allow
                       {:id           :ex/ssnViewRule,
                        :f/action     {:id :f/view},
                        :f/targetRole {:_id 211106232532995},
                        :f/equals     [{:id :f/$identity} {:id :ex/user}]},
                       :f/path {:id :schema/ssn}},
                      :f/targetClass {:id :ex/User}}]
                    @(fluree/query loaded-db '{:select {?s [:*
                                                            {:type [:_id]}
                                                            {:f/allow [:* {:f/targetRole [:_id]}]}
                                                            {:f/property [:* {:f/allow [:* {:f/targetRole [:_id]}]}]}]}
                                               :where  [[?s :type :f/Policy]]})))))))
     (testing "loading predefined properties"
       (let [conn (test-utils/create-conn {:context test-utils/default-str-context
                                           :context-type :string})
             ledger-alias  "shacl/a"
             ledger @(fluree/create conn "shacl/a" {:defaultContext ["" {"ex" "http://example.org/ns/"}]})

             db1 @(test-utils/transact ledger
                                       {"@type" "sh:NodeShape",
                                        "sh:targetClass" {"id" "schema:Person"}
                                        "sh:property"
                                        [{"sh:path" {"id" "schema:familyName"}
                                          "sh:datatype" {"id" "xsd:string"}}]})
             property-query {:select {"?s" ["*"]}, :where [["?s" "sh:property" "?property"]]}
             shape-id (-> @(fluree/query db1 property-query)
                          first
                          (get "id"))
             loaded1 (test-utils/retry-load conn ledger-alias 100)]
         (is (= [{"id" shape-id
                  "type" "sh:NodeShape",
                  "sh:targetClass" {"id" "schema:Person"},
                  "sh:property" {"id" "_:f211106232532993"}}]
                @(fluree/query db1 property-query)))
         (is (= [{"id" shape-id
                  "type" "sh:NodeShape",
                  "sh:targetClass" {"id" "schema:Person"},
                  "sh:property" {"id" "_:f211106232532993"}}]
                @(fluree/query (fluree/db loaded1) property-query)))
         (testing "load ref retracts"
           (let [db2 @(test-utils/transact loaded1
                                           {"@id" shape-id
                                            "sh:property"
                                            [{"sh:path" {"id" "schema:age"}
                                              "sh:datatype" {"id" "xsd:string"}}]})
                 loaded2 (test-utils/retry-load conn ledger-alias 100)]
             (is (= [{"id" shape-id
                      "type" "sh:NodeShape",
                      "sh:targetClass" {"id" "schema:Person"},
                      "sh:property" {"id" "_:f211106232532994"}}]
                    @(fluree/query (fluree/db loaded2) property-query)))))))
    (testing "can load after deletion of entire subjects"
       (let [conn              @(fluree/connect
                                  {:method :memory
                                   :defaults
                                   {:context      test-utils/default-context
                                    :context-type :keyword}})
             ledger-alias      "tx/delete"
             ledger            @(fluree/create conn ledger-alias {:defaultContext ["" {:ex "http://example.org/ns/"}]})
             db1               @(fluree/stage
                                  (fluree/db ledger)
                                  {:graph
                                   [{:id                 :ex/fluree
                                     :type               :schema/Organization
                                     :schema/description "We ❤️ Data"}
                                    {:id                 :ex/w3c
                                     :type               :schema/Organization
                                     :schema/description "We ❤️ Internet"}
                                    {:id                 :ex/mosquitos
                                     :type               :ex/Monster
                                     :schema/description "We ❤️ human blood"}
                                    {:id                 :ex/kittens
                                     :type               :ex/Animal
                                     :schema/description "We ❤️ catnip"}]})
             description-query '{:select {?s [:id]}
                                 :where  [[?s :schema/description ?description]]}
             _                 @(fluree/commit! ledger db1)
             loaded1           (test-utils/retry-load conn ledger-alias 100)
             loaded-db1        (fluree/db loaded1)
             db2               @(fluree/stage
                                  loaded-db1
                                  '{:delete [:ex/mosquitos ?p ?o]
                                    :where  [[:ex/mosquitos ?p ?o]]})
             _                 @(fluree/commit! ledger db2)
             loaded2           (test-utils/retry-load conn ledger-alias 100)
             loaded-db2        (fluree/db loaded2)]
         (is (= [{:id :ex/fluree} {:id :ex/w3c} {:id :ex/kittens}]
                @(fluree/query loaded-db2 description-query))
             "The id :ex/mosquitos should be removed")
         (let [db3        @(fluree/stage
                             loaded-db2
                             '{:delete [?s ?p ?o]
                               :where  [[?s :type :schema/Organization]
                                        [?s ?p ?o]]})
               _          @(fluree/commit! ledger db3)
               loaded3  (test-utils/retry-load conn ledger-alias 100)
               loaded-db3 (fluree/db loaded3)]
           (is (= [{:id :ex/kittens}]
                  @(fluree/query loaded-db3 description-query))
               "Only :ex/kittens should be left"))))))

(deftest ^:integration query-test
  (let [query {:select ["?person" "?name"]
               :where  [["?person" :type :ex/User]
                        ["?person" :schema/name "?name"]]}
        expected [[:ex/liam "Liam"]
                  [:ex/cam "Cam"]
                  [:ex/alice "Alice"]
                  [:ex/brian "Brian"]]]
    (testing "basic query works"
      #?(:clj
         (let [conn    (test-utils/create-conn)
               ledger  (test-utils/load-people conn)
               results @(fluree/query (fluree/db ledger) query)]
           (is (= expected results)))
         :cljs
         (async done
           (go
            (let [conn    (<! (test-utils/create-conn))
                  ledger  (<! (test-utils/load-people conn))
                  results (<p! (fluree/query (fluree/db ledger) query))]
              (is (= expected results))
              (done))))))))

(deftest ^:integration fuel-test
  #?(:clj
     (testing "fuel tracking"
       (let [conn   (test-utils/create-conn)
             ledger @(fluree/create conn "test/fuel-tracking"
                                    {:defaultContext
                                     ["" {:ex "http://example.org/ns/"}]})
             db0    (fluree/db ledger)]
         (testing "transactions"
           (testing "with the `:meta` option"
             (let [response    @(fluree/stage db0 test-utils/people {:meta true})
                   db          (:result response)
                   flake-total (count (<?? (query-range/index-range db :spot)))]
               (is (= flake-total (:fuel response))
                   "Reports fuel for all the generated flakes")))
           (testing "without the `:meta` option"
             (let [response @(fluree/stage db0 test-utils/people)]
               (is (nil? (:fuel response))
                   "Returns no fuel")))
           (testing "short-circuits if request fuel exhausted"
             (let [response @(fluree/stage db0 test-utils/people {:max-fuel 1})]
               (is (util/exception? response))
               (is (re-find #"Fuel limit exceeded"
                            (-> response ex-cause ex-message))))))
         (testing "queries"
           (let [db          @(fluree/stage db0 test-utils/people)
                 flake-total (count (<?? (query-range/index-range db :spot)))
                 query       '{:select [?s ?p ?o]
                               :where  [[?s ?p ?o]]}]
             (testing "queries not returning metadata"
               (let [sut @(fluree/query db query)]
                 (is (nil? (:fuel sut))
                     "Reports no fuel")))
             (testing "queries returning metadata"
               (let [query* (assoc-in query [:opts :meta] true)
                     sut    @(fluree/query db query*)]
                 (is (= flake-total (:fuel sut))
                     "Reports that all flakes were traversed"))))
           (testing "short-circuits if request fuel exhausted"
             (let [query   '{:select [?s ?p ?o]
                             :where  [[?s ?p ?o]]
                             :opts   {:max-fuel 1}}
                   db      @(fluree/stage db0 test-utils/people)
                   results @(fluree/query db query)]
               (is (util/exception? results))
               (is (re-find #"Fuel limit exceeded"
                            (-> results ex-cause ex-message))))))))
     :cljs
     (async done
       (go
        (testing "fuel tracking"
          (let [conn   (<! (test-utils/create-conn))
                ledger (<p! (fluree/create conn "test/fuel-tracking"
                                           {:defaultContext
                                            ["" {:ex "http://example.org/ns/"}]}))
                db0    (fluree/db ledger)]
            (testing "transactions"
              (testing "with the `:meta` option"
                (let [response    (<p! (fluree/stage db0 test-utils/people {:meta true}))
                      db          (:result response)
                      flake-total (count (<? (query-range/index-range db :spot)))]
                  (is (= flake-total (:fuel response))
                      "Reports fuel for all the generated flakes")))
              (testing "without the `:meta` option"
                (let [response (<p! (fluree/stage db0 test-utils/people))]
                  (is (nil? (:fuel response))
                      "Returns no fuel")))
              (testing "short-circuits if request fuel exhausted"
                (let [response (try
                                 (<p! (fluree/stage db0 test-utils/people
                                                    {:max-fuel 1}))
                                 (catch :default e (ex-cause e)))]
                  (is (util/exception? response))
                  (is (re-find #"Fuel limit exceeded"
                               (-> response ex-cause ex-message))))))
            (testing "queries"
              (let [db          (<p! (fluree/stage db0 test-utils/people))
                    flake-total (count (<? (query-range/index-range db :spot)))
                    query       '{:select [?s ?p ?o]
                                  :where  [[?s ?p ?o]]}]
                (testing "queries not returning metadata"
                  (let [sut (<p! (fluree/query db query))]
                    (is (nil? (:fuel sut))
                        "Reports no fuel")))
                (testing "queries returning metadata"
                  (let [query* (assoc-in query [:opts :meta] true)
                        sut    (<p! (fluree/query db query*))]
                    (is (= flake-total (:fuel sut))
                        "Reports that all flakes were traversed"))))
              (testing "short-circuits if request fuel exhausted"
                (let [query   '{:select [?s ?p ?o]
                                :where  [[?s ?p ?o]]
                                :opts   {:max-fuel 1}}
                      db      (<p! (fluree/stage db0 test-utils/people))
                      results (try
                                (<p! (fluree/query db query))
                                (catch :default e (ex-cause e)))]
                  (is (util/exception? results))
                  (is (re-find #"Fuel limit exceeded"
                               (-> results ex-cause ex-message))))))))
        (done)))))
