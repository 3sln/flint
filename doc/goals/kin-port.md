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

**HOLES 9, 10 AND 11 ARE CLOSED**, and this table went on ranking them
highest for four more shipped files. They were paid for by the work that
needed them -- `and`/`or` is `kin/src/kin/lang.cljc:160`, and `alloc`, `Addr`,
`NIL`, `is-fixnum` and the sibling call are all in `flint/impl/rt.cljc` -- and
nothing updated the list that says what is blocked. A blocker list nobody
retires entries from reads exactly like a blocker list, which is how `and`/`or`
went unnoticed in the other direction: this file is wrong in both directions
for the same reason, and the fix is to write the closing down when it happens.

**HOLE 5 IS CLOSED TOO**, by `str-cat` in `flint/impl/rt.cljc` and
`kin/nouns.kin` as its first user. It needed nothing from kin: a form is
`(fn [ctx form] ...)`, the shape `for` and `while` already had, and the
variadic Rust side builds its `format!` string from the argument count. The
capability had simply never been written, which is a different thing from
being blocked and was recorded as the second for four files.

**NO CAPABILITY GATE REMAINS.** 9, 10, 11 and 5 are closed; 6 is refused on
measurement; 2, 3 and 4 are small and were always mis-ranked. What is left in
rows 6 and 8 is porting work, not waiting.

One thing hole 5 did NOT buy, and the number is the reason. Rust's `seq`
refusal still does not name the value: calling the new `describe` from it
costs **2 935 bytes in every shipped module** against **1 220 bytes** of
headroom under the 304 000 floor. Unused, `describe` costs nothing -- 302 780
either way, the shaker removes it whole -- so the capability is in the tree at
zero cost and only the CALL is a budget decision. It wants one.

| hole | this file claimed | measured against Rust | now |
| --- | --- | --- | --- |
| **9 (new) `and` / `or`** | absent | **15 functions** in `Seqs`+`Eq`+`Pike` alone, plus every `&&` guard in `Maps`, `Vec`, `Str`. Cost: **one line** -- `'and "&&" 'or "\|\|"`, spelled identically on all three | **CLOSED** |
| **10 (new) allocation** | absent | **~2,000 lines.** `alloc`, `heap`, `fixnum`, a `NIL` name, an `Addr` tag. Every constructor in every file | **CLOSED** |
| **11 (new) cross-module calls** | absent | **11 functions.** Rust reaches a sibling through `self`; the ports through a class-qualified static taking `rt`. One pattern, not one decision per function | **CLOSED** |
| 5 string building | mid | **13 in `Table`**, 0 in `Maps`, 0 blockers in `Seqs`/`Eq`/`Pike` | **CLOSED** |
| 2 arrays | "the real gate on 4,500 lines" | **2 functions** in `Maps`+`Table`. And the array-of-`Value`s half **is not needed at all** -- converging node construction to `(base, n)` on the shadow stack removes it from eight functions, measured 5.5x faster in Rust at 2 kids | open |
| 3 `case` statements | "every dispatch" | **1 function** in `Maps`+`Table`, **0** in `Str`/`Bytes`/`Vec`, 5 in `Seqs`+`Eq` | open |
| 4 absence | "every partial function" | **1** in `Table` (the others are drift), 3 in `Seqs`+`Eq`+`Pike` after converging | open |
| **6 borrowed types** | "close it" | **REFUSE IT.** Measured: converging Rust's borrow-and-memcmp down to the ports' copy is 2.7x-8x worse; converging to a portable indexed loop is **28x worse at 256 bytes**. Neither direction survives -- the first divergence found in this port that is neither naming nor a missing capability | **REFUSED** |

**Hole 9 is the finding.** One line of vocabulary, unblocking more functions
than everything else combined, and it went unnoticed for four shipped files
because none of them happened to need a boolean connective. A capability
census taken from the files that DID ship is a census of what those files
needed.

### The `Vec` header convergence was a defect in both ports

Row 4 lists "the header convergence" as a prerequisite. Looking at what it
actually was, before writing any kin:

**A vector was six slots on native and five on both ports.** Rust's `TY_VEC`
has carried `V_HASH` -- a cached hash, `nil` until asked for -- since vectors
were written. Neither port allocated the slot, so neither could cache, so
`hash` of a vector walked all of it EVERY time. The ports' own `hashValue`
makes the argument two cases above the one that ignored it: *"a rope used as a
map key must not rehash every lookup"*.

**No gate could see it.** The snapshot check compares the two ports against
each other byte for byte, and native is not in that comparison -- so the two
ports agreed with each other while both disagreed with native, which is
exactly the reading a same-image baseline is supposed to prevent. The lesson
is not about vectors: **a conformance check between two of three runtimes is a
check that they were written from the same misreading.**

Converging it -- slot 5 on all three, cached and read the same way -- moved
the conform gas row from `109292 native, 109291 jvm (within 1)` to `109292
native, 109292 jvm (within 0)`. The one-step gap had been sitting under a
tolerance the whole time, and it was this: native charged for one walk where
the JVM charged for two. A within-1 tolerance is a place a real divergence can
hide, and this one did -- so the tolerance is gone. The row was 1% of the
native figure, about 1 090 steps of slack, held over from when the ports'
map allocated more per entry than the Rust CHAMP did; the kin CHAMP closed
that and nothing tightened the bound behind it. It is exact now, which is
how the two ports have always been compared to each other.

### The same defect class, twice more, both under the same coverage hole

Looking for `Vec`'s prerequisites turned up two more divergences of exactly
the `V_HASH` kind -- native and the ports disagreeing about an object's
shape, with no gate able to see it.

**`nodeClone` had a `max(n, 1)` floor on both ports and not on native.** The
only caller that can ask for zero is persisting an EMPTY transient, where
`cnt - tailOff` is 0, so the ports allocated a two-slot tail node where native
allocates one. `newEmpty` on the same port already builds a zero-length tail,
so the two ways of reaching an empty vector disagreed with each other on one
runtime as well as across runtimes.

MEASURED, not read: a probe on that line fired exactly once, on `(persistent!
(transient []))`. It fired zero times across the entire conformance suite --
**because the suite contained no transient at all.** `collections.cljc` had
`conj`, `assoc`, `get`, `count`, `seq`, `nth` and not one `transient!`,
`conj!` or `persistent!`. That zero was a zero of coverage, and taking it at
face value is precisely the mistake the opcov census made twice.

The suite has transients now -- vector, map and set, the empty case first
because it is the one that was wrong, then the boundaries the trie turns on
(32, 33, 1025).

**`pop!` did not work on a transient, on any runtime -- and the ports had no
vector `pop` at all.** `clojure.core` had `(defn pop! [t] (flint.rt/pop t))`,
the PERSISTENT pop, so on a transient vector it fell through to the list
branch and answered `()`. `tvec_pop` was implemented on native and reached by
nothing but its own unit test.

Chasing it found the larger half. Neither port had `popTail`, `pop` or `tpop`
in `Vec`: the `pop` builtin REBUILT the vector with a conj loop, O(n) where
native unwinds the trie in O(log n). Same answer every time, so no gate could
notice -- a conformance suite compares ANSWERS, and this divergence is only
visible in the shape of the work.

