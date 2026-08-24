# The interpreter

## The interpreter

A stack machine. Opcodes `0x00–0x7F` are base instructions; `0x80–0xFF` are
reserved for fused superinstructions, so fusing hot pairs later is not a format
break.

**Why a stack machine and not registers.** The literature is clear that
registers dispatch fewer instructions (Shi, Casey, Ertl & Gregg, VEE 2005 /
TACO 2008: ~47% fewer dispatches, ~32% faster, ~25% larger bytecode), and the
case is *stronger* on wasm than natively, because there is no computed goto and
no tail-call threading — a `br_table` loop is what you get and the branch is
unpredictable. Two things outweighed it:

- **Codegen is a post-order walk.** Register allocation is real work sitting on
  the bootstrap critical path: the compiler has to compile itself before
  anything runs at all, and there was never a point where it could not.
- **Stale register slots retain references.** A stack drops a reference when it
  pops; a register slot holds whatever was last written until overwritten, so
  dead slots keep objects alive unless the compiler emits liveness or clears
  them. That is floating garbage, invisible, and miserable to diagnose — a GC
  argument, not just an engineering one.

**And the measurement, rather than the opinion.** Dispatch costs **6.2 ns per
instruction** on a tight arithmetic loop. On workloads that touch data
structures it is 7–19 ns per instruction, which is the same dispatch cost
diluted by real work. So dispatch is roughly a third of the time on
allocation-light code and much less elsewhere: superinstructions would help the
tight-loop case and little else. The number comes from timing a workload with
the step counter off and counting it with the counter on — the counter is gated
on a step limit, so it costs nothing in a normal run.

Other properties:

- **Clojure recursion runs on the VM's frame stack, not Rust's.** 3000-deep
  non-tail recursion works, and unbounded recursion throws a catchable
  `StackOverflowError` instead of smashing the wasm stack.
- **Tail calls are constant space** (tested at a million deep), so mutual
  recursion in tail position works even though Clojure's `recur` only handles
  self-recursion.
- **Exceptions unwind frames to a handler stack.** Native builtins signal
  failure by setting `rt.thrown` and returning `nil`, so there is no `Result`
  plumbing on the hot path.
- **Diagnostics exist**, because they had to: a step limit that reports a frame
  trace (turning "it hangs" into "it hangs in `read-form`"), function names in
  arity and call errors, and a bytecode disassembler (`bin/flint --disasm`).
