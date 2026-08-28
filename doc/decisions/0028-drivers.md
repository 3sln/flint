# 0028 — A driver: ports are the only way to drive a sandbox

> **PARTLY BUILT.** The SDK shape is done in Rust: `Driver`, `Inline`,
> `ThreadPool`, an asynchronous `call`, coalesced dispatch (200 requests into
> 1, measured). Underneath, the heap has left `Rt`, and TWO EXECUTORS NOW
> SHARE ONE HEAP THROUGH COLLECTIONS — 50 collections, roots fixed up across
> both threads, verified by value. Not yet safe, and recorded as an ignored
> test rather than a comment: the intern tables, the remembered set and
> `globals`. Until those are protected the driver still serialises, so K > 1
> is correct and not yet faster.

`Sandbox::call` runs the program ON THE CALLING THREAD. That works, it is what
the three SDKs do today, and it forecloses thread pools: a sandbox that only
advances when someone calls into it cannot be advanced by a pool, because a
pool's whole job is to decide *when* and *on which thread* work runs.

So the host stops driving directly. **Ports become the only way to drive a
sandbox**, and between the ports and the sandbox sits a **driver**.

## Where a thread may stop, and why it matters more than it reads

A collection MOVES objects, so a thread that stops while holding a `Value` in a
Rust local resumes holding a stale pointer. Under one executor that rule only
applied around your OWN allocations; under several it applies around everyone's.

So the only safepoint is the interpreter's checkpoint — between two bytecode
instructions, `ip` written back, every live value on the value stack by
construction. A thread inside a native does not poll and cannot stop, and the
collector waits for it. That is a latency cost, not a correctness one.

**And a collector must wait only for threads that can actually stop.** The
first version of this deadlocked, and the reason is worth keeping: it waited
for every REGISTERED executor, including one that had already finished its
work. An executor that is not running guest code polls nothing, so waiting for
it is waiting forever — while its roots still have to be scanned, because they
are still roots. Registered and running are two different questions, which is
the same split a JVM makes between a thread "in Java" and one "in native".

## What is built, and what the lock is still doing

`sdks/rust` has the whole shape. `ThreadPool::new(4)` gives four OS threads
that genuinely contend for one sandbox — 100 calls fired from four threads
through one `(swap! seen inc)` come back as exactly 1..=100, so the state is
one state and the threads really are sharing it.

**What they are not yet doing is running at the same time.** The program sits
behind a lock, so the parallelism is in the dispatch and not in the
interpreter. That is the honest state, and it is a useful one: the interface
is exercised with K > 1 from the first version, which is the only way to find
out whether it is the right interface. Removing the lock changes no signature
here — it changes `runtime/src`.

## What a driver owns

* **How many threads a sandbox gets, and which ones.** Not "at most one": the
  end state is a pool over a SINGLE sandbox, several threads advancing one
  heap. Today that number is 1 and is enforced; the interface has to express
  it as a number rather than assume it away.
* **When to advance.** An arrival makes a sandbox runnable; the driver decides
  when runnable becomes running. That is where debouncing lives.
* **Where the instance lives**, and therefore what memory it gets.

A `SingleThreadDriver` runs everything inline and is the default. A
`ThreadPoolDriver` offers K threads — across sandboxes, and eventually WITHIN
one. Nothing else in the system changes between them, which is the test of
whether the seam is in the right place.

## Why "driver"

`Scheduler` is taken: flint has one inside every sandbox for green threads
(`0007`), and two schedulers at two levels sharing a name would be a
permanent source of confusion. `Executor` implies tasks submitted by a caller,
and here nothing is submitted — arrivals drive. `Reactor` is accurate and is
the established word for this shape, but it is jargon for the thing that
"drives the sandbox", which is already how everyone describes it out loud.

So: `Driver`. It is the verb people already reach for.

## Debouncing is a latency choice, not a correctness one

Every host write to a port does not have to wake the sandbox. The driver may
coalesce arrivals — by a small time window, by a batch size, or simply by
"already runnable, do nothing".

**This is safe only because the port buffer is the queue.** A delayed wake-up
loses nothing: the messages are in the port, and the sandbox sees all of them
when it does run. If the queue lived in the sandbox's heap, debouncing would be
dropping mail.

Two rules keep it from becoming a bug:

* **A runnable sandbox must eventually run.** Debouncing is bounded — a
  maximum delay, not a condition that can fail to fire. An unbounded coalesce
  is a deadlock with a plausible explanation.
* **The driver never runs guest code on the sender's thread.** `0027` already
  says push wakes rather than executes; the driver is what makes that
  mechanical, since waking now means "hand to the driver" rather than "call
  into the sandbox". This stays true when a sandbox has several threads: a
  sender's thread is not one of them unless the driver made it one.

## `call` has to become asynchronous, and now rather than later

If ports are the only way in, then `call` is: encode a request onto the system
port, let the driver schedule the sandbox, take the reply. That cannot return a
value on the caller's thread, because the sandbox may not run on that thread at
all.

So `call` returns a promise, a future, or a completion — one per language.
A `SingleThreadDriver` may complete it before returning, so the simple case
stays as simple as it reads, but **the type is asynchronous from the start**.

A synchronous API cannot be made asynchronous later without breaking every
caller, and the cost of getting this wrong is not paid by us. Nothing is
published yet — the `0.0.1` publish failed and was left — so this costs a
rewrite of three SDK surfaces today and an ecosystem-wide break later.

A blocking convenience (`call_blocking`) is fine ON TOP, and only on drivers
that can honestly offer it. It must never be reachable from inside a sandbox:
a call made from guest code while occupying a pool thread, waiting on a reply
that needs a pool thread, is the oldest deadlock there is. From inside, a call
is a port send and a park — which it already is, once ports are the only way
in.

## Memory: the driver decides, and shared memory becomes REQUIRED

The instinct that the driver should provide the memory is right, and there is a
mechanical reason beyond tidiness. In a browser a `WebAssembly.Instance` is not
structured-cloneable, so it cannot be handed to a worker. What CAN cross is a
compiled `WebAssembly.Module` plus a memory. A pooled driver therefore has to
instantiate ON the worker, which means the driver owns instantiation, and
whatever owns instantiation owns the memory.

That makes `Sandbox` a **handle** — an id, its system port, and a reference to
its driver — rather than the instance.

**Several threads in one sandbox means one linear memory reached from several
threads, so a `SharedArrayBuffer` is not optional.** An earlier draft of this
file argued a pool needs no shared memory, on the reasoning that the pool is
over sandboxes and each can be pinned to its worker. That is true, and it
answers a different question: it makes many sandboxes concurrent, not one
sandbox parallel. Pinning is a fine intermediate state and a dead end as a
destination.

So `:memory` — which is in the COMPATIBILITY KEY (`0020`), and is `:unshared`
in every build we produce — becomes a real fork. A shared-memory runtime needs
the threads proposal, `+atomics`, `+bulk-memory` and its own build, and on the
web it needs cross-origin isolation from the embedder. None of that exists yet.

Which is the strongest argument for the driver owning memory: an embedder that
cannot set isolation headers gets the single-threaded driver and an unshared
module, and the same program runs on both. If the MODULE chose, that program
would have had to choose its deployment constraints at compile time.

## Designing for K > 1 without building it

The ask is that the harness allow several threads in one sandbox later. That is
a real constraint on what gets built now, and it is mostly one refactor plus
one discipline.

**Split `Rt` into what is shared and what is per-executor.** Today one `Rt` is
both the heap and the single thread of execution running on it: the value
stack, the frames, the root stack, the gas counter and the current green thread
all live beside the heap, the globals, the intern tables and the port registry.
Those are two different lifetimes. One is the SANDBOX; the other is an
EXECUTION CONTEXT, and K > 1 means K of the second against one of the first.

With K = 1 that split is a refactor with no behaviour change, which is exactly
when it is cheap to do. After K > 1 it is a rewrite under a deadline.

**Make the multi-threaded entry point exist immediately, and correct
immediately, by serialising.** A driver should be able to hand K threads to one
sandbox from the first version, with the sandbox taking a lock so that K > 1 is
CORRECT but not yet FASTER. Then the harness is real, testable and exercised
long before the parallel collector exists, and the later work is removing a
lock rather than changing an interface. An interface that has never been called
with K > 1 is an interface that will turn out to be wrong.

**What removing that lock will actually cost**, named now so it is not
discovered as a surprise:

