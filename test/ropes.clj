;; Rope strings (`doc/decisions/0011` §2).
;;
;; Three tiers -- inline, flat, rope -- and ONE string. Everything here is a
;; property that survives a naive implementation: the answers are all checked
;; against babashka running the same source, because "it looks right" is how a
;; representation bug reaches a map key.
(require '[clojure.string :as str] '[babashka.fs :as fs] '[clojure.string :as string])

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

;; The body is shared verbatim between flint and babashka, so a divergence is a
;; divergence and not a difference in the test.
(def body
  (str "(defn big [n unit] (loop [i 0 acc \"\"] (if (< i n) (recur (inc i) (str acc unit)) acc)))\n"
       "(defn probe []\n"
       "  (let [a (big 200 \"abcdefghij\")\n"
       "        b (big 200 \"0123456789\")\n"
       "        c (str a b)\n"
       "        flat (apply str (concat (repeat 200 \"abcdefghij\") (repeat 200 \"0123456789\")))\n"
       "        u (big 60 \"héllo wörld \")]\n"
       "    [(count a) (count c) (count u)\n"
       "     (= c flat) (= (hash c) (hash flat))\n"
       "     (= (get {c :found} flat) :found)\n"
       "     (subs c 0 10) (subs c 1995 2005) (subs c 3990)\n"
       ;; `str` around each `nth`: flint has no char type -- a character IS a
       ;; one-character string (`runtime/src/strs.rs`) -- and that divergence is
       ;; deliberate and not what this test is about.
       "     (str (nth c 0)) (str (nth c 1999)) (str (nth c 2000))\n"
       "     (str (nth u 1)) (subs u 0 6) (count (str/split c #\"0\"))\n"
       "     (str/includes? c \"j0\") (str/starts-with? c \"abc\") (str/ends-with? c \"789\")\n"
       "     (str/index-of c \"0123\") (str/upper-case (subs c 0 4))\n"
       "     (count (str/replace c \"abc\" \"X\"))\n"
       "     (= (str a b) (str a b)) (compare (subs c 0 3) \"abd\")]))\n"))

(spit (str d "/probe.cljc")
      (str "(ns probe (:require [clojure.string :as str]))\n" body
           "(defn main [_] (pr-str (probe)))\n"))
(spit "/tmp/rope-bb.clj"
      (str "(require '[clojure.string :as str])\n" body "(prn (probe))\n"))

(println "ropes: three tiers, one string")

(let [r (sh "./bin/flint" ":src" d ":fn" "probe/main" ":out" "out/ropes.wasm")]
  (when-not (zero? (:exit r)) (println "build failed:" (:all r)) (System/exit 1)))
(let [f (str/trim (:out (sh "node" "host/flint.mjs" "out/ropes.wasm")))
      b (str/trim (:out (sh "bb" "/tmp/rope-bb.clj")))]
  (check "every rope answer matches babashka on the same source" f b)
  (when (not= f b)
    (println "        flint   " f)
    (println "        babashka" b)))

;; And the discipline 0011 asks for by name: count the flattens, do not hope
;; about them. A rope that materialises on every operation is slower than the
;; flat string it replaced and passes every check above.
(spit (str d "/flat.cljc")
      (str "(ns flat)\n"
           "(defn main [_]\n"
           "  (let [s (loop [i 0 acc \"\"] (if (< i 4000) (recur (inc i) (str acc \"0123456789abcdef\")) acc))]\n"
           "    (str (count s))))\n"))
(sh "./bin/build-units" "--diagnostics")
(let [r (sh "./bin/flint" ":src" d ":fn" "flat/main" ":out" "out/ropes-flat.wasm")]
  (when-not (zero? (:exit r)) (println "build failed:" (:all r)) (System/exit 1)))
(let [r (sh "node" "-e"
            (str "import('./host/flint.mjs').then(async (m) => {"
                 "const {module} = await m.load('out/ropes-flat.wasm');"
                 "const i = m.instantiate(module); const out = i.main().out.trim();"
                 "console.log(JSON.stringify({out, calls: Number(i.exports.stat_flattens(0)),"
                 " materialised: Number(i.exports.stat_flattens(1)),"
                 " bytes: Number(i.exports.stat_flattens(2)),"
                 " unflat: Number(i.exports.stat_flattens(3))}));})"))
      out (str/trim (str (:out r) (:err r)))]
  (println (str "    " out))
  (check "4000 concatenations build the right string"
         (some-> (re-find #"\"out\":\"(\d+)\"" out) second) "64000")
  ;; The point of the tree: building it materialises NOTHING. One `count` at the
  ;; end is allowed to, and that is the whole budget.
  (check-that "and materialise the bytes at most once"
              (<= (or (some-> (re-find #"\"materialised\":(\d+)" out) second parse-long) 99) 1))
  ;; And nothing may reach `as_str` as an unflattened rope. It borrows, so it
  ;; cannot materialise; it returns `None`, and a caller reading that as "not a
  ;; string" truncates in silence -- which is what the port drain did until the
  ;; send flattened first.
  (check "no rope reached `as_str` without being flattened"
         (some-> (re-find #"\"unflat\":(\d+)" out) second) "0"))
(sh "./bin/build-units")

(println (if (zero? @fails) "ropes: ok" (str "ropes: " @fails " FAILURES")))
(System/exit (if (zero? @fails) 0 1))