FIXED, all three runtimes: `popTail`, `pop` and `tpop` ported to both ports
against the Rust, a `pop!` builtin added in four places (`builtins.rs`,
`flint.rt`, `hostfns`, the unit manifest) and both ports' builtin tables, and
`transient_pop` guarding the empty case -- `cnt` is a `u32`, so `cnt - 1` on
an empty transient wraps to `u32::MAX` in release and gets written back as the
count.

The conformance rows are in now that they mean something, at the boundaries
where the trie changes shape: 33 -> 32 empties the tail and pulls the previous
leaf back out, 1025 -> 1024 drops a level.

### `ushr` and `sar` are named in three comments and defined by no subject

`kin.lang` deliberately refuses to carry `bit-shift-right`, because which
shift is correct depends on the tag's signedness in Rust, where the type
chooses and the operator does not. It says a subject vocabulary names `ushr`
and `sar` explicitly. `flint.impl.rt` says the same thing twice more.

None of the three is true: neither form exists. `shl` is there alone, with a
comment explaining the absence of its counterpart as though the counterpart
were elsewhere. Nothing has needed a right shift yet, which is why it held --
`Vec` needs one in `tailOff`, `arrayFor`, `newPath`, `pushTail` and `popTail`,
which is nearly every path in the file.

WRITTEN, and its shape was set by a use rather than guessed. Neither takes a
tag, which is worth saying because the comparisons beside them do: each
template is already right for both tags. `ushr` is Rust's `>>` because its
host type is `u32` for `I32` and `U32` alike, and Java's `>>>` whatever the
high bit holds. `sar` is Java's and C#'s plain `>>` because they spell `int`,
and in Rust has to go through `i32` and back because the OPERATOR does not
choose there. So the tag decides nothing at the use; the target decides, once.

### The first `Vec` slice ships: `vecnode.kin`

The node layer -- `node-len`, `node-get`, `node-set`, `node-edit`, `new-node`
-- five one-line functions that were written three times, each carrying the
same off-by-one, because slot 0 of a node is the transient ownership token and
the children start at 1. Generated once now.

Getting there needed three things that were not on the list:

* **`TY_NODE` was not in the tag table.** Every other vector-adjacent tag was.
* **The CLR spelled `Vec`'s constants differently from its own `Maps`** --
  `Bits`, `Width`, `Mask`, `VCnt` .. `VHash` against `HM_ROOT` and `AM_META`
  two files away. One runtime disagreeing with the other two AND with itself
  is worth less than C# casing convention, so the CLR was renamed and the
  constants added to the agreement table.
* **The driver fixture disagreed with the real `olen`.** It took an `Addr` in
  the fixture and a `Value` in all three runtimes, so the source compiled in
  the probe harness and did not compile in the runtime. Third time a
  fixture/reality mismatch has cost a cycle; the fixture is a claim about the
  runtime's signatures and is only as good as the last time it was checked.

`node-set` is the one statement in the layer and confirmed kin emits a void
function cleanly -- `pub fn ... ()`, `static void`, `static void`.

### `^:pub` on a previously-private function costs bytes in every module

`vecread` shipped 285 bytes over the floor budget -- 304 025 against a 304 000
gate -- and the whole of it was four characters of mark.

`vec-shift`, `tail-off` and `array-for` are private `fn`s in Rust. Marking them
`^:pub` makes them `pub` where kin's default is already `pub(crate)`. The
runtime grew 283 bytes and the floor module 285; dropping the mark recovered
all of it and four bytes besides: 303 736, against 303 740 before the slice.

**The effect is measured; the MECHANISM I first gave for it was wrong.** This
file said "three extra public symbols are three the linker can no longer
internalise". A later measurement refutes that: `is_seqable` was a `pub fn` on
`Rt` with ZERO callers anywhere in the repo, and deleting it changed the floor
module by exactly 0 bytes -- so an unreferenced public symbol is already being
shaken out, and symbol retention cannot be what the 285 bytes were.

What it actually is remains open. The likeliest candidate is cross-module
inlining -- `pub(crate)` lets a call be inlined and folded where `pub` leaves
a real call and a surviving body -- but `#[inline]` recovered zero bytes when
tried, which does not fit that either. The rule stands on the A/B, which is
reproducible; the explanation does not, and is recorded as unexplained rather
than left as a plausible-sounding sentence that happens to be false.

`^:pub` means part of the CRATE's public API. It does NOT mean "something
outside this file calls it" -- kin's default already covers that, which is the
whole reason the default is `pub(crate)` and not `fn`. Nothing is lost on the
ports: a generated module is its own package and Java has nothing between
package-private and public, so Java emits `public` either way, and C# gets
`internal` within one assembly.

`#[inline]` was the first hypothesis and it was WRONG -- the two hot readers
had carried it and this file had dropped it, so restoring it looked obviously
right, and it recovered exactly zero bytes. It is kept because it is faithful
to the original, not because it bought anything. Measuring the fix mattered as
much as measuring the regression.

**Swept, and the compiler answered the question better than reading would.**
Of 43 marks, 17 are load-bearing because another kin namespace `:refer`s the
name -- a require of a non-`^:pub` name does not resolve. The other 20 were
candidates, so all 20 were dropped at once and the build was asked which it
minded.

It minded EIGHT, and it took three rounds to find them all, which is the more
useful half of the result.

* `cargo build` objected to one: `map-assoc`, from `units-src/flint-data-xml`
  and `units-src/flint-data-html`, which are separate CRATES.
* The gates found four more: `is-map`, `is-array-map` and `map-get` from
  `runtime/tests/vm.rs`, `hash-long` from the CLR conform harness. Integration
  tests are their own crate and `cargo build` does not compile them, and
  `runtimes/clr/conform/Conform.csproj` is a second ASSEMBLY, so C#'s
  `internal` does not reach it. The premise that the CLR was one assembly was
  simply wrong.
* A third round found three: `map-dissoc`, `lazy-seq` and `range`, the same
  way. A compiler stops early, so each round only reveals the first few.

The remaining 12 came off, 43 marks down to 31. The floor module went 303 736
-> **303 605**: 131 bytes saved in every program that ships, on top of the 285
the slice itself had cost.

Three things worth keeping. Reading would not have sorted them -- twelve of
twenty looked exactly like the eight. Dropping all of them and letting the
toolchain object is a cheaper and more reliable oracle, and it is available
only because the mistake is a compile error rather than a silent one. But
`cargo build` is NOT that oracle: it caught one of eight. And iterating on
compiler output converges slowly, because each round shows the first errors
only -- reading the HARNESSES for every generated name they reach finished in
one pass what three build rounds had not.

### The module budget measures the PREVIOUS run's runtime

`test/threads.clj` runs at `bin/test` line 76. `bin/build-dist`, which is what
produces the `dist/flint-runtime.wasm` the floor module links against, runs at
line 195. So the size gate measures the runtime built by the LAST invocation of
`bin/test`, not the tree in front of it.

