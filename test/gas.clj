;; Gas is a BOUND, not a statistic (`DECISIONS.md#resource-limits`).
;;
;; The property: no operation may do unbounded work for bounded gas. A budget a
;; single call can escape is worse than no budget, because somebody will trust
;; it -- and this was not true. Appending 20 000 rows to a table allocated
;; 49 061 464 bytes and charged 461 232 gas, LESS than the 741 252 charged by the
;; bulk build that allocated 3 254 832. Gas said the expensive path was the cheap
;; one, and nothing was watching.
;;
;; So this file measures SCALING rather than absolutes. Each case runs at n and
;; at 8n and asserts the gas grew with the work. The setup is subtracted, because
;; building an 8x input is itself 8x the work and would make every case pass.
;;
;; The control matters as much as the cases: an O(1) operation must NOT scale, or
;; the test would pass on a runtime that charged by wall-clock and proved nothing.
(require '[babashka.fs :as fs] '[clojure.string :as str])

(def fails (atom 0))
(defn check-that [label ok extra]
  (if ok (println "  ok  " label)
      (do (swap! fails inc) (println "  FAIL" label (str "\n        " extra)))))

(defn sh [& args]
  (let [p (.start (ProcessBuilder. (into-array String args)))
        out (slurp (.getInputStream p)) err (slurp (.getErrorStream p))]
    (.waitFor p) {:exit (.exitValue p) :out out :err err}))

(println "gas: no operation does unbounded work for bounded gas (0009)")

(def d (str (fs/create-temp-dir)))

;; Each case is [label setup op scaling?]. `setup` is bound to `v` ONCE, and the
;; baseline consumes `v` while the measured pass applies `op` to it -- so the
;; difference is the operation and not the input.
;;
;; That subtraction was wrong the first time and the CONTROLS are what caught
;; it: every op rebuilt its own input, so `(count v)` scaled 7.9x, byte for byte
;; the same as building a vector. Without an operation asserted NOT to scale,
;; the whole file would have passed while measuring nothing but its own setup.
(def cases
  [["a vector is built"      "(range n)"                    "(vec v)"                    true]
   ["strings concatenate"    "(vec (range n))"              "(apply str v)"              true]
   ["two vectors compare"    "[(vec (range n)) (vec (range n))]" "(= (nth v 0) (nth v 1))" true]
   ["a map is built"         "(vec (range n))"              "(into {} (map (fn [i] [i i]) v))" true]
   ["a vector is sorted"     "(vec (reverse (range n)))"    "(sort v)"                   true]
   ;; MAPS AND SETS, on gas, and they were not here. Hashing and comparing
   ;; both used to walk through `map-for-each`, which charged a unit per
   ;; entry; both are generated tree walks now, and the first version of that
   ;; walk charged NOTHING -- so hashing a 200,000-entry map was free to
   ;; metered code and nothing in this file could tell. `hash` and `=` were
   ;; covered for VECTORS only.
   ["a map is hashed"        "(into {} (map (fn [i] [i i]) (range n)))" "(hash v)"    true]
   ["two maps compare"       "[(into {} (map (fn [i] [i i]) (range n)))
                               (into {} (map (fn [i] [i i]) (range n)))]"
                             "(= (nth v 0) (nth v 1))"                                true]
   ["a set is hashed"        "(set (range n))"              "(hash v)"                   true]
   ["a table is built"       "(mapv (fn [i] {:id i}) (range n))" "(ft/table S v)"        true]
   ["a table is appended to" "(range n)"
                             "(reduce (fn [t i] (ft/add-row t {:id i})) (ft/table S []) v)" true]
   ["a table is built transiently" "(range n)"              "(ft/build S v (fn [i] {:id i}))" true]
   ;; HASH FLOODING. A lookup on keys that all share one hash scans the whole
   ;; collision node, and used to be billed a FLAT 16 STEPS however wide the
   ;; node was -- measured flat at 1 024, 4 096 and 16 384 colliding keys,
   ;; where 16 384 was 179us of work billed the same as 1.70us. The width is
   ;; chosen by whoever supplies the keys, so that is unbounded work for
   ;; bounded gas, which is the one thing this file exists to refuse.
   ;; See `doc/goals/hash-flooding.md`.
   ;; THE PROBE KEY IS BUILT IN THE SETUP, not in the op. Built in the op it
   ;; added a FIXED ~1 021 gas -- twelve rounds of `str` -- which is in the
   ;; measured arm and not the baseline, so it swamped the ratio: 1 221 at
   ;; n=200 against 2 621 at n=1600 read as 2.1x and failed, while the
   ;; difference of exactly 1 400 for exactly 1 400 more entries showed the
   ;; scan was already billed per entry. The measurement was wrong, not the
   ;; charge.
   ["a flooded lookup is billed"
    "[(into {} (map (fn [i] [(ckey i) i]) (range n))) (ckey (dec n))]"
    "(get (nth v 0) (nth v 1) 0)"                                                       true]
   ;; The control that makes the row above mean something: the SAME shape with
   ;; distinct keys must stay O(1). Without it, a runtime that charged by map
   ;; SIZE rather than by work scanned would pass the case and still be wrong.
   ["and a normal lookup is still O(1)"
    "[(into {} (map (fn [i] [(str \"d-\" i) i]) (range n))) (str \"d-\" (dec n))]"
    "(get (nth v 0) (nth v 1) 0)"                                                       false]
   ;; The controls. Both are O(1) claims this project makes elsewhere, so they
   ;; are pinned here as gas rather than only asserted in prose.
   ["counting a vector is O(1)" "(vec (range n))"           "(count v)"                  false]
   ["a table row is a REF, not a copy" "(ft/table S (mapv (fn [i] {:id i}) (range n)))"
                             "(:id (get v 3))"                                           false]])

