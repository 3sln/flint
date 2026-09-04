# Goal — port the runtimes' shared logic to kin

**Status:** phase 1 begun — the codec's primitive writers are generated and
verified byte-identical on all three targets.
**Design:** `doc/decisions/0038-kin.md`. **Tool:** `kin/`.

## The objective

flint has four runtimes that are meant to be verbatim mirrors, and they are kept
so BY HAND. Write the shared parts once and generate them, so a change is made
in one place and the three cannot drift.

The cost being paid now is measured, not felt: fixing one park bug meant writing
the same thirty lines three times in Rust, Java and C#, and the gate caught the
port that had not been done yet with `opcode 0x1e is not ported yet`.

## The size of the prize

Comparing the JVM and CLR mirrors by non-comment line count — for hand-mirrored
code, agreeing to within a percent means the structure agrees too:

| file | jvm | clr | delta |
| --- | --- | --- | --- |
| `Maps` | 856 | 848 | 0% |
| `Table` | 832 | 830 | 0% |
| `Str` | 575 | 574 | 0% |
| `Bytes` | 469 | 464 | 1% |
| `Snap` | 449 | 456 | 1% |
| `Vec` | 383 | 379 | 1% |
| `Codec` | 309 | 312 | 0% |
| `Seqs` | 230 | 229 | 0% |
| `Eq` | 226 | 223 | 1% |
| `Pike` | 222 | 220 | 0% |
| `Interns` | 62 | 62 | 0% |

**This table measures the wrong pair, and the phase ordering inherited the
error.** Its columns are `jvm` and `clr`. Rust is not in it. The JVM and CLR
are near-identical to each other because BOTH WERE PORTED FROM THE RUST BY
THE SAME HAND -- so the delta says how alike the two copies are, not how alike
either is to the original, and the original is the one that diverges.

`Interns` is the proof: 0% here, and three differences from Rust the table
cannot see (see phase 2). Only one of them turns out to be essential, and it
points at converging rather than at refusing -- but the table could not tell
either way, because it never looked at Rust.

This is the same failure as everything else this file records. Two things that
agree by construction cannot testify about a third: `conform` comparing four
identical truncations, `check-builtins` crashing rather than checking, and a
similarity metric computed on two of four mirrors are all the same mistake at
different layers.

Every blocker actually MET so far has been a Rust divergence, which is what a
JVM-vs-CLR table cannot see: the receiver (`impl Rt` against statics taking
one), a lifetime on `Reader<'a>`, `matches!` against a switch, `to_le_bytes`
against four pushes, `Result` against exceptions. **The ordering should be
redone against Rust before phase 3 is trusted.**

**About 4,500 lines per runtime.** Opcode bodies, which is where this started,
are a few dozen lines and were never the prize.

## CODE FIRST, THEN TESTS — and the reason matters

The tests are the ORACLE. Porting them at the same time as the code would mean a
bug in kin could produce a wrong implementation and a wrong test that agrees
with it, and the suite would stay green while both were wrong.

So: port implementation code, verify it against the EXISTING hand-written tests,
and only once a file's implementation is generated and green does its test
become safe to port. Never port a test and its subject in the same change.

## Where things stand

* `kin/` — the library, three vocabularies, four sources, a babashka driver.
  Plain `.cljc`, no reader conditionals, runs under bb so it can bootstrap.
  `flint.impl.core` holds the SHAPE of a program and each subject vocabulary
  merges it in, so a new source pays for its own subject and not for `defn`.
* **`Hash` ships**, emitted into `runtime/src/hash.rs`, `Hash.java` and
  `Hash.cs`, which no longer carry murmur3 by hand. **`Eq.category` ships**,
  which is the first generated function with a RECEIVER.
* **`codec.kin` and `reader.kin` still ship nowhere**, and the two have
  different reasons. Measured, not guessed -- see below. Until they land, those
  slices proved the generator rather than reduced the tree.
* Verified end to end: the `apply` spread, the `type-p` opcode, and `if` in both
  statement and expression position (Rust gets an `if` expression, Java and C#
  get the conditional operator, from one source).
* **The generated Rust compiles verbatim**, pasted into `runtime/src/vm.rs`
  under `#[cfg(test)]` — so the build checks the claim rather than this file.
* Seven opcodes that were implemented three times and emitted never have been
  removed; their numbers are retired, not reused.
* **Two silent compiler bugs, found by the coverage work and fixed.** Both
  were TRUNCATIONS that no runtime refused, and neither was visible to
  `conform` because **all four runtimes truncate identically** -- so the suite
  compared four identical wrong answers and reported agreement. That is the
  same shape as `bin/check-builtins` crashing instead of checking, one layer
  up: a gate that cannot distinguish "everyone is right" from "everyone is
  wrong the same way" is not a gate.

  * **A call's argument count is one byte** and was written unmasked, so the
    260-argument `(+ v0 ... v259)` in `wide-locals` was emitted as a
    FOUR-argument call. The VM popped four operands, took the fifth from the
    top as the callee, and died with `value is not a function (255, 4 args)`.
    Now refused at compile time, naming the count, following this file's own
    `jump out of range` precedent.
  * **There was no wide `set-local`.** A wide READ (`local-w`, 0x07) has
    always existed; the WRITE truncated its index to a byte, so binding local
    256 stored into local 0. Low locals were clobbered and high ones read
    `nil` -- silent wrong answers, no crash. `set-local-w` (0x2D) is now
    implemented in all four runtimes and both AOT backends, and every local
    index in the emitter goes through ONE width-aware helper rather than eight
    hand-written `put!` calls, which is what let the two halves disagree.

    Cost, and worth recording as a measure of the prize: one opcode, written
    by hand into `vm.rs`, `Rt.java`, `Rt.cs`, both `AotPlan`s, both
    `AotEmit`s, and `aot.cljc`'s wasm backend. The interpreter arm is one line
    per runtime; the tables around it are where the porting actually goes.

* **Opcode coverage was NOT 38 of 38.** Every opcode the compiler can emit is
  executed by `bin/conform-hosts` on all three runtimes. That is the baseline a
  port must not lower.

  The census counted an opcode as covered when a program that emitted it ran.
  `local-w` was emitted, and the program that emitted it THREW before
  producing an answer -- so the opcode was reached and its correctness was
  never observed. Reaching an instruction is not covering it, and the fix for
  the count is the same as the fix for the suite: check the answer, not the
  arrival.

  Checking it per slice means running `opcov` over the images a build leaves in
  `out/`, which is a SMALLER set than the 38-of-38 figure covers -- it reports
  36 hot, and comparing that number against 38 would be comparing two different
  image sets. So the check is a before/after over the SAME images, taken by
  stashing the slice and re-running: `Hash` measured 220 of 256 cold both ways,
  unchanged. Answering criterion 4 with a reading -- "hashing contains no
  opcode dispatch, so coverage cannot move" -- would have been true and would
  not have been a measurement.

  The set is now fixed by `bin/opcov-gate` rather than reconstructed by hand
  each time, because reconstructing it by hand went wrong twice in one sitting:

  * `opcov` was pointed at `out/*.wasm`. That directory ACCUMULATES -- it held
    260 images, most of them ad-hoc -- and `opcov` executes each one, so it sat
    at 100% CPU on the first long-running program and printed nothing for 23
    minutes. The number of images a build "leaves in `out/`" is not a set.
  * `opcov` runs IMAGES (`--emit-image`, `.img`), not wasm modules, and it
    needs `--features diagnostics` to record anything at all. Handed the wrong
    artifact it rejected all sixteen, reported "256 of 256 slots cold", and the
    first version of the gate script **still exited 0**.

  That last one is the failure this whole file is about, reproduced inside the
  tool built to detect it: a gate reporting total coverage loss and calling it
  success. `bin/opcov-gate` now fails if any image is rejected, if fewer than
  fifteen build, or if the census says nothing ran. The count moved from 220
  over 17 images to 217 over 16 because the set is different and now written
  down -- not because coverage fell.

