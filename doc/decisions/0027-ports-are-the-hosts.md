# 0027 — Ports belong to the host, not to a sandbox

> **BUILT**, on all four runtimes, except the two parts named under "What is
> still open" at the bottom.

## What this banner used to say

It said "QUEUED — nothing in this file exists yet", and it went on saying it
long after half of the file had been built and then left unreachable:
`install_global_port`, `install_system_port`, `SC_SYSTEM` and `K_GLOBAL` all
existed, and `system_port()` had **zero callers on all three runtimes**.

Two generations of the port model were live at once, which is how a wire port
came to be classed `CARRY_CROSSING` on the JVM and CLR and then refused by their
own send. A banner that lies about the tree is worse than no banner: it is what
made that state readable as "not started yet" rather than as "half-done", and it
is why `bin/check-decisions` compares the two.

**A port is one of two things, and the difference is which side of a heap its
two ends are on.** A LOCAL port joins two green threads inside one sandbox and
passes values by reference. A GLOBAL port joins two sandboxes and passes them
encoded. Everything below is about the second kind; the first stays exactly as
cheap as it is today.

Ports today are a sandbox's own. Each `Rt` owns its registry, a port id is an
index into it, and the sandbox both CREATES its endpoints and hands one out.

That is a coupling, and it shows up the moment there are two sandboxes. An
endpoint that exists only inside sandbox A cannot be held by sandbox B: B has a
different heap, a different collector and a different id space, and A's id 3
means something else there or nothing at all. Inter-sandbox communication is
not an extension of that model, it is a contradiction of it.

So the registry moves out. A port is the HOST's, and a sandbox holds one
because it was given one.

## What follows

**The system channel is passed IN.** A sandbox no longer manufactures its own
end and offers it up; the host makes the channel, keeps one end, and passes the
other to the sandbox constructor. This is the same inversion `0022` already
made for capabilities — authority is never something the thing being confined
produces for itself — and applying it to ports makes ports capabilities
properly rather than by analogy.

**Creating a port is a dispatch on the system port.** There is no intrinsic
that makes one. A sandbox that wants a new endpoint asks, and the host answers
with one or refuses. Combined with `0025`'s rule — flint is given no way to
turn an integer into a port — this becomes something stronger than either
piece: flint cannot obtain a port by fabrication OR by construction. Every
endpoint a sandbox will ever hold was handed to it. That is a property worth
having, and it is only reachable once creation is a request.

**A message between two sandboxes is COPIED, never shared.** Two sandboxes have
separate heaps and separate collectors, so a value cannot cross by reference —
and it does not have to, because the wire codec already exists for exactly this
shape. A transfer is an encode in one heap and a decode in the other, and the
guest decoder already refuses to materialise a port or a sentinel it was not
given. The boundary was already built; this puts it where it belongs.

**Queueing outlives both ends.** A message sitting in a host-owned registry
survives a sandbox being paused, stepped to exhaustion, or collected. A message
sitting in the sender's heap does not, and that is the bug this avoids rather
than fixes later.

## The collectors: the encode IS the boundary

This is the hard part, and it is where a shared registry usually goes wrong. A
queued message cannot be a pointer into the sender's heap: the nursery is
COPYING, so the object moves, and the old generation is swept, so it is freed.
A host-owned queue holding sandbox pointers would be a queue of dangling
addresses one collection later.

So it holds none. The split is three-way, and no collector ever sees a pointer
it does not own:

* **In the sandbox heap: the HANDLE, and only the handle.** An ordinary object
  carrying a host id -- a `u64` and no pointer. The collector traces it, moves
  it and sweeps it exactly like any other object, with no special case. It is
  not the port; it is a reference to one.
* **In host memory: the port, and every queued message as ENCODED BYTES.**
  Neither collector traces them, because neither has anything to trace: the
  bytes are a value's WRITTEN form, not its heap form.
* **Nothing anywhere is a cross-heap pointer.** There is no moment at which one
  exists, so there is no moment at which one can be stale.

A send encodes out of the sender's heap; a take decodes into the receiver's.
That is a copy, and it was always going to be a copy -- two sandboxes have two
heaps and no shared representation, so a value could not cross by reference
even if we wanted it to. What is new is only that the copy happens at a place
that already had a codec built for it, and the guest decoder already refuses to
materialise a port or a sentinel it was not handed.

It also makes the queue independent of both ends' liveness: a message already
encoded survives the sender being collected, paused, or stepped to exhaustion.

### A local port pays none of this

Encoding two green threads' messages through a byte buffer would be a pure
loss: same heap, same collector, both ends traceable, nothing to protect
anyone from. So a local port does not.

* **Local**: an ordinary heap object. Its queue holds VALUES, the collector
  traces it like any other object, and a `put` is a pointer write. No host, no
  registry, no lock, no codec. This is what `0007`'s green threads use and it
  is unchanged.
