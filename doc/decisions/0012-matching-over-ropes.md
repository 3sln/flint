# 0012 — The matcher has to consume a rope, which decides the design

> **BUILT** — `runtime/src/pike.rs`, one shared NFA with two simulators (the
> cljc reference and the native one) over a rope cursor. `re-pattern`,
> `re-find`, `re-matches` and `re-seq` are in `clojure.core`; `test/pike.clj`
> is the standing check, and it asserts the catastrophic case stays linear
> (`(a+)+$` over 24 and 48 a's: 37 ms and 38 ms).

> **PREREQUISITE, and it is hard: ropes exist first.** Every experiment,
> benchmark and tuning decision in this document must be run against the string
> representation that will actually ship — the three tiers of `0011`, cursor and
> all — not against flat strings. A matcher tuned on flat bytes is tuned on a
> configuration flint will not have: the cursor's cost model changes (a byte read
> plus an occasional leaf advance instead of a contiguous index), the prefilter's
> `str-index-of` fast path changes shape, and any per-position number measured on
> flat is measuring something else. Build `0011` §1–2 first, then this, and take
> every number here on the rope.

Supersedes the delegation half of `0011 §5`. The objection that forced it:

> It won't work over our rope strings though, the stock regex engines for
> jvm/clr right?

Correct, and it undoes "flatten before matching". Flattening to hand a `String`
to `Pattern` or `Regex` materialises the whole thing on every host that
delegates — which is most of the value of a rope given back at the one operation
that touches the most text.

So if ropes are the representation, **we control the matcher.** That is settled
by the data structure, not by preference.

## Which then answers PEG versus regex, and not the way I expected

The question is not which notation is nicer. It is **which matcher can consume a
rope without rewinding**, because rewinding is what a rope is bad at.

- A **backtracking** matcher rewinds constantly. That is what our current engine
  does, and what Java, .NET and JS all do internally.
- **PEG also rewinds.** Ordered choice *is* backtracking: try the first
  alternative, fail, restore the position, try the next. So PEG has exactly the
  property that hurts here.
- A **Pike VM** — Thompson NFA simulation — **never rewinds.** It makes one
  left-to-right pass, carrying a set of live threads, consuming each character
  once.

That last line is the whole decision. A Pike VM needs nothing from its input but
`next-character`, so it runs over a **rope cursor** natively, with no flattening
and no random access. It is not merely compatible with ropes; it is the shape a
rope wants.

And it keeps everything else we wanted:

- **Linear time by construction** — no catastrophic backtracking, so the ReDoS
  hazard is gone rather than mitigated.
- **Exactly countable** for `0009`'s gas accounting: a step is a thread-step, and
  the simulator is the thing doing the counting.
- **Regex syntax stays**, so `split`, `replace`, `re-find` and every ported
  program keep working. Nobody learns a new notation to split on a comma.
- **It agrees with Rust's `regex` crate by construction** on the wasm host, since
  that crate is the same algorithm over the same subset.

PEG remains worth having later for *structured* input — recursion, balanced
delimiters — which regex genuinely cannot express. It is a complement, and it is
not the answer to "fast string search over a rope".

## "Native for each env" is smaller than it sounds

Split the work where the semantics live, and only the hot half is per-host:

**Shared, in cljc — the pattern compiler.** Parse the pattern, build the NFA,
emit a small program. Runs **once per pattern** and the result is cached, so it
is not on the hot path and does not need to be fast. Being shared is what stops
the dialects drifting: every host executes *the same compiled NFA*, so there is
no per-host pattern parser to disagree about `\\w`.

**Per host, native — the simulator.** For each input position, step the thread
list. That is a few hundred lines in Rust, Java or C#: a couple of arrays, a
sparse set for dedup, and a loop. The known-fiddly part is **capture tracking**,
which costs a slot vector per thread; Russ Cox's writing is the reference.

So "native for each env" is **~300–400 lines per host over a shared compiler**,
not an engine per runtime. That is feasible in a way three regex implementations
never were, and the shared front end means the conformance suite has much less to
police.

## What this changes elsewhere

- **`0011 §5` is superseded** where it said to delegate to host engines and
  normalise `\\w`/`\\d`/`\\s`. There is no delegation, so there is nothing to
  normalise — we define the classes once, in the shared compiler.
- **The `:regex/engine` policy choice disappears with it.** It existed because
  delegating gave back the bound; our own Pike VM is bounded everywhere, so there
  is one engine and it is always accountable.
- **Flattening is still needed for host interop** — handing a string out through
  a port, or to a host API — but no longer for search, which was the operation
  that made it hurt.
- **The Rust `regex` crate stays available** as an optional wasm unit for programs
  that want maximum speed and are willing to flatten. Same subset, same
  semantics, so it is a drop-in rather than a second dialect.

### Settled: the Pike route, WITH its native simulator

Two narrowings from the owner. First, no interim:

> we don't need the rust crate stepping stone; ropes for strings should be our
> next work as soon as the gc/threading/ports bugs are resolved

Then:

> forget about the rust crate or native regex for now and focus only on the pike
> route, simplicity; we can add the alternative flattening path later if actually
> needed/wanted