## Phases

Ordered by RATIO — how alike the mirrors already are — not by size.

### 1. `Codec` — the first real port
309/312 lines, self-contained, no allocation subtleties, and the wire format is
specified elsewhere already. Big enough to be a real test of the tool and small
enough to abandon if it is not.

**Done:** `u32`, `u64`, `str` — the primitive writers. Generated for all three,
compiled, run, and BYTE-IDENTICAL:

```text
rust   0403020188776655443322110600000068c3a96c6c6f
java   0403020188776655443322110600000068c3a96c6c6f
csharp 0403020188776655443322110600000068c3a96c6c6f
```

`kin/verify <source>` is that check, made repeatable: it generates, compiles
each target, runs each, and requires the outputs to match. Compiling is not
enough — three implementations can each compile and disagree, which is the
failure the port exists to prevent. A new port writes a `.drivers` file beside
its source and the harness is unchanged.

**Done:** the reader's `u8`, `u32`, `u64` and `text` — the whole primitive
half of the decoder. The first kin source that declares a MUTABLE STRUCT and
the first whose functions can FAIL.

```text
rust   01020304 ff 44332211 9 1122334455667788 hello
java   01020304 ff 44332211 9 1122334455667788 hello
csharp 01020304 ff 44332211 9 1122334455667788 hello
```

The `u64` there is a genuine ROUND TRIP: the writer put those bytes on the wire
little-endian and the reader took them back, two independently generated halves
agreeing across three targets.

*(That said, both halves were later measured and REFUSED -- see "What the
writers measured". They proved the generator and ship nowhere. The lines above
are kept because the round trip is still the evidence that the generator
works.)*

### What the reader surfaced

The first divergence in this port that is **not naming**, and it was predicted:
Rust returns a `Result` where the other two throw. That changes the SIGNATURE
and every call site, so it cannot live in a call template.

`^:throws` on a function name now says it can fail. Rust turns the return type
into `Result<T, String>` and wraps returns in `Ok(...)`; Java and C# ignore the
mark entirely, because an exception needs nothing in either. That is the shape
every further Rust-only rule should take.

Three more Rust-only rules, each found by `rustc` refusing something:

* an **index is `usize`** and the struct field is `u32`, so indexing casts;
* a **length is `usize`** and has to come back to be compared;
* **`x as u32 << 8` does not parse** — Rust reads `u32 <<` as the start of
  generic arguments and says so. A cast used as a shift operand needs its own
  parentheses.

**A second non-naming divergence, and it rides the TAG rather than the
function.** A decoded string may be ABSENT — which is not the same as empty,
and is what distinguishes `:kw` from `:/kw`. Rust says so in the type,
`Option<String>`; Java and C# use null. So `MaybeText` is a tag whose Rust type
is `Option<String>`, with `absent` and `present` as forms. Absence is a property
of the VALUE, so it belongs on the tag; failure is a property of the CALL, so it
belongs on the function. Two marks, two homes, and the reason each is where it
is.

**A constant can differ per target when the types do.** The absent-namespace
marker is the same 32 bits everywhere and is `u32::MAX` in Rust and `-1` in
Java and C#, whose ints are signed. Writing `4294967295` in the source produced
`integer number too large` on two of the three, so `no-ns` is a form.

And one that was not Rust's fault: `defstruct` emitted Java's
`static final class` for C# as well, because the vocabulary had a Rust branch
and a branch for "the rest". `final` is not C#. **A two-way split with three
targets is a default wearing a shared name**, and the fix was to write all three
out.

### 1b. What the writers measured, and why they must not be ported

The four-line `u32` writer looked like the safest thing in the codebase to
generate. It is the one function found so far that **must not be**.

Rust writes `o.extend_from_slice(&n.to_le_bytes())`. The JVM and the CLR write
four bytes one at a time, and that is the shape `codec.kin` generates for
all three. Compiled at `-O` and counted:

| | instructions |
| --- | --- |
| `extend_from_slice(&n.to_le_bytes())` | 18 |
| four `push` calls | 52 |

Nearly 3x, because four pushes are four capacity checks and four stores where
the other is one reserve and one 4-byte write. **This is the first time the
not-worse rule has blocked a port rather than tidied one**, and it blocked it
on a measurement rather than on taste.

The obvious escape -- make `write-u32-le` a vocabulary form -- is not one. The
body would then be written three times inside the vocabulary, which is the
same duplication moved one level down and dressed as a primitive.

So the writers stay hand-written. Twelve lines across three runtimes is the
correct price for three genuinely different best spellings of "put four bytes
in a buffer".

**The reader measures the same way, and an earlier entry here said the
opposite.** That claim -- "`from_le_bytes` 19 against shift-and-or 13, so the
generated form is BETTER" -- was wrong twice over, and it is recorded here
rather than quietly deleted because it licensed a port that measurement
refuses.

* **Wrong baseline.** It compared against `HostReader`, which does an explicit
  `if` and then four indexes. The GUEST `Reader` -- the one a port would
  replace -- does `self.b.get(self.i..e)`: **one** range check for four bytes.
* **Wrong method.** It compiled a binary with a `main` calling each form once
  on constants, so rustc specialised them against that call site. Measuring a
  generated function needs `--crate-type=lib`, or the numbers are about the
  benchmark rather than the code.

Measured again as a lib crate, instructions to the first `ret`:

| | hand-written | generated |
| --- | --- | --- |
| `Reader::u8` | 32 | 36 |
| `Reader::u32` | **33** | **49** |
| `Reader::u64` | 32 | 44 |
| `Reader::str` | 75 | 93 |

Every reader primitive is WORSE generated, by 1.1x to 1.5x, and for the same
reason as the writers: the hand-written Rust pays one bounds check where the
shared shape pays four. So the codec's primitives are one answer after all --
do not port -- and the interesting thing is that the per-function discipline
was right while the number feeding it was not. **A per-function verdict built
on a bad measurement is still a bad verdict.**

Two SAFETY holes, both found by running the generated code rather than reading
it, and both worse than the one previously recorded here:

* **Rust.** Generated `text` guards with `(r.i + n) > r.b.len()` in `u32`
  arithmetic, which wraps in release. A length of `0xfffffffe` after a 4-byte
  header gives `4 + 0xfffffffe = 2`, the guard passes, and the slice panics --
  in a `no_std` runtime, where the hand-written `b.get(i..e)` returns a
  refusal.
* **JVM.** The same input gets past the missing `n < 0` guard to a
  `StringIndexOutOfBoundsException`.

So porting `reader.kin` as written makes ALL THREE less safe, not two. And
the guards cannot be one spelling: Rust widens to `usize` where `i + n` cannot
wrap on 64-bit, and the JVM and CLR have no unsigned `int` and need the
`n < 0`. That is a third genuine non-naming divergence, and an honest
vocabulary form with three bodies rather than duplication in disguise.

### 2. The small pure files
`Eq`, `Hash`, `Seqs`. Near-identical, no ownership subtleties.

**`Interns` was listed here and should not have been.** Not because it is
missing from Rust -- it is not; `Interns.java`'s own header says "ported from
`runtime/src/gc.rs`", where it lives as `InternTable` because the tables are
COLLECTOR-ADJACENT: entries are weak and dropped by the GC, which is what
lets every short string and keyword be interned without leaking. Rust files it
beside the heap; Java and C# convention gives it a file of its own.

It was moved to phase 5 on the grounds that its three differences from Rust
were structural. **That was wrong, and the reasoning was backwards.** The rule
in this file is to treat a divergence as INCIDENTAL until somebody can point
at a language refusing the alternative -- and the divergence itself was used
as the evidence, which inverts it. Different implementations having different
trade-offs does not mean they SHOULD differ: the trade-offs port too.

