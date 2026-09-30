(ns ^{:corpus/kind :numeric
      :corpus/source "Computer Language Benchmarks Game, spectral-norm"}
  spectral-norm
  (:require [clojure.math :as m]))

(defn a [i j] (/ 1.0 (+ (/ (* (+ i j) (+ i j 1)) 2) i 1)))

(defn mul-av [v n]
  (mapv (fn [i] (reduce + (map (fn [j] (* (a i j) (nth v j))) (range n)))) (range n)))

(defn mul-atv [v n]
  (mapv (fn [i] (reduce + (map (fn [j] (* (a j i) (nth v j))) (range n)))) (range n)))

(defn mul-atav [v n] (mul-atv (mul-av v n) n))

(defn main [_]
  (let [n 60
        [u v] (reduce (fn [[u _] _] (let [v (mul-atav u n)] [(mul-atav v n) v]))
                      [(vec (repeat n 1.0)) nil] (range 10))
        vbv (reduce + (map * u v))
        vv (reduce + (map * v v))]
    (str (m/round (* (m/sqrt (/ vbv vv)) 1e9)))))
