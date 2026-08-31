;; Tables: columnar storage that is a value (`doc/decisions/0026`).
;;
;; `0026` puts a MEASUREMENT GATE at step 3, before the path copy and the
;; transient, because the whole justification is a memory and scan win and this
;; is the cheapest moment to learn it is not there. This file is that gate.
;;
;; The workload builds once and scans twenty times, so construction is
;; amortised and what is measured is the scan -- which is what a column store
;; is FOR. Measuring a single build-and-scan instead would be measuring the
;; vector of maps that `table` is handed, on both sides.
(require '[babashka.fs :as fs] '[clojure.string :as str])

(def fails (atom 0))
(defn check [label actual expected]
  (if (= actual expected)
    (println "  ok  " label)
    (do (swap! fails inc)
        (println "  FAIL" label "\n        expected" (pr-str expected)
                 "\n        got     " (pr-str actual)))))
(defn check-that [label ok extra]
  (if ok (println "  ok  " label)
      (do (swap! fails inc) (println "  FAIL" label (str "\n        " extra)))))

(defn sh [& args]
  (let [p (.start (ProcessBuilder. (into-array String args)))
        out (slurp (.getInputStream p)) err (slurp (.getErrorStream p))]
    (.waitFor p) {:exit (.exitValue p) :out out :err err}))

(println "tables: columnar storage that is a value (0026)")

(def d (str (fs/create-temp-dir)))
(spit (str d "/tbl.cljc")
      (str "(ns tbl (:require [flint.table :as ft]))\n"
           "(def N 10000)\n(def PASSES 20)\n"
           "(defn- rows [] (mapv (fn [i] {:id i :score (* i 2)}) (range N)))\n"
           "(defn- sum-vec [v] (reduce + 0 (map :score v)))\n"
           "(defn- sum-tab [t] (loop [i 0 acc 0]\n"
           "  (if (< i (count t)) (recur (inc i) (+ acc (:score (get t i)))) acc)))\n"
           "(defn main [args]\n"
           "  (let [w (first args)]\n"
           "    (cond\n"
           "      (= w \"vec\") (let [v (rows)]\n"
           "        (pr-str (loop [p 0 a 0] (if (< p PASSES) (recur (inc p) (+ a (sum-vec v))) a))))\n"
           "      (= w \"table\") (let [t (ft/table (ft/schema [[:id :int] [:score :int]]) (rows))]\n"
           "        (pr-str (loop [p 0 a 0] (if (< p PASSES) (recur (inc p) (+ a (sum-tab t))) a))))\n"
           "      :else (pr-str :none))))\n"))

(let [r (sh "./bin/flint" ":src" d ":fn" "tbl/main" ":out" "out/tbl.wasm")]
  (when-not (zero? (:exit r)) (println "build failed:" (:out r) (:err r)) (System/exit 1)))

(spit "out/tbl-run.mjs"
      (str "import { load, instantiate } from '../host/flint.mjs';\n"
           "const { module } = await load('out/tbl.wasm');\n"
           "const out = {};\n"
           "for (const w of ['vec', 'table']) {\n"
           "  const i = instantiate(module);\n"
           "  i.exports.set_step_limit(0x7ffffff0);\n"
           "  const r = i.main(w);\n"
           "  out[w] = { answer: r.out,\n"
           "             allocated: Number(i.exports.stat_bytes_allocated()),\n"
           "             peak: Number(i.exports.stat_peak_live()),\n"
           "             collections: Number(i.exports.stat_collections()),\n"
           "             gas: Number(i.exports.stat_steps()) };\n"
           "}\n"
           "console.log(JSON.stringify(out));\n"))
(def res (let [r (sh "node" "out/tbl-run.mjs")]
           (when-not (zero? (:exit r)) (println "run failed:" (:out r) (:err r)) (System/exit 1))
           (read-string (str/replace (str/trim (:out r)) #"\"(\w+)\":" "\"$1\" "))))
(def v (get res "vec"))
(def t (get res "table"))

;; SAME ANSWER first, or none of the numbers below mean anything: a column
;; store that is fast and wrong is worse than absent.
(check "a table scans to the same answer as the vector of maps"
       (get t "answer") (get v "answer"))

(println (format "    %-12s %10s %10s %6s %12s" "" "allocated" "peak live" "colls" "gas"))
(doseq [[nm m] [["vector of maps" v] ["table" t]]]
  (println (format "    %-12s %10d %10d %6d %12d"
                   nm (get m "allocated") (get m "peak") (get m "collections") (get m "gas"))))

;; The four claims `0026` rests on, each as a ratio rather than an absolute, so
;; the test survives the numbers moving and fails if the SHAPE changes.
(check-that "it is resident in far less memory"
            (< (* 4 (get t "peak")) (get v "peak"))
            (format "peak live %d against %d -- less than 4x is not the win 0026 claims"
                    (get t "peak") (get v "peak")))
(check-that "  ... and allocates far less to scan"
            (< (* 2 (get t "allocated")) (get v "allocated"))
            (format "allocated %d against %d" (get t "allocated") (get v "allocated")))
(check-that "  ... so it collects far less often"
            (< (get t "collections") (get v "collections"))
            (format "%d collections against %d" (get t "collections") (get v "collections")))
(check-that "  ... and the scan itself is cheaper"
            (< (* 3 (get t "gas")) (* 2 (get v "gas")))
            (format "gas %d against %d" (get t "gas") (get v "gas")))

;; COVERAGE: a run that collected nothing and allocated nothing would satisfy
;; every ratio above for the wrong reason.
(check-that "  ... and the vector run was real work, not a zero"
            (and (> (get v "collections") 5) (> (get v "allocated") 1000000))
            "the baseline did too little to compare against")

(if (pos? @fails)
  (do (println "tables:" @fails "FAILURES") (System/exit 1))
  (println "tables: ok"))