Taken one at a time, only ONE of the three is a language refusing anything:

| | Rust | JVM / CLR |
| --- | --- | --- |
| storage | `Vec<(u32, u64)>`, array of pairs | `int[] hashes` + `long[] values`, parallel arrays |
| `lookup` result | `Result<Value, usize>` -- value OR insert index | returns the value, writes the index to a mutable `slot` field |
| candidate test | an `FnMut(Value) -> bool` closure | a `Match` functional interface |

**Storage is the only real one, and it points at converging.** Java has no
value types, so a packed array of `(u32, u64)` pairs is not available to it --
it would be object references and pointer chasing. Java is FORCED to parallel
arrays. Rust and C# can do either.

Which is faster is a genuine trade-off, and it is the same trade-off in all
three: parallel arrays give better locality while SCANNING, because a probe
walks 16 hashes per cache line against 4 packed entries; an array of pairs
wins when the first probe hits, because the value is already in the line. At
75% load -- which is this table's grow threshold, and high for linear probing
-- probe chains are what dominate. Measured, `rustc -O`, ns per lookup:

| table | | parallel arrays | array of pairs |
| --- | --- | --- | --- |
| 16384 slots | hit | **7.86** | 8.27 |
| | miss | **14.05** | 15.20 |
| 1024 slots (real size) | hit | 7.2-7.4 | 6.1-7.4 (noise) |
| | miss | **20.4-20.8** | 21.4-22.1 |

At the size the tables actually run, hits are within noise and misses favour
parallel arrays by ~5%; at a grown table the advantage is 5-8% on both. So
parallel arrays are equal-or-better everywhere measured, AND are the only
option one target has. Rust should move to them, and then the layout is one
decision expressed once rather than three accidents.

**The other two are incidental.** `Result<Value, usize>` against a returned
value plus a mutable `slot` field is a shape kin cannot say yet (hole 4) --
but nothing refuses the alternative; Java could return a packed `long`. The
`FnMut(Value) -> bool` against a `Match` functional interface is not a
divergence at all: a functional interface IS the closure, and they correspond
one to one.

(The mutable `slot` field is safe, incidentally -- every read of it happens
between `lockIntern` and `unlockIntern`. Checked rather than assumed.)

**A note on how it got here, because the mistake is repeatable.** I first
reported `Interns` as having no Rust counterpart at all, on the evidence that
`ls runtime/src/interns.rs` failed. That tests a FILENAME, not whether the
code exists, and the answer was one `grep` away. Same class of error as
measuring the wrong reader function: a check that is cheap to run is not the
same as a check that answers the question.

**`Hash` is done, and is the first thing generated that actually ships.**
The murmur3 core -- `mix-k1`, `mix-h1`, `fmix`, `hash-int`, `hash-long`,
`hash-combine`, `mix-coll-hash`, `ordered-step`, `unordered-step` and the
constants -- is now written once in `kin/hash.kin` and emitted into all
three runtimes. It was the right file to start substituting with because the
three copies already SAID they were kept in step by hand: `Hash.java` warns
against `String.hashCode()` because it would let the two ports reach the same
number by different routes, and ends "The arithmetic is written out on both."

    rust   ... 736442005 439094965 1231 1237
    java   ... 736442005 439094965 1231 1237
    csharp ... 736442005 439094965 1231 1237

736442005 is Clojure's hash of `[1 2 3]` and 439094965 is its hash of
`#{1 2 3}`, both pinned in `hash.rs`'s tests against real Clojure. Three
targets agreeing with each other is the weaker claim; this is three targets
agreeing with Clojure.

#### What `Hash` surfaced

* **`^:mut` on a parameter.** Rust alone has to say a parameter is reassigned.
* **`^:inline`.** Rust says so in the source; the JVM and CLR decide at run
  time from profile data, which is strictly more information than a source
  has. Emitted for one target and dropped by two, and dropping it is not a
  loss. Two `#[inline]`s were missed on the first pass and the diff caught it.
* **`^:unchecked`.** C# wraps the BODY, which is what the hand-written runtime
  does, and is what lets murmur's wrapping arithmetic be plain `*` and `+`.
  Per-expression `unchecked(...)` nests into `unchecked(unchecked(a * b) + c)`
  -- the same IL and much harder to read.
* **Constants are named per target.** Rust and Java scream (`HASH_TRUE`), C#
  pascalises (`HashTrue`), and the existing callers are written to it. So a
  declaration registers how its name is SPELLED, and a reference in the body
  reads that rather than guessing. Locals are camel in BOTH Java and C#, where
  functions are camel and Pascal — one rule for both emitted `ItemHash`.
* **Hex is said by the source.** Deriving it from the value wrote
  `HASH_TRUE = 0x4cf`: the right number and the wrong constant.
* **A rotate is an intrinsic in all three** and only Rust was using it. Naming
  it in the vocabulary kept the Rust identical and upgraded the other two --
  the not-worse rule paying rather than costing, for once.
* **Compound assignment is per target.** `mul32` is `*=` on the JVM and the
  CLR and `wrapping_mul` in Rust, which has no compound form at all.
* **Paren-stripping got its safe rule at last.** Ask the TEMPLATE, not the
  expression: strip an argument's outer parens only where the template puts
  `{i}` immediately between delimiters, so there is provably nothing to bind
  with. `wrapping_add({1})` qualifies; `((int) {0})` does not, and that is
  exactly the case whose parens were load-bearing.

#### `champ.kin` -- the CHAMP node accessors, and the first file that cost nothing new

The eight `bn_*` accessors -- `bn-datamap`, `bn-nodemap`, `bn-key`, `bn-val`,
`bn-set-key`, `bn-set-val`, `bn-node`, `bn-set-node` -- are how every insert,
lookup and iteration in `map.rs` / `Maps.java` / `Maps.cs` addresses a bitmap
node. They are generated into all three.

Two things this settled.

**The codec spike's "no array subject" was the wrong shape for the question.**
It ranked array primitives as the real gate on phase 3's ~4,000 lines. But
phase 3 has no native arrays: `Maps`, `Vec`, `Table` and `Snap` walk the SLOTS
of a heap object, Rust through a method on `Rt` and the other two through a
static taking one. The gate turned out to be three call templates -- `slot`,
`set-slot`, `olen` -- not an array subject. A blocker named from reading is
not the same as a blocker met.

**It needed no new structural capability** -- but that claim needs one
qualification, found by trying a fourth file. `Rt.isSeq` is the same shape on
paper and could NOT be ported, because `^:method` means "Rust `self`, JVM and
CLR static taking the receiver", and that is true of `Eq` and `Maps` by
accident of how those files happen to be written. `Rt.isSeq` is an INSTANCE
method on all three, which `^:method` cannot say -- exactly hole 7 as the
codec spike predicted it. It also wants per-target visibility: `pub` in Rust
and package-private on the JVM.

Two new capabilities for a six-line function is a bad trade, so it stays hand
written and the finding is recorded instead. **What converged is the
vocabulary of a SUBJECT -- slots, rooting, type tags. What has not converged
is the shape of a DECLARATION**, and the next file that needs an instance
method will have to pay for it.

`hash` bought four marks;
`eq.category` bought the receiver, vocabulary names and statement-vs-value
`case` arms; `champ` bought heap slots and a `doc` form and nothing else. That
is the first evidence the vocabulary is CONVERGING rather than growing once
per file, which is the whole question of whether this scales.

The `doc` form was bought the same way everything here is: the first emit
turned a `///` into a `//` and quietly demoted a documented function to an
undocumented one in three runtimes at once.

`cnCount` is deliberately NOT in the region. Same shape, but in all three
files it sits among the `cn_*` functions with `cn_new` between it and the
block, and a region has to be contiguous. Moving it would tidy the generator
at the cost of the reader.

