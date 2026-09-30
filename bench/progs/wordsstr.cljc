(ns wordsstr
  "The same workload as `words`, but splitting on a literal string instead of a
  regex. The pair is the point: it isolates what the cljc regex engine costs.

  NOT IN THE CORPUS, where `words` is: splitting on a STRING is flint's, and JVM
  Clojure's `str/split` takes only a regex -- a ClassCastException there. The
  text is restated here rather than required from `corpus/words.cljc` so this
  instrument builds from `bench/progs` alone."
  (:require [clojure.string :as str]))

(def text
  (str "the quick brown fox jumps over the lazy dog "
       "the dog barks and the fox runs away while the quick dog sleeps "
       "a lazy afternoon for the brown fox and the sleeping dog "))

(defn corpus [n] (str/join " " (repeat n text)))
(defn top-words [s k]
  (->> (str/split (str/lower-case s) " ")
       (remove str/blank?)
       frequencies
       (sort-by (fn [e] [(- (val e)) (key e)]))
       (take k)
       (mapv (fn [e] [(key e) (val e)]))))
(defn main [args]
  (let [n (if (seq args) (parse-long (first args)) 200)]
    (pr-str (top-words (corpus n) 5))))