MEASURED: the runtime on disk was 763 323 bytes and rebuilding it from the same
tree gave 763 606 -- 283 bytes of drift, which is precisely the regression the
gate was supposed to be reporting.

The effect is that a change which grows the module passes on the run that
introduces it and fails on the next, unrelated one. That happened here, and it
sent the first round of attribution at the wrong commit: the failure surfaced
during `vecread` while appearing to belong to `vecnode`.

### `nth`'s absence: the convergence is `map-get`'s, and the census says so

`vec_nth` answers `Option<Value>` in Rust and `NOT_FOUND` on both ports. It was
left out of `vecread.kin` because converging it is a decision with a blast
radius, not a line of porting. Here is the radius, counted rather than
estimated -- 95 call sites in Rust:

    70   .unwrap_or(NIL)     or .unwrap_or(dflt)
    19   .unwrap()           the caller has already bounds-checked
     2   assert ... == None  two unit tests
     4   structural          .and_then, .map, .unwrap_or_else, one `match`

**89 of 95 want "the value, or a default".** That is not an `Option` being
used as an `Option`; it is a default spelled 89 times at the call site.

The first count of this was WRONG and worth recording as a hazard: the regex
`vec_nth\([^)]*\)` stops at the first `)`, so every nested call --
`vec_nth(self.r(vi), i)`, which is most of them -- had its suffix mis-read.
It reported a category of 31 "bare" uses that do not exist. Balanced-paren
scanning gives the table above; the shape of the answer changed completely.

So the convergence is the one `map-get` already made, and it is DONE:

    vec-nth(rt, v, i, dflt)   ->  the element, or `dflt` when out of range

`map-get` is a shipped kin function with exactly this signature, and matching
it means the two lookups in the runtime answer absence the same way rather
than each having its own idea. Absence stopped being a hole and became an
argument, which is what let both `nth`s be ported at all.

**218 call sites across four runtimes**, and all but six were mechanical: 97
in Rust rewritten by a balanced-paren transformer, 59 on the JVM and 62 on the
CLR given an explicit `Val.NOT_FOUND` so that every one behaves exactly as it
did before. `tvec_nth` came with it -- the same divergence one level down, and
one decision covered both.

The six that were not mechanical are the interesting ones, because each had a
reason to be structural:

* `vm.rs` and `coll.rs` must RAISE on an out-of-range index rather than answer
  nil, and `coll.rs` additionally has to tell "out of range" from "the element
  is nil" because the caller's own default may be nil. Both pass `NOT_FOUND`
  as the probe -- the one value that cannot be an element -- and compare.
* `builtins.rs`'s `vec->bytes` had an `and_then` that read as being about the
  index and was actually about `as_i64` failing on a non-integer element. The
  loop is bounded by the count, so the default is unreachable there.
* Three test assertions of `None`.

Two things the toolchain had to tell me, both about crate boundaries the
transformer could not see:

* `runtime/tests/vm.rs` is its OWN crate, so `crate::value::NIL` in a rewritten
  call resolved to the test crate rather than the runtime.
* `units-src/flint-conc` has its own tests calling `vec_nth`, and the sweep
  had been scoped to `runtime/`. `cargo build` did not catch it -- again --
  because integration tests of another workspace member are not built by it.
  `bin/test` did.

It took FOUR rounds to find them all, and each round was a different corner
the previous sweep could not see:

    cargo build             the runtime and its callers
    cargo test              runtime/tests/vm.rs -- its own crate
    bin/test                units-src/flint-conc/tests -- another member
    --features diagnostics  runtime/tests/portrace.rs -- only built with it

Plus one `.expect(...)` the transformer had no case for, which the compiler
named. Rewritten as an explicit `assert_ne!` against `NOT_FOUND`, because the
point of that check is that the vector HAS an element -- an absent one must
fail the test rather than become a nil that reads as a legitimate answer.

Worth stating as a rule, because it is the same rule three times now: a
signature change is REPO-WIDE by default, `cargo build` is not the oracle for
one, and a feature-gated test file is invisible until the feature is on. The
way to find them is not a better grep, it is to make the change and let every
gate object -- which works only because this one is a compile error.

### `verify` was killing the Rust compiler with its own log

`vecwrite` verified green on Java and C# and produced NOTHING on Rust -- no
binary, no error text, an empty answer, and a "the targets disagree" failure
that named no cause.

The line is `rustc -O -o probe.rust gen.rs 2>&1 | head -20`. A probe fixture
is deliberately partial, so it is full of unused-method and unused-mut
warnings. Once those passed twenty lines, `head` closed the pipe, rustc took
SIGPIPE, and it died before linking. The compiler was being killed by its own
log, and the failure looked exactly like a source that does not compile.

`-A warnings` fixes it, and `head` stays: it is a bound on a REAL error, and
silencing the noise is what keeps a real error inside the bound. Worth noting
the first diagnosis was wrong too -- "the error scrolled past" -- and the give
away that it was not is that there was no error text at all.

### The write path ships: `vecwrite.kin`

`new-vec`, `new-path`, `push-tail`, `vec-conj`. 117 lines out of `vector.rs`,
102 out of the JVM and 113 out of the CLR.

`push-tail` takes NO `edit`. Rust carried one and threaded it through the
recursion; every caller passed nil, because the transient half has its own
`t-push-tail` that does the owned-node work. Both ports had already dropped
it. A parameter with one possible value is not a parameter.

One hoist for Rust's borrow checker: `node-set` takes `&mut self` and so does
`new-path`, so nesting the call in the argument list is two mutable borrows.
Every target reads the hoisted form fine.

`conj` KEEPS its old name on the ports, as a one-line delegation to the
generated `vecConj`. 53 call sites per port use `Vec.conj`, in files that have
nothing to do with vectors, and renaming them is the same trade this file
already recorded against the `champ_*` wrappers and answered with "worth doing
LAST".

`^:pub` came back on `vec-shift`, `tail-off` and `node-clone`, which the sweep
two commits ago had taken off. `vecwrite` `:refer`s them, and a require of a
non-`^:pub` name does not resolve. So the rule is not "these were private in
Rust" -- it is "nothing outside this module needs the name", which is a fact
about the port as it stands and changes as the port grows.

That is also where kin's misleading diagnostic bit for the third time: the
first attempt reported *"flint.rt.vecwrite generates for NO target. Its
vocabularies have no target in common"* when what had happened is that
`vecread` exported nothing being referred. The per-SYMBOL message is fine --
"flint.rt.vecread has no node-clone to refer" -- so the bad message is
specifically the case where a required namespace exports nothing at all.

### Every probe fixture was lying about absent slots

The real allocator NIL-fills a value slot: `gc.zero_body` writes `Value::NIL_`
into every one. The fixtures in `kin/*.drivers` left them at 0, and NIL is
`0xFFFF` -- so **"this slot is absent" never read as absent** in any probe.

`vec_pop`'s level collapse is guarded by "the root's second child is nil".
Under the old fixture that was never true, so the collapse silently never
fired, `pop` of a 1025-element vector kept a shift of 10 instead of dropping
to 5, and ALL THREE TARGETS AGREED. That is the one failure a three-way
comparison cannot catch: a fixture that lies lies identically everywhere.

