# Extern refs, and how much of the core should be a protocol

> **NOT BUILT — a design draft.** Two subjects in one file because they meet:
> the first needs the second (an extern type wants `Deref`, `Closeable` and a
> printer), and the second is bounded by what the first can afford.
>
> Nothing here is measured. Every number below is a target or an estimate and
> says which it is. A size or a ratio read without rebuilding is a stale
> artefact, not a result, and this document has not rebuilt anything.

---

# Part one — extern refs

## What this is for

flint has no host interop, by design and in four places that say so: the
analyzer refuses `new`, `.`, `set!`, `deftype*` and `reify*`
(`src/flint/analyzer.cljc:992`), `:import` is refused by name
(`analyzer.cljc:1089`), `flint.deps` tells a library author that "flint has no
host interop" when their code will not compile, and `README.md:1758` lists it
under Limits.

An **extern ref** is a flint value that names a host object — a
`java.io.File`, a `System.Net.Sockets.Socket`, a `Class`, a `MethodHandle`. It
is the one thing on that list that stops being true, and it stops being true in
the way `0022` made `(Object.)` stop being true: **as a value, never as
syntax.** There is still no `.` and no `new`. There is a namespace of ordinary
functions:

```clojure
(let [the-type       (flint.interop/type my-extern)
      the-getter     (flint.interop/getter the-type "theThing")
      the-setter     (flint.interop/setter the-type "theThing")
      the-dispatcher (flint.interop/dispatcher the-type "theMethod")]
  (the-getter my-extern)             ; -> <value>
  (the-setter my-extern "foo")
  (the-dispatcher my-extern 1 2 3))
```

Everything in that snippet is a value. `the-type` is an extern (the host's
`Class` object is a host object like any other). `the-getter` is an ordinary
flint closure. So `(map the-getter xs)` looks like it should work — it does
not, and the reason is in the pinning section below, which is the single
sharpest consequence in this document.

## The constraint that decides the whole design

**A flint value is sixty-four bits and a host object is not.**

This is more absolute than it looks, and the two ported runtimes are the
evidence. `runtimes/jvm/src/com/flint/rt/Val.java:3-22` records that the
previous JVM port used `Object` for every value, measured 85 ns per iteration
on a counting loop, and concluded *"Boxing is not a detail here; it was the
ceiling."* A value is now a NaN-boxed `long`; the heap is a `MemorySegment`
from a shared `Arena`, off-heap and invisible to the host collector
(`Space.java:7-29`); and the collector is flint's own, ported verbatim from
`runtime/src/gc.rs`, generational, copying in the nursery, with forwarding
pointers and a shadow stack (`Gc.java:7-14`, `Roots.java:5-28`). The CLR is the
same over `NativeMemory.AllocZeroed` and a raw `byte*` (`Space.cs:20-36`,
`Gc.cs:7-15`).

> **A stale claim to fix while doing this.** `0010`'s tier-2 paragraph says a
> port leans on "its garbage collector (the generational collector here was
> among the hardest parts and simply disappears)", `0029` and `0030` say the
> same, and both runtime READMEs say "The collector is gone." None of that has
> been true since 2026-08-29. Both ports have their own moving collector over
> their own linear memory. A design that assumed otherwise — and the first
> draft of this one did — would have concluded that externs are a wasm problem
> and that the JVM can just hold a reference. It cannot. **There is nowhere in
> a flint heap, on any of the three runtimes, that a host reference can live.**

So an extern ref is necessarily an **indirection**: a number in the flint heap,
resolved against a table the host owns and flint's collector never walks into.
That is the specification and it is right. What follows is about where the
table lives, who indexes it, which host thread may touch it, and when a slot is
released.

## The shape

### The extern table, in three halves

**One per sandbox**, beside the intern tables in `SharedRoots`
(`runtime/src/gc.rs:296-325`), and weak in the sense `INTERN_PORT` is weak
(`gc.rs:394-402`). Entry `i` is three things living in three different places,
and the split is what makes pinning work rather than being a detail of it:

| half | where it lives | who may read it |
|---|---|---|
| `flint[i]` — the canonical `TY_EXTERN` value | the flint heap, weakly | anyone |
| `meta[i]` — owner, state, type name | the **shared** heap (a `TY_NODE` segment) | anyone |
| `host[i]` — the host reference itself | the **owner's** memory, outside the flint heap | **the owning host thread only** |

On the JVM and CLR `host[i]` is an `Object[]` segment; on a JS/wasm target with
a `SharedArrayBuffer` heap it is a plain JS array **inside one worker**, which
is precisely why it cannot be a shared structure and why `meta[i]` has to be
separate from it. Any thread can ask *what* an extern is and whether it is
alive; only one thread can *touch* it.

`host[i]` is not in the flint heap, is not a `Value`, and is not reachable from
one. **The flint collector never sees it, never traces it and never moves it**,
which is the whole answer to "how does a moving collector cope with references
it must not move": it does not cope with them, because it never meets one.
`0022` needed a stored identity hash because an opaque value *is* a heap object
that moves; an extern's host reference is not in the heap at all.

`flint[i]` is weak, and the collector already knows how to do this: `refresh`
at minor collection drops an entry whose value was not forwarded, and at major
drops one whose value was not marked (`gc.rs:1501-1518`, `gc.rs:1644-1650`;
`Gc.java:350-361`, `Gc.java:387-390`; `Gc.cs:341-352`, `Gc.cs:378-381`). That
is the lifetime mechanism, and it is not new machinery — it is the port
registry's mechanism (`conc.rs:3038-3045`: *"A flint end that nothing refers to
any more has gone from the weak table. That is semantically identical to the
script having called `close`"*), pointed at a different table.

### The value, and its bit layout

A new type tag, `TY_EXTERN = 52`. `Layout::Vals`, `len = 1`, one fixnum slot.
Sixteen bytes.

A fixnum's payload is 48 bits and `as_fixnum` sign-extends from bit 47
(`value.rs:118-121`), so **bit 47 stays zero** and there are 47 usable bits.
Every runtime reads the slot as a non-negative fixnum and nothing has to know
about sign.

```text
  TY_EXTERN [pin]

    pin[47]     0        keeps the fixnum non-negative on every runtime
    pin[46:35]  owner    12 bits — host-thread id, 0 = GLOBAL
    pin[34:0]   index    35 bits — extern table index
```

**What each limit costs, with the real numbers.**

*Owner, 12 bits: 4095 host threads plus global.* The runtime's own cap is
**`MAX_EXECUTORS = 64`** today (`par.rs:38`, `Parallel.java:36`,
`Parallel.cs:35`), so 12 bits is sixty-four times the current ceiling. Seven
bits would fit today exactly; twelve is chosen so that raising `MAX_EXECUTORS`
is not a value-encoding change.

*Index, 35 bits: 34 billion slots.* This limit is never reached, because the
slots themselves are the constraint: 34 billion host references is 274 GB of
pointers before a single host object exists. **The limit a real program hits is
memory, not the index**, by three orders of magnitude, and the honest statement
is that the index field is oversized on purpose so that the owner field can be
oversized too.

The alternative was to put the owner in `meta[i]` alone and keep the value word
a bare index. Rejected for one reason: on a `SharedArrayBuffer` target the
question "may I touch this at all" has to be answerable from a word that is
certainly readable, and it is nicer for it to be answerable without an
indirection. The word's owner is a **cache**; `meta[i]` is authoritative, and
the two cannot disagree because an extern's owner is fixed for the life of the
slot (see *Recovery and death*).

The immediate-value alternative — `value.rs:19-20` reserves exactly two spare
NaN-box tags, `0xFFFE` and `0xFFFF` — would allocate nothing at all.
**Rejected**, for liveness. With a heap object, "is index `i` still wanted" is
precisely "did the collector forward or mark `flint[i]`", which the collector
computes anyway. With an immediate, liveness would have to be a mark bitmap
accumulated over every value word visited during the trace — and a *minor*
collection does not visit old-space words, so an index reachable only from an
old object would be swept while live. Fixing that needs a second remembered set
or a rule that only major collections may free, and both are more mechanism
than sixteen bytes are worth. The spare tags stay spare.

### Interning, in both directions

Two host objects must not share an index, and one host object must not have
two. The second is the load-bearing half:

* **host → index** is a host-side identity map, held **weakly on the host
  side**, and **per owner**: `IdentityHashMap` behind a `ReferenceQueue`, a
  `ConditionalWeakTable`, or a `WeakMap`. It lives with `host[i]`, in the
  owner's memory, because it holds host references.
* **index → canonical value** is `flint[i]`, above, shared.

With both, `(flint.interop/type x)` called twice returns *the same flint value*,
and everything downstream gets simple:

**`=` is index equality**, which is object identity, which is what `identical?`
already answers for a heap value. `category` in `kin/eq.kin` puts `TY_EXTERN`
in the scalar arm and `valeq.kin` needs no new code at all — the arrangement
`TY_OPAQUE` has today (`kgen/rt/eq.rs:20-33`).

**`hash` is `hash-long(index)`** — the *index*, not the packed word, so that
the owner plays no part in identity. One new arm in `kin/valhash.kin` beside
the `TY_OPAQUE` arm it is modelled on (`kgen/rt/valhash.rs:164-175`). Indices
are dense small integers, so they must go through `hash-long` rather than be
used raw.

