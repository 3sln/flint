# Goal — port the runtimes' shared logic to splint

**Status:** phase 1 begun — the codec's primitive writers are generated and
verified byte-identical on all three targets.
**Design:** `doc/decisions/0038-splint.md`. **Tool:** `splint/`.

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
bug in splint could produce a wrong implementation and a wrong test that agrees
with it, and the suite would stay green while both were wrong.

So: port implementation code, verify it against the EXISTING hand-written tests,
and only once a file's implementation is generated and green does its test
become safe to port. Never port a test and its subject in the same change.

## Where things stand

* `splint/` — the library, one vocabulary, three sources, a babashka driver.
  Plain `.cljc`, no reader conditionals, runs under bb so it can bootstrap.
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

`splint/verify <source>` is that check, made repeatable: it generates, compiles
each target, runs each, and requires the outputs to match. Compiling is not
enough — three implementations can each compile and disagree, which is the
failure the port exists to prevent. A new port writes a `.drivers` file beside
its source and the harness is unchanged.

**Next:** `encodeInto` — the tag dispatch. It needs type predicates
(`isNil`, `isString`, `isHeapTy`), heap slot reads, and the `Refused`
exception, which is more vocabulary than the primitives needed.

**Then:** `decodeAt`, which needs the reader and bounds checks.

### 2. The small pure files
`Eq`, `Hash`, `Interns`, `Seqs`. Near-identical, no ownership subtleties.

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

## Acceptance, per phase

Nothing lands without all of these:

1. Generated output compiles on **all three** runtimes.
2. `bin/conform-hosts` green — this is the test that would catch a wrong port.
3. `./bin/test` green.
4. `opcov` coverage has not dropped. A regeneration that makes something
   unreachable is exactly the quiet failure this guards.
5. The generated code is diffed against what it replaces and is **not worse**.

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
  a comment explaining itself, and neither survived. splint's job here is to
  CONVERGE the runtimes, not to encode differences nobody chose.
* **Verified or reverted.** Four attempts at the park bug were written, measured,
  and reverted rather than left in the tree as unverified interpreter changes.

## Known hazards

* **Rust carries more rules than the other two.** Hoisting, `mut` inference,
  numeric casts, assignment-to-a-place — all Rust-only, all in the vocabulary.
  Expect each new file to surface one or two more.
* **`:require` resolution is real but the driver wires vocabularies directly.**
  Making it load them properly is unfinished, and is the same question `0036`'s
  namespace resolver answers.
* **A form that must emit a statement while being used as an expression** has
  `splint-emit-anchor!` and nothing else. Untested beyond hoisting.
* **The generated snippet in `vm.rs` is a snapshot.** Regenerate with
  `bb splint/run.clj` when the vocabulary changes; nothing does it automatically.
