;; Differential test: run the SAME conformance file under babashka's real
;; Clojure. If flint says PASS but this says FAIL, the expectations were wrong
;; and flint was agreeing with a mistake.
(require '[clojure.string :as str])
(load-file "test/conform/basics.cljc")
(in-ns 'conform.basics)
(clojure.core/refer 'clojure.core)
(let [results (map (fn [x]
                     (let [actual (try ((:thunk x))
                                       (catch Throwable e (str "threw " (ex-message e))))]
                       (assoc x :actual actual
                              ;; `:two-readers` as well as `:divergence`: the
                              ;; `#?@` case is not a divergence, it is this file
                              ;; being READ differently on the two sides. Both
                              ;; carry a Clojure answer; only one is a claim
                              ;; about flint's semantics.
                              :ok (= actual (if (or (:divergence x) (:two-readers x))
                                              (:clojure x)
                                              (:expected x))))))
                   (cases))
      failures (remove :ok results)]
  (doseq [f failures]
    (println "FAIL" (:label f)
             "\n  expected" (pr-str (:expected f))
             "\n  clojure  " (pr-str (:actual f))))
  (println (str "  (" (count (filter :divergence results)) " of them documented divergences,"
                " compared against their Clojure answer)"))
  (println (if (empty? failures) (str "clojure agrees: " (count results) " cases")
               (str "clojure DISAGREES on " (count failures) "/" (count results)))))