**Both are stable across collection**, because an index is not an address. Same
conclusion as `0022`, by a different route, and worth stating in `0022`'s own
terms: *the hash must be stored, not derived from the address* — here the index
**is** the stored identity, doing double duty as the table key.

**Neither needs a host identity hash.** Deliberate, and it is what keeps
requirement 7 open: the JVM has `System.identityHashCode` and the CLR has
`RuntimeHelpers.GetHashCode`, and **JavaScript has neither**. A design that
hashed a host object would have foreclosed the JS port on day one. Interning
through a `WeakMap` is the portable shape and every host can supply it.

### The block allocation protocol, in the intern table's idiom

The premise needs one correction first. **There is no lock-free CAS allocation
loop in flint's intern tables.** The CAS loop is a *spinlock acquire* — one
atomic word per table — and the "try, see who wins, retry" behaviour lives one
level up: a thread allocates **outside** the lock, then re-probes **under** the
lock and discards its own allocation if a peer published first
(`runtime/src/strs.rs:124-244`; `Str.java:168-194`; `Str.cs:136-160`). Sharding
the lock was considered and rejected on a measurement: ~0.04% duty cycle
(`par.rs:80-87`).

Externs take the same three phases, plus a block:

```clojure
;; Claim a run of INDICES. Held under the lock for a bump and nothing else --
;; the lock is never held across an allocation, which is the invariant
;; `par.rs:229-232` depends on: a parked thread must never hold one.
(defn ^:method ^I32 claim-extern-block [^:mut ^Rt rt ^I32 n]
  (lock-extern rt)
  (let [^I32 base (extern-next rt)]
    (set-extern-next rt (+ base n))
    (unlock-extern rt)
    (return base)))

;; The per-executor cursor. NO ATOMICS: `blk-base` and `blk-end` live in
;; ExecRoots, which one executor owns.
(defn ^:method ^I32 take-extern-slot [^:mut ^Rt rt]
  (if (== (blk-base rt) (blk-end rt))
    (let [^I32 base (claim-extern-block rt EXTERN_BLOCK)]
      (set-blk-base rt base)
      (set-blk-end rt (+ base EXTERN_BLOCK))))
  (let [^I32 i (blk-base rt)]
    (set-blk-base rt (+ i 1))
    (return i)))
```

and the publish, which is the intern table's shape verbatim — allocate outside
the lock, re-probe inside it, adopt the winner:

```clojure
(defn ^:pub ^:method ^Value extern-for [^:mut ^Rt rt ^HostRef h]
  (comment "Phase 1: probe. `host-index-of` is the OWNER's weak identity map,"
           "so this runs on the owning thread by construction -- an extern is"
           "always MINTED by the thread that will own it. No allocation, so"
           "nothing needs rooting: the same reason `intern_probe` roots"
           "nothing (strs.rs:159-172).")
  (let [^I32 found (host-index-of rt h)]
    (if (>= found 0)
      (return (extern-value-at rt found))))

  (comment "Phase 2: allocate OUTSIDE the lock. This is what keeps the lock"
           "off every allocation path, and so keeps a parked thread from"
           "ever holding one.")
  (let [^I32 i (take-extern-slot rt)
        ^Value v (new-obj rt TY_EXTERN 1)]
    (if (nil? rt v) (return NIL))
    (set-slot rt v 0 (fixnum (pack-pin (own-owner-id rt) i)))
    (let [^RootIx base (mark rt)
          ^RootIx vi (push rt v)]

      (comment "Phase 3: publish. RE-PROBE, because taking the lock can park,"
               "and parking permits a peer's collection to MOVE the object --"
               "so `v` is read back from the shadow stack and never from a"
               "host local (`0031`, and `strs.rs:180-190` for the same"
               "sentence about the same hazard).")
      (lock-extern rt)
      (let [^I32 again (host-index-of rt h)]
        (if (>= again 0)
          (do (unlock-extern rt)
              (comment "Somebody got there first. Theirs is canonical; ours"
                       "is nursery garbage and costs a collection nothing --"
                       "and the index we took is returned by the next sweep,"
                       "because nothing will ever point at it.")
              (pop-to rt base)
              (return (extern-value-at rt again))))
        (host-put rt i h)
        (set-extern-meta rt i (own-owner-id rt) ST_LIVE (host-type-name rt h))
        (set-extern-value rt i (r rt vi))
        (unlock-extern rt)
        (pop-to rt base)
        (return (r rt vi))))))
```

**How much contention does this actually remove today?** Honestly: none,
because there is none. Green threads are cooperatively scheduled inside one
interpreter loop (`conc.rs:21-27`), and the multi-executor build is the only
thing that makes two host threads touch one sandbox — `0028` still lists the
intern tables among what is "not yet safe" under real parallelism. So the block
is not solving a measured problem; it is written this way so that the problem,
when it arrives, is already solved in the idiom `0019` chose for gas: *"each
thread draws a block from the global budget and spends it locally, drawing
again when it runs out. Contention drops by the block size, and the local spend
stays a plain non-atomic decrement."* Same sentence, different counter. It
would be a mistake to claim a contention number for it before `0028` closes.

`EXTERN_BLOCK` starts at 32 and is a knob. Waste is at most `EXTERN_BLOCK - 1`
indices per executor, and the sweep returns block tails that were never filled.

### The table grows the way the intern tables grow — almost

`InternTable::grow` doubles and rehashes, wholesale (`gc.rs:144-156`). The
extern table cannot do that, because an index that moved would be a value that
changed. So it grows by **appending a segment**: index `i` reads segment
`i >> SEG_SHIFT` at offset `i & SEG_MASK`. This is `table.rs`'s chunked column
store, whose `CHUNK = 256` is a power of two "so the row-to-chunk split is a
shift and a mask rather than a division" (`table.rs:12-29`).

Segments are where the specification's "linked extern heap chunks" survives.
They are chunks of the one table, not per-owner arenas — see *Item 4*, below,
for why. `host[i]`'s segments are additionally **partitioned by owner**, so a
thread's death is a range operation rather than a scan.

---

## Pinning: an extern belongs to a host thread

### Two different questions, which must not be conflated

**Reachability** — which green threads and which collections can *see* the
value — is ordinary garbage collection, and pinning does not touch it.

**Accessibility** — which host thread may *dereference* the value into the host
— is the pin.

They are separated structurally by the three-halves table above. `flint[i]` and
`meta[i]` are in the shared heap and are readable from anywhere; `host[i]` is
in the owner's memory. So:

* **A pinned extern may be stored in any collection.** Nothing special happens.
* **A pinned extern may be read out of a collection by any green thread on any
  executor.** Nothing special happens: the value word and `meta[i]` are both
  readable.
* **A pinned extern may be `=`-compared, hashed, used as a map key, printed,
  counted, and sent down a channel from any thread.** None of those touch
  `host[i]`.
* **Only an interop call — `type`, `getter`, `setter`, `dispatcher`, and the
  closures they return — is gated by the pin.**

That last line is the design. Everything expensive about pinning is confined to
one code path, in one namespace, that most programs never link.

### Why this is forced, not chosen

On two of the five intended targets the platform requires it. A JS worker's
objects are not reachable from another worker even when the *flint heap* is a
shared `SharedArrayBuffer`; wasm with an externs table has the same shape. A
design that assumed a host reference is globally readable would work on the
JVM, the CLR and native Rust and be unimplementable on JS and wasm — which is
requirement 7 violated at the foundation rather than at the edges.

And it is **useful on the three targets where it is not forced.** JVM and CLR
objects are not thread-confined by the platform, but a great many are confined
by contract: a UI toolkit's widget, a JDBC `Connection`, a `SimpleDateFormat`.
A host that mints those with a non-zero owner gets "call this on the right
thread" enforced by the scheduler for free, instead of by a convention nobody
checks. So the mechanism earns its place on all five targets even though only
two force it.

### Using an extern from the wrong thread yields

Not an error. The green thread parks, and becomes runnable **only on the
executor that owns the extern**. This is not a new mechanism: `park_on` is
already a flint `Value` used as a wake key (`conc.rs:653-657`), threads already
park on ports and on joins, and `pick` already scans `SC_THREADS` skipping what
is not runnable (`conc.rs:1569`). The additions are:

* `park_on` may be **an extern value**. Waking is not by thread id — it is by
  slot, which is what closes the id-recycling hazard the second addendum names,
  because a slot's owner is fixed and a slot cannot be recycled while any value
  names it.
* `pick` gains one test: a thread parked on an extern is runnable *for this
  executor* iff `owner == 0` or `owner == mine`, and is woken with a throw if
  the slot is orphaned.

**Parking on a slot rather than on an id is the whole answer to id recycling at
the scheduler level.** A green thread queued for owner 3 cannot be woken by a
*new* thread 3, because it is not queued for 3 — it is queued for slot 4711,
and slot 4711's state is authoritative.

### The rule that makes the yield safe

`vm.rs:564-611` already states it for every parking builtin:

> Rewind to the instruction itself and leave the operands in place: resuming
> re-executes the call, **which is why a parking builtin must decide to park
> before it changes anything.**

So the pin check is the **first** thing a getter, setter or dispatcher does —
before marshalling arguments, and obviously before calling. On resume the whole
call re-executes, marshalling included. A setter that marshalled its argument
and then parked would marshal it twice, which for a value type is waste and for
anything with an effect is a bug.

