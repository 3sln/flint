;; The dominant defect shape in this codebase, made into a check.
;;
;; Three times in one session a LINEAR algorithm was quadratic because one step
;; inside it was O(n) per call:
;;
;;   * `str_index_of` rescanned the whole haystack for `is_ascii`, so `split`
;;     was O(n^2) -- 223 million byte checks to find 6 800 spaces;
;;   * it then did the same again via `from_utf8`, which validates;
;;   * and the Pike VM decoded the subject on every `find-from`, so `split` was
;;     quadratic a third time and the new matcher was FOUR TIMES SLOWER than the
;;     backtracker it replaced.
;;
;; Every one was found by accident. None was visible to the correctness suite,
;; because all three produced right answers. What they were visible to is GAS:
;; the counter is proportional to work and deterministic, so doubling the input
;; must at most double the count. A ratio near four is a quadratic, and it says
;; so without a stopwatch and without flaking.
;;
;; This is a floor, not a ceiling. Add an operation here whenever one is written.
(require '[clojure.string :as str] '[babashka.fs :as fs])

(def fails (atom 0))
(defn check-that [label ok]
  (if ok (println "  ok  " label)
      (do (swap! fails inc) (println "  FAIL" label))))

(defn sh [& args]
  (let [p (.start (ProcessBuilder. (into-array String args)))
        out (slurp (.getInputStream p)) err (slurp (.getErrorStream p))]
    (.waitFor p) {:exit (.exitValue p) :out out :err err :all (str out err)}))

(def d (str (fs/create-temp-dir)))

;; Every case builds its own input from `n`, so the input grows with the
;; parameter and nothing is shared between runs.
(spit (str d "/scale.cljc")
      (str "(ns scale (:require [clojure.string :as str]))\n"
           "(defn text [n] (loop [i 0 acc \"\"] (if (< i n) (recur (inc i) (str acc \"word\" i \" \")) acc)))\n"
           "(defn words [n] (mapv (fn [i] (str \"w\" i)) (range n)))\n"
           "(defn main [args]\n"
           "  (let [what (first args)\n"
           "        n (flint.rt/str->num (second args))]\n"
           "    (str\n"
           "      (cond\n"
           "        (= what \"concat\") (count (text n))\n"
           "        (= what \"split-literal\") (count (str/split (text n) \" \"))\n"
           "        (= what \"split-regex\") (count (str/split (text n) #\"\\s+\"))\n"
           "        (= what \"re-seq\") (count (re-seq #\"[a-z]+\" (text n)))\n"
           "        (= what \"replace-str\") (count (str/replace (text n) \"word\" \"W\"))\n"
           "        (= what \"replace-re\") (count (str/replace (text n) #\"[0-9]+\" \"#\"))\n"
           "        (= what \"lower-case\") (count (str/lower-case (text n)))\n"
           "        (= what \"index-of\") (loop [i 0 c 0]\n"
           "                              (let [j (str/index-of (text n) \" \" i)]\n"
           "                                (if (or (nil? j) (> c 200)) c (recur (inc j) (inc c)))))\n"
           "        (= what \"join\") (count (str/join \",\" (words n)))\n"
           "        (= what \"subs\") (loop [i 0 c 0] (if (< i n) (recur (inc i) (+ c (count (subs (text 40) 0 (min 20 i))))) c))\n"
           "        (= what \"includes\") (count (filterv (fn [w] (str/includes? (text 40) w)) (words n)))\n"
           "        (= what \"conj-vec\") (count (reduce conj [] (range n)))\n"
           "        (= what \"into-map\") (count (into {} (mapv (fn [i] [i i]) (range n))))\n"
           "        (= what \"frequencies\") (count (frequencies (words n)))\n"
           "        (= what \"group-by\") (count (group-by odd? (range n)))\n"
           "        (= what \"distinct\") (count (distinct (range n)))\n"
           "        (= what \"str-count\") (count (text n))\n"
           "        (= what \"reverse\") (count (reverse (range n)))\n"
           "        :else -1))))\n"))

(let [r (sh "./bin/flint" ":src" d ":fn" "scale/main" ":out" "out/scale.wasm")]
  (when-not (zero? (:exit r)) (println "build failed:" (:all r)) (System/exit 1)))

;; `sort` is n log n, so it is measured with the bound it deserves rather than
;; excluded -- an operation nobody checks is where the next one hides.
(def linear
  ["concat" "split-literal" "split-regex" "re-seq" "replace-str" "replace-re"
   "lower-case" "index-of" "join" "subs" "includes" "conj-vec" "into-map"
   "frequencies" "group-by" "distinct" "str-count" "reverse"])

(println "scaling: doubling the input must not square the work")
(let [runner (str "import('./host/flint.mjs').then(async (m) => {"
                  "const {module} = await m.load('out/scale.wasm');"
                  "const out = {};"
                  "for (const w of [" (str/join "," (map pr-str linear)) "]) {"
                  "  const r = [];"
                  "  for (const n of [400, 800]) {"
                  "    const i = m.instantiate(module);"
                  "    i.exports.set_step_limit(0x7fffffff, 0xffffffff);"
                  "    const res = i.main(w, String(n));"
                  "    if (res.code !== 0) { console.error(w + ': ' + res.out); process.exit(1); }"
                  "    r.push(Number(i.exports.stat_steps()));"
                  "  }"
                  "  out[w] = r;"
                  "}"
                  "console.log(JSON.stringify(out));})")
      r (sh "node" "-e" runner)
      out (str/trim (str (:out r) (:err r)))]
  (when-not (zero? (:exit r)) (println out) (System/exit 1))
  (let [m (into {} (for [[_ k a b] (re-seq #"\"([a-z-]+)\":\[(\d+),(\d+)\]" out)]
                     [k [(parse-long a) (parse-long b)]]))]
    (doseq [w linear]
      (let [[a b] (get m w [0 0])
            ratio (if (pos? a) (double (/ b a)) 0.0)]
        (println (format "    %-16s %9d -> %9d   %.2fx" w a b ratio))
        ;; Below 1.5 would mean the work is not being counted at all, which is
        ;; the OTHER way this check can be worthless -- a zero from an
        ;; instrument that is not measuring.
        (check-that (str w " is linear in the input, and counted")
                    (and (> ratio 1.4) (< ratio 2.6)))))))

(println (if (zero? @fails) "scaling: ok" (str "scaling: " @fails " FAILURES")))
(System/exit (if (zero? @fails) 0 1))
