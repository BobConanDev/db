(ns fluree.db.conn.s3
  (:require [cognitect.aws.client.api :as aws]
            [fluree.db.nameservice.s3 :as ns-s3]
            [clojure.core.async :as async :refer [go]]
            [fluree.crypto :as crypto]
            [fluree.db.conn.cache :as conn-cache]
            [fluree.db.connection :as connection]
            [fluree.db.index :as index]
            [fluree.db.indexer.default :as idx-default]
            [fluree.db.ledger :as ledger]
            [fluree.db.serde.json :refer [json-serde]]
            [fluree.db.indexer.storage :as index-storage]
            [fluree.db.util.async :refer [<? go-try]]
            [fluree.db.util.core :as util]
            [fluree.db.util.json :as json]
            [fluree.db.util.log :as log]
            [fluree.json-ld :as json-ld]
            [fluree.db.storage :as storage]
            [fluree.db.storage.s3 :as s3-storage])
  (:import (java.io Writer)))

(set! *warn-on-reflection* true)

(defn write-data
  [{:keys [store] :as _conn} ledger data-type data]
  (go-try
    (let [alias    (ledger/-alias ledger)
          branch   (-> ledger ledger/-branch :name name)
          json     (if (string? data)
                     data
                     (json-ld/normalize-data data))
          bytes    (.getBytes ^String json)
          hash     (crypto/sha2-256 bytes :hex)
          type-dir (name data-type)
          path     (str alias
                        (when branch (str "/" branch))
                        (str "/" type-dir "/")
                        hash ".json")
          result   (<? (storage/write store path bytes))]
      {:name    path
       :hash    hash
       :json    json
       :size    (count json)
       :address (:address result)})))

(defn read-commit
  [{:keys [store] :as _conn} address]
  (go-try
    (let [commit-data (<? (storage/read store address))]
      (json/parse commit-data false))))

(defn write-commit
  [conn ledger commit-data]
  (write-data conn ledger :commit commit-data))

(defn write-index
  [conn ledger index-type index-data]
  (write-data conn ledger (str "index/" (name index-type)) index-data))

(defn read-index
  [{:keys [store] :as _conn} index-address]
  (go-try
    (let [index-data (<? (storage/read store index-address))]
      (json/parse index-data true))))


(defrecord S3Connection [id state ledger-defaults parallelism lru-cache-atom nameservices store]
  connection/iStorage
  (-c-read [conn commit-key] (read-commit conn commit-key))
  (-c-write [conn ledger commit-data] (write-commit conn ledger commit-data))
  (-txn-read [_ txn-key]
    (go-try
      (let [txn-data (<? (storage/read store txn-key))]
        (json/parse txn-data false))))
  (-txn-write [conn ledger txn-data]
    (write-data conn ledger :txn txn-data))
  (-index-file-write [conn ledger index-type index-data]
    (write-index conn ledger index-type index-data))
  (-index-file-read [conn index-address]
    (read-index conn index-address))

  connection/iConnection
  (-close [_] (swap! state assoc :closed? true))
  (-closed? [_] (boolean (:closed? @state)))
  (-did [_] (:did ledger-defaults))
  (-msg-in [_ _] (throw (ex-info "Unsupported S3Connection op: msg-in" {})))
  (-msg-out [_ _] (throw (ex-info "Unsupported S3Connection op: msg-out" {})))
  (-nameservices [_] nameservices)
  (-state [_] @state)
  (-state [_ ledger] (get @state ledger))

  index/Resolver
  (resolve [conn {:keys [id leaf tempid] :as node}]
    (let [cache-key [::resolve id tempid]]
      (if (= :empty id)
        (index-storage/resolve-empty-node node)
        (conn-cache/lru-lookup lru-cache-atom cache-key
                               (fn [_]
                                 (index-storage/resolve-index-node
                                   conn node
                                   (fn [] (conn-cache/lru-evict lru-cache-atom
                                                               cache-key)))))))))


(defmethod print-method S3Connection [^S3Connection conn, ^Writer w]
  (.write w (str "#S3Connection "))
  (binding [*out* w]
    (pr (connection/printer-map conn))))

(defn ledger-defaults
  [{:keys [did]}]
  {:did did})

(defn default-S3-nameservice
  "Returns S3 nameservice or will throw if storage-path generates an exception."
  [s3-client s3-bucket s3-prefix]
  (ns-s3/initialize s3-client s3-bucket s3-prefix))

(defn connect
  "Create a new S3 connection."
  [{:keys [defaults parallelism s3-endpoint s3-bucket s3-prefix lru-cache-atom
           memory serializer nameservices]
    :or   {serializer (json-serde)} :as _opts}]
  (go
    (let [aws-opts       (cond-> {:api :s3}
                           s3-endpoint (assoc :endpoint-override s3-endpoint))
          client         (aws/client aws-opts)
          conn-id        (str (random-uuid))
          state          (connection/blank-state)
          nameservices*  (util/sequential
                           (or nameservices (default-S3-nameservice client s3-bucket s3-prefix)))
          cache-size     (conn-cache/memory->cache-size memory)
          lru-cache-atom (or lru-cache-atom
                             (atom (conn-cache/create-lru-cache cache-size)))
          s3-store       (s3-storage/open s3-bucket s3-prefix s3-endpoint)]
      (map->S3Connection {:id              conn-id
                          :store           s3-store
                          :memory          memory
                          :state           state
                          :ledger-defaults (ledger-defaults defaults)
                          :serializer      serializer
                          :parallelism     parallelism
                          :msg-in-ch       (async/chan)
                          :msg-out-ch      (async/chan)
                          :nameservices    nameservices*
                          :lru-cache-atom  lru-cache-atom}))))
