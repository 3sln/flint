# 0016 — Two builds: a stripped production VM, and everything else optional

A cross-cutting rule that supersedes the per-feature clauses in `0009`, `0014`
and `0015`. Those each said "keep it out of the pure module"; this says what that
means and how it is enforced, once, for all of them.

> **A production module contains no diagnostic machinery. Not cheap, not
> runtime-gated — absent.**

## Why a runtime flag is not enough

The tempting shape is a boolean the host sets. It is the wrong one: the code is
still there, it is still linked, it still costs bytes, and it still puts a branch
somewhere hot. `0009` already worked this out for gas and monomorphised the
counter out of the loop; this is the same conclusion applied everywhere.

**Cargo features and monomorphisation, not runtime flags.** Absent code cannot be
enabled by accident, cannot be branched on, and cannot be measured — which is the
only guarantee worth having.

## What counts as diagnostic, and what does not

The distinction that matters, because getting it wrong strips something the
product needs:

**Diagnostics — optional, absent by default.** Snapshots and their export format,
the inspector, the root verifier, GC stress mode, write-attribution, tracing,
the `forward()` plausibility check, the `slot()` forwarded-pointer assertion,
every `stat_*` export, and the debug runner of `0014`.

**Production features — always present.** Gas limits and the memory cap
(`0009`), the deterministic scheduler, error reporting with a chain, and the
`:exclude` and unit machinery. These are resource control and correctness, not
instrumentation. **Gas in particular must not be stripped** — construe's gates
depend on a deterministic instruction count, and it is already free when
unlimited because the loop is monomorphised.

If something is genuinely both — a check that is diagnostic and also a safety
property — say so, and default it to the safe side with the reasoning written
down.

## The live example: the `forward()` check costs 357 bytes today

It is gated `cfg!(debug_assertions) || self.stress`, so a release build carries
it for the sake of the stress path. That is exactly the shape this decision
forbids, and it is the most valuable check in the codebase — so **make it a
feature rather than deleting it.** Dev and staging builds get it; production does
not pay for it; a host chasing a production fault deploys the instrumented build
and reproduces.

That is better than either extreme: the capability survives, and the default is
honest.

## Enforced, not intended

- **The module-size assertion stays** and the floor drops to whatever a genuinely
  stripped module weighs.
- **A symbol check**, in the shape `test/modularity.clj` already uses to prove a
  program without XML carries no XML parser: assert a production module exports
  no `snapshot_*`, no `stat_*`, no `set_gc_stress`, no verifier.
- **Both builds are built and tested in CI.** A feature nothing compiles is a
  feature that rots, and the first person to need the instrumented build will
  find it broken. The suite runs against the stripped build; the diagnostic
  tests run against the instrumented one.

## And it is a security argument, not only a size one

flint's strongest measured case is as a **sandbox for untrusted model-written
code** — 15× faster to first answer than a V8 isolate, with no host access. A
production module that ships snapshot export is a production module that can be
asked to dump its entire heap, and it exists precisely to run code somebody else
wrote.

Absent is a different guarantee from disabled. That is the argument that settles
this even where the bytes would not.

## What must be true

- A production module exports no diagnostic symbol, asserted by name.
- The stripped floor is recorded in the README, and the instrumented delta beside
  it, so the cost of turning diagnostics on is a published number.
- Both builds compile and are exercised in CI.
- Gas, the memory cap and the deterministic scheduler survive stripping —
  asserted, because they are the ones most likely to be cut by mistake.
