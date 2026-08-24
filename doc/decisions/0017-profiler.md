# 0017 — A profiler: named blocks, and CPU told apart from waiting

> **NOT BUILT, AND NOT NEXT.** Recorded now because two decisions it depends on
> are being built today, and one measurement it would give away for free is
> already owed elsewhere.

Opt-in, development only, under `0016`'s rule: absent from a production module
rather than disabled in one.

## The property that makes this worth more here than elsewhere

**Instruction counts are deterministic.** `0009` made the same program report the
same count on every machine, every run. So a profile measured in INSTRUCTIONS is
reproducible, and two profiles can be diffed with the difference meaning
something. A wall-clock profiler gives you noise plus a signal and leaves you
guessing which is which.

For construe that is not a nicety. "Is this evolved candidate cheaper than the
incumbent" is exactly the question its gates ask, and an exact instruction count
turns it from a flaky benchmark into an assertion.

**And flint can separate CPU from waiting EXACTLY**, where a sampling profiler
has to infer it. The scheduler already knows when a thread is parked and what it
is parked on — a port receive, a full send, an `open`. That is not a heuristic,
it is the state the VM is already keeping.

## Two clocks, reported separately and never summed

- **Instructions** — CPU, deterministic, diffable.
- **Wall time** — waiting: a host port, a document fetch, anything outside. Real,
  necessary, and inherently non-deterministic.

Never add them into one number. A block that takes 3 000 instructions and waits
200 ms has two facts about it, and a single "cost" figure destroys the one that
tells you what to fix.

**Bytes allocated belongs beside them.** The GC is the other large cost in this
runtime, and allocation is the thing a Clojure programmer controls most directly.

## Blocks: named, nested, per thread

Boundary instructions open and close a named block. Two things decide whether
this is usable:

**The block stack is PER GREEN THREAD**, like dynamic vars (`0005`). A block open
in thread A while thread B runs must not collect B's instructions. Getting this
wrong produces a profile that looks plausible and attributes work to the wrong
place, which is worse than no profile.

**A block's parent is whatever was open when it opened**, so the tree falls out
of nesting without anything having to reconstruct a call graph. Attach the
frame's function identity as well, so a block can be located in source rather
than only in the tree.

**Parking inside a block is the interesting case, and it works by construction**:
instructions stop accruing and wall time keeps running. The CPU-versus-waiting
split is not a feature to build, it is what the two clocks already say.

## Where it fits with what exists

- **`0016`** governs it: a cargo feature, monomorphised out, with the symbol
  assertion proving a production module has none of it.
- **`0015`** carries it: a profile is VM state, so a snapshot should include it
  and the inspector should read it. Same object model, built once.
- **`0013` gets its measurement for free.** The AOT-regions decision is waiting
  on a histogram of contiguous non-parking region lengths weighted by execution
  count. That is per-instruction instrumentation with a grouping — which is what
  this is. Build the profiler and 0013 becomes a query rather than a project.
- **A profile stream is a port.** Ports already carry data out; there is no need
  for a second channel or a new host entry point.

## The first customer is the compiler

flint self-hosts, so the profiler can profile flint compiling flint — a real,
large, allocation-heavy program that is already in the repository and already
has a deterministic instruction count from the fixpoint test. It needs no
fixture, and the answer is immediately actionable: the self-hosted compiler at
3.7 s does not fit a Worker's CPU budget (`0007`), and nobody yet knows where
that time goes.

## What must be true if this is built

- A production module contains no profiler, asserted by symbol.
- The same program profiled twice reports **identical instruction counts** per
  block; wall time may differ and is reported separately.
- A block that parks shows instructions stopping and wall time continuing.
- Concurrent threads do not contaminate each other's blocks — tested with two
  threads in differently-named blocks interleaving.
- Profiling the self-hosted compile produces a tree that accounts for
  approximately all of the instructions, with the shortfall stated rather than
  hidden.
