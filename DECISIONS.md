# Decisions

This file consolidates the decision records that used to live as 38 separate
numbered files in `DECISIONS.md`. Each section below is one decision, with
its reasoning kept rather than trimmed to a conclusion — several of these
records hold hard-won findings (numbers measured, approaches tried and
abandoned, bugs that motivated a rule), and the reasoning is the part worth
having.

**Status matters as much as content.** This file mixes descriptions of
shipped behaviour with plans for work that does not exist, and each section
says which, as of the date this consolidation was written. A status banner
can go stale — this project has more than once had a banner claim "nothing
built yet" long after half of it was, which is the more dangerous direction
because it is the one that gets something rebuilt by mistake. Where a
decision was later revised, superseded, or turned out wrong, that is recorded
in its own section rather than silently dropped: the reasoning that produced
a wrong answer is usually the reasoning somebody needs in order not to repeat
it.

Sections are grouped by topic rather than by the numeric order they were
written in, which was mostly an accident of when a question came up. Each
heading's anchor is the decision's old filename slug, so existing citations
(`` `strings-and-matching` ``-style references in code comments, commit messages, and other
docs) resolve to the right section once rewritten against this file; the
original number is noted under each heading for anyone cross-referencing
material that predates that rewrite.

**Contents**

*I. The execution model*
[dispatch](#dispatch) ·
[emit-wasm-instead-of-dispatch](#emit-wasm-instead-of-dispatch) ·
[resource-limits](#resource-limits) ·
[two-builds](#two-builds) ·
[debug-runner](#debug-runner) ·
[snapshots](#snapshots) ·
[profiler](#profiler) ·
[a-vec-of-values-is-not-a-root](#a-vec-of-values-is-not-a-root)

*II. Modularity and the build*
[modularity](#modularity) ·
[namespace-units](#namespace-units) ·
[exclude-and-unit-path](#exclude-and-unit-path) ·
[module-metadata-and-shards](#module-metadata-and-shards) ·
[no-runtime-linking](#no-runtime-linking)

*III. Data structures and language surface*
[strings-and-matching](#strings-and-matching) ·
[matching-over-ropes](#matching-over-ropes) ·
[tables](#tables) ·
[tagged-literals](#tagged-literals) ·
[reader-tags](#reader-tags) ·
[checks](#checks)

*IV. Concurrency, ports, and the host boundary*
[threads-and-ports](#threads-and-ports) ·
[host-abi](#host-abi) ·
[structured-ports](#structured-ports) ·
[ports-are-the-hosts](#ports-are-the-hosts) ·
[bridges](#bridges) ·
[drivers](#drivers) ·
[thread-pool](#thread-pool)

*V. Capabilities, the CLI, and dependencies*
[cli](#cli) ·
[opaque-values](#opaque-values) ·
[workspace-capabilities](#workspace-capabilities) ·
[system-namespaces-and-deps](#system-namespaces-and-deps)

*VI. Other runtimes*
[other-hosts](#other-hosts) ·
[jvm-runtime](#jvm-runtime) ·
[clr-runtime](#clr-runtime) ·
[kin](#kin) ·
[cross-runtime-benchmarks](#cross-runtime-benchmarks)

*VII. construe: the first customer*
[construe-benchmarks](#construe-benchmarks) ·
[construe-integration-bar](#construe-integration-bar)

---

## about this file, and the sign-off

Every decision here carries a **Ratified** box, and none of them is ticked.

THAT IS THE POINT. These records were written in the course of doing the work,
mostly by an assistant, and most were never reviewed by a human. A decision in
this file is a record of what was DONE. It is not an agreement, and it does not
bind anything until somebody signs it off.

**An unratified decision is a proposal with running code behind it.** Treat it
as evidence of what the tree currently does, not as a rule you have agreed to.
Where you disagree with one, the code is what has to change -- but the
disagreement is legitimate and the record does not settle it.

### Triage, 2026-09-12: 47 unratified, and they are not one pile

**32 of the 47 carry a status reading "per the record; not independently
verified".** Those were copied forward in the spring cleaning from the old
decision files, and the phrase is exactly true: nobody checked them against the
code.

**Ratifying one of those signs off on folklore, not on a decision.** The fifteen
that HAVE been checked make the case: four of them were wrong and had to be
corrected — `kin`'s banner said "NOT BUILT" beside 91 sources; `structured-ports`
carried a "NOT BUILT — a proposal" banner over working code; `cli` claimed the
binary IS the CLI while serving five of thirteen commands; `other-hosts` read as
a flint→LLVM backend that did not exist. A fifth, `:local/root`, was recorded
built and had never worked.

Four corrections in fifteen checks. Against 32 unchecked statuses, that rate
suggests several more are wrong — which is an argument for checking them, not
for signing them.

**So the pile is three piles.**

**Ratifiable as recorded — built, checked, behaving (8).** These were verified
against the code this session, and re-verified after the merges that followed:
`structured-ports`, `kin`, `npm-cli`, `pods-are-a-resolvable-dependency`,
`kin-probes-assert-a-value`, `a-parallel-gate-body-never-exits`,
`dialects-and-preludes`, `port-tests-in-kin`.

**Needs a decision from you before it means anything (7).** Each has running
code and an open question the code cannot answer:

* `cli` and `other-hosts` — does the native binary survive? The 5.8x that
  justified it measures 1.7x, and it serves five of thirteen commands.
* `the-pike-vm-is-the-last-triplicate` — is generating `Rt` the goal? ~21 000
  hand-duplicated lines and thirteen unportable tests turn on it.
* `llvm-ir-target` — should `:to :llvm` imply `:optimize [perf]`; where does
  `nativeabi/` belong?
* `one-dependency-walk` — manifest parsing landed in `.cljc`, not the Rust
  modules `ROADMAP.md` specified. The reason is recorded; the deviation is
  yours to accept or reverse.
* `standalone-scripts` — an agent decided the entry-point rule, that a script
  declares no capabilities, and that a script cannot be a dependency. All three
  are defensible and none is yours yet.
* `no-runtime-linking` — partly built, and the remainder is a scope question.

**Not ratifiable until somebody checks them (32).** Everything else. The work is
not review, it is verification: read the code the status describes and correct
the status. `bin/check-decisions` proves a citation RESOLVES and can never prove
it is TRUE.

A cheap way in: `port-tests-in-kin` has no status line at all, which makes it
the one item in this file whose claim cannot even be assessed.

AND THE STATUS CLAIMS IN THESE RECORDS ARE NOT RELIABLE. Several were verified
wrong: one opens "NOT BUILT -- a spike, nothing in the tree uses it yet" and
describes a generator that now emits 89 modules into three runtimes; another
says a data type "does not exist" beside 552 lines implementing it; a pair of
runtime ports were designed to lean on their host's own collector and both
now carry a verbatim port of the collector instead. The old project status
index (the the decision index (now folded into `DECISIONS.md`) table this file replaces) is not a safe
tiebreaker either -- it contradicted the decision files it was summarising in
more than one row, and in at least one place contradicted itself between
adjacent rows. Where a section below repeats a status claim from its source
file, read it as REPORTED rather than established, and where it says a claim
was checked directly against the current code, that check was real and is
named as such -- the two are marked differently on purpose. A swarm of
agents is independently re-verifying every status claim in this file against
the code as it stands; where their findings land, this file's own claims
should be treated as superseded rather than final.

---

## dispatch

**Interpreter vs AOT, and stack vs register**
*(formerly `dispatch`)*

**Ratified:** ☐ not signed off

**Status: shipped — verified 2026-09-12 against the code and by measurement.**
The stack machine is `Rt::run`/`run_with`/`run_inner` in `runtime/src/vm.rs`
(a `match opcode` over a value stack in linear memory, `vpush`/`vpop`); there
is no register form anywhere in the tree. The dispatch numbers were re-taken
by the method `runtime/src/abi.rs` states beside `stat_steps` — time it with
counting off, count it with counting on, divide — on a module built by
`./target/release/flint compile :to :wasm` and run under node via
`host/flint.mjs`, best of three fresh instances: **4.78 ns/instruction** on a
3 000 000-iteration `(loop [i 0 acc 0] ...)` (39 000 824 steps, 186.2 ms), and
**6.69 ns/instruction** on a `str/split` + `frequencies` payload (7 839 964
steps, 52.5 ms). Both are at or below the 6.2 / 8–19 ns the record carries, so
the figures below are conservative rather than stale. The AOT half of
the question is its own decision (`emit-wasm-instead-of-dispatch`, below).

### What was decided

flint interprets a stack-machine bytecode rather than compiling straight to
wasm functions, and the bytecode is a stack design rather than a register
design.

### Why

**Interpreter over AOT.** The tempting alternative — emit a real wasm
function per Clojure `fn`, letting the host JIT do the work — collides with
how flint roots values. wasm locals are not scannable by a collector. Under
AOT, live references would sit where the collector cannot see them, requiring
a shadow-stack spill around every allocation site that hands back most of the
speed gained. The interpreter keeps every live value in linear memory
instead, which is exactly what makes "the value stack IS the root set" work
as a rooting design. That gap is what WasmGC exists to close; until a runtime
has it, the interpreter is the right choice on this constraint alone.

**Stack over register.** The literature (Shi, Casey, Ertl & Gregg, *Virtual
Machine Showdown: Stack Versus Registers*, VEE 2005 / TACO 2008) shows roughly
47% fewer dispatched instructions and ~32% faster execution for registers, at
~25% larger bytecode — and the case for registers is if anything *stronger*
in wasm than natively, because wasm has no computed goto and no tail-call
threading, so a `br_table` dispatch loop with an unpredictable branch is what
a stack design gets stuck with regardless.

Even so, the stack machine won, for two reasons specific to this project
rather than to stack machines in general:

- **Codegen is a post-order walk.** Register allocation is real engineering
  work sitting on the bootstrap critical path — the compiler has to compile
  itself before anything works at all, so anything that delays a working
  compiler is expensive here in a way it would not be for an established
  language.
- **Stale registers retain references.** A stack machine drops a reference
  the moment it pops; a register slot holds whatever was last written until
  something overwrites it, so a dead slot keeps an object alive unless the
  compiler proves liveness or clears it explicitly. That is floating garbage,
  invisible, and interacts directly with the GC design flint depends on.

The working theory was that dispatch is a *second-order* cost here — HAMT
traversal, hashing, allocation and GC should dominate real workloads, with
dispatch mattering most in the self-hosted compiler's own symbol/keyword
churn. That is roughly what the measured 6.2 ns/instruction confirmed: real
where it bites, not the dominant cost overall.

### Later

The bytecode format was deliberately designed so hot instruction pairs could
later be fused into superinstructions without a format break, to recover part
of the register-machine's dispatch win cheaply. Whether that mattered in
practice was answered later, empirically, by `register-native-aliases!` (see
`emit-wasm-instead-of-dispatch`) rather than by superinstructions as such —
extending native-call aliasing per-arity turned out to be the cheap lever that
delivered a comparable win, making the **interpreter** 1.85× faster on its
own, independent of AOT.

---

## emit-wasm-instead-of-dispatch

**AOT regions instead of a dispatch loop, and why it under-delivered**
*(formerly `emit-wasm-instead-of-dispatch`)*

**Ratified:** ☐ not signed off

**Status: BUILT and SHIPPED as an opt-in; what is parked is further
optimisation, not the feature. "Shelved" was the stale word — corrected
2026-09-12 by checking the code and running it.** `:optimize [perf]` is a
documented option of the shipped CLI (`flint --help`), backed by a second
runtime blob the binary carries (`dist/flint-runtime-aot.wasm` and
`dist/slots-aot.json`, `include_bytes!` at `cli/src/main.rs:39`), and
`bin/test` exercises it on every run in three sections (`test/aot_emit.clj`,
`test/aot.clj`, and the whole `test/common` language suite diffed
interpreted against compiled, byte for byte).

**The method for the numbers below, because a ratio without one can only be
believed or ignored.** One program — `(defn tight [n] (loop [i 0 acc 0] (if
(< i n) (recur (inc i) (+ acc i)) acc)))` — compiled twice by
`./target/release/flint compile :path p :fn bench/main :to :wasm`, once
plain and once with `:optimize '[perf]'`: **597 349 bytes** against **654 234
bytes**, the second reported by the CLI as "compiled arities". Both modules
run under node through `host/flint.mjs`, n = 3 000 000, best of three fresh
instances each, both returning the same answer (`4499998500000`):
**191.8 ms interpreted against 56.0 ms compiled — 3.4x.** That is better than
the 1.21x this section's table records at shelving time, and consistent with
the later fix that table notes (136.9 → 50.9 ns/iteration).

`src/flint/aot.cljc` is not dormant either, and this is what makes "shelved"
unrecoverable rather than merely stale: **the LLVM emitter requires it and
reads its opcode table.** `src/flint/llvm.cljc` opens `(:require [flint.aot
:as aot])` and calls into it in real code — `aot/OPS` for the opcode byte (in
`opcode-byte`, deliberately "one table" rather than a restatement),
`aot/resume-after`, `aot/jump-target`, `aot/TAG-FIXNUM`, `aot/FIXNUM-BITS`,
`aot/AOT-NEVER`. Deleting the shelf would take the LLVM backend with it.

The rest of the old status HOLDS unchanged, and was checked rather than
carried: the `aot` cargo feature is absent by default (`runtime/Cargo.toml`)
and a default-compiled module carries no compiled arities; and both
correctness bugs below are fixed, with the fix visible as the shape they
needed — `AotFn::points` (`runtime/src/aot.rs:101`) holding every valid
re-entry point, and `aot_ip` carried per frame (`runtime/src/vm.rs:238`) and
saved in the thread-save format (`runtime/src/conc.rs:595`) rather than
inferred from `ip`. This
section is long because it is one of the most heavily instrumented pieces of
reasoning in the project: multiple rounds of measurement, two real bugs, and
a final diagnosis of *why* the win was smaller than predicted.

### What was decided

Rather than dispatching bytecode one instruction at a time, compile
contiguous **regions** of a function's bytecode into real wasm code that
operates on the same thread structs and the same linear-memory value stack —
so this does not reopen the rooting problem `dispatch` solved: values never
leave linear memory, wasm is just used to manipulate it.

### Why, and how the design got here

The naive version — compile whole functions to wasm calls — breaks parking.
A green thread parks by being VM state the scheduler declines to run; but a
thread parked deep inside compiled-to-wasm calls has its continuation *on the
wasm call stack*, which cannot be suspended. That is the JSPI/Asyncify
problem the interpreter design was built to avoid, arriving through the back
door. Trampolining every call reintroduces the dispatch cost this design
exists to remove; statically "colouring" which functions can park spreads
almost everywhere in a language of higher-order functions, since `map` takes
a function that might park.

The design that survived, arrived at over several rounds of refinement:

- **Compile contiguous non-parking regions**, cut wherever a park could
  happen, each a wasm function taking a pointer to the thread struct. No
  colouring is needed for *correctness*, because the cut point defines the
  property: a region contains no parking call by construction.
- **A single runtime guard replaces static colouring entirely.** Every
  abnormal outcome — park, throw, gas exhaustion — already travels through
  one distinguished value (`thrown`). So a compiled region emits one
  load-test-branch after each call: *did anything abnormal happen?* If so,
  bail to the interpreter. The region never needs to know what parking even
  is. This works because a parking builtin always decides to park before
  changing anything, so re-executing the call from the interpreter is always
  valid, and because — the same GC constraint that forced the interpreter in
  the first place — every value is already in linear memory, so bailing has
  nothing to spill. Set the instruction pointer, return.
- **This makes the compilable unit a whole function, not a short chunk**,
  since a call no longer has to end a region — only a guard that actually
  fires does, and at runtime that is rare.
- **Re-entry points, not per-chunk boundaries.** If compiled code can only be
  *entered* at a function's start, a bail permanently de-optimises the rest
  of that invocation to interpretation. That is usually cheap, except for
  exactly the shape ports exist to serve: a loop that parks per iteration
  bails on the first iteration and interprets every remaining one forever.
  The fix is re-entry points at loop back-edges and at the instruction after
  any call that could bail — a handful of known offsets per function, given
  an entry dispatch that jumps to the right block. This is cheap for the same
  reason bailing is: nothing needs reconstructing, because nothing left
  linear memory.

### The measurement, before building anything

Before writing an emitter, the region-length distribution was measured by
instrumenting the interpreter to record region-boundary crossings on real
construe fixtures, and pricing the wasm call boundary directly: **2.02 ns**
per `call_indirect`, against 6.2 ns/instruction of dispatch. That put the
break-even region length at 1.33 instructions — any region of two
instructions or more already pays for itself, which meant the earlier worry
that "if the average region is three ops, the call eats the saving" was wrong
by roughly a factor of two.

Two chunking models were compared: ending a region at every call (mean 3.2–3.3
instructions on construe workloads) versus the guard-only, whole-function
model (mean 11.3–15.0 instructions). Priced against the measured constants,
guard-only recovered 88–91% of dispatch cost against 58–60% for the
per-call model — about 30 points of dispatch cost, on top of an already
worthwhile option.

### What got built, and the numbers

`flint.aot` emits one wasm function per arity as a chain of fallthrough
blocks inside a `loop` with a `br_table` at the top — free at runtime for a
forward jump (later chunks enclose earlier ones), paying the dispatcher only
on a backward jump (2.4% of instructions). Twelve opcodes cover 98.7% of
executed instructions and are all inlined; everything else hands one
instruction back to the interpreter and resumes at the next chunk, which is
what let the emitter be complete from its first version.

Against the interpreter, same host, same payloads, at shelving time:

| workload | interpreter | compiled | |
|---|---:|---:|---|
| `tight` (dispatch-bound loop) | 164.79 ms | 136.54 ms | 1.21× |
| construe `parse` ×20 | 7.47 ms | 7.01 ms | 1.07× |
| construe `suggest` | 17.00 ms | 13.63 ms | 1.25× |

Costs: module +98% on the construe payload, cold start +12% (1.00 ms flint
was measuring as its single largest win against a JIT isolate), production
build unaffected (the machinery is a cargo feature and compiles to nothing
when absent). **The prize was smaller than the estimate**: the estimate
priced dispatch at 6.2 ns/instruction and predicted 88–91% recovered; the
measured win was 8–25%. The gap turned out to be the estimate's, not the
implementation's — see "Why it lost" below.

### The two correctness bugs, and what they had in common

A program combining green threads with a host port produced wrong answers
under `--aot`, tracked down over roughly four days to two faults, both the
same underlying mistake: **a resume point is a pair** — a bytecode offset
*and* the block that offset names — and both faults came from carrying one
half of the pair and inferring the other.

1. **A tail call named a resume point that no longer existed.** `TAIL_CALL`
   replaces the current frame; the emitter had handed every non-inlined
   opcode a resume point of "the next instruction", which after a tail call
   is the `RETURN` that follows it — registered against a frame that no
   longer existed. `reduce` ended up returning `coll` instead of `init`,
   surfacing several frames and one tail call away from the cause as a
   `ClassCastException` on `persistent!`.
2. **Restoring a parked thread paired the wrong offset with the wrong
   block.** The thread-save format recorded `ip` and `aot_block` per frame,
   but never saved `aot_ip` — restore substituted `ip` for it. For a
   *parked* frame those two offsets coincide, which is why the bug survived
   as long as it did; for a *bailed* frame they differ by exactly one
   instruction, so restore re-entered compiled code one chunk early, skipping
   the instruction that had been handed back.

Both fixes were the same fix: stop inferring the missing half and ask for it
directly (`AotFn::points` already held every valid re-entry point). Both are
now regression-tested with a ten-line reproducer (`test/aot.clj`) that needed
every one of its details — a spawn, a channel receive, specific collection
sizes — found by delta-debugging over *sets* rather than prefixes, because
adding or removing any arity shifted what else ran compiled.

### Why it lost, and what would make it win

Written after the fact, because "AOT was only 1.07–1.25×" is not actionable
on its own. Hand-emitting the same loop three ways in the same engine
isolated the answer:

| | ns/iteration |
|---|---:|
| operands in wasm **locals**, real `loop`, arithmetic inlined | 1.9 |
| same, through `call_indirect` | 4.8 |
| memory operand stack + `loop`/`br_table` — what this emitter produces | 6.4 |
| flint, compiled | 136.9 (50.9 after a later fix, below) |

**The entire emission shape accounts for 6.4 ns.** A compiled `add` does
exactly what the interpreter's `add` arm does — load, check tags, unbox, add,
check overflow, box, store — with only the branch and operand decode removed.
The emitter **transliterated the interpreter; it did not compile the
program.** It never did type specialisation (knowing both operands are
fixnums, to skip tag checks and boxing entirely — the largest item), never
kept unboxed values in wasm locals across allocation-free spans, and never
inlined. Direct evidence for the last one: the biggest win of the whole
exercise was extending `register-native-aliases!` to work per-arity — turning
`+`, `<`, `inc` and friends from real Clojure calls into direct native calls
— which took `tight` from 1.21× to 1.78× compiled *and made the interpreter
1.85× faster on its own*, proving the win was never AOT-specific. It also
turned up a live, previously-unfound bug: `reduced` had never actually worked
in flint (`reduce` tried to `deref` it, which only knows atoms/volatiles/
delays), fixed with `(nth acc' 0)` and pinned against Clojure's own answers.

Other candidates were tried and measured away: an inline cache on call-site
arity selection (24.7% of construe `parse`'s real call sites would miss —
not worth its miss path) and removing a compiled-to-compiled call boundary
(the boundary the interpreter already pays too; only ~2 ns of the 28.6 ns a
Clojure call costs). What did pay: writing only the two `refresh()` fields
that can actually change instead of all seven (1.21× → 1.24×, proven safe by
re-deriving all seven on every crossing in a diagnostics build — 8,302
crossings, 0 drifted) and a conditional prologue that emits only the bases a
callee's body actually reads (→ 1.25×).

**Revisit when dispatch is the top item in a profile** — after specialisation
and inlining have been tried, since AOT's ceiling really is higher than the
interpreter's (you cannot specialise an interpreter), but reaching it needs a
compiler, not a dispatch-removal pass. `bench/regions.mjs` and the ceiling
harness (`bench/ceiling.clj`/`.mjs`) are the reproduction path for anyone
picking this back up.

### Also settled here

- **`swap!` was a plain read-modify-write**, silently losing updates under
  concurrent sandboxes; it is now a compare-and-swap retry loop, a builtin
  used across all four runtimes. A build inconsistency that looked like a
  correctness bug in this area (stale slot tables from a partial rebuild) led
  to `bin/check-dist`, which now checks build consistency directly instead
  of letting it surface as a segfault far from the cause.
- The design **ports to the JVM and CLR runtimes**, because wasm, JVM
  bytecode and CIL are all stack machines with locals over flat memory — what
  changes is the opcode table and the container, not the shape. `:optimize
  [perf]` compiles arities on all four runtimes now, gated by comparing
  interpreted against compiled output *and* gas counts *and* comparing the
  two hosts' transcripts character for character.

---

## resource-limits

**Hard limits, and the loop that does not count**
*(formerly `resource-limits`)*

**Ratified:** ☐ not signed off

**Status: shipped — verified 2026-09-12 by running it, not by reading it.**
Deterministic gas, charged natives, and a catchable memory cap, all three
observed against a module built by `./target/release/flint compile :to :wasm`
and driven under node through `host/flint.mjs`. **Determinism:** the same
program reported **30 985 steps** on three fresh instances. **The limit is
exact and catchable:** `set_step_limit(200000)` on a runaway loop returned
`ResourceExhausted: gas limit exceeded: spent 200000 of 200000 (thread 0)`,
and the same program wrapped in `try/catch` caught it. **The memory cap:**
`set_memory_limit(8 MiB)` returned `memory limit exceeded: 8388608 bytes of
8388608 in use after a collection`, caught by the guest. **Charged natives**
were proved adversarially, since that is the half that would quietly not
work: a program whose bytecode is constant — `(= (vec (range n)) (vec (range
n)))` — reported 7 882, 673 098 and 67 194 713 steps for n = 10², 10⁴ and 10⁶,
so the O(n) native work is billed (`Rt::charge*`, `runtime/src/rt.rs`). This
determinism is what makes every cross-engine number in
`cross-runtime-benchmarks` comparable, and it is one of the most heavily
relied-on properties in the codebase.

### What was decided

flint bounds a program's execution by an exact, deterministic **instruction
count** (gas) rather than a wall-clock timeout, and by an exact memory cap
enforced by the collector. Both are catchable errors, not crashes.

### Why

A wall-clock timeout bounds *time*, not *work* — it varies with machine load,
with what else is running, with the weather. A gate built on one is flaky by
construction: the same candidate passes on a quiet machine and fails on a
busy one. An instruction count is deterministic: the same program produces
the same count on every machine, every run — which turns "did this candidate
hang?" from a flaky timeout into a reproducible fact. For construe, whose
entire premise is gates measuring model-written code and being believed, that
distinction is close to the whole point of using flint at all.

### The mechanism, and why it looks the way it does

**Monomorphised, not branched.** The naive shape — a branch checking the
step count on every instruction — is a branch on every instruction, even
though predictable. Instead the interpreter loop is generic over a budget
policy (a const bool / zero-sized type) and instantiated twice: `NoBudget`'s
increment compiles to nothing and the check disappears entirely; `Counting`
keeps the check. The instantiation is chosen once at sandbox entry, not
per-instruction — the same technique `two-builds` later generalised to
everything diagnostic.

**Per-executor local counters, batched into a shared atomic.** With more than
one thread able to run inside a sandbox, a shared counter incremented every
instruction would put an atomic read-modify-write on the hottest line in the
interpreter. Instead each executor counts into a private `u64`; when a local
checkpoint fires, it adds its batch to a shared atomic and reads the total
back. The guarantee weakens from "stops at the limit" to "stops as soon as it
can after the limit", with overrun bounded by batch size × executor count —
the right trade, since the point of a limit is that a runaway is stopped, and
stopping it a few thousand instructions late stops it just as dead. **The
total consequently stops being exactly deterministic once more than one
thread runs concurrently in one sandbox** — a property of parallelism, not of
this design, and worth stating so it is not mistaken for a regression later.
Every number `cross-runtime-benchmarks` compares is at one executor, so those
numbers stay exactly comparable.

**The same checkpoint doubles as the collector's safepoint.** A moving
collector needs every thread to reach a stopping point before it starts; the
gas checkpoint already polls at a bounded interval, so it costs nothing
extra to also serve as that poll. This resolves an apparent tension with "the
unbudgeted loop has no counter at all": the dispatch policy is chosen at
sandbox-construction time based on what there is to poll *for* — a single
executor with no gas limit still runs the fully free `NoBudget` loop; having
a peer thread, or a limit, selects a polling policy.

**Natives must charge for their own work**, or the whole scheme has a hole
exactly where it matters most: instruction counting bounds *bytecode*, and a
call into a native builtin is one instruction regardless of what it does
internally. One `re-find` against a pathological pattern, one `sort` of a
huge vector, one big `merge` — all one instruction, and all capable of
running arbitrarily long. Construe has an entire gate for catastrophic
regex backtracking precisely because this is a live hazard in model-written
code, and a gas limit a single regex call can escape is worse than no limit,
because people will trust it. So every builtin whose cost is not O(1) charges
proportionally to what it actually did (elements touched, comparisons,
backtracking steps), which is also what makes the regex Pike VM's gas
accounting exact rather than heuristic.

**Allocation must be charged too, and this was missed for a long time.**
Natives charged for string bytes, comparisons, and regex steps, but nothing
charged for allocation itself — so a builtin could allocate without bound for
a constant gas price. It was found by accident: appending 20,000 rows to a
table allocated 49,061,464 bytes for 461,232 gas, *less* than the 741,252
charged by a bulk build that allocated only 3,254,832 bytes — gas was saying
the more expensive path was cheaper. It is now charged once, centrally, in
the allocator itself (one unit per 8 bytes, the same rate byte-charging
already used), deliberately in one place rather than trusted to every
builtin's author, and deliberately *not* through the path the collector's own
promotion uses, so gas stays independent of when a collection happened to
run.

**Billing is not the same as bounding, and that gap had its own bug.**
`charge_work` adds to a counter; nothing looks at that counter until the next
*interpreter* instruction, so a native that charges a large amount and then
loops internally still burns real CPU for the whole internal loop before the
budget check ever fires. Measured by giving a program exactly enough gas to
build its input and then run one more operation: `(apply str v)` and
`(apply + v)` both ran roughly 2.7 million steps past an exhausted budget —
the same amount, which is what pointed at `apply` (the spreading mechanism)
rather than either callee as the actual culprit. The fix is checking the
budget *inside* the loop, not merely charging before it: `charge_tick` checks
every 64 iterations for a tight inner loop, `charge_checked` checks every
time for a loop whose iterations are already substantial, and both `apply`
(the builtin) and the compiled `APPLY` opcode needed the fix, because a bound
that holds on only one of two paths is not a bound.

The test file (`test/gas.clj`) doubles workload size and asserts gas scales
proportionally — and its *negative controls* (`count` and a table row ref
must **not** scale with size) turned out to be load-bearing: the first
version of the test subtracted its own setup cost incorrectly and every
operation "scaled," including ones that should not have, which would have
passed while measuring nothing but its own baseline error.

### Details worth keeping

- Charging is for **allocation, not collection** — collection cost depends on
  heap size and on when it happened to run, and charging it would make gas
  depend on the memory limit chosen, which is exactly the kind of
  non-portable dependency this whole mechanism exists to avoid.
- Exceeding either limit is a **catchable error** carrying what was spent
  against what the limit was — never a trap — so a host can distinguish "the
  program is wrong" from "the budget was too small."
- **Hitting the memory cap collects first, then fails** — otherwise the cap
  would depend on GC timing, since a collection might have freed enough to
  continue.

---

## two-builds

**A stripped production VM, and everything diagnostic optional**
*(formerly `two-builds`)*

**Ratified:** ☐ not signed off

**Status: shipped — verified 2026-09-12 against the shipped artefact, not
just the script.** Both builds are compiled and tested on every suite run:
`bin/test` builds production (`bin/build-dist`, `bin/build-units`, plus
`test/twobuilds.clj`) and then the instrumented one (`cargo test -p flint-rt
--features diagnostics`, `bin/build-units --diagnostics`, `bin/test:602-624`).
The rule itself was checked on the bytes rather than the build script:
`strings dist/flint-runtime.wasm` finds `set_step_limit` and
`set_memory_limit` — production features — and **zero** occurrences of
`stat_heap_used`, `stat_bytes_allocated` or any `flint_snapshot_*`; the same
holds for a module compiled here with the release CLI. Absent, not disabled.

### What was decided

A production module contains **no diagnostic machinery at all — not cheap,
not runtime-gated, absent.** This is a cross-cutting rule that supersedes the
per-feature "keep it out of the pure module" clauses in `resource-limits`,
`debug-runner`, and `snapshots`, stating once, for everything, what "keep it
out" actually means and how it is enforced.

### Why

A runtime flag is tempting and wrong: the code is still linked, still costs
module bytes, and still puts a branch somewhere hot. `resource-limits`
already worked this out for gas specifically and monomorphised the counter
out of the loop; this generalises that conclusion to everything diagnostic.
**Cargo features and monomorphisation, not runtime flags** — absent code
cannot be enabled by accident, cannot be branched on, and cannot be measured,
which is the only guarantee worth having.

The live example that motivated writing this down: the `forward()`
plausibility check — arguably the single most valuable diagnostic in the
codebase, the thing that actually found the port bug this project spent a
fortnight chasing — was gated `cfg!(debug_assertions) || self.stress`, which
meant a release build paid 357 bytes for the sake of a stress-testing path it
never used. The fix is not to delete the check; it is to make it a feature,
so a diagnostics or staging build carries it, production does not pay for it,
and a host chasing a production fault can deploy the instrumented build and
reproduce there.

**What counts as diagnostic and what does not, and getting this wrong strips
something the product needs.** Diagnostics (absent by default): snapshots and
their export format, the inspector, the root verifier, GC stress mode,
write-attribution tracing, the forwarding-pointer assertions, every `stat_*`
export, the debug runner. Production features (always present): gas limits
and the memory cap, the deterministic scheduler, chained error reporting, the
`:exclude`/unit machinery. These are resource control and correctness
properties, not instrumentation — **gas in particular must never be
stripped**, since construe's gates depend on a deterministic instruction
count and it already costs nothing when unlimited.

**It is also a security argument, not only a size one.** flint's strongest
measured case is as a sandbox for untrusted, model-written code — a large
cold-start win against a V8 isolate, with no host access by default. A
production module that ships snapshot export is a production module that can
be asked to dump its entire heap, running code somebody else wrote. Absent is
a different guarantee from disabled, and that is what settles this even where
the raw byte count would not.

Enforcement is checked, not merely intended: a symbol-name assertion that a
production module exports no `snapshot_*`, no `stat_*`, no stress-mode
setter, no verifier; both builds compiled and exercised in CI, since a
feature nothing compiles is a feature that silently rots until the first
person who needs it finds it broken.

---

## debug-runner

**DAP, nREPL, and `(break)`**
*(formerly `debug-runner`)*

**Ratified:** ☐ not signed off

**Status: roadmap, explicitly not next — verified 2026-09-12 as still
unbuilt.** Nothing here exists: the tree has no hit for `nREPL` or the Debug
Adapter Protocol in any language, no `(break)` special form (every "break" in
`src/flint/` is prose in a comment), and no REPL in the CLI. `cli/src/serve.rs`
is the host event pump for `system-namespaces-and-deps`, not a debug server —
checked by reading it, because its name invites the opposite assumption.
Recorded because the design is
unusually cheap here for reasons worth knowing before something is built that
would make it expensive.

### What was decided

An extended runner supporting the Debug Adapter Protocol, an nREPL server,
and an in-source `(break)` form, all reusing existing machinery rather than
inventing new suspension, stepping, or transport mechanisms.

### Why this is cheap here

Debugging a compiled language usually means DWARF, source maps, or a JIT that
can deoptimise — and debugging wasm from a source language is notoriously bad
for exactly that reason: the thing running is not the thing that was written.
flint has none of that problem, for the same reasons green threads and
snapshots are cheap here:

- **Execution state is data in linear memory** — frames, value stack,
  locals, instruction pointer. A debugger reads them; it does not walk a
  native stack.
- **A breakpoint is a park.** Green threads already suspend on demand, hand
  control to a host, and resume with a value; `(break)` is shaped exactly
  like `open` — no new suspension mechanism needed.
- **Stepping is the gas counter.** The instruction count already stops
  execution at an exact count and reports it as a catchable event; "step one
  instruction" is a budget of one.
- **A debug session is a port.** DAP and nREPL are message protocols, and
  ports already carry messages between host and runtime; the adapter is a
  driver over the existing ABI, not a new host interface.
- **`eval` already works** wherever the compiler is linked in, which is what
  an nREPL needs to be more than a stack viewer.

So the runtime work is close to nothing; the real work is protocol adapters
and mapping bytecode offsets to source line/column, which the compiler can
emit because it is flint's own compiler.

Two things to get right, one to avoid: debug info belongs in a separate
optional unit rather than always-present, the same discipline `two-builds`
generalised everywhere else; a debug-enabled interpreter loop must be a
separate monomorphised instantiation with zero cost when absent, not a
runtime-checked flag; and **debugging must never become a second execution
mode** — if attaching a debugger changes scheduling, allocation, or ordering,
bugs move when you look at them, which would spend the deterministic
scheduler's whole value.

---

## snapshots

**VM snapshots: instant, exportable, inspectable**
*(formerly `snapshots`)*

**Ratified:** ☐ not signed off

**Status: shipped — verified 2026-09-12 by capturing one and reading it.**
Capture, export/import, and an inspector, all opt-in
under `two-builds` (present only in a diagnostics build). Driven under node
against the diagnostics module `out/sn-work.wasm`: `flint_snapshot_capture`
returned a **5 276 704-byte** memcpy image, which `host/snapshot.mjs` parsed
and validated in one pass (**2 757 objects walked, 0 problems**, gas counter
carried); `flint_snapshot_restore` returned 1; `flint_snapshot_export`
returned a **41 430-byte** live set with magic `XSLF`
(`snap::MAGIC_LIVE`, `runtime/src/snap.rs:563`), and
`flint_snapshot_import` accepted it **into a different instance** — which is
the "two formats, two jobs" split recorded below, demonstrated rather than
asserted. The opt-in half was probed adversarially, since a snapshot export
in a production module is exactly the thing `two-builds` exists to prevent:
compiling `(:require [flint.snapshot])` with the release CLI fails closed —
`compile error: no such builtin: flint.rt/snapshot`.

### What was decided

The whole VM state can be captured instantly, exported as bytes and restored,
and read by an inspector tool — specified after ad-hoc debugging instruments
had repeatedly and confidently lied about what was happening.

### Why: it is a copy, not a question

Every probe built for the port bug this project spent a fortnight on asked
*one* question, and more than one answered it wrongly in a way that looked
clean: a counter hooked on the collector's `forward()` reported zero moves
and could not have reported anything else given where it was hooked; a watch
address registered against a young object went stale the instant that object
was promoted. A snapshot has neither failure mode, because **it is a copy,
not a question** — ask whatever you like afterwards, re-ask when the question
changes, compare two snapshots rather than trusting a running probe. The
instrument cannot misrepresent state it never interpreted.

**Capture must be a memcpy, not a graph traversal**, for the same reason: a
traversal that misses an edge produces a snapshot silently missing an object,
which means debugging the capture instead of the bug it was meant to expose.
So the original capture format copies the raw bytes of both semispaces and
old space, plus Rust-side state, and interprets *later*. What must be in it
because omitting any one piece is silently wrong: roots (value stack, shadow
stack, globals, consts, singletons, intern tables), the frame table and every
green thread's saved state, the remembered set (both the list *and* the
per-object flags, since the original investigation turned on those two being
able to disagree), and scheduler/allocation state.

**The format is tied to a runtime version** and stamped with a fingerprint of
the image it belongs to, refused loudly on any mismatch rather than read as a
plausible-looking heap that means something else — every frame's instruction
pointer, every constant index, every var slot in a snapshot is an index into
a specific image, and restoring against a different one would otherwise
silently mean something else.

**Correctness is proven by round-trip, not merely asserted:** snapshot,
export, import, snapshot again, byte-identical; and a program snapshotted
mid-run, exported, imported, and resumed produces the same answer *and the
same instruction count* as one that ran through uninterrupted — which the
deterministic scheduler and deterministic gas together make a testable
equality rather than a hope.

> **NOT BUILT, and the record said otherwise.** A subsection here described
> `(snap "name")` — capture-and-continue as its own form, a per-name ring
> buffer keeping the latest hit with a count, compiling to nothing in a
> production build with the compiler reporting how many it elided — as settled
> design. None of it exists. There is no `snap` form in the analyser and none
> in `lib/flint/snapshot.cljc`; the real API is `flint.snapshot/snapshot!`, a
> single anonymous snapshot.
>
> Two commits put that prose here. "Named snapshots from inside the program,
> and the rule they inherit" (`1c7a061`) changed ONE file: this record, +54
> lines. "snap is its own form, and the guard makes the AOT unit a whole
> function" (`426af38`) changed two documents and zero lines of code.
>
> It is removed rather than corrected, because there is nothing to correct —
> it was never a decision that was later reversed, it was a description of
> work that did not happen, written in a voice that reads as though it had.
> The idea may still be worth building; it is tracked in `ROADMAP.md` as an
> unbuilt item rather than recorded here as a settled one.

**And it is not only for debugging.** Snapshot-restore doubles as the fix for
flint's per-invocation cold-start cost: snapshot after top-level
initialisation runs once, restore per request, and each request starts from
a memcpy of a small live set instead of re-running every initialiser. A
snapshot plus the host's event log is also a complete, deterministic replay,
and "attach the exact state at the moment a gate failed" is worth more to a
platform running model-written code than a stack trace.

### Later: two formats, because there turned out to be two jobs

The original argument against a traversal-based format — that a bespoke
traversal missing an edge silently corrupts the snapshot — does not hold for
*this* traversal, because the collector, not the exporter, decides what is
live: a major collection runs first, and the exporter then walks the
resulting heap linearly, where everything not `TY_FREE` is live by
construction. A missed edge here would be a collector bug that loses objects
in ordinary running, which the standing GC-stress suite already checks for
independently.

That traversal-based "live-set" format was added because the memcpy format
cannot do three things a second job needs: it copies the whole reserved
space including dead objects (a small program's memcpy capture was 5.3 MB
against 38.5 KB of actual live data — 0.7%); it pins the restore to identical
addresses, by the original design's own admission, so it can restore only
into the exact same instance; and it is wasm-only in principle, since the JVM
and CLR ports hold flint values as host objects with no byte range to copy at
all. The live-set format restores into a *different* instance, which is what
shelving a running sandbox actually requires — at the cost of being unable to
capture a heap whose pointers are already known-corrupt, which is precisely
the situation the memcpy format was built for. Both are kept: **traverse to
move a sandbox, memcpy to debug one.**

---

## profiler

**Named blocks, and CPU told apart from waiting**
*(formerly `profiler`)*

**Ratified:** ☐ not signed off

**Status: roadmap, not next — verified 2026-09-12 as still unbuilt.** Nothing
here exists: no named-block API, no per-thread block stack, no `flint.prof`
namespace anywhere in `lib/`, `src/`, the runtime or the ports. The four
tree-wide matches for "profil" are Maven profiles in `lib/flint/deps.cljc`
and a storage-profile comment in `host/docstore.mjs` — checked, because a
grep for this word is nearly all false positives. The diagnostics build's
`runtime/src/aotstat.rs` counters are an opcode census and the region
histogram for `emit-wasm-instead-of-dispatch`, not this. Recorded because two
of its dependencies are
being built elsewhere and one measurement it would give away for free is
already owed.

### What was decided

An opt-in, development-only profiler recording named, nested, per-green-
-thread blocks — with **instruction count and wall-clock time kept as two
separate numbers, never summed.**

### Why

The determinism that makes gas useful makes profiling unusually strong here
too: the same program profiled in **instructions** reports the identical
count every run, on any machine, so two profiles can be diffed and the
difference means something — where a wall-clock profiler gives noise plus
signal and leaves the reader guessing which is which. And the scheduler
already knows *exactly* when a thread is parked and on what (a port receive,
a full send, an `open`) — not a sampling profiler's inference, but state the
VM already keeps. So CPU-versus-waiting is not a feature to build; it falls
directly out of reporting the two clocks separately: a block that costs 3,000
instructions and waits 200 ms has two true facts about it, and collapsing
them into one "cost" number destroys the one that says what to fix.

Blocks are named, nested (a block's parent is whatever was open when it
opened, so the call tree falls out of nesting for free), and — critically —
**the block stack is per green thread**, exactly like dynamic vars; getting
this wrong would produce a profile that looks entirely plausible while
attributing work to the wrong thread, which is worse than no profile.

It shares infrastructure deliberately rather than being a new subsystem: a
cargo feature under `two-builds`; carried inside a `snapshots` capture, read
by the same inspector object model; and delivered as a port stream rather
than a new host entry point. It would also give `emit-wasm-instead-of-
dispatch` its region-length histogram for free, since that is exactly
per-instruction instrumentation with a grouping. The first real customer
would be profiling flint's self-hosted compiler compiling itself — a large,
allocation-heavy, already-in-the-repository program whose instruction count
is already known from the self-hosting fixpoint test.

---

## a-vec-of-values-is-not-a-root

**A Rust `Vec<Value>` is not a root, and it cost a day**
*(formerly `a-vec-of-values-is-not-a-root`)*

**Ratified:** ☐ not signed off

**Status: fixed** (2026-08-28), **and heavily cited across the runtime as the
canonical statement of a rooting rule every port now checks itself against —
verified 2026-09-12 by reading the fix, not the claim.** The named fault is
gone at the site that produced it: `Rt::ordered_map`
(`runtime/src/kgen/rt/mapmake.rs`) now pushes each element on the shadow
stack **before** calling `next`, with this rule cited by name in the comment
that explains why. It is a kin-generated shared source, so the identical fix
is in `runtimes/jvm/src/com/_3sln/flint/kgen/rt/Mapmake.java` — the ports do
not each re-derive it. The citation claim holds too, and is a count of real
citations rather than of grep hits: **68** across the Rust runtime, both
ports' own sources, and kin.

### What was decided

**A value held in a host-language local or container across a call that can
collect a value the collector does not know about, and is unsafe the moment
that call actually collects.** Concretely: a Rust `Vec<Value>` gathered
outside the shadow-stack rooting mechanism is not scanned by the collector,
so anything already in it goes stale the instant a collection moves it.

### Why this is worth a whole record rather than a one-line rule

The bug it names produced `RuntimeError: memory access out of bounds` with no
flint frame and no message, triggered by adding one unrelated `def` to the
compiler's own source. The first three hours were spent on the wrong
question ("why does adding a variable break the compiler?") because the
compiler compiles itself, so any change to its own source changes the
program being compiled, and the fault is sensitive to exactly where a
collection happens to land — which the input size moves. Two plausible-
looking discriminators turned out to be testing the wrong thing entirely
("the diagnostics build passes" — because the spec being compiled had also
changed underneath it; "`--keep-names` fixes it" — same artefact, byte-for-
byte). **What actually ended it was freezing the pair** — one module, one
input, both fixed on disk — which is the same lesson `snapshots` draws one
level up: a moving reproducer defeats careful reasoning, and the fix is to
stop anything from moving before varying the next thing.

With the pair frozen, a diagnostics build's stale-write instrumentation named
it directly: `flint/array-map` gathered lazy-seq elements into a plain Rust
`Vec<Value>` across calls to `first`/`next` that force the seq and can
therefore collect — 28 stale values out of 24.5 million pushes checked, all
at one collection, written into one map slot. The fix is to root each value
**as it is taken**, on the shadow stack, and read it back from there after
the allocation — the same pattern the runtime's own `cons` and rope-node
construction already used correctly, and correctly is the point: this is not
an unknown pattern, it is a known pattern applied inconsistently.

**Why nothing caught it sooner:** the bug needs three things at once — a
*lazy* input (so the walk actually allocates), *enough* elements to span a
collection, and a *build that notices* (the stale-write/stale-push
counters). The counters already existed and were already asserted at zero in
the GC-stress suite; the suite simply never exercised a large map literal
built from a lazy seq, which is exactly the shape the compiler's own map
literals are (the reader deliberately uses `array-map` for source-order
preservation, since two hosts iterating a hash map differently would break
the self-hosting fixpoint). The same fault, same shape, was found in the
codec's collection-gathering code by reading for the pattern rather than
waiting for a second symptom, and fixed the same way.

### What this says about the discipline going forward

Every other `Vec<Value>` in the runtime was audited at the time and found
safe, each for a stated reason worth keeping as a checklist: some re-read
values from the shadow stack after allocating (the correct pattern); some are
built through an immutable borrow that the type system proves cannot
allocate; some collect through a callback that itself never allocates and
roots before doing anything that does. This rule is now the single most
cited decision in the codebase outside of `strings-and-matching`, `tables`
and `resource-limits`, and is invoked verbatim across the Rust runtime, both
ports, and `kin`'s shared vocabulary sources whenever a native holds a value
across a call site that could collect — including three further rooting bugs
found later by the same reading discipline (in `Eq`'s sequence-walking and
table comparison code on the JVM and CLR ports, all fixed, all covered by a
stress-mode gate that now runs on both ports' own runtime test suites, not
just the shared conformance fixtures).

---

## modularity

**Only reachable code ships, builtins included**
*(formerly `modularity`)*

**Ratified:** ☐ not signed off

**Status: superseded in mechanism; the requirement it states still stands**,
met by `namespace-units` below. Verified 2026-09-12: neither rejected mechanism
is in the tree — `bin/build-units` opens with "No cargo on the compile path:
`flint` only links these", and nothing nulls dispatch-table entries. The
requirement holds, measured at the artifact: `flint inspect` reports `(+23
builtins)` for `(defn main [_] "hi")`, `(+39)` for the same program calling
`flint.data.json/write-str`, `(+192)` for the loader build. The parsers are
adapted crates, not hand-written — `units-src/flint-data-json` over
`serde_json`, `…-xml` over `xmlparser`, `…-html` over `htmlparser`, reached
from cljc through `flint.rt/json-parse` (`lib/flint/data/json.cljc:39`).

### What was decided, and what replaced it

The requirement — only reachable code, including core builtins, should be in
the output; parsers should be adapted Rust crates rather than hand-written —
is the owner's original requirement and it stands unchanged. This document's
own proposed *mechanism* for meeting it does not: it considered rebuilding
the runtime per compile with cargo features (correct, but puts a full Rust
build — tens of seconds — on every compile), or keeping one prebuilt runtime
and having a patcher null out unreached dispatch-table entries before running
wasm dead-code elimination (clever, but depends on a discipline — "the
builtin registry is the *only* thing that references an optional Rust
function" — that nothing enforces and that is impossible to retrofit once
something calls a parser directly). Both were rejected in favour of the
linking-based design in `namespace-units`.

### Why it is kept rather than deleted

The reasoning survives even though the mechanism does not: it is what first
named the actual problem precisely — a prebuilt runtime whose builtins are
all reached through one interpreter dispatch table is a runtime where a
linker can prove *nothing* dead, because every builtin is live by
construction through that one indirection. Every later design in this area
(`namespace-units`, `no-runtime-linking`) is answering exactly this problem,
so the statement of it is worth keeping even though both of this file's own
proposed answers lost.

---

## namespace-units

**A namespace is a compilation unit, and linking composes them**
*(formerly `namespace-units`)*

**Ratified:** ☐ not signed off

**Status: shipped — but there is no `flint link` command; composition runs
inside an ordinary compile.** Checked 2026-09-12. `src/flint/link.cljc`
(`discover-units`, `check-abi!`, `plan`, `link-objects`, `compose`) resolves one
`<ns>.unit.edn` per namespace off the unit search path and runs `rust-lld
-flavor wasm --no-entry --gc-sections` over only the reachable units' `.o`
files; `units/` holds one unit per namespace (`flint.rt`, `flint.conc`,
`flint.data.{json,html,xml}`), built by `bin/build-units`. No `link` subcommand
exists in `cli/src/main.rs`, `bin/flint` or `lib/flint/cli.cljc`. Observed:
`./bin/flint :src … :fn m/main` on `(defn main [_] "hi")`, then `./bin/flint
inspect` on the result, prints `units: flint.rt` / `(+23 builtins)`, against
`(+192 builtins)` for the loader build of the same program — only the reached
builtins survive the link. One mechanism detail below is *not* how it was
built: the registry is assembled after the link, by reading the module's export
section and appending an element segment (`compose`), rather than by the
runtime walking a linker section at startup. The property it was for — no
hand-written table holding every builtin live — holds either way.

### What was decided

Every namespace compiles ahead of time into its own **relocatable wasm
object**. The compiler computes the reachable set from the entry function and
hands only those objects to `wasm-ld --gc-sections`, producing one linked
module. This is not primarily a tree-shaking trick — it is a **composition
system**, with built-ins simply being its first customer:

> a pre-compiled wasm module for each built-in namespace... If we have that,
> then we have a nice wasm composing system already in case we later want to
> support independently compiling namespaces.

### Why

Compiling per-namespace and linking is fast because there is no `rustc` on
the compile path at all — only a link step, milliseconds to low hundreds of
milliseconds — and it still produces a single module, so flint's "runs
anywhere, no host-side module wiring, no Component Model dependency" contract
survives untouched. This won over `modularity`'s two proposals directly:
rebuilding with cargo features is correct but slow; nulling dispatch-table
entries and running wasm DCE is clever but depends on a discipline
("everything calls through the registry, nothing calls a builtin directly")
that nothing enforces and cannot be retrofitted once violated.

**The crux, easy to get wrong: the registry must be assembled *by the
linker*, not hand-written.** If the runtime holds one static table naming
every builtin, then every builtin is reachable from that table and
`--gc-sections` removes nothing — the table itself is the reference keeping
everything alive, which is precisely the failure mode `modularity`
described. So each namespace object contributes its *own* registration
entries into a dedicated linker section, and the runtime walks that section
at startup: link a namespace and its entries appear, leave it out and they
simply do not exist. The linker decides what the registry contains, not a
hand-maintained list.

The same shape applies one tier up for the cljc standard library: each cljc
namespace precompiles to a bytecode image fragment, and only the reachable
fragments are concatenated into the final program image — otherwise every
invocation would recompile `clojure.core` from source on every run.

**The unit format was deliberately designed for the general case from the
start**, rather than baking in "this is for built-ins": a namespace unit is
described by what it *is* — its compiled artifact, the symbols/vars it
exports, the units it depends on, compatibility metadata — never by who
shipped it. That is what let independently-compiled user namespaces,
incremental builds, and distributable precompiled libraries become the same
feature later rather than a rewrite; it cost almost nothing to keep the
boundary honest from day one, and nothing here required building user-
namespace compilation immediately, only not making it impossible.

---

## exclude-and-unit-path

**`:exclude` as an assertion, and `:wasm-path`**
*(formerly `exclude-and-unit-path`)*

**Ratified:** ☐ not signed off

**Status: shipped** — on `bin/flint`, the front end that links; the shipped
`target/release/flint` carries no linker (`no-runtime-linking`) and its
`compile` takes neither flag. Verified 2026-09-12 by running both. `:exclude
[flint.data.json]` against a program calling `json/write-str` failed the
compile with the chain — `flint.main/-main -> j/main ->
flint.data.json/write-str`, plus the six other reachable vars — so it is the
assertion described below, not a pruning (`check-exclusions!`,
`src/flint/compiler.cljc:684`). `:wasm-path <dir>` over a copied unit tree
linked and printed `note: unit flint.rt at units/flint/rt.unit.edn is shadowed
by <dir>/flint/rt.unit.edn` for each of the five, so `units/` really is the last
entry on the path; editing that copy's `:abi` to `{:runtime 2 …}` got `refusing
unit flint.data.json …: runtime 2 (need 1)` (`abi-problem`/`check-abi!`,
`src/flint/link.cljc`). (The flag
was originally proposed as `:wasm-ld`, renamed to `:wasm-path` in
`host-abi`, because "flags to pass to wasm-ld" is not what it means — "where
to find precompiled units" is.)

### What was decided

Two CLI options, both exposing `namespace-units`' composition system to
users directly. `:exclude [ns ...]` drops namespaces — including built-in
ones — from a compile; `:wasm-path <dir> ...` is a search path for
precompiled namespace units, resolved by namespace the same way source
resolution works (`flint.data.json` → `<dir>/flint/data/json.*`).

### Why `:exclude` is an assertion and not a suggestion

The obvious reading of "leave these out" has a trap in it: if the excluded
code is genuinely reachable, silently leaving it out produces a module that
compiles, links, ships, and dies at runtime on a path nobody tested — a build
flag whose failure mode is a production crash is a bad flag. So an exclusion
is a **claim the compiler checks**: `:exclude [foo.bar]` means "nothing
reaches `foo.bar`; tell me if I am wrong." If it *is* reached, that is a
compile error, not a silent omission — and the error must show the full
reference chain (which namespace required or called it, and from where), or
it is unactionable. Reachability is already computed for linking, so this is
mostly bookkeeping to remember *why* each namespace is in the reachable set,
by keeping the predecessor edge that makes the chain printable.

That framing is what makes the flag genuinely useful rather than merely
restrictive: it can *guarantee an absence* ("this module must not contain an
XML parser," enforced by the build rather than eyeballed by a reviewer), help
*find what is dragging something in* (exclude it and read the chain), and
*keep a module small on purpose* with a build failure if a later refactor
quietly reintroduces the dependency.

### Why `:wasm-path` needed three things settled, not just built

Precedence when a namespace is available as both source and a precompiled
unit needs a stated answer (either is defensible; silence is not).
Compatibility — a unit built against a different runtime ABI or image layout
— must be refused by name and version rather than linked and left to crash,
using the compatibility metadata `namespace-units`' unit format already
carries. And built-in namespaces should go through the *same* path as
user-supplied units, so `units/` is just the default entry on the search
path rather than a second mechanism — which means the user-supplied case is
exercised by every ordinary compile, not just a special test.

---

## module-metadata-and-shards

**What a module says about itself, and shards**
*(formerly `module-metadata-and-shards`)*

**Ratified:** ☐ not signed off

**Status: part 1 shipped** — every module carries a `flint` custom section,
readable from the bytes without instantiating, and `flint inspect` prints
it. **Part 2 (shards) is not built** — the hard part, deciding which
namespaces may be privately bundled versus which must be imported, remains
unsolved work rather than solved-and-unbuilt. Verified 2026-09-12: the section
is written by `src/flint/link.cljc` last of all (`modmeta/describe`,
`src/flint/modmeta.cljc`) and read from the bytes by `bin/flint inspect` and
`host/modmeta.mjs`. Observed on a module from each front end — `./bin/flint
:src … :fn m/main` and `target/release/flint compile … :to :wasm` — `./bin/flint
inspect` printing `flint 0.1.0`, `compatibility key 6cebaa00   abi {:runtime 1,
:value 1, :image 1}   unshared`, then present/absent features, units, exports
and imports. For part 2, "shard" appears in `src/`, `lib/`, `cli/`, `host/` and
`bin/flint` only in prose and citations; nothing classifies a namespace into
`:provides`/`:bundles`/`:requires`.

### What was decided

Two things, requested together but genuinely different in kind:

1. **Build-time opt-ins go in the module's own bytes**, in a wasm custom
   section readable without instantiating, so a runner handed a finished
   `.wasm` can decide how to wire itself up and whether it is even
   compatible, before running anything.
2. **The compiler should be able to build a "shard"**: an entry-point
   namespace compiled into a self-contained, loadable *library* wasm module
   — no runtime implementation of its own, leaning entirely on a resident
   program module's.

### Why the custom section is not just a repackaging

`namespace-units`' unit format already has a compatibility check — an
`:abi {:runtime :value :image}` map checked before every compile, refused by
name and version on mismatch — but it lives in a sidecar `.unit.edn` file
consumed by the *compiler* at link time. Nothing is carried in the wasm
itself, so a *runner* handed a finished module can learn nothing from it
without a side channel. wasm custom sections are ignored by every engine and
readable straight from the bytes (`WebAssembly.Module.customSections()` on
the web), so putting the same facts there — generated from the manifest
rather than maintained beside it — closes that gap, and putting the section
*early* in the module lets a streaming reader decide whether to instantiate
at all before downloading the whole code section.

**The section also gains a free-form metadata map the runtime does not
interpret.** What a key in it means is between whoever wrote it and whoever
reads it — a runtime that knew what `:capabilities` meant would have taken a
decision that belongs to its caller. The CLI's own convention (`cli`,
`workspace-capabilities`) is to write `{:capabilities [...]}` there from
`:with`, and read it back on `run` — but that is the CLI's convention, not a
rule the format enforces.

### The trap: two classes of fact, and conflating them breaks diagnostics builds

The obvious implementation — one blob of build configuration compared for
equality — is wrong in a way that only shows up once shards exist.
**Compatibility keys** must match exactly or a module cannot load at all:
the ABI, whether the program uses shared memory (`thread-pool`), whether gas
metering is compiled *into* AOT'd code (since that changes what emitted code
calls, not just what it reports), the heap layout a shard's code assumes.
**Capability descriptors** are facts a runner inspects to wire itself up and
must *not* gate compatibility: which host imports are required, whether
diagnostics/snapshots/profiler are present, what the module exports.

**Conflating them means a diagnostics build invalidates every shard for no
reason** — turning on `two-builds`' instrumented build only adds side
tables, changing nothing a shard's code actually depends on, but a flat
equality check over one config blob would see it as an incompatible change
and rebuild the world. So the compatibility key is a hash over only the
ABI-affecting subset, with everything else carried descriptively beside it —
a hash rather than a hand-maintained version number, because a version
number gets bumped by memory and a layout change forgets to. `two-builds`'
two builds are the concrete case that proves the key is drawn correctly:
they differ only in diagnostics and must remain shard-compatible with each
other.

### Why shards are genuinely new, not a repackaging of a unit

Today's units are **link inputs**, consumed and absorbed at build time by
`wasm-ld`; they are never themselves loaded. A shard is a **loadable module
that resolves against an already-running program** — a fundamentally
different artifact, not a repackaging of the existing one. It cannot own its
own linear memory (values live in the program's heap, so a shard's code has
to *import* memory rather than define it) and should carry no static data
segments of its own, building any constants at init time through the
program's own allocator instead — which is exactly the shape that produced
one of this codebase's real GC bugs (a value held across a module
initialiser that allocates), so shards inherit that hazard and its standing
test on day one.

**The genuinely hard problem: "self-contained" conflicts with identity.**
Bundling pure code privately into a shard is fine. Bundling anything carrying
identity or mutable state is not: a protocol is identity (a type extended to
*this* protocol object satisfies *this* one), and if a shard privately
bundles a namespace defining a protocol while the host program has its own
copy, there are now two protocols with the same name and dispatch silently
diverges — a value extended on one side fails `satisfies?` on the other, with
no error, just a wrong answer somewhere downstream. The same argument covers
namespace-level vars and atoms. So the rule is: **a shard may privately
bundle pure code; it must import anything carrying identity or mutable
state** — which means the manifest and custom section both need three lists
rather than one (`:provides` — namespaces defined canonically, an error if
two shards both claim one; `:bundles` — namespaces safely duplicated
privately, declared so the duplication is auditable rather than implicit;
`:requires` — namespaces that must come from the program, because a second
copy would be a second identity). The compiler already knows, for any given
namespace, whether it defines a protocol or top-level mutable state — **that
classification is the actual remaining work**; the module format around it
is comparatively straightforward.

---

## no-runtime-linking

**No linking at compile time; byte strings and transient ropes**
*(formerly `no-runtime-linking`)*

**Ratified:** ☐ not signed off

**Status: partly built — checked directly, not merely repeated.** `flint.bundle`
splices an image into a prebuilt module and `flint.shake`/`flint.wasmshake`
cut it down, both without a linker, both measured. This decision's own file
banner says byte strings and their transient are built; `runtime/src/bytes.rs`
is 552 lines, confirming it. **The old project status index disagreed with
this file about its own decision** — its table row for this entry claimed
"the byte strings do not exist," which is simply wrong, and is exactly the
kind of adjacent-source contradiction the swarm verifying this document was
told to expect. Still not built: emitting a module from `flintc.wasm`
itself, and the embedded JVM/CLR targets — unverified independently, stated
as the record has it.

### What was decided

**`wasm-ld` runs exactly once — when flint itself is built. Never when a
user program is compiled.** flint's own build produces one prebuilt runtime
module per target, carrying every builtin, linked once. The compiler embeds
those modules as data. Compiling a *program* becomes: source → bytecode
image → **splice** the image into the embedded runtime — no link step at
all. `flint compile wasm` gives an interpreting module; `flint compile
wasm-aot` appends compiled arities by byte manipulation, which needs no
linker either, since wasm cannot add a function to an already-linked module
by any means *but* appending bytes.

### Why

The owner's direction: *"drop the concept of loading/linking external wasm
stuff... The goal is to get rid of runtime linking within flint altogether,
offload it to the runtime where possible."* This gives up something real —
per-program linking via `namespace-units` tree-shakes builtins a program
never reached, where a prebuilt runtime necessarily carries all of them
(measured: 219,726 bytes linked for a trivial program versus 573,959 bytes
for a prebuilt runtime with all 166 builtins) — but `bin/flint` keeps the
linking path available for anyone who wants the smaller artifact; it is no
longer what `compile` *means* by default.

**The one real blocker turned out to be self-hosting, not design.**
`flint.wasm` — the binary wasm reader/writer everything above depends on — is
written against Java byte arrays (`aget`, `alength`,
`ByteArrayOutputStream`), which does not compile *under* flint itself. The
tempting fix, "a flint vector holding ints," is wrong and was rejected
outright: a flint vector holds NaN-boxed 64-bit values, so a 574 KB module
would become 4.6 MB of vector payload plus trie overhead just to represent
bytes.

**So byte strings got the same rope treatment text already had**
(`strings-and-matching`): flat for small byte strings, a shallow B-tree rope
above a threshold, simpler than the text rope in one respect — a byte node
only needs its subtree's byte length, no code-point count to sum and no
ASCII bit to track. **And a transient was needed for bytes too, which
canonical Clojure never has a reason to want.** flint's strings/byte-strings
are trees with a flat-copy threshold below which concatenation *copies*
rather than building a node — so building one incrementally (`(str acc x)`
in a loop, or the equivalent for bytes) is quadratic in bytes copied until
pieces outgrow the threshold, which is exactly what nobody expects a
persistent rope to do. Measured on 20,000 pieces building an 88,890-
character result: repeated `str` cost 8.7 ms and 2.1 MB of allocation for
the same 19,995 allocation *count* as collecting into a transient and
joining once (0.9 ms, 0.7 MB) — the count being equal is what pins the
cause as bigger objects, not more of them: the same bytes were being copied
again and again. A transient tail buffer that appends in place and promotes
into the tree only when full removes the quadratic, exactly as it already
does for vectors.

**Tree-shaking does not need a linker either, and this was assumed lost and
was not.** Shaking is a mark-from-roots pass over a call graph followed by
removing what was not marked, and none of that inherently needs `wasm-ld` —
it is a pass over a module that already exists, and the mark phase is
**target-independent**, worth building once rather than per-target: a call
graph is a call graph whether it came from a wasm code section, a JVM
constant pool, or a CLR metadata table. It can even be *more* precise than
the linker, because by the time shaking runs the image exists and the exact
set of builtins it imports is a known fact rather than a conservative guess.
Two pragmatic corners, both proven safe rather than merely convenient: the
call-edge scan is conservative (scans for the `call` opcode byte rather than
fully decoding the instruction stream, which can only invent an edge, never
miss one — safe in the direction that matters), and dead functions are
**stubbed with `unreachable`, not deleted**, since a wasm function index is
positional and deleting one would renumber every call site, table entry and
export after it — an `unreachable` body keeps every index valid while still
dropping the code section bytes, which are 89% of the module and nearly all
of the actual prize. Measured on a program using `clojure.string`, `reduce`
and `filterv`: shaking recovered 57% of what `wasm-ld` removes with no
linker at all, 79% on a trivial program.

**A shaking-under-load bug found a real bug in `clojure.core`, not in the
shaker.** At scale, shaking traps inside the self-hosted compiler; a named
backtrace (`bin/flint --keep-names`) traced it through `apply` — reached
because `for` compiled to `mapcat`, whose variadic `concat` recursed one
`apply` frame per argument, *before realising any of it*, so a `for` over
600 items became 600 nested frames. `mapcat` and `concat`'s variadic case
were rewritten to be properly lazy/iterative, a defect that had nothing to
do with wasm and had been costing every `for` over a large collection.

---

## strings-and-matching

**Rope strings, and what to do about regex**
*(formerly `strings-and-matching`)*

**Ratified:** ☐ not signed off

**Status: shipped** for sections 1–2 (ropes); **section 5's conclusion is
superseded** by `matching-over-ropes`, below, which is itself shipped. This is
one of the most heavily cited decisions in the codebase and is treated at full
length here for that reason. Verified 2026-09-12. The three tiers are real and
named: inline in `runtime/src/strs.rs`, flat and the B-tree in
`runtime/src/rope.rs`, which fixes `FLAT_MAX` 1024, `FANOUT` 16 and `SLICE_MIN`
256 and stores per-subtree counts rather than absolute offsets. Observed
through `target/release/flint run`: a 2,490-byte string built by repeated `str`
answers `kind` `:string`, gives the right `subs` and `nth`, and is `=` to the
same content built another way — one interface over the tiers. `TY_ROPE`/
`TyRope` is 42 on all four runtimes (`runtime/src/obj.rs:77`,
`runtimes/jvm/…/Obj.java:69`, `runtimes/clr/src/rt/Obj.cs:26`, nine generated
`Rope*` files per port). The "count the flattens" discipline is a real counter
with a gate: `Rt::flatten` in `rope.rs` and `bin/check-flattens`.

### What was decided

Strings are a **three-tier representation** — inline (packed into the value
word itself, no allocation), flat (a contiguous byte array with cached
counts), and rope (a balanced B-tree of flat pieces) — presenting one
interface, with `(kind s)` answering `:string` for all three. Regex is kept
as the language surface (no move to PEG), but the *matching engine*
underneath it changes; see `matching-over-ropes`.

### Why ropes, and why three tiers rather than two

A rope — a tree of string pieces with structure sharing, tiny strings
inlined directly in the value — is not exotic (Boehm/Atkinson/Plass, 1995;
V8 ships one today as `ConsString`), and it fits this language unusually
well: `str` becomes O(1) (a cons node, where flat strings make repeated
concatenation O(n²), and Clojure code concatenates constantly), `subs`
becomes O(1) (a slice node over the parent), and sharing is safe *because
flint values are immutable* — the same property that already made passing
ports by reference sound. The cost is that random access becomes O(log n),
which matters less than it sounds for UTF-8, since indexing by code point
was never O(1) to begin with.

A rope is the wrong answer for `"ok"` — tree metadata would dwarf the
content, and most strings in a real program are short — so there are three
tiers rather than two, with **flat as the tier that must not be skipped**:
between "fits in the value word" and "big enough to want a tree" is most of
the strings a program actually touches. The transition rules are also the
retention fix: a small `subs` of a large rope *copies* into flat or inline
rather than creating a slice node, because a slice node would otherwise
retain the entire large parent through a three-byte view — tiering and
retention turned out to be one problem, solved by the same rule.

### The engineering that makes ropes actually work, not just exist

**It must be balanced, and that is not a refinement — a naive cons-rope
degenerates immediately.** `(reduce str "" xs)` builds a right-leaning spine
of depth n, making `subs`/`nth` O(n) — *worse* than the flat string it
replaced, at exactly the operation ropes exist to make cheap. The design
takes a **B-tree rope with size tables** (wide nodes, shallow tree — at
fanout 16–32 a megabyte string is two or three levels deep, near-random-
access in practice) over classic Boehm rebalancing, because it is the same
technique the RRB vectors already in this codebase use — a sibling
structure rather than a new idea. Parameters worth defending explicitly:
fanout 16–32 (depth is what random access pays for), leaves of ~512–1024
bytes rather than per-fragment (tiny leaves make the tree deep and let
metadata dominate content), and merging adjacent small leaves on concat (or
a thousand two-character appends produce a thousand leaves and the balance
invariant erodes by increments).

**Each node carries a code-point count, not just a byte length**, because
flint stores UTF-8 and indexes by code point — without a per-node count,
indexing means scanning, making `nth` and `count` O(n) on a structure built
specifically to make them cheap. Composing a node must never rescan its
children's bytes, and it does not have to: a concatenation is all-ASCII
exactly when every part is (an AND), and the code-point count is a sum —
both compose in O(fanout), so the byte scan happens exactly once, at leaf
construction, bounded by the leaf size, and every composition above that is
pure arithmetic. There was already room for this in the value header: the
`TY_STR` header has four unused bytes between the hash and the data, and bit
18 was already an ASCII flag.

**Counts must be relative, never absolute offsets — this is the one detail
that would be expensive to unwind.** A node stores the size of its own
subtree, never its start index in the whole string, because structure
sharing is the entire point: the same leaf can appear in two different
ropes at two different offsets (`(str a b)` and `(str b a)` share `b`, at
offset `(count a)` in one and offset 0 in the other), so a node recording an
absolute position would be correct in at most one of them. Absolute position
is instead computed during descent, accumulating child counts on the way
down — the standard B-tree-with-size-tables shape, forced here rather than
chosen.

**The ASCII flag's justification changes per tier, and both mechanisms stay
needed rather than one subsuming the other.** With per-node counts, the flag
buys nothing for indexing *across* a rope — descending the tree already
locates code point *k* in O(log n) regardless. But a flat string carries
only one total count, so without the flag `nth`/`subs` on a flat string is
O(n) (this is precisely what the `words` benchmark exercises); and even
inside one rope leaf, finding a byte offset for a code point is a scan
bounded by leaf size, which the flag turns into O(1). So: not needed for
rope-level indexing, still needed for flat strings, useful-but-bounded
inside a leaf. **The flag is also a cached, derivable property, and that
makes it capable of being wrong** — anywhere a `TY_STR` is allocated without
deriving it correctly, `nth`/`subs` silently use byte offsets as code-point
offsets and return wrong answers with no error at all. The diagnostics build
re-derives the bit on every read and asserts agreement, the same technique
that separately proved a different write-once-field mechanism correct
across 8,302 crossings with zero drift.

**Operations must actually use the tree structure, and this is not
hypothetical — it is the shape of a real, already-fixed bug.**
`str_index_of` once called `is_ascii()`/`from_utf8` on every call, each
scanning the *whole* haystack, turning a linear scan into 223 million byte
checks and 37 ms of a 55 ms benchmark — a builtin that looks native and
therefore free, silently doing O(n) hidden work per call, is exactly the
failure mode a flatten-on-demand rope invites everywhere unless it is
actively guarded against. What must genuinely use the structure: `str`/
concat (a tree join, O(1) or O(log n), never a copy); `count` (O(1) from
stored counts); `nth`/`subs` (descend, sharing subtrees for a large slice);
`index-of`/`split`/`replace`/comparison (walk leaves through a cursor — none
of these needs contiguous bytes); `starts-with?`/`ends-with?` (one leaf at
each end). **The discipline is to count the flattens, not hope about them**
— a diagnostics counter incremented on every materialisation, asserted in
benchmarks, because a rope that silently flattens on every `index-of` passes
every correctness test while being slower than the flat string it replaced.

**Two correctness requirements that are easy to miss entirely.** Equality
and hash must be independent of *representation*, not merely of tree shape:
`"abc"` inline, flat, and as a rope are one string, must compare `=`, hash
identically, and be found in a map by any of the three forms — getting this
wrong produces map behaviour that silently depends on how a key happened to
be built, and (since content-addressed artifacts depend on stable hashing)
would make hashes differ across hosts, which `other-hosts` separately flags
as not cosmetic. And a rope must never retain a huge parent through a tiny
slice — `(subs big 0 3)` holding `big` alive is a memory leak with a
plausible-looking cause, closed by the copy-small-slices rule above.

### Regex: why the syntax stays and the engine does not

The instinct to abandon regex for PEG is diagnosed as right in spirit and
wrong in the specifics. flint's regex feature set is already the safe
subset — `lib/flint/regex.cljc` refuses lookahead, lookbehind,
backreferences, and named groups, which is roughly RE2's subset and is
exactly the subset matchable without backtracking at all. **The actual
hazard is that the implementation is a backtracker regardless of the
subset**: even with no backreferences, `(a+)+b` is exponential in a
backtracking engine.

The measured 275× slowdown against JS on a regex split decomposes cleanly
once broken apart: a literal (no-regex) split was 18× slower than JS — the
flat cost of running as interpreted bytecode at all, not recoverable except
by native code — while the regex split was 275×, meaning the **engine
inefficiency on top of interpretation was roughly 15×**, and that 15× *is*
recoverable by a better engine written in cljc. That decomposition is what
correctly redirects the fix from "abandon regex" to "replace the matching
engine, keep the syntax," which is exactly `matching-over-ropes`'s subject —
regex `split`/`replace`/`re-find`/`re-seq` all keep working for every ported
program, nobody has to learn a new notation to split on a comma, and PEG
remains worth having later as a *complement* for genuinely structured input
regex cannot express (recursion, balanced delimiters), never as regex's
replacement.

### What is portable across hosts, and what is a port's own business

UTF-8 representation itself is not a cross-host problem — a byte array with
code-point semantics on top is straightforward on the JVM and CLR too. What
*is* the specification, non-negotiably, is **behaviour**: `count`, `subs`,
equality, hashing, and dispatch must answer identically on every host, while
*how* a short string is packed into a value word is each port's own
optimisation (a reference cannot be NaN-boxed on a managed runtime the way
it can natively, so each port approximates its own way). This is
`other-hosts`'s rule restated for the case most likely to drift silently: the
conformance suite is the specification, and representation is precisely the
kind of thing it must never be able to observe.

---

## matching-over-ropes

**The matcher must consume a rope, which decides the whole design → a Pike VM**
*(formerly `matching-over-ropes`)*

**Ratified:** ☐ not signed off

**Status: shipped.** One shared NFA compiler with two simulators — a cljc
reference and a native one — over a rope cursor. `re-pattern`, `re-find`,
`re-matches`, `re-seq` all run on it; the catastrophic-backtracking case
stays linear (measured: `(a+)+$` over 24 and 48 characters, 37 ms and 38 ms
— i.e., not exponential). Verified 2026-09-12. All three parts exist:
`lib/flint/nfa.cljc` (the shared compiler), `lib/flint/pike.cljc` (the cljc
reference), and the native simulators — `runtime/src/pike.rs` plus the
generated `Pike.java`/`Pike.cs` — which `lib/flint/regex.cljc` reaches through
the `flint.rt/re-compile` and `flint.rt/re-run` builtins (l.295, 322, 333, 391),
not through the cljc one. Observed through `target/release/flint run`:
`re-find` with capture groups, `re-matches` matching and returning nil,
`re-seq`, `re-pattern`, `str/split` and `str/replace` all answer correctly. The
linearity was re-checked at the binary's own boundary rather than re-deriving
the 37/38 ms figures: `(a+)+$` over 24, 48 and 2000 `a`s cost 1.62 s, 1.60 s
and 1.75 s of total wall clock (compile-dominated, hence flat) — a backtracker
would not have returned at 48.

### What was decided

Supersedes `strings-and-matching` §5's conclusion (delegate matching to host
regex engines with a shared normalisation pass). Instead: **flint owns the
matcher, built as a Pike VM (Thompson NFA simulation) over a rope cursor,
shared across every host as one compiled NFA program with a per-host native
simulator.**

### Why the delegation plan died on contact with ropes

The objection that forced the reversal: *"It won't work over our rope
strings though, the stock regex engines for jvm/clr right?"* — correct, and
it undoes "flatten before matching" as a strategy, because flattening to
hand a `String` to `Pattern`/`Regex` materialises the whole rope on every
host that delegates, at exactly the operation that touches the most text and
therefore has the most to lose from a rope given back. A short survey of
whether host engines can even consume an abstraction confirms delegation was
never viable: Java's `Pattern.matcher` takes a `CharSequence` in principle,
but matching is random-access and backtracking-heavy, so every `charAt`
becomes O(log n) and `CharSequence` is UTF-16 against flint's UTF-8 storage
— a trap, not a solution. .NET, JS, and Rust's own `regex` crate all
categorically require contiguous memory. **So if ropes are the
representation, flint controls the matcher — that is settled by the data
structure, not by preference.**

### Why a Pike VM specifically, and why this also settles PEG vs regex

The real question was never "which notation is nicer" — it is **which
matcher can consume a rope without rewinding**, since rewinding is
precisely what a rope is structurally bad at. A backtracking matcher
rewinds constantly (this is what the current engine, and Java/.NET/JS
internally, all do). **PEG also rewinds** — ordered choice *is*
backtracking: try an alternative, fail, restore position, try the next — so
PEG has exactly the property that hurts here, which reverses the intuition
that PEG would be the "safer" choice. A **Pike VM** (Thompson NFA
simulation) never rewinds: one left-to-right pass, a set of live threads,
each character consumed exactly once. It needs nothing from its input but
`next-character`, so it runs natively over a rope cursor with no flattening
and no random access — not merely compatible with ropes, but the shape a
rope actually wants. It also keeps everything the project needed from
regex: linear time by construction (the ReDoS hazard is gone rather than
mitigated), exactly countable for `resource-limits`' gas accounting (a step
*is* a thread-step), unchanged syntax, and — on the wasm host — agreement
with Rust's own `regex` crate *by construction*, since that crate is the
same algorithm over the same subset.

### Why the native simulator was the point, not an optional extra

The owner's course-correction here is worth keeping verbatim as reasoning:
a cljc-only reference simulator is interpreted, so it pays the 18×
interpretation tax `strings-and-matching` measured, landing around 25–30×
slower than JS after recovering the 15× engine-inefficiency component —
*better* than 275×, but still disqualifying for the annotator-shaped
workloads this project cares about. **The native simulator is what actually
reaches parity while still consuming a rope** — the one combination nothing
else offers, since host engines and the Rust crate are fast but need flat
buffers, and a cljc simulator reads a rope but is slow. So the shipped
design is genuinely three parts: a shared cljc pattern compiler (parse, build
the NFA, emit a program — runs once per pattern, cached, so it need not be
fast), a cljc reference simulator (the conformance oracle, and what a brand
new host runs on day one before it has its own), and a native simulator per
host reading through a rope cursor (a few hundred lines each; the fiddly
part is capture-group tracking, for which Russ Cox's writing on Pike VMs is
the reference). A consequence worth stating: this makes the rope itself
load-bearing in the Rust runtime rather than incidental, since the native
simulator depends on it existing there.

### The compiled pattern is a cached value, and caching had a determinism trap

A `TY_REGEX` object holds the compiled NFA and is interned on `(source,
flags)` in a **weak** table — the same mechanism strings and keywords
already use — so two `(re-pattern "abc")` calls share one object, and an
unreferenced dynamic pattern (built in a loop from a fresh source string
each time, e.g. `(re-pattern (str "^" prefix))`) is collected rather than
leaking forever. A `#"…"` compile-time literal is different and is meant to
be: it lives as a constant for the module's whole life, strongly rooted by
construction, which is correct rather than a leak.

**The trap: caching threatens gas determinism in a way that is easy to
miss.** `resource-limits` made the instruction budget deterministic
specifically so the same program reports the same count on every machine —
but *whether a compile happens* now depends on *whether a collection
happened to run*, which itself depends on unrelated allocation history. So
gas is charged at every `re-pattern` call, cache hit or not, priced on the
compiled program's size — the cache then saves wall-clock time and never
moves the instruction count, which also closes the exact hole
`resource-limits` names in its own terms (a native whose cost is not O(1)
must charge for what it actually did). A second, related hazard is bounded
the same way: counted repetition like `(a{100}){100}` is a tiny pattern
source producing an enormous NFA — a memory-exhaustion path with an
innocent-looking source, so the compiled program size is bounded and a
pattern that would exceed it is refused by name rather than left to run out
of memory.

---

## tables

**Columnar storage that is a value**
*(formerly `tables`)*

**Ratified:** ☐ not signed off

**Status: shipped.** The banner read "partly built — steps 1–6 of 9", naming
the transient, the column API, the codec/reader tag and the JVM/CLR ports as
not built. All four are in the tree, and the body of this section already
describes three of them as done — the banner was stale, not the content.
Checked 2026-09-12, by reading and by running. In `lib/flint/table.cljc`:
`build` is `transient`/`conj!`/`persistent!` (l.157), `column`,
`reduce-column`, `slice` and `select` are the column API (l.174–213), and
`read-table` (l.218) is bound to `#flint/table` in `src/flint/reader.cljc:526`.
A probe run through `target/release/flint run` on a 300-row table answered
`(reduce-column t :a + 0)` = 44850, `(count (slice t 10 20))` = 10,
`(count (build S (range 50) f))` = 50, printed
`#flint/table {:schema [[:a :int] [:b :string]] :rows [{:a 1, :b "x"}]}`, and
read that form back `=` to the original. The wire tag is `K_TABLE = 18`
(`runtime/src/codec.rs:66`, `runtimes/jvm/…/Codec.java`,
`runtimes/clr/src/rt/Codec.cs`). The ports are `runtimes/jvm/src/com/flint/rt/Table.java`
and `runtimes/clr/src/rt/Table.cs` over the generated `kgen`
`Table*` files (`kin/table*.kin`; commit 2bfa4fc, "`Table` is generated"), and
`tables` is in `bin/conform-hosts`' three-runtime suite list (l.762) — the
ports are checked here by code and by that listing, not by running the gate.
This decision is heavily cited across the runtime and is treated at length.

### What was decided

A table is a **vector of maps from the outside** — `(get t 0)` gives
`{:a 1 :b 2}`, `count`/`assoc`/`update` all work exactly as on a vector of
maps — backed by **columnar chunks in a persistent trie, with a schema fixed
at construction (a closed schema).**

### Why: closing the schema is the whole design

A vector of maps stores every key in every row — ten thousand rows of
`{:a int :b int}` is ten thousand map objects, twenty thousand key
references, and a hash lookup per field access. The same data stored by
column is two arrays and an index, and scanning one field touches one
array — Arrow's argument, not new, except that here it has to be a genuine
**persistent value**: structurally shared, `=` by content, safe to hold
across a collection, which Arrow (a mutable buffer with a schema) is not.
This is flint's own persistent trie with columnar leaves, the same
technique its vectors and maps already use for the same underlying reason.

**Closing the schema is what makes the rest of the design fall out cleanly
rather than requiring case analysis.** An earlier draft spent real effort
deciding what to do about ragged rows, a null bitmap for absent fields, and
an overflow map for keys outside the schema; closing the schema — column
names and each column's type are fixed at construction — deletes the
question entirely rather than answering it: `assoc` to an unknown key is
rejected, naming the key and which columns actually exist; `assoc` with a
value of the wrong type is rejected, naming the column, its declared type,
and what was given. These are ordinary runtime errors, not the optional
`#?(:flint/check ...)` mechanism of `checks` below — a *closed* table that
silently accepted a bad row in a release build would not actually be
closed. What they do borrow from `checks` is the *quality* of the message
(expected, actual, and which column) rather than a class-cast exception
three frames removed from the mistake. And because the schema is closed, a
column's encoding can be chosen freely per chunk — collapsed to a single
constant, run-length encoded, dictionary-encoded — entirely invisibly to
`get`, because there is no possibility of an unexpected shape arriving that
would force the encoding to be re-derived.

### The shape, and why a row is a reference rather than a map

The persistent vector trie already exists and already path-copies for
`assoc`; a table reuses that exact trie with a different leaf — a **chunk**,
an array of column objects, each one uniform (an unboxed `i64` run the
collector never traces, or a `Vals` array traced normally) — so no new tree
had to be written. Columns are addressed by a **stable id, not position**,
which is what makes migration cheap (below) at the cost of one indirection.

**Iterating a table yields table refs — schema, chunk, row index — not
materialised maps.** A ref behaves as a map for every purpose (`get`,
keyword lookup, `count`, `keys`, `vals`, `seq`, `contains?` all work,
`map?` is true, `kind` is `:map`, so code that does not know it is holding a
table keeps working — the same "many kinds answer one `kind`" pattern
`strings-and-matching` already established for its three string tiers) and
equals/hashes identically to the equivalent literal map. It does **not**
hold the table — only the schema and one chunk — so retaining one row out of
a million-row table retains one chunk, not the table; and because chunks
are themselves persistent, a ref can never dangle: a later `assoc` on the
table path-copies rather than mutating the chunk a ref is looking at, so the
ref keeps seeing exactly the row it was made from. `assoc` on a ref produces
an ordinary map (a ref is a *view*; changing it makes an independent value,
touching neither the chunk nor the table). This also quietly deletes a
special case an earlier draft required: `get-in` needed special-casing to
avoid materialising a row, but since `(get table 0)` already materialises
nothing via a ref, the general path already is the fast path.

### Why a table is not `=` to the vector-of-maps it prints as

`(= table [{:a 1}])` is **false** — a table is its own distinct kind of
value, and this was a real, reconsidered decision rather than an accident:
refusing that equality is what lets `hash` be genuinely columnar (hashing a
million-row table can hash column *runs* rather than materialise a million
maps first), and an `:int` column and an `:any` column holding the same
numeric values are legitimately different tables, which vector-of-maps
equality could never express. `flint/table` is deliberately **not** a
reserved tag in the sense `tagged-literals` and `bridges` use that word —
reserved there means *confers authority* (forging `flint/port` fabricates a
claim on something); forging a table only fabricates data, which anyone can
already do by writing a literal, so it needs no special protection.

**The printer does not know about tables, and that decision saved real
bytes.** The first implementation branched inside the shared printer
function directly, and that branch alone cost 15,832 bytes in *every*
module that prints anything at all, because the shared printer is linked by
nearly every program and the branch's closure became a `call_indirect`
target the tree-shaker had to conservatively keep everything reachable
from. Making the table type implement the existing `Printable` protocol
instead — exactly the mechanism a protocol exists for — means a program
that never `require`s `flint.table` prints a table as `#<unprintable>`,
which is correct: such a program could never have built one in the first
place, and requiring the decoder (needed if a table can arrive over a
port) brings the printer in with it for free rather than as a separate cost.

### Migration: explicit, and usually startlingly cheap

A schema change makes a **new table**, never in-place evolution or
inference — `(migrate t new-schema)`, optionally with a per-row mapper.
Because chunks address columns by stable id rather than position, two of
the three cases are **head-only edits that share every existing chunk
unchanged**: removing a column just omits its id from the new schema
(measured: dropping a column from a 50,000-row table costs 218 gas, against
6,750,156 to rewrite every row — roughly 31,000× cheaper); adding a column
with a constant default is *also* head-only, because a constant column is
exactly the "collapse to one value" chunk encoding already described (a
20,000-row column that never varies costs 160,368 bytes less than one that
does — one 8-byte slot per row, entirely). Only adding a column computed by
a per-row mapper actually has to rewrite chunks, because those values
genuinely differ per row — and that cost is explicit and visible in the
API rather than something `assoc` could accidentally trigger.

### What the measurements say the type is actually for

The two numbers that justify the whole design, on a 40,000-row table with
build cost subtracted: scanning one field with `reduce-column` costs 200,011
gas and **32 bytes allocated**, against 2,764,077 gas and 2,912,152 bytes
doing the same sum by walking materialised rows — the scan touches chunk
runs directly and never builds a row at all. A `slice` of 38,000 rows costs
3,871 gas and 30,848 bytes, against 10,480,385 gas and 34,153,240 bytes to
rebuild the same range from scratch, because `slice` shares every chunk it
spans (the same "share large, copy small" discipline `strings-and-matching`
uses for rope slices, achieved here by construction rather than by a
threshold) and drops chunks outside the range so a slice does not retain
the whole table. `select` is simply a `migrate` to a narrower schema, so it
inherits the head-only sharing for free. And the transient, once built,
turned out to have a real surprise in it: appending 20,000 rows through the
persistent path allocates 49,061,464 bytes and collects 23 times, against
3,082,984 bytes and one collection through a transient — but a *full* open
chunk handed over uncopied on seal turned out to allocate *more* than the
bulk-build path it was meant to beat, which only the measurement (not the
design) revealed.

### The wire and print format changed once, because the first one could not read back

An earlier print form, `#flint/table [{:a 1}]`, could not round-trip: it
loses the schema's declared types, and types are half of a table's identity
(an `:int` column and an `:any` column holding identical values are
different tables). The corrected, tested form is
`#flint/table {:schema [[:a :int]] :rows [{:a 1}]}`, bound as a built-in
reader tag to `flint.table/read-table` (via the mechanism `reader-tags`
below provides), with the round trip actually *run* in the test suite
rather than merely claimed. The wire codec's table tag is **columnar, not
row-major** — schema, row count, then each column written out in full
before the next starts — because a row-major encoding would just be a
vector of maps with extra steps, forcing a receiver to rebuild a map per row
to read a single field, which defeats the entire point of the type. The
same commit that added the table wire tag also had to add a wire tag for
tagged literals (`tagged-literals`), because neither type had one yet and
without it both were confined to being image constants, unable to cross a
port at all — half of what each type existed for.

---

## tagged-literals

**A tagged literal is a value, not a map**
*(formerly `tagged-literals`)*

**Ratified:** ☐ not signed off

**Status: shipped.** `TY_TAGGED` on all four runtimes. Verified 2026-09-12:
the type number is 47 in `runtime/src/obj.rs:114` (native and wasm),
`runtimes/jvm/src/com/flint/rt/Obj.java:83`, and as `TyTagged` in
`runtimes/clr/src/rt/Obj.cs:39`, with `K_TAGGED = 17` in each of the three
codecs. Behaviour observed through `target/release/flint run`: `kind` is
`:tagged`, `map?` is false, it prints `#my.ns/thing 42`, `tag`/`form` read the
two slots back, `assoc` on `:tag` and on `:form` each return a tagged literal,
and `assoc` on any other key refuses with "a tagged literal has :tag and :form
and nothing else, so it cannot take :zzz".

### What was decided

`#my.ns/thing v` reads to its own heap type — `TY_TAGGED`, two slots, a
namespaced symbol and a value — rather than to a two-key map like
`{:flint/tagged 'my.ns/thing :flint/value v}`.

### Why a map was not good enough

**A tag is structurally ambiguous with a map in every format that has both
natively.** EDN and CBOR carry tags as a first-class concept, so a codec
meeting `{:flint/tagged x :flint/value v}` cannot tell whether it is looking
at a tagged literal that should round-trip as `#x v`, or an ordinary map
that happens to use those two keys — guessing by shape breaks round-tripping
in whichever direction is guessed wrong. **JSON loses the namespace
entirely**, since `#my.ns/thing` is a namespaced symbol and degrading it
through a string-keyed map either flattens the namespace into the name or
drops it, so the tag no longer identifies what it identified. And it made
the `bridges` forgery guard a **shape heuristic rather than a name check**:
refusing a guest value that would serialise to a reserved tag had to inspect
every map for two particular keys, where a real type lets the check be one
comparison on the tag symbol of a value that is unambiguously a tagged
literal, with no false positives.

`assoc` on `:tag` or `:form` preserves the type; on any other key it
**refuses, naming the two keys that exist** — the object is exactly two
slots, so the alternative (silently promoting to a map) would quietly lose
the taggedness, which is the kind of silent coercion this codebase refuses
everywhere else. `map?` is **false** and `kind` is `:tagged`, deliberately:
answering the map-lookup protocols is not the same thing as *being* a map,
and `kind` is the closed set protocol dispatch runs on
(`threads-and-ports`) — answering `:map` would make every
`extend-protocol :map` in every program silently start catching tagged
literals too.

### Later

The reader itself no longer *invents* a tagged literal for an unrecognised
tag — see `reader-tags`, immediately below, for why and what changed.

---

## reader-tags

**A reader tag is a name; the var it names is the identity**
*(formerly `reader-tags`)*

**Ratified:** ☐ not signed off

**Status (checked 2026-09-12 on BOTH front ends; holds): partly built.** An unknown reader tag in source is an error, as in
canonical Clojure. Tags are bound per project in `deps.edn` under
`:flint/tag-readers`, mapping a short tag name to a fully-qualified var, and
apply only to that project's own source roots. `#flint/table` is built in.
Not built: `reader-tag-of`, so a printer can ask what name the current
build bound to its own reader. How this was checked: a throwaway project
binding `{pt demo/point}` compiled and ran under `target/release/flint run`
(`point={:x 3, :y 4}`) and compiled under `bin/flint`; a sibling file using
`#nope/thing` was refused by BOTH with the same "no reader for the tag" error,
which lists `#flint/table, #pt` as what the project can read. `read-dispatch`
in `src/flint/reader.cljc:600` is the single site — `builtin-tags` (line 516)
holds `flint/table` and is merged with the workspace's at line 801, and both
front ends share this reader. `reader-tag-of` has no definition anywhere: the
only occurrences in `git ls-files` are in this file, `ROADMAP.md` and
`deck/cards/language.md`, all saying it is not built.

### What was decided

`#foo/bar form` in source used to build a tagged literal out of *any* tag at
all, whether or not anything had claimed it. That is now an **error**. A tag
is bound to a reader **var**, per project, in `deps.edn`, and the binding
applies only when reading that project's *own* source roots — a
dependency's tags are read under the dependency's own bindings, never the
requiring project's.

### Why inventing a value for an unknown tag was actively dangerous

It is not merely non-conformant with Clojure — a mistyped tag (`#inst` for
`#instant`, a namespace misremembered) used to read as a perfectly good
value and then fail somewhere else entirely, or silently do the wrong
thing, with the failure arbitrarily far from the typo that caused it. A tag
is a *request for a reader*, not permission to invent a value. `(tagged-
literal 'a/b form)` and `clojure.edn/read-string` with explicit `:readers`
both still work exactly as before and are the two things that should keep
working — what changed is only the reader's own default behaviour on an
unclaimed tag.

### Why a global registry (Clojure's answer) does not fit here

Clojure's `data_readers.clj` registry is global and classpath-rooted: two
libraries that both want `#inst` collide, a project cannot locally rename a
tag it finds too long, and a dependency's tags are in scope for your source
whether you asked for them or not (and vice versa) — one namespace of tag
names, everybody sharing it. **The fix is keeping the tag *name* and the
reader *identity* strictly apart**: a binding maps a short, convenient,
*not-necessarily-unique* name to a fully-qualified, therefore-unique var,
and the binding is scoped to one project's own roots. That makes using a
library's tag opt-in, renaming one purely local, and lets two libraries that
both want `#x` coexist without collision, since the root project can bind
one of them to `#y` while both readers stay reachable.

### Why printing has to ask the same question reading does, and the first draft got this wrong

The first draft assumed printing should always emit the *canonical*
(fully-qualified) tag, on the theory that an alias is purely a read-side
convenience — by analogy with `clojure.string` always printing under its
real name regardless of what a namespace happened to alias it as locally.
**That analogy only holds for globally-unique qualified names, and a bare
tag name is not one.** If library A prints values with `#x`, and the root
project has rebound A's own tag to `#y` locally because library B also
wanted `#x`, then a value of A's printing itself as `#x` produces a form
which, read back **in that very same project**, silently calls B's reader
instead — a plausible-looking wrong answer, which is worse than an
unreadable one. So printing must ask exactly the question reading answers:
*in this program, what name is currently bound to this reader?* The answer
is resolved once, at **build** time (`reader-tag-of`, not yet built, is
meant to resolve to a literal string at compile time, costing nothing at
runtime and adding no registry to the image — `tables` already paid 15,832
bytes once for an unconditional printing branch, and this is deliberately
avoiding a repeat).

### The rewrite must remember what it was, or errors point at generated code

`#x form` is **rewritten** to `(the-bound-var form)` at read time — the
reader calls nothing, needs no compiler in scope, and does not care whether
the var turns out to be a macro (which can fold the whole literal to a
constant at compile time, which is exactly what `#flint/table` wants) or an
ordinary function (an ordinary call, evaluated when that code runs). But a
rewrite that forgets its own origin reports errors against code nobody
wrote, so the emitted form carries `:flint/read-form` (the tag exactly as
written, as a `tagged-literals` value — so `pr-str` gives back `#x [1 2]`
character for character) and `:flint/read-var` (what it actually resolved
to), because the two errors that matter name different things: "no reader
bound to `#x`" names the tag, "the reader threw" names the var. This is
explicitly the same fix, one layer bigger, as an earlier bug where reader
conditionals used to relabel their expanded result with the position of the
`#?` itself, breaking every check failure inside a conditional's branches —
the rule that fixed that bug is the rule applied here: *a form that already
knows where it came from does not get relabelled by whatever it came out
of.* The rewrite also has to survive macro expansion (an expansion inherits
`:flint/read-form` from the form it came from, unless it already carries its
own), and costs nothing at runtime, since it is metadata on a form and forms
never ship.

### Why the SDK integration stalled, and what actually unblocked it

This file's own second listed step — "the SDK's equivalent of the
`deps.edn` key" — sat undone for a long time because the SDK had no notion
of a *project* at all, only a flat map from path to source with every file
a peer. The fix that landed was not a second, parallel option carrying
`{tag-name -> var}` (which this file's own first draft proposed, and which
would have been a second route to the same fact — precisely how the two
front doors, CLI and SDK, get out of step with each other in the first
place). It was `workspace-capabilities`' namespace resolver: one function
both front doors build, answering `{:src :file :workspace :tags}`, with tags
becoming one field of a record every namespace already carries rather than
a value threaded separately beside it. The proof that landed with it: two
workspaces binding the *same* tag name to *different* readers, compiled
together, each source reading correctly under its own binding — plus the
companion assertion that a tag no workspace has bound is still refused, so
the positive case is not merely "tags appear from nowhere and are accepted."

---

## checks

**Checks that cost nothing in the build that ships**
*(formerly `checks`)*

**Ratified:** ☐ not signed off

**Status (checked 2026-09-12 against both front ends): shipped, EXCEPT that
`:optimize [perf]` strips checks only under `bin/flint`.** `#?(:flint/check
...)` is on by default everywhere — `reader/default-features` is
`#{:flint :flint/check}` (`src/flint/reader.cljc:762`), and `flint run :path
.scratch/chk/src :fn chk/-main` on `target/release/flint` fires `(expect
nat-int? -1)` with a caret under the `-1`. The strip is real under babashka:
`bin/flint:891` does `(disj :flint/check)` when `:optimize` names `perf`, and
`bin/flint :optimize [perf]` reports `offering :flint/check` as an elision. It
is ABSENT from the shipped binary: `cli/src/main.rs` never emits `:features`
into the compile spec (`build_spec_with`, lines 284–460) and `wants_aot`
(line 471) turns `perf` into AOT only — so `target/release/flint compile
:optimize [perf]` produced a module still carrying `flint.check`, and running
it (`host/run.mjs`) threw the check. Checks are therefore still in the release
artifact the Rust CLI builds. The rest of the mechanism is shipped and was run:
`target/release/flint test :path test/common` generates `flint.check.registry`
from `^:flint.check/test` metadata and reports `125/125 checks passed` (the
suite has grown since the 53 named below).

### What was decided

`(expect pred x)`-style argument checks are written as a **reader
conditional**, `#?(:flint/check (expect nat-int? n))`, with `:flint/check`
in the reader's default feature set.

### Why

Good error messages and cheap production code normally pull in opposite
directions: a library that validates arguments pays for the validation on
every call forever, while one that does not hands back a null three frames
from the actual mistake. The usual compromises are both bad — assertions
behind a runtime flag still cost the branch and still ship every message
string; a separate "debug build" means the thing that was tested is not the
thing that ships. Making the check a reader conditional sidesteps both: under
`:optimize [perf]` it is stripped **before the source is even read**, so the
branch is never analysed, never appears in the image, and `flint.check`
itself is absent from the program entirely — not merely "compiled away."
That is what lets checks be **on by default**, which is the actual point:
a check nobody remembers to turn on is a check nobody has.

**Predicates carry their own explanation, because a predicate is a
value, not always a name.** An early version recognised standard-library
predicates by *name* and looked up a canned message — which handles
`(expect string? x)` and fails completely on `(expect x 1 2)` where `x` is
a local bound to some other predicate value. The fix is a `Predicate`
protocol (`check`/`explain`), dispatched first on a value's metadata and
falling back to its `kind`, so a plain function still works via `:fn`
implementing `check` as `apply`, while a function that *carries*
`flint.check/explain` metadata explains itself specifically. This forced
two real capability gaps to close: closures previously could not carry
metadata at all (`with-meta` on a closure silently no-opped, fixed by moving
the metadata slot to the end of `TY_CLOSURE`'s layout so every existing
upvalue index stayed unchanged), and a `defn`'s own metadata normally lands
on its *var*, which a callee never sees — routed instead through a
dedicated `:flint/value-meta` key that lands on the function itself.

**Error messages are built entirely from `&form` at macro-expansion time —
no source text is embedded in the image and none is read back at
runtime.** Getting an accurate caret under the failing sub-expression needed
two real reader changes, both worth keeping independent of this feature:
symbols, vectors, maps, and sets now all carry `:line`/`:column` (Clojure
itself never does this for symbols, which is a limitation with no reason to
reproduce), and since a literal itself cannot carry metadata, its parent
collection now carries a flat `:child-pos` array that `expect` reads to
place the caret. Making positions universal this way surfaced a real
pre-existing bug: a form returned from inside a `#?(...)` conditional used
to be stamped with the *conditional's* position rather than its own,
independent of this feature — existing metadata now correctly wins.

**Tests reuse exactly the same mechanism, deliberately with no separate
framework.** `^:flint.check/test` on a function is indexed by the compiler
along with every other var's metadata (not specially — this one key is
simply one client of the same general index a doc generator or lint pass
could equally query), and from it the compiler generates
`flint.check.registry` as source. A test passes by returning and fails by
throwing, which is exactly what `expect` already does — there is
deliberately no assertion count and no registration API, because a check
that has to be manually registered somewhere is a check that can be
forgotten. The resulting suite (`test/common`, 53 checks running identically
on all four runtimes) covers a real gap `runtimes/conform` structurally
cannot: that harness diffs transcripts *across* runtimes, so two ports that
are wrong in the *same* way agree with each other and silently pass — which
actually happened once, when both ports read a double's mantissa as an
integer.

---

## threads-and-ports

**Green threads, ports, and protocols**
*(formerly `threads-and-ports`)*

**Ratified:** ☐ not signed off

**Status (checked 2026-09-12 by running a program that uses all four; holds): shipped** — green threads, ports, protocols, dynamic vars. The
port bug that ran through this whole phase is closed (`doc/HANDOFF.md` is its
post-mortem and the source of five standing rules the project still holds
itself to). This is one of the two or three foundational decisions in the
codebase and is treated at full length. How this was checked: one program
spawning a green thread that sends over a `flint.port/channel` while rebinding
a `^:dynamic` var, read back by a protocol extended to two kinds, answered
`recv=inner dyn-outer=outer proto=str:x,num:7 thread?=true` under
`target/release/flint run` — so parking, the channel, per-thread dynamic
scoping and kind dispatch all work together, not merely separately. The
mechanism is `runtime/src/conc.rs` (3 171 lines) with hand-written ports in
`runtimes/jvm/.../Conc.java` and `runtimes/clr/src/rt/Conc.cs`.

### What was decided

`open` (and everything else that can block — a send to a full port, a
receive on an empty one) **parks a green thread rather than blocking the
host**, ports are typed endpoints with value semantics, dynamic vars are
scoped per green thread, and polymorphism is built entirely on protocols
dispatching on a closed set of built-in kinds plus metadata.

### Why parking, not blocking — the decision that shapes everything after it

The obvious reading of "a blocking `open` that calls out to the host" runs
straight into the one genuinely hard problem in wasm: a synchronous wasm
export cannot be suspended mid-execution to await a host answer. The two
standard escapes are both bad for this project specifically: **JSPI**
(JavaScript Promise Integration) is JS-hosts-only, which trades away the
portability that is the entire point of the project; **Asyncify** works
everywhere but costs code size and speed on *every* function, forever,
whether or not it ever actually suspends.

**flint needs neither, because it is an interpreter.** A green thread is
just VM state — its own value stack and frame stack, nothing more — and the
scheduler is a loop picking a runnable thread and running it for a step
budget. "Blocking" means *this thread is not runnable until something makes
it runnable.* Nothing ever blocks the host and nothing ever suspends a wasm
frame, because the interpreter never actually leaves its own dispatch loop —
this is the same leverage `dispatch` was already implicitly paying for by
choosing an interpreter, and this decision is where that leverage gets spent
deliberately. The module's exported interface grows from "call `main` once,
get an answer" to: the host calls `main`; the scheduler runs until every
thread is finished or parked; if any are parked on host ports, `main`
returns a status meaning "I need the host," with pending requests readable;
the host services them and calls back in to resume. **The pure case stays
exactly as simple as it is today** — a program with no ports runs to
completion in one call with no pump loop and nothing new for the host-side
caller to think about.

### The rest follows from that one design choice

**None of this may grow a pure module.** Threads and ports are namespace
units like any other (`namespace-units`), so a program that never mentions
`open`, `channel`, or spawning a thread must produce a module with no
scheduler, no port machinery, and no host callback surface at all — the same
size as before this feature existed, asserted by a test rather than trusted.

**The GC's whole design rests on "the VM's value stack IS the root set,"
and N threads mean N stacks — including parked ones full of live
references nothing is currently executing.** The root walk has to iterate
the thread table, and the thread table is itself a root. The standing
stress-testing discipline (spawn threads, park some, collect at every
allocation, check parked threads resume with values intact) exists
specifically because getting this wrong produces a use-after-collect that
only appears when a collection lands while a thread happens to be parked —
exactly the case ordinary tests would never hit by accident.

**Dynamic vars are scoped per green thread, not per host thread** — this
removes a limit the README used to list, and forced one genuine open
question to be answered rather than left implicit: does a spawned thread
inherit its spawner's `binding`s? (Clojure conveys them to `future` and to
agents.) Either answer is defensible; silence is the actual bug, since it is
exactly the kind of thing somebody would otherwise discover through a
production incident at three in the morning.

**Ports: `open` signals the host, which allows or refuses**; refusal must be
a clean, catchable error, not a crash, since "the capability is not
available" is an entirely ordinary and expected outcome. A send to a full
port parks the sender using the *same* parking mechanism as `open`, not a
second one. What may cross a port is **data, and other ports — nothing
else**; functions and closures are refused by name at the point of sending.

**Transfer is by value, and passing by reference within one runtime is a
safe optimisation specifically because flint values are immutable** — the
same property that later justifies sharing structure in ropes and vectors.
Ports themselves are the deliberate exception: a port has identity and
mutable state, so sending a port through a port raises a real design
question (does the sender keep its own end, or hand it over?) which this
document leaves as a decision to be made and stated explicitly rather than
allowed to default silently — later reversed and resolved by
`structured-ports`.

**The scheduler must be deterministic, and this is treated as close to the
whole value proposition of the project.** A pure logic executor whose
answer depends on scheduling order is not worth its name — round-robin, a
fixed step budget, no randomness, no wall-clock dependence, the same program
and the same host event order producing the same answer every time. This
determinism is what `resource-limits`, `debug-runner`, and `snapshots` all
later depend on and build further guarantees on top of.

### Protocols, and the largest deliberate deviation from Clojure in the language

flint has **no `deftype`, no `defrecord`, no host classes** — so "what type
is this?" has no general answer the way it does in Clojure, which makes the
dispatch design here genuinely different, not merely smaller. **Built-in
kinds are a small, closed set** — nil, number, string, keyword, symbol,
vector, map, set, list, fn, port, and (as the language grew) thread, atom,
var, regex, exception, tagged, opaque, bytes, delay, volatile, schema,
table — and protocols extend to those by kind. The rule that governs the
set's growth, arrived at after a real near-miss: **`:other` is not a
kind — it is the absence of one**, and a value answering `:other` cannot be
dispatched on at all. For a long stretch, four kinds of value a guest could
actually hold (opaque values, byte strings, delays, volatiles) all answered
`:other`, which meant a single `extend-protocol :other` written for any one
of them would have silently caught *all* of them, plus every future type
added later. The corrected rule: **anything a guest can hold gets a kind of
its own**; `:other` exists only for what a guest categorically cannot hold.

**Everything else dispatches on metadata**, which Clojure has as
`extend-via-metadata` — opt-in, and something of a corner case there.
**Here it is the primary mechanism**, stated plainly as a real deviation
rather than left for someone to infer, because there is nothing else for a
user-defined abstraction to be, given the absence of `deftype`/`defrecord`.
A method key belongs to the protocol that *defines* it, never to whichever
namespace happens to be doing the extending — `extend-protocol` originally
built its dispatch key from the *extending* namespace, which meant
extending a protocol from a namespace other than the one that defined it
silently wrote an implementation under a key nothing would ever look up,
failing invisibly (`protocol-miss`) at some unrelated later call. Nothing
caught this for a long time because every protocol test both defined and
extended in the same file, where the two namespaces happen to coincide — it
only surfaced once the printer itself moved onto a protocol so a library
type could print itself, which is precisely the cross-namespace case the
mechanism exists to serve. One further hard limit falls directly out of the
value encoding and is stated rather than left to be discovered: **inline
values cannot carry metadata** — small strings, keywords, and characters are
interned directly into the value word itself, so there is nowhere to hang a
metadata map at all.

---

## host-abi

**Tokens, one event queue, and where the marshalling cost actually is**
*(formerly `host-abi`)*

**Ratified:** ☐ not signed off

**Status (checked 2026-09-12 against `runtime/src/conc.rs`, and exercised; holds): shipped** — tokens, one event queue, two lifetimes. Refines
`threads-and-ports` §5; several of its specific rules were later reversed by
`structured-ports` and `ports-are-the-hosts`, noted inline below. How this was
checked: the token really is an index with a generation — `new_waiter` /
`waiter_at` / `free_waiter` (`conc.rs:1126`–`1205`) pack the index in the low
16 bits, bump the generation on free, and reject a mismatch — and there is one
queue, `SC_EVENTS`, written only by `push_event` (`conc.rs:1893`) and read only
by `drain_events` (`conc.rs:2969`), carrying all six event kinds. Two
lifetimes: `reap_ports` (`conc.rs:3045`) treats a flint end the collector lost
as a `close`, pushing `EV_CLOSED` and `EV_RELEASE`, while the host end is held
by a holder count. Exercised end to end: `target/release/flint run :with [env]`
(the shipped binary, not `bin/flint`) on a program
calling `flint.sys.env/cwd` parks on a token, emits `EV_REQUEST`, and resumes
with the host's answer — it printed this worktree's path.

### What was decided

A single continuation **token** generalises every parking operation; a
**single outbound event queue** replaces separate host exports per event
kind; the runtime creates both ends of a channel pair itself rather than
round-tripping through the host; `continue` always **enqueues**, never
re-enters the scheduler; and a port's two ends have genuinely **separate
lifetimes**, with the host end acting as a strong GC root.

### Why

**The token generalises, because everything that parks parks the same
way.** `open`, a send to a full port, a receive on an empty one — one
waiter table, one token type, one resume path; the host never learns what a
"thread" even is, only that it holds a token to hand back later. The token
must pack an **index with a generation counter**: a bare index is reusable,
so a late or duplicated host reply would resume whatever now happens to
occupy that slot — a wrong thread, silently woken with a stranger's value,
which is close to unfindable in production. Bumping the generation on free
and rejecting a mismatched token costs one `u32` and closes that hole
outright. A host that simply never calls `continue` leaks a parked thread,
and that has to be made *visible* — countable, nameable in a diagnostic —
rather than a silent, permanent stall.

**One event queue, not several host exports**, because separate exports
mean separate calls, separate ordering rules, and multiple chances for a
host implementation to forget one; draining everything pending in one call
also amortises the marshalling cost, which turns out to be the actual
expense (see below), not the call boundary itself.

**`continue` must enqueue, never re-enter.** If a host calls `continue`
*while the runtime is already running* — from inside a host function
invoked by wasm — a naive implementation would re-enter the scheduler on top
of itself. The rule is unconditional: continue only records the answer and
marks the thread runnable; the scheduler picks it up on its next pump. This
is cheap to specify correctly up front and a genuinely miserable class of
bug to find after the fact if it is not.

**Where the cost actually is, and therefore what has to be batched.** A
wasm↔host call itself is tens of nanoseconds — the expensive part is
**marshalling**: copying bytes out of linear memory, parsing, allocating
host objects. So a message is serialised into linear memory at send time
(the host reads byte ranges and never walks the flint heap or needs to know
the value encoding at all), the event queue drains everything pending in one
call so the per-message boundary cost goes to zero, and buffers are bounded
in **bytes, not message count**, since back-pressure exists to bound memory
and one 4 MB message is not "one message's worth" of anything. Eagerly
serialising costs real work even when a host never actually reads a given
message — accepted deliberately, because it is what makes the drain cheap
and the byte bound meaningful; if a later benchmark said otherwise, that
would itself be a real finding worth having.

**Formats: JSON, EDN, and a binary EDN — and JSON's limits must be an
error, never a silent coercion.** Transit (msgpack) is the recommended
starting point for binary rather than inventing a fourth format, since it
already exists for exactly this and already has the extension mechanism
tagged literals need. The important, load-bearing fact: **JSON cannot
represent EDN.** Keywords, sets, symbols, tagged values, and non-string map
keys have no JSON form, and "the runtime will try to convert" hides exactly
the failures that matter — a keyword silently becoming `"a"` does not
round-trip; `{:a 1}` becoming `{"a": 1}` is convenient, lossy, and
asymmetric; a set becoming an array loses its setness. So a value that
cannot be represented in the chosen format is a **send-time error naming
the value and the reason**, never a coercion — where a convenience coercion
is genuinely wanted (keyword map keys to strings is the common case), it is
an explicit, off-by-default option on the port, the same way
`clojure.data.json`'s `:key-fn` is the caller's decision to make, not the
library's.

**Lifetime: two ends, two genuinely separate lifetimes, and a safety net
under the common failure mode.** The request was for the runtime to signal
when a port is collected or closed. The first-pass answer — a host-held
port is rooted by the host end and lives until the host explicitly closes
it — was correctly called too optimistic: it left explicit `close` as the
*only* way a script could signal it is finished, and the common failure is
not a script that forgets to close, it is one that throws, or simply
returns having dropped its last reference. So: **the host end is a strong
root** (a port cannot be collected while the host holds a handle — without
this, every host handle is a use-after-free waiting for a collection), while
**the flint end is ordinary reachable memory**, and when the collector finds
it unreachable that is treated as semantically identical to the script
having called `close` itself — the runtime emits a `:closed` event on the
script's behalf. This reuses the collector's existing weak-reference
machinery, applied here to the flint end of a port. Collection-triggered
close is explicitly a **safety net, not the primary mechanism** — it is
deterministic but not *prompt*, and a host holding a socket open because a
script simply has not been collected yet is a real cost, so `with-open`
remains the documented good path, closing on both the normal exit and a
throw.

**An event is a notification; the port's own state is the truth, and this
distinction generalises beyond ports.** The host learns a flint end closed
two ways and needs *both*: a pushed event for prompt reaction, and a
queryable state for the case that actually matters — **if an event is the
only way to learn a durable fact, then an event that is dropped, missed, or
not yet drained is an unrecoverable leak.** Making the state queryable turns
the event into an optimisation over polling rather than the sole carrier of
truth, and this same principle is stated as one worth applying to anything
else this ABI ever notifies about. It is symmetric: a script can query its
own end's state the same way, and — more importantly — a send or receive
against a port whose peer is already gone must **error rather than park**,
since a script blocking forever on a host that has already hung up is the
identical failure to a host silently leaking a handle, seen from the other
side. The same reachability machinery buys **deadlock detection nearly for
free**: a thread parked on a receive whose peer end has become unreachable
can never succeed, and rather than hanging forever it is woken with an error
the moment the collector notices — a genuine liveness property falling out
of work the collector is already doing for other reasons.

### Later

`structured-ports` reverses the "ports are not transferable" rule this
document treated as settled (deliberately, as the right *default* rather
than a permanent limit — "transfer can be added later; it cannot be
removed"). `ports-are-the-hosts` moves port ownership itself out of the
sandbox, changing who creates a port and where the queue actually lives,
though the token/generation mechanism and the two-lifetimes model both
survive into that redesign essentially unchanged.

---

## structured-ports

**A wire codec, and structured ports**
*(formerly `structured-ports`)*

**Ratified:** ☐ not signed off

**Status: BUILT. The "NOT BUILT — a proposal" banner it carried was wrong,
and was wrong for most of this record's life.** Confirmed 2026-09-11 against
the code, claim by claim:

| what it decided | where it lives |
| --- | --- |
| a single wire codec for every value crossing the boundary | `runtime/src/codec.rs`, 1 315 lines |
| the host calls any function BY NAME | `Sandbox::call(name, args)`, `sdks/rust/src/sandbox.rs:308` |
| arguments are DATA, not strings | that same signature takes `&[Value]` |
| a port can be sent through a port | `K_PORT` in the codec, carrying identity inline |

The last of those reverses `host-abi`'s "ports are NOT transferable", which
that record treated as settled and which is no longer true — ports cross
today. `host-abi` says of it "transfer can be added later; it cannot be
removed", which is exactly what happened.

THE BANNER WAS THE WHOLE PROBLEM. This is among the most heavily cited
records in the set, and a reader checking whether `host-abi`'s rule still
stood would come here, read "nothing in this file exists", and conclude it
did.

### What was decided

Four changes bundled as one: a **single wire codec** for every flint value
crossing the host boundary; the host calls **any function by name** with a
positional argument list, rather than one fixed entry point taking an argv;
arguments are **data, not strings**; and — reversing the rule
`host-abi` treated as settled — **a port can be sent through a port**, so a
capability can be delegated.

### Why

**It is a codec, not a message format**, deliberately: the entry argument
map is not "a message," it is an ordinary function argument that happens to
arrive in the same encoding, and naming the whole mechanism after one of its
uses would misdescribe the other. An explicit builder API exists alongside
a convenience `from` that guesses shape from a host value, because a host
that can only convert its own native values cannot always *say* what it
means — `{a: 1}` is ambiguous between a string-keyed and keyword-keyed map,
`1` between an integer and a double, `[1,2]` between a vector and a list —
and guessing right most of the time is exactly what makes a guess a latent
bug.

**Sending a port: identities travel inline, and the first design for this
was wrong in an instructive way.** The first draft put a transfer table in
every encoded value — the live things it carried, referenced from the body
by index — on the theory that a raw port id would let a guest fabricate
`K_PORT 7` and claim a port it never held. **That defence solves a problem
that cannot occur**: the forgery it defends against requires the guest to
*construct an encoding* by hand, and it cannot — when a program does
`(p/send port v)` it hands over an already-real port *value*, and the
runtime does the encoding; for `K_PORT` to appear in the output bytes at
all, the guest must already have legitimately held a port. `7` on its own
encodes as an ordinary integer. So identities are simply inline —
`K_PORT <id>`, `K_SENTINEL <host-id> <label>` — with no table, no index
indirection, and no per-value bookkeeping.

**The actual invariant is stated precisely, and it lives on the sandbox
side, not the host side**: *flint is given no way to turn an integer into a
port or a sentinel.* The host itself needs no protecting — it holds the
memory and can call any export, and if it is compromised there is nothing
left to protect. The rule this places on the codec is small and exact: the
runtime encodes ports and sentinels only from real values, by construction;
if a guest-callable *encoder* is ever added, the live thing itself must be
supplied (holding it is the proof of legitimacy); and if a guest-callable
*decoder* is ever added, it must categorically refuse `K_PORT` and
`K_SENTINEL`, since a decoder is exactly an encoder read backwards and bytes
are things a guest can freely write. This already held in the one place it
mattered: `(opaque "label")` hard-codes its host id to zero on the guest
path, so a guest-minted sentinel is honestly self-describing as
not-one-of-mine.

**One system port replaces eight separate host ABI exports and a bespoke
record format.** Every sandbox gets a system port carrying all traffic to
and from the host; `open` becomes a message with a transaction id on it
rather than a distinguished export, and so does a send, a close, and
termination. There is deliberately no special "start the program" message,
because there is no special *the* program — a **call** names a function, its
arguments, and a `tx`, which means an artifact is a set of callable
functions rather than a program with one entry point, and the host decides
which to call. This reframing renames the two central nouns to say what
they actually are: an **Image** is the compiled, inert artifact; a
**Sandbox** is an image instantiated, holding state, serving calls over its
system port — the Docker image/container analogy, taken deliberately, since
it names the property that is the whole point of the project and a reader
who knows nothing else about flint still knows what is guaranteed. `main`
correspondingly demotes from a mechanism to a mere convention: the function
`flint run` happens to call when nothing else is named.

**`take` drives the sandbox; `put` usually does not — and this falls
directly out of what each verb means rather than being a separate design
choice.** `take` runs the sandbox until it produces a message or terminates,
so the boundary crossing happens once per *batch* rather than once per
message; `put` merely enqueues and returns a promise so back-pressure has
somewhere to live, except when the buffer is genuinely full, in which case
`put` itself must drive the sandbox, because the only thing that can make
room is the guest consuming — the same polling-park shape from the other
side. There is consequently no separate `flush()` and no debounce parameter
to tune: the two operations already say precisely when work has to happen.

**The CLI's own conventions — an entry map of `{:args ... :capabilities
...}`, `:with` for granting capabilities, `:optimize` as an ordered
preference list, `:to` naming a target rather than a filename — are
explicitly the CLI's house style layered *over* the SDK, never features of
the SDK itself.** A program called through the raw SDK looks like whatever
its author wanted; only a program run through the CLI takes `{:keys [args
capabilities]}`, because the CLI is what passes that shape. This split
matters because a mechanism that itself knows what `:capabilities` means has
quietly taken a decision that belongs to whoever is calling it — the same
principle `workspace-capabilities` later relies on for why the runtime has
no notion of "capability" at all.

### A discrepancy worth flagging

This file's own banner reads "NOT BUILT — a proposal," and the project
status table (formerly the the decision index (now folded into `DECISIONS.md`) index) likewise lists
it as "Roadmap." **The current runtime source treats several of its central
rules as already-shipped, settled fact, not as a proposal.**
`runtime/src/codec.rs` states outright, in a normal doc comment rather than
a TODO, "This is the whole of `structured-ports`'s safety rule, and it is one line: a
guest..."; `runtime/src/conc.rs` says a capability "REVERSES [`host-abi`'s
no-transfer rule], which is the [mechanism]"; `lib/flint/port.cljc` and
`lib/flint/virtual.cljc` both build on port delegation as a working feature,
not a future one; and this pattern repeats identically across the Rust
runtime and both the JVM and CLR ports' source. **The rule that ports and
sentinels cannot be minted from a bare integer, and that a port can now be
sent through a port, is real and load-bearing in the shipped runtime today**
— it is the file's own status banner (and the project index derived from
it) that is stale here, in the direction the closing note of the original
decisions index specifically warned is the more dangerous one: understating
what exists is what gets something rebuilt by someone who trusts the
banner. What is **not** evidenced as built is the rest of this document's
scope — the single unified system port replacing the eight `host-abi`
exports, the `Image`/`Sandbox` renaming, and the resolver-based compiler
API — which do still read as proposed rather than shipped.

---

## ports-are-the-hosts

**Ports belong to the host, not to a sandbox**
*(formerly `ports-are-the-hosts`)*

**Ratified:** ☐ not signed off

**Status (checked 2026-09-12, exceptions first; holds): shipped**, on all four runtimes, except two named pieces of
follow-on work at the end. This file's own banner used to claim the whole
design was unbuilt long after half of it had shipped and gone unreachable —
see "A banner that lied," below, which the project's own closing
documentation held up as its worked example of why a banner must be checked
against the code rather than trusted. How this was checked, taking the two
exceptions rather than the headline: the weak-table fixup is indeed still the
simpler sweep — `reap_ports` (`runtime/src/conc.rs:3045`) walks `SC_BRIDGES`
after a collection and pushes `EV_CLOSED`/`EV_RELEASE` for any id whose
`port_by_id` lookup now misses, with no fixup-on-forward anywhere; and codec
back-references are still not built — `runtime/src/codec.rs`'s own header says
"A stream duplicates a subtree that appears twice, where a pool would share
it." For the headline, the orphaned second generation the banner story is
about is gone: `K_GLOBAL` no longer appears in `runtime/src/conc.rs` at all,
and the bridge path has real callers on the JVM and CLR
(`installBridgePort`/`forgetBridge` in `Conc.java`, `InstallBridgePort`/
`CloseAllBridges` in `Conc.cs`), reached from the decoder through
`rt.bridgeHook`.

### What was decided

A port is either **local** (joins two green threads inside one sandbox,
passes values by reference — unchanged, and still as cheap as ever) or
**global** (joins two sandboxes, passes values encoded) — and every global
port, and the registry that owns it, belongs to the **host**, not to any
one sandbox. A sandbox holds a global port only because it was handed one.

### Why

Ports were originally a sandbox's own property: each runtime instance owned
its own registry, and a port id was simply an index into it. That coupling
is fine with one sandbox and breaks the instant there are two: an endpoint
that exists only inside sandbox A cannot be *held* by sandbox B, because B
has a different heap, a different collector, and a different id space — A's
id 3 means something else in B, or nothing at all. Inter-sandbox
communication is not an extension of the old model, it is a direct
contradiction of it, so the registry has to move out to something both
sandboxes can see: the host.

**This is the same inversion `opaque-values` already made for capabilities**
— authority is never something the thing being confined manufactures for
itself — applied here to ports specifically.

> **A FRAMING NOTE, added 2026-09-11.** This section used to end "which makes
> ports capabilities in fact rather than merely by analogy", and read as
> though obtaining a port WERE the capability request. That is not the model.
> Confirmed with the person whose model it is, and against the code:
>
> Capabilities are a first-class concept, declared in a workspace's config —
> `:flint/capabilities-grant` and `:flint/capabilities-guard` in `deps.edn` —
> and enforced at compile time and at run time to DIFFERENT extents. The
> compile-time half is `workspace-capabilities`: which workspace may name
> which var, and now which builtin. At run time enforcement is mostly the
> HOST's: a grant may be a map of name to policy rather than a bare set, and
> `lib/flint/deps/resolve.cljc` says of it, "the policy is the host's, checked
> when a call happens" — so the receiver regulates against the options the
> grant carries.
>
> A port is therefore a MECHANISM authority travels over, not the authority
> itself. What this record gets right is the inversion — a sandbox cannot
> fabricate one — and that part stands. Creating a *global* port becomes a
dispatch request on the system port rather than an intrinsic a sandbox can
just do; combined with `structured-ports`' rule that flint has no way to
turn an integer into a port, this becomes something stronger than either
half alone: **flint cannot obtain a global port by fabrication or by
construction — every one it will ever hold was handed to it.**

**A message between two sandboxes must be copied, never shared — and the
encode step itself is the boundary, not an addition to it.** Two sandboxes
have separate heaps and separate collectors, so a value cannot cross by
reference at all, encoding or no encoding; what changes is only *where* the
existing wire codec's encode/decode pair gets used. The collector interaction
is the genuinely hard part any shared-registry design has to get right: a
queued message cannot be a raw pointer into the sender's heap, because the
nursery is copying (the object moves) and old space is swept (it can be
freed outright) — a host-owned queue holding sandbox pointers would become a
queue of dangling addresses after the very next collection. The split that
avoids this is three strict layers, with no collector ever seeing a pointer
it does not own: inside a sandbox heap, only a **handle** exists — an
ordinary traced object carrying a host id and no pointer at all; in host
memory, the port and every queued message exist purely as **encoded
bytes**, which neither collector needs to trace because there is nothing
heap-shaped in them to trace; and consequently **no cross-heap pointer ever
exists at any point**, so there is no moment at which one can go stale. A
local port pays none of this cost, deliberately: encoding two green
threads' messages through a byte buffer when both share one heap and one
collector would be pure loss, so a local port stays exactly what it always
was — an ordinary heap object with a value-holding queue the collector
traces normally.

**Reclaiming a port a sandbox stopped holding needed a real design choice
between two options, and the one that looks more expensive is actually
cheaper.** A "box per arrival" (wrap each arriving port in a fresh
heap object owning one refcount increment) looks like it avoids needing a
table, but it still has to tell the host when it dies, so it pays for the
same collector hook anyway — and it adds two costs a table does not have:
identity breaks (the same port arriving in two separate messages becomes
two different boxes, so `=` says no and a map keyed by "the" port silently
holds two entries for it), and needless refcount churn (N arrivals of one
port cost N atomic increments for a question whose only real content is
"does this sandbox still hold it *at all*"). The **weak intern table** —
one canonical handle object per global port per sandbox, incrementing once
on first arrival and decrementing once when that canonical handle dies —
answers both directly: each sandbox contributes at most one to a port's
count, so the count means "how many holders," a number a person can reason
about, rather than "how many references." Three things this table has to
get exactly right, each named because a version of this bug has already
happened once elsewhere in this codebase: weak-through-a-copy fixup (a
surviving handle must be re-pointed at where the nursery moved it, not
merely kept-or-cleared); interning itself allocates, and an allocation can
collect, so anything live across a lookup miss must be rooted exactly as
`a-vec-of-values-is-not-a-root` requires everywhere else; and a dropped
sandbox must walk its own table on teardown, or it leaks every global port
it ever held into a place no collector will ever look again.

**Push must wake, never execute.** A global port supports both a parking
`take` and a push-style readiness signal, because supporting both costs
almost nothing over supporting either (same buffer, same lock) — and
pull-only cannot drive a long-lived sandbox loop at all: if the only way to
learn something arrived is polling, a host serving several sandboxes ends up
spinning across all of them just to learn "no," burning a core and adding
latency proportional to how rarely it asks. But it would be easy, and
actively wrong, for `put` to run the receiver's waiting code directly on the
sender's own thread — that would execute guest code inside a heap the
sender does not own, potentially while that heap's real owner is
mid-collection, and the collector's whole design assumes exactly one
mutator per heap at a time. So an arrival does exactly three bookkeeping
things and nothing else: append to the port's buffer under its own lock,
mark the parked thread runnable in the *receiving* sandbox's own scheduler,
and signal that sandbox's readiness — a sandbox is still only ever advanced
by whoever owns it; what push buys is that the owner can *wait* for the
signal instead of polling.

### A banner that lied, kept as the project's own cautionary example

This file's banner used to read "QUEUED — nothing in this file exists yet,"
and kept saying so long after half of the design had actually been built
and gone silently unreachable: the global-port installation functions, the
system-port constant, and the `K_GLOBAL` tag all existed in the tree with
**zero callers on any of the three runtimes**. Two generations of the port
model were live in the codebase at once, and the stale banner is what let
that state be misread as "not started" rather than "half-finished and
orphaned" — it cost a wire-port path that was granted permission on the JVM
and CLR and then refused by their own `send`, because the newer and older
models disagreed about what a port even was. This is the concrete worked
example the project's own closing documentation held up for why a status
banner must be checked against the code when it is touched, not trusted and
left for whoever finishes it.

### What is still open

The weak-table fixup through a nursery copy is handled today by a simpler
mechanism (the sweep walks the bridge table after every collection and
releases ids whose lookup misses) rather than the full fixup-on-forward
scheme described above. And **back-references in the codec are not built**
— a value whose subtree is shared ten times currently encodes ten times,
a real, named, and still-open cost.

---

## bridges

**A bridge owns its messages, and a port is six verbs**
*(formerly `bridges`)*

**Ratified:** ☐ not signed off

**Status (checked 2026-09-12; holds): not built — a proposal.** It settles who owns a message in
flight, a question `host-abi` and `drivers` both left resting on the
sandbox, and what the wire codec and terminology work in `structured-ports`
and `ports-are-the-hosts` are still waiting on. How this was checked: none of
the six verbs exists — no `reserve`/`grow`/`commit`/`abort` contract, no
`format()`/`memo()`, and no `Bridge` trait, in `runtime/src`, `sdks`,
`runtimes/jvm`, `runtimes/clr`, `cli/src`, `nativeabi` or `kin` (the only
"reserve" hits are the GC's address-space reserve and `Vec::reserve`). The
word "bridge" IS everywhere in the runtime, but it names the thing
`ports-are-the-hosts` shipped — a port whose far end is the host — not this.
The premise this document rests on is also still true: `push_event`
(`runtime/src/conc.rs:1893`) is still a read-modify-write that `vec_conj`s onto
the `SC_EVENTS` persistent vector in the sending sandbox's own heap, with
allocations in the middle, so a message still cannot outlive its sandbox.
(Note for whoever picks this up: the ring in `port_enqueue`, `conc.rs:1055`,
HAS since been made single-CAS and lock-free; the event queue has not.)

### What was decided

**A bridge — not a sandbox — owns messages in flight.** A port becomes a
small six-verb contract (`reserve`/`grow`/`commit`/`abort`,
`take`/`release`, plus `format()` and `memo()`) that knows nothing about
sandboxes, heaps, or transports; the same interface is implemented
completely differently underneath depending on whether the two ends share a
process (a ring in shared memory) or not (a pipe, socket, or file).

### Why: today's design cannot let a message outlive the sandbox that wrote it

As things stand, every message in flight lives entirely inside the sending
sandbox's own heap — `port_send` encodes into a byte string *in the sandbox
heap*, and it sits in a persistent vector there until the host calls
`drain_events`. Two consequences follow directly, and the first is the
entire reason this document exists: **a message cannot outlive the sandbox
that wrote it**, so a bridge cannot be handed off to a different sandbox
with its traffic intact, and a port cannot be a genuinely durable thing. The
second is a live, unconfirmed hazard rather than a demonstrated bug: the
event push is a read-modify-write on a shared persistent vector with an
allocation in the middle — precisely the shape that already lost half the
messages on an internal inbox once before this was fixed elsewhere, and
nothing has yet proven two executors sending across bridges concurrently
would not reproduce it.

**Ownership is claimed only at `commit`, deliberately never earlier — this
is the whole reason ownership sits on the *slot*, not on a lease held by the
writer.** A writer that dies mid-serialisation can therefore never wedge the
ring; it can only strand the one buffer it was working on, never block
anyone else. A stranded buffer still matters — exhausting the allocator
turns every future producer into a blocked one, so an unreclaimed leak
becomes a deadlock by a different route — and "the sandbox releases it when
it dies" is not a sufficient answer, because a killed sandbox runs no code
at all to release anything, and a peer process that segfaults on a
shared-memory bridge runs none either. So **each participant allocates from
its own arena inside the bridge**: if a participant dies, the survivor
reclaims that participant's *entire* arena in one step — no per-buffer
bookkeeping, no scan, no timer — and it survives even a `SIGKILL`, because
the party doing the reclaiming is, by construction, the one still alive.
Three designs were considered and two rejected for stated reasons: rooting a
value in the sandbox heap until the bridge is done cannot work at all, since
a root only keeps something alive *inside a heap*, and if the sandbox dies
the heap is gone with it; the status-quo shared sandbox-side event buffer
concentrates contention rather than reducing it; and per-buffer leases with
timeouts solve the same problem as the arena refcount while adding a clock,
a scanner, and a failure mode where a slow-but-live writer has its buffer
stolen out from under it.

**Identity tags serialise as themselves, and by *type*, which is what makes
the design safe rather than merely convenient.** Every supported wire
format can express identity somehow (EDN has tagged literals, CBOR has
tags, JSON gets a documented convention), so refusing to let only `:flint`
name a port would be an invented restriction rather than one the formats
themselves impose — what a format actually decides is only whether it has
*any* way to say it at all. A codec emits a port tag because it encountered
an actual port value, never because it encountered data merely shaped like
one, and a guest cannot construct a port it does not genuinely hold — this
is `structured-ports`' invariant, restated at the wire-format layer rather
than the in-memory one. Two guards are needed, defending opposite
directions, and neither is redundant with the other: inbound, resolution is
always mediated through the grant table, never through possession alone
(`opaque-values`' rule, extended to cover bytes arriving from anywhere); and
outbound, a guest value that would *serialise* to a reserved tag by any
route throws, naming the tag — needed specifically because the far side of
a bridge to a foreign peer (a plain socket, another process, a JSON web
service) may have no grant table at all to fall back on, so only the
outbound guard protects a peer that is not flint's own.

**Two JSON dialects, not one with judgement calls, so the caller chooses
explicitly what it needs.** `:json-strict` degrades best-effort into plain
JSON and throws for anything that genuinely cannot fit — and the refusal
list (bigints, non-string map keys, ports, opaque values) is not arbitrary:
the rule is that *type* loss is acceptable but *value* loss is not, which is
one rule deciding the whole table (a keyword returning as a string is
weaker typing over the same data; a bigint through a JSON double comes back
silently rounded, which is wrong arithmetic, not weaker typing). A plain
JSON number is exact only to 2^53, but a flint fixnum's range is comfortably
inside that (±140,737,488,355,327, roughly 64× more headroom than needed),
so **every fixnum round-trips exactly through `:json-strict`** — it is only
a bigint that can actually exceed the safe range, which is why only bigints
are refused rather than all integers. `:json` adds a `$flintTag` convention
for exactly what plain JSON cannot express (ports, opaque values, bigints,
tables, sets) — and the table's own tag payload is deliberately
**columnar**, for the identical reason `tables` chose columnar storage in
the first place: a row-major vector-of-maps repeats every column name on
every row, where a columnar payload names each column exactly once, keeping
the format's own compactness argument intact all the way out to the wire.

---

## drivers

**A driver: ports are the only way to drive a sandbox**
*(formerly `drivers`)*

**Ratified:** ☐ not signed off

**Status (checked 2026-09-12 by reading `sdks/rust/src/driver.rs` and running
the parallel suite): partly built, and the shared-heap gaps this line used to
name are now CLOSED.** The SDK shape exists in Rust — `Driver`, `Inline`,
`ThreadPool`, an asynchronous `call`, coalesced dispatch — all in
`sdks/rust/src/driver.rs` and `sandbox.rs`. Underneath, the heap has moved out
of the single-threaded `Rt` struct and two executors genuinely share one heap
across real collections. What this line used to say was still unsafe is no
longer: the intern tables take a lock per table held across the probe
(`Rt::lock_intern`, `runtime/src/rt.rs:549`, used by `runtime/src/strs.rs`),
the remembered set is per-executor and every parked executor's list is drained
by whoever stages the collection (`Rt::alloc_shared`, `runtime/src/rt.rs`), and
`globals` is an array of atomics (`GlobalSlot`, `runtime/src/gc.rs:260`). The
deliberately-ignored test is gone: `cargo test -p flint-rt --features parallel
--test parallel --release` reports `7 passed; 0 failed; 0 ignored`, and those
seven include one text interning to one object across two executors and
old-to-young edges surviving a collection staged on the other thread. What
remains true is the last clause: the sandbox still serialises its executors, so
K > 1 is *correct* and not yet *faster* — stated in `driver.rs`'s own module
doc, and visible in `kin/atoms.kin`, where `compare-and-set-atom` is a plain
compare-then-`set-slot` rather than a hardware CAS.

### What was decided

`Sandbox::call` used to run a program **on the calling thread** directly,
which forecloses thread pools by construction: a sandbox that only ever
advances when something calls directly into it cannot be advanced by a
pool, since a pool's entire job is deciding *when* and *on which thread*
work happens. So the host stops driving directly: **ports become the only
way to drive a sandbox**, with a **driver** sitting between the ports and
the sandbox deciding when and on what thread to actually run it.

### Why

**The only safe place for a thread to stop is the interpreter's own
checkpoint.** A collection moves objects, so any thread that stops while
holding a `Value` in a plain host-language local resumes holding a stale
pointer the instant a collection runs. Under one executor that rule only
ever applied around your *own* allocations; under several it applies around
*everyone's* — which is exactly `a-vec-of-values-is-not-a-root`'s rule,
generalised from "a Rust local across an allocating call" to "any thread
that might stop while another thread allocates." A thread inside a native
function does not poll and genuinely cannot stop, so the collector simply
waits for it — a real latency cost, not a correctness one, and a distinct
concern from the second point below: **a collector must wait only for
threads that can actually stop.** The first version of this deadlocked
outright, and the cause is worth keeping: it waited for every *registered*
executor, including one that had already finished all its work and was
polling nothing — "registered" and "actively running" are two different
questions, the same split a JVM draws between a thread that is "in Java"
versus one that is "in native."

**Real bugs found while building this, kept because each is a specific,
recurring shape.** An `Rt` must never move once registered, because what
gets registered is the *address* of its root stack — wrapping one in a
`Mutex`/`Arc` after registration let the collector walk freed memory
(observed as a stack-top value of 14,728,600,375,357,765,408 against an
actual length of 0), fixed by boxing the `Rt` so the invariant is structural
rather than merely remembered. Counting "every running executor except me"
silently assumed the caller is always one of the executors being counted,
which is false during allocation outside guest code entirely (loading an
image, running initialisers) — the collector started with a peer still
executing because the count was off by exactly one in that case.

**Having more than one executor is what selects the counting policy, and
this directly resolves an apparent tension with `resource-limits`.** The
safepoint poll lives inside the same checkpoint `resource-limits`
monomorphises away entirely when nothing is counting — a sandbox with peer
threads cannot have a thread that never polls at all, so having a peer (or a
gas limit) selects the counting dispatch policy exactly the way a gas limit
alone already does; one executor with no limit still runs the fully free
loop. Gas itself is threaded the same way: it stops being one shared
counter under K > 1 and becomes per-executor local counters flushed to a
shared atomic on checkpoint, with the total therefore stopping being exactly
deterministic once concurrency is real — a property of parallelism, not a
regression, and the same conclusion `resource-limits` already reached on its
own.

**`call` had to become asynchronous now, not later, and the cost of getting
this wrong falls on a public API rather than an internal one.** If ports are
the only way in, `call` is fundamentally: encode a request onto the system
port, let the driver schedule the sandbox whenever it chooses, take the
reply — which cannot return a value synchronously on the caller's own
thread, because the sandbox may not even run on that thread. A
`SingleThreadDriver` may resolve the resulting promise before returning, so
the simple case still *reads* as simple, but the type is asynchronous from
the very first version, because a synchronous API cannot be made
asynchronous later without breaking every existing caller — and since
nothing had been published yet, this was a rewrite of three SDK surfaces
paid once now rather than an ecosystem-wide break paid later by everyone
else. A blocking convenience on top is fine for drivers that can honestly
offer one, but must never be reachable *from inside* a sandbox: a call made
from guest code, occupying a pool thread, waiting on a reply that itself
needs a pool thread to arrive, is the oldest deadlock shape there is — from
inside, a call is simply a port send plus a park, which it already is once
ports are the only way in at all.

**Shared memory moves from optional to required the moment one sandbox gets
more than one thread, for a specific mechanical reason, not merely
tidiness.** A `WebAssembly.Instance` cannot be structured-cloned in a
browser, so it cannot be handed to a worker — what *can* cross is a compiled
`WebAssembly.Module` plus a memory, which means a pooled driver has to
instantiate *on* the worker, which means whatever owns instantiation owns
the memory. An earlier draft argued a pool needs no shared memory at all, on
the reasoning that sandboxes can simply be pinned one-per-worker — true, and
it answers a different question: it makes many sandboxes run concurrently,
it does not make *one* sandbox run in parallel, and pinning is a reasonable
intermediate state but a dead end as a destination. So `Sandbox` becomes a
**handle** (an id, its system port, a reference to its driver) rather than
the instance itself, and the memory-model field already in
`module-metadata-and-shards`' compatibility key (`:memory`, `:unshared` in
every build produced today) becomes a genuine fork: a shared-memory
build needs the wasm threads proposal, atomics, bulk-memory, its own build
configuration, and on the web, cross-origin isolation headers from the
embedder — none of which exists yet. This is also the strongest argument for
the driver owning memory rather than the module choosing it: an embedder
that cannot set isolation headers simply gets the single-threaded driver and
an unshared module, and the identical program runs on both, rather than a
program having to choose its own deployment constraints at compile time.

**Designing for K > 1 without yet building it: split what a sandbox owns
from what one execution context owns, while it is still a free refactor.**
Today one `Rt` conflates the heap itself with the single thread of execution
running on it — value stack, frames, root stack, gas counter, current green
thread all live directly beside the heap, globals, intern tables, and port
registry. Those are genuinely two different lifetimes: one is the *sandbox*,
the other is an *execution context*, and K > 1 simply means K of the second
against one of the first. At K = 1 that split is a pure refactor with no
behaviour change, which is exactly when it is cheap to do — after K > 1
actually exists, it becomes a rewrite under a deadline. The chosen path:
**make the multi-threaded entry point exist immediately, correct
immediately, by serialising** — a driver can already hand K threads to one
sandbox, with the sandbox taking a lock so K > 1 is correct but not yet
faster, and the harness becomes real and exercised long before the parallel
collector exists at all; the later work becomes *removing a lock*, not
*inventing an interface no one has ever actually called with K > 1*, which
tends to turn out wrong.

**Two specific shared-mutable-state bugs found and fixed while building
this, both worth keeping as evidence for the general design.** The intern
tables (`flint.strs`) needed exactly one lock **per table, not sharded**,
proven by measurement rather than assumed: on the most string-heavy real
workload available (flint compiling construe), the tables are probed 18,247
times across 4.25 seconds, with a probe costing tens of nanoseconds and a
duty cycle around 0.04% — sharding would be optimising something that is not
actually happening. Unsynchronised, the race genuinely manifests: with two
executors interning the same 3,000 strings, 10–17 of them ended up as two
distinct interned objects on 40 runs out of 40, which is a correctness bug
(`eq` reads "both interned, not bit-equal" as unequal, so the two objects
compare unequal while printing identically) rather than merely a wasted
allocation. The write barrier — the remembered-set list an old object
holding a young pointer needs to be found by — is the hottest genuinely
shared structure in the runtime, hotter than allocation itself, so it stays
strictly **per-executor**, with a collection draining every executor's own
list; draining only the collecting executor's list failed 12 runs out of 12,
since young objects another thread had just stored into old ones were freed
while still referenced.

---

## thread-pool

**A thread pool: two models, and only one of them is close**
*(formerly `thread-pool`)*

**Ratified:** ☐ not signed off

**Status (checked 2026-09-12 against the runtime): still not built — but the
DISTANCE below is stale, and Model A is much nearer than this section says.**
No worker pool exists on either model: `sdks/rust/src/driver.rs`'s
`ThreadPool` dispatches whole sandboxes and the sandbox serialises its
executors, and atoms are not genuinely atomic — `compare-and-set-atom` in
`kin/atoms.kin` is a plain compare-then-`set-slot`. Model B is untouched. But
"Model A is close to a rewrite of the collector", and the recommendation of
Model B that rests on it, no longer describe the tree: under `drivers` the
shared heap, the stop-the-world safepoint, the N per-executor root stacks and
remembered sets, the locked intern tables and the atomic `globals` array have
all landed (`runtime/src/rt.rs`, `runtime/src/gc.rs`, `runtime/src/par.rs`),
with `cargo test -p flint-rt --features parallel --test parallel` passing 7
tests and ignoring none. What is left for Model A is letting two executors run
guest code at once and making an atom's CAS a real one — not the collector
rewrite this section prices.

### What was decided

Two candidate designs, evaluated rather than chosen between outright:
**Model A**, a shared heap across worker threads, giving genuinely atomic
cross-worker atoms and real volatile memory ordering; and **Model B**, a
heap per worker with ports carrying data between them (the Erlang model).
The recommendation is Model B first, because it is close to what already
exists and Model A is close to a rewrite of the collector.

### Why

**Model A requires rewriting the collector, which is the hardest component
in the whole project.** Real cross-worker atomicity needs one heap every
worker can see, which makes allocation contended (per-worker buffers rather
than one bump pointer), makes collection require every worker to reach a
safepoint simultaneously (one worker still running while another evacuates
is exactly the class of corruption this project has already spent real time
chasing), turns roots into N value stacks and N shadow stacks (a change of
degree, not of kind — the existing design already scans precisely, so this
part is comparatively unfrightening), and puts a genuinely sharp edge at
compare-and-swap on a pointer the collector might relocate mid-operation,
constraining where safepoints are even allowed to be.

**Model B needs almost nothing new, because the pieces were already designed
compatibly.** `host-abi` already established that ports transfer by value,
with by-reference sharing as merely a within-one-runtime optimisation —
across two heaps that optimisation simply does not apply, and the semantics
are otherwise completely unchanged. A green thread is already data, so
migrating one between workers is just copying a VM state between heaps
rather than moving a native stack. **The collector needs no changes at
all** — each worker collects its own heap independently, with no safepoint,
no shared roots, and no contention whatsoever. What Model B genuinely does
*not* give is the thing literally asked for: an atom shared across workers
cannot be atomic if the workers do not share a heap, so atoms stay
per-worker. Whether that is actually a loss depends on the real intent — if
the goal is throughput (several documents processed in parallel, several
independent gate runs at once), Model B delivers it and the isolation is a
feature rather than a limitation; only genuinely shared mutable state across
parallel workers needs Model A specifically.

**The cost nobody had priced before this: determinism, and Model B keeps
most of it while Model A spends essentially all of it.** `threads-and-ports`
insisted on a deterministic scheduler and `resource-limits` on deterministic
gas, and construe's gates depend on the second directly. Real parallelism
spends both: interleaving becomes non-deterministic, so any program touching
genuinely shared mutable state stops being reproducible; a snapshot plus the
host's event log stops being a complete replay; "is this candidate cheaper"
stops being an exact, answerable question. **Model B keeps most of this
regardless** — a single green thread's own instruction count stays exactly
deterministic because nothing else can touch its heap, and a program whose
threads communicate purely through ports has a reproducible *answer* even
when its *timing* varies run to run. Model A does not get to keep any of
that. That asymmetry is worth more than it first appears, since determinism
is one of the few properties flint genuinely has that a JIT-based runtime
structurally cannot offer at all.

**Deployment reality may decide this before cost does, and should be
checked first.** wasm multi-threading needs shared linear memory and the
atomics proposal, which requires `SharedArrayBuffer`, which on the web
requires cross-origin isolation headers (COOP/COEP) from the embedder — a
real constraint on where flint can even be deployed. If construe's actual
target (a Cloudflare Worker) does not offer shared memory and wasm atomics,
the entire shared-heap model is simply unavailable there regardless of what
gets built, which is worth checking before costing anything else.
Standalone runtimes (wasmtime, node workers) do support it, so this is a
per-host question rather than a universal one — itself an argument against
making the *core* depend on it either way.

---

## cli

**A native CLI: cross compiler, interpreter, and capabilities**
*(formerly `cli`)*

**Ratified:** ☐ not signed off

**Status (measured 2026-09-11): partly built, and NARROWER THAN THIS SECTION
READS.** The native binary exists and `run`/`compile` take
`:path`/`:fn`/`:with`/`:args`/`:to`/`:optimize`/`:meta`. But it serves FIVE
commands, and `bin/flint` serves thirteen:

    present in the shipped binary: deps, run, compile, test, version
    absent:  build, check, fetch, inspect, paths, targets, task, tasks

So most of the PROJECT surface — building from `deps.edn`, running tasks,
fetching dependencies, reporting paths — exists only in babashka. This section
said "the single native binary exists and is the CLI" without that.

A related seam, also measured: the native CLI does NOT read `:paths` from
`deps.edn` (`flint run :path .` on a project declaring `:paths ["src"]` answers
"no source for …"; `:path src` works), while it DOES read that file for
workspace identity and capabilities. It is partially project-aware and nothing
said where the line falls.

Still to do: `:to :native`, the remaining cross-compilation backends, nREPL,
and the `{:args :capabilities}` entry-map wrapping (arguments arrive today as
the bare vector, not yet wrapped). **`:to :llvm` is built** as of 2026-09-11
(`llvm-ir-target`); this line said it was not, and said so for one reason that
covered two targets.

**Maven's transitive dependency resolution is REQUIRED, not cancelled.** The
status line here said "deliberately cancelled — see the measurement below"
while the body of this same section, a hundred lines down, recorded the
reversal: every dependency kind needs transitive resolution. The measurement
that prompted the cancellation stands as history and is kept below; the
decision it produced does not. Tracked in `ROADMAP.md`.

### What was decided

A per-platform native binary (built via `wasmer create-exe`, with a
hand-written Rust host embedding a wasm engine costed as the fallback if
that fights the release matrix), carrying flint's own compiler and
interpreter, capable of cross-compiling to any supported target, with
capability injection on the command line and `deps.edn` support for git,
npm, and Maven dependencies.

### Why a native binary at all

`bin/flint` is a babashka script, which is fine for developing flint itself
and is a wall for everyone else — the published npm package's entire
purpose was a shim whose job is to say "install babashka first." A native
CLI removes that dependency chain entirely, and only because of a property
specific to this project: flint self-hosts (a byte-identical compilation
fixpoint, continuously asserted), so the compiler *is* a flint program, a
flint program compiles to wasm, and a wasm module becomes a native
executable — the chain ends at one binary with nothing installed underneath
it. That is a stronger reason to build this than developer convenience:
today flint is usable if you already have a Clojure toolchain; a binary
makes it usable if you do not.

### Capabilities: how the design arrived at "a value, not a secret"

This file's original design made a capability a **cryptographic secret
token**. It worked, and then had to defend the crypto it introduced:
constant-time comparison, host entropy for generation, tokens recorded to
guard against replay, secrets sitting inside exported snapshots — four
separate obligations, every one of them a *consequence of the choice*
rather than of the underlying problem. **The decision that superseded it: a
capability is a heap object of its own type, and possession of the object
is the authority — there is no secret to defend at all.**
`(p/open "/etc/hosts" [fs-cap] {:codec edn/codec})`: a stranger calling
`(p/open "/etc/hosts" [])` fails outright, simply because it holds nothing
to present.

**What makes this unforgeable is the value encoding itself, not a secret
guarded by policy.** A capability is a heap reference, and guest code has no
operation that turns a fixnum into a heap pointer, and no way to set the
NaN-boxed tag that distinguishes one — only the host can ever mint one. That
is a *stronger* guarantee than an unguessable string, because an unguessable
string can in principle be guessed given enough tries, and this cannot be
*constructed* at all, by any means, ever. It keeps every property the secret
design was bought for regardless: propagation stays explicit (the capability
is a value the caller must actually hand over), attenuation stays available
(the entry function chooses exactly what goes into each callee's argument
list), and it opens a door the string design had actively closed — **derived
capabilities**, where the host mints a narrower child from a broader one
(`:fs` restricted to a subtree, `:http` to one origin), which is natural for
an object and awkward to express for a bare name. Revocation becomes
trivial too: the host simply marks a capability spent or invalid and
subsequent opens refuse, with no key rotation and no cache to clear
anywhere.

**Snapshot import is the one place this design genuinely needs an explicit
answer, not an automatic one.** A capability is an ordinary heap object, so
a naive snapshot restore would silently *resurrect live authority* — a
snapshot taken from a run holding `:fs` would carry `:fs` straight into
whatever later imports it. So on import, capability objects must be
re-bound by the host or explicitly invalidated, never restored as live
authority automatically. (This is the exact rule `opaque-values` later
finds to be subtly wrong in its original form — see that section for why
*erasing* the id, rather than *re-binding* it, turned out to defeat the
whole point of being able to shelve a running sandbox at all.)

**What this design guarantees, stated precisely, and what it does not.**
The runtime guarantees **unforgeability**: code never given a capability
cannot construct one and cannot receive one through a message, since ports
are not transferable under `threads-and-ports`' original rule. It does
*not* guarantee **containment** — a library handed a capability is simply
holding a value, and a value can be stashed in a dynamic var, closed over,
or written into any structure something else later reads; flint has dynamic
vars, so a caller genuinely can make a capability ambient by binding one,
and the runtime will not stop it. So the actual, precisely-scoped property
is: *you cannot reach a capability you were never given* — not *capabilities
cannot spread*. The first is enforced by the runtime; the second is
discipline, and the CLI's own job is to make that discipline the path of
least resistance by handing the entry function a plain map and nothing
else.

### The `deps.edn` survey, and why maven resolution was cancelled rather than merely deferred

> **REVOKED, 2026-09-11, by the person who is supposed to have decided it.**
> The reasoning below stands as reasoning; the conclusion does not. What the
> survey actually measured was how little transitive resolution would buy
> *right then*, against a standard library that was missing `spec.alpha`,
> `zip`, `data` and `datafy` — and those were implemented shortly afterwards,
> which moves the denominator the argument rests on.
>
> WHAT HAPPENED IN THE CODE IS NARROWER AND WORSE THAN A CANCELLATION.
> `deps-of` in `lib/flint/deps/resolve.cljc` reads a manifest and returns real
> transitives for `:npm`; every other kind, Maven included, falls through to
> `{}`. The docstring explains git's omission deliberately — its `deps.edn`
> needs a checkout, so the CALLER fetches — and says nothing about Maven,
> which simply returns nothing.
>
> So npm transitives were built, presumably when the gap surfaced there, and
> Maven's silently kept the old behaviour. Meanwhile `flint.deps.mvn` serves
> `pom` from the CLI, with real fetches and caching, and no `.cljc` has ever
> called it: the fetcher this needs already exists and is unreached.
>
> Both npm and Maven need transitive resolution. Tracked in `ROADMAP.md`.

Git, npm, and Maven dependencies are not close to equally expensive: git
(clone at a sha, add its paths) is cheap and is where a flint-specific
library ecosystem would actually live; npm (registry metadata, fetch a
tarball, take the `.cljc` inside it) is moderate; Maven (POM parsing, the
full transitive graph, version-conflict resolution) is much more expensive
than the other two and the piece most likely to end up half-built.

A survey of 135 `.cljc` namespaces sampled from 20 well-known Clojure
libraries — deliberately biased *towards* libraries that already ship cljc,
so this is closer to a best case than the registry's actual mean — found
that **only 8.9% compile cleanly** (meaning nothing silently deleted; a
reader conditional matching nothing quietly deletes the form it stood in,
so a raw "compiles" figure of 20.7% was overcounting mutilated output before
that distinction was drawn). Breaking down the 83 missing-require failures
specifically: 35 need ClojureScript's own runtime (`goog.*`/`cljs.*`), which
flint will simply never have regardless of dependency resolution; 24 need
Clojure namespaces flint genuinely lacks (`spec.alpha`, `zip`, `data`,
`datafy`, `tools.reader`) — not fixable by better *resolution*, but fixable
by *implementing them*, which is exactly what happened afterward (see
`construe-integration-bar`); only 24 are the kind transitive resolution
could actually fix, and only where the resolved artifact itself also
compiles, which at an 8.9% clean rate is roughly two namespaces. **So
transitive resolution was worth at most two working namespaces**, while
implementing `clojure.zip`/`clojure.data`/`clojure.datafy` directly in the
standard library — pure Clojure, no interop needed — unblocks strictly more
for strictly less engineering. The conclusion is stated as the opposite of a
future roadmap item: the expensive half of Maven support is cancelled, and
the effort it would have taken was redirected into the core library
instead.

What actually got **built**: git, npm, and Maven all resolve one coordinate
at one *exact* pinned version (an exact coordinate reduces to a derived URL
identically for all three, so that half cost almost nothing regardless of
source). What stayed deliberately unbuilt: POM parsing, the full transitive
graph, and version-conflict resolution.

---

## opaque-values

**Opaque values: identity without structure**
*(formerly `opaque-values`)*

**Ratified:** ☐ not signed off

**Status: shipped, and two clauses of the old status line were wrong. Verified 2026-09-12 at 639430e.**
`(opaque)` / `(opaque "label")` and `TY_OPAQUE` are real — `kin/opaque.kin`,
`runtime/src/obj.rs:78`, `flint/opaque` in `dist/builtins.json` — and identities
are **preserved** across a snapshot rather than erased, reversing this
document's own original answer (`runtime/src/snap.rs:269-291`, whose
`count_host_opaques` counts and leaves alone). This decision is heavily cited
across the runtime as the canonical statement of the sentinel-identity pattern.

*How this was checked.* `cargo test -p flint-rt --features diagnostics --test vm
an_imported_snapshot_keeps_its_identities` passes: host id 7 and the identity
hash both survive a capture/restore, and a guest-minted value still reads 0.
A program run on a freshly built `target/release/flint` — minting two anonymous
and one labelled opaque, using them as map keys, forcing 300,000 allocations
between the `assoc` and the `get` — answers `distinct=true self=true
opaque?=true label="label" nolabel=nil labels-not-identity=true
key-after-gc=:first key3-after-gc=:third print=#<opaque label>`, so the stored
(not address-derived) hash holds across a copying collection.

*Two clauses that did NOT hold.* (1) **"never sendable" is false.**
`runtime/src/conc.rs:1430` is `TY_OPAQUE => Ok(())` with a comment retiring the
old rule, and the same program sending an opaque through a channel and reading
it back answers `channel-send=OK same=true`. What is still refused is a
guest-reachable *decoder* (`decode_guest` in `runtime/src/codec.rs`), not the
send. (2) **"reaching the entry function as its second argument" describes a
`flint_main` that no longer exists** (`runtime/src/abi.rs:157-162`,
`src/flint/bundle.cljc:147-151`; removed by `structured-ports` step 5). A host
names a function through `flint_call`, and a host-minted opaque travels as an
ordinary encoded argument (`K_SENTINEL`, `runtime/src/codec.rs:232`) or over a
port. The `{:args :capabilities}` entry map is still unstarted.

### What was decided

A single value type, `TY_OPAQUE`, generalising `cli`'s capability design:
rather than a capability-specific type, **an opaque value in general** —
identity, and nothing else, mintable by guest code (`(opaque)`,
`(opaque "label")` — the label is print-only and plays no part in identity)
or by the host across the ABI, with the two kinds distinguished purely by
**provenance**.

### Why this generalisation is worth more than the capability it grew out of

flint has no `(Object.)`. In Clojure the unique-sentinel idiom is entirely
ordinary — a private, un-forgeable marker distinguishing *absent* from
*present and nil*, a key nobody else could collide with, a private marker a
protocol can check for. flint has no host classes at all, so there was
simply no equivalent, which is a real gap in the language having nothing
inherently to do with the CLI's capability problem. An opaque value is
exactly that idiom, plus the one extra property `cli` needed.

**The trap this generalisation creates, and it has to be named plainly:**
once the type is available to guest code, authority can no longer be
determined by "is it opaque" — a program could simply mint its own and try
to present it. So a capability check must always be **the host recognising
this specific object in its own grant table, never a bare type test.** The
type is necessary and nowhere near sufficient, and this needed stating
explicitly here precisely because the generalisation is what introduces the
hazard — with a capability-*only* type, "is it a capability" would have been
a perfectly sound check, and a later reader could reasonably have assumed it
still was after the type widened.

**The hash must be a stored field, never derived from the object's
address.** The nursery is a copying collector, so an object's address
changes under collection — an address-derived identity hash would silently
change too, and a value already sitting in a map as a key would become
unfindable by that very key the moment a collection moved it. A stable id is
assigned once at creation and stored in the object header instead, exactly
as the JVM does for its own identity hashes — flagged as the single most
likely thing to get wrong here, because it fails only intermittently, only
under load, which is the worst possible way to discover it.

### Later: why erasing the host id on snapshot import was the wrong half of a right instinct

This document originally said a host-minted id must be re-bound *or
invalidated* on snapshot import, "otherwise importing a snapshot taken from a
run that held `:fs` grants `:fs`" — correct as a conclusion, and the
mechanism actually built for it (erasure) was wrong. **Possession was never
the check; the grant table is** — that is this same document's own central
claim — and that fact alone settles the import case without erasing
anything: a host that no longer wishes to honour id 7 simply refuses it,
exactly as it already refuses an outright forgery, while a host that *does*
want a shelved sandbox to carry on can rebind 7 to a live resource. Erasing
the id took that decision away from the only party actually entitled to make
it. The practical failure that surfaced this: erasure made shelving
pointless in exactly the case `snapshots`' live-set format exists to serve —
a sandbox holding a file handle came back holding a handle to *nothing*,
with no identity left to rehydrate against at all. What keeps a preserved id
safe is not the import step, but the *surface* around it: guest code can
only ever mint an id of 0, and there is deliberately no builtin that reads a
host id back out — so an id remains a thing only the host ever wrote and
only the host can ever read, whether it arrived by original construction or
by import.

---

## workspace-capabilities

**Capabilities are granted per workspace, and guarded per dependency**
*(formerly `workspace-capabilities`)*

**Ratified:** ☐ not signed off

**Status: partly built — and two items this line called "not built" have shipped. Verified 2026-09-12 at 639430e.**
The namespace resolver, grants, workspace guards, var guards, the request
primitive, **virtual namespaces** and **pods** are in. Still not built: the
load-time reference-guard binding (step 9 — the one case compile-time-only
guarding cannot cover) and the host-facing token half (step 10). This is
the densest and most heavily reworked decision in the project — several of
its own earlier sections are explicitly superseded by later ones within the
same document — and it is treated at full length here for that reason.

*How this was checked — by running programs that must be refused, with controls
that must be allowed, because this guard fails open and was inert in the
shipped binary earlier this session while reading perfectly well.* All four
against a freshly rebuilt `target/release/flint`:

| ran | answer |
|---|---|
| ungranted workspace names `flint.host/request` | REFUSED: `compile error: flint.host/request is guarded with #{:host} by flint/flint; src does not hold #{:host}` |
| control — same source, `:flint/capabilities-grant [:host]` in `deps.edn` | allowed: `reached: true` |
| `app` requires `acme.thing` across two source roots, `acme/lib` guarding `[:secret]`, `app` granted nothing | REFUSED: `app requires acme.thing, which acme/lib guards with #{:secret}; app/src does not hold it` |
| control — `app` granted `[:secret]` | allowed: `sensitive` |

Virtual namespaces and pods were checked the same way: a local pod
(`test/fixtures/demopod`, `{:pod/path "./demopod"}`) boots, describes and
invokes — `add=6 greet=hello flint` — and an unknown var in it is a *compile*
error, `unable to resolve d/subtract -- pod.demo is a virtual namespace and
does not hold subtract`, which is the optional var list doing its job. This
line's "not built" for both was already flagged as wrong by `ROADMAP.md`
lines 102 and 212; it is now confirmed by running them.

### What was decided

The grain a capability is granted to is the **workspace** (a project, its
`deps.edn`, its own source roots — the unit of third-party identity, of "who
wrote this code"), not the whole sandbox and not the individual function.
`:flint/capabilities-grant` on a dependency says what that dependency's code
may *do*; `:flint/capabilities-guard` on a project says who may *require*
it. A cross-workspace reference to a guarded var is checked entirely at
**compile time**, at the point the reference is resolved — not at
invocation, and (for the ordinary case of code a trusted compiler actually
saw) with **no run-time token at all.**

### Why the grain matters: what was missing before this

`opaque-values`/`cli` already gives the right shape at the host↔sandbox
boundary: a capability a host projects in, which a program *presents* when
requesting a port. That shape has no way at all to say **which code inside
one sandbox** may use a capability — a program that opens `fs` can hand the
resulting handle to any function it calls, including one from a dependency
it never even read. There is no unit of identity between "the whole
sandbox" and "this one value," and therefore nothing to attach a policy to.
Inside one workspace there is genuinely nothing to defend — the author can
already call their own functions freely, so a boundary there would cost
indirection and buy nothing — which is exactly why the workspace, and not
the namespace or the function, is the right grain: every module in one
workspace shares its capabilities, full stop.

**This directly opens the door to babashka pods, without inventing a shape
for them specifically.** A pod becomes an ordinary dependency carrying
`:pod/version`, with an ordinary **virtual namespace** underneath it — the
compiler emits `flint.virtual/call` at any reference into it, and what makes
this tractable is specifically the *guard*, not the grant: nothing can
regulate what a pod does internally, since it is an independent process with
its own authority, but the guard regulates **who is allowed to depend on it
at all** — turning "this code can do anything" from an implicit property of
the build into an explicit decision a project takes and records in its own
`deps.edn`.

### The SDK blocker, and why it recurs

**The SDK had no notion of a project at all** — `Compiler.compile` took a
flat map from path to source, every file a peer, no root, no boundary,
nowhere to hang anything per-project. `reader-tags` had already hit this
exact wall and only partly gotten past it: its own step 3 ("the SDK's
equivalent of the `deps.edn` key") sat undone specifically because the SDK
could not express the concept at all, and the fix that finally lands here —
a single **namespace resolver** both front doors (CLI and SDK) produce,
answering `{workspace, identity, reader}` for a real namespace or
`{workspace, identity, :virtual}` for one served remotely — retroactively
completes that stalled step too, proven by the same test: two workspaces
binding the same reader-tag name to two different readers, compiled
together, each source correctly reading under its own binding. **The
recurring lesson, stated once and then deliberately reused rather than
re-derived**: a value scoped per-workspace has to reach *every* reader that
touches source (`reader-tags` needed three separately-patched call sites
before this was true of tag bindings; `default-features` had recorded the
identical defect earlier still for compile-time feature flags) — a value
only one reader knows about is a value the other readers silently get
wrong, and the fix each time is to compute it once and carry the single
record through every consumer, rather than trusting each one to ask
correctly.

### The virtual-namespace mechanism: what the compiler emits, and why it is almost nothing

Seeing the resolver's `:virtual` flag, any reference into that namespace —
a call, a value use, a function passed as a value — becomes a call into a
small library (`flint.virtual/call`, `/get`, `/fn`) naming the target with a
quoted symbol, and **that is the entirety of the compiler's own part**: no
stub namespace generated, no new emission path, no port machinery inside the
emitter at all — an ordinary call to an ordinary function, with the resolved
name carried as a compile-time constant for any diagnostic that needs it.
The library, not the compiler, does namespace-to-port resolution (opened
lazily, on first use, which is the better answer to an earlier open
question: a program that never actually calls into a pod never even asks
for the authority to reach it, which is precisely the property a guard is
supposed to provide), memoisation (a thousand calls into one pod cost
exactly one port), and request/response correlation over that port.

**The var list a virtual namespace describes is deliberately optional, and
that single design choice is the whole answer to a harder-looking
problem.** With a var list, every ordinary compile-time check already works
untouched — an unknown var is a compile error with the existing message, a
wrong arity is caught exactly where every other wrong arity is caught.
Without one, any symbol resolves and both of those become run-time errors
instead, in a language where they are otherwise compile-time. Crucially, the
**emission is identical either way** — the var list buys checking, not
codegen — so a build can choose, and must be able to *say* which mode it got
for any given namespace, rather than leaving "was this checked, or merely
trusted" to be discovered only when something throws in production. The
list itself can come from exactly one place asked in exactly one way,
deliberately not a second, separately-maintained description format: `:list`
in the very same protocol used for calls, which a build willing to pay the
cost of a live pod process during compilation can ask for and cache.

### Guarding the reference, not the invocation — the design that made the run-time half nearly unnecessary

The original worry that forced this file toward run-time tokens in the
first place: anything a macro can emit, a hostile author can simply read out
of `--explain` and type by hand, bypassing the macro's own guard entirely.
The resolution that dissolves most of that worry rather than defending
against it: **a guard checks the reference to a guarded var, at the one
static site in source where that reference textually appears — never the
eventual call.** `(map fs/read paths)` hands a guarded function to `map`,
which lives in a completely different workspace and does the actual
invoking — but the reference `fs/read` appears in exactly one place, in the
*caller's* source, at compile time; guard it there, and once obtaining the
resulting closure is permitted, the closure is simply an ordinary value
again, and `map` calling it is fine — the caller chose to hand it over,
which is delegation, and delegation is allowed everywhere else in this
design for the identical reason. **Guard every rung and the ladder has no
unguarded rung**: if the raw builtin underneath a convenience macro is
*also* guarded, the hand-written bypass is refused at compile time exactly
as the macro path would have been, which is what makes "read the expansion
and copy it by hand" no longer a way around anything.

This is a genuine two-part answer, not one substituting for the other:
**authority to act** is lexical — it travels with the code that was granted
it, and the caller has nothing to do with it, which is the property a
dynamic-var-based design was explicitly shown *not* to have (a function from
workspace A called from B would read B's dynamic bindings, so A would lose
its own authority merely by being called from somewhere else — a fatal flaw
on its own, plus a confused-deputy hazard where a caller substitutes its own
`:net` token where a callee expected `:fs`, with no privilege escalation but
a real confusion nonetheless). **Permission to call** is a property of the
*caller*, checked once, at the reference site, against the referencing
workspace's grants. What makes the emitted form of this safe rather than
merely convenient is that it must never be a **callable builtin**: a
`resolve`-style builtin taking a quoted symbol would be a general reflective
lookup any hostile namespace could invoke directly, defeating the entire
scheme by the same route a dynamic `*ns*`-based design would have — so it
has to be an analyzer construct with no builtin and no var behind it at all,
lowered directly to a load-bound slot at compile time.

### What is undecided, stated candidly rather than left implicit

Whether a grant is transitive (if A grants `fs` to B, and B requires C, does
C have it? — leaning **no**, since that keeps the dependency graph
auditable, at the cost of making vendoring more of a chore); what a
capability *name* actually is at the boundary between a build-time concept
that must resolve to *some* run-time value and `opaque-values`' rule that
the runtime itself has no concept of "capability" whatsoever; and whether
the guard is checkable at build time only, given that an image loaded at
run time (`cli`'s `flint_load_image` path) arrives having been checked by no
compiler this system trusts at all.

---

## system-namespaces-and-deps

**System access and dependencies are virtual namespaces the CLI serves**
*(formerly `system-namespaces-and-deps`)*

**Ratified:** ☐ not signed off

**Status: partly built, and the old line had the "not built" list backwards in two places. Verified 2026-09-12 at 639430e.**
In: virtual namespaces; `flint.sys.fs`/`env`/`slurp` and
`flint.deps.npm`/`mvn`/`git` (six served namespaces, 24 vars —
`./bin/check-sys-catalogue` answers `ok 6 served namespaces, 24 vars, same
order`, and they are the six in `cli/src/sys.rs` and `cli/src/deps.rs`); the
`.cljc` resolution plan; `flint deps add`; pods; **and the whole rest of the
`flint deps` surface** — `tree`, `why`, `pin` and `bump` all work, which this
line said were not built. Still not built: `flint.sys.net`/`proc`/`clock`;
deleting the old babashka-shelling dependency path; **and capability
delegation on dependency entries, which this line said was in and is not.**

*How this was checked.* Against a freshly rebuilt `target/release/flint`:
`flint deps tree` answers `left-pad 1.3.0`; `flint deps why left-pad` answers
`left-pad 1.3.0 -- declared directly`; `flint deps pin` answers `pinned every
transitive into :flint/overrides` and writes the `:npm/integrity` hash into
`deps.edn`; `flint deps bump :minor` answers `nothing to bump`. A program
requiring `flint.sys.fs` answers `deps=true nope=false` when run with
`:with [fs]` and `SecurityException: this sandbox was given no system port, so
it cannot ask for "flint.sys.fs"` without it — the refusal and its control.
Requiring `flint.sys.net`, `flint.sys.proc` or `flint.sys.clock` answers
`no source for flint.sys.<x>` in every case. `bin/flint` still shells out to
`git`, `curl` and `unzip` at lines 648-714, so the babashka path is still there.

*The delegation correction, which is a capability claim and therefore was
probed rather than read.* `system-namespaces-and-deps`' own three delegation
rules are **inert in the shipped binary**. `lending-errors` in
`lib/flint/deps/resolve.cljc:383` implements rules 1 and 2 and **has no caller
anywhere in the tree**; `flint deps add` (`cli/src/depscmd.rs`) never asks for
or writes a grant, so rule 3 is absent too. Running it: a project holding
nothing and writing `:flint/capabilities-grant [:host]` on a dependency entry
— the exact "mint authority from nothing" case rule 1 exists to refuse — is not
refused. `flint deps tree` answers `left-pad 1.3.0` followed by `note: left-pad
-- :flint/capabilities-grant is not a key npm understands, and is ignored`, and
the same for a `:local/root` entry. The key is not in any kind's known-key set
(`lib/flint/deps.cljc` `coord-notes`), so it neither grants nor refuses: it
does nothing at all, silently, in the direction that reads like success.

### What was decided

Three previously-separate problems, all really the same shape: `flint.fs`
looked like an ordinary language namespace (shipping in `lib/`, alongside
`clojure.core`) while actually being one host's private convention; there
was no networking at all, not even a partial implementation; and dependency
fetching was babashka shelling out to `git`/`curl`/`tar`/`unzip`, with the
shipped 2.6 MB native binary having no dependency surface whatsoever. The
fix: **`flint.sys.*` and `flint.deps.*` are virtual namespaces
(`workspace-capabilities`' mechanism) served over RPC by the CLI**, so a
reader can tell from the *name itself* whether something is served by a
host or built into the language.

### Why the naming split is the whole point, not a style preference

Three properties fall directly out of making these namespaces genuinely
virtual rather than merely conventionally separate: they cannot be linked
into a pure module at all, because there was never any code to shake out in
the first place — a program mentioning `flint.sys.fs` on a host that does
not serve it fails honestly when the port simply does not open, rather than
three calls deep inside code that looked like part of the language;
`flint inspect` can list exactly what a program asks the world for, because
a virtual namespace is a *require the artifact records*, not code it
silently absorbed; and a different host is free to serve the same namespace
differently, which is not a hole in the design — it is what makes
`flint.sys.fs` correctly mean "the filesystem this particular host chose to
lend," rather than implying a filesystem the language itself somehow
promises.

**The capability split is drawn by authority, not by convenience — two
operations share a namespace exactly when granting one would already have
been enough to do the other anyway.** `flint.sys.slurp` (`:slurp`) is
deliberately split from `flint.sys.fs` (`:fs`) on precisely this basis:
`slurp` is a pure key-value read and nothing more — `(slurp "file://...")`
and `(slurp "https://...")` are the identical question, *give me the bytes
at this name* — while `:fs` alone exposes structure and mutation (list,
stat, walk, mkdir, write, delete, rename), and a program reading one
configuration file should never be forced to hold the capability that can
enumerate an entire disk. That `file://` and `https://` share one capability
is the genuinely uncomfortable half of this decision, since they are not
really one authority — a URL fetch sends the URL itself to somewhere — and
the answer follows `workspace-capabilities`' own already-settled principle
for exactly this shape: the compile-time guard stays coarse ("this
workspace may read by name at all"), and the host's *grant* carries the
actual fine detail (a scheme and host allowlist), because the guard is
checked where a var is *referenced*, and the URL itself is only ever a
run-time value — a guard that pretended to vary by scheme would be a check
that *looks* stronger than it actually is, which this design refuses to do
anywhere.

**A dependency's capability grant is a delegation, and needs its own three
explicit rules, each closing a real hole in what would otherwise be a
loophole in the whole system.** (1) *You cannot lend what you do not hold*
— a grant written on a dependency entry that the requiring project itself
was never granted is refused at read time, naming both sides; without this
one rule, `deps.edn` itself would be a way to mint authority from nothing.
(2) *A dependency declaring a guard must actually be granted it, or the
build refuses*, naming the dependency and the missing capability by name —
the guard already refuses the bare `:require`; refusing it again at the
dependency-entry level says so at the exact place a person reading the
manifest can actually fix it. (3) `flint deps add` **writes the grant it
found**, after explicitly asking, rather than silently either granting or
withholding it — because granting a capability is a real decision, and this
tool interaction is the moment that decision should be made visible in a
diff rather than happening implicitly somewhere else.

**Grants can only narrow as they descend the dependency graph, never
widen — one rule, applied uniformly from the invoker at the very top all
the way down to the deepest transitive dependency.** This generalises the
same rule `:fs`'s root-scoping already followed on its own (a derived grant
can only ever be a subtree of its parent, never a superset) into the single
governing rule for the whole delegation chain, which is what keeps the
entire chain auditable purely from the top: nothing below the root can ever
add authority that was not already present above it. Enforcing this per
*workspace* rather than per *whole-program* needed one further real design
decision, and an initially-obvious answer was tried and specifically
rejected: wrapping a guarded reference in a closure carrying the referencing
workspace's options does not work, and not merely as a matter of taste — it
crashes outright the moment `apply` reaches it, since a park reached through
`apply` is refused, meaning a wrapper of exactly this shape breaks on the
very first capability that does any I/O at all, which is effectively all of
them. **The answer that actually works is binding policy to the *port*
itself, rather than to any value that travels through the guest.** A
virtual namespace's port is memoised per **(namespace, workspace)** pair
rather than merely per namespace, so two different workspaces talking to
`flint.sys.slurp` each get a genuinely distinct port; the host binds each
workspace's fully narrowed, effective policy to its own port at grant time;
and every message arriving on a given port is, by construction, from that
one workspace and no other. Nothing is allocated per call, no policy value
ever enters the guest heap at all, `apply` is never involved, and a
transitive dependency simply gets its own port and its own policy with
nothing manually threaded through the program to make that true.

### The `flint deps` design: git resolves once, at `add` time, not on every build

`:git/version` — a semver range resolved live against tags — was **built and
then deliberately removed**, and the removal, not the original addition, is
the decision worth keeping, because the addition looked obviously
reasonable at the time. It was wrong in two independent ways: a live range
makes *every build* into a network resolution, so what a checkout even means
silently depends on when it happened to run — the exact objection this
project already raises against tracking a mutable git branch directly — and
it answers the wrong question regardless, since what actually causes pain in
practice is not naming *a* version, it is **two different dependencies
naming two different, disagreeing tags of one shared repository** — a range
does not resolve that disagreement, it merely gives each dependency its own
private way to be locally "right." So resolution happens exactly **once**,
at `flint deps add`, which picks the current highest matching tag and
writes it down permanently as `:git/tag`, with `:git/sha` recorded
thereafter purely as **integrity** (the tag must independently resolve to
that exact commit or the plan is refused outright), never as the identity
itself. `flint deps agree` is the tool this removal makes necessary — it
detects two dependencies naming disagreeing tags of the same repository
(folding equivalent URL spellings together first, since a naive string
comparison would miss the conflicts that actually occur in practice),
proposes only a tag *someone already explicitly asked for* rather than going
looking for something newer on its own initiative, and writes the resolved
sha alongside the agreed tag.

**Pins live in `deps.edn` itself, under `:flint/overrides`, rather than in a
second lockfile format — everything is pinned by default, with no floating
left anywhere.** One file, in the same language as the primary
declarations, is a deliberate choice: a person can read a pin, edit it
directly, and understand exactly what it did, where a separate lockfile in
a different format would be a second thing that has to be kept
independently true — and `reader-tags` is this very project's own record of
what that kind of duplication costs when it inevitably drifts. Pinning a
transitive dependency and forcing a specific version turn out to be the
identical operation under this design, which is exactly why they share one
spelling rather than two.

### An honest ledger: what building this actually found, not merely what it claimed to prevent

**A genuinely general park-across-a-Rust-frame bug, discovered here and
initially miscategorised as a virtual-namespace-specific problem.** The
first version of this record claimed the crash was specific to virtual-
namespace calls and was a divergence between the wasm and JVM/CLR ports.
Both claims were wrong, and the correction is the substantive finding: what
had actually been measured was one instance of a much more general bug —
**any park occurring underneath a Rust stack frame crashes, on every
runtime, with no ports and no virtual namespaces involved at all** —
demonstrated with a lazy seq containing nothing but an ordinary channel
receive. `apply` (a Rust opcode) and lazy-seq forcing (which calls back into
the interpreter from Rust) both crash; `loop`, and higher-order functions
written in flint itself like `mapv`/`reduce`, do not, because they never
cross a Rust frame in the first place. The root cause: `apply`'s deep-park
handling correctly avoids pushing a result on top of an already-saved
continuation, but never reclaims its *own* operands sitting underneath that
continuation — every later stack-top computation ends up off by that
uncorrected amount, and the eventual panic surfaces nowhere near the actual
park that caused it.

**Refusing to park in these positions was tried as the cheap fix, and
rejected outright as unacceptable, not merely as unfinished.** Making every
one of these code paths reach the existing "cannot park here" guard and
throw a clean, catchable error instead of crashing would make whether
parking legally works depend on whether a particular form happens to be a
compiler-emitted opcode versus flint-defined library code — `mapv` would
work and `map` would not, `reduce` would work and `apply` would not, with
nothing in a program's own source indicating which is which. A language
where `(map f xs)` and `(mapv f xs)` silently differ on whether `f` may
safely block has a leak in it, and moving that leak from "crashes" to
"throws a confusing exception" does not close it — parking genuinely has to
work across these frames, which is recorded here as real, unfinished,
correctly-scoped work rather than closed off with a workaround. Four
different fix attempts at the stack-bookkeeping level were each tried,
measured against all three observed symptoms, changed none of them, and
were reverted rather than left half-working in the tree — because the
actual problem is that a native function's own execution state cannot be
saved and resumed at all yet, not that the existing bookkeeping around it is
subtly wrong.

**The Rust CLI binary's size claim, quoted in its own README for a long
time, was simply wrong by roughly an order of magnitude, corrected by
actually measuring rather than continuing to estimate.** This document's own
first guess for adding an HTTP client, git support, and archive handling was
"plausibly 15–25 MB." The real, measured cost: `ureq` + `rustls` (the entire
TLS stack, used by `slurp`) added 1.1 MB; `flate2`/`tar`/`zip`/`semver`/
`sha2`/`serde_json` together (used by npm and Maven) added a further 0.4 MB
— and, notably, adding a dependency to `Cargo.toml` changed the binary size
by *nothing at all* until something actually called it, since link-time
optimisation and an aggressive size-optimised build profile were already
removing any crate nobody used. The stated resolution going forward is to
measure each crate as it actually lands rather than continue predicting in
a table, and the wrong guess is left visible in this record rather than
quietly edited away, on the same "measure, don't estimate" principle this
whole file applies everywhere else. One further deliberate exception to
"just pull in the crate," recorded so it reads as a considered choice rather
than an inconsistency: git support shells out to the **`git` program**
itself rather than depending on `gix` or `git2`, because the only two
operations actually needed — `ls-remote --tags` and a depth-1 fetch of one
sha — do not justify pulling in either crate's considerably larger
dependency tree, and `git` the program is already present wherever anyone
would plausibly be fetching source from git at all.

---

## other-hosts

**SDKs, and other host targets**
*(formerly `other-hosts`)*

**Ratified:** ☐ not signed off

**Status (re-read 2026-09-11): partly built, and THIS LINE HAS BEEN MISREAD.**
The native target works — flint's own runtime runs with no wasm engine present
at all, and `bin/flint` is built on it. The compiler running as native code
took a compile from 15.6 s to 3.5 s, in a 2.1 MB binary rather than 7.1 MB.
Native AOT is not built.

**"Compiled through LLVM" here means RUSTC, not a flint→LLVM backend.** The
runtime is Rust, and every Rust binary is compiled through LLVM. This sentence
reads as though flint emits LLVM IR, and it does not: `flint.emitter` is "AST
to bytecode", `flint.aot` is "bytecode to wasm", and nothing in `src/` or
`lib/` emits IR. `:to :llvm` refuses.

The distinction matters because the refusal in `cli/src/main.rs` compounds it:
`"llvm" | "native"` is ONE match arm for two different targets, refused with a
reason — "emitting a native artifact needs a linker" — that applies only to the
second. Emitting IR is writing a `.ll` or `.bc` file and needs no linker. The
real blocker for `:to :llvm` is that no IR emitter exists. The JVM and CLR ports are the rest of this document; see
**Status (per the record; not independently verified): partly built.** The native target (flint's own runtime, compiled
through LLVM to run with no wasm engine present at all) works, and `bin/flint`
is built on it — the compiler running as native code took a compile from
15.6 s to 3.5 s, in a 2.1 MB binary rather than 7.1 MB. **Native AOT is now
built** (2026-09-11, `llvm-ir-target`): `flint compile :to :llvm
:optimize [perf]` emits compiled arities as LLVM functions against the same
helper ABI the wasm emitter uses, and `nativeabi/` is the archive they link
against. This line said native AOT was not built, and it was not until that
change. The JVM and CLR ports are the rest of this document; see
`jvm-runtime` and `clr-runtime`.

### What was decided

Three tiers for reaching a new host, priced very differently: **tier 1**, a
thin SDK wrapping the existing wasm ABI (a few hundred lines, works
anywhere a wasm runtime already exists); **tier 2**, porting the VM itself
to a host, leaning on that host's own collector and core libraries; **tier
3**, emitting that host's native bytecode directly. And, orthogonally to all
three: **the conformance suite is the actual specification, not the
bytecode** — a host target is finished when it passes it, full stop.

### Why the tiers are priced the way they are, and why measurement overturned the ordering for the JVM specifically

Tier 1 is cheap specifically because the wasm ABI (`host-abi`) was
deliberately kept tiny — one event queue, one continue call, one drain — so
an SDK over it is genuinely a few hundred lines rather than a project, and
almost every language now has *some* wasm runtime available to build one
over. **This tier was expected to be the default answer for the JVM too**,
until it was actually measured: Chicory (a wasm interpreter written in
Java) runs flint at 500× slower than V8 interpreted and 39× slower even
compiled to JVM bytecode, plus 440 ms of parse-and-compile overhead per
process — disqualifying for a platform where Clojure already runs
*natively*. That measurement, taken cheaply and early specifically to avoid
learning the same thing the expensive way, is what moved the JVM directly to
tier 2 rather than treating it as a later luxury (see `cross-runtime-
benchmarks` for the full numbers).

Tier 2 is tractable specifically because **the bytecode is the portable
artifact** — the reader, analyzer, macro expander, and the entire cljc
standard library already compile to one shared image, so a new host needs a
VM loop over roughly 60 opcodes and the builtins, not a second compiler.
Leaning on the host's own collector and core libraries was the plan that
made this cheap, and it **partly did not survive contact with reality** —
see the superseded note below.

Tier 3 is genuinely easier on the JVM/CLR than it would be natively, and the
reason connects directly back to `dispatch`: flint is an interpreter at all
specifically because wasm locals are not scannable by a collector, so
compiling straight to wasm functions would put live references where a
linear-memory collector cannot see them. **The JVM and CLR scan their own
stacks**, so that particular constraint simply does not exist there, making
a native tier-3 backend a legitimate option on those hosts rather than a
fight against the platform. It was in fact **built for both** (`jvm-runtime`,
`clr-runtime`), as small host-native emitters (358 lines of Java, 379 of
C#) that compile arities on first call — giving up the "keep every compiler
inside flint so cross-compilation is free" property this document argues
for elsewhere, taken deliberately because writing a class-file encoder and a
PE/metadata encoder *in flint itself* costs more than it buys today; the
wasm backend (`flint.aot`) keeps that property intact for the target that
still has it.

### The superseded plan: "lean on the host's collector" did not hold

**Superseded, 2026-08-29.** Both ports now carry a verbatim port of `gc.rs`
— generational, copying nursery, mark-and-sweep old space — over a flat
space of their own, rather than leaning on the JVM's or CLR's collector.
Leaning on the host collector *worked* and shipped, but it left the two
ports with **nothing to compare** — and the invariant this whole project
actually rests on is not "each runtime works," it is that all of them make
the *same decisions at the same points*, which `conform-hosts` now checks
directly by comparing collection counts across ports and failing if they
diverge. The tier judgement itself still stands — porting the VM and only
later replacing the collector was cheaper than starting at tier 3 outright —
only the collector-reuse half of the original plan is retired.

### Why the portable guarantee has to be the conformance suite, and not merely "the bytecode ran"

The bytecode makes a port *cheap*; it does nothing on its own to make two
ports *agree*, and several real hosts diverge in ways that are easy to miss
entirely because each one looks correct in isolation: regex engines differ
in lookbehind, backreferences, named groups, and Unicode class handling
(Rust's own `regex` crate has no backreferences or lookaround at all, which
is what shapes flint's chosen subset in `strings-and-matching`); the JVM and
CLR are UTF-16 where flint is UTF-8, so `count`/`subs`/indexing disagree on
anything outside the BMP unless code-point semantics are pinned and
enforced explicitly; the JVM's `long` wraps silently on overflow where
flint's number tower throws, so a port needs checked arithmetic everywhere;
and — flagged as **not cosmetic** — hash and map-iteration order differing
between hosts would make `pr-str` of a map differ per host, which would
break content-addressed artifacts hashing identically across deployments,
a property construe genuinely depends on. So: a host target is finished
specifically when it passes the shared conformance suite (started from 130
expressions run identically on flint and on real Clojure via babashka, so
expectations are checked against an actual Clojure rather than against
memory), extended with exactly these drift cases — because they are exactly
the kind nobody writes into a test suite by accident. Without this, "runs
anywhere" would only mean "runs everywhere differently," which is worse
than not porting at all.

---

## jvm-runtime

**The JVM runtime**
*(formerly `jvm-runtime`)*

**Ratified:** ☐ not signed off

**Status: partly built. The self-hosting claim is verified; three of this line's numbers were stale. Verified 2026-09-12 at 639430e.**
The image loader, the interpreter, the builtins, real programs, several threads
sharing one program, and AOT emitting real bytecode all work, and **the flint
compiler itself runs on the JVM and emits the exact same image the native
compiler does, byte for byte** — that last is the claim that matters and it
holds.

*How the self-hosting claim was checked.* `javac -d <out>
runtimes/jvm/src/com/flint/rt/*.java runtimes/jvm/src/com/_3sln/flint/kgen/rt/*.java
runtimes/jvm/test/*.java` (JDK at `/opt/homebrew/opt/openjdk`), then
`./bin/flint :src bench/progs :fn hello/main --emit-spec :out self.spec`,
`node host/flint-file.mjs dist/flintc.wasm self.spec > self.ref`, then
`java -Xss1g -cp <out> RtSelfHost self.spec self.ref` — the same three steps
`bin/conform-hosts` runs under `FLINT_SELFHOST=1`, run directly here. Output:
`the compiler: 1603 fns, 3065 consts, 100079 code bytes, 159 natives` /
`builtins it wants that this runtime lacks: 0` / `ok 736 initialisers ran` /
`ok the compiler ran and produced 10425 chars` / **`ok and it is byte for byte
what the wasm compiler emits`**, exit 0.

*Three numbers corrected.* **"all 46 opcodes" → 39.** Seven were retired and
their numbers deliberately not reused (`src/flint/emitter.cljc:27-67`, 39
entries; `bin/opcov-gate` says "All 39 defined opcodes"). **"all 155 builtins"
→ 168**; `./bin/check-builtins` answers `the native runtime carries 168, 2 of
them emitted by the compiler` / `ok jvm carries all 168, the 2 mandatory
included`. The port carrying *all* of them is still true — the count moved, not
the claim. (`runtimes/jvm/README.md`'s "141 of the 144 the compiler imports;
the 3 missing are regex" and its "Self-hosting: close, not there" are both
stale against this; the self-host run above needed nothing.) **"all nine
conformance cases" → 25** (`runtimes/conform/*.cljc`); `bin/conform-hosts` was
not run here, so the agreement claim rests on the record and on the self-host
run, not on a fresh diff.

*One figure not reproducible as stated.* "12× on a counting loop" names no
machine and no command, and the tree disagrees with itself about it:
`README.md:118` reads it as AOT over *the JVM port's own interpreter*, while
`runtimes/jvm/README.md:52` records `1.67x` for the same row. `RtAot` asserts
that compiling does not change the answer and reports no timing at all, so
nothing in the tree regenerates the 12×. Treat the figure as recorded without
its method until someone re-measures it and says on what.

### What was decided

`other-hosts` chose tier 2 for the JVM specifically on measurement (Chicory:
500× V8 interpreted, 39× even compiled) rather than on taste — this is that
port, built with its own generational copying collector (see the superseded
note in `other-hosts`) over a flat space, rather than leaning on the JVM's
own GC.

### Why the conformance gate mattered more than the port itself

`bin/conform-hosts` — compile one source, run it on both runtimes, diff the
output byte for byte — is what actually made this port checkable, and it
earned that claim on its very first run: **every divergence it ever found
produced the right elements in the wrong shape, never a crash.** A guessed
type-code table made `int?` false for *every* integer, so flint's own `str`
printed a perfectly good number as `#<unprintable>` — the worst possible
failure for a type predicate, since it makes every type annotation in the
whole program silently vacuous rather than simply wrong. One shared list
type for both seqs and vectors made `(rest [1 2 3])` print as `[2 3]` — `=`
to the correct answer, and visibly different when printed, which is exactly
how an answer gets compared in this project's own tests. Map iteration order
diverged because flint's maps are an array-map below eight entries and a
CHAMP trie above it, so a nine-key map printed the right pairs in the wrong
sequence — the case `other-hosts` specifically calls out as not cosmetic,
closed by porting the hash function bit-for-bit and walking the CHAMP
exactly as the native runtime does.

**Self-hosting the compiler was a strictly better gate than the conformance
corpus, and running it found seven further bugs the corpus's 8/8 passing
score had never revealed.** A tail call was only actually a tail call to
*itself* — both ports optimised self-recursion but took a genuine host
stack frame for any other call target, so *mutual* tail recursion between
three of the compiler's own functions grew one frame per hop, ran for
minutes, and died in a trace of 11.6 million identical frames. `seq?` was
implemented as `seqable?` (true for a vector, map, set, *and* string) —
invisible until something actually dispatches on the distinction, which the
analyzer does: testing `seq?` before `vector?` on an argument vector like
`[& clauses]` made the analyzer treat its own binding form as a function
call and descend into it forever. `assoc` on a **vector** silently produced
a **map** — the right value under the right key, in entirely the wrong kind
of collection, failing only several calls later on something no longer
indexed at all. Getting to a byte-identical self-compiled image took getting
each of these exactly right, because an image that differs even slightly is
a compiler that differs, and that difference would eventually surface in
some program no test in the suite happened to exercise.

**Three lessons about how to even measure a bug like this, learned the hard
way and worth keeping as general technique.** *The depth is the diagnosis*
— "deep but finite" and "genuinely unbounded" look identical in a raw stack
trace and want opposite fixes; only actually counting the frames (11.6
million) separated them, since a large stack alone is not evidence of
anything on its own. *A call log is not a stack trace* — a ring buffer
recording the last functions called also records calls that have already
*returned*, so a `reduce` loop reads back as a repeating cycle and named the
wrong function as the failure site, twice. *What actually found each bug was
a backstop that names a value, not merely a location* — depth-limited
recursion guards in the analyzer and evaluator, each throwing a flint error
carrying the actual node or form, with the analyzer's error printing the
innermost twelve forms *by depth* (not by call-log order) — which is what
caught the vector-turned-map bug directly: the printed trace showed a vector
literal that had silently become a map entry mid-expression.

### Multi-threading needed almost nothing new, because most of what it needed was already true for other reasons

There is no safepoint machinery to build here at all, because there is no
collector of flint's own to stop — the JVM's own collector already handles
that. What genuinely needed care was flint's own shared state, and most of
it turned out already fine: values are immutable by design, and keywords/
symbols already intern through a `ConcurrentHashMap`, which gives the "one
text, one object" property for free that the native runtime spends an
explicit lock to guarantee (`drivers`). What actually changed: var slots
became an `AtomicReferenceArray`, since a plain array write is only
"eventually visible" to another thread, and "eventually" is not a real
semantics for a language variable. Measured directly: eight threads driving
200 increments each through one atom lose **none** of them — 1,601 wanted,
1,601 got — because `swap!` is a compare-and-swap retry loop rather than a
plain read-modify-write (see `emit-wasm-instead-of-dispatch` for why
enabling that fix once looked like an AOT-specific failure and was actually
a stale-build artefact).

---

## clr-runtime

**The CLR runtime**
*(formerly `clr-runtime`)*

**Ratified:** ☐ not signed off

**Status: partly built, at the same level of completeness as `jvm-runtime`. Self-hosting verified; the builtin and conformance counts were stale. Verified 2026-09-12 at 639430e.**
All **168** builtins (not 155), **25** conformance cases (not nine), several
threads sharing one program, AOT to real IL, and **the flint compiler
self-hosting on .NET to the byte-identical image the native compiler
produces**.

*How the self-hosting claim was checked.* `DOTNET_ROOT=/opt/homebrew/opt/dotnet/libexec`,
`dotnet build -v q --nologo -c Release` in `runtimes/clr/conform`, then
`./runtimes/clr/conform/bin/Release/net10.0/Conform --rt-selfhost self.spec
self.ref` against the same spec and wasm-produced reference the JVM row used
(`./bin/flint :src bench/progs :fn hello/main --emit-spec`, then
`node host/flint-file.mjs dist/flintc.wasm`). Output: `the compiler: 1603 fns,
3065 consts, 100079 code bytes, 159 natives` / `builtins it wants that this
runtime lacks: 0` / `ok 736 initialisers ran` / `ok the compiler ran and
produced 10425 chars` / **`ok and it is byte for byte what the wasm compiler
emits`**, exit 0. The builtin count is `./bin/check-builtins`: `ok clr carries
all 168, the 2 mandatory included`. `bin/conform-hosts` was not run here, so
"all conformance cases agree" rests on the record plus this self-host run.

*One figure not reproducible as stated.* "1.8× on a counting loop" names no
machine and no command. `README.md:118` carries the same figure as AOT over the
CLR port's own interpreter, and nothing in the tree regenerates it — the CLR's
AOT test asserts the answer is unchanged, not how fast it was. Recorded without
its method; re-measure before citing.

### What was decided

The same tier-2 approach as `jvm-runtime` — port the VM, with its own
generational copying collector over a flat space (see `other-hosts`'
superseded note; "lean on the host's collector" was tried and retired on
both ports for the identical reason).

### Why this port is the more interesting evidence, precisely because it was uneventful

**It passed the entire conformance suite on the first run, and that
absence-of-drama is itself the finding worth recording.** Every case that
had cost the JVM port a real debugging session — seqs printing differently
from vectors, guessed rather than copied type-predicate codes, the
namespace/name argument order of `keyword`, array-map-to-CHAMP transition
order — cost this port nothing at all, because `jvm-runtime` and the shared
conformance harness's own README had already written down exactly what each
one was before this port started. **That is the argument for the
conformance harness stated as a measured number rather than as a
principle**: the first port needed the harness to actively *find* four
divergences that all shared the same "right elements, wrong shape" signature;
the second port needed it only to *confirm* there were none left to find.

**Two bugs were genuinely specific to the CLR, both porting-surface
mismatches rather than design questions, and both worth keeping as examples
of "the host library that looks equivalent and is not."** `count` originally
refused to count a cons cell or a lazy seq outright, because neither exposes
a `.Count` property to read directly — the fix is to actually **walk** them
instead, which is also what correctly makes counting a genuinely infinite
sequence hang rather than answer, exactly matching Clojure's own behaviour.
And an error was being represented as a bare host string rather than a
structured value, so `ex-message` on one silently answered `nil`, and the
compiler's own error-rewrapping (catching an error and re-throwing it
annotated with the form it happened in) produced a message with a location
and no actual message text in front of it — a real failure whose report said
nothing at all, which the record calls out explicitly as "the second time
this codebase has paid for exactly that combination."

**Getting the built-in library semantics *right*, not merely present, was
the actual work — porting the mechanical bulk of the 155 builtins was
comparatively easy; matching .NET's own library behaviour to what flint
means was not**, and each mismatch found is a small, specific trap: `Math.
Round` defaults to round-half-to-even, which happens to be exactly what
`rint` needs, but only stays that way if stated explicitly rather than
assumed — the 2.5 → 2.0, 3.5 → 4.0 pair is what pins it in the test suite.
`IndexOf` treats `"a"` as found inside `"A"` under some system locales,
requiring an explicit ordinal comparison rather than trusting the platform
default. The CLR counts UTF-16 code units where flint counts Unicode code
points, which `subs` on a string containing "héllo" is what actually
separates. And `hypot` is deliberately *not* implemented as
`sqrt(x*x + y*y)`, because that naive form overflows for large operands and
underflows for small ones — the platform's own numerically-stable
implementation has to be used instead. The regex implementation, notably,
is **not** a thin wrapper over `System.Text.RegularExpressions` — that would
have been a tenth of the code and a fundamentally different set of
semantics — but the same shared Pike VM (`matching-over-ropes`) every other
runtime uses, fed a program compiled once in cljc, so one engine's
semantics reach every host rather than each host bolting on its own regex
dialect with its own edge cases.

---

## kin

**kin: write a runtime's shared logic once**
*(formerly `kin`)*

**Ratified:** ☐ not signed off

**Status: contradicted by the code, corrected here.** The decision file's own
banner reads "NOT BUILT — a spike... Nothing in the tree uses it yet." That
is false as of this writing, checked directly rather than taken on faith:
`kin/` holds 89 `.kin`/`.drivers` sources, and they generate 89 Rust modules
under `runtime/src/kgen/`, 88 Java modules under `runtimes/jvm/.../Kgen/`,
and 88 C# modules under `runtimes/clr/.../Kgen/` — verified by
`bin/check-kin`, which exists specifically to fail if a generated file
drifts from the kin source that produces it, or if a generated file has no
kin source at all. So the mechanism this file calls a spike nobody uses is,
in fact, load-bearing across all three ported runtimes' shared logic today.
What follows below is the *reasoning* the original document recorded while
building it, which still reads as accurate; only its own top-line status
claim was stale.

### What was decided (or rather, what was measured, since nothing here is a final decision)

An exploration of whether the logic shared across flint's four runtime
implementations — kept as verbatim mirrors entirely **by hand** today — can
be written once, in a small Clojure-like source language, and compiled per
target by treating per-target knowledge as ordinary **code**, not as a data
table a generic translator interprets.

### Why this is worth spiking at all: the cost is not theoretical

Fixing the `apply`-across-a-park bug recorded in `system-namespaces-and-deps`
meant writing the identical thirty lines three times — once each in Rust,
Java, and C# — and the gate caught a fourth port that simply had not been
done yet with the message `opcode 0x1e is not ported yet`. The three
hand-written versions differed only in naming, punctuation, and one
structural rule each — which is the actual bet this spike tests: if that
small residue of per-target knowledge can be expressed as data (or, as it
turned out, as ordinary functions), the much larger shared part can be
written exactly once.

### Why rules are code, not a data table — a real design reversal, with a concrete reason

The first version of this spike made per-target knowledge a table of format
strings, got most of the way there, and then leaked in four separate,
irreducible places: Rust needs call arguments hoisted into temporaries where
the other two languages do not; a loop test that needs hoisting has to be
rewritten into the loop body rather than merely relocated; `mut` has to be
*inferred* from whether a variable's body actually assigns to it, not
declared; and numeric width is a per-call cast. **A translator that
correctly knows all four of those special cases is not a translator with a
configuration file attached — it is a compiler for three languages,
disguised as one wearing a config file.** With each per-target rule
expressed as an ordinary function instead, all four become unremarkable code
living inside exactly the implementation that actually needs them: Rust's
own `set-r` implementation hoists because Rust's own implementation of it
says to, and nothing else in the system needs to know that fact at all.

**Position is not a property of a form — it is pushed down, and the
enclosing form is what actually knows it.** A top-level form inside a method
body is a statement whether or not the underlying construct could *also* be
used as an expression, because the body around it is what knows it is a
body; so the enclosing form scopes `:position` and the implementation reads
it, rather than a form being tagged with a fixed kind of its own. This
directly enables the clearest single proof in the whole spike: one identical
source `if`-expression, compiled three ways, produces a genuine Rust `if`
*expression* (exactly what a person writes there), a Java/C# ternary
conditional (exactly what those languages actually have available), and
braces in all three when the same construct appears in statement position
instead — the same `if`, emitted correctly and differently, entirely because
of where it sits rather than what it is.

### The rule that kept the output honest: generated code may not be worse than a person would write

Stated as a standing constraint on the whole spike, not a nice-to-have: *if
what comes out is worse or less efficient than what a person would have
written by hand, either the rules are wrong or that part should stay
hand-written.* This actually caught three real regressions, each found by
diffing generated output against the pre-existing hand-written original
rather than merely by reading the generator's code: every `while` loop was
initially being rewritten into `while true { if !c { break; } ... }` because
a test that sometimes needs hoisting cannot always stay directly in the loop
condition — fixed so the rewrite only happens when a test genuinely needs a
temporary, leaving the natural `while !c { ... }` form everywhere else. Every
call argument was initially being hoisted into its own temporary, producing
three named temporaries where a person would write none at all — Rust's own
two-phase borrows actually accept one level of nesting like
`self.seq(self.r(si))` without complaint, so hoisting is now applied only
when an argument is itself a call *containing* another call. And
`spread = (spread + 1)` was being emitted where a person writes
`spread += 1`.

### The measurement that actually changed the picture: two "divergences" between the hand-written ports turned out to be nobody's decision at all

Porting real opcodes through the spike surfaced that the three runtimes are
**not** verbatim mirrors even at the statement level in every place — the
`TYPE_P` opcode rewrites its top stack slot in place on the JVM and CLR but
pops-then-pushes on Rust, and `LIST` conses directly off the value stack on
Rust while the JVM first copies every element to a shadow stack. **Both
turned out to be incidental rather than deliberate, and finding that out
mattered more than the port itself.** The `TYPE_P` divergence was originally
believed to be forced by Rust's borrow checker — checked directly, and it is
not: the naive in-place form is refused *twice* by the compiler (once for
the call on the right-hand side, once for the index expression), but reading
the value and the index into locals first compiles cleanly and is exactly
what kin generates, so Rust's runtime should genuinely be in-place too, and
the existing divergence is simply an accident nobody chose. The `LIST`
divergence carried an explicit justifying comment on the JVM side ("cons
allocates, and the value stack is where they are now") that does not
actually hold up: the value stack already *is* a scanned root set in the
collector's own forwarding pass, so a value sitting in it survives an
allocation and gets correctly updated in place — and `cons` itself already
roots both of its arguments before it allocates regardless, so the extra
shadow-stack copy protects against a hazard that was already handled one
level down. **The conclusion drawn from this belongs to the whole project,
not just to kin**: a divergence between runtimes should be treated as
*incidental until someone can point at the language actually refusing the
alternative* — both of these divergences looked principled, one of them
even had a comment explaining itself, and neither survived being actually
checked.

### Coverage came first, deliberately, before trusting any generated port

Regenerating opcode bodies across three runtimes is only safe if a mistake
in the regeneration would actually be *noticed* — so the question "which
opcodes does the cross-runtime conformance suite even execute?" was asked
and measured **before** porting anything, using the diagnostics build's
existing opcode histogram. The result: only 36 of 45 defined opcodes were
ever exercised at all. Tracing each of the nine gaps back through the
compiler found that seven of the nine are **emitted by no code path
whatsoever** — present in the opcode table and fully implemented in all
three interpreters, and produced by nothing. **The port question for those
seven is consequently the wrong question to be asking at all**: they should
either be deleted outright, or the compiler should start actually emitting
them if the optimisation they represent was genuinely intended and simply
never got wired up — porting unreachable code across three languages would
be carefully maintaining three copies of dead code, which is a worse outcome
than the hand-maintenance problem this whole spike exists to fix. The two
genuinely-reachable gaps were each given targeted coverage once the specific
trigger was understood (a function needing more than 256 local bindings for
the wide-local opcode; `==` specifically combined with an `^int` type
annotation, since generic `=` never reaches the specialised equality opcode
at all).

### What else turned out to be shared, measured by comparing the JVM and CLR ports file by file

Opcodes were the obvious target for this spike and are not remotely the
biggest opportunity. Comparing the JVM and CLR mirror implementations file
by file, counting non-comment lines, files implementing collections, tables,
strings, byte strings, snapshots, vectors, the wire codec, sequences,
equality, the regex simulator, and interning all agree to within 0–1% of
each other in length — for genuinely hand-mirrored code, line counts that
close mean the underlying *structure* agrees too, not merely the line
count. That is roughly **4,500 lines per runtime** of logic over data
structures, against only a few dozen lines of opcode bodies — meaning the
actual prize this spike is chasing is almost entirely outside the opcode
interpreter, in the builtins. Rust is measurably the harder third target:
its counterparts are consistently larger than the JVM/CLR equivalents (a
vector implementation is 1,013 lines against 383; the collector is 2,137
against 413), and the difference is ownership — genuinely different work in
some places, and in others exactly the kind of borrow-checker-driven
temporary this spike already showed it can generate correctly. The
recommended attack order is consequently by **ratio, not by size**: the wire
codec first (self-contained, no allocation subtleties, and the format is
already specified elsewhere independently); then equality/hashing/interning/
sequences (small, near-identical, and purely functional); then the larger
collection types, each large enough to want its own dedicated review; and
explicitly **not** the object header, frame, space, roots, or collector
implementations — the divergence in those is fundamentally in what each
*language* makes cheap, and the measured ratios say plainly to leave them
alone rather than force convergence.

### What it deliberately does not attempt

The GC write barrier, the wasm ABI shim, and unsafe heap access are named
explicitly as parts that should **not** be attempted here, because the three
runtimes are genuinely different by nature at exactly those points —
pretending a single shared source could honestly express all three would
produce three things that are each subtly wrong in their own way, which is
worse than three things that are honestly, visibly separate from each
other. The claim this spike actually makes is scoped tightly to 1:1 shared
logic — which is most of an opcode body, most of a builtin, and most of what
has actually cost real debugging time across the four runtimes so far, but
is deliberately not a claim about the whole runtime.

---

## cross-runtime-benchmarks

**Benchmark across wasm runtimes, because every number so far was V8**
*(formerly `cross-runtime-benchmarks`)*

**Ratified:** ☐ not signed off

**Status: the work was done; the harness is BROKEN and none of the ns/instruction figures below can be reproduced today. Checked 2026-09-12 at 639430e.**
This decision directly decided `other-hosts`' JVM tier, and one of its own
central predictions turned out to be backwards once actually measured — both
of those still stand on the record. What does not stand is the table: run
`./bin/bench-xruntime` now and **every engine row fails.**

*What was run, and on what.* `./bin/bench-xruntime` on an Apple M1 Pro,
Darwin 23.6.0, with all six engines the harness looks for present — node
v24.6.0, bun 1.3.14, deno 1.44.0, wasmtime 48.0.1, SpiderMonkey
JavaScript-C140.14.0, wasm3 v0.9.0. Every row came back
`FAILED: Command failed: …` and the ns/instruction table printed nothing.

*Why, and it is two separate breakages, both caused by `structured-ports`
step 5 removing `flint_main`.* (1) A flint module no longer exports `main` at
all — `wasmtime --invoke main out/xrt-0.wasm` answers `no func export named
'main' found`, and the module's exports are `arg_alloc arg_push flint_call`.
That kills the wasmtime and wasm3 rows outright. (2) The JS driver
`bench/xrt-run.mjs` defaults to the hard-coded entry
`construe.bench.xrt25/main` and `bench/xruntime.mjs` never passes a name, so
of the five modules in the fitted family only `xrt-25` has a matching entry;
`node bench/xrt-run.mjs out/xrt-0.wasm` exits 1, while
`node bench/xrt-run.mjs out/xrt-0.wasm construe.bench.xrt0/main` exits 0.
That kills node, bun, deno and SpiderMonkey.

*What still works.* The resident-memory table (`bench/xmem.mjs`) ran and
reported node +13.4 MB, bun +27.2 MB, deno +25.0 MB, wasmtime +0.0 MB,
wasm3 +0.0 MB over a trivial module. The Chicory and workerd rows are measured
by separate scripts (`bin/bench-chicory`, `bench/workerd`) and were not run
here; workerd is not installed on this machine.

*So: the figures quoted below — node/V8 11.0, bun 10.8, wasmtime 9.7, deno
15.9, SpiderMonkey 15.7, wasm3 165.0 ns/instruction, and the 1.44–1.64× /
1.07–1.18× AOT split — name their command and their workload but not their
machine, and the command no longer produces them.* Fix the two entry-point
breakages before citing any of them again.

### What was decided

Benchmark flint's performance across every engine construe might plausibly
run on, not only V8 — because flint's determinism (`resource-limits`) gives
a genuinely apples-to-apples cross-engine metric no ordinary benchmark suite
can claim: the same program executes the **exact same instruction count** on
every engine, so nanoseconds-per-instruction isolates engine speed alone
from any difference in the work being measured.

### Why the answers turned out to be engine-*dependent*, not merely engine-*scaled*

The interpreter's cost concentrates in precisely the construct engines
differ most sharply on — a hot `br_table` dispatch loop, which some engines
compile to a real jump table, some to a chain of compares, and which some
tier up to an optimising compiler and some never do at all. So the same
measurement genuinely decides *different* things depending on which engine
answers it: whether `emit-wasm-instead-of-dispatch` is worth it at all
(marginal-looking on V8's aggressive optimiser, potentially decisive on an
engine that never tiers up), how large flint's cold-start win actually is
(near-zero extra with wasmtime's precompiled `.cwasm`, worse on an engine
that compiles eagerly every time), and how much `namespace-units`' module-
size work is worth (real latency plus real bytes where compile time scales
with module size; only bytes where modules are precompiled and mmapped).

### The measured numbers, and the prediction that turned out backwards

Nanoseconds per instruction, `bin/bench-xruntime`, a module importing
nothing so every engine runs identical bytes with zero host code involved:
node/V8 11.0, bun/JavaScriptCore 10.8, wasmtime/Cranelift 9.7,
deno/V8 15.9, SpiderMonkey 15.7, wasm3 (a genuine wasm *interpreter*) 165.0
— **roughly 15× slower than any JIT engine**, while the spread *between*
JIT engines is only about 1.5× at worst. The practical reading: anywhere a
JIT is present, flint costs roughly the same; on a small embedder with no
JIT at all, it costs an order of magnitude more.

**This document's own prediction about where AOT would help most was
backwards, and the correction is the actual finding, not a footnote.** It
argued AOT would matter *least* where TurboFan already optimises the
dispatch loop aggressively (V8), and *most* on an interpreting engine with
no tier-up (wasm3) — where AOT would supposedly be "the difference between
usable and not." Measured with gas held identical between builds (so it is
provably the same work, not less of it): AOT delivered 1.44–1.64× on the
JIT engines and only 1.07–1.18× on wasmtime and wasm3. **The JIT engines
gained the most, and the pure interpreter gained the least** — because on
wasm3, the wasm *blocks* AOT emits are themselves being interpreted, so the
trade is interpreted-bytecode-dispatch for interpreted-wasm-execution with
little difference between them; only on an engine that turns those emitted
blocks into genuine native code does the dispatch cost actually disappear.
So AOT is fundamentally a JIT-engine optimisation, not an interpreter
rescue, confirmed directly at multiple scales on wasm3 rather than merely
inferred from a curve fit.

**Chicory decided the JVM tier question directly, exactly as intended, and
cheaply.** flint genuinely runs, correctly, on Chicory (a wasm interpreter
written in Java) — 500× slower than V8 interpreted, 39× slower even compiled
to JVM bytecode (Chicory's own compile mode is a real 12.9× speedup over its
interpreter, so both numbers are reported rather than only the flattering
one), plus 440 ms of parse-and-compile overhead per process. That settles
`other-hosts`' tier question the way the document expected: not a viable
Clojure implementation on a platform where Clojure already runs natively, so
tier 1 is out specifically for the JVM and tier 2 (porting the VM) is the
actual route — learned for the cost of a benchmark rather than the cost of
finishing an SDK first and discovering the same thing afterward.

**Resident memory, measured because it is the actual binding constraint
under a Worker's isolate limits, not throughput.** flint adds only about 0.5
MB on the one engine that merely interprets the module (wasm3), but 20–27
MB on every engine that JIT-compiles it — for the *identical* 257 KB of wasm
and 6.3 MB of linear memory in both cases. So under a typical 128 MiB
isolate ceiling, it is specifically the engine's own JIT-compiled code, not
flint's heap, consuming the bulk of the budget — and module size
consequently buys latency *and* memory together on a JIT engine, and
effectively neither on a pure interpreter, which is precisely the mirror
image of what this document originally expected about compilation time.

**The tail latency finding, under the realistic image-per-call deployment
shape, is the opposite of the usual worry about a garbage-collected
runtime.** Under sustained allocation-heavy load, p99 latency sits within
about 6% of the median; at idle (load-and-initialise only), the spread is
wider, around 21%. flint's collector runs inside the wasm heap rather than
depending on the host engine's own pause behaviour, and it collects the
young generation little and often rather than rarely and at length — so
there is no single pause large enough to show up distinctly at the tail even
under real allocation pressure, which is specifically the property a
per-request CPU budget in a Worker actually needs.

**The discipline that runs through this whole document: report every engine
separately, never averaged, including where flint does badly.** An average
across engines describes no deployment anyone actually has — the same rule
`construe-benchmarks` states independently and for the identical reason: a
results table that only contains wins is a marketing page, and the person
reading it has to make a real deployment decision with these numbers.

---

## construe-benchmarks

**Benchmark the decision, not the runtime**
*(formerly `construe-benchmarks`)*

**Ratified:** ☐ not signed off

**Status: done, and it still reproduces — but two recorded figures have drifted badly. Verified 2026-09-12 at 639430e.**
Results are folded into the README (§"What this means for construe") with
`doc/construe-benchmarks.txt` as the full output, and both state their method
and machine — "Apple M1 Pro, Darwin 23.6.0, node v24.6.0", construe's own
258-line seed interpreter and four real annotated contexts, one source file to
both compilers, checked to compute the same answer before being timed. The
cross-engine half of the second clause is now **unreliable**: the engine-labelled
figures came from `bin/bench-xruntime`, which no longer runs at all — see
`cross-runtime-benchmarks`.

*How this was checked.* `./bin/bench-construe` on that same machine
(construe's `node_modules` present, so nothing was skipped). Most rows
reproduce within noise: parse latency 0.079 ms against cherry's 0.054 (1.5×,
recorded 1.4×); cold start 1.25 ms against a V8 isolate's 14.69 ms (recorded
0.998 and 14.59); the 500-case suite 39.42 ms against 29.02; merge, reduce and
map-access rows all within 10-20%.

*Two figures that have moved enough to matter, and the README carries the old
ones.* The compiled module went **289,579 B → 486,052 B** and whole-module
compile time **935 ms → 1,564 ms** — a 68% size increase against the number the
README's footprint argument is built on. In the other direction,
`clojure.string/split, regex` improved from **274.9× cherry to 39.3×**. The
README tables and `doc/construe-benchmarks.txt` are a stale capture, not a
wrong method; re-run and re-fold before quoting the size or the split figure.

### What was decided

Benchmarks should be organised around **the question construe is actually
deciding**, not around "is flint fast" in the abstract: for each place
construe runs code, is flint better, worse, or simply irrelevant there — and
what does that do to the bill? Two facts from construe's own spec sharpen
this considerably: CPU is 96% of what a session actually costs (not tokens,
not bandwidth, so anything moving parse/suggest CPU moves the unit economics
directly), and cherry — construe's current compiler — cannot compile inside
its own deployed Cloudflare Worker at all, which is a live blocker on
promoting any evolved candidate to production today.

### Why each benchmark exists, and what it specifically decides

Real fixtures only — construe's actual seed interpreter and real annotated
contexts dumped from its own annotator, never a lookalike, because a
lookalike measures the wrong shape by construction. **Parse latency** is the
number that touches the bill directly, and the honest comparison is against
cherry-compiled-to-JS running JIT-compiled in node — expected to be a loss
for flint, and reported plainly either way, since an interpreter in wasm
losing to JIT'd native JS is not a failure of the project, and pretending
otherwise would be. **Cold start and footprint** is where flint may win
big instead: a V8 isolate costs real milliseconds to spin up and real
megabytes to hold, where flint measured 0.11 ms cold in a few hundred KB —
this decides whether flint is a genuinely *cheaper sandbox* for running
untrusted, model-written code, which may be the strongest economic case of
all and has nothing to do with throughput. **The 500+ case suite run**
measures whether a GC pause appears mid-suite, since construe's own gates
run exactly this shape on every edit an evolving agent makes, and its own
spec calls that "how one round consumes a month of sandbox time." **Compile
time** matters because flint's compiler is itself flint code and self-hosts,
so it can run *inside* a deployed artifact where cherry demonstrably
cannot — the number that matters there is whether compiling fits a Worker's
CPU budget at all, not whether it is fast in absolute terms. **Heavy
documents** measures memory, not throughput, superseded in its own detail by
`bench/construe/document-resource.md`, which is where that design now lives. **Suggest/prefix scan** is called out
in construe's own spec as "the most expensive unmeasured number" — assumed
at 1 ms, suspected nearer 0.2 ms, and 96% of session cost — cheap to
actually measure here and valuable to construe regardless of what the
number turns out to be.

**The discipline stated explicitly at the end, and it is the rule this
whole document is really about**: report a table per question with the
incumbent placed directly beside flint wherever one exists, close with a
plain-language "what this means for construe" answering specifically
whether flint can serve the read path, whether it is a cheaper sandbox,
whether it unblocks compiling in production, and where adopting it would
plainly cost more than it saves — and **say where flint loses**, explicitly,
because a benchmark section containing only wins is a marketing page, and
the person reading it has to make a real decision with these numbers.

---

## construe-integration-bar

**What "ready for construe" means, concretely**
*(formerly `construe-integration-bar`)*

**Ratified:** ☐ not signed off

**Status: live — a milestone definition rather than a design. Checked 2026-09-12 at 639430e; the status holds, its figures are a mix of reproduced and unreproducible.**
It exists so the handoff to construe is judged against an explicit list rather
than a feeling, and so the work between here and there stays aimed at what the
first real customer actually needs.

*How this was checked.* The document is a bar, not a build: every item below is
a condition, not a component, and `ROADMAP.md:133` independently carries it as
"in progress / live milestone" with the same items open. So "live" is right.
The figures it cites divide three ways.

*Reproduced.* `./bin/bench-construe` on an Apple M1 Pro, Darwin 23.6.0,
node v24.6.0 answers 1.25 ms to first answer against a V8 isolate's 14.69 ms —
this line's 1.00 ms / 14.59 ms, within noise, and still ~12× rather than 15×.

*Not reproducible here, and the blocker item depends on it.* The two workerd
demonstrations — a flint module under workerd with no polyfill, and the flint
compiler itself compiling inside workerd (1,178 ms for a 22-namespace program,
~2 ms to load an 8 KB image, 555 KB loader against 214 KB) — could not be
re-run: **there is no `workerd` on this machine**, and `bench/workerd/` is a
config and a worker script with no installed engine to drive them. Those
figures name a runtime but no machine and no repeatable command. Since this is
the one item the bar itself calls "binary, not a matter of degree", it should
be re-demonstrated on a machine with workerd before the handoff is judged met.

*Superseded by a later measurement.* "Word frequency at 11.6× babashka" is a
`strings-and-matching`/`matching-over-ropes` figure, not one this document
regenerates; the construe run above shows the related `clojure.string/split,
regex` row has since moved from 274.9× cherry to 39.3×, so the string/regex
picture has changed under this bar since it was written.

### What was decided

A concrete bar, grounded in what construe actually *is* rather than in what
would merely be nice to have: construe is a **deterministic**
natural-language-to-structured-constraint transform, with no model running
in its request path at all — the parser is ordinary deterministic code,
evolved *offline* by a model against a growing corpus, gated by comparing
every candidate against the incumbent before it ships, and then parsing
happens per WebSocket frame inside a Cloudflare Worker.

### Why that shape changes which measurements actually matter

Three consequences follow directly from construe's actual shape, and none
of them is the obvious one a casual reader might assume. **The hot path is
per-frame parse latency and cold start, not steady-state throughput** — a
session is a socket, a parse is one frame — which is exactly why flint's
cold-start win (measured at 1.00 ms against a V8 isolate's 14.59 ms) is
worth more to construe than any steady-state speedup could be, and why
`emit-wasm-instead-of-dispatch`'s own +12% cold-start cost for +8%
throughput was specifically the wrong trade *for construe*, even though it
might be a perfectly reasonable trade somewhere else. **The gates need an
exact cost metric, not an approximate one** — they compare a candidate
directly against the incumbent, so "is this candidate cheaper" has to be an
*exact* question with an exact answer, which is precisely
`resource-limits`' deterministic instruction count, and is why determinism
is a genuine product requirement here rather than merely a nicety. **The
code being run is model-written**, which is what `cli`'s and
`workspace-capabilities`' capability work is ultimately in service of: a
parser candidate should be able to reach nothing whatsoever it was not
explicitly handed.

### The bar itself, and what was actually found while checking each item

**A real parser candidate must run and agree, byte for byte, with the
incumbent** across construe's real corpus — not a benchmark standing in for
this, an actual candidate the evolution loop would produce.

**flint must compile inside the deployed Worker — this is the actual
blocker construe has today, and it is binary, not a matter of degree.**
cherry cannot compile inside workerd at all, which is a live constraint on
promoting any candidate; flint self-hosts, so this is the thing that removes
the blocker if it holds up under test, and it was genuinely unverified
before this work. It was verified in two separate, escalating steps. First,
a flint module was run directly under workerd with no polyfill, no WASI, and
no host functions of any kind, confirming the runtime itself needs nothing
special from the deployed environment. Then, decisively, **the flint
compiler itself — as a flint program — ran inside workerd, compiled a real
candidate from an EDN spec, and a separate resident module loaded and ran
the resulting image**, with no linker anywhere in the path. This works
specifically because an image records each builtin it imports **by name as
well as by slot** — slots are meaningful only to whichever module originally
compiled them, but names are portable — so a general-purpose "loader"
module carrying every builtin on the path can re-resolve any given image's
imports against its own table, refusing by name if the image needs
something the loader genuinely lacks. **The consequence for construe's
actual deployment shape**: a Worker should compile once and run many
requests against the resulting image rather than producing a fresh module
per request — compilation (measured at 1,178 ms in workerd for a real
22-namespace program, the standard library accounting for nearly all of it)
is a promotion-time cost, appropriate for promoting a vetted candidate, and
genuinely not something to repeat per request; loading and running an
already-compiled 8 KB image, by contrast, costs about 2 ms each. The
explicit trade this makes: a general-purpose loader carries every builtin
rather than only the ones any one program actually reached, so it is
considerably larger (555 KB against 214 KB) — the mirror image, in the
opposite direction, of the tree-shaking trade `namespace-units` makes for a
single self-contained program.

**Gas must stay exact and must survive actually being useful, not merely
exist as a mechanism.** `resource-limits` is shipped and asserted in
isolation; what specifically was not yet demonstrated by this document's own
bar was that two *candidates*, not just one program run twice, can genuinely
be compared by instruction count in a way the gates can act on — reproducibly
across separate runs and across a redeploy.

**The library surface must cover what a parser specifically needs, and the
question is narrower than "how much of Clojure is implemented."** The
existing deficiency lists were true without being *aimed* at this bar; what
actually matters is whether string handling, regex, maps, sequences, and
sorting are present and not pathologically slow for a parser's specific
workload shape — with `reduced` having silently never worked at all (found
elsewhere, during the AOT work) standing as the concrete warning that core
language features can be entirely absent without a single existing test
noticing.

**Strings and regex, once construe's single largest known gap, closed
substantially.** "Word frequency (regex split)" sat at 56× babashka before
`strings-and-matching` and `matching-over-ropes` shipped, and sits at 11.6×
now — the same workload with the regex removed entirely runs at 7.3 ms
against 54.5 ms before those two decisions landed. The decomposition behind
the original 56× is worth keeping precisely because the first reading of it
was wrong: the regex engine itself was only 27% of the gap, and the
remaining majority was two ordinary quadratic scans hiding inside
`str_index_of` and a per-character `lower-case` — fixing those first made
the *engine* 88% of what remained, which is what actually justified building
the Pike VM rather than continuing to chase string-level fixes.

**Memory per parse must be bounded and known, not merely assumed
reasonable**, since Workers enforce hard memory limits and construe's own
specification independently calls memory the primary bottleneck for
document-shaped work — measured directly, peak live memory per parse and
across a whole session of many parses, rather than estimated from first
principles.

### What is deliberately not on this bar, and why leaving it off matters

Stated explicitly, so the handoff is not implicitly held up by things
construe genuinely does not need yet: AOT (shelved, and the wrong trade
specifically for a cold-start-dominated request path); the CLI (developer
convenience, not a runtime requirement construe's deployment needs); shards
and module metadata (matters once several namespaces ship independently,
which is not construe's current shape); the thread pool (construe parses
exactly one frame at a time); and snapshots, the debugger, and the profiler
(development tools, not runtime requirements). Once the bar above is met,
the flint side of the work continues on its own separate track — the CLI,
general tooling, and whether the AOT question is ever worth reopening — and
the integration work itself moves to construe's own side of the boundary.


## standalone-scripts

**A single file that carries its own dependencies, config and entry point**

**Ratified:** ☐ not signed off

**Status: BUILT, awaiting sign-off**, except dependency FETCHING — see
"`:deps` is surface without a fetcher" below. `flint <file> [args]` runs a
`#!` script through `target/release/flint`; the reader skips a `#!` first line;
`^:script` names the entry; `src` is the file plus what `(:paths [..])` names.

### What was decided

A script is ONE FILE that runs directly:

```clojure
#!/usr/bin/env flint
(ns ^:script the-script-ns
  (:deps {some/lib {:npm/version "1.2.0"}})
  (:require [clojure.string :as str]))

(defn main [_] (println "hi"))
```

**The `ns` form IS the project.** A script has no `deps.edn` and must not need
one — that is the entire point — so everything `deps.edn` would have said moves
into the `ns` form: dependencies, and in time whatever else a workspace
carries. This is not a new configuration language, it is the existing one
relocated to the only file there is.

**`src` IS THE FILE, PLUS WHAT THE SCRIPT EXPLICITLY NAMES.** A script does not
scan the directory it sits in. This is the property that makes it standalone:
dropping a script into a working tree full of `.cljc` must not silently pull
that tree into the build, and a script mailed to somebody must behave the same
in their directory as in yours. Directories join the source path only by being
named in the embedded config.

### Why

The alternative is a script plus a `deps.edn` beside it, which is not a script
— it is a project with one file in it, and it cannot be copied, mailed, or
dropped in `~/bin` as a single artifact. The shebang is what makes a file
executable; carrying configuration in a second file forfeits it.

### What this collides with in the code today

Three concrete obstacles, each verified:

**1. The reader has no `#!` handling.** Nothing in `src/flint/reader.cljc`
treats a leading `#!` line specially, so the shebang would be read as flint
source and fail. A script format needs the reader to skip a `#!` FIRST LINE
only — not `#!` anywhere, which would make a comment syntax out of something
that is a kernel convention about byte one.

**2. ~~`analyze-ns` SILENTLY IGNORES an unknown clause.~~** **Already fixed
when this work started**: the fallthrough throws and names `known-ns-clauses`,
so `:deps` had to be added to that one list deliberately — which is the whole
point of the list. It is there now, with `:paths`, as `script-ns-clauses`.

**They are REFUSED OUTSIDE A SCRIPT rather than ignored there.** A project
already has a `deps.edn`; a second place to declare dependencies that nothing
reads is the same disease one level up — the clause is spelled correctly,
accepted, and does nothing. `analyze-ns` asks `script-entry` and refuses if the
`ns` is not marked.

**3. Nothing names the entry point.** A module deliberately has no entry
(`DECISIONS.md#structured-ports`); today the CLI takes `:fn` or reads
`:flint/main` from `deps.edn`. A script has neither.

**4. A single-file source was keyed by its FILENAME.** `build_spec_with` in
`cli/src/main.rs` inserted a non-directory source under `s.file_name()`, and the
compiler looks a namespace up at `flint.project/ns->path`. So the two had to
agree by accident, and for a script they never can: a script is `~/bin/greet`,
with no extension and a name chosen for the shell. It is now keyed by the
namespace it DECLARES, which is a fix for every single-file source and not only
for scripts.

**5. A file source inherited the `deps.edn` beside it.** The same loop walked up
from a file's parent directory looking for one, and took its reader tags, its
prelude and its capability GRANTS. For a script that is precisely the hazard the
feature exists to avoid — drop `greet` into a working tree and it silently
acquires that project's authority — and it fails open: the script compiles,
runs, and says nothing. A source that is a FILE now inherits nothing.

### Decided here, 2026-09-11

**THE ENTRY POINT: `^:script` names a convention, and `^{:script go}`
overrides it.** Both, not either. `^:script` is what everyone will write and
`main` is what everyone will call it, so the bare flag has to mean something;
and a file whose entry is called something else needs an exact way to say so
rather than renaming its function to suit the launcher. `flint.analyzer/script-entry`
is the one function that answers it, and the CLI scans for the same mark.

A file NOT marked is refused rather than run with a guessed entry. A module has
no entry by design, and inventing one is what this codebase refuses everywhere
else.

**CAPABILITIES: A SCRIPT DECLARES NONE.** `:flint/capabilities-grant` does not
move into the `ns` form, and the reasoning is `AGENTS.md` §5's distinction
rather than an analogy to `deps.edn`: a **grant is conferred from outside** and
a **guard is an author's assertion about their own var**. A script's `ns` form
is written by the script's author, so a grant there is an assertion the caller
made about itself — the exact shape that let a var's own guard authorise its own
body, which was measured, compiled, and removed.

So the only grant is `:with`, given by whoever runs it:

```
#!/usr/bin/env -S flint :with [fs]
```

which `env -S` splits, and which is visible on line one of the file to anyone
reading it and on the command line to anyone running it. A script with no grant
is a pure function of its arguments whose return value is printed, which is
already the useful case.

This costs nothing, because of the next decision.

**A SCRIPT MAY NOT BE A DEPENDENCY.** Nothing requires a script: its directory
is not scanned, no `deps.edn` names it, and `^:script` marks it as the thing a
module deliberately is not. The two decisions hold each other up — since nothing
requires a script, a script never needs to HOLD a capability to get past another
workspace's guard, and the grant it cannot give itself is one it would have had
no use for.

The reverse direction is unaffected: a script may `:require` anything its
`:paths` reach, under the ordinary guard rules, as the anonymous workspace.

**THE CLAUSE SPELLING: `:deps` and `:paths`, the `deps.edn` names.** They are
the existing configuration language relocated, not a new one, so they keep the
spelling the file they came from uses. `:require` is untouched and still means
what it meant — which namespaces this file uses — and the two do not blur
because they answer different questions: `:paths` says where source is FOUND,
`:require` says what is USED.

**`:deps` IS SURFACE WITHOUT A FETCHER, and it says so.** `target/release/flint`
compiles from the source path and fetches nothing; the fetch loop lives in
`flint.cli` and is driven by a host. So a script declaring `:deps` is REFUSED,
naming the gap, rather than compiled without it — which would fail as "no source
for namespace some.lib": true, and pointing at the wrong thing. That is the same
judgement obstacle 2 records one level down. Wiring `flint.cli`'s fetch loop into
the native binary is the work that closes it.

### Still open

* **Whether `flint compile` can produce a module from a script.** Only `run` is
  wired. The entry and the source path are the same computation, so this is
  plumbing rather than a decision — but it has not been done or tested.
* **`bin/flint` has no script path.** The development CLI cannot run one. The
  reader half (`#!`) is shared, so this is the front end only.
* **Fetching, per above.**

## dialects-and-preludes

**Portable `.cljc` and flint-only `.fln`, and a prelude a workspace can extend**

**Ratified:** ☐ not signed off

**Status: BUILT, awaiting sign-off.** The extension resolves
(`flint.project/source-extensions`), the custom prelude applies to `.fln` only
(`flint.analyzer/prelude-of`), and the READER now refuses a flint-only tag in a
portable file (`flint.reader/read-dispatch`). What is still open is marked below.

### What was decided

**`.fln` is a PLATFORM EXTENSION, not a category of namespace.** It sits beside
`.clj`, `.cljs` and `.cljd` in the model Clojure already has: one namespace may
have several implementations, and each runtime loads the one it understands.
`foo/bar.cljc` and `foo/bar.fln` are the same namespace, and flint prefers the
`.fln`, exactly as the JVM prefers `.clj` over `.cljc`.

**There is therefore no edge rule on requires**, and an earlier draft of this
section was wrong to propose one. "A portable namespace may not require a
flint-only one" mistakes a property of a FILE for a property of a NAMESPACE. A
`.cljc` file requiring `foo.bar` is fine: under Clojure that resolves to
`foo/bar.cljc` or `.clj`, under flint to `foo/bar.fln` or `.cljc`. Whether the
namespace is available is answered by whether an implementation exists on the
platform doing the loading — which is the consumer's question at load time, not
a static property of the graph.

**Divergence has two scales, and the small one already works.** flint's reader
carries `:features #{:flint}` (`src/flint/reader.cljc`), so `#?(:clj a :flint b)`
reads today, and `lib/clojure/core.cljc` already uses conditionals in anger.

* small divergence → a reader conditional inside one `.cljc`
* wholesale divergence → a separate `.fln` implementation

**Resolution order gains `.fln` at the front.** `project/collect` tries
`[base.cljc, base.clj]` today; it becomes `[base.fln, base.cljc, base.clj]`.
Platform-specific beats portable, which is the established convention.

Worth noting in passing: flint's existing order prefers `.cljc` over `.clj`,
the reverse of the JVM's. That is defensible here — a `.clj` in a flint project
is an oddity rather than the native case — but it is a divergence, and adding a
third extension is when to decide it was intended.

**Enforcement lives at the READER and the resolver, not the graph.** What makes
a `.cljc` non-portable is using flint-only surface *in that file*:

* a flint-only reader tag
* a symbol that resolves only through a custom prelude

Both are known at read time for the file being read, which is where the check
belongs and where the answer is local. The prelude half is BUILT — a custom
prelude applies to `.fln` only, and a `.cljc` gets `clojure.core`. The tag half
is not, and two things found before starting it change what it should say.

**IT IS NOT ONLY WORKSPACE-BOUND TAGS.** An earlier draft said "a reader tag
the workspace binds", which misses the built-in ones. `#flint/table` is bound
by `flint.reader` itself, always available and never declared — and Clojure
cannot read it either. Portability is about whether ANOTHER platform's reader
can make sense of the file, so the rule is flint-only tags, however they came
to be bound.

**AND THE RULE ALREADY CONDEMNS FILES IN THIS REPO**, which is the honest
version of the audit item below. It condemns fewer and different ones than this
section first claimed, and the correction is worth keeping because of HOW the
first list was wrong:

* ~~`lib/flint/table.cljc` uses `#flint/table`.~~ **It does not.** All five
  occurrences are inside comments, a docstring and two `str` literals — the
  file PRINTS the tag and READS it, and never writes one. The audit was a grep
  and a grep cannot tell a tag from the text of a tag. The standard library
  needed no migration at all.
* `test/tags.clj` builds two projects whose `.cljc` sources bind and use the
  project tag `#pt`. That one was right; they are now `a.fln` and `b.fln`.
* **`test/tables.clj` was missed**, and it is the real one: it spits an
  `ops.cljc` containing a live `#flint/table` literal. Now `ops.fln`.
* **`test/sysns.clj` was missed too, and it went GREEN while refused.** Its
  `app.cljc` used `#pt`, and its assertion was `includes? "3"` — which the read
  error also satisfies, because the message ends `(app.cljc:2:32)`. A check
  that a substring appears is not a check that the right thing happened. The
  file is `app.fln` and the assertion is now `= "3"` on the trimmed output.

None of this is an argument against the rule; all of it is the rule working.
But it means **enforcement cannot land before the migration**: turning it on
first would break the build, and a check whose first act is to condemn the
standard library is one nobody will trust. Rename what the rule catches, then
enforce. (Two migrations were found by *running* the enforcement, not by
reading for it — which is the same lesson one layer up.)

**WHAT MAKES A TAG FLINT-ONLY IS A LIST, not the absence of one.**
`flint.reader/portable-tags` holds the tag names every Clojure-family reader
binds, and it is empty today: flint binds `#flint/table` and whatever a
workspace declares, and Clojure's reader knows neither. The alternative —
hardcoding "every tag is flint-only" — is a sentence that would stop being true
the day flint binds `#inst`, and nothing would fail when it did. The check reads
the list.

**THE UNBOUND-TAG ERROR STILL COMES FIRST.** A `#pt` in a `.cljc` that binds no
`pt` is two complaints at once, and "no reader for the tag #pt" is the one that
leads somewhere: a tag this project cannot read is broken in `.fln` too. So
portability is checked only after the tag has resolved.

**The prelude rule survives the correction, for a better reason.** A custom
prelude applies to `.fln` only — not because flint namespaces are a separate
species, but because a `.cljc` is a file other platforms' readers will read, and
they know nothing of flint's prelude. A `.cljc` whose meaning depends on one is
not portable, whatever it says on the tin.

**The prelude becomes a workspace's ordered list.** Today the implicit refer is
hardcoded in ONE place — `flint.analyzer` falls back to `clojure.core` for any
unqualified symbol it cannot otherwise resolve — and that single lookup is what
becomes configurable:

```clojure
;; src/flint/analyzer.cljc, the whole of today's prelude
(when (get-in cc [:vars (symbol "clojure.core" (name sym))])
  (symbol "clojure.core" (name sym)))
```

**`core-first` IS A DIFFERENT LIST, and an earlier draft of this section wrongly
said the prelude replaces it.** It pins `clojure.core`, `flint.core`,
`flint.protocols` and `flint.check` into the front of the LOAD order, and only
the first of those is implicitly referred. The other three are there because the
compiler EMITS references into them that no source names — `defprotocol`
expanding to `flint.protocols/extend-method`, `protocol-miss` calling
`flint.core/kind` — which is a question about initialisation order, not about
what a bare symbol means.

The two lists overlap without being the same, and conflating them would make
`:flint/prelude` quietly responsible for load-order correctness that has already
cost this project several debugging sessions. A prelude entry must of course be
initialised before the code that uses it, so the prelude contributes to the pin;
it does not subsume it.

The declaration:

```clojure
{:flint/prelude [clojure.core flint.core my.lib.prelude]}
```

**EACH ENTRY CARRIES ITS OWN `:include`/`:exclude`.** An entry is a plain
symbol, or a map when something needs saying:

```clojure
{:flint/prelude [{:ns clojure.core :exclude [count]}
                 flint.core
                 {:ns my.lib.prelude :include [count nth-or]}]}
```

* omitted `:include` means every name the namespace publishes;
* omitted `:exclude` means none;
* a plain symbol is therefore exactly `{:ns sym :include :all :exclude []}`.

The declaration lives on the entry it AFFECTS. That is the half
`:refer-clojure :exclude` gets right and a shadow declaration gets wrong: the
list of names dropped from `clojure.core` belongs next to `clojure.core`, not
next to whichever later namespace happens to supply a replacement.

**Which means there is no shadowing to resolve.** A name excluded from the
earlier entry is simply not in the prelude, so the later entry's version is the
only one there. The collision never forms.

**A collision that DOES form is an error, not an order-resolved silent win.**
Two entries publishing one name, neither excluding it, is ambiguous — and now
the author has an exact way to say which they meant, so refusing costs them
nothing and guessing could cost them an afternoon. The message names both
entries and the exclusion that settles it. This is `:exclude`'s existing posture
(`DECISIONS.md#exclude-and-unit-path`) and the file's general one: refuse rather
than quietly pick.

**So ORDER IN THE LIST IS LOAD ORDER, and only that.** The two questions were
conflated in the earlier draft. Name resolution is settled by `:include` and
`:exclude`, which are order-independent; the sequence is what `core-first`
already encodes and must keep encoding — `flint.protocols` before `flint.check`,
because `flint.check` uses `extend-protocol` at top level and the graph has no
edge to order by. Separating them means a workspace can reorder for
initialisation without silently changing what a name means.

**Giving both `:include` and `:exclude` on one entry is refused.** Every such
list has a shorter unambiguous spelling as an `:include` alone, so accepting
both would add a second way to write one thing and a question about which
applies first.

### Why

flint has accumulated features Clojure does not have — reader tags bound per
project, tables, ports, capability guards — and nothing marks which code depends
on them. "Portable" is currently a property a file has by inspection and
convention, so it decays silently: the failure shows up when someone runs the
file under Clojure, far from whoever introduced the dependency.

An extension makes the claim explicit, and a resolver tag makes it checkable.

### What this collides with in the code today

* **FOUR places resolve source extensions**, and every one must learn `.fln`:
  `src/flint/project.cljc` tries `[base.cljc, base.clj]`; `cli/src/main.rs`
  collects files ending `.cljc`/`.clj`; and `bin/flint` has TWO — its
  `source-candidates` and its linter's file filter. `bin/flint` puts `src` and
  `lib` on its classpath, so it can read one shared list rather than keep a
  fourth hand-copy.

  **THERE WERE FIVE.** `cli/build.rs` walks `lib/` and embeds what it finds,
  and it took `.cljc`/`.clj` only — so a standard-library namespace moved to
  `.fln` would simply have been absent from the shipped binary, with no error
  from either side: the file is on disk, the resolver looks for it, and it is
  not in the map. It was found by counting the enumerators rather than by
  anything failing, which is the case §1 of `AGENTS.md` is about.
* **`core-first` is duplicated** in `src/flint/project.cljc` and `bin/flint`,
  and the order within it is load-bearing: `flint.protocols` must precede
  `flint.check`, which uses `extend-protocol` at top level. A configurable
  prelude must default to exactly that list in exactly that order.
* **`collect` builds its per-namespace record in two places** — one for virtual
  namespaces, one for real ones. `:dialect` belongs on the second; a virtual
  namespace has no file and so no dialect.
* **`lib/` is 33 namespaces with no dialect marking.** Ten are `clojure.*` and
  presumptively portable; the rest are `flint.*` and a mix. Which are genuinely
  flint-only is an audit, not a guess.

### Open, and needing sign-off

* **Ordering.** Recorded as later-overrides-earlier above; the alternative is
  earlier-wins, which makes the prelude a base nobody can shadow.
* ~~Whether `.fln` is the extension.~~ **Settled 2026-09-11: `.fln`.** The
  first choice was `.fl`, and it was rejected on legibility. `fl` is a
  TYPOGRAPHIC LIGATURE -- many fonts render it as the single glyph `ﬂ` -- and
  in a sans-serif face `fl`, `f1` and `fI` are near-indistinguishable. An
  extension is read far more often than it is typed, frequently in a diff or a
  stack trace where there is no context to disambiguate it. `.fln` is also
  three characters, which matches `.clj`; `.fl` was the odd one out at two.
* ~~**Whether portability is checkable beyond the edge rule.**~~ **Built
  2026-09-11.** `flint.reader` carries a `:dialect`, derived from the file's
  extension by `flint.project/dialect-of` and threaded to all THREE reads of a
  source — `collect`'s, `bin/flint`'s and `compiler/read-namespace!`'s — for
  the reason `default-features` records: a value only one reader knows about is
  a value the other two get wrong. `read-namespace!` takes it off the compile
  context rather than as a sixth argument, because `compile-image` has already
  lifted it there.
* **What a `.fln` that requires a portable namespace means for the OTHER
  platform** is still unanswered, and is deliberately not the reader's
  question. This check is about one file's own surface. A `.cljc` that requires
  a namespace only flint implements reads fine under Clojure and fails to
  LOAD there, which is the consumer's question at load time and is what
  "no edge rule on requires" above already says.
* **What `clojure.core` means for `.fln`.** Presumably still the first prelude
  entry, but a flint-only dialect could in principle start from a different
  base.

## the-pike-vm-is-the-last-triplicate

**The regex engine is written three times, and the thing that blocks generating
it is that kin cannot pass a mutable array**

**Ratified:** ☐ not signed off

**Status: FINDING, nothing built.** Recorded 2026-09-11 while porting the
remaining hand-written functions out of `bytes.rs`, `strs.rs`, `vector.rs` and
`pike.rs`. The census below was re-derived from the tree, not quoted from
`doc/goals/kin-port.md`.

### What was found

Of the 55 functions left in those four files, **`bytes.rs` is 29 of them and
every one is a vocabulary primitive** — a host `Vec<u8>`, a host `&str`, a
borrowed slice or the allocator. `strs.rs` is the same wall: eight of its
fourteen take a host `&str`, and three more are the intern table. Those are
hole 5 and hole 6 and they are *supposed* to be hand-written; the ports have
their own and the vocabulary in `kin/src/flint/impl/rt.cljc` names all three
spellings, so they cannot drift silently.

`pike.rs` is not that. **`class_hit`, `consumes`, `add_thread` and `run_over`
are pure integer functions over a program and a code-point array**, written out
in Rust, Java and C# — about 130 lines each, three times — and the only thing
keeping them out of `kin/` is a host array. That is the largest genuine
triplicate left anywhere in the runtime, and it is the one where a silent
divergence changes *what a regular expression matches*.

### Why it is not generated yet, precisely

kin renders `^:mut` on a parameter as Rust's `mut x: T`, a by-value rebinding —
not `&mut T`. So a tag whose Rust type is `Vec<i32>` and whose Java type is
`int[]` means **pass by move on one target and pass by reference on the other
two**: a callee that writes into it would be seen by the caller in Java and C#
and not in Rust. There is no tag that spells a shared mutable array in all
three, and adding one is a change to `kin.lang`, which is a separate library.

The shape that *does* work is the one this runtime already uses for exactly
this problem: **a buffer the runtime owns, named by an integer**. `Sink`, `Cps`
and `Walk` are all that, for the same reason. A Pike VM written that way needs

* the thread lists flat in two `Cps`-shaped buffers — `pc` then `nslots` slot
  values per thread — with the count carried as an ordinary return value, since
  `add-thread` is threaded linearly;
* the `seen` set as a third;
* the program words in a fourth, filled once per call (which is the copy all
  three already do — see below);
* `cps-set` and `cps-truncate` added to the vocabulary. `cps-open`,
  `cps-close`, `cps-put`, `cps-len` and `cps-at` exist.

### What is NOT the reason, though it looks like one

**All three runtimes already copy the whole program out of the `TY_RAW` blob on
every `re-run` and every `re-find-all`** — `pike.rs`'s `chunks_exact(4)`,
`Pike.java`'s `progOf`, `Pike.cs`'s `ProgOf`. So moving the program into a
runtime-owned buffer costs nothing new. What it *would* cost is the inner
loop: `consumes` runs per live thread per character, and `cps_at` is a bounds-
checked double indirection where `code[b]` is an indexed load. **That is the
measurement to take before committing** — `DECISIONS.md#matching-over-ropes`
records the Pike VM being four times slower than the backtracker it replaced
until `re-find-all` existed, so this engine's constant factor has already been
load-bearing once.

### The divergences the three copies have already accumulated

Found by reading all three, not by a failing test — no gate compares them.

1. **`from` is narrowed before it is clamped on both ports.** `re-run`'s third
   argument is any integer a guest program passes. `(int) Math.max(from, 0)` of
   2^31 is -2147483648, which passes `runOver`'s `from > cps.length` guard and
   then indexes `cps` negatively — an `ArrayIndexOutOfBoundsException` thrown
   out of the host by guest code. Native holds it in an `i64` and answers nil.
   **Fixed** in `Pike.java` and `Pike.cs` at the same time as this was written:
   clamp in 64 bits, refuse past the end, then narrow.

2. **A match that never writes slot 1 hangs every runtime.** `re-find-all`
   advances by `at = en > st ? en : en + 1`, and a program that reaches MATCH
   without a SAVE leaves `en` at -1, so `at` becomes 0 and the loop restarts
   from the beginning for ever. Native wraps through `usize` to the same 0 in
   release and panics in debug. Gas is charged once, *outside* the loop, so
   nothing stops it. `re-compile` accepts an arbitrary vector of words from
   guest code, so this is reachable without a compiler bug. **NOT fixed** —
   see below.

3. **`RX_NGROUPS` zero-extends on native and sign-extends on both ports**
   (`raw[2] as i64` from a `u32`, against `Val.fixnum` of an `int`). Only
   differs if the group-count word has bit 31 set, which means only for a
   crafted program.

4. **The program blob is little-endian on native and native-endian on the
   ports** — `to_le_bytes`/`from_le_bytes` against `writeU32`/`readU32`. Equal
   on every host either runs on today.

5. **`re-compile` drops its shadow-stack frame before writing the three slots
   on native and after on both ports.** Not observable: `set_slot` does not
   allocate. Worth converging when this file is next touched, because the
   native order is the one that is only safe by accident.

### Open, and needing sign-off

* **Whether `re-compile` validates the program it is handed.** Divergence 2 is
  a denial of service reachable from guest code, and the cheap fix — make `at`
  strictly increase — changes the loop that `test/regex_pike.clj` and the
  conformance transcripts both cover, in four runtimes. The alternative is to
  reject a malformed program at compile time, which is where the gas is already
  charged. That is a decision about what `re-compile`'s contract is, not a bug
  fix, so it is recorded rather than taken.
* **Whether the Pike VM is generated at all.** It is the largest triplicate
  left and the one where drift is most expensive. It is also the hottest loop
  in the runtime, and the buffer indirection above has not been measured.
## npm-cli

**`@3sln/flint-cli`: the CLI as an npm package, node hosting the wasm compiler**

**Ratified:** ☐ not signed off

**Status: BUILT.** Recorded 2026-09-11. `sdks/cli/` is the package;
`sdks/cli/selftest.mjs` is what checks it.

### What was decided

**The npm CLI is the compiler PLUS a server of namespaces, and the second half
is the whole of the work.** `dist/flintc.wasm` has run under node for a long
time -- `host/flint.mjs` does it and `bin/conform-hosts` drives that path. What
a CLI is, beyond that, is:

* reading a directory into a spec: every `.fln`/`.cljc`/`.clj` under each source
  root, plus the standard library, keyed by the path a namespace maps to;
* telling the compiler what it SERVES, so `(:require [flint.sys.fs :as fs])`
  resolves and `(fs/lst-dir "x")` is a compile error rather than a run-time one;
* reading each root's `deps.edn` for workspace identity, tag readers, grants and
  guards, one spec entry per file;
* and then, while the program runs, answering the calls it makes back out --
  `flint.sys.fs`, `flint.sys.env`, `flint.sys.slurp`, `flint.deps.npm`,
  `flint.deps.mvn`, `flint.deps.git`.

A host that serves none of those leaves the compiler unable to read a source
file at all.

**The correctness standard is BYTE-IDENTICAL OUTPUT, not "it works".** The
compiler is deterministic, and both CLIs run the same compiler over the same
spec text, so the same project must compile to the same module. That turns
every detail of the spec builder -- the escaping, the ordering, which
`deps.edn` is read, whether an empty one falls through to the parent -- into
something a comparison can catch, instead of something that surfaces years
later on an unusual project. `sdks/cli/src/spec.mjs` is therefore a
transliteration of `build_spec_with` in `cli/src/main.rs` rather than an
idiomatic rewrite, and where it departs it says so in a comment.

Measured 2026-09-11 on `sdks/cli/fixture`: identical bytes for a plain compile,
for `:optimize [perf]`, for `:with`/`:meta` recorded in the artifact, for a
program requiring the served namespaces, and for a two-root project with a
capability guard between the roots. With a control -- the same comparison
between deliberately different arms -- because two arms that cannot be told
apart would report agreement too.

**`run` compiles to a MODULE here, where the native CLI runs a bytecode
image.** This is the one place the two pipelines are not the same shape, and it
is forced: an image's native references are NAMES, which only a natively-linked
host can resolve, and a wasm loader wants table slots. So `run` compiles the
same program to the same module `compile` produces and instantiates that. The
program and the answer are identical; what differs is that this pays module
emission where the native CLI pays none.

**The containment rule moves across unchanged, and is stricter in one place.**
Every path under `:fs` resolves under the granted root and an escape is REFUSED
rather than clamped; `..` is refused rather than popped, so `a/../b` -- which
normalises to somewhere inside the root -- is refused too. The check touches no
filesystem, so it gives the same answer whether or not the file exists.

The one deliberate difference: node's `under()` treats `\` as a separator on
EVERY platform, where Rust's `Path` treats it as an ordinary character on unix.
So `..\..\etc\passwd` is a filename to the native CLI on unix and a refusal
here. The node side is stricter, which is the safe direction, and it is what
makes the check correct on Windows -- where node genuinely runs and where `\`
genuinely is a separator.

**The served catalogue is two lists, and a checker keeps them one.**
`cli/src/sys.rs`'s `catalogue()` cannot be imported into node, so AGENTS.md §1's
"make one read the other" is not available across the boundary.
`bin/check-sys-catalogue` parses the `vars()` bodies out of `cli/src/sys.rs` and
`cli/src/deps.rs`, parses `sdks/cli/src/catalogue.mjs`, and fails naming the
namespace, var, arity or ordering that differs. Verified non-vacuous by
perturbing one arity and watching it fail.

### What is NOT in the package

* **`flint deps`** (`add`, `tree`, `why`, `pin`, `bump`, `agree`). The
  namespaces it resolves THROUGH are served here, so a program can call them;
  what is missing is the subcommand, which drives `flint.deps.resolve` as a
  program and rewrites `deps.edn`. `flint deps` names itself as unimplemented
  rather than failing as an unknown command.
* **Pods.** `declared_pods` boots a subprocess to discover a pod's surface
  before the compiler runs. The spec builder takes a `pods` argument and emits
  the entries, so the hole is the booting, not the shape.
* **`:to :llvm`**, which is absent from the native CLI too and for the same
  reason: emitting a native artifact needs a linker.

### Open, and needing sign-off

* **Whether the native binary still earns its keep.** ROADMAP.md's "Design: how
  the CLI itself ships" records two invalid attempts at this measurement and
  says the only number that settles it is the two shipped CLIs, invoked. Both
  now exist and do provably the same job, so the measurement is available for
  the first time. Making the decision is not this record's business, but the
  number is, so here it is.

  Measured 2026-09-11, five timed invocations each, median, both artifacts
  rebuilt first (`bin/build-dist` then `cargo build --release -p flint-cli`, in
  that order, because neither implies the other):

  | command | native | node | ratio |
  | --- | --- | --- | --- |
  | `compile` a four-namespace project | 1724 ms | 2812 ms | 1.63x |
  | `compile` the compiler itself (`src/`) | 4140 ms | 7074 ms | 1.71x |
  | `version` -- process start and nothing else | 3 ms | 32 ms | — |

  Both `compile` rows produced byte-identical output, which is what says the
  ratio is between HOSTS and not between pipelines.

  **This is 1.7x, not the 5.8x `cli/Cargo.toml` records.** That comment says
  running the compiler natively "is 2.7 s against 15.6 s through a wasm
  engine", and it is the entire argument for the native path. Whatever that
  figure measured, a modern node on this workload is nowhere near it. The
  absolute numbers were taken on a loaded machine and are inflated; the ratio
  is the comparable part, and the constant 29 ms of node start is visible in
  the `version` row and matters for short invocations in a way the ratio hides.

  Re-measuring `cli/Cargo.toml`'s claim before acting on it is the next step,
  not a conclusion drawn here.
* **Whether `run` should grow the image path.** It would need the loader to
  assign native slots by name at load time, which is a runtime change rather
  than a packaging one.
---

## llvm-ir-target

**`:to :llvm` emits IR; `:to :native` would emit a program**

**Ratified:** ☐ not signed off

**Status (built and measured 2026-09-11, in this change): `:to :llvm` is BUILT.
`:to :native` is not, and now refuses for its own reason.** `bin/check-llvm`
emits IR for seven programs, links each one with `clang`, and every one answers
what `flint run` answers and charges the same gas compiled as interpreted:
`arith` 260 831, `colls` 97 323, `hof` 38 591, `strs` 10 157, `handler` 1 728,
`deep` 253 129, `park` 8 690. `park` is a green thread parked mid-bail and
`handler` is an unwind into a catch, so the two hardest re-entry routes are
covered. NOT covered, and listed in full below: `:to :native`, any host for the
emitted program, 32-bit targets, and any performance claim whatsoever.

### What was wrong before

`cli/src/main.rs` had ONE match arm for two targets:

```rust
"llvm" | "native" => bail!(
    "`:to :llvm` is not built yet: emitting a native artifact needs a linker,
     and this binary carries none. ..."),
```

Two different mistakes are stacked in that. The first is that `:to :llvm` and
`:to :native` are not the same target: **LLVM IR is text, and emitting text
needs no linker.** The second is that the stated reason therefore belonged to
the other arm — what actually blocked `:to :llvm` was that no IR emitter
existed, and the message named a blocker that would still be there after the
emitter was written.

That mattered beyond tidiness. A refusal that names the wrong obstacle sends
the next reader to solve the wrong problem: "carry a linker" is a large piece
of work and was never what this needed.

### What was decided

**`:to :llvm` emits one self-contained `.ll` and runs no linker.** It carries
the program image as a constant, every arity the emitter can take as an LLVM
function, a table naming them, and a `main`. Turning that into an executable is
`clang prog.ll libflintnative.a -o prog` — the user's linker, the user's step.

**`:to :native` stays unbuilt and now refuses for its own reason**: an
executable IS a link, this binary carries no linker, and the message points at
`:to :llvm` as the thing that gets you to one command away.

**The emitter is `flint.aot` aimed at a different target, not a second
emitter.** `flint.llvm` requires `flint.aot` and reads the opcode table, the
decoder, the chunk boundaries, the stack-depth dataflow and `resume-after` out
of it. What is duplicated is emission and nothing else. Two emitters that
each carried their own idea of where a chunk begins would agree on every
program that never took the disagreeing path — which is precisely the drift
AGENTS.md §1 is about, and the one class of bug that does not show up in a
test until it shows up in production.

### Why the shape is the same, when LLVM would allow a different one

`flint.aot`'s chunk-and-dispatcher layout exists for two reasons, and only one
of them is about wasm.

The wasm-specific one is that **you cannot branch INTO structured control
flow**, so re-entry has to be arranged through a `br_table` at the top of a
loop, and a backward jump pays it. LLVM has no such rule, so this emitter drops
that half: a chunk is a basic block, a jump is `br label %chunkN` in either
direction, and the `switch` on the entry block runs once on the way in.

The one that is NOT about wasm is why **a Clojure call does not become a native
call**. A green thread parks by unwinding to the interpreter; recursion that
lived on the machine stack could not be suspended, and deep recursion would
fault instead of raising a catchable `StackOverflowError`. Both are load
bearing (`threads-and-ports`). So `aot_call` pushes a frame and RETURNS here
exactly as it does for wasm, and the interpreter enters the callee — which may
itself be compiled. Nothing in `emit-wasm-instead-of-dispatch`'s argument for
that was ever about wasm.

The third property — re-entry at every chunk — is kept for the reason 0013
measured: a quarter of the work in a program that parks happens in a frame that
has already been resumed.

### The runtime ABI had to widen, and it was byte-identical to do so

`AotSync`'s seven fields, `aot_prologue`'s result and `AotEntry`'s last
parameter were all `u32`. On wasm32 that is the right type. On a 64-bit host it
is a TRUNCATED POINTER, and a truncated `stack` is a wild store on the first
push.

They are now `usize`, which is `u32` under wasm32 — so the wasm layout, the
wasm function types and the offsets `flint.aot` hard-codes are all unchanged,
and a `const` assertion at the bottom of `runtime/src/aot.rs` says so in both
builds rather than leaving it to be believed.

`mem::Region::base_addr` had the same truncation (`self.base as u32`) and is
now `usize` for the same reason. That one was not theoretical: it was the first
SIGSEGV, on the first `:upval`, because the heap base compiled code added an
object offset to was the low half of a real pointer.

**And the emitter's own 32-bit assumption went with it.** `flint.aot` writes
`i32.wrap_i64` to turn a `Value` into a heap address, which IS the mask on
wasm, where an address is 32 bits by the platform's definition. `mem::Addr` is
a `u64` and `Value::as_heap` takes 48 payload bits, so `flint.llvm` masks to 48
rather than wrapping to 32.

### Natively a slot is an index, not an address

On wasm, `AotFn::slot` indexes `__indirect_function_table` and `call_aot`
transmutes it. A natively linked program has no such table, so the emitted
module carries one (`@flint_aot_table`) and registers it before the program
runs, and `call_aot` looks the slot up there. That is the same answer
`native::resolve_natives` already gives for builtins, for the same reason: an
index only means something inside the artifact that produced it.

`call_aot`'s `unreachable!("compiled arities exist only in a wasm module")` was
correct when it was written and is now reachable — for an artifact from
`:to :llvm` and nothing else. An image whose `aot` table is empty never asks;
one that asks without having registered still gets a panic rather than a jump
through whatever integer the slot happened to be.

### What is NOT built, precisely

* **`:to :native`.** Unbuilt, refuses, says why.
* **A host.** A program linked from `:to :llvm` runs and computes and its
  output comes back. It has NO host: `flint run` serves `fs`, `env`, `slurp`
  and pods from the CLI, and none of that is in `nativeabi/`. A program that
  opens a port parks for ever rather than being answered. The interface for
  fixing that is `Program::drain_events`, and it belongs in whatever embeds the
  archive — but today nothing does.
* **32-bit hosts.** The sync block is emitted as seven `i64`s. LLVM IR is not
  target-independent about integer widths, and a 32-bit native target would
  need that struct type re-emitted (and nothing else).
* **`opt`/`llc` are not run.** flint emits IR at `-O0` shape — allocas for
  every piece of body state — and leaves optimisation to whoever compiles it.
  That is deliberate for a first version: `mem2reg` is the first thing any
  pipeline runs, and an emitter that pre-optimised would be a second optimiser
  to keep correct. **No performance claim is made here.** `emit-wasm-instead-of-dispatch`'s
  measurements are about the wasm emitter, and nothing in this change measured
  the LLVM one against the interpreter.

### A latent bug in `flint.aot`, found by writing the second emitter

`flint.aot/emit-instr`'s bail arm called `(resume-after op ip len chunk-of)`
where `op` is the BYTE EMITTER defined above it, not the instruction's opcode.
`resume-after` tests `(= op :tail-call)`, so it compared a function to a
keyword — always false, and the tail-call arm that its own long docstring is
about never ran.

It is latent rather than live: a TAIL_CALL replaces the frame, so `enter`
overwrites the `aot_ip` this published before anything could resume at it.
It is still the wrong ip to publish, and it is exactly the one the docstring
says cost a debugging session. Fixed to pass `k`.

Worth recording for the reason rather than the fix: **it was invisible to
review and to the suite, and visible the moment a second reader had to decide
what the argument meant.** Nothing about writing the LLVM emitter tested
`flint.aot`; what found the bug was having to answer "which of the two things
called `op` does this want?"

### How it is checked

`bin/check-llvm`, from `bin/test`. Four checks, because each alone has a hole:

1. **It is IR, and not a wasm module under a `.ll` name.** This is what the
   assertion it replaces was guarding — a target that silently means a
   different target is worse than an absent one — and it stays guarded.
2. **`clang` accepts it.** LLVM's verifier is the only reader that can say the
   control flow is well formed.
3. **The linked program answers what `flint run` answers.**
4. **The linked program charges THE SAME GAS with compiled arities as
   without.** `two-builds` makes gas a production feature and `resource-limits`
   makes it a bound on work, so a chunk that charges for an instruction it did
   not run is a bug rather than a rounding error — and this is the check that
   catches a mis-chunked body whose ANSWER happens to come out right, which is
   most of them. `test/aot.clj` makes the same argument for the wasm emitter.

Both `.ll`s in that check come from ONE program, `[perf]` against `[size]`, so
a difference is the emitter and cannot be anything else.

**And check 1 could not fail when it was written.** It was

    head -c 4 "$f" | od -An -c | grep -q 'a s m'

and `od -c` pads every byte to a three-character column, so a wasm module
renders as `\0   a   s   m` and that pattern never matched anything. The one
guarantee being carried forward from the assertion this replaces was carried by
a grep that could only pass — `checks`'s vacuous gate, and the shape
AGENTS.md §5 describes: no error, no warning, and a green suite, which is what
a working check also produces.

It is now four hex bytes against `0061736d`, and it is PROBED rather than
reasoned about, twice: `selftest` runs before anything else and requires the
comparison to tell a real wasm header from a real `.ll` first line, both arms;
and by hand, the whole script with `:to :llvm` rewritten to `:to :wasm` and
nothing else changed, so `flint` really does write a wasm module under a `.ll`
name — it fails with "arith.perf.ll is a wasm module, not LLVM IR", against an
unmodified control that passes.

The gas comparison was wrong first too, and in a way that reads exactly like an
emitter bug: the interpreted arm reported 104 steps against the compiled arm's
104 652. Nothing was miscounted. With no gas limit the interpreter runs
`NoBudget`, whose `tick` is a constant the optimiser deletes along with the
counter (`resource-limits`), while compiled code charges through `aot_*`
regardless — the two arms were not comparable, and a limit is what turns
counting on. That is AGENTS.md §3's "state what each arm does end to end"
arriving as a number that looked like a finding.

### Open, and needing sign-off

* **Whether `nativeabi/` is where the archive belongs.** It is a fifth crate
  whose whole content is two entry points. The alternative is `sdks/c`, which
  already emits a `staticlib` — but that is an SDK ABI for embedding flint, and
  this is the runtime half of one compiler target. They were kept apart on that
  reading; merging them is defensible.
* **Whether `:to :llvm` should imply `:optimize [perf]`.** Today it mirrors
  `:to :wasm`: without `perf` the module is the image plus the two calls that
  start it, and every arity is interpreted. That is a useful artifact (it is
  how the gas comparison gets its control) but it is arguably not what somebody
  asking for LLVM IR meant.
* **Whether the emitted `main` should exist at all.** A module carrying `main`
  is an executable-shaped artifact; a library-shaped one would export the image
  and the table and let the embedder write the entry. Both are one line apart
  and only one can be the default.

---

## one-dependency-walk

**Every dependency kind resolves transitively, through one walk**

**Ratified:** ☐ not signed off

**Status: built 2026-09-11, and checked against the code by building each case.**
`lib/flint/deps/manifest.cljc` is new; `flint.deps/fetch-plan` and
`flint.deps.resolve/deps-of` both read it. `bb test/cli.clj` covers maven and
npm transitives, `:local/root`, and the refusal wording, against local
fixtures rather than the network.

### What was wrong

There were **three** transitive mechanisms and they covered different kinds.
`flint.deps/fetch-plan` read a fetched dependency's own `deps.edn` and recursed,
which covered git and `:local/root`. `flint.deps.resolve/plan` read an npm
MANIFEST and recursed separately, on a walk that nothing doing the fetching
ever called. Maven had neither: a jar's dependencies are in a POM, nothing
parsed one, and `flint.deps.mvn/pom` sat served, cached, and never called by
anything. `deps-of`'s docstring described a caller-side git mechanism that did
not exist.

Three walks agreeing about the common case is the same shape as two tables
agreeing about the common case, which is what `coord-types` exists to end one
level down: it is invisible until something uncommon arrives, and here the
uncommon thing was ordinary — a maven library with a dependency.

### The split that makes one walk possible

**A DOWNLOADER and a MANIFEST SCANNER are different things, and coupling them
is what produced three mechanisms.** What fetches a package and what reads its
dependency list are independent:

* a **downloader** per ecosystem — npm from a registry, maven from a
  repository, git by clone, a plain file for everything else. `:local/root`
  has none, and that is the case that proves the axes are separate: there is
  nothing to fetch and still a manifest worth reading.
* a **scanner** per FORMAT — `deps.edn`, `package.json`, `pom.xml`, a pod
  manifest. Each takes a directory that is already on disk and answers in one
  shape, so the walk never learns which file the answer came out of.

WHICH SCANNER RUNS IS ANSWERED TWO DIFFERENT WAYS, and that is deliberate. A
LOCAL reference names its ecosystem in how it is written (`:local/root` means
`deps.edn`, `:pod/path` means the pod manifest), so there is nothing to guess.
A FETCHED package had to satisfy some registry's format and may say more than
that registry understands — a flint library published to npm carries a
`package.json` because npm demands one and a `deps.edn` saying what it actually
depends on — so `deps.edn` comes first and the ecosystem's own file after it.
That precedence lives in `coord-types` beside the kinds, so adding a kind has
to answer "what does one of these carry" in the same edit.

The host dispatches on `:via` — the downloader — and not on `:kind`. The copy
of the KINDS that used to live in `bin/flint`'s fetch dispatch is what actually
broke the last time the kinds moved, and `:via` is a smaller thing to agree
about: `:git`, `:tgz`, `:zip`, `:file`, `:none`.

### Why the parsing is in `.cljc` and not in Rust, against the roadmap

`ROADMAP.md` put manifest parsing in the native `deps.*` modules, "so the
`.cljc` side stops knowing which ecosystem keeps its dependencies in which
file". That reason is satisfied by one portable scanner just as well — the
WALK does not know; the scanner does — and the location is settled by a
constraint the roadmap did not weigh: **the walk has to run under `bin/flint`,
which is babashka with no flint runtime and no ports, and inside the shipped
binary.** Parsing in Rust would mean either that the bootstrap host cannot
resolve a transitive dependency at all, or that it grows its own babashka copy
of every format — a fourth copy of exactly the thing being unified.

For the same reason the scanner does not use `flint.data.json` or
`flint.data.xml`, which is the obvious objection to it: both bottom out in
`flint.rt/json-parse` and `flint.rt/xml-parse`, runtime builtins babashka does
not have. What is there instead is deliberately **not a parser** — it finds one
member of one JSON object and one element of one XML document, and the
narrowness is the point, because a manifest reader that cannot read arbitrary
JSON cannot be wrong about arbitrary JSON. It handles the two things a naive
scan gets wrong on real files and that do occur: a nested `"dependencies"`
belonging to some tool's own configuration block, and a backslash escape inside
a string.

What the POM reader does and does not do is stated where a reader meets it
(`flint.deps/maven-note`): the top-level `<dependencies>`, `<properties>`
substitution and `${project.version}`, with `dependencyManagement`, `test` and
`provided` scopes and optional dependencies excluded — and NOT parent POM
inheritance, profiles, or version ranges. A version those leave unresolved is
reported by name rather than guessed at.

### Two bugs this found, both invisible until something uncommon arrived

**`:local/root` never worked.** The walk asked whether a dependency was present
by probing for a `.flint-fetched` stamp, which the host writes after a
successful download — and nothing ever writes one into somebody's own source
tree. So every `:local/root` came back pending for ever, and `bin/flint`
reported `no such :local/root for my/lib: ../lib` for a directory that was
right there. `ROADMAP.md` recorded the coordinate as BUILT. Nothing tested it
end to end, which is how a feature can be recorded as built and be broken in
its only path.

The fix needed the host seam widened by exactly one function: `slurp*` reads
FILES, and whether a directory exists is a different question. `fetch-plan` and
`flint.cli/run` take an options map carrying `:exists?` (and the platform, for
pods); a host that passes nothing still works, and an on-disk dependency gets
the benefit of the doubt.

**`flint deps pin` wrote coordinates that could not be read back.** `pins`
produced `{}` for any kind it had no clause for, which included `:local` and
`:pod` — and `{}` is a coordinate of no kind. It was unreachable only because
the walk that fetches did not read `:flint/overrides` at all, which is itself
the third finding: **pinning a transitive and forcing a version are the same
operation**, `flint.deps.resolve/plan` already worked that way, and the walk
that actually fetches did not. So a pin written by the tool was never read by
the build. Both halves are fixed together, and the pair is what makes a
transitive npm RANGE workable rather than merely refused: a range arriving from
somebody else's `package.json` is not the project's to edit, and `flint deps
pin` is what turns it into something exact.

### What is still not done

**The parallelism is in `bin/flint`, which is the driver that exists, not in a
native one.** The round is a batch and now runs concurrently — the shape was
always right and the driver declined to use it. But the shipped binary has no
`fetch` or `build` command at all, so the "fetching belongs in the native
driver" half of the design is unmoved: `bin/flint` still shells out to `curl`,
`tar`, `unzip` and `git`. What this change does is make the plan complete and
kind-agnostic enough that a native driver has one interface to implement rather
than three.

---

## pods-are-a-resolvable-dependency

**A pod resolves from a registry, and a fetched pod is a local pod**

**Ratified:** ☐ not signed off

**Status: built 2026-09-11.** `lib/flint/deps/registry.cljc` resolves
`:pod/version`; `bin/flint` fetches it; `cli/src/main.rs` boots what was
fetched. `bb test/sysns.clj` boots a registry-fetched pod with the shipped
binary; `bb test/cli.clj` covers artifact selection and the registry document
against a `file://` fixture. The community registry is **not serving yet**, so
a `:pod/version` needs `:flint/pod-registries` naming one that is.

### What was decided

`:pod/path` already worked: a directory holding `manifest.edn`, with the
manifest selecting a per-platform `:artifact/executable`. `:pod/version` was
recognised and refused with a sentence naming the missing registry. This builds
the registry and the resolution, and the shape it takes is one property:

**A FETCHED POD IS INDISTINGUISHABLE FROM A LOCAL ONE by the time anything
boots it.** The artifact is chosen ONCE, in the plan, where the platform is
known; what lands in the cache is an ordinary pod directory with an ordinary
manifest naming the one executable that was fetched. Nothing downstream learns
which coordinate it came from — `cli/src/main.rs` resolves both forms to a
directory and reads the same manifest either way, which is what keeps the
platform rule from being implemented twice at two different moments.

### Two registries, because they answer different questions

**The in-repo registry (`registry/pods.edn`) serves flint's own TOOLING pods
and nothing else** — the dependency drivers, and whatever else sheds out of the
binary. It exists so flint's own build does not depend on third-party
infrastructure being up. **A user's dependencies resolve against the COMMUNITY
registry**, which is where the pods people publish live.

Keeping them apart gets both properties instead of trading one for the other. A
first-party registry that also answered for `pod.org/postgres` would quietly
change what somebody else's coordinate means, and that is worth more than the
convenience of one lookup path. So the in-repo one is NOT in the default list:
a project that wants flint's tooling pods says so with
`:flint/pod-registries`, the same shape `:flint/npm-registry` and
`:flint/maven-repos` already have.

`registry/pods.edn` ships empty, and that is honest rather than unfinished:
nothing has moved out of the binary yet, and a registry entry for a pod that
does not exist would be a published lie. What exists now is the format and the
hook that reads it.

### The registry document, and why it needs no new host operation

A registry is one EDN map — a pod, its versions, and which artifact matches
which host. Fetching one is an ORDINARY FETCH, so it goes in the same batch as
everything else and the fixpoint picks the resolution up on the next round:
round one has nothing to resolve the pod with and asks for the document; round
two reads it and asks for the artifact. Nothing was added to the host interface
to make this work, which is the test of whether the plan/execute split was real.

A native artifact is matched on `:os/name` and `:os/arch` (`std::env::consts`
spellings, because that is what the binary compares against at boot), and an
artifact with neither matches anything — so a one-artifact manifest, which is
what a pod under development has, works without saying the same thing three
times. **A wasm build is the FALLBACK and never the first choice**, so the
matrix does not have to be complete: publish natives for the platforms worth
publishing for, publish one wasm build beside them, and a host that can run it
covers the tail.

### The open question, answered: a pod may depend on pods, and only on pods

`ROADMAP.md` recorded this as open. The answer: **a pod's manifest may declare
`:deps`, and every one of them must be a pod.** What a pod links natively is
its own affair and invisible from here; a pod depending on ANOTHER pod is
meaningful, and resolving it is the one piece that cannot be delegated to a
driver pod, because the pod manager is the fixed point everything else is
fetched by. A coordinate of any other kind in a pod's manifest is dropped
rather than walked — a subprocess must not be able to put a compiler's worth of
source on the path behind itself.

For the same reason a pod contributes **no source roots**, which is now stated
in `coord-types` (`:source :none`) rather than achieved by the walk skipping
pods entirely. Skipping them was how it worked before, and it had a cost: a
`:pod/path` naming a directory that did not exist was never reported, because a
coordinate outside the plan cannot be checked.

### What is still not done

`flint fetch` is `bin/flint`'s, so a pod is fetched by babashka and booted by
the shipped binary. The native side READS the cache and says
`the pod X has not been fetched ... flint fetch resolves it` when it is empty —
honest, and one command away from being self-sufficient. Closing it means the
native CLI gaining a fetch driver, which is `one-dependency-walk`'s open half
and not a separate piece of work.

## kin-probes-assert-a-value

**A kin probe says what the answer should BE, not only that three targets said the same thing**

**Ratified:** ☐ not signed off

**Status: built 2026-09-12.** `kin/scripts/verify` reads an `--expect` section
from the `.drivers` file and compares it, byte for byte, against the output the
targets agreed on. **All 90 kin sources carry one**, and `bin/check-kin`
refuses if any source does not — not a floor with a margin, because a margin
is how ninety drivers came to hold not one expected value without anyone
deciding on it.

### What was missing

`kin/scripts/verify` ended in exactly two verdicts: *"ok N targets, one source,
identical output"* or *"FAIL the targets disagree"*. That is a real check and
it catches a real class — per-target TRANSLATION divergence, where one source
emits different semantics into different languages. `kin/hamt.drivers` is the
model: an arithmetic shift and a logical one agree below 2^31, so it
deliberately probes `0x80000001`.

**But all three targets are generated from ONE source.** A logic error in that
source produces three identically-wrong implementations, which agree perfectly
and pass. The script already states the principle one case earlier — *"an
output identical to itself is not a check"*, where it refuses a source that
generates for fewer than two targets — and simply did not extend it one level
up. Three outputs identical to each other are not a check either, for the same
reason.

Not one of the 90 drivers asserted what its answer should be. Grepped before
starting: none of them contained an expected value in any form.

### The shape, and why not a separate harness

**The expectation goes in the `.drivers` file, and `verify` compares it.** The
alternative considered was a separate expected-value harness beside the
drivers. Three things settled it the other way:

* **Cost.** `verify` already compiles and runs all three targets; comparing one
  more string is free. A second harness would compile them again, and
  `./kin/scripts/verify <file>` costs 4 seconds — the budget this has to stay
  inside, because it is meant to run on every change.
* **One list, not two.** A separate harness would restate each probe's output
  format, and the two would drift the first time a driver gained a field
  (`AGENTS.md` §1).
* **`bin/check-kin` gets it for free**, because it already runs `verify` over
  every source.

`section` reads one named section and ignores every other, so a section nothing
reads is a place to write prose. **`--expect-why` is that place, and it is not
optional in spirit:** a number carries its method or it is folklore
(`AGENTS.md` §2). It records how the value was obtained — derived from the kin
source, computed from an independent oracle, or pinned from a run — so the next
reader does not have to guess which.

### Compared exactly, never by substring

The comparison is `cmp` over the whole line, with only trailing whitespace
trimmed. A substring test would let an expectation of `"3"` pass against a
failure message that happens to contain a 3 — which is not hypothetical; it is
a mistake made in this tree the same week, where `(str/includes? out "3")`
passed whether the feature worked or not because the FAILURE text ended
`(app.cljc:2:32)`. **An assertion that cannot tell success from failure is not
a check**, and the cheapest way to keep that property is to make the assertion
total.

The mechanism was proved with a control before any expectation was trusted:
`hamt`'s `2` was changed to `30` — the answer an arithmetic shift gives — and
`verify` reported `FAIL the targets agree on the WRONG answer`.

### Where the values came from

Independence from the code under test is the whole value, so the oracle is
named per source in `--expect-why`. Four kinds were used:

* **A different implementation of the same published thing.** `kin/hash.kin`'s
  numbers came out of real Clojure 1.12 (`clojure.lang.Murmur3`,
  `hash-ordered-coll`), which is the oracle that file's own header names.
  `kin/dblstr.kin`'s twenty-one doubles came from Clojure's `Double.toString`
  rule, run.
* **Arithmetic worked by hand from the source's stated rule**, for `hamt`,
  `champ`, `unsigned`, `numarith`, `numdiv`, `codepoints`, `bytehash`.
* **A fourth implementation.** `valhash` and `collhash` were transcribed into
  python and run against the driver's own fixture — a language none of the
  three targets is, so an error shared by all three generated copies has
  nowhere to hide. Both matched character for character.
* **A second reader of the data.** `casetable`'s 1528 integers were parsed out
  of the `.kin` source by script and folded independently, and its two section
  boundaries were checked against where the data actually stops ascending
  rather than restated from the `defconst`s.

### What it found

**No logic error in any kin source.** Ninety sources, every expectation
derived, and every one matched. That is worth saying plainly rather than
padding: the kin layer was already right, and this now says so in a form that
survives the next edit.

What it did find is **five drivers whose comments describe behaviour the probe
does not exercise**, each recorded in the `--expect-why` beside it rather than
silently fixed:

* `valcmp` — `short=` and `long=` are labelled as testing "the SHORTER one
  first when one runs out", and do not: the fixture derives each element from
  the length, so the FIRST elements already differ and the element comparison
  decides. Neither run-out branch of `cmp-sequential` is reached by any field.
* `valhash` — the last two fields are labelled "an ASCII rope must take
  `rope-hash` (0x54xx) and a non-ASCII one `java-string-hash` (0x77xx)". Both
  are 0x54xx. `valhash.kin` routes `TY_ROPE` unconditionally; `s-ascii` is
  imported and never called. The comment describes the two-walk arrangement the
  source's own "ONE WALK FOR EVERY TIER" block says was removed.
* `casechange` — headed as upcasing sharp s "by a FULL mapping to two code
  points". The fixture's `full_index` returns -1 unconditionally, so the entire
  `case-full-at` branch is never entered.
* `byteconcat` — the `t3` comment promises a merge into the rightmost leaf with
  the depth unchanged. With the fixture's `FLAT_MAX` of 8 no piece can both
  pass the gate and fit, so tier 3 is unreachable from this fixture.
* `mapread` — a comment computes a key's hash as `0x900` where the key is
  decimal `900`, and the consequence is that `node_find`'s success value is
  unreachable: the compound hash-map HIT is untested and only the miss runs.

Also noted: `numarith`'s `mul-overflows` docstring illustrates `MIN * -1` with
the operands the other way round from the code (`I64_MAX / -1` where the code
divides by `x`). Both orderings are correct and both refuse; the driver now
asks BOTH, where before it asked one.

None of these is a bug in shipped behaviour. All of them are places where a
reader would believe something is checked that is not — which is the same
failure mode as a status line that decayed, and the reason they are written
down where the check is.

### Why this was worth doing now

The project wants to stop running a 30-minute gate on every commit and run only
what a change can break. That is only safe if the fast checks catch logic
errors, and until now the kin layer had none that could: every check it had
compared an implementation against two copies of itself.

### One trap, fixed rather than remembered

Several `.drivers` files did not end in a newline. Appending a section to one
fuses the header onto the last line of the previous section — `    }--expect` —
so it stops being a header, its text lands inside the probe, and the target
fails to compile with `CS1519: Invalid token '='`. That reads as *"the targets
disagree"* about the code under test, which is the one thing it is not.
`kin/scripts/verify` now refuses a drivers file that does not end in a newline,
and says why.

## a-parallel-gate-body-never-exits

**A loop body run under `xargs` records its failure in a file; only the code after the loop may fail the gate**

**Ratified:** ☐ not signed off

**Status: built 2026-09-12.** `bin/conform-hosts`, the 23-program conformance
loop: 116 s to 24 s measured on this machine (10 jobs, `sysctl hw.ncpu`), and
the script's own phase total 213 s to 110 s. `bin/check-kin` was parallelised
the same way a day earlier, 371 s to 66 s. Proved by three adversarial probes
before it was believed: a fixture that does not compile, a port that answers
differently, and a job that dies leaving no verdict. All three exit non-zero
and name the program.

### The hazard, which is specific to this transformation

A sequential loop body fails the gate with `exit 1`. That line does not change
meaning when the loop becomes parallel — it changes SCOPE. Under `xargs` the
body is a subshell, so `exit 1` ends that subshell, the other jobs carry on,
`xargs` returns success, and the script never learns anything happened.

**A red gate goes green, and nothing in the output says so.** This is the worst
available direction for a bug in a gate: a slow gate wastes minutes, a gate
that cannot fail wastes the reason for having one. `bin/conform-hosts` has been
bitten by this exact shape twice already, both recorded in its own comments —
most plainly when a broken fixture `continue`d, took thirteen programs worth of
checks away, and left the summary at 213 checks instead of 246 without a single
FAIL line.

So the rule is not "be careful with `exit` in the body". It is that **the body
has no way to fail the gate at all**. Every failure goes through one `fail`
function, which appends its message to that program's transcript, touches a
marker file, and exits the subshell ZERO. After the loop, the script counts
marker files. A file on disk survives a subshell; a status does not.

### Every job must produce a verdict

Collecting failures is only half of it. The other half is the job that never
ran — `xargs` declining to start it, a shell the machine killed, a `sh -c` that
died before its first command. It writes no failure marker, so a
failures-only check reports success over a program that was never tested. That
is the same fault one level up: not a check that fails, a check that is not
there.

Each body run therefore touches `ok` as its LAST act. After the loop, a program
with neither `ok` nor `fail` is a failure with a name. Probed by simulating it
— an `exit 0` inserted at the top of the body for one program — and the gate
says `FAIL churn reached no verdict -- its job did not finish` and exits 1.

The same argument gives the list a floor (at least 20 programs listed), because
an empty list runs nothing, reports nothing, and looks exactly like a clean
pass.

### Output: collected per job, replayed in list order

Streaming from ten concurrent jobs interleaves lines, which is unreadable and,
worse, REORDERS between runs — a gate whose output cannot be diffed against a
previous run loses most of its value. `bin/check-kin` collects and SORTS,
because each of its jobs emits one line.

This loop emits three or four lines per program, and the second of them — `ok
... and under a collection at every allocation` — means nothing away from the
first. A sort separates them. So the transcripts are written per program and
replayed in the order of the program list, which is just as stable as a sort
and keeps each program's lines together and in body order. Two consecutive
parallel runs produced the same 310 lines in the same order.

### Per-run temp files, not fixed ones

The body wrote `/tmp/flint-c-jvm.out`, `/tmp/flint-c-clr.out` and two others at
fixed paths. Twenty-three concurrent jobs would trample those, and the symptom
would be a cross-port comparison against another program's output — a
divergence report naming the wrong thing. They are now per program inside one
`mktemp -d` per run.

Worth saying that this was already a live bug before any parallelism: a second
run of this script, in another worktree on the same machine, tramples exactly
these files today. The rest of the script still uses fixed `/tmp` names and
still has that flaw.

### What this does NOT license

Parallelising a loop whose iterations are not independent. This one qualifies
because each program compiles to its own image and its own wasm module and is
read by three runtimes that share nothing — verified by reading every write in
the body, not assumed from the loop looking parallel.

## port-tests-in-kin

**Ratified:** ☐ not signed off

Recorded 2026-09-12. `ROADMAP.md`'s "Port tests belong in kin" asked for the
fourteen duplicated runtime port tests to move into `kin/`. One of them did.
This records why the other thirteen did not, because "we ported one of
fourteen" is the kind of result that gets re-attempted by somebody who assumes
the first attempt gave up early.

### What the fourteen actually are

`runtimes/jvm/test/*.java` (1 598 lines, fourteen `main()` programs) and
`runtimes/clr/conform/Program.cs` (1 442 lines, the same fourteen as `--rt-*`
flags) are two hand-written transcripts of one suite, run by
`bin/conform-hosts` and `cmp`d. The roadmap's insight was right and is worth
restating: they are NOT framework-shaped. They print `"  ok  …"` lines, which
is exactly what `kin/scripts/verify` consumes, so no test-framework vocabulary
is needed to move one.

**The blocker is not the test's shape. It is what the test CALLS.**

`kin/scripts/verify` builds a self-contained probe — one `.rs` through `rustc`,
one `Probe.java` through `javac`, one `Program.cs` through `dotnet run`. It
cannot link a runtime. Every dependency a probe has is supplied by a
hand-written `--rust-head` / `--java-head` / `--csharp-head` fixture, three
times over, on purpose: the fixture is a STUB, and what the probe checks is the
generated code between the stubs.

Thirteen of the fourteen construct a `Rt` — the interpreter, the NaN-boxed
heap and the moving collector, which are hand-written in each runtime and are
the thing the test exists to check. Against a stub `Rt` those tests assert
nothing about any runtime. Porting them would have reduced the line count and
the coverage at the same time, which is the failure mode `AGENTS.md` §2
describes: a status line that is true of something adjacent.

Specifically, and each verified by reading the file rather than inferred from
the name:

| test | what it needs that kin cannot supply |
|---|---|
| `RtFoundation` | `Space`, `Obj` headers, 48-bit forwarding, the dispatch loop over hand-assembled bytecode, a GC-stress pass — **and it prints a ns/iteration TIMING**, so byte-identical stdout is impossible by construction |
| `RtMaps` | generated map code over the REAL heap, with `rt.gc.major` run across it. The generated half is already covered by `kin/map*.drivers`; the half this adds is the heap, which is the hand-written half |
| `RtSnapshot` | the snapshot format, exported and re-imported at DIFFERENT addresses |
| `RtShelve` | a program stopped mid-run — frames live, locals live — moved to a fresh runtime |
| `RtImage`, `RtFlags`, `RtSteps`, `RtGas`, `RtAot`, `RtSelfHost` | `Img.load` and `runProgram`: a real image, on a real interpreter |
| `RtHostPorts` | `Conc.hostGrant`, `hostDeliver`, `drainEvents` — the host-port ABI |
| `RtParallel` | `new Thread(...)`, the host's own scheduler |
| `RtStale` | the `FLINT_STALE=1` root-discipline detector, a debug facility of the hand-written `Rt` |

**The way to move any of the thirteen is to generate the thing it tests.** That
is the same order this port has always run in, and it is a runtime decision
rather than a testing one.

### The one that moved, and what moving it cost

`RtHash` was the exception: its subject is four functions that touch no host at
all — `hash_double`, `hash_bytes`, `hash_symbol`, `hash_keyword`, bytes in and
an `int` out.

They were written out **three times by hand**, in `runtime/src/hash.rs`,
`runtimes/jvm/src/com/flint/rt/Hash.java` and `runtimes/clr/src/rt/Hash.cs`,
and both port copies carried a header saying so — that `String.hashCode()`
"would leave the two ports computing the same number by different routes", so
"the arithmetic is written out on both". A file whose comment says it is kept
in step by hand is a file asking to be generated (`AGENTS.md` §6).

So the test could not move on its own: a kin probe calling a stubbed
`hash_bytes` would pin a number about the stub. Moving the test meant moving
the implementation, and `kin/hashtext.kin` is both. Both hand-written `Hash`
classes are deleted.

**The three copies agreed on every number and disagreed on the INTERFACE.**
Rust's `hash_symbol` took `Option<&str>` and `&str`; both ports took `byte[]`
and `null`. Two spellings of "there is no namespace", on three sides, with
nothing comparing them — precisely the drift two lists produce. The generated
function takes one `Bytes` on all three, and an ABSENT namespace is an EMPTY
one, which is an identity rather than a convention:

    hash-bytes []  =  hash-int (fold over no bytes)  =  hash-int 0  =  0

and `hash-int` short-circuits zero. `kin/hashtext.drivers` evaluates both sides
and PRINTS them rather than asserting they are equal, because `true` cannot say
which side moved; `clojure.lang.Util/hashCombine` agrees that both are
-1390931727.

### What the expectations are worth, and what they are not

`--expect` for both `kin/hash.drivers` and `kin/hashtext.drivers` was computed
from `clojure.lang.Murmur3` and `clojure.lang.Util` through the `clojure` CLI,
in a script that implements the fold independently. Nothing was pasted from a
flint run. The derivation is in each `--expect-why`.

**Both retired copies said `clojure says` for rows that are not Clojure's
numbers.** Strings, symbols and keywords have hashed over UTF-8 BYTES since the
UTF-16 basis was removed; `runtime/src/hash.rs` says so at length, and its own
Rust tests were updated to stop pinning Clojure numbers for symbols. The two
port transcripts were not. Only the ASCII rows coincide — `日本語` is the row
that separates them, and it was in both files, under a message naming the wrong
oracle. That is the divergence two implementations of one suite produce, and it
is the reason for this work.

### Where the guarantee now comes from, stated exactly

This is a real change and must not be glossed. Before, `RtHash` composed the
REAL generated murmur with the REAL hand-written text hash, inside each port,
against a pinned number. After:

* **agreement** across rust/java/csharp is `kin/scripts/verify` on both
  sources, byte-identical stdout, run by `bin/check-kin`;
* **absolute correctness of the murmur core** is `kin/hash.drivers` against
  Clojure;
* **absolute correctness of the text hashes** is `kin/hashtext.drivers` against
  Clojure — composed with a murmur fixture transcribed into each head section,
  not with the generated one;
* **the composition of the two, inside a real runtime, against a pinned
  number** is `runtime/src/hash.rs`'s own tests, which still run under
  `cargo test` and still assert `hash_bytes(b"a") == 1455541201`.

The composition is therefore pinned once (native) rather than twice (jvm, clr),
and the two ports are covered by being generated from the same source as the
native one plus the agreement check. That is a weaker end-to-end pin on the
ports and a stronger one everywhere else, and it is written down here rather
than discovered.

### Counts

| | before | after |
|---|---|---|
| hand-written assertions | 24 in `RtHash.java` + 24 in `Program.cs` | 0 |
| distinct values pinned | 24 | 27 |
| targets each value is checked on | 2 (jvm, clr) | 3 (rust, java, csharp) |
| checked against a written-down expectation | no | yes |
| hand-written copies of the four functions | 3 | 0 |

The three added values are the two halves of the empty-namespace identity and
the second half of the emoji pair, both of which were single boolean assertions
before. Nothing was dropped.

### Where they run, and what it cost

`bin/conform-hosts` loses the hash phase and its cross-port `cmp`; the run is
141 s and the two rows were two process launches inside a phase that never
appeared in the top ten, so the saving is not measurable and is not claimed.
`bin/check-kin` gains one source: 90 sources in 66 s became 91 in 73 s,
`time` on this machine. It is parallel and runs in the static-check group,
where `bin/conform-hosts` is a two-minute gate — so the check moved from the
expensive end of the suite to the cheap one, which is the part of this worth
having.

## wasm-engine

**Ratified:** ☐ not signed off

Recorded 2026-09-12. `flint.sys.wasm` is the served namespace for running a
compiled module. It exists because running a module is the ONE host operation
that differs between the three front ends in kind rather than in spelling, and
that difference had been leaking upward: `bin/flint` shells out to `node` three
times purely to execute wasm, and `flint.cli/run` returned `{:exec {...}}` —
a task handed back for the host to run — because the CLI could not run one
itself.

### Why a native binary has no engine

The native CLI carries flint's runtime compiled natively: the same interpreter,
collector and builtins the wasm module is built from. What it does not carry is
a **wasm engine**, and it should not. Embedding one costs megabytes in every
copy of the binary for something most runs never do, and the binary's whole
argument is that you get flint without installing an ecosystem.

So it LOOKS for one, in the order that asks least of the machine:

1. **`jsc`** — JavaScriptCore. Ships with macOS, needs no install, and is not
   on anyone's `PATH`: it lives at
   `/System/Library/Frameworks/JavaScriptCore.framework/Versions/A/Helpers/jsc`.
   Note `Helpers/`, not `Resources/` — the `Resources/` path is the one that
   gets written down and it does not exist.
2. **`node`, `bun`, `deno`** — whatever is already installed.

**`wasmtime` is not in that list even when it is installed**, and that is the
point of the list being short: the driver hands the engine a `.mjs`, so pinning
wasmtime would mean handing it a file it cannot read and failing on every run
afterwards. See "What is not built" below. Listing something that cannot be
driven is worse than listing nothing.

The answer is written to `~/.flint/wasm-runner` as `kind path`. The search is
worth doing once, not once per invocation. `(wasm/reset)` forgets it and
`(wasm/use path)` pins one by hand; on node both are no-ops that return
successfully, so a program written against this namespace runs on either host
without asking which one it got.

### That the engines agree is measured, not assumed

`out/conform.wasm` — the conformance suite, 524 648 bytes — run as
`conform.runner/main` on each engine, output hashed:

| engine | exit | md5 of stdout |
|---|---|---|
| `node host/flint.mjs` (the existing driver, reference) | 0 | `c64358649c2b` |
| jsc | 0 | `c64358649c2b` |
| node | 0 | `c64358649c2b` |
| bun | 0 | `c64358649c2b` |
| deno | 0 | `c64358649c2b` |

Byte-identical on all four. This is the same standard `kin/scripts/verify`
holds the three code generators to, and for the same reason: four engines that
agree exactly are doing the same work, and four that nearly agree are four
implementations of something nobody has specified.

### What was portable already, and what was not

Almost all of it. `sdks/esm/src/guest.js` and `codec.js` were written with no
`node:` import, no `process` and no filesystem — the header of each says so —
and that held up: the guest driver, the pump and the capability plumbing ran
unchanged on all four engines. Two things did not, and they are the whole of
`host/portable.js` (64 lines):

* **`TextEncoder` / `TextDecoder`.** These are Web APIs, not ECMAScript. The
  jsc shell has neither, and the codec asks only for UTF-8 — twenty lines.
* **Reading a file.** `node:fs` covers node, bun and current deno. jsc has a
  bare `readFile`, which needs `readFile(path, "binary")` or it hands back a
  string.

Two further differences were designed around rather than papered over:

* **argv.** Four shells disagree: jsc's module mode has no `process`, deno
  wants `--`, bun follows node. The job therefore travels in a generated
  prelude that sets one global and imports the driver — identical on all four.
  Everything it carries is escaped as a JS string literal (`js_string`),
  because a module path and a function name are text being pasted into source.
* **stdout.** `inst.run` CAPTURES the program's output rather than writing it,
  so the driver prints exactly one line of JSON and nothing else. There is no
  interleaving to get wrong, and no dependence on whether an engine spells
  `process.stdout.write` or appends a newline to `print`.

### Running a module is a grant

`wasm` is a capability like `fs` or `env`, gated by presence in `:with`, and it
has to be: `(wasm/run m "ns/f")` executes arbitrary code. It is not something
the CLI does because it is able to.

### What is not built

**The wasmtime fallback.** The intended last resort — download the platform's
`libwasmtime` and use it — is NOT implemented, and the "no engine found" error
says so and names what was looked for.

The reason is that it is not the same shape as the other three. The wasmtime
**CLI** cannot drive this ABI: a flint module has no `_start` and no entry
point (`DECISIONS.md#structured-ports` step 5), and `wasmtime run --invoke`
cannot write arguments into the instance's memory, call `flint_call` and read
the result back. Using wasmtime means embedding the **library** through its C
API with `dlopen`, which is a different piece of work from spawning a process,
and shipping a downloader for a native shared object is a supply-chain decision
rather than a convenience.

What this costs: a machine with no JavaScript engine at all gets a clear
refusal instead of a download. On macOS that machine does not exist. On Linux
without node, bun or deno it does, and that case is open.

## flint-sdk

**Ratified:** ☐ not signed off

Recorded 2026-09-12. `flint.sdk` is a served namespace giving flint code the
compiler: `compile`, `run`, `version`.

`sdks/c`, `sdks/rust` and `sdks/esm` let C, Rust and JavaScript embed flint.
This is the same offer made to the language itself, and the reason it can be
made at all is that flint is self-hosted — the compiler is already linked into
the binary, so a flint program compiling another flint program is a function
call.

### What it replaces

`flint.cli/run` returns `{:exec {:src ... :entry ... :paths ...}}` for
`flint task`: not an answer, but a job handed back for the host to do. Each of
the three front ends then does it its own way — `bin/flint` writes the source
to a temp directory and shells out to `bb bin/flint` to compile it, then to
`node host/flint.mjs` to run it, two processes deep.

That is the hand-back the user's question was about: *why should the CLI tell
the host to compile and run something, when the compiler can be imported into
the CLI build?* It should not. With `flint.sdk` it does not have to, and with
`flint.sys.wasm` (`DECISIONS.md#wasm-engine`) the running half is covered too.

### The shape is the other SDKs' shape

`sdks/rust` is the reference: `Compiler::compile(Compile { resolve, fn_name,
exports, .. }) -> Image`, then `Image::sandbox() -> Sandbox`, then
`Sandbox::call(name, args)`. `flint.sdk` is the same four steps —
`compile`, `sandbox`, `call`, `close` — because a program embedding flint
should not find a different vocabulary than C, Rust or JavaScript would.

### It grants NO IO, and that is the point

The first version took `:paths` and `:out`, which meant `sdk` alone could read
any tree and write any path without holding `fs`. It now takes **`:sources`, a
map of namespace to source text**, and hands back the artifact **bytes**. There
is no path in the request for a caller to point anywhere.

A caller compiling a project on disk reads it with its own `fs` grant first and
passes the text in; a caller wanting the artifact on disk writes it the same
way. The two capabilities compose, rather than one silently implying the other.
This is the same answer `sdks/rust` gives with a `resolve` closure — the host
decides what the compiler may see — expressed as data because a served call
cannot hold a callback into the guest that made it.

The sources are written to a private temporary directory the caller cannot
name, and the ordinary spec builder runs over that. That is the host touching
its own disk, not the guest reaching anything, and it keeps one spec builder
rather than a second that agrees with it until it does not.

A constructed sandbox **holds nothing**: no ports, no capabilities, no IO. It
reaches the world only through what it is handed afterwards — the inversion
`DECISIONS.md#ports-are-the-hosts` made for ports, applied to everything.

### `call` passes arguments individually

`(call box "guest/greet" ["ada" "alan"])` calls `greet` with two arguments.
That is the `flint_call` ABI, and it is NOT what `run` does: `run` hands its
arguments to the entry as one vector, the `main [args]` convention.

**Both front ends had to be corrected to agree here**, in opposite directions.
The native side first used `Program::run`, which has no way to select a
function at all — it calls the image's compiled-in entry, so the function name
arrived as argument zero and `(call box "guest/main" ["a" "b"])` reported three
arguments. The node side first used the driver's `run`, which wraps arguments
into one vector, so the same call was an arity error there and not on native.
One surface, two meanings, until it was measured.

A failure is DATA on both — `{:error kind :message text}` — rather than a
second channel. The node driver throws, so its answer is turned back into that
value rather than into a different kind of failure.

### `:exports` is not `:roots`

Only reachable code ships, so a function nobody calls from the entry is exactly
the one a host wants to call: `(call box "guest/greet" ..)` answers "this image
has no `guest/greet`" for a function whose source is right there. `:exports`
keeps it. It is a separate spec key from `:roots`, which names namespaces to
RESOLVE from — a qualified function name there reports itself missing, which is
what the first attempt did.

### What the two front ends do not share

`compile` answers a flint bytecode IMAGE natively and a **wasm module** on
node, because node has no natively linked runtime to load an image into. The
same difference `run` already had and states. The bytes are therefore not
portable between hosts; the answer from `call` is.

### `run` interprets; `compile` produces an artifact

`(sdk/run {:paths ["."] :fn "ns/f"})` needs **no module and no wasm engine** on
the native binary: the runtime is compiled in, so the source is loaded into a
second `Program` in the same process and interpreted. `compile` is the one that
writes a module, and `flint.sys.wasm/run` is what executes one afterwards.

On node, `run` goes through a module — that is what node has — and the header
on `runSource` already said so. Same answer, different amount of work, and the
difference is stated rather than hidden.

### It is NOT a grant, and it is off under a gas limit

It used to be gated by `:with [sdk]`. It is not any more, and the reshape above
is why: `compile` takes source text and hands back bytes, so the SDK reaches
nothing a program could not already reach. It is pure computation. Gating it
bought no safety and made every nested compile ask for a capability that
conferred nothing.

**Except under a gas limit.** A limit is a promise about how much work a
program may do before it is stopped, and a nested sandbox runs on its OWN
budget — so a program that could build one steps outside the promise by
construction, however small its own allowance. The guarantee holds for the
whole process or it is not one. `FLINT_STEP_LIMIT` is the spelling, the same
one `host/flint.mjs` and the wasm driver already use.

The namespace is SERVED AND REFUSING rather than absent, so the reason is said.
Left unserved, a program meets "this sandbox was given no system port", which
names neither this namespace nor the limit that turned it off.

**A consequence worth stating: a program granted nothing now has a system
port.** `test/globalport.clj` puts the old property as "a sandbox given none
can run its logic and ask the world for nothing", and the transport being
absent was how that showed. Serving the SDK to everything means the port always
exists, so an ungranted namespace is refused BY NAME — `the host refused to
open "flint.sys.fs"` — rather than by the transport being missing. Strictly
more informative, and what a program can reach is unchanged. It is written down
because it is a real change to a property this project stated deliberately.

### Turning it off at compile time

`:flint/nested` is in `flint.reader/default-features`, and a build whose
`:features` omits it does not get the namespace emitted at all — so
`(:require [flint.sdk])` is a COMPILE error and the artifact cannot reach the
SDK however it is later run. A feature rather than a grant, because it says
what this artifact is allowed to BE rather than what it may reach.

    flint compile :path . :fn app/main :features [flint flint/check]

**The strip-checks set had to learn about it.** `:optimize [perf]` emits
`:features #{:flint}` to drop `:flint/check` — that literal was the whole
default minus checks, so once the default gained `:flint/nested` the old
emission would have turned the SDK off in every performance build as a side
effect of dropping checks. It is now `#{:flint :flint/nested}`: two features,
two decisions. Both front ends emit it, and `sdks/cli/selftest.mjs` compares
them on this axis too.

A namespace the CLI withheld reads exactly like one the author misspelled, and
the compiler cannot tell them apart — it was never offered either. So the CLI,
the only side that knows, says so:

    no source for flint.sdk
    `flint.sdk` is not missing -- this build turned it off. `:features` was
    given without `:flint/nested`, which is what makes the SDK nameable.

### `run` still may not lend what the caller lacks

**`run` takes `:with`, and the first version let the caller put anything in
it.** That made `sdk` the only capability anyone needed: a program granted
`sdk` alone could write a child that reads a file, run it with `:with ["fs"]`,
and read the answer. Probed rather than reasoned about, on the day it was
written, and it worked — `code=0 out=child read: SECRET-CONTENTS`.

A caller may now pass on only what it holds, and an excess is REFUSED rather
than quietly narrowed — a child that silently loses a capability fails
somewhere else, for a reason that does not name this:

    run: this program was not granted `fs`, so it cannot lend it.
    it holds: sdk
    a program may pass on what it has, not mint what it has not.

Scoping goes one way, and both directions are tested: holding bare `fs` lends
`fs:write`, and holding `fs:write` does **not** lend bare `fs`. The chain is
bounded at every link, because a child's own `flint.sdk` is constructed with
the child's capabilities.

`test/sysns.clj` carries the attack and a control that differs in exactly one
thing — the same caller, granted `fs` as well, must still succeed. Without the
control, a refusal for any unrelated reason would read as the check working.

### What had to change to serve it on node, and what it cost

The node compile path was `async` for exactly one reason: `await
WebAssembly.compile(...)`, three times. A served `invoke` has to answer in one
call — `serveRequest` returns the reply that `api.deliver` queues — so an async
compiler could not be served at all without making the pump itself async.

`new WebAssembly.Module(bytes)` compiles synchronously and `inst.run` is a pump
rather than a task, so `runCompiler`, `compile` and `runSource` became
synchronous functions. **Existing `await compile(...)` call sites are
unaffected**, because awaiting a plain value is a no-op — nothing outside the
file had to change.

Both `compile` functions gained a quiet form, the split `run_source_q` already
made: a library call that writes `wrote /tmp/…/task.wasm` to the user's
terminal is chatter the caller did not ask for, and `flint task` would print it
on every run.

### That the two agree is measured

The same program — a flint namespace that calls `sdk/run` on a second namespace
and then `sdk/compile` on it — through both front ends:

    native: v=0.0.1 code=0 out=inner says hi to 0 args bytes=596255
    node:   v=0.0.1 code=0 out=inner says hi to 0 args bytes=596255

Identical, module size included.

### Not yet done

The `{:exec ...}` arm still exists and `flint task` still goes through it. This
decision records the capability that makes removing it possible; removing it is
a change to `lib/flint/cli.cljc` and to all three hosts, and is its own step.

## aot-diverges-between-hosts

**Ratified:** ☐ not signed off

Recorded 2026-09-12, **and fixed the same day.** Found while checking that
`flint.sdk` had broken nothing; it was older than that work.

### The symptom

Under `:optimize [perf]` the two CLIs compiled one fixture to different
modules. Without it they were byte-identical.

| arm | bytes | wasm functions |
|---|---|---|
| native `flint compile` | 698 224 | 1078 |
| node `flint compile` | 715 879 | 1101 |

### The cause

**The node CLI had no `:checks` axis at all** — zero occurrences of the word in
`sdks/cli/src/`. On the native CLI, `strip_checks(optimize, checks)` makes
`:optimize [perf]` compile the checks out by emitting ` :features #{:flint}`
into the spec, which drops `:flint/check`. On node nothing did, so **every
module the npm CLI produced under `:optimize [perf]` shipped its own check code
in.** That is a semantics difference, not a size one; the byte count is how it
was noticed.

The fix is `stripChecks(optimize, checks)` in `sdks/cli/src/cli.mjs`, the same
rule as the native `strip_checks`, plus `:checks true|false` in its argument
parser and `stripChecks` threaded through `buildSpec`. Both CLIs are now
byte-identical on both arms, which `sdks/cli/selftest.mjs` asserts.

### How it was found, because the first three answers were wrong

Worth keeping: the symptom pointed at the AOT emitter and the cause was not
there.

* **"A stale artifact."** No — reproduced after rebuilding the binary against
  the current `dist/`, in that order.
* **"Two different compilers."** No — `dist/flintc.bytecode` and
  `dist/flintc.wasm` share 99.3% of their bytes (`bytecode[1285:195787]` occurs
  verbatim inside the wasm). Both are `FLINTIMG` v3. The 1285-byte head that
  differs is the builtin→slot table: zeros in the native image, ascending
  indices in the wasm one, which is the host binding and is meant to differ.
* **"Byte signedness in `flint.aot/decode`."** The most attractive wrong
  answer. `u16`/`i16` build jump targets from `(nth bs 0)` and `(nth bs 1)`,
  and this file already records a "zero-extends on native, sign-extends on both
  ports" divergence in the regex engine. A probe through both front ends gives
  `b0=195 b1=169 u16=43459 i16=-22077` on both. Not it.
* **"The AOT emitter refuses different arities."** Also no. Instrumenting
  `flint.bundle/aot-bundle` to throw with its refusal list gave `AOTREFUSED []`
  on BOTH — neither refuses anything. That is what turned the search around:
  the difference was in how many arities were OFFERED, not how many were taken.

The measurement that ended it, from a throw placed immediately before
`compile-arities`:

| | native | node |
|---|---|---|
| plain | `fns=142` | `fns=142` |
| `:optimize [perf]` | `fns=123` | `fns=142` |

Node was consistent; **native dropped 19 functions**. Read that way round it is
not an AOT bug at all — it is native stripping checks and node not.

### Why AOT is where it surfaced

Shaking runs AFTER `compile-arities`, and the arities it appends become roots
("a tree shaker cannot find them"). Without `:optimize [perf]` the extra check
functions are dead and the shaker removes them, so the two modules come out
identical and the divergence is invisible. With it, they are compiled to wasm
functions first, rooted, and survive. **The bug was in every `:optimize [perf]`
build; AOT only made it observable.**

### Why nothing caught it

`sdks/cli/selftest.mjs` is the only thing that compares the two front ends'
output, and no gate ran it — not `bin/check`, not `bin/test`, not
`bin/release-gate`. `bin/test` now runs it, beside `sdks/esm/build`. Not
`bin/check`: it takes 30 s against that gate's whole 5 s budget, and it needs
both CLIs built, which is a test-tier requirement rather than a static one.
