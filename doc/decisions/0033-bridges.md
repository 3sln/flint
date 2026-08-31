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
format() -> :flint | :json-strict | :json | :edn | :cbor
memo() -> bytes | none
```

`memo()` is arbitrary data the BRIDGE supplies, carried alongside a port's id
whenever the port is serialised, so a receiver can reconstruct the connection
rather than merely name it. It is also what a snapshot stores, so a restored
sandbox can ask for its bridges back. NOT BUILT -- the verb is listed here
because the encoding leaves room for it and the two must agree.

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

The port machinery never invokes a codec and never learns what the bytes mean.
The codec is configuration the WRITER consults, which keeps the port agnostic
and makes the encoder ordinary guest code -- so a program that only uses
`:flint` never links a CBOR encoder.

### Identities serialise as themselves

Every one of these formats CAN express an identity -- EDN has tagged literals,
CBOR has tags, JSON can carry a convention object -- so a rule that only
`:flint` may name a port would be invented here rather than imposed by the
formats. What a format decides is whether it has ANY way to say it; `:json`
does and `:json-strict` deliberately does not.

**The serialised form is the real id**, not a reference into some per-message
table:

    #flint/port [333 <memo>]
    #flint/opaque ["foo" 111]

For a port the id may be process-bound, and that is fine -- it is the
embedder's number and the embedder's business.

**A PORT ALSO CARRIES ITS MEMO**, so the receiver has options rather than a
number it can only compare. The id is the fast path -- same process, port still
live -- and the memo is what makes the reference reconstructable when it is not:
a different process, or a bridge that has to be re-established. Bridge memos are
NOT BUILT (see below), so the slot exists in the encoding now and is filled when
they are.

An earlier draft of this file had the body reference identities by index into a
runtime-owned table, so a guest could only ever name an identity already present
in the message. It was solving a problem `0022` already solves, and it cost more
than it bought: a reference valid only inside one message cannot be stored,
logged, or forwarded later.

**Resolution is mediated, which is the whole of the safety argument.** A
receiver hands the runtime an id and asks for the thing; whether it gets one is
the grant table's answer, not the format's. NAMING IS NOT HAVING. A guest may
write any id it likes and get nothing back, which is `0022` restated rather than
new machinery -- "possession is not the check, the grant table is".

**One invariant this retires, deliberately.** `builtins.rs` says of an opaque's
host id: *"the host id is not readable, and there is deliberately no builtin
that returns it. Reading provenance from guest code would invite exactly the
check 0022 forbids."* A guest-side codec writing `#flint/opaque ["foo" 111]`
either sees the 111 or has the runtime emit it, and the second is awkward for a
text format the codec assembles as a string. Since resolution is mediated,
seeing an id grants nothing -- so the rule was guarding a door that already has
a lock, and it goes. It is listed here because it is a stated invariant and
should be retired on purpose rather than eroded.

**What the format still decides** is structural coverage, and rather than one
JSON with a judgement call per type there are TWO, so the caller chooses:

`:flint` (default), `:json-strict`, `:json`, `:edn`, `:cbor`.

### `:json-strict` -- for something that is not flint

Best effort into plain JSON, and a throw for what will not fit. Nothing a
foreign consumer has to know about flint appears in the output.

| flint | JSON | back as |
| --- | --- | --- |
| vector, set | array | vector |
| map, string keys | object | map |
| keyword, symbol, string | string | string |
| fixnum, double | number | number |
| bytes | base64 string | string |
| nil, boolean | null, boolean | same |
| table | array of objects | vector of maps |
| bigint | -- | REFUSED |
| map, non-string keys | -- | REFUSED |
| port, opaque | -- | REFUSED |

The three refusals are not arbitrary. **Type loss is acceptable; value loss is
not**, and that one rule decides the whole table:

* A keyword returning as a string is weaker typing over the same data.
* A BIGINT through a double comes back silently rounded, which is wrong
  arithmetic rather than weaker typing.
* Non-string keys cannot be expressed at all -- `{1 :a, "1" :b}` stringifies
  into one entry, so the map is not merely weaker, it is smaller.
* An IDENTITY has nowhere to go without a tag, and a port returning as a number
  is not a weak port, it is not a port.

So a strict port CANNOT DELEGATE. That is the right answer rather than a
limitation: an HTTP service on the other end has no use for a flint port.

**Integers are safe, and it is worth writing down why.** A fixnum is 47 bits
plus sign, +/-140 737 488 355 327. A JSON number is a double, exact to 2^53,
about 9.0e15 -- 64x the headroom. EVERY fixnum round-trips exactly, so
int-versus-double is type loss and never value loss. Only a bigint can exceed
it, which is why only a bigint is refused.

### `:json` -- for flint on the other end

Everything above, plus `$flintTag` for what plain JSON cannot say: port,
opaque, bigint, table and set. A tagged value is
`{"$flintTag": <name>, "$flintVal": <payload>}`, and a user map carrying that
exact key set is escaped on the way out or the encoding is not total.

**The table tag is columnar, and fidelity and size point the same way.** A
vector of maps repeats every key name on every row; columnar names each once:

    {"$flintTag": "table",
     "$flintVal": {"cols": ["id", "name"],
                   "data": [[1, 2, 3], ["a", "b", "c"]]}}

At 1 000 rows and 5 columns that is 5 key strings rather than 5 000, which is
the compactness a table exists for (`0026`) and which array-of-objects throws
away. `0026` was written early for exactly this reason -- "a tag is cheaper to
add before that format ships than after" -- and this is that moment for the
JSON encodings as well as for the wire codec.

**Keywords and symbols stay strings in BOTH.** Tagging them would restore
fidelity, but a JSON object key must be a string regardless, so `{:a 1}`
degrades either way -- and a keyword faithful as a VALUE while lossy as a KEY is
a worse inconsistency than uniform degradation. It also keeps the rule intact:
tags are for value loss and breakage, not for type loss.

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
