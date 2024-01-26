(ns fluree.db.nameservice.remote
  (:require [fluree.db.nameservice.proto :as ns-proto]
            [fluree.db.method.remote.core :as remote]
            [clojure.core.async :as async :refer [go go-loop]]
            [fluree.db.util.core :as util #?(:clj :refer :cljs :refer-macros) [try* catch*]]
            [fluree.db.util.async :refer [<? go-try]]
            [fluree.db.util.json :as json]
            [fluree.db.util.log :as log]))

#?(:clj (set! *warn-on-reflection* true))

(defn remote-lookup
  [state server-state ledger-address opts]
  (go-try
    (let [head-commit  (<? (remote/remote-read state server-state ledger-address false))
          head-address (get head-commit "address")]
      head-address)))

(defn monitor-socket-messages
  [{:keys [conn-state msg-in] :as _remote-ns} websocket]
  (go-loop []
    (let [next-msg (async/<! msg-in)]
      (if next-msg
        (let [[_ message] next-msg
              parsed-msg (json/parse message false)
              ledger     (get parsed-msg "ledger")
              callback   (get-in @conn-state [:subscription ledger])]
          (if callback
            (try*
              (callback parsed-msg)
              (catch* e
                      (log/error "Subscription callback for ledger: " ledger " failed with error: " e)))
            (log/warn "No callback registered for ledger: " ledger))
          (recur))
        (do
          (log/info "Websocket messaging connection closed, closing websocket.")
          (remote/close-websocket websocket))))))

(defn launch-subscription-socket
  "Returns chan with websocket after successful connection, or exception. "
  [{:keys [server-state msg-in msg-out] :as remote-ns}]
  (go
    (let [ws (async/<! (remote/ws-connect server-state msg-in msg-out))]
      (if (util/exception? ws)
        (do
          (log/error "Error establishing websocket connection: " (ex-message ws))
          (ex-info (str "Error establishing websocket connection: " (ex-message ws))
                   {:status 400
                    :error  :db/websocket-error}))
        (do
          (log/info "Websocket connection established.")
          (monitor-socket-messages remote-ns ws)
          ws)))))


(defn subscribe
  [ns-state ledger-alias callback]
  (if (fn? callback)
    (swap! ns-state assoc-in [:subscription ledger-alias] callback)
    (throw (ex-info (str "Subscription request for " ledger-alias
                         " failed. Callback must be a function, provided: " (pr-str callback))
                    {:status 400
                     :error  :db/invalid-fn}))))

(defn unsubscribe
  [ns-state ledger-alias]
  (swap! ns-state update :subscription dissoc ledger-alias))

(defrecord RemoteNameService
  [conn-state server-state sync? msg-in msg-out]
  ns-proto/iNameService
  (-lookup [_ ledger-alias] (remote-lookup conn-state server-state ledger-alias nil))
  (-lookup [_ ledger-alias opts] (remote-lookup conn-state server-state ledger-alias opts))
  (-push [_ commit-data] (throw (ex-info "Unsupported RemoteNameService op: push" {})))
  (-subscribe [nameservice ledger-alias callback] (subscribe conn-state ledger-alias callback))
  (-unsubscribe [nameservice ledger-alias] (unsubscribe conn-state ledger-alias))
  (-sync? [_] sync?)
  (-ledgers [nameservice opts] (throw (ex-info "Unsupported RemoteNameService op: ledgers" {})))
  (-address [_ ledger-alias {:keys [branch] :or {branch :main} :as _opts}]
    (go (str ledger-alias "/" (name branch) "/head")))
  (-alias [_ ledger-address]
    ledger-address)
  (-close [nameservice]
    (async/close! msg-in)
    (async/close! msg-out)))

(defn initialize
  [server-state conn-state]
  (go-try
    (let [msg-in    (async/chan)
          msg-out   (async/chan)
          remote-ns (map->RemoteNameService {:server-state server-state
                                             :msg-in       msg-in
                                             :msg-out      msg-out
                                             :conn-state   (or conn-state (atom nil))
                                             :sync?        true})
          websocket (async/<! (launch-subscription-socket remote-ns))]
      (if (util/exception? websocket)
        (ns-proto/-close remote-ns)
        remote-ns))))
