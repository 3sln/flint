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
- The request/response layer exists as its own namespace, is used by the document
  driver, and parks rather than spins while waiting.
