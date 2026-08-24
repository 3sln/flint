;; Compiled arities against the interpreter (`doc/decisions/0013`).
;;
;; The bar is the one 0015 set for snapshots and it is the right one here too:
;; not "close enough" but **the same answer and the same instruction count**.
;; Gas is a production feature (`doc/decisions/0016`) and construe's gates depend
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
        out (slurp (.getInputStream p)) err (slurp (.getErrorStream p))]
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
       "i.exports.set_step_limit(0x7ffffff0);"
       "const r = i.main();"
       "console.log(JSON.stringify({out: r.out.trim(), code: r.code,"
       " steps: Number(i.exports.stat_steps())}));})"))

(defn run! [wasm]
  (let [r (sh "node" "-e" runner wasm)]
    (when-not (zero? (:exit r))
      (println "run failed for" wasm ":" (:all r)) (System/exit 1))
    (json/parse-string (str/trim (:out r)) true)))

(doseq [n progs]
  (let [i (run! (build! n false))
        a (run! (build! n true))]
    (check (str n " — the same answer") (:out a) (:out i))
    (check (str n " — the same instruction count") (:steps a) (:steps i))))

;; And the rule that makes all of it optional: a module built without `--aot`
;; carries no compiled arities at all, which is what lets the interpreter's own
;; loop monomorphise the re-entry check away (`doc/decisions/0016`'s reasoning,
;; applied to a production feature rather than a diagnostic one).
(let [plain (fs/size (build! "arith" false))
      compiled (fs/size (build! "arith" true))]
  (println (format "    module %d bytes plain, %d with compiled arities (+%.0f%%)"
                   plain compiled (- (* 100.0 (/ compiled plain)) 100)))
  (check-that "compiling costs module bytes, and only when asked for"
              (> compiled plain)))

(println (if (zero? @fails) "aot: ok" (str "aot: " @fails " FAILURES")))
(System/exit (if (zero? @fails) 0 1))
