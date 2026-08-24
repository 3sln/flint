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

## THE STALE POINTER IS NAMED

`is_young` spans BOTH semispaces, so a pointer left over from before a flip
still tests young. Nothing that only asks `is_young` — not the write barrier, not
the generational invariant check — can tell it from a live one. That is how zero
violations and a stale chain were true at the same time.

Asking the second question, "is it in the LIVE half", at the failing dequeue:

    spaces: from=2555904 bump=2566608 to=458752 half=2097152
      live half = [2555904, 2566608)   dead half = [458752, 2555904)

    port    @5090424   old space
    inbox   @2566040   LIVE half        <- fresh, post-collection
    tail    @2566016   LIVE half        <- fresh
    MESSAGE @2555336   DEAD HALF        <- pre-flip, stale

So it is the **trace** branch, not the write branch: `PT_INBOX` is the current
post-collection vector and the tail is fresh; only element 0 is stale.

Made a standing check — every heap slot must point into the live half or old
space, never the dead half — it names the fault in one run:

    NODE@2566016 slot  1 -> 2555336   at collection 721
    NODE@2566608 slot 20 -> 2555336   at collection 721
    NODE@459336  slot 20 -> 2555336   at collection 722

`NODE@2566016` is the tail node from the failing chain, and slot 1 is element 0
(slot 0 is the transient edit field). **The same message is referenced from three
different nodes**, one as a tail element and two as element 19 of larger trie
nodes — so it is shared between vectors, and after a flip every one of those
references points into the dead half.

Note the collection number: 721, not the 692 the upgrade bisection found.
Compatible — upgrading #692 stops the situation arising, while 721 is where the
stale pointer first becomes observable — but do not conflate them.

Both checks are permanent in `test/gc_stress.clj`, and both are worth having
whatever they say about this bug:

    {"start":0,"end":0,"dead":0,"collections":198}

### One more instrument lesson, the same one twice

The dead-half check reported a clean zero on its first run because it walked only
OLD space, and the stale pointer sits in a YOUNG object. Exactly the mistake made
minutes earlier by watching the young inbox with an old-space walker. **A check
means nothing about a space it does not walk** — state the space, then verify the
walk covers it.

### What is left

One concrete question: why does a node in the live half hold an element pointing
into the dead half at collection 721. The node is fresh, so either it was copied
from a node whose slot was already stale, or it was built by `node_clone` /
`vec_conj` from a source that was. `node_clone` copies slots verbatim, so a stale
element propagates into every clone — which would explain three nodes sharing one
stale target.

## All three holders are live, and 691 ties to 721

**Correction to the previous reading, and to the suggestion that only one of the
three nodes was live.** The bounds printed at the failing dequeue do not classify
anything at collection 721 or 722: a half boundary moves at every flip. Recording
the bounds *at the moment of each finding* shows all three holders were in the
live half then:

    NODE@2566016 slot  1 -> 2555336  at 721   live half THEN [2555904, 4653048)  holder live: yes
    NODE@2566608 slot 20 -> 2555336  at 721   live half THEN [2555904, 4653048)  holder live: yes
    NODE@459336  slot 20 -> 2555336  at 722   live half THEN  [458752,  483408)  holder live: yes

So the sharing story stands: three live nodes, one stale target. And note the
third is at collection **722**, in the *next* live half — the stale pointer is
being carried forward across flips.

That is a mechanism, not a coincidence. After a flip the dead half is neither
from-space nor to-space, so `forward` sees a dead-half pointer, finds it is not
`in_from`, and **returns it unchanged**. A stale pointer is therefore preserved
verbatim through every subsequent collection and copied into every node cloned
from its holder. One of them propagates indefinitely.

### 691, not 692

Running the live-half check with an upgrade applied ties the numbers together:

    no upgrade         63 waves   dead-half refs: 3 (first at collection 721)
    upgrade ONLY #692  63 waves   dead-half refs: 3 (first at collection 721)
    upgrade ONLY #691  64 waves   dead-half refs: 0
    upgrade ONLY #720  63 waves   dead-half refs: 3 (first at collection 721)

Upgrading #691 removes the stale pointer entirely *and* the wave loss. Upgrading
#692 changes neither. So **#691 is the collection where the situation arises**,
and 721 is merely where the walk first sees it — the earlier phrasing
"collection #692 loses the message" was the wrong way round and should be read as
"#691 is the last collection whose upgrade still prevents it".

