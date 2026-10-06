;; The same programs under canonical JVM Clojure, measured the same way the
;; ports measure themselves: `main` called with NO arguments, so each program
;; uses its own default size, best of 9 WARM runs in one process.
;;
;; Warm on purpose, and it is the comparison that flatters Clojure most: the
;; JVM's JIT needs those runs, and startup -- about a second of it -- is left
;; out entirely. flint's cold start is 0.12 ms. Both numbers are real and they
;; answer different questions; this file answers "how fast is the steady
;; state", which is the harder one for flint to win.
(require '[clojure.string :as str])

(defn best-ms [f runs]
  (dotimes [_ 3] (f))                     ; warm the JIT before timing
  (reduce min (for [_ (range runs)]
                (let [t0 (System/nanoTime)]
                  (f)
                  (/ (- (System/nanoTime) t0) 1e6)))))

(let [progs (str/split (first *command-line-args*) #",")]
  (doseq [p progs]
    (load-file (str "bench/progs/" p ".cljc"))
    ;; THE FILE'S NAME IS ITS NAMESPACE. `concat.cljc` used to say `(ns cc)`,
    ;; which needed a special case here and compiled under flint only because
    ;; the compiler ignored the ns form's name; it no longer does
    ;; (`DECISIONS.md#a-source-defines-only-its-own-namespace`).
    (let [ns-sym (symbol p)
          main (ns-resolve (the-ns ns-sym) 'main)
          answer (main [])
          ms (best-ms #(main []) 9)]
      (printf "%-10s %8.2f ms   %s%n" p ms answer)
      (flush))))
