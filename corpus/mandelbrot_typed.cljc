(ns ^{:corpus/kind :numeric
      :corpus/annotated true
      :corpus/twin "mandelbrot"
      :corpus/source "Computer Language Benchmarks Game, mandelbrot (counted, not rendered)"}
  mandelbrot-typed)

;; `mandelbrot` with `^double`/`^long` on the parameters and the return, which
;; are the positions JVM Clojure accepts (see corpus/README.md). The loop locals
;; are left bare: their initialisers are literals, which Clojure already treats
;; as primitive and refuses a hint on.

(defn escapes? [^double cr ^double ci ^long limit]
  (loop [zr 0.0 zi 0.0 i 0]
    (cond
      (> (+ (* zr zr) (* zi zi)) 4.0) true
      (= i limit) false
      :else (recur (+ (- (* zr zr) (* zi zi)) cr) (+ (* 2.0 zr zi) ci) (inc i)))))

(defn coord ^double [^long k ^long size ^double offset]
  (- (/ (* 2.0 k) size) offset))

(defn main [_]
  (let [size 120 limit 50]
    (str (count (for [y (range size) x (range size)
                      :when (not (escapes? (coord x size 1.5) (coord y size 1.0) limit))]
                  1)))))