(spit (str d "/g.cljc")
      (str "(ns g (:require [flint.table :as ft]))\n"
           "(def S (ft/schema [[:id :int]]))\n"
           ;; O(1), and it FORCES the value: without a consumer a lazy result
           ;; could go unrealised and the case would measure nothing.
           "(defn- sink [x] (if (nil? x) 0 1))\n"
           ;; A KEY THAT COLLIDES WITH EVERY OTHER ONE. Flint's string hash is
           ;; a base-31 polynomial, so `Aa` and `BB` hash alike and the
           ;; property composes: choosing one or the other at each of 12
           ;; positions gives 4 096 distinct strings sharing a single hash.
           ;; That is the input an attacker supplies, and it is the only way
           ;; to make a collision node wide enough to measure.
           "(defn- ckey [i]\n"
           "  (loop [j 0 acc \"\" x i]\n"
           "    (if (< j 12) (recur (inc j) (str acc (if (odd? x) \"BB\" \"Aa\")) (quot x 2)) acc)))\n"
           (apply str
                  (map-indexed
                   (fn [i [_ setup op _]]
                     (str "(defn c" i "-base [n] (let [v " setup "] (sink v)))\n"
                          "(defn c" i "-op [n] (let [v " setup "] (sink " op ")))\n"))
                   cases))
           "(defn main [args]\n"
           "  (let [k (first args) n (flint.rt/str->num (first (rest args)))]\n"
           "    (pr-str\n"
           "      (cond\n"
           (apply str
                  (map-indexed
                   (fn [i _]
                     (str "        (= k \"" i "b\") (c" i "-base n)\n"
                          "        (= k \"" i "o\") (c" i "-op n)\n"))
                   cases))
           "        :else 0))))\n"))

(let [r (sh "./bin/flint" ":src" d ":fn" "g/main" ":out" "out/gas.wasm")]
  (when-not (zero? (:exit r)) (println "build failed:" (:out r) (:err r)) (System/exit 1)))

(def keys* (vec (mapcat (fn [i] [(str i "b") (str i "o")]) (range (count cases)))))
(spit "out/gas-run.mjs"
      (str "import { load, instantiate } from '../host/flint.mjs';\n"
           "const { module } = await load('out/gas.wasm');\n"
           "const out = {};\n"
           "for (const k of [" (str/join ", " (map pr-str keys*)) "]) {\n"
           "  for (const n of ['200', '1600']) {\n"
           "    const i = instantiate(module);\n"
           "    i.exports.set_step_limit(0x7ffffff0n);\n"
           "    const before = Number(i.exports.stat_steps());\n"
           "    i.run('g/main', [k, n]);\n"
           "    out[k + '@' + n] = Number(i.exports.stat_steps()) - before;\n"
           "  }\n"
           "}\n"
           "console.log(JSON.stringify(out));\n"))
