# 0014 — A debug runner: DAP, nREPL, and `(break)`

> **NOT BUILT — roadmap, and explicitly not next.** Recorded because the design
> is unusually cheap here and the reason is worth knowing before anything is
> built that would make it expensive.

An extended runner supporting the Debug Adapter Protocol, an nREPL server, and an
in-source `(break)` form.

## Why this is cheap here, and would not be elsewhere

Debugging a compiled language means DWARF, source maps, or a JIT that can
deoptimise. Debugging wasm from a source language is notoriously bad for exactly
that reason: the thing running is not the thing you wrote.

flint has none of that problem, and it is the same property that made green
threads cheap:

- **Execution state is DATA in linear memory.** Frames, the value stack, locals,
  the instruction pointer. A debugger reads them; it does not walk a native
  stack.
- **A breakpoint is a park.** `0005` already suspends a thread, hands control to
  the host, and resumes it with a value. `(break)` is `open`-shaped: signal, wait
  for a continuation token, carry on. **No new suspension mechanism.**
- **Stepping is the gas counter.** `0009` already stops at an exact instruction
  count and reports it as a catchable event. "Step one instruction" is a budget
  of one.
- **A debug session is a port.** DAP and nREPL are message protocols; `0006`
  already carries messages between the host and the runtime. The adapter is a
  driver over the existing ABI, not a new host interface.
- **`eval` already works** where the compiler is linked (`0003`), which is what
  an nREPL needs to be more than a stack viewer.

So the runtime work is close to nothing. The work is the protocol adapters and
the source mapping — and the mapping is bytecode-offset to line/column, which the
compiler can emit because it is our compiler.

## The two things to get right, and one to avoid

**Debug info is optional, and modular.** Line tables are large. They belong in a
side unit that a debug build links and a production build does not, exactly as
`0003` handles everything else. A module that carries its line table always is a
module that pays for a debugger it will never run.

**Zero cost when not debugging.** A breakpoint check per instruction is the same
mistake `0009` just fixed by monomorphising the gas counter into two loop
instantiations. Do the same: a debug-enabled loop chosen at entry, and the
non-debug loop with no check compiled in at all.

**Avoid: making the debugger a second execution mode.** If debugging changes
scheduling, allocation, or the order of anything, then bugs move when you look at
them. `0005` made the scheduler deterministic for its own reasons; that decision
pays off here, because a session can be replayed to the same breakpoint. Guard it
— it is a rare property and easy to spend.

## What it would buy, beyond the obvious

- **Reproducible debugging.** Same program, same inputs, same host event order,
  same breakpoint hit at the same instruction count. Most debuggers cannot
  promise this.
- **Debugging the thing that runs in production**, since it is the same bytecode
  in the same interpreter, with only a side table added.
- **Debugging model-written code**, which for construe is the point: an evolved
  artifact that fails a gate could be stepped through against the case it failed.
- It is also the natural place for the **region histogram** `0013` wants, since
  both need per-instruction instrumentation behind the same feature flag.

## What must be true if this is built

- A production module's size is **unchanged** by the existence of debug support,
  asserted by a test.
- The non-debug interpreter loop contains no breakpoint check, shown by the same
  before/after measurement `0009` used.
- A breakpoint hit, a step, a variable read and a resume all work through the
  existing port ABI with no new host entry point.
- The same program, run twice with the same inputs, stops at the same instruction
  count.
