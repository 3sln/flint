(ns ^{:corpus/kind :numeric
      :corpus/annotated true
      :corpus/twin "spectral-norm"
      :corpus/source "Computer Language Benchmarks Game, spectral-norm"}
  spectral-norm-typed
  (:require [clojure.math :as m]))

;; `spectral-norm` with hints on parameters and returns -- the positions JVM
;; Clojure accepts. `a` is the hot function: every matrix element is one call.

(defn a ^double [^long i ^long j] (/ 1.0 (+ (quot (* (+ i j) (+ i j 1)) 2) i 1)))

(defn mul-av [v ^long n]
  (mapv (fn [^long i] (reduce + (map (fn [^long j] (* (a i j) (double (nth v j)))) (range n)))) (range n)))

(defn mul-atv [v ^long n]
  (mapv (fn [^long i] (reduce + (map (fn [^long j] (* (a j i) (double (nth v j)))) (range n)))) (range n)))

(defn mul-atav [v ^long n] (mul-atv (mul-av v n) n))

(defn main [_]
  (let [n 60
        [u v] (reduce (fn [[u _] _] (let [v (mul-atav u n)] [(mul-atav v n) v]))
                      [(vec (repeat n 1.0)) nil] (range 10))
        vbv (reduce + (map * u v))
        vv (reduce + (map * v v))]
    (str (m/round (* (m/sqrt (/ vbv vv)) 1e9)))))