### Ruled out by the owner, do not re-run

Uninitialised slots from a grow-clone: `gc.rs` zeroes on the bump path
(`write_header` then `zero_body`, every `Vals` slot set to NIL), and `node_clone`
is properly rooted, re-reading source and destination through `self.r()` each
iteration. Both out.

### The question now

What happens at collection #691 that leaves a live node holding a pointer to an
object in the half that flip is about to abandon. The dead-half check is armed
and permanent, so pointing it at #691 specifically — rather than waiting for 721
— is the next read.

## CORRECTION to the collection numbers, and the mechanism in one line

**The findings labelled 721 and 722 were the same event.** `check_remset` ran at
the start of a minor, *before* `stats.minor += 1`, while the trace cycle used
`stats.minor + 1` — so the two halves of one collection were labelled with
different numbers. Fixed by passing the cycle in. Corrected:

    NODE@2566016 slot  1 -> 2555336   at collection 722  (start-of-minor walk)
    NODE@2566608 slot 20 -> 2555336   at collection 722  (start-of-minor walk)
    NODE@459336  slot 20 -> 2555336   at collection 722  (end-of-minor walk)
    forward() saw 2555336 in limbo    at collection 722

One event, not two. Treat any collection number in this file from before this
correction as suspect unless it came from `set_gc_trace_cycle`, which was always
consistent.

### What the trace says happened

Tracing collections 690, 691, 692, 720 and 721 for the holder and the target:

    collection 721: target@2555336 appears as a ROOT four times, and is FORWARDED
                    holder@2566016 is NOT TOUCHED at any of them

So at 721 the message is reachable from the roots and is moved. The node holding
it is never scanned, so its slot keeps the pre-move address — and at the flip
ending 721 that address falls into the abandoned half. By the start of 722 it is
stale, `forward` sees it is not `in_from` and returns it **unchanged**, and it is
copied verbatim into the next half.

**The mechanism, in one line: the message is forwarded, the node holding it is
never scanned, so the node keeps the old address.** Upgrading collection #691
prevents the whole sequence.

### The fifth standing check

`forward()` now asserts that a young-range pointer is either in from-space or an
already-copied to-space address; anything else points into an abandoned half at
nothing. Diagnostics-only, beside the other four. It fires once here, on the same
address, at the same collection — later than the dead-half walker in this
instance, but it catches the class at the point of tracing rather than whenever
somebody next walks the heap.

### Ruled out by the owner, do not re-run

`vec_conj`'s tail path holding `root`/`meta` across `new_vec`'s allocation:
`new_vec` pushes root, tail and meta onto the shadow stack before its alloc and
re-reads all three through `self.r()` afterwards. Correctly rooted.

### The question now

Why is `NODE@2566016` not scanned at collection 721 when it is live and holds a
reference to an object that IS reachable from the roots. Either it is unreachable
at that moment and later resurrected, or it is reachable by a path the tracer
does not follow.

## THE THIRD OPTION IS RIGHT: the node did not exist at 721

The discriminator needed `bump` at the END of collection 721, before anything
allocated afterwards — the value at the next collection's walk cannot answer it,
because it has already grown. Captured at that exact point:

    at the END of collection 721: from=2555904  bump=2564960
      holder@2566016  -> ABOVE bump: ALLOCATED AFTERWARDS
      target@2555336  -> below from: in the half 721 abandoned

So the holder was **not skipped** — it did not exist. It was built after
collection 721 and written with the message's **pre-721 address**, which is
read-call-write-back for the sixth time in this codebase. Neither resurrection
nor an untraced path is required.

### The whole sequence, now closed end to end

1. At collection 721 the message is reachable from the roots (it appears as a
   root four times), is forwarded to a new address, and a `TY_FWD` header is left
   behind at `2555336`.
2. After 721, a node is allocated and written with `2555336` — the address as it
   was *before* the collection, held in a Rust local across it.
3. At the flip ending 721 that address falls into the abandoned half, so
   `forward` finds it is not `in_from` and returns it unchanged for ever after,
   copying it into every clone of the holder.
4. The dequeue reads that slot and gets a `TY_FWD` object. One wave lost.

