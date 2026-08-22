# 0008 — Documents: structure eagerly, content on demand

Replaces the paging idea in 0007 §5. Paging assumed extraction is a linear scan.
It is not: you read the structure, find the table, and read *that* table's cells.
Most of a fifty-page scan is irrelevant to `{merchant, total, lines}`, and paging
pays for all of it.

**Memory is the bottleneck**, so the design is shaped by what must be resident.

## The shape

**The structure is loaded once, into flint memory. Only content crosses a port.**

That division is the whole design:

- A document's structure — node id, type, box, page, parent/child — is small,
  perhaps a few percent of the bytes, and every interesting query (`children`,
  `type`, `select`) runs against it. Loading it once means tree exploration is
  **ordinary in-memory Clojure at full speed**.
- Content — the text runs, the image regions — is the bulk, and is fetched
  **only when something actually asks for it**.

The alternative, making every `children` call a message, would be catastrophic:
a walk over five hundred nodes becomes five hundred round trips. Structure
queries must not touch the port at all.

## What that demands of the storage side

This is a **platform change in construe**, recorded here because it is half of
the design (`~/Projects/@3sln/construe`, `doc/notes/document-split.md`).

Today `packages/media` inlines text into the node: `{type:'p', text, box}`, so
the manifest *is* the whole document. The split is:

- a **structure artifact** — nodes with a *reference* into content, no text;
- a **content artifact** — the runs, fetchable by byte range.

Two properties matter for the flint side to be cheap:

- **Range-addressable.** One fetch should serve a region, not one node.
- **Locality.** Lay content out so nodes near each other in the tree are near
  each other in bytes — then a subtree is one range rather than forty. This is a
  storage-layout decision that decides whether the driver can batch at all.

## Batching, again, one level up

0006 established that the boundary cost is marshalling, so drain many per call.
The same applies here and harder, because behind the host is **R2**: a fetch per
node is a network round trip per node.

So the content protocol is plural from the start — *fetch the content for these
nodes* — and the driver should gather what a pass needs before asking. A driver
that requests one node at a time is the failure mode this design exists to avoid,
and it is the easy thing to write, so say so in the README.

## Fetch planning: coalescing, and where the break-even really is

The layout gives the fetcher its leverage: **a top-level node owns a contiguous
chunk, subdivided among its children**. So a wanted set of nodes is a set of
INTERVALS, and planning a fetch is interval merging.

State that as a requirement on the layout rather than an assumption about
documents: *a node's content is one contiguous range, or a small set of them.*
Reading order and logical structure diverge sometimes — a table across a page
break, a footnote — so allow a set and let the merger handle it, instead of
over-constraining the writer and discovering the exception later.

### The API states intent; the HOST plans

The caller asks for **the pieces it wants**. It does not ask for byte ranges, and
it does not decide how many requests to make.

That division is deliberate: only the host knows the storage's cost
characteristics and the memory budget, so only the host can plan. The same script
then runs efficiently against R2, a local disk or an in-memory fixture, without
knowing which it is talking to.

### The cost model, and the surprising number

Merge two intervals across a gap when fetching the gap is cheaper than making a
second request:

```
gap_bytes / bandwidth  <  request_latency
```

Put real numbers in and the answer is startling. Against R2 at, say, **20 ms per
request and 100 MB/s**, one round trip costs about **2 MB of bandwidth**. So the
break-even gap is measured in megabytes, and the right policy is far more
aggressive coalescing than instinct suggests: fetching `a` through `c` and
discarding `b` is correct for very large `b`.

**Measure the two constants rather than trusting mine** — they decide the whole
policy, and they differ by an order of magnitude between R2 and a local file.

### Memory is the constraint that caps it

Bandwidth says merge everything; memory says stop. The budget is the binding
constraint and it produces two requirements the naive version misses:

- **Discarded bytes must never enter the guest heap.** If `a`–`c` is fetched to
  get `a` and `c`, the `b` in the middle is dropped at the boundary. Otherwise
  over-fetching costs memory as well as bandwidth, and the whole policy inverts.
- **A wanted set larger than the budget cannot be satisfied at once.** So the
  batch call must be able to answer **in waves** — the caller processes and
  releases, and the next wave arrives. That is a multi-response request, which
  the request/response layer below has to support deliberately rather than by
  accident.

Refusing an oversized ask would also be honest, but it pushes the chunking into
every caller and they will each get it wrong differently.

### Delivery order is defined, not incidental

Results arrive in the order they were asked for, whatever order the fetch planner
chose. A script whose behaviour depends on how the planner happened to coalesce
is not deterministic, and 0005 spent real design effort on determinism.

## The driver must not cache by default

If a script keeps every fetched run, memory is O(document) again and the whole
exercise was pointless. The runtime cannot stop a script retaining values, but
the driver should not retain them *for* it.

State plainly in the README: **memory is proportional to what you keep**, not to
the document. And no weak-reference cache — a cache whose contents depend on when
a collection ran makes behaviour depend on GC timing, which costs the determinism
that 0005 insisted on.

## And a general pattern falls out: request/response over ports

A port is a one-way message stream. **Almost every real capability is
request/response** — this document resource, a key-value store, an HTTP client.
Every driver will otherwise reinvent correlation ids, and reinvent them
differently.

So provide it once: a small layer over ports carrying a request id, matching
replies to callers, and parking the calling thread until its reply arrives. It is
a few dozen lines on top of what 0005 already builds, and it is the difference
between ports being a primitive people can use and a primitive people wrap badly.

Cancellation and timeout belong there too, or every driver invents those twice.

## What must be true at the end

- Structure exploration makes **zero** port traffic — asserted by counting
  messages during a tree walk, not by inspection.
- Peak memory is proportional to **content actually fetched**, not to document
  size. Measure it across documents of very different sizes with the same access
  pattern.
- A batched fetch of N nodes costs materially less than N single fetches, with
  the number reported. If it does not, the locality work is missing.
- **The coalescer's two constants are measured**, not assumed, and the chosen
  gap threshold follows from them.
- An ask larger than the memory budget is answered **in waves**, and peak memory
  stays under the budget throughout — tested with an ask several times the
  budget.
- Bytes fetched but not wanted never reach the guest heap, asserted by measuring
  resident memory rather than by reading the code.
- The request/response layer exists as its own namespace, is used by the document
  driver, and parks rather than spins while waiting.