* **Allocation** needs per-thread buffers carved out of the nursery by an
  atomic bump. Well understood, and the cheapest item on this list.
* **Collection** needs safepoints. Every thread has to reach a stopping place
  before a copying collector moves anything, and every thread's root stack has
  to be scanned and fixed up.
* **The root discipline gets harder in kind, not degree.** "A `Value` in a Rust
  local across an allocation is stale" becomes "stale because ANOTHER thread
  allocated". The same bug, now nondeterministic.
* **The intern tables** (`flint.strs`) and the var table become shared mutable
  state.
* **`swap!` becomes a real CAS**, and the green-thread run queue becomes a
  concurrent one.

**The safepoint rides on the gas checkpoint, and that resolves the apparent
conflict with `0009`.** The interpreter already polls a checkpoint every
instruction, and `0009` makes that poll ABSENT when nothing is counting --
`NoBudget::tick` is a constant the optimiser deletes, which is the point of it.
A sandbox with several threads cannot have a thread that never polls.

The resolution is that the dispatch policy is already chosen at RUN TIME. A
sandbox with one executor and no limit keeps `NoBudget` and stays exactly as
free as it is today; a sandbox with more than one gets a polling policy. A poll
is compiled in only when there is something to poll FOR.

And gas itself stops being a shared counter. Each executor counts into a local
`u64` -- a plain increment, no contention -- and flushes to a shared atomic
when its checkpoint fires. The limit weakens from "stops AT the limit" to
"stops as soon as it can after it", with the overrun bounded by batch size
times executors. That is the right trade: the point of a limit is that a
runaway is stopped, and stopping a few thousand instructions late stops it just
as dead. The total also stops being deterministic at K > 1, which is a property
of parallelism rather than of this design (`doc/decisions/0009`).

## Locking the intern tables: one lock each, not sharded

`Rt::string`, `keyword` and `symbol` all probe and insert into weak tables that
every executor shares. Unsynchronised that is a data race, and it is one that
MANIFESTS: with both executors interning the same 3 000 texts, 10 to 17 of them
end up as two distinct interned objects, on 40 runs out of 40.

Two copies is not a wasted allocation, it is a correctness bug. `eq` reads
"both interned and not bit-equal" as NOT EQUAL, so the two compare unequal
while printing identically -- and symbol equality is slot equality on exactly
these strings, so it spreads to symbols and to anything keyed by one. That is
also why the test asserts BIT-equality: text-equality is what still passes when
this is broken.

**One lock per table, and not sharded**, which is measured rather than assumed.
On the most string-heavy workload we have -- flint compiling construe -- the
tables are probed 18 247 times across 4.25 seconds. 26% of `Rt::string` calls
reach a table, but a probe is tens of nanoseconds and the duty cycle is about
0.04%. Sharding would optimise something that is not happening.

**The lock is only ever held across a probe, never across an allocation.** The
value is built outside it, then a re-probe under the lock either publishes it
or takes the copy another executor published first. That ordering is what
gives the collector a free hand: a thread only parks while WAITING for an
intern lock, never while holding one, so a parked thread never holds one and
the collector can walk and rewrite the tables during a stop without taking
anything.

It also means the protocol exists once rather than three times. `string`,
`keyword` and `symbol` have historically had the same four lines and the same
rooting bug; three copies of a lock protocol would be three chances to diverge.

## The write barrier: per-executor, not locked

An old object holding a young pointer is only found by a minor collection
because the barrier recorded it. That list is the hottest shared thing in the
runtime -- far hotter than allocation -- so locking it would cost more than it
protects, and a shared `Vec` pushed to from two threads reallocates under the
other's push.

So the list is **per-executor**, and a collection drains every one of them.
Draining only the collecting executor's fails 12 runs out of 12: the young
objects another thread had just stored into old ones are freed while still
referenced, and it reads back as a slot that lost its value.

Two things fell out of building it:

**`set_slot` takes `&Gc`, not `&mut Gc`.** Two executors holding `&mut` to one
shared heap is aliasing UB whether or not the writes ever touch, and the
barrier is exactly where two of them would. The only thing it mutates is a flag
bit in the object's own header and a list belonging to the calling executor,
so `&self` is enough. Two executors setting that flag on one object write the
same bit; the worst case is the object appearing in two lists, which is a
second scan rather than a wrong answer.

