;; Compiles the program `test/limits.mjs` drives, then runs it. The assertions
;; are on the JavaScript side because they are about what the HOST can see and
;; rely on: a count it can reproduce, an error it can catch, a limit it can set.
(require '[babashka.fs :as fs])

(def d (str (fs/create-temp-dir)))

(defn sh [& args]
  (let [p (.start (ProcessBuilder. (into-array String args)))
        out (slurp (.getInputStream p)) err (slurp (.getErrorStream p))]
    (.waitFor p) {:exit (.exitValue p) :all (str out err)}))

(spit (str d "/limits.cljc")
      (str
       "(ns limits\n"
       "  \"Work of known shapes, so a budget can be measured against it.\"\n"
       "  (:require [clojure.string :as str]))\n"
       "\n"
       ";; A loop with a fixed amount of work per iteration: the instruction\n"
       ";; count for a given n is a fact about the program, not about the machine.\n"
       "(defn work [n]\n"
       "  (loop [i 0 acc 0]\n"
       "    (if (< i n)\n"
       "      (recur (inc i) (+ acc (* i 3) (if (even? i) 1 0)))\n"
       "      acc)))\n"
       "\n"
       "(defn spin [] (loop [i 0] (if (< i 1000000000) (recur (inc i)) i)))\n"
       "\n"
       ";; Each of these is ONE native call doing O(n) work -- the hole that\n"
       ";; instruction counting alone would leave open.\n"
       "(defn big-eq [n] (let [a (vec (range n)) b (vec (range n))] (= a b)))\n"
       "(defn big-hash [n] (hash (vec (range n))))\n"
       "(defn big-mapseq [n]\n"
       "  (let [m (into {} (map (fn [i] [i i]) (range n)))]\n"
       "    (count (seq m))))\n"
       "(defn big-join [n] (count (flint.rt/str-join (mapv (fn [i] \"abcdefgh\") (range n)))))\n"
       "\n"
       ";; The classic catastrophic pattern: nested quantifiers with a failing\n"
       ";; anchor, which backtracks exponentially in the length of the run.\n"
       "(defn redos [] (count (re-seq #\"(a+)+$\" \"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaab\")))\n"
       "\n"
       ";; Large objects are born in the old generation, so this fills the cap\n"
       ";; directly instead of making the collector copy a growing live set.\n"
       "(defn chunk [] (flint.rt/str-join (mapv (fn [i] \"0123456789abcdef\") (range 4096))))\n"
       "(defn eat [] (loop [acc [] i 0] (if (< i 4000) (recur (conj acc (chunk)) (inc i)) (count acc))))\n"
       "\n"
       "(defn main [args]\n"
       "  (let [what (first args)\n"
       "        n (if (second args) (flint.rt/str->num (second args)) 0)]\n"
       "    (cond\n"
       "      (= what \"work\") (str (work n))\n"
       "      (= what \"spin\") (str (spin))\n"
       "      (= what \"caught\")\n"
       "      (try (str (spin)) (catch Throwable e (pr-str (ex-data e))))\n"
       "      ;; Catch the runaway and immediately start another one: a gate a\n"
       "      ;; candidate can catch its way out of is not a gate.\n"
       "      (= what \"caught-then-spin\")\n"
       "      (str (try (spin) (catch Throwable e (spin))))\n"
       "      (= what \"eq\") (str (big-eq n))\n"
       "      (= what \"hashing\") (str (big-hash n))\n"
       "      (= what \"mapseq\") (str (big-mapseq n))\n"
       "      (= what \"joining\") (str (big-join n))\n"
       "      (= what \"redos\") (str (redos))\n"
       "      (= what \"eat\")\n"
       "      (try (str (eat)) (catch Throwable e (str (ex-message e) \" \" (pr-str (ex-data e)))))\n"
       "      :else \"?\")))\n"))

(let [r (sh "./bin/flint" ":src" d ":fn" "limits/main" ":out" "out/limits.wasm")]
  (when-not (zero? (:exit r))
    (println "build failed:" (:all r)) (System/exit 1)))

(let [r (sh "node" "test/limits.mjs")]
  (print (:all r))
  (flush)
  (System/exit (:exit r)))
