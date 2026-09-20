(ns aot-try
  "TWO `try` REGIONS IN ONE FUNCTION, under a compiled arity.

  A FIXED DEFECT, kept as the guard for it. This program is four lines because
  it is the reduction of a fault that first showed in `runtimes/conform/tables.cljc`
  and later in `green.cljc`, both of which were excluded from the AOT row while
  it stood. It is in `conform_progs` and in that row FIRST, so if it ever breaks
  again the four-line program says so before the two large ones do.

  ## What it was

  `aotCallAt` decides, after a nested compiled call returns, whether compiled
  code may carry on where it left off. Carrying on is only safe if nothing moved
  the frame out from under it, and one of the things that can is an unwind to a
  handler in THIS VERY FRAME -- which truncates the frame stack back to exactly
  the depth the call started at, so a frame-count test cannot see it. The guard
  therefore also asks whether an unwind happened at all:

      frames.size() == before && unwinds == unwindsBefore && !aotUnwoundOut

  Both ports carried the field and carried that read. NEITHER INCREMENTED IT.
  `unwinds` was zero for the life of the process, the second conjunct was the
  constant `true`, and the guard degenerated into precisely the frame-count test
  its own comment warns against. Compiled code was told to carry on past the
  handler with an unwound stack, which surfaced as `POP_HANDLER` on an empty
  list or as `value is not a function` once the value stack had come apart.

  One `try` never reaches it: it takes a second throw, caught in the same frame
  that made the call, for the two readings of the guard to differ.

  ## How it was found, after a wrong turn

  The note that stood here said the fault was in how an opcode is EMITTED, on
  the strength of the maximal-chunking bisection -- a failure that survives
  `chunkAll` is not a missing boundary, so it must be the emitter. It survived,
  and it was neither. The dichotomy had a third case nobody had named: the
  CROSSING PROTOCOL, which runs whatever is emitted. Un-inlining every opcode in
  turn changed nothing, and un-inlining ALL of them -- so that every single
  instruction bails to the interpreter and no opcode is emitted at all -- still
  reproduced it. That is the measurement that named the right half.

  A bisection handle answers only the question it was built to ask. This one
  asks which of two halves, and it is honest about that; believing it had asked
  which of ALL halves cost the search its first direction."
  (:require [flint.check :refer [expect]]))

(defn- t [f] (try (str (f)) (catch Exception e (str "THREW " (ex-message e)))))

(defn main [_]
  (pr-str {:one (t (fn [] (nth [1 2] 99)))
           :two (t (fn [] (/ 1 0)))}))
