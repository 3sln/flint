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
* **Opcode coverage is 38 of 38.** Every opcode the compiler can emit is
  executed by `bin/conform-hosts` on all three runtimes. That is the baseline a
  port must not lower.

  Checking it per slice means running `opcov` over the images a build leaves in
  `out/`, which is a SMALLER set than the 38-of-38 figure covers -- it reports
  36 hot, and comparing that number against 38 would be comparing two different
  image sets. So the check is a before/after over the SAME images, taken by
  stashing the slice and re-running: `Hash` measured 220 of 256 cold both ways,
  unchanged. Answering criterion 4 with a reading -- "hashing contains no
  opcode dispatch, so coverage cannot move" -- would have been true and would
  not have been a measurement.

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

**Next:** `encodeInto` — the tag dispatch. It needs type predicates
(`isNil`, `isString`, `isHeapTy`), heap slot reads and recursion, which is more
vocabulary than anything so far.

**Then:** `decodeAt`, which builds heap values and so needs rooting.

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
`Eq`, `Hash`, `Interns`, `Seqs`. Near-identical, no ownership subtleties.

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
