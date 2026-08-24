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

## The shape that would work — regions, not functions

My first answer here was selective compilation at FUNCTION granularity, gated on
a call-graph analysis of what can park. The owner's refinement is better and
removes the analysis entirely:

> we wouldn't actually need to aot everything, or color the functions. We could
> just aot the contiguous non-blocking chunks into their own native functions
> that take a pointer to our thread

**Compile contiguous non-parking REGIONS**, each a wasm function taking a pointer
to the thread struct, operating on the same linear-memory value stack. Cut the
region wherever a park could happen and return to the interpreter there.

Why that is the better cut:

- **No colouring, because the cut point defines the property.** A region contains
  no parking operation by construction, so nothing needs to be proven about
  transitive reachability — which was the part that spread until nearly
  everything was coloured.
- **It applies everywhere**, not only to leaf functions. Function-level selection
  misses the arithmetic inside a function that also does one send.
- **Rooting is untouched.** Values stay in the linear-memory stack; the region
  gets a pointer to the thread and manipulates it exactly as the interpreter
  does. This is the property that makes the whole idea admissible.
- **Gas fits.** Increment once per region by its known static length, plus the
  usual charge inside any native it calls. Cheaper than per-instruction counting
  and equally deterministic, since the length is a compile-time constant.
- **It is incremental.** Compile the hottest regions only; everything else stays
  bytecode and nothing else changes.

Note what a region may contain: calls to natives that cannot park — `+`, `conj`,
`assoc`, `nth` — are ordinary calls, not cut points. Only the parking primitives
and calls to Clojure closures end a region. That matters, because it is the
difference between regions being a handful of ops and being most of a function
body.

## And it is an empirical question, so measure before building

The whole thing turns on a distribution nobody has looked at: **how long are the
regions in real code, and what does entering one cost?** If the average region
is three ops, the call into it eats the saving; if it is thirty, this is a large
win.

That can be answered **without building the compiler**, for a fraction of the
effort:

1. Instrument the interpreter to record, per dispatch, whether a region boundary
   was crossed — a call to a Clojure closure, a parking primitive, a back-edge.
2. Run construe's real fixtures and the benchmark suite; emit a **histogram of
   region lengths**, weighted by execution count rather than by static
   occurrence.
3. Measure the cost of a wasm call taking a pointer, on this host, as the
   boundary constant.
4. The estimated saving per region is then `(length − 1) × dispatch_cost −
   boundary_cost`, summed over the weighted histogram.

If that number is small, the answer is no and it cost a day. If it is large, the
histogram also says *which* regions to compile first, so the work starts
data-directed instead of speculative.

**Do this measurement before any of the implementation.** It is the cheapest
thing in this document and it decides everything else in it.

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

**And the region histogram is worth having even if the answer is no**, because it
is the same data superinstructions need: the hottest fusable sequences fall out of
it directly. One measurement, two decisions.
