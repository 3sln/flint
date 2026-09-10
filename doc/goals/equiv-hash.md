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
* ~~**No benchmark exists.**~~ ANSWERED for wasm -- see §7. It is still
  unanswered for the two ports, which is the same gap as the third bullet
  above.

## 7. The baseline, which now exists

`bench/progs/equiv.cljc` and `bench/equiv.mjs`, taken BEFORE the header bit, on
wasm at n=20 000. §6's last bullet asked for exactly this and it is now answered
for one runtime.

| mode | per op | what it is |
| --- | --- | --- |
| `reject-hi` | 75 ns | root count mismatch, O(1) -- the floor |
| `arraymap` | 176 ns | the tier below CHAMP, and most maps in a real program |
| `reject-shared` | 533 ns | rejection after a ONE-PATH descent |
| `nested` | 744 ns | 12 levels deep, two entries per level |
| `share` | 947 ns | 20 000 entries, one path differing -- the short-circuit |
| `identical` | 73 ns | same pointer |
| `fixnum` | 73 ns | returns before the header word is read |
| `reject-deep` | 65 us | shares nothing, differs at one leaf |
| `noshare` | 420 us | shares nothing, fully equal -- the pairwise walk |

REPEATABLE TO UNDER 2% on an unchanged tree, which took two attempts to get.
The first version used a fixed repetition count for every mode, and the modes
span four orders of magnitude; a 2ms signal sitting on a 50ms map build moved
`share` between 477ns and 1119ns across two runs of identical code. The driver
now calibrates per mode and prints the base arm beside the signal, so a row
that did not separate says so instead of printing a number the next run
contradicts.

**ZERO ALLOCATIONS on every row.** That is the fact the design should be held
to: an implementation that allocates per comparison is visible here even where
the clock is not.

WHAT THE ROWS SAY ABOUT THE DESIGN. The 880x between `share` and `noshare` is
the whole argument for the node bit, and it is bigger than the 840us -> 1.2us
that has been quoted at this design from `mapeq.kin`. But note WHICH exits are
actually at risk: `share`, `reject-hi` and `reject-shared` all resolve on
pointer or bitmap comparison and never read a leaf header, so the bit cannot
reach them. `noshare` and `reject-deep` are the rows to watch, and `nested` is
where "once at the root" would become "once per level".

NOT COVERED, and left uncovered on purpose rather than faked: collision nodes
(engineering a collision needs flint's hash rather than a guess, and a mode
that believes it collides and does not would report a clean baseline for a path
never taken), and the two ports (this measures wasm; §6's third bullet stays
open).

### What the baseline found on its way to existing

A benchmark's first job is to be wrong about something. This one was wrong
twice, and the second time it was the RUNTIME that was wrong.

**`reject-lo` was mislabelled.** It was written as "the worst case" -- two maps
differing at the last leaf, forcing a walk to the bottom -- and measured 40ns,
faster than the O(1) root rejection. It was built by `assoc` from the same map,
so it shared every node but one path and the walk pruned the rest. It does
descend to the bottom; down ONE path. Renamed `reject-shared`, and a real
`reject-deep` added beside it.

**`=` ON TWO VECTORS WALKED TWO SEQS.** 20 000 elements cost 1.27ms and FORTY
THOUSAND allocations -- two per element, one seq cell per side -- against
0.42ms and ZERO for a 20 000-entry MAP. `mapeq` had been given a structural
comparison and `seq-eq` was left generic, so the indexable case was the slow
one. `valhash` had already made this move for hashing, which is why hashing a
vector was an indexed loop while comparing two was not.

**AND `hash` OF A VECTOR DID THE SAME THING**, found by the same benchmark one
row later: two allocations to hash a TWO-ELEMENT vector. The cache hid it --
a vector used repeatedly as a map key hashes once -- but the first hash still
walked, and a vector hashed exactly once, every element going into a set,
never reaches the cache at all.

Fixed as `vec-eq-indexed` in `kin/valeq.kin` and `hash-vec-indexed` in
`kin/valhash.kin`:

| operation | before | after | allocations |
| --- | --- | --- | --- |
| `=` on 32 elements | 2 061 ns | 330 ns | 64 -> 0 |
| `=` on 2 000 | 127 005 ns | 20 098 ns | 4 000 -> 0 |
| `=` on 20 000 | 1 273 190 ns | 203 818 ns | 40 000 -> 0 |
| `hash` of 2 | 80 ns | 13 ns | 2 -> 0 |
| `hash` of 2 000 | 74 927 ns | 19 181 ns | 2 000 -> 0 |

GAS IS UNCHANGED TO THE INSTRUCTION, which is the whole difficulty, and the
two functions needed DIFFERENT tick counts. `seq-eq` charges at the top of
every iteration INCLUDING the last one that finds both sides nil, so equal
vectors cost n+1 and a pair differing at index k costs k+1. `hash-ordered`
checks the end FIRST and charges after, so it ticks n and not n+1. A
pre-charge would be cheaper than either and would diverge on every early
exit. Both were verified by diffing probe output against the pre-change code
rather than by reading.

It costs 1 934 bytes on the module floor, priced and recorded in
`test/threads.clj`, which moved two budgets to hold it.

This is the argument for taking baselines before implementations rather than
after. The defect had been in all three runtimes since vectors were written,
`check-builtin-coverage` reported `=` as reached, and the conformance suite was
green on it -- because it was never WRONG, only slow and allocating. Nothing in
the tree was asking the question this file asks.
