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

```clojure
(defsnippet spread-seq
  "Spread `seq` onto the value stack, returning how many went on."
  [^usize si]
  (let [^usize spread 0]
    (set-r si (seq (r si)))
    (while (not (nil? (r si)))
      (if (not (charge spread 1 "apply"))
        (do (pop-to si) (break)))
      (vpush (first (r si)))
      (set spread (+ spread 1))
      (set-r si (next (r si))))
    spread))
```

`splint/rules/<target>.edn` says what each call, field, type and statement looks
like. `splint/splint.cljc` knows `let`, `set`, `if`, `while`, `do`, `return`,
`break`, `continue` and operators — and nothing about any language.

The subject is deliberate: it is the exact code that was written three times by
hand, so the output can be compared against something real rather than against
an example chosen to flatter the tool.

## What came out

```java
// jvm
int spread = 0;
setR(si, Seqs.seq(this, r(si)));
while (true) {
    if (Val.isNil(r(si))) { break; }
    if (!chargeTick(spread, 1, "apply")) { popTo(si); break; }
    vpush(Seqs.first(this, r(si)));
    spread = (spread + 1);
    setR(si, Seqs.next(this, r(si)));
}
```

```rust
// rust
let mut spread: usize = 0;
let t1__ = self.r(si);
let t2__ = self.seq(t1__);
self.set_r(si, t2__);
while true {
    let t3__ = self.r(si);
    if t3__.is_nil() { break; }
    let t4__ = self.charge_tick(spread as u64, 1, "apply");
    if !t4__ { self.pop_to(si); break; }
    ...
```

The JVM and CLR outputs are the hand-written ones modulo whitespace. The Rust
output is the hand-written one **including its temporaries** — and that is the
finding.

## Four things it is not just naming, and all four are expressible

The spike's value is these, because each was discovered by the compiler
refusing something rather than by thinking about it.

1. **Rust needs temporaries; the others do not.**
   `self.set_r(si, self.seq(self.r(si)))` is two mutable borrows and `rustc`
   refuses it. Java and C# accept the same shape. So a target may set
   `:hoist-call-args`, and Rust alone does — which is why the Rust output has
   `t1__` and the others do not, from one source.

2. **A loop test cannot be hoisted.** The first version lifted it and emitted
   `while !t1__` with the temporary bound once, before the loop. The fix is to
   put the test inside — `while (true) { if (!test) break; … }` — which is
   correct on every target and needs no per-target rule.

3. **Mutability is inferred, not declared.** Rust needs `mut` and refuses a
   second assignment without it; Java and C# need nothing. Asking the author to
   write it would be asking them which target they are writing for.

4. **Numeric width is per-call.** `charge_tick` takes `u64`, the caller counts
   in `usize`, and Java and C# use `long` for both. The cast lives in the
   Rust rule for that call: `"self.charge_tick({0} as u64, {1}, {2})"`.

Two bugs found the same way, worth recording because they are what a reader
would hit next: temporaries numbered per statement collided in one scope (Rust
shadows silently, Java and C# reject — so it would have looked like a
two-target bug), and a nested block reset the pending temporary list, producing
`if !t6__` with no `let t6__` above it.

## The result that matters

**The generated Rust compiles verbatim**, pasted into `runtime/src/vm.rs`
without an edit. That is asserted by the build rather than claimed here: the
snippet is in the file under `#[cfg(test)]`, so if it stops compiling, the
build says so.

## What it deliberately cannot do

The divergent parts, and they should not be attempted: the GC write barrier,
the wasm ABI shim, `unsafe` heap access, anything where the three runtimes are
different by nature. Pretending otherwise would produce three things that are
each subtly wrong, which is worse than three that are honestly separate.

The claim is only about 1:1 logic — which is most of an opcode body, most of a
builtin, and most of what has cost time.

## What is undecided

* **Whether the rules stay data.** Four structural transforms already exist
  (hoisting, the loop rewrite, mutability inference, per-call casts). One or
  two more and the "rules are data, translator knows control flow" split stops
  being true, and it becomes a compiler with a config file — which is a
  different and much larger thing.
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