#### `Eq.category`, and the receiver

`Eq` is not the clean mirror the ratio ordering assumed -- Rust has
`is_sequential` and `eq_may_alloc` that the JVM and CLR simply do not -- but
`category` is, and it is now generated into all three.

What it took was `^:method`. Rust puts these on `impl Rt` and reaches the
runtime as `self`; the JVM and CLR make them statics taking an `Rt`. That
difference, not the bodies, is what had kept `Eq`, `Seqs` and most of the bulk
out of reach, and it costs one mark on the `defn` plus registering the
receiver's NAME so `(. rt gc)` comes out as `self.gc` in one place and `rt.gc`
in two.

Three more things it surfaced:

* **A vocabulary provides NAMES, not just forms and tags.** `TY_CONS` is
  imported unqualified in Rust and Java and is `Obj.TyCons` in C#. It cannot
  be a form, because it appears in a `case` label where a call cannot go, and
  it cannot be left alone, because one target spells it differently. Names
  resolve through the require scope like everything else, so an alias works on
  one exactly as it works on a form.
* **`case` arms are VALUES, and that is forced rather than chosen.** A Rust
  match arm is an expression, so `return X;` inside one emits `=> return X;,`
  -- which is what the first attempt produced. A JVM `case` label cannot yield
  a value, so each arm must `return` for itself. The source says the value and
  each target spends what it must: Rust wraps the whole match in one `return`,
  the other two put a `return` in every arm. This is the statement/expression
  split the design anticipated, arriving in the first place it bites.
* **A generator that drops comments is a generator that throws away the
  expensive part.** `category` carries the explanation of why a row ref is in
  the map category, with a pointer to `0026`, and the first emit silently
  deleted it from two runtimes. `;;` in a kin source is for the source and
  never reaches the output -- the reader discards it -- so a comment meant for
  a reader of the GENERATED file has to be said as a form. That distinction is
  exactly the difference between explaining the rule and explaining the code
  the rule produces.

### 3. The bulk
`Maps`, `Table`, `Str`, `Bytes`, `Vec`, `Snap`, `Pike`. Each large enough to
want its own change and its own review.

### 4. Then the tests
Only after a file's implementation is generated and green. `runtimes/conform/*`
and the per-runtime unit tests are the candidates.

### 5. Never
`Obj`, `Frame`, `Space`, `Roots`, `Gc`, the wasm ABI shims, `unsafe` heap
access. The mirrors diverge by 12–36% there and the divergence is what each
language makes cheap. Generating three subtly wrong things is worse than three
honestly separate ones.

## How generated code reaches the runtimes

`kin/verify` proves three targets agree. It does not put anything in the
tree, and for the first three sources nothing was: the codec and reader
slices were a verified parallel implementation that shipped in no runtime.
Every acceptance criterion below presupposes SUBSTITUTION, so that gap made
four of the five unanswerable.

`kin/emit` closes it. Each source has a `.targets` file naming the file
and indent per target, and each target file carries a marked region:

```
// kin:begin kin/hash.kin
...generated...
// kin:end kin/hash.kin
```

The generated code is **checked in**. Somebody cloning this repo to build the
JVM runtime must not have to install a Clojure to do it, and a generator in
the build path is a generator that breaks the build. So `kin/emit` is run
by hand, its output is committed, and the markers make the next run a diff
rather than a merge.

The region has to be CONTIGUOUS, which means the hand-written file is
reordered once when it is first carved. That is free in all three -- a Rust
module, a Java class and a C# class do not care what order their members are
declared in -- and it is a one-time cost per file.

## Where phase 2 stands, with the numbers

Six sources ship, about thirty functions, each generated once and emitted into
Rust, Java and C#. All five criteria, measured rather than asserted:

| | evidence |
| --- | --- |
| 1 conform | exit 0, 227 checks, 0 failures |
| 2 runtime build | Rust `cargo check --features diagnostics` clean; JVM `javac` clean; CLR built by conform |
| 3 `bin/test` | exit 0, 0 failures |
| 4 coverage | **217 of 256 cold, against a 217-of-256 baseline over the same 16 images**, re-measured after EVERY slice including the last -- and the cold SET matches, not merely the count. Run by `bin/opcov-gate` |
| 5 ships | substituted in-tree between markers, committed, declaration sets diffed against `HEAD` |

| phase | file | what ships |
| --- | --- | --- |
| 2 | `Hash` | the murmur3 core |
| 2 | `Eq` | `category` |
| 2 | `Seqs` | `vecseq`, `strseq`, `lazySeq`, `range`, `rangeEmpty` |
| 2 | `Interns` | `mask`, `insert_at`, `needs_grow`, `raw_insert` |
| 3 | `Maps` | the eight CHAMP node accessors |
| 3 | `Pike` | `word_cp`, `space_cp`, `pred_hit` |
| 3 | `Maps` | `mergeTwo` -- the CHAMP insert's hard case |
| 3 | `Maps` | all six structural copies -- insert, remove, set-value, set-node, inline-to-node, node-to-inline |
| 3 | `Maps` | `nodeAssoc` -- the CHAMP insert itself |
| 3 | `Maps` | `collAssoc` -- an entry into a collision node, closing the cycle |
| 3 | `Maps` | `nodeDissoc` + `collDissoc` -- removal, and the shape invariant |
| 3 | `Maps` | `nodeFind` + `nodeFindScalar`, and `eqMayAlloc` in `Eq` |
| 3 | `Maps` | the collision-node accessors, and the size predicate |

The assoc/dissoc block is complete: thirteen functions of `Maps`, one
definition each. The three analyses had gated the whole block on converging
the hit/found sentinel; that turned out to be one line of thought (`cnt` is
already outside the valid range) rather than the capability it was ranked as.

`dissoc.kin` is the first source to ship TWO functions that call each other,
which makes its driver the first that exercises a real interaction rather
than a function against stubs -- `nodeDissoc` reaches the generated
`collDissoc`, not a fixture's stand-in.

### `nodeAssoc` ADDS forty lines, and that is the honest number

Every slice before this one removed hand-written lines. This one does not:

    hand-written removed   100
    generated added        140
    net                    +40

The reason is not a regression, it is what the two forms are for. The ports
wrote `out = cond ? a : b` and declared three locals to a line; kin emits one
statement per line, and the source's comments are duplicated into all three
runtimes rather than living in one of them. So the generated file is longer
than the C# it replaced even though it says the same thing.

This is worth stating plainly because the "size of the prize" section above
counts lines, and a reader who carries that framing forward would read this
slice as a loss. The prize was never line count -- it is that `nodeAssoc` now
has ONE definition instead of three that a person has to keep in step by hand.
A slice that costs lines and buys that is still the trade this goal is making;
a slice that saved lines and lost the single definition would not be.

`collAssoc` then cost `+104` on the same terms, so this is a pattern rather
than one slice's accident: the DENSER the hand-written ports were, the more a
port costs in lines. The early slices removed lines because `Hash` and the
CHAMP accessors were already one statement per line in all three. `Maps`'s
control flow is not, and will not be.

Two thirds of that is C#. `out` is a reserved word, so the result is
`@out`; and CS0136 refuses a local whose name is reused in an enclosing
scope, which Rust and Java both allow -- so `collAssoc`'s three blocks name
their roots `wbase`/`rbase`/`gbase` rather than `base` three times. One
source has to satisfy the strictest of the three targets, and every slice
after this one pays that same tax.

Comparing the SET and not only the count is the part that matters: a
regeneration making one opcode unreachable while another became reachable
would hold the total at 220 and change the membership. A number that agrees
for the wrong reason is the failure this whole file keeps circling.

