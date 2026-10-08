(ns ^{:corpus/kind :numeric
      :corpus/source "Computer Language Benchmarks Game, n-body"}
  nbody
  (:require [clojure.math :as m]))

;; Floating point in a tight loop over a small mutable-in-spirit system, written
;; the way idiomatic Clojure writes it: bodies as maps, each step a new vector.
;; Energies are reported as integers scaled by 1e9 so the answer does not depend
;; on how each runtime PRINTS a double -- that is a separate question.

(def pi 3.141592653589793)
(def solar-mass (* 4 pi pi))
(def days-per-year 365.24)

(def initial
  [{:x 0.0 :y 0.0 :z 0.0 :vx 0.0 :vy 0.0 :vz 0.0 :m solar-mass}
   {:x 4.84143144246472090e+00 :y -1.16032004402742839e+00 :z -1.03622044471123109e-01
    :vx (* 1.66007664274403694e-03 days-per-year) :vy (* 7.69901118419740425e-03 days-per-year)
    :vz (* -6.90460016972063023e-05 days-per-year) :m (* 9.54791938424326609e-04 solar-mass)}
   {:x 8.34336671824457987e+00 :y 4.12479856412430479e+00 :z -4.03523417114321381e-01
    :vx (* -2.76742510726862411e-03 days-per-year) :vy (* 4.99852801234917238e-03 days-per-year)
    :vz (* 2.30417297573763929e-05 days-per-year) :m (* 2.85885980666130812e-04 solar-mass)}
   {:x 1.28943695621391310e+01 :y -1.51111514016986312e+01 :z -2.23307578892655734e-01
    :vx (* 2.96460137564761618e-03 days-per-year) :vy (* 2.37847173959480950e-03 days-per-year)
    :vz (* -2.96589568540237556e-05 days-per-year) :m (* 4.36624404335156298e-05 solar-mass)}
   {:x 1.53796971148509165e+01 :y -2.59193146099879641e+01 :z 1.79258772950371181e-01
    :vx (* 2.68067772490389322e-03 days-per-year) :vy (* 1.62824170038242295e-03 days-per-year)
    :vz (* -9.51592254519715870e-05 days-per-year) :m (* 5.15138902046611451e-05 solar-mass)}])

(defn offset-momentum [bodies]
  (let [px (reduce + (map #(* (:vx %) (:m %)) bodies))
        py (reduce + (map #(* (:vy %) (:m %)) bodies))
        pz (reduce + (map #(* (:vz %) (:m %)) bodies))
        sun (first bodies)]
    (assoc bodies 0 (assoc sun :vx (- (/ px solar-mass))
                               :vy (- (/ py solar-mass))
                               :vz (- (/ pz solar-mass))))))

(defn energy [bodies]
  (let [n (count bodies)]
    (reduce + (for [i (range n)]
                (let [b (nth bodies i)
                      ke (* 0.5 (:m b) (+ (* (:vx b) (:vx b)) (* (:vy b) (:vy b)) (* (:vz b) (:vz b))))
                      pe (reduce + (for [j (range (inc i) n)]
                                     (let [c (nth bodies j)
                                           dx (- (:x b) (:x c)) dy (- (:y b) (:y c)) dz (- (:z b) (:z c))]
                                       (/ (* (:m b) (:m c)) (m/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))))))]
                  (- ke pe))))))

(defn advance [bodies dt]
  (let [n (count bodies)
        bodies (reduce
                (fn [bs [i j]]
                  (let [b (nth bs i) c (nth bs j)
                        dx (- (:x b) (:x c)) dy (- (:y b) (:y c)) dz (- (:z b) (:z c))
                        d2 (+ (* dx dx) (* dy dy) (* dz dz))
                        mag (/ dt (* d2 (m/sqrt d2)))
                        bm (* (:m b) mag) cm (* (:m c) mag)]
                    (-> bs
                        (assoc i (assoc b :vx (- (:vx b) (* dx cm)) :vy (- (:vy b) (* dy cm)) :vz (- (:vz b) (* dz cm))))
                        (assoc j (assoc c :vx (+ (:vx c) (* dx bm)) :vy (+ (:vy c) (* dy bm)) :vz (+ (:vz c) (* dz bm)))))))
                bodies
                (for [i (range n) j (range (inc i) n)] [i j]))]
    (mapv (fn [b] (assoc b :x (+ (:x b) (* dt (:vx b)))
                           :y (+ (:y b) (* dt (:vy b)))
                           :z (+ (:z b) (* dt (:vz b)))))
          bodies)))

(defn scaled [e] (m/round (* e 1e9)))

(defn main [_]
  (let [bodies (offset-momentum initial)
        e0 (energy bodies)
        final (nth (iterate #(advance % 0.01) bodies) 1000)]
    (str (scaled e0) " " (scaled (energy final)))))
