# 0033 — A bridge owns its messages, and a port is six verbs

> **NOT BUILT — a proposal.** Nothing in this file exists yet. It settles who
> owns a message in flight, which `0006` and `0028` left with the sandbox, and
> it is what the codec and terminology work both wait on.

## What is true today

A bridge owns nothing. Every message in flight lives in the sandbox's heap and
dies with it.

**Guest to host.** `port_send` encodes the value into a byte string *in the
sandbox heap*, charges its length against `PT_BYTES` on the host-end object,
then `push_event` builds a four-element persistent vector and conjs it onto
`SC_EVENTS` — also a persistent vector, also in the sandbox heap. It sits there
until the host calls `drain_events`, which walks it and copies the bytes into a
host buffer.

**Host to guest.** `host_deliver` charges the bytes, decodes them into a guest
value, and compare-and-swaps it into the receiving port's ring.

So the "host end" is four fields of bookkeeping — id, byte counter, peer link,
state — and there is no host-side storage at all. It had a message ring until
`4b4bfb6`, which nothing ever put a message in.

Two consequences, and the first is the reason for this document:

* **A message cannot outlive the sandbox that wrote it**, so a bridge cannot be
  the durable thing it should be, and a port cannot be handed to a different
  sandbox with its traffic intact.
* **`push_event` is a read-modify-write on a shared persistent vector with an
  allocation in the middle** — the exact shape that lost half the messages on
  the inbox before `d335010`. Two executors sending across bridges should lose
  events the same way. NOT DEMONSTRATED; it is the same code shape, and the
  inbox one took twenty lines to prove.

## The decision

**The bridge owns the messages. Both ends are symmetric. A port knows nothing
about sandboxes.**

A port is not a thing a sandbox has; it is a thing that exists, which a sandbox
or a host thread may hold. Nothing in its interface names a heap, a sandbox or
a transport.

### The port contract

Six verbs, and a writer needs no more than these to write a message safely:

```
reserve(size_hint) -> buf      grow(buf, n) -> buf
commit(buf)                    abort(buf)
take() -> buf | none           release(buf)
format() -> :flint | :json | :edn | :cbor
```

Reserve, serialise into it, commit — or abort. Take, read, release. The
implementations diverge completely underneath and no caller can tell:

| | shared-memory ring | pipe / socket / file |
| --- | --- | --- |
| `reserve` | allocate from the bridge arena | hand out a recycled scratch buffer |
| `commit` | CAS a tagged pointer into a slot | write length-prefixed bytes, recycle |
| `take` | CAS the slot to null | read one frame |
| `release` | push to the arena free list | recycle |

**Two things the interface must deliberately NOT promise.** It must not promise
zero-copy: the ring is, a socket cannot be, and a caller that depended on it
would break when the bridge kind changed. And it must not promise that framing
is free: a byte stream has no message boundary, so `commit` synthesises one and
`take` consumes it. The caller sees neither.

**Back-pressure already generalises.** "No free slot" and "the kernel buffer is
full" are the same answer -- *not now* -- which is the park-and-retry shape the
runtime already has.

### The buffer lifecycle, and why it is not a lease

    allocate -> own exclusively -> serialise -> commit | abort

The slot is claimed ONLY at commit, and only if it is null. So a writer that
dies mid-serialisation can never wedge the ring; it can only strand a buffer.
That is the whole reason ownership sits here rather than on the slot.

A stranded buffer still matters -- exhaust the allocator and every producer
blocks, so the leak becomes a deadlock by another route -- and "the sandbox
releases it when it dies" is not enough, because a killed sandbox runs no code
and a peer process that segfaults on a shared-memory bridge runs none either.

**So each participant allocates from its OWN arena inside the bridge.** If a
participant dies, the survivor reclaims that whole arena in one step: no
per-buffer bookkeeping, no scan, no timer, and it survives SIGKILL because the
party doing the reclaiming is the one still alive. Published messages point into
the dead participant's arena, so one refcount PER ARENA -- bumped on commit,
dropped on release -- says when it may go. One counter per arena, not per
buffer.

**Growing is safe** because the buffer is exclusively owned before commit, so a
resize can move it and nobody can observe the move. That is what avoids the
alternative -- serialise into scratch, measure, copy -- which is the extra copy
this design exists to remove.

**A producer parks holding a buffer, and that is not a deadlock.** A consumer
never needs a buffer in order to consume, so nothing waits on the allocator to
make progress: producers wait on slots, consumers produce slots.

### What was rejected, and why

* **The bridge roots a value in the sandbox heap until it is done.** Not merely
  expensive -- it cannot work. A root keeps a value alive *inside a heap*, and
  if the sandbox dies the heap is gone. It satisfies the requirement only if the
  bridge's lifetime nests inside the sandbox's, which is what this reverses.
* **One shared sandbox-side buffer for all bridges, each entry naming its
  port.** That is what `SC_EVENTS` already is, so it is the status quo rather
  than a design; it leaves ownership where the problem is, and concentrates
  contention rather than reducing it.
* **Per-buffer leases with timeouts, renewal and a lapse scan.** Solves the same
  problem as the arena refcount with a clock, a scanner and a case where a slow
  but live writer has its buffer stolen.

