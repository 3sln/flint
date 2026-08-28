# 0013 — Emitting wasm instead of dispatching, and what it costs

> **SHELVED — 2026-08-24, by the user's decision (*"drop aot for now, focus on
> strings and regex"*). The correctness bug that shelving named is FIXED —
> 2026-08-28.** It was two faults, not one, and both were the same mistake about
> resume points; see "The bug that was open for four days". The document
> capability under `--aot`, which is where it was first seen, now agrees with the
> interpreter on every one of its checks, and `test/aot.clj` carries the
> ten-line reproducer.
>
> `--aot` remains off by default and behind a cargo feature, so the production
> module carries none of it — that is a decision about what a default should
> cost, not a hedge about correctness. The performance half of the rationale has
> also moved; see "Re-measured 2026-08-28". Nothing here is deleted, because the
> measurements are worth more than the emitter and **the reason it
> under-delivered is now understood** — see "Why it lost, and what would make it
> win" at the end.
>
> **The measurement this document gated itself on has been taken.** It is in
> "The measurement, taken" below, and it says yes. Everything above that section
> is the argument as it stood before the numbers; the numbers did not overturn
> it, they sized it.

## `swap!` is atomic, and the AOT failure was a stale build

`swap!` was `(reset! a (f (deref a)))` -- a read-modify-write, so it lost
updates once two threads were inside one sandbox (`doc/decisions/0028`). It is
now Clojure's: a retry loop over `compare-and-set!`, which is a builtin.

Turning it on used to make `flint compile :optimize [perf]` abort with
`to-space overflow` on an unrelated two-line program. **It does not reproduce.**
The same change, on a tree where `units/`, `dist/` and the CLI were all rebuilt
from one source, compiles cleanly 17 times out of 17 and passes the whole
suite.

So the cause was almost certainly what the other two symptoms in that
investigation were: an **inconsistent build**. Adding a builtin shifts the table
slot of everything after it, and rebuilding only some of `units/`, `dist/` and
the CLI leaves artifacts that disagree about where a builtin lives. That does
not fail where it is stale. It segfaults somewhere else, in a program that
never mentioned the builtin, sensitive to a five-byte change in its source --
which is exactly the shape that sent me looking for a GC bug three times.

`bin/check-dist` now answers that question in one line: it reads the builtin
names straight out of `runtime/src/builtins.rs` and asserts `dist/slots.json`
and `dist/slots-aot.json` know every one of them. It runs in the suite BEFORE
the CLI is exercised, and it fails with a message naming the missing builtin
instead of a crash somewhere downstream.

Three wrong explanations came out of reading the source -- a shifted slot, a
shadowed special form, a value held in an unscannable local. The thing that
settled it was making the build state checkable.




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

## Re-measured 2026-08-28, after loop counters started specialising

The shelving rested on 1.07-1.25x. Half of that is now stale, and the half that
moved is exactly the half this document predicted would move.

`0013`'s own diagnosis is that the emitter "transliterated the interpreter; it
did not compile the program", and it names **type specialisation** as "the
largest single item on this list" -- knowing both operands are fixnums removes
the tag checks and the boxing, which is the 1.9-vs-6.4 ns gap it measured by
hand.

That is now partly true, without touching this emitter at all. A `loop` binding
takes its initialiser's type as a hypothesis and keeps it if every `recur`
proves it, so an ordinary counting loop emits the specialised integer opcodes --
which `aot.cljc` already compiles to `i64.add` and friends. Nobody annotates a
loop counter, and before that change not one specialised opcode was emitted in a
whole benchmark of integer arithmetic.

| | when shelved | 2026-08-28 |
|---|---:|---:|
| `tight` (arithmetic in a loop) | 1.21x | **2.97x** |
| construe `parse` x20 | 1.07x | 1.10x |
| construe `suggest` | 1.25x | 1.27x |

**Construe did not move, and that is the finding, not a disappointment.** The
coverage note in `bench/aot-construe.mjs` says why: construe's arithmetic
operands come from untyped PARAMETERS, not from loop counters, so almost none of
it reaches the specialised path. The change fixed counters. What construe
measures is dispatch removal, which is what this emitter always had.

So the shelving rationale stands for parser-shaped code and does not stand for
arithmetic-shaped code. Whoever picks this up should read that as a sharpening
of the same conclusion: the remaining wins are the other two items on the list --
unboxed values in wasm locals, and inlining -- and they are worth what this
document estimated, not less.

The same three items were built on the JVM and CLR backends and behaved as
predicted there: specialisation plus keeping an integer expression unboxed took
the JVM's AOT from 5.9x to 12x over its own interpreter (`0029`). That is
independent evidence for the mechanism, on a backend where it was cheap to try.