### The cliff: an interop call cannot park inside a native frame

This is the sharpest consequence in this document and it is not negotiable
without changing `vm.rs`. `parked` refuses in two cases, both with a clean
error (`vm.rs:568-600`):

```text
cannot park here: this call is nested inside native code (map, sort, reduce,
a lazy seq). Park from a green thread's own code instead.
```
```text
cannot park here: this native was reached through `apply` ... the call cannot
be re-executed on resume. Call it directly instead.
```

Therefore **`(map the-getter xs)` on pinned externs throws.** So does `sort`
with a comparator that dereferences one, so does a lazy seq that produces one,
and so does `(apply the-dispatcher x args)`. On global externs (`owner = 0`) all
of these work, because nothing parks. The failure is thus *conditional on
data*, which is the worst shape for a surprise: it works in the test that used a
JVM object and fails in production on a worker.

Three possible answers, and this draft does not pick one:

1. **Accept it and document it loudly**, with the error message extended to
   name the extern and say "hoist the interop call out of `map`".
2. **A batching form.** `flint.interop/on` runs a whole traversal on the owner
   thread in one park: `(flint.interop/on the-type (fn [] (mapv the-getter xs)))`.
   One park, one crossing, and it is *the same shape the wasm target needs
   anyway* for a completely different reason (see below). The convergence is
   suggestive.
3. **Make `map`, `filter` and friends re-executable**, which is a change to the
   VM's park rule and much bigger than it sounds.

**DECIDED, and none of the three.** The park moves to the operation's ENTRY.

A collection that receives an extern is PINNED at its top level to that
extern's thread, and adding an extern owned by a different thread to the same
collection throws. Every builtin that would otherwise park inside a native
frame -- `map`, `filter`, `reduce`, `sort`, `group-by`, a lazy seq, `apply` --
checks the pin ON ENTRY and parks there, which is green-thread code and legal.
`vm.rs:568` never sees it.

WHY THIS BEATS THE BATCHING FORM it replaces. `flint.interop/on` gave the
programmer a way to AVOID the cliff; this removes the cliff. The failure
conditional on data -- works with a JVM object in the test, throws on a worker
in production -- stops existing rather than becoming documented. `on` survives
as an optimisation for batching several operations into one crossing, which is
what it should have been.

THE OPEN PIECE, and it is the Equiv/Hash node bit wearing different clothes:
**pins must propagate on nesting.** A vector pinned to thread A placed inside a
map obliges that map to be pinned to A, or an operation on the map reaches A's
externs from anywhere. So a collection carries the UNION of its children's
pins, maintained at insert, and the throw generalises from "an extern owned by
another thread" to "anything pinned to another thread". Two consequences fall
out:

* **Persistence.** `(conj pinned x)` inherits the pin. Removing the last
  extern LEAVES IT SET -- a conservative over-approximation costs a slower path
  and never a wrong answer, which is exactly how `doc/goals/equiv-hash.md`
  reasons about `dissoc` and the leaf bit.
* **Where it lives.** Free, if it rides the variant node this document already
  describes -- "that node morphs into a variant of the same thing, with an
  externs slot". A collection holding no externs allocates nothing for this.

THE COST WORTH NAMING RATHER THAN DISCOVERING: blocking on entry runs the whole
traversal on the owner thread, so a `map` over a million elements containing
ONE extern is a million elements of work on one host thread. That is throughput
and not correctness, and it is a cliff of a different shape.

AND IN THE COMMON CASE NONE OF THIS EXISTS. A sandbox with no thread pool marks
its externs GLOBAL (`owner = 0`), and then there are no collection pins, no
entry guards and no propagation to maintain -- the machinery is conditional on
there being a pool to need it.

> **Global is the HOST'S DECLARATION, not an inference from the executor
> count.** Thread affinity belongs to the host OBJECT, not to flint's
> scheduler: a single-threaded sandbox embedded in a multi-threaded host that
> passes in EDT-confined widgets or a thread-bound database handle still needs
> pins. So the SDK setting defaults to global for a single-threaded sandbox and
> the host may say otherwise; the runtime never guesses.

### Determinism, which this spends

`0005` is unambiguous: *"A pure logic executor whose answer depends on
scheduling order is no longer worth its name. The scheduler must be
deterministic... Reproducibility is a large part of what makes this project
valuable; do not spend it here."*

A pinned park makes a green thread runnable on one executor rather than all of
them, so which thread runs next depends on which host thread reaches the
scheduler first. **That is non-deterministic, and there is no way to make it
otherwise** — the whole point is that the work must happen on a particular host
thread, and when that thread is available is not flint's to decide.

What survives, exactly:

* **A single-executor sandbox is fully deterministic**, pinned externs or not,
  because there is one queue and a pinned park either can run or can never run.
* **A multi-executor sandbox using only global externs (`owner = 0`) is exactly
  as deterministic as it is today**, which is to say: `0028` already had this
  question and this design does not make it worse.
* **A multi-executor sandbox with pinned externs is not deterministic**, and
  the README's determinism claim needs a clause rather than a footnote.

That is a real cost against a stated project value, and it is worth saying out
loud that it is being paid rather than discovered later.

**DECIDED: determinism is a property of a CONFIGURATION, and externs are
opt-in.** The deterministic sandbox stays exactly as it is and remains the
default; externs with a thread pool are an additive extension that spends
determinism knowingly. `0005` is not weakened -- it is scoped, and the scope is
the configuration that promises it.

WHICH MAKES IT AN ENFORCEMENT QUESTION RATHER THAN A DOCUMENTATION ONE. The SDK
should REFUSE to enable pinned externs in a sandbox configured deterministic,
rather than silently degrading it: a promise that quietly stops holding is
worse than one that was never made. And the README must name the
configurations that hold it instead of claiming it flatly.

### Deadlock between green threads is not reachable

Two green threads each holding an extern pinned to the other's host thread: A
parks for owner 1, B parks for owner 2. Neither holds anything — **a pinned
park releases the green thread entirely; it is data in a table.** Executor 1
runs A, executor 2 runs B. There is no cycle because there is no resource held
across the wait, and flint has no lock, no `locking`, no `dosync` and no STM to
supply one. (`TH_TX` is not a transaction: it is the system-port reply id for a
thread the host asked for, `conc.rs:76-79`. `swap!` is a retry loop over
`compare-and-set!`, `core.cljc:962-980` — it holds nothing, though a park inside
the update function will re-run it, which is what a retry loop is for.)

The reachable failure is different and simpler: **a queue for an owner that
will never run.** Either the sandbox is being driven by one executor and
something parked for owner 2, or the owning thread has died. The first is a
configuration error and should be caught at mint time — a host that mints a
pinned extern into a single-executor sandbox has made a mistake, and saying so
at the mint is far better than at the first use. The second is the subject of
the next section.

`Conc::drive` already reports a deadlock by name rather than hanging
(`Conc.java:1956-2009`), and that report must learn about pin queues: if every
unsettled thread is parked on an extern whose owner is not among the live
executors, that is a deadlock, and the message should name the extern and the
owner.

---

## Recovery, death and orphaning

### Two host events, and only the host can tell them apart

* **Recovery.** A pool thread died and was replaced by an equivalent one, with
  access to the same things. **Nothing changes.** The owner id is unchanged, the
  slots are unchanged, `host[i]` is unchanged, and a green thread queued for
  that owner proceeds when the replacement runs. The runtime is told so that it
  can wake anything that was parked, and for no other reason.
* **Death.** The thread cannot be recovered. Every slot it owned is
  **orphaned**, permanently.

Flint cannot infer which one happened, and should not try. Whether a
replacement thread is *equivalent* is a statement about what the thread had —
a `ThreadLocal` connection, a graphics context, a worker's module scope — and
only the host knows. So this is two ABI calls, not a heuristic:

```text
flint_extern_thread_recovered(owner)   -- same id, same slots, wake the queue
flint_extern_thread_died(owner)        -- orphan every slot owned by `owner`
```

**On the JVM**, a `ThreadPoolExecutor` subclass's `afterExecute`/`ThreadFactory`
knows whether it replaced a worker or shut one down; a dedicated thread's owner
calls `died` in a `finally`. **On the CLR**, the same over `ThreadPool` or a
`Thread` with a `try/finally`. **On JS workers**, `worker.onerror` and
`worker.terminate()` are deaths; a supervisor that respawns a worker which
re-registers the same capability set calls `recovered`. **On wasm**, the
embedder owns the table and therefore owns both events. Expressible everywhere,
and in each case it is a decision the host was already making.

### Why there is no generation counter, and what would happen if there were

The ABA hazard the second addendum names is real and this design closes it
somewhere other than where the conventional answer puts it.

**The slot is the identity, not the thread id.** Orphaning marks
`meta[i].state = ORPHANED`, and that mark is permanent *for that slot*. Two
facts then compose:

1. **An orphaned slot is never freed while any value names it**, because
   `flint[i]` is a weak entry and the sweep frees a slot exactly when the
   collector proves nothing points at it. That is the same computation that
   already governs interned strings and closed ports, and it is exact rather
   than approximate.
2. **A freed slot can only be reused when nothing names it**, by (1).

So an old extern can never meet a new occupant of its slot. The value says
"index 4711"; index 4711 says ORPHANED for as long as that value exists; the
instant no value exists, the index is reusable and there is nothing left to be
confused. **Thread-id recycling never enters the question**, because the check
never reads the thread id — it reads the slot.