`0x2d` is among the cold opcodes and correctly so: it is `set-local-w`, added
this session, and no conformance program has more than 255 locals now that
`wide-locals` sums in chunks. It is covered by a direct repro instead, which
is worth saying out loud -- an opcode being cold in this census is not the
same as it being untested, and conflating the two is what made the 38-of-38
claim wrong in the first place.

## Acceptance, per phase

Nothing lands without all of these:

1. Generated output compiles on **all three** runtimes.
2. `bin/conform-hosts` green — this is the test that would catch a wrong port.
3. `./bin/test` green.
4. `opcov` coverage has not dropped. A regeneration that makes something
   unreachable is exactly the quiet failure this guards.
5. The generated code is diffed against what it replaces and is **not worse**.

## What the codec spike found: the holes, ranked

The codec was chosen as a hard case on purpose. The verdict is **partly
portable, and the split is the opposite of what was built first**: of ~1,030
shared lines, the ~890 in `encodeInto` / `encodeCollection` / `decodeAt` /
`encode` / `decode` are worth generating, and the ~140 of primitives that
`codec.kin` and `reader.kin` actually implement are the part that must
not be. Net if the capabilities below existed: about **660 lines removed**
across the three runtimes, from a source of ~230 -- more than `Hash` returned.

The holes, ranked by how much of the REST of the port hits them. Numbers 1
and 8 are now done -- both were small, both were found by the spike rather
than by reading, and number 1 was the gate on everything after `Hash`:

1. ~~**No counted loop.**~~ **DONE.** `for`, `while`, `break` and `continue`
   are in `core_vocab` and verified running identically on all three
   (`kin/loops.kin`). `while` had existed only in `flint.impl.vm`, so
   every other source was unable to loop at all even where a `while` was
   exactly right; it moved, along with the `let`/`set`/`if` that predate this
   file. Building it immediately exposed a hole nothing else had: **a `let`
   that is later `set` needs `mut` in Rust**, so `^:mut` now marks a local as
   well as a parameter.
2. **No array subject at all.** Declaring, indexing, allocating, copying and
   measuring an array is the CONTENT of phase 3. kin has two `byte-at` and
   `len` templates in one vocabulary and nothing general. This, not the loop,
   is the real gate on the 4,500-line prize.
3. **`case` arms cannot be statements.** `rt_vocab`'s `case` renders arms as
   values, which is right for `category` and wrong for `decodeAt`'s fourteen
   multi-statement arms. It needs the same statement/expression split `if`
   already has, plus C#'s `break`.
4. **Absence has no form for the TEST.** `Option<T>` against null is how every
   partial function in `Maps`, `Vec` and `Table` answers. The `MaybeText` tag
   solved absence in a VALUE; `if let Some(i) = ...` against `if (i != null)`
   is unsolved.
5. **No string building.** Rust's `format!` needs a literal format string, so a
   variadic `str+` cannot be a template -- its Rust side has to BUILD the
   format string, making it the first form whose implementation is code rather
   than data.
6. **No type parameter on a generated type.** Proven, not guessed: a `Reader`
   holding a borrowed slice emits `error[E0261]: use of undeclared lifetime
   name 'a`. Every borrowed cursor has this shape.
7. **`^:method` answers the wrong question for a non-`Rt` receiver.** It means
   "Rust `self`, JVM/CLR static", which is right for `Rt` and wrong for
   `Reader`, where all three want an instance method. Two questions wearing
   one mark.
8. ~~**Bare `(return)` and `else if`.**~~ **DONE.** `(return)` emitted
   `return null;` on all three and compiled nowhere; `else` whose body was an
   `if` nested a brace level per arm. Both fixed and verified. Rust's bare
   return is `Ok(())` when the function is `^:throws`, since that turns the
   return type into `Result<(), String>`.

And two findings that are not kin's fault and block the codec just as hard:
**`codec.rs`'s shared half is 446 lines against the JVM's 290**, with nine
divergences that have to be closed BY HAND before generating is even
meaningful -- several of which look like drift rather than choice, since
`schema_name_at` and `opaque_label` already exist in Rust and `codec.rs`
simply does not call them.

## OPEN ITEMS

The single list FOR THIS PORT. Project-wide work lives in
`doc/decisions/README.md` under "Open, in the order the last measurement left
them" -- this file is item 0a there, and anything that is not about generating
a runtime's shared logic belongs in that list rather than this one. Two lists
that overlap is how one of them goes stale.

Anything not in one of those two places is not tracked. Earlier stretches of
this work kept a running list in conversation, which is the same as keeping it
nowhere.

### Ready to do, no decision needed

| | item | why |
| --- | --- | --- |
| 1 | **Port the rest of `Interns`** once the layout lands | `mask`, `needs_grow`, `insert_at`, `raw_insert` are portable as soon as Rust and the ports share parallel arrays. `lookup` and `grow` are not: `lookup` needs holes 3 and 4, `grow` needs array allocation |
| 2 | **Re-rank phase 3 against Rust** | the ratio table is `jvm` vs `clr` with Rust absent, and every blocker actually met has been a Rust divergence. `Maps`, `Table`, `Str`, `Bytes`, `Vec`, `Snap`, `Pike` are all scored 0-1% on a metric that cannot see the thing that blocks them |
### Wants a decision first

| | item | the question |
| --- | --- | --- |
| 3 | **Measure the intern layout on the JVM and CLR** | the numbers backing the conversion are Rust on one machine. The conclusion is defensible without them -- the JVM has no other option -- but "equal-or-better everywhere" is currently one-third measured |

### Blocked on a capability, in the order they bite

**RE-RANKED against Rust, and the old order was wrong in both directions.**
Three analyses covering `Maps`+`Table`, `Str`+`Bytes`+`Vec` and
`Seqs`+`Eq`+`Snap`+`Pike` agree: the holes this file ranked highest block
almost nothing, and the two that block most were not on the list at all.

| hole | this file claimed | measured against Rust |
| --- | --- | --- |
| **9 (new) `and` / `or`** | absent | **15 functions** in `Seqs`+`Eq`+`Pike` alone, plus every `&&` guard in `Maps`, `Vec`, `Str`. Cost: **one line** -- `'and "&&" 'or "\|\|"`, spelled identically on all three |
| **10 (new) allocation** | absent | **~2,000 lines.** `alloc`, `heap`, `fixnum`, a `NIL` name, an `Addr` tag. Every constructor in every file |
| **11 (new) cross-module calls** | absent | **11 functions.** Rust reaches a sibling through `self`; the ports through a class-qualified static taking `rt`. One pattern, not one decision per function |
| 5 string building | mid | **13 in `Table`**, 0 in `Maps`, 0 blockers in `Seqs`/`Eq`/`Pike` |
| 2 arrays | "the real gate on 4,500 lines" | **2 functions** in `Maps`+`Table`. And the array-of-`Value`s half **is not needed at all** -- converging node construction to `(base, n)` on the shadow stack removes it from eight functions, measured 5.5x faster in Rust at 2 kids |
| 3 `case` statements | "every dispatch" | **1 function** in `Maps`+`Table`, **0** in `Str`/`Bytes`/`Vec`, 5 in `Seqs`+`Eq` |
| 4 absence | "every partial function" | **1** in `Table` (the others are drift), 3 in `Seqs`+`Eq`+`Pike` after converging |
| **6 borrowed types** | "close it" | **REFUSE IT.** Measured: converging Rust's borrow-and-memcmp down to the ports' copy is 2.7x-8x worse; converging to a portable indexed loop is **28x worse at 256 bytes**. Neither direction survives -- the first divergence found in this port that is neither naming nor a missing capability |

**Hole 9 is the finding.** One line of vocabulary, unblocking more functions
than everything else combined, and it went unnoticed for four shipped files
because none of them happened to need a boolean connective. A capability
census taken from the files that DID ship is a census of what those files
needed.

