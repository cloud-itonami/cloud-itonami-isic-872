(require '[clojure.test :as t])
(require '[clojure.java.io :as io])

(defn load-tests [dir]
  (doseq [file (file-seq (io/file dir))
          :when (.endsWith (.getName file) "_test.clj")]
    (load-file (.getAbsolutePath file))))

(load-tests "test")

(let [results (t/run-tests 'mhscare.store-test 'mhscare.advisor-test 'mhscare.governor-test 
                           'mhscare.phase-test 'mhscare.operation-test)]
  (System/exit (if (zero? (+ (:failures results) (:errors results))) 0 1)))
