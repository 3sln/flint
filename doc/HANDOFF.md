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
out of release, so a desynchronised old-space walk would be silent. **This was
tried**: the check was made real and the repro run, and it reports **zero parse
errors**. So the old-space walk is consistent and this suspect is out too.

### The causal chain, established

This is the sequence, and every step is measured rather than inferred:

1. A **stale root points at nursery address 131 096** (`0x20018`) — a pointer to
   an object that lived there in an *earlier* nursery cycle.
2. After a flip, the **scheduler is allocated at 131 072**, spanning
   `[131 072, 131 152)`. The bump advance is correct (80 bytes, len 9) — the
   allocation is not the bug. But that span now **covers 131 096**.
3. The next minor collection calls `forward()` on the stale root. `131 096` is
   in from-space, so `forward` treats it as an **object start**, copies from it,
   and writes a `TY_FWD` header there.
4. `131 096` is `scheduler + 24`, which is **slot 2 = `SC_NEXTID`**. It becomes
   `0x00220000_01000000` — low word `0x01000000` is `TY_FWD << 24`, high word
   `0x00220000` is the forwarding address, which is exactly the base of the
   other semispace. Measured directly at the moment `sched()` returns:

       no stress: SC_NEXTID = 0xfffa0000_00000001   (fixnum 1, correct)
       stress:    SC_NEXTID = 0x00220000_01000000   (a forwarding header)

5. Port and thread ids are then handed out from `0x1000000`, `wake_on` cannot
   match the waiter, and the program deadlocks.

So the root cause is **a stale value in the root set**, and everything else —
the corrupted ids, the lost wakeup, the lost document wave — is downstream of it.

**Where the stale root comes from is the remaining question.** The window
bisection puts it early: a single collection at allocation #81, during
`self.string("flint/str->num")` in the image loader's constant pool, is enough
to cause it. Arming the root verifier from `run_one` finds **nothing**, which is
consistent — by the time threads run the damage is already done. `make_native`
and `make_closure` were read and are correctly rooted.

**Worth doing regardless of the cause:** `forward()` has no defence against a
stale or interior pointer. It takes any heap Value in from-space as an object
start. A cheap check that the address is a plausible object start would turn
this class of silent corruption into a detectable event, and would have made
this a one-run diagnosis instead of a multi-session one.

Ruled out this session, with evidence, so none of it is re-chased: no frame ever
sits above the traced `stack_top` (sampled in `Rt::alloc`, at the collection
itself); no to-space overflow (the `debug_assert` was made real); no old-space
parse error; no root pointing at an unmarked old object before the sweep; and
`is_young`/`in_from` cannot claim an old-space address, since `half` and
`young_base` are fixed at construction.

### What write-attribution added

Making the stack name its own writer (a shadow array recording, per slot, the
opcode and ip that last changed it, plus a ring of the last instructions) moved
this on. It is committed and reverted at `732a375`/`857b257`.

The corruption is first *seen* at a `NATIVE` call, `ip = 1446`, `stack_top = 16`,
at minor collection 80, with this leading up to it:

    LOCAL  ip=1442 top=14
    LOCAL  ip=1444 top=15
    NATIVE ip=1446 top=16      <- collection here, stack[1] already bad

`stack[1]` belongs to an outer frame, not the one being called.

**The address is probably not a swept old block.** The pre-sweep check found no
root pointing at an unmarked old object, and the bad address (`0x20018`) sits
below where old chunks were allocated. `ty = 0` is therefore most likely
`read_u32` on memory that is not an object at all — a **stale nursery address
from before a semispace flip** rather than a freed old-space block. If so the
question is not "who freed it" but "why was it not forwarded", and the answer to
that is that it was outside `[0, stack_top)` when some minor collection ran.

`MIN_TOP` instrumentation says the shallowest `stack_top` at any minor collection
is **0**. That is normal between threads, so it is not proof on its own — but a
collection with `stack_top = 0` traces nothing on the value stack, and every
heap value in a frame that is still live at that moment goes stale. Finding the
window where that is true *while a frame is genuinely live* is the next step.
Instrument `frames.len()` at the collection itself rather than at the last
dispatched instruction, which is what made the current reading ambiguous.

### Attribution pitfalls, so the tooling is not trusted blindly

* `LAST_OP` is global. It is not reset between green threads or across
  `restore_state`, so a restored stack is attributed to whatever the previous
  thread last ran. It named `RETURN` when the writer was `restore_state`. Mark
  the non-VM writers.
* The first bad value seen is during module top-level initialisation and is
  noise. Arm the detector from `run_one`.

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
