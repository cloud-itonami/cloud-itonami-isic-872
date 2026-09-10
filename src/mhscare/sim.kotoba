(ns mhscare.sim
  "Simulation harness: run a proposal end-to-end for testing/demo,
  driving the REAL compiled `mhscare.operation/build` StateGraph via
  `langgraph.graph/run*` -- not a hand-rolled pipeline call."
  (:require [langgraph.graph :as g]
            [mhscare.operation :as operation]
            [mhscare.store :as store]))

(defn run-demo
  "`:exec-fn` entry point for `clojure -X:run` -- takes (and ignores) the
  kwargs map `-X` always passes."
  [& [_opts]]
  (let [s (store/seed-db)
        actor (operation/build s)
        request {:op :flag-safety-concern :resident-id "resident-1"}
        result (g/run* actor {:request request :phase-num 3} {:thread-id "demo-run"})
        state (:state result)]
    (println "=== MhsCare Residential Care Simulation ===")
    (println "Resident: resident-1")
    (println "Operation: :flag-safety-concern")
    (println "Graph status:" (:status result))
    (println "Decision:" (:decision state))
    (println "Ledger:")
    (doseq [fact (store/ledger s)]
      (println " " fact))))
