(ns ^{:corpus/kind :parser
      :corpus/source "construe's seed interpreter over four real annotated contexts (was bench/construe)"
      :corpus/twin "construe-parse"
      :corpus/annotated true}
  construe-parse-typed
  (:require [construe.typed.parse :as p]))

;; The interpreter's OUTPUT, not only its checksum: a checksum of printed
;; lengths agrees across two parsers that put the same atoms in different
;; fields. Maps are rendered with their entries sorted by printed key, so the
;; answer does not depend on any runtime's hash order.
(defn canon [x]
  (cond (map? x) (vec (sort-by #(pr-str (first %)) (map (fn [[k v]] [k (canon v)]) x)))
        (coll? x) (mapv canon x)
        :else x))

(defn main [_]
  (str (p/run 20) " " (pr-str (mapv #(canon (p/interpret %)) p/contexts))))
