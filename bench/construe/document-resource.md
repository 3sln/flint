# Documents: structure eagerly, content on demand

construe's document-extraction design. It lived in flint's decision record
until 2026-09-11 and was moved here, because it is not a decision about the
LANGUAGE -- it is the shape of one customer's use case, and the thing that
holds it honest is `test/document.clj`, which asserts 64 waves and 4 194 304
bytes against exactly this design.


**Documents: structure eagerly, content on demand**
*(formerly `document-resource`)*


**Status (per the record; not independently verified): shipped.** Both wave assertions pass: 64 waves, 4,194,304 bytes.
Replaces the paging idea from `construe-benchmarks` §5.

### What was decided

For a document capability (construe's extraction use case — read a
structure, find a table, read that table's cells), **the document's
structure loads once into flint memory in full; content crosses a port only
when something actually asks for it**, fetched in coalesced batches planned
entirely on the host side.

### Why paging was the wrong model, and what replaced it

Paging assumes extraction is a linear scan through a document; it is not —
most of a fifty-page document is entirely irrelevant to extracting
`{merchant, total, lines}`, and paging pays the cost of reading all of it
regardless. **Memory is the actual bottleneck**, so the whole design is
shaped around what genuinely has to be resident: a document's structure
(node id, type, box, page, parent/child) is a small fraction of total bytes,
and every interesting query runs purely against it — loading it once makes
tree exploration ordinary in-memory Clojure at full interpreter speed, with
**zero port traffic**, asserted directly by counting messages during a
structure walk rather than merely claimed. The alternative — making every
`children` call itself a message — would be catastrophic: a walk over five
hundred nodes becomes five hundred network round trips.

**The API states intent; only the host plans the actual fetch**, and this
split is deliberate rather than incidental: the caller asks for the *pieces*
it wants, never for byte ranges, and never decides how many requests to
make — only the host knows the storage backend's real cost characteristics
and the memory budget, so only the host can actually plan. The identical
script then runs efficiently against R2, a local disk, or an in-memory test
fixture, with no code change and no awareness of which one it is talking
to. This generalises directly into a broader pattern worth having once,
rather than reinvented per driver: **almost every real host capability is
request/response** — this document resource, a key-value store, an HTTP
client — so a small, shared layer over ports (a request id, matching replies
to callers, parking the calling thread until its reply arrives, with
cancellation and timeout) is built once rather than reinvented, and
differently, by every driver that needs it.

**The coalescing math has a genuinely surprising number in it, worth
keeping as a concrete illustration of why intuition undershoots here.** Merge
two fetch intervals across a gap exactly when fetching the gap is cheaper
than a second request: `gap_bytes / bandwidth < request_latency`. Plugged in
with representative R2 numbers (roughly 20 ms per request, 100 MB/s), one
round trip costs about **2 MB of bandwidth** — so the break-even gap is
measured in *megabytes*, and the correct policy is far more aggressive
coalescing than instinct suggests: fetching `a` through `c` and discarding
`b` in the middle is correct even for a fairly large `b`. The two constants
deciding this policy genuinely have to be *measured* per storage backend
rather than assumed, since they differ by an order of magnitude between R2
and a local file, and the whole policy follows directly from whichever
numbers are actually true for the backend in use.

**Memory is the constraint that ultimately caps the aggressive-coalescing
policy above, and it forces two requirements the naive version of it
misses.** Bytes fetched purely to bridge a gap and then discarded must
**never enter the guest heap at all** — otherwise over-fetching costs memory
as well as bandwidth, and the whole policy inverts on itself. And a wanted
set larger than the memory budget genuinely cannot be satisfied in one
batch, so the batch call has to be able to answer **in waves** — the caller
processes and releases a wave, and the next one arrives — which is a
multi-response request shape the request/response layer has to support
deliberately rather than by accident. **Delivery order is defined, not
incidental**: results arrive in the order they were originally *asked for*,
regardless of what order the fetch planner internally chose to coalesce
them in — a script whose observable behaviour depended on the planner's
internal coalescing choices would not be deterministic, and
`threads-and-ports` already spent real design effort specifically buying
determinism, so this is a place that effort would otherwise quietly leak
back out.

**The driver must not cache by default, and — critically — not even with a
weak reference cache.** If a script simply keeps every fetched content run
in memory, memory becomes proportional to the whole document again and the
entire exercise was pointless; the runtime cannot stop a script from
retaining values, but the *driver* should not retain them on the script's
behalf either way. A weak-reference cache is explicitly rejected rather than
merely omitted, and for a specific reason worth stating: a cache whose
contents depend on exactly when a garbage collection happened to run would
make program behaviour depend on GC timing, which is precisely the kind of
non-determinism `threads-and-ports` was built to eliminate everywhere else
in this system.

---
