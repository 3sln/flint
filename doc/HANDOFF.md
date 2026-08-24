# Handoff — the parked-thread collector bug

> **Update after the run of 2026-08-24**, which ended on a transient
> `403 Unable to verify organization membership` — an auth failure, not a
> diagnosis dead end. ~187 lines of instrumentation and attempted fix are
> uncommitted in `vm.rs`, `conc.rs`, `gc.rs`, `abi.rs`, `rpc.cljc` and
> `link.cljc`. The tree builds.
>
> **The finding that moves this: DISABLING THE TIME SLICE FIXES IT.** So the
> fault is on the PREEMPTION path, not the parking path — a different suspension
> mechanism, and the one with no test behind it.
>
> Two facts already established, both worth not re-deriving:
>
> * the slice-expiry branch (`vm.rs`, `if base_depth == 0 { park_on =
>   PARK_YIELD; return NIL; }`) **returns without committing `ip`**, and
>   `commit!` is not even in scope there;
> * **committing `ip` alone did not fix it**, so something else is stale across
>   a preemption too. The obvious candidates are anything else the dispatch loop
>   caches in a Rust local — the frame pointer, the cached stack top — and the
>   consistency between the resumed `ip` and the value-stack depth left behind
>   by a half-evaluated expression.
>
> The parking path rewinds deliberately (`frames.last_mut().ip = opcode_at`) so
> the call re-executes. Preemption cannot do that — the thread must resume where
> it stopped, with the stack exactly as it was. That asymmetry is where to look.
>
> My `call_value` observation (no PARK handling on the dynamic-dispatch path) is
> still worth closing, but it is probably not this bug. The shared `Parked` enum
> that now exists is the right shape for both.

`0009` is done and committed (`ebd9a0e`, `884ac66`). The suite is green except
**two assertions in `test/document.clj`**, which are the bug below.

## It is pre-existing, and that is measured rather than assumed

Verified by stashing *all* uncommitted work, rebuilding the runtime from
`a56cba2` with only a two-line `set_gc_stress` export added, and reproducing.
It is a `0005`/`0006` green-threads bug, not a regression from limits.

## The reproduction, which is now small and deterministic

```clojure
(ns k (:require [flint.thread :as t] [flint.port :as p]))
(defn main [args]
  (let [n  (flint.rt/str->num (first args))
        [tx rx] (p/channel 1 "probe")
        w  (t/spawn (fn [] (inc n) (p/send tx :end) :sent))
        got (p/receive rx)]
    (t/join w)
    (pr-str {:got got})))
```

Run it with `inst.exports.set_gc_stress(1)`. Without stress it answers
`{:got :end}`; with stress it deadlocks. **No host port is involved**, so it is
not the host ABI.

The trigger is narrow and worth knowing, because it is what makes it look like a
compiler bug when it is not:

* capturing a **fixnum** upvalue and doing arithmetic on it (`(inc n)`, `(< 0 n)`)
  fails; capturing a string or a port, or merely mentioning the fixnum, does not;
* that is a red herring. The worker's body changes the module's constants and
  code, which changes how many allocations run before the channel is made, which
  changes *when* a collection lands. The bug is allocation-timing-dependent, not
  shape-dependent.

## What is actually wrong

A **root in the VM value stack is left dangling across a collection**. A root
verifier that walks `stack`/`shadow`/`globals`/`consts`/`singletons` after every
collection and rejects any heap value that is forwarded, outside both spaces, or
of an impossible type, reports the first bad root as `stack[1]`.

Downstream, that surfaces as:

* `SC_NEXTID` reads `0x1000000`, so the first port is given id **16777216**
  instead of 1 and the ids run on from there;
* a `wake_on(port)` then fails to match the parked thread's waiter, the wakeup is
  lost, and the program deadlocks with one thread parked.

`16777216` is `1 << 24`, and object headers are `(ty << 24) | …` — so the value
read out of that slot has the shape of a **header**, i.e. something is reading or
writing through a pointer that no longer owns that memory. There were **no
out-of-bounds slot writes** and no overlapping allocations, so it is a stale
pointer rather than a size or layout error.

## Where the search had got to

* `sched()`, `spawn_thread`, `new_waiter`, `free_waiter`, `park_on_port`,
  `port_send`, `port_enqueue`, `save_current_state`, `restore_state`, the weak
  intern tables and `intern_into` were all read and are all correctly rooted.
  `empty_vec`/`empty_map` are singletons and do not allocate.
* `roots.consts` **is** traced, so the image loader's constants are safe.
* The remaining strong suspect is the shape the VM uses around native calls:

  ```rust
  let base = self.roots.stack_top - argc;
  let r = self.call_native(idx, base, argc);
  self.roots.stack_top = base;          // args leave the root set here
  …
  self.roots.stack_top = base + argc;   // and come back on the park path
  ```

  plus any native that copies an argument into a Rust local and then allocates.
  `arg(rt, a, 0)` followed by an allocating call is the pattern to grep for.

## Tools

`set_gc_stress(on)` is committed (40 bytes on a pure module). Two throwaway
tools were **not** committed, because they cost ~2 KB on every shipped module
and `0005` says a pure module must not grow. They are cheap to rebuild and were
what actually cracked it:

1. **A stress window.** `stress_from`/`stress_until` on `Gc`, collecting only for
   allocations in `[from, until)`. Bisecting the window narrows the failure to a
   single allocation. Do not assume monotonicity when bisecting — the predicate
   is not monotone, so verify any window you land on by re-running it.
2. **The root verifier** described above, run after each collection, recording
   which root array and index first dangles.

## Test that is still owed

`units-src/flint-conc/tests/threads.rs` now covers a **waiter** park across
collection (`a_waiter_park_survives_collection_at_every_allocation`), which the
suite lacked — the old stress test parks with `yield`, which registers no waiter.
It passes, so it does *not* yet reproduce this. The untested combination is
where these live, exactly as the compound-keys bug taught: stress mode was
already running on the map tests and still missed that one, because scalar keys
never allocate in `=`. When the bug is found, the regression test needs the
negative control `without_the_barrier_the_reference_would_be_lost` set — it must
fail if the fix is removed.
