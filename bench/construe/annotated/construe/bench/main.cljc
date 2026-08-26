(ns construe.bench.main
  "flint's entry point. Kept out of `parse.cljc` so that the file cherry and
  flint both compile is byte-for-byte the same code, and neither is measured
  running something the other never saw."
  (:require [construe.bench.parse :as p]
            [construe.bench.patterns :as pat]
            [construe.bench.suggest :as sug]))

(defn main [args]
  (let [what (or (first args) "parse")
        n (if (second args) (flint.rt/str->num (second args)) 1)]
    (cond
      (= what "parse") (str (p/run n))
      (= what "suggest") (str (sug/run 4000 n))
      :else (str (pat/run what n)))))
