(ns wordcount
  "A slightly bigger example: read EDN configuration from the first argument,
  count words in the second, and report the result as EDN.

      flint :src examples :fn wordcount/main :out out/wc.wasm
      node host/flint.mjs out/wc.wasm '{:top 3 :min-length 3}' 'the cat sat on the mat'

  Nothing here is flint-specific -- it is ordinary portable Clojure."
  (:require [clojure.string :as str]
            [clojure.edn :as edn]))

(def defaults {:top 5 :min-length 1})

(defn parse-config [s]
  (merge defaults (when (seq s) (edn/read-string s))))

(defn tally [text {:keys [min-length]}]
  (->> (str/split (str/lower-case text) #"[^a-z0-9']+")
       (remove str/blank?)
       (filter (fn [w] (>= (count w) min-length)))
       frequencies))

(defn report [text config]
  (let [counts (tally text config)]
    {:words (reduce + (vals counts))
     :distinct (count counts)
     :top (->> counts
               (sort-by (fn [e] [(- (val e)) (key e)]))
               (take (:top config))
               (mapv (fn [e] [(key e) (val e)])))}))

(defn main [args]
  (let [config (parse-config (first args))
        text (str/join " " (rest args))]
    (pr-str (report text config))))
