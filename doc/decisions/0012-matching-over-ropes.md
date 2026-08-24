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

### Which comes first, and the trap in answering "the crate"

Both appear in these notes and they are not alternatives, they are **ordered** —
worth stating because "adopt the Rust crate" is the tempting answer and is only
right under a condition that is about to stop holding.

**Today, strings are flat.** The crate is a drop-in that takes 275× to about 1×
for a few days of work, which is the cheapest large win available.

**Once ropes land it cannot consume the input**, because its API takes `&str`.
Then it means flattening before every match, which hands back the rope at the one
operation touching the most text — the exact thing `0012` exists to prevent. So
the crate is a **stepping stone, not the endpoint**, and it becomes the optional
flatten-and-go-fast unit above rather than the default matcher.

**The endpoint is one design**: a shared cljc pattern compiler emitting an NFA
program, and a native Pike VM simulator per host reading through a cursor. That
is what consumes ropes, what makes gas exact, and what keeps every host executing
the same compiled NFA.

So the sequencing question is only ever: *are ropes close enough that the crate
would be built and then superseded?* If ropes are the next substantial piece of
work, go straight to the native simulator and skip the adapter. If regex is
hurting now and ropes are further off, take the crate and keep it afterwards as
the fast path for flat strings.

## What must be true if this is built

- The simulator consumes a rope cursor and **never** materialises the input:
  asserted by matching over a rope built from many pieces and checking no
  flatten occurred.
- `(a+)+b` against a long non-matching input completes in linear time.
- The same pattern compiles to a byte-identical NFA program on every host.
- Gas accounting bounds a match, tested with a pathological pattern.
- Capture groups are correct against the conformance battery, including the
  leftmost-first alternation cases where engines classically differ.