## The bug that was open for four days, and what it was

**A program that combined green threads with a HOST port produced wrong answers
under `--aot`.** It is fixed. It was TWO faults, found five days apart, and both
were in the same place: what a frame is told about where compiled code takes
over again.

### One: a tail call named a resume point

A `TAIL_CALL` replaces the frame -- the interpreter pops it and enters the
callee in its place -- so there is no next instruction of that arity left to
run. The emitter handed every non-inlined opcode back with "resume at
`ip + len`", and after a tail call that is the `RETURN` which follows it, so the
resume point said "return whatever is on top of the stack", registered against
a frame that no longer existed.

`reduce` ends `(reduce-seq f init coll)`. Compiled, it answered `coll` instead
of `init`, so `into` handed `persistent!` the empty list it had been reducing
over: `ClassCastException: not a transient`, several frames and one tail call
away from the cause. `aot/resume-after` is the rule and `test/aot_emit.clj`
asserts it.

### Two: the restore paired a block with the wrong ip

A thread save records each frame's `ip` and its `aot_block`. **`aot_ip` is not
saved at all** -- the restore substituted `ip` for it and kept the saved block.

For a frame that PARKED those two offsets are the same and the pairing is right,
which is why it held for as long as it did. For a frame that BAILED they differ
by exactly one instruction: a bail leaves `ip` on the instruction being handed
back and `aot_block` on the chunk AFTER it. Restore them as a pair and the frame
re-enters compiled code **one chunk early**, skipping the handed-back
instruction entirely.

`vec` is `(if (vector? coll) coll (into [] coll))`. It was mid-bail on the
`(vector 0)` that pushes the empty vector when its slice ended. It came back at
the chunk after that push, never made the empty vector, and the `tail-call 2`
then read the operand stack one slot low -- finding `coll` where `into` should
have been:

```text
ClassCastException: value is not a function (object type 13, 2 args)
  in vec <- fn <- fn <- reduce-seq <- mapv <- go <- main
```

Object type 13 is `TY_RANGE`. Six frames from the cause, and in a function whose
source has no range in it.

**The fix is one field.** The restore no longer reads the saved block; it asks
the compiled arity which block that `ip` names. `AotFn::points` already held
every re-entry point -- the field's own doc comment said a thread restored from
a save was one of its two users, and it was not. So the fix is also the check
the pair never had: `None` means "not a re-entry point", and the frame simply
carries on interpreting.

Measured before it was believed. A diagnostics build compared, for every
restored compiled frame, the saved block against the block its `ip` really maps
to: **one mismatch out of eleven restores**, and it named itself --

```text
bad: aot-idx 68  ip 964  saved block 4  real block 3
aot arity 68 = fn 65 arity 0 [:string "vec"] (off 949 len 23 argc 1)
```

### The reproducer, and why it took four days

The failure the investigation started from was a deadlock in a document store.
That was a SYMPTOM: `flint.rpc` spawns a reader thread that routes replies by
`:id`; the decode threw, the reader died, nothing routed, and the caller parked
for ever. Talking to the capability without `rpc` gave the real error, and it
was this document's original one: `edn: map needs an even number of forms`.

From there it shrank to ten lines with no capability, no reader and no EDN.
It is now `test/aot.clj`'s `park` program:

```clojure
(defn- go [] (count (mapv (fn [i] {:id i :kids (vec (range (rem i 4)))}) (range 33))))
(defn main [_]
  (let [[tx rx] (p/channel 1 "test")]
    (t/spawn (fn [] (p/send tx :go)))
    (p/receive rx)                       ; <- the park
    (pr-str (go))))
```

Every part is load-bearing, checked one at a time: without the `p/receive` there
is no save; at 32 elements rather than 33 the slice ends somewhere else; and
removing either the map literal or the nested `(vec (range ...))` changes which
arities are compiled. Delta-debugging over SETS -- not prefixes -- took it to
four arities that must all be compiled: `reduce-seq`, `vec`, and two lambdas.

It is in the suite as three checks: the same answer, and the same instruction
count. Without the fix it fails by 99 instructions -- the chunk that was skipped.

What made it expensive is that almost every discriminating measurement came back
NEGATIVE, and each one was worth taking anyway. Ruled out, each by a check that
did not fire:

* **Not a missing chunk boundary.** `chunk-all?` makes every instruction a
  boundary and the failure survives it. That same discriminator is what FOUND
  the tail-call fault, so it earns its keep in both directions.