(def g (let [r (sh "node" "out/gas-run.mjs")]
         (when-not (zero? (:exit r)) (println "run failed:" (:out r) (:err r)) (System/exit 1))
         (read-string (str/replace (str/trim (:out r)) #"\"([^\"]+)\":" "\"$1\" "))))

(defn op-gas [i n] (- (get g (str i "o@" n)) (get g (str i "b@" n))))

(println (format "    %-32s %10s %10s %8s" "" "n=200" "n=1600" "ratio"))
(doseq [[i [label _ _ scaling?]] (map-indexed vector cases)]
  (let [small (op-gas i "200") big (op-gas i "1600")
        ratio (if (pos? small) (double (/ big small)) 0.0)]
    (println (format "    %-32s %10d %10d %8.1f" label small big ratio))
    (if scaling?
      ;; 8x the input for at least 4x the gas. Not 8x: there is per-call
      ;; overhead in the baseline subtraction, and a test that demands exactly
      ;; linear would fail on anything that amortises.
      (check-that (str label " charges for its work")
                  (and (pos? small) (> big (* 4 small)))
                  (format "%d gas at n=200, %d at n=1600 -- 8x the work for %.1fx the gas"
                          small big ratio))
      ;; The control. If this scaled, the test would be measuring something
      ;; other than work done and every row above would be meaningless.
      (check-that (str label " -- and does NOT scale, which is the control")
                  (< big (* 3 (max small 1)))
                  (format "%d gas at n=200 and %d at n=1600: an O(1) operation grew"
                          small big)))))


;; ---------------------------------------------------------------- overshoot
;;
;; Scaling above says a big operation is BILLED. This says it is STOPPED, which
;; is a different property and the one that makes gas a bound rather than a
;; statistic.
;;
;; `charge_work` only adds to a counter, and nothing looks at that counter until
;; the next interpreter instruction -- so a native that charges a million and
;; then loops a million times still burns a million iterations of real CPU. The
;; measurement: give the program exactly enough gas to build its input plus a
;; sliver, then see how far past the limit the operation runs.
;;
;; What it found, on 300 000 elements:
;;
;;   | operation      | overshoot |
;;   | -------------- | --------: |
;;   | (apply str v)  | 2 698 029 |
;;   | (apply + v)    | 2 698 032 |
;;   | (table S v)    |   637 379 |
;;   | sort, into, reverse, str-join |  54-91 |
;;
;; `apply` was the culprit for BOTH of the first two -- they blew it by the same
;; amount, which is what named the spread rather than either callee. Two guesses
;; came first and were wrong: `str-join`, which turned out to be innocent, and
;; the APPLY opcode, which is a second road to the same spread and now ticks too.
;; Every builtin that can be handed a big collection or string, driven with one.
;; `in` builds the input, `op` is the single call under test. Written as data so
;; adding a builtin is a row rather than a program.
(def over-cases
  [["apply-str"    "(vec (range N))"                    "(apply str v)"]
   ["apply-add"    "(vec (range N))"                    "(apply + v)"]
   ["str-join"     "(mapv (fn [_] \"x\") (range N))"      "(flint.rt/str-join v)"]
   ["table"        "(mapv (fn [i] {:id i}) (range N))"  "(ft/table S v)"]
   ["sort"         "(vec (reverse (range N)))"          "(sort v)"]
   ["into-set"     "(vec (range N))"                    "(into #{} v)"]
   ["into-map"     "(mapv (fn [i] [i i]) (range N))"    "(into {} v)"]
   ["reverse"      "(vec (range N))"                    "(reverse v)"]
   ["equals"       "(let [x (vec (range N))] [x x])"    "(= (nth v 0) (nth v 1))"]
   ["hash"         "(vec (range N))"                    "(hash v)"]
   ["compare"      "(let [x (vec (range N))] [x x])"    "(compare (nth v 0) (nth v 1))"]
   ["seq-of-map"   "(into {} (mapv (fn [i] [i i]) (range N)))" "(count (seq v))"]
   ["seq-of-set"   "(into #{} (range N))"               "(count (seq v))"]
   ["vals"         "(into {} (mapv (fn [i] [i i]) (range N)))" "(count (vals v))"]
   ["nth-on-seq"   "(map inc (range N))"                "(nth v (dec N))"]
   ["last"         "(map inc (range N))"                "(last v)"]
   ["upper-case"   "(apply str (mapv (fn [_] \"ab\") (range N)))" "(flint.rt/upper-case v)"]
   ["lower-case"   "(apply str (mapv (fn [_] \"AB\") (range N)))" "(flint.rt/lower-case v)"]
   ["subs"         "(apply str (mapv (fn [_] \"ab\") (range N)))" "(subs v 1)"]
   ["index-of"     "(apply str (mapv (fn [_] \"ab\") (range N)))" "(flint.rt/str-index-of v \"zz\" 0)"]
   ["str-bytes"    "(apply str (mapv (fn [_] \"ab\") (range N)))" "(flint.rt/str-bytes v)"]
   ["bytes-concat" "(fb/of-string (apply str (mapv (fn [_] \"ab\") (range N))))" "(fb/cat v v)"]
   ["bytes-to-str" "(fb/of-string (apply str (mapv (fn [_] \"ab\") (range N))))" "(fb/to-string v)"]
   ["re-find"      "(apply str (mapv (fn [_] \"ab\") (range N)))" "(re-find #\"zq+z\" v)"]
   ["frequencies"  "(vec (range N))"                    "(count (frequencies v))"]
   ["distinct"     "(vec (range N))"                    "(count (distinct v))"]
   ["group-by"     "(vec (range N))"                    "(count (group-by even? v))"]
   ["concat-count" "(vec (range N))"                    "(count (concat v v))"]
   ["table-migrate" "(ft/table S (mapv (fn [i] {:id i}) (range N)))" "(ft/migrate v S2 {:tag :x})"]
   ["table-build"  "(vec (range N))"                    "(ft/build S v (fn [i] {:id i}))"]])

(spit (str d "/o.cljc")
      (str "(ns o (:require [flint.table :as ft] [flint.bytes :as fb]))\n"
           "(def S (ft/schema [[:id :int]]))\n"
           "(def S2 (ft/schema [[:id :int] [:tag :keyword]]))\n"
           "(def N 200000)\n"
           "(defn- sink [x] (if (nil? x) 0 1))\n"
           "(defn- inp [k]\n  (cond\n"
           (apply str (map (fn [[k in _]] (str "    (= k \"" k "\") " in "\n")) over-cases))
           "    :else nil))\n"
           "(defn main [args]\n"
           "  (let [k (first args) op? (= \"1\" (first (rest args))) v (inp k)]\n"
           "    (str (if-not op? (sink v)\n"
           "           (sink (cond\n"
           (apply str (map (fn [[k _ op]] (str "             (= k \"" k "\") " op "\n")) over-cases))
           "             :else v))))))\n"))
(let [r (sh "./bin/flint" ":src" d ":fn" "o/main" ":out" "out/gas-over.wasm")]
  (when-not (zero? (:exit r)) (println "overshoot build failed:" (:out r) (:err r)) (System/exit 1)))
(def overs (mapv first over-cases))
(spit "out/gas-over-run.mjs"
      (str "import { load, instantiate } from '../host/flint.mjs';\n"
           "const { module } = await load('out/gas-over.wasm');\n"
           "const out = {};\n"
           "for (const k of [" (str/join ", " (map pr-str overs)) "]) {\n"
           ;; ONE BigInt. It was two u32s, and passing a single argument set the
           ;; limit to n<<32 -- which is how a probe of this ran 15 seconds and
           ;; 661 938 057 steps under what it believed was a 200 000-step cap,
           ;; and very nearly reported the runtime broken. As a u64 a plain
           ;; number throws instead.
           "  let i = instantiate(module);\n"
           "  i.exports.set_step_limit(0x7ffffff0n);\n"
           ;; THE SETUP'S ANSWER IS KEPT. `sink` maps nil to 0, so a setup that
           ;; THREW reads as "0" here -- and until this line the harness
           ;; discarded it, timed an operation over a nil, and reported ok.
           ;; `bytes-to-str` sat like that: its input needed `str->b` on a
           ;; rope, which threw, so the case never ran and never said so.
           "  const setup = i.run('o/main', [k, '0']).out.trim();\n"
           "  const build = Number(i.exports.stat_steps());\n"
           "  const limit = build + 2000;\n"
           "  i = instantiate(module);\n"
           "  i.exports.set_step_limit(BigInt(limit));\n"
           "  const t0 = Date.now();\n"
           "  i.run('o/main', [k, '1']);\n"
           "  out[k] = { over: Number(i.exports.stat_steps()) - limit, ms: Date.now() - t0,\n"
           "             setup: setup };\n"
           "}\n"
           "console.log(JSON.stringify(out));\n"))
(def ov (let [r (sh "node" "out/gas-over-run.mjs")]
          (when-not (zero? (:exit r)) (println "overshoot run failed:" (:out r) (:err r)) (System/exit 1))
          (read-string (str/replace (str/trim (:out r)) #"\"([^\"]+)\":" "\"$1\" "))))
(println (format "    %-16s %12s %6s" "" "overshoot" "ms"))
(doseq [k overs]
  (let [m (get ov k)]
    (println (format "    %-16s %12d %6d" k (get m "over") (get m "ms")))
    ;; 20 000 is far above the 54-322 these cost when they tick, and far below
    ;; the hundreds of thousands they cost when they do not. A bound that has to
    ;; be exact would fail on a scheduler slice landing differently.
    ;; THE SETUP FIRST. Timing an operation over a nil measures nothing and
    ;; reports ok, which is indistinguishable from a pass -- the same shape
    ;; `bin/check-builtins` records for a gate that cannot find what it checks.
    (check-that (str k " builds an input at all")
                (= "1" (get m "setup"))
                (format "the setup for %s produced nil, so the case below asks nothing" k))
    (check-that (str k " stops near its limit rather than running past it")
                (< (get m "over") 20000)
                (format "ran %d steps past an exhausted budget in %d ms -- a loop is not checking"
                        (get m "over") (get m "ms")))))

(if (pos? @fails)
  (do (println "gas:" @fails "FAILURES") (System/exit 1))
  (println "gas: ok"))
