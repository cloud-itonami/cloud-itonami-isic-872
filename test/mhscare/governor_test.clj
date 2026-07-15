(ns mhscare.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [mhscare.governor :as governor]
            [mhscare.store :as store]))

(deftest test-resident-unverified-violation
  (testing "Governor rejects unverified resident"
    (let [st (store/seed-db)
          proposal {:op :log-resident-note :resident-id "resident-3" :effect :propose :confidence 0.9}
          result (governor/check {:resident-id "resident-3"} :test proposal st)]
      (is (not (:ok? result)))
      (is (some #(= :resident-unverified (:rule %)) (:violations result))))))

(deftest test-resident-not-found-violation
  (testing "Governor rejects nonexistent resident"
    (let [st (store/seed-db)
          proposal {:op :log-resident-note :resident-id "nobody" :effect :propose :confidence 0.9}
          result (governor/check {:resident-id "nobody"} :test proposal st)]
      (is (not (:ok? result)))
      (is (some #(= :resident-unverified (:rule %)) (:violations result))))))

(deftest test-effect-not-propose-violation
  (testing "Governor rejects effect != :propose"
    (let [st (store/seed-db)
          proposal {:op :log-resident-note :resident-id "resident-1" :effect :execute :confidence 0.9}
          result (governor/check {:resident-id "resident-1"} :test proposal st)]
      (is (not (:ok? result)))
      (is (some #(= :effect-not-propose (:rule %)) (:violations result))))))

(deftest test-scope-excluded-medication
  (testing "Governor rejects proposal mentioning medication"
    (let [st (store/seed-db)
          proposal {:op :log-resident-note :resident-id "resident-1" :effect :propose
                    :summary "提案は投薬管理の変更を含む" :confidence 0.9}
          result (governor/check {:resident-id "resident-1"} :test proposal st)]
      (is (not (:ok? result)))
      (is (some #(= :scope-excluded (:rule %)) (:violations result))))))

(deftest test-scope-excluded-clinical-diagnosis
  (testing "Governor rejects proposal mentioning clinical diagnosis"
    (let [st (store/seed-db)
          proposal {:op :log-resident-note :resident-id "resident-1" :effect :propose
                    :summary "clinical diagnosis required" :confidence 0.9}
          result (governor/check {:resident-id "resident-1"} :test proposal st)]
      (is (not (:ok? result)))
      (is (some #(= :scope-excluded (:rule %)) (:violations result))))))

(deftest test-scope-excluded-care-plan
  (testing "Governor rejects proposal mentioning care plan change"
    (let [st (store/seed-db)
          proposal {:op :log-resident-note :resident-id "resident-1" :effect :propose
                    :summary "Recommend care-plan change" :confidence 0.9}
          result (governor/check {:resident-id "resident-1"} :test proposal st)]
      (is (not (:ok? result)))
      (is (some #(= :scope-excluded (:rule %)) (:violations result))))))

(deftest test-scope-excluded-restraint
  (testing "Governor rejects proposal mentioning restraint"
    (let [st (store/seed-db)
          proposal {:op :log-resident-note :resident-id "resident-1" :effect :propose
                    :summary "Consider physical restraint" :confidence 0.9}
          result (governor/check {:resident-id "resident-1"} :test proposal st)]
      (is (not (:ok? result)))
      (is (some #(= :scope-excluded (:rule %)) (:violations result))))))

(deftest test-scope-excluded-end-of-life
  (testing "Governor rejects proposal mentioning end-of-life"
    (let [st (store/seed-db)
          proposal {:op :log-resident-note :resident-id "resident-1" :effect :propose
                    :summary "Discuss end-of-life options" :confidence 0.9}
          result (governor/check {:resident-id "resident-1"} :test proposal st)]
      (is (not (:ok? result)))
      (is (some #(= :scope-excluded (:rule %)) (:violations result))))))

(deftest test-op-not-allowed
  (testing "Governor rejects op outside allowlist"
    (let [st (store/seed-db)
          proposal {:op :unauthorized-op :resident-id "resident-1" :effect :propose :confidence 0.9}
          result (governor/check {:resident-id "resident-1"} :test proposal st)]
      (is (not (:ok? result)))
      (is (some #(= :op-not-allowed (:rule %)) (:violations result))))))

(deftest test-legitimate-safety-concern-not-blocked
  (testing "Governor allows safety-concern with legitimate concern description"
    (let [st (store/seed-db)
          proposal {:op :flag-safety-concern :resident-id "resident-1" :effect :propose
                    :summary "Resident observed difficulty concentrating"
                    :confidence 0.85}
          result (governor/check {:resident-id "resident-1"} :test proposal st)]
      (is (:ok? result)))))

(deftest test-confidence-below-floor
  (testing "Governor escalates low-confidence proposal"
    (let [st (store/seed-db)
          proposal {:op :log-resident-note :resident-id "resident-1" :effect :propose :confidence 0.5}
          result (governor/check {:resident-id "resident-1"} :test proposal st)]
      (is (:ok? result))
      (is (:escalate? result)))))

(deftest test-flag-safety-concern-always-escalates
  (testing "Governor always escalates flag-safety-concern"
    (let [st (store/seed-db)
          proposal {:op :flag-safety-concern :resident-id "resident-1" :effect :propose :confidence 0.95}
          result (governor/check {:resident-id "resident-1"} :test proposal st)]
      (is (:ok? result))
      (is (:escalate? result)))))

(deftest test-high-stakes-flag
  (testing "Governor marks safety-concern as high-stakes"
    (let [st (store/seed-db)
          proposal {:op :flag-safety-concern :resident-id "resident-1" :effect :propose :confidence 0.95}
          result (governor/check {:resident-id "resident-1"} :test proposal st)]
      (is (:high-stakes? result)))))

(deftest test-clean-high-confidence-proposal
  (testing "Governor accepts clean, high-confidence proposal"
    (let [st (store/seed-db)
          proposal {:op :log-resident-note :resident-id "resident-1" :effect :propose
                    :summary "Daily care note" :confidence 0.9}
          result (governor/check {:resident-id "resident-1"} :test proposal st)]
      (is (:ok? result))
      (is (not (:escalate? result)))
      (is (not (:hard? result))))))
