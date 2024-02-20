(ns fluree.db.reasoner.core
  (:require [fluree.db.flake :as flake]
            [fluree.db.util.log :as log]
            [fluree.db.util.async :refer [go-try <?]]
            [fluree.db.reasoner.resolve :as resolve]
            [fluree.db.reasoner.graph :refer [task-queue add-rule-dependencies]]))

#?(:clj (set! *warn-on-reflection* true))

(defn reasoned-flake?
  "Returns truthy if the flake has been generated by reasoner"
  [flake]
  (-> flake flake/p :reasoned))

(defn non-reasoned-flakes
  "Takes a sequence of flakes and removes any flakes which are reasoned.

  This is primarily used to remove reasoned flakes from commits."
  [flakes]
  (remove reasoned-flake? flakes))

(defn reasoned-flakes
  "Takes a sequence of flakes and keeps only reasoned flakes"
  [flakes]
  (filter reasoned-flake? flakes))

(defn rules-graph
  [db]
  (go-try
    (let [rules (<? (resolve/find-rules db))]
      (->> rules
           (resolve/rules->graph db)
           add-rule-dependencies))))

(defn schedule
  "Returns list of rule @id values in the order they should be run.

  If optional result-summary is provided, rules that don't need to be
  re-run will be filtered out.

  A result summary is list/set of the rule dependency patterns which
  match newly created Flakes from the last run. When the result-summary
  is empty, no rules will be left to run, but based on the dependencies
  it is possible no rules will be left to run even if the result-summary
  is non-empty"
  ([rules]
   (task-queue rules))
  ([rules result-summary]
   (task-queue rules result-summary)))