The value word's cached `owner` is a fast path for the *live* case only, and it
cannot go stale, because an extern's owner is fixed for the life of its slot:
recovery keeps the id, death retires the slot, and there is no third event that
changes an owner. **If a host wants to move a host object between threads, it
releases the extern and mints a new one.** That rule is what makes the cached
copy sound, and it should be stated in the ABI rather than assumed.

**And if a generation counter were added anyway** — because some host insists on
handing flint a bare `(owner, generation)` pair from outside — here is the
budget, honestly:

```text
  pin[46:37]  owner       10 bits — 1023 host threads
  pin[36:25]  generation  12 bits — 4095 deaths per owner id
  pin[24:0]   index       25 bits — 33 554 432 slots
```

Which limit binds first in a realistic program? **The index**, and not closely.
33.5 million live externs is 268 MB of host pointers before a single referent
exists; a program is dead of memory long before. The generation is second: 4095
un-recoverable deaths *of the same reused id*. The owner count is never reached
— `MAX_EXECUTORS` is 64.

**Wraparound is the ABA problem one level down, so it must not happen.** On the
4096th death of owner id `k`, the answer is **refuse, and say what ran out**:
retire id `k` permanently and allocate a fresh id from the 1023-id space; when
*that* is exhausted, refuse the thread registration with

```text
this sandbox has exhausted its host-thread identity space: 1023 ids, each
retired after 4095 unrecoverable deaths (4 190 208 in total). A sandbox that
reaches this has a thread that is dying in a loop.
```

Accepting a wrap would re-admit exactly the bug the counter was added to
prevent, and widening is not available inside 47 bits without taking it from
the index. Refusing is loud, bounded, and diagnostic — and the bound, 4.19
million unrecoverable thread deaths in one sandbox's lifetime, is one no
healthy program approaches.

**This draft recommends no generation field**, and the recommendation rests
entirely on claim (1) above. If that claim is ever weakened — if a slot can be
freed while something still names it, for instance by a host that holds a bare
index across a snapshot — the generation comes back, and the layout above is
what it looks like.

### What orphaning costs the extern heap

* **The host half is released at the death notification.** On an orderly exit
  the dying thread clears its own `host[i]` range on the way out — it is the
  only thread permitted to, and doing it there is both correct and prompt. On a
  crash, nobody can clear it: on the JVM and CLR the objects are then reachable
  only from an `Object[]` segment the runtime owns, and clearing that segment is
  safe from any thread precisely because nothing else may hold those references;
  on JS, terminating the worker frees its heap outright. So the host objects go,
  in every case, at the death event.
* **The metadata half is not released.** `meta[i]` keeps `owner`, `ORPHANED`,
  and the type name, and keeps them for as long as any value names the slot.
  That is the cost of closing ABA and it should be stated as a number: **one
  metadata entry per orphaned-but-still-referenced extern**, a couple of words,
  freed by the ordinary sweep as soon as the last reference goes.
* **The slot is not reused** until then. Also by design, also the ABA fix.

So an orphaned extern **is not a leak of the host object** — the expensive
thing is released at the death event — and **is a small, bounded,
self-clearing retention of flint-side metadata**. That is the right side of the
trade, and it is worth saying explicitly because the second addendum names both
failure modes and this design takes neither.

### What the throw says

Following the house convention that an error names what a reader can act on
(`0026`'s rejections, `0032`'s quality bar):

```text
extern 4711 (java.sql.Connection) is orphaned: the host thread that owned it
(3) died and could not be replaced. Its host object is gone; obtain a new one.
```

Which extern, which type, which thread, and **that the thread died** rather
than that something is merely absent — because "absent" and "was killed" lead a
reader to different places. The type name is in `meta[i]`, captured at mint
time, which is why it is stored rather than fetched: at the moment this message
is built, there is nobody left to ask.

A green thread already parked on a slot when it is orphaned is woken with this
same throw at the death event, not left in the queue. That is the affirmative
half of "no silent deadlock": the loud failure is caused by a positive event,
not detected by a timeout.

### Collections containing an orphaned extern

Every walk over a collection must be safe, and each is, for the same reason:
**nothing except an interop call ever reads `host[i]`.**

| operation | behaviour |
|---|---|
| `=` | index equality. Never throws. Two orphaned externs with the same index are still `=`; an orphaned and a live one are different indices and so are not. |
| `hash` | `hash-long(index)`, unchanged by orphaning. **A map containing an orphaned extern key is still well-formed and still findable by that key**, which matters: the alternative is a map that silently corrupts when a thread dies. |
| `print` | `#<extern java.sql.Connection ORPHANED>`, from the cached type name. Never throws and never consults the host — it cannot, and on a foreign thread it may not. |
| `count`, `seq`, `conj`, `assoc`, `dissoc`, `into` | untouched; an extern is a word. |
| an interop call | throws, as above. |

**Invariant, and it wants a test:** no operation that walks a collection can
throw because of an extern in it — orphaned, pinned to a foreign thread, or
otherwise. Only `flint.interop`'s call path throws or parks. The test is a map
containing an orphaned extern, compared, hashed, printed, round-tripped through
a channel, and only then dereferenced.

### Detection cost, and how it stays free

`meta[i]` is one word, laid out so that **the common case is one comparison
against zero**:

```text
  meta[46:35]  owner   0 = global
  meta[34:32]  state   0 = LIVE
  meta[31:0]   type-name index (into the sandbox's string table)
```

A pin-and-liveness check reads the word and tests `(w >> 32) == 0` — live and
global. Any non-zero goes to a slow path that separates "pinned to me" from
"pinned elsewhere" from "orphaned". One load, one compare, one branch that is
perfectly predicted on the JVM and the CLR, where a host mints everything with
owner 0 and nothing is ever orphaned.

And the load itself is not extra: an interop call has to read `host[i]` anyway,
and the metadata read is on the same code path immediately before it.

**A program with no interop pays nothing at all**, not "nearly nothing": the
check lives in builtins reachable only from `flint.interop`, which is a
namespace unit shaken per var (`README.md:426`, `0003`, `0024`). A program that
never requires it never had it.

---

## Where this draft departs from the specification, and why

Six of the numbered items came through unchanged. One dissolved and one is
rejected. Saying which, and why, is most of the value of this document.

**Item 1** — the extern heap, block allocation, intern-table idiom. **Kept**,
with the correction that the idiom is *allocate outside a spinlock, re-probe
inside it, adopt the winner* rather than a lock-free CAS loop.

**Items 6, 7, 8** — targets, not foreclosing Go and JS, the user-facing
interface. **Kept**, with a marshalling rule and a capability gate this draft
adds, and with the `map` cliff named above.

**The pinning addendum** — **kept in full**, and it changed the shape of the
table (three halves rather than two) rather than being layered on top.

**The orphaning addendum** — **kept**, with the generation counter argued
*out* rather than in, on the grounds that the slot is a stronger identity than
the thread id and flint's weak sweep already computes exactly the property that
makes it sound.

### Item 3 — "the activation record grows a slot on its green thread's extern heap". **Dissolves, and that is a win.**

Because an extern ref is an ordinary heap value, pushing one on the stack is
pushing a value on the stack. The activation record needs no extern slot, and
`Frame` (`vm.rs:200-256`) needs no new field — which matters more than it
sounds, because `vm.rs:222-229` records what happened the last time a frame
cached something the collector could not see:

> The frame deliberately does **not** cache the closure. It used to, and that
> copy was a root the collector could not see: after a collection moved the
> closure, `UPVAL` and `SELF` read a stale address.

The rooting a green thread already has is exactly the rooting an extern needs.
When a thread parks, `save_current_state` turns the whole value stack into a
`TY_NODE` (`conc.rs:440-524`) and *"the collector traces it as it traces
anything"*; an extern in that stack is traced with it, which keeps `flint[i]`
alive, which keeps `host[i]` alive. When a thread dies, `settle` nulls
`TH_STACK` (`conc.rs:1496-1546`), the saved stack becomes garbage, and the next
collection releases the host objects. **Nothing has to be written for
green-thread lifetime. It already works.**

Two caveats — pre-existing bugs this makes more expensive, which should be
fixed with it rather than by it:

* **The `ST_FAILED` arm of `settle` does not null `TH_STACK`.** A thread that
  threw keeps its saved stack — and now its host objects — reachable from the
  scheduler for the life of the sandbox.
* **`SC_THREADS` never removes a thread object** (`conc.rs:669-706`). Dead
  green threads accumulate, and with externs in the picture an accumulated one
  can be holding a socket.

### Item 4 — a collection node "morphs" into a variant carrying an externs chunk. **Rejected.** Five reasons, in order of how hard they are to work around.

**(a) It covers only collection nodes, and values are held in about forty
places.** An extern in an atom, a var, a `TY_CONS`, an unforced `TY_LAZYSEQ`, a
`TY_TAGGED`, a `TY_EXINFO`'s data map, a closure's upvalues, a table cell, a
`TY_REDUCED`, a port's inbox ring, or a parked thread's saved stack has no node
to morph. Every one would need its own variant, or externs would be legal in
some containers and not others. This alone settles it.