Six fixtures had an allocator and none of them filled -- `collnode`,
`mapcore`, `nodeclass`, `vecnode`, `vecread`, `vecwrite`. All six now do, and
all six still verify with UNCHANGED output, which is worth stating: their
conclusions were sound, they were just being reached for a weaker reason than
they looked.

It also exposed dead coverage. `vecwrite`'s driver conjed to 1100 elements,
and every one of those lands under child 1 of the root, so `push-tail`'s
absent-child arm -- the one that builds a fresh subtree with `new-path` -- was
never reached. It could not have been: the arm tests for a nil child, and
nothing read as nil. The driver goes past 2048 now, where a fresh subidx
appears, and `wrong=0` still holds across all three.

The general shape, and it is the third time this port has hit it: a fixture is
a CLAIM about the runtime, and it is only as good as the last time somebody
checked it against the runtime. `olen` took an `Addr` where the runtime takes
a `Value`; `schema_len` took `&mut self` where the runtime takes `&self`; and
now `alloc` left slots at zero where the runtime fills them with NIL. The
first two were compile errors in the runtime and cost a cycle each. This one
compiled everywhere and produced a green result that meant nothing.

### `vecassoc.kin`: assoc and the pop family

`do-assoc`, `vec-assoc`, `pop-tail`, `vec-pop`. 131 lines out of `vector.rs`
and 115 out of each port.

`vec-conj` is STUBBED AS A MARKER in the driver rather than reimplemented.
`vec-assoc` routes `i == cnt` to conj, and that routing is what this source is
responsible for; rebuilding conj in the fixture would have tested the fixture.
The driver asserts the marker comes back.

Vectors are built directly by the fixture rather than by conjing, for the same
reason -- conj belongs to a required namespace. That is what made the level
collapse testable at 1025 without depending on anything `vecwrite` does.

### `Vec` is done bar the two `nth`s

`vectrans.kin` (the transient's plumbing) and `vectwrite.kin` (`conj!`,
`assoc!`, `pop!`, `persistent!`) take the last 256 lines out of `vector.rs`,
211 out of the JVM and 209 out of the CLR.

    file                      before   after
    runtime/src/vector.rs      1 040     461   (and ~290 of that is tests)
    jvm Vec.java                 478     155
    clr Vec.cs                   469     136

Seven kin sources, 802 generated lines across three targets, from one source
each. What is left hand-written is small and named: `is-vector`,
`is-vector-like`, `map-entry-as-vec`, `vec-count`, `vec-root`, `vec-tail`,
`vec-from-roots`, and the two `nth`s.

**Both `nth`s are the same blocked thing.** `vec_nth` and `tvec_nth` each
answer `Option<Value>` in Rust and `NOT_FOUND` on the ports, and the
convergence -- a `dflt` argument, as `map-get` already has -- is one decision
covering both.

The ports keep their old names as ONE-LINE DELEGATIONS to the generated
bodies: `Vec.conj`, `Vec.assoc`, `Vec.pop`, `Vec.tconj`, `Vec.tassoc`,
`Vec.tpop`, `Vec.tpersistent`, `Vec.transientOf`, `Vec.newEditToken` and the
rest. That is 53 call sites for `conj` alone, in files that have nothing to do
with vectors; renaming them is the trade this file already answered with
"worth doing LAST".

Two fixture notes, both the same lesson one more time. The C# probe hit CS0136
-- a local named `ret` in two nested scopes -- which is the same rule that
made `mapread` name its scalar count `sn`. And a chained rename in the script
that builds the C# fixture turned `TvecShift` into `TVecShift` and then
`tVecShift`; the compiler caught each one, which is the only reason
generating fixtures by transformation is safe at all.

### `count-hint`: the ports were right about the type and wrong about the name

`seqcore.kin` ships `count-hint` and `cons`. The convergence in it is small
and is a good example of the shape these keep taking.

Rust answered `Option<u32>`; both ports answered a VALUE, fixnum or nil. The
ports were right, and for a reason visible at the call site: the answer goes
straight into a cons slot that holds a fixnum or nil, so the `Option` was
being unwrapped into exactly that, one line later.

But the ports also folded the `+ 1` in -- `countHint` answered the count of
the cons being BUILT, not of the value handed to it. That reads fine while
`cons` is the only caller, and it is wrong for `seq_count`, which wants the
value's own count and is why Rust had not folded it. So the type came from
the ports and the meaning from Rust: `count-hint` answers `v`'s own count, and
`cons` adds one.

Neither side had it right whole. That is worth recording because the previous
convergences all went one way or the other; this is the first where the answer
was a piece of each.

The CLR's cons slots were `CFirst` .. `CCount` -- the same PascalCase
divergence its `Vec` constants had, renamed for the same reason.

`is-fixnum` joins the vocabulary beside `nil?`. Needed wherever a slot holds
"a number or nothing", which is a cons's cached count and a vector's cached
hash, and it was missing.

### `verify` called three crashed probes an agreement

`is-seq` verified green with all three targets printing NOTHING. Every one of
them threw, so every one printed an empty string, so all three "agreed" and
the harness said `ok 3 targets, one source, identical output`.

That is the same defect `bin/check-builtins` had and `conform` had: silence
and success are indistinguishable unless something is built to tell them
apart. `verify` now fails on an empty answer, naming the target, and the check
runs BEFORE the comparison because after it the three are equal.

Proved by breaking it on purpose -- a `panic!` in the Rust probe -- and
checking the exit code, not just the message: 1 when a target crashes, 0 when
green. A gate that has never been seen to fail is a gate nobody has checked.

The crash itself was a fixture lie of the now-familiar kind: `is_heap` was
`v >= 100`, and NIL is `0xFFFF`, so `is_seq(NIL)` read off the end of the type
array. NIL is not a heap value in any real runtime.

### `is_seqable` was dead on all three, and cost nothing

Zero callers anywhere in the repo -- the only match for the name is a TEST's
name -- and it does not exist on either port. Deleted.

The measurement is the interesting part: removing it changed the shipped floor
module by **0 bytes**. Which refutes the mechanism this file gave for the
`^:pub` finding two slices ago. "Three extra public symbols are three the
linker can no longer internalise" cannot be right, because an unreferenced
public symbol is evidently shaken out. The 285 bytes are real and
reproducible; the explanation was not, and has been corrected to say so.

It is still deleted, on a different ground than cost: a function that exists
on one of three runtimes and is called by none is a divergence with no
benefit, and leaving it means the next capability census counts it as work.

### `first` has FOUR divergences, and one of them resisted converging

`first` is the next thing in `Seqs` and it is not one port away. Rust and the
JVM differ in four places at once:

| | Rust | the ports |
| --- | --- | --- |
| a map entry | `seq` makes a vecseq over the ENTRY, and `first` reads it | `seq` copies the entry into a two-element VECTOR first |
| the default arm | answers nil | throws `UnsupportedOperationException` |
| a string seq | `char_at_byte` | `Str.nth` |
| out of range | `vec_nth(.., NIL)` | `Vec.nth(.., NOT_FOUND)` |

