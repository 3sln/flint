(ns maps)
;; Past ARRAY_MAP_MAX (8), where a flint map stops being a flat array and
;; becomes a CHAMP -- so these iterate in HASH order, not insertion order.
;; That is the case the JVM port originally got wrong, with the right pairs in
;; the wrong sequence.
(defn big [] (reduce (fn [m i] (assoc m (str "k" i) i)) {} (range 40)))
(defn kwmap [] (reduce (fn [m i] (assoc m (keyword (str "key" i)) i)) {} (range 20)))
(defn mixed [] {:a 1 "b" 2 :c 3 4 :four 5.5 :five :g 7 :h 8 :i 9 :j 10 :k 11 :l 12})
(defn main [_]
  (pr-str
   {:string-keys (big)
    :keyword-keys (kwmap)
    :mixed-keys (mixed)
    ;; The hashes themselves, so a divergence names itself rather than showing
    ;; up only as an ordering.
    :hashes [(hash 42) (hash "abc") (hash :a) (hash 'foo/bar) (hash [1 2]) (hash #{1 2})]
    :lookup [(get (big) "k17") (get (kwmap) :key3) (count (big))]}))
