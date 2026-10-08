(ns clojure.math
  "clojure.math, over libm.

  Deliberately absent, because half-implementing them is worse than leaving them
  out: `ulp`, `nextAfter`, `nextUp`, `nextDown`, `IEEEremainder`, `getExponent`,
  `scalb`, `fma`, and the `*Exact` overflow-checking integer functions -- all of
  which need guarantees about rounding and exponent access that we do not have.
  `random` is absent because it is not pure and this language has no source of
  entropy.")

(def PI 3.141592653589793)
(def E 2.718281828459045)

(defn sqrt [x] (flint.rt/sqrt x))
(defn cbrt [x] (flint.rt/cbrt x))
(defn exp [x] (flint.rt/exp x))
(defn expm1 [x] (flint.rt/expm1 x))
(defn log [x] (flint.rt/log x))
(defn log10 [x] (flint.rt/log10 x))
(defn log1p [x] (flint.rt/log1p x))
(defn sin [x] (flint.rt/sin x))
(defn cos [x] (flint.rt/cos x))
(defn tan [x] (flint.rt/tan x))
(defn asin [x] (flint.rt/asin x))
(defn acos [x] (flint.rt/acos x))
(defn atan [x] (flint.rt/atan x))
(defn atan2 [y x] (flint.rt/atan2 y x))
(defn sinh [x] (flint.rt/sinh x))
(defn cosh [x] (flint.rt/cosh x))
(defn tanh [x] (flint.rt/tanh x))
(defn hypot [x y] (flint.rt/hypot x y))
(defn pow [x y] (flint.rt/pow x y))
(defn floor [x] (flint.rt/floor x))
(defn ceil [x] (flint.rt/ceil x))
(defn rint [x] (flint.rt/rint x))
(defn signum [x] (flint.rt/signum x))
(defn copy-sign [x y] (flint.rt/copy-sign x y))
(defn to-radians [d] (flint.rt/mul d (flint.rt/div PI 180.0)))
(defn to-degrees [r] (flint.rt/mul r (flint.rt/div 180.0 PI)))

(defn abs
  "Absolute value. Integral for integers, as `Math/abs` is."
  [x]
  (if (int? x) (if (< x 0) (- x) x) (flint.rt/fabs x)))

(defn round
  "Rounds half up, to a long, as `Math/round` does."
  [x]
  (if (int? x) x (flint.rt/to-long (flint.rt/floor (flint.rt/add x 0.5)))))

(defn floor-div [x y] (flint.rt/to-long (flint.rt/floor (flint.rt/div x y))))
(defn floor-mod [x y] (- x (* (floor-div x y) y)))