### Port order, re-derived

| # | region | gate | lines across 3 |
| --- | --- | --- | --- |
| 1 | `Pike`: `word_cp`, `space_cp`, `pred_hit` | **hole 9 only**; contiguous in all three, no reorder | 50 |
| 2 | `Seqs`: `rest` | one template, after the `emptyList` convergence | 16 |
| 3 | `Eq`: `cmp_named` | hole 9 + 2 templates | 37 |
| 4 | `Vec`: the whole file, two regions | holes 9, 10 + the header convergence | **~860 net** |
| 5 | `Seqs`: the constructors | hole 10 + convergences | 247 |
| 6 | `Bytes`: two regions | hole 10 + a byte sink + the `(base, n)` convergence | **~880 net** |
| 7 | `Maps`: `merge_two` + the six structural copies | **nothing new** -- 639 lines, algorithm line-for-line identical | 639 |
| 8 | `Table`, `Str`'s rope half, the rest | hole 5, reorders | ~2,600 |

Rows 1, 3 and 7 have shipped, and so has `nodeAssoc`, which this table never
listed. The three analyses put it in a block -- `node_assoc`, `coll_assoc`,
`node_dissoc`, `coll_dissoc` -- and gated the whole block on converging the
hit/found sentinel, because `coll_assoc` returns `Option<u32>` in Rust where
`coll_dissoc` uses `u32::MAX`. That gate is real for the other three. It is
NOT real for `node_assoc`, which never names a sentinel: it calls `coll_assoc`
and passes the result straight out.

So the block was gated on its hardest member. Splitting it shipped the insert
now and leaves the convergence to be paid by the functions that actually need
it -- worth noting because the same shape (a block ranked by its worst
function) is what put `and`/`or` four files late.

`Vec` first among the big ones because it has **no host arrays at all** --
its "arrays" are heap slots and the shadow stack, which `champ.kin` already
paid for.

### What is actually left in `Maps`, counted after the block shipped

The re-ranked table above was derived before any of phase 3 shipped, and its
central claim -- that the assoc/dissoc block was gated on converging the
hit/found sentinel -- turned out to be one line of thought rather than a
capability. So the remainder is counted here rather than estimated.

`runtime/src/map.rs`, excluding `#[test]` functions:

| | functions | lines |
| --- | --- | --- |
| generated | 19 | 634 |
| hand-written, needs nothing new | 26 | 254 |
| hand-written, blocked | 11 | 375 |

The blockers, by what they need:

| need | functions | lines | the big ones |
| --- | --- | --- | --- |
| `match` on a tag | 6 | 236 | `map_assoc`, `map_get`, `map_dissoc` |
| an absent value | 4 | 219 | the same three, plus `am_index_of` |
| a CLOSURE argument | 5 | 133 | `map_for_each`, `node_for_each`, `hash_map_hash`, `map_eq` |

`match` and absence overlap on the three public entry points, which is most of
the remaining weight in one place. The closure group is the genuinely new
capability: `map_for_each` takes a callback, and nothing kin has expresses a
function argument.

A CAUTION ON THIS TABLE. It was produced by pattern-matching Rust syntax, and
that is an estimate too. `node_find` came out as "needs nothing" and does not:
it uses `loop { ... break value }`, which yields a value and has no kin form.
The ports spell the same function with an `out` variable and a plain `break`,
which is the portable shape -- so the function is portable and the DETECTOR
was wrong, not the estimate's conclusion. Treat the counts as a map of where
the weight is, and read each function before porting it.

### `node_find` has a fast path in Rust and in neither port

Rust opens with `if !self.eq_may_alloc(key) { return self.node_find_scalar(...) }`
-- a whole second copy of the walk that skips shadow-stack rooting when the key
is a scalar, which is the common case. `eqMayAlloc` and `nodeFindScalar` exist
in neither port.

This is the `Interns` situation again: a divergence is not evidence the
runtimes should diverge, and the trade-off very likely ports. But which way it
ports is a MEASUREMENT -- either the fast path is worth its second copy of the
walk and the ports should have it, or it is not and Rust should lose it.

**MEASURED: the fast path is worth about 6%, so it ports to the ports.**
20,000,000 scalar-key lookups into a 2,000-entry CHAMP on the native runtime,
two binaries built from the two sources and run INTERLEAVED, minimum of four
rounds each, with the 1.67s compile cost subtracted:

    with the scalar path      2.05s of lookup work
    without it                2.18s
    ratio                     1.063

Modest, real, and reproducible -- the arms do not overlap (3.72-3.76 against
3.85-3.87 wall clock). Six percent of the most common map operation is worth
having in all four runtimes, and because kin generates it there is one source
to maintain rather than the three copies the duplication argument assumes.

#### Two ways this measurement was wrong before it was right

**It measured a dead branch.** The first attempt toggled `eq_may_alloc`
inside `node_find` -- and `map_get` checks `eq_may_alloc` itself and calls
`node_find_scalar` DIRECTLY, so scalar lookups never reach `node_find` at
all. The toggle changed nothing because it could not. The live dispatch is in
`map_get`.

**And it produced a 22% figure that was noise.** Baseline 1.46s, modified
1.78s, three runs each, stable to two decimal places -- and on restoring the
original the baseline came back at 1.80s, not 1.46s. Running all of arm A
then all of arm B measures drift as readily as the change. The fix is two
saved binaries run INTERLEAVED, and comparing minima rather than means.

**And 93% of what it timed was the compiler.** `flint run` compiles before it
runs; a no-op program costs 1.67s of the 1.80s measured. The workload had to
grow twentyfold before the thing under test was most of the number. A ratio
taken without knowing the fixed cost is a ratio of the fixed cost.

### `fixnum` was widening the wrong way on both ports

kin's `I32` is Rust's `u32`. Java and C# spell it `int`, which is SIGNED, so
`Val.fixnum(h)` on a value with the high bit set stores 48 bits of ones where
Rust stores 32. The form now masks -- `Val.fixnum({0} & 0xFFFFFFFFL)` -- which
is what the hand-written ports had all along.

It survived thirteen shipped sources because the only use was a seq index,
which is small and non-negative, so the wrong widening was unreachable. The
first use that could reach it was `cn_new` storing a 32-bit HASH, and the
driver names the case directly: it constructs a node with hash `0x80000001`
and reads it back.

**The same asymmetry bites `I32` more widely, and is not fixed.** A value with
the high bit set prints as `2147483649` in Rust and `-2147483647` on the
ports, and `<`, `>` and `quot` on it mean different things -- unsigned there,
signed here. Equality, the bitwise operators and `>>>` (which the vocabulary
already spells as `hash-mask`) are safe. Nothing shipped compares or divides a
hash, so nothing is wrong today; but the vocabulary offers `<` on an `I32`
without saying it is only sound below 2^31, and a driver that never builds a
high-bit value will not notice.

FIXED, the way `ushr`/`sar` already answers the same question one operator
family over: the subject names `u<`, `u>`, `u<=`, `u>=`, `uquot` and `urem`
explicitly. `kin.lang`'s generic `<`, `>` and `quot` stay, because they are
right for indices, counts and shifts, which is nearly every use; the unsigned
forms exist for the values that can carry the high bit.

`kin/unsigned.kin` pins it. That source ships NOWHERE -- it has no `.targets`
-- and exists only to be verified, which makes `kin/verify` a place to pin a
claim about what a FORM means rather than about what a runtime function does.
Measured with `0x80000001`:

    (u< hi 5)     0 0 0     agrees
    (< hi 5)      0 1 1     DISAGREES -- negative on both ports

The second line cannot live in that file, because a source whose targets
disagree cannot pass. It is the reason the first line is worth pinning.
### Re-counted after the tag work: 133 lines are blocked, not 375