The map-entry one looks like the clear win: the ports allocate a two-element
vector on every `seq` of a map entry, which is every entry of every map
walked, and the native runtime allocates nothing -- it rides the same vecseq
and reads the entry directly, at the cost of one tag check in `first` and one
in `next`.

**It was tried, and REVERTED.** Converging both ports onto Rust's shape --
`seq` of an entry makes a vecseq over the entry, `first` and `next` know the
tag -- broke exactly one row of the `tables` suite: `build-eq`, which is
`(= T (build S (range 600) row))`, false on the JVM where native says true.
Deterministic across runs.

It does not reproduce in isolation, and any perturbation hides it. A
standalone program doing the same comparison at the same size passes; so does
a trimmed copy of the conform program holding only `:build` and `:build-eq`;
so does the conform program itself once the expression is rewritten to bind
the result in a `let` first.

**Two diagnoses were made and both were wrong, which is the part worth
keeping.** The first was "a rooting bug, exposed by different allocation
timing". Testing it meant building the ports a stale-push detector they did
not have -- the native runtime has had one since `0031` and the ports had
NOTHING equivalent, which is why a bug like this has to be found by bisecting
a conformance suite rather than being named where it happens. It reports zero
stale pushes on the failing run.

The second diagnosis died faster: the CLR fails IDENTICALLY. Two
independently written runtimes with different host GCs giving the same wrong
answer is not a GC bug at all. It is semantic.

Instrumentation then confirmed the new branches are reached exactly as
designed -- 1 806 entry seqs, 3 612 firsts, 1 806 nexts over 600 rows of three
columns -- so the mechanism works and something else observes the difference.
Only `seq`, `first` and `next` read a vecseq's collection in either port, and
all three were patched, so whatever distinguishes the two shapes does not go
through them.

Reverted, because a change I cannot localise is not a change I can defend, and
the tree stays green. The detector STAYS: it was built to test a hypothesis
that turned out to be wrong, and it is worth more than the hypothesis was.

### `first`'s four divergences, closed one at a time

The table earlier in this file listed four. All four are gone, and the way
each was settled is worth keeping because no two were settled the same way.

| divergence | settled by |
| --- | --- |
| map entry: vecseq over the entry, or over a COPY | measurement -- 13.9% off a map walk, once the rooting bugs it exposed were fixed |
| string index: BYTE offset or CODE POINT | a bug -- the byte design read a rope's header as text |
| `nth` sentinel: `NIL` or `NOT_FOUND` | a proof of unreachability, then the simpler one |
| default arm: nil or THROW | the same proof |

The last two were probed rather than assumed. Over twelve conformance suites
and a deliberately adversarial set of non-seqs -- `nil`, `[]`, `""`, `{}`,
`#{}`, an empty range, an empty lazy-seq, a map entry -- both arms were hit
ZERO times. That is a coverage zero on its own, so it is backed by a
structural argument: `seq` answers nil, a cons, a vecseq, a strseq or a range,
and `first` handles every one of those above the default. Nothing can reach
it.

Converged on nil, not on the throw. Keeping the throw would mean `^:throws` on
`first`, and that turns its Rust signature into `Result<Value, String>` at
every call site -- a real cost for an arm nothing can reach.

`char_at` needed the `nth` convergence one function over: `Option<Value>` in
Rust, a fixed sentinel on the ports, four call sites in Rust and two per port.
It takes a `dflt` now, like `vec-nth` and `map-get`. Three lookups in this
runtime, one way of answering absence.

### `first` and `next` ship, and a NAME COLLISION reached the call sites

`seqwalk.kin`. 77 lines out of `seqs.rs`, 66 out of the JVM, 56 out of the CLR.

The interesting failure was not in the source. `seq-of` was a plain sibling
emitting `Seqs.seq(...)`, and a generated module lives in `flint.rt` where
`seqs.kin` generates a `flint.rt.Seqs` -- so inside another generated module
the bare name binds to the GENERATED class, which has `vecseq` and `range` and
no `seq` at all. The C# said *"'Seqs' does not contain a definition for
'Seq'"* and was exactly right.

`java-emit` already documents this collision as the reason the generated
package had to move. What was missed is that it reaches the CALL SITES too,
wherever a hand-written class and a generated one share a name -- and `Maps`
has the same shape, which is why `empty-map` was already written qualified
while nobody had said why.

`seq-of`, `first-of` and `next-of` are written out with explicit templates
now, because the CLASS differs per target -- `com.flint.rt.Seqs` against
`Flint.Rt.Seqs` -- and `sibling` varies only the method name.

Worth noting the shape of the bug: it is invisible until a generated module
calls a hand-written class whose name a DIFFERENT generated module has taken.
`seqcore.kin` and `seqs.kin` had both existed for a while without touching it.

The ports keep `Seqs.first` and `Seqs.next` as one-line delegations -- some
thirty call sites each, in files with nothing to do with seqs.

Also in the vocabulary: `vec-nth` still said arity 2 after the function grew a
`dflt` and became 3. Nothing had used it yet, so nothing broke; it would have
generated a call that does not compile the first time a source did.

### Port order, re-derived

| # | region | gate | lines across 3 |
| --- | --- | --- | --- |
| 1 | `Pike`: `word_cp`, `space_cp`, `pred_hit` | **hole 9 only**; contiguous in all three, no reorder | 50 |
| 2 | `Seqs`: `rest` | one template, after the `emptyList` convergence | 16 |
| 3 | `Eq`: `cmp_named` | hole 9 + 2 templates | 37 |
| 4 | `Vec`: the whole file, two regions | holes 9, 10 + the header convergence | **~860 net** |
| 5 | `Seqs`: the constructors | hole 10 + convergences | 247 | **SHIPPED** bar `seq`, which waits on hole 5 |
| 6 | `Bytes`: two regions | hole 10 + a byte sink + the `(base, n)` convergence | **~880 net** |
| 7 | `Maps`: `merge_two` + the six structural copies | **nothing new** -- 639 lines, algorithm line-for-line identical | 639 |
| 8 | `Table`, `Str`'s rope half, the rest | hole 5, reorders | ~2,600 | **SHIPPED** |

WHERE THE PORT STANDS, measured rather than estimated (re-derive before
quoting; this file keeps recording that a stale count is worse than none):

| file | lines | `pub fn` left | what they are |
| --- | --- | --- | --- |
| `table.rs` | 135 | **0** | constants and module wiring |
| `seqs.rs` | 188 | **0** | constants and the tests |
| `bytes.rs` | 477 | 22 | ALL PRIMITIVES: sinks, walks, `new_bytes`/`b_to_vec` |
| `rope.rs` | 190 | 6 | the UTF-8 decode, interning, the gas wrapper |
| `strs.rs` | 787 | 12 | interning, and functions taking a host `&str` |
| `map.rs` | 856 | 14 | the CHAMP, incl. the five the closure hole blocks |
| `coll.rs` | 812 | 19 | the string ops, `conj`, and two that are portable |
| `vector.rs` | 449 | 6 | predicates and `vec_from_roots` |
| `set.rs` | 223 | 5 | `set_for_each` and the TWO it still blocks |
| `eq.rs` | 286 | 4 | `is_sequential`, `eq_value`, `nil_or` -- and TESTS |
| `num.rs` | 293 | 13 | the predicates, `add`/`sub`/`mul`, `integer` |

