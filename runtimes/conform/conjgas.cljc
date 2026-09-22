(ns conjgas
  "Variadic `conj` costs the same gas on every runtime.

  Native's `conj` builtin loops over the arguments calling `Rt::conj` per
  argument, so it re-reads the collection's TYPE TAG once per element. Both
  ports dispatch on the type ONCE and then loop calling `Vec.conj` directly.
  Two shapes for one operation -- the same thing `coll.rs` records having
  fixed for `map_conj_map`, where the two algorithms had two different gas
  costs that `bin/conform-hosts` could see in a total and could not attribute.

  MEASURED 2026-09-22, and NOT YET SETTLED. Per-element cost is identical:
  1200 extra conj operations cost native 76 962 and the jvm 76 964, so the
  extra type-tag reads are not billed. But the native/port gap is 88 at 100
  iterations and 86 at 400, and a gap that MOVES is the thing every other gas
  row here asserts cannot happen.

  It is not the startup difference between two programs -- these two entry
  points differ only in a literal, and an earlier version of this fixture that
  DID differ in code shape was rewritten for exactly that reason. It is not
  per-iteration either: 300 extra iterations moved it by 2. A one-time
  threshold crossed at different points is the remaining guess, and it is a
  guess.

  SO THIS FIXTURE IS NOT WIRED INTO `bin/conform-hosts`. A row asserting the
  gap is constant would fail, and a row asserting nothing is not a row. It is
  here to reproduce the question."
  (:require [flint.thread :as th]))

(defn- churn [n]
  (loop [i 0 v []]
    (if (< i n)
      (recur (inc i) (conj v 1 2 3 4 5 6 7 8))
      (count v))))

(defn small [_] (str (churn 100)))
(defn big [_] (str (churn 400)))
