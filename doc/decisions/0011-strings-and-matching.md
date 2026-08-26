# 0011 — Rope strings, and what to do about regex

> **BUILT** — `runtime/src/rope.rs`, three tiers behind one string type, with
> the flattens counted rather than assumed. `test/ropes.clj` is the standing
> check; it asserts a 64 000-character build materialises nothing.
> Sections below are the reasoning that produced that design; where a section
> weighs an option that was not taken, it is kept for the reasoning, not as a
> description of the tree.

## 1. UTF-8 on the JVM and CLR is not the problem

Conceded — a byte array with code-point semantics on top is straightforward on
either. Strike that from `0010`'s drift list, and keep the *conformance* half:
whatever the representation, `count`, `subs` and indexing must agree across hosts,
and the non-BMP cases belong in the suite because nobody writes them by accident.

## 2. Ropes: feasible, well-trodden, and a good fit here

A tree of string pieces with structure sharing, tiny strings still inlined in the
value. This is not exotic — Boehm, Atkinson & Plass described it in 1995 and V8
ships it today as `ConsString`.

It fits this language particularly well:

- **`str` becomes O(1)** — a cons node. Repeated concatenation is O(n²) with flat
  strings, and Clojure code concatenates constantly.
- **`subs` becomes O(1)** — a slice node over the parent. For an annotator taking
  many substrings of one prompt, or a document reader slicing text runs, that is
  the dominant operation.
- **Sharing is safe because values are immutable**, which is the same property
  that made passing ports by reference sound (`0006`).

The cost is that random access becomes O(log n). For UTF-8 that is less of a loss
than it sounds, because indexing by code point was never O(1) anyway.

### The standard mitigation, and it is the important part

**Flatten on demand, and cache the flat form.** Concatenate and slice freely; the
first operation that needs contiguous bytes flattens once and the node remembers
it. That is what V8 does, and it is what makes the next section work.

### Three tiers, not two: inline, flat, rope

A rope is the answer for big strings and the wrong answer for `"ok"`. Tree
metadata would dwarf the content, and most strings in a real program are short.

- **Inline** — in the value word itself, no allocation at all. Every char is
  inline by construction, which is why there is no separate char type.
- **Flat** — a contiguous byte array with its counts. No tree, no size tables.
  This is the tier that must not be skipped: between "fits in a word" and "big
  enough to want a tree" is most of the strings a program touches.
- **Rope** — the balanced B-tree, for large or heavily concatenated strings.

Write the thresholds down and defend them, rather than letting them emerge.

### The transitions, which are also the retention fix

- tiny ⊕ tiny → **inline** if it still fits, otherwise flat.
- flat ⊕ flat → **flat** while under the rope threshold, otherwise rope.
- **a small `subs` of a large rope COPIES into flat or inline**, it does not
  make a slice node.

That last rule is the same mechanism as the leak I flagged below: `(subs big 0 3)`
must not retain `big`. Tiering and retention are one problem, and copying small
slices solves both.

Going the other way matters too: a rope whittled down by slicing should collapse
back to flat rather than staying a tree with three bytes in it.

### One interface over all three, and `kind` must not leak

Every operation — `count`, `nth`, `subs`, `=`, `hash`, `str`, and the matcher's
cursor — works over all three tiers with no caller branching on representation.
The cursor is what unifies them for matching: inline, flat and rope all present
`next-character` and nothing else is required.

**`(kind s)` is `:string` for all three.** That is not cosmetic — `0005` made
`kind` the closed set protocol dispatch runs on, so a representation leaking into
it would make `extend-protocol :string` work for some strings and not others,
depending on how they were built. That is a bug nobody would guess from the
symptom.

### It must be BALANCED, and that is not a refinement

A naive cons-rope degenerates immediately. `(reduce str "" xs)` builds a
right-leaning spine of depth n, and `subs`/`nth` on it is **O(n)** — worse than
the flat string it replaced, at the operation ropes are supposed to make cheap.
Balance is what makes the structure work at all, not a later optimisation.

Two options, and the second suits this codebase:

- **Boehm-style rebalancing** — the classic: hold the invariant that a rope of
  depth *d* has length at least `Fib(d+2)`, and rebuild when it slips. Binary,
  so depth is `log2 n` and access is a lot of pointer chasing.
