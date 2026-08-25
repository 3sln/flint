;; The Pike VM (`doc/decisions/0012`).
;;
;; Three things this has to establish, and only the first is about answers:
;;
;;   1. the native simulator, the cljc reference simulator and babashka all agree
;;   2. matching a rope never materialises it
;;   3. `(a+)+b` is linear, so the ReDoS hazard is gone rather than bounded
(require '[clojure.string :as str] '[babashka.fs :as fs])

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

;; One battery, run three ways. The alternation and quantifier cases are the ones
;; where engines classically differ, which is why they are here rather than a
;; handful of literals.
(def cases
  [["a+b" "xxaaab"] ["[0-9]+" "ab123cd"] ["(a|ab)c" "abc"] ["(ab|a)c" "abc"]
   ["\\s+" "aa  bb"] ["^ab" "abc"] ["c$" "abc"] ["a{2,3}" "aaaa"]
   ["(a+)(b*)" "aaabb"] ["\\bfox\\b" "the fox ran"] ["[^0-9]+" "12ab34"]
   ["x*" "yyy"] ["a*?b" "aaab"] ["[a-c-]+" "ab-c"]
   ["(foo|foobar)baz" "foobarbaz"] ["a.c" "a\nc"] ["\\d{2,}" "a1b22c333"]
   ["(a)(b)?(c)" "ac"] ["" "abc"]])

;; One documented divergence, in the shape `test/conform_vs_clojure.clj` uses.
;; `(a?)*b` against "b": Java reports group 1 as the empty string, because its
;; loop runs the body once with an empty match before giving up. A Thompson
;; simulation deduplicates by program counter, so the empty iteration is never
;; taken and the group does not participate -- which is also what Go's RE2 and
;; Rust's `regex` report. Linearity and that capture cannot both be had.
(def divergences [["(a?)*b" "b"]])

(def battery
  (str "(defn probe [pairs]\n"
       "  (mapv (fn [pr]\n"
       "          (let [p (re-pattern (nth pr 0)) s (nth pr 1)]\n"
       "            [(re-find p s) (re-seq p s) (vec (str/split s p))\n"
       "             (str/replace s p \"#\") (re-matches p s)]))\n"
       "        pairs))\n"))

(spit (str d "/battery.cljc")
      (str "(ns battery (:require [clojure.string :as str]))\n" battery
           "(def cases " (pr-str cases) ")\n"
           "(defn main [_] (pr-str (probe cases)))\n"))
(spit "/tmp/pike-bb.clj"
      (str "(require '[clojure.string :as str])\n" battery
           "(prn (probe " (pr-str cases) "))\n"))

;; The same battery through the cljc REFERENCE simulator, which is the oracle
;; the native one is checked against and the thing a new host runs on day one.
(spit (str d "/refsim.cljc")
      (str "(ns refsim (:require [flint.regex :as rx] [flint.nfa :as nfa] [flint.pike :as pike]))\n"
           "(defn find1 [pat s]\n"
           "  (let [p (rx/pattern pat) prog (nfa/compile-ast (:ast p))]\n"
           "    (loop [i 0]\n"
           "      (if (> i (count s)) nil\n"
           "        (let [r (pike/run prog s i)]\n"
           "          (if r [(get r 0) (get r 1)] (recur (inc i))))))))\n"
           "(def cases " (pr-str cases) ")\n"
           "(defn main [_] (pr-str (mapv (fn [pr] (find1 (nth pr 0) (nth pr 1))) cases)))\n"))

;; And the native one, reduced to the same shape so the two are comparable.
(spit (str d "/natsim.cljc")
      (str "(ns natsim (:require [flint.regex :as rx]))\n"
           "(def cases " (pr-str cases) ")\n"
           "(defn main [_]\n"
           "  (pr-str (mapv (fn [pr]\n"
           "                  (let [p (rx/pattern (nth pr 0)) s (nth pr 1)\n"
           "                        r (rx/find-from p s 0)]\n"
           "                    (when r [(nth r 0) (nth r 1)])))\n"
           "                cases)))\n"))

(println "pike: one shared program, two simulators")

(defn build! [n]
  (let [o (str "out/pike-" n ".wasm")
        r (sh "./bin/flint" ":src" d ":fn" (str n "/main") ":out" o)]
    (when-not (zero? (:exit r)) (println "build failed:" (:all r)) (System/exit 1))
    o))

