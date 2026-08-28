# 0031 — The self-compile cliff: 64 bytes of source, and a trap with no name

> **NOT BUILT — an open fault, found 2026-08-28.** Adding one top-level `def` to a
> compiler namespace makes flint compiling flint trap with `memory access out of
> bounds`. Deterministic. Production runtime build only — the diagnostics build
> compiles the byte-identical image and passes. Nothing here is a theory: every
> line below is a run.

## What happens

`src/flint/image.cljc` gained a four-byte trailing field and a constant naming
its one bit (`doc/decisions/0025`'s `:optimize`, carried into the image so the
JVM and CLR ports can honour it). The field was fine. The constant was not.

| what `emit` writes | image bytes | gen0 module | self-compile |
| --- | ---: | ---: | --- |
| nothing (baseline) | 162 017 | 571 426 | **ok** |
| `(u32 0)` | 162 029 | 571 434 | **ok** |
| `(u32 (if perf? 1 0))` | 162 070 | 571 475 | **ok** |
| `(u32 (if perf? FLAG-PERF 0))` | 162 134 | 571 539 | **traps** |

The last two rows differ by one `def` and the var reference to it: 64 bytes of
image, 64 of module. The trap is

```text
RuntimeError: memory access out of bounds
    at wasm://wasm/…:wasm-function[190]:0x31b0e
```

with no flint-level frame, no message, and nothing naming a cause.

## What it is not

Each of these is a check that did not fire, not a guess:

* **Not the memory cap.** `host/flint-file.mjs` already raises it to 3 GB
  because of a previous cliff, and its comment describes this exact symptom --
  "past it an allocation answers NIL, the NIL reaches the tree, and the failure
  surfaces as `memory access out of bounds` with nothing pointing at the cap".
  Raising it to 3.9 GB changes nothing. `set_memory_limit` is exported by the
  production module and is being called.
* **Not a constant-index overflow.** Const and fn indices are `u16` in the
  bytecode, which is a real wall at 65 535. The compiler's own image carries
  2 458 constants. There is 96% headroom.
* **Not size alone.** A dead `defn` of the same size changes the image not at
  all -- only reachable code ships (`doc/decisions/0002`), which is why the
  obvious control experiment is worthless here and had to be discarded.
* **Not flaky.** Three consecutive runs, three traps.
* **Not the compiler that was BUILT.** A gen0 built from the passing source
  still traps when the source it is handed contains the extra `def`. The failure
  follows what is being compiled, not what is doing the compiling.

## The discriminator, and why it is the interesting part

**The diagnostics build passes.** Same source, same 162 134-byte image, a
665 874-byte module instead of 571 539, and the fixpoint completes:
`bb-compiled and flint-compiled images are IDENTICAL`, `generation 2 reproduces
itself byte for byte`.

So the fault is in what the production build does differently, and
`doc/decisions/0016` is emphatic that the difference is supposed to be
*measurement only*. Either that is not true, or the two builds differ in heap
geometry enough to move a latent fault in and out of reach. Both are worth
knowing and neither is known yet.

It also means **the bug cannot be instrumented where it happens.** Every
`stat_*` export is diagnostics-gated, so reading the heap on the build that
fails is not possible today, and turning them on makes the failure go away.
That is the same observer effect `0015` was written for, and it is the reason
this is a document rather than a fix: the next step needs an instrument that
survives being production, which is a decision and not a patch.

## Why it is not merely a curiosity

flint's whole compilation story rests on self-hosting: babashka compiles the
compiler once, and from then on flint compiles flint (`doc/decisions/0003`,
`test/selfhost.clj`). A runtime where **adding a variable to a compiler
namespace can stop that working, with no diagnosis**, is a runtime that will do
this again on a change that has nothing to do with images. It has to be fixed
before 0.0.1 rather than after.

The shape is familiar and the resemblance is a lead: `0013` spent four days on a
failure that was "sensitive to a five-byte change in its source", and the answer
there was an inconsistent build. This is not that -- `bin/check-dist` exists
because of it, and units, `dist/` and the CLI were all rebuilt from one source
between every row of the table above.

## What was done instead, and why it is not a fix

`flint.image` writes the literal `1` with a comment saying why, so the flag
lands and the tree is green. The three readers each name the constant properly
(`image.rs`'s `FLAG_PERF`, `Img.java`'s `FLAG_PERF`, `Img.cs`'s `FlagPerf`);
only the flint-side writer is bare, and only because a `def` there is currently
load-bearing in a way it has no business being.

That is a way past, on one namespace, until the next one. It is written down
here rather than in a commit message because it is a live hazard rather than a
finished piece of work.

## What must be true before this is closed

- A source change of any size to any compiler namespace cannot break the
  self-compile.
- An allocation failure raises a catchable flint error naming the cap, never a
  wasm trap. `host/flint-file.mjs`'s comment stops being true.
- The production build can be instrumented enough to attribute a trap, or the
  suite runs the self-hosting fixpoint on BOTH builds so the divergence is
  caught by the gate rather than by somebody adding a variable.
- `flint.image` can hold a `def` again, and does.