- **A B-tree rope with size tables** — wide nodes, shallow tree. At a fanout of
  16–32 a megabyte string is two or three levels deep, which is *near* random
  access in practice rather than merely logarithmic. This is what Ropey and Xi
  do, and it is the same technique as the RRB vectors already in this codebase,
  so it is a sibling rather than a new idea.

Take the B-tree. Three parameters worth writing down and defending:

- **Fanout 16–32.** Depth is what random access pays for.
- **Leaves of ~512–1024 bytes**, not per-fragment. Tiny leaves make the tree deep
  and the metadata dominate the content.
- **Merge adjacent small leaves on concat**, or a thousand two-character appends
  produce a thousand leaves and the invariant is lost by increments.

### Each node carries a code-point count, not just a byte length

We store UTF-8 and index by code point. Without a per-node **count of code
points** alongside the byte length, indexing by code point means scanning — so
`nth` is O(n) again and `count` is too, on a structure specifically built to make
them cheap.

Carrying both is what makes `count` O(1) and `subs` genuinely logarithmic. It is
also the thing that makes the same operations agree across hosts, since a JVM
port storing bytes computes the same counts.

### What a leaf carries, so composing a node never rescans

Composing an internal node must not rescan its children's bytes. It does not
have to, because **both aggregates compose in O(fanout)**:

- **ASCII is an AND.** A concatenation is all-ASCII exactly when every part is.
- **The code-point count is a SUM.**

So a leaf carries, beside its bytes: the **byte length**, the **code-point
count**, and the **ASCII bit**. An internal node carries the same two aggregates
over its subtree, computed from its children at construction — a handful of adds
and ands at fanout 16–32, never a byte scan.

**The scan happens once, at leaf construction, bounded by the leaf size** (512–
1024 bytes). Every composition above that is arithmetic. That is the property
that keeps `str` O(1)-ish rather than O(n).

There is already room for this: the `TY_STR` header has **four unused bytes
between the hash and the data**, which is exactly a code-point count, and bit 18
is already the ASCII flag.

### But the counts must be RELATIVE, never absolute offsets

This is the one that would be expensive to unwind, so it is worth being explicit:
a node stores **the size of its own subtree**, not its start index in the whole
string.

The reason is structure sharing, which §"A tree of string pieces" makes the point
of the design. The same leaf can appear in two different ropes at two different
offsets — `(str a b)` and `(str b a)` share `b`, sitting at offset `(count a)` in
one and offset 0 in the other. A node that recorded its absolute start would be
correct in at most one of them, and sharing is what makes concatenation cheap in
the first place.

**So the absolute position is computed during descent**, accumulating child
counts on the way down, and no node ever knows where it is. That is the standard
B-tree-with-size-tables arrangement and it is forced here rather than chosen.

### Slicing: inherit the flag when you can, scan a bounded range when you cannot

A `subs` that cuts into the middle of a leaf produces a new leaf over a byte
range, and it needs both aggregates for it:

- **If the source leaf is ASCII, the slice is ASCII** — inherit it, and the
  code-point count is the byte length. O(1), no scan.
- **If the source is not ASCII**, the slice still might be, so either scan the
  range or mark it conservatively non-ASCII. Prefer the scan: it is bounded by
  the leaf size and marking conservatively is sticky — a rope whittled down to
  pure-ASCII content would keep paying for a multi-byte character it no longer
  contains.

Finding the cut's byte offset inside a non-ASCII leaf is a bounded scan for the
same reason, so the cost is the same order either way.

**And the flag is always derived from the bytes, never taken from a caller.**
Every construction path sets it by looking, or the diagnostics check that
re-derives it will find out — see the note on cached derived properties below.

### So is the ASCII flag still needed? Per tier, and the answer differs

`str_is_ascii` (bit 18 of the string header) exists for one reason: if every byte
is under 0x80, a code-point index **is** a byte index, so `nth`, `subs` and
`count` are O(1) instead of O(n). It has nothing to do with the encoding —
strings are UTF-8 always — and nothing to do with regex. It is a cached property
*of* those bytes.

With per-node code-point counts, its justification changes tier by tier:

- **Rope: not needed for indexing.** Descending the tree locates code point *k*
  in O(log n) whatever the bytes look like. The asymptotic argument for the flag
  is gone here.
- **Flat: still needed.** A flat string carries one total count, which does not
  locate code point *k*. Without the flag, `nth` and `subs` on a flat string are
  O(n) and splitting one is quadratic — which is exactly the `words` benchmark.
  §"Three tiers" keeps flat deliberately, so the flag keeps earning its place.
- **Inside a rope leaf: a constant, not an asymptote.** Finding the byte offset
  of a code point *within* a leaf is still a scan, bounded by the leaf size
  (512–1024 bytes). The flag makes that step O(1), which is worth having and is
  no longer worth much.

So the two mechanisms are **complementary rather than redundant**: per-node
counts give indexing across the tree, the flag gives it within a flat run. Keep
both, and be clear which is doing what — a later reader who deletes the flag
because "the rope has counts" will make flat strings quadratic and the rope
benchmarks will not notice.

**And it is a cached derived property, so it can be wrong.** A `TY_STR`
allocated without `set_str_ascii` yields a string marked ASCII that is not, and
then `nth` and `subs` use byte offsets as code-point offsets and return **wrong
answers**, silently. The diagnostics build should re-derive the bit from the
bytes on every read and assert it agrees — the same shape that proved
`refresh()`'s write-once fields (8,302 crossings, 0 drifted). Cheap, and the
alternative is a correctness bug that looks like a text-handling quirk.

### String operations must exploit the tree, and that must be measured

The point of a rope is that operations use its structure. The failure mode is an
operation that quietly calls "flatten on demand" and thereby becomes O(n) — at
which point the tree costs memory and buys nothing.

**This is not hypothetical; it is the shape of the bug just fixed.**
`str_index_of` called `&str::is_ascii()` and `from_utf8` per call, each scanning
the whole 32 799-byte haystack, turning a linear scan into 223 million byte
checks and 37 ms of a 55 ms benchmark. A builtin that looks native and therefore
free, doing O(n) hidden work per call, is exactly what a flatten-on-demand rope
invites — and there will be more places to hide it, not fewer.

What must use the structure rather than flatten:

- **`str` / concat** — a tree join, O(1) or O(log n). Never a copy.
- **`count`** — O(1) from the stored counts, both tiers.
- **`nth` / `subs`** — descend; a large `subs` shares subtrees, and only a small
  slice of a large rope copies down into flat (§"Three tiers" already says so).
- **`index-of`, `split`, `replace`, comparison** — walk leaves through the
  cursor. None of them needs contiguous bytes, and `split` in particular is the
  one that made this benchmark quadratic twice.
- **`starts-with?` / `ends-with?`** — one leaf at each end, not a flatten.

**The discipline: count the flattens, do not hope about them.** A diagnostics
counter incremented every time an operation materialises a rope, and a benchmark
assertion that the count is what it should be for that workload. A rope that
flattens on every `index-of` passes every correctness test and is slower than the
flat string it replaced. Only a counter tells the difference, and by now this
project knows what happens to properties nobody counted.

### The cursor cares less than you would think

Worth separating the two access patterns, because they have different costs:

- **`subs` and `nth` are random** and pay for depth. This is what balance buys.
- **The Pike VM's cursor is sequential** — it walks leaves in order, so its cost
  is a byte read plus an occasional leaf advance, near-flat whatever the depth.

So an unbalanced rope would not obviously hurt matching, and would quietly
destroy substring access. That asymmetry is exactly how this kind of bug survives
a benchmark suite that measures the wrong one.

### Two correctness requirements that are easy to miss

**Equality and hash must be independent of REPRESENTATION as well as shape.**
Not merely two rope shapes: `"abc"` inline, `"abc"` flat and `"abc"` as a rope
are one string. They must be `=`, hash identically, and a map keyed by one must
be found by any other. `(str "ab" "c")` and
`(str "a" "bc")` are different trees and the same string: they must be `=`, and
they must hash the same. Get this wrong and maps behave differently depending on
how a key was built — which is exactly the kind of bug that survives every small
test. It also matters beyond correctness: differing hashes would make
content-addressed artifacts differ, which `0010` flagged as not cosmetic.