**The collector has to hand the rebuilt set back.** `scan_object` re-enrols
every old object that still points young, and leaving those in the collector's
own list drops them from the remembered set entirely -- so the NEXT collection
frees young objects an old one is still holding. That surfaced one collection
later and nowhere near the cause, which is the shape this whole area fails in.

It costs 5 040 bytes of module and is NOT behind the `parallel` feature, which
is the opposite call to the root scan. The barrier is not machinery for a
feature a wasm module cannot use -- every module runs it. What is
parallel-specific is only which list it appends to, and a compile-time fork
there means two copies of a twenty-five line function. `flint.strs` records
what two copies of a subtle function cost.

## Var slots are atomic, and only where they need to be

`GET_VAR` reads a var slot and `SET_VAR` writes one, so several executors read
while one may write. The slots are `AtomicU64` under `parallel` and a `Cell`
without it -- 7 277 bytes of difference, measured -- both
`#[repr(transparent)]` over eight bytes, which is not cosmetic: compiled code
reads the array through a raw base pointer (`flint.aot`), so the representation
is part of an ABI.

Relaxed ordering throughout. A var slot carries no happens-before for anything
else: the heap object it names is published by the allocation lock and the
safepoint, so this only has to be a read that sees a whole value rather than
half of one.

## Where a native may not stop

A thread that has said `enter_running` is one the collector WAITS for. A native
that walks heap data for a long time without allocating never reaches a
safepoint, and the collector waits forever -- which showed up exactly as
`waiting for executors to park: stop=1 active=2 parked=0`.

So a long non-allocating native loop over heap data has to poll. And the spin
loops report rather than hang: a deadlock that prints what everyone was doing
is diagnosable, and a hang is indistinguishable from slow.

## Parallelism is a property of the TARGET, not of the SDK

The SDKs mirror each other (`0025`), and that has to survive a world where
some targets can run several threads in a sandbox and some cannot. So the
vocabulary is identical everywhere and the ANSWER differs:

* **Native (LLVM)** — real threads on a real shared heap, no wasm constraints
  at all. This is where K > 1 gets built first.
* **JVM and CLR** — the same, once those ports exist. Neither is started.
* **wasm, in a browser or anywhere else** — K = 1 for now. It needs the threads
  proposal, `+atomics`, a shared-memory build and cross-origin isolation from
  the embedder. The door stays open; nothing walks through it yet.

**Doing native first is not a detour, because the runtime is one codebase.**
`runtime/src` compiles to wasm32 and to the host from the same source — the
same collector, the same interpreter, the same builtins (`0010`). Per-thread
allocation buffers, safepoints, per-executor root stacks and a concurrent run
queue are written once and are then present in the wasm module too; what wasm
additionally needs is a build with atomics and a memory it is allowed to
share, not a second implementation.

What that does require is that the concurrency primitives — spawn, park,
atomics — sit behind a small platform layer, so that on a target without
threads it degenerates to K = 1 rather than failing to compile.

### Asking for threads is a preference, and the answer is readable

Every SDK exposes the same drivers, including on targets that cannot honour
them. A `ThreadPool(4)` on single-threaded wasm gives back a driver whose
parallelism reads **1**.

Not a refusal, because portable code then cannot be written; and not a silent
substitution either, because a driver that quietly gives one thread when asked
for four is a performance mystery with no evidence in it. Ask for what you
want, get this build's best effort, and be able to READ what you actually got.

That is deliberately the same rule as `:optimize` (`0021`): an ordered
preference, unrecognised or unavailable tokens ignored rather than refused, and
the outcome observable from outside. One rule twice beats two rules.

## What this costs elsewhere

`gas()` and the diagnostics currently read straight out of the instance. Once
the instance may be on another thread, those become either a snapshot delivered
with a reply, or a driver-mediated request. Reading them across a thread
boundary without going through the driver would be reading a number while
something else is writing it.

Under K > 1 gas stops being one counter at all: it is per-executor and summed,
because a single shared counter would put an atomic increment on the hottest
line in the interpreter. That makes the total exact only at a safepoint, which
is fine for a limit — the limit trips at a checkpoint anyway — and is worth
saying out loud before someone reads a mid-flight sum and calls it a bug.
