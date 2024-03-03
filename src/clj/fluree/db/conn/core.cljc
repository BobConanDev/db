(ns fluree.db.conn.core
  (:require [clojure.core.async :as async]
            [fluree.db.constants :as const]
            [fluree.db.util.core :as util :refer [try* catch* get-first-value]]
            [fluree.db.util.log :as log :include-macros true]
            [fluree.db.util.async :refer [<? go-try]]
            [fluree.json-ld :as json-ld]
            [fluree.db.connection :as connection]
            [fluree.db.ledger.proto :as ledger-proto]))

;; state machine for connections

#?(:clj (set! *warn-on-reflection* true))


(comment
  ;; state machine looks like this:
  {:ledger {"ledger-a" {:event-fn :main-system-event-fn ;; returns async-chan response once complete
                        :subs     {:sub-id :sub-fn} ;; active subscriptions
                        ;; map of branches, along with current/default branch
                        :branches {}
                        :branch   {}}}


   :await  {:msg-id :async-res-ch} ;; map of msg-ids to response chans for messages awaiting responses
   :stats  {}}) ;; any stats about the connection itself


(defn blank-state
  "Returns top-level state for connection"
  []
  (atom
    {:ledger  {}
     :await   {}
     :stats   {}
     :closed? false}))

(defn register-ledger
  "Creates a promise-chan and saves it in a cache of ledgers being held
  in-memory on the conn.

  Returns a two-tuple of
  [not-cached? promise-chan]

  where not-cached? is true if a new promise-chan was created, false if an
  existing promise-chan was found.

  promise-chan is the new promise channel that must have the final ledger `put!` into it
  assuming success? is true, otherwise it will return the existing found promise-chan when
  success? is false"
  [{:keys [state] :as _conn} ledger-alias]
  (let [new-p-chan  (async/promise-chan)
        new-state   (swap! state update-in [:ledger ledger-alias]
                           (fn [existing]
                             (or existing new-p-chan)))
        p-chan      (get-in new-state [:ledger ledger-alias])
        not-cached? (= p-chan new-p-chan)]
    (log/debug "Registering ledger: " ledger-alias " not-cached? " not-cached?)
    [not-cached? p-chan]))

(defn release-ledger
  "Opposite of register-ledger. Removes reference to a ledger from conn"
  [{:keys [state] :as _conn} ledger-alias]
  (swap! state update :ledger dissoc ledger-alias))

(defn cached-ledger
  "Returns a cached ledger from the connection if it is cached, else nil"
  [{:keys [state] :as _conn} ledger-alias]
  (get-in @state [:ledger ledger-alias]))

(defn notify-ledger
  [conn commit-map]
  (go-try
    (let [expanded-commit (json-ld/expand commit-map)
          ledger-alias    (get-first-value expanded-commit const/iri-alias)]
      (if ledger-alias
        (if-let [ledger-c (cached-ledger conn ledger-alias)]
          (<? (ledger-proto/-notify (<? ledger-c) expanded-commit))
          (log/debug "No cached ledger found for commit: " commit-map))
        (log/warn "Notify called with a data that does not have a ledger alias."
                  "Are you sure it is a commit?: " commit-map)))))

(defn printer-map
  "Returns map of important data for print writer"
  [conn]
  {:id              (:id conn)
   :stats           (get @(:state conn) :stats)
   :cached-ledgers  (keys (get @(:state conn) :ledgers))
   :nameservices    (mapv type (:nameservices conn))
   :ledger-defaults (:ledger-defaults conn)
   :parallelism     (:parallelism conn)})
