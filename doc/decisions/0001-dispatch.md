# 0001 — Interpreter vs AOT, and stack vs register

Raised while the runtime was at `mem`/`value`/`obj`, before layer 9 exists. The
plan settles "stack machine" in one line; that is probably right and it is not
yet ARGUED. Both forks below want a recorded decision in the README.

## Fork 1: interpreter vs compiling straight to wasm

The plan assumes an interpreter with the program spliced in as data. The
alternative is emitting real wasm functions per Clojure fn, which is far faster —
the host JIT does the work and there is no dispatch at all.

**It collides with the rooting constraint.** wasm locals are not scannable, so
under AOT live references sit where the collector cannot see them, and you need a
shadow-stack spill around every allocation site — handing back much of the speed.
The interpreter keeps every live value in linear memory, which is precisely what
makes the "value stack IS the root set" design work. This is the gap WasmGC
exists to close, and it is a real reason to choose the interpreter TODAY.

Write that down rather than leaving it implicit. Somebody will ask.

## Fork 2: stack vs register

**For registers.** Shi, Casey, Ertl & Gregg, *Virtual Machine Showdown: Stack
Versus Registers* (VEE 2005 / TACO 2008): roughly 47% fewer dispatched
instructions and ~32% faster, at ~25% larger bytecode. Java-derived, so
indicative rather than predictive here.

**And the argument is STRONGER in wasm than natively.** No computed goto, no
tail-call threading — a `br_table` dispatch loop is what you get, and the branch
is unpredictable. The techniques that narrow the gap on native hardware are
unavailable, so "fewer dispatches" buys more here than the literature suggests.

**For the stack.**
- Codegen is a post-order walk. Register allocation is real work on the
  BOOTSTRAP CRITICAL PATH — the compiler must compile itself before anything
  works at all.
- **Stale registers retain references.** A stack machine drops a reference when
  it pops; a register slot holds whatever was last written until overwritten, so
  dead slots keep objects alive unless the compiler emits liveness or clears
  them. Floating garbage, invisible, and miserable to diagnose. This interacts
  directly with the GC design.

**Neutral:** rooting scannability. Both are contiguous slots in linear memory.

## What is probably true, and what to do

For pure data manipulation — the stated target — dispatch is likely SECOND ORDER.
HAMT traversal, hashing, allocation and GC should dominate. Where dispatch does
bite is the self-hosting compiler itself: a torrent of small symbol and keyword
work.

So:

1. Keep the stack machine to get bootstrapped. Simplicity on the critical path
   is worth more than a dispatch win that may not show up.
2. **Design the bytecode so superinstructions can be fused later without a format
   break.** Fusing hot pairs recovers a good share of the register benefit for a
   fraction of the complexity.
3. **Measure it rather than assuming either way.** A benchmark that isolates
   dispatch cost from data-structure cost — the same workload with and without a
   fused hot path, or an instruction-count-per-operation figure — turns this from
   an opinion into a number.
4. Record both decisions and the measurement in the README.

If the measurement says dispatch dominates and superinstructions do not close it,
that is a real finding and worth acting on. Say so either way.
