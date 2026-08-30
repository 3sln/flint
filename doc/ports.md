# The two JVM/CLR runtimes, and why both are still here

There are two implementations of flint on each host, and this is not an
oversight. It is a state to get out of deliberately rather than by deleting the
older one and finding out what it was for.

## `com.flint.rt` / `Flint.Rt` — the ported runtime

A verbatim mirror of the Rust: the same NaN-boxed 64-bit values, the same
generational collector over the same flat byte-addressed heap, the same object
layouts, the same CHAMP, the same Pike VM, the same green threads and the same
scheduler. The heap is a `MemorySegment` on the JVM and `NativeMemory` on the
CLR, which is the ONLY file on either side that differs from the Rust in more
than syntax.

It runs all ten conformance programs and agrees with the native runtime
character for character, snapshots in both formats, and **self-hosts**: the
flint compiler runs on it and emits byte for byte what the wasm compiler emits.

## `com.flint` / `Flint` — the older boxed port

Every value is a host object, so an integer is boxed. Measured at 24x slower on
the JVM and 7x on the CLR for the interpreter's inner loop, which is why the
ported runtime exists.

It has **no green threads and no ports at all**, and its own source says why:
"a continuation living on the host stack cannot be parked, saved, or resumed".

Two things are still only here:

* **AOT** (`Aot.java`, `Aot.cs`) -- compiling arities to host bytecode at load
  time, behind `:optimize [perf]`.
* **Host-thread safety** -- `ThreadTest` runs several REAL host threads through
  one VM. The ported runtime's threads are green, so this asks a different
  question of it, and the answer is not the same answer.

## What the cutover needs

Deleting the boxed port today would drop those two. That is a trade, not a
tidy-up, and it is worth saying out loud rather than discovering later:

* AOT would have to be ported, or dropped on these hosts and the `:optimize`
  flag made a wasm-only concern;
* the host-thread question would have to be re-asked of the green-thread
  runtime -- probably "several host threads each driving their own sandbox",
  which is a different and better-defined thing.

Until then both build, both are tested, and this file says which is which.
