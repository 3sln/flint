# Handoff — where the last run stopped

The run of 2026-08-22 was cut off mid-diagnosis by a weekly usage limit, not by a
failure. 54 turns, ~24 minutes. Nothing was committed; **everything is on disk,
uncommitted**, and the tree is mid-flight.

## The bug it was chasing, and how far it got

A waiter token is appearing **in a port's id slot**. The corrupted value it
observed was `16777217` — which is exactly `gen 256 << 16 | idx 0 + 1`, a waiter
token by construction rather than by coincidence.

Its trail, in order:

1. it reproduces with **two threads and a channel and no host port at all**, so
   it is not the host ABI;
2. it needed several **waiter reuses** to appear, so it is about slot recycling;
3. at the deadlock, **two ids had been corrupted to ~2²⁴** and the wake then
   fails to match;
4. its last stated step: *check the weak port table's behaviour across a
   collection.*

That last line is the lead. The generational token from `0006` exists precisely
so a stale reply cannot wake the wrong thread — so a token landing in an id slot
means something is writing through a slot it no longer owns, and a collection
moving or clearing a weakly-held port is the obvious suspect.

Note this sits exactly where `0005` warned the collector would bite: the root set
stopped being one value stack and became N, including parked threads. The stress
test `0005` asked for — spawn, park, collect at every allocation, resume with
values intact — is the thing that would have caught this, and the agent was
checking whether it exists when the limit hit.

## Also outstanding

- **`host/docstore.mjs` drops one node.** Diagnosed in the previous handoff and
  still unfixed: `next()` does `if (at < 0) { i += 1; continue; }`, which turns
  "could not resolve this node" into "pretend it was delivered". `resolveUpTo`
  advances `planIdx` unconditionally, so a node straddling two plan entries is
  never pushed and becomes unreachable. Test showed 64 waves host-side, 63
  guest-side, 65 536 bytes missing — one whole budget.
- **`0009` resource limits** are written and untested-in-tree: gas measured
  deterministic at 16 693 instructions over five runs, catchable with
  spent/limit/thread, and an error that escapes a handler which catches and
  loops. Plus a real find of its own: **a failed allocation returned `nil` and
  the program carried on with wrong data** — a capped run reporting `:total
  3932160` instead of `13107200`.
- `test/limits.clj` and `test/limits.mjs` are new and uncommitted.

## Ground truth, so it is not re-litigated

`doc/decisions/0010`, `0011` and `0012` are **roadmap, not description**. There
is no rope and no PEG matcher in this tree; `flint.regex` is still the
backtracking engine. All three carry a NOT BUILT YET banner now. The README's
regex and ReDoS claims are current and need no re-deriving.
