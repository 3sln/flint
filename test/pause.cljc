(ns pause
  "The collector collects LITTLE AND OFTEN, and that is why the latency tail is
  tight (`doc/decisions/0018`).

  `bin/bench-image` measures the consequence -- p99/p50 of 1.06 under
  allocation-heavy load, tighter than the 1.21 at idle -- but a benchmark run is
  not a guard. A change that made the nursery collect rarely and at length would
  leave every existing test green and quietly move the tail, and the number
  would only be noticed the next time somebody ran the benchmark and thought to
  compare.

  So the MECHANISM is asserted here rather than the wall-clock, because the
  mechanism is deterministic and the wall-clock is not. Two quantities say
  `little and often`:

    often   how much is allocated between collections -- roughly one nursery.
    little  the largest number of bytes any single collection copies.

  Both are exactly reproducible, so a regression in either is a failing test
  rather than a slower afternoon."
  (:require [clojure.string :as str]))

(defn probe
  "Allocate `n` times, keeping every `keep-every`-th value alive, and watch the
  collector while it happens.

  `gc-stats` is sampled after EVERY iteration so that at most one collection
  falls between samples and each jump in `bytes-copied` is one collection's
  copy. Where two do fall in one sample the jump is their sum, which makes
  `max-single` an upper bound -- the safe direction for a bound -- and
  `samples` versus `minor` is what says whether that happened."
  [keep-every n]
  (let [s0 (flint.rt/gc-stats)]
    (loop [i 0 prev (:bytes-copied s0) mx 0 samples 0 acc []]
      (if (>= i n)
        (let [s (flint.rt/gc-stats)]
          {:minor (- (:minor s) (:minor s0))
           :copied (- (:bytes-copied s) (:bytes-copied s0))
           :max-single mx
           :samples samples
           :alloc (- (:bytes-allocated s) (:bytes-allocated s0))
           :live (count acc)
           :keep-every keep-every})
        (let [acc (if (zero? (mod i keep-every)) (conj acc [i (str "k" i)]) acc)
              c (:bytes-copied (flint.rt/gc-stats))
              d (- c prev)]
          (recur (inc i) c (max mx d) (if (pos? d) (inc samples) samples) acc))))))

(defn main [_]
  ;; Four live-set sizes over the same allocation rate: 469 values kept up to
  ;; 30000. If pause size were unbounded rather than bounded by the live young
  ;; set, this is the axis it would show up on.
  (pr-str (mapv (fn [k] (probe k 30000)) [64 16 4 1])))
