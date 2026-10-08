(ns ^{:corpus/kind :strings
      :corpus/source "prefix scan over a generated lexicon, construe's suggest shape (was bench/construe)"
      :corpus/twin "construe-suggest-typed"}
  construe-suggest
  (:require [construe.bench.suggest :as s]))

(defn main [_]
  (let [lex (s/lexicon 4000)]
    (str (s/run 4000 26) " " (pr-str (mapv :term (take 5 (s/scan lex "ab")))))))
