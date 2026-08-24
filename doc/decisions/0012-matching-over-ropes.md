# 0012 — The matcher has to consume a rope, which decides the design

> **NOT BUILT YET — this is a plan, not a description.** Nothing in this file
> exists in the tree. Do not read it as documentation of shipped behaviour, and
> do not treat statements in the README as stale on account of it.

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

### Settled: no stepping stone

I framed the Rust `regex` crate as a cheap interim and left the sequencing open.
The owner has closed it:

> we don't need the rust crate stepping stone; ropes for strings should be our
> next work as soon as the gc/threading/ports bugs are resolved

So **ropes are the next substantial piece of work**, which makes the crate a
thing that would be adopted and superseded within one cycle. Its API takes
`&str`, so the moment ropes land it means flattening before every match — handing
back the rope at the operation that touches the most text.

**Go straight to the endpoint**: shared cljc pattern compiler emitting an NFA
program, native Pike VM simulator per host reading through a rope cursor.

Two things this buys beyond avoiding throwaway work:

- **The rope and the matcher are designed together.** The cursor is the interface
  between them, and building the matcher against a real rope rather than against
  `&str` means the cursor is shaped by its actual consumer instead of retrofitted.
- **The regex work stops being a detour.** It is not "fix a slow engine", it is
  part of building the string layer, which is where the 18× on splitting a
  literal lives too.

The crate keeps the one role `0012` already gave it: an optional wasm unit for a
program that wants maximum speed on flat strings and will pay a flatten. Not the
default matcher, and not on the path to one.

### Order of work, once the runtime bugs are closed

1. **Ropes** — three tiers, balanced B-tree, code-point counts per node, the
   adversarial depth tests (`0011`).
2. **The shared NFA compiler in cljc**, replacing the backtracker's engine while
   keeping its syntax and its refusals.
3. **The native simulator**, wasm first, ~300–400 lines over the shared compiler.
4. Only then the string natives for the remaining `clojure.string` gap.

## What must be true if this is built

- The simulator consumes a rope cursor and **never** materialises the input:
  asserted by matching over a rope built from many pieces and checking no
  flatten occurred.
- `(a+)+b` against a long non-matching input completes in linear time.
- The same pattern compiles to a byte-identical NFA program on every host.
- Gas accounting bounds a match, tested with a pathological pattern.
- Capture groups are correct against the conformance battery, including the
  leftmost-first alternation cases where engines classically differ.
