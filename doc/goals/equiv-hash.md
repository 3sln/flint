# Goal — a value can bring its own equality, and a collection full of them stays fast

## Status

DESIGN, not shipped. Nothing here exists in the tree: there is no `Equiv`, no
`Hash`, no header bit, and no node propagation. `flint.protocols` holds
`Printable` and nothing else, and `=` and `hash` are still one line each into
the runtime's closed switch:

```clojure
(defn = ([a b] (flint.rt/= a b)))
(defn hash [x] (flint.rt/hash x))
```

**This document exists because the design was settled in conversation and
written nowhere**, while `doc/goals/extern-refs.md` part two argued the
opposite conclusion in writing. Two documents disagreeing is the state this
repo has already paid for once: four documents said the ports had no collector
for eleven days after they grew one, and a design draft reasoned confidently
from them before hitting the source. §5 below supersedes that entry by name.

## 1. The problem, which is nesting rather than equality

`flint.rt/=` is implemented in the runtime and recurses into nested values
itself. So a protocol dispatched in the library can never see a value that sits
*inside* a map or a vector: `clojure.core/=` hands the whole structure to the
runtime and never gets control back.

That is why the obvious library-level answer is not merely slow but wrong. It
would make `(= a b)` honour an implementation and `(= [a] [b])` ignore it,
which is worse than not having it at all.

`find-protocol-method` already consults `(meta x)` before the kind table, so a
per-value implementation is *expressible* in flint today. `=` simply does not
look, and could not usefully look only at the top level.

## 2. What it must not cost

A program with no extended values must pay nothing measurable. Two facts set
the bar:

* `mapeq`'s structural short-circuit takes 20,000 entries from 840us to 1.15us
  by pruning a shared subtree on pointer equality. It is sound only because
  both sides are the runtime's own.
* `=` and `hash` are the hot path in a persistent-collection runtime, and the
  cross-runtime budget (1.5x) is measured on exactly these paths.

Any design that adds a metadata lookup per comparison is dead on arrival. Any
design that adds an indirect call per comparison spends the `mapeq` number.

## 3. The mechanism

**A leaf bit, in the object header.** The header word carries the type tag in
bits 24-31, GC age in 21-23 and the mark in bit 20. **Bits 0-19 are unused.**
One of them says "this value carries a runtime-honoured implementation".

`with-meta` stamps it, once, when the metadata map actually carries the key.
The expensive part -- a map lookup for a protocol key -- happens at attach
time and never at comparison time. The bit cannot go stale, because values are
immutable.

Reading it is free: `val-eq` already loads that word to read the type tag, so
the test is one AND and a predictable branch on a register that is already
hot, after the double / identity / fixnum early-outs have returned.

**A node bit, which is the half that matters.** A CHAMP node carries the UNION
of its children's bits, maintained at assoc. A map containing no extended
value therefore does no per-level work during a descent -- the question is
answered once at the root, not once per level, and `mapeq`'s short-circuit
runs untouched.

On dissoc the bit is LEFT ALONE. It is a conservative over-approximation: a
stale-high bit costs a slower path and never a wrong answer, because the slow
path still tests each leaf's own bit and finds none. So removal needs no
recomputation and no child dereferencing.

> The exact version, if a measurement ever asks for it: CHAMP already carries
> `datamap` and `nodemap`, so a third `overridemap` bitmap makes removal
> `overridemap &= ~(1 << pos)` -- O(1), exact, no pointer chasing. One extra
> word per node. Not now.

**The callback needs no new machinery.** `invoke` runs a nested interpreter
loop and `vm.rs` says it "is safe to re-enter because all VM state lives in
`Rt`" -- it is already used for lazy-seq forcing, comparators and
higher-order builtins, and `MAX_FRAMES` already answers unbounded recursion
with `StackOverflowError`.

> NOT the same as the extern-refs cliff. That one is about PARKING inside a
> native frame, which `vm.rs:568` refuses. An equality callback calls; it does
> not park. A user implementation that itself parks hits the cliff, and that
> is the same open question extern refs has.

## 4. Three protocols, not two

`Equiv` without a matching `Hash` silently corrupts maps: two values that
compare equal and hash differently land in different buckets, and the symptom
is a map that has lost a key. Clojure has the same hazard and lives with it.

Refusing the pairing at `with-meta` was the first answer and it is the worse
one -- a runtime check, reported far from the mistake. Instead:

| protocol | means |
| --- | --- |
| `Equiv` | this value knows when it equals another |
| `Hash` | this value knows its own hash |
| `EquivHash` | both, consistently -- **required to be a map key or a set element** |

The requirement becomes structural. A value carrying only `Equiv` is a legal
value and compares as it says; it is simply not admissible as a key, and the
refusal names the protocol it is missing rather than describing a symptom.

## 5. What this supersedes

`doc/goals/extern-refs.md` part two, tier 3, lists `=` and `hash` as "the
strongest no in the document", on three grounds. Against the code as it stands
that is correct, and it is worth saying which grounds this design answers and
which it does not:

| the objection | status |
| --- | --- |
| `mapeq`'s 840us -> 1.15us short-circuit is sound only because both sides are the runtime's own | **answered.** The short-circuit runs whenever the node bit is clear, which is every collection containing no extended value. |
| re-entrancy: a protocol `=` is called from inside map lookups | **answered, by machinery that already exists.** `invoke` is re-entrant by construction and `MAX_FRAMES` bounds it. |
| the `=`-implies-equal-hashes invariant is silently breakable | **not answered by the bit.** Answered by `EquivHash` in §4 -- structurally, at the point a value is used as a key. |

The tier-3 entry was written against a premise this design changes: that any
extension means consulting metadata on the hot path. It does not, because the
lookup moves to attach time and the node bit keeps it off the descent.

## 6. Open

* **`TY_TAGGED` cannot carry metadata.** `has-meta` covers SYM, VEC, ARRAYMAP,
  HASHMAP, SET, CONS, EMPTY_LIST, LAZYSEQ, ATOM and CLOSURE. A tagged value is
  the most natural carrier for a domain value in a language with no `deftype`,
  so it likely needs a meta slot for any of this to be much use. That is a
  layout change, and it is the first thing to decide.
* **Gas.** The callback is a user call from inside equality and must be charged
  like any other. Where the charge goes -- per callback, or folded into the
  comparison's existing charge -- is undecided.
* **All three runtimes must agree on the bit**, and the ports read the header
  the same way, so this looks like one change rather than three. Unverified.
* **What `hash` does for a value whose `Hash` throws.** `=` has an answer
  (propagate); `hash` is called from inside bucket selection and has less room.
* **No benchmark exists.** Every cost claim in §2 and §3 is read rather than
  run. The first implementation step is a benchmark that can show the
  unextended path is unchanged, not an implementation.
