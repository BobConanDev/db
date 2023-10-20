(ns fluree.db.query.dataset
  (:refer-clojure :exclude [alias set]))

(defrecord DataSet [current names defaults])

(defn combine
  [named-map defaults]
  (->DataSet ::default named-map defaults))

(defn for-alias
  [ds alias]
  (-> ds :db-map (get alias)))

(defn defaults
  [ds]
  (:defaults ds))

(defn dataset?
  [ds]
  (instance? DataSet ds))
