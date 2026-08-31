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


;; --------------------------------------------------------------- step 4
;;
;; `assoc`, `conj`, iteration, printing and the REFUSALS. The refusals are
;; asserted on their MESSAGE, not on the fact that something threw: `0026` says
;; a closed table's value is that it says what was wrong, and a test that only
;; checks for an exception would pass on "invalid row" -- which is the message
;; this codebase keeps replacing.
(spit (str d "/ops.cljc")
      (str "(ns ops (:require [flint.table :as ft]))\n"
           "(def S (ft/schema [[:id :int] [:name :string]]))\n"
           "(def T (ft/table S [{:id 1 :name \"a\"} {:id 2 :name \"b\"}]))\n"
           "(defn- msg [f] (try (do (f) \"no throw\") (catch Exception e (ex-message e))))\n"
           "(defn main [_]\n"
           "  (pr-str\n"
           "   {:print (pr-str T)\n"
           "    :str (str T)\n"
           "    :rows (mapv (fn [r] (:name r)) (ft/rows T))\n"
           "    :row-is-a-map (map? (first (ft/rows T)))\n"
           "    :row-equals-map (= {:id 1 :name \"a\"} (get T 0))\n"
           "    :conj (count (ft/add-row T {:id 3 :name \"c\"}))\n"
           "    :conj-reads (:name (get (ft/add-row T {:id 3 :name \"c\"}) 2))\n"
           "    :set (:name (get (ft/set-row T 0 {:id 9 :name \"z\"}) 0))\n"
           "    :update (:id (get (ft/update-row T 1 (fn [r] (assoc r :id 20))) 1))\n"
           "    :original-unmoved (:name (get T 0))\n"
           "    :ref-assoc-is-a-map (ft/table? (assoc (get T 0) :name \"q\"))\n"
           "    :ref-assoc-value (:name (assoc (get T 0) :name \"q\"))\n"
           "    :e-extra (msg (fn [] (ft/add-row T {:id 3 :name \"c\" :extra 1})))\n"
           "    :e-missing (msg (fn [] (ft/add-row T {:id 3})))\n"
           "    :e-type (msg (fn [] (ft/add-row T {:id 3 :name :notastring})))\n"
           "    :e-key (msg (fn [] (assoc T :id 4)))\n"
           "    :e-range (msg (fn [] (ft/set-row T 7 {:id 1 :name \"a\"})))\n"
           "    :e-notmap (msg (fn [] (ft/add-row T [1 \"a\"])))}))\n"))
(let [r (sh "./bin/flint" ":src" d ":fn" "ops/main" ":out" "out/tbl-ops.wasm")]
  (when-not (zero? (:exit r)) (println "ops build failed:" (:out r) (:err r)) (System/exit 1)))
(def ops (let [r (sh "node" "host/flint.mjs" "out/tbl-ops.wasm")]
           (when-not (zero? (:exit r)) (println "ops run failed:" (:out r) (:err r)) (System/exit 1))
           (read-string (str/trim (:out r)))))

(check "a table prints as its own literal, which reads back"
       (:print ops) "#flint/table [{:id 1, :name \"a\"} {:id 2, :name \"b\"}]")
(check "  ... and `str` reaches the same printer"
       (:str ops) (:print ops))
(check "iterating a table yields rows, materialising none of them"
       (:rows ops) ["a" "b"])
(check "  ... and a row reads as the map it is" (:row-is-a-map ops) true)
(check "  ... and is `=` to one with the same entries" (:row-equals-map ops) true)

(check "conj appends a row" (:conj ops) 3)
(check "  ... and it reads back" (:conj-reads ops) "c")
(check "assoc replaces one" (:set ops) "z")
(check "update-row hands `f` the row and re-checks the result" (:update ops) 20)
(check "  ... and the table it was built from has not moved" (:original-unmoved ops) "a")
(check "assoc on a ROW REF makes a map, not a table" (:ref-assoc-is-a-map ops) false)
(check "  ... carrying the change" (:ref-assoc-value ops) "q")

;; Each refusal names the thing that was wrong AND the columns there are,
;; because "which column?" is the next question every one of these provokes.
(check "a key outside the schema is refused, named, with the columns listed"
       (:e-extra ops)
       "row 2 has :extra, which is not a column; a table is closed, and the columns are :id :name")
(check "a missing column is refused, named"
       (:e-missing ops)
       "row 2 has no :name; a table is closed, so every row has every column, and the columns are :id :name")
(check "a wrong type names the column, the type it holds and the KIND it got"
       (:e-type ops)
       "row 2, column :name holds :string and was given a keyword")
(check "indexing a table by a column says how to reach a column"
       (:e-key ops)
       "a table is indexed by row number and this key is a keyword; to reach a column, index the row first: (assoc-in t [row :column] v)")
(check "a row out of range says what the range is"
       (:e-range ops)
       "row 7 is out of range for a table of 2 rows; assoc may replace any row or append at 2")
(check "a row that is not a map says what it is instead"
       (:e-notmap ops)
       "a table row is a map, and row 2 is a vector")

(if (pos? @fails)
  (do (println "tables:" @fails "FAILURES") (System/exit 1))
  (println "tables: ok"))
