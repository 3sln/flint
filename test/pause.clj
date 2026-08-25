;; The collector collects little and often (`doc/decisions/0018`).
;;
;; `bin/bench-image` measures the consequence -- a p99/p50 of 1.06 under load,
;; tighter than the 1.21 at idle. This asserts the CAUSE, because the cause is
;; deterministic and the consequence is a wall-clock number that would make a
;; flaky test.
(require '[clojure.string :as str] '[clojure.edn :as edn])

(defn sh [& args]
  (let [p (.start (ProcessBuilder. (into-array String args)))
        out (slurp (.getInputStream p)) err (slurp (.getErrorStream p))]
    (.waitFor p) {:exit (.exitValue p) :out out :err err}))

(def fails (atom 0))
(defn check [label actual expected]
  (if (= actual expected)
    (println "  ok  " label)
    (do (swap! fails inc)
        (println "  FAIL" label "\n        expected" (pr-str expected)
                 "\n        got     " (pr-str actual)))))
(defn check-that [label ok] (check label (boolean ok) true))

(println "pause: the collector collects little and often (0018)")
(let [b (sh "./bin/flint" ":src" "test" ":fn" "pause/main" ":out" "out/pause.wasm")]
  (when-not (zero? (:exit b)) (println "build failed:" (:out b) (:err b)) (System/exit 1)))
(def rows (edn/read-string (str/trim (:out (sh "node" "host/flint.mjs" "out/pause.wasm")))))

(doseq [r rows]
  (println (format "    keep 1/%-3d  live %5d  alloc %6.1f MB  %2d collections  largest copies %6.1f KB"
                   (:keep-every r) (:live r) (/ (:alloc r) 1048576.0)
                   (:minor r) (/ (:max-single r) 1024.0))))

;; COVERAGE FIRST. Every bound below is meaningless if no collection happened,
;; and `max-single` is meaningless if the probe never saw one.
(check-that "every probe actually collected" (every? #(pos? (:minor %)) rows))
(check-that "  ... and the probe saw the collections it is measuring"
            (every? #(pos? (:samples %)) rows))
(check-that "  ... catching most of them individually, so max-single is a tight bound"
            ;; `pos?` is not redundant with the check above it: `0 >= 0.6 * 0`
            ;; is true, so on a run where nothing collected this passed while
            ;; asserting nothing -- which is the exact shape the coverage rule
            ;; exists to catch, found by regressing the nursery on purpose.
            (every? #(and (pos? (:minor %)) (>= (:samples %) (* 0.6 (:minor %)))) rows))

;; OFTEN. Allocation between collections is about one nursery. A change that
;; made the collector wait would show here as a large number.
(doseq [r rows]
  (let [per (/ (:alloc r) (:minor r) 1048576.0)]
    (check-that (format "  live %5d: collects every %.2f MB allocated, under 4" (:live r) per)
                (< per 4.0))))

;; LITTLE. The largest single collection copies a small, bounded amount. This is
;; the pause, and it is what a p99 would feel.
(doseq [r rows]
  (check-that (format "  live %5d: largest single copy %.1f KB, under 512" (:live r) (/ (:max-single r) 1024.0))
              (< (:max-single r) (* 512 1024))))
(doseq [r rows]
  (let [frac (/ (:max-single r) (double (:alloc r)))]
    (check-that (format "  live %5d: largest copy is %.2f%% of what was allocated, under 2%%"
                        (:live r) (* 100 frac))
                (< frac 0.02))))

;; And the shape: a bigger live young set costs a bigger copy, which is what
;; bounds the pause in the first place. If this stopped holding, the bound above
;; would be an accident rather than a consequence.
(let [small (first rows) big (last rows)]
  (check-that "the pause scales with the LIVE set, which is what bounds it"
              (> (:max-single big) (:max-single small)))
  (check-that "  ... sub-linearly: 64x the live data costs well under 64x the pause"
              (< (/ (:max-single big) (double (:max-single small)))
                 (/ (:live big) (double (:live small))))))

(println (if (zero? @fails) "pause: ok" (str "pause: " @fails " FAILURES")))
(System/exit (if (zero? @fails) 0 1))
