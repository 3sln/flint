# flint on the JVM

An image loader, an interpreter over all 46 opcodes, and the builtins. Plain
`javac` — no Maven, no Gradle — so the suite can build it wherever a JDK is.

```sh
bin/conform-hosts          # compile once, run on both runtimes, diff
FLINT_JDK=/path/to/jdk bin/conform-hosts
```

## The collector came back

This section used to say the collector was gone, and that a flint value was a
Java object — `nil` a `null`, an integer a `Long`, a vector a `List`. That was
true, it was `jvm-runtime`'s design, and it is no longer how this port works.

Since `9f6f70e` (2026-08-29) a flint value here is a NaN-boxed `long` over a
flat `Space`, and `Gc.java` is `runtime/src/gc.rs` ported verbatim:
generational, copying in the nursery, mark-and-sweep in the old space, with a
write barrier and a remembered set. Values move, and roots are roots.

WHY IT CAME BACK: the two runtimes have to make the SAME decisions at the same
points, not merely both work. `conform-hosts` diffs their collection counts and
fails if they part — 105 minor collections, 15 major, 8,000,000 bytes
reclaimed, the same numbers on both. Two collectors that happened to agree
would not produce that, and leaning on the host's meant there was nothing to
compare.

It also found a bug that only a real collector can have: `scanObject` dropped
the re-enrolment of an old object still pointing young, so the edge was
forgotten, the young object died while referenced, and its address was reused
— a list that looped 9,907 objects into a 200,000-element walk.

## The bytecode is the portable artifact

A new host needs this loader, a loop over the opcodes, and the builtins — not a
compiler. The reader, the analyzer, the macro expander and the whole core
library are already portable and compile to exactly the image this reads.

Builtins are resolved **by name**, because an image's native slots belong to
whichever module it was linked against.

## What is here

| | |
| --- | --- |
| image format | complete |
| opcodes | all 46 |
| builtins | all 168, the 2 mandatory included (`bin/check-builtins`, 2026-09-12) |
| conformance | `bin/conform-hosts`, every case agreeing |
| threads | several threads on one program |
| AOT | `java.lang.classfile`, on first call, 1.67x |

## The emitter is Java, and compiles on first call

Two things worth knowing before relying on it:

**There is no cross-compilation.** Unlike the wasm backend -- which is
`src/flint/aot.cljc`, written in flint -- this emitter is Java, so producing
JVM bytecode needs a JVM present. Deliberate; see `DECISIONS.md#jvm-runtime`.

**It is not ahead of deployment.** Each arity is compiled the first time it is
called, into a hidden class in memory. Nothing is written out. The wasm
backend splices compiled arities into the artifact at build time; this does
not.

Missing builtins are **absent, not stubbed**. Reaching one names it. A stub
returning `nil` would let a program get a wrong answer quietly here and the
right one elsewhere, which is the drift the conformance harness exists to
catch.

## Self-hosting: close, not there

The compiler is the largest flint program there is, so it reaches builtins a
small program never does. Running it here went from 89 missing builtins to
**3** -- `flint/re-compile`, `flint/re-run`, `flint/re-find-all`, which need
flint's Pike VM ported.

Getting that far found five real bugs, none of which any conformance case had
touched:

* **Builtins threw a bare string.** flint's runtime throws a structured error
  with a kind, a message and data (`runtime/src/err.rs`), so `ex-message` on
  one of mine answered nil -- and the compiler, which catches an error and
  re-throws it with the form it happened in, produced `"\n  in
  flint.main/-main"`: a location with no message in front of it. A real failure
  reported as nothing.
* **Sets, maps and vectors were not callable.** `(#{\space \tab} c)` is how
  the reader tests whitespace, so the compiler could not read its own source.
* **`transient` refused a set.**
* **`deref` refused a volatile**, which the Rust runtime accepts alongside an
  atom.
* **A vector holding `nil` could not be made persistent.** `List.copyOf`
  rejects nulls and the guard around it returned the same list on both
  branches, so it guarded nothing.

Where it stops now is a genuine compile error from the compiler itself --
`unable to resolve symbol: string?` -- whose cause is not yet found. The
error's `:ns` says `flint.main`, which is not in the program being compiled, so
the compiler's error CONTEXT is stale as well. Both are open.

## Known divergence

Map iteration order. flint's maps are a CHAMP and iterate in hash order; this
port uses insertion order. The values are identical and only the printing
differs, which is exactly why it matters — `pr-str` of a map is how an answer
is compared. Closing it means porting flint's hash and the CHAMP's ordering,
not picking a different Java map.
