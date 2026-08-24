# 0019 — A thread pool: two models, and only one of them is close

> **NOT BUILT.** An honest assessment of distance, because the question splits
> into two projects that share a name and almost nothing else.

The ask: green threads picked up by any worker in a pool, atoms genuinely atomic,
volatiles real.

## The constraints the user has since given

Three, and they narrow the design usefully:

**Opt-in, and free when declined.** A program that does not want a thread pool
must pay nothing for it — no atomic instructions on the single-threaded path, no
extra branch in the interpreter loop. This is the same discipline `0009` applies
to gas and `0015` to diagnostics, and it is the reason those two are already
monomorphised rather than flagged at runtime: the build selects the loop.
Applied here it means the shared-heap machinery is a *build configuration*, not a
runtime mode, and single-threaded flint keeps the instruction loop it has today.

**Gas is allocated in blocks, per thread.** A shared gas counter would be
contended on every instruction — the worst possible place for an atomic. Instead
each thread draws a block from the global budget and spends it locally, drawing
again when it runs out. Contention drops by the block size, and the local spend
stays a plain non-atomic decrement, so the hot path is unchanged from today's.
It also keeps a single thread's count deterministic even when the interleaving is
not — which preserves more of `0009` than the determinism section below assumed.

**Snapshots are an app-wide halt.** Every thread runs to a safe point, the world
stops, and the snapshot is taken of the whole system. This is the only coherent
answer — a snapshot of one thread while others mutate shared state is not a state
the program was ever in — and it means `0015` needs a barrier rather than a
redesign. `0009`'s slice check is again the natural safe point, so the halt
protocol and the collection safepoint of Model A are the same mechanism serving
two callers.

These three fit together: block-allocated gas and a stop-the-world snapshot both
assume threads reach safe points at a bounded interval, which the slice check
already guarantees.

## First, the deployment constraint, because it may decide it

wasm multi-threading needs **shared linear memory** and the atomics proposal.
That means `SharedArrayBuffer`, which on the web requires cross-origin isolation
(COOP/COEP) — a real constraint on where flint can be embedded.

**Check what construe's actual target supports before costing anything else.**
If workerd does not offer shared memory and wasm atomics, then the whole shared
model is unavailable on the deployment flint exists to serve, and the question
answers itself for that host regardless of what we build.

Standalone runtimes (wasmtime, node with workers) do support it, so this is
per-host rather than universal — which is itself an argument for not making the
core depend on it.

## Model A — shared heap. What was asked for, and it is a rewrite

Atoms that are atomic *across workers* and volatiles with real memory ordering
require one heap that every worker sees. That makes the collector the project:

- **Allocation** becomes contended. Per-worker allocation buffers rather than one
  bump pointer, and a nursery per worker or a partitioned one.
- **Collection needs every worker at a safepoint**, because the nursery moves
  objects. One worker still running while another evacuates is corruption of
  precisely the kind this repository has spent a fortnight chasing.
- **Roots become N value stacks and N shadow stacks**, which is a change of
  degree rather than kind — the design already scans them precisely, so this is
  the least frightening part.
- **CAS on a moving pointer** is the sharp edge: a compare-and-swap on a heap
  slot races the collector relocating what the pointer names. The standard answer
  is that a safepoint cannot occur inside the CAS, which constrains where
  safepoints go.

**One thing is already in place**: the interpreter has a natural safepoint. The
slice check from `0009` already polls at a bounded interval, which is exactly
where a "stop for collection" test belongs — and `0009` already monomorphises it
away when unwanted, so the machinery for making it conditional exists.

**Estimate: the collector is a rewrite**, and it is the hardest component in the
project. Everything else is refactoring.

## Model B — a heap per worker, ports between them. Much closer

Green threads migrate between workers; each worker owns its own nursery and old
space; **nothing is shared, and ports carry data across.** This is the Erlang
model, and flint is already most of the way there:

- **`0006` already says ports transfer by value**, with by-reference as an
  optimisation *within one runtime*. Across two heaps the optimisation simply
  does not apply, and the semantics are unchanged.
- **A green thread is data**, so migrating one is copying a VM state between
  heaps rather than moving a native stack.
- **The collector needs no changes at all** — each worker collects its own heap
  independently, with no safepoint, no shared roots, no contention.
- **Immutability means most values could still be shared read-only** if a later
  optimisation wanted it, though it is not needed to start.

**What it does NOT give you is what you asked for**: an atom shared across
workers cannot be atomic if the workers do not share a heap. Atoms stay
per-worker.

Whether that is a loss depends on the intent. If the goal is throughput —
several documents extracted at once, several rounds graded in parallel — Model B
delivers it and the isolation is a feature. If the goal is genuinely shared
mutable state across parallel workers, only Model A does that.

## The cost nobody has priced: determinism

`0005` insisted on a deterministic scheduler and `0009` on a deterministic
instruction count, and **construe's gates depend on the second**. Real
parallelism spends both:

- interleaving is non-deterministic, so any program touching shared mutable state
  stops being reproducible;
- a snapshot plus the host event log stops being a complete replay (`0015`);
- "is this candidate cheaper" stops being an exact question.

**Model B keeps most of it.** A single green thread's instruction count stays
deterministic because nothing else touches its heap, and a program whose threads
communicate only through ports has a reproducible answer even if the *timing*
varies. Model A does not.

That asymmetry is worth more than it first looks: determinism is one of the few
properties flint has that a JIT-based runtime does not.

## So: how far?

- **Model B**: reachable. The green threads, the ports and the value semantics
  already exist and were designed compatibly. The work is a worker pool, thread
  migration, and making the runtime instantiable N times — none of it touching
  the collector.
- **Model A**: a rewrite of the collector plus a safepoint protocol, gated on a
  host capability the primary deployment may not have, and it spends the
  determinism that `0009` and `0015` are built on.

**Recommendation: after AOT, ropes and regex, benchmarks and the profiler** —
the user's stated ordering, with the pool last and expected to be taken up. When
it is taken up, do Model B first — it is most of the throughput at a fraction of the
risk, and it is the model the existing design already implies.

## What must be true if either is built

- Host support is established by measurement, not assumption, per target.
- The determinism cost is stated in the README rather than discovered.
- If Model A: a stress test with N workers, moving collection, and a checker that
  no worker reads a pointer another worker relocated — which is the whole bug
  class this repository has just spent a fortnight on, multiplied by N.