### Construction sites audited and CLEARED

All push to the shadow stack before allocating and re-read through `self.r()`
afterwards: `node_clone`, `new_vec`, `new_path`, `vec_conj` (both the tail and
the overflow path), `vec_from_roots`, `port_enqueue`, `t_push_tail`, and
`tvec_conj`. The transient path was the most promising — transients mutate nodes
**in place**, which is the shape that would do it — and it is correctly rooted:
`t_push_tail` pushes `parent` before `ensure_editable` allocates, and the value
written by `node_set` is always either read from the shadow stack or freshly
returned from the call that allocated.

### What is left

The site that builds a node holding a message value after collection 721, and it
is not in `vector.rs`. The message is an element of a body vector decoded from
EDN, so the guest-side construction path — the EDN reader, `conj`, `into`, or
whatever `content-each` uses to accumulate a wave — is where to look next. The
runtime-side vector machinery is now cleared by audit rather than by assumption.

## Allocation-origin stamping: it works, and it names an index

Rather than auditing a fresh space by reading — which is how six hypotheses were
spent — allocations are now stamped with the native that was running when they
happened. A diagnostics-only global set around `call_native` on both entry paths,
and a fixed ring recording `(address, native)` at the bump.

    holder@3090304 slot 1 -> 3079624   at collection 722
        holder allocated by: native import #63
        the second holder: the interpreter itself (no native running)

**So the node holding the stale pointer was built by a native, in the gap right
after collection 721** — which confirms the third option and rules out the guest
side by measurement rather than by the (correct) argument that guest values live
in the precisely-scanned value stack.

Two things about the instrument, both learned by getting them wrong:

* **The ring must be scoped to a window.** Unscoped, 18 million allocations
  overwrite the interesting entry long before anything asks; the first run
  reported "not in the origin ring" for every holder. `set_gc_origin_window`
  narrows it to the gap between two collections, which is about 25 000
  allocations and fits.
* **The index cannot be resolved to a name from inside wasm.** `image.natives`
  keeps the runtime SLOT, not the name constant, and on wasm a slot is a wasm
  table index rather than a position in `builtins::host_registry` — which is
  host-only anyway. Mapping slot 117 through the host registry printed
  `flint/pow`, which is obviously wrong, and is a good example of a lookup that
  produces a plausible answer from the wrong table.

**Next, and it is small:** retain the name constant in `Image::natives` when the
image is loaded, so a diagnostics build can report "import #63" as a name. The
loader already reads that constant and discards it. Then re-run the above and
the site names itself.

## THE SITE NAMES ITSELF: flint/port-send

Retaining the name constant in `Image::natives` at load — one line the loader
used to read and discard — turns "import #63" into a name:

    holder@3090304 slot 1 -> 3079624   at collection 722
        holder allocated by: flint/port-send  (import 63)
        the other holder:    the interpreter itself

`slot 1` is element 0 (slot 0 is the transient edit field), and a one-element
vector whose tail node holds the message at element 0 is exactly what
`port_enqueue`'s `vec_conj` builds onto an empty inbox. So the holder is the
inbox tail node, created inside `port_send`, holding the message — and the
message address it holds is the pre-collection one.

That is the end of the search for WHERE. What remains is WHY the value reaching
`port_enqueue` is already stale, because the code between is rooted correctly:

* `port_send` reads `payload`/`val` from the shadow stack immediately before the
  call that pushes them, with no allocation in between;
* `port_enqueue` pushes `p` and `v` before touching anything;
* `vec_conj` pushes `x` at its start;
* `push_event` pushes `payload` first.

All four audited and clean. So the staleness arrives with `v`, before
`port_send` is entered — and `v` comes off the value stack, which is the traced
root set. The next question is what `port_send` does that could make its own
argument stale: the obvious candidate is the PARK path, since a full buffer parks
and the native re-executes on resume, and the argument is re-read from a stack
that was saved and restored in between.

**That is a small, bounded question with a named function, which is where this
investigation has been trying to get for a dozen sessions.**

## The park path is eliminated — by measurement, with coverage

The live-half predicate, applied at the resume boundary to the stack as
restored. No new instrument: the same check that broke this open, reused where
the question is, which also means it was already validated against a known-bad
case.

    resumes checked: 100 921
    values examined: 4 780 342
    STALE values in a restored stack: 0

