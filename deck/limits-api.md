# Resource limits

## Resource limits

An interpreter loses to a JIT on speed; see [Benchmarks](#benchmarks), where it
loses by 1.4× on parsing and by 275× on regex. Deterministic resource limits are
a large part of what it buys back, and they are a **feature rather than a knob**.

The argument is the same one as the sandbox argument, and it is about being a
*better boundary* rather than a faster one:

> An isolate gives you a **wall-clock timeout**. That bounds *time*, and time
> varies with machine load, with what else is on the box, with whether the JIT
> tiered up. A gate built on one is flaky by construction — the same program
> passes on a quiet machine and fails on a busy one.
>
> An instruction count bounds **work**, and work is the same on every machine.
> That turns "did this candidate hang?" from a flaky timeout into a reproducible
> fact.

For a system whose whole premise is gates measuring model-written code and being
believed, the difference is the product.

```js
inst.exports.set_step_limit(hi, lo);      // gas, in bytecode instructions
inst.exports.set_memory_limit(bytes);     // heap ceiling
```

### The same program costs the same every time

`test/limits.clj` runs one program five times and reads the counter back:

    16693 instructions, five times over

Runaway loops stop **at** the limit rather than near it — a 500 000 budget
reports `spent 500000 of 500000`. Exceeding either limit is a *catchable error*
carrying `{:spent :limit :thread}` as ex-data, not a trap: a host that wants to
report which candidate ran away can.

Catching it does not defeat it. A program that swallows its own budget error and
starts another runaway loop is stopped by an error that escapes every handler,
because a budget a candidate can catch its way out of is not a budget.

### The hole that would have quietly not worked

Instruction counting bounds **bytecode**, and a native call is one instruction
however much work it does. Left there, `(= big-vector-a big-vector-b)` would have
cost 1 against the budget while touching a million elements — a gas limit with a
hole exactly where the expensive operations live.

So every builtin whose cost is not O(1) charges the same counter in proportion to
what it touched. Doubling the work doubles the charge:

| one native call | 10 000 elements | 20 000 | ratio |
|---|---:|---:|---:|
| `=` over two big vectors | 550 730 | 1 100 730 | **2.00×** |
| `hash` of a big vector | 280 671 | 560 671 | **2.00×** |
| `seq` over a big map | 550 702 | 1 100 702 | **2.00×** |
| `str-join` over many pieces | 310 673 | 620 673 | **2.00×** |

**Where a native still cannot cheaply account for itself**, so you know the
shape of what is left: map and set lookup (`get`, `contains?`) is O(log₃₂ n) and
is deliberately *not* charged on the hot path, because the accounting would cost
more than the operation. The bound is therefore exact for linear work and
optimistic by a logarithmic factor for point lookups in a loop. Sorting,
merging and `into` are cljc, so they are bytecode and counted instruction by
instruction with no native shortcut at all.

### Catastrophic backtracking is bounded exactly

`#"(a+)+$"` against a failing subject is the textbook ReDoS pattern. flint's
regex engine is written in cljc, so its backtracking **is bytecode** and every
step was already on the counter. The gate is exact rather than heuristic, and it
needed no special case:

    a known catastrophic regex is stopped by the gas limit

### Memory: collect first, then fail

Hitting the memory cap runs a collection before giving up, so a program is never
killed for garbage it was about to drop. Only if the heap is still full does it
raise — again catchable, naming what was held against what was allowed.

This closed a genuine silent-wrong-answer bug: a failed allocation used to return
`nil` and the program **carried on**. Under an 8 MB cap a run reported
`:total 3932160` where the answer was `13107200`. A wrong answer is worse than an
error, and allocation now raises.

### What it costs, both halves

The loop is *swapped* rather than branched in: the budget policy is a zero-sized
type whose `tick` either counts or compiles away entirely, and `run` picks the
instantiation once at entry rather than testing per instruction. The nice part
is the interaction with the scheduler — the step budget *is* the green-thread
time slice, so counting is already required wherever concurrency is. Gas is free
exactly where threads exist, and the uncounted loop is for single-threaded
unlimited runs.

| | |
|---|---|
| counting, on an unlimited single-threaded run | **6.7–7.6% slower** |
| one interpreter instantiation | 184 936 bytes |
| two instantiations | **201 271 bytes** |
| what the free loop costs in module size | **+16 335 bytes** (~9%) |

Both halves are reported because only one of them is flattering. Buying back
7% of interpreter speed with 16 KB of module is a real trade, not a free win,
and on a size-constrained target it may be the wrong one.
