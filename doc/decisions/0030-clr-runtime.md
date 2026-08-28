# 0030 — The CLR runtime

> **PARTLY BUILT.** The image loader, the interpreter over all 46 opcodes and
> sixty-odd builtins run real flint programs on .NET, agreeing with the native
> runtime on every conformance case, several threads run one program on it, and
> AOT emits real IL (1.53x on a compute loop, every case agreeing with the
> interpreter). Not built: most of the remaining builtins.

Tier 2, the same as `0029`: port the VM and lean on the host's collector. A
flint value is a .NET object, the CLR owns lifetime, and the generational
copying collector does not exist here.

## It passed conformance on the first run, and that is the finding

Every case agreed with the native runtime immediately — hashes, forty-key CHAMP
ordering, seqs printing as seqs, all of it. Not luck. It is the JVM port's
mistakes, already paid for and written down:

* a seq is not a vector, and they print differently;
* the `TYPE_P` codes are COPIED from `flint.types`, not inferred;
* `flint/keyword2` means the NAME with one argument and `(ns, name)` with two;
* maps are an array-map to eight entries and a CHAMP past that, in hash order.

Each of those cost a debugging session on the JVM. On the CLR they cost
nothing, because `runtimes/conform/README.md` and `0029` said what they were.

**That is the argument for the conformance harness stated as a number rather
than a principle.** The first port needed the harness to find four divergences
that all produced the right elements in the wrong shape. The second port needed
it to confirm there were none.

## Multi-threading

Works, and needed almost nothing: values are immutable, `Kw`/`Sym` intern
through a `ConcurrentDictionary` — which gives free the "one text, one object"
property the wasm runtime spends a lock on — and the var slots are guarded.
There is no safepoint to build because there is no collector of ours to stop.

`swap!` still loses updates under contention, on every runtime, because it is a
read-modify-write in `lib/clojure/core.cljc`. The test asserts the loss rather
than wishing otherwise; `doc/decisions/0013` records why the fix is not on.

## What is missing

Most of the 143 builtins the flint compiler itself imports, and AOT — which on
the CLR means emitting IL, where `0010` notes the constraint that forced an
interpreter on wasm is absent.

Missing builtins are **absent, not stubbed**: reaching one names it, because a
stub returning nil would let a program answer wrongly here and rightly
elsewhere.
