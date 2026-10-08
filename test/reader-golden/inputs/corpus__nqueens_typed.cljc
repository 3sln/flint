(ns ^{:corpus/kind :search
      :corpus/annotated true
      :corpus/twin "nqueens"
      :corpus/source "classic backtracking"}
  nqueens-typed)

;; Hints only where JVM Clojure accepts them: see `fannkuch-typed`. `(count ..)`
;; is already primitive, so hinting the local it initialises is a compile error
;; there even though flint accepts it.
(defn safe? [placed ^long col]
  (let [row (count placed)]
    (not-any? (fn [[^long r ^long c]] (or (= c col) (= (- row r) (abs (- col c)))))
              (map-indexed vector placed))))

(defn solve [^long n placed]
  (if (= (count placed) n)
    1
    (reduce + (for [^long col (range n) :when (safe? placed col)] (solve n (conj placed col))))))

(defn main [_] (str (solve 8 [])))
