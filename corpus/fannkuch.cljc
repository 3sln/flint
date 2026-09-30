(ns ^{:corpus/kind :collections
      :corpus/twin "fannkuch-typed"
      :corpus/source "Computer Language Benchmarks Game, fannkuch-redux"}
  fannkuch)

;; Permutations of a small vector and prefix reversals, UNANNOTATED. Its twin
;; `fannkuch-typed` is the same algorithm with `^long` hints, so the pair
;; isolates what annotations buy on each runtime.

(defn flips [perm]
  (loop [p perm n 0]
    (let [k (first p)]
      (if (zero? k)
        n
        (recur (into (vec (reverse (subvec p 0 (inc k)))) (subvec p (inc k))) (inc n))))))

(defn permutations [v]
  (if (<= (count v) 1)
    [v]
    (for [i (range (count v))
          rest-p (permutations (into (subvec v 0 i) (subvec v (inc i))))]
      (into [(nth v i)] rest-p))))

(defn main [_]
  (let [n 7
        ps (permutations (vec (range n)))
        fs (map flips ps)
        checksum (reduce + (map-indexed (fn [i f] (if (even? i) f (- f))) fs))]
    (str checksum " " (reduce max fs))))
