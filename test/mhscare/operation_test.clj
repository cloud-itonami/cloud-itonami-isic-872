(ns mhscare.operation-test
  (:require [clojure.test :refer [deftest is testing]]
            [mhscare.operation :as op]
            [mhscare.store :as store]
            [mhscare.governor :as governor]))

(deftest test-clean-proposal-phase-3-commits
  (testing "Clean proposal commits at phase 3"
    (let [st (store/seed-db)
          request {:op :log-resident-note :resident-id "resident-1"}
          result (op/run-proposal st request :mock 3)]
      (is (= :commit (:decision result)))
      (is (= :committed (:execution result))))))

(deftest test-proposal-escalates-phase-1
  (testing "Proposal escalates at phase 1 (approval-gated)"
    (let [st (store/seed-db)
          request {:op :log-resident-note :resident-id "resident-1"}
          result (op/run-proposal st request :mock 1)]
      (is (= :escalate (:decision result))))))

(deftest test-unverified-resident-holds
  (testing "Proposal with unverified resident holds"
    (let [st (store/seed-db)
          request {:op :log-resident-note :resident-id "resident-3"}
          result (op/run-proposal st request :mock 3)]
      (is (= :hold (:decision result))))))

(deftest test-invalid-request-holds
  (testing "Invalid request (missing resident-id) holds"
    (let [st (store/seed-db)
          request {:op :log-resident-note}
          result (op/run-proposal st request :mock 3)]
      (is (= :hold (:decision result))))))

(deftest test-safety-concern-always-escalates
  (testing "Safety concern always escalates even at phase 3"
    (let [st (store/seed-db)
          request {:op :flag-safety-concern :resident-id "resident-1"}
          result (op/run-proposal st request :mock 3)]
      (is (= :escalate (:decision result))))))

(deftest test-ledger-records-escalation
  (testing "Escalation is recorded in ledger"
    (let [st (store/seed-db)
          request {:op :flag-safety-concern :resident-id "resident-1"}
          result (op/run-proposal st request :mock 3)
          ledger (store/ledger st)]
      (is (> (count ledger) 0))
      (is (some #(= :proposal-escalated (:op %)) ledger)))))

(deftest test-ledger-records-hold
  (testing "Hold is recorded in ledger"
    (let [st (store/seed-db)
          request {:op :log-resident-note :resident-id "resident-3"}
          result (op/run-proposal st request :mock 3)
          ledger (store/ledger st)]
      (is (> (count ledger) 0))
      (is (some #(= :proposal-held (:op %)) ledger)))))

(deftest test-ledger-records-commit
  (testing "Commit is recorded in ledger"
    (let [st (store/seed-db)
          request {:op :log-resident-note :resident-id "resident-1"}
          result (op/run-proposal st request :mock 3)
          ledger (store/ledger st)]
      (is (> (count ledger) 0))
      (is (some #(= :proposal-committed (:op %)) ledger)))))
