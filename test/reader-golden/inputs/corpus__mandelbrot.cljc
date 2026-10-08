(ns ^{:corpus/kind :numeric
      :corpus/source "Computer Language Benchmarks Game, mandelbrot (counted, not rendered)"}
  mandelbrot)

;; How many points of a grid stay bounded -- floating point and a data-dependent
;; inner loop, which is the shape that punishes a slow `recur`.

(defn escapes? [cr ci limit]
  (loop [zr 0.0 zi 0.0 i 0]
    (cond
      (> (+ (* zr zr) (* zi zi)) 4.0) true
      (= i limit) false
      :else (recur (+ (- (* zr zr) (* zi zi)) cr) (+ (* 2.0 zr zi) ci) (inc i)))))

(defn main [_]
  (let [size 120 limit 50]
    (str (count (for [y (range size) x (range size)
                      :let [cr (- (/ (* 2.0 x) size) 1.5)
                            ci (- (/ (* 2.0 y) size) 1.0)]
                      :when (not (escapes? cr ci limit))]
                  1)))))