**(b) A CHAMP node has no spare word, and the spare word cannot be at the
end.** Sub-nodes are addressed *backwards from the object's length* —
`bn-node` is `(slot rt n (- (- (olen rt n) 1) j))` (`kin/champ.kin:45-49`) — so
a trailing slot silently re-aims every sub-node read. `cn-count` is
`(quot (- (olen rt n) CN_BASE) 2)` (`kin/collnode.kin:40-41`), so a trailing
slot breaks the pair count and must keep it even. A leading slot shifts
`BN_BASE` for every entry access. Either way the variant needs its own type tag
so the `olen` arithmetic can branch, the two-tag node space becomes four, and
all nine `is-bmnode` guards become two-way tests.

**(c) It threatens the canonicality `mapeq` is built on.** `map-eq` short-
circuits on pointer identity of subtrees, and the measurement is why the file
exists: 20 000 entries, sharing structure, **840us → 1.15us**
(`doc/goals/data-structures.md:31-37`). That is only sound because a CHAMP is
canonical — and `data-structures.md:283-288` records that collision nodes
already are not, as a special case rather than a bug. A node shape that depends
on whether a value happened to be an extern adds a second exception to an
invariant that has already been surprised once.

**(d) Persistence has no good answer.** `assoc` path-copies the node on the
path. Sharing the parent's chunk pins a *dead* entry's host object behind a
live sibling version. Copying the chunk makes `assoc` O(externs in the node) —
paying for externs it never touched. Re-indexing on copy is worse: the index is
in the value word, so re-indexing rewrites entries that did not change.

**(e) Transients write nodes in place.** A transient owns a node when its edit
token matches (`kin/copies.kin:112-113`) and writes into it; a chunk hanging off
that node would be edited under a persistent parent that still names it.

**What is worth keeping from item 4** is the intuition underneath it: per-owner
arenas would make reclamation *prompt*. That is a real property and this design
gives it up — see *Lifetime*, where the house already has a position on exactly
this trade. Note that partitioning `host[i]`'s segments **by owner**, which
pinning requires anyway, recovers the one operational benefit that mattered: a
thread's death is a range clear rather than a scan.

### Item 5 — "an extern value is an index into the extern heap of the thing it is attached to". **Kept, minus the second half.**

An extern value is an index. It is an index into the sandbox's one extern
table, and it means the same thing wherever it is read from. That is what makes
`=`, `hash`, a map lookup, a `TY_CONS` and a parked thread's saved stack all
work without any of them knowing what an extern is.

The half that had to go is *relative to its owner*. `=` and `hash` are
implemented in a closed switch that receives an `Rt` and a `Value` and nothing
else (`kin/valeq.kin`, `kin/valhash.kin`) — there is no owner in hand and no way
to pass one, because the comparison that matters happens *inside* a map
descent, comparing a key in a node against a key on the stack. Owner-relative
indices would make those two words incomparable without threading the owner of
each through every walk. `valeq.kin`'s header already records what happens when
one arm of that switch is in the wrong place.

The *thread* half of "attached to" survives, and is now the pin — but note
carefully that it is a statement about **who may dereference**, not about
**where the index is valid**. Conflating those two is the mistake this section
exists to prevent.

---

## The hard questions

### Lifetime and reclamation

**A slot is released when its canonical `TY_EXTERN` value becomes unreachable**,
detected by the same `refresh` pass that already reclaims interned strings and
closed ports, at both minor and major collection. Releasing it means clearing
`host[i]`, which drops flint's last reference and hands the object to the host
collector.

**Pinning changes who does the clearing.** The sweep runs at a safepoint on
whichever executor triggered the collection, and that executor may not touch
another owner's `host[i]`. So a swept slot whose owner is not the collecting
executor is pushed onto that **owner's release queue**, drained by the owner at
its next safepoint. The queue is a plain per-executor `Vec<u32>` of indices —
not flint values, so `0031` does not apply to it — and draining it is a loop of
array stores. For `owner = 0` slots there is no queue and the sweep clears them
directly, which is every slot on the JVM and CLR.

**A green thread's death releases its externs** with no special code, via
`settle` nulling `TH_STACK` (subject to the `ST_FAILED` bug above).

**A collection node's death releases its externs** with no special code either,
because the node held an ordinary value that is now unreachable.

**It is not prompt, and that is a stated house position rather than a
concession.** `lib/flint/port.cljc` says it for ports:

> `with-open` closes on the way out, including on a throw, and it is the shape
> to reach for. If a script simply drops its last reference to a port, the
> collector finds it unreachable and the runtime closes it on the script's
> behalf — but that is a *safety net*: it is deterministic, and it is not
> prompt, and a host holding a socket open until then is a real cost.

Extern refs take the same rule and the same words. Three things follow:

* **`flint.interop/release!` exists**, and `with-open` extends to externs. An
  explicitly released extern's slot goes to `ORPHANED` immediately — the same
  state, reached deliberately — so using it afterwards gives the same clean
  throw, with "released by this program" rather than "its thread died".
* **The extern table is heap pressure the collector cannot see.** A hundred
  16-byte `TY_EXTERN` objects can pin a hundred megabytes of host buffers, and
  nothing in the nursery's sizing knows that. So the table keeps a **pressure
  count**, and crossing a threshold requests a major collection — the same idea
  as `LARGE_OBJECT` (`gc.rs:38-42`) making a 16 KiB object skip the nursery,
  applied to a size flint is told rather than one it can measure. The host
  supplies the weight, or supplies nothing and gets a count.
* **Minor collections do release**, because `refresh` runs at minor
  (`gc.rs:1501-1518`) and a short-lived extern that never left the nursery goes
  at the first one. The promptness gap is only for a promoted extern, which is
  what `release!` and the pressure trigger are for.

**Flint has no pinning of its own**: no pin bit, no handle table, no address
stability for anything young. The three existing substitutes are "be old", the
stored-id trick (`0022`), and `Gc::epoch`. This design uses the second, and the
word "pinned" in this document always means *pinned to a host thread*, never
*pinned in memory*. That collision of terms is unfortunate and worth a sentence
in the README.

### Persistence and structural sharing

Nothing changes. An extern ref in a map is a 64-bit value in a node slot, so:

* `assoc` path-copies as it always did, copying a word;
* two versions of a map sharing a node share the extern refs in it, and the
  host object stays alive while *either* version does, which is exactly what
  sharing an immutable value means;
* `dissoc` drops the value; if it was the last reference, the next collection
  releases the slot;
* a transient owns and rewrites nodes containing extern refs with no more
  ceremony than for a keyword;
* `mapeq`'s identity short-circuit is untouched and the CHAMP stays canonical;
* **a collection containing pinned or orphaned externs is shared across threads
  freely**, per the invariant above.

This is the section that would have been three pages under item 4, and it is
seven lines because the indirection is in the table rather than in the node.
That asymmetry is the argument.

### Identity and equality

* `identical?` — pointer equality on the canonical value. True iff the same
  host object, because interning gives one canonical value per index.
* `=` — the same answer, via `category`'s scalar arm. **No structural
  comparison and no host `equals`.** A host `equals` would be arbitrary
  flint-visible host code running inside `=` — inside a map descent, inside a
  hash bucket walk — and it could not run at all for an extern pinned to
  another thread without `=` learning how to park. `valeq.kin`'s header calls
  `=` *"THE HOTTEST FUNCTION IN THE RUNTIME, and the one where a disagreement
  does the most damage."* If a program wants host equality it calls a
  dispatcher for it, in the open, where the park is visible.
* `hash` — `hash-long(index)`. Stable for the life of the extern, agrees with
  `=` by construction, unaffected by owner or by orphaning, and needs no host
  identity hash (which JS does not have).
* `kind` — `:extern`, one new arm in `kin/tablekind.kin`. `0005`'s rule
  requires it: *"anything a guest can hold gets a kind of its own"*, because a
  type answering `:other` cannot be dispatched on and would be caught by every
  future `extend-protocol :other`.
* Printing — `#<extern java.io.File>`, or `#<extern java.io.File ORPHANED>`,
  from the cached type name. **No read syntax**, for `0022`'s reason exactly: a
  value whose printed form can be read back is forgeable by construction.

### Passing through ports

A port's send already walks the message and decides what may cross
(`check_sendable_at`, `conc.rs:1337-1420`), and already answers a question of
exactly this shape for ports themselves. Externs add one row:

| sent | through | | why |
|---|---|---|---|
| extern | channel | **yes** | both ends are in this heap, so the index means the same thing at both. Free — a channel send is a pointer move. Pinning is irrelevant: the receiver may hold and compare it; only an interop call parks. |
| extern | host port, `:format :flint` | **yes** | the runtime is the encoder (`0025`), and the far end is the host that owns the table. `K_EXTERN = 19` carries index **and owner and state**, because the host needs to know which of its threads may touch it and whether it is orphaned. |
| extern | any codec reachable from the guest | **no** | the receiver could write the same bytes, and then a host object is mintable from an integer. `0025`'s rule for ports and `0022`'s for opaque values, unchanged. |
| extern | a bridge to **another sandbox** | **no** | a different heap has a different table; the index would name one of our objects from outside. Identical to the channel-endpoint refusal, with the same error shape. |

**"Ports may cross real host threads" — and the index survives that.** An
executor is a host thread sharing one sandbox (`RtParallel.java:11-15`); the
extern table's flint and metadata halves are in `SharedRoots`, so an extern sent
between green threads is valid whichever executor picks either up. Pinning is
what makes this *safe* rather than merely *possible*: without it, a socket sent
down a channel would be touched by whichever executor happened to run the
receiver, which is the data race the sandbox has no way to see. With it, the
receiver parks and the touch happens on the right thread.

