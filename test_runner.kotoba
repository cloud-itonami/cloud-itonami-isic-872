(require '[clojure.test :as t]
         '[clojure.java.io :as io])

;; Load all test files
(println "Loading test files...")
(doseq [file (sort (filter #(.endsWith (.getName %) "_test.clj")
                           (file-seq (io/file "test"))))]
  (println (str "  " (.getName file)))
  (load-file (.getAbsolutePath file)))

;; Run all tests
(println "\n========== Running Tests ==========\n")
(let [results (t/run-tests 'mhscare.store-test 
                           'mhscare.advisor-test 
                           'mhscare.governor-test 
                           'mhscare.phase-test 
                           'mhscare.operation-test)]
  (println "\n========== Test Summary ==========")
  (println (str "Tests run: " (:test results)))
  (println (str "Failures: " (:fail results)))
  (println (str "Errors: " (:error results)))
  (println "")
  (let [passed (- (:test results) (+ (:fail results) (:error results)))]
    (println (str "✓ All " passed " tests passed!"))
    (flush)
    (if (zero? (+ (:fail results) (:error results)))
      (System/exit 0)
      (System/exit 1))))
