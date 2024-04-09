(ns fluree.db.json-ld.api
  (:require [fluree.db.conn.ipfs :as ipfs-conn]
            [fluree.db.conn.file :as file-conn]
            [fluree.db.conn.memory :as memory-conn]
            [fluree.db.conn.remote :as remote-conn]
            [fluree.json-ld :as json-ld]
            #?(:clj [fluree.db.conn.s3 :as s3-conn])
            [fluree.db.json-ld.iri :as iri]
            [fluree.db.platform :as platform]
            [clojure.core.async :as async :refer [go <!]]
            [fluree.db.api.query :as query-api]
            [fluree.db.api.transact :as transact-api]
            [fluree.db.util.core :as util]
            [fluree.db.ledger.json-ld :as jld-ledger]
            [fluree.db.ledger :as ledger]
            [fluree.db.util.log :as log]
            [fluree.db.query.range :as query-range]
            [fluree.db.nameservice.core :as nameservice]
            [fluree.db.connection :refer [notify-ledger]]
            [fluree.db.reasoner.core :as reasoner]
            [fluree.db.flake :as flake]
            [fluree.db.json-ld.policy :as perm])
  (:refer-clojure :exclude [merge load range exists?]))

#?(:clj (set! *warn-on-reflection* true))

(defn promise-wrap
  "Wraps an async channel that will contain a response in a promise."
  [port]
  #?(:clj
     (let [p (promise)]
       (go
         (let [res (<! port)]
           (when (util/exception? res)
             (log/error res))
           (deliver p res)))
       p)
     :cljs
     (js/Promise.
       (fn [resolve reject]
         (go
           (let [res (<! port)]
             (if (util/exception? res)
               (reject res)
               (resolve res))))))))

;; ledger operations

(defn connect
  "Forms connection to ledger, enabling automatic pulls of new updates, event
  services, index service.

  Multiple connections to same endpoint will share underlying network connection.

  Options include:
    - did - (optional) DId information to use, if storing blocks as verifiable credentials,
            or issuing queries against a permissioned database.
    - write - (optional) Function to use for all writes, if empty will store in memory until a commit is performed
    - read - (optional) Function to use for reads of persisted blocks/data
    - commit - (optional) Function to use to write commits. If persistence desired, this must be defined
    - push - (optional) Function(s) in a vector that will attempt to push the commit to naming service(s)
    "
  [{:keys [method parallelism remote-servers] :as opts}]
  ;; TODO - do some validation
  (promise-wrap
    (let [opts* (assoc opts :parallelism (or parallelism 4))

          method* (cond
                    method         (keyword method)
                    remote-servers :remote
                    :else          (throw (ex-info (str "No Fluree connection method type specified in configuration: " opts)
                                                   {:status 500 :error :db/invalid-configuration})))]
      (case method*
        :remote (remote-conn/connect opts*)
        :ipfs   (ipfs-conn/connect opts*)
        :file   (if platform/BROWSER
                  (throw (ex-info "File connection not supported in the browser" opts))
                  (file-conn/connect opts*))
        :memory (memory-conn/connect opts*)
        :s3     #?(:clj  (s3-conn/connect opts*)
                   :cljs (throw (ex-info "S3 connections not yet supported in ClojureScript"
                                         {:status 400, :error :db/unsupported-operation})))))))

(defn connect-file
  [opts]
  (connect (assoc opts :method :file)))

(defn connect-ipfs
  "Forms an ipfs connection using default settings.
  - server - (optional) IPFS http api server endpoint, defaults to http://127.0.0.1:5001/
  - profile - (optional) IPFS stored profile to use.
  - did - (optional) DId information to use, if storing blocks as verifiable credentials"
  [opts]
  (connect (assoc opts :method :ipfs)))

(defn connect-memory
  "Forms an in-memory connection using default settings.
  - did - (optional) DId information to use, if storing blocks as verifiable credentials"
  [opts]
  (connect (assoc opts :method :memory)))

(defn address?
  "Returns true if the argument is a full ledger address, false if it is just an
  alias."
  [ledger-alias-or-address]
  (jld-ledger/fluree-address? ledger-alias-or-address))

