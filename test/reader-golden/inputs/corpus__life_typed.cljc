(ns ^{:corpus/kind :collections
      :corpus/annotated true
      :corpus/twin "life"
      :corpus/source "Conway's Game of Life, set-of-cells formulation"}
  life-typed)

;; `life` with the hints JVM Clojure accepts: parameters, and locals bound from
;; an object. See corpus/README.md on why that is fewer than flint takes.

(defn neighbours [[^long x ^long y]]
  (for [dx [-1 0 1] dy [-1 0 1] :when (not (and (= dx 0) (= dy 0)))]
    [(+ x (long dx)) (+ y (long dy))]))

(defn alive? [cells cell ^long n]
  (or (= n 3) (and (= n 2) (contains? cells cell))))

(defn step [cells]
  (set (for [[cell n] (frequencies (mapcat neighbours cells))
             :when (alive? cells cell n)]
         cell)))

(def seed #{[1 0] [2 0] [0 1] [1 1] [1 2]})

(defn main [_]
  (let [gens (take 101 (iterate step seed))]
    (str (count (last gens)) " "
         (reduce + (map count gens)))))