Coverage stated first, because zero from a check that never ran is not a result.
A hundred thousand resumes over nearly five million values, and not one restored
argument points into a half that is neither from- nor to-space. **The save and
restore path is a scanned root and it is correct.**

So a parking native re-reading its arguments on resume is not how the stale value
arrives, and `document.clj` spends most of its time on that path — the bounded
channel is in back-pressure for most of the 64 waves — so this is a strong
negative rather than an untested corner.

### Which leaves the interpreter loop

Everything between `port_send` and the allocation is rooted; the argument arrives
stale; the value stack is a scanned root; and the restore path is clean. The
remaining way a stale value reaches a value-stack slot is an instruction that
caches an operand in a Rust local across an allocating step and writes it back
afterwards — the same shape as `run_program`'s argument vector, one layer down.

The second holder supports it: it was allocated by **the interpreter itself, with
no native running**. The opcodes to look at are the ones that allocate while
holding operands — `VECTOR`, `MAP`, `SET`, `LIST`, `CLOSURE`, `APPLY` — and the
origin stamp can name which, since it already distinguishes "the interpreter
itself" from any builtin.

## The serial separates the introducer from the carriers

The stale pointer propagates verbatim into every clone, so several live nodes
carry it and each was allocated by somebody. The origin stamp names a CARRIER,
not necessarily the introducer — and two attributions, `flint/port-send` and
"the interpreter itself", are not two bugs. Ordering them settles which is which:

    holder@3352448 slot  1 -> 3341768   flint/port-send            serial 33
    holder@3353040 slot 20 -> 3341768   the interpreter itself     serial 46
    holder@1245768 slot 20 -> 3341768   (allocated later, outside the window)

**`flint/port-send` is the earliest. It is the introducer; the interpreter holder
is a copy made thirteen allocations later.**

So `VECTOR`, `MAP`, `SET`, `LIST`, `CLOSURE` and `APPLY` are **not in this
story**, and reading them would have been a session spent on a copy. Picking
between two attributions by plausibility is the same move that made `flint/pow`
briefly look like an answer; the serial removes the judgement call.

### Which means the earlier audit missed something

`port_send`, `port_enqueue`, `vec_conj` and `push_event` were all audited and
found correctly rooted. The serial says the introduction is inside that call
tree anyway, so one of those readings is wrong. That is a bounded re-read with a
named entry point rather than an open search — and it should be done with the
same standard applied to the rest of this investigation: not "this looks rooted"
but "this value cannot be stale across this allocation, and here is why".

The park branch of the channel path is worth reading first: it calls
`self.pop_to(base)` — unrooting both the port and the message — and only then
enters `park_on_port`, which allocates. The message survives on the value stack,
which is traced, so the reasoning holds; but it is the one place in that tree
where a value is deliberately unrooted before an allocation, and "it survives by
another route" is exactly the kind of argument this bug has punished five times.

## Both park branches of `port_send` are eliminated — by coverage

Asked rather than read: at the allocation the park path performs, walk the traced
roots and ask whether the message's address is present. Not whether it looks
rooted — whether it is there.

    parks on a full channel checked: 0
    parks on a full host port checked: 0
    of those, message in NO traced root: 0

**Neither park branch executes in the failing run.** The concern that
`pop_to(base)` unroots the message before `park_on_port` allocates was moot: the
code never runs, so the "it survives on the value stack" argument was never even
exercised. A zero here is a coverage fact, not a clean result — and it is the
strongest kind of elimination, because it does not depend on the check being
right.

So the introduction inside `flint/port-send` is on a **non-parking** path: either
the host branch's `push_event`, or the channel branch's `port_enqueue`. Both were
read as correctly rooted; the serial says one of those readings is wrong.

## The rules this investigation produced

Six, in the order they cost the most:

1. **A lookup against the wrong table returns something, and it looks like a
   finding.** Structural, not a habit of scepticism: `flint/pow` was
   disbelievable, and a plausible wrong answer would not have been.
2. **A check means nothing about a space it does not walk.** State the space,
   then verify the walk covers it.
3. **Measure presence directly, not through the thing presence causes.** A
   counter on `forward()` could not fire for a value that is never traced.
