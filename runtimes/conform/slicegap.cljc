(ns slicegap
  "A PURE ARITHMETIC LOOP, at two sizes, for measuring the per-slice cost of
  being preempted and resumed.

  It allocates nothing and calls no builtin, so every step it bills is the
  interpreter's own. `DECISIONS.md#calls-are-ports` localised a ~46.5-step gap
  between native and the jvm to exactly this shape: a cost PROPORTIONAL to the
  number of 4096-step slices, which a difference-of-two-workloads comparison
  cancels only if both workloads cross the same number of slice boundaries.

  Two sizes and a 2x ratio on purpose: a per-slice charge shows up as a gap
  that doubles with the work, where a fixed startup cost does not."
  (:require [flint.core]))

(defn- loopsum [n]
  (loop [i 0 acc 0]
    (if (< i n) (recur (inc i) (+ acc i)) acc)))

(defn small [_] (str (loopsum 20000)))
(defn big [_] (str (loopsum 40000)))