What flint still cannot promise is that a **global** (`owner = 0`) host object
tolerates concurrent access. A `java.util.ArrayList` minted with owner 0 and
sent to two green threads is a race, and it is the host's error to have minted
it global. That belongs in the README's Limits beside the others.

### Snapshots

`0015` exports and imports VM state, and an extern cannot survive it: the host
object is not in the heap being copied, and neither is the thread that owned it.

The answer is `0022`'s amended answer, which was arrived at painfully and should
not be re-derived: **preserve the index, do not erase it.** An imported extern
is **dangling**, and:

* a host that wants the shelved sandbox to carry on **rebinds** index 4711 to a
  live object *and an owner*, exactly as it rebinds an opaque id;
* a host that does not, does nothing, and the first use throws;
* the snapshot carries the **type name** — already in `meta[i]` — so the throw
  can say *"extern 4711 (was `java.io.File`) did not survive the snapshot"*
  instead of naming a number.

Erasure was tried for opaque values and `0022` records why it was wrong:
*"Erasure also made shelving useless... a sandbox holding a file handle comes
back holding a handle to nothing, and there is no identity left to rehydrate
against."* The same argument, more literally.

**And this is the one place the ABA argument gets thin.** A snapshot is a bare
index that outlives every value — the exact thing the "slot is authoritative"
claim relies on not happening. It is safe only because **an imported extern is
dangling until the host rebinds it, and is never resolved against a live table
by index**. That sentence is load-bearing and must be a test, not a comment.

**Open:** whether `export` should *refuse* a heap containing live externs by
default, with rebinding opt-in. Safer, less useful, not decided here.

### The interface, and what crosses the boundary

`(flint.interop/type x)` returns an extern for the host's type object.
`(getter t "name")`, `(setter t "name")` and `(dispatcher t "name")` return
**ordinary flint closures** that close over an extern for the resolved accessor
— a `MethodHandle`, a `VarHandle`, a delegate — and call one builtin. No new
function type, `kind` still says `:fn`, and a getter is a value like any other.

**Types are pinned exactly as values are, and the accessors inherit it.** A
`Class` obtained in worker 1 is worker 1's; a `MethodHandle` resolved against it
is worker 1's; `(the-getter x)` from worker 2 parks and runs on worker 1. On JS
this is not a policy, it is the platform. Note the composition: `type` is itself
an interop call, so `(flint.interop/type pinned-x)` parks first and mints the
type extern on the owner's thread, which is the only thread that could have
produced it anyway.

Resolution is **once, eagerly, at `getter` time**, and that is the point of the
shape: a `MethodHandle` looked up once and invoked in a loop is a different
performance story from reflection per call, and the API makes the difference
visible instead of hiding it behind a cache. With pinning it buys something
more: one park at resolution, then a per-call park only when the caller is on
the wrong thread.

**Marshalling is a closed, lossless set and nothing else.** In and out:
`nil`↔null, boolean, integer, double, string. Everything else is an extern. Not
because deep conversion is hard, but because it is wrong: converting a host
`List` to a flint vector on every call copies it, and converting it back cannot
know whether the callee intended to mutate it. `flint.interop/->flint` and
`->host` are explicit, take the collection kinds, and are cheap to skip. And
marshalling happens **after** the pin check and **on the owner's thread**, per
the re-execution rule.

**Open, and it needs measuring rather than deciding:** what an integer wider
than a flint fixnum does. A fixnum is 48 bits signed (`Val.java:74-84`); a Java
`long` is 64. Promoting to `TY_BIGINT` is correct and allocates; truncating is
not an option; throwing is honest and annoying. The right answer probably
differs for a return value and for an argument.

### Capability, which is the part that can go badly wrong

Reflective access to host types from a sandbox is a hole the size of the host.
`(flint.interop/type x)` on any extern reaches the type object; from a `Class`
one reaches `getClassLoader`, and from a classloader one reaches everything.
This is the classic escape and it is not hypothetical.

Two gates, both required, neither sufficient:

* **A workspace guard on the namespace**, `^{:flint/capabilities-guard
  [:interop]}`, in the shape `flint.host/request` already uses (`0036` step 8).
  Checked where the reference is written; emits nothing. It answers "may this
  workspace do interop at all", which is coarse and worth having.
* **A host policy hook on every member resolution.** `type`, `getter`, `setter`
  and `dispatcher` all ask the host, by type name and member name, and the host
  answers. The host decides; the runtime takes no view. This is `0022`'s central
  claim one level down: *"a capability check is the host recognising this
  specific object in its own grant table — never a type test"*, and here a type
  test would be worse than useless, because the dangerous types are ordinary
  ones.

**The trap `0022` names is the trap here too, and sharper.** With opaque values
the hazard was that "is it opaque" stops being an authority check once guests
can mint them. With externs the hazard is that a *legitimately granted* extern
is a path to types nobody granted. A host that allows `type` on anything has
allowed everything. That sentence should be in the README before the feature
ships, not after.

**DECIDED, and the sentence above stops being true.** The surface is
deliberately narrow at three points:

* **Externs enter only through a PORT**, which is already a point of
  regulation. There is no other door.
* **`flint.interop/type`, NOT `clojure.core/type`.** `type` in core is a total
  function on every value, so overloading it would put the interop surface
  within reach of any code that happens to call it. In its own namespace it is
  something a workspace must have REQUIRED, which the capability guard can
  gate.
* **It yields an OPAQUE EXTERN-TYPE, not a host `Class`.** From that handle a
  guest obtains opaque CLOSURES that act as accessors on values of the same
  type. The path from an extern to the classloader is gone, because nothing
  hands back a `Class` to walk.

WHICH TURNS AMBIENT AUTHORITY INTO ORDINARY CAPABILITY DISCIPLINE. The accessor
closure IS the capability: holding one is the grant. It is a value, so it can
be passed, stored, withheld and revoked by not sharing it, and the SDK builds
them -- so a host may regulate how they are constructed, override them, or
switch the feature off entirely. One resolution per TYPE serves every instance,
so the cost is per-type rather than per-access.

THE CHECK THAT MUST EXIST: an accessor built for type `T` and applied to a
value of type `U` refuses at call time. The closure outlives the resolution
that produced it, so the type it was built for travels with it.

AND IT COMPOSES WITH (1) RATHER THAN MERELY COEXISTING: `(map getter xs)` works
because the entry guard parks before the traversal, so an accessor is an
ordinary function everywhere an ordinary function goes.

**Still design work**, and named as such: the accessor-construction protocol,
what the SDK's override looks like, and how a host revokes a grant already
handed out.

## What each runtime supplies, and what stays in `kin`

**Shared, in `kin`** — one source, three targets, verified byte-identically by
`kin/scripts/verify` and gated by `bin/check-kin`:

* the `TY_EXTERN` object and the pin packing/unpacking: `new-extern`,
  `is-extern`, `extern-index`, `extern-owner`, `pack-pin`;
* the `=`, `hash`, `kind`, `describe` and codec-tag arms;
* the block cursor and `take-extern-slot`;
* the three-phase `extern-for` protocol above;
* the pin-and-liveness check, and the decision to park, throw or proceed;
* the sweep's flint half and the per-owner release queue's drain;
* the sendability rules and the snapshot rules;
* `flint.interop` and `flint.gc` as ordinary `.cljc` namespace units.

**Per runtime, hand-written** — the extern ABI. Fourteen primitives, short on
purpose, because everything above it is shared:

| | |
|---|---|
| `host-put(i, ref)` / `host-get(i)` / `host-clear(i)` | the owner's segmented host array |
| `host-clear-owner(owner)` | range clear on death |
| `host-index-of(ref)` | the owner's weak identity map, host → index |
| `lock-extern` / `unlock-extern` | one atomic word, in `Parallel` |
| `own-owner-id()` | this executor's owner id, or 0 |
| `host-type-of(i)` / `host-type-name(i)` | the type object; the name, cached at mint |
| `host-member(type-i, name, which)` | resolve a field or method; **calls the host policy hook** |
| `host-invoke(handle-i, target, base, n)` | call, arguments from a contiguous run of shadow-stack roots, the `invoke_roots` convention (`vm.rs:512-543`) |
| `host-marshal-in` / `host-marshal-out` | the closed scalar set |
| `host-weight(i)` | optional pressure hint, 0 if the host does not know |

plus the two lifecycle calls **into** flint: `thread_recovered(owner)` and
`thread_died(owner)`.

Concretely: **JVM** — `Object[]` segments, `IdentityHashMap` +
`ReferenceQueue` (or `ClassValue` for types), `MethodHandles.Lookup`,
`AtomicInteger` and `Thread.onSpinWait` as `Parallel.java:153-161` already does.
**CLR** — `object[]` segments, `ConditionalWeakTable`, `System.Reflection` +
`Delegate.CreateDelegate`, `Interlocked.CompareExchange` and
`Thread.SpinWait(1)` as `Parallel.cs:139-147` already does.

Both ports already have every concurrency primitive this needs and **neither
has a single host weak reference today** — `WeakReference`, `WeakHashMap`,
`ReferenceQueue`, `ConditionalWeakTable`, `GCHandle` and `Cleaner` do not appear
anywhere in `runtimes/*/src`. All weakness in flint today is flint's own, inside
flint's own collector. So the weak identity map is genuinely the first
host-GC-aware thing either port will contain, and it should be one file with one
job.

