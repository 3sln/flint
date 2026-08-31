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


;; `reduced` short-circuiting a `reduce`. It was broken for the whole life of
;; the runtime -- `reduced` is a one-element vector with a marker in its
;; metadata, and `reduce` unwrapped it with `deref`, which knows about atoms,
;; volatiles and delays and nothing else. Found by accident while optimising the
;; call path, which is the only reason it was found at all.
(src! "reduced" (str "(ns reduced)\n"
                     "(defn main [_]\n"
                     "  (pr-str [(reduce (fn [a x] (if (> x 2) (reduced a) (+ a x))) 0 [1 2 3 4])\n"
                     "           (reduce (fn [a x] (+ a x)) 0 [1 2 3 4])\n"
                     "           (unreduced (reduced 7))\n"
                     "           (reduce (fn [a x] (if (= x :stop) (reduced a) (conj a x)))\n"
                     "                   [] [:a :b :stop :c])]))"))
(let [i (run! (build! "reduced" false))
      a (run! (build! "reduced" true))]
  (check "reduced short-circuits a reduce, as Clojure does" (:out i)
         (pr-str [3 10 7 [:a :b]]))
  (check "  ... and identically in compiled code" (:out a) (:out i)))


;; A PARKED thread coming back into compiled code. This is the whole of the open
;; bug 0013 carried for four days, at the size it finally reduced to.
;;
;; A thread save records each frame's `ip` and its `aot_block`, and the restore
;; paired them -- but `aot_block` is the block to resume at `aot_ip`, which is
;; NOT saved. For a frame that parked those two offsets are the same and the
;; pairing is right. For a frame that BAILED they differ by one instruction: the
;; bail leaves `ip` on the instruction handed back and `aot_block` on the chunk
;; after it. `vec` was mid-bail on the `(vector 0)` of `(into [] coll)` when the
;; slice ended, so it came back one chunk late, never pushed the empty vector,
;; and the tail call read `coll` where `into` should have been --
;; `value is not a function (object type 13, 2 args)`, six frames away.
;;
;; Every part is load-bearing, checked one at a time: without the `p/receive`
;; there is no save; at 32 elements rather than 33 the slice ends elsewhere; and
;; removing either the map literal or the nested `(vec (range ...))` changes
;; which arities are compiled. So this is written at exactly the size that fails
;; and not one element smaller. `test/fixtures/aot-park-repro.cljc` is the same
;; program with the investigation written on it.
(src! "park" (str "(ns park (:require [flint.thread :as t] [flint.port :as p]))\n"
                  "(defn- go [] (count (mapv (fn [i] {:id i :kids (vec (range (rem i 4)))})\n"
                  "                          (range 33))))\n"
                  "(defn main [_]\n"
                  "  (let [[tx rx] (p/channel 1 \"test\")]\n"
                  "    (t/spawn (fn [] (p/send tx :go)))\n"
                  "    (p/receive rx)\n"
                  "    (pr-str (go))))"))
