# Guiding cases for a kin macro or specialisation system

Kin has `defn`, `defconst`, `defdata` and `defstruct`. There is no macro, no
template, and no way for one source to emit two specialised copies of the same
algorithm. This file is the evidence for what such a facility would have to do,
gathered from flint rather than imagined: every case below is duplication that
EXISTS, in generated code, today.

It is input for designing that system. It is not a request for a particular
design.

## The shape: two byte trees, one set of algorithms

Flint has two tree-of-bytes types. A STRING is a rope: flat leaves under
branch nodes, with a cached hash and a byte count per node. A BYTE STRING is
the same picture with different slot numbers, a different type tag, and a
different accessor for reading a byte out of a leaf.

Every algorithm over one exists over the other. The pairs, with sizes:

| strings | bytes | lines |
| --- | --- | --- |
| `ropeflat.kin` | `byteflat.kin` | 182 / 173 |
| `ropecat.kin` | `byteconcat.kin` | 202 / 169 |
| `ropenode.kin` | `bytenode.kin` | 121 / 80 |
| `ropeeq.kin` | `byteeq.kin` | 118 / 106 |
| `ropeslice.kin` | `byteslice.kin` | 105 / 94 |
| `ropemeas.kin` | `bytecore.kin` | 87 / 82 |

About 1,500 lines, and the algorithms in them are one set written twice.

## Case 1 — the cached tree-fold, which is exact

`rope-hash` (`ropeflat.kin`) and `b-hash` (`bytehash.kin`). Walk a leaf with
`h * 31 + byte`; return the cached hash if the node has one; otherwise fold the
children with `h * pow31(child bytes) + child hash`, cache, return.

Diffed as EMITTED Rust, the fold is line-for-line identical under six
substitutions. `pow31` is ALREADY shared -- `ropeflat.kin` requires it from
`bytehash.kin` -- so the arithmetic converged and the walk around it did not.

The two results are allowed to differ and do: the string side applies a final
mix, the byte side does not. That is legal because a byte string is only ever
`=` to another byte string (`valeq.kin` tests both sides). **So an instance
must be able to differ in a step, not only in its accessors.**

## Case 2 — tree equality, where one instance does MORE

`tree-eq` (`ropeeq.kin`) and `b-eq` (`byteeq.kin`), ~100 lines each. Normalise
the names and diff the generated Rust and you get three differences: the count
accessor, the leaf-length accessor, and one extra block.

That block is the interesting part. The string side has a fast path that
compares two flat leaves with `run-eq` -- a memcmp -- and falls back to the
byte loop when either side is not heap-allocated. The byte side has only the
loop.

**So specialisation cannot be substitution alone.** An instance has to be able
to add an optimisation the other does not have, without forking the algorithm.

## What varies, across all the pairs

Nine things, and they are all names or numbers:

    is-a-tree?        is-rope            is-brope
    byte count        s-bytes            b-count
    leaf length       leaf-len           olen
    leaf byte         leaf-byte          read-u8 at HDR + i
    hash slot         RP_HASH            BB_HASH
    children base     RP_KIDS            BB_KIDS
    child count       rope-kids          olen - BB_KIDS
    data offset       STR_DATA           HDR
    type tags         TY_STR TY_ROPE     TY_BYTES TY_BROPE

Nothing structural varies. No control flow depends on which tree it is.

## What this implies

* Parameterise over ACCESSORS and CONSTANTS, since that is all that differs.
* Let an instance override or extend a step (case 2), not only fill in names.
* Let an instance differ in result where the types are not comparable (case 1).
* Emit two ordinary functions. The alternative flint considered -- one function
  branching on the type per node -- costs a branch in a hot path, and the
  recursion prevents hoisting it out of the loop. That is why this is a kin
  question and not a flint one.
* Keep the generated output readable. `bin/check-kin` diffs it, and a person
  reads it when a runtime disagrees.

## Case 3 — the same walk, two ways of stepping