`set.rs` lost one to a re-reading rather than to a new capability. A set IS
its backing map here -- element to itself -- so set equality IS map equality,
which is how BOTH PORTS had always written it while native walked the
elements one at a time probing the other set. That walk needed a callback, so
`set_eq` counted against the closure hole for no reason: it was never a walk
that had to happen. What is left behind `set_for_each` is `hash_set`, whose
sum over element hashes genuinely differs from a map's, and
`set_element_vector`, which is the buffer shape the measurement says loses.

The lesson is not "one down". It is that a function can sit on a blocked list
because of how it was written, not because of what it does, and the way to
tell is to look at how the OTHER runtimes wrote it. Both ports had the
answer in three lines the whole time.

`eq.rs` WAS 718 LINES AND IS 286, of which everything past line 190 is tests.
`=`, `hash`, the ordered hash and `compare` are generated, and the four
functions left are two-liners. `num.rs` lost the four operations with an
overflow edge; what remains agrees across all three and was read to check
that -- `num_eq`, `num_cmp` and `num_hash` are the same rule three times.

`coll.rs` WAS 1,609 LINES AND IS 812. What went is the whole GENERIC DISPATCH
layer -- `count`, `pop`, `peek`, `empty`, `get`, `contains?`, `assoc`, `nth`,
the six transient entry points, the transient map and set, and the atoms --
across `collgen`, `collread`, `collwrite`, `transients`, `maptrans` and
`atoms`. Nineteen functions remain and they sort into three piles:

* **ELEVEN ARE HOST STRING WORK.** `char_count`, `str_concat2`, `char_at`,
  `code_point_at`, `substring`, `keyword_from_values`, `symbol_from_values`,
  `number_to_string`, `string_to_number`, `join_strings`, `str_index_of` and
  `string_bytes_vector` all reach `as_str`, `sbuf()` or a raw `&[u8]`. That is
  the same wall `strs.rs` is against, and it is the right wall.
* **`conj` IS BLOCKED ON THE CLOSURE HOLE**, and only in one arm: merging
  another map means walking its entries. Every other arm is a tag dispatch of
  the kind now generated six times over.
* **`ordered_map` AND `new_volatile` ARE PORTABLE AND NOT PORTED.** Both ports
  already say them line for line, so generating them consolidates three copies
  and finds nothing. Worth doing; worth doing after something that does.

`bytes.rs` IS DONE IN THE SENSE THAT MATTERS. Every function left in it is a
VOCABULARY PRIMITIVE -- something a generated source names and cannot express,
because it holds a host `Vec`. The byte data structure itself is generated.

WHAT IS LEFT IN `strs.rs` IS MOSTLY NOT PORTABLE. `raw_string`,
`contiguous_string`, `flat_string`, `indexed_string`, `string`, `keyword`,
`symbol` and `string_from_parts` all take a host `&str`; the intern probe and
publish are the intern table. `ns_of`, `symbol_hash` and `is_symbol` are
portable and small; `name_of` and the two hashes need a bit-level
keyword-to-string primitive and a host hash respectively.

ROW 8 IS DONE. `Table` holds NO hand-written functions in any runtime --
`table.rs` is 137 lines of constants and module wiring, from 1,595, and the
three runtimes together are 454 lines from 3,700, in eleven kin sources.

THE LAST FUNCTION WAS FILED UNDER THE CLOSURE HOLE AND DID NOT NEED IT.
`table_reduce_column` takes a callback, so the census counted it with
`map_for_each`. But its callback is a FLINT closure invoked through the
runtime's own `invoke`; what was missing was a way to hand it its ARGUMENTS,
which is the `(base, n)` convergence `list_from_roots` already established.
`invoke-roots` takes them from a contiguous run of shadow-stack roots and
allocates nothing, where all three runtimes had been building a host argument
array once per row. The five `map_for_each` functions remain a real hole; this
was the census reading its own warning back at it.

THE OBVIOUS WAY OUT OF THE HOLE DOES NOT WORK, and it is worth writing down
which one, because it looks right. `bytes.rs` already has a CURSOR protocol
that generated code drives without a closure -- `walk-open`, `walk-next`,
`walk-done`, `walk-close`, with the state in a host stack the source names by
an INDEX. `byteeq` and `ropeeq` both use it. The same shape for maps would
unblock all five.

It cannot be that shape as it stands. `walks` is
`Vec<Vec<(Value, u32)>>` on the Rust side and the collector DOES NOT SCAN IT
-- the root scan covers the executor stacks, the shadow stacks and the
globals, and nothing else. It is safe for `b-eq` and `tree-eq` because neither
ALLOCATES while walking. Every blocked map function does: `map-eq` calls
`map-get` and `=`, `hash-map` calls `hash`. The node addresses parked in that
stack would be stale at the first collection, which is `0031` exactly.

So there are two designs, not one, and the difference is where the walk keeps
its path:

* **A CALLBACK capability in kin.** Measured in `runtime/src/map.rs`: the
  buffer alternative costs 1.73x the time and 12 500x the peak roots, so
  "just materialise the entries" is not the answer either.
* **A cursor that holds SHADOW-STACK INDICES rather than Values.** The shadow
  stack is scanned, so a walk that pushes its node path as roots and records
  the indices survives a collection. It needs no closures in kin and no change
  to the collector -- only `Vec<(RootIx, u32)>` where `Vec<(Value, u32)>` is
  today, in three runtimes.

The second is not obviously worse and was not on the table before, because the
first objection to a cursor -- that it cannot survive allocation -- is only
true of the one that stores Values.

`Str`'S ROPE HALF IS DONE. Row 8's string share shipped across five sources --
`ropenode`, `ropecat`, `ropeslice`, `ropeflat`, `ropeeq`, `ropecp`,
`ropemeas` -- taking `Str` from 2,462 to 1,196 lines across the three runtimes.
What is left in `rope.rs` is five functions and every one of them is a host
boundary rather than a blocker: `s_copy_range` and `copy_concat` are a UTF-8
decode, `s_empty` interns, `s_to_vec` answers a host vector, and `flatten` is
the gas-and-counter wrapper `0011` asks for. `Table` is what remains of row 8.

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

THE CLOSURE HOLE IS REAL, AND NOW MEASURED. The obvious workaround is the
`(base, n)` convergence used everywhere else in this port -- push the entries
onto the shadow stack and hand over a run. `runtime/examples/iterbench.rs`
says no, on 50 000 entries and 20 walks:

    callback      4.673 ms   peak roots        8
    buffer        8.089 ms   peak roots  100 001
    buf/noslide   4.924 ms   peak roots  100 001

1.73x the time and 12 500x the peak roots. THE ROOTS NUMBER DECIDES IT: the
collector scans the shadow stack, so 100 001 live roots is 100 001 words to
trace at every collection during the walk -- and a walk is exactly when a
program is most likely to allocate. Where allocation meets live roots, building
the entry vector five times, it is 29.715 ms against 31.656 ms.