The earlier count said 11 functions and 375 lines were blocked, split across
`match` on a tag, an absent value, and closures. Two of those three were never
blockers, and the count was produced by pattern-matching Rust syntax -- the
same method that had already been wrong about `node_find` once.

`runtime/src/map.rs`, hand-written and excluding tests:

| | functions | lines |
| --- | --- | --- |
| genuinely blocked -- CLOSURES | 5 | 133 |
| needs nothing kin lacks | 23 | 389 |

**`match` on a runtime type was never a blocker.** It is `case`, which kin has
had since `eq.kin` shipped `category`. Dispatching on `ty(...)` is exactly what
that form is for. I labelled it a hole because a regex saw the word `match`.

**An "absent value" was never a blocker either.** `am_index_of` returns
`Option<u32>` in Rust and `-1` on the ports -- which is precisely the
divergence `coll_assoc` and `coll_dissoc` already resolved, twice, by
answering `n`. It also has the `eq_may_alloc` fast path that Rust has and the
ports lack, which is the same convergence `node_find` already made and which
was measured at about 6% of lookup time. Both problems are solved; the
functions holding them were simply never re-examined.

So `map_get`, `map_assoc` and `map_dissoc` -- 209 lines, the three public
entry points, and the largest single block of "blocked" work -- are portable
today with conventions already established and already shipped.

**THIS COUNT HAS AN EXPIRY, and it has already passed.** It was taken before
promises, the link phase and the `:wrap`/`:declare`/`:generate` split landed in
kin. Two censuses before it were wrong in the SAME direction -- over-stating
what was blocked -- because each was taken before a capability shipped and
never retaken after. Re-derive before quoting it; do not carry the number
forward on the strength of having once been careful.

CLOSURES ARE THE ONE REAL HOLE, and the five functions that need them are
exactly the iteration ones: `map_for_each`, `node_for_each`, `hash_map_hash`,
`map_eq`, `map_entry_vector`. A callback argument is a shape kin has nothing
for, and unlike the other two this is not a naming difference or a sentinel
convention -- it is a capability.

The lesson is the one this file keeps recording: a capability census taken by
grepping is a census of what the grep understood. Both false blockers survived
because nothing re-ran the census after the capability that dissolved them
shipped.
### A refer of a NON-EXPORTED name reports the wrong thing

Found porting the read path, and it cost twenty minutes of bisecting.

`mapread.kin` required `flint.rt.mapcore` and referred `am-key` and `am-val`,
which were `^:inline ^:method` and NOT `^:pub`. A namespace's export
vocabulary is built from its public definitions, so referring only private
names produces an EMPTY vocabulary -- and an empty vocabulary has an empty
target set, which intersects with everything to nothing.

The message was:

    flint.rt.mapread generates for NO target. Its vocabularies have no target
    in common. A source that generates nothing is a source nothing checks.

True, and about three steps downstream of the cause. The reader is sent to
look at TARGET SETS when the fault is a name that is not exported.

Two things make it worse than a bad message:

* **bisecting does not isolate it.** Dropping any ONE of the three requires
  still left no common target, because two of them were also referring private
  names. Every subset failed, so the usual halving says nothing.
* **`kin why` reports it for every source.** Asking about `find.kin` printed
  mapread's error, because the project-wide scan fails before any single
  source is answered. The one command built to explain a source cannot be
  aimed at a working one while a broken one exists.

What it should say: `am-key` is defined in `flint.rt.mapcore` but not `^:pub`,
so it cannot be referred. That is a fact kin has at hand -- it knows the name,
the namespace, and that the definition exists without the mark.

Worth fixing in kin rather than remembering: this is the first source to
require three kin namespaces at once, and there will be many more.
### The census has now been wrong three times, in both directions

Recorded because the failures rhyme and the lesson is not "be careful".

| when | said | was | what the regex missed |
| --- | --- | --- | --- |
| before the port | 375 blocked | 133 | `match` and `Option` are solved conventions, not blockers |
| after `node_find` | "needs nothing" | needed a shape | `loop { ... break value }` yields a value |
| after `mapwrite` | 75 portable | 21 | `&mut dyn FnMut` is a closure and the probe matched only `&mut |` |

They go BOTH ways -- twice over-stating what was blocked, once under-stating
it -- so the bias is not optimism or caution. It is that a census by pattern
is a census of what the pattern understood, and the thing it does not
understand is invisible in exactly the same way whichever direction it errs.

The count that has held is the FIRST one: 133 lines, five functions, all of
them needing a callback argument. Every re-count since has either reproduced
it or been wrong. That is worth knowing before the next re-count is trusted
more than it deserves.

The probe now tests six spellings of a callback rather than two, and lists
them explicitly, so the next miss is a form nobody has written yet rather
than one the pattern happened not to cover.

### THE CLOSURE BLOCKER IS REAL: measured, and the buffer shape loses

Found while scoping closures for kin, and it changes the plan.

**Rust iterates a map with a CALLBACK.** `map_for_each(m, state, f)` where `f`
is `&mut dyn FnMut(&mut Rt, Value, Value, &mut S)`, called from `codec.rs`,
`coll.rs`, `conc.rs` and `map.rs`.

**Both ports iterate with a BUFFER.** `entries(rt, m, at)` writes every key and
value onto the shadow stack starting at `at` and returns the pair count; the
caller then loops over `rt.r(at + 2*i)`. Used in `Maps`, `Sets`, `Codec` and
`Conc`.

So the same job is done two ways, and **the ports' way needs no callback at
all** -- it is a loop and a push. If the convergence goes that way, the five
functions holding 133 lines stop needing a capability kin does not have, and
kin does not need closures for this at all.

That is the third time in this port that a "capability gap" has turned out to
be a divergence in approach: `match` was `case`, an absent value was a
sentinel, and now a callback is a buffer.

#### It is a real trade, not a free win, and it wants measuring

`entries` on an N-entry map pushes 2N roots before the caller reads any of
them. `map_for_each` visits in place and pushes nothing. On a large map that
is the difference between a bounded walk and a shadow-stack spike proportional
to the collection.

Against that: the ports already do this, on every map operation that iterates,
and they pass conformance including the gas-parity row. So the cost is being
paid today by half the runtimes and has not shown up as a problem.

What would settle it is a measurement -- peak root depth and time for both
shapes over a large map -- rather than an argument from either side. That is
worth doing BEFORE building closures into kin, because if the buffer shape
wins, or ties, the capability is never needed and the 133 lines are portable
now.

#### The measurement: `runtime/examples/iterbench.rs`

Both shapes in ONE runtime, over one map. Comparing native-callback against
JVM-buffer would have compared the approach and everything else about the two
runtimes at once, so `map_entries` -- the ports' shape, transcribed -- was
added to the Rust runtime beside the callback it competes with. Both are behind
`--features bench`, ABSENT from the shipped runtime: the buffer shape lost, and
the code is kept only so the number stays re-runnable.

Three shapes, because the first mirror was unfair to the buffer. The ports root
each node handle and slide the whole subtree down over it; iteration allocates
nothing, so no collection can happen mid-walk and that handle never needed
rooting at all. `buf/noslide` is the buffer's honest best case.

A bare walk allocates nothing, so 2N live roots cost nothing there and the walk
alone would have flattered the buffer. The row that decides is **entry-vector**
-- `map_entry_vector`, which allocates an entry and a conj per key with the
whole buffer live and scanned at every collection. That is where the roots are
actually paid for.

    map of 200 000 entries          time        peak roots
    callback                      20.7 ms                8
    buffer (as the ports write it) 40.7 ms          400 001
    buffer, slide-free            29.1 ms          400 001

    entry-vector, the allocating workload, 5 runs
    via callback                 176.5 ms
    via buffer                   223.5 ms   1.24x

