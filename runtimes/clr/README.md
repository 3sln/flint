# flint on the CLR

An image loader, an interpreter over all 46 opcodes, and the builtins. Built
with `dotnet build`; run through `bin/conform-hosts`.

## The collector is gone

A flint value is a .NET object: `nil` is `null`, an integer is a `long`, a
vector is a `Vec`, a keyword is an interned `Kw`. The CLR's collector owns
lifetime, so the generational copying collector — the hardest single piece of
the wasm runtime — is not here at all.

Calls use the CLR's own stack for the same reason: it scans it, which is
exactly what wasm cannot do (`doc/decisions/0001`) and why flint is an
interpreter there at all. `TAIL_CALL` still loops rather than recursing,
because the CLR will not do that for us.

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
through a `ConcurrentDictionary`, and the var slots are guarded. There is no
safepoint to build because there is no collector of ours to stop.

`swap!` still loses updates under contention — it is a read-modify-write in
`lib/clojure/core.cljc`, and the test says so rather than wishing otherwise.
Not a CLR problem; see `doc/decisions/0013`.

## What is missing

Most of the 143 builtins the flint compiler itself imports, and AOT.
Missing builtins are **absent, not stubbed**: reaching one names it, because a
stub returning nil would let a program answer wrongly here and rightly
elsewhere.