`(base, n)` is right where it is used because those calls pass a HANDFUL of
arguments. This would pass the whole map. Same shape, different size, opposite
answer -- and the difference is a measurement rather than a judgement.

(The harness had stopped compiling: `map_entry` went private when `mapcore`
was generated. One `^:pub` restored it.)

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

**MEASURED ON THE PORTS, and it changes what "do nothing" costs.** The buffer
is not a cost that generating would introduce. It is a cost two of the three
runtimes ALREADY PAY, on every map comparison, today:

| map entries | roots the JVM pushes in `entries` |
|---|---|
| 8 | 16 |
| 100 | 200 |
| 10,000 | 20,000 |
| 50,000 | 100,000 |

Exactly `2n`. The native measurement that rejected the buffer shape was
8.089ms and 100,001 peak roots at 50,000 entries against 4.673ms and 8 for
the callback -- and 100,000 is what both ports spend there right now.

So the three options do not divide into "keep native fast" and "pay for
generating". They divide by WHO pays:

- **Nothing.** Native holds 8 roots, both ports hold `2n`, forever, and the
  functions stay hand-written three times.
- **A callback capability in kin.** Native keeps 8; the ports could DROP from
  `2n` to 8, which makes this the only option that improves a runtime rather
  than trading between them.
- **A cursor of shadow-stack indices.** Everyone lands at O(depth).

**A fourth option was tried and does NOT work, which is worth writing down
because it looks obviously right.** If the CHAMP were canonical, two equal
maps would have identical tries and equality would be a STRUCTURAL walk of
two trees -- no callback, no buffer, O(depth) roots, and generatable with the
vocabulary that already exists.

The CHAMP *is* canonical. Built ascending and descending, the tries agree
exactly:

| entries | same shape built either way |
|---|---|
| 9 | yes |
| 100 | yes |
| 5,000 | yes |

**But the TIER is path-dependent, and that kills it.** Eight entries built
directly are an ARRAY-MAP. Eight entries reached by growing to nine and
`dissoc`-ing one stay a CHAMP -- there is no conversion back down. So two
equal maps can be structurally incomparable while `=` correctly answers true,
and a structural walk would have to keep the lookup path anyway for the mixed
case. Keeping both algorithms is not a simplification.

Checked while there: those two maps DO hash alike (473211113 both ways), and
`=` answers true in both directions. The invariant now has a test --
`lang.edges/coming-back-down-a-boundary-changes-nothing` -- because
everything in that file grew, and nothing had ever compared two equal
collections that settled in different tiers. That is the same blind spot that
let a rope and a flat string hash differently.

The first measurement of this read `roots.stackTop` and reported 0 roots at
every size. `stackTop` is the OPERAND stack; the shadow stack is
`shadowTop`. A zero that is identical across four inputs is not a finding,
and this is the fourth time in this port that an exact zero meant the
instrument was pointed at the wrong thing.

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
| 5 | no string building | every error message, and most of `Str` -- **CLOSED**: `str-cat` for messages, `Sink` for bytes |
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
* **`cargo check --workspace --all-targets` is the build oracle, and nothing
  narrower is.** This has now bitten twice. `cargo check` does not build TESTS,
  so a signature change compiled while the tests that call it did not. Then
  `--all-targets` run inside `runtime/` did not build the OTHER CRATES, so
  converging `opaque_host_id` from `u64` to `i64` left `flint-conc`'s
  host-ports test broken with a clean check in front of me. Each time the
  narrower command answered "fine" and `bin/test` answered otherwise twenty
  minutes later. The cost of the wide one is seconds.
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

### The other two GC checks: attempted, unsound, NOT shipped

The stale-push check is on in both ports now. `doc/HANDOFF.md` names two more
that native asserts and neither port has: every old-to-young edge is
remembered, and no live object points into the half just abandoned. Both were
written for the JVM, and both were **thrown away**. What the attempt
established is worth more than the code was:

**A port-side audit that walks old space is UNSOUND between majors.** Old
space holds unreachable objects that have not been swept yet. The collector
correctly never scans them, so their slots keep pre-collection addresses --
and an audit walking the chunk arrays cannot tell a corpse from a live
object, because `marked` is only meaningful during a major.

Measured, not reasoned: on a CLEAN collector the audit reported two
violations, one of them an old `TY_SET` pointing at a young `TY_FWD`, which
is exactly the shape of a real missed edge. Forcing a major and re-running
gave **zero** across the next nineteen collections, including deliberate
write-barrier traffic. They were garbage.

**The first version could not fail at all.** It asked the remset question at
the END of a minor, where anything young that an old object points at has
already been promoted, so old-to-young edges are structurally zero -- it
survived a deliberately broken write barrier without a murmur. Native asks at
BOTH ends and counts the end violations separately (`remset_end_violations`),
which is the detail that makes its end-of-minor check mean something.

**Where a sound version goes:** immediately after `sweepOld()`, when old
space contains only live objects and TY_FREE. That is a small change and it
is the next person's, not a guess left in the tree.

The instrument caught a real rooting bug on the way: the probe written to
exercise it read `rt.r(vi)` before `Str.of(...)` allocated, because Java
evaluates arguments left to right. `0031` in the test rather than the
runtime, found one step before it mattered.

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

## Divergences the port found and did NOT close

Generating a function is the moment the three runtimes get compared, so the
port keeps turning up disagreements that are wider than the function being
ported. These are recorded rather than fixed, because closing them is a
decision about what a builtin MEANS and not about where its body lives.

* **`conj!`, `assoc!` and `dissoc!` are VARIADIC on both ports and unary on
  native.** The ports loop over `n` arguments; native's builtin reads exactly
  two and drops the rest, so `(conj! t a b)` keeps `b` on the JVM and the CLR
  and loses it on wasm. Clojure's are variadic, so the ports are right and the
  fix belongs in `builtins.rs` -- `kin/transients.kin` supplies the single step
  all three fold, and the folding is the part that is still three copies.
* **`conj!` onto a transient map takes any two-element SEQ on the ports** --
  they use `first`/`rest` -- where native takes a map entry or a vector, which
  is what `slot-or-nth` reaches. `(conj! m '(:a 1))` is a list, and Clojure
  accepts it.
* **The ports' `assoc!` on a transient vector does not check the index sign.**
  `Val.asFixnum` of `-1` is passed straight to `tassoc`. Native refuses, and
  `kin/transients.kin` now refuses on all three -- but only through the
  DISPATCHER, which the ports' variadic loop bypasses.
* **`dissoc!`'s refusal on both ports says `disj!`.** One message for two
  builtins, and the one it names is the other one.
* **`(contains? '(1 2) 0)` answers false on all three and THROWS in Clojure.**
  That was wasm's behaviour before the generic was generated and is its
  behaviour after -- `contains?` falls through to `get` against `NOT_FOUND`,
  and a seq answers no rather than refusing. The same fallback is what makes
  `(contains? "abc" 1)` true, which Clojure agrees with, so the arm is right
  and only the seq case is wrong. Making it throw is a decision about the
  builtin.
