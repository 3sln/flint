(ns wiregas
  "Writing to a wire costs the same gas on every runtime.

  THE TWO RUNTIMES FACTOR THE WRITER DIFFERENTLY, which is why this exists.
  Native writes a tag and its payload in ONE call, `wire_piece`, checking that
  the writer is live once. Both ports compose `put(tag)` with a payload writer
  -- `u32`, `u64`, `text`, `raw` -- and each of those checks liveness for
  itself. So a port does two `isWriter`/`WR_LIVE` slot reads and two mark/push
  cycles where native does one, and the obvious worry is that one of them
  bills more.

  MEASURED 2026-09-23, and it does not:

      wasm   small 18 388   large 25 690   difference 7 302
      jvm    small 18 300   large 25 602   difference 7 302

  Identical to the instruction over 300 extra values, with the same constant
  88 between the totals that every other gas fixture here removes by
  comparing a DIFFERENCE rather than a total. The extra liveness check costs
  nothing observable, so the choice between the two factorings is about code
  shape alone -- `DECISIONS.md` records it as the one thing still blocking
  `Wire` from being generated.

  NOTHING MEASURED THIS BEFORE. `runtimes/conform/wire.cljc` checks the
  writer's BEHAVIOUR -- what it refuses, and that it refuses alike everywhere
  -- and carries no workload pair, so the gas rows had never seen a
  wire-heavy program.

  A VECTOR OF `n` is what makes the loop legal: a bare writer is COMPLETE
  after one value, which is the refusal `wire.cljc` pins. The entry points are
  five characters each, because on wasm the gas depends on how long the entry
  point's name is -- see `conjgas`, where that cost two steps and looked like
  a gap that moved."
  (:require [flint.thread :as th]))

(defn- churn [n]
  (loop [i 0 w (flint.rt/wire-vec (flint.rt/wire-writer) n)]
    (if (< i n)
      (recur (inc i) (flint.rt/wire-int w i))
      i)))

(defn small [_] (str (churn 100)))
(defn large [_] (str (churn 400)))
