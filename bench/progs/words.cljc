(ns words
  "A realistic mixed workload: split text, normalise, count, sort. Strings,
  maps, sequences and sorting, which is what most real programs actually do."
  (:require [clojure.string :as str]))

(def text
  (str "the quick brown fox jumps over the lazy dog "
       "the dog barks and the fox runs away while the quick dog sleeps "
       "a lazy afternoon for the brown fox and the sleeping dog "))

(defn corpus [n] (str/join " " (repeat n text)))

(defn top-words [s k]
  (->> (str/split (str/lower-case s) #"\s+")
       (remove str/blank?)
       frequencies
       (sort-by (fn [e] [(- (val e)) (key e)]))
       (take k)
       (mapv (fn [e] [(key e) (val e)]))))

(defn main [args]
  (let [n (if (seq args) (parse-long (first args)) 200)]
    (pr-str (top-words (corpus n) 5))))