## Bridge varieties

The kind decides how many ports exist and what `commit` does. Nothing
downstream cares.

| kind | ends | transport |
| --- | --- | --- |
| point-to-point, in process | two ports | a ring in shared memory |
| point-to-point, inter-process | two ports | a ring in a shared mapping |
| one-ended | one port | an OS pipe, socket pair, or file |

A one-ended bridge is not a special interface, only a construction that hands
out one port instead of two. What is on the other side is the OS's business.
Which of these an OS provides differs -- Windows and unix do not agree about
socket pairs or about what a pipe is -- so the SET of kinds is
platform-dependent while the port contract is not.

## Formats

`format()` is on the port so the writer serialises DIRECTLY into the reserved
buffer, in the format the far side wants. Nothing re-encodes at the boundary,
which was the whole objection to shuffling a value sandbox -> host ->
destination.

`:flint` (default), `:json`, `:edn`, `:cbor`.

The port machinery never invokes a codec and never learns what the bytes mean.
The codec is configuration the WRITER consults, which keeps the port agnostic
and makes the encoder ordinary guest code -- so a program that only uses
`:flint` never links a CBOR encoder.

### Identities, and who owns which half of the frame

Every one of these formats CAN express an identity -- EDN has tagged literals,
CBOR has tags, and JSON can carry a convention object -- so "only `:flint` may
name a port" would be a restriction invented by this design rather than one the
formats impose. The real constraint is not expressiveness. It is two invariants
that bite from opposite directions:

* **A guest cannot read an id.** `flint/opaque-label` exists and there is
  deliberately no builtin returning the host id, because reading provenance from
  guest code invites the check `0022` forbids. So guest code cannot WRITE
  `#flint/opaque ["foo" 111]`: it has no way to obtain the 111.
* **A guest-side decoder must not turn bytes into an identity.** If it could
  parse `#flint/port [333]` into a live port then possession stops being proof,
  which is the whole of `0022`.

So the split is not by format. It is by **which half of the frame each side
owns**:

    frame := identity-table   -- the RUNTIME writes and reads this
             body             -- the CODEC writes and reads this

The body references identities BY INDEX into the table, never by id. Outbound,
the codec meets a port value, calls a runtime hook that appends it to the table
and hands back an index, and writes `#flint/port [2]`. Inbound, the codec sees
`#flint/port [2]` and asks the runtime to resolve index 2.

Which gives every property at once:

* every format carries identities, in its own natural tagging;
* the guest never sees an id, so `0022` holds unweakened;
* the guest can only name identities ACTUALLY PRESENT in this message -- an
  out-of-range index is an error, not a handle -- so there is nothing to forge,
  and that is a stronger statement than "this format cannot say it";
* `check_sendable` therefore does NOT grow a format dimension for identities. It
  keeps only the rows about what a format can represent structurally.

**What the format still decides** is structural coverage. A keyword, a symbol, a
set and a bigint have no JSON equivalent, so a `:json` port either refuses them
-- naming the value and the format, rather than coercing quietly -- or tags them
by the same convention. That is a per-port choice and it has a cost either way:

* `:a` written as `"a"` comes back a STRING. Round-tripping stops being
  identity, which is a legitimate trade for output that looks like JSON, but it
  is a property of the port and must not be a surprise on the far side.
* `{"$flintTag": ..., "$flintVal": ...}` needs an ESCAPE, or the encoding is not
  total: an ordinary map that happens to carry that key is ambiguous. Requiring
  the exact key set and escaping a colliding user map is the shape that works.

## Consequences

* **Port ids leave the per-sandbox registry.** They are indices into
  `interns[INTERN_PORT]`, which lives in one sandbox's shared roots -- so
  decoding a `K_PORT` from another sandbox resolves against the wrong table, and
  handing an endpoint to a different sandbox does not work at all today.
  Bridge-scoped or process-global ids are required by the model and fix that.
* **Channels and bridges stop sharing an implementation.** The value ring
  (`8f33c76`) is right for a channel -- both ends in one heap, values shared
  rather than copied, nothing serialised -- and wrong in ownership terms for a
  bridge. Channels keep it; bridges get arenas and slots of tagged pointers.
* **The sandbox-side structure holds no heap pointers**, so the write barrier on
  it disappears and it need not live in the flint heap.
* **`0006` §5's per-port codecs come back**, in the form `0025` left room for:
  attached to a port, run by the writer, and bounded by what each format can
  express.
* **Terminology follows**: a port is an END, a channel joins two ends in one
  heap, a bridge joins ends that do not share one. `K_FLINT`/`K_HOST` stop being
  a hierarchy, because there is no near end and no far end.

## Order

1. Port ids out of the per-sandbox registry, which nothing else can proceed
   without.
2. The arena allocator and its refcount, with the reclamation test written
   first: kill a participant mid-write and assert the survivor reclaims.
3. The six verbs over the in-process ring, and channels left alone.
4. `push_event` onto the same structure, which is what fixes the race above.
5. Formats, and `check_sendable` growing its format dimension.
6. One-ended bridges, per platform.