* **`dissoc` on native only acts on maps** -- `if rt.is_map(m)` and otherwise
  the accumulator is passed through unchanged, so `(dissoc 5 :k)` is `5`.
  Unsurveyed on the ports.

## What generating found, and how

Nine ports and every one of them turned up a disagreement, which is the case
for doing it: a body written three times is compared exactly once, when someone
writes it a fourth. What follows is the list, because the SHAPES repeat.

**A cache that was written and never read.** `strHash` on both ports, set
during interning and read by nothing; a keyword's slot 2 and a symbol's slot 3,
set to nil rather than to the hash. Native read all three. The ports recomputed
from bytes on every hash of the two commonest map keys there are.

**Dead code that looked live.** Both ports' `eq` tested for a row ref BELOW the
category switch, where `category` had already sent it to `map-eq`. It worked
only because THEIR `map-eq` materialises refs and native's does not -- two
strategies, one with an unreachable block in front of it.

**A guard on one runtime and not the others.** `(quot MIN -1)` overflows;
native checked and panicked doing it, the JVM wrapped silently, the CLR raised
a host exception. Three answers to one question.

**A narrow test where the language has a wide one.** `is_string` is true for a
rope, and `compare` read `str_bytes` -- which debug-asserts `TY_STR`, so a
release build compared a rope's SLOTS as UTF-8 and two unequal 1 400-byte
strings compared as 0. `str->b` asked `as_str`, which cannot materialise a rope
and answers `None`, and refused a value `string?` accepts. `join_strings`
carries a comment about this exact trap and was fixed for it years before
either of these was.

**A constructor that truncates.** Both ports' IMAGE loader decoded a large
integer literal with `fixnum` where the wire codec used `integer`, so `2^62`
read back as 0 -- while the same number COMPUTED at runtime was fine. Every
arithmetic test built its big values rather than writing them down.

**A test whose setup threw.** `test/gas.clj` timed `bytes-to-str` over a nil
for as long as the case existed, because building its input needed `str->b` on
a rope. Fixing one bug exposed the other: 48 163 steps past an exhausted
budget on a path that charged for nothing.

## What the invariants found, and why they could

Generating compares three runtimes against each other. That cannot see a bug
all three SHARE, and two of the worst found here were shared.

**A self-comparing property can.** `(= x (read-string (pr-str x)))` compares a
value against itself rather than against another runtime, and it found that a
ROPE and an equal FLAT string hashed differently -- `string-hash` finalises
through `hash-int` and `rope-hash` did not. All three agreed, so
`conform-hosts` was silent. The rule it breaks is the only one `0011` states
about tiers.

**The shape behind it: TWO FUNCTIONS COMPUTING ONE MEANING.** Byte strings were
checked for the same and are clean -- `b-hash` serves both tiers through one
algebra, which is exactly why they never diverged. Where there are two, check
them against each other; where there is one, there is nothing to disagree.

**And the pair can be split ACROSS the runtimes rather than inside one.**
Native's `char_count` was collapsed onto `s_count`; both ports kept a
hand-written `charLen`/`CharLen` beside the generated `sCount`, and the
GENERATED callers -- `Seqwalk`, `Collgen` -- reached the hand-written one.
So one line of kin meant an O(1) slot read on native and a walk of the whole
rope into a fresh host array on both ports.

Measured before claiming, and the first measurement was wrong: `RP_FLAT`
stays nil either way, so the walk was not flattening and the "it flattens"
story did not survive contact. What it was doing is paying an O(n) walk with
nothing cached, every call. A rope of 180,900 characters counted 20,000
times: **728ms before, 2ms after, same answer.** No gas moved -- nothing on
that walk charges -- so this was wall clock and allocation, never
determinism.

The lesson is where to LOOK for the pair. Inside one runtime it is visible by
reading the file. Split across runtimes it is invisible from either side:
each looks like a single implementation, and only the vocabulary table says
they are the same word.

**And a property asked of EVERY entrance, not the one being looked at.** A
call-position fault set `thrown` and the interpreter checked only whether it
had PARKED, on both ports, at all three of CALL, APPLY and TAIL_CALL -- so a
`try` around a bad call saw nothing and the throw aborted an unrelated
expression two operations later. The NATIVE opcode path had the check the whole
time. `lang.control/every-throwing-path-unwinds-where-it-happens` now asks it
of eleven entrances rather than of whichever one someone was reading.

**And a path can be under-tested rather than wrong.** Both ports truncated
every boxed integer they loaded from an image. That is not a subtle bug -- it
is `fixnum` where `integer` belongs, on a line either port's author would have
caught reading it. It survived because of what exercised it: the only REAL
image any port had ever read was a program that summed 0..999, so the loader
was tested at `K_INT` small, `K_FN`, and nothing else. Fourteen kinds, one
sample.

`out/rt-probe/probe.cljc` in `bin/conform-hosts` is now a CORPUS OF LITERALS,
one of every kind the image can carry at every tier, folded through `hash` --
which all three runtimes port, so the whole thing collapses to one integer
they must agree on, reachable without any standard library.

It was checked the only way a new test can be: **reverting the fix turns the
corpus red and leaves the summing loop green.** A test that has never been
observed to fail is a claim, not a result.

The literal is worth singling out because it is a FOURTH implementation of
every tier, beside the three runtimes' constructors: it goes through the image
writer and then each loader, while the same value built at runtime goes
through a constructor. That is the two-functions-one-meaning shape again, and
`lang.edges/every-literal-agrees-with-the-same-value-built` now asks it of
every kind -- which is how `2^62` written down could read back as 0 while
`2^62` computed was fine, for as long as every arithmetic test BUILT its big
values rather than writing them down.

`test/common/lang/edges.cljc` exists because of the last four. It walks the
tier boundaries -- fixnum at 2^47, inline at 5 bytes, interned at 32, flat at
1 024, array-map at 8, table chunk at 256 -- and asserts INVARIANTS rather than
answers, which is what makes it writable without knowing what is broken.

One divergence in that batch WAS closed, and toward native rather than toward
the ports, which is worth writing down because the ports were the permissive
side. Both ports let the generic `persistent!` take a BYTE transient. Adding
that arm to the shared dispatcher MEASURED at 8 186 bytes in the linked module
and dropped the shaker's recovery from 46% to 44%, because `persistent!` is
reachable from anything and the arm drags the whole byte builder in behind it
-- the same shape as the table branch in `pr-str*` that `test/threads.clj`
records. Nothing routes a byte transient through the generic: `selfhost` and
`wasm` both name `flint/b-persistent!`. So the arm is gone from all three, and
the whole dispatcher port costs 131 bytes.

That measurement was WRONG twice before it was right. `bb test/shake.clj` reads
`dist/flint-runtime.wasm` and the prebuilt units, and neither `bb` alone nor
`bin/build-units` regenerates the dist -- so the first two attempts compared a
fresh `linked` against a stale `prebuilt` and `shaken`. **`./bin/build-dist &&
./bin/build-units` before `bb test/shake.clj`**, or the ratio is arithmetic on
numbers from two different builds.