(defn create
  "Creates a new json-ld ledger. A connection (conn)
  must always be supplied.

  Ledger-name (optional) is a friendly name that is used for:
  - When publishing to a naming service that allows multiple pointers for the
    same namespace (e.g. IPNS), this becomes a sub-directory off the namespace.
    For multple directories deep, use '/' for a
    e.g. the ledgers movies/popular, books/authors, books/best-sellers could
    use the same IPNS id (in this example using IPNS DNSLink):
    fluree:ipns://my.dns.com/books/authors
    fluree:ipns://my.dns.com/books/best-sellers
    fluree:ipns://my.dns.com/movies/top-rated
  - When combining multiple ledgers, each ledger becomes an individual named
    graph which can be referenced by name.

  Options map (opts) can include:
  - did - DId information to use, if storing blocks as verifiable credentials"
  ([conn] (create conn nil nil))
  ([conn ledger-alias] (create conn ledger-alias nil))
  ([conn ledger-alias opts]
   (promise-wrap
    (do
      (log/info "Creating ledger" ledger-alias)
      (jld-ledger/create conn ledger-alias opts)))))

(defn alias->address
  "Returns a core.async channel with the connection-specific address of the
  given ledger-alias."
  [conn ledger-alias]
  (log/debug "Looking up address for ledger alias" ledger-alias)
  (nameservice/primary-address conn ledger-alias nil))

(defn load
  "Loads an existing ledger by its alias (which will be converted to a
  connection-specific address first)."
  [conn alias-or-address]
  (promise-wrap
    (jld-ledger/load conn alias-or-address)))

(defn exists?
  "Returns a promise with true if the ledger alias or address exists, false
  otherwise."
  [conn ledger-alias-or-address]
  (promise-wrap
    (go
      (let [address (if (address? ledger-alias-or-address)
                      ledger-alias-or-address
                      (<! (alias->address conn ledger-alias-or-address)))]
        (log/debug "exists? - ledger address:" address)
        (<! (nameservice/exists? conn address))))))

(defn notify
  "Notifies the connection with a new commit map (parsed JSON commit with string keys).

  If the connection knows of the ledger, and is currently maintaining
  an in-memory version of the ledger, will attempt to update the db if the commit
  is for the next 't' value. If a commit is for a past 't' value, noop.
  If commit is for a future 't' value, will drop in-memory ledger for reload upon next request."
  [conn commit-map]
  (promise-wrap
    (if (map? commit-map)
      (notify-ledger conn commit-map)
      (go
        (ex-info (str "Invalid commit map, perhaps it is JSON that needs to be parsed first?: " commit-map)
                 {:status 400 :error :db/invalid-commit-map})))))


(defn index
  "Performs indexing operation on the specified ledger"
  [ledger])


;; MAYBE CHALLENGE?
(defn validate
  "Validates a ledger, checks block integrity along with signatures."
  [])


(defn pull
  "Checks name service for ledger and pulls latest version locally."
  [])


(defn combine
  "Combines multiple ledgers into a new, read-only ledger."
  [])


(defn stage
  "Performs a transaction and queues change if valid (does not commit)"
  ([db json-ld] (stage db json-ld nil))
  ([db json-ld opts]
   (let [result-ch (transact-api/stage db json-ld opts)]
     (promise-wrap result-ch))))


(defn commit!
  "Commits a staged database to the ledger with all changes since the last commit
  aggregated together.

  Commits are tracked in the local environment, but if the ledger is distributed
  it will still need a 'push' to ensure it is published and verified as per the
  distributed rules."
  ([ledger db]
   (promise-wrap
     (ledger/-commit! ledger db)))
  ([ledger db opts]
   (promise-wrap
     (ledger/-commit! ledger db opts))))

(defn transact!
  [conn txn]
  (promise-wrap
    (transact-api/transact! conn txn)))

(defn create-with-txn
  [conn txn]
  (promise-wrap
    (transact-api/create-with-txn conn txn)))

(defn status
  "Returns current status of ledger branch."
  ([ledger] (ledger/-status ledger))
  ([ledger branch] (ledger/-status ledger branch)))


(defn push
  "Pushes all commits since last push to a name service, e.g. a Fluree Network, IPNS, DNS, Fluree Nexus.
  Depending on consensus requirements for a Fluree Network, will accept or reject push as newest update."
  [])



(defn squash
  "Squashes multiple unpublished commits into a single unpublished commit"
  [])



(defn merge
  "Merges changes from one branch into another branch."
  [])



(defn branch
  "Creates a new branch of a given ledger"
  [])


;; db operations

