# 0029 — The JVM runtime

> **PARTLY BUILT.** The image loader, the interpreter over all 46 opcodes and
> fifty-odd builtins run real flint programs on the JVM, and all five
> conformance cases agree with the native runtime byte for byte -- including
> hashes and forty-key CHAMP ordering. Not built: most of the remaining
> builtins, AOT, and multi-threading.

`0010` chose tier 2 for the JVM on measurement rather than taste: Chicory runs
flint at **500× V8 interpreted and 39× compiled**, so embedding a wasm engine
is out and the VM gets ported. This is that port.

## The collector is gone, and that is the tier rather than a shortcut

A flint value is a Java object. `nil` is `null`, an integer is a `Long`, a
vector is a `List`, a keyword is an interned `Kw`. The JVM's collector owns
lifetime, so the generational copying collector — the single hardest part of
the wasm runtime, and the source of most of its subtle bugs — **does not exist
here**. Nothing roots anything, there is no write barrier, and no value moves.

Calls use the JVM's own stack for the same reason. `0001` explains that flint
is an interpreter on wasm because *wasm locals are not scannable*, so compiled
code would put live references where a linear-memory collector cannot see
them. The JVM scans its own stack, so that constraint simply is not present —
which is also why tier 3 there is a legitimate option rather than a fight.

`TAIL_CALL` is still a real tail call, looping rather than recursing, because
the JVM will not do that for us and flint programs written as self-recursion
depend on it.

## The gate is the conformance harness, not the port

`bin/conform-hosts` compiles one source and runs it on both runtimes, diffing
byte for byte. It is the whole of what makes the port checkable, and it earned
that on its first run.

**Every divergence it has found produced the right elements in the wrong
shape.** Not one would have crashed:

* A guessed `TYPE_P` code table made `int?` false for every integer, and
  flint's own `str` then printed a perfectly good number as `#<unprintable>`.
  `flint.types` calls a wrong code here the worst possible failure, because it
  makes every annotation in the program vacuous.
* One list type for both seqs and vectors made `(rest [1 2 3])` print as
  `[2 3]`. They are `=` to each other and they print differently, and `pr-str`
  is how an answer is compared.
* Map iteration order. flint's maps are an array-map up to eight entries and a
  CHAMP past that, so a nine-key map printed the right pairs in the wrong
  sequence. `0010` singles this one out as not cosmetic — content-addressed
  artifacts would hash differently per host. Closed by porting the hash
  bit-for-bit and walking the CHAMP as `map.rs` does.
* `(keyword "key0")` produced `:key0/`. `flint/keyword2` means the NAME with
  one argument and `(ns, name)` with two; taking argument 0 as the namespace
  regardless gave a keyword with an empty name, which printed almost right and
  matched nothing.

The harness holds a FLOOR rather than demanding every case, because the port is
unfinished and a known divergence is better visible than deleted. What must not
happen is the number going down.

## What the numbers say about the rest

Measured by loading real images and asking which builtins are missing:

| image | builtins imported | missing here |
| --- | ---: | ---: |
| a two-function program | 17 | 0 |
| collections, laziness, transients | 28 | 0 |
| the conformance corpus | 40 | 0 |
| **the flint compiler itself** | 143 | ~100 |

So the remaining work is mostly mechanical — bit operations, the maths library,
byte strings, regex — and the interesting parts are the two that are not:

**AOT** is tier 3: emit JVM bytecode rather than interpret. `0010` notes the
constraint that forced an interpreter on wasm — locals not being scannable — is
simply absent here, so this is a legitimate backend rather than a fight.

**Multi-threading** is the opposite problem to `0028`'s. There is no safepoint
to build, because there is no collector of ours to stop; the JVM's handles it.
What needs care instead is that flint's own structures are shared safely, and
most of them already are: values are immutable, and `Kw`/`Sym` intern through a
`ConcurrentHashMap`, which gives for free the "one text, one object" property
the native runtime spends a lock on.
