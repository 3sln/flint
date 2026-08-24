# 0013 — Emitting wasm instead of dispatching, and what it costs

> **The measurement this document gated itself on has been taken.** It is in
> "The measurement, taken" below, and it says yes. Everything above that section
> is the argument as it stood before the numbers; the numbers did not overturn
> it, they sized it.

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

### Which makes the compilable unit a WHOLE FUNCTION, not a straight line

Worth stating plainly, because it changes the size of the prize. The original
framing was "compile contiguous non-parking chunks", where a region ended at
every call — and in idiomatic Clojure that is every few instructions.

With the guard, a call does not end a region. A region ends only where the guard
actually fires, which at runtime is rare. **So the static unit becomes the whole
function body**, and the guard is ordinary error propagation rather than a
boundary.

That is a much better position than the one I was arguing from: it is close to
what a real AOT compiler does, and the payoff scales with function size rather
than with the distance between calls.

### Re-entry, and why the answer is a few points rather than many chunks

The owner's follow-up is the right question:

> granular chunks let us hop back from interpretter mode to aot mode more
> granularly, at the end of every chunk, instead of just per function

That names a real problem. If compiled code can only be ENTERED at a function's
start, then after any bail the rest of that invocation is interpreted. The
compiled body is not wrong, it is simply unreachable until the function is called
again.

**Usually that costs almost nothing**, because a bail is rare: most functions
never park, and the AOT benefit lives in the invocations that never bail.

**One shape makes it catastrophic, and it is exactly our shape.** A loop that
parks per iteration — `drain-each` receiving in a loop, any I/O-driven consumer —
bails on the first iteration and then interprets *every remaining iteration*, for
ever. One park permanently de-optimises the whole loop. That is the case worth
designing for, and it is common in precisely the programs ports exist to serve.

### But the fix is re-entry POINTS, not many chunks

The set of places worth re-entering is small and known statically:

- **loop back-edges**, which is the case above;
- **the instruction after a call that could bail**, so a resumed park continues
  compiled.

That is a handful of `ip` values per function, not a chunk per basic block. So
compile the whole function and give it an **entry dispatch** — a parameter naming
which resumable point to start at, and a branch to that block — rather than
splitting the body into separately-called pieces.

**And this is cheap here for the same reason deopt is.** Re-entering compiled
code mid-function is normally on-stack replacement, and hard, because machine
state has to be reconstructed at an arbitrary point. Every flint value is already
in the linear-memory stack, so there is nothing to reconstruct: the dispatch
jumps to the block and the block reads the stack. The GC constraint pays for a
third thing.

### Why not the chained-chunks version

It would work, and the owner is right that the boundaries cost less here than
they would elsewhere — there are no registers to spill, because everything
round-trips through memory anyway.

But the JIT's real wins inside a compiled body are local: keeping a stack pointer
in a register, folding adjacent stack traffic, holding a temporary. Those are
what chunk boundaries interrupt, and they are the whole reason to compile at all.
Paying that at every chunk to buy re-entry at every chunk is paying everywhere
for something needed in a few places.

Compile whole, re-enter at the few points that matter.

### Which the measurement should now answer

Add to the histogram: **instructions executed after a bail before the function
returns**, and how many of those are inside a loop. That is the number that says
what re-entry is worth, and it separates the ordinary case (a bail near the end,
costing nothing) from the pathological one (a bail on iteration 1 of 10 000).

### Two consequences of the larger unit

**A bail from a nested call unwinds several wasm frames**, each one checking and
returning — which is fine, and it means EVERY compiled function must propagate,
not just the outermost. The interpreter's own frames have to stay in step so the
unwind lands somewhere valid, which is an argument for compiled functions
maintaining exactly the frame discipline the interpreter does rather than an
optimised variant of it.

**Deopt metadata grows with call sites**, since each needs a mapping back to a
bytecode `ip`. That is bytes in the module, and `0003`'s modularity story is
measured in bytes — so count it in the histogram alongside the saving, rather
than discovering it after the fact.

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


---

# The measurement, taken

`bench/regions.mjs`, against construe's real fixtures and against the wave run.
Weighted by execution, not by static occurrence. Reproduce with
`./bin/build-units --diagnostics && node bench/regions.mjs`.

## The boundary constant

**2.02 ns** for one `call_indirect` taking a pointer, on this host — measured,
not assumed, through the same table shape the natives already use. A direct
`call` would have been inlined by the engine and would have measured nothing.

Against the README's measured **6.2 ns/instruction** of dispatch, the break-even
region length is

    1 + 2.02 / 6.2 = 1.33 instructions

**Any region of two instructions or more already pays for itself.** That single
number is the surprise in this measurement, and it changes the shape of the
answer: the question was never whether regions are long enough, and the
"if the average region is three ops, the call into it eats the saving" worry
above is wrong by a factor of two.

## The two models

| workload | Model A: ends at every call | Model B: guard-only, one frame |
| --- | --- | --- |
| construe parse | mean 3.3 instructions | mean 11.3 |
| construe parse ×20 | 3.2 | 11.4 |
| construe suggest | 3.2 | 15.0 |
| waves (parks per iteration) | — | 11.4 per segment |

