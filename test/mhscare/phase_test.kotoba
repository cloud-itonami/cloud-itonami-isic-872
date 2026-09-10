(ns mhscare.phase-test
  (:require [clojure.test :refer [deftest is testing]]
            [mhscare.phase :as phase]))

(deftest test-phase-0-read-only
  (testing "Phase 0 is read-only"
    (is (not (phase/can-auto-commit? :log-resident-note 0)))))

(deftest test-phase-1-no-auto-commit
  (testing "Phase 1 approval-gated (no auto-commit)"
    (is (not (phase/can-auto-commit? :log-resident-note 1)))))

(deftest test-phase-2-no-auto-commit
  (testing "Phase 2 coordination-gated (no auto-commit)"
    (is (not (phase/can-auto-commit? :schedule-family-or-guardian-visit 2)))))

(deftest test-phase-3-auto-commit-log-resident-note
  (testing "Phase 3 auto-commits log-resident-note"
    (is (phase/can-auto-commit? :log-resident-note 3))))

(deftest test-phase-3-auto-commit-schedule-visit
  (testing "Phase 3 auto-commits schedule-family-or-guardian-visit"
    (is (phase/can-auto-commit? :schedule-family-or-guardian-visit 3))))

(deftest test-phase-3-auto-commit-coordinate-supply
  (testing "Phase 3 auto-commits coordinate-supply-request"
    (is (phase/can-auto-commit? :coordinate-supply-request 3))))

(deftest test-phase-3-auto-commit-schedule-shift
  (testing "Phase 3 auto-commits schedule-staff-shift-proposal"
    (is (phase/can-auto-commit? :schedule-staff-shift-proposal 3))))

(deftest test-flag-safety-concern-never-auto-commits
  (testing "flag-safety-concern never auto-commits at any phase"
    (is (not (phase/can-auto-commit? :flag-safety-concern 0)))
    (is (not (phase/can-auto-commit? :flag-safety-concern 1)))
    (is (not (phase/can-auto-commit? :flag-safety-concern 2)))
    (is (not (phase/can-auto-commit? :flag-safety-concern 3)))))

(deftest test-valid-phases
  (testing "Valid phases are 0-3"
    (is (phase/valid-phase? 0))
    (is (phase/valid-phase? 1))
    (is (phase/valid-phase? 2))
    (is (phase/valid-phase? 3))
    (is (not (phase/valid-phase? 4)))
    (is (not (phase/valid-phase? -1)))))

(deftest test-phase-names
  (testing "Phase names match enum"
    (is (= :read-only (phase/phase-name 0)))
    (is (= :intake-gated (phase/phase-name 1)))
    (is (= :coordination-gated (phase/phase-name 2)))
    (is (= :auto-commit (phase/phase-name 3)))))
