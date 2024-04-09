(ns fluree.db.conn.file
  (:require [clojure.core.async :as async :refer [go]]
            [fluree.db.util.async :refer [<? go-try]]
            [clojure.string :as str]
            [fluree.crypto :as crypto]
            [fluree.db.util.core :as util]
            [fluree.json-ld :as json-ld]
            [fluree.db.index :as index]
            [fluree.db.connection :as connection]
            [fluree.db.conn.cache :as conn-cache]
            [fluree.db.util.log :as log :include-macros true]
            [fluree.db.indexer.storage :as index-storage]
            [fluree.db.indexer.default :as idx-default]
            [fluree.db.serde.json :refer [json-serde]]
            [fluree.db.util.bytes :as bytes]
            [fluree.db.util.json :as json]
            [fluree.db.nameservice.filesystem :as ns-filesystem]
            [fluree.db.ledger :as ledger]
            [fluree.db.storage :as storage]
            [fluree.db.storage.file :as file-storage])
  #?(:clj (:import (java.io Writer))))

#?(:clj (set! *warn-on-reflection* true))

(defn- write-data
  [{:keys [store] :as _conn} ledger data-type data]
  (go-try
    (let [alias    (ledger/-alias ledger)
          branch   (name (:name (ledger/-branch ledger)))
          json     (if (string? data)
                     data
                     (json-ld/normalize-data data))
          bytes    (bytes/string->UTF8 json)
          hash     (crypto/sha2-256 bytes :hex)
          type-dir (name data-type)
          path     (str alias
                        (when branch (str "/" branch))
                        (str "/" type-dir "/")
                        hash ".json")

          {:keys [hash address]} (<? (storage/write store path bytes))]
      {:name    path
       :hash    hash
       :json    json
       :size    (count json)
       :address address})))

(defn read-data [conn address keywordize?]
  (go-try
    (-> (<? (storage/read (:store conn) address))
        (json/parse keywordize?))))

(defn close
  [id state]
  (log/info "Closing file connection" id)
  (swap! state assoc :closed? true))

(defrecord FileConnection [id state ledger-defaults parallelism msg-in-ch store
                           nameservices serializer msg-out-ch lru-cache-atom]

  connection/iStorage
  (-c-read [conn commit-key] (read-data conn commit-key false))
  (-c-write [conn ledger commit-data] (write-data conn ledger :commit commit-data))
  (-txn-read [conn txn-key] (read-data conn txn-key false))
  (-txn-write [conn ledger txn-data] (write-data conn ledger :txn txn-data))
  (-index-file-write [conn ledger index-type index-data]
    (write-data conn ledger (str "index/" (name index-type)) index-data))
  (-index-file-read [conn index-address] (read-data conn index-address true))

  connection/iConnection
  (-close [_] (close id state))
  (-closed? [_] (boolean (:closed? @state)))
  (-did [_] (:did ledger-defaults))
  (-msg-in [conn msg] (throw (ex-info "Unsupported FileConnection op: msg-in" {})))
  (-msg-out [conn msg] (throw (ex-info "Unsupported FileConnection op: msg-out" {})))
  (-nameservices [_] nameservices)
  (-state [_] @state)
  (-state [_ ledger] (get @state ledger))

  index/Resolver
  (resolve
    [conn {:keys [id leaf tempid] :as node}]
    (let [cache-key [::resolve id tempid]]
      (if (= :empty id)
        (index-storage/resolve-empty-node node)
        (conn-cache/lru-lookup
          lru-cache-atom
          cache-key
          (fn [_]
            (index-storage/resolve-index-node conn node
                                        (fn [] (conn-cache/lru-evict lru-cache-atom cache-key)))))))))

#?(:cljs
   (extend-type FileConnection
     IPrintWithWriter
     (-pr-writer [conn w opts]
       (-write w "#FileConnection ")
       (-write w (pr (connection/printer-map conn))))))

#?(:clj
   (defmethod print-method FileConnection [^FileConnection conn, ^Writer w]
     (.write w (str "#FileConnection "))
     (binding [*out* w]
       (pr (connection/printer-map conn)))))

(defn trim-last-slash
  [s]
  (if (str/ends-with? s "/")
    (subs s 0 (dec (count s)))
    s))

(defn ledger-defaults
  [{:keys [did]}]
  {:did did})

(defn default-file-nameservice
  "Returns file nameservice or will throw if storage-path generates an exception."
  [path]
  (ns-filesystem/initialize path))

(defn connect
  "Create a new file system connection."
  [{:keys [defaults parallelism storage-path lru-cache-atom memory serializer nameservices]
    :or   {serializer (json-serde)} :as _opts}]
  (go
    (let [conn-id        (str (random-uuid))
          state          (connection/blank-state)
          nameservices*  (util/sequential
                           (or nameservices (default-file-nameservice storage-path)))
          cache-size     (conn-cache/memory->cache-size memory)
          lru-cache-atom (or lru-cache-atom (atom (conn-cache/create-lru-cache cache-size)))
          file-store     (file-storage/open storage-path)]
      ;; TODO - need to set up monitor loops for async chans
      (map->FileConnection {:id              conn-id
                            :store           file-store
                            :ledger-defaults (ledger-defaults defaults)
                            :serializer      serializer
                            :parallelism     parallelism
                            :msg-in-ch       (async/chan)
                            :msg-out-ch      (async/chan)
                            :nameservices    nameservices*
                            :state           state
                            :lru-cache-atom  lru-cache-atom}))))
