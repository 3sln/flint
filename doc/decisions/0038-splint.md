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

### Position is pushed DOWN, and a form asks

A form does not have a kind. It has a POSITION, and whatever encloses it is what
knows: a top-level form in a method body is a statement whether or not the
construct could also be an expression, and the body is the thing that knows it
is a body. So the enclosing form scopes `:position`, and the implementation
reads it.

That is decisive rather than tidy, and this is the proof — one source:

```clojure
(let [^Val picked (if (invoke ^Val (r si) nil?) (first (r si)) (next (r si)))]
  (vpush picked)
  (if (invoke ^Val picked nil?)
    (do (pop-to si) (break))))
```

```rust
let picked: Value = if self.r(si).is_nil() { self.first(self.r(si)) } else { self.next(self.r(si)) };
self.vpush(picked);
if picked.is_nil() {
    self.pop_to(si);
    break;
}
```

```java
long picked = Val.isNil(r(si)) ? Seqs.first(this, r(si)) : Seqs.next(this, r(si));
vpush(picked);
if (Val.isNil(r(si))) { ... }
```

**The same `if`, twice, differently.** Rust gets an `if` EXPRESSION, which is
what a person writes there; Java and C# get the conditional operator, which is
what those languages have; and in statement position all three get braces.

### What this replaced, and why it was wrong

Two earlier versions, both discarded:

* a set of "statement heads" in the vocabulary — wrong because the question is
  about the TARGET and the targets disagree, `if` being a statement in Java and
  an expression in Rust;
* metadata on the implementation saying what it EMITS, plus a per-target
  `place` wrapper combining the two — wrong because it still assigned `if` ONE
  kind, so the example above could not be expressed at all.

Both were answering from the wrong end. Position is not a property of a form.

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

## Namespacing, because two vocabularies can both define `let`

A source's `ns` form is honoured for real:

```clojure
(ns runtime.spread
  (:require [flint.impl.vm :as vm
             :refer [let set if while do break invoke not + Usize Val
                     r set-r vpush pop-to seq first next charge]]))
```

`let` (referred), `vm/let` (aliased) and `flint.impl.vm/let` (qualified) all
resolve; anything NOT referred is not in scope unqualified, exactly as in
Clojure — `(seq 1)` in a file that referred only `vpush` is refused by name and
told what the file did ask for. **Tags are namespaced the same way**, so
`^Usize` means the one this file required.

A head symbol that is not in scope is an ERROR rather than a literal. Falling
through would emit the symbol's name and produce something that looks like a
call and is not one.

The library is plain `.cljc` with no reader conditionals and runs under
babashka, which is what it has to do to bootstrap.

## Porting real opcodes, and the holes it surfaced

This is the measurement the spike needed, and it changed the picture.

**The three runtimes are not verbatim mirrors at the statement level.** They are
behaviourally equivalent with locally different implementations, and the
differences are not all drift:

| | |
| --- | --- |
| `TYPE_P` | Rust pops and pushes; the JVM and CLR rewrite the top slot IN PLACE |
| `LIST` | Rust conses backwards off the value stack; the JVM copies to the shadow stack first — a **different algorithm**, not a different spelling |

Both turn out to be incidental. See below — that question is the one worth
asking about every divergence, and neither of these survived it.

So a port is not "translate what is there". It is "pick one shape and regenerate
all three", and the generated code will then differ from what at least two
runtimes currently contain — which means the not-worse rule has to be judged
against the BEST of the three rather than against each.

### Are the divergences justified, or incidental?

Both are **incidental**, and the first of them corrects a claim this file made
one commit ago.

**`TYPE_P`: not forced.** The earlier text said the divergence was forced by the
borrow checker. That was wrong, and wrong in the direction that matters — it
would have licensed leaving three implementations different for a reason that
does not exist.

What is true is that the NAIVE in-place form is refused, twice:

```text
self.roots.stack[i] = Value::boolean(self.type_p(c, self.roots.stack[i]))
E0502: cannot borrow `self.roots` as immutable because it is also
       borrowed as mutable
```

— once for the call on the right, and again for the index expression. What is
NOT true is that in-place is impossible. With the value and the index in locals
it compiles, and that is the form splint generates:

```rust
let bool_1 = Value::boolean(self.type_p(c, self.roots.stack[self.roots.stack_top - 1]));
let at_2 = self.roots.stack_top - 1;
self.roots.stack[at_2] = bool_1;
```

Three statements, same as the pop/push version, and it does not touch
`stack_top` at all where pop/push moves it down and back up. So the Rust runtime
should be in-place too, and the divergence is something nobody chose.

**`LIST`: also not justified.** The JVM copies all *n* elements to the shadow
stack before consing, with the comment *"cons allocates, and the value stack is
where they are now"*. That reasoning does not hold here, for two independent
reasons:

* the value stack IS a root set — `for v in &mut self.own.stack[..top]` in the
  collector's forwarding pass — so a value in it survives an allocation and is
  updated;
* and `cons` roots both of its arguments on the shadow stack before it allocates
  anyway, so even a value read into a host local is protected across the call
  that could move it.

So the copy buys nothing and costs *n* shadow slots and *n* pushes, where the
Rust version uses one slot. Rust's shape is the better one and the JVM's is
incidental — it looks like someone applying `0031`'s rule ("a value in a host
local does not survive an allocation") in a place where the callee already
handles it.

### What that means for splint

Better than the alternative. If the divergences had been justified, splint would
have to express three different shapes and the shared source would be a fiction.
They are not — so **splint's job here is to converge the runtimes, not to
encode their differences**, and the generated code is the argument for changing
two of them.

It also sharpens what to be suspicious of: a divergence should be treated as
incidental until someone can point at the language refusing the alternative.
Both of these looked principled, one of them had a comment explaining itself,
and neither survived being checked.

### Holes found, all four closed

Each was found by the compiler refusing something or by the not-worse rule, not
by reading:

1. **A place is not a call.** `(top)` renders to `roots.stack[...]`, an index,
   which does not borrow — so hoisting it produced a temporary a person would
   not write.
2. **Assignment to a place is a borrow site** (above).
3. **The index of a place is a borrow site too** (above).
4. **Opcode framing is a target's business.** `op::X => {}`, `case Op.X -> {}`
   and `case Op.X: {} break;` differ in mechanism and not in meaning, so
   `defop` owns it and an opcode body never mentions it.

Generated `TYPE_P`, all three, from one source:

```rust
op::TYPE_P => {
    let c: usize = self.u8_at(ip);
    ip += 1;
    let bool_1 = Value::boolean(self.type_p(c, self.roots.stack[self.roots.stack_top - 1]));
    let at_2 = self.roots.stack_top - 1;
    self.roots.stack[at_2] = bool_1;
}
```

```java
case Op.TYPE_P -> {
    int c = u8(ip);
    ip += 1;
    roots.stack[roots.stackTop - 1] = Val.bool(typeP(c, roots.stack[roots.stackTop - 1]));
}
```

The Java and C# output is what is in the tree, modulo one line break. The Rust
is not — it is the in-place shape, which the tree does not use, and it compiles.

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