Priced with the measured constants, as a share of the dispatch cost recovered:

| workload | Model A | Model B |
| --- | --- | --- |
| parse | 60.2% | 88.3% |
| parse ×20 | 59.1% | 88.3% |
| suggest | 58.3% | 91.1% |

So the guard is worth roughly **30 points of dispatch** over the
region-ends-at-every-call model — which is the argument in "Which makes the
compilable unit a WHOLE FUNCTION" above, now with a number on it. But note the
other half: even Model A recovers 58%. The guard is a large improvement on an
option that was already worth taking.

## The guard costs nothing, and this is measured rather than argued

| workload | guards executed | of instructions | fired |
| --- | --- | --- | --- |
| parse ×20 | 188,605 | 23.4% | 0 |
| suggest | 507,401 | 25.1% | 0 |
| waves | 106,565,262 | 25.8% | **131** (0.0001%) |

A guard on one instruction in four, firing once in 800,000. That is a perfectly
predicted branch, which is what "the guard is ordinary error propagation rather
than a boundary" needs to be true.

**And read the zeros correctly.** Construe's fixtures report `guards fired: 0`
and `resumed frames: 0` — that is a COVERAGE zero, not a result. They never open
a port, so they never park, and the entire risk of this design is what happens
after a park. The wave run is in the table for exactly that reason.

## Re-entry: the pathological case is real, and it is 26% of the work

0013 names the shape that would make function-granular entry catastrophic: *"a
loop that parks per iteration bails on the first iteration and then interprets
every remaining iteration, for ever."* Measured on the wave run:

* 100,921 state saves, **all** of them port parks, none a courtesy yield;
* **25.8% of every instruction executed** runs in a frame that has already been
  resumed;
* and 97.4% of that work is in segments of **1024–2047 instructions** — one
  frame, parking and resuming ~94,000 times, running ~1,500 instructions between
  parks.

Without re-entry points, a quarter of this workload is interpreted for ever
after the first wave. **Re-entry points are not an optimisation here, they are
the difference between the design working and not working on the programs ports
exist to serve.**

## What sizes the chunks

Chained chunks inside one wasm function cost **nothing at run time** — a chunk
boundary is a fallthrough, and the engine optimises across it. What they cost is
module bytes. So the static side, over the construe program:

* 370 functions, 419 arities, 18,522 bytes of bytecode, 7,400 instructions
* mean **17.7 instructions per arity**, largest 1,026
* **1,213 call sites** and **35 distinct backward-jump targets**
* a re-entry point at every one of those is **1,248 points, one per 5.9
  instructions**
* at back-edges only: 35 points, one per 211

Zero arities are a single instruction, so nothing in this program is too small
to compile.

**The decision, which the measurement makes rather than justifies:** a re-entry
point after **every call site and at every backward-jump target**. The runtime
cost is a `br_table` arm; the byte cost is ~4 bytes each, against a compiled body
of roughly 20 bytes per instruction — call it 3% of the emitted code. Restricting
to back-edges would save 1,213 arms and roughly 5 KB, and would lose the resumed
segments above, which are 26% of the work in the one workload that parks. That
is not a trade worth making, and the number is why.

## What is still owed

* **Module size and cold start** are the two costs in "The other costs" above and
  neither is measured yet, because neither can be until an emitter exists. They
  are the ones that could still make this a bad trade, and they get measured
  against the same construe payloads.

---

# Built, measured, and where it stands

`--aot` on `bin/flint`, against units built with `bin/build-units --aot`. **Off
by default and EXPERIMENTAL**: there is an open correctness bug, recorded below
in full rather than left for somebody to rediscover.

## The shape that got built

`src/flint/aot.cljc` emits one wasm function per arity, laid out as a chain of
blocks that fall through in order inside a `loop` with a `br_table` at the top.
Three properties fall out of that layout and all three were asked for:

* the engine optimises across the join, because adjacent chunks are
  straight-line fallthrough;
* a forward jump is one `br`, because later chunks enclose earlier ones — only a
  BACKWARD jump pays the dispatcher, and back-edges are 2.4% of instructions;
* **re-entry is possible at every chunk**, which is the part wasm forces. You
  cannot branch INTO structured control flow, so reconstructing `if`/`else`
  nesting would have allowed entry only at the top — and the measurement above
  says a quarter of the work in a program that parks happens in a frame that has
  already been resumed.

A chunk boundary is: every jump target, every call, every native, every opcode
the emitter does not inline, every backward jump, and the instruction after each
of those. Free at run time; ~4 bytes of `br_table` each.

**Twelve opcodes are 98.7% of executed instructions** and all of them are
emitted inline. Everything else hands one instruction back to the interpreter
and resumes at the next chunk — which is what lets the emitter be COMPLETE from
the first version rather than refusing a function over one rare opcode.

## One correction to the design above, and it is not optional

0013 assumed compiled functions call each other and that a bail unwinds several
wasm frames. Half of that is right and half is not:

