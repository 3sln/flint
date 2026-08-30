# The JVM and CLR runtimes

There is now ONE implementation of flint on each host. There used to be two, and
this file recorded why; the second is gone and this records what that cost.

## `com.flint.rt` / `Flint.Rt` — the ported runtime

A verbatim mirror of the Rust: the same NaN-boxed 64-bit values, the same
generational collector over the same flat byte-addressed heap, the same object
layouts, the same CHAMP, the same Pike VM, the same green threads and the same
scheduler. The heap is a `MemorySegment` on the JVM and `NativeMemory` on the
CLR, which is the ONLY file on either side that differs from the Rust in more
than syntax.

It runs every conformance program and agrees with the native runtime character
for character, snapshots in both formats, and **self-hosts**: the flint compiler
runs on it and emits byte for byte what the wasm compiler emits.

It also carries the two things this file used to say only the boxed port had:

* **Parallel executors** (`doc/decisions/0028`) — K REAL host threads driving one
  heap, with the interpreter's checkpoint as the only safepoint. That is the
  host-thread question, asked properly and answered.
* **Host ports** (`doc/decisions/0006`, `0027`) — `open`, `hostContinue`,
  `hostDeliver`, `hostClosePort`, `drainEvents`, `reapPorts`, the weak port
  registry and the generation-tagged waiter tokens. Three drivers run one image
  through one script and `bin/conform-hosts` compares the transcripts byte for
  byte.

## What the cutover cost: AOT

The boxed port compiled arities to host bytecode at load time, behind
`:optimize [perf]`. That is gone on these two hosts, and it is not coming back
as a port of the deleted code.

The reason is the representation. `Aot.java` and `Aot.cs` emitted bytecode over
a value model where **every value was already a host object** — an integer was
an `Integer`, and a call was a virtual dispatch the JIT already understood. The
ported runtime's values are NaN-boxed longs in a flat, manually managed heap.
Emitting JVM bytecode or IL against that is a NEW BACKEND against a different
machine, not a translation of the old one. Pretending otherwise would have meant
carrying 1 048 lines of code that had to be rewritten before it could run.

So `:optimize [perf]` is a wasm and native concern. On the JVM and CLR the flag
is still CARRIED and still READ — `RtFlags` and `--rt-flags` print
`flags=1 aot=false` — because a decision the compiler writes and no runtime
reads is one that can silently stop being written. What the gate asserts is that
it arrives, not that something acts on it.

## What the cutover removed

7 353 lines of a second runtime, and with it:

* `ThreadTest` / `--threads`, superseded by `RtParallel` / `--rt-parallel`,
  which asks a harder version of the same question: K host threads on ONE heap,
  with a collection staged by one walking the other's roots.
* `Conform` and the CLR's bare-argument form, superseded by `RtImage` /
  `--rt-image`, which also compares against the NATIVE answer rather than only
  printing its own.
* `SelfHost` / `--selfhost`, superseded by `RtSelfHost` / `--rt-selfhost`.
* `AotTest` / `--aot`, which had nothing left to test.
* `LoadTest`, `RunTest`, `HashTest`, which nothing had run for some time.
