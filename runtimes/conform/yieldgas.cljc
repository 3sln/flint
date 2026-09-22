(ns yieldgas
  "Yielding costs the same gas on every runtime.

  `DECISIONS.md#the-codec-is-guest-code` records this as open: the ports disarm billing
  between a yield and the next `drive` -- they set the checkpoint to the
  never-reached value, and `alloc` asks whether anything is counting -- so the
  scheduler's own allocation goes unbilled there where native's does not.
  Nothing measured how much that is worth, and no gas fixture here yields at
  all: every one of them exercises collections, strings and loops on a single
  thread, so the scheduler never runs between two instructions.

  TWO THREADS, because a yield with nobody to yield TO need not reach the
  scheduler at all. Each side yields `n` times, so the scheduler drives
  between them `n` times over.

  THE SLOPE IS THE QUESTION, not the gap. Unbilled work per yield shows up as
  a native/port difference that grows with `n`; a fixed cost of getting
  started does not. Two sizes at a 4x ratio is what tells them apart."
  (:require [flint.thread :as th]))

(defn- yield-n [n]
  (loop [i 0]
    (if (< i n)
      (do (th/yield) (recur (inc i)))
      i)))

(defn- both [n]
  (let [t (th/spawn (fn [] (yield-n n)))]
    (yield-n n)
    (+ (th/join t) n)))

(defn small [_] (str (both 10)))
(defn big [_] (str (both 40)))