(defn db
  "Retrieves latest db, or optionally a db at a moment in time
  and/or permissioned to a specific identity."
  ([ledger] (db ledger nil))
  ([ledger opts]
   (if opts
     (throw (ex-info "DB opts not yet implemented"
                     {:status 500 :error :db/unexpected-error}))
     (ledger/-db ledger opts))))


(defn wrap-policy
  "Wraps a db object with specified permission attributes.
  When requesting a db from a ledger, permission attributes can
  be requested at that point, however if one has a db already, this
  allows the permission attributes to be modified.

  Returns promise"
  ([db identity-map]
   (wrap-policy db identity-map nil))
  ([db identity-map context]
   (promise-wrap
     (let [parsed-ctx (json-ld/parse-context context)
           policy-id  (perm/parse-policy-identity identity-map parsed-ctx)]
       (perm/wrap-policy db policy-id)))))


(defn query
  "Queries a db value and returns a promise with the results."
  ([db q] (query db q {}))
  ([db q opts]
   (promise-wrap (query-api/query db q opts))))

(defn query-connection
  "Queries the latest db in the ledger specified by the 'from' parameter in the
  query (what that actually looks like is format-specific). Returns a promise
  with the results."
  ([conn q] (query-connection conn q {}))
  ([conn q opts]
   (promise-wrap (query-api/query-connection conn q opts))))

(defn history
  "Return the change history over a specified time range. Optionally include the commit
  that produced the changes."
  [ledger query]
  (let [latest-db (ledger/-db ledger)
        res-chan  (query-api/history latest-db query)]
    (promise-wrap res-chan)))

(defn range
  "Performs a range scan against the specified index using test functions
  of >=, <=, >, <"
  ;; TODO - assert index is valid index type
  ([db index test match]
   (promise-wrap
     (query-range/index-range db index test match)))
  ([db index start-test start-match end-test end-match]
   (promise-wrap
     (query-range/index-range db index start-test start-match end-test end-match))))

(defn slice
  "Like range, but returns all flakes that match the supplied flake parts."
  [db index match]
  (promise-wrap
    (query-range/index-range db index = match)))

(defn expand-iri
  "Expands given IRI with the default database context, or provided context."
  ([context compact-iri]
   (json-ld/expand-iri compact-iri
                       (json-ld/parse-context context))))

(defn internal-id
  "Returns the internal Fluree integer id for a given IRI.
  This can be used for doing range scans, slices and for other
  more advanced needs."
  [db iri]
  (iri/encode-iri db iri))

;; reasoning APIs

(defn reason
  "Sets the reasoner type(s) to perform on a db.
  Reasoning is done in-memory at the db-level and is not persisted.

  Reasoning types currently supported are :datalog and :owl2rl.

  You can give a single reasoning type as an argument, or multiple
  as a sequential list/vector."
  ([db regimes] (reason db regimes nil nil))
  ([db regimes graph] (reason db regimes graph nil))
  ([db regimes graph opts]
   (promise-wrap
     (reasoner/reason db regimes graph opts))))

(defn reasoned-count
  "Returns a count of reasoned facts in the provided db."
  [db]
  (let [spot (-> db :novelty :spot)]
    (reduce (fn [n flake]
              (if (-> flake flake/m :reasoned)
                (inc n)
                n))
            0 spot)))

(defn reasoned-facts
  "Returns all reasoned facts in the provided db as  4-tuples of:
  [subject property object rule-iri]
  where the rule-iri is the @id of the rule that generated the fact

  Returns 4-tuples of  where
  the rule-iri is the @id of the rule that generated the fact.

  NOTE: Currently returns internal fluree ids for subject, property and object.
  This will be changed to return IRIs in a future release.

  Optional opts map can include:
  :group-by - :rule (default), :subject, :property"
  ([db] (reasoned-facts db {:group-by :rule}))
  ([db opts]
   (let [group-fn (case (:group-by opts)
                    nil nil
                    :subject (fn [p] (nth p 0))
                    :property (fn [p] (nth p 1))
                    :rule (fn [p] (nth p 3)))
         result   (->> db :novelty :spot
                       reasoner/reasoned-flakes
                       (map (fn [flake] [(flake/s flake)
                                         (flake/p flake)
                                         (flake/o flake)
                                         (-> flake flake/m :reasoned)])))]
     (if group-fn
       (group-by group-fn result)
       result))))

