# 0029 — The JVM runtime

> **PARTLY BUILT.** The image loader, the interpreter over all 46 opcodes and
> every builtin but three run real flint programs on the JVM, and all eight
> conformance cases agree with the native runtime byte for byte -- including
> hashes, forty-key CHAMP ordering, infinite lazy sequences and mutual tail
> recursion 300 000 deep -- several threads run one program on it, and AOT emits
> real bytecode (12x on a counting loop, every case agreeing with the
> interpreter). **The flint compiler runs on it and emits the same image the
> native compiler does, byte for byte.** Not built: the three regex builtins.

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

**It self-hosts.** The flint compiler runs on the JVM and emits an image that is
byte for byte the one the native compiler emits from the same input -- 5 361
bytes, identical. Byte for byte rather than "both run and agree", because an
image that differs is a compiler that differs, and the difference would surface
in a program no test here happens to run.

Getting there cost seven bugs, none of them a missing builtin, and every one
invisible to a conformance corpus that was passing 8/8 throughout:

* **A tail call was only a tail call to ITSELF.** Both ports optimised
  self-recursion and took a host frame for anything else, so MUTUAL tail
  recursion grew a frame per hop -- constant stack in flint's own VM, unbounded
  here. The compiler tail-calls between three of its own functions, so it ran
  for minutes and died in a trace of 11.6 million identical frames.
* **`seq?` was `seqable?`.** True for a vector, a map, a set and a string. It is
  invisible until something dispatches on it, and the analyzer does: it tests
  `seq?` before `vector?`, so an argument vector `[& clauses]` was analyzed as a
  CALL and the analyzer descended into its own head forever.
* **`seq` returned its argument**, so `(seq? (seq [1 2]))` was false.
* **`sequential?` used `Collection`**, which covers Set, so `#{1}` was
  sequential.
* **`assoc` on a VECTOR made a map.** `(assoc [:a :b :c] 1 :B)` answered
  `{1 :B}` -- the right value under the right key, in the wrong kind of
  collection, which then failed several calls later on something that was no
  longer indexed.
* **`str-bytes` answered how many bytes, not which.** `flint.image/utf8` is
  `(flint.rt/str-bytes s)` and the image writer counts the result, so the
  compiler counted a number and the error surfaced frames away as
  "14 (Long) cannot be counted", 14 being the length of whatever string it was
  on.
* **The CLR was 27 builtins short, not 3** -- all eight bit operations, all
  fourteen type predicates and `dissoc!`. The predicates exist as the `type-p`
  opcode, which is what a direct `(int? x)` compiles to, so they look present
  until something passes one as a VALUE. `(filter int? xs)` is how the compiler
  uses them.

Three lessons about MEASURING this, all learned the hard way:

**The depth is the diagnosis.** "Deep but finite" and "unbounded" look identical
in a stack trace and want opposite fixes. Counting the frames -- 11.6 million --
is what separated them, and 2 GB of stack is not evidence of anything on its
own.

**A call log is not a stack trace.** The ring buffer that names the functions
records the last calls MADE, so calls that already returned are in it, and a
`reduce` loop reads as a repeating cycle. Read that way it said the failure was
in `flint.eval/ev`; it was not. It then said the failure was in `emit-const`;
it was not there either -- a guard added inside `emit-const` never fired. Twice
the log named a function that happened to be busy rather than one that was
wrong.

**What found each of them was a backstop that names a VALUE.** `flint.eval` and
`flint.analyzer` both have a depth limit now, and each throws a flint error
carrying the node or the form. The analyzer's prints the innermost twelve forms
BY DEPTH -- indexed by the depth counter, so it really is the nesting -- and it
printed `(if 2 [2 nil] (clojure.core/cond))`. `[2 nil]` is how a MAP ENTRY
prints, and the trace vector holding it had turned into a map: that is how
`assoc` was caught. A limit is only useful if it can be REACHED, too: the first
one was 10 000 and babashka's stack gave out first, so the error arrived with no
message at all.

Both backstops earn their keep beyond this: a runaway macro used to exhaust the
host stack, and now names itself.

The earlier `unable to resolve symbol: string?` is gone, along with its
`:ns flint.main`; that was the `array-map` arity bug.

`bin/conform-hosts` gates on it under `FLINT_SELFHOST=1`, off by default for a
measured reason: 179 s on the JVM and 704 s on the CLR, against about a minute
for everything else. CI should turn it on.

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

Eight threads computing the same thing agree, and eight threads driving 200
increments each through one atom lose **none** of them: 1601 wanted, 1601 got.
`swap!` is a retry loop over `compare-and-set!` rather than a read-modify-write,
which is what makes that true; `doc/decisions/0013` records why enabling it
looked like an AOT failure for a while and was a stale build.
