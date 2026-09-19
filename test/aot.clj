;; Compiled arities against the interpreter (`DECISIONS.md#emit-wasm-instead-of-dispatch`).
;;
;; The bar is the one 0015 set for snapshots and it is the right one here too:
;; not "close enough" but **the same answer and the same instruction count**.
;; Gas is a production feature (`DECISIONS.md#two-builds`) and construe's gates depend
;; on the count, so a compiler that changed it by one would be wrong even if
;; every answer matched.
(require '[clojure.string :as str] '[babashka.fs :as fs] '[cheshire.core :as json])

(def fails (atom 0))
(defn check [label actual expected]
  (if (= actual expected)
    (println "  ok  " label)
    (do (swap! fails inc)
        (println "  FAIL" label "\n        expected" (pr-str expected)
                 "\n        got     " (pr-str actual)))))
(defn check-that [label ok] (check label (boolean ok) true))

(defn sh [& args]
  (let [p (.start (ProcessBuilder. (into-array String args)))
        ;; STDERR IS DRAINED ON ITS OWN THREAD (`DECISIONS.md#the-codec-is-guest-code`,
        ;; "the test helper deadlocked"). Reading stdout to completion and
        ;; stderr after DEADLOCKS the moment a child writes more than a pipe
        ;; buffer to stderr: the child blocks writing, this blocks reading, and
        ;; neither moves again.
        err (future (slurp (.getErrorStream p)))
        out (slurp (.getInputStream p))
        err @err]
    (.waitFor p) {:exit (.exitValue p) :out out :err err :all (str out err)}))

(def d (str (fs/create-temp-dir)))
(defn src! [n body] (spit (str d "/" n ".cljc") body))

;; Each of these exercises a different part of the emitter: straight-line
;; arithmetic in a loop, persistent-collection work, higher-order calls, string
;; building, and a `try` whose handler has to be re-entered.
(src! "arith" (str "(ns arith)\n"
                   "(defn run [n] (loop [i 0 acc 0] (if (< i n) (recur (inc i) (+ acc i)) acc)))\n"
                   "(defn main [_] (str (run 20000)))"))
(src! "colls" (str "(ns colls)\n"
                   "(defn main [_]\n"
                   "  (let [m (reduce (fn [m i] (assoc m i (* i i))) {} (range 500))\n"
                   "        v (reduce conj [] (range 500))]\n"
                   "    (str [(count m) (get m 30) (reduce + 0 v) (peek v)])))"))
(src! "hof" (str "(ns hof)\n"
                 "(defn main [_]\n"
                 "  (str [(count (filterv odd? (range 300)))\n"
                 "        (reduce + 0 (mapv (fn [x] (* x 2)) (range 300)))\n"
                 "        (apply + (range 50))]))"))
(src! "strs" (str "(ns strs (:require [clojure.string :as str]))\n"
                  "(defn main [_]\n"
                  "  (let [ws (str/split \"the quick brown fox jumps\" #\" \")]\n"
                  "    (str [(count ws) (str/join \"-\" ws) (str/upper-case (first ws))])))"))
(src! "handler" (str "(ns handler)\n"
                     "(defn boom [x] (if (> x 3) (throw (ex-info \"b\" {})) x))\n"
                     "(defn main [_]\n"
                     "  (str (mapv (fn [i] (try (boom i) (catch Exception _ -1))) (range 6))))"))

(def progs ["arith" "colls" "hof" "strs" "handler"])

(defn build! [n aot?]
  (let [out (str "out/aot-" n (if aot? "-a" "-i") ".wasm")
        r (apply sh (concat ["./bin/flint" ":src" d ":fn" (str n "/main") ":out" out]
                            (when aot? ["--aot"])))]
    (when-not (zero? (:exit r))
      (println "build failed for" n ":" (:all r)) (System/exit 1))
    out))

(println "aot: compiled arities answer exactly as the interpreter does")

;; The gas count is read with a limit set, because with no limit the loop is
;; monomorphised free of the counter and `steps` is not maintained at all --
;; asserting equality of two zeros would be a coverage zero, not a result.
(def runner
  (str "import('./host/flint.mjs').then(async (m) => {"
       "const {module} = await m.load(process.argv[1]);"
       "const i = m.instantiate(module);"
       "i.exports.set_step_limit(0x7ffffff000000000n);"
       ;; The FUNCTION IS NAMED, and its name is derivable from the artifact:
       ;; `build!` writes `out/aot-<ns>-{a,i}.wasm`. Nothing is called
       ;; automatically (`DECISIONS.md#structured-ports` step 5).
       "const fn = /out\\/aot-([a-z]+)-[ai]\\.wasm$/.exec(process.argv[1])[1] + '/main';"
       "const r = i.run(fn, []);"
       "console.log(JSON.stringify({out: r.out.trim(), code: r.code,"
       " steps: Number(i.exports.stat_steps()),"
       ;; C_RESTORES is counter 16, and `stat_region(80 + k)` reads `COUNTS[k]`
       ;; (`runtime/src/abi.rs`). It counts PREEMPTIONS -- how many times a
       ;; thread's state was restored -- which is what the row below needs.
       " restores: Number(i.exports.stat_region(80 + 16))}));})"))

(defn run! [wasm]
  (let [r (sh "node" "-e" runner wasm)]
    (when-not (zero? (:exit r))
      (println "run failed for" wasm ":" (:all r)) (System/exit 1))
    (json/parse-string (str/trim (:out r)) true)))

;; THE SAME INSTRUCTION COUNT, EXACTLY -- and it is exact again for a reason
;; worth keeping.
;;
;; This row was red on `colls` for a long time, and the cause was never the
;; emitter. Compiled code notices a slice boundary only at a back-edge, so it
;; trips late and fits one fewer slice into a run; the two paths are therefore
;; PREEMPTED a different number of times, which is legitimate. What was not
;; legitimate is that a preemption COST billed steps: `save_current_state`
;; saved three buffers and billed one of them, the handler buffer, while the
;; stack and the frame buffers were deliberately unbilled because their size is
;; a property of the calling convention rather than of the program. Nothing
;; argued for the difference; it was an oversight, and it made gas depend on
;; where a thread happened to be preempted.
;;
;; With that buffer unbilled in all three runtimes a preemption costs nothing,
;; and compiled and interpreted agree to the instruction again -- `arith` and
;; `colls` included, which still preempt 64/63 and 24/23 times respectively.
;; The preemption counts are printed below precisely so that a future
;; divergence can be checked against them without re-deriving any of this.
;;
;; A row that briefly asserted "equal, net of preemptions not taken" lived here
;; while the cause was still being found. It is gone: an exact equality that is
;; TRUE is worth more than a law with a constant in it, and the constant in
;; that law was the defect.

(doseq [n progs]
  (let [i (run! (build! n false))
        a (run! (build! n true))]
    (check (str n " — the same answer") (:out a) (:out i))
    (println (format "    %s: steps %d interpreted / %d compiled, preemptions %d / %d"
                     n (:steps i) (:steps a) (:restores i) (:restores a)))
    (check (str n " — the same instruction count") (:steps a) (:steps i))))