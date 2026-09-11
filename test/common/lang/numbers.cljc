(ns lang.numbers
  "Arithmetic, and the boundaries a NaN-boxed value has that a host int does not.

  A flint fixnum is 48 bits of payload, not 64, so the interesting numbers are
  not the ones a JVM `long` finds interesting. Those edges are checked here
  because every runtime has to place them identically -- the boxing is part of
  the language, not part of a port."
  (:require [flint.check :refer [expect]]))

(defn ^:flint.check/test integer-arithmetic []
  (expect = 3 (+ 1 2))
  (expect = 7 (- 10 3))
  (expect = 42 (* 6 7))
  (expect = 3 (quot 7 2))
  (expect = 1 (rem 7 2))
  (expect = -3 (quot -7 2))
  (expect = -1 (rem -7 2))
  (expect = 0 (+))
  (expect = 1 (*)))

(defn ^:flint.check/test division-is-not-truncation []
  ;; `/` on two ints that do not divide evenly is the case where a language
  ;; picks a side. flint's answer is a double, so `(/ 1 4)` is 0.25 and not 0 --
  ;; a runtime that quietly truncated would pass every `quot` check above.
  (expect = 0.25 (/ 1 4))
  (expect = 2 (/ 4 2))
  (expect = 0.5 (/ 1.0 2)))

(defn ^:flint.check/test fixnum-boundary []
  ;; 47 bits plus a sign: the largest value that stays unboxed. One past it has
  ;; to still be RIGHT, whatever representation it takes -- the boxing is an
  ;; implementation detail and arithmetic across the boundary is not.
  (let [m 140737488355327]                 ; (1 << 47) - 1
    (expect = m (- (+ m 1) 1))
    (expect = (+ m 1) (+ m 1))
    (expect < m (+ m 1))
    (expect = (- 0 m) (- m))
    (expect = 0 (+ m (- m)))))

(defn ^:flint.check/test comparison-chains []
  (expect true? (< 1 2 3))
  (expect false? (< 1 3 2))
  (expect true? (<= 2 2))
  (expect true? (> 3 1))
  (expect false? (>= 2 3))
  (expect true? (= 1 1))
  (expect false? (= 1 2)))

(defn ^:flint.check/test equals-is-not-numeric-equality []
  ;; `=` across the two numeric representations is FALSE, and `==` is true.
  ;; That is Clojure's split and it surprises people, which is exactly why it
  ;; is pinned: a runtime that made `=` numeric would be more intuitive and
  ;; would silently change the meaning of every map keyed by a number.
  (expect false? (= 1 1.0))
  (expect true? (== 1 1.0))
  (expect true? (== 3.0 (+ 1 2.0)))
  (expect true? (== 6.0 (* 2.0 3)))
  ;; And the keys really do stay distinct, which is the consequence that
  ;; matters more than the predicate.
  (expect = 2 (count (assoc {1 :int} 1.0 :float))))

(defn ^:flint.check/test whole-doubles-keep-their-point []
  ;; The classic silent divergence (`DECISIONS.md#other-hosts`): a double that
  ;; happens to be whole must not print as an integer, or two hosts disagree
  ;; about a value they both computed correctly.
  (expect = "4.0" (str 4.0))
  (expect = "1" (str 1))
  (expect = "2.5" (pr-str 2.5)))

(defn ^:flint.check/test bit-operations-are-signed []
  (expect = 8 (bit-and 12 10))
  (expect = 14 (bit-or 12 10))
  (expect = 6 (bit-xor 12 10))
  (expect = -1 (bit-not 0))
  (expect = 1024 (bit-shift-left 1 10))
  ;; The case that separates `>>` from `>>>` has to use a negative operand or
  ;; the two agree and neither is tested.
  (expect = -4 (bit-shift-right -16 2))
  (expect true? (bit-test 5 0))
  (expect false? (bit-test 5 1)))

(defn ^:flint.check/test loop-counter-type-hypothesis []
  ;; A loop slot's type is hypothesised from its initialiser and kept only if
  ;; every `recur` proves it. `counted` holds the hypothesis; `widened` breaks
  ;; it by putting a float in an int slot, which is legal -- so the answer has
  ;; to be right with the specialised opcodes and without them.
  (expect = 285 (loop [i 0 acc 0]
                  (if (< i 10) (recur (+ i 1) (+ acc (* i i))) acc)))
  (expect = 1.5 (loop [x 0 n 0]
                  (if (< n 3) (recur (+ x 0.5) (+ n 1)) x))))

(defn ^:flint.check/test predicates-survive-being-values []
  ;; A direct `(int? x)` compiles to the `type-p` opcode; passing one to
  ;; `filterv` is the only thing that needs the BUILTIN to exist. The two
  ;; paths have been out of step before.
  (expect = [1 3] (filterv int? [1 :a 2.5 "s" 3]))
  (expect = [2.5] (filterv double? [1 :a 2.5 "s" 3]))
  (expect = [1 2.5 3] (filterv number? [1 :a 2.5 "s" 3])))

(defn ^:flint.check/test a-large-literal-survives-the-image []
  ;; A literal OUTSIDE the fixnum range has to be BOXED when the image loads
  ;; it. Both ports called `fixnum` and truncated instead, so `2^62` read back
  ;; as 0 and `Long.MAX_VALUE` as -1 -- while the same number COMPUTED at
  ;; runtime was fine. That is why nothing caught it: every arithmetic test
  ;; built its big values rather than writing them down.
  (expect = "4611686018427387904" (str 4611686018427387904))
  (expect = "9223372036854775807" (str 9223372036854775807))
  (expect = "-9223372036854775807" (str -9223372036854775807))
  (expect = 4611686018427387904 (* 2305843009213693952 2))
  (expect = 0 (- 4611686018427387904 4611686018427387904)))

(defn ^:flint.check/test dividing-the-most-negative-integer []
  ;; `(quot MIN -1)` is the one integer division that OVERFLOWS: the true
  ;; answer is 2^63 and the widest integer here is 2^63-1. Native guarded it
  ;; and both ports used the host's `/` -- which on the JVM answers MIN
  ;; SILENTLY and on the CLR raises a host `OverflowException` that is not a
  ;; flint value at all. Three runtimes, three answers, and nothing asked.
  (let [most-negative (- -9223372036854775807 1)]
    (expect = "integer overflow"
            (try (quot most-negative -1) nil (catch Throwable e (ex-message e))))
    (expect = "integer overflow"
            (try (/ most-negative -1) nil (catch Throwable e (ex-message e))))
    (expect = "integer overflow"
            (try (- most-negative) nil (catch Throwable e (ex-message e))))
    ;; And the ordinary cases still divide.
    (expect = 3 (quot 7 2))
    (expect = -3 (quot -7 2))
    (expect = 1 (rem 7 2))))