* A call to a closure DOES run on the wasm stack, and it has to — handing every
  Clojure call back costs four boundary crossings plus an interpreter dispatch,
  which in a numeric loop is three of those per iteration and more than the
  dispatch it saves. Measured: without it, `tight` was **1.18× SLOWER** than the
  interpreter.
* But it is **bounded at 48 frames deep**. The wasm stack cannot be suspended
  and cannot be grown; past the cap compiled code hands back and the interpreter
  carries on, so deep recursion still fails with a catchable
  `StackOverflowError` at `MAX_FRAMES` rather than trapping. Parking works at
  any depth because the frames the scheduler saves are the interpreter's, not
  wasm's — a park leaves through each level in turn and nothing about the
  continuation lives on the stack being unwound.

## Gas is exact, not approximate

Charged inline per chunk by the chunk's static instruction count, into a wasm
local, flushed on every exit. Exact because a chunk has no internal branch — and
making that true is why a jump ends a chunk. `test/aot.clj` asserts the
instruction count is **identical** with and without compilation on five programs,
not merely close; 0016 makes gas a production feature and construe's gates depend
on it. The first version of this was wrong in both directions at once and every
answer still matched, which is precisely why the count is asserted.

## The numbers

Against the interpreter, same host, same payloads:

| workload | interpreter | compiled | |
| --- | --- | --- | --- |
| `tight` (dispatch-bound loop) | 164.79 ms | 136.54 ms | **1.21× faster** |
| `words` | 110.91 ms | 90.62 ms | **1.22× faster** |
| `json` | 102.15 ms | 86.21 ms | **1.18× faster** |
| `maps` (allocation-bound) | 47.02 ms | 43.68 ms | 1.08× faster |
| construe `parse` ×20 | 7.47 ms | 7.01 ms | 1.07× faster |
| construe `suggest` | 17.00 ms | 13.63 ms | **1.25× faster** |

And the costs, which are the reason this is not on by default:

* **Module: +98%** on the construe payload (315 KB → 624 KB).
* **Cold start: 0.965 ms → 1.08 ms**, a 12% regression on flint's largest
  measured win.
* **Production is unaffected**: 203 757 bytes, against 203 360 before any of
  this. The machinery is a cargo feature (`aot`), absent by default, for the
  same reason diagnostics are — it measured 7 002 bytes of production module
  when it was merely unused rather than absent, and 0009 had already spent a
  chosen budget on instantiating the loop twice.

**The prize is smaller than the estimate said.** The estimate priced dispatch at
6.2 ns/instruction and predicted 88–91% of it recovered. The measured win is
8–25%. The gap is the estimate's, not the implementation's: 6.2 ns is the cost
of an entire tight-loop iteration for the simplest instruction, and a compiled
instruction still does the same loads and stores — only the branch and the
operand decode go away.

## The open bug

**A program that combines green threads with a HOST port produces wrong answers
under `--aot`.** Everything else measured — every `bench/progs` program, both
construe payloads, all five programs in `test/aot.clj`, and `test/threads.clj`
end to end — gives identical answers and identical instruction counts.

Reproducer, ~15 lines: open the `doc` capability with the EDN codec, send
`{:op :structure}`, receive. Interpreted it answers; compiled it raises
`edn: map needs an even number of forms` from the reader.

Delta-minimised to five arities that must ALL be compiled for it to appear:
`conj`, and `pk` / `nx!` / `skip!` / `token` from `clojure.edn`. No single one of
them does it, and no pair — so it is an interaction, not a bad instruction.

Ruled out by measurement, not by reading:

* **Not the GC.** The standing checks report zero over the failing run — but
  read that correctly: `stat_stale_root` reports *"over 0 collections"*. No
  collection ran at all. That is a coverage zero AND an elimination, and only
  because the coverage was printed beside it.
* **Not the nested wasm-stack call.** Setting `AOT_MAX_DEPTH` to 0 does not fix
  it.
* **Not chunk-internal gas accounting.** Fixing that changed the symptom (from
  `not a number` to `map needs an even number of forms`) without fixing it,
  which is itself the finding: the failure is sensitive to chunk layout.
* **Not the EDN reader alone.** Round-tripping the same payloads through
  `clojure.edn` under `--aot`, without a port, is correct.
* **Not a prefix.** Prefix bisection pointed at `read-symbolic`, then at
  `read-str`, then at `-`; every one of them was wrong on its own. Adding an
  arity shifts what else runs compiled, so a prefix names nothing. Same trap as
  collection #300 in the GC hunt, and the same answer: minimise a SET.

`bin/flint --aot` prints the warning, `test/aot.clj` guards everything that
works, and the bisection handles used to get this far are still there:
`FLINT_AOT_LIMIT`, `FLINT_AOT_FROM`, `FLINT_AOT_ONLY`, `FLINT_AOT_PICK`,
`FLINT_AOT_SKIP_FROM`/`_TO`, `FLINT_AOT_DUMP`, `FLINT_AOT_FN`.