(let [f (str/trim (:out (sh "node" "host/flint.mjs" (build! "battery"))))
      b (str/trim (:out (sh "bb" "/tmp/pike-bb.clj")))]
  (check (str "the whole API agrees with babashka over " (count cases) " patterns") f b)
  (when (not= f b)
    (spit "/tmp/pike-flint.edn" f)
    (spit "/tmp/pike-bb.edn" b)
    (doseq [[i [x y]] (map-indexed vector (map vector
                                              (clojure.edn/read-string f)
                                              (clojure.edn/read-string b)))]
      (when (not= x y)
        (println (format "        case %d %s" i (pr-str (nth cases i))))
        (println (str "          flint    " (pr-str x)))
        (println (str "          babashka " (pr-str y)))))))

;; The divergence, asserted rather than left to be rediscovered. If it ever
;; changes -- in either direction -- this notices.
(spit (str d "/diverge.cljc")
      (str "(ns diverge)\n(defn main [_] (pr-str (re-find #\"(a?)*b\" \"b\")))\n"))
(check "the one documented divergence is exactly where it is documented"
       (str/trim (:out (sh "node" "host/flint.mjs" (build! "diverge"))))
       "[\"b\" nil]")
(println "    Java reports group 1 as \"\" there; a Thompson simulation never takes")
(println "    the empty iteration, and neither do Go's RE2 or Rust's regex.")

(let [r (str/trim (:out (sh "node" "host/flint.mjs" (build! "refsim"))))
      n (str/trim (:out (sh "node" "host/flint.mjs" (build! "natsim"))))]
  (check "the native simulator agrees with the cljc reference, span for span" n r))

;; A subject built from many pieces is a ROPE. The simulator reads it through a
;; cursor, so nothing should be materialised -- which `doc/decisions/0012` asks
;; to be asserted rather than assumed.
(spit (str d "/ropey.cljc")
      (str "(ns ropey (:require [flint.regex :as rx]))\n"
           "(defn main [_]\n"
           "  (let [s (loop [i 0 acc \"\"] (if (< i 400) (recur (inc i) (str acc \"word\" i \" \")) acc))\n"
           "        p (rx/pattern \"\\\\s+\")]\n"
           ;; The simulator, directly: `split` would `subs` afterwards and a
           ;; `subs` of a rope does flatten, which is a different claim.
           "    (str (quot (count (flint.rt/re-find-all (:re p) s 0)) 2))))\n"))
(sh "./bin/build-units" "--diagnostics")
(let [w (build! "ropey")
      r (sh "node" "-e"
            (str "import('./host/flint.mjs').then(async (m) => {"
                 "const {module} = await m.load('" w "');"
                 "const i = m.instantiate(module); const out = i.main().out.trim();"
                 "console.log(JSON.stringify({out, materialised: Number(i.exports.stat_flattens(1))}));})"))
      out (str/trim (str (:out r) (:err r)))]
  (println (str "    " out))
  (check "the simulator finds every match in a rope of 400 pieces"
         (some-> (re-find #"\"out\":\"(\d+)\"" out) second) "400")
  (check-that "  ... without materialising it, which is what a cursor is for"
              (= "0" (some-> (re-find #"\"materialised\":(\d+)" out) second))))
(sh "./bin/build-units")

;; Linear by construction. The backtracker needed the gas limit to stop this;
;; there is now nothing to stop.
(spit (str d "/redos.cljc")
      (str "(ns redos)\n"
           "(defn main [args]\n"
           "  (let [n (if (seq args) (parse-long (first args)) 24)\n"
           "        s (apply str (repeat n \"a\"))]\n"
           "    (str (count (re-seq #\"(a+)+$\" (str s \"b\"))))))\n"))
(let [w (build! "redos")
      t (fn [n] (let [t0 (System/nanoTime)]
                  (sh "node" "host/flint.mjs" w (str n))
                  (/ (- (System/nanoTime) t0) 1e6)))
      a (t 24) b (t 48)]
  (println (format "    (a+)+$ over 24 a's %.0f ms, over 48 %.0f ms" a b))
  (check-that "doubling the input does not square the time" (< b (* 3 (max a 1.0)))))

(println (if (zero? @fails) "pike: ok" (str "pike: " @fails " FAILURES")))
(System/exit (if (zero? @fails) 0 1))