4. **A watch address is only valid if the object cannot move.**
5. **An unscoped ring is a *silently* weaker instrument** — "not in the ring"
   reads exactly like a negative result and is not one.
6. **Order by allocation to turn an attribution into a cause.** Any propagating
   corruption has carriers, and any per-object attribution names one of them.

And one about arguments: **a more careful reading of a rooting argument is still
a rooting argument.** Four functions were read as correctly rooted and the
measurement puts the introduction inside them. When the line is found, the
question worth answering is not which one was wrong but *what made the wrong
reading look right* — because that is what will make the next one look right.

## The widened check: 9 803 allocations, zero unrooted — and what it cannot see

Armed at EVERY allocation reached while `flint/port-send` is live, rather than at
one hand-chosen one, so no choice between `push_event` and `port_enqueue` was
needed and paths neither of us named are covered too. It follows forwarding
first, so an object that moved in an earlier collection is compared by its new
address rather than reported as a false absence.

    allocations inside flint/port-send, checked: 9 803
    of those, the message was in NO traced root:     0

**Do not read that as "so the staleness arrives before entry".** The check asks
whether the *live object* is present in some root. This bug's failure mode is a
**stale copy of an address written into a node while the live object stays
properly rooted elsewhere** — and presence of the live object cannot see that.
The instrument answers a different question from the one the bug poses.

What it does establish, with coverage: the message never becomes unrooted during
`port_send`, so it is not dying there. That eliminates one whole family. It does
not eliminate the other.

The check that WOULD see it asks the opposite question, and it already exists in
another form: at every allocation inside `port_send`, assert that no traced root
and no reachable object holds an address whose object is `TY_FWD` — the live-half
predicate applied continuously rather than at collection boundaries. That is
expensive, but `port_send` runs 9 803 allocations in this test, not 18 million,
so it is affordable exactly here.

### The premise, and then the rules

**A more careful reading of a rooting argument is still a rooting argument.**
That is why the list below exists rather than being a peer to it.

1. A lookup against the wrong table returns something, and it looks like a
   finding.
2. A check means nothing about a space it does not walk — and a coverage zero
   and a clean zero are the same output with opposite meanings, so count.
3. Measure presence directly, not through the thing presence causes.
4. A watch address is only valid if the object cannot move.
5. An unscoped ring is a *silently* weaker instrument.
6. Order by allocation to turn an attribution into a cause.

To which this run adds the sharpest form of the general problem: **an instrument
that answers a different question from the one the bug poses will answer it
cleanly.** Rule 3 is the special case; state the question the failure mode poses
before trusting a zero.

## THE WRITE IS CAUGHT, INSIDE `port_send`

The live-half predicate applied continuously — at every allocation while
`flint/port-send` is live, over the roots plus the objects born in that frame
(bounded by the frame, re-based after a collection since one moves them). The
predicate rather than a `TY_FWD` header test, because a forwarding header stops
being evidence once its half is reused.

    frame scans: 9 803   hits: 1
      NODE@3352448 slot 1 -> 3341768
      caught while allocating a VEC

**So the stale address IS written inside `port_send`, into a node born in that
frame.** The origin stamp was pointing at the write, not merely at the node's
birthplace — which is worth stating, because this bug has punished the opposite
assumption before.

`vec_conj`'s tail path allocates in the order: clone the tail node, write the
element into it with `node_set`, then allocate the new `VEC`. The hit is caught
**while allocating the VEC**, i.e. immediately after the node was populated. That
is `port_enqueue` -> `vec_conj`, tail path, and the write is the `node_set`.

Two possibilities remain, and they are distinguishable:

* the value written is stale — but the presence check says the live object is
  rooted at every allocation in the frame, so `x` should be current;
* **`node_clone` copied a stale slot out of the SOURCE tail**, since it copies
  verbatim and the source is the previous inbox vector's tail.

The second fits everything, including why the presence check came back clean:
nothing is unrooted, an old stale *copy* is simply propagated. It would also make
this holder a carrier after all — so re-check the serial against the source
node's, rather than assuming.

### Caveat on the number

`allocation #9753` is the **cumulative** count of armed allocations across all
`port_send` calls, not an index within one frame. It does not localise the call;
the `VEC` being allocated does. Fixing that counter to be frame-relative is a
one-line change and would name the call outright.

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
