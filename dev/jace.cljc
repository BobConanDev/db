(ns jace
  (:require [fluree.db.json-ld.api :as fluree]))

(comment
  
  (def init-txn
    {"ledger" "fluree/myledger3",
     "insert" [{"createdAt" "now"}]})
  
  (do (def conn @(fluree/connect {:method :file
                                  :storage-path "../data"}))
      (def ledger @(fluree/create conn "fluree/myledger3"))
      (def db (fluree/db ledger))
      (def db1 @(fluree/stage db init-txn))
      (def commit @(fluree/commit! ledger db1)))

  (def new-txn
    {"@context" [
                 {
                  "ex" "http//example.org/",
                  "f" "https://ns.flur.ee/ledger#",
                  "foaf" "http://xmlns.com/foaf/0.1/",
                  "owl" "http://www.w3.org/2002/07/owl#",
                  "rdf" "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
                  "rdfs" "http://www.w3.org/2000/01/rdf-schema#",
                  "schema" "http://schema.org/",
                  "sh" "http://www.w3.org/ns/shacl#",
                  "skos" "http://www.w3.org/2008/05/skos#",
                  "wiki" "https://www.wikidata.org/wiki/",
                  "xsd" "http://www.w3.org/2001/XMLSchema#"
                  }
                 ],
     "ledger" "fluree/myledger3",
     "insert" [
               {
                "@id" "ex:freddy",
                "@type" "ex:Yeti",
                "schema:age" 4,
                "schema:name" "Freddy",
                "ex:verified" true
                },
               {
                "@id" "ex:letty",
                "@type" "ex:Yeti",
                "schema:age" 2,
                "ex:nickname" "Letty",
                "schema:name" "Leticia",
                "schema:follows" [{ "@id" "ex:freddy" }]
                },
               {
                "@id" "ex:betty",
                "@type" "ex:Yeti",
                "schema:age" 35,
                "schema:name" "Betty",
                "schema:follows" [{ "@id" "ex:freddy" }]
                },
               {
                "@id" "ex:andrew",
                "@type" "ex:Yeti",
                "schema:age" 35,
                "schema:name" "Andrew Johnson",
                "schema:follows" [
                                  { "@id" "ex:freddy" },
                                  { "@id" "ex:letty" },
                                  { "@id" "ex:betty" }
                                  ]
                }
               ]
     })
  
  (deref (fluree/transact! conn new-txn))

) 