**A rope must not retain a huge parent through a tiny slice.** `(subs big 0 3)`
holding `big` alive is a memory leak with a plausible-looking cause. Copy small
slices; share large ones; write down the threshold.

## 3. Do host regex engines work over an abstraction? Mostly no.

The honest survey:

- **Java** — yes, in principle. `Pattern.matcher` takes a `CharSequence`, an
  interface, so a rope can implement it. **And it is a trap**: matching is
  random-access and backtracking-heavy, so every `charAt` becomes O(log n), and
  `CharSequence` is UTF-16 while our storage is UTF-8.
- **.NET** — no. `Regex` wants a `string` or a `ReadOnlySpan<char>`, and a span is
  contiguous memory by definition.
- **JavaScript** — no. `RegExp` takes a `string`.
- **Rust** — no. `regex` works over `&str`.

So the answer is **flatten before matching**, which the cache above makes cheap
and which is simpler than the alternative anyway.

## 4. Do not drop regex for PEG. Change the ENGINE, not the syntax.

The instinct behind the question is right — regex is where portability goes to
die — but the diagnosis needs sharpening.

**The feature set here is already the safe one.** `lib/flint/regex.cljc` refuses
lookahead, lookbehind, backreferences and named groups. That is roughly RE2's
subset, and it is the subset that can be matched without backtracking at all.

**But the implementation is a backtracker**, and that is the actual hazard: even
with no backreferences, `(a+)+b` is exponential in a backtracking engine.

### Correcting myself: the 275× is NOT one problem, it is two

I first wrote that the 275× and the ReDoS exposure both come from the engine.
That was too quick. Decomposing it against the measured numbers:

| | flint | JS | ratio |
|---|---:|---:|---:|
| `split` on a **literal** | 4 472 ns | 249 ns | **18×** |
| `split` on a **regex** | 164 425 ns | 598 ns | **275×** |

The literal case has no regex in it at all, so **18× is the interpretation tax** —
what any cljc code pays running as bytecode. If the engine were as efficient
per character as a native one, the regex case would cost about `598 × 18 ≈
10 700 ns`. It costs 164 425.

So the 275× is roughly **18× interpretation × 15× engine inefficiency**, and they
are separate problems with separate fixes:

- the **15×** is recoverable in cljc — it is per-character overhead in this
  engine, and a better one claws it back;
- the **18×** is not. It is the cost of being interpreted, and only native code
  removes it.

Which means a Pike VM in cljc fixes ReDoS and buys maybe an order of magnitude —
landing somewhere near 25–30× rather than 275×. **It does not make regex fast.**
Only a native unit does that.

**So: keep the syntax, replace the engine with a Pike VM / Thompson NFA.** Linear
time by construction, no catastrophic backtracking possible, and:

- it makes the gas accounting in `0009` honest almost for free, because steps are
  bounded by `pattern × input` rather than unbounded;
- it makes construe's ReDoS gate *exact* rather than heuristic — and given
  construe runs model-written patterns, that is worth more than the speed;
- **it aligns the reference with the fast path.** Rust's `regex` crate is already
  a Thompson NFA with no backreferences or lookaround. If the defined subset is
  RE2's, then a Rust unit is a drop-in fast path that agrees with the cljc
  reference *by construction* rather than by testing.

That last point resolves the tension `0010` left open: a portable reference
implementation and a fast native one that might disagree. Choose the subset so
they cannot.

### And PEG as a complement, not a replacement

PEG is the better tool for structured input — recursion, balanced delimiters,
anything regex cannot express — and it is worth having. But replacing regex with
it would break `clojure.string/split`, `replace`, `re-find`, `re-seq` and every
ported program that uses them, to solve a problem the engine swap already solves.

Offer both. Do not make people learn a new notation to split on a comma.

### On other hosts, inlining is a representation choice, not a semantic one

Packing a string into the value word is a NaN-boxing trick, and the JVM and CLR
cannot do it the same way — a reference there is managed and not ours to encode.

They approximate instead: a tagged-long immediate scheme, an interned compact
object, a `char` primitive for the length-one case. Which they choose is their
business.