(let [i (run! (build! "park" false))
      a (run! (build! "park" true))]
  (check "a thread that parks mid-bail comes back to the chunk its ip names" (:out i) "33")
  (check "  ... and compiled code agrees" (:out a) (:out i))
  ;; A PARK USED TO COST ONE GAS UNIT MORE COMPILED THAN INTERPRETED, and the
  ;; measurements are kept because the shape of them is what found it.
  ;;
  ;;   | program        | interpreted | compiled |
  ;;   | -------------- | ----------: | -------: |
  ;;   | no park        |        5254 |     5254 |
  ;;   | one park       |        5331 |     5332 |
  ;;
  ;; Three facts narrowed it, and each killed a theory:
  ;;
  ;;   * lengthening the basic block before the parking native moved both
  ;;     numbers and kept the gap at one -- so it was ONE INSTRUCTION charged
  ;;     twice, not a chunk prefix re-executed;
  ;;   * three parks still cost one -- so it was not per park;
  ;;   * a bare `(t/spawn (fn [] 1))` with no join, no yield and no port cost
  ;;     it too, and a program with no threads at all was exact -- so it was
  ;;     not parking. It was having a SLICE.
  ;;
  ;; `aot_tick` is the only place a compiled loop can be preempted. It flushes
  ;; the chunk's static `gas`, which INCLUDES the back-edge instruction because
  ;; compiled code jumps for itself, and then hands that same instruction back
  ;; so the interpreter can perform the hand-over -- where the interpreter's own
  ;; tick charges it again. Three charges against the interpreter's two. It now
  ;; gives one back on the trip path, after the comparison, so the slice still
  ;; ends on the same instruction.
  ;;
  ;; It surfaced when `clojure.core` gained its `:flint/check` conditionals,
  ;; which moved the code layout, which moved where the slice boundary lands.
  ;; The equality was holding by where the slice happened to fall.
  (check "  ... on the same instruction count" (:steps a) (:steps i)))

;; THE MINIMAL REPRODUCTION, kept as its own row.
;;
;; The park program above caught the slice-trip double charge, and only by
;; accident: it is written at exactly the size that exposed a DIFFERENT bug,
;; and whether its slice boundary lands on a back-edge is a property of the
;; code layout that any unrelated change can move. It went green again on its
;; own once before.
;;
;; This one cannot. All it needs is a slice, which is what having a thread at
;; all creates -- no join, no yield, no port, nothing to park on. If compiled
;; code ever charges a preemption differently from the interpreter again, this
;; says so whatever the layout is doing.
(src! "slice" (str "(ns slice (:require [flint.thread :as t]))\n"
                   "(defn- work [] (count (mapv (fn [i] (* i i)) (range 200))))\n"
                   "(defn main [_] (t/spawn (fn [] 1)) (pr-str (work)))"))
(let [i (run! (build! "slice" false))
      a (run! (build! "slice" true))]
  (check "a program with a thread costs the same compiled" (:out a) (:out i))
  (check "  ... including its preemptions, to the instruction" (:steps a) (:steps i)))

;; A SECOND, SMALLER DIVERGENCE IS STILL OPEN, in the other direction. Written
;; down rather than left for the next person to re-derive, because the two are
;; easy to mistake for one and the measurements above cost a while.
;;
;; A threaded program whose work is INTERPRETED -- a lazy seq, so the preemption
;; lands in the interpreter and never in compiled code -- charges slightly LESS
;; compiled, and it grows with the number of preemptions:
;;
;;   (defn main [_] (t/spawn (fn [] 1))
;;                  (pr-str (reduce + (map (fn [i] (* i i)) (range N)))))
;;
;;   |    N | interpreted | compiled | slices (SLICE = 4096) |
;;   | ---: | ----------: | -------: | --------------------: |
;;   |  100 |        6175 |     6175 |                     1 |
;;   |  200 |       11976 |    11975 |                     2 |
;;   |  400 |       23578 |    23575 |                     5 |
;;   |  800 |       46781 |    46775 |                    11 |
;;
;; It is NOT the bug fixed above, and the check that says so is that these four
;; numbers are byte-identical with `aot_tick`'s correction and without it -- so
;; `aot_tick` never trips in them at all. The same program with `mapv` instead
;; of `reduce`/`map`, which compiles, is exact.
;;
;; Where to look: the interpreter's own tick writes `f.ip` and yields, and on
;; resume the AOT re-entry comparison runs BEFORE `B::tick`. A frame whose
;; `aot_ip` equals that `ip` therefore re-enters compiled code without the
;; interpreter charging for the dispatch, and whether the chunk's own charge
;; covers exactly that instruction is the thing to check.

(println (if (zero? @fails) "aot: ok" (str "aot: " @fails " FAILURES")))
(System/exit (if (zero? @fails) 0 1))
