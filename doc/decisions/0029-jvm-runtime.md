# 0029 — The JVM runtime

> **PARTLY BUILT.** The image loader, the interpreter over all 46 opcodes and
> fifty-odd builtins run real flint programs on the JVM, and all five
> conformance cases agree with the native runtime byte for byte -- including
> hashes and forty-key CHAMP ordering -- several threads run one program on it,
> and AOT emits real bytecode (1.67x on a compute loop, every case agreeing
> with the interpreter). Not built: most of the remaining builtins.

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
| **the flint compiler itself** | 144 | **3** (all regex) |

The mechanical bulk is done: predicates, bit operations, the maths library,
string operations and byte strings. What is left is regex --
`flint/re-compile`, `flint/re-run`, `flint/re-find-all` -- which needs flint's
Pike VM ported rather than a host regex engine underneath it, because
`doc/decisions/0011` pins the semantics to a defined subset every host honours
and a best-effort translation is exactly what it rules out.

Running the compiler is what found the last five bugs, and none of them was a
missing builtin. Every one was a shape the conformance corpus never used: a
bare string thrown where the runtime throws a structured error, a set used as a
function, `transient` on a set, `deref` on a volatile, and a vector holding
`nil`. The corpus is a good gate and the compiler is a better one, because it
is the only flint program large enough to use the whole language.

It does not self-host yet. It stops at `unable to resolve symbol: string?` --
a genuine compile error from the compiler, cause not yet found, with the
error's own `:ns` reading `flint.main`, which is not the namespace being
compiled. Both of those are open.

The interesting remaining parts are the two that are not mechanical:

**AOT** is tier 3: emit JVM bytecode rather than interpret. `0010` notes the
constraint that forced an interpreter on wasm — locals not being scannable — is
simply absent here, so this is a legitimate backend rather than a fight.

**Multi-threading works**, and it was the opposite problem to `0028`'s. There
is no safepoint to build because there is no collector of ours to stop; the
JVM's handles it. What needed care was flint's own shared state, and most of it
was already fine: values are immutable, and `Kw`/`Sym` intern through a
`ConcurrentHashMap`, which gives for free the "one text, one object" property
the native runtime spends a lock on. What changed is the var slots, which
became an `AtomicReferenceArray` -- a plain array write is visible to another
thread eventually or never, and "eventually" is not a semantics.

Eight threads computing the same thing agree, and eight threads through one
atom do NOT lose nothing -- because `swap!` is a read-modify-write. That is not
a JVM problem and the fix is written; it is off because it does not survive
AOT (`doc/decisions/0013`).
