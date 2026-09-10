(ns mhscare.store-test
  (:require [clojure.test :refer [deftest is testing]]
            [mhscare.store :as store]))

(deftest test-mem-store-demo
  (testing "MemStore demo data"
    (let [st (store/seed-db)
          residents (store/all-residents st)]
      (is (= 3 (count residents)))
      (is (every? :registered? residents)))))

(deftest test-resident-lookup
  (testing "resident lookup"
    (let [st (store/seed-db)
          r1 (store/resident st "resident-1")]
      (is (some? r1))
      (is (= "Emma Brown (age 14, in shelter)" (:name r1)))
      (is (:registered? r1))
      (is (:verified? r1)))))

(deftest test-unverified-resident
  (testing "unverified resident"
    (let [st (store/seed-db)
          r3 (store/resident st "resident-3")]
      (is (some? r3))
      (is (:registered? r3))
      (is (not (:verified? r3))))))

(deftest test-resident-not-found
  (testing "resident not found"
    (let [st (store/seed-db)
          r-none (store/resident st "nonexistent")]
      (is (nil? r-none)))))

(deftest test-mem-store-custom-residents
  (testing "MemStore with custom residents"
    (let [residents {"alex" {:resident-id "alex" :name "Alex" :registered? true :verified? true}
                     "jordan" {:resident-id "jordan" :name "Jordan" :registered? true :verified? true}}
          st (store/mem-store residents)
          all (store/all-residents st)]
      (is (= 2 (count all))))))

(deftest test-empty-store
  (testing "Empty store"
    (let [st (store/mem-store {})
          all (store/all-residents st)]
      (is (= 0 (count all))))))

(deftest test-ledger-append
  (testing "Ledger append"
    (let [st (store/seed-db)
          fact {:op :test-op :timestamp 123}
          result (store/append-ledger! st fact)]
      (is (= fact result))
      (is (= [fact] (store/ledger st))))))

(deftest test-coordination-log
  (testing "Coordination log"
    (let [st (store/seed-db)
          record {:proposal {:op :test} :decision :committed}
          result (store/commit-record! st record)]
      (is (= record result))
      (is (= [record] (store/coordination-log st))))))
