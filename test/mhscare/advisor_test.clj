(ns mhscare.advisor-test
  (:require [clojure.test :refer [deftest is testing]]
            [mhscare.advisor :as advisor]
            [mhscare.store :as store]))

(deftest test-mock-advise-log-resident-note
  (testing "Mock advisor proposes log-resident-note"
    (let [st (store/seed-db)
          request {:op :log-resident-note :resident-id "resident-1"}
          proposal (advisor/advise :mock st request)]
      (is (= :log-resident-note (:op proposal)))
      (is (= "resident-1" (:resident-id proposal)))
      (is (= :propose (:effect proposal)))
      (is (> (:confidence proposal) 0.7)))))

(deftest test-mock-advise-schedule-visit
  (testing "Mock advisor proposes schedule-family-or-guardian-visit"
    (let [st (store/seed-db)
          request {:op :schedule-family-or-guardian-visit :resident-id "resident-1"}
          proposal (advisor/advise :mock st request)]
      (is (= :schedule-family-or-guardian-visit (:op proposal)))
      (is (= :propose (:effect proposal))))))

(deftest test-mock-advise-coordinate-supply
  (testing "Mock advisor proposes coordinate-supply-request"
    (let [st (store/seed-db)
          request {:op :coordinate-supply-request :resident-id "resident-1"}
          proposal (advisor/advise :mock st request)]
      (is (= :coordinate-supply-request (:op proposal)))
      (is (= :propose (:effect proposal))))))

(deftest test-mock-advise-schedule-shift
  (testing "Mock advisor proposes schedule-staff-shift-proposal"
    (let [st (store/seed-db)
          request {:op :schedule-staff-shift-proposal :resident-id "resident-1"}
          proposal (advisor/advise :mock st request)]
      (is (= :schedule-staff-shift-proposal (:op proposal)))
      (is (= :propose (:effect proposal))))))

(deftest test-mock-advise-flag-safety-concern
  (testing "Mock advisor proposes flag-safety-concern"
    (let [st (store/seed-db)
          request {:op :flag-safety-concern :resident-id "resident-1"}
          proposal (advisor/advise :mock st request)]
      (is (= :flag-safety-concern (:op proposal)))
      (is (= :propose (:effect proposal)))
      (is (>= (:confidence proposal) 0.8)))))

(deftest test-mock-advise-unknown-op
  (testing "Mock advisor returns zero-confidence for unknown op"
    (let [st (store/seed-db)
          request {:op :unknown-op :resident-id "resident-1"}
          proposal (advisor/advise :mock st request)]
      (is (= :unknown (:op proposal)))
      (is (= 0.0 (:confidence proposal))))))

(deftest test-all-proposals-have-effect-propose
  (testing "All mock proposals have :effect :propose"
    (let [st (store/seed-db)
          ops [:log-resident-note :schedule-family-or-guardian-visit :coordinate-supply-request
               :schedule-staff-shift-proposal :flag-safety-concern]
          proposals (map #(advisor/advise :mock st {:op % :resident-id "resident-1"}) ops)]
      (is (every? #(= :propose (:effect %)) proposals)))))
