(ns ^{:corpus/kind :search
      :corpus/twin "nqueens-typed"
      :corpus/source "classic backtracking"}
  nqueens)

(defn safe? [placed col]
  (let [row (count placed)]
    (not-any? (fn [[r c]] (or (= c col) (= (- row r) (abs (- col c)))))
              (map-indexed vector placed))))

(defn solve [n placed]
  (if (= (count placed) n)
    1
    (reduce + (for [col (range n) :when (safe? placed col)] (solve n (conj placed col))))))

(defn main [_] (str (solve 8 [])))
