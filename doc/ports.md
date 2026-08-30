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

It also carries the two things this file used to say it did not:

* **Parallel executors** (`doc/decisions/0028`) -- K REAL host threads driving
  one heap, with the interpreter's checkpoint as the only safepoint. That is the
  host-thread question, re-asked and answered.
* **Host ports** (`doc/decisions/0006`, `0027`) -- `open`, `hostContinue`,
  `hostDeliver`, `hostClosePort`, `drainEvents`, `reapPorts`, the weak port
  registry and the generation-tagged waiter tokens. Three drivers run one image
  through one script and `bin/conform-hosts` compares the transcripts byte for
  byte.

## `com.flint` / `Flint` — the older boxed port

Every value is a host object, so an integer is boxed. Measured at 24x slower on
the JVM and 7x on the CLR for the interpreter's inner loop, which is why the
ported runtime exists.

It has **no green threads and no ports at all**, and its own source says why:
"a continuation living on the host stack cannot be parked, saved, or resumed".

One thing is still only here:

* **AOT** (`Aot.java`, `Aot.cs`) -- compiling arities to host bytecode at load
  time, behind `:optimize [perf]`.

`ThreadTest` used to be the other. It ran several REAL host threads through one
VM, and this file used to say the green-thread runtime "asks a different
question". It does not: `doc/decisions/0028` is exactly that question, and
`RtParallel` on both hosts is exactly that answer -- K host threads on ONE
heap, with a collection staged by one walking the other's roots.

## What the cutover needs

Deleting the boxed port today would drop those two. That is a trade, not a
tidy-up, and it is worth saying out loud rather than discovering later:

* AOT would have to be ported, or dropped on these hosts and the `:optimize`
  flag made a wasm-only concern.

That is now the whole list. Until it is settled both build, both are tested, and
this file says which is which.