**What must be identical is the behaviour, not the layout.** `count`, `subs`,
equality, hashing and dispatch answer the same on every host; how a short string
is stored is a port's own optimisation. This is `0010`'s rule again: the
conformance suite is the specification, and representation is exactly the kind of
thing it must not be able to see.

## 5. So: do we write a performant engine for every runtime? No — we write none.

> **CONCLUSION SUPERSEDED BY `0012`.** This section decides to delegate to host
> regex engines with a shared normalisation pass, and offers a `:regex/engine`
> fast-vs-bounded policy choice. Both are dead: stock engines cannot consume a
> rope (`.NET` wants a span, JS a string, Rust a `&str`), so we own the matcher,
> there is nothing to delegate to and nothing to normalise, and one engine that
> is bounded everywhere removes the policy choice.
>
> **The ANALYSIS below is still good** — the survey of which hosts accept an
> abstraction, and the 275× decomposition above it — which is why it is kept
> rather than deleted. Read the conclusions in `0012`.

The honest answer to "can we feasibly build a performant regex for each target"
is that we should not try. Three tiers, and only one of them is new code:

**wasm — adopt Rust's `regex` crate as an optional unit.** Days of work, not
weeks: it is already a Thompson NFA over exactly our subset, so it is linear
time, ReDoS-proof, and fast. This is where flint's regex problem actually is, and
it is the cheapest fix available.

**JVM, CLR, JS — translate the pattern to the host engine.** Our subset (no
backreferences, no lookaround) is a *subset* of what `Pattern`, `Regex` and
`RegExp` all accept, so a flint pattern is already a valid pattern for each of
them. What is needed is not an engine but a **normalisation pass**, because the
common syntax is where they quietly disagree:

- `\w` `\d` `\s` — ASCII-only in JS, Unicode by default in .NET, configurable
  in Java. **Expand them ourselves into explicit character classes** and the
  divergence disappears at the source.
- `$` before a trailing newline, `.` versus newline, Unicode case folding — pin
  each, or reject the construct.

That is a few hundred lines shared across every delegating host, against
thousands per hand-written engine.

**Everywhere — keep the cljc engine as the reference.** It does not need to be
fast. It needs to be *right*, to define what the suite asserts, and to be
available on a host with no acceptable native engine.

### The trade this creates, which must be documented not hidden

Java, .NET and JS regex engines **backtrack**. So delegating buys speed and gives
back the bound: on those hosts a pathological pattern is unbounded again, and the
gas accounting in `0009` cannot see inside a host `Regex` call.

That is a real trade, not a detail:

- **`:regex/engine :native`** — fast, and unbounded on backtracking hosts.
- **`:regex/engine :reference`** — the cljc Pike VM: slow, identical everywhere,
  and bounded by the gas counter.

**Default to bounded wherever untrusted code runs.** For construe that is the
gates, and the gates are on the wasm host where Rust's engine gives both.

## 6. The rule that keeps hosts honest

Semantics are defined by **the cljc reference implementation plus the conformance
suite**. A host may substitute a native implementation *only if it passes*. That
is the same principle `0010` settles on, applied to the one library most likely
to drift.

## What must be true if this is built

- `=` and `hash` agree across all three TIERS and across differently-shaped
  ropes: build the same string inline, flat and as a rope, assert equality, equal
  hashes, and that a map keyed by one is found by the others.
- `(kind s)` is `:string` whatever the tier, and a protocol extended to `:string`
  dispatches for all of them.
- A small `subs` of a large rope copies, and the large one becomes collectable —
  measured by the live set, not read from the code.
- A small slice of a large string does not retain the large one, tested by
  measuring the live set.
- Flattening happens once and is cached, shown by counting flattens across
  repeated matches.
- **Depth stays bounded under adversarial construction.** Build the same string
  a thousand ways — repeated right-concat, repeated left-concat, alternating,
  slice-then-concat — and assert depth and access time do not degrade. Repeated
  right-concat is the degenerate case and belongs in the suite by name.
- `count` is O(1) and `nth` is logarithmic on a rope built by many small appends,
  measured rather than assumed.
- A pattern that is catastrophic under backtracking — `(a+)+b` against a long
  non-matching input — completes in linear time.
- The regex conformance battery passes identically on the cljc engine and on any
  native substitute.
