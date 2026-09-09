(ns mathfns
  "`clojure.math`, which no conformance program touched.

  Found by asking which of the 192 builtins in `dist/slots.json` are named by
  no conformance image: 59 were, and eighteen of those were the transcendental
  functions -- the largest uncovered block, and the one where four
  implementations are least likely to agree by construction:

    wasm     the `libm` crate
    native   the platform's libm, through Rust's `f64::sin`
    JVM      `Math.sin`, permitted to differ from `StrictMath` by one ulp
    CLR      `System.Math.Sin`

  THIS FILE COVERS THE EXACT ONES ONLY, and that is a deliberate line rather
  than the easy subset. `sqrt` is correctly rounded by IEEE 754 and `floor`,
  `rint`, `copy-sign` and the integer divisions have one right answer, so a
  disagreement in any of them is a BUG. The transcendentals do not: they are
  permitted to differ in the last ulp, all four of these do, and deciding
  which is authoritative is a design question about what flint promises --
  not something a conformance file should answer by asserting whichever
  runtime was measured first.

  MEASURED, so the question has numbers attached. Against the JVM, native
  differs on atan (2 of 10 inputs), exp (2 of 13), cosh (3 of 11), tan (6 of
  15), sinh (3 of 11), and singly on cos, cbrt, asin and acos -- always in
  the last digit, e.g. `atan(0.5)` is 0.46364760900080615 native and
  0.4636476090008061 on the JVM.

  AND A SEPARATE ONE THE SAME RUN FOUND, which is about PRINTING and not
  about mathematics: `4.9E-324` and `5.0E-324` are the same double, and Rust
  and Java disagree about which digits are the shortest that read back. Over
  20 007 doubles they choose differently for 7 -- three subnormals, and four
  where a tie in the last digit breaks the other way. `dblstr.kin` borrows
  the digits from the host and so inherits that, which is a real limit on
  `A double looks the same on every runtime` and is written down here rather
  than left as a claim the suite cannot back."
  (:require [clojure.math :as m]))

(defn- probe [f xs] (mapv (fn [x] (str (f x))) xs))

(defn main [_]
  (let [near-zero [0.0 -0.0 1.0e-17 -1.0e-17]
        ordinary [0.5 1.0 -1.0 2.0 3.141592653589793 2.718281828459045]
        positive [1.0e-300 0.5 1.0 2.0 1.0e17]]
    (pr-str
     {;; CORRECTLY ROUNDED by IEEE 754: every implementation must return the
      ;; same bits, so this one is an assertion and not a courtesy.
      :sqrt (probe m/sqrt (concat near-zero positive))
      ;; EXACT: a single right answer, and the sign of zero is part of it.
      :floor (probe m/floor [-1.5 -0.5 0.0 -0.0 0.5 1.5 1.0e300])
      :ceil (probe m/ceil [-1.5 -0.5 0.0 -0.0 0.5 1.5 1.0e300])
      ;; `rint(-0.5)` IS -0.0, and the native build said 0.0 while the wasm
      ;; build said -0.0 -- one hand-rolled round-half-to-even that negated by
      ;; subtracting, in the branch the other build never took.
      :rint (probe m/rint [-2.5 -1.5 -0.5 -0.0 0.0 0.5 1.5 2.5])
      ;; NaN AND BOTH ZEROS. The CLR reached this through `System.Math.Sign`,
      ;; which answers an int -- so -0.0 came back 0.0 -- and which THROWS on
      ;; a NaN rather than returning one.
      :signum (probe m/signum [-2.0 -0.0 0.0 2.0 (/ 0.0 0.0)])
      :copy-sign (mapv (fn [p] (str (m/copy-sign (first p) (second p))))
                       [[1.0 -0.0] [1.0 -2.0] [-1.0 0.0] [-1.0 2.0]])
      :abs (probe m/abs [-1.5 1.5 -0.0 0.0])
      :round (probe m/round [-2.5 -1.5 -0.5 0.5 1.5 2.5])
      :floor-div (mapv (fn [p] (str (m/floor-div (first p) (second p))))
                       [[7 2] [-7 2] [7 -2] [-7 -2]])
      :floor-mod (mapv (fn [p] (str (m/floor-mod (first p) (second p))))
                       [[7 2] [-7 2] [7 -2] [-7 -2]])
      ;; One multiplication by a constant, so exact given the same constant.
      :to-radians (probe m/to-radians [0.0 90.0 180.0 -45.0])
      :to-degrees (probe m/to-degrees [0.0 1.5707963267948966 3.141592653589793])
      ;; POW AND HYPOT only where the answer is exact: a power of two, a unit,
      ;; and the 3-4-5 triangle. Everything else about them is approximation.
      :pow (mapv (fn [p] (str (m/pow (first p) (second p))))
                 [[2.0 10.0] [2.0 0.5] [1.0 1.0e300] [0.0 0.0] [-1.0 2.0]])
      ;; THE LEGS THAT OVERFLOW A NAIVE FORMULA. `hypot` exists precisely to
      ;; avoid the intermediate, so these are a requirement rather than an
      ;; approximation: the CLR computed `Sqrt(x*x + y*y)` and answered
      ;; Infinity for the first and 0.0 for the second.
      :hypot (mapv (fn [p] (str (m/hypot (first p) (second p))))
                   [[3.0 4.0] [0.0 0.0] [-3.0 -4.0]
                    [1.0e300 1.0e300] [1.0e-300 1.0e-300]])})))
