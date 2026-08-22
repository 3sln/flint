# 0011 — Rope strings, and what to do about regex

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

### Two correctness requirements that are easy to miss

**Equality and hash must be independent of shape.** `(str "ab" "c")` and
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

## 5. So: do we write a performant engine for every runtime? No — we write none.

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

- `=` and `hash` agree across differently-shaped ropes with the same content,
  tested with adversarially different shapes.
- A small slice of a large string does not retain the large one, tested by
  measuring the live set.
- Flattening happens once and is cached, shown by counting flattens across
  repeated matches.
- A pattern that is catastrophic under backtracking — `(a+)+b` against a long
  non-matching input — completes in linear time.
- The regex conformance battery passes identically on the cljc engine and on any
  native substitute.
