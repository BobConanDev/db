(ns fluree.db.json-ld.credential-test
  (:require [fluree.db.json-ld.credential :as cred]
            [clojure.core.async :as async]
            #?(:clj [clojure.test :as t :refer [deftest testing is]]
               :cljs [cljs.test :as t :refer [deftest testing is] :include-macros true])
            [fluree.db.json-ld.api :as fluree]
            [fluree.db.test-utils :as test-utils]
            [fluree.db.did :as did]))


(def kp
  {:public "03b160698617e3b4cd621afd96c0591e33824cb9753ab2f1dace567884b4e242b0"
   :private "509553eece84d5a410f1012e8e19e84e938f226aa3ad144e2d12f36df0f51c1e"})
(def auth (did/private->did-map (:private kp)))

(def pleb-kp
  {:private "f6b009cc18dee16675ecb03b2a4b725f52bd699df07980cfd483766c75253f4b",
   :public "02e84dd4d9c88e0a276be24596c5c8d741a890956bda35f9c977dba296b8c7148a"})
(def pleb-auth (did/private->did-map (:private pleb-kp)))

(def other-kp
  {:private "f6b009cc18dee16675ecb03b2a4b725f52bd699df07980cfd483766c75253f4b",
   :public "02e84dd4d9c88e0a276be24596c5c8d741a890956bda35f9c977dba296b8c7148a"})
(def other-auth (did/private->did-map (:private other-kp)))

(def example-cred-subject {"@context" {"a" "http://a.com/"} "a:foo" "bar"})
(def example-issuer (:id auth))

(def clj-generated-jws
  "eyJhbGciOiJFUzI1NkstUiIsImI2NCI6ZmFsc2UsImNyaXQiOlsiYjY0Il19..HDBFAiEA80-G5gUH6BT9D1Mc-YyWbjuwbL7nKfWj6BrsHS6whQ0CIAcjzJvo0sW52FIlgvxy0hPBKNWolIwLvoedG_4HQu_V")

(def cljs-generated-jws
  "eyJhbGciOiJFUzI1NkstUiIsImI2NCI6ZmFsc2UsImNyaXQiOlsiYjY0Il19..HDBFAiEAy9MuRjVn_vwvEgQlsCJNnSYwCJEWU_UOg5U_R8--87wCID-qficJv-aCUotctcFGX-xTky1E08W2Y7utUCJZ3AZY")

(def example-credential
  {"@context"          "https://www.w3.org/2018/credentials/v1"
   "id"                ""
   "type"              ["VerifiableCredential" "CommitProof"]
   "issuer"            (:id auth)
   "issuanceDate"      "1970-01-01T00:00:00.00000Z"
   "credentialSubject" example-cred-subject
   "proof"             {"type"               "EcdsaSecp256k1RecoverySignature2020"
                        "created"            "1970-01-01T00:00:00.00000Z"
                        "verificationMethod" "did:key:z6DuABnw7XPbMksZo5wY4HweN8wPkEd7rCQM4YGgu8hPqrd5"
                        "proofPurpose"       "assertionMethod"
                        "jws"                #?(:clj clj-generated-jws
                                                :cljs cljs-generated-jws)}})

#?(:clj
   (deftest credential-test
     (with-redefs [fluree.db.util.core/current-time-iso (constantly "1970-01-01T00:00:00.00000Z")]
       (testing "generate"
         (let [cred (async/<!! (cred/generate example-cred-subject (:private auth)))]
           (is (= example-credential
                  cred))))

       (testing "verify correct signature"
         (let [clj-result (async/<!! (cred/verify example-credential))
               cljs-result (async/<!! (cred/verify (assoc-in example-credential ["proof" "jws"] cljs-generated-jws)))]
           (is (= {:subject example-cred-subject :did example-issuer} clj-result))
           (is (= {:subject example-cred-subject :did example-issuer} cljs-result))))

       (testing "verify incorrect signature"
         (let [wrong-cred (assoc example-credential "credentialSubject" {"@context" {"a" "http://a.com/"} "a:foo" "DIFFERENT!"})]
           (is (= "Verification failed."
                  (-> (async/<!! (cred/verify wrong-cred))
                      (Throwable->map)
                      (:cause))))))

       (testing "verify not a credential"
         (let [non-cred example-cred-subject]
           (is (nil? (async/<!! (cred/verify non-cred)))))))))

#?(:cljs
   (deftest generate
     (t/async done
              (async/go
                (with-redefs [fluree.db.util.core/current-time-iso (constantly "1970-01-01T00:00:00.00000Z")]
                  (let [cred (async/<! (cred/generate example-cred-subject (:private auth)))]
                    (is (= example-credential
                           cred))
                    (done)))))))

#?(:cljs
   (deftest verify-correct-signature
     (t/async done
              (async/go
                (with-redefs [fluree.db.util.core/current-time-iso (constantly "1970-01-01T00:00:00.00000Z")]
                  (let [cljs-result (async/<! (cred/verify example-credential))
                        clj-result  (async/<! (cred/verify (assoc-in example-credential ["proof" "jws"] clj-generated-jws)))]
                    (is (= {:subject example-cred-subject :did example-issuer} cljs-result))
                    (is (= {:subject example-cred-subject :did example-issuer} clj-result))
                    (done)))))))