**I read "native regex" as including the native Pike simulator and wrote this
section down to a cljc-only first pass. That was wrong.** "Native regex" means
the HOST engines — `Pattern`, `Regex`, `RegExp` — and the Rust crate; the
"alternative flattening path" is exactly those, since they are what require a
flat buffer. The owner's correction:

> why no native simulator in the first pass for Pike VM? That seems like the
> whole thing that makes the pike vm route feasible for this job.

Which is right, and the arithmetic says so. A cljc simulator is interpreted, so
it pays the 18× interpretation tax from `0011`: it recovers the 15× engine
inefficiency and lands near **25–30× slower than JS**. Better than 275×, and
still too slow for annotator-shaped work — so a cljc-only pass would have bought
correctness and boundedness while leaving the number that disqualifies flint
roughly intact.

**The native simulator is the point.** It is what gets to parity AND consumes a
rope, which is the combination nothing else offers: host engines and the Rust
crate are fast and need a flat buffer; a cljc simulator reads a rope and is slow.

### So the first pass is all three parts

1. **The pattern compiler, in cljc, shared.** Parse, build the NFA, emit a
   program. Once per pattern and cached, so it is not hot and does not need to be
   fast. Being shared is what stops the dialects drifting.
2. **A reference simulator, in cljc.** Not for speed — it is the conformance
   oracle, it lets the compiler be tested before any native exists, and it is
   what a new host runs on day one before it has its own.
3. **The native simulator, in Rust, for wasm.** The hot loop: two thread arrays,
   a sparse set for dedup, capture slots, and a loop over the NFA ops. A few
   hundred lines, and the fiddly part is captures and leftmost-first semantics.

What the owner's "simplicity" does rule out is a **third** implementation: no
Rust `regex` crate adapter, and no delegation to host engines. Two
implementations of one shared NFA program, one of which exists to check the
other.

**A consequence worth noting: the rope must be Rust-side.** The native simulator
reads through a rope cursor, so the rope lives in the runtime beside it — which
is where `0011` already puts it (`strs.rs`), and it is now load-bearing rather
than incidental.

### Order of work, once the runtime bugs are closed

1. **Ropes** — three tiers, balanced B-tree, code-point counts per node, the
   adversarial depth tests (`0011`).
2. **The shared NFA compiler**, plus the cljc reference simulator, replacing the
   backtracker's engine while keeping its syntax and refusals.
3. **The native simulator for wasm**, reading through a rope cursor, checked
   against the reference by the conformance battery.
4. Other hosts get their simulator when they get their port (`0010`).

## The compiled program is a cached value, and it must be collectable

A pattern compiles once and is reused; that is what makes the shared compiler
affordable, since it runs per pattern rather than per match. But a cache that
never releases is a leak, and patterns are not all literals — `(re-pattern (str
"^" prefix))` in a loop generates a fresh source string every iteration.

**The compiled program IS the pattern value.** A `TY_REGEX` heap object holding
the NFA, interned on `(source, flags)` in a **weak** table, exactly as strings
and keywords already are (`weak_interns_drop_dead_entries_and_forward_live_ones`
is the existing test for that machinery). Two `(re-pattern "abc")` share one
object; when no live value refers to it, the collector takes it and the entry
drops.

Two things follow that are worth stating rather than discovering:

- **A `#"…"` literal lives as long as the module, and that is correct.** It is a
  constant in the const pool, `roots.consts` is traced, so it is strongly held by
  construction. That is not the leak; the leak is dynamic patterns.
- **Thrash is bounded by ordinary liveness.** A pattern used inside a loop is
  held by the local or the constant that names it, so it is not collected between
  iterations. Only a pattern whose last reference dies is recompiled if asked
  for again — which is the behaviour you want and needs no LRU on top.

### The trap: caching must not make gas non-deterministic

`0009` made the instruction budget deterministic — the same program reports the
same count on every machine, which is the property construe's gates rest on. A
cache threatens that in a way that is easy to miss: **whether a compile happens
depends on whether a collection ran**, and a collection depends on allocation
history.

So if compiling charges gas, the same program can report two different counts.
That is exactly the flakiness the gas counter was built to replace.

**Charge the gas at every `re-pattern`, cache hit or not**, priced on the
compiled program's size. Then the cache saves wall-clock and never moves the
count. It also closes the hole `0009` names in its own terms: a native whose cost
is not O(1) must charge for what it did, and compiling a pattern is real work
that would otherwise be free.

### And bound the compiled size

Counted repetition expands: `(a{100}){100}` is a small pattern and an enormous
NFA. That is a memory-exhaustion path with a friendly-looking source, and it is
the same hazard as ReDoS arriving through the compiler instead of the matcher.

Bound the program size, refuse a pattern that exceeds it **naming the limit**,
and charge gas in proportion to what was built. The `0009` memory cap catches it
eventually; a specific refusal is a better answer than an out-of-memory error.

## What must be true if this is built

- The simulator consumes a rope cursor and **never** materialises the input:
  asserted by matching over a rope built from many pieces and checking no
  flatten occurred.
- `(a+)+b` against a long non-matching input completes in linear time.
- The same pattern compiles to a byte-identical NFA program on every host.
- Gas accounting bounds a match, tested with a pathological pattern.
- Capture groups are correct against the conformance battery, including the
  leftmost-first alternation cases where engines classically differ.
