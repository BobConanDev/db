(ns json-ld.shacl
  (:require [fluree.db.method.ipfs.core :as ipfs]
            [fluree.db.api :as fdb]
            [fluree.db.db.json-ld :as jld-db]
            [fluree.db.json-ld.transact :as jld-tx]
            [clojure.core.async :as async]
            [fluree.db.flake :as flake]
            [fluree.db.json-ld.api :as fluree]
            [fluree.db.util.async :refer [<?? go-try channel?]]
            [fluree.db.query.range :as query-range]
            [fluree.db.constants :as const]
            [fluree.db.dbproto :as dbproto]
            [fluree.db.did :as did]
            [fluree.db.conn.proto :as conn-proto]
            [fluree.db.util.json :as json]
            [fluree.json-ld :as json-ld]
            [fluree.db.indexer.default :as indexer]
            [fluree.db.indexer.proto :as idx-proto]
            [fluree.db.util.log :as log]
            [fluree.db.index :as index]
            [criterium.core :as criterium]
            [clojure.data.avl :as avl]
            [clojure.tools.reader.edn :as edn]))


(comment

  (def ipfs-conn @(fluree/connect-ipfs
                    {:server   nil                          ;; use default
                     ;; ledger defaults used for newly created ledgers
                     :defaults {:ipns    {:key "self"}      ;; publish to ipns by default using the provided key/profile
                                :indexer {:reindex-min-bytes 9000
                                          :reindex-max-bytes 10000000}
                                :context {:id     "@id"
                                          :type   "@type"
                                          :xsd    "http://www.w3.org/2001/XMLSchema#"
                                          :schema "http://schema.org/"
                                          :sh     "http://www.w3.org/ns/shacl#"
                                          :rdf    "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                                          :rdfs   "http://www.w3.org/2000/01/rdf-schema#"
                                          :wiki   "https://www.wikidata.org/wiki/"
                                          :skos   "http://www.w3.org/2008/05/skos#"
                                          :f      "https://ns.flur.ee/ledger#"}
                                :did     (did/private->did-map "8ce4eca704d653dec594703c81a84c403c39f262e54ed014ed857438933a2e1c")}}))


  (def ledger @(fluree/create ipfs-conn "shacl/b" {}))

  ;; should work OK
  (def newdb
    @(fluree/stage
       ledger
       {:context          {:ex "http://example.org/ns/"}
        :id               :ex/brian,
        :type             :ex/User,
        :schema/name      "Brian"
        :schema/email     "bplatz@flur.ee"
        :schema/birthDate "2022-08-17"
        :schema/ssn       "42"}))

  (-> newdb :novelty :spot)

  (def newdb2
    @(fluree/stage
       newdb
       {:context              {:ex "http://example.org/ns/"}
        :id                   :ex/UserShape,
        :type                 [:sh/NodeShape],
        :sh/targetClass       :ex/User
        :sh/property          [{:sh/path     :schema/name
                                :sh/minCount 1
                                :sh/maxCount 1
                                :sh/datatype :xsd/string}
                               {:sh/path     :schema/ssn
                                :sh/datatype :xsd/string
                                :sh/maxCount 1
                                :sh/pattern  "^\\d{3}-\\d{2}-\\d{4}$"}
                               {:sh/path     :schema/email
                                :sh/minCount 1
                                :sh/maxCount 1
                                :sh/nodeKind :sh/IRI}]
        :sh/ignoredProperties [:rdf/type]
        :sh/closed            true}))


  @(fluree/query newdb2 {:context {:ex "http://example.org/ns/"}
                         :select  {'?s [:* {:sh/property [:*]}]}
                         :where   [['?s :rdf/type :sh/NodeShape]]})


  @(fluree/query newdb {:context {:ex "http://example.org/ns/"}
                        :select  {'?s [:*]}
                        :where   [['?s :rdf/type :ex/User]]})

  ;; should error - no email
  (def db2
    @(fluree/stage
       newdb2
       {:context     {:ex "http://example.org/ns/"}
        :id          :ex/john2,
        :type        [:ex/User],
        :schema/name "John"}))

  ;; should error - too many names
  (def db2
    @(fluree/stage
       newdb2
       {:context      {:ex "http://example.org/ns/"}
        :id           :ex/john2,
        :type         [:ex/User],
        :schema/name  ["John", "Johnny"]
        :schema/email "john@flur.ee"}))

  ;; should error - name not a string
  (def db2
    @(fluree/stage
       newdb2
       {:context      {:ex "http://example.org/ns/"}
        :id           :ex/john3,
        :type         [:ex/User],
        :schema/name  45456456
        :schema/email "john@flur.ee"}))


  ;; should be OK!
  (def db2
    @(fluree/stage
       newdb2
       {:context      {:ex "http://example.org/ns/"}
        :id           :ex/john2,
        :type         [:ex/User],
        :schema/name  "John"
        :schema/email "john@flur.ee"}))


  @(fluree/query db2 {:context {:ex "http://example.org/ns/"}
                      :select  {'?s [:* {:sh/property [:*]}]}
                      :where   [['?s :rdf/type :ex/User]]})


  (-> newdb2 :schema :shapes deref)
  (-> db2 :novelty :spot)

  ;; bad SSN
  (def db2
    @(fluree/stage
       newdb2
       [{:context      {:ex "http://example.org/ns/"}
         :id           :ex/john2,
         :type         [:ex/User],
         :schema/name  "John"
         :schema/email "john@flur.ee"
         :schema/ssn   "345-12-456b"}]))










  (-> @(fluree/commit! newdb {:message "First commit!"
                              :push?   true})
      :commit)

  )

(comment
  (def flakes-ss (->> (map #(flake/create % (rand-int 2000) 42 const/$xsd:integer -1 true nil) (range 10000))
                      (apply flake/sorted-set-by flake/cmp-flakes-post)))

  (def from-to (map (fn [x] (let [num  (rand-int 2000)
                                  from (-> (quot num 5)
                                           (* 5))
                                  to   (+ from 4)]
                              [from to]))
                    (range 100)))

  (def ffrom (ffirst from-to))
  (def fto (second (first from-to)))

  )