Found twice, independently, in files that know nothing about each other:

* `hash-vec-indexed` vs `hash-ordered` (`valhash.kin`)
* `vec-eq-indexed` vs `seq-eq` (`valeq.kin`)

In both, the second function DISPATCHES to the first when the value is a plain
vector, and otherwise carries its own copy of the same walk. The charging, the
early exit, the accumulation are identical. What differs is how the walk
STEPS: an index into `vec-nth` against a cursor moved by `first`/`next`, and a
termination test to match.

Two unrelated authors solved the same problem the same way, which is what makes
it a case rather than an accident. **And it is a different axis from cases 1 and
2**: nothing here is an accessor rename. A facility built only for substituting
names and constants would not cover it.

## Case 4 — the same walk, a different arity at the step

Inside one file. `node-entry-sum` vs `node-key-sum` in `collhash.kin`, and
`map-entry-sum` vs `map-key-sum` beside them. Identical CHAMP walk -- collision
loop, datamap loop, nodemap recursion, same charging -- differing only in what
is accumulated: `entry-hash(key, val)` against `hash-value(key)`.

A two-argument call against a one-argument call. **A third axis.**

## Case 5 — persistent and transient vectors, WHICH MAY NOT NEED THIS AT ALL

`push-tail` / `t-push-tail`, `do-assoc` / `t-do-assoc`, `vec-conj` /
`tvec-conj`, and the `assoc`/`pop` pairs. Same trie descent; the transient
threads an `edit` token, swaps `node-clone` for `ensure-editable`, and drops a
nil-check the persistent path needs because it allocates.

READ THIS ONE WITH THE NEXT SECTION. Maps solved the identical problem without
duplicating anything, and the vector half may simply be able to follow.

Two details worth keeping either way. `pop-tail` is NOT duplicated -- the
transient requires it and calls it unmodified -- so whatever the answer is, it
has to be per-FUNCTION and not per-module. And the transient does LESS defensive
work than the persistent, not more, so "specialise by adding" is not the only
direction.

## The boundary: what ordinary kin already handles

This is the most useful thing the audit found, and it scopes the facility more
than the cases do.

* **Maps and sets, persistent vs transient.** `node-assoc` and `node-dissoc`
  take an `edit` parameter. `mapwrite.kin` passes `NIL`; `maptrans.kin` passes
  a token. ONE implementation, two behaviours, no code generation -- just a
  runtime value. Vectors have the same split and wrote it twice (case 5).
* **Upper and lower casing.** `change-case` and `case-map` take a `^Bool up`.
  One walk, two directions.
* **Sets on maps.** `setcore.kin` delegates -- every operation calls into
  `mapcore`/`mapread`/`mapwrite`/`mapeq`. Composition, not duplication.
* **Tables on vectors.** `tableref`/`tablerow` require `vecread`/`vecwrite` and
  call them. A table is BUILT ON a vector rather than being a second one.

So the question to ask of any proposed case is not "do these look alike" but
"can a plain parameter already express the difference". Where it can, kin needs
nothing new. **The facility is for what a value cannot carry**: accessor and
constant substitution, a step present on one side only, a different way of
iterating, a different arity at the step.

## Refuted, so nobody re-checks

* maps vs sets -- delegation, no duplicated body
* vectors vs tables -- tables call vector functions
* persistent vs transient for MAPS and SETS -- already one implementation
* persistent vs transient for TABLES -- `tabletrans.kin` is a genuinely
  different algorithm, a column-batched bulk fill built because the per-row
  path copied a 256-row chunk 256 times
* a string twin for `bytetwrite.kin` -- strings have no transient builder at
  all, so this is an asymmetry in features, not duplication

## What is NOT evidence for this

`set-eq`, `hash-mask`, `bitpos`, `index-of` and `vec-count` were all written
once per runtime by hand and were ported to kin as ORDINARY functions in
2026-09. They needed no specialisation -- they needed somebody to notice they
were portable. A macro system should not be justified by those.
