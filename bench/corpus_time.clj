;; The in-process timer for `bin/bench-corpus`, run UNCHANGED by JVM Clojure
;; and by babashka so the two arms cannot differ in method.
;;
;;   clojure -Sdeps '{:paths ["corpus"]}' -M bench/corpus_time.clj <reps> <ns>...
;;   bb --classpath corpus bench/corpus_time.clj <reps> <ns>...
;;
;; Per program: call `main` until WARM-MS have passed AND at least three calls
;; have been made (the JIT's chance; babashka gets the same budget and has no
;; use for it), then time `reps` calls and report the best and the median.
;; Prints one EDN map per program, one per line, the answer included so the
;; harness can refuse a timing whose answer is wrong.
(def warm-ms 300)

(defn now-ms [] (/ (System/nanoTime) 1e6))

(defn time-one [n reps]
  (require (symbol n))
  (let [main (resolve (symbol n "main"))
        t0 (now-ms)
        out (loop [k 0 out nil]
              (if (and (>= k 3) (> (- (now-ms) t0) warm-ms))
                out
                (recur (inc k) (main []))))
        ts (vec (sort (for [_ (range reps)]
                        (let [t (now-ms)] (main []) (- (now-ms) t)))))]
    {:name n :best (first ts) :median (nth ts (quot (count ts) 2)) :out out}))

(let [[reps & names] *command-line-args*
      reps (Long/parseLong reps)]
  (doseq [n names]
    (prn (try (time-one n reps)
              (catch Throwable e {:name n :error (str (.getMessage e))})))))
