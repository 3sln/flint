(ns construe.bench.suggest
  "A prefix scan over a lexicon.

  §10.0 of construe's spec calls this \"the most expensive unmeasured number\":
  assumed at 1 ms, suspected nearer 0.2 ms, and part of the 96% of session cost
  that is CPU. **This is a representative implementation, not construe's own** —
  the fixtures in `bench/construe/` are the seed interpreter and four annotated
  contexts, not the annotator — so read the number as \"what a prefix scan of
  this shape costs on this runtime\", not as construe's own figure."
  (:require [clojure.string :as str]))

(def alphabet ["a" "b" "c" "d" "e" "f" "g" "h" "i" "j" "k" "l" "m"
               "n" "o" "p" "q" "r" "s" "t" "u" "v" "w" "x" "y" "z"])

(defn lexicon
  "`n` terms with realistic shape: a stem, a separator and a qualifier, so
  prefixes collide the way real vocabulary does."
  [n]
  (mapv (fn [i]
          (let [a (nth alphabet (mod i 26))
                b (nth alphabet (mod (quot i 26) 26))
                c (nth alphabet (mod (quot i 676) 26))]
            {:term (str a b c "-" (mod i 313))
             :atom (str a b c)
             :ancestry [(str a b c) "ingredient"]}))
        (range n)))

(defn scan
  "Every entry whose term starts with `prefix`, ranked shortest first — which is
  what a suggest list wants and what makes the cost the scan rather than the
  sort."
  [lex prefix]
  (let [hits (filterv (fn [e] (str/starts-with? (:term e) prefix)) lex)]
    (vec (sort-by (fn [e] (count (:term e))) hits))))

(defn run [n reps]
  (let [lex (lexicon n)]
    (loop [i 0 acc 0]
      (if (< i reps)
        (recur (inc i)
               (+ acc (count (scan lex (nth alphabet (mod i 26))))))
        acc))))
