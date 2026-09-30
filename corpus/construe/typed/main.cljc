(ns construe.typed.main
  "flint's entry point. Kept out of `parse.cljc` so that the file cherry and
  flint both compile is byte-for-byte the same code, and neither is measured
  running something the other never saw."
  (:require [construe.typed.parse :as p]
            [construe.typed.patterns :as pat]
            [construe.typed.suggest :as sug]))

(defn main [args]
  (let [what (or (first args) "parse")
        n (if (second args) (parse-long (second args)) 1)]
    (cond
      (= what "parse") (str (p/run n))
      (= what "suggest") (str (sug/run 4000 n))
      :else (str (pat/run what n)))))
