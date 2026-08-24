# 0013 — Emitting wasm instead of dispatching, and what it costs

> **NOT BUILT — analysis of a fork, recorded so it is not re-argued from
> scratch.** The conclusion is *not now*, with a condition for revisiting.

## The proposal

Rather than a dispatch loop over bytecode, emit wasm per Clojure function whose
body is the inlined opcode implementations, operating on **the same thread
structs and the same linear-memory value stack**. Gas by injected counters at
chosen points rather than per instruction.

**The important part is that this does not resurrect the rooting problem.**
`0001` chose an interpreter because wasm locals are not scannable — but values
here would still live in the linear-memory stack, with wasm used only to
manipulate it. So this is a genuine middle path, not the AOT design that was
rejected, and it deserves better than the earlier answer.

## What it buys

Dispatch is measured at **6.2 ns/instruction** on a tight loop, 8–19 ns diluted
by real work. Removing it entirely would take the construe parse from 1.4×
cherry to something under 1×. Straight-line arithmetic benefits most, because the
host JIT can then keep a stack pointer in a register and fold adjacent
push/pop traffic that the interpreter must materialise.

## What kills it today: parking

A green thread parks when a port send or receive or an `open` cannot proceed.
Parking is cheap **because a thread is data** — a VM state the scheduler declines
to pick.

Emit wasm and Clojure calls become wasm calls, so a thread parked deep in a call
chain has its continuation *on the wasm stack*, which cannot be suspended. That
is precisely the JSPI/Asyncify problem `0005` avoided, arriving through the back
door.

The escapes:

- **Trampoline every call** — return to a driver loop that pushes the next frame.
  Correct, and it reintroduces dispatch at call granularity. Idiomatic Clojure is
  calls all the way down, so most of the win goes with it.
- **Colour the functions** — mark what can park, compile only the rest. This is
  "what colour is your function", and it spreads: anything transitively reaching
  a port operation is coloured, which in a language of higher-order functions is
  nearly everything (`map` takes a fn that might park).

## The shape that would work, if this is ever wanted

**Selective compilation, decided by an analysis already available.** The compiler
knows the call graph. A function that cannot transitively reach a parking
operation is compiled to wasm and registered as though it were a builtin; the
interpreter calls it directly. Everything else stays bytecode.

That confines the change to a leaf optimisation with no new suspension
semantics — and the functions it covers are exactly the arithmetic- and
collection-heavy ones where dispatch dominates. Threads, `eval` and the module
carrying its own compiler all keep working, because the interpreter is still
there.

## The other costs, which apply to any version

- **Module size.** A bytecode op is ~1 byte; the wasm for the same op is tens.
  The program portion grows by an order of magnitude, and `0003`'s whole
  modularity story is measured in bytes.
- **Cold start regresses**, and cold start is flint's largest measured win —
  1.00 ms to first answer against a V8 isolate's 14.59 ms. More code is more
  baseline compilation at instantiate.
- **`eval` and the embedded compiler get harder.** Emitting bytecode at runtime
  is writing bytes into a heap; emitting wasm at runtime needs the host to
  compile a new module, which is async in JS and unavailable in some embedders.
  That is the capability that makes flint's compiler able to run where cherry's
  cannot.

## Why not now, concretely

The measured bottleneck is not dispatch. Against cherry: parse **1.4×**, regex
**275×**, splitting on a literal **18×**. Removing dispatch addresses the 1.4×
and leaves the two order-of-magnitude problems untouched, and those have known
cheap fixes.

**Revisit when** dispatch is the top item in a profile — plausibly after the
regex and string work lands, and after superinstructions (`0001`) have been tried,
since those are the cheap half of the same win.
