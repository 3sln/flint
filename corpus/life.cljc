(ns ^{:corpus/kind :collections
      :corpus/source "Conway's Game of Life, set-of-cells formulation"}
  life)

;; The idiomatic Clojure Life: the board is a set of live cells, and a step is
;; `frequencies` over every live cell's neighbours. Sets, maps and seqs, with
;; vectors as keys -- hashing and equality of small collections is the work.

(defn neighbours [[x y]]
  (for [dx [-1 0 1] dy [-1 0 1] :when (not (and (= dx 0) (= dy 0)))]
    [(+ x dx) (+ y dy)]))

(defn step [cells]
  (set (for [[cell n] (frequencies (mapcat neighbours cells))
             :when (or (= n 3) (and (= n 2) (contains? cells cell)))]
         cell)))

;; An R-pentomino: small seed, long chaotic history.
(def seed #{[1 0] [2 0] [0 1] [1 1] [1 2]})

(defn main [_]
  (let [gens (take 101 (iterate step seed))]
    (str (count (last gens)) " "
         (reduce + (map count gens)))))