#?(:cljs
   (deftest verify-incorrect-signature
     (t/async done
              (async/go
                (with-redefs [fluree.db.util.core/current-time-iso (constantly "1970-01-01T00:00:00.00000Z")]
                  (let [wrong-cred (assoc example-credential "credentialSubject" {"@context" {"a" "http://a.com/"} "a:foo" "DIFFERENT!"})]
                    (is (= "Verification failed."
                           (-> (async/<! (cred/verify wrong-cred))
                               (.-message e))))
                    (done)))))))
#?(:cljs
   (deftest verify-non-credential
     (t/async done
              (async/go
                (with-redefs [fluree.db.util.core/current-time-iso (constantly "1970-01-01T00:00:00.00000Z")]
                  (let [non-cred example-cred-subject]
                    (is (nil? (async/<! (cred/verify non-cred))))
                    (done)))))))

#?(:clj
   (deftest ^:integration cred-wrapped-transactions-and-queries
     (let [conn   @(fluree/connect {:method :memory})
           ledger @(fluree/create conn "credentialtest" {:defaultContext
                                                         [test-utils/default-str-context
                                                          {"ct" "ledger:credentialtest/"}]})

           db0       @(test-utils/transact ledger {"@context" "https://ns.flur.ee"
                                                   "insert"  {"@id" "ct:open" "ct:foo" "bar"}})
           root-user {"id" (:id auth) "ct:name" "Daniel" "f:role" {"id" "role:cool"} "ct:favnums" [1 2 3]}
           pleb-user {"id" (:id pleb-auth) "ct:name" "Plebian" "f:role" [{"id" "role:notcool"}
                                                                         { "id" "role:irrelevant"}]}
           policy    {"id"           "rootPolicy"
                      "type"         "f:Policy"
                      "f:targetNode" {"id" "f:allNodes"}
                      "f:allow"      [{"f:targetRole" {"id" "role:cool"}
                                       "f:action"     [{"id" "f:view"} {"id" "f:modify"}]}]}
           tx        [root-user pleb-user policy]
           ;; can't use credentials until after an identity with a role has been created
           db1       @(test-utils/transact ledger {"@context" "https://ns.flur.ee"
                                                   "insert"  tx})

           mdfn {"@context" "https://ns.flur.ee"
                 "delete" {"@id"     "?s"
                           "ct:name"    "Daniel"
                           "ct:favnums" 1}
                 "insert" {"@id"     "?s"
                           "ct:name"    "D"
                           "ct:favnums" [4 5 6]}
                 "where"  {"@id" "?s"}
                 "values" ["?s" [(:id auth)]]}

           db2 @(test-utils/transact ledger (async/<!! (cred/generate mdfn (:private auth))))

           query {"select" {(:id auth) ["*"]}}]
       (is (= [{"id" "ct:open", "ct:foo" "bar"}]
              @(fluree/query db0 {"select" {"ct:open" ["*"]}}))
           "can see everything when no identity is asserted")
       (is (= "Applying policy without a role is not yet supported."
              (-> @(fluree/query db0 (async/<!! (cred/generate {"select" {"open" ["*"]}}
                                                               (:private auth))))
                  (Throwable->map)
                  :cause))
           "cannot see anything before policies are mapped to identities")
       (is (= [root-user]
              @(fluree/query db1 query)))
       (is (= [{"id" (:id auth) "ct:name" "D" "ct:favnums" [2 3 4 5 6] "f:role" {"id" "role:cool"}}]
              @(fluree/query db2 query))
           "modify transaction in credential")

       (is (= []
              @(fluree/query (fluree/db ledger) (async/<!! (cred/generate query (:private pleb-auth)))))
           "query credential w/ policy forbidding access")
       (is (= [{"id"      (:id auth)
                "ct:name"    "D"
                "ct:favnums" [2 3 4 5 6]
                "f:role"  {"id" "role:cool"}}]
              @(fluree/query db2 (async/<!! (cred/generate query (:private auth)))))
           "query credential w/ policy allowing access")

       (is (= []
              @(fluree/query db2 (async/<!! (cred/generate query (:private other-auth)))))
           "query credential w/ no roles")
       (is (= [{"f:t"       2,
                "f:assert"  [{"id" (:id auth)
                              "ct:name" "Daniel"
                              "ct:favnums" [1 2 3],
                              :id (:id auth)
                              "f:role" {"id" "role:cool"}}],
                "f:retract" []}

               {"f:t"       3,
                "f:assert"  [{"ct:name" "D", "ct:favnums" [4 5 6], :id (:id auth)}],
                "f:retract" [{"ct:name" "Daniel", "ct:favnums" 1, :id (:id auth)}]}]
              @(fluree/history ledger (async/<!! (cred/generate {:history (:id auth) :t {:from 1}} (:private auth)))))
           "history query credential - allowing access")
       (is (= "Subject identity does not exist: \"did:fluree:TfHgFTQQiJMHaK1r1qxVPZ3Ridj9pCozqnh\""
              (-> @(fluree/history ledger (async/<!! (cred/generate {:history (:id auth) :t {:from 1}} (:private pleb-auth))))
                  (Throwable->map)
                  (:cause)))
           "history query credential - forbidding access"))))

(comment
  #?(:cljs

     (cljs.test/run-tests))

  ,)
