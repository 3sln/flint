# Handoff — the parked-thread collector bug

Two of `test/document.clj`'s assertions are red. Everything else in `bin/test`
is green. The bug is **pre-existing** — verified by stashing all uncommitted
work, rebuilding from `a56cba2`, and reproducing.

## Fixed on the way, and not the same bug

`f74ad1b` fixed a genuine, reachable, wrong-answer bug that the search turned
up: there are two paths into `call_native`, and only the CALL_NATIVE opcode
handled parking. A parking native reached through a *value* had its arguments
dropped out of the root set and its park handed to `unwind` as a thrown error.

    (let [recv flint.rt/port-receive] (recv rx))   =>  #<unprintable>

It is fixed, tested, and it is **not** the bug below: a counter on that path
shows zero hits in either failing case.

## What the failure looks like

`test/document.clj`: the host sends 64 waves, the guest sees 63, and
4194304 − 4128768 = 65536 is exactly one budget. Runtime counters show
**65 messages enqueued and 65 dequeued on both hops** — the host port and the
rpc channel — so every message crossed every port. The loss is above the ports:
one message reaches `drain-each` and is neither counted nor passed to `f`.

## Three things that look like the cause and are not

Each of these makes it go away, and each is only moving *when a collection
lands*. Do not re-chase them:

1. **The time slice.** Disabling preemption fixes it. But the failure is **not
   monotone in slice size** — 1024 and 2048 pass, 4096 loses one wave, 65536
   loses nearly everything. Non-monotone means "where a preemption lands",
   not "how often".
2. **`reap_ports`.** Disabling it fixes it at every slice. But logging shows the
   *same* ports reaped and orphaned (`reaped=[5,4] orphaned=[4]`, the structure
   call's dead channel) in both the passing and the failing run, and the worst
   failure (slice 65536) reaps nothing at all. Disabling it removes its
   allocations, nothing more.
3. **Code shape.** Adding a loop variable to `drain-each`, or a `throw` branch
   to the rpc reader, fixes it. Capturing a fixnum upvalue and doing arithmetic
   on it breaks it while capturing a string does not. All of this shifts
   allocation counts and therefore collection timing.

## What is actually established

Under `set_gc_stress(1)` a two-thread channel program with **no host port**
deadlocks. A root verifier run after every collection reports:

    dangling root: stack[1] ty=0 phase=run loop stack_top=16 at minor 80

* `ty = 0` is `TY_FREE` — the value points at a **swept old-space block**.
* `stack_top` was **16 at collection time**, so `stack[1]` was inside the traced
  range and *was* traced.
* A check inserted in `major` immediately before `sweep_old` found **no root
  pointing at an unmarked old-space object**. So marking did not miss it.

That combination is the whole puzzle: the root is traced, it is marked, and it
still ends up pointing at a `TY_FREE` block. The next suspect is therefore the
route by which memory becomes `TY_FREE` *other than* sweeping — `push_free`,
called from `split` in `take_free`. If a free-list entry is ever stale, or a
block's recorded length disagrees with the heap walk, `take_free` hands out a
live object's address and `split` stamps a `TY_FREE` header into the middle of
something live. `sweep_old`'s parse check is a `debug_assert!` and is compiled
out of release, so a desynchronised old-space walk is currently silent. **Make
that check real and run the repro** — that is the next move.

Ruled out with evidence: `sched`, `spawn_thread`, `new_waiter`, `free_waiter`,
`park_on_port`, `port_send`, `port_enqueue`, `save_current_state`,
`restore_state`, `link_peers`, `peer_id_of_dead`, the weak intern tables (the
key is compared before the predicate, so ports cannot collide), `roots.consts`
(traced), and `alloc_old` not zeroing its body (tried; no effect).

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