* **Not the collector.** Zero collections over the failing run -- and read that
  correctly: `stat_stale_root` reports *"over 0 collections"*, so the coverage
  was printed beside the zero. Nothing moved and no root went stale.
* **Not SYNC drift.** The write-once fields were re-derived on every crossing:
  0 disagreements over 2 127 crossings.
* **Not a lost message.** Identical host traffic, passing and failing.
* **Not back-edge preemption.** The theory fitted the size-dependence exactly;
  the counter said `TICK TRIPS = 0`. A good story beaten by a measurement, and
  the unverified change it motivated was reverted.
* **Not the shape at the hand-back.** A check asserted that every bail hands the
  interpreter an operand stack whose callee really is a function: **0 bad out of
  68**. That was the finding that mattered, and it was read wrongly at first --
  it says the corruption is not at the bail, so look at what happens *after*
  one. The restore is what happens after one.
* **Not a prefix.** Prefix bisection pointed at `read-symbolic`, then `read-str`,
  then `-`; every one wrong on its own, because adding an arity shifts what else
  runs compiled. Same trap as collection #300 in the GC hunt, same answer:
  minimise a SET.
* **Not this week's compiler work.** The same reproducer built from `ac58229~1`
  -- before loop-type hypothesis and whole-integer-expression emission --
  failed identically. "The compiler changed and now AOT is broken" is the
  obvious story and it was the wrong one.

The bisection handles that got there are still in the tree: `FLINT_AOT_LIMIT`,
`FLINT_AOT_FROM`, `FLINT_AOT_ONLY`, `FLINT_AOT_PICK`, `FLINT_AOT_SKIP_FROM`/
`_TO`, `FLINT_AOT_DUMP`, `FLINT_AOT_FN`, `FLINT_AOT_CHUNK_ALL`, and
`FLINT_AOT_NAME`/`FLINT_AOT_NAMES`, which is what turned "arity 68" into "`vec`".

### What the two faults have in common

Both are the same mistake in two places: a resume point is a PAIR -- a bytecode
offset and the block that offset names -- and both faults came from carrying one
half of the pair and inferring the other. The emitter inferred the offset from
the instruction (`ip + len`, wrong after a tail call). The restore inferred the
offset from the frame (`ip`, wrong after a bail).

The block is derivable from the offset and always was; nothing needed to infer
anything. Both fixes are the same fix: ask.

---

# "Are you giving the JIT a chance?" — measured

A fair challenge to the 1.21×, and three specific mechanisms by which the engine
might be getting none. All three are now measured rather than argued.
Reproduce with `bb bench/ceiling.clj && node bench/ceiling.mjs`, and the tiering
check with `node --no-liftoff` / `--liftoff-only` over `bench/aot.mjs`.

## 1. Is this even TurboFan? Yes.

| | default tiering | `--no-liftoff` | `--liftoff-only` |
| --- | --- | --- | --- |
| tight | 1.20× | 1.26× | 1.10× |
| maps | 1.08× | 1.08× | 1.04× |
| json | 1.19× | 1.21× | 1.11× |
| words | 1.22× | 1.23× | 1.13× |

Default tiering matches forced TurboFan, so the benchmarks are measuring
optimised code and nothing is being reframed. The interesting row is the last
one: under Liftoff only, everything is ~1.7× slower in absolute terms AND the
compiled advantage collapses to 1.10×. **TurboFan does more for the compiled
code than for the interpreter** — the engine is not merely present, it is the
reason there is a win at all.

## 2 and 3. Operands in memory, and the `br_table` shape — worth 1%.

`out/ceiling.wasm` runs tight's loop three ways in the same engine on the same
NaN-boxed arithmetic:

| | ns/iteration |
| --- | --- |
| C1 operands in wasm LOCALS, a real `loop`, arithmetic inlined | 1.9 |
| C2 the same, arithmetic through `call_indirect` | 4.8 |
| C3 operands on a memory stack, `loop` + `br_table` — **what we emit** | 6.4 |
| flint, compiled | 136.9 |

**C2 → C3 is 1.33×, and 1.6 ns of 136.9.** That is the whole of hypotheses 2 and
3 together: keeping operands in wasm locals between safepoints and emitting hot
back-edges as real nested loops would buy about **one percent**. The idea is
sound — 0001 does make it legal, and guard-only chunking does make the spans
long — but the measurement says it is not where the time is, and building
register allocation between safepoints on this evidence would be work spent on
1.6 ns.

## Where the time actually is: the call protocol

The same loop with the builtins called DIRECTLY, so it makes no Clojure calls at
all:

