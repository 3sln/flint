(ns ^{:corpus/kind :collections
      :corpus/annotated true
      :corpus/twin "fannkuch"
      :corpus/source "Computer Language Benchmarks Game, fannkuch-redux"}
  fannkuch-typed)

;; `fannkuch` with `^long` hints, same algorithm, same answer: the difference
;; between the two is the difference annotations make.
;;
;; ONLY HINTS JVM CLOJURE ACCEPTS, which is fewer than flint does. Clojure refuses
;; to hint a local whose initializer is already primitive -- "Can't type hint a
;; local with a primitive initializer" -- so `(loop [^long n 0] ..)` and
;; `(let [n 7] ..)`, both fine in flint, are not portable and are not here.
;; What remains is what Clojure allows: parameters, and locals bound from an
;; object (`first`, `reduce`, a destructured element).

(defn flips [perm]
  (loop [p perm n 0]
    (let [^long k (first p)]
      (if (zero? k)
        n
        (recur (into (vec (reverse (subvec p 0 (inc k)))) (subvec p (inc k))) (inc n))))))

(defn permutations [v]
  (if (<= (count v) 1)
    [v]
    (for [^long i (range (count v))
          rest-p (permutations (into (subvec v 0 i) (subvec v (inc i))))]
      (into [(nth v i)] rest-p))))

(defn main [_]
  (let [n 7
        ps (permutations (vec (range n)))
        fs (map flips ps)
        ^long checksum (reduce + (map-indexed (fn [^long i ^long f] (if (even? i) f (- f))) fs))]
    (str checksum " " (reduce max fs))))
