(ns flint.thread
  "Green threads.

  A green thread is a VM state: its own value stack and frame stack, held as an
  ordinary heap object. The scheduler is a loop *inside* the interpreter that
  picks a runnable thread and runs it for a fixed slice. **Nothing here suspends
  a wasm frame and nothing blocks the host** — \"blocked\" means \"not runnable
  yet\", which an interpreter can express directly. That is the leverage
  `doc/decisions/0001` was already paying for; `doc/decisions/0005` spends it.

  Scheduling is deterministic: round-robin from the thread that just ran, with a
  fixed instruction slice, no randomness and no clock. The same program with the
  same host answers in the same order gives the same result, every time.

  Nothing in this namespace is in a module that does not use it."
  (:require [flint.rt]))

(defn spawn
  "Run `f` (no arguments) as a new green thread, and return the thread.

  The new thread **inherits a snapshot of the spawner's dynamic bindings**, as
  Clojure conveys them to `future` and agents. A snapshot: rebinding in the
  spawner afterwards does not reach the child."
  [f]
  (flint.rt/spawn f))

(defn yield
  "Give the scheduler a chance to run somebody else. Threads are also preempted
  at the end of their slice, so this is a courtesy rather than a requirement."
  []
  (flint.rt/yield))

(defn self [] (flint.rt/self))
(defn thread? [x] (flint.rt/thread? x))

(defn state
  "`:new`, `:runnable`, `:parked`, `:done` or `:failed`."
  [t]
  (flint.rt/thread-state t))

(defn thread-id [t] (flint.rt/thread-id t))

(defn result
  "What the thread returned, or the value it threw. `nil` until it finishes."
  [t]
  (flint.rt/thread-result t))

(defn join
  "Wait for `t` and return its value; rethrows if it failed. Parks — it does not
  spin, because a spinning thread is always runnable and the scheduler would
  never get the chance to hand control back to the host."
  [t]
  (flint.rt/thread-join t))