### wasm and native Rust, deliberately omitted, and what would change

**wasm.** There is no in-process host object. The table's host half lives on the
*embedder's* side of the ABI (`0006`), an index is issued by the embedder much
as an opaque host id already is, and every getter, setter and dispatcher call is
an ABI round trip. Which means **every interop call parks**, whether or not it
is pinned — and that collides head-on with the native-frame cliff, because now
even a global extern cannot be read inside `map`. The honest consequence is that
`flint.interop` on wasm needs the batching form (`flint.interop/on`) as a
*requirement* rather than an optimisation. That the same form is the best answer
to the pinning cliff on JS is the strongest argument for designing it properly
rather than deferring it.

**Native Rust.** In-process, so the host half is a `Vec<Box<dyn Any + Send>>`
and the mechanics are the JVM's. What Rust does not have is **reflection**:
`host-type-of` and `host-member` cannot be built from a name. So on native Rust
`flint.interop` is a **registration** API — the embedder registers named
accessors against a type it owns, and `getter` looks up a registration rather
than reflecting. Same signature, different function, and a good reason for
native to be last rather than first. Pinning maps cleanly: `Send`-less host
types get a non-zero owner and the type system and the scheduler agree for once.

### Go and JavaScript, which must stay possible

**Go.** `reflect` covers members; `weak.Pointer` (Go 1.24) and
`runtime.AddCleanup` cover the weak identity map; a `map[any]int` covers
interning where the referent is comparable, `unsafe.Pointer` where it is not.
Goroutines are not host threads, so the owner id would name a
goroutine-plus-channel rather than an OS thread — which is *closer* to flint's
model than the JVM's is, and the park becomes a channel send. Nothing
foreclosed. The thing to watch is that Go's `reflect` boxes, so the marshalling
set matters more.

**JavaScript.** `WeakMap`, `WeakRef` and `FinalizationRegistry` are all present;
property access by name is native and dispatch is `obj[name](...)`, so
`flint.interop` is the *cheapest* on JS of any target for a same-worker call —
and the *most* pin-constrained across workers, which is the case that produced
this addendum. **JS has no identity hash**, which is why nothing here uses one.
If the JS port borrows the wasm data structures it inherits the wasm embedder
story too, except that on JS the embedder and the host are the same context, so
the same-worker round trip is a function call. **Do not design the wasm ABI so
that a same-context call has to pay a crossing**, or the cheap case is
foreclosed for the sake of the expensive one.

## Cost when unused

`0005` §2 states the requirement and this design has to meet it: *"a program
that never mentions `open`, `channel` or thread spawning must produce a module
with no scheduler, no port machinery and no host callback surface — the same
size as today, within noise. Assert it with a test and report the number."*

What a program with no externs pays:

* **The table: nothing.** Zero segments allocated until the first extern, on the
  model of `INTERN_PORT`'s four slots — *"a program with no ports never grows
  it"* (`gc.rs:415-416`).
* **The sweep: one branch.** `if extern_count == 0 { skip }` at two sites.
* **The pin check: nothing.** It lives in builtins reachable only from
  `flint.interop`, a namespace unit shaken per var. Not linked, not paid.
* **The scheduler: one arm** in `pick`'s existing `park_on` dispatch, taken only
  by a thread parked on an extern.
* **`flint.interop` and `flint.gc`: nothing.** All reflection lives there.
* **`kind`, `=`, `hash`, `describe`, the codec: one arm each, and these are the
  real cost**, because they are in `kin` and therefore in the module whether
  used or not. `kind-of` already runs a chain of ~25 type tests with no `case`
  (`kin/tablekind.kin`), so a 26th is small but not free; `category` needs *no*
  new arm, because `TY_EXTERN` falls into the scalar default the way
  `TY_OPAQUE` does.
* **`TY_MAX` 52 → 53**, costing the distinctness assertion one entry
  (`obj.rs:150-171`) and nothing at run time.

And for a program that uses externs but no pinning — every JVM and CLR program
— the marginal cost of the whole pinning apparatus is **one load and one
compare per interop call**, on a path that was about to cross into the host
anyway.

**Target: unchanged within noise, and it must be measured, not asserted.**
`test/modularity.clj` already asserts module sizes by symbol name and the number
goes in this file when there is one. Until then this section is a claim about a
design, not a result.

## What must be true

* A host object handed in twice is **one** flint value: `identical?`, `=`, and
  the same hash. Tested by asking twice and by round-tripping through a map.
* An extern used as a **map key is still findable after a collection**,
  including a major one that moves the `TY_EXTERN` object — `0022`'s test, with
  a forced collection between `assoc` and `get`.
* **Dropping the last reference releases the host object**, asserted by the host
  observing its own reference queue, not by flint asserting about itself.
* **A dead green thread releases its externs**, including one that failed. (This
  fails today, on the `ST_FAILED` path.)
* **A pinned extern used from the wrong executor parks and then proceeds**, with
  the result identical to the same call made on the owner.
* **A pinned interop call inside `map` throws the native-frame error**, and the
  message names the extern. Asserted, because it is the surprise.
* **A parking interop call changes nothing before it parks**: a setter that
  parks is observed, on resume, to set exactly once.
* **Recovery is invisible.** A thread replaced by an equivalent one resumes the
  queue, and every extern still works.
* **Death orphans, and orphaning is permanent.** Including the adversarial case:
  kill owner 3, register a new thread that receives id 3, and assert the old
  extern still throws.
* **An orphaned extern never blocks a collection walk.** A map containing one is
  compared, hashed, printed and sent through a channel; only the dereference
  throws.
* **A green thread parked on a slot that is then orphaned is woken with the
  throw**, not left in the queue.
* **The deadlock report names a pin queue** whose owner is not live.
* **Every codec reachable from a guest refuses an extern**, with the same error
  shape a port gets.
* **A guest cannot mint one.** No builtin turns an integer into an extern;
  asserted directly, as `0022` asserts it for host ids.
* **The member policy hook is consulted**, with a test where the host refuses
  `getClassLoader` and the refusal is a clean `SecurityException`.
* **A snapshot round trip leaves a dangling extern that throws by name**, a
  rebound one that works, and — the ABA case — **an imported index is never
  resolved against the live table**.
* **A program with no externs is the same size**, by symbol name, in
  `test/modularity.clj`.
* **All three runtimes agree**, via the conform harness — and the extern ABI is
  the seam where they will not, because it is the one part not generated from
  one source.

## Open questions, named as open

1. **The `map` cliff.** Accept, batch with `flint.interop/on`, or change the
   VM's park rule. Batching looks right and is undesigned, and wasm needs it
   independently.
2. **Determinism.** Pinned parks in a multi-executor sandbox are not
   deterministic and cannot be. `0005`'s claim needs a clause; whether that
   clause is acceptable is the user's call, not this document's.
3. **The promptness gap.** Releasing at collection is the house rule for ports,
   extended to file handles and sockets by analogy. Good enough, or does an
   extern want a stronger default? Refcounting is where `0031`'s whole class of
   bug lives, so it is not free.
4. **Whether `export` refuses live externs by default**, and the snapshot ABA
   case above, which is the one place "the slot is authoritative" is not
   self-enforcing.
5. **Integers wider than a fixnum**, at the marshalling boundary.
6. **Whether the extern table is per sandbox or per host runtime.** Per sandbox,
   here, so an extern cannot cross a sandbox-to-sandbox bridge. A process-wide
   table would let it and would immediately raise the forgery question `0022`
   answers for opaque ids — with a worse answer, because the guest would be
   naming a slot another sandbox filled.
7. **What `(flint.interop/type x)` does for a non-extern.** Throwing is
   defensible; answering the flint kind is defensible; nil is probably wrong.
8. **The wasm batching shape**, which decides whether the JS port shares the
   wasm interop path or has its own.
9. **Whether an extern may carry metadata.** `0022` said no for opaque values,
   deliberately, and said it was additive later. Here the answer may want to be
   yes, because metadata is how a protocol is extended per value (`0005` §6) —
   which is the next part's subject and exactly what the weak map is for.
10. **Whether minting a pinned extern into a single-executor sandbox should be
    refused at the mint.** It is a guaranteed future deadlock, and refusing
    early is cheap; but a host may legitimately intend to add executors later.

## Weak references, which the specification is right to raise and wrong about where

Extending a flint protocol to a host type needs a map from type to
implementation that does not pin the type. The specification proposes
`flint.gc/weak-map` and `flint.gc/weak-ref` as guest-visible primitives. Three
things to say.

**First, the weak map is needed inside the runtime before it is needed by a
guest.** The host-side identity map — host object → index — must be weak or
nothing is ever released. That is not `flint.gc/weak-map`; it is one file per
port using the host's own facility. It is load-bearing and not optional.

**Second, a weak map keyed by externs is nearly free, and a general one is
not.** Keyed by extern, the key is an index, the entry dies when the slot is
swept, and the sweep already runs — no new collector phase, no fixpoint. Keyed
by arbitrary flint values, a weak map is an **ephemeron table**: an entry is
live iff its key is live *by some path that does not go through this table*,
which the major mark phase can only decide by iterating to a fixpoint. That is a
real cost in the collector's hottest loop. **Recommended split:**
`flint.gc/weak-map` ships first restricted to extern keys, which is what protocol
extension actually needs; general ephemerons are a separate, separately costed
project.

