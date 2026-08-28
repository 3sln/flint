(ns lazy)
;; Laziness has to be lazy. A chain of lazy seqs must not force one another,
;; and an INFINITE one must be usable by taking a prefix -- the JVM port
;; materialised both, which was a stack overflow and then an OutOfMemoryError.
(defn main [_]
  (pr-str
   {:map-chain (vec (take 5 (map inc (map inc (map inc (range 100))))))
    :infinite (vec (take 4 (iterate (fn [x] (* 2 x)) 1)))
    :repeat (vec (take 3 (repeat :x)))
    :filtered (vec (take 3 (filter even? (range 100))))
    :first-of-infinite (first (iterate inc 0))
    :rest-is-lazy (first (rest (iterate inc 0)))
    :nested (vec (take 3 (mapcat (fn [x] [x x]) (range 10))))
    :realised (vec (map (fn [x] (* x x)) [1 2 3]))}))
