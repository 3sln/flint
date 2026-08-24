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
