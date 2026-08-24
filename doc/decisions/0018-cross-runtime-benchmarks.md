# 0018 — Benchmark across wasm runtimes, because every number so far is V8

> **NOT BUILT.** Every figure in the README — 6.2 ns/instruction, 1.00 ms to
> first answer, 1.4× cherry on parse — comes from node. That is one engine, and
> the claim on the tin is "runs anywhere".

## Why the answers are engine-dependent, not just the numbers

This is not a matter of scaling everything by a constant. **The interpreter's
cost is concentrated in exactly the construct engines differ most on**: a hot
`br_table` dispatch loop. Some compile it to a jump table, some to a chain of
compares; some tier a hot loop up to an optimising compiler, some never do.

So the same measurement decides different things per engine:

- **`0013` — is eliminating dispatch worth it?** On V8, where TurboFan optimises
  the dispatch loop hard, the answer looked marginal. On a baseline-only or
  interpreting engine, dispatch could dominate completely and AOT regions become
  the difference between usable and not.
- **Cold start.** flint's largest measured win is 1.00 ms to first answer against
  a V8 isolate's 14.59 ms. On wasmtime with a precompiled `.cwasm` that is
  near-zero; on an engine that compiles eagerly it is worse. The win is real and
  its size is not portable.
- **Module size.** Where compilation time scales with size, the modularity work
  of `0003` buys latency as well as bytes. Where modules are mmapped
  precompiled, it buys only bytes.

## The determinism gives a clean cross-engine metric

Usually comparing engines means comparing wall-clock on workloads that may not be
doing identical work. Here `0009` guarantees the same program executes **the same
instruction count on every machine and every engine** — the work is provably
identical and only the time differs.

So **nanoseconds per instruction is an apples-to-apples engine comparison**, in a
way most benchmark suites cannot claim. Report it per engine; it is the single
most informative number in this exercise.

## Which engines, and what each one decides

- **V8** (node, and workerd) — construe's actual deployment target. The baseline
  we have.
- **SpiderMonkey and JSC** — the rest of the browser story, since the element
  and the demo run in a page.
- **wasmtime (Cranelift)** — the standalone server case, and the one where
  precompilation changes cold start qualitatively.
- **WAMR / wasm3** — small embedders. Likely the worst case for an interpreter
  inside an interpreter, and worth knowing rather than guessing.
- **Chicory** — and this one decides something. It is a **wasm interpreter written
  in Java**, so flint on Chicory is an interpreter running inside an interpreter.
  If that is unusably slow, then `0010`'s tier 1 (an SDK over the wasm module) is
  not the answer for the JVM, and tier 2 (porting the VM) becomes the route
  rather than a later luxury. **This benchmark decides that**, and it is cheaper
  than discovering it after writing an SDK.

## Report per engine, never averaged

An average across engines describes no deployment anybody has. A table with an
engine per row and the honest spread is the deliverable, including the ones where
flint does badly — the `0007` rule, applied again.

Where a number is qualitatively different rather than just larger — an engine
with no tier-up, an engine that mmaps precompiled code — say so in words beside
the figure. A reader deciding where to deploy needs the reason, not just the
ratio.

## What to measure on each

The construe fixtures again (`bench/construe/`), so the comparison is against
work that matters rather than a microbenchmark:

- instantiate to first answer, and warm parse latency;
- **ns per instruction**, the cross-engine metric;
- the 500-case suite, for throughput and any GC pause behaviour;
- resident and reserved memory, since engines differ on whether a 6.4 MB
  reservation is committed;
- and, once `0013` exists, the same pair with and without AOT regions — because
  that ratio is the number that varies most between engines.

## What must be true if this is built

- Every engine in the table runs the **same module bytes**. A per-engine build
  would be measuring builds, not engines.
- Each engine's version is recorded, since engine performance moves fast.
- Any engine where flint fails outright is listed as such rather than omitted.
- The README's existing numbers are labelled **V8** rather than presented as
  flint's numbers.