| | interpreted | compiled | |
| --- | --- | --- | --- |
| `tight` — 3 closure calls per iteration | 165.1 ns | 136.9 ns | 1.21× |
| the same loop — **no closure calls** | 89.2 ns | 51.2 ns | **1.74×** |

So:

* **One Clojure call costs 25.3 ns interpreted and 28.6 ns compiled.** Compiled
  code makes calls slightly WORSE — it pays `aot_call`, `enter`, the reserve,
  the resync and `call_aot` where the interpreter pays only `enter`.
* **Everything else is 38.0 ns/iteration cheaper compiled**, which is the
  dispatch removal working exactly as intended.

**The speedup is therefore a function of call density, and the construe numbers
confirm it without being fitted to it**: `suggest` is 6.9% calls and runs 1.25×;
`parse` is 10.3% calls and runs 1.07×. More calls, less win.

## What this means for the decision, and for the next piece of work

The 1.21× is a **floor set by the call protocol, not by the emitter**. The
headroom is 28.6 ns per Clojure call, against 1.6 ns for everything the emitter's
shape costs. The candidates are all in the protocol and none of them is a
rewrite:

* `refresh()` writes seven fields on every crossing, and only the stack base and
  top can actually have changed on most of them;
* `enter` re-selects the arity on every call, and the call site is monomorphic
  almost always;
* a compiled-to-compiled call still crosses into Rust twice, to do a frame push
  the emitter has the information to do itself.

Measure those before building any of them — that is the rule this whole document
now runs on.

---

# Optimising it: what the numbers said to do, and what they said not to

The instruction was to make the compiled code faster, with the ceiling
measurement in hand and three named candidates. Two of the three are now
eliminated **by measurement**, one paid a little, and the biggest win was
somewhere none of us had proposed.

## The three candidates, measured

**`refresh()` writing seven fields per crossing.** Five of the seven can only be
written once — `consts` and `globals` stop growing when the image finishes
loading, `heap` is an arena base that `sbrk` extends but never moves, and the
last two are addresses of `Rt` fields in a static the `Rt` is moved into exactly
once. None of that is obvious from the call site, so the diagnostics build
re-derives all five on every crossing and asserts they have not moved:
**8 302 crossings checked, 0 drifted.** Writing two instead of seven: 1.21× →
1.24×, one call 28.6 → 28.0 ns. Real, small.

**An inline cache on `enter`'s arity selection.** The premise is that the call
site is monomorphic, and that is a measurable claim. Measured at real `CALL`
sites in construe: **24.7% of `parse`'s and 8.9% of `suggest`'s would MISS.**
Against a `select` that already returns on its first iteration for an exact
match, a cache that misses a quarter of the time is not worth its miss path.
**Not built.**

**A conditional prologue** — emit only the bases the body actually reads, since
a callee like `+` is four instructions long and touches neither the closure nor
the constants. 1.24× → 1.25×. Real, small.

**Removing a boundary from compiled-to-compiled calls** was the one I was told to
start with, and it is the right instinct — but by the time the others were
measured it was clear the boundary is not what a call costs. A Clojure call is
25.4 ns **in the interpreter too**; compiled code adds ~2 ns to it. Removing two
of the crossings could recover about 4 ns of 28. It is still worth doing and it
is not done.

## The lever that was not on the list

The measurement kept saying the same thing: the speedup is a function of **call
density**, and `tight` spends three closure calls per iteration on `<`, `inc` and
`+`. Those are not user code. They are four-instruction wrappers in
`clojure.core` around a single builtin.

`register-native-aliases!` already existed to make exactly that free — *"a core
var whose body is exactly one `flint.rt/x` call with the same arguments is
recorded, so call sites go straight to the builtin"*. It required the var to have
**exactly one arity**, which excluded `+`, `-`, `*`, `/`, `<`, `<=`, `>`, `>=`,
`=`, `==`, `conj`, `assoc`, `dissoc`, `disj`, `get`, `nth`, `subs`, `ex-info` and
the rest — every one of which is written as a two-argument arity beside a
variadic tail. **The most common call in the language was paying for a full
Clojure call.**

Registering per ARITY, and allowing constants among the parameters so that
`(inc i)` becomes `add(i, 1)`:

| | before | after | |
| --- | --- | --- | --- |
| `tight`, interpreted | 164.8 ms | **90.5 ms** | 1.82× |
| `tight`, compiled | 136.5 ms | **50.9 ms** | 2.68× |
| construe `suggest`, compiled | 13.6 ms | **12.1 ms** | |
| construe `parse`, compiled | 7.0 ms | **5.6 ms** | |

And because it removes calls rather than making them cheaper, **the compiled
advantage went up as well**:

