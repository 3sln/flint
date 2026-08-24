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

## Colouring, revisited — the objection is answered

I rejected function colouring because it spreads: anything transitively reaching
a parking operation is coloured, and in a language of higher-order functions
`map` takes a function that might park, so nearly everything ends up coloured.

The owner's design answers that directly:

> We color our native/built-in functions like +/-/break/etc. And then we
> propagate the blocking attribute outward, so any function that calls a blocking
> function becomes blocking. For closures, I don't think we should always
> automatically exclude them, most closures will be non-blocking. We can include
> closure calls with some kind of instruction with a runtime check.

**The spread was caused entirely by dynamic calls, and a runtime check removes
it.** Static colouring cannot know what a closure does, so instead of
pessimistically colouring the caller, carry the bit ON THE CLOSURE and test it at
the call site. `map` stays compilable; only a call that turns out to be blocking
leaves the region.

### The bit is static per function, tested per call

Blocking-ness is a property of the function a closure closes over, which is known
at compile time. So the closure object carries one bit, and a higher-order call
site emits: *call it here if the bit is clear, otherwise leave the region*. One
bit test and a well-predicted branch, at dynamic call sites only.

### Leaving the region is nearly free, BECAUSE of the GC constraint

This is the part that makes the design work rather than merely sound plausible.

A deoptimisation exit is normally expensive: the compiled code holds values in
machine registers and locals, and bailing out means reconstructing an interpreter
frame from them. **flint has nothing to reconstruct.** The reason `0001` chose an
interpreter is that wasm locals are not scannable, so an AOT region keeps every
value in the linear-memory stack anyway. The region has no private state.

So "leave the region" is: set `ip`, return to the interpreter. The constraint that
forced the interpreter is the same constraint that makes escaping compiled code
cheap.

### Three colours, not two

- **definitely non-blocking** — no blocking natives, no dynamic calls. Compiles
  whole, no checks.
- **conditionally blocking** — contains dynamic calls. Compiles, with a check at
  each one.
- **definitely blocking** — statically reaches a parking native. Its *regions*
  between those calls still compile (`0013`'s original unit), it just cannot be
  one region end to end.

Colouring and regions compose rather than competing: colour says where a boundary
is FORCED, regions say what to do between boundaries.

### The analysis must be conservative, and there is a check for it

A function wrongly marked non-blocking parks inside a wasm region with no way to
suspend — corruption or a hang. So any uncertainty colours blocking, and
recursion is a fixpoint starting from non-blocking and propagating until stable.

**And it is checkable**: assert, in a diagnostics build (`0016`), that a park
never occurs while inside an AOT region. That is the negative control for the
entire analysis, and it costs production nothing.

### And then colouring turns out not to be REQUIRED at all

The owner's follow-up, which I checked against the source rather than reasoned
about:

> we probably don't need coloring at all? Basically the instruction calls the
> closure, and captures blocks. If a block occured it jumps out of that compiled
> chunk... It just needs a compiled guard to check some 'blocked?' type thread
> variable?

**It checks out, and it is simpler than colouring.** Three facts make it work,
all of them already true:

1. **One flag covers everything abnormal.** `failed()` is `!thrown.is_nil()`, and
   a park travels as a distinguished value in that same `thrown` slot. So a
   region emits ONE load-test-branch after each call — *did anything abnormal
   happen* — and bails to the interpreter if so. Park, throw, gas exhaustion, all
   one check. **The region never needs to know what parking is.**
2. **A parking call is re-executable by construction.** The existing rule is that
   a parking builtin decides to park before it changes anything, and the resume
   path rewinds `ip` and re-runs the call. So "bail and let the interpreter do it
   properly" is always valid.
3. **Bailing is nearly free**, for the reason in the section above: every value
   is already in the linear-memory stack, so there is nothing to spill. Set `ip`,
   return.

So the guard is the mechanism and colouring is **an optimisation over it** — it
removes checks where they are provably unnecessary — rather than a prerequisite.
That is a much better place for it: the correctness story needs no analysis, no
fixpoint, and no closure bit, and the analysis can be added later purely to make
it faster.

### Two things to get right if the guard is the whole mechanism

**Not every call site is re-executable, and the code already knows it.** `vm.rs`
carries a `reexecutable: bool` through the park path precisely because `apply` has
already spread its operands and has no instruction to rewind to. Those sites must
keep raising rather than deopting — a region that assumed universal
re-executability would silently corrupt exactly the case that is already
documented as special.

**Without colouring, EVERY call site is load-bearing.** A missed guard in a
definitely-non-blocking function is harmless; a missed guard in the
guard-everything model is a park inside a wasm region with no way to suspend.
That is an argument for generating the guards mechanically from one emitter path
rather than by hand, and for the `0016` diagnostics assertion that a park never
occurs inside a region.

### Which also shrinks what colouring would buy

Worth being honest about, since I argued for colouring a message ago: the hot
case for AOT is arithmetic and small collection operations, and those natives
want **inlining** rather than calling. An inlined native has no call site, so it
has no guard either way. Colouring's saving is therefore concentrated on real
calls to non-blocking closures — narrower than it first looked.

Measure it as part of the region histogram rather than assuming: guards per
region, and how many a colouring pass would remove.

### A refinement worth taking at the same time

A native like `port-send` blocks only when the buffer is full, so colouring it
blocking ends a region at every send even though the common case does not block.
The same runtime-check trick applies: emit *send if there is room, otherwise leave
the region*. The fast path stays inside.

## And it is an empirical question, so measure before building

The whole thing turns on a distribution nobody has looked at: **how long are the
regions in real code, and what does entering one cost?**

**And colouring changes that distribution substantially**, so the measurement has
to model it. Without colouring a region ends at EVERY call, which in idiomatic
Clojure is every few instructions. With it, a region runs through every
non-blocking call — arithmetic, `conj`, `assoc`, and any closure whose bit is
clear — and ends only at a genuine parking point. Regions get much longer and the
payoff much larger, so a histogram computed on the old model would understate it
badly. If the average region
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
