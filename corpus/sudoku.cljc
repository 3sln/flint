(ns ^{:corpus/kind :search
      :corpus/source "constraint-propagating backtracking, after Norvig's solver"}
  sudoku
  (:require [clojure.string :as str]))

;; A grid is a vector of 81 ints, 0 for empty. Candidates for a cell are the
;; digits its row, column and box do not already hold; search fills the cell
;; with the FEWEST candidates first, which is what keeps this from exploding.

(def peers
  (vec (for [i (range 81)]
         (let [r (quot i 9) c (rem i 9)
               br (* 3 (quot r 3)) bc (* 3 (quot c 3))]
           (vec (disj (set (concat (for [k (range 9)] (+ (* r 9) k))
                                   (for [k (range 9)] (+ (* k 9) c))
                                   (for [dr (range 3) dc (range 3)]
                                     (+ (* (+ br dr) 9) (+ bc dc)))))
                      i))))))

(defn candidates [grid i]
  (reduce (fn [s p] (disj s (nth grid p))) #{1 2 3 4 5 6 7 8 9} (nth peers i)))

(defn solve [grid]
  (let [empties (filter #(zero? (nth grid %)) (range 81))]
    (if (empty? empties)
      grid
      (let [[i cs] (apply min-key #(count (second %))
                          (map (fn [i] [i (candidates grid i)]) empties))]
        (some #(solve (assoc grid i %)) (sort cs))))))

(defn parse [s] (mapv #(if (= % ".") 0 (parse-long %)) (map str s)))

(def puzzles
  ["53..7....6..195....98....6.8...6...34..8.3..17...2...6.6....28....419..5....8..79"
   "..3.2.6..9..3.5..1..18.64....81.29..7.......8..67.82....26.95..8..2.3..9..5.1.3.."
   "4.....8.5.3..........7......2.....6.....8.4......1.......6.3.7.5..2.....1.4......"])

(defn main [_]
  (str/join "\n" (map #(apply str (solve (parse %))) puzzles)))