Stable across repeats (1.238 / 1.244 / 1.261 at 200 000; 1.061 / 1.070 / 1.073
at 50 000). **The buffer does not win and does not tie.** It is 1.07x behind at
50 000 and 1.24x at 200 000 -- and the gap WIDENING with size is the signature
of the cost being looked for, since a constant-factor loss would not grow.

So closures are load-bearing after all. The 133 lines stay hand-written until
kin can express a callback, and kin should grow that capability -- now because
a measurement asked for it, not because a census said five functions were
blocked.

Two things fell out of building it:

* **The ports' slide-down is unnecessary ceremony.** Removing it took the bare
  walk from 1.97x to 1.31x -- about half the buffer's overhead is the slide,
  not the buffering. That is a portable improvement to `Maps`, `Sets`, `Codec`
  and `Conc` on both ports, independent of kin and independent of this
  decision, since the ports are going to keep the buffer shape until kin can
  replace it.
* **`0031` enforced itself.** The first draft left the map in a Rust local
  across the entry-vector rounds, which allocate, and got an empty vector back.
  The rule is not a style preference.

### What is left in `Maps`, and it is one capability

    closure-blocked   5 fns  133 lines   map_for_each node_for_each
                                         map_entry_vector map_eq hash_map_hash
    portable          7 fns   21 lines   the champ_* wrappers, 3 lines each

The closure-blocked five are now blocked on MEASURED grounds, not census
grounds -- see the iterbench section above.

The portable remainder is seven delegating wrappers -- `champ_find` is
`node_find(root, 0, h, key)` and the rest are the same shape. Porting them
would buy one definition each and cost the usual boundary work at every call
site. Worth doing, and worth doing LAST: it is the only work left here that
does not need a new capability, and the capability is what actually unblocks
the file.
### Never, on evidence

* **`Snap`** moves to phase 5. 785/449/456 lines of direct `Gc`, `Roots`,
  `Frame` and raw-memory access -- the list phase 5 already names. Its 1%
  jvm-clr delta is the purest example of the old table's failure.
* **`Seqs.force`** -- the most alike function in the file, and it must not be
  ported. `0037` says the park fix turns it into a re-entry state machine.
  Generating it now would freeze a shape known to be about to change.
* **`Str`'s constructor half** -- five stacked blockers, and two Rust
  functions there do not exist in the ports at all.
* **`Eq.utf16_cmp`** -- Rust walks an iterator over `&str`; the ports index a
  host `String`. Rust has no host `String`. The languages genuinely refuse
  each other's form.



Numbers are the codec spike's ranking, kept so the references in this file
stay meaningful. 1 and 8 are done.

| | hole | what it blocks |
| --- | --- | --- |
| 2 | no array subject | `grow`, and any file that allocates or copies an array |
| 3 | `case` arms cannot be statements | `decodeAt`'s fourteen arms, the opcode switch, `Pike` |
| 4 | no test for absence (`Option` against null) | `Interns::lookup`, and every partial function in `Maps`, `Vec`, `Table` |
| 5 | no string building | every error message, and most of `Str` |
| 6 | no type parameter on a generated type | anything holding a borrow: `Reader<'a>`, and the same shape in `Str`/`Bytes`/`Snap` |
| 7 | `^:method` cannot say "instance method on all three" | `Rt.isSeq`, and most of what lives on `Rt` in the ports. Also wants per-target visibility, `pub` in Rust against package-private on the JVM |

### Not this project

| | item |
| --- | --- |
| A | the `/goal` hook still names `doc/goals/splint-port.md`; this file moved |
| B | the lazy-seq park crash, documented with a repro in `0038` |

## Rules that govern this, each learned by breaking it

* **Generated code may not be worse or slower than the hand-written code it
  replaces.** If it is, adjust the rules or hand-write that part. This has
  already caught four things: a loop rewritten when it did not need to be,
  hoisting where `rustc` did not require it, `x = (x + 1)` for `x += 1`, and a
  parenthesised ternary.
* **…but CORRECT outranks TIDY, and that is not theoretical.** Stripping
  redundant parentheses from call arguments produced
  `u32(o, (int) n >>> 32)` — which casts and then shifts — where the parentheses
  had been carrying the precedence. An aesthetic rule silently changed the
  semantics. Paren-stripping is now allowed only on a whole assignment
  right-hand side, where there is nothing to bind with.
* **Coverage before regeneration.** Ask what would notice a mistake before
  making it. `list` — the opcode with the most interesting divergence — had zero
  cross-runtime coverage.
* **Treat a divergence as incidental until somebody can point at the language
  refusing the alternative.** Two were checked; both looked principled, one had
  a comment explaining itself, and neither survived. kin's job here is to
  CONVERGE the runtimes, not to encode differences nobody chose.
* **One distinct local name per function, because the strictest target sets
  the rule.** C# refuses a name reused in an enclosing scope (CS0136) where
  Rust shadows freely and Java allows sibling scopes. A `vv` inside a loop and
  another outside it compiles twice and fails once. Distinct names read better
  anyway, so this costs nothing to obey.
* **An escape is the right backstop and the wrong habit.** `base` and `out`
  are keywords in C# and ordinary locals in Rust and Java. kin escapes them to
  `@base`/`@out`, which compiles -- and shipped into `Seqs.cs` beside
  hand-written code that renames to `bas` and `outv`, so a reader met two
  conventions in one file. The sources now use names that collide nowhere.
  Keep the escape for the case nobody foresaw; do not lean on it.
* **Generated code can name a type the HOST file does not import.** The seq
  constructors emit `let a: Addr = ...`, and `seqs.rs` had no `use
  crate::mem::Addr`. `kin/verify` cannot see this -- its driver supplies its
  own mocks -- and neither can the region, because an import is a fact about
  the host. Only the host build finds it. A third thing the gates catch that
  nothing upstream of them can.
* **A region's boundary is a CLAIM about what lies between two functions, and
  it must be checked against the previous commit rather than against a
  compiler.** Carving `Interns` from `mask` to `grow` silently deleted four
  declarations -- Java's `Match` and `Refresh` interfaces, C#'s `Match` and
  `Rehome` delegates -- because they sat between functions and a region takes
  everything in its span. Rust's region had no interleaved declarations, so
  `cargo check` passed, and I read that as the carve being clean: ONE TARGET
  VERIFIED AND THREE ASSUMED. `kin/verify` cannot catch it either -- it
  compiles the GENERATED code against a mock, so a declaration deleted from
  the HOST file is invisible to it. Verify proves the region is right; only
  the host build proves the carve is. The check that works is a diff of the
  declaration set against `HEAD`.
* **Verified or reverted.** Four attempts at the park bug were written, measured,
  and reverted rather than left in the tree as unverified interpreter changes.
* **Measure a generated function as a LIBRARY, never as a binary.** A `main`
  that calls each form once on constants lets rustc specialise both against
  that call site, and the answer is about the benchmark. This is not
  hypothetical: it is how the reader came to be recorded as 1.5x FASTER
  generated when it is 1.5x slower, and the wrong number sat in this file
  licensing a port that measurement refuses.
* **Compare against the function you would actually replace.** The same wrong
  entry measured `HostReader`, which no port would touch, instead of the guest
  `Reader` that a port would.

## Known hazards

* **Rust carries more rules than the other two.** Hoisting, `mut` inference,
  numeric casts, assignment-to-a-place — all Rust-only, all in the vocabulary.
  Expect each new file to surface one or two more.
* **`:require` resolution is real but the driver wires vocabularies directly.**
  Making it load them properly is unfinished, and is the same question `0036`'s
  namespace resolver answers.
* **A form that must emit a statement while being used as an expression** has
  `kin-emit-anchor!` and nothing else. Untested beyond hoisting.
* **The generated snippet in `vm.rs` is a snapshot.** Regenerate with
  `bb kin/run.clj` when the vocabulary changes; nothing does it automatically.
