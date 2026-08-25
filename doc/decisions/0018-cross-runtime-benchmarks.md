# 0018 — Benchmark across wasm runtimes, because every number so far is V8

> **BUILT**, for five engines: node, deno, bun, workerd and wasmtime, plus wasm3
> as the small-embedder case. `bin/bench-xruntime` and `bin/bench-image`.
> SpiderMonkey and Chicory are still missing; Chicory needs a JVM.
>
> **One of this document's central predictions is wrong, and the measurement is
> below.** It says AOT regions matter most on an engine with no tier-up. They
> matter *least* there.

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

## Measured

`bin/bench-xruntime`. A flint module imports nothing and exports its entry as
`main`, so wasmtime and wasm3 run it from the command line with **no host code
at all** — which is what lets every engine run identical bytes. The iteration
count is baked into a family of modules because a CLI `--invoke` cannot drive
`arg_alloc`/`arg_push`; fitting a line over the family separates the work from
the fixed cost, and `0009` is what makes dividing by the instruction count
legitimate: 33,233 per iteration, verified linear to the instruction.

| engine | per iter | ns/instr | vs V8 | fixed cost | R² | AOT |
|---|---:|---:|---:|---:|---:|---:|
| node (V8) | 0.364 ms | 11.0 | 1.00× | 32.13 ms | 0.9980 | 1.41× |
| bun (JavaScriptCore) | 0.358 ms | 10.8 | 0.98× | 27.66 ms | 0.9583 | 1.14× |
| deno (V8) | 0.512 ms | 15.4 | 1.40× | 24.56 ms | 0.9961 | 1.52× |
| wasmtime (Cranelift) | 0.325 ms | 9.8 | 0.89× | 6.41 ms | 0.9999 | 1.15× |
| wasm3 (interpreter) | 5.665 ms | 170.5 | 15.55× | 4.27 ms | 0.9999 | 1.16× |

**The spread between JIT engines is small — about 1.4× at worst — and the
interpreter is 15.6× slower.** That is the deployment fact: anywhere with a
JIT, flint costs about the same; on a small embedder it costs fifteen times
more. wasm3's fixed cost is the lowest of all, so its trade is fast to start and
slow to run.

bun's R² of 0.958 is below the bar and its row is left saying so. R² is reported
because the first version of this table fitted counts 0..8 against a 20–35 ms
process start and confidently reported node at 5.1 ns/instruction and deno at
26.9 — both V8.

### The AOT prediction was backwards

This document argued that `0013` looked marginal on V8 because TurboFan
optimises the dispatch loop hard, and that *"on a baseline-only or interpreting
engine, dispatch could dominate completely and AOT regions become the difference
between usable and not."*

Measured, with gas identical between the two builds — 6,648,019 instructions
either way, so it is the same work and not less of it:

| | AOT speedup |
|---|---:|
| deno (V8) | 1.52× |
| node (V8) | 1.41× |
| wasm3 (interpreter) | 1.16× |
| wasmtime (Cranelift) | 1.15× |

**The JIT engines gain most and the interpreter gains least.** The reason is
visible once stated: on wasm3 the wasm blocks AOT emits are themselves
interpreted, so the trade is interpreted-bytecode-dispatch for
interpreted-wasm-execution and there is little in it. On V8 those blocks become
native code and the dispatch really does disappear.

So AOT is a JIT-engine optimisation, not an interpreter rescue, and the
argument that it might rescue small embedders does not survive. Confirmed
directly at three scales on wasm3 (1.10×, 1.10×, 1.17× at 100, 200 and 400
iterations, runs of seconds) rather than left to the fit.

**And the benefit is larger cold than warm.** In-process and fully warm, AOT is
1.22× on node (8.68 → 7.10 ns/instruction) and 1.27× on bun (8.04 → 6.31). The
1.41–1.52× above includes tier-up. That cuts in AOT's favour for the deployment
that matters: a Worker request is short, and short is where AOT helps most.

## The image-per-call shape, which is what is actually deployed

`bin/bench-image`. A resident loader instantiated once per isolate, a bytecode
image loaded per call, nothing shared between runs.

| engine | compile | instantiate | load+run | +25 iters | ns/instr | heap |
|---|---:|---:|---:|---:|---:|---:|
| node (V8) | 1.26 ms | 0.17 ms | 96.2 µs | 7.29 ms | 8.7 | 6.31 MB |
| bun (JSC) | 2.35 ms | 0.40 ms | 74.6 µs | 6.79 ms | 8.1 | 6.31 MB |
| deno (V8) | 0.00 ms | 2.00 ms | 106.0 µs | 8.81 ms | 10.5 | 6.31 MB |
| workerd (V8 isolate) | — | 0.00 ms | 102.0 µs | 8.43 ms | 10.0 | 6.31 MB |

**workerd loads an image and runs it in 102 µs.** `load+run` is an image that
does no work, so `ns/instr` subtracts it rather than blaming image-load overhead
on the interpreter.

**Peak memory is flat**, which is the constraint under a 128 MiB isolate
ceiling: 2,000 image loads plus 300 loaded calls — 249 million flint
instructions — leave the wasm heap exactly where it started on every engine. The
report asserts this rather than assuming it.

The resident loader also gets the warm number (8.7 ns on node against 11.0 from
a fresh process), because it stays tiered up across calls.

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
