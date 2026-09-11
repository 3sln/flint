# flint on the CLR

An image loader, an interpreter over all 46 opcodes, and the builtins. Built
with `dotnet build`; run through `bin/conform-hosts`.

## The collector came back

This section used to say the collector was gone, and that a flint value was a
.NET object. That was `clr-runtime`'s design and it is no longer how this port works.

Since `9f6f70e` (2026-08-29) a flint value here is a NaN-boxed `long` over a
flat `Space`, and `Gc.cs` is `runtime/src/gc.rs` ported verbatim — the same
design over the same memory as the JVM's, rather than two collectors that
happen to agree. `conform-hosts` diffs their collection counts and fails if
they part, which is the check: not that each works, but that both make the
same decisions at the same points.

Calls still use the CLR's own stack, which it scans — exactly what wasm cannot
do (`DECISIONS.md#dispatch`) and why flint is an interpreter there at all.
`TAIL_CALL` loops rather than recursing, because the CLR will not do that for
us.

## It passed conformance on the first run

Every case agreed with the native runtime immediately, and that is not luck —
it is the JVM port's mistakes, already paid for:

* **A seq is not a vector.** They are `=` and they print differently, and
  `pr-str` is how an answer is compared.
* **The `TYPE_P` codes are copied, not inferred.** A guessed table makes `int?`
  false for every integer and every annotation in the program vacuous.
* **`flint/keyword2` means the NAME with one argument** and `(ns, name)` with
  two. Reading argument 0 as the namespace gives a keyword with an empty name:
  it prints almost right and matches nothing.
* **Maps are an array-map to eight entries and a CHAMP past that**, iterating
  in hash order, with flint's hash ported bit-for-bit.

That is what the conformance harness is for. The second port cost a fraction of
the first because the first one's failures were written down.

## Multi-threading

Works, and needed almost nothing: values are immutable, `Kw`/`Sym` intern
through a `ConcurrentDictionary`, and the var slots are guarded.

This paragraph used to end "there is no safepoint to build because there is no
collector of ours to stop", which was true of the FIRST design and has not been
true since. `Gc.cs` is 440 lines: a real generational collector ported from
`gc.rs`, which the section at the top of this file describes. Stopping it is
`RtParallel`'s problem and it is solved there, not absent here.

`swap!` still loses updates under contention — it is a read-modify-write in
`lib/clojure/core.cljc`, and the test says so rather than wishing otherwise.
Not a CLR problem; see `DECISIONS.md#emit-wasm-instead-of-dispatch`.

## The emitter is C#, and compiles on first call

`Reflection.Emit` builds a `DynamicMethod` per arity, the first time it is
called. So there is no cross-compilation -- emitting IL needs .NET present --
and nothing shippable is produced. Deliberate; see `DECISIONS.md#clr-runtime`.

## What is missing

NOT THE BUILTINS, AND NOT AOT, both of which this section claimed. `bin/check-builtins`
reports "clr carries all 168, the 2 mandatory included", and `Aot.cs`,
`AotEmit.cs` and `AotPlan.cs` are all here. The claim dated from the port's
first weeks and was never revisited.

The policy it stated is still the right one and still holds: a missing builtin
is **absent, not stubbed**, because reaching one names it, where a stub
returning nil would let a program answer wrongly here and rightly elsewhere.
