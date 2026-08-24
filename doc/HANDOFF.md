# Handoff — the document wave loss

**Discard any link to the GC/scheduler corruption.** That bug is fixed (`34d0092`:
`run_program` held the argument vector in a Rust local across every module
initialiser). Fixing it did not move the document numbers **at all** — still 63
waves, 4 128 768 bytes, missing exactly 65 536. They are independent.

## What the wave loss actually is

Established this session, each step measured on the real failing run with no
stress mode:

1. The **host is not at fault.** `stats.waves` is 64 and `deliver()` is called
   64 times for waves (65 including the structure reply), with exactly one
   `final: true`, on the last one. The `at < 0` branch in `next()` was verified
   by reading the current source: it raises, it does not skip.
2. The **ports are not at fault.** Runtime counters show 65 enqueues and 65
   dequeues on both hops — the host port and the rpc channel. Every message
   crossed.
3. `drain-each` takes its **else branch exactly once**. Proved by making the
   else branch `(- n 1000)`: the result is `-937`, i.e. 63 increments and one
   miss. So one reply fails `(contains? msg :body)`.
4. That reply is **not** nil, a map, a string, a vector, a keyword, a number, a
   seq or a set, and `(count msg)` is 0. `pr-str` gives `#<unprintable>`.
5. At the port boundary it is a **`TY_FWD` object — a forwarded pointer.** With
   the dequeue instrumented: value type `TY_FWD`, inbox type `TY_VEC` (well
   formed), port type `TY_PORT` (well formed), `head = 0`.

So a **port inbox vector holds a pointer the collector moved and did not
update**, and `port_dequeue` hands that stale pointer to the script as a
message. Nothing is enqueued wrong — the same check at `port_enqueue` never
fires.

## Where to go next

The port, the inbox vector and the head index are all sound, so the untraced
edge is *into* that vector. Two candidates, in order:

* **The inbox is reached through a stale port.** Ports are held strongly only by
  whatever the script holds; the scheduler keeps **ids**, and `port_by_id` goes
  through the **weak** `INTERN_PORT` table. If a lookup can return a port
  address that a collection has moved, `port_send` enqueues into a stale copy
  and the live port keeps an older inbox. `peer_of` is the path to check.
* **The write barrier on `PT_INBOX`.** `port_enqueue` does
  `set(port, PT_INBOX, new_vec)` through `gc::set_slot`, which remembers an old
  object pointing at a young one. Confirm the barrier fires for a port that has
  been promoted, and that the remembered set survives the collection that
  promotes it.

**Narrowed further, with the `obj::slot` assertion forced on in a wasm build:**
the forwarded pointer is read from **slot 1 of a `TY_NODE`** — a vector internal
node holding a stale element. So the untraced edge is into a node of the inbox
vector, not into the port itself.

That measurement needs one caution, learned the hard way: the collector reads
forwarded pointers constantly while updating them, so any such check MUST
exclude it. Without that exclusion the first hit was `TY_PORT` slot 3, which was
just the collector scanning a port, and it sent this diagnosis down the wrong
road for a while.

