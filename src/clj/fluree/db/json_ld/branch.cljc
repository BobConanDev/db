(ns fluree.db.json-ld.branch
  (:require [fluree.db.json-ld.commit-data :as commit-data]
            [fluree.db.flake :as flake]
            [fluree.db.dbproto :as dbproto]
            [fluree.db.util.log :as log :include-macros true])

  (:refer-clojure :exclude [name]))

#?(:clj (set! *warn-on-reflection* true))

;; branch operations on json-ld ledger

(defn current-branch
  "Returns current branch name."
  [ledger]
  (-> ledger
      :state
      deref
      :branch))

(defn branch-meta
  "Returns branch map data for current branch, or specified branch"
  ([ledger]
   (branch-meta ledger (current-branch ledger)))
  ([ledger branch]
   (-> ledger
       :state
       deref
       :branches
       (get branch))))

;; TODO - if you branch from an uncommitted branch, and then commit, commit the current-branch too
(defn new-branch-map
  "Returns a new branch map for specified branch name off of supplied
  current-branch."
  ([ledger-alias branch-name ns-addresses]
   (new-branch-map nil ledger-alias branch-name ns-addresses))
  ([current-branch-map ledger-alias branch-name ns-addresses]
   (let [{:keys [current-db]} current-branch-map]
     (let [initial-commit (commit-data/blank-commit ledger-alias branch-name ns-addresses)]
       (cond-> {:name      branch-name
                :commit    initial-commit
                :current-db current-db}
         (not-empty current-branch-map)
         (assoc :from (select-keys current-branch-map [:name :commit])))))))

(defn skipped-t?
  [new-t current-t]
  (and (not (or (nil? current-t)
                (zero? current-t))) ; when loading a ledger from disk, 't' will
                                    ; be zero but ledger 't' will be >= 1
       (flake/t-after? new-t (flake/next-t current-t))))

(defn updated-index?
  [current-commit new-commit]
  (flake/t-before? (commit-data/index-t current-commit)
                   (commit-data/index-t new-commit)))

(defn use-latest
  [new-db current-db]
  (let [new-t     (:t new-db)
        current-t (:t current-db)]
    (if (skipped-t? new-t current-t)
      (throw (ex-info (str "Unable to create new DB version on ledger. "
                           "current 't' value is: " current-t
                           " however new t value is: " new-t
                           ". Successive 't' values must be contiguous.")
                      {:status 500 :error :db/invalid-time}))
      (let [current-commit (:commit current-db)]
        (if (flake/t-before? new-t current-t)
          (let [outdated-commit (:commit new-db)
                latest-commit   (commit-data/use-latest-index current-commit outdated-commit)]
            (if (updated-index? current-commit latest-commit)
              (dbproto/-index-update current-db (:index latest-commit))
              current-db))
          (let [new-commit    (:commit new-db)
                latest-commit (commit-data/use-latest-index new-commit current-commit)]
            (if (updated-index? new-commit latest-commit)
              (dbproto/-index-update new-db (:index latest-commit))
              new-db)))))))

(defn update-db
  "Updates the latest staged db and returns new branch data."
  [{:keys [current-db] :as branch-data} db]
  (let [{:keys [commit] :as current-db*} (use-latest db current-db)]
    (assoc branch-data
           :commit commit
           :current-db current-db*)))

(defn updatable-commit?
  [current-commit new-commit]
  (let [current-t (commit-data/t current-commit)
        new-t     (commit-data/t new-commit)]
    (or (nil? current-t)
        (and (updated-index? current-commit new-commit)
             (not (flake/t-after? new-t current-t))) ; index update may come after multiple commits
        (= new-t (inc current-t)))))

(defn update-commit
  "There are 3 t values, the db's t, the 'commit' attached to the db's t, and
  then the ledger's latest commit t (in branch-data). The db 't' and db commit 't'
  should be the same at this point (just after committing the db). The ledger's latest
  't' should be the same (if just updating an index) or after the db's 't' value."
  [branch-map {new-commit :commit, db-t :t, :as db}]
  (let [{current-commit :commit} branch-map
        current-t                (commit-data/t current-commit)
        new-t                    (commit-data/t new-commit)]
    (if (= db-t new-t)
      (if (updatable-commit? current-commit new-commit)
        (update-db branch-map db)
        (throw (ex-info (str "Commit failed. current-t:" current-t
                             " new-t:" new-t
                             " current-index: " (commit-data/index-t current-commit)
                             " new-index: " (commit-data/index-t new-commit))
                        {:status 400 :error :db/invalid-commit})))
      (throw (ex-info (str "Unexpected Error updating commit database. "
                           "New database has an inconsistent t from its commit:"
                           db-t " and " new-t " respectively.")
                      {:status 500 :error :db/invalid-db})))))

(defn current-db
  "Returns latest db from branch data"
  [branch-map]
  (:current-db branch-map))
