# 0009 — Hard limits, and the loop that does not count

> **BUILT** — deterministic gas, charged natives, and a catchable memory cap.
> The determinism is what makes every cross-engine number in `0018` comparable.

The interpreter costs speed against JIT'd native code. This is a large part of
what it buys back, and it is worth treating as a headline feature rather than a
knob.

## Why it matters more than it sounds

A V8 isolate gives you a **wall-clock timeout**. That is a limit on *time*, not
on *work*, so it varies with machine load, with what else is running, and with
the weather. A gate built on one is flaky by construction: the same candidate
passes on a quiet machine and fails on a busy one.

**An instruction count is deterministic.** The same input produces the same count
on every machine, every run. That turns "did this candidate hang?" from a flaky
timeout into a reproducible fact — which matters enormously to construe, whose
entire premise is gates measuring model-written code and being believed.

Memory is the same story: the GC owns linear memory, so a cap is exact and
exceeding it is a catchable error rather than an OOM kill.

Both already exist in part — `max_heap` in the collector,
`exhaustion_is_reported_not_crashed` covering the failure, and a `step_limit` in
the VM. What follows is the two things missing.

## 1. Swap the loop; do not branch in it

Today the hot loop reads:

```rust
if self.step_limit != 0 { self.steps += 1; if self.steps > self.step_limit { … } }
```

A predictable branch, but a branch, on **every instruction**. Monomorphise it
away instead: make the loop generic over a budget policy — a const bool or a ZST
with no-op methods — and instantiate twice. `NoBudget`'s increment compiles to
nothing and the check disappears entirely; `Counting` keeps today's behaviour.

Pick the instantiation once at entry, not per instruction.

### The scheduler interaction, which is the nice part

The step budget is already doing double duty as the scheduler's **time slice** —
running out means "your turn is over" rather than "you have hung". So:

- **Threads present** → counting is required anyway for preemption, and a gas
  limit therefore costs *nothing extra*. Gas is free exactly where concurrency is.
- **Single-threaded, no limit** → the free loop, with no counter at all.

Which means the fast path stays fast and the feature is free wherever it is most
likely to be wanted.

## 2. The hole: a native call is one instruction and arbitrary work

**This is the part that would quietly not work.** Instruction counting bounds
*bytecode*, and a call into a native builtin is one instruction regardless of what
it does. So:

- one `re-find` against a pathological pattern is **1 instruction** and can run
  for a very long time;
- one `sort` of a huge vector is 1 instruction;
- so is a large `merge`, a big `into`, a deep `=`.

A budget that does not bound these does not bound the thing construe most needs
bounded. Construe has an entire gate for catastrophic backtracking
(`packages/gates/src/redos.js`) precisely because this is a live hazard in
model-written code — and a gas limit that a single regex escapes is worse than no
limit, because somebody will trust it.

**So natives charge for their work.** Every builtin whose cost is not O(1) adds
to the same counter in proportion to what it did: elements touched, comparisons
made, backtracking steps taken. The regex engine in particular must charge per
step, which also makes the ReDoS gate exact rather than heuristic.

Where a native cannot cheaply account for itself, say so in the README rather
than leaving the budget with a silent hole in it.

## Details worth settling

- **Charge allocation, not collection.** Bytes allocated is deterministic; work
  done by the collector depends on heap size and on when it ran, and charging it
  would make gas depend on the memory limit.
- **Global or per-thread?** Global bounds the run, which is what a caller wants.
  Per-thread accounting is worth keeping for diagnostics — *which thread spent
  it* is the first question when a budget is exceeded.
- **Exceeding either limit is a catchable error** carrying what was spent and
  what the limit was, not a trap. A host needs to distinguish "the program is
  wrong" from "the budget was too small".
- **Hitting the memory cap collects first, then fails.** Failing while garbage is
  reclaimable would make the cap depend on GC timing.

## What must be true at the end

- The free loop and the counted loop are **separate instantiations**, and a
  benchmark reports the difference. If it is not measurable, say so — that is a
  useful finding too.
- A tight loop under a step limit stops at the limit, deterministically: the same
  program reports **the same count every run, on any machine**.
- A single pathological `re-find` is **stopped by the gas limit**, with a test
  using a known catastrophic pattern.
- A large `sort`/`merge`/`into` charges proportionally, not 1.
- Exceeding memory raises a catchable error after a collection, and the error
  says what was spent against what limit.
- The README presents limits as a feature, with the honest comparison: a wall
  clock bounds time and varies by machine; this bounds work and does not.