Also ruled out by direct audit: nothing bypasses the write barrier. The only
three uses of `set_slot_raw` are inside `gc.rs` itself (the barrier, the
collector's own update, and one test that is deliberately unbarriered), and
`init_slot` forwards to `set_slot`. `port_enqueue` stores the new inbox through
`Rt::set` -> `Gc::set_slot`, so the port -> inbox edge is barriered.

## The trie / barrier-granularity hypothesis is disproved

Worth stating flatly so it is not tried again. The idea was that
`port_enqueue` mutates a slot inside a vector *internal node* while the barrier
records the vector, so the node is never descended into — and that this would
explain why it takes many waves, since a vector under ~32 elements has no
internal node at all.

Measured at the failing dequeue:

    kind = 0 (a flint channel, not the host port)
    port id = 7, cap = 1, inbox length = 1, head = 0

**A one-element vector lives entirely in its tail. There is no internal node to
miss.** The `TY_NODE` the assertion named is the vector's tail node, and slot 1
is element 0 of it, because `node_set` writes `i + 1` (slot 0 is the transient
edit field). The prediction that the failure needs ~32 in-flight messages is
contradicted by the same measurement: there is exactly one.

The audit backs that up independently: `vec_conj` is fully persistent —
`node_clone` for the tail, `push_tail` clones the path, and every write goes
through `node_set` -> `Gc::set_slot`, which is barriered. No old node is ever
mutated in place.

## And the port is not a stale copy

The other discriminator was run too. At the failing dequeue, `port_by_id(id)`
returns **the same address** as the port being dequeued from, so this is the
live port, not a copy left in unreused from-space.

So the whole chain — port, inbox vector, tail node — is live and well formed,
and the tail's element 0 is a forwarded pointer.

## What that leaves

The message was evacuated and this one slot was not updated, while everything
above it was. Since the chain is barriered and persistent, the remaining
explanation is that the chain **was not traced at the collection that moved the
message** — the port was unreachable then, and became reachable again after.

There is a mechanism that would let exactly that happen without being noticed:
`INTERN_PORT` is weak, and `minor`'s refresh only drops entries whose objects
live in from-space. An **old** port is kept unconditionally:

    if v.as_heap().wrapping_sub(from) < half { ...forward or drop... }
    else { Some(v) }                    // old: kept, reachable or not

So an old, unreachable port stays in the table and `port_by_id` keeps handing it
back — which is consistent with "looks live, was never traced". The next step is
to establish whether the port really is unreachable at that collection: mark the
port at enqueue and check, at the next collection, whether tracing reaches it.
`rx` should be on the parked caller's saved stack and `tx` in the client's
`:waiting` map, so if it is genuinely unreachable, one of those two roots is not
what it appears to be.

## The decisive test: a forced major fixes it, at any cadence

`collect_now()` is a full major and is already exported, so this needed no
runtime change — the host calls it from the document capability's `poll`, which
runs once per pump.

    major every pump   -> 64 waves, 4194304 bytes   (correct)
    major every 2      -> correct
    major every 4      -> correct
    major every 8      -> correct
    major every 16     -> correct
    no forced major    -> 63 waves, 4128768 bytes   (the bug)

**This is monotone**, and that matters. The slice and `reap_ports` results were
not: some values passed and some failed, which is the signature of a timing
perturbation. Here *every* cadence fixes it and only the absence fails. That
points at something a major does structurally.

It does **not** confirm the weak-table lead, and it is honest to say so: forcing
majors *prevents* the failure rather than probing the port's reachability while
it is in the bad state. The sweep test as posed cannot be run this way.

What a major does that a minor does not, in the order to check them:

1. **Weak tables are refreshed by MARK rather than by forwarding.** `minor`
   keeps every entry outside from-space unconditionally, so an unreachable OLD
   port survives minors indefinitely; `major` drops it. This is the lead.
2. It sweeps old space, so an unreachable old object's memory is actually
   reclaimed rather than lingering intact.
3. It runs an additional minor.

The next step is the reachability walk rather than another forced-collection
experiment: after a minor, mark from the roots and ask directly whether the port
being dequeued from is in the reachable set. Do it only for ports in `SC_PORTS`
and only every Nth collection, so the cost is bounded. That answers the question
the sweep test could not.

One caution carried from earlier in this file: a check like that MUST exclude
the collector's own reads, and any flag it uses must live on `Space` rather than
in a global, or parallel `cargo test` silences it across Rts.

## Technique: monotonicity separates a cause from a timing perturbation

Worth keeping as a general test, because it is what finally sorted signal from
noise on this bug. Several things "fixed" the wave loss:

| knob | behaviour | verdict |
|---|---|---|
| scheduler slice size | 1024 ok, 2048 ok, **4096 fails**, 8192 ok, 65536 much worse | **non-monotone** |
| `reap_ports` on/off | off fixes it, but the same ports are reaped in passing and failing runs | non-monotone / no differential |
| guest code shape | an extra loop variable or a `throw` fixes it | non-monotone |
| **forced major GC** | every cadence from every-pump to every-16 fixes it; only absence fails | **monotone** |

A knob that fixes a bug at some values and not others is moving *when* something
happens — it is a timing perturbation, not a cause, and chasing it wastes runs.
A knob that fixes it at every setting is doing something structural. Apply this
before investing in any "X makes it go away" result.

## The weak-table lead is dead, by direct measurement

The reachability walk was triggered from the moment the stale pointer is read —
not sampled — so it is guaranteed to observe the bad state. At that instant:

    port      REACHABLE   young = 0 (old)
    inbox vec REACHABLE   young = 1 (young)
    tail node REACHABLE
    port in_remset FLAG = 1   and in the remembered LIST = 1

So the chain is reachable, the port is old with a young inbox — the classic
old-to-young edge — and it is properly recorded in the remembered set by **both**
the header flag and the list, which can disagree and here do not. Nothing is
being resurrected by `INTERN_PORT`. The lead dies cleanly.

Note the one thing this does *not* establish: the walk observes the state at the
**dequeue**, not at the collection that actually moved the message. Reachable and
remembered now does not prove reachable and remembered then.

Relevant while reading the collector: `forward()` returns immediately for any
address outside from-space, so an **old object is never scanned by virtue of
being reachable from the roots** — only via the remembered set. Reachability and
traced-ness are different questions for old objects, and this bug lives exactly
in that gap.

## The agreed next step, and it is the only one

No more hypotheses until this output exists: **the collector's own trace of the
inbox object at the collection that moves the message** — what it visited, what
it forwarded, what it skipped.

The way in: the port is OLD, and old objects do not move, so its address is
stable for the whole run and can be watched. Instrument `scan_object` and
`forward` to log visits to that address, then compare against the number of
minor collections. A first attempt at this watched the wrong port — it took the
first old channel port enqueued into, which is not the one that fails — so
**identify the failing port first** (it is a `K_FLINT` channel, `cap = 1`,
inbox length 1) and watch that specific one.

An early reading from the wrong port, for whatever it is worth: it was scanned
63 times across 706 minor collections. If that ratio holds for the failing port
it is worth explaining, but it proves nothing yet.

## THE TRACE — AND A CORRECTION TO IT

**Retracted:** an earlier run of this trace reported "one untraced old-to-young
edge, one lost wave" and it was an **instrument artifact**. It registered watch
addresses for ports at enqueue time *without checking their generation*. A young
port's address goes stale the moment it is promoted, so the later reads were of
arbitrary memory. Watching only OLD ports — whose addresses do not move — the
same measurement reports **zero**.

With a sound watch, the remembered set is clean on every axis:

    enqueues creating an old-port -> young-inbox edge:            129
      of which the port was NOT flagged in_remset after the write:  0
      of which the port was NOT in the remembered list after it:    0
    START-of-minor: old port, young inbox, absent from taken list:  0
    END-of-minor  invariant violations:                             0
    END-of-major  invariant violations:                             0

So: the barrier records every edge, and the invariant "an old object pointing at
a young one is in the remembered set" holds at the start of every minor, the end
of every minor, and the end of every major. **The remembered set is not the
bug**, and neither is the barrier.

That leaves the original observation unexplained: a `TY_FWD` value is read out of
the inbox vector's tail node while the port, the vector and the node are all
reachable, and the port is correctly remembered.

## Two instrument rules this bug has now taught, both the hard way

Keep these beside the monotonicity table; all three are about instruments that
cannot answer the question asked of them.

1. **Measure presence directly, not through the thing presence causes.** Counting
   moves by hooking `forward()` reported zero out of 64 messages and looked like
   a clean result. It was structurally incapable of being anything else: a
   message whose edge is never traced is never forwarded.
2. **A watch address is only valid if the object cannot move.** Old objects do
   not move and are safe to watch; young ones are not. Registering a watch
   without checking the generation produced a confident, wrong "the bug is
   located" result.

Every check must also exclude the collector's own reads, and any flag it uses
belongs on `Space` rather than in a global — see above.

## Where that leaves it

Ruled out by measurement, not argument: the write barrier, the remembered set at
every phase boundary, the weak `INTERN_PORT` table, reachability of the whole
chain, trie/node granularity (the inbox is a one-element vector), a stale port
copy, copy-without-scan, and remset-processed-too-late.

What remains unexplained is narrow and precise: **a `TY_FWD` value is read from
the inbox vector's tail node, while the port is old, reachable and correctly
remembered, the inbox is young and reachable, and the tail node is reachable.**

The next thing to measure is the one not yet instrumented: whether `scan_object`
is actually reached for that specific inbox vector and tail node during the minor
that moves the message — not whether the port is remembered, which is now known
to be true, but whether the traversal from the remembered port through
`PT_INBOX` to `V_TAIL` to element 0 actually happens. Watch by object identity
using an address that cannot move, and log each hop.

## The differential: all three fix it, which is itself the answer

Running a major's phases in isolation from the host, once per pump:

    mode 0  nothing extra                          -> 63 waves  (the bug)
    mode 1  two minors back to back, nothing else  -> 64 waves
    mode 2  minor + mark + weak refresh, NO sweep  -> 64 waves
    mode 3  minor + mark + sweep, NO weak refresh  -> 64 waves

**Every one of them fixes it, including two plain minors.** So the differential
does *not* isolate the weak-table refresh or the old-space sweep — the thing they
share is simply *an additional traversal*, and that is what repairs the state.

This weakens the earlier "monotone therefore structural" reading rather than
supporting it. An extra collection per pump also ages objects faster and promotes
more of them, and promoted objects do not move — so "any extra collection fixes
it" is consistent with a mechanism that merely reduces the number of chances for
a young object to move while something still points at it. Monotone across
cadences, but the knob is still moving *when* things happen.

The live question is now the one that follows from mode 1: **what does a second
minor traverse that the first did not**, given the remembered set is provably
correct at both boundaries. A single minor descends into old objects ONLY via the
remembered set; a mark phase descends into everything reachable. That asymmetry
is the thing to look at, and it is the same asymmetry noted earlier — reachable
and traced are different questions for an old object.

## Snapshots are built (0015), and what they say about this bug

`594d13d` and the commit after it: capture, export/import, and an inspector with
reverse pointer lookup, whole-heap validation in one pass, and a two-snapshot
diff. A pure module is unchanged at 203 917 bytes, asserted.

Two things it found immediately, both in itself:

* the capture was **incomplete on wasm** -- one contiguous range missed an old
  chunk sitting at 22.8 MB, because `Space::take` grows memory via `sbrk`;
* the reader's linear walk **turned one parse error into 106 false findings**,
  by continuing past a header it could not parse so that every later object
  looked absent.

Both are fixed, and both were caught by the walk-completeness check rather than
by reasoning. That is the tool working as intended.

**The limit that matters for this bug: taking a snapshot makes the failing run
pass.** Every window tried -- every pump, a two-pump window, early, late, with
the buffer pre-warmed so the capture does not allocate -- gives the correct 64
waves. A snapshot cannot lie about state it did not interpret, but *taking* one
is not free of observer effect, and this bug is sensitive to exactly that.

So the next move on the wave loss is a capture that allocates **nothing**: size
the buffer to the maximum heap once, before the run, and have `capture_into`
never grow it. Then a snapshot costs a memcpy and no allocation, and the diff
across the minor that moves the message becomes available.

## The bisection is impractical at this scale — measured, not assumed

The stress window is back, now as diagnostics-feature machinery rather than
throwaway scaffolding (`set_gc_stress_window`, `stat_allocs`). Bisecting it found
the answer in the 222-allocation repro. It does **not** scale to this one:

    the document run: 18 583 324 allocations, 748 natural collections
    forcing collections for just the first 200 000 allocations: >10 minutes

A prefix probe costs O(window) collections, so a probe near the top end would run
for something like fifteen hours. Binary search over 18.5 million allocations is
not available.

**But 748 is a small space, and that is the reframing.** The next attempt should
bisect over the natural collection points, not over allocations. What it needs is
an instrument that can *suppress or shift* a chosen natural collection, because
the one thing already known is that **adding** a collection anywhere fixes the
bug — so forcing cannot discriminate between the 748.

Also confirmed while setting this up: the bug reproduces in the diagnostics
build (63 waves), so the instrumented configuration is a valid place to hunt it.
`test/document.clj` now runs in the diagnostics phase of `bin/test`, because it
uses `collect_now` and the heap statistics, which a production module no longer
carries.

### The count of perturbations that fix it is now nine

A forced major at any cadence, two minors, the weak refresh alone, the sweep
alone, an extra loop variable in the guest, a `throw` in the guest, `reap_ports`
off, taking a snapshot, and linking the snapshot unit at all. Every one changes
when a collection happens relative to allocations, and nothing that leaves
allocation timing alone has ever fixed it.

## THE COLLECTION IS NAMED: #692

Upgrading a chosen collection from a minor to a **major** is the first
perturbation in this investigation that leaves allocation timing alone. The same
number of collections happen at the same allocation indices, and a major performs
no `Gc::alloc`, so nothing downstream shifts. `set_gc_upgrade_window(from, until)`
does it, under the diagnostics feature.

It discriminates, where forcing did not:

    upgrade ONLY collection #1     -> 63 waves   (does not fix)
    upgrade ONLY collection #5     -> 63 waves
    upgrade ONLY collection #20    -> 64 waves   (fixes)
    upgrade ONLY collection #100   -> 64 waves
    upgrade ONLY collection #400   -> 64 waves
    upgrade ONLY collection #600   -> 64 waves
    upgrade ONLY collection #740   -> 63 waves
    upgrade ONLY collection #747   -> 63 waves

Early ones are before the content stream starts; late ones are after the damage
is done. Bisecting the upper edge, and re-running both sides to confirm:

    upgrading collection #691 -> 64 waves, 4 194 304 bytes   (saves it)
    upgrading collection #692 -> 63 waves, 4 128 768 bytes   (too late)

**Collection #692 is the one that loses the message.** A major at #691 prevents
the loss; at #692 it cannot recover it, and a major begins with a minor, so the
damage is done in that minor's tracing.

### A warning about the prefix bisection

Bisecting a prefix `[0, k)` converged neatly on "collection #300", and that was
**wrong**. Upgrading a prefix changes enough state that the failure point moves,
so the bisection located an artifact. Single-collection upgrades are what
discriminate. The tell was that upgrading any single collection near 300 fixed
it, while `[0, 300)` did not -- two results that cannot both be locating the same
thing. Prefer the narrowest perturbation that still answers the question.

### What to do with it

The question is now small: **what does collection #692 trace differently.** It is
one collection, reproducible, and both the snapshot inspector and the root
verifier can be pointed at exactly it rather than at every pump -- which is what
made them perturb the run before. Capture at the end of #691 and the end of #692
and diff; or run the `forward()` plausibility check for that collection alone.

## The traversal of collection #692, logged

Scoped to one collection the log is free and perturbs nothing — the failure is
still present in the traced run (63 waves), which is the first thing to check
before trusting any of it.

    collection #692: {:waves 63, :bytes 4128768}
    632 events | roots(heap) 438 | remembered entries 3 | scanned 97 | forwarded 94
    remembered by type: THREAD 1, SCHED 1, VOLATILE 1
    roots by type: STR 245, CLOSURE 129, KW 17, ARRAYMAP 10, VEC 10, PORT 3, ...
    PORTS scanned or forwarded: NONE

Three facts, in order of how much they narrow things:

1. **No port is scanned or forwarded.** Three ports are in the ROOTS, but for an
   OLD object that does nothing: `forward` returns early for anything outside
   from-space, so a minor descends into an old object only via the remembered
   set. Reachable and traced remain different questions, and this collection is
   where it bites.
2. **But the ports are not the violation.** Checked at that exact collection: all
   three old root ports have a NON-young inbox and are correctly absent from the
   remembered set. There is no old-port-to-young-inbox edge to miss here.
3. **The remembered set has three entries — THREAD, SCHED, VOLATILE.** For a
   collection forwarding 94 objects, with 64 KB messages moving through
   containers that have been promoted, three is a very small number.

So the message dies inside an OLD container whose old-to-young edge is not
recorded, and that container is not the port. The candidates are the inbox
vector and its tail node, both of which can be promoted while the message inside
them stays young. `vec_conj` builds a young vector and the barrier remembers the
PORT; nothing in that path remembers the vector or the node when THEY are the old
side of the edge.

**Next: log which object owns the young message at #692.** The traversal log
already carries every scanned and forwarded address; extend it to record, for
each old object in the remembered set and each old object reached, whether it
points at a young object. The invariant check that matters is the general one --
"every old object pointing at a young one is in the remembered set" -- applied to
the whole heap at that one collection, not just to ports. Three entries says it
will not be expensive.

## The generational invariant HOLDS — the missed-edge family is dead

Built as a standing assertion rather than a probe, because it is not a question
about this bug: **every old object pointing at a young one must be in the
remembered set** is the invariant a generational collector rests on. It walks
every old object at the start of every collection and again at the end, names
every violator, is read-only, and allocates nothing — so unlike a snapshot it
cannot perturb what it inspects. `set_gc_verify_remset`, diagnostics-only.

Run over the failing document run, with the failure still present (63 waves):

    generational invariant violations: 0
    of which at the END of a collection: 0

Zero at both boundaries. So the barrier is right, the remembered set is right,
promotion re-remembers correctly, and **the message still dies**. That kills the
whole missed-old-to-young-edge family, including the reading from the previous
session that pointed at the inbox vector or its tail node.

There is no barrier gap to fix, so the sibling audit that would have followed is
moot for now — it should be run against whatever the real cause turns out to be.

### Where that leaves the wave loss

Everything checked and cleared, by measurement: the write barrier and every site
that writes a slot; the remembered set at the start and end of every collection;
the weak `INTERN_PORT` table; reachability of the whole chain at the dequeue;
trie/node granularity; a stale port copy; copy-without-scan; remset-processed-too
-late; the old-space walk; and now the generational invariant over the whole
heap.

What remains true and unexplained: at collection #692 a message inside a port's
inbox goes stale, and reading it later yields a `TY_FWD` object. The port is old
and its inbox is old at that collection, so there is no old-to-young edge for the
invariant to catch — which means the young thing that dies is reached through
something the invariant does not model. The next question is therefore what the
message actually IS at #692: young or old, and if old, which object holds the
only reference to it. The traversal log for #692 already records every scanned
and forwarded address, so the message's fate at that collection can be read off
directly rather than inferred.

## Coverage proved, and the message is YOUNG

The zero was checked before being trusted, because a walker that covers nothing
and a walker that finds nothing print the same thing — and this codebase has
already shipped one walker that reported success while missing an old chunk at
22.8 MB.

    invariant violations: 0 (end 0)
    walk coverage: 6 673 120 objects visited, 0 spans it could not finish
    the failing port specifically: visited 1433 times across 748 collections

Twice per collection, start and end, for the exact port that fails. **The zero is
real.**

One instrument lesson from the first attempt: the walk was told to watch the
*inbox*, and reported "visited 0 times" — because the inbox is YOUNG and the walk
covers old space. A watch has to target the space the walker actually visits, or
its silence means nothing. Aimed at the old port instead, it visits 1433 times.

### The either/or is answered: the message is young

    port    @5090424   young = 0   (old)
    inbox   @2566040   young = 1
    tail    @2566016   young = 1
    MESSAGE @2555336   young = 1   <- the TY_FWD one

So this is **not** "who wrote a `TY_FWD` header into an old object". The message
is young, it was legitimately evacuated, and the stale pointer to it is the
problem. And there IS an old-to-young edge at the dequeue — port to inbox — which
the invariant never once flagged across 748 collections.

Both of those can only be true together if, at every collection boundary, the
port either had no young inbox or was correctly remembered. So the chain that is
stale at the dequeue was not stale at any boundary.

That points somewhere new and narrow: the message was forwarded, and what is
stale is a *copy* of the pointer to it that the collector did not update — a
reader holding the pre-collection inbox rather than the port's updated
`PT_INBOX`. The next read to do, from the #692 traversal log which already has
every scanned and forwarded address: was `2555336` forwarded during #692, and was
the vector holding it scanned in the same collection or only its replacement.

## Reproduction

`bb test/document.clj`. No stress mode needed; it fails identically every run.
The two failing assertions are the wave count and the byte total.

## Reproductions

Fast, deterministic, no host port — build with `bin/flint` and call
`set_gc_stress(1)` before `main`:

```clojure
(ns k (:require [flint.thread :as t] [flint.port :as p]))
(defn main [args]
  (let [n (flint.rt/str->num (first args))
        [tx rx] (p/channel 1 "probe")
        w (t/spawn (fn [] (inc n) (p/send tx :end) :sent))
        got (p/receive rx)]
    (t/join w)
    (pr-str {:got got})))
```

The slow one is `bb test/document.clj`, whose wave assertions fail without any
stress at all.

## Tooling

`a996441` holds the diagnostic scaffolding and `f64e137` reverts it; `git revert
f64e137` brings it back. It is out of the tree because it adds ~4 KB to every
module and breaks the pure-module budget, which is the assertion working. It
contains the runtime slice override, the stress *window* (collect only for
allocations in a range — bisect it to find the single allocation that matters,
but re-run any window it lands on, because the predicate is not monotone), the
root verifier with phase markers, and the reap log.

## The test that is still owed

`units-src/flint-conc/tests/threads.rs` covers a **waiter** park across
collection, with allocation between parks and a no-stress negative control. It
passes, so it does not reproduce this — a known gap rather than assumed
coverage. When the fix lands the regression test must **park and preempt**, not
just yield, and must fail if the fix is removed.