**Third, and this is the objection that matters: an observable weak reference
breaks determinism.** `0005` again — a `weak-ref` whose `deref` may answer nil
makes a program's answer depend on when the collector ran, which is a function
of allocation history, heap size and host timing. It is exactly the property
`0005` refuses, arriving through a different door — and note that pinning has
*already* spent some of that budget, above, which makes spending more of it a
decision rather than a slide.

So: **weakness may be used where the observation is not part of the answer** — a
cache, a protocol table, an interning map, where a miss is recomputed and
nothing downstream can tell. Weakness may not be *observed*. `flint.gc/weak-map`
is defensible with get-that-may-miss-and-recompute semantics and a `count` that
is deliberately not exposed; `flint.gc/weak-ref` with an observable nil is not,
unless flint decides to spend more of `0005`'s determinism and says so in the
README. **This draft does not spend it, and flags the decision as the user's.**

---

# Part two — how much of the core should be a protocol

## What a protocol call costs here, specifically

`find-protocol-method` (`lib/clojure/core.cljc:1754-1763`) is:

```clojure
  (or (get (meta x) mkey)
      (get (get (deref impls) (flint.rt/kind x)) mkey)))
```

On a miss in metadata — the common case — that is: a `meta` builtin call, a map
`get`, an atom `deref`, a `kind` call, a map `get`, a map `get`, and then an
invoke of whatever it found. Against a direct builtin, which is one
`call_indirect` through the table `0003` describes.

And `kind` is itself a **linear chain of about twenty-five type tests**, with no
`case`, for a reason recorded in place (`kin/tablekind.kin`: *"hole 3 is still
open, and the chain costs nothing here"* — true when only protocol dispatch
called it).

So a protocol call is somewhere between one and two orders of magnitude more
expensive than the closed switch it would replace. **That is an estimate from
reading, not a measurement, and the first thing any of this needs is a benchmark
of `find-protocol-method` against a builtin call.**

There is a second cost that is easier to miss. **`extend` is a run-time side
effect** — `(swap! (:impls protocol) update kind merge mmap)`. The tree shaker
works on the reference graph from `:fn` (`README.md:426`); a top-level `extend`
in a linked namespace is a root, and everything it registers is retained. So
extending a protocol to eight kinds in `clojure.core` would drag eight closures
— and whatever they call — into every module. **The shaker cannot see that a
program never holds a table.** The existing arrangement is already the right one
and the rule should be written down: **a protocol is extended by the namespace
that owns the type, never by core on its behalf.** `flint.table` extends
`Printable` for `#flint/table`; `clojure.core` does not.

## Why ClojureDart pays less

ClojureDart has types. A protocol method compiles to Dart dispatch — an
interface call or an extension resolved statically — and Dart's AOT compiler can
devirtualise a monomorphic site. flint has no types (`0005` §6: *"no deftype, no
defrecord, no types at all"*), so the only keys available are a **kind keyword**
and a **metadata map**, and both are hash lookups. And flint is an interpreter,
so there is no JIT to notice that a call site only ever sees one kind.

Two penalties, compounding. flint cannot buy extensibility at ClojureDart's
price and should not pretend to. It can buy it where the dispatch is off the hot
path, and the whole question is which operations those are.

## The principle

It is already written in the codebase, above `pr-str*`'s last arm
(`lib/clojure/core.cljc:1447-1456`):

> The branches above are core's own types, and core knowing its own internals is
> not a coupling. Anything a LIBRARY adds is a different matter: a
> `(flint.rt/table? x)` here would mean the printer — which every program links
> — had to know about a type most programs never use, which is what a protocol
> exists to stop.

Generalised:

> **A protocol is the last arm of a closed switch. It is never the switch.**

Every built-in kind keeps its answer in the closed switch, so a program with no
extensions pays one predictable branch on a path that was about to throw anyway.
Four corollaries decide the tier:

1. **A protocol may go wherever the closed switch's current last arm is a throw
   or a default**, because that arm is cold by definition. `deref` on something
   that is not an atom, a volatile or a delay currently throws
   `ClassCastException` (`kin/atoms.kin`). That throw is free real estate.
2. **A protocol may not go where the dispatch machinery re-enters it.**
   `find-protocol-method` calls `meta` and `get`. Making `get` or `meta` a
   protocol is a cycle, not a cost.
3. **A protocol may not carry an invariant another operation's correctness
   depends on.** `=` and `hash` must agree or a map silently loses keys. A user
   can only break that quietly, and `valeq.kin`'s header already says what the
   damage is.
4. **Prefer a protocol whose dispatch is amortised over work.** One dispatch per
   `reduce` over ten thousand elements is a rounding error. One dispatch per
   `first` is the loop.

## The tier

### Tier 1 — should be protocols

| | why it is cheap here |
|---|---|
| **`Deref`** | `deref` is a closed switch over `TY_ATOM`/`TY_VOLATILE`/`TY_DELAY` ending in a throw (`kin/atoms.kin`). The protocol replaces the throw. Zero cost for the three real cases; an extern or a library box gets `@x`. This is the one the user asked for and the cleanest in the list. |
| **`Printable`** | Already built and already this shape (`lib/flint/protocols.cljc`). It is the proof the pattern works, including the two-method split and the `printer-for` door that answers nil rather than throwing. |
| **`Closeable`** | `with-open` knows about ports. Externs and drivers both want it. Cold by construction — once per resource. |
| **`Reduce` / `Reduce-init`** | The best ratio in the survey: one dispatch amortised over the whole collection, and it is what lets a CHAMP or a table reduce internally instead of through a seq. A performance win as well as an extensibility one. |
| **`Datafiable` / `Navigable`** | Already protocols, already cold, already Clojure's. Nothing to decide. |

### Tier 2 — a fallback arm only, and not a dispatch

Add the protocol as the **last arm** of the existing closed switch, so nothing
built-in ever reaches it: **`Seqable`** (`seq`), **`Counted`** (`count`),
**`Indexed`** (`nth`), **`Named`** (`name`, `namespace`), **`Comparable`**
(`compare`), **`Collection`** (`conj`, `empty`), **`Lookup`** (`get`'s default
arm), **`Fn`** (the arm before "not a function").

Two warnings that belong with them.

`seq`, `first` and `next` are called **per element** — from `=`, from `hash`,
from every seq function. The fallback arm is free for everything built in, and a
*user-defined* seq source pays a protocol dispatch per element, which will be
slow enough to be surprising. Say so in the docstring rather than letting
somebody find it.

`get` as a fallback arm is safe **only** because `find-protocol-method` looks up
in a real map and never reaches the fallback. That is a load-bearing accident
and deserves a comment at both ends, or it will be refactored away by somebody
who cannot see the other half.

### Tier 3 — stays closed, and the reasons are not "performance"

* **`=` and `hash`.** Three reasons, any one sufficient. The invariant (`=`
  implies equal hashes) is one a user can break silently, and the symptom is a
  map that has lost a key. The re-entrancy: a protocol `=` is consulted through
  a metadata map, and `=` is called *from inside* map lookups. And the
  measurement: `mapeq`'s structural short-circuit is 840us → 1.15us, sound only
  because both sides are the runtime's own. **The strongest "no" in the
  document** — and note that extern refs are what tempts it, since a host
  `equals` is exactly what a user will ask for, and it would have to park.
* **`meta` and `with-meta`.** Metadata *is* the dispatch mechanism (`0005` §6).
  A protocol here is a cycle.
* **Arithmetic.** `+` on two fixnums must stay an add. There is no cold arm to
  hang anything on, because the failure case is a type error, and a protocol
  there would put a branch in the hottest instruction in the interpreter to
  serve a case nobody has asked for.

## What tier 1 needs first

Three cheap changes, all of which help every protocol and none speculative:

1. **`kind-of` becomes a table lookup on the type tag** rather than a
   twenty-five-arm chain: a 256-entry array of interned keywords. The comment
   that justified the chain — *"the chain costs nothing here"* — stops being true
   the moment `deref` calls it.
2. **`:impls` becomes an array indexed by kind ordinal** rather than a map keyed
   by keyword. One indexed read instead of a hash lookup, and the miss case —
   the common one — becomes a null test.
3. **An inline cache per call site**, keyed by kind, in the bytecode's constant
   pool. This is where the order of magnitude is, and it is the one that needs
   measuring before it is written.

And one rule to write down with them: **core does not extend a protocol on
behalf of a namespace the program did not require.** Otherwise the protocol
drags in exactly what the closed switch would have shaken out, and the module
gets bigger for a feature nobody used — which is what `0002`, `0003` and `0024`
exist to prevent.

## Open

* **The measurement.** Everything above is reasoning about code that has been
  read, not run. `find-protocol-method` against a builtin call, and `kind-of`
  chained against `kind-of` tabled, are two benchmarks and half a day, and would
  settle the tier better than another page of argument.
* **Whether externs may carry metadata**, which is `flint.interop`'s route to
  per-instance protocol extension and interacts with open question 9 above.
* **Whether `Deref` on an extern should mean anything at all** — a `Future`, a
  `CompletableFuture` and an `AtomicReference` all want `@`, all three want it to
  park rather than block, and on a pinned extern it would park twice for
  different reasons.
