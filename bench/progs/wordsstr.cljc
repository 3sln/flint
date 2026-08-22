(ns wordsstr
  "The same workload as `words`, but splitting on a literal string instead of a
  regex. The pair is the point: it isolates what the cljc regex engine costs."
  (:require [clojure.string :as str]
            [words]))
(defn top-words [s k]
  (->> (str/split (str/lower-case s) " ")
       (remove str/blank?)
       frequencies
       (sort-by (fn [e] [(- (val e)) (key e)]))
       (take k)
       (mapv (fn [e] [(key e) (val e)]))))
(defn main [args]
  (let [n (if (seq args) (parse-long (first args)) 200)]
    (pr-str (top-words (words/corpus n) 5))))