* **Global**: created by dispatch on the system port, owned by the host
  registry, with its OWN buffer holding messages already serialised, and safe
  across threads because it is shared. A sandbox sees it through a handle; the
  buffer is never in any heap.

**Which one it is, is fixed when it is made**, not discovered when it is used.
The alternative — start local, promote when an end crosses — reads better and
is worse: a port's cost would change underneath the code using it, and
promotion would have to encode a queue that may itself contain ports, which
recurses. A port's ends are part of what it IS.

So creating a local port stays an allocation, and only creating a GLOBAL one is
a dispatch. That is not a special case, it is the same rule: a local port
confers no authority outside the sandbox, so there is nothing for the host to
mediate. Mediation is for what crosses.

**The operations do not branch on this and neither does the code using them.**
`put` and `take` are the same two functions; the port carries its own kind and
the branch is one tag test inside them. Only the line that MAKES a port differs,
which is the line that knows the answer anyway.

**A local end cannot be handed to another sandbox**, and cannot be sent through
a global port. That is the one visible difference, and it is a refusal with a
reason rather than a silent promotion. A GLOBAL port's handle can be sent
through a global port -- the host owns both, the id means the same thing on
both sides, and the receiver ends up holding the same port. That is how a
channel gets handed out, and it is the case worth having.

### Reclaiming a port a sandbox stopped holding

Global ports only; a local one is just an object and dies like one. This is the
one place the collector needs a hook, and there are two ways to build it.

**A box per arrival.** Every time a global port enters the sandbox, wrap it in
a fresh heap object that owns one increment, and decrement when that object
dies.

**A weak intern table.** One canonical handle object per global port per
sandbox: handle-id to the handle object, not a root. A port arriving that is
already in the table gets the handle already there. One increment when it first
enters, one decrement when it dies. After each collection, walk the table and
report the ids whose object was not forwarded (nursery) or not marked (old
generation) -- a walk over ports held, not over the heap, so proportional to a
handful rather than to anything that scales.

**The table wins, and the reason is that the box does not actually save
anything.** It looks cheaper because it needs no table -- but a box still has
to tell the host when it dies, so it still needs the same collector hook. It
pays that, and then adds two costs the table does not have:

* **Identity breaks.** The same port arriving in two messages becomes two
  boxes. `=` says no, a map keyed by a port misses, and code that holds "the"
  port ends up holding two of them. Ports are identities; that is the point of
  them.
* **Refcount churn.** N arrivals of one port cost N increments across a
  boundary that has to be atomic, for a count whose only real question is
  whether this sandbox still holds it at all.

The table answers that directly: **each sandbox contributes at most one to a
port's count**, so the count means "how many holders" rather than "how many
references" -- a number a human can reason about. The interning is the same
discipline `flint.strs` already uses for symbols and keywords, so it is not a
new shape here.

The host keeps that count and releases the port at zero. The system port is
rooted for the sandbox's whole life, so it never takes this path.

An explicit close stays available and stays the fast path; the sweep is the
backstop for the handle nobody closed, not the mechanism.

Three things the table has to get right, each named because it is a bug we have
already had once in some form:

* **Weak means weak through a COPY.** The nursery moves objects, so an entry is
  not merely cleared-or-kept: a surviving handle has to be re-pointed at where
  it moved, and only a handle NOT forwarded (or not marked, in the old
  generation) is dead. That is a fixup pass, not a filter, and writing it once
  makes it reusable for anything else weak.
* **Interning allocates.** A lookup that misses allocates a handle, and an
  allocation can collect. Everything live across it has to be rooted -- exactly
  the bug `flint.strs` had in `symbol` and `keyword`, where a string left the
  root stack one line before the `alloc` that could move it.
* **A dropped sandbox has to walk its table.** Tearing one down without
  decrementing leaks every global port it ever held, and leaks it somewhere no
  collector will ever look.

### Push AND pull, and why push is the one that matters

A global port supports both: a `take` that parks when the buffer is empty, and
a readiness signal that fires when something lands in it. They are one
mechanism seen from two sides -- same buffer, same lock -- so supporting both
costs almost nothing over supporting either.

**Pull-only cannot drive a sandbox loop.** A thread does `take`, the buffer is
empty, the thread parks; now nothing in that sandbox is runnable. Something
outside has to decide to step it again, and with nothing to wait on, "decide"
means POLL. A host with several sandboxes ends up spinning over all of them
asking whether any has work, which burns a core to learn "no" and adds latency
proportional to how rarely it asks.

With push, an arrival makes the receiving sandbox RUNNABLE, and its owner can
block instead of poll. That is the difference between an event loop and a
polling loop, and it is what makes a long-lived sandbox -- one that waits for
work, serves it, and waits again -- possible at all rather than only the
request-and-return shape.

