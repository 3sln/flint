(ns lang.bytes
  "Byte strings, checked on EVERY RUNTIME rather than only on native.

  `test/bytes.cljc` is thorough and runs in one module, driven by
  `test/bytes.clj`. That is why the three runtimes were free to disagree about
  what an out-of-range index does: native refused it, and both ports answered
  nil, and nothing ever asked them the same question. `conform` diffs what it
  is given, and it was not given this.

  So what is here is not a second copy of that file. It is the handful of
  answers where the three could drift apart without anything noticing."
  (:require [flint.bytes :as b]
            [flint.check :refer [expect]]))

(defn ^:flint.check/test the-tiers-read-alike []
  (let [flat (b/of-string "hello")
        ;; Past FLAT_MAX, so this one is a tree.
        tree (reduce b/cat (b/of-string "")
                     (mapv (fn [_] (b/of-string "0123456789abcdef")) (range 200)))]
    (expect = 5 (b/size flat))
    (expect = 3200 (b/size tree))
    (expect = 104 (b/at flat 0))
    (expect = 48 (b/at tree 0))
    ;; A child boundary, which is where an off-by-one in the descent shows.
    (expect = 102 (b/at tree 15))
    (expect = 48 (b/at tree 16))
    ;; The tier must not be visible in what a value IS.
    (expect = 0 (b/depth flat))
    (expect < 0 (b/depth tree))))

;; ---------------------------------------------------- what was disagreed on

(defn ^:flint.check/test an-index-past-the-end-is-refused []
  ;; NATIVE REFUSED AND BOTH PORTS ANSWERED NIL. `test/bytes.clj` has asserted
  ;; the refusal since the type was written, but only ever against native, so
  ;; the ports drifted and stayed drifted. The refusal is the contract: `at` is
  ;; `nth`-shaped, and `nth` past the end throws in Clojure.
  (let [flat (b/of-string "hello")]
    (expect = :out-of-range
            (try (b/at flat 99) :no-throw (catch Throwable _ :out-of-range)))
    (expect = :out-of-range
            (try (b/at flat -1) :no-throw (catch Throwable _ :out-of-range)))
    ;; And in the tree tier, where the descent rather than the leaf check is
    ;; what has to refuse.
    (let [tree (reduce b/cat (b/of-string "")
                       (mapv (fn [_] (b/of-string "0123456789abcdef")) (range 200)))]
      (expect = :out-of-range
              (try (b/at tree 999999) :no-throw (catch Throwable _ :out-of-range))))))

(defn ^:flint.check/test an-empty-byte-string-is-a-value []
  (let [e (b/of-string "")]
    (expect = 0 (b/size e))
    (expect bytes? e)
    (expect = e (b/of-string ""))
    (expect = 0 (b/depth e))))

(defn ^:flint.check/test slice-bounds-agree []
  ;; A SECOND DIVERGENCE OF THE SAME FAMILY as `at` past the end. Native
  ;; refused a negative bound -- `b-slice wants two integers` -- and both ports
  ;; clamped it to zero and answered a slice. Reachable through
  ;; `flint.bytes/slice`, which is the only arity the library exposes.
  (let [b (b/of-string "hello")]
    (expect = "ell" (b/to-string (b/slice b 1 4)))
    ;; Whole-range is the identity, and shares rather than copies.
    (expect = "hello" (b/to-string (b/slice b 0 5)))
    (expect = "" (b/to-string (b/slice b 2 2)))
    ;; Past the end clamps -- both sides always agreed about THIS one.
    (expect = "llo" (b/to-string (b/slice b 2 99)))
    ;; And a negative bound is refused, which is what native has always done.
    (expect = :refused
            (try (b/slice b -1 3) :no-throw (catch Throwable _ :refused)))))
