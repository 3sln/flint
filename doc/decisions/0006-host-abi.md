# 0006 — The host ABI: tokens, one event queue, and where the cost really is

Refines 0005 §5. The owner designed most of this; the notes below are the places
it needs sharpening, and the reasoning for each.

## Settled by the owner

- **Ports are NOT transferable and cannot be sent through a port.** Only data
  crosses. This is a large simplification: no ownership transfer, no capability
  leaking through message passing, and the wire format never needs to represent a
  port. The cost is that a capability cannot be delegated at run time. That is
  the right default — **transfer can be added later; it cannot be removed** — and
  the limit belongs in the README rather than being discovered.
- **A continuation token.** `open` signals the host and hands over a token; the
  host does its work and calls back with it to resume. The owner's own read of it
  is the correct one: *it is not an id, it is a continuation.*

## The token generalises — use it for everything that parks

Anything that parks parks the same way: `open`, a send to a full port, a receive
on an empty one. One waiter table, one token type, one resume path. The host
never learns what a thread is.

**Pair the index with a generation counter.** A bare index is reusable, so a late
or duplicated host reply resumes *whatever now occupies that slot* — a wrong
thread woken with a stranger's value, which is unfindable in production. Pack
`(index, generation)` into the token, bump the generation on free, and reject a
token whose generation does not match. This is a slot map and it costs one `u32`.

A host that never calls continue leaks a parked thread. Make that visible —
countable, and nameable in a diagnostic — rather than silent.

## One outbound event queue, not several exports

The owner sketched `__host_take_port_values__` alongside the open signal and a
close notification. Fold them into **one queue the host drains**:

```
{:kind :open-request :token t :name "the-thing"}
{:kind :message      :port p :bytes <ptr,len>}
{:kind :closed       :port p}
```

One export, one call per pump, everything batched and in a deterministic order.
Separate exports mean separate calls, separate ordering rules, and three chances
to forget one.

## Let the RUNTIME create the pair

The sketch has the host call `__host_channel__`, receive both ends, and hand one
back through continue. Simpler: when the runtime services an open request it
creates the pair itself, keeps the flint end, and the host's continue call
carries only an integer port handle. The host never holds two ends and never
hands one back — fewer steps, fewer states, nothing to get out of order.

## `continue` must ENQUEUE, never re-enter

If the host calls continue while the runtime is running — from inside a host
function invoked by wasm — a naive implementation re-enters the scheduler on top
of itself. Define it as: **continue records the answer and marks the thread
runnable; the scheduler runs on the next pump.** Never re-entrant. This is cheap
to specify now and a horrible class of bug to find later.

## Where the cost actually is, and therefore what to batch

The instinct to batch is right, and the reason matters for what to do about it. A
wasm↔host call is tens of nanoseconds. **The expensive part is marshalling**:
copying bytes out of linear memory, parsing, allocating host objects. So:

- **Serialise at send time into linear memory.** A message for a host port
  becomes bytes when it is sent, and the host reads byte ranges. The host never
  walks the heap and never needs to know the value encoding.
- **Drain many per call.** The event queue returns everything pending, so the
  per-message boundary cost goes to zero and only the per-pump cost remains.
- **Bound buffers in BYTES, not messages.** Back-pressure exists to bound memory,
  and one 4 MB message is not one message's worth of memory.

Eager serialisation costs work when the host never reads. Accept it: it is what
makes the drain cheap and the buffer bound meaningful. If a benchmark later says
otherwise, that is a measurement worth having.

## Formats, and the conversion that must be allowed to FAIL

The creating side names the format it receives: **JSON, EDN, and a binary EDN.**

For binary, look at **Transit (msgpack)** before inventing a fourth format — it
exists for exactly this, it is self-describing, and it already has the extension
mechanism that tagged literals need. Inventing one is a week that buys little.

**The important part: JSON cannot represent EDN.** Keywords, sets, symbols,
tagged values and non-string map keys have no JSON form. "The runtime will try to
convert" hides the failure, and the failures are the silent kind:

- a keyword becoming `"a"` does not round-trip back to `:a`;
- `{:a 1}` becoming `{"a": 1}` is convenient, lossy, and asymmetric;
- a set becoming an array loses its setness.

So: **a value that cannot be represented in the chosen format is an error at
send, naming the value and the reason.** Not a coercion. Where a convenience
coercion is genuinely wanted — keyword map keys to strings is the common one —
make it an explicit option on the port, off by default, exactly the way
`clojure.data.json` makes `:key-fn` the caller's decision.

## Lifetime: a host-held port is ROOTED by the host

The request was for the runtime to signal when a port is collected or closed.
Collection is the part to design away rather than signal.

**A port with a host end is reachable from outside the runtime.** If the
collector may take it because no flint value refers to it any more, every host
handle becomes a potential use-after-free across the boundary. So the host end is
a root: the port survives until the host closes it, full stop.

That leaves one honest event, which the queue already carries: **the flint end
closed** (`{:kind :closed :port p}`). The host learns its peer is gone, and
closes its own end when ready. No collection race, and nothing to signal about
the collector at all.

Closing from the flint side must also wake anything parked on that port, with a
clean error rather than a hang.

## What must be true at the end

- A stale or duplicated continue is **rejected by generation**, tested.
- Continue during a running scheduler enqueues rather than re-entering, tested.
- One pump drains many messages; a benchmark reports per-message cost at a batch
  size of 1 and of 1000, so "batched" is a number.
- Sending an unrepresentable value to a JSON port **fails, naming the value**.
- A host-held port is never collected; the flint end closing raises `:closed`
  and wakes anything parked on it.
- The three formats round-trip what they claim to, and the README says exactly
  what JSON cannot carry.
