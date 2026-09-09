(ns numedge
  "CONVERSION AND WRAPPING, the other block the builtin census turned up.

  `flint/to-long`, `flint/trunc` and the three `unchecked-*` operations were
  named by no conformance image. They are where a runtime's host arithmetic
  shows through: a double-to-integer conversion has to decide what to do with
  a NaN, an infinity and a value one ulp outside the range, and `as i64` in
  Rust, `(long)` in Java and `(long)` in C# do not agree about any of them
  unless someone made them.

  Wrapping is the same question from the other side: `unchecked-add` is
  defined to wrap, and a runtime that traps or promotes instead is wrong in a
  way no ordinary program notices until it does."
  (:require [clojure.math :as m]))

(defn- attempt
  "THE CLASS AS WELL AS THE TEXT. A first version reported only the message,
  and `to-long` of a string threw `ClassCastException` on native and
  `IllegalArgumentException` on both ports -- two divergences that read as
  one, because only the wording showed. The class is what a `catch` selects
  on, so it is the half that matters more."
  [f]
  (try (str (f))
       (catch Exception e (str "threw " (flint.rt/ex-kind e) " " (ex-message e)))))

(defn main [_]
  (let [maxl 9223372036854775807
        minl -9223372036854775808]
    (pr-str
     {;; THE ORDINARY CASES, so the row is not all edges.
      :long [(attempt #(long 1.9)) (attempt #(long -1.9)) (attempt #(long 0.0))
             (attempt #(long -0.0)) (attempt #(long 42)) (attempt #(long -42))]
      ;; TRUNCATION IS TOWARD ZERO, which is not what `floor` does for a
      ;; negative and is the difference the two ports spelled out separately.
      :trunc [(attempt #(long 1.5)) (attempt #(long -1.5))
              (attempt #(long 0.9)) (attempt #(long -0.9))]
      ;; THE EDGE OF THE RANGE. 2^63 is not representable, and the nearest
      ;; double to it is exactly 2^63 -- so the boundary test is about which
      ;; side of it the implementation puts that value on.
      :range [(attempt #(long 9.223372036854775e18))
              (attempt #(long -9.223372036854776e18))
              (attempt #(long 9.3e18))
              (attempt #(long -9.3e18))
              (attempt #(long 1.0e300))]
      ;; NOT A NUMBER AND NOT FINITE: three runtimes, three host conversions,
      ;; and the answer has to be one refusal.
      :nonfinite [(attempt #(long (/ 0.0 0.0)))
                  (attempt #(long (* 1e300 1e300)))
                  (attempt #(long (* -1e300 1e300)))]
      :notnum [(attempt #(long "x")) (attempt #(long nil)) (attempt #(long [1]))]
      ;; WRAPPING, at both ends and through zero.
      :unchecked [(attempt #(unchecked-add maxl 1))
                  (attempt #(unchecked-subtract minl 1))
                  (attempt #(unchecked-multiply maxl 2))
                  (attempt #(unchecked-add maxl maxl))
                  (attempt #(unchecked-multiply minl -1))]
      ;; AND CHECKED ARITHMETIC AT THE SAME PLACES, which must NOT wrap.
      :checked [(attempt #(+ maxl 1)) (attempt #(- minl 1)) (attempt #(* maxl 2))]
      ;; `m/trunc` is the double-to-double one, and keeps the sign of zero.
      :ftrunc [(attempt #(m/floor -0.5)) (attempt #(m/ceil -0.5))
               (attempt #(m/floor 0.5)) (attempt #(m/ceil 0.5))]})))