Backpressure is the same in the other direction: a `put` on a full buffer parks
the sender, and a `take` that drains one wakes it.

**The rule that keeps this safe: push WAKES, it does not EXECUTE.**

It would be easy, and wrong, to have `put` run the receiver's waiting code
right there. That would execute guest code on the SENDER's thread, in a heap
the sender does not own, while that heap's real owner may be mid-collection.
Our collector assumes one mutator at a time per heap and would be right to.

So an arrival does exactly three things, all of them bookkeeping:

1. append to the port's buffer, under the port's own lock;
2. mark the parked thread runnable in the receiving sandbox's scheduler;
3. signal that sandbox's readiness.

A sandbox is still only ever advanced by whoever owns it. What push buys is
that the owner can wait for the signal instead of asking repeatedly -- and that
is a property of the HOST's loop, which is exactly where it belongs now that
the port is the host's.

### Two costs, named now

**An unbounded queue is a leak in host memory** rather than in a heap, which
makes it nobody's collector's problem and therefore easy to miss. Queues are
bounded, and a full one answers "would block" -- which the scheduler already
knows how to park on (`doc/decisions/0007`).

**Encoding loses structure sharing.** A value whose subtree is shared ten times
encodes ten times, because the wire form is a tree. That is a real cost for the
shape it hits, and the fix -- back-references in the codec -- is known and not
built. It goes here so it is not discovered as a surprise.

## What was built, and what it is called

The vocabulary moved once during implementation and the file uses the new words
throughout: what this document calls a GLOBAL port is `K_BRIDGE`, and there is
no "host port" at all. `K_FLINT` and `K_HOST` -- the pair a sandbox used to
manufacture on `open` -- are deleted, along with `PT_ROOT`, `PT_FORMAT`,
`PT_OPTS` and `PT_BINARY`.

* `open` is a request ON the system port. Nothing is allocated until the host
  answers. A sandbox given no system port is told so rather than parked for ever
  on an event nobody will drain.
* A grant has to NAME a port, so `host_continue(token, true)` is refused and
  `host_grant(token, port)` is the granting half.
* The handle is INTERNED by host id, in the weak port table. `EV_RETAIN` goes
  out on the miss that mints it; `EV_RELEASE` from the collection sweep, or
  promptly from `close`. The count is of HOLDERS, exactly as the table argument
  above says it should be.
* The handle is NOT rooted, which is what makes a drop observable.
* Encoding happens at the bridge, in the runtime, in both directions. The guest
  has no codec: `:codec`, `set-codec` and the raw byte mode are gone, and with
  them `CARRY_SANDBOXED`, whose rule is now enforced by the guest not having an
  encoder rather than by a check.
* The host gets both halves explicitly: `codec::Wire` builds a message with no
  flint heap involved, and `install_port` / `host_grant` / `encode` / `decode`
  sit beside it. The ESM driver adds the conveniences -- `deliver(port, value)`
  encodes for you, `tryDeliverBytes` does not.

Measured, one image through three separately written host drivers, compared
byte for byte: the native, JVM and CLR transcripts are identical.

### The decoder must not drag the scheduler in

Interning an arriving port means the decoder calls `install_bridge_port`, and
`flint_call` is an unconditional export with the decoder hanging off it. Done
directly, that linked the scheduler, the ring, the event queue and the port
registry into EVERY module: a pure one grew from 300,801 to 335,320 bytes,
against a 304,000 budget `test/threads.clj` holds.

So it goes through `Rt::bridge_hook`, a function pointer only `ensure_sched`
sets. `None` means refuse rather than "not yet", and that is correct: a port
arrives over a bridge, so a program with no ports can never be handed one. The
residue is +217 bytes.

## What is still open

* **The weak-table fixup through a nursery COPY**, described under "Three things
  the table has to get right". Today the sweep walks `SC_BRIDGES` after a
  collection and releases the ids whose lookup misses.
* **Back-references in the codec**, so a value whose subtree is shared ten times
  does not encode ten times. Named below as a cost; still a cost.

## "Global" means the host's, not a process static

The registry is a handle the host creates and shares among the sandboxes it
makes. It is not a `static`. A process static would make thread safety the only
question; a handle makes it a property of that handle, keeps two independent
host universes independent, and lets a test build one and throw it away.

Thread safety comes with it, because a shared registry is reachable from
whichever thread is running a sandbox. That is a cost, and it is one we were
going to pay: green threads inside one sandbox is `0007`, but sandboxes on
different OS threads is the reason to have more than one sandbox at all.

## The cost, stated before it is measured

Port creation becomes a round trip instead of an allocation. In-process that is
a call and not IPC, so it should be cheap — but "should be" is not a
measurement, and the number goes here once creation is built rather than into
an argument for skipping it. If it turns out to matter, the fix is a grant of
several endpoints at once, not a retreat to sandbox-local creation.
