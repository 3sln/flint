# 0010 — SDKs, and other host targets

Roadmap, not current work. Written now because the answer changes what should be
frozen today.

## Three tiers, and the cheapest one covers most of the ground

**Tier 1 — an SDK over the wasm module. Cheap, and it is most of "every
language".** Almost every language now has a wasm runtime: wasmtime for
Rust/Python/Go/C, Chicory or wasmtime-java for the JVM, Wasmtime.NET for the CLR,
native `WebAssembly` in every browser and JS runtime. So a host does not need a
port of flint — it needs a **thin wrapper over the ABI in 0006**: pump, drain the
event queue, answer continuation tokens, serve ports.

That ABI is deliberately tiny — one event queue, one continue call, one drain —
and *that* is what makes an SDK a few hundred lines rather than a project. **So
the ABI is the thing to freeze and document properly**, because every future SDK
is a bet on its stability. Do this first; it is where the coverage is.

**Tier 2 — port the VM to the JVM and the CLR.** For hosts that will not embed
wasm, or that want native interop and the host collector.

The thing that makes this tractable: **the bytecode is the portable artifact.**
The reader, analyzer, macro expander and the whole cljc core library are already
portable and compile to the same image. A new host needs a VM loop over ~60
opcodes, the builtins, and the data structures — not a compiler.

And you lean on the host for the two expensive pieces: **its garbage collector**
(the generational collector here was among the hardest parts and simply
disappears) and its core libraries. A few thousand lines of runtime, not a
rebuild.

**Tier 3 — emit JVM bytecode or CLR IL directly.** This is where the speed is,
and it is worth noting *why it is easier there than here*:

> The reason flint is an interpreter at all is that **wasm locals are not
> scannable**, so compiling to wasm functions would put live references where a
> linear-memory collector cannot see them (`0001`). **The JVM and CLR do not have
> that problem** — their collectors scan their own stacks. So the architecture
> forced on wasm is not forced there, and a native backend is a legitimate option
> rather than a fight.

Still a real backend: constant pools, `StackMapTable`, call sites. Clojure itself
proves the path exists. But it is the expensive tier and it buys speed rather
than reach.

## The hard part is not the VM. It is semantic drift.

"Lean on their core libraries" is right for cost and dangerous for meaning. Every
one of these differs across hosts, and each is a program that quietly gives a
different answer:

- **Regex.** JS `RegExp`, Java `Pattern`, .NET `Regex` and Rust's `regex` differ
  in lookbehind, backreferences, named groups, Unicode classes and greediness
  edges — Rust's has no backreferences or lookaround at all. The owner's instinct
  for a **normalisation layer is exactly right**, and it has to be a *defined
  subset that every host can honour*, not a best-effort translation.
- **Strings.** flint is UTF-8; the JVM and CLR are UTF-16. `count`, `subs` and
  indexing disagree on anything outside the BMP. A program handling emoji or
  older CJK gives different answers per host unless the semantics are pinned to
  code points and enforced.
- **Numbers.** flint's tower is i64/f64 and overflow throws. JVM `long` wraps
  silently. Matching means checked arithmetic everywhere on that host.
- **Hash and iteration order.** If hashes differ, `pr-str` of a map differs, and
  **content-addressed artifacts hash differently per host** — which would break
  the property construe depends on. This one is not cosmetic.
- **Float printing** and **sort stability**, both classic silent divergences.

## So the portable guarantee is the conformance suite, not the bytecode

The bytecode makes a port *cheap*. It does nothing to make two ports *agree*.

The machinery for that already exists and should become the contract: **130
conformance expressions run on flint and, unchanged, under babashka**, so the
expectations are checked against a real Clojure rather than against memory.

Make that the admission test. **A host target is finished when it passes the
conformance suite, and the suite is the specification** — extended with the drift
cases above, which are exactly the ones nobody writes by accident: non-BMP string
indexing, overflow, hash stability, float printing, and a regex battery over the
defined subset.

Without that, "runs anywhere" means "runs everywhere differently", which is worse
than not porting at all.

## What to do now, given none of this is current work

- **Freeze and document the 0006 ABI**, since Tier 1 rests on it entirely.
- **Keep host-specific behaviour behind the same seams the units already
  provide**, so a port replaces units rather than editing the core.
- **Write the drift cases into the conformance suite now**, while there is one
  host. They are cheap to add today and they are the specification a second host
  will be built against.
