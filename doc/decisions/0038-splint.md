# 0038 — splint: write a runtime's shared logic once

> **NOT BUILT — a spike.** `splint/` exists and translates one real snippet to three targets;
> the generated Rust compiles verbatim. Nothing in the tree uses it yet, and the
> open questions at the bottom decide whether anything should.

## What it is for

flint has four runtimes meant to be verbatim mirrors, and they are kept so BY
HAND. The cost is not theoretical: fixing the `apply` park bug (`0037`) meant
writing the same thirty lines three times, in Rust, Java and C#, and the gate
caught the port I had not done yet with `opcode 0x1e is not ported yet`.

The three versions differed in naming, punctuation, and one structural rule.
That ratio is the bet: **if per-target knowledge is data, the shared parts can
be written once.**

## The shape

A splint source is an ordinary `ns` with `:require`, so **what a file may say is
what it asked for** — two sources can be written in different vocabularies, and
a form nobody imported is not silently available.

```clojure
(ns runtime.spread
  (:require [flint.impl.vm :refer [let set if while do break invoke
                                   Usize Val
                                   r set-r vpush pop-to seq first next charge]]))

(let [^Usize spread 0]
  (set-r si (seq (r si)))
  (while (not (invoke ^Val (r si) nil?))
    (if (not (charge spread 1 "apply"))
      (do (pop-to si) (break)))
    (vpush (first (r si)))
    (set spread (+ spread 1))
    (set-r si (next (r si)))))
```

A **vocabulary** is `splint-ns`: tags, and each form implemented per target as a
FUNCTION. A **driver** is `splint`: targets, each with a path and a file
preamble.

### Rules are code, and that is the decision

The first version made per-target knowledge a table of format strings. It got
most of the way and then leaked — four things turned out not to be expressible
as data, and each ended up special-cased in the TRANSLATOR:

* Rust needs call arguments hoisted into temporaries and the others do not;
* a loop test cannot be hoisted and has to be rewritten into the loop;
* `mut` has to be inferred from whether the body assigns;
* numeric width is a per-call cast.

A translator that knows all four is not a translator with a config file; it is a
compiler for three languages wearing one. With forms as functions, all four
become ordinary code inside the implementation that needs them. Rust's `set-r`
hoists because Rust's `set-r` says so, and nothing else in the system knows.

### Tags are values

A tag carries the type each target spells it as AND a dispatch table, so
`(invoke ^Val x nil?)` asks the TAG what to emit:

```text
rust    self.r(si).is_nil()
java    Val.isNil(r(si))
csharp  Val.IsNil(R(si))
```

That is the compile-time protocol: a new target is a new entry in a tag rather
than a new case in the translator.

### Anchors, not a second sink

`splint-emit-anchor!` drops a named place in the output and returns it;
`(splint-emit! a "...")` writes there, from arbitrarily deep, resolved when the
buffer is joined.

This replaced a `:pre` sink that could reach exactly ONE level up — before the
statement being built. That is enough for hoisting a temporary and enough for
nothing else: a loop-invariant binding wants to go before the LOOP, a scratch
declaration wants the top of the FUNCTION, and neither is one level up. An
anchor is carried in a scope frame, so a form deep inside emits to a place an
enclosing form chose.

### Statement-ness belongs to the TARGET

A form's implementation says what it EMITS, in metadata on the implementation
so the two cannot drift. The target supplies `place`, which is handed the
position and the kind and decides.

This started as a set of "statement heads" in the vocabulary, and that is wrong
because **"is this a statement" is a question about the target, and the targets
disagree — including two of ours**:

* in Java and C#, `if` is a statement and cannot produce a value;
* **in Rust `if` is an expression**, and `let x = if c { a } else { b };` is
  what a person writes;
* a language with no statements at all — a Lisp backend, which is the point of
  being language-agnostic — has nothing to wrap, and supplies a `place` that
  returns its argument.

Moving it changed no output, which is the check that it was a refactor.

## The rule: generated code may not be worse

**If what comes out is worse or less efficient than what a person would have
written, adjust the rules or hand-write that part.** Generated code that is
worse than what it replaces is not worth generating.

It has already caught three things, each found by diffing against the
hand-written original rather than by reading the output:

1. **The loop lost its natural shape.** Every `while` was rewritten to
   `while true { if !c { break; } … }` because a test that hoists cannot stay in
   the condition. Most do not hoist — so the rewrite now happens only when the
   test actually needs a temporary, and everything else keeps `while !c { … }`.
2. **Hoisting was too aggressive.** Every call argument was being bound to a
   temporary, giving three where a person writes none. Rust's two-phase borrows
   accept `self.seq(self.r(si))` — one level, and the runtime is full of it.
   What `rustc` refuses is two. So an argument is hoisted only when it is itself
   a call containing a call.
3. **`spread = (spread + 1)`** where a person writes `spread += 1`, and
   parenthesised assignment right-hand sides.

Against the hand-written original the remaining difference is the temporaries'
names — no extra work, no extra allocation. Temps are named after their callee
(`seq_1`, `first_2`) rather than numbered, for the same reason.


## What came out

```rust
let mut spread: usize = 0;
let t1__ = self.r(si);
let t2__ = self.seq(t1__);
self.set_r(si, t2__);
while true {
    if self.r(si).is_nil() { break; }
    let t3__ = self.charge_tick(spread as u64, 1, "apply");
    ...
```

```java
int spread = 0;
setR(si, Seqs.seq(this, r(si)));
while (true) {
    if (Val.isNil(r(si))) { break; }
    if (!chargeTick(spread, 1, "apply")) { popTo(si); break; }
    vpush(Seqs.first(this, r(si)));
```

Same source. Rust gets temporaries, `mut`, a type and a cast; Java and C# get
the natural nested form and none of them. The JVM and CLR output is the
hand-written code modulo whitespace.

**The generated Rust compiles verbatim**, pasted into `runtime/src/vm.rs`
without an edit — asserted by the build rather than claimed here, since the
snippet sits in the file under `#[cfg(test)]`.

## What it deliberately cannot do

The divergent parts, and they should not be attempted: the GC write barrier,
the wasm ABI shim, `unsafe` heap access, anything where the three runtimes are
different by nature. Pretending otherwise would produce three things that are
each subtly wrong, which is worse than three that are honestly separate.

The claim is only about 1:1 logic — which is most of an opcode body, most of a
builtin, and most of what has cost time.

## What is undecided

* **How `:require` resolves.** The spike wires the vocabulary directly and
  ignores the `ns` form. Making it real means deciding where a vocabulary lives
  and how a source names one — which is the same question flint answers with
  its namespace resolver (`0036`), so it may be the same answer.
* **Expressions that need statements.** A form that must emit a statement while
  being used as an expression — a Rust `match`, a ternary — has `splint-before!`
  and nothing else. It is enough for hoisting and it has not been tested on
  anything harder.
* **Where the generated code goes.** Pasting into the three runtimes means
  generated code in the repository, which needs a check that it is up to date.
  Generating at build time means three build steps that need flint or babashka
  present.
* **How much is actually shared.** The spike did one snippet. The honest next
  step is to take an existing ported opcode — `LIST`, `TYPE_P`, `SET` — express
  all three in splint, and diff against what is there. If the diff is empty for
  most of them the case is made; if it is not, the ratio is the answer.
* **Whether it should be `flint` or its own thing.** It uses flint's reader and
  nothing else of flint, and it is not about Clojure at all. `splint` as a
  separate small tool may be the honest packaging.