| | before | after |
| --- | --- | --- |
| tight | 1.21× | **1.78×** |
| words | 1.22× | **1.40×** |
| json | 1.18× | **1.30×** |
| suggest | 1.25× | **1.29×** |
| maps | 1.08× | **1.16×** |
| parse | 1.07× | **1.15×** |

`tight` compiled is now 50.9 ns/iteration against 49.7 for the hand-written
version that makes no closure calls at all — it has become that program, which
is the confirmation that the mechanism is understood and not merely correlated.

The interpreter gets the same win, which is the part worth noticing: this was
never an AOT optimisation.

## A live bug, found by accident

Extending the alias needed a template scan, the first draft used `reduce` with
`reduced`, and self-hosting broke. `reduced` has **never worked in flint**: it is
a one-element vector carrying a marker in its metadata, and `reduce` unwrapped it
with `deref`, which knows about atoms, volatiles and delays and nothing else. Any
short-circuiting `reduce` raised `ClassCastException: cannot deref this value`.

`(nth acc' 0)` fixes it, `test/aot.clj` pins it against Clojure's own answers,
and it is worth recording how it was found: not by a test, but by a compiler pass
that happened to use the feature. Nothing in the suite had ever taken that branch.

---

# Why it lost, and what would make it win

Written after the fact, because "AOT was only 1.07–1.25×" is useless to whoever
picks this up and "here is the mechanism" is not.

## It transliterated the interpreter; it did not compile the program

The ceiling was measured by hand-emitting `tight`'s loop three ways in the same
engine on the same NaN-boxed arithmetic:

| | ns/iteration |
|---|---:|
| operands in wasm **locals**, real `loop`, arithmetic inlined | 1.9 |
| same, arithmetic through `call_indirect` | 4.8 |
| memory operand stack + `loop`/`br_table` — **what this emitter produces** | 6.4 |
| flint, compiled | 136.9 (50.9 after the aliasing fix) |

**The entire emission shape accounts for 6.4 ns.** Everything else was never
dispatch. A compiled `add` does exactly what the interpreter's `add` arm does:
load two i64s from the value stack in linear memory, check tags, unbox, add,
check overflow, box, store back. AOT deleted the branch and the operand
decode — about 6.2 ns — from a per-instruction cost far larger than that.

It kept the interpreter's **data representation** and its **calling convention**
and removed only its `switch`. That is why it wins where dispatch dominates
(`tight`, 1.78×) and barely moves where calls do (`parse`, 1.15×), and the model
predicts rather than fits: `suggest` is 6.9% calls and runs 1.29×, `parse` is
10.3% and runs 1.15×.

## The three things a compiler does that this does not

- **Type specialisation.** Knowing both operands are fixnums removes the tag
  checks and the boxing entirely. That is exactly the 1.9-vs-6.4 gap, and it is
  the largest single item on this list.
- **Unboxed values in wasm locals** across spans with no call and no allocation.
  Legal by `0001` — wasm locals are not scannable, so a value may live only in a
  local exactly as long as nothing can collect or park, and guard-only chunking
  makes those spans long. Worth ~1% *on its own*, because boxing is still there;
  it is what makes specialisation pay.
- **Inlining.** And the evidence is direct: the largest win of the whole
  exercise was `register-native-aliases!` extended per-arity, which is
  hand-inlining `+`, `<` and `inc` at one specific shape. It beat every call
  protocol tweak combined — and it made the **interpreter** 1.85× faster, which
  is the sharpest possible statement that the win was never AOT-specific.

## So the ceiling is genuinely higher, and the work is a compiler

You cannot specialise an interpreter, so AOT's ceiling really is above the
interpreter's. It has not been reached because what was built removes dispatch
and nothing else. Anyone resuming this should start from specialisation and
inlining, not from the emitter's shape — the shape is worth 1.6 ns and has
already been measured.

## What was eliminated on the way, so it is not re-proposed

- **Not a tiering problem.** Forced TurboFan matches default tiering (`tight`
  1.20× vs 1.26×); under `--liftoff-only` the compiled advantage collapses to
  1.10×, so the optimiser is engaging and helps compiled code *more* than the
  interpreter.
- **Not an inline-cache problem.** Call sites are not monomorphic enough:
  **24.7%** of construe `parse`'s real `CALL` sites and 8.9% of `suggest`'s would
  miss, against a `select` that already returns on its first iteration.
- **Not the sync protocol.** `refresh()` did write seven fields where five are
  write-once — proven, not assumed, by re-deriving all five on every crossing:
  **8,302 crossings, 0 drifted.** Fixing it was worth 1.21× → 1.24×.
