(ns fluree.db.json-ld.reify
  (:require [fluree.db.storage :as storage]
            [fluree.db.constants :as const]
            [fluree.db.util.async :refer [<? go-try]]
            [fluree.db.util.core :as util :refer [get-first get-first-id get-first-value]]
            [fluree.db.util.log :as log :include-macros true]
            [fluree.json-ld :as json-ld]))

;; generates a db/ledger from persisted data
#?(:clj (set! *warn-on-reflection* true))

;; TODO - validate commit signatures
(defn validate-commit-proof
  "Run proof validation, if exists.
  Return actual commit data. In the case of a VerifiableCredential this is
  the `credentialSubject`."
  [proof]
  ;; TODO - returning true for now
  true)

(defn credential?
  [commit-data]
  (contains? commit-data const/iri-cred-subj))

(defn verify-commit
  "Given a full commit json, returns two-tuple of [commit-data commit-proof]"
  [commit-data]
  (if (credential? commit-data)
    (let [credential-subject (get-first commit-data const/iri-cred-subj)]
      (validate-commit-proof commit-data)
      [credential-subject commit-data])
    [commit-data nil]))

(defn read-commit
  [{:keys [store] :as _conn} commit-address]
  (go-try
    (let [commit-data   (<? (storage/read-json store commit-address))
          addr-key-path (if (contains? commit-data "credentialSubject")
                          ["credentialSubject" "address"]
                          ["address"])]
      (log/trace "read-commit at:" commit-address "data:" commit-data)
      (when commit-data
        (-> commit-data
            (assoc-in addr-key-path commit-address)
            json-ld/expand
            verify-commit)))))

(defn read-db
  [{:keys [store] :as _conn} db-address]
  (go-try
    (let [file-data (<? (storage/read-json store db-address))
          db        (assoc file-data "f:address" db-address)]
      (json-ld/expand db))))

(defn trace-commits
  "Returns a list of two-tuples each containing [commit proof] as applicable.
  First commit will be t value of `from-t` and increment from there."
  [conn latest-commit from-t]
  (go-try
    (loop [[commit proof] (verify-commit latest-commit)
           last-t         nil
           commit-tuples  (list)]
      (let [dbid             (get-first-id commit const/iri-data)
            db-address       (-> commit
                                 (get-first const/iri-data)
                                 (get-first-value const/iri-address))
            db-data-jsonld   (<? (read-db conn db-address))
            prev-commit-addr (-> commit
                                 (get-first const/iri-previous)
                                 (get-first-value const/iri-address))
            commit-t         (-> commit
                                 (get-first const/iri-data)
                                 (get-first-value const/iri-fluree-t))
            commit-tuples*   (conj commit-tuples [commit proof db-data-jsonld])]
        (when (or (nil? commit-t)
                  (and last-t (not= (dec last-t) commit-t)))
          (throw (ex-info (str "Commit t values are inconsistent. Last commit t was: " last-t
                               "and the prevCommit t value is: " commit-t " for commit: " commit)
                          {:status      500
                           :error       :db/invalid-commit
                           :commit-data (if (> (count (str commit)) 500)
                                          (str (subs (str commit) 0 500) "...")
                                          (str commit))})))
        (when-not (and dbid db-address)
          (throw (ex-info (str "Commit is not a properly formatted Fluree commit, missing db id/address: "
                               commit ".")
                          {:status      500
                           :error       :db/invalid-commit
                           :commit-data (if (> (count (str commit)) 500)
                                          (str (subs (str commit) 0 500) "...")
                                          (str commit))})))
        (if (= from-t commit-t)
          commit-tuples*
          (let [verified-commit (<? (read-commit conn prev-commit-addr))]
            (recur verified-commit commit-t commit-tuples*)))))))
