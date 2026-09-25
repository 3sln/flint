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
[no-runtime-linking](#no-runtime-linking) ·
[the-shadow-stack-is-not-a-default](#the-shadow-stack-is-not-a-default)

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
[thread-pool](#thread-pool) ·
[bridges-are-the-only-door](#bridges-are-the-only-door) ·
[the-codec-is-guest-code](#the-codec-is-guest-code)

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
  hand-duplicated lines and thirteen unportable tests turn on it. (The pike VM
  ITSELF is now generated, all four functions, and the capability question that
  blocked it is settled — kin gained an aliasing axis on 2026-09-16. What is
  left for you is the scope question about `Rt`, not a blocker.)

  **MEASURED 2026-09-21, because the decision turns on that number. The
  figure has now been corrected downward three times; read the history below
  before quoting it.** Of the jvm's hand-written METHOD lines, **95 are
  three-way and generatable** as of `38877f16` — and that is still an upper
  bound.

      published   what it missed
        228       field accesses: a method reading `rt.gc.from` and iterating
                  `rt.gc.oldChunks` counted as generatable, because neither is
                  a CALL
        166       vocabulary words: `chargeTick` and `newObj` are words kin
                  already emits, so generating them is circular. `--rank` had
                  filtered them since that check existed and `--calls`, which
                  produced this figure, did not
         95       current. 23 of the difference is `node-entries`, taken and
                  generated; the rest is the filter

  *Every refinement has moved it down and none has moved it up.* Treat 95 as a
  ceiling rather than a measurement: the remaining classifier leniencies are
  named in `doc/goals/kin-port.md` and all of them point the same way.

  The original wording, for the record: of the jvm's 3 274 hand-written METHOD
  lines, 166 were said to be three-way and generatable
  — every call and field access in them being something kin can already emit,
  and the same method existing on native. The pools now are `Rt` at 35, `Obj`
  at 14 and `Val` at 13, with no single method above eight lines.

  That is **under one per cent** of the 21 000, which counts duplication of
  every kind and is mostly host strings, host collections, raw memory and host
  callbacks — none of which a generator can take.

  **The first figure published here was 228 and it was an upper bound.** It
  came from a classifier that checked every CALL a method makes and not its
  FIELD accesses, so twenty lines of raw heap walking
  (`Snap.countHostOpaques`, which reads `rt.gc.from` and iterates
  `rt.gc.oldChunks`) counted as generatable. A blocklist run alongside it said
  256, and the two were described here as independent agreement; they were not
  independent enough to be worth that. 166 is the figure with both calls and
  fields checked, and it is the one to quote.

  `bin/port-survey --calls` re-derives it; the working and the correction are
  in `doc/goals/kin-port.md`. The scope question is still yours — this only
  says what answering it yes would buy.
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

> **THIS TRIAGE IS ITSELF STALE, corrected 2026-09-19 by counting.** Every
> figure in it has moved, and one of them had moved in a way that hid work:
>
>     claim here                          counted 2026-09-19   2026-09-20
>     47 unratified                       55                   55
>     32 carry "not independently
>       verified"                         1                    0
>     port-tests-in-kin is THE ONE
>       item with no status line          it was one of EIGHT  --
>
> Counted again 2026-09-20 by grep, not by reading: 56 `##` headings, 55 of
> them `Ratified: ☐`, none ticked. The 56th is *about this file*.
>
> **THE LAST ONE WENT 2026-09-20**, and it was worth the check: `other-hosts`
> not only repeated an unverified status, it carried TWO that contradicted
> each other about whether native AOT was built, and a sentence that stopped
> mid-clause. Four of its six factual claims were false, all in the direction
> the preamble above calls the dangerous one -- saying unbuilt about something
> that ships and is gated by `bin/check-llvm`.
>
> The 32 shrank because the verification this triage asks for was largely done.
> The 47 grew because decisions kept being added. And the last line was the
> costly one: seven more status-less sections had appeared since, so the item
> named as the single unassessable claim was an eighth of the problem. All
> eight now carry a status line, written 2026-09-19 against the code --
> `port-tests-in-kin`, `wasm-engine`, `flint-ception`,
> `aot-diverges-between-hosts`, `calls-are-ports`, `vars-is-its-own-grant`,
> `the-codec-is-guest-code`, `bridges-are-the-only-door`.
>
> One internal contradiction, for the record: this triage lists
> `port-tests-in-kin` among the eight "ratifiable as recorded -- built,
> checked, behaving" AND as the item whose claim cannot be assessed. Both
> cannot hold.
>
> **The lesson is the one this file exists to teach, turned on the file.** A
> count written into prose is a status claim like any other, and it decays the
> same way. `bin/check-decisions` proves a citation resolves; nothing proves a
> NUMBER in a paragraph, so re-count before quoting these.

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

### DECIDED: gas charges ALL guest code, and the third mode is not worth it

Three reds in the survey trace to one thing: gas was defined when a program ran
with no scheduler under it, and every image now carries a control plane.
Measured, not argued -- the native-jvm gap is one parked thread's scheduling
cost (~46 steps a slice, scaling with thread count), and an unbudgeted wasm run
counts 41 392 steps where the contract claims 0.

A three-way mode was proposed -- `Off` / `Program` / `Total`, where `Program`
billed the embedder's code and not the machinery. **Rejected, and the reason is
worth keeping**, because the design looked free and is not.

**Gas is already collected locally.** `par.rs` batches it: `GAS_BATCH = 4096`,
"how many instructions an executor counts locally before telling anyone",
because "a shared counter incremented once per instruction would put an atomic
read-modify-write on the interpreter's hottest line and have every thread
fighting for one cache line". Each executor has its own `Rt` and its own
`steps`. (The publish half of that design turns out not to be wired -- see
below -- but the LOCAL half is exactly how it already works.) So local
collection is not something a per-thread mode would have to invent.

I first argued the mode would force an atomic publish at every green-thread
switch, since `SLICE` and `GAS_BATCH` are both 4096. **That was wrong and is
worth correcting**, because it conflates two different operations. ATTRIBUTING
an executor's local steps to some other counter at a switch is a local add --
`counter += rt.steps - start` -- two reads and a subtract, no atomics.
PUBLISHING to the shared total is the expensive one, and it can stay batched at
4096 however often attribution happens. Per-thread accounting is cheap.

The mode is still not wanted, but for the plainer reason: **all guest code is
charged, including the control plane's.** It is guest code --
`lib/flint/system.cljc`, compiled into the image like anything else -- and a
sandbox that runs it is doing that work. Splitting the bill is a distinction
the embedder did not ask for.

### Two counters and two caps, because an entry limit is wanted anyway

Per-ENTRY accounting is not the rejected mode wearing a hat: it buys something
the sandbox-wide cap cannot. A global cap bounds a sandbox's whole life, so one
runaway call either trips it -- killing a sandbox that was meant to serve many
more calls -- or hides inside a budget large enough for all of them. **A
per-entry limit stops the runaway call and leaves the sandbox alive**, which is
the behaviour a server wants.

So both, and they are independent:

* **Per-entry count and limit.** Starts at zero when a call is entered, bounds
  that call. Attribution is the local add above, done where green threads
  switch.
* **The live sandbox count and cap.** What exists today: batched per executor,
  published every `GAS_BATCH`, bounding the sandbox's total.

**The checkpoint already has room for it.** `refresh_checkpoint` is

    checkpoint = min(gas_limit or MAX, slice_end or MAX)

-- a merge of "stop to preempt" and "stop, budget spent". An entry limit is a
third term in the same `min`, so the hot loop keeps ONE comparison against ONE
number and learns nothing new. That is the property worth protecting, and this
design keeps it.

**And it dissolves the SDK row.** With an entry counter there is a real answer
to "what did this call cost" that does not depend on whether anyone set a
limit, so `gas()` reporting 0 for an unbudgeted sandbox stops being a fudge and
starts being a different question with its own counter.

### What still has to change, and it is smaller than a mode

Charging everything does not make the reds go away; it says which way to fix
them.

**`conform-hosts`** compares a runtime that has a control plane against one
that does not. `RtSteps` calls `runProgram` directly and `runtimes/` has no
system port at all, so the two sides are not running the same program. With
"charge everything" settled, the fix is the one this file's first option named:
make the ports call over their system ports too. Then both pay, and the row
compares like with like.

**The SDK rows are a REPORTING question, not a counting one.** `counting()` is
`checkpoint != u64::MAX`, and `refresh_checkpoint` sets

    checkpoint = min(gas_limit or MAX, slice_end or MAX)

so arming a slice makes `counting()` true with `gas_limit == 0`. That is one
predicate doing two jobs: "I must stop at a boundary soon" and "I am billing".
The interpreter has to count to preempt -- preemption is step-based -- and that
is not the embedder asking for a bill. Nothing in the hot loop needs to change
for this: the steps are needed either way. What needs deciding is what `gas()`
REPORTS when no limit was ever set, and "0, because nothing was budgeted" keeps
the SDK's assertion true without touching the counter.

### The shared cap is BUILT AND UNWIRED, which changes the premise

The discussion above assumed a sandbox-wide total that executors publish into.
`par.rs` has one -- `set_limit`, `limit()`, `spent()`, `flush_gas(batch)` and
the `GAS_BATCH = 4096` constant, with a docstring explaining the whole design.
**None of it has a single caller.** Checked across `runtime/`, the units and
the SDKs: `GAS_BATCH` appears once, at its own definition, and the three
methods appear only where they are defined.

What exists instead: `sdks/rust` holds `executors: Vec<Mutex<Option<Box<Rt>>>>`
-- **each executor is its own `Rt`**, with its own `steps` and its own
`gas_limit`, sharing only the heap -- and the SDK's two entry points touch only
the first of them:

    pub fn set_step_limit(&self, n: u64) {
        self.core.program.lock().unwrap().set_step_limit(n);   // the PRIMARY only
    }
    pub fn gas(&self) -> u64 {
        self.core.program.lock().unwrap().steps()              // the PRIMARY only
    }

So a spare executor is not merely counted separately -- it is **not limited at
all**, and what it spends is **not reported at all**. Guest code really does run
there: the SDK counts "dispatches that ran on a SECONDARY executor" and the gate
prints the number. A budget set on a pooled sandbox bounds one of its threads.

`gas`'s own docstring says as much, in the future tense: "When several threads
really run one heap, gas becomes per-executor and summed ... and this WILL THEN
BE a snapshot true at a safepoint." The intent was recorded; the wiring is what
is missing, and `par.rs` holds the half that was written for it.

That is latent rather than live -- `parallel` is off by default and on for the
Rust SDK -- but it is the hole the unwired code was written to close, and the
comment describing the batching reads as a description of shipped behaviour
when it is a description of an intention.

**And it makes the adaptive batch the right design rather than an optimisation.**
The recorded price of batching is "a limit stops the program a little late --
by at most this times the number of executors". Shrinking the local batch as
`spent` approaches `limit` removes that: far from the limit an executor
publishes every 4 096 instructions and the atomic stays off the hot line; near
it, the batch narrows and the limit is enforced tightly. The common case keeps
the cheap path and the endgame gets the exact one, which is the property a
budget needs -- a cap that overshoots by `batch x executors` is not a cap
anyone can reason about.

**Not built.** Three independent pieces now: the ports' system-port entry, what
an unbudgeted sandbox reports, and wiring the shared total with a batch that
narrows as the limit nears.

> ALL THREE ARE BUILT, and the third was already done when this said it was
> not. What an unbudgeted sandbox reports was settled when `gas()` was made to
> honour its own docstring. The shared total and its narrowing batch went in on
> 2026-09-18. **The ports' system-port entry had ALREADY landed** -- both ports
> have a system port and `bootSystemThreadOnce`, and the jvm's `RtSteps` calls
> over a bridge -- so the text above, which says `runtimes/` has no system port
> at all, was stale and was sending the next reader to do work that was done.
> The clr's `RtSteps` was the genuinely unfinished half; see the section below.

### BUILT: the cap is on the sandbox, and it was not before, 2026-09-18

Picked up because this record named it unbuilt and because it is the only one
of the three that is a broken GUARANTEE rather than a conformance row. It was
worse than "latent".

**Demonstrated before it was fixed, which is the only reason to believe any of
this.** A pooled sandbox, four threads, given a two-thousand instruction
budget, asked for sixteen calls to a ten-million-iteration loop:

    0 refused, 16 ran to completion;
    1 dispatches served 16 calls, 1 of them on a secondary

ONE dispatch carried all sixteen calls onto a secondary executor, and that
executor had never been told about the budget. The debouncing makes it worse
than the per-thread picture suggests: a batch is unbounded in size, so one
unbudgeted executor can serve an arbitrary number of calls. The primary, which
does hold the limit, was never consulted.

**What was wired.** The design was already written and already had no callers;
this is the calling.

* `Rt::arm_shared_gas` / `Rt::flush_shared_gas`. Under a shared budget
  `gas_limit` stops meaning "the cap" and starts meaning "the end of this
  executor's current batch", so the interpreter's trip path asks
  `flush_shared_gas` BEFORE it raises anything: publish what this executor
  spent, and carry on unless the SANDBOX is out. It answers true when there is
  no shared budget, so an inline sandbox and the whole wasm build keep the old
  meaning with no second test in the hot path.
* `Rt::gas_batch(limit, spent, executors)` -- the narrowing. `remaining /
  executors`, clamped to `[1, GAS_BATCH]`.
* `Program::set_shared_step_limit` / `shared_gas` / `arm_shared_gas`, and the
  SDK: `set_step_limit` sets the sandbox's budget when there are other
  executors, both dispatch paths arm before running guest code, and `gas()`
  reads the shared total rather than one executor's `steps`.

**The narrowing is what makes it a cap rather than an estimate, and it is one
line of arithmetic.** The recorded price of batching was "a limit stops the
program late, by at most the batch times the number of executors". Dividing
what is LEFT between the executors that could spend it turns that into
`executors * batch <= remaining` -- every executor may run its whole batch and
the total still lands on the limit rather than past it. Far from the limit the
division exceeds `GAS_BATCH` and the clamp hands back the cheap path, which is
the entire reason batching exists: one atomic per 4 096 instructions, off the
interpreter's hottest line. Only the endgame pays for exactness.

**Measured after: 400 000 asked for, 467 548 spent.** The overshoot is one
`GAS_GRACE` (65 536, granted once so a `finally` can run) plus about two
thousand. The batching contributes the two thousand.

**THE BATCHING HIDES A LEAK, and I wrote it in before writing the test that
found it.** Worth recording because the mistake is natural and the symptom is
silence.

An executor publishes when it reaches a batch boundary. A call that ENDS before
it reaches one has therefore spent something nobody has been told about, and
the obvious way to arm the executor for the next call -- move the watermark to
where `steps` now stands -- DISCARDS that rather than deferring it. The
consequence is not a small under-count: a stream of calls each shorter than a
batch never publishes anything at all, so the total stays at zero and the
budget is never spent however long the sandbox runs. A cap that holds for long
calls and not for short ones is worse than no cap, because the long-call test
passes.

Arming now publishes first. Measured after: a 60 000-instruction budget stops a
stream of `tally` calls after 1 432 of them, with `gas()` reading exactly
60 000.

The test that found it uses `call_blocking` ON PURPOSE. A burst would be
coalesced into one dispatch big enough to cross a boundary by itself, and the
leak would close by accident -- so the test that looks more realistic is the
one that cannot see the bug.

**Four gates, and every one was made to fail first.**

    sdk.rs  a_step_limit_binds_every_executor_not_just_the_first
            16 calls, every one must be refused. Written so the WRONG
            behaviour is a passing call rather than a slow one, and it
            asserts a secondary was used -- without that witness it would
            pass just as well when nothing was parallel.
    rt.rs   the_batch_never_lets_the_executors_overshoot_together
            The arithmetic, over the whole endgame rather than at a point.
            Flattening the batch back to GAS_BATCH: RED.
    rt.rs   past_the_limit_the_batch_does_not_wrap
            `spent` passes `limit` in the ordinary course -- the executor
            that crosses publishes its whole batch -- and an underflow there
            would hand back a colossal batch at exactly the moment the budget
            needs to be tight. `wrapping_sub` instead of `saturating_sub`:
            RED.
    sdk.rs  a_stream_of_short_calls_still_spends_the_budget
            4 000 calls of ~42 instructions under a 60 000 budget. It was
            RED on the full gate before the arming was made to publish --
            18 passed, 1 failed, and that one was this.

The end-to-end test deliberately does NOT guard the narrowing, and says so in
its own comment: grace dominates that measurement, and a flat batch would still
land inside its bound. The arithmetic is guarded by the arithmetic test. A
number that cannot discriminate is worse than no number, because it reads as
coverage.

**It costs the default build nothing**, which was a constraint rather than a
hope: `parallel` is off by default and priced at 1 056 module bytes, so every
line of this is inside `#[cfg(feature = "parallel")]`. The plain build compiles
with no reference to any of it.

**Full gate after, `FLINT_TEST_KEEP_GOING=1`: 62 of 62 sections, the same three
reds as before with the same numbers** -- `test/aot.clj`'s `colls`,
`conform-hosts` at 174, `test/document.clj`'s peak-memory row -- and the Rust
SDK section green at 19 tests. The plain module is 503 538 bytes in that run
and in the one before it, byte for byte, which is the evidence for "costs the
default build nothing".

**`gas()`'s docstring was corrected while it was being made true.** It said the
summing "will then be" a snapshot at a safepoint and that it "is not wired".
The first is now the present tense and the second is no longer so, and a
docstring describing an intention in the voice of shipped behaviour is the
failure mode this file exists to prevent.

**Still open, and unchanged by this:** the ports' system-port entry, which is
what the `conform-hosts` gas row needs; and the jvm and clr have no shared-budget
path at all, because they have no executor pool to need one.



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
(`snap::MAGIC_LIVE`, `runtime/src/snap.rs:578`), and
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
queue, `SC_EVENTS`, written only by `push_event` (`conc.rs:1486`) and read only
by `drain_events` (`conc.rs:2969`), carrying all six event kinds. Two
lifetimes: `reap_ports` (`conc.rs:2288`) treats a flint end the collector lost
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
| the host calls any function BY NAME | `Sandbox::call(name, args)`, `sdks/rust/src/sandbox.rs:325` |
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
simpler sweep — `reap_ports` (`runtime/src/conc.rs:2288`) walks `SC_BRIDGES`
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
a real, named, and still-open cost. **Measured, 2026-09-22:** twenty
references to ONE subtree encode to 1 065 bytes, and twenty separately built
subtrees that are merely `=` to each other encode to 1 065 bytes as well —
identical at two sizes, which is what "no back-references" means stated as a
number. Each extra reference costs **53 bytes**, the whole of the subtree
again. `codec::tests::a_shared_subtree_still_encodes_once_per_reference`
holds that, control and all, and goes red the day the cost is paid off.

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
(`runtime/src/conc.rs:1486`) is still a read-modify-write that `vec_conj`s onto
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
(`Rt::lock_intern`, `runtime/src/rt.rs:596`, used by `runtime/src/strs.rs`),
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
`runtime/src/obj.rs:92`, `flint/opaque` in `dist/builtins.json` — and identities
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
ordinary encoded argument (`K_SENTINEL`, `runtime/src/codec.rs:174`) or over a
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
probed rather than read.* **Rule 1 was inert and is now live; rules 2 and 3
still are not.** As first written this paragraph said all three of
`system-namespaces-and-deps`' delegation rules were inert in the shipped
binary, and that `lending-errors` had no caller anywhere in the tree. That was
true when it was written and `25c50dc6` fixed the first of the three; the
paragraph was not updated with it, so it went on citing a file the function had
by then left. What holds today, re-probed:

* **Rule 1 — you cannot lend what you do not hold — is enforced.**
  `lending-errors` (`lib/flint/deps.cljc:707`) moved out of
  `flint.deps.resolve`, which `flint.cli` can never require: that namespace
  pulls in `flint.deps.npm` and `flint.deps.git`, which are VIRTUAL and served
  by the CLI, so the rule was structurally unreachable from the only place a
  refusal can happen. `lending-errors` (`lib/flint/cli.cljc:225`) is called
  before anything is fetched, and refuses `build`, `task`, `paths` and
  `fetch`. `bb test/cli.clj` passes today, including the refusal and both
  controls — a project that HOLDS the capability may lend it, and a project
  with no grants is untouched.
* **Rule 2 — a dependency declaring a guard must be granted it — is written but
  cannot fire.** `lending-errors` implements it, but only in its 3-arity, from
  a `guards` map of each dependency's own demands. The one caller uses the
  2-arity, so `guards` is always `{}` and the rule contributes nothing. It
  needs the fetch plan to supply each fetched dependency's guards, which it
  does not yet.
* **Rule 3 — `flint deps add` writing the grant it found — is still absent.**
  `cli/src/depscmd.rs` neither asks for nor writes a grant.

The original probe, kept because it is what the refusal now prevents: a project
holding nothing and writing `:flint/capabilities-grant [:host]` on a dependency
entry — the exact "mint authority from nothing" case — used to be answered by
`flint deps tree` with `left-pad 1.3.0` followed by `note: left-pad --
:flint/capabilities-grant is not a key npm understands, and is ignored`, the
same for a `:local/root` entry. The key was in no kind's known-key set
(`lib/flint/deps.cljc` `coord-notes`), so it neither granted nor refused: it
did nothing at all, silently, in the direction that reads like success.

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

**Status: partly built -- VERIFIED 2026-09-20 by running each target, not by
reading about it. This section was the last one in the file still carrying
"per the record; not independently verified", and it was ALSO contradicting
itself on the same page.**

    claim, as it stood                        checked 2026-09-20
    "nothing in `src/` or `lib/` emits IR"    `src/flint/llvm.cljc`
    "`:to :llvm` refuses"                     emits 2 135 218 bytes of IR
    `"llvm" | "native"` is ONE match arm      two arms, cli/src/main.rs:760-761
    "Native AOT is not built"                 contradicted three lines below
    "`bin/flint` is built on it"              `bin/flint` is a babashka script
    "a 2.1 MB binary"                         4.9 MB

**`:to :llvm` IS BUILT and works end to end.** `bin/check-llvm` exits 0 today:
seven programs at two optimisation levels each, IR emitted, linked by `clang`
against `nativeabi/`, and every answer matched against the interpreter's. A
single compile writes 2 135 218 bytes beginning `; flint program, as LLVM IR`.

**`:to :native` is the one that refuses, and it refuses for its own reason.**
`cli/src/main.rs` has separate arms: `"llvm"` dispatches to `compile_llvm`,
`"native"` bails with a message about linking that points the reader at
`:to :llvm` and at `nativeabi/`, and an unrecognised target names the two real
ones. The "one arm for two targets" this section complained about was fixed
with the emitter and the complaint was never retired.

**`bin/flint` IS NOT BUILT ON THE NATIVE TARGET**, and that is the most
misleading line here rather than the most wrong. They are two separate front
doors: `bin/flint` is a 1 172-line babashka script that `require`s
`flint.compiler` out of `src/` and `lib/`, and `target/release/flint` is the
Rust crate `flint-cli`, which carries an EMBEDDED compiler sandbox and loads
it through `load_compiler`. Neither invokes the other.

**The 15.6 s -> 3.5 s figure could not be reproduced, and the direction did
not hold on the one program measured.** Compiling `slicegap/small` from
`runtimes/conform`: the babashka front end took 3.86 s wall, and the native
binary 4.60 s best of three. That is NOT a like-for-like comparison and is not
offered as a refutation -- the two write different modules, 494 437 bytes
against 621 223 -- only as a reason the recorded figure should not be quoted
until somebody says what it compiled.

**The two front doors compile the SAME PROGRAM**, which was worth checking
before the size difference was read as a divergence: both modules answer
`199990000` and bill 275 937 steps, to the instruction. The 127 KB is
packaging, not semantics. See `one-dependency-walk` for the standing hazard
that every front door builds its own compile spec.

Native AOT is built (2026-09-11, `llvm-ir-target`): `flint compile :to :llvm
:optimize [perf]` emits compiled arities as LLVM functions against the same
helper ABI the wasm emitter uses, and `nativeabi/` is the archive they link
against. The JVM and CLR ports are the rest of this document; see
`jvm-runtime` and `clr-runtime`.

### What stood here, kept because the distinction is still worth having

The paragraphs below were written when `:to :llvm` genuinely did refuse, and
their point -- that "compiled through LLVM" meaning RUSTC is a different claim
from flint emitting IR -- is a real distinction that a reader can still make
the mistake of collapsing. They no longer describe the code.

They are kept for a second reason. A later edit spliced a new status INTO the
middle of them: the sentence "The JVM and CLR ports are the rest of this
document; see" was left dangling with no target, the closing paragraph was
duplicated, and the section ended up carrying two Status banners that
disagreed about whether native AOT was built. *A record that contradicts
itself on one page has usually been edited, not reasoned about* -- and neither
banner was wrong when it was written.

> **"Compiled through LLVM" here means RUSTC, not a flint->LLVM backend.** The
> runtime is Rust, and every Rust binary is compiled through LLVM. This
> sentence reads as though flint emits LLVM IR, and it does not:
> `flint.emitter` is "AST to bytecode", `flint.aot` is "bytecode to wasm", and
> nothing in `src/` or `lib/` emits IR. `:to :llvm` refuses.
>
> The distinction matters because the refusal in `cli/src/main.rs` compounds
> it: `"llvm" | "native"` is ONE match arm for two different targets, refused
> with a reason -- "emitting a native artifact needs a linker" -- that applies
> only to the second. Emitting IR is writing a `.ll` or `.bc` file and needs
> no linker. The real blocker for `:to :llvm` is that no IR emitter exists.

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
→ 223** (this line said 168 until 2026-09-23, which is the second time the
figure has gone stale); `./bin/check-builtins` answers `the native runtime
carries 223 (169 core + 54 in units, 3 host-provided and 3 diagnostics-only),
2 of them emitted by the compiler` / `ok jvm carries all 223, the 2 mandatory
included`. Quoting the command is the durable part; quoting its OUTPUT is what
rots. The port carrying *all* of them is still true — the count moved, not
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
All **223** builtins, **30** conformance programs, several threads sharing
one program, AOT to real IL, and **the flint compiler self-hosting on .NET to
the byte-identical image the native compiler produces**.

*Both counts have now gone stale TWICE.* This line said 155 and nine, was
corrected to 168 and 25 on 2026-09-12, and read 223 and 30 when re-measured on
2026-09-23. The claim that matters -- the port carries ALL of them -- has been
true throughout; it is the number beside it that rots, because nothing keeps a
figure pasted into prose honest. `./bin/check-builtins` and
`ls runtimes/conform/*.cljc` are the durable half of this sentence. Re-run
them rather than reading the numbers here.

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

### `conj`'s dispatch is generated, and a bridge wrapper was removed to let it be, 2026-09-22

Two decisions, taken while porting and recorded here because neither is
obvious from the diff.

**THE DISPATCH IS THE THING WORTH PORTING, not the arms.** `vec-conj`,
`set-conj`, `map-assoc`, `map-conj-map`, `table-conj` and `map-entry-as-vec`
were already kin functions with three targets each. What stayed hand-written
three times was the CHOICE between them -- and the three copies had drifted
into two shapes: native looped over the arguments calling `Rt::conj` per
element, re-reading the collection's type tag each time, while both ports read
the tag once and then looped. `kin/collconj.kin` is that choice, written once,
and all three runtimes now loop per argument over the generated function.

The shapes cost the same: `runtimes/conform/conjgas.cljc` measures 1 200 extra
conj operations at 76 962 gas on native and 76 964 on the jvm, so the extra tag
reads are not billed. This was a convergence, not a bug fix. It removed 63
hand-written lines from `coll.rs`, 87 from `Builtins.java` and 80 from
`Builtins.cs` -- 230 lines, for 97 of kin source.

**NO NEW VOCABULARY, AND THAT IS THE GENERAL RULE.** Every callee is reached by
`:require`/`:refer` over a kin namespace. A vocabulary word emits a call into
HAND-WRITTEN code; when the thing being called is itself a kin function, a word
would be a fourth spelling of something that already exists in three. Reach for
`:refer` first and add a word only for what the runtime alone can do.

**A BRIDGE WRAPPER THAT COLLIDES WITH ITS OWN GENERATED FUNCTION IS REMOVED,
NOT WORKED AROUND.** `Seqs.cons` on the jvm and `Seqs.Cons` on the clr were
one-line delegations to the generated `Seqcore.cons`. Every generated module
imports hand-written `Seqs` AND the kin modules it requires, so the first kin
source to need `cons` -- this one -- would not compile: *"reference to cons is
ambiguous"* on the jvm, `CS0121` on the clr. The wrapper had two call sites per
port. Deleting it and pointing those four at the generated name is smaller than
any workaround, and it is the direction the tree is going anyway.

That generalises: these wrappers were scaffolding for a migration that has
largely happened, and an audit of the tree on 2026-09-22 counted **450 of them
across 957 lines** (rust 32, jvm 209, clr 209, the two ports exact mirrors).
174 of those have the same name as the function they delegate to and could go
with a static import and no call-site edits at all. Removing them is not
urgent; knowing the number is, because `Vec.java`'s own comment defers the job
by citing "53 call sites" and the real figure is 25 on the jvm and 22 on the
clr -- the cost that bought "worth doing LAST" is overstated about twofold.


### The clr canonicalised NaN to different bits, 2026-09-22

**The clr now matches native and the jvm.** `Val.OfDouble` canonicalises a
double whose top bits collide with the tag range, and all three runtimes
derived the replacement from their own host:

    native   CANONICAL_NAN                     0x7FF8000000000000
    jvm      Double.doubleToRawLongBits(NaN)   0x7FF8000000000000
    clr      BitConverter.DoubleToInt64Bits(double.NaN)
                                               0xFFF8000000000000

Measured, not inferred: .NET's `double.NaN` is the NEGATIVE canonical quiet
NaN where Java's and Rust's is positive. So for every double reaching that arm
-- native's comment says "only negative NaNs with a large payload collide with
the tag range" -- the clr produced a different bit pattern from the other two.

**WHY IT MATTERED AND WHY NOTHING SAW IT.** Both answers are NaN, so `=` never
noticed: NaN compares false to everything including itself. What does notice is
anything that reads the BITS -- hashing, snapshots, the wire codec -- where two
values that must be identical across runtimes were not.

And the three runtimes looked consistent while it was true. Each derived the
value from its host's own NaN constant, so the EXPRESSIONS matched and only the
values differed. `bin/check-port-consts` had nothing to compare, because
neither port declared a constant. Naming it is what made the comparison
possible at all -- and the gate flagged the two ports within seconds of the
name existing, before the port had even been written.

**The decision:** name the value on all three rather than derive it from a
host that is entitled to a different one. `CANONICAL_NAN` (jvm) and
`CanonicalNan` (clr) now sit beside native's, and all three are under the
three-way constants gate: 299 constants agree across the runtimes, up from
298.

C# cannot write that literal plainly -- `0x7FF8000000000000UL` is a `ulong`
and the narrowing must be explicit -- so it reads `unchecked((long) ...)`,
which `norm_val` now strips. That is the sixth normalisation the constants
gate has needed and the sixth to come from a real case rather than an
anticipated one.

Verified: `conform-hosts` green at 363 rows, 0 failures, with the clr rebuilt.


### A generated function answered differently on three runtimes, 2026-09-22

**The worst shape this project can produce, and it was live.** `hash-double`
is generated from `kin/hashtext.kin` into all three runtimes. It answered
2 146 959 360 on the jvm and -524 288 on native and the clr, for any NaN
carrying a sign bit or a payload.

Not a hand-written copy that drifted -- those are ordinary, and porting is the
cure. This was ONE SOURCE, generated, which is supposed to make disagreement
impossible. The cause was a word:

    'f64-bits   rust    {0}.to_bits()                     raw
                java    Double.doubleToLongBits({0})      CANONICALISES NaN
                csharp  BitConverter.DoubleToInt64Bits    raw

`doubleToLongBits` collapses every NaN to `0x7FF8000000000000`; the other two
preserve the payload. So the word meant "the bits of this double" in two
languages and "the bits of this double, unless it is a NaN" in the third.

**GENERATING FROM ONE SOURCE GUARANTEES CONSISTENCY ONLY IF THE PRIMITIVES ARE
CONSISTENT**, and nothing was checking that. Every instrument agreed while it
was false: `check-kin` verified the three targets matched their source, the
drivers passed, `conform-hosts` was green. All true. None of them can see a
vocabulary word whose three templates do different things.

**Why no fixture caught it.** `hash-double`'s own docstring says NaN "is not
special here and must not be -- `(= ##NaN ##NaN)` is false, so nothing looks it
up". That is exactly right about LOOKUP and exactly wrong about BITS, which is
what a hash is made of. Every double in the fixture was ordinary.

**The decision:** `f64-bits` emits `doubleToRawLongBits` on the jvm, matching
the other two, and `kin/hashtext.drivers` now passes `0xFFF8000000000000` on
all three targets so the word cannot drift back. Verified: jvm and native both
answer -524 288; `conform-hosts` green at 363 rows, 0 failures.

**What this says about the rest of the vocabulary.** 176 words, each with three
templates written by hand, and the only thing asserting they agree is whichever
drivers file happens to exercise them over whichever inputs its author chose.
That is a real gap, and this is the first instance found in it. A word used by
one source over a narrow range of values is a word whose three spellings have
been compared on that range and nowhere else.

### A fourth NaN, and the constant the earlier fix could not reach

**Found 2026-09-22 while porting `Num.f64` and `Num.cmp` to kin.** The three
runtimes disagreed about what `num_f64` answers when handed something that is
not a number. Each spelled it as its own host's constant, and two of those are
the same number and one is not:

    rust     f64::NAN      7FF8000000000000
    java     Double.NaN    7FF8000000000000
    csharp   double.NaN    FFF8000000000000     <- the sign bit

**Measured on all three hosts, not argued from the specs**, and the
measurement corrected the first guess twice over. `double.NaN` on .NET really
is the negative quiet NaN, at runtime and not merely as a folded constant.
But ARITHMETIC NaN is not the divergence: `0.0/0.0`, `sqrt(-1)` and
`inf-inf` all produce `7FF8000000000000` on all three hosts here. The first
probe said otherwise only because `0.0/0.0` written as a literal is folded by
the C# compiler, which does not use the hardware's answer. The named constant
is the whole of it.

**Why every instrument agreed.** `=` on two NaNs is false whichever bits they
carry, so equality could not see it. The bits escape through `hash-double`,
the snapshot and the wire codec -- a different program from the one that made
them. `bin/check-port-consts` compares the two ports' DECLARED constants, and
`Double.NaN` is the host's, not either port's, so there was nothing to
compare.

**The earlier `CANONICAL_NAN` fix could not reach this**, and the reason is
worth keeping. When the same divergence was found in `Val`, all three runtimes
were given a named `CANONICAL_NAN` and `make-double` (`kin/valtag.kin`) folds
a NaN to it. But it folds only a pattern whose tag is at or above
`TAG_MIN_BOXED`, `0xFFF9`, because that is the range that would collide with a
tagged value. `0xFFF8` is one short of it. The CLR's NaN passes that filter
untouched. A canonicalising constructor is not a guarantee that every NaN in
the system is canonical -- it is a guarantee about the range it was written to
defend.

**The decision:** `num_f64` and `num_cmp` are generated, from `kin/numf64.kin`,
and the not-a-number answer is `f64-of-bits CANONICAL_NAN` -- the same number
in three languages because it stopped being the host's. Native's `num_f64`
(`runtime/src/num.rs:93`) and the clr's `F64` (`runtimes/clr/src/rt/Num.cs:92`)
are delegators onto `number_f64` (`runtime/src/kgen/rt/numf64.rs:32`).

**`Option<i64>` was the stated blocker and was not one.** All three files
carried the same note: `asI64` answers "the integer, or nothing", a nullable
type kin has not got, so `f64` and `cmp` stay hand-written. True about
`as_i64`, which keeps its thirty-odd other callers. False about these two --
they used the nullability only to ASK whether a value is an integer, and
`is-int` answers that as a predicate. `numkind.kin` had already recorded the
same substitution for `both-ints`. A blocker attached to a shared helper had
been read as a blocker on every caller of it.

**`kin/numf64.drivers` pins it** with the not-a-number case printed as RAW
bits, since NaN is not equal to itself and a probe comparing doubles would
report agreement whatever each runtime produced. The jvm probe uses
`doubleToRawLongBits` for the same reason its non-raw sibling caused the
`f64-bits` divergence above. Two more cases earn their place: `2^53+1`
against `2^53` must compare as 1 and -1, though both convert to the same
double, so a `num_cmp` that promoted first would call two integers that
differ by one equal; and a mixed integer/double pair must NOT take the
integer path.

**A second finding, from the same change.** Rust's sibling imports in
`targets.cljc` were a HAND-KEPT list of five module names, and the comment
beside it already said that was a trap -- a new free-function source compiles
on both ports and fails only on Rust, with "cannot find function" at the
caller, and `hamt` had cost exactly that. `valtag` then cost it again. The
comment did not prevent the second instance, because a paragraph asking
someone to remember a list is not a mechanism that remembers it.
`siblings-used` (`kin/src/flint/impl/targets.cljc:220`) had been deriving the
same thing for Java and C# two hundred lines up. Rust now uses it: 115
generated files, 552 import lines removed and 342 added.

**AND THE SAME BUG WAS ALSO IN THE VOCABULARY, one file away.** Sweeping for
other host NaN constants after fixing `num_f64` found `F64_NAN`, whose three
templates were `f64::NAN`, `Double.NaN` and `double.NaN` -- and it is what
`kin/strnum.kin` returns for the `##NaN` READER LITERAL. So the divergence was
one character of ordinary flint source away from any program, not buried
behind an unguarded internal call. It now reinterprets `CANONICAL_NAN` on all
three.

**`kin/strnum.drivers` already parsed `##NaN` and could never have seen it.**
Its printer answered `dNaN` for any NaN, because that is how you print a
double without depending on the host's formatter -- and a label agrees no
matter which bits arrived. It prints `NaN:%016X` now. Proven by reverting the
C# template alone: the probe then reports `dNaN:FFF8000000000000` against
`dNaN:7FF8000000000000` on the other two and FAILS, which is also the
end-to-end confirmation that the reader really did produce different bits on
the CLR.

**A bounded survey, so this is not left as "there may be more".** The
vocabulary has 158 constants with three literal templates. All but four
resolve to a constant one of the runtimes DECLARES -- those are what
`bin/check-port-consts` already compares. The four the hosts supply are
`I64_MIN`, `I64_MAX`, `F64_INF` and `F64_NAN`. The first three cannot differ:
two's complement fixes the integer bounds and +infinity has one
representation. `F64_NAN` is the only host-supplied constant in the
vocabulary whose value was free to vary between hosts, and it was the one
that varied.

**A third, found by the fix tripping a gate.** `check-names` refused the new
`F64_NAN` template: "no file under runtimes/jvm/src/com/flint/rt defines
`CANONICAL_NAN)`" -- with the closing paren. Its `ident` took the last
`.`/`:`-separated chunk, which is the whole of a dotted path and not the whole
of an EXPRESSION, and `F64_NAN` is the first name template that had to be one.
It now takes the last identifier. The failure was worth reading rather than
working around: it named a real constant and a real tree and said nothing
defined it, and every word was true of the string it had built. Proven still
sharp by planting `NO_SUCH_CONSTANT` inside the same call shape, which it
catches.

### What is left to port, and why each one is blocked

**Surveyed 2026-09-22, after `Num` finished.** The remaining hand-written mass
in the runtime is `Obj`, `Wire` and `Gc`, and none of the three is blocked for
the reason "nobody has got to it yet". Each was probed rather than assumed, so
the next session can start from a verified blocker instead of re-deriving one.

**`Obj` -- kin's memory vocabulary is ROOTED AT `Rt`, and `Obj` is rooted at
`Space`.** Twenty-one hand-written functions, almost all bit-packing over the
object header, and the bit positions AGREE across all three runtimes -- every
shift and mask, fifteen functions compared -- so there is no divergence to
fix, only three copies to collapse. What stops it is a type: `read-u64`
(`kin/src/flint/impl/rt.cljc:1912`) emits `{0}.gc.sp.read_u64({1})`, so a
generated accessor must take an `Rt` and reach the space through it, while
`ty` (`runtime/src/obj.rs:283`) takes `&Space` directly.

That signature is LOAD-BEARING and not an accident of style. The collector
calls these as `ty(&self.sp, a)` from `&mut self` methods of `Gc`, which is a
field of `Rt` -- borrowing the whole `Rt` there would not compile.

**DONE for the readers, and the estimate above was wrong.** "Real work on the
type machinery" was a name and three type strings -- `{:name 'Space :types
{:rust "&Space" :java "Space" :csharp "Space"}}` -- plus one vocabulary word
and one line of the Rust preamble. Rust taking it by SHARED reference is not a
compromise: `Space`'s writers are `&self` too, because the space is an arena
whose interior mutability lives below this level, so one tag serves both
directions. `kin/objhdr.kin` generates `obj-ty`, `obj-len`, `obj-age`,
`obj-marked`, `obj-in-remset` and `obj-str-ascii`, and all three runtimes
delegate.

The lesson is about the estimate rather than the work: a blocker that is real
says nothing about what clearing it costs, and this one was recorded as
expensive without being priced.

**`Obj` IS DONE**, at 21 delegating against 2 hand-written: its private
constructor, and `slot`.

`slot` cannot go, and that is a finding rather than a gap. Native guards it
with a `debug_assertions` check that a forwarded pointer is never read outside
the collector -- reading one elsewhere means the edge INTO that object was
never traced, so the collector moved the target and nothing updated the slot.
kin has no way to spell a debug-only assertion and neither port carries the
check at all, so porting it would silently drop an assertion that exists to
catch a class of collector bug. The asymmetry runs into the vocabulary: it
gained `sp-write-u64` and NOT `sp-read-u64`, because the write is portable and
the read is the one with the guard on it.

Three vocabulary words were needed along the way and each earned itself
immediately: `bit-not`, because clearing a field in place is `w & ~(7 << 21)`
and Rust spells the complement `!` where both ports say `~`; `addr-of-u32`,
which zero-extends where `to-addr` would sign-extend on both ports; and
`to-ty`, a real cast on Rust and nothing on the other two. A fourth,
`sp-read-u64`, was written speculatively and refused by
`bin/check-vocab-used` for having no caller.

**`Wire` -- the three runtimes FACTOR THE WRITER DIFFERENTLY**, so there is no
single shape to generate. Native writes a tag and its payload in one call,
`wire_piece` (`runtime/src/codec.rs:79`), with 19 call sites. Both ports have
no equivalent and instead compose `Wire.put(tag)` with a payload writer --
`put` has 10 call sites on the jvm, `u64` 3, `text` 4. Eleven of `Wire`'s
methods are already generated and every hand-written one is `Rt`-rooted, so
the usual blockers do not apply: what has to happen first is a DECISION about
which factoring wins, and then the other two runtimes' builtins change to
match. That is a refactor with no known bug behind it, which is why it is
recorded rather than done.

The asymmetry left three superseded helpers behind on native --
`wire_u64`, `wire_text` and `wire_scan_ports` are named in no Rust source but
their own definitions, while the ports' `u64` and `text` are live. `bin/dead-runtime-fns`
reports them among its 25.

**`Gc` -- priced, and carrying a divergence.** Instance methods on a `Gc`
that owns the space, with `minor` at 42 lines and `major` at 31. The `Space`
tag `Obj` introduced is not enough on its own; the field predicates need,
exactly:

* a `Gc` tag, `{:rust "&Gc" :java "Gc" :csharp "Gc"}`, the same shape as
  `Space`;
* five field words -- `young-base`, `half`, `from`, `bump`, `old-live`. The
  names line up across all three runtimes, which is the part that could have
  been ugly and is not;
* an UNSIGNED 64-BIT COMPARISON, which the vocabulary has not got. `<` is
  unsigned-aware at 32 bits only -- `Integer.compareUnsigned` on the jvm --
  and these predicates are built on the wrap trick, `(addr - from) <u (bump -
  from)`, which needs the 64-bit form.

Pricing it rather than calling it "not a small port" is the lesson from `Obj`
applied: that entry called a blocker expensive without measuring and was
wrong by an order of magnitude.

**AND `would-collect` ALREADY DIVERGES.** Native saturates where both ports
do not:

    native  size >= LARGE_OBJECT || bump.saturating_add(size)
                                      > from.saturating_add(half)
    ports   size >= LARGE_OBJECT || bump + size > from + half

All three are live, called from the same place in `Rt`. Overflow is
unreachable -- the heap is bounded far below 2^63 -- so this is a divergence
in the DEFINITION rather than a live defect, the same shape as `Value::heap`
masking on two runtimes and not the third. It matters more than most because
of what the predicate is for: `would_collect`
(`runtime/src/gc.rs:956`) is asked before allocating so a collection can be
STAGED across executors, and its own comment says a false yes costs a
needless safepoint while a false no "would let the collector move objects
while another thread was running". Plain addition that wrapped would answer
false -- the unsafe direction.

**FIXED by porting it**, which is why it was not hand-patched into the two
ports: three hand-written copies agreeing is not one source, and the patch
would have been deleted by the port that fixed it properly.

The saturation WENT rather than spreading, on a reachability argument.
`max_heap` is built from a `u32`, so every operand is at most 4 GiB and the
sum at most about 2^33 -- nowhere near where a 64-bit add wraps. Plain
addition IS the saturating answer over that range. The condition is recorded
in both `kin/objsize.kin` and native's comment: if the heap limit stops being
a `u32`, that is the line that changes with it.

The alternative was a `sat-add` vocabulary word, and it would have been worse
than the problem. Neither port has a saturating add, so the template would
have read `((a + b) < 0 ? MAX : (a + b))` -- an argument evaluated twice, in a
vocabulary word, guarding a case that cannot happen.

**The rest of `Gc` still stands as priced above**, minus the two pieces now
done: the five field predicates and `would-collect`. What remains is the
collector proper -- `forward` at 40 lines, `minor` at 42, `major` at 31,
`scan-object` at 29 -- and it is BLOCKED, for a reason worth stating as a
general rule rather than per function.

**A `cfg` ON a function is portable; a `cfg` INSIDE its body is not.** When
the whole function is conditional, the generated body is unconditional and
native's delegator keeps the attribute -- which is exactly how `would-collect`
was ported while carrying `#[cfg(feature = "parallel")]`. When the
CONDITIONAL PART IS INSIDE, the body differs between builds, kin has no
conditional compilation, and generating it would silently drop whichever
build's version lost.

That is what blocks the collector, and it is not incidental: 22 of `gc.rs`'s
80 functions carry native-only instrumentation, concentrated in exactly the
core -- `minor` 8 blocks, `forward` 4, `major` 4, `collect-cycle` 2. `forward`
alone tracks `limbo_refs`, stamps `limbo_bad`, and asserts
`plausible_from_object`, none of which either port has. It is the same shape
that keeps `Obj::slot` hand-written, where a `debug_assertions` block checks
that a forwarded pointer is never read outside the collector.

So the rule sorts the runtime cleanly: predicates and arithmetic over fields
generate, and the instrumented core does not. `Gc` is finished as a porting
target at the five field predicates and `would-collect` unless someone decides
the diagnostics should move too, which is a question about the diagnostics and
not about kin.

**`class-of` was assessed and DECLINED**, which is worth recording so it is
not re-assessed. It is four lines -- `size / 8`, clamped to `NCLASS - 1` --
and all three runtimes already agree character for character. Porting it
would cost a third visibility widening, since native's `NCLASS` is private to
`gc.rs`, plus a name-table entry and a `usize`/`int` mismatch at the wrapper.
That is friction bought for no divergence risk. The pieces worth porting are
the ones where the three copies can drift or already have.

**AND `Snap` WAS CHECKED AND IS FINE**, also recorded so the suspicion is not
re-run. It looked like 1316 lines of unreferenced hand-written code across
the two ports -- native reaches its `snap.rs` through the `flint-snap` wasm
unit, which the ports have no equivalent of -- and it is not: the callers are
in `runtimes/jvm/test/` and `runtimes/clr/conform/`, which a search of
`runtimes/*/src/` does not see. Capture/restore round-trips, both refusal
cases and export/import are all covered per runtime.

**CORRECTION, 2026-09-23.** The paragraph here said cross-runtime snapshot
interop is NOT a goal and therefore not a claim anything checks. Both halves
were wrong, and `bin/conform-hosts` says so on two rows: native READS THE LIVE
SNAPSHOT THE JVM WROTE, and the one the clr wrote, asserting `matches=true`
rather than merely that the import was accepted -- a distinction the harness
learned the hard way, because checking `accepted` once passed a real snapshot
with a byte flipped in the middle of it.

How I got it wrong is the part worth keeping. I read this section, found its
byte-identical claim describes a round trip WITHIN one runtime, found no
cross-runtime assertion in the text, and concluded none existed anywhere. The
assertion was in the harness, not the prose, and I never ran the harness --
I was looking for a gap and stopped at the first place that could have held
one. `export-live` is relocatable BY DESIGN, and these rows are what holds the
three encoders to it.

**`Val`'s last four are NOT blocked on a byte-array type**, which is what
their comments have said and what I set out to fix by adding one. kin has had
a `Bytes` tag all along -- `&[u8]` / `byte[]` / `byte[]` -- so the missing
piece was supposed to be a MUTABLE one. It is not. The signatures differ:

    native  fn inline_bytes(self, buf: &mut [u8; INLINE_MAX]) -> &[u8]
    ports   byte[] inlineBytes(long v)

Native takes a caller-provided buffer, fills its first `n` bytes and returns a
BORROWED SLICE of it; both ports ALLOCATE a fresh array and return it. That is
a deliberate difference in allocation discipline -- native is `no_std` on wasm
and avoids the allocation, the ports are on collected hosts and do not care --
and it is a difference in ARITY and lifetime, not in body. No tag fixes it,
because the ports' version does not take a buffer at all. Generating one
signature would mean choosing which runtime's memory contract wins.

The same shape blocks `Str`'s remaining surface, which is why `string-hash`
and `keyword-hash` cannot be ported either: native reaches `inline_bytes`
through a stack buffer. So this is one blocker wearing three names, and it is
a design decision rather than a gap in kin.

**`Interns` is unchanged** and still blocked on a callback type, as its own
comment says.

### Where the dead-code sweep stops, and why native is different

69 unreferenced members were removed across the three runtimes. 10 remain on
native, 7 on the jvm, 9 on the clr, and the native ten stop for a reason the
ports' did not.

**The ports carry no packaging metadata.** No `pom.xml`, no gradle, and
`Flint.csproj` has no `PackageId`, `IsPackable` or `GeneratePackageOnBuild`.
Their only consumers are the generated tree and the in-repo tests, both of
which `bin/dead-runtime-fns` indexes. So `public` there means "reachable from
`com._3sln.flint.kgen.rt`", and a public member the generated tree does not
reach is dead with no surface to protect.

**`flint-rt` is publishable.** `runtime/Cargo.toml` carries a crates.io
`description` and no `publish = false`. `pub fn var_exists` on `Program` --
the native entry point that mirrors `abi.rs` -- may therefore be API for an
embedder outside this repository, and reachability inside it cannot tell.
That is a question about intended surface, and it is not one a count answers.

So the native remainder is left, named rather than swept:

* `var_exists`, `collect_all`, `set_from_map`, `throw_value` -- thin `pub`
  wrappers over `major`, `new_set`, a field write and `var_named`;
* `from_now`, `bump_now`, `to_now`, `half_now` -- `cfg(diagnostics)`
  accessors for `pub(crate)` collector fields, so their whole purpose is to
  let something OUTSIDE the crate read collector state. Eight other
  `cfg(diagnostics)` functions do have callers, so the surface is used; these
  four are the ones no consumer has wanted;
* `forget_fixed`, `u32s` -- the last two, unexamined.

**rustc already covers the other half.** Its `dead_code` lint reports 18
PRIVATE items in the runtime and says nothing about `pub` ones, which is
exactly the gap this tool fills -- and exactly why the native half of its list
is the half without a compiler behind it.

**A measurement caveat, recorded because it has now misled me three times.**
Matching members across runtimes by NAME undercounts: `probe` is
`intern_probe` on native, `publish` is `intern_publish`, `contiguous` is
`contiguous_string`, `of` is `string`. Any count of "how much of this class
has a native twin" built on snake-casing the port's name is a floor, not an
answer.







## cross-runtime-benchmarks

**Benchmark across wasm runtimes, because every number so far was V8**
*(formerly `cross-runtime-benchmarks`)*

**Ratified:** ☐ not signed off

**Status: the work was done; the harness is BROKEN and none of the ns/instruction figures below can be reproduced today. Checked 2026-09-12 at 639430e; RE-RUN AND RE-DIAGNOSED 2026-09-19 — still broken, but not for the reasons recorded below.**

**The banner is TRUE and was re-checked rather than repeated.** `./bin/bench-xruntime`
today: exit 0, every engine row `FAILED`, the ns/instruction table empty, the
resident-memory table printed. Exactly as described.

**But both named breakages have moved, and one of them is already fixed.**

* *Breakage (2), the hard-coded entry*, is FIXED IN THE TREE. `bench/xrt-run.mjs`
  derives `construe.bench.xrt<N>/main` from the module filename and says so in
  its own comment — "this is that one fixed". Nothing updated this record, so it
  still sends a reader to repair something already repaired.
* *The live blocker is a THIRD breakage nobody wrote down*: `flint_call` is no
  longer an export. `node bench/xrt-run.mjs out/xrt-0.wasm construe.bench.xrt0/main`
  now fails with `TypeError: e.flint_call is not a function`. The module's
  exports are the PORT PROTOCOL — `flint_system_port`, `flint_deliver`,
  `flint_resume`, `flint_drain`, `flint_continue` — because a call is a message
  on the system port (`calls-are-ports`), and the single-shot entry the
  benchmark driver was built on went with it.

**THIS WANTS A DECISION, NOT A PATCH, and that is the finding.** `xrt-run.mjs`
is deliberately "nothing but a `WebAssembly.Instance` — the JS engines'
equivalent of `wasmtime --invoke main`", and the table's claim rests on that:
the SLOPE is comparable because every engine does the SAME minimal thing. Two
things follow, and they point the same way:

* the wasmtime and wasm3 rows cannot be rescued in the driver at all. There is
  no `main` to `--invoke` and a bare invoker cannot drive a bind/call/pump
  protocol;
* giving node, bun, deno and SpiderMonkey an SDK-style pump WOULD work, and
  would make them do strictly more work than the two standalone engines. The
  table would still print six rows and would no longer be comparing the same
  thing.

A partial fix here is the exact failure this record already caught itself in
once: *"The control was measuring the wrong thing, so agreement proved
nothing."* Six plausible rows that are not comparable is worse than an empty
table, because an empty table is obviously empty.

So the choice is between **exporting a benchmark shim** — one `main`-like
export that runs a named function, so every engine can do the same minimal
thing again, at the cost of an export in every module and a deliberate
exception to "nothing is called automatically" (`structured-ports` step 5) —
and **narrowing the table to the JS engines** and saying in the header that
wasmtime and wasm3 are no longer measurable this way. Not taken here: the
first spends module budget on a benchmark and the second gives up the
comparison this decision exists for.

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

**THE SHAPE THIS BAR ASKS FOR IS DISAVOWED, 2026-09-24, and the artefact
demonstrating it has not run since 2026-09-13.** Both halves matter and neither
was written down.

*The ruling.* A sandbox is ONE program. A host that runs many programs creates
many sandboxes; it does not share one by loading images into it. So "a resident
loader instantiated ONCE per isolate, an image loaded per request" -- which
`bench/workerd/worker.js` opens by calling "the shape construe actually runs" --
is not the intended model.

*And that file cannot have run for eleven days.* Its `wireCall` ends in
`e.flint_call(p, b.length)`. `calls-are-ports` deleted that export on
2026-09-13 (`e6d55a31`); the file was last touched 2026-09-11 (`8cee498d`).
Nothing in `bin/test` drives it and there is no workerd on the development
machine, so the one item this bar calls "binary, not a matter of degree" has
been demonstrated by dead code. The note above already said the figures could
not be re-run; it did not say the harness was broken.

*What this does NOT settle.* `flint_load_image` supports REPLACING the image in
a live sandbox -- `abi.rs` clears frames, handlers, the started flag, the stack
top and `thrown` for exactly that, and carries a bug history for it ("a swapped
image found the flag already set, never bound its vars, and answered
`two/main` is not a function"). Every working consumer loads exactly ONE image
per instance: `host/run.mjs`, `bench/xrt-image.mjs`, `bench/xtail.mjs`, one call
each. Only `test/loader.clj` loads a second, to assert that replacement works.
So nothing working depends on replacement, and removing it is cheap -- but this
bar names the resident-loader shape as a CUSTOMER requirement, and "no code
depends on it" is not "no customer depends on it". That is the open question,
and it is not the runtime's to answer.

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

* **Fetching, per above.**

### Done 2026-09-16: both CLIs take a script

**`flint compile <file>` builds a module from a script.** It was plumbing, as
recorded -- the entry and the source paths are one computation, which is now
`script_spec` in `cli/src/main.rs` and is used by `run` and `compile` both, so
a change to what a script MEANS cannot reach one command and miss the other.

Two things the doing settled that the note did not anticipate:

* **The script has to come off the front before the options are parsed.**
  `parse` treats a bare word as a source path, so leaving it in made the
  script look like a `:src` and the guard fired on the command that was
  correct.
* **A script's `:capabilities` becomes METADATA, not a grant.** `compile`
  already treats `:with` that way -- the arguments arrive later, so what a
  program needs has to survive until then -- and a script declaring its needs
  in the file is the same statement made in a different place. Consent stays
  in `run`, where somebody is present to give it.

**`bin/flint` takes one too, and the note UNDERSTATED it.** "The front end
only" was wrong: this CLI's `:src` resolved a namespace to `<dir>/<ns>.<ext>`,
so it could not take a single FILE at all, script or not. `source-candidates`
now answers for a file entry by the namespace it is named after -- the same
rule a directory follows -- and `bin/flint <file>` fills in the source and the
entry from the file's own `^:script` mark.

The mark is read with flint's own reader rather than by matching text, because
`^:script` is metadata: a regex gets `^{:script go}` wrong, and that is
precisely the form naming an entry other than `main`.

Verified end to end on all three doors: `flint <file>` runs, `flint compile
<file>` and `bin/flint <file>` both build a module that runs and prints the
same answer, an `ns` without `^:script` is refused by name on both, and an
ordinary `:src <dir>` project build is unchanged.

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

* ~~**Ordering.**~~ **Settled 2026-09-22 -- and it was settled in this
  section's own body, two hundred lines above where this item sat.** The item
  said ordering was "recorded as later-overrides-earlier above". Nothing above
  records that. What the body says is that a name excluded from an earlier
  entry is simply not in the prelude, so **the collision never forms**; that
  one which DOES form is **an error, not an order-resolved silent win**; and
  therefore that **order in the list is load order, and only that**.
  `flint.analyzer/prelude-resolve` implements exactly that -- two entries
  offering one name THROWS, naming both and the `:exclude` that settles it --
  and `test/sysns.clj` pins it with "two entries offering one name is refused"
  and "the refusal names the exclusion that settles it". Both pass at
  `a54c5269`.

  Left on the sign-off list this was worse than stale. A reader who went
  looking for what still needed deciding would have found an ordering
  semantics to choose, and implementing either answer removes a refusal the
  rest of the section argues for at length.
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

**The regex engine was written three times because no tag could say whether an
array is shared or copied. One can now, and the engine is generated**

**Ratified:** ☐ not signed off

**Status: DONE, pending your sign-off.** All four -- `class-hit`, `consumes`,
`add-thread` and `run-over` -- are generated into the three runtimes from
`kin/pike.kin` and called by all three. The `Thread` struct is gone from all
three with them, and the per-thread slot allocation it existed to own went
with it: a thread is a flat row in an arena the caller lays out.

Verified beyond the drivers. `bb test/regex_pike.clj` passes whole -- nineteen
patterns against babashka, the one documented divergence still exactly where
it is documented, the simulator matching the `.cljc` reference span for span,
400 rope pieces without materialising, and `(a+)+$` not squaring -- and one
ten-pattern program covering alternation, captures, anchors, word boundaries,
counted repetition and a subject containing a newline answers identically on
native, the JVM and the CLR.

**What the port cost, and it is not nothing**: an aliasing axis in kin, and
one shipped bug found (below) that had been answering nil for every match
after a newline. Recorded 2026-09-11 as a finding with nothing built; the blocker it
named was retired 2026-09-16 by an ALIASING AXIS in kin -- `^:shared` and
`^:copied` on a binding, with the tag supplying the per-target rendering. The
original reasoning is kept below and marked where it was wrong, because half
of it was right and the wrong half is instructive: the diagnosis was correct
and the conclusion "adding a tag is a change to another library" was an
ownership objection wearing a technical one.

The census below was re-derived from the tree, not quoted from
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

### Why it was not generated, and what the answer turned out to be

**SUPERSEDED, 2026-09-16. The blocker is gone and the remedy proposed below is
the wrong one.** What follows is kept because the reasoning is instructive and
because half of it was right.

kin renders `^:mut` on a parameter as Rust's `mut x: T`, a by-value rebinding —
not `&mut T`. So a tag whose Rust type is `Vec<i32>` and whose Java type is
`int[]` means **pass by move on one target and pass by reference on the other
two**: a callee that writes into it would be seen by the caller in Java and C#
and not in Rust. That diagnosis was correct, and it is worse than it reads:
every target COMPILES, so nothing reports it.

What was wrong was the conclusion — "there is no tag that spells a shared
mutable array in all three, and adding one is a change to `kin.lang`, which is
a separate library." That is an OWNERSHIP objection wearing a technical one.
kin now carries an **aliasing axis**: `^:shared` and `^:copied` on a binding,
with the TAG supplying the per-target rendering, because what a shared
`U32s` looks like is a target's own business — `&[u32]` in Rust, `int[]`
everywhere else. A tag that declares the question makes answering compulsory;
a tag that cannot answer refuses the mark by name and target. Merged into kin
as `A binding can say who owns the storage`, with `test/aliasing.clj`.

**`^:mut` could never have been the answer, and the similar word is the trap.**
`^:mut` says a binding is REASSIGNED — a question about the name. Aliasing is a
question about the storage. `^:mut` on a RECEIVER does render `&mut self`, but
that is the receiver position special-cased in the emitter, not a mechanism
that could reach a parameter.

**AND THE `Cps` REMEDY BELOW IS THE WORSE OPTION.** It was proposed to work
around a limitation that, for these four functions, is not load-bearing:
`run_over` takes no `&mut Rt` at all, and owns `clist`, `nlist` and `seen` as
call-locals. Rust's aliasing rules never come into play, so a plain
`&mut Vec<i32>` local is expressible — and indexes as an indexed load, where
`cps_at` is a bounds-checked double indirection in the inner loop. The section
below flags that cost itself and says it is "the measurement to take before
committing". There is no longer anything to measure: the cheap shape works.

What the `Cps` plan got right and still applies is the FLATTENING — `pc` then
`nslots` values per thread rather than a `Vec<Thread>` — because kin has no
user structs. It can flatten into a call-local buffer instead of a
runtime-owned one, keeping the cheap indexing.

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

### A SHIPPED BUG THE PORT FOUND: no match after a newline, ever

Found 2026-09-16 while porting `consumes`, by running the generated function's
own behaviour past the driver and against the other two runtimes.

    (re-find #"b"   "a\nb")      => nil
    (re-find #"abd" "a\nc abd")  => nil
    (re-find #"a"   "\na")       => nil

**Every pattern, on every subject containing a newline, for anything after the
newline.** All three runtimes agreed, which is why nothing caught it: the
conformance suite compares the runtimes against each other, and they were
wrong together. `\t`, `\r`, `\\` and ordinary characters were all fine.

The cause is one line in `lib/flint/nfa.cljc`. An unanchored search is compiled
as a `.*?` prefix in front of the anchored program, and the prefix stepped
forward with `OP-ANY`:

    (emit! st OP-SPLIT ENTRY-ANCHORED 1)
    (emit! st OP-ANY 0 0)          ;; <- the search walking forward
    (emit! st OP-JMP 0 0)

and `OP-ANY` is the USER'S `.`, which excludes a newline because Java's does
without DOTALL. That reading is right for `.` and wrong for the search: a walk
that refuses to step over a newline cannot reach anything past one.

**Two meanings, one opcode.** `OP-ANYNL` (11) now carries the second: any code
point INCLUDING a newline, emitted only by the search prefix. `.` is unchanged
-- `(re-find #"a.c" "a\nc")` is still nil, which is the Java behaviour this
engine deliberately adopted -- and `(re-find #"a.c" "a\nc abc")` now answers
`abc`, which it could not before.

**The fix landed in ONE place because `consumes` is generated.** Had it been
found a week earlier it would have been three edits in three languages with
nothing comparing them, which is the argument for the port stated as a cost
rather than a principle.

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
targets agreed on. **EVERY kin source carries one** -- 119 of them as of 2026-09-23,
where this line said 90 until then -- and `bin/check-kin`
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

**Status: the reasoning HOLDS; the counts in it drift every time anybody
looks. Verified 2026-09-22 by listing the files, not by re-reading the
record.** The one that
moved did move: `runtimes/jvm/test/RtHash.java` is gone and `kin/hashtext.kin`
with its `.drivers` is in the tree. The thirteen that did not are still
hand-written, for the reason given below.

What has drifted, and it is the direction that matters: there are now
**fourteen** `main()` programs in `runtimes/jvm/test/` and fourteen `--rt-*`
flags in `Program.cs`, not thirteen. Tests have been ADDED since this was
written, so the duplication this section is about has grown rather than shrunk
while the section sat still. The line counts moved with them -- 1 598 to 1 909
on the jvm side, 1 442 to 1 569 on the clr -- and part of that last figure is
mine, from mirroring `HostCall` into the clr on 2026-09-18.

**And again by 2026-09-22, three days later: fifteen and fifteen**, 1 909 to
**2 016** on the jvm side and 1 569 to **1 602** on the clr. The fifteenth is
`runtimes/jvm/test/RtRooting.java`, added by `da10c833` -- mine as well.

Three measurements, three different numbers, every one of them larger:
thirteen, fourteen, fifteen. That is worth more than any of the individual
figures. This section argues the remaining duplication is not worth porting,
and the argument may well be right -- but it is being made about a quantity
that grows whenever the ports are worked on, which is often, and the growth
comes from the people who read this section and agree with it. A count in
prose is a snapshot; what is actually true here is a rate. Re-measure rather
than trusting any of the three figures above; it is four commands:

    grep -l 'static void main' runtimes/jvm/test/*.java | wc -l
    grep -o '\-\-rt-[a-zA-Z-]*' runtimes/clr/conform/Program.cs | sort -u | wc -l
    cat runtimes/jvm/test/*.java | wc -l
    cat runtimes/clr/conform/*.cs  | wc -l

`ls` is the wrong first command and gives sixteen: `HostCall.java` is a shared
helper, not a program, and it is the one this record already mentions
mirroring into the clr. Count `main()`, not files.

This section previously had NO status line, which the triage near the top of
this file names as the one item whose claim could not be assessed at all. Seven
others have since joined it; see that triage, which is itself corrected.

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

**Status: BUILT -- verified 2026-09-19 in the handler table, not by looking
for a file.** `flint.sys.wasm` is served at `cli/src/sys.rs:653`, alongside
`flint.sys.fs`, `flint.sys.env` and `flint.sys.slurp`.

Worth saying how this was nearly got wrong, because the next person will reach
the same way I did: there is no `lib/flint/sys/wasm.cljc`, and its absence
proves nothing. `flint.sys.*` namespaces are VIRTUAL -- no source by design,
spoken to over a port (`flint.virtual`) -- so "does the file exist" is the
wrong question and answers it in the wrong direction. The handler table is
where a served namespace lives.

Not re-checked here: this section's account of what `bin/flint` and
`flint.cli/run` did BEFORE. `bin/flint` still mentions `node` and
`lib/flint/cli.cljc` still returns `{:exec {...}}`, and whether those are the
same occurrences this section describes as removed was not established.

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

## flint-ception

**Ratified:** ☐ not signed off

**Status: BUILT -- verified 2026-09-19 in `cli/src/main.rs`.** `flint.ception`
is served there (`main.rs:743`, "serves `compile` to a PROGRAM"), and the
`:flint/nested` gate this section describes is real: `main.rs:378` makes
`(:require [flint.ception])` a compile error when the feature is off, and
`main.rs:630` turns the resulting "missing namespace" into a message that says
the build turned it off rather than that it does not exist.

Same caution as `wasm-engine`: there is no `lib/flint/ception.cljc` and there
is not meant to be.

Recorded 2026-09-12, renamed 2026-09-13. `flint.ception` is a served namespace
giving flint code the compiler: compile a program, construct a sandbox from it,
call a function in it.

### Why not `flint.sdk`

Because `sdk` collides in this repo. `sdks/c`, `sdks/rust` and `sdks/esm` are
host-side kits for embedding flint FROM another language; this points the other
way — flint hosting flint. `flint.sdk` reads as "the SDK" when it is the
opposite direction, which is the one-name-two-concepts shape that produced the
`:checks` divergence and the duplicated dependency tables.

The cost is discoverability: nobody hunting "how do I compile from flint" greps
`ception`. That is paid off by the docstring and this record carrying the
search terms, and the name is unambiguous once seen.

### What it replaces

`flint.cli/run` returns `{:exec {:src ... :entry ... :paths ...}}` for
`flint task`: not an answer, but a job handed back for the host to do. Each of
the three front ends then does it its own way — `bin/flint` writes the source
to a temp directory and shells out to `bb bin/flint` to compile it, then to
`node host/flint.mjs` to run it, two processes deep.

That is the hand-back the user's question was about: *why should the CLI tell
the host to compile and run something, when the compiler can be imported into
the CLI build?* It should not. With `flint.ception` it does not have to, and with
`flint.sys.wasm` (`DECISIONS.md#wasm-engine`) the running half is covered too.

### The shape is the other SDKs' shape

`sdks/rust` is the reference: `Compiler::compile(Compile { resolve, fn_name,
exports, .. }) -> Image`, then `Image::sandbox() -> Sandbox`, then
`Sandbox::call(name, args)`. `flint.ception` is the same four steps —
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
`(:require [flint.ception])` is a COMPILE error and the artifact cannot reach the
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

    no source for flint.ception
    `flint.ception` is not missing -- this build turned it off. `:features` was
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
bounded at every link, because a child's own `flint.ception` is constructed with
the child's capabilities.

`test/sysns.clj` carries the attack and a control that differs in exactly one
thing — the same caller, granted `fs` as well, must still succeed. Without the
control, a refusal for any unrelated reason would read as the check working.

### A call is a port send and a park, and the outer drives

`call` goes over the sandbox's SYSTEM PORT, not through `Program::call`.

That distinction is the whole of this. `Program::call` is `call_on` is
`rt.call_named`: it runs to completion and has no park or resume, which is the
`flint_call` ABI — `sdks/esm` calls it "the no-port call: synchronous, no
scheduler involved". A function that opens a port cannot be called through it
at all, so a sandbox built this way could reach nothing: `this sandbox was
given no system port, so it cannot ask for "flint.sys.env"`.

**The runtime already implemented the other path.** `runtime/src/conc.rs`'s
`system_message` takes

    ->  {:tx n :op :call :fn "ns/name" :args [..]}
    <-  {:tx n :op :return :value v}
    <-  {:tx n :op :throw  :kind ".." :message ".."}

and says why it is a message rather than a function the host calls straight
through: **a call runs as a GREEN THREAD**, so "the called function may open a
port and park, and the host has to be able to answer that while the call is
still outstanding — a call on the host's stack could not park at all."

Only the native DRIVER was missing. `Host::call_named` installs the system
port, delivers the request and pumps: our `:tx` comes back as the answer,
anything else is an ordinary request and goes to `handle`, which is what lets
the inner function park on a capability and be answered mid-call. `:tx` is
checked rather than assumed, because several calls can be in flight and taking
the first `:return` would hand one caller another's value.

This is what `sdks/rust` means by "from inside, a call is a port send and a
park rather than this", warning that guest code occupying a driver thread while
waiting for a driver thread "is the oldest deadlock there is". The outer's own
green thread parks inside a served request; no driver thread waits on one.
**Which thread drives is not knowable and does not need to be** — the top-level
pool decides.

The surface did not change. `(call box "ns/f" args)` still reads as an ordinary
call, because parking is transparent to a guest.

### A sandbox is lent capabilities, and may not be lent more than the caller holds

`(sandbox image {:with ["env"]})` serves the inner program exactly that, through
`host_for` — the same table the CLI serves itself, so a sandbox lent `[fs]` gets
the `fs` its parent would have. A sandbox given nothing reaches nothing, which
stays the default. The lending rule is `run`'s, on the other door: a caller may
pass on what it holds and not mint what it has not.

### A throw inside is catchable outside

The protocol separates `{:op :return}` from `{:op :throw}`, so a throw becomes
an error the caller can catch. **node had to be changed to agree.** It answered
the error as DATA, matching what `flint_call` does — correct before the native
side moved to the port path, and afterwards it meant a `(try ... (catch ...))`
around a nested call fired on one front end and not the other. Found by running
both, which is the only thing that ever finds these.

### What had to change to serve it on node, and what it cost### What had to change to serve it on node, and what it cost

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

**Status: FIXED, and the fix is still in place -- verified 2026-09-19.**
`stripChecks` is defined at `sdks/cli/src/cli.mjs:46` and applied at both call
sites, 106 and 126. The section's own account of the fix matches the code.

Recorded 2026-09-12, **and fixed the same day.** Found while checking that
`flint.ception` had broken nothing; it was older than that work.

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

## calls-are-ports

**Ratified:** ☐ not signed off

**Status: BUILT -- verified 2026-09-19.** `flint_call` is gone as an entry
point: `runtime/src/abi.rs:176` reads "`flint_call` used to live here", and the
remaining mentions across the runtime are history in comments rather than an
export. The system port is the way in.

Recorded 2026-09-13. **A sandbox is reached only through its system port.** A
call in, a request out, and driving are all messages on it. `flint_call` — the
second way in — is gone, and every sandbox has a system port whether or not
anything is served to it.

### Why one way and not two

Ports are the whole sandbox boundary. With two ways in, the boundary is two
things that have to agree, and this project's recurring defect is exactly that:
one surface exercised, the other assumed. The `:checks` axis existed on one CLI
and not the other; a throw came back as an error on one front end and as data
on the other, so a `catch` around a nested call fired on one and not the other.
Both were two implementations of one idea drifting.

A port also gives what a direct call cannot:

* **calls distribute across a thread pool** — a message can be picked up by
  whichever driver is free, and which one is driving at any moment is not
  knowable and does not need to be;
* **a call can park** — it runs as a green thread, so the called function may
  open a port and wait, and the host answers that while the call is still
  outstanding. `flint_call` ran on the host's stack and could not park at all,
  which is why a sandbox built on it could reach nothing.

### What it cost, measured rather than assumed

`sdks/esm/src/guest.js` argued for keeping the second path with a number:
routing every call through the system port would put a scheduler, a ring and an
event queue into a module whose whole source is `(defn f [x] x)` — 300 801
bytes becoming 335 320.

**That cost is no longer real.** A trivial module — `(ns t) (defn main [args]
"x")` — is 589 649 bytes with the port machinery and 589 649 without it,
byte for byte. `clojure.core` already reaches a builtin the concurrency unit
provides, so every module that carries the standard library already linked it.
The saving the second path existed to protect had already been spent.

`flint.conc` is now in the link closure unconditionally, beside `flint.rt`,
rather than by reachability. That changes no size today; it makes the invariant
hold for a module that reaches no conc builtin at all, which is the case the
old rule would have broken.

### What went, exactly

* `flint_call` in `runtime/src/abi.rs`, and `encode_error`, its only caller.
* `"flint_call"` from `flint.link/abi-exports`. Removing the function alone was
  not enough: `rust-lld` failed with `symbol exported via --export not found`,
  which is the link line asking for it by name.
* `callSync` in `sdks/esm/src/guest.js`, and the two-path choice above it.

`arg_alloc`, `arg_push`, `out_ptr` and `out_len` stay: they are how a host
writes an encoded value in and reads one back, which the port needs too.

### CLOSED 2026-09-19 -- was "OPEN, AND IT BLOCKS THE GATE": gas no longer agrees across runtimes

**The row is green and was re-run today**: `bin/conform-hosts` exits 0 and reads

    ok   the same program costs the same gas, to the instruction: 143035 [jvm]

Everything below is kept rather than deleted, because the reasoning is the part
worth having -- and because one of its conclusions was wrong in a way that is
worth carrying forward. THE OLD TITLE IS KEPT IN THIS HEADING so citations to
*OPEN, AND IT BLOCKS THE GATE* still find it.

**The decision this section poses was never taken, and was never needed.**
Neither "make the ports call over their system ports" nor "take the scheduler's
steps out of gas" happened. The gap closed as three ordinary defects, recorded
in full in `doc/goals/kin-port.md`:

* **a preemption that COST billed steps.** `save_current_state` saved three
  buffers and billed one of them -- the handler buffer -- while the stack and
  frame buffers were deliberately unbilled, their size being a property of the
  calling convention rather than of the program. Nothing argued for the
  difference. It made gas depend on WHERE a thread happened to be preempted;
* **native billing the slice boundary differently** from the ports, and
  initialisers billed on one side and not the other;
* **an instruction that ran free.** `tick` tests before it charges, so the
  iteration that trips a slice does not increment `steps`. When the runtime
  can preempt, it returns and nothing is lost; when it CANNOT -- Rust frames
  underneath, which is what forcing a lazy seq looks like -- native re-armed
  the slice and FELL THROUGH to execute the instruction anyway. The jvm and
  the clr already `continue`d there and so charged it.

**What the ports did change, and it is not what this section asked for.** Both
`RtSteps` drivers now enter the program through a bridge call rather than
`runProgram` -- dated 2026-09-18 in their own comments -- so the "the two enter
by a different door" half of point 1 is closed. `system_message` still exists
on native (`runtime/src/conc.rs`, `cli/src/serve.rs`) and still does not exist
in `runtimes/`, checked today. That remains true and is no longer a gas
question.

**THE CORRECTION WORTH CARRYING.** *LOCALISED, 2026-09-16* below was right
about WHERE -- constant per SLICE, in the preempt/resume path, and the handler
buffer is exactly that. Its prescription was not what fixed it:

> the way to make them agree by construction is to generate the resume path.

Generating it would have made two implementations agree on a charge that
**should not have existed on either of them**. Convergence is not correctness:
"make them agree by construction" answers which answer they give and says
nothing about whether it is the right one, and a generated resume path would
have frozen the wrong number into all three at once. The same trap the
section at `doc/goals/kin-port.md` records from the other side -- "the port
made the scheduler agree and the numbers still disagreed, because the numbers
are not produced by the part that was generated."

---


`bin/conform-hosts` compares the gas two workloads cost, native against jvm, to
the INSTRUCTION. That row now fails:

    FAIL gas differs by 682: 143717 native against 143035 [jvm]

Measured either side, raw rather than as the difference the row reports:

| | small | big | difference |
|---|---|---|---|
| native / wasm | 57 850 | 201 567 | 143 717 |
| jvm | 57 253 | 200 288 | 143 035 |
| overhead | +597 | +1 279 | +682 |

**The jvm number did not move.** The wasm side rose, because a call is a green
thread now and the scheduler's own work is counted as the program's gas. It
does not cancel in the difference because it is not fixed: 597 steps on a
57 000-step program and 1 279 on a 200 000-step one. It GROWS with the work —
the thread's stack grows and the checkpoint runs more often.

Two things are wrong and they need separating:

1. **The ports have not made this change.** `RtSteps` calls `runProgram`
   directly, and `runtimes/` has no `system_message` at all. So the rule — only
   ports — holds for wasm and native and not for jvm or clr, which is the
   convergence this project treats as a defect rather than a configuration.
2. **Gas is supposed to be the PROGRAM's instruction count**, and now carries
   the host's pump on two runtimes out of four. Determinism is what lets a
   cross-engine comparison claim apples-to-apples, and construe's gates depend
   on the number.

**The tolerance is not the answer.** That row deliberately has none: the
comment above it records that a 1% bound absorbed a real defect — the ports
hashing twice where native hashed once — for as long as the bound existed. "A
bound wide enough to absorb an unexplained difference will absorb the next one
too." Widening it to fit 682 would be that, again.

So the choice is between making the ports call over their system ports too,
which makes every runtime pay the scheduler and bakes it into gas everywhere,
and taking the scheduler's steps out of gas so it measures the program again.
That is a decision about what gas MEANS.

**Neither was chosen and neither was needed** -- see the status at the top of
this section. The framing was not wrong to pose; it was answered by the gap
turning out not to be about the scheduler at all.

### ~~LOCALISED, 2026-09-16: it is ~46.5 steps per SLICE~~ CLOSED, re-measured 2026-09-20

**THE PER-SLICE CHARGE IS GONE, and the note below is kept for its method.**
The same shape of program, re-measured today across all three runtimes:

    slicegap/small (20 000)   wasm 275 937   jvm 275 843   clr 275 843
    slicegap/big   (40 000)   wasm 535 937   jvm 535 843   clr 535 843

    raw gap wasm against jvm:   94 at 20 000,  94 at 40 000
    the note below predicted:  3 164          6 190

**A CONSTANT, NOT A SLOPE, which is the whole claim.** The small run is
preempted 64 times and the big one 127; a charge of ~46.5 per slice would show
as a gap that grew by about 2 930 between the two numbers, and it grew by
zero. `big - small` is 260 000 on all three, to the instruction -- a loop that
allocates nothing and calls no builtin now costs exactly the same everywhere.

**The fix this section prescribed is in the tree.** It asked for the resume
path to be generated so the three would agree by construction; `run_one` is
`sched_run_one` / `schedRunOne` / `SchedRunOne` out of `kin/sched.kin` on all
three, and `settle` followed it into `kin/settle.kin` on 2026-09-20. What
closed the number, though, were the three ordinary defects listed under the
CLOSED banner above -- the billed handler buffer, the slice-boundary billing,
and the instruction that ran free -- not the generation. Both things are true
and only one of them is the cause.

**What is left is a fixed per-program offset**, and it is not one number: 94
on `slicegap`, 97 on `gasmeter`. It does not move with the work within a
program, so it is what each runtime spends getting in, and it is exactly what
the difference-of-two-workloads method exists to cancel. `gasmeter` reports
143 035 on wasm and on the jvm, which is the figure `bin/conform-hosts`
asserts.

**AND IT IS GATED NOW, which it was not before.** The `gasmeter` row subtracts
two workloads, and a constant is precisely what a subtraction hides -- so for
as long as the gap was constant, nothing in the tree could tell a fixed offset
from a per-slice charge that had shrunk. `bin/conform-hosts` gained a
`slicegap` row that asserts the SHAPE: the raw gap must be the same at both
sizes. It was run against a deliberately reintroduced defect -- 46 steps
charged per preemption on the jvm -- and read 2 850 at 20 000 against 5 794 at
40 000, failing as it should. The row also asserts that the big run is still
preempted far more than the small one, because two constants compared on a
program that never crosses a slice boundary would pass for the wrong reason.

**The clr is in this measurement for the first time.** The note below only
ever compared native against the jvm; the two ports agree here to the
instruction on both sizes.

### The 2026-09-16 note, kept for its method


The reading above -- "a PRICING difference in operations both runtimes have",
spread across all seven parts in proportion to work -- does not survive a
measurement it was never given. Micro-benchmarks, each one operation, native
against jvm with BOTH now called over a bridge:

    op         native      jvm     gap     pct
    base        15945    15851      94   0.59%
    allocv      96913    95863    1050   1.08%
    mapv1      160284   158510    1774   1.11%
    amaps       59309    58689     620   1.05%
    intoset    203989   201737    2252   1.10%
    strs       132330   130894    1436   1.09%

The same ~1.1% on maps, on sets, on strings AND on a loop that conjes onto a
vector. So it is not maps and it is not hashing. The decisive one is a pure
arithmetic loop that allocates NOTHING and calls no builtin at all:

    (loop [i 0 acc 0] (if (< i 20000) (recur (inc i) (+ acc i)) acc))

    20 000 iterations   native 279 069   jvm 275 905   gap 3 164   1.133%
    40 000 iterations   native 542 095   jvm 535 905   gap 6 190   1.142%

`SLICE` is 4096 on all three. 279 069 / 4096 = 68 slices, and 3 164 / 68 =
**46.4 steps per slice**; the 40 000 run gives **46.7**. A constant, and
`HostCall.java`'s own comment predicted it -- "about 48 steps per 4096-step
slice" -- before the row's analysis talked itself out of it.

**Why the earlier experiment did not refute this, though it looks like it
did.** That experiment drove the JVM over a real bridge and got "the same
figures". It did, because it kept the DIFFERENCE-OF-TWO-WORKLOADS methodology,
which cancels a fixed startup cost and does NOT cancel a cost proportional to
the work -- and a per-slice charge is proportional to the work by definition.
The control was measuring the wrong thing, so agreement proved nothing.

**What is ruled out**, measured rather than reasoned:

* allocation VOLUME -- `bytes-allocated` from `flint.rt/gc-stats` on the same
  program: allocv 447 040 native against 445 776 jvm, 0.28%, against a gas gap
  of 1.08%. It does not track;
* the allocation CHARGE -- `size_for(ty, len) >> 3` in both, character for
  character, and `Obj.sizeFor` matches `obj::size_for` branch for branch;
* saving thread state -- `alloc_unbilled` / `allocUnbilled` on both;
* restoring it -- neither charges; both write the array directly;
* `conj` of a map onto a map, which WAS a genuine two-algorithm divergence and
  is now generated (`kin/mapconj.kin`). The gap did not move, which is how we
  know it was not this either.

So the cost is in what a green thread pays to be PREEMPTED AND RESUMED, and
native pays about 46 steps more of it than the jvm does, every slice. That is
a `Conc` divergence in `run_one` and the interpreter's checkpoint, which is
the one part of the scheduler still written three times.

**This does not need the decision the section above poses.** Neither "make the
ports call over their system ports" nor "take the scheduler out of gas" is
required to close a 46-step-per-resume difference between two implementations
of the same resume. They should simply agree, and the way to make them agree
by construction is to generate the resume path.

> **DONE, AND THE NUMBER WAS CLOSED BY SOMETHING ELSE.** The resume path is
> generated -- `sched_run_one` out of `kin/sched.kin`, and `settle` out of
> `kin/settle.kin` -- and the gap is a flat 94 rather than a slope. But it was
> the three defects under the CLOSED banner that moved the number, not the
> generation, and a reader who took this paragraph's word for the remedy would
> have credited the wrong change. Re-measured 2026-09-20; see the heading
> above this note.

### Not yet done

`Program::call` and `flint_rt::native::call_on` still exist on the NATIVE side,
and `sdks/rust`'s driver dispatches through them. Nothing reaches them from the
CLI any more — `flint.ception` goes over the port — but the Rust SDK has its
own inbox-and-driver model built on `call_on`, and moving it to the port
protocol is its own change. Until then the rule holds for wasm and not for a
natively embedded sandbox.

## bridges-are-the-only-door

**Ratified:** ☐ not signed off

**Status: BUILT on the three runtimes checked -- verified 2026-09-19.** The
one-shot boot exists under its own name in each: `boot_system_thread_once`
(`runtime/src/conc.rs`), `bootSystemThreadOnce` (`Conc.java`),
`BootSystemThreadOnce` (`Conc.cs`). The control plane it spawns is
`lib/flint/system.cljc`, and it ships in EVERY module rather than on request --
`test/twobuilds.clj:156` prices that at +67 409 bytes and asserts it, so the
claim is gated rather than merely true today.

**And the seven-row "what has to change" table below was checked row by row,
2026-09-19.** It reads as a settled design; it is a PLAN, and roughly half of
it has happened. What landed is the control plane -- a system port on each
runtime, `bootSystemThreadOnce`, and the protocol itself moved into the image
as `lib/flint/system.cljc`. What did not land is the structural half:

    row              as the table describes "today"        now
    bridge memory    ring inside the receiving sandbox     UNCHANGED
                       (`PT_INBOX`)                          conc.rs:142, :783
    executor         per-sandbox `Driver`                  UNCHANGED
                                                             sandbox.rs:248
    wake condition   on write                              UNCHANGED -- `wake_on`
                                                             wakes ALL parked
                                                             threads and they
                                                             re-park
    Rust SDK         `Mutex<VecDeque<Request>>`            UNCHANGED
                                                             sandbox.rs:54
    CLI              `Host`/`Service`, pumping inline      UNCHANGED
                                                             cli/src/serve.rs:87
    JVM / CLR        `TH_LEN=12`, no `systemMessage`       REACHED, by the other
                                                             route -- see below
    constructor      no system port; installed after       UNCHANGED

The `JVM / CLR` row resolved the way this section's own note predicted: the
ports never received `structured-ports` step 5, and did not need to, because
the protocol moved into the image instead of being hand-ported. They have
`installSystemPort` and `bootSystemThreadOnce`; `systemMessage` has zero
occurrences on either and that is correct rather than missing.

**The wasm outside edge, MEASURED 2026-09-23 rather than described.** The
design this section is named for is "one door": a `boot`/`init` that accepts a
bridge port, which becomes the system port, and nothing else. Read off a
freshly linked module, the edge was 25 exports plus 90 `flint_b_*` when this
was written and is 23 now, two of them having been measured dead and removed
the same day (see the sweep below):

* the bridge protocol, TWELVE functions, not one -- `flint_install_port`,
  `flint_system_port`, `flint_deliver`, `flint_drain`, `flint_events_ptr`,
  `flint_continue`, `flint_resume`, `flint_close`, `flint_port_state`,
  `flint_in_alloc`, `flint_grant`, `flint_answer`
  (`units-src/flint-conc/src/bin/manifest.rs:14`);
* the byte transport `arg_alloc` / `out_ptr` / `out_len`, resource control
  `set_step_limit` / `set_memory_limit` / `stat_steps`, and `FLINT_IMAGE_DESC`,
  `flint_opaque_host_id` -- all of them named by `abi-exports`
  (`src/flint/link.cljc:156`).

**WHICH OF THESE IS LEGACY, by call site rather than by reading.** Counted
across `host/`, `sdks/`, `cli/src/`, `test/` and `bench/`, every export above
has live callers except three, and the three are not alike:

* `arg_push` -- ZERO callers, all eight mentions comments. It built an argument
  list for `flint_call` and outlived it; the `static mut ARGS` behind it was
  written by one export and read by nobody. REMOVED 2026-09-23.
* `image_desc_addr` -- ZERO callers, and redundant: both consumers read
  `FLINT_IMAGE_DESC` as an exported GLOBAL instead (`runtime/src/native.rs:43`,
  `src/flint/bundle.cljc:127`). REMOVED 2026-09-23.
* `flint_opaque_host_id` -- zero callers and NOT dead, which is why the other
  two needed checking one at a time. Its PRESENCE in the export list is the
  `:capabilities` flag (`src/flint/bundle.cljc:168`, `src/flint/link.cljc:497`).
  Removing it means re-sourcing that flag first.

`arg_alloc` survives on a technicality worth writing down: its only remaining
callers load an IMAGE (`host/run.mjs:71`, `test/loader.clj`), which is
`loader-exports` work. A production module built without `--loader` has no user
for it at all. Moving it is a decision rather than a cleanup, and it would
likely be overtaken by `boot`/`loop` below.

*The codec is therefore NOT a second way to make values inside a sandbox.*
That reading is natural and was half right: the one export that genuinely
assembled a value from pushed arguments was `arg_push`, and it was already
dead. What is left moves BYTES for the port -- the live driver writes inbound
through `flint_in_alloc` (`sdks/esm/src/guest.js:159`, `:238`, `:386`) and
reads outbound through `out_ptr`/`out_len` (`:76`, `:466`).

There is no `boot` or `init` export at all. `flint_install_port(id, len,
system)` is the wasm projection of "the bridge port becomes the system port",
and it takes a NAME out of a byte buffer plus a flag, not a port. The rest of
the twelve are the transport a C ABI needs underneath a port -- a wasm boundary
cannot pass one -- rather than second doors, which is why `calls-are-ports`
stays true with twelve exports.

*The 90 builtins are not a widening of that surface, and this is worth writing
down because it reads like one.* `src/flint/link.cljc:464` already settles it:
they are "internal linkage detail, hundreds of them, and nothing a runner can
do with the names", `--export`ed only so the registry table survives
`--gc-sections`, and the module's own `:exports` metadata COUNTS them rather
than listing them. The declared ABI is the 25.

**Do not read the edge off `out/a.wasm`.** It is stale in the direction that
misleads: it still exports `main` and has no `flint_system_port`, so it
describes the ABI as it was before `calls-are-ports` landed.

**The caller port is real and calls do not travel on the system port.**
`lib/flint/system.cljc:189` -- `:bind {:port p}` spawns `serve-calls` on `p`,
and a call is `{:op :call :fn ... :args ... :tx n}` on THAT port, answered
`:return`/`:throw`. The system port carries only `:bind`, `:unbind`, `:close`.
The one difference from the design as usually stated: the caller port is handed
IN by the host at bind time rather than created by the system port.

**One claim in this section is now FALSE and it is the interesting one.** It
says "**None of the concurrency is generated.** All 91 kin sources were
checked: not one touches ports or scheduling, so `Conc` is hand-written three
times." The section closes by guessing "the scheduler is probably portable too
-- untested, and not this change", and that guess came good; the sentence
three lines above it did not survive.

**Re-counted 2026-09-21, and the correction had gone stale too.** The note
below this one said 97 sources and three concurrency files; it is now 109
sources and FIFTEEN of them are ports and scheduling outright -- `sched`,
`schedlists`, `schedmake`, `settle`, `portring`, `portdrain`, `portmake`,
`portbytes`, `portinstall`, `porthost`, `portrecv`, `portpark`, `reapports`,
`threadjoin` and `mainanswer`. `Conc` across the three runtimes has gone from
6 743 lines to 5 540, and 44 of its 88 functions are now one-line delegations
to generated code -- exactly half.

*A correction decays at the same rate as what it corrected.* This is the
second time this sentence has been rewritten and the first rewrite lasted two
days; what makes the count wrong is the work going well, which is the one
cause nobody thinks to guard against.

The "eight implementations" phrasing is still not confirmed as a count: four
hosts were never enumerated here, and only the three runtimes plus the Rust SDK
and the CLI were looked at.

Recorded 2026-09-13. The settled shape of `DECISIONS.md#drivers`, worked out
across several rounds and written down before it is built, because eight
implementations get built against it — four runtimes and four hosts.

### The rule

**A bridge port is the only way to talk to a sandbox.** Not the main way: the
only one. No direct call, no entry point, no host reaching in. `flint_call` is
already gone (`DECISIONS.md#calls-are-ports`); `Program::call`, `call_on`, the
Rust SDK's `Request` inbox and the CLI's `Host::call_named` go with it.

### What a bridge is

A standalone object with **its own memory**, owned by neither side, with **two
ends**. Writing into an end copies the value into the bridge — as encoded
bytes, because two sandboxes have separate heaps and a value cannot cross by
reference.

**Bridges are host-side.** `runtime/` is `#![no_std]`, so a registry with locks
and a pool cannot live there. The sandbox side is what it already is: a
refcounted HANDLE — a `K_BRIDGE` port interned by host id, so a handle arriving
twice counts once (`install_bridge_port`: "hand back the SAME object and say
nothing to the host"), with exactly one `EV_RETAIN` and one `EV_RELEASE` per
port per sandbox.

**Refcounted per END. A bridge dies when both ends reach zero.** A sandbox
tracks which bridge ports it holds so it cannot double-count, which the
interning already gives.

LOCAL channels are unaffected and stay in-heap: `crosses_a_heap` already
separates them, and a channel between two green threads in one sandbox has no
reason to pay for a host round trip.

### What drives a sandbox

ONE executor, **shared across sandboxes, including inner ones**. It owns the
thread pool, or a single thread.

**A sandbox is runnable exactly when a thread parked on a bridge end has a
value waiting** — not when a bridge is written. A write nobody is parked on
does nothing; a thread parked on an empty bridge does nothing. The predicate is
a sibling of `sched_needs_host` (`runtime/src/kgen/rt/sched.rs:89`, GENERATED
from `kin/sched.kin` since this was written): the same walk over
`SC_THREADS` and `TH_PARK_ON`, asking whether the port has a value rather than
merely whether it is a bridge.

Wakes **debounce**: N arrivals between two runs cost one dispatch, which
`Driver::wake` already requires and `Core.scheduled` already implements.

### The control plane is FLINT CODE, not runtime code

**The system thread is an ordinary flint closure.** Bootstrap mints it over the
system port and spawns it as a green thread; nothing about it is special to the
scheduler. That single choice is what keeps this from being a protocol ported
four times:

* the loop lives in `lib/`, is compiled into the image, and every runtime gets
  it from the image;
* `:tx` is a LOCAL in that loop, so there is no `TH_TX` slot and no
  `answer_call`;
* `hostDeliver` needs no system branch — the branch exists today only because
  nothing can receive on the system port, so delivery has to handle the message
  inline and "the bytes are given straight back: the request is consumed now,
  so it holds no queue". Park a thread there and that reason is gone.

So this DELETES rather than ports:

> **THE TWO SLOTS ARE ACTUALLY GONE NOW, 2026-09-21, and leaving them cost
> more than "Rust only" suggests.** `TH_ARGS` and `TH_TX` had no reader
> anywhere in the tree, so an earlier note in `kin/schedmake.kin` called the
> drift harmless. A thread object is allocated with `TH_LEN` slots and
> allocation is charged by SIZE: two dead slots billed two gas on every spawn,
> on native and not on the ports, against `resource-limits`' rule that the
> same program costs the same gas everywhere. No fixture spawned a thread, so
> nothing asked. `runtimes/conform/spawngas.cljc` asks now and
> `bin/conform-hosts` asserts the per-spawn cost does not differ.

| | | |
|---|---|---|
| `system_message` | Rust only | deleted |
| `answer_call` | Rust only | deleted |
| `TH_ARGS`, `TH_TX` | Rust only | **deleted 2026-09-21** — `tx` is a local |
| `hostDeliver`'s system branch | Rust only | deleted |

**The JVM and CLR being a generation behind mostly stops mattering**, because
the generation they missed is the one being removed. What they need is what
every runtime needs: spawn the closure at bootstrap, and answer the readiness
predicate. Both are small and neither is a protocol.

### The system thread, and why calls do not run on it

The constructor takes a **system bridge end** — a sandbox cannot be built
without one — and bootstrap spawns a **system thread** parked on it. So the
readiness predicate has no bootstrap exception: something is parked on the
system port from the first instant.

**The system port is control-plane only: `bind`, `unbind`, `close`. No `:call`
on it.** Calls run on a DEDICATED CALL THREAD, bound to a port:

    -> {:op :bind   :port P}     spawn a call thread parked on P
    -> {:op :unbind :port P}     stop it
    -> {:op :close}              the sandbox goes

The holder makes a bridge pair, keeps one end, and sends the other as a
`K_PORT` value — delegation that already works, since a port the host names in
a message is one it is handing over (`DECISIONS.md#ports-are-the-hosts`).

Calls then go on the bound port and answers come back on it carrying `:tx`:

    -> {:tx n :op :call :fn "ns/f" :args [..]}
    <- {:tx n :op :return :value v}
    <- {:tx n :op :throw  :kind ".." :message ".."}

**One bound port is a QUEUE: serial by construction.** Several calls may be
outstanding, and they are processed one at a time in arrival order. Concurrency
is explicit — bind two ports — so the cost of it is visible rather than a
thread appearing per call.

**Why not on the system thread.** Because a call that parks would park the
control plane, and `close` would be stuck behind a call that is waiting on
something. And because "the system thread never becomes irrecoverable" is then
a property maintained by catching everything, where one missed edge — a throw
while unwinding, an allocation failure mid-handler, a native that traps — takes
out the sandbox's only control plane. **Guest code never runs on the system
thread, so guest code cannot kill it.** Guarantee by construction, the same
move as a grant being conferred from outside rather than asserted by the var it
protects.

The call thread's contract is smaller and can be met: a throw answers
`{:tx :op :throw}`, and the thread re-parks to serve the next request.

### The sandbox object is sugar

What a constructor returns is **an API over the system port end** — an id, that
end, a reference to the executor. Every operation is a message, `close`
included; there is no disposal path beside it. `DECISIONS.md#drivers` already
says the handle is "an id, its system port, a reference to its driver", and
this is that, with the consequence followed through: an SDK wraps a bound port
in a client object so the simple case reads simply, and two clients is two
bound ports.

### What has to change, and where it is not

| | today | target |
|---|---|---|
| bridge memory | ring inside the receiving sandbox (`PT_INBOX`) | the bridge's own, host-side |
| executor | per-sandbox `Driver` | one, shared |
| wake condition | on write | parked thread + non-empty end |
| Rust SDK | `Mutex<VecDeque<Request>>` → `call_on` | bridge → system port |
| CLI | `Host`/`Service`, pumping inline | nothing; the executor drives |
| JVM / CLR | `TH_LEN=12`, no `systemMessage`, no system branch in `hostDeliver` | the whole protocol |
| constructor | no system port; installed afterwards | system bridge required |

**PRICED 2026-09-23, because "UNCHANGED" seven times reads as expensive and
the table was costed against a world that has since gone.** The question asked
was whether the wasm edge can become `boot(bridge_ptr)` plus `loop()`. What it
would touch, counted:

| piece | size | note |
| --- | --- | --- |
| the twelve externs | 78 lines, `units-src/flint-conc/src/lib.rs` | collapse into two |
| `sdks/esm/src/guest.js` | 29 lines of 589 | the ONLY hand-written driver |
| `sdks/esm/src/flint.js` | one export (`flint_system_port`) | |
| `sdks/cli/src/sys.mjs` | one export (`flint_resume`) | |
| tests | 4 files | `host_abi.mjs`, `globalport.mjs`, `threads.clj`, `e2e_link.clj` |
| `sdks/*/dist/*` | generated | rebuilt, not edited |
| `cli/src/` | ZERO references | the native CLI drives `conc.rs` directly |

*The row that changes the estimate is the ring itself.* This table says
"bridge memory: ring inside the receiving sandbox -- UNCHANGED", and it was
written while this section also said "None of the concurrency is generated ...
`Conc` is hand-written three times" -- the claim this section now marks FALSE.
The inbox ring is `kin/portring.kin`, 112 lines, "A PORT'S INBOX, WRITTEN
ONCE", generating into Rust, Java and C# from one source; its four siblings
(`portdrain`, `portinstall`, `porthost`, `portrecv`) are another 419. So row
one is an edit to ONE source with three-way drivers, not to three hand-mirrored
copies, and every "UNCHANGED" here was priced before that was true.

**What stays, and why it is not an exception worth arguing about.**
`set_step_limit`, `set_memory_limit` and `stat_steps` cannot become messages:
a sandbox that has exhausted its gas has no gas with which to process a message
about gas. `memory` must stay exported or the host cannot reach the ring at
all. That puts the floor at six -- `boot`, `loop`, three limit/metric calls and
`memory` -- plus the loader set when built with `--loader`.

**The risk is not in the line count.** `portring.kin`'s own header says the
three copies "agreed on every test because no test ran them concurrently
against each other", and moving ownership of a lock-free ring from the
receiving sandbox to the bridge changes exactly that structure. Price the
DRIVERS for the new discipline before the code, not after.

**The ports never received `structured-ports` step 5** — no `TH_ARGS`/`TH_TX`,
no `systemMessage`, no system-port branch. Which turns out not to matter: those
are the things being deleted. Recorded because it was nearly the other way
round, and the version of this plan that ported them into Java and C# by hand
would have been three times the work for a worse result.

**None of the concurrency is generated.** All 91 kin sources were checked: not
one touches ports or scheduling, so `Conc` is hand-written three times. That is
still true of the SCHEDULER; it stops being true of the protocol, which moves
into the image. `^:mut ^Rt` is expressible in kin and used across the sources,
and `kin/atoms.kin` already does compare-and-set-shaped work, so the scheduler
is probably portable too — untested, and not this change.

> **OVERTAKEN, 2026-09-19.** The guess in the last sentence came good and the
> claim in the first no longer holds. There are 97 kin sources and three of
> them are ports and scheduling outright -- `flint.rt.sched`,
> `flint.rt.portring`, `flint.rt.reapports`. `wake_on`, `wake_waiter`, `drive`
> and `reap_ports` are generated; `runtime/src/conc.rs:1028` says so where they
> are called. `Conc` is no longer hand-written three times in the parts that
> matter most for drift.
>
> Kept rather than edited, because the paragraph is a good record of how the
> scope looked from here: the thing it called untested and out of scope is the
> thing that got done, and the thing it stated as fact is what expired.

### What it settles

The gas divergence in `DECISIONS.md#calls-are-ports` closes when every runtime
calls the same way, because every runtime then pays the same scheduler.

## vars-is-its-own-grant

**Ratified:** ☐ not signed off

**Status: BUILT, and the guard is ENFORCED rather than only documented --
verified 2026-09-19.** `src/flint/analyzer.cljc:326` carries the table

    {"flint/request"   #{:host}
     "flint/var-named" #{:vars}}

so the grant is a real entry in the analyzer's capability map, not a comment
beside an ungated builtin. `runtime/src/builtins.rs:645` is the builtin itself.
Checked in that order on purpose: a docstring saying GUARDED is what an
ungated builtin looks like from the outside.

Recorded 2026-09-14. Resolving a var by NAME at run time is a capability of its
own, `:vars`, and not part of `:host`.

### What it is

`Rt::var_named` exists (`runtime/src/vm.rs:2087`) and is not a builtin, so flint
code cannot turn a string into a var's value. The system loop needs to:
`{:op :call :fn "ns/f"}` names a function as text, and something has to resolve
it (`DECISIONS.md#bridges-are-the-only-door`).

### Why not `:host`

`:host` means *may ask the host for something* — it gates `flint.host/request`,
which reaches `flint.rt/request`, and `lib/deps.edn` holds it for exactly that
reason. Reaching into the image's own var table is not asking the host
anything. Putting both behind one name would make that name mean two unrelated
things, which is the shape that produced the `:checks` axis on one CLI and not
the other, and the duplicated dependency-kind tables.

It is named for what it reaches, like `fs`, `env`, `net`, `deps` and `slurp`.

### What it actually confers, stated plainly

**An escape from the compile-time workspace guard.** `workspace-capabilities`
decides which workspace may NAME which var, and it decides that while
compiling. A string resolved at run time was never seen by that check. So
`:vars` is not "a bit of reflection" — it is the authority to reach a var the
compiler would have refused, and it should be granted with that in mind.

That is also why it cannot simply be a builtin anyone may call: an ungated
`var-named` would make every compile-time guard advisory.

### It reaches only what SURVIVED THE SHAKE

A name resolved at run time does not keep a var alive — the shaker cannot see
a string. Measured: a program whose only mention of `v/target` is
`(var-named "v/target")` gets `nil`, and the same program with an ordinary
reference to `target` elsewhere gets the function and calls it.

That is the right behaviour and not a limitation to work around. It means
`:exports` is what makes a function callable from outside
(`DECISIONS.md#flint-ception` already uses it for exactly this), and it keeps
`var-named` from quietly defeating the shaker: only reachable code ships, and
this does not change what reachable means.

### Who holds it

`lib/deps.edn`'s workspace, which is where the system loop lives. A guest
program holds it only if an embedder grants it, and the ordinary case is that
nobody does — a program names its functions at compile time like any other.

## the-codec-is-guest-code

**Ratified:** ☐ not signed off

**Status: BUILT -- verified 2026-09-19, and this section was written BEFORE it
was built, which is the direction that misleads least.** `lib/flint/wire.cljc`
is the codec in flint and it is live: `lib/flint/port.cljc` requires it and
calls `wire/encode` at line 111. The primitives underneath are generated --
`kin/wirecore.kin` and `kin/wirescan.kin`.

**What did NOT happen is the deletion this section anticipates**, and the
question left open on 2026-09-19 -- whether the three hand-written codecs are
still on a live path -- was answered the same day. The answer differs per
runtime, which is why one sentence could not cover it:

* **Native: LIVE.** `runtime/src/codec.rs` is reached from
  `runtime/src/native.rs` at 537, 542, 669 and 708 -- `Program::call` decodes
  the encoded call through it. Not dead, not deletable.
* **Both ports: DEAD except the tag constants.** `Conc.java` and `Conc.cs`
  reference `Codec` ZERO times; `hostDeliver` queues the host's bytes by length
  without decoding them. `Codec.decode` / `Codec.Decode` have no caller
  anywhere in the repository. `encode` / `Encode` has exactly one each, and
  both are test harnesses (`RtHostReq.java:155`, `Program.cs:667`).

**And it was NOT deleted, deliberately.** `decodeGuest` carries a comment
saying nothing calls it and that it exists so whoever adds a guest-reachable
decoder finds the safe one rather than writing the unsafe one. That is a
recorded decision; removing it on the strength of a caller count would override
it. What was wrong here was the DOCUMENTATION, and that is what was fixed.

**Both ports' `Codec` headers were stale in two ways, and the second was a
SECURITY argument.** They said `send` encodes and `hostDeliver` decodes "and
both run in the RUNTIME" -- neither does any more -- and, load-bearingly, that
"there is no builtin that encodes and none that decodes", which is what they
offered as the reason a guest cannot turn bytes into a port. There are
twenty-seven `flint/wire-*` builtins and they run in both directions.

**The property survives; the mechanism moved.** Minting is gated on the
READER'S PROVENANCE -- `wire_may_mint` refuses a reader over bytes the program
supplied and allows one over bytes that arrived on a bridge -- and that guard
is GENERATED from one kin source for all three runtimes (`wirecore`), gated at
both `wire-port-in` and `wire-opaque-in` on each port, and asserted in both
directions at the unit level and end-to-end on all four builds. So this was a
documentation defect and not a hole; but a header that offers an obsolete
security argument is the kind of thing somebody reasons from, so both now carry
a correction rather than a rewrite.

Recorded 2026-09-14. Settled in conversation after `WireMeta` landed and made
the seam obvious; written down before it is built, because it replaces code in
three runtimes and two hosts.

### The rule

**The wire codec is flint, not runtime code.** Encoding and decoding a value
that crosses a bridge is guest code running inside the sandbox, over a small
set of runtime primitives — not a tag switch hand-written in Rust, Java and C#.

The primitives are a STREAMING writer and reader, in the shape a JSON writer
has: the guest emits pieces and the runtime turns them into bytes.

### The writer

ONE PRIMITIVE PER SHAPE, not one `emit` taking a tag. A tag argument would put
a dispatch in the hottest loop the language has, and -- the reason that matters
here -- it would hide the dangerous primitive among the harmless ones. Named
separately, the one that needs auditing has its own name and its own signature:

    (wire-nil w)  (wire-bool w b)  (wire-int w n)  (wire-double w x)
    (wire-str w s)  (wire-bytes w b)
    (wire-kw w ns name)  (wire-sym w ns name)
    (wire-vec w n)  (wire-list w n)  (wire-set w n)  (wire-map w n)
    (wire-meta w)   ; then the metadata, then the value
    (wire-port w p)
    (wire-opaque w o)

**Counts, not brackets.** `wire-vec` takes the element count and the elements
follow, because that is what the format already says (`K_VECTOR`, then a `u32`,
then the elements). The wire does not change here -- only who writes it -- so
every existing host keeps reading what it read before, and a differential test
against the runtime encoder can assert byte-for-byte equality.

**`wire-port` takes a PORT, never an id.** This is the whole safety rule, and it
survives unchanged for the reason it held before: flint is given no way to turn
an integer into a port (`DECISIONS.md#structured-ports`). A guest can only pass
a port it holds, so a port tag can only appear in bytes written by someone who
held one. Same for `wire-opaque`. What moves is WHERE the rule lives -- from a
tag switch maintained in three languages to two primitives in one.

**THE WRITER IS AN OPAQUE OBJECT, NOT A BYTE BUFFER, and this is the part that
is easy to get wrong.** flint already has transient bytes -- `transient-bytes`,
`conj-byte!`, `append!` -- and they look exactly like what a writer needs. They
are the wrong thing, and reaching for them quietly destroys the rule above: a
guest that can append an arbitrary byte can write a `K_PORT` tag followed by any
id at all, and the bytes then claim a capability the sandbox never held.

Today that is impossible because the guest never writes bytes -- the runtime
encodes from a port VALUE, so the id can only be one the sandbox has. Moving the
codec into flint gives up that guarantee unless the writer is a type the guest
can only append to THROUGH the primitives. So it is: `port/send` takes a writer,
not bytes, and a guest that builds a byte string by hand can still send it, but
it crosses as `K_BYTES` -- a value -- rather than as an encoding anybody
interprets.

The alternative was considered and rejected: let the guest write bytes, and have
the RECEIVING host verify that the sending sandbox actually holds every port id
in them. The host has the holders table, so it could. But that is a new check in
every host, written once per SDK -- which is the shape this decision exists to
get rid of, reappearing on the other side of the boundary.

### The reader

`(wire-read r)` answers one piece at a time: a tag, and for a structural tag the
count, so the guest reads that many and decides for itself what to build -- or
stops, which is the point.

**A reader carries whether it MAY mint.** A reader over bytes that arrived on a
bridge may produce ports and opaques; a reader over bytes the guest supplied may
not, and refuses those tags. That is exactly today's `decode_guest`, which
exists because "a decoder is an encoder read backwards, and bytes are integers a
guest can write". One flag on one primitive, instead of the same distinction
made again in every port.

### Why

**The trust boundary shrinks to something one person can audit.** Today's rule
is that a decoder reachable from the guest must refuse the live tags
(`DECISIONS.md#structured-ports`): `K_PORT` and `K_SENTINEL` carry their
identity inline, and that is safe only because a guest cannot write those bytes.
It is enforced by a hand-written tag switch in three languages, and it has to be
got right in all three. With primitives the invariant moves to ONE place and
becomes a property of the primitive set: **`emit :port` is impossible for a port
you do not hold.** Same rule, stated once, where it can be checked.

**A streaming reader can refuse mid-message, which no decoder here can.** An
inbound message is decoded WHOLE before anything gets to object to it — its
depth, its size, a tag the receiver does not want. An event reader lets the
guest stop reading. That is an integrity property, not tidiness.

**Three implementations become one.** `runtime/src/codec.rs` and its mirrors in
`runtimes/jvm` and `runtimes/clr` are the same file written three times, and
every tag added since has had to be added three times — `K_WITH_META` most
recently. Guest code is compiled once and runs on all four runtimes, which is
the same argument `flint.system` already won (`DECISIONS.md#bridges-are-the-only-door`):
a protocol in the image beats a protocol maintained in parallel.

**The re-entrancy problem disappears.** `flint.port/for-the-wire` exists only
because the encoder is runtime code and cannot dispatch a protocol: it walks the
value in flint first, asks `WireMeta`, and hands the encoder something already
narrowed. A flint encoder just asks. The pass goes.

### What this does NOT do

**The host side stays native.** A host — the Rust SDK, the ESM driver, the CLI
— is not a sandbox and cannot run guest code to read its own mail. So this
collapses the three RUNTIME copies into one and leaves the host-side codecs in
Rust and JavaScript where they are. Two implementations, not one, and the
remaining two are on the side of the boundary that is already trusted.

### What it costs, measured

Encoding stops being free runtime work and becomes billed guest work
(`DECISIONS.md#resource-limits`). That is more honest — the guest asked for the
send — and it moves every gas figure, including the parity row in
`bin/conform-hosts`.

The speed question was asked as "do we lose that much?", so it was measured
rather than guessed:

* an interpreted flint pass over a 200-entry structure of small maps costs
  **0.48 µs per map**, by slope — 96 µs per walk, differencing 300 against
  3 000 repetitions so process start-up cancels;
* the same payload through a `flint.ception` round trip costs **about 1.1 µs per
  map per encode/decode pass**, differencing a 2-map payload against a 200-map
  one over 300 calls.

So a native encode pass and an interpreted flint pass over the same structure
are already the same order of magnitude, and the round trip's time is not going
into encoding. **Caveat, stated because the number will be quoted:** the second
figure divides by an assumed four passes per round trip. It is an inference from
the protocol, not an instrumented count, and there is no encode-only benchmark
in the tree to check it against. Build one before betting anything large on it.

### Both sides track structure, and the table stops being special

The three options below were written when the table looked like a format
problem. It is not. It is the first place a missing property showed up, and the
property is: **the codec machinery knows where it is, and the guest does not get
to say.**

**The reader API was backwards, and that was a hole.** As first built, the guest
chose which read to make -- `wire-tag`, `wire-u32`, `wire-port-in` -- and the
reader was a cursor that obeyed. `wire-port-in` took four bytes at whatever
offset the cursor happened to be and minted a handle from them, with no check
that a `K_PORT` tag preceded it. `RD_LIVE` stops a guest doing that to its OWN
bytes, but not to a message it received: ask a peer to send a string whose
payload is four chosen bytes, point the cursor inside it, and mint a handle to
any id at all. Data into capability. Latent only because no live reader is
reachable from flint yet, which is luck rather than design.

So the reader TELLS the guest what is there, rather than being told what to
read: one step answers what it found -- an int and its value, a vector and its
count, a port already minted because the encoding said `K_PORT` at a place a
port is allowed. There is no interpretation left for a guest to choose.

**Reader context alone does not close the WRITER hole**, and it is worth being
exact about why, because the symmetry is tempting. A forged `K_PORT` sits at a
position where `K_PORT` is legal; a context-tracking reader sees a valid port
tag in a valid place and mints, correctly, on a claim the sender was not
entitled to make. Nothing on the reading side can tell the difference.

**Both sides tracking is what closes it.** A writer that knows its own structure
can offer a row count only where a row count is due -- and at that position the
reader is expecting a count and will never read those four bytes as a tag. The
forgery has nowhere to land. This is option 2 below, and it pays for itself
twice over: it also catches a guest that declares `wire-vec 3` and emits two,
which nothing catches today.

**DECIDED: option 2, on both sides.** The writer is a streaming encoder that
ENFORCES VALIDITY -- it refuses to write the next thing when the next thing is
not what the format allows there. The reader answers what it found rather than
obeying what it was asked for. Neither side lets the guest assert structure.

What the writer has to refuse, and each of these is a bug it now catches rather
than a byte sequence it emits:

* a value where the format does not expect one -- a second top-level value, or
  anything after the message is complete;
* a count where a value is due, which is the forgery the table walked into;
* a value where a count is due;
* and, at `port/send`, a writer whose structure is UNFINISHED -- `wire-vec 3`
  with two values emitted is a message no reader can read, and today it would
  go out and fail on the far side.

**BUILT (2026-09-15), on all four builds.** `wire-table` opens a frame meaning
"a row count is due, with this many columns" -- negative, so `wire_expect_value`
refuses a value there by the check it already had -- and `wire-table-rows`
requires that frame and replaces it with the cell count. Every refusal above is
asserted in `runtimes/conform/wire.cljc`, and the three runtimes answer
identically. `test/host_abi.mjs` then compares the guest encoder against the
runtime's byte for byte across 35 shapes, so the enforcement demonstrably did
not change the FORMAT -- only who may write it.

The options are kept below for the record.

### The table as a format question (superseded by the section above)

Every shape encodes through structured primitives except one. A table's
encoding (`K_TABLE`) is: the tag, a column count, then a NAME and a TYPE per
column as ordinary values, then a ROW COUNT, then the columns. The row count
sits in the middle, after values, and there is no structured primitive that can
put it there.

**A bare `wire-u32` would be a forgery hole, and this was checked rather than
assumed.** A guest emitting `0x0000000F` writes the bytes `0f 00 00 00`; at a
value position a decoder reads the first as a tag, and `0x0F` is `K_PORT`. The
next four bytes are then read as a port id -- which the guest supplies with a
second bare `u32`. So a primitive that writes four raw bytes at a value
position hands back exactly the capability forgery the opaque writer exists to
prevent. Every other primitive is safe because it writes a COMPLETE,
self-delimiting piece.

Three ways out, none of them free:

1. **Change the table's format** so both counts are in the header
   (`K_TABLE`, ncols, nrows, then the pairs, then the columns). One primitive
   writes the whole header. It costs the property that makes this decision
   checkable -- the wire not changing, so a differential test can compare bytes.
2. **Give the writer a state machine**: `wire-table` records that a row count
   is due after `2 * ncols` values, and `wire-table-rows` is legal only there.
   Principled, and it would catch count mismatches generally, but it is real
   machinery in three runtimes.
3. **Leave the table a leaf**: `wire-table` takes the TABLE and the runtime
   encodes all of it, the way `wire-port` takes a port. Safe and small, and the
   honest cost is that table encoding stays written three times -- which is the
   thing this decision exists to stop.

Unresolved. It blocks deleting the runtime codecs, not the reader, and not the
encoder for every other shape.

### The receiver cannot decode while delivery does

Switching `port/receive` to the flint decoder needs `host_deliver` to stop
decoding, so there are bytes left to hand the receiver. Tried, and it breaks
`bind` then `call` -- for a reason worth writing down, because the obvious
diagnosis is wrong.

**Decoding does not create a port.** `install_bridge_port` creates the
SANDBOX'S HANDLE to a bridge whose identity is the host's id, and it is
idempotent -- an id that arrives twice finds the first handle and takes no
second reference. The bridge is the host's, made when the host made it.

What actually couples them is that **`host_deliver` looks the port up in the
sandbox**:

    pub fn host_deliver(&mut self, host_port_id: i64, bytes: &[u8]) -> bool {
        let host = self.port_by_id(host_port_id);
        if host.is_nil() { return false; }

So a host putting bytes into a bridge IT OWNS is refused unless the sandbox
happens to hold a handle already. With delivery no longer decoding, the bind
that names the call port is queued but not read, the sandbox has no handle, and
the host's next delivery to its own bridge is rejected.

**And that is an artefact of bridges not being standalone yet.** This decision's
neighbour says what a bridge should be -- "a standalone object with its own
memory, owned by neither side, with two ends… bridges are host-side"
(`DECISIONS.md#bridges-are-the-only-door`) -- and that is unbuilt. Today the
queue IS the sandbox-side handle: `PT_INBOX`, `PT_RING`, `PT_READ` and
`PT_WRITE` are slots on the `TY_PORT` in the guest heap. So "has the bridge
somewhere to put bytes" and "has the sandbox a handle" are the same question,
and decode timing answers both.

With a real host-side bridge, delivery queues into the bridge, the sandbox takes
a handle whenever it first names the port, and decode timing has nothing to say
about either. **So the receive switch waits on the bridge work, not on anything
in this decision.**

### A port in flight has no owner, and that is a live bug

**While a port is inside a bridge, the bridge owns a reference to it.** It does
not today, and the hole is demonstrable rather than theoretical.

`encode` writes a port as its tag and its id and nothing more -- bytes hold no
reference. So a sandbox that sends a port and then drops its own handle has left
the port referenced by nobody, and `reap_ports` does what it is supposed to: a
flint end nothing refers to has been closed. The message naming it is still
queued.

Measured. A guest opens `passenger`, sends it through `carrier`, drops it, and
allocates; the host reads the message and asks for the port's state WHILE THE
PROGRAM IS STILL RUNNING:

    passenger opened as 1001
    message names port 1001
    WHILE THE PROGRAM IS STILL RUNNING, port 1001 is: unknown

`unknown` is 255 -- the runtime knows nothing about that id. The receiver has
been handed the name of something that no longer exists.

**It also explains the delivery-time decode.** In the host-to-guest direction
the hole is closed by accident: delivery decodes, which installs the handle
immediately, which is a reference. That is why deferring the decode has to come
WITH this fix rather than before it -- otherwise the same hole opens in the
direction that currently works.

So the rule is: a port encoded INTO a bridge is retained by that bridge, and
released when the message carrying it is read, or when the bridge dies. Which
needs a bridge that can hold a reference -- a standalone object with its own
memory (`DECISIONS.md#bridges-are-the-only-door`), not a queue living inside one
side's handle.

### Writing is a lease too: stream INTO the bridge

Encoding into a sandbox-side writer and then copying the bytes into the bridge
does the work twice. The symmetric design does not: **a write lease gives a
place in the bridge's memory and a message writer over it, and the message is
`submit`ted or `cancel`led.** The writer enforces the format, as above -- it is
the same state machine, owned by the lease instead of by a sandbox-side object.

**Back-pressure moves to the lease, which is where it belongs.** `port/send`
today encodes the whole value and THEN discovers the byte bound is full; the
work is already spent. A lease that cannot be granted says so before a byte is
encoded.

**Cancel becomes necessary rather than optional.** A sandbox-side encode that
throws half way costs nothing -- the bytes were scratch. Writing into the
bridge means a half-written message is in somebody else's memory, so an encode
that fails must be able to take it back.

Four things the design had to answer, and all four are now answered
(2026-09-15).

1. **The size is not known when streaming.** A lease cannot reserve `n` bytes up
   front. Either the region is chunked and the chunks are chained at submit, or
   a lease claims another chunk when it fills. Chunking is what lock-free MPSC
   byte queues do and it keeps the claim a single compare-and-swap.

   **DECIDED: chunked.** And the reason it costs nothing is the one that makes
   it obvious in hindsight: *both encoding and decoding are streaming, so
   nothing needs random access.* A chunk boundary is only a problem for a
   reader that seeks -- and neither side does. The writer appends; the reader
   walks forward and never looks back. So the chain is followed rather than
   indexed, and the contiguous-region version buys nothing for its extra copy.
2. **A port written into an uncommitted message is retained at WRITE, not at
   submit.** Retaining at submit would mean re-walking the message to find the
   ports, which is exactly what streaming avoids. So the lease records what it
   retained and releases it on cancel -- the mirror of the read lease releasing
   on ack. **DECIDED: retain at write.**
3. **Death must release write leases**, and this is worse than the read side: an
   unsubmitted lease pins a region of the bridge AND the ports it retained, and
   there is no message anyone will ever read to notice. It rides the same
   teardown walk as the read leases. **DECIDED: death releases leases**, both
   directions, one walk.
4. **No contention on the write path**, which the ring already demonstrates:
   `PT_INBOX` says "claiming a slot and filling it are a single compare-and-swap"
   and it is written that way because the read-modify-write version lost half
   the traffic -- "4 000 sent, 2 000 received" with two executors on one channel.
   The pattern and the lesson are both in the tree; what is new is a
   variable-length region rather than a fixed slot. **DECIDED: minimise
   contention** -- the claim stays a single compare-and-swap, which chunking is
   what makes possible.

**The guest never touches the bytes**, and that has to stay true. "The sandbox
writes into the bridge" means the RUNTIME writes on its behalf through the
primitives -- which matters most on wasm, where the bridge's memory is not in
the guest's linear memory at all. The region bounds are enforced runtime-side
and are never read off the lease handle the guest holds.

### Minting at entry is the first increment, and the weak table forces its shape

Probed rather than assumed (2026-09-15), because the recorded blocker deserved
checking at the source:

`Host::caller` delivers `{:op :bind :port p}` to the system port and
DELIBERATELY DOES NOT RESUME -- its own comment says so, because a port queues
and the call can wait behind the bind. `Host::call` then delivers on `p`
immediately, and `host_deliver` begins with `port_by_id`. So the sandbox-side
object for `p` must exist before any guest code has run at all.

**Today it exists only because delivery DECODED the bind**, and decoding a
`K_PORT` minted the handle. That is the coupling stated as a fact: port
creation is a side effect of parsing a message that happens to mention the
port. Nothing else creates it.

**And the intern table is WEAK on purpose.** `register_port` says it plainly --
"the scheduler keeps ids, not references: a strong list here would pin every
port for ever and there would be nothing to notice." So minting at entry is not
sufficient by itself: a handle nobody holds is collectable before the guest
reads the message it arrived in. That is the same "a port in flight has no
owner" bug measured earlier in this section, approached from the other side.

**So the first increment is forced, and it is exactly the decided design:**

1. `host_deliver` SCANS the encoding for ports and mints their handles, rather
   than decoding it into a value. The scan builds nothing; it walks the
   structure, which also keeps today's refusal of a malformed message at the
   boundary.
2. The queue entry carries `[len bytes ports]` -- the bridge HOLDING THE REFS
   for everything inside the message, which is "retain at write" in its
   smallest possible form and the thing the weak table makes mandatory.
3. `port/receive` on a bridge answers a LIVE reader, and `flint.port/receive`
   decodes it with `flint.wire/read-from`.

What this increment does NOT do is leases: the refs are released when the
message is dequeued rather than when the receiver has finished hydrating it, so
a sandbox that takes a message and dies mid-decode drops them early. That is
the two-phase work below, and it is the next increment rather than this one.

**BUILT (2026-09-15).** `port/receive` decodes in flint on a bridge. The walk
is `kin/wirescan.kin` -- generated into all three runtimes rather than written
three times, with `kin/wirescan.drivers` running it on each target against a
derived answer. The per-runtime part is the `byte[]`-shaped door to it and the
mint, which is the only step that differs.

**What it cost, measured.** 20 622 bytes on a pure module, 4.2%: 493 021 with
the runtime decode against 513 643 with the guest one, by reverting the one
line and rebuilding the units. `test/threads.clj`'s budget is raised to 525 000
and says so. The raise is expected back when the runtime codecs go, since the
runtime is linked INTO a wasm module and a dead encoder there is dead weight
here.

**Two things the drivers file caught that nothing else would have.** The
`--expect` answer is derived rather than recorded, and the derivation was wrong
twice: a truncated `K_INT` leaves TWO bytes, not zero, because the tag is
consumed before its payload is checked. All three targets agreed on the right
answer while the expectation was wrong -- which is exactly the case a
cross-target comparison cannot report, and the reason kin demands an expected
value as well as agreement.

### Consuming a message is two phase

The bridge cannot release its reference when the message is HANDED OVER, only
when the receiver has finished hydrating it. So consumption is two steps:

1. **take** -- the receiver gets the message and the bridge keeps its refs;
2. **ack** -- the receiver has hydrated everything, and the bridge lets go.

**The order is what makes it safe.** Every port in the message gains a sandbox
handle DURING hydration, which is before the ack, so the bridge's reference is
still held while the sandbox's is being taken. The count never dips through
zero. Releasing first and hydrating after is the version that has the race, and
it is the obvious way to write it.

**This got harder, not easier, when the decoder moved into flint.** Hydration
used to be one runtime step, atomic by construction. It is now guest code: it
can be preempted mid-message, it can park, and it can throw half way through a
value. The window between take and ack is unbounded and has arbitrary guest code
in it, which is exactly why the ack has to be explicit rather than implied by
the read returning.

**An ack that never comes is a leak**, and that is the failure mode to design
for rather than discover. A receiver that takes a message and then throws --
malformed bytes, an unknown tag, a budget that runs out mid-decode -- must still
release the bridge. Two things can guarantee it, and they compose: `receive`
acks in a `finally` so the ordinary throw is covered, and the READER ITSELF
holds the lock so that a reader nobody finished with releases when it is
collected. The reader is the right holder because its lifetime is exactly the
hydration.

**Back-pressure moves with it.** `port_receive` refunds `PT_BYTES` on dequeue
today. With two phases the refund belongs at the ACK, not the take: a message
that has been taken but not hydrated is still occupying the bridge, and freeing
its bytes early would let a sender past a bound that has not actually been
released.

**A take is a LEASE, and a lease must not block the queue.** Acking message two
cannot be a precondition for taking message three -- a receiver that read ahead,
or that hydrates several messages concurrently on different threads, would
deadlock against its own back-pressure. So leases are per MESSAGE and are acked
in whatever order they finish, which means the bridge needs a per-message
lifetime and not a head pointer: a message is removed from the bridge's heap
when ITS lease is acked, whether or not older ones have been.

**The unacked backlog is bounded, and by a bound that already exists.** With the
refund at the ack, a message taken and never acked goes on counting against
`PT_CAP`, so a receiver that leaks leases stops being able to receive and
senders meet back-pressure. That is the right failure: it is visible, it is the
mechanism the system already has for "this end is not keeping up", and it turns
what would otherwise be unbounded growth into a stall. Worth stating plainly
because "not acking leaks memory" suggests something unbounded, and inside one
sandbox's lifetime it is not.

**Across a sandbox's lifetime it IS unbounded, and that is the case to design
for.** A sandbox that dies holding leases must release them, or the bridge never
frees those messages AND every port named inside them stays alive -- the refs
the bridge holds on the sandbox's behalf outlive the sandbox that asked for
them. Nothing collects that: the bridge is host-side and cannot see the guest's
heap die.

So a lease needs two releases, and they compose the way the two ack guarantees
do. Inside the sandbox the READER holds it, so a reader nobody finished with
releases when it is collected. Across the boundary it rides the teardown that
already exists -- `close_all_bridges` walks `SC_PORTS` and releases every bridge
end at exit, and the host's own `holders` table is what learns of it. Leases
belong on that same walk, because it is the one path that runs whether the
sandbox ended tidily or not.

**And a parked decoder pins ports**, which is correct and worth saying out loud:
a call thread that takes a message and parks mid-decode holds the bridge's refs
until it finishes. That is the price of the guarantee, not a defect.

### The send switch is made (2026-09-15)

`port/send` encodes with `flint.wire` on a bridge. The runtime's encoder is
still there and `port-send` still accepts a value, but nothing in `lib/` reaches
it: what goes to a bridge is a finished writer, and the runtime's part is to
check it is complete and take its bytes.

Two things had to be true first, and both are now:

**The table.** The encoder could not encode one (`this cannot cross a boundary:
:table`), so switching would have turned every table sent over a bridge into a
throw. `wire-table` and `wire-table-rows` exist on all four builds, the row
count is legal only where a count is due, and `test/host_abi.mjs` compares the
two encoders byte for byte across 35 shapes -- three of them tables.

**A hole the switch would have opened, found by probing for it.** The rule that
a CHANNEL end cannot be sent to the host lives in `check_sendable_via`, which
runs on a VALUE. A guest-encoded message is not a value by the time it reaches
`send`, so the check never sees it: `(p/send bridge (wire/encode channel-end))`
wrote the id of an object the host was never told about into a message the host
reads, which is the integer-to-port conversion the whole design exists to
prevent. It was written as a failing test first and it failed -- `value=refused,
encoded=NO THROW` -- so the fix could be checked rather than assumed.

`wire-port` now refuses a port that does not cross a heap, on all three
runtimes, and the test asserts the other half too: a BRIDGE port still encodes,
or the guard would have taken delegation with it.

**The lesson generalises past this switch.** A check written against values does
not cover the path that bypasses values. Moving who does the encoding moved
where the rule has to be stated, and only the probe said so.

### The codec's state machine belongs in kin, and was written three times first

**The wire writer and reader were hand-written in Rust, Java and C#.** They
should have been one `kin/wire.kin` from the start, and they are now.

This was not a close call and the tree already said so. `kin` generates 90
modules into each of the three runtimes; the convention every other builtin
follows is that the BODY is generated and only the registration -- the `def`
line, the argument type check, the throw by name -- is written per runtime.
`flint.system`'s own docstring makes the same argument one level up: "the
alternative was the same protocol hand-written in `conc.rs`, `Conc.java` and
`Conc.cs`, three copies kept in step by care."

**The cost was paid before it was noticed.** The marker bug in the section
below was found once and repaired three times, by hand, and what made those
three repairs agree was attention rather than a generator. `kin/tableref.kin`
already carries the same lesson from a previous round: "Rust answered `-1` in
an `i64` and both ports `-1` in an `int`; that happened to agree, but only
because the type was signed." A sentinel that agrees across three languages by
luck is exactly what was shipped and then repaired here.

**What moved and what did not.** `kin/wire.kin` holds the frames, the marker,
the cell bound, the completion rule and the reader's cursor. What stays in each
runtime is the slot LAYOUT and the byte-payload helpers -- appending a `&[u8]`,
a `byte[]` or a `byte[]` is not a body three languages can share, and those are
thin enough to read side by side.

**Three things the port surfaced immediately**, each of which had been a
divergence waiting to happen:

* `local` DECLARES and does not initialise; the three-argument form was
  accepted and the initialiser silently dropped, which emitted a read of an
  uninitialised variable. Rust refused to compile it. Neither port would have.
* `I32` is UNSIGNED in kin, so `wire-peek` could not answer `-1` for "past the
  end" -- which is the `tableref.kin` lesson arriving again, and this time
  before it shipped. It takes a default instead.
* Rust rejects `set-r(ni, vec-conj(...))` as a second mutable borrow, so every
  allocating call is hoisted into a `let`. The generator does not hide a
  language's rules; it makes all three obey the one source that satisfies them.

**And then it found a live one.** kin's `fixnum` ZERO-EXTENDS on the ports --
correct, because its argument is an `I32`, which Java and C# spell as a signed
`int`. The frames are `I64`, and this was the first source to pass a wide
NEGATIVE value through it. Rust kept the sign and both ports masked it to 32
bits, so `-2` became `4294967294`: the marker existed on one runtime and not on
the other two, a well-formed table was refused and a VALUE was accepted where a
count was due. The safety property the frames exist for, absent on two runtimes
out of three, and `runtimes/conform/wire.cljc` caught it on the first run after
the port. `fixnum64` is the form that keeps the sign and the width.

That is the argument for the generator stated as an event rather than a
principle: the same mistake in three hand-written copies is three mistakes to
find, and each of them can be made differently.

**And it is checked the way kin checks everything else.** `kin/wirecore.drivers`
compiles and RUNS the generated module on all three targets against a toy heap
and compares the output, which is the half that drift-checking cannot do: three
identically-wrong implementations agree. The `--expect` answer is derived from
the format rather than recorded from a run, and one of its six fields is the
marker's sign read straight out of a frame -- `-4` for a table of three
columns, a value that cannot be confused with `4294967292` by any formatting
difference.

`bin/check-kin` is green at 92 sources, every one against a written-down
expected answer.

### The marker was reachable by arithmetic, and that was the hole

Enforcement bought with a new representation has to be checked against that
representation's edges, and this one was not. Writing it down because the shape
generalises: **the frames are fixnums, and a fixnum is 48 bits, sign-extended.**

The table's marker -- "a row count is due here, not a value" -- is a NEGATIVE
frame. So the question nobody asked was whether an ordinary count could become
one. It could:

    (-> (wire-writer) (wire-vec (+ 140737488355328 1))   ; 2^47 + 1
        (wire-table-rows 15))                            ; accepted

`2^47 + 1` is written to the wire as its low four bytes -- `1` -- so a reader is
told to expect ONE value inside that vector. The frame, read back sign-extended,
is negative: the writer believes a count is due. `wire-table-rows 15` then puts
`0f 00 00 00` exactly where the reader will look for a tag, and `0f` is
`K_PORT`. A capability minted from an integer, through the machinery added to
make that impossible.

Measured, not reasoned: the row above was accepted while `(wire-int 1)` in the
same position was REFUSED, which is the writer saying in so many words that it
was in the marker state.

**The fix is a bound that was always implied: `u32`.** Every count in the format
is four little-endian bytes, so a count past `u32::MAX` was already writing
something other than what it opened -- the bytes and the writer's own idea of
the message disagreeing, which is the one thing the frames exist to prevent.
With counts bounded there, the largest legitimate frame is about `2^33`, nothing
sign-extends, and negative means marker and nothing else. The table's
`ncols * nrows` is the one frame built by multiplying, so it is checked against
a cell bound as well.

**What this says about the earlier decision.** "Both sides track structure" is
still right, and the enforcement it bought is real. What was missing is that
introducing a sentinel VALUE creates an obligation to prove nothing else can
produce it -- and `-1` was chosen for being obviously distinguishable from a
count, which it is mathematically and was not in 48 bits.

The exploit is kept as rows in `runtimes/conform/wire.cljc`, refusing
identically on all four builds, so no runtime can regress it quietly.

### The test helper deadlocked, and it read as a slow gate

Not codec work, recorded here because it is what three firings of this section
actually spent their time on and the misdiagnosis was mine.

`bin/test` stopped producing output at `== ropes: ...` and sat there. The
reading was "ropes rebuilds the units twice, so it is slow" -- plausible, since
`test/ropes.clj` does exactly that, and wrong. Measured instead of assumed: the
`cargo` under it had used **0.02 seconds of CPU in 42 minutes**, which is not a
slow build, it is a stopped one.

**The helper reads stdout to completion and stderr afterwards.**

    out (slurp (.getInputStream p)) err (slurp (.getErrorStream p))

A child that writes more than a pipe buffer to stderr blocks writing it; the
parent is still blocked reading stdout; neither moves again.
`bin/build-units --diagnostics` emits **71 266 bytes** of cargo warnings
against a 64 KB buffer. It is not marginal and it is not intermittent -- past
that threshold it hangs every time.

The volume is not new work's fault: 243 warnings, none of them from
`kin/wirecore.kin` or `kin/wirescan.kin`. The shape has been latent in six test
files and something ordinary pushed it over.

**Fixed by draining stderr on its own thread**, in all six. `ropes` now
finishes in under five minutes.

**Two lessons worth keeping.** A plausible explanation for silence is not a
diagnosis -- CPU time would have said "stopped" in one command, at any point
across three firings. And a hang that looks like slowness is the expensive
kind: it was blamed on the gate, then on interrupting the gate, and each wrong
answer cost a full run.

### The ports could not send on a bridge at all, and `bin/test` cannot see it

**A regression this section introduced at step 2, found five steps later.**
When `port/send` switched to `flint.wire`, `lib/flint/port.cljc` began handing
`flint/port-send` a WRITER on a bridge. Native grew a branch for that. The JVM
and the CLR did not: their `send` called `Codec.encode` on the writer
unconditionally, which refused it, so **every bridge send on both ports threw**
from that moment.

**What made it invisible for so long.** `bin/test` did not run
`bin/conform-hosts` THEN, and the host-port drivers each print their own
transcript and call every line `ok` -- 16 ok, 0 fail, while sending nothing.
Only `bin/conform-hosts` compares the transcripts, with `cmp -s`, and that
gate was itself blocked earlier by an older `gas differs by 1536`.

*Both halves of that are past tense now, checked 2026-09-23.* `bin/test:705`
runs `./bin/conform-hosts` and fails the suite on a nonzero exit, inside the
`hosts` section; and the `1536` that blocked it is gone -- the row reads `the
same program costs the same gas, to the instruction: 143035`, and the gate ran
365 rows with zero failures. The sentence is kept because the SHAPE is the
lesson and it is not dated: a driver that prints its own transcript and calls
every line `ok` cannot fail, and only something that COMPARES two transcripts
can. So a total failure of
the feature on two runtimes out of three sat behind a green-looking driver and
a gate that stops before reaching it.

It surfaced only because `port_open` needed the same treatment, which sent me
to read `Conc.java`'s `send` and find it had no writer branch to read.

**Fixed in both**: a writer is not walked by `checkSendable` (there is nothing
in it to check, and the check would refuse a type it does not know), a channel
refuses one by name, and a bridge requires one. `message(500,8,"ack")` now
appears in all three transcripts.

**The coverage gap is the real finding.** A driver that reports `ok` for
whatever happened is not a test; it is a transcript. The assertion lives in a
gate that does not run in `bin/test`, so anything it would catch can rot for as
long as that gate stays red for another reason.

**Still diverging, and NOT the receive switch**: native ends the fixture parked
(`status 2`, port `half-closed`) where both ports finish (`status 0`,
`closed`), and the open token is 1 against 2. The two ports agree with each
other.

Tested rather than assumed: reverting `flint.port/receive` to the runtime
decode, rebuilding the units and the dist, and re-running leaves native parked
exactly as before. So the guest-side decode is not the cause. All three
runtimes implement `hostClosePort` identically -- P_HALF if P_OPEN -- so the
state difference is a CONSEQUENCE of native's program not finishing, not a
separate bug.

**And this blocks the last migration.** `host_request`/`host_answer` are the
only guest-facing codec uses left, and they should not be moved without
coverage -- the ports' `send` regression above is exactly what happens when a
runtime path is changed with nothing watching it. The natural place for that
coverage is the host-port fixture, which all three drivers must reproduce
line for line. It cannot be extended while its transcripts already disagree
for a reason nobody has attributed.

**Attributed as far as it can be without a HEAD bootstrap**, by elimination and
by mechanism:

* NOT the receive switch. Reverted it, rebuilt units and dist, re-ran: native
  parks exactly as before.
* NOT the `flint.system/boot` shake root. Reverted that hunk, rebuilt dist and
  the fixture image, re-ran: unchanged.
* NOT the driver under-pumping. Native's driver gives up after four resumes;
  raised to sixteen and native still reports `status 2`.

So a thread really is parked for ever, and it is the SYSTEM thread: the control
plane ships in every module now, so every program has one, and it parks on a
system port the host never closes. The runtimes disagreed about what "done"
means with such a thread parked -- and **native was right, provably, from its
own source.**

`drive` asks two questions when nothing is runnable: has the entry finished,
and does anything need the host? NATIVE ASKS THE HOST FIRST, and its comment
says why it was changed to:

> Asking `main_finished` first here closed the system port out from under a
> call that had just parked on an `open` -- the grant then arrived for a port
> that was already gone, and the guest saw its own capability refused.

**Both ports still asked `mainFinished` first, carrying the superseded comment
verbatim** -- the one native's comment quotes as the thing it replaced. So the
fix was never propagated, and the two runtimes have disagreed about when a
program is over ever since.

`Host::call` settles which is right independently: it treats `code != 2` as
"the sandbox stopped before answering" and returns an error. A live sandbox
with its control plane parked MUST report 2, or every call into a JVM-hosted
sandbox reads as a dead one.

**Reordered in both ports.** `needsHost` is narrow on purpose -- a thread
parked on a CHANNEL does not count, because no host can help it, only a bridge
does -- so the change keeps a program alive exactly when the host might still
act. The host-port transcript went from eight divergent lines to two, and
`green`, `threads`, `wire`, `tables` and `collections` all still agree across
the three runtimes.

**Of the two lines that still differed, one was the drivers and is fixed.**
Native prints `out.out` -- the answer as a string, empty when a program has not
answered -- where both ports rendered the NIL as `nil`. Two renderings of the
same "no answer", differing on a line where nothing had diverged. The ports now
print nothing for an absent answer, as native does.

**The last line is a real ordering divergence, and it is now measured rather
than guessed.** Instrumenting waiter allocation in both runtimes:

    native   [waiter] kind=1 idx=0   <- the entry's OPEN is first
             [waiter] kind=3 idx=1   <- then the control plane parks
    jvm      [waiter] kind=3 idx=0   <- the control plane parks FIRST
             [waiter] kind=1 idx=1   <- then the entry's open

So the open token reads 1 on native and 2 on the ports, because **the control
plane's first run is ordered differently relative to the entry.** Native runs
the entry on the live stack until it parks on `open`, and only then does
`drive` boot the control plane; the ports let the control plane run first.

That is a scheduler question -- when the control plane first runs -- and
changing it touches the same ordering native's `drive` comment says was already
got wrong once. It deserves its own decision rather than being tacked onto the
codec migration, and it is the ONLY line still separating the three
transcripts.

> **REPRODUCED 2026-09-19**, independently and from scratch, by instrumenting
> both generated `new_waiter`s: the table above comes out identical, kinds and
> indices included. Still the only line separating the three transcripts.
>
> **And one candidate cause is now RULED OUT.** The two harnesses differ in
> when they run initialisers -- `RtHostPorts.run` calls `ensureStarted` before
> allocating the entry's arguments, `Program::run_with` after -- which looks
> like the right shape for an ordering difference. It is not the cause: forcing
> initialisers early on native (a temporary `ensure_started_probe`, since
> reverted) leaves the order and the token unchanged.
>
> **ROOT CAUSE FOUND, 2026-09-19, and it is a gas divergence rather than a
> scheduler one.** Probed at the entry call on both sides, same image:
>
>     native   entry-call: steps=  226  slice_end=4096   -> under the boundary
>     jvm      entry-call: steps= 8135  sliceEnd =4096   -> ALREADY PAST it
>
> `slice_end` is 4096 on both -- it was armed when the scheduler came into
> existence, at `installSystemPort`, while `steps` was near zero. By the time
> the entry runs, the jvm has spent 8 135 steps and the saved slice is long
> gone, so the entry trips on its FIRST tick (steps unchanged at 8 135),
> yields, and the scheduler boots the control plane, which parks. Native is at
> 226, still under 4 096, so its entry runs through to `open`. That is the
> whole of the inversion.
>
> **Why the step counts differ by 36x for the same work.** Native disarms the
> slice around the initialiser loop -- `set_slice_end(0)` -- and on this
> runtime that switches the COUNTER off as well, because `checkpoint` becomes
> `u64::MAX`, `counting()` goes false, and `run` dispatches to `NoBudget`,
> whose `tick` is a constant the optimiser deletes. Measured directly:
>
>     PROBE pre-init:       steps=127  counting=true
>     PROBE init-disarmed:             counting=false
>     PROBE entry-call:     steps=226
>
> The jvm has no such dispatch. Its hot loop does `steps++` unconditionally
> (`Rt.java:976`), so its initialisers are billed and native's are not.
>
> **So this row is not independent of `resource-limits` after all.** It is the
> same question -- what counts as billable, and whether the two runtimes agree
> -- surfacing as a token number instead of a gas number. Initialisers are free
> on native and billed on the ports; that is a divergence in its own right,
> and the open token is a symptom of it.
>
> RULED OUT along the way: initialiser TIMING (forcing them early on native
> changes nothing) and slice ARMING as such (native re-arms the slice for the
> entry too -- `vm.rs` says so and the probe confirms `slice_end=4096`). It is
> not whether a slice is armed; it is how far `steps` has travelled by then.

So the order is: settle what completion means with a parked system thread, then
add a request exchange to the fixture and the three drivers, then move the
pair, then delete and take the 27 082 bytes.

### AND IT TRUNCATES THE GATE, which nobody noticed for as long as it has been red

Found 2026-09-17, and it changes what the row below MEANS.

`bin/test` fails fast -- `fail()` ends with `exit 1` -- and the `colls` row
below is inside `test/aot.clj`. So the run stops there. **`bin/test` declares
62 sections and 22 of them run.** Forty never execute, among them:

* `distributable` -- which carries THE ONLY COMPARISON OF THE TWO FRONT ENDS'
  OUTPUT, the byte-identical rows whose own comment says "nothing ran it until
  now";
* `system`, `io`, `bytes`, `shake`, `dist`, `binary`, `llvm`, `parallel`,
  `hosts`, `global ports`.

**It reads as a baseline and it is not one.** "22 sections, 1 known failure"
is the phrase this failure has been reported under for many sessions,
including in the standing instructions an agent works from -- and it sounds
like a full run with one red row. It is a run that stopped a third of the way
through. Every claim of the form "the gate is at its known baseline" made
while this was true was weaker than it sounded, including several of mine.

**Made visible rather than worked around.** `fail` now says how many sections
ran of how many exist and that the remainder never executed, and the timing
summary prints the same count on a clean run. That does not fix the coverage;
it stops the absence from looking like a pass.

**What it needs from you** is the policy, which is not an agent's to pick: a
suite that stops at the first failure is a defensible choice and so is one
that runs everything and reports every red. While `colls` is red and the
policy is fail-fast, forty sections are dark -- so the two questions are the
same question, and fixing `colls` closes both.

### AND THE DARK SECTIONS WERE HIDING A SECOND RED

Measured, not assumed: a copy of `bin/test` that continues past the `colls`
row reaches four more sections and then fails on `test/types.mjs`.

    FAIL an annotation inside an `and` guard costs exactly nothing
         annotated 54,625, unannotated 54,627 -- a difference means the
         projection did not survive the let that `and` expands to

**Reproduced on its own, under the production units**, so it is not an
artefact of running out of order. Two instructions on a 54 000-step program,
where the test demands exactly zero -- it is an analyzer question (does an
annotation's projection survive the `let` that `and` expands to?) and not a
runtime one, and the test compares two arms of the SAME program so a runtime
change cancels in it.

**Not attributable to any recent work on the evidence available**, and it
wants its own look. What matters here is that it was invisible: the gate has
never reached the section it lives in.

**The enumeration stops there**, because finding the next one needs the same
keep-going mode and that is the policy question above. Two reds are known;
how many more sit in the remaining thirty-six sections is not knowable until
the suite is allowed to finish. A first probe of this reported a THIRD failure
(`test/common --aot`) that turned out to be the probe's own doing -- the
`colls` failure path restores the production units, and the next section needs
the AOT ones -- which is worth recording as the shape of the trap: a suite
that fails fast also CLEANS UP fast, so simply not exiting changes what the
later sections run against.

### THE DARK REGION, SURVEYED 2026-09-17 -- it is not one red row

`bin/test` now honours `FLINT_TEST_KEEP_GOING=1`: opt-in, default unchanged
and verified unchanged (a plain run still stops at 22 of 62 and says so). It
exists because a suite that cannot be finished cannot be surveyed, and forty
sections had never run.

**Finished, it reports EIGHTEEN red sections.** That number is not the finding;
what survives verification is.

**Verified real**, re-run standalone under the units their section uses:

    test/tables.clj    FAIL a constant column costs nothing per row
    test/limits.clj    FAIL ... carries spent, limit and thread as data
                            {:spent 1000000, :limit 1000000, :thread 2}
    test/snapshot.clj  FAIL ... the snapshot taken while they were parked
                            carries them -- found 1 THREAD objects
    test/document.clj  FAIL ... peak memory stayed a fraction of the ask
                       FAIL ... not one stale pointer was written

**Verified NOT real**: `test/manifest.clj` passes standalone. It is a cascade
-- something earlier left state it did not expect -- so the raw eighteen
includes at least one phantom and the rest are unverified either way.

**THE TRAP IN VERIFYING THEM**, which cost a wrong answer before it was
noticed: a section's test must be re-run under the UNITS THAT SECTION BUILDS.
`test/tables.clj` calls `stat_bytes_allocated`, which is
`#[cfg(feature = "diagnostics")]`, and its section runs
`./bin/build-units --diagnostics` first. Run against production units it dies
with "stat_bytes_allocated is not a function", which reads exactly like a
missing export in `link.cljc` -- and was nearly recorded as one.

**Two of these are in code this session changed, and are NOT attributed.**
`snapshot` is about parked threads carrying their saved stacks and frame
records; `limits` reports `:thread 2` for a gas error. Both sit on the
scheduler and thread machinery the `Conc` port moved into `kin/sched.kin`, and
neither has been bisected against a baseline. They may be regressions from that
work, they may be older than it, and saying which needs a run from before it --
which nothing in the tree can currently produce, because the gate has not
finished in long enough that no green baseline for these sections exists.

**What this changes about every "the gate is at its known baseline" claim**
made in this file and in the standing instructions: the baseline covered 22
sections. The other forty were never a baseline at all.

### The `colls` gap, narrowed 2026-09-17 -- and three of the old guesses are wrong

Chased because it is the FIRST red and `fail` exits, so it is what keeps forty
sections dark. Not fixed. What follows is what a session of bisection
established, so the next attempt does not re-walk it.

**FIRST, THE RECORD POINTED AT THE WRONG FILE.** The note below reasons about
"`colls` is `(ns colls)` with no requires", which reads like
`bench/progs/colls.cljc`. It is not: `test/aot.clj` WRITES ITS OWN five-line
`colls` into a temp directory. The real `bench/progs/colls.cljc`, built and run
the same way, shows a gap of ZERO. The reproducer is:

    (ns colls)
    (defn main [_]
      (let [m (reduce (fn [m i] (assoc m i (* i i))) {} (range 500))
            v (reduce conj [] (range 500))]
        (str [(count m) (get m 30) (reduce + 0 v) (peek v)])))

**The "what is special about colls" hypothesis does not survive.** `assoc` on a
map, `conj` on a vector, `peek`, and `reduce +` were each built as a
single-operation program, interpreted against compiled: **gap 0 for every one
of them.** Whatever this is, it is not one of those operations.

**IT IS DATA-DEPENDENT, AND NOT MONOTONIC.** The same source at different
collection sizes:

    n = 10, 50, 100, 150, 200, 250, 300, 400   gap 0
    n = 500    gap 46
    n = 1000   gap 2
    n = 2000   gap 48

A bug in how an OPERATION is charged would scale with the operation count.
This does not.

**IT IS ALSO LAYOUT-FRAGILE.** Adding one unrelated `flint.rt/gc-stats` call to
the same program makes the counts agree exactly. So the trigger is the emitted
LAYOUT -- where the chunk boundaries happen to fall -- and not the program's
meaning.

**Two mechanisms ruled out by measurement, not by argument:**

* **NOT the collector.** `flint.rt/gc-stats` reports zero minors and zero
  majors on both builds of the reproducer. Nothing collected.
* **NOT the allocation charge.** The compiled build does allocate less -- 568
  bytes on the reproducer -- and that looks like the answer until you measure
  a program that PASSES: `arith` allocates 1 008 bytes less under AOT and
  `hof` 264 less, and both have step counts identical to the instruction.
  So an allocation difference between the two builds is normal and does not
  move gas; it is unbilled allocation.

**Where that leaves it.** `chunks` charges `(count is)`, minus one when the
chunk hands its last instruction back for the interpreter to run -- so every
instruction should be ticked exactly once, by the chunk or by the interpreter.
Removing that decrement was tried: the compiled side then overshoots by 17 230
on one probe, so the hand-back is load-bearing and broadly right. The gap is a
narrow case where an instruction is ticked by NEITHER -- layout-dependent,
which is why it appears and disappears with unrelated edits.

### Narrowed again 2026-09-17, with the emitter's OWN handles

The previous entry called for instrumentation. It already exists: `link.cljc`
reads `FLINT_AOT_LIMIT`, `FLINT_AOT_ONLY`, `FLINT_AOT_PICK`, `FLINT_AOT_FROM`,
`FLINT_AOT_SKIP_FROM`/`_TO`, `FLINT_AOT_NAME`, `FLINT_AOT_NAMES` and
`FLINT_AOT_CHUNK_ALL`. A whole bisection harness, and two sessions of guessing
happened beside it.

**WHICH HALF: opcode emission, not chunk boundaries.** `FLINT_AOT_CHUNK_ALL=1`
makes every instruction its own chunk, and its docstring says what the answer
means: "if a failure survives maximal chunking then no boundary was missing and
the fault is in how an opcode is EMITTED". **The gap survives it, at the same
46.** So the fault is in an opcode's emitted form, and boundary placement is
exonerated.

**A THREE-FUNCTION REPRODUCER**, found by delta-debugging the arity set (34
probes, from `{0..129}` down):

    FLINT_AOT_PICK=118,120,129     gap 2

    118 = reduce-seq  arity 0  (off 1733 len 75 argc 3)
    120 = reduced?    arity 0  (off 1817 len 28 argc 1)
    129 = conj        arity 1  (off 2072 len  9 argc 2)

**Every PAIR of those three is clean**; all three together lose 2 steps. The
full 46 is roughly twenty-three independent instances of the same thing, which
is why bisecting for "the arity at fault" kept landing on interactions -- there
is no single guilty arity.

**It is RARE and data-dependent, even minimised.** The three-function set loses
2 steps at `(range 500)` and ZERO at 50, 200 and 2000. A per-call
mis-accounting would scale with the call count; this does not. Combined with
"the fault is in emission", that points at a BRANCH inside one of the three
that is taken occasionally -- `conj`'s slow path is the obvious candidate at
nine instructions -- rather than at the common path of any of them.

**The three functions, disassembled** (`./bin/flint ... --disasm conj`), so the
next attempt starts from the code rather than from the offsets:

    conj arity 1 (argc 2)        reduced? arity 0 (argc 1)
      2072  local 0                1817  local 0
      2074  local 1                1819  type-p
      2076  native 30 2            1820  set-local 15
      2080  return                 1822  return

    reduce-seq arity 0 (argc 3) -- the loop, abridged
      1745  local 4                1769  call 1        <- reduced?
      1747  jump-if-false -> 1805  1771  jump-if-false -> 1787
      1756  native 3 1             1791  native 4 1
      1760  call 2        <- conj  1799  jump -57 -> 1745   <- BACK-EDGE

`len` in the arity table is BYTES, not instructions: `conj` arity 1 is four
instructions, not nine.

**What the emission does with gas**, read rather than guessed:

* `:native` is in `INLINED`, so a chunk CHARGES for it; the emitted form passes
  the accumulated `GAS` to `aot_native`, which does `rt.steps += gas`, and then
  resets the local to 0.
* `:call` is in `CALLS`, so `handed-back?` is true and the chunk charges
  `count - 1`; `aot_call` adds `gas + 1`, the `+ 1` being the call itself.
* A back-edge flushes the accumulated gas and is where preemption is tested.

Each of those is individually consistent. The loss is in their interaction --
`reduce-seq`'s loop contains both a `call` and a `native` and a back-edge, and
it is the only one of the three with a loop.

**A usable instrument exists and is worth knowing about**: build the units with
BOTH flags -- `./bin/build-units --aot --diagnostics` -- and a compiled module
can then be read with `stat_region(60 + k)` for the `C_*` counters in
`aotstat.rs`. An AOT module refuses to run under non-AOT units, which is why
this combination is the one that works.

**What those counters CANNOT do** is settle this, and that is worth recording
so the next attempt does not spend the time: they count EVENTS, and they count
different events in the two builds -- on the reproducer, `instrs` reads 79
interpreted against 2 128 compiled, `calls` 3 407 against 1 904. Nothing there
decomposes GAS, which is the quantity that differs by 2.

### The gas ledger, built and read 2026-09-17

`C_AOT_GAS` (`aotstat.rs`, index 30) now sums every `gas` value flushed out of
compiled code, across all six helper sites in `aot.rs`. `steps` minus it is
what the interpreter ticked, so a step count DECOMPOSES for the first time.
Diagnostics-only, and kept -- it is the instrument this question needs.

**THE FIRST READING OF IT WAS WRONG, and the export's own comment caused it.**
`stat_region` documents itself as "`60..` the counters". There are FOUR
histograms of twenty in front of them, so `COUNTS[k]` is at `80 + k`;
`stat_region(60 + k)` returns `SEG_HIST[k]`, silently, with numbers that look
entirely plausible. The `gas -203 / +203` figures first recorded here were
segment histogram buckets. The comment is corrected in `abi.rs` and says why.

**Read correctly, the ledger is self-consistent and the bug is elsewhere.**
Interpreted runs are all ticks; a compiled run splits into ticks plus gas, and
the two sum to the same total on every clean size:

    n = 499   interpreted 113 282   compiled 86 582 ticks + 26 700 gas = 113 282
    n = 500   interpreted 113 546   compiled 86 791 ticks + 26 753 gas = 113 544

So the compiled build's own arithmetic adds up. What differs is the COST OF THE
FIVE-HUNDREDTH ELEMENT: the interpreter charges 264 for it and compiled code
charges 262. Every element before it costs both builds the same, which is why
n = 499 agrees to the instruction.

**Per exit, that increment is:** native +30 (6 more calls), return +11 (3),
call +6 (5), tick +6 (2), bail +0 -- summing to the +53 of gas, against +209
ticks. No single door is short; the shortfall is in the total for one
element.

**And a real bug in the diagnostics themselves, found on the way.**
`C_SITES_SEEN` and `C_SITES_POLY` were 26 and 27 -- the indices `C_BAIL_CALLS`
and `C_BAIL_BAD_CALLEE` already held. Both pairs are written, the bail pair from
`aot.rs` and the site pair from `vm.rs`, so each slot read back as the sum of
two unrelated measurements. Moved to 28 and 29. **Any past reading of those four
numbers is suspect**, including whatever the inline-cache question was decided
on.

### What the threshold looks like, and why it resists

* **Razor-thin**: `n = 499` is clean and `n = 500` is not.
* **Needs BOTH collections large**: `m=500 v=200` and `m=200 v=500` are clean;
  `m=500 v=499` and `m=499 v=500` both fail.
* **The size of the gap is unstable**: 0, then 2, then 46 across single-element
  changes of the input.
* **Still no collector involvement**: zero minors and zero majors at every
  failing configuration measured.
* **Observation destroys it**: adding one `flint.rt/gc-stats` call to the
  program makes the counts agree exactly.

That combination -- heap-size sensitive, not GC, and destroyed by any added
instruction -- says the trigger is where the emitted code lands relative to
something size-dependent, and that reading it out of the program will not work.
### FIVE INSTRUCTIONS ARE CHARGED BY NOBODY, 2026-09-17

The per-exit breakdown (`C_GAS_*`, `C_N_*`, indices 31..42) said no single door
is short, so the next instrument was the OPCODE HISTOGRAM -- `OPS`, at
`stat_region(123 + op)` -- diffed between n=499 and n=500 on both builds. `OPS`
is incremented only by the interpreter, so interpreted-minus-compiled is
exactly the work compiled code took over.

**The 500th element, decomposed:**

    instructions the interpreter ran ........ 96   (interpreted build)
    ... still interpreted when compiled ..... 38
    ... moved into compiled code ............ 58

    charged for those 58:
      aot_call's `+ 1`, five calls ..........  5
      chunk-accumulated gas ................. 48
      ------------------------------------------
      total .................................. 53   -- FIVE SHORT

    other charges (allocation and the rest):
      interpreted ........................... 168
      compiled .............................. 171   -- THREE MORE

    264 interpreted against 262 compiled, and the visible gap of 2 is the
    five short offset by the three extra.

**`OPS` is per-instruction and `steps` is not**, which is what makes the two
columns add up: the whole interpreted run ticks 58 245 instructions against
113 546 steps, the rest being allocation and other `charge_work`. Any future
comparison has to keep those apart.

**The fifty-eight, by opcode** (moved = interpreted delta minus compiled
delta): local 20, jump-if-false 7, native 6, set-local 6, call 5, var 3,
return 3, type-p 3, jump 2, false 2, const 1. The five `call`s are the handed-
back ones and are accounted for; the missing five are somewhere in the other
fifty-three, all of which are `INLINED` opcodes that a chunk should have
charged for.

**So the fault is a chunk whose `charge` does not cover every instruction it
runs** -- which is what "the fault is in how an opcode is EMITTED" meant, now
with a number on it. The next step is to make the emitter assert its own
invariant: for each chunk, `charge` plus the hand-backs must equal the
instruction count, checked at emit time rather than inferred from a step
total three layers away.

### An AOT parity failure that is not this section's, reported not absorbed

With the deadlock fixed, `bin/test` reached the end for the first time and
found ONE failure. Recorded here because it is the gate's only red and the next
person to run it deserves to know what was already ruled out.

    aot: compiled arities answer exactly as the interpreter does
      FAIL colls -- the same instruction count
            expected 113542   (interpreted)
            got      113496   (compiled)

Deterministic, and only `colls` -- `arith`, `hof`, `strs` and `handler` all
pass the same check. What is special about `colls` is `assoc` on a map and
`conj`/`peek` on a vector; the others do not touch either.

**Not the codec work.** `colls` is `(ns colls)` with no requires: no ports, no
`flint.wire`, nothing this section changed is reachable from it.

**And not the `flint.system/boot` root**, which was the obvious suspect: the
uncommitted compiler change puts that var in every module's roots, which
changes image composition and therefore which arities AOT selects. Tested by
reverting that hunk, rebuilding `dist`, rebuilding the units `--aot` and
re-running: **the gap is unchanged at 46**. A good hypothesis, measured, and
wrong -- which is the only way to know.

What it takes to classify further is a baseline at HEAD, and that needs a full
bootstrap (`dist/flintc.bytecode` is generated, not checked in, so a fresh
worktree cannot build the CLI without one). Judged disproportionate to do
inline for a failure off this section's path.

**The accounting is deliberate and documented**, which is why 46 is a real
number rather than noise: `test/aot.clj` explains that a compiled chunk's
static gas "INCLUDES the back-edge instruction because compiled code jumps for
itself, and then hands that same instruction back". Something in that
hand-back is off for whichever arity `colls` now has compiled.

### The deletion landed, and the module is smaller than before this started

**DONE (2026-09-16).** Every guest-facing path encodes and decodes in flint.
What remains of the Rust codec is host-facing -- `Program::encode`,
`Program::decode` and the `flint_call` ABI -- and it now sits behind
`cfg(not(target_arch = "wasm32"))`, so a guest module carries none of it.

**Measured, and checked rather than assumed.** 513 643 bytes with the Rust
codec linked against **487 287** without: **26 356 back**, within 700 of the
27 082 the stub experiment predicted. The check is not the number alone --
`strings dist/flint-runtime.wasm` no longer contains `value nested too deeply
to decode`, which is `decode_at`'s and nothing else's.

**The module is now smaller than before the guest codec existed**: 487 287
against the 493 021 it measured while `receive` still used the runtime's
decoder. The migration paid for itself and 5 734 bytes over, and
`test/threads.clj`'s budget goes back from 525 000 to 500 000.

**The cfg is the point, not the byte count.** Relying on the linker to drop an
unreferenced function is a hope; `cfg` is a statement, and it fails the build
the day someone reaches for the runtime codec from guest-facing code again.

**One thing this uncovered.** `bin/build-units` had been FAILING since
`hostreq.rs` was added -- a new `[[bin]]` without `required-features =
["host-tools"]` is compiled for wasm32, where `HOST_CATALOGUE` is configured
out and `std` collides with the runtime's `panic_impl`. It went unseen for two
firings because the invocations were written `> /dev/null 2>&1`. That is the
same silenced-compile trap recorded elsewhere in this file, and the same one
that once made the ports look like they disagreed with native.

### "Delete the runtime codec" is not a step, and the probe says why

The plan carried "delete the three runtime codecs, needs send and receive" as
the last step. Both switched; the codecs are NOT dead. Probed (2026-09-15),
here is everything that still reaches them:

| caller | direction | what it is |
| --- | --- | --- |
| `port_open` | guest → host | the open handshake encodes `[name, ...args]` |
| `host_request` | guest → host | the request/response protocol's arguments |
| `host_answer` | host → guest | the answer to one of those |
| `native.rs::call_on` | host → guest → host | the `flint_call` ABI |
| `Program::encode` / `decode` | host-facing | what an embedder calls |
| `port_send` | guest → host | only the path where a VALUE is passed, which
  `lib/` no longer takes |

**Only two of those are ports at all.** The rest is a different protocol
(request/response), a different ABI (`flint_call`), or the embedding API --
none of which `port/send` and `port/receive` were ever going to touch. The step
was written as though the port codec were the only codec.

**What the end state actually is.** The runtime keeps a codec for the
HOST-FACING surface, which is correct and not a compromise: `Program::encode`
and `Program::decode` are what an embedder calls, and `call_on` is the
`flint_call` ABI, which is host code by definition. What should still move is
the GUEST-facing remainder -- `port_open` and `host_request` encoding, and
`host_answer` decoding -- because the safety argument that moved `send` and
`receive` applies unchanged: an answer can carry an opaque.

So the real remaining step is "move the open and request paths to the guest
codec", and the one after it is "delete what is then unreachable", which will
be less than three whole codecs.

**WHAT THE DELETION IS WORTH, measured (2026-09-15).** Stubbing the last two
guest-facing codec uses so the encoder and decoder are unreachable in a wasm
build and rebuilding the units: **486 561 bytes against 513 643, so 27 082
back**. That is more than the 20 622 the guest codec cost, and it lands the
module BELOW where it started -- 486 561 against the 493 021 it measured with
the runtime decode still in `receive`. The 525 000 budget can go back to 500 000
when this lands, which is the number to check.

**`port_open` IS MOVED (2026-09-15).** `flint.port/open` writes `[name opts]`
with `flint.wire` and hands the writer in; all three runtimes check it is
finished and take its bytes. The wire form is unchanged, which the host-port
conformance shows by still reporting
`open(1,1,["fs" {:capability #opaque["fs" id=7]}])`.

Three things worth keeping about the shape:

* **The name crosses twice** -- once inside the payload, once as an argument --
  and that is deliberate. The runtime needs it for the refusal message, and
  reading it back out of the encoding would mean decoding in the runtime, which
  is what this moved away from.
* **A spent writer is refused by name** rather than sent as nothing. It is not
  a path anyone reaches: `port_open` is re-entrant, running once to ask and
  again when the host answers, and the second pass returns before the payload
  is touched.
* **Two drivers had to learn to encode.** `units-src/flint-conc/tests/hostports.rs`
  drives the builtin directly with no `flint.port/open` to encode for it, so
  its bytecode now writes the payload itself -- which is what a guest encoder
  looks like in assembly, and it chains cleanly because every wire primitive
  takes the writer and answers it.

`host_request` and `host_answer` were NOT moved with them, because no host in
this tree answered an `EV_REQUEST`: `bin/check-builtin-coverage` said as much
about `flint/request`, and neither `cli/src/serve.rs` nor `host/flint.mjs`
handled one. Migrating an unexercised path is changing code with nothing to
catch a mistake -- the ports' `send` regression above is what that looks like.

**So the host was written (2026-09-15).** `runtimes/conform-host/hostreq.cljc`
and `units-src/flint-conc/src/bin/hostreq.rs` are the first host in the tree to
answer one, and the transcript is:

      ok   it asked: ["clock" ["utc"]]
      ok   answered: true
      ...
      ok   requests seen: 4
      ok   the program answered: ["tick" "tick" :refused nil]
      ok   status 0

That last line is the one that matters, and it is what makes the migration
safe: it is the string the GUEST assembled from the answers, so it exercises
`host_answer`'s decode and not merely the host's half. `request` throws on a
refusal and `ask` answers nil, which is the whole difference between them.

**Two things the fixture had to get right.**

`runtimes/conform-host` needed a `deps.edn` granting `:host`, because
`flint.host/request` is guarded and a guard is satisfied by what a workspace
HOLDS, never by what it asserts (`DECISIONS.md#workspace-capabilities`).

And the driver must CLOSE THE SYSTEM PORT before reading the answer. A sandbox
whose control plane is parked is never "done" -- correctly, per `needs_host` --
so the entry's value cannot be read while the host still holds the door. That
is the same condition the host-port transcript shows as `status 2`, arrived at
from the other side.

**And it found a bug on its first run against the JVM.** `RtHostReq.java` is
the same script against the same image, and the transcripts agree on every line
but one:

    native   ok   the program answered: ["tick" "tick" :refused nil]
    jvm      ok   the program answered:

Every exchange matches -- four requests asked, two answered, two refused, same
payloads -- so the HOST half of the JVM's request path is right. What does not
arrive is the answer.

**And it is not the request path at all.** Bisected with the smallest program
that could possibly show it:

    (ns zzprobe)
    (defn main [_] "CONSTANT")

    native   ok   the program answered: CONSTANT
    jvm      ok   the program answered:

No request, no port, no codec. **The JVM loses the ENTRY'S VALUE whenever a
system port is installed before the program runs** -- which every real host
does, because `open` is a request on one.

Instrumenting `settle` names the mechanism: the first settle of that two-line
program is

    [settle] cur=0 parkOn=SET thrown=nil result=nil

`parkOn=SET` for a program that returns a constant and cannot park. Thread 0 is
not the entry on the JVM. The scheduler is created at INSTALL, with no frames
live, so `ensureSched` mints thread 0 as a DONE placeholder; the initialisers
then run on the live stack and claim it, and the entry's value settles
somewhere `mainResult` does not read. Native reads thread 0 too -- its
`settled_answer` is the same shape -- so on native thread 0 really is the
entry.

**FOUND AND FIXED (2026-09-16), and it was neither the codec nor the
bootstrap's thread numbering.** The entry PARKED -- `runProgram` came back
`v=nil parked=true` for a program that returns a constant and cannot park.

Native's `run_program` documents the cause in full, having been bitten by it:

> A slice is armed the moment a scheduler exists, and a scheduler can exist
> before this function is ever entered -- a host that installs a port at
> construction creates one. The loop below then ran namespace initialisers
> under a live slice, and a yield inside one is DISCARDED here ... and never
> recorded the entry's value. The run reported "the entry function did not
> return a string", and it reported it for a program whose entry was
> `(defn main [_] "constant")`.

Both ports ALREADY carry the fix -- `ensureStarted`/`EnsureStarted` disarm the
slice around the initialiser loop. **The conformance DRIVERS bypassed it**,
hand-rolling `started = true` plus a `call` per initialiser, and so ran them
under a live slice. The drivers now call the runtime's one-shot runner, which
is what their own comments claimed they were avoiding for a different reason.

`RtHostPorts`, `RtHostReq` and the CLR's `RunAll` all had it. **The request
transcript now matches native line for line on both ports.**

**A prediction I made and got wrong:** this was recorded as "one root, two
symptoms" with the open-token divergence. It is not -- the token still reads 1
against 2 after the fix. The slice bug and the boot ordering are separate, and
the token remains the single line between the three host-port transcripts.

That is exactly what this coverage was written to catch, and it is the second
time in this section that a runtime path with nothing watching it turned out to
be broken on the ports -- the first was `send`, which had been refusing every
bridge message for five steps.

**So `host_request`/`host_answer` still must not move**, and now for a sharper
reason than "untested": one of the three runtimes demonstrably does not deliver
an answer at all. Migrating the encode and decode underneath that would mix a
real defect with a refactor. Both are now done: the drivers are fixed and the CLR
has a `--rt-hostreq`, so the request path is covered on all three.

**AND THE PAIR IS MOVED (2026-09-16).** `flint.host/request` writes
`[what & args]` with `flint.wire` and reads the answer with
`flint.wire/read-from`; the runtime's part is to check the writer is finished,
take its bytes, and hand back a LIVE reader over whatever the host answered.
All three transcripts still read `["tick" "tick" :refused nil]`.

**`grep -c 'self.encode(|self.decode(' runtime/src/conc.rs` is now 0.** No
guest-facing path encodes or decodes in the runtime any more.

Three things this cost, each worth keeping:

* **`flint.host/ask` called the BUILTIN, not `request`.** Harmless while the
  builtin took values; the moment it took an encoding, a one-argument call left
  the writer slot unread and the runtime indexed past its own arguments -- an
  out-of-bounds panic, not a type error. `ask` goes through `request` now and
  every arity is checked in all three runtimes.
* **A parking call must be BOUND, not nested.** `(wire/read-from (flint.rt/request ...))`
  puts a call that PARKS in an argument position; `flint.port/receive` binds it
  first for the same reason, and this now does too.
* **The standard library is EMBEDDED in the CLI** via `cli/build.rs`, whose
  `rerun-if-changed` does not fire for files inside `lib/`. An edit to
  `lib/flint/host.cljc` reached nothing until `touch cli/build.rs`, and the
  symptom was the new builtin insisting its argument was not a writer while the
  source plainly passed one.

**The transcript prints no tokens and no port ids**, deliberately. A request's
token is an opaque handle the host echoes and never reads, so three
implementations agreeing on its numeric value is not part of the contract --
and they demonstrably do not agree, for scheduler reasons recorded above that
have nothing to do with this protocol. Asserting it would pin a waiter index
rather than a behaviour.

**`decode_guest` IS gone**, and it is the one thing that was genuinely dead. It
refused the live tags for guest-produced bytes and its own docstring said it
was waiting: "it exists so that whoever adds a guest-callable decoder finds it
rather than writing the unsafe one." That decoder arrived, and the rule it held
is now `RD_LIVE` on the reader -- strictly better, because the tags are legal
in the format and what decides is where the bytes came from. The test that
asserted the rule moved to the reader rather than being deleted with it.

**And the budget note needs correcting.** `test/threads.clj` says the 20 622
bytes come back "when the runtime codecs go". Most of them will not: the
encoder stays for the host-facing API. What can come back is whatever the
guest-facing paths were keeping alive, and that number is not yet measured.

### A writer passes through `send` unencoded

`port/send` asks `flint.rt/wire-writer?` before encoding. A guest that encoded
for itself hands over a writer, and encoding that again asks the encoder to
encode its own output -- which it duly refused, `this cannot cross a boundary:
:other`, because a writer has no kind. Answering "is this a writer" is not a
capability: a guest holding one already knows what it made.

### What has to be true first

The codec must be in every image that owns a bridge, and must survive the shake
— the same treatment `flint.system` needed, for the same reason: nothing
references it, so reachability drops it. A module with no ports must not pay for
it (`DECISIONS.md#namespace-units`).

### Two of the dark-region reds are attributed, 2026-09-17 -- and one was never a runtime bug

`snapshot` and `limits` were the two reds sitting in code this session moved
into `kin/sched.kin`, and neither had been bisected. A worktree at HEAD
(`git worktree add --detach`, which does not touch a tree carrying 145
uncommitted files) answers both. Note the trap that cost the first attempt: the
snapshot unit ships ONLY in a `--diagnostics` build
(`DECISIONS.md#two-builds`), so a baseline built without it fails with `no such
builtin: flint.rt/snapshot` and says nothing about the question being asked.

**`snapshot` passed at HEAD and failed here — and the runtime was innocent.**
The symptom read as one: "the snapshot taken while they were parked carries
them -- found 1 THREAD objects", where four were expected. An object census of
the same capture, both sides, is what broke it open:

    HEAD  THREAD 4  PORT 3  CLOSURE 176  ATOM 1   walkErrors: []
    now   THREAD 1  PORT 1  CLOSURE   0  ty44 1   walkErrors: at 1325560,
                                                  size 4294639616, short 42216

A size of 2^32 - 327680 is not a heap, it is a misread header — and `ty44` is
the culprit standing right next to it. `host/snapshot.mjs` carried its own copy
of `obj.rs`'s `layout_of`, in TWO places (`sizeOf` and `slots`), and neither
knew `TY_BYTES` (44). A byte string was therefore sized `HDR + len * 8` instead
of `HDR + len`, the linear walk advanced by the wrong stride, landed
mid-object, and stopped — reporting the 42 216 bytes after it as ABSENT. The
threads, ports and all 176 closures were in the snapshot the whole time.

**The runtime had already paid for this exact bug**, and says so in `size_of`'s
own comment: a type added to `layout_of` and not to the second copy of that
match sized "a 513-byte `TY_BYTES` as 4 112", and the collector walked
from-space with the wrong stride. Deriving the size from one table removed the
second copy inside Rust. The third and fourth were in JS. What changed this
session is only that the heap now CONTAINS a `TY_BYTES` where it did not
before; the reader has been unable to walk one for as long as the type has
existed.

So the fix is one table (`LAYOUT_RAW`), read by both `sizeOf` and `slots`, plus
the eight type names 42–53 that were missing outright. `snapshots: ok`.

**And a guard, because a copy that drifts is not a copy.**
`bin/check-snapshot-layout` parses `layout_of`'s `Layout::Raw` arm and every
`pub const TY_*` out of `obj.rs` and fails if the inspector disagrees — on the
raw set, on a missing name, on a name that means a different number. It is in
`bin/check`'s fast loop. Both arms were made to fail on purpose before being
believed: dropping `44 /* BYTES */` reproduces precisely this session's bug,
and the message says what it will do ("will size it HDR+len*8 and derail the
walk") rather than merely that two lists differ.

The asymmetry is worth keeping in mind: a type missing from the NAME table
reads as `ty44` and is harmless, while one missing from the RAW set fabricates
absences. That is why the check reports both but only one of them can invent a
bug.

**`limits` failed at HEAD too, so it is not this session's** — but it had
drifted, and the drift is the interesting half. The row asserts the gas error
carries `:thread 0`; HEAD answers 1 and here it answers 2. `:thread 0` is the
BOOTSTRAP thread, and asserting it encodes a world with no control plane. A
call now arrives as a message on a bound port and is served on a thread
`flint.system` spawned for it, precisely so a call that parks cannot park the
control plane with it (`DECISIONS.md#bridges-are-the-only-door`). The extra
step from 1 to 2 is this session's: the runtime boots the control plane itself
in `sched_drive` (`boot_system_thread_once`) rather than leaving it to the
host, so the chain is bootstrap 0, system thread 1, call thread 2. The id is an
allocation order and pinning it just fails again the next time anything spawns
earlier, so what the row now asserts is what it is named for — that the error
carries the thread AS DATA.

### A THIRD red, uncovered by fixing the second — and it IS this session's

With the stale `:thread` expectation out of the way, `limits` fails one row
further down, and this one passes at HEAD:

    FAIL   ... by an error that escapes every handler
           flint: the host pump made no progress

The program is `(try (spin) (catch Throwable e (spin)))` — a gate a candidate
can catch its way out of is not a gate. Expected: the second runaway escapes
and the host is told `gas limit exceeded`. Actual: `flint_resume` answers 2
(needs host) a million times over and the pump gives up, which `run` then
reports as exit 1 with the pump's message instead of the gas error — which is
why the row ABOVE it still passes and this one does not.

**The mechanism is not established, and two plausible readings are already
contradicted by the evidence** — recorded so the next attempt does not spend
the time again:

* *The catch handler is re-entered, so the throw catches itself forever.* But
  the handler machinery is in `rt.rs`, whose entire diff this session is the
  seven-line `system_booted` flag; and `unwind-to-handler` in `kin/sched.kin`
  mirrors HEAD's `unwind_from_resume` call site exactly.
* *The control plane parks on the system port for ever, so `sched_needs_host`
  is true for ever and the sandbox can never settle to report a dead call.*
  But the plain `spin` arm leaves the control plane parked in exactly the same
  way and terminates cleanly with the right message. CATCHING is what differs,
  not parking.

What is known: `gas_error` sets `thrown` like any other catchable error and
there is no terminal "this sandbox is out of gas" state, so once `steps >=
gas_limit` every thread that resumes re-raises immediately — including,
in principle, the control plane's own reply path, which must allocate a map to
answer. Whether that is what spins is exactly the thing to measure next, and
the cheap instrument is the resume-code and outstanding-waiter sequence across
the pump's first few iterations rather than another reading of the source.

It is left RED and unattributed in mechanism rather than patched, because the
candidate fixes are design decisions about what a gas-exhausted sandbox owes a
host, and guessing one in would be the second time this session that a
plausible reading of a symptom pointed at the wrong layer entirely.

### The third red, diagnosed against a baseline: the gate's escape is unreportable

Measured, both sides, on the same three arms of `test/limits.clj` at a
`--diagnostics` baseline worktree and here. The program is
`(try (spin) (catch Throwable e (spin)))`.

    HEAD  spin              ResourceExhausted: gas limit exceeded ... (thread 1)
                            step limit exceeded; frames (2): spin <- main
          caught-then-spin  ResourceExhausted: ... spent 1065589 of 1065589
                            step limit exceeded; frames (2): spin <- main

    now   spin              ResourceExhausted: ... (thread 2)
                            frames (4): spin <- main <- answer <- serve-calls
          caught-then-spin  flint: the host pump made no progress

**`frames (2)` against `frames (4)` is the whole finding.** A guest call is now
served INSIDE the control plane's own guest code — `answer` and `serve-calls`
in `flint.system` — where at HEAD it ran with nothing under it. Everything
below follows from that one change.

**Why it hangs.** `vm.rs`'s gate is two-stage and deliberately so: the first
trip grants `GAS_GRACE` (65 536 steps) so a `finally` can put things back and
unwinds to a handler; a second trip `return`s NIL *escaping every handler*,
because "a gate that a candidate can catch its way out of is not a gate". The
arithmetic confirms it fires: steps freeze at 1 065 671, which is the limit
plus GAS_GRACE plus 135.

What that sentence did not anticipate is that the control plane is now one of
those handlers. `(catch Throwable e (spin))` spends the ENTIRE grace itself, so
when trip 2 escapes, `answer`'s `catch Throwable` — the code that would build
`{:op :throw}` and send it — is escaped too. No reply is ever built. And the
control-plane thread stays parked on the system port, so `sched_needs_host` is
true for ever, `sched_all_settled` is never consulted, and `settled_answer` —
the path that surfaced exactly this failure at HEAD — is unreachable. The host
pumps to its million-iteration guard and gives up.

**GAS_GRACE is a single global budget shared between the guest's handler and
the control plane's reply path, and the guest can spend all of it.** That is
the sentence to fix against. It is reachable on purpose, not only by accident:
a guest that wants to hang its host need only burn the grace inside a `catch`.

**What was measured, so the next attempt need not re-measure it:**

* The 42 steps per resume are NOT guest code. Per-opcode counters
  (`stat_region`, OPS base `NBUCKET*4 + 43`) show ZERO opcodes across a cycle;
  the 42 are allocation charges from `reap_ports` rebuilding its `held` and
  `live` vectors every `drive`. Housekeeping charges gas even when nothing
  runs — true before this and worth knowing separately.
* The host is not involved: ONE event (`retain`) in the entire run, so there is
  no host/guest ping-pong.
* Handler re-entry is ruled out: `unwind` pops (`self.handlers.pop()`).
* `gas_trips` is reset only by `set_gas_limit`, and the grace path assigns
  `gas_limit` directly, so trip 2 genuinely fires.

**The fix is a design decision and is NOT taken here.** The narrow one: once
the gate has escaped every handler the sandbox is finished — it cannot execute
anything, since the limit is still exceeded and every charge raises — so
`drive` should take the terminal path rather than claim the host can help,
clearing on `set_gas_limit` so a host may still deliberately raise the budget
and resume. That means a flag in the runtime, a check in `sched_drive` ahead of
`needs_host`, and a way for the terminal answer to carry the failed thread's
result rather than thread 0's. It is generated code, so it is one kin change
and three targets plus an expectation DERIVED from the contract — the right
shape of work, and not one to rush at the end of an investigation.

### And the kind was being flattened to "Error" — fixed

The same `frames (4)` reroute cost every runtime error its kind. `answer` read
`(or (some-> e ex-data :kind) "Error")`, but a runtime error carries its kind
in the object's `EX_KIND` slot, not in its data map — so gas, the memory cap
and every `ClassCastException` arrived at a host as the generic `"Error"` the
moment calls began passing through this catch. HEAD said `ResourceExhausted`
and this said `Error`, on the same program.

`flint.rt/ex-kind` is exactly "the kind a `catch` selects on" and already
exists as a builtin (`kin/exinfo.kin`), so the fallback is one clause:
`(or (some-> e ex-data :kind) (flint.rt/ex-kind e) "Error")`. A guest-declared
kind still wins; what changes is only the case that used to be thrown away.
Verified back to `ResourceExhausted`, matching the baseline exactly.

**And it costs the ports nothing**, which is the control plane being flint code
rather than runtime code paying off: one edit in `lib/flint/system.cljc` and
all four runtimes have it, with no chance of three copies drifting
(`DECISIONS.md#bridges-are-the-only-door`).

### The gate's escape is reportable again, and it is generated (2026-09-17)

The hang recorded above is fixed, in `kin/sched.kin` and therefore in all three
runtimes at once. `test/limits.clj` is green.

**No new state.** The question "has the gate escaped every handler?" is
`gas_trips > 1`, which all three already compute identically and none of them
exposed. Reading the counter rather than adding a flag means there is nothing
new to keep in step, nothing new in the snapshot format, and `set_gas_limit`
resetting it gives "a host may raise the budget and carry on" for free — which
a flag would have had to reimplement.

**The fix is an ORDER, which is why it belongs in the generated scheduler.**
`drive` now asks the gate straight after `reap-ports`, ahead of BOTH `pick` and
`needs-host`:

* Ahead of `needs-host` because that is the check that was lying. A thread
  parked on a bridge makes it answer "the host can help", and after the gate
  has escaped no host can: the budget is still exceeded, so the next charge
  raises whatever arrives — including the charges `flint.system/answer` needs
  to build a reply.
* Ahead of `pick` for a reason found by measuring rather than by reasoning.
  With the check after `pick`, the sandbox kept running threads after the
  escape, each blowing the budget again, and `gate-answer` then spoke for the
  LAST one to do so. The host was told the sandbox died in `receive <- serve`
  — the runtime's own control plane — instead of in the guest's `spin <- main`.
  Running anything after the gate escapes can only manufacture a worse
  diagnosis.

`gate-answer` is separate from `settled-answer` on purpose: a settled program
is spoken for by thread 0, the thread the host called in; a GATED one is spoken
for by whichever thread was running when the budget escaped, which `SC_CURRENT`
names and which is almost never thread 0.

**The runtime side needed nothing.** `flint_resume` already renders a status-0
run with `thrown` set through `finish_run`, which returns 1 with
`"Kind: message"` in `OUT`. What was missing was on the HOST: `pumpFor` threw
its own "the call was never answered" without ever looking. It now reads what
the runtime rendered, and only on `code === 1` — precisely when this run
rendered it, since `OUT` is cleared and rewritten per render and reading it
after any other status risks replaying an older call's message.

**The expectation was derived before anything ran, and it held**: `0111 011 20`
on the first emit, on all three targets. Both halves were then made to fail on
purpose, because three mirrors agreeing proves nothing:

* the check moved back after `needs-host` → `2001 200 20`, which is the bug
  exactly: status 2, bridges unclosed;
* `>= 1` instead of `> 1` → the control row alone flips `20` → `01`, so the
  row that pins the GRACE case is what catches that off-by-one.

Note that in both probes all three targets agreed with each other and were
wrong together. The derived answer is the only thing that separated them.

**STILL OPEN, and it is a real question rather than a defect.** GAS_GRACE is a
single global budget and green threads interleave, so the thread that takes the
second trip is whichever happened to be running when the grace ran out — not
necessarily the one that overspent. In the measured case the guest's handler
spent the grace and the CONTROL PLANE took the trip, so the error names
`receive <- serve` with `(thread 1)`. Moving the check ahead of `pick` stops
the sandbox manufacturing further failures, but it does not decide whose fault
a global budget is. Per-thread grace would, and is a larger design question
than this fix.

### And the module-size budget does NOT fail — that was a measuring error

Recorded because it cost a detour and will cost the next one the same.
`test/threads.clj` carries `pure-size < 500000` and reported **555 008**
against it, which reads as the control plane's 67 409 bytes having blown a
stated budget. It had not. The measurement was taken with `--diagnostics`
units on disk, which are instrumented and larger; the section runs under
PRODUCTION units, where the same build measures **487 757** and passes.

The same trap as `test/tables.clj` needing diagnostics and `test/snapshot.clj`
failing without them, in the other direction: `grep -n "build-units" bin/test`
says which mode a section runs under, and a size measured in the wrong one is
not a number about this program at all.

**It no longer has to be inferred from `bin/test`, 2026-09-22.**
`bin/build-units` records which build it made in `units/.build-mode`, written
BEFORE the build so an interrupted diagnostics run is still marked as one;
`bin/check` refuses to measure a tree that says `diagnostics`, and
`test/threads.clj` asserts it directly above the budget this section is about
-- which is where a wrong answer is expensive, because every size there is
read against a budget and the failure would name the budget rather than the
build. Absence is not failure: a tree that has never built units has no stamp,
and that is not the same as having the wrong one.

### A SECOND hang, found by the gate run and NOT caused by the gate fix

`bin/test` now stops at section 16, `test/capability.clj`, with the same words
the gas hang used: `flint: the host pump made no progress`. It is a different
bug, it is pre-existing, and the shared message is what makes it look like a
relapse.

**Narrowed to one option.** Three compiles through the ESM `Compiler`, same
files, same entry:

    plain            ok
    with meta        ok
    with exports     flint: the host pump made no progress

So a SELF-HOSTED compile that is given `:exports` never answers. The same
program with the same exports compiles fine through `./bin/flint`, which is the
bb compiler rather than the sandboxed one — so the fault is in the self-hosted
path, not in what `:exports` means.

**Not this session's, and the elimination is recorded so it is not redone:**

* the gate check neutralised (`gas_trips > 99999`), units, dist and the ESM
  bundle all rebuilt — still hangs;
* the `ex-kind` clause in `flint.system/answer` reverted, rebuilt — still
  hangs;
* and the run that first showed it used `sdks/esm/dist/flint.js` dated 07:22,
  which predates both edits. Its runtime is EMBEDDED, so units on disk cannot
  reach it.

The gate fix is also structurally incapable of causing this one: `Compiler`
calls `instantiate(this.module)` with no options, so `set_step_limit` is never
called, `gas_limit` stays 0, no charge ever raises, and `gas_trips` cannot
leave 0. The new check reads exactly that counter.

**What is known about the mechanism** is only what the symptom says: the
sandbox returns 2 for ever, so no reply is ever put on the call port. That is
the same shape as the gas hang — something fails on the path that would build
the answer, `serve-calls` swallows it, `receive` parks, and the control plane
waits for a message that will never come. The ESM bundle now carries the
`OUT`-reading fallback and it does NOT fire here, which says the sandbox never
reaches a terminal status at all: it is not a failure being mis-reported, it is
a failure that never ends.

**Where to start**, since the instrument is already built: drive the same
compile through `sdks/esm/src/guest.js` rather than the bundle, with the full
spec the bundle builds — `:builtins`, `:slots` and the base image included,
because a spec missing them fails EARLY with `builtin \`gt\` is not available at
compile time` and answers a different question. Then read the per-opcode
counters across one resume the way the gas hang was read: zero opcodes means
the guest is not running at all and the charge is housekeeping.

**And the artefact staleness is its own finding.** `sdks/esm/dist/flint.js` is
a committed bundle with the runtime embedded, and nothing in `bin/test` rebuilds
it — `bin/build-dist` does not, and `sdks/esm/build` is a separate script that
also runs the selftest. So the ESM surface is tested against whatever bundle
happens to be on disk, which can be arbitrarily old. That is the same trap as
`build-dist` not rebuilding the CLI, one layer out.

### A reply too large to encode

The `test/capability.clj` hang is diagnosed. It is not about `:exports` and it
is not a size limit; it is an allocation failure that three layers each turn
into something quieter, ending in a host that pumps to its guard.

**The chain, measured at each step.** A guest program asked to encode a large
value:

    len=765080  writer?=true      <- wire/encode answers a writer
    len=765090  writer?=false     <- and here it answers a NON-writer:
                                     kind=:other, not nil, no throw at all

* `wire_piece` appends its payload ONE BYTE AT A TIME through `b_conj`. When
  the tail fills, `tb_flush` allocates a fresh `TAIL_CAP` byte string, and on
  failure returns false; `b_conj` turns that into NIL. `wire_piece` then writes
  that NIL into `WR_BUF` and **returns `true`** -- it never looks at what
  `b_conj` answered. The writer is destroyed and the caller is told it worked.
* `port/send` therefore hands `flint.rt/port-send` a bare value, and the bridge
  refuses it: *"a bridge carries an encoding -- use `flint.port/send`, which
  writes one, rather than the builtin with a bare value"*. A true message
  about a false situation -- `flint.port/send` is exactly what was used.
* `serve-calls` wraps that send in `(catch Throwable _ nil)`, so the error is
  dropped, the loop parks on `receive`, and the caller waits for a `:tx` that
  can never arrive.

**It is NOT a byte ceiling, and the first reading of the evidence said it was.**
A single-string encode failed just above 765 085 bytes, which looked like a
constant. It is not: the same compiler hands back an **821 400 byte** reply
successfully. What differs is how much else is live when the encode runs, which
is why `:exports` fails and the plain compile does not -- exports keep more
reachable, so more is live. `set_memory_limit` does not move the threshold
either, so the exhausted space is not the configured heap.

**How it was found**, since the instruments are worth reusing: the snapshot
inspector, on a compiler built with `flint.snapshot` reachable FROM `main` (a
`:require` alone does not link the unit, so a host cannot capture what the
shake removed). It read the hung sandbox directly:

    THREAD id=0 DONE
    THREAD id=1 PARKED -> PORT id=1 BRIDGE  (the control plane, system port)
    THREAD id=2 PARKED -> PORT id=2 BRIDGE  (the call thread, read=1)
    WAITER thread@..1 kind=RECEIVE   WAITER thread@..2 kind=RECEIVE

Both parked on RECEIVE, and `PT_WRITE` nil with `PT_BYTES` 0 on both -- so the
reply was never written at all, which is what pointed at the send rather than
at the delivery. Note the walk was clean (`walkErrors: []`), which is the
`TY_BYTES` sizing fix earning its keep on the first real use.

**The fix has three candidate layers and the bottom one is the user's.**

1. `wire_piece` and `wire_raw` must not ignore `b_conj`. That is a two-line
   correctness fix and it converts a silent corruption into a throw -- but the
   throw then needs a message that is not `wrote`'s "this writer has already
   been finished", which would be false.
2. `serve-calls` should ANSWER a failed send rather than dropping it. Written
   and measured this session, and **deliberately not shipped**: `flint.system`
   is in every module, so the extra code grew every image, and a reply already
   near the cliff crossed it -- turning the plain compile from passing to
   failing. The patch belongs WITH the encoder fix, not before it:

       (try (port/send p (answer m))
            (catch Throwable e
              (try (port/send p {:tx (:tx m) :op :throw :kind "SendFailed"
                                 :message (str "this call's answer could not "
                                               "be sent back: " (ex-message e))})
                   (catch Throwable _ nil))))

3. **PER-CHUNK ACKS, which removes the cause rather than reporting it.** The
   user's proposal, 2026-09-17: stream a bridge message in chunks, each acked
   by the receiver, so neither side holds a whole huge message. Nothing needs
   to materialise an 800 KB writer, so the allocation cliff has nowhere to
   happen and bridge memory is bounded by the chunk.

   The load-bearing property is the one the proposal states: **an ack means the
   receiver has parsed AND HYDRATED every value fully contained in that chunk**,
   which is what makes port handoff safe. The evidence says why it must be that
   and not mere receipt: `K_PORT` carries identity, and `wire-port` refuses to
   take an id precisely so a guest cannot mint a port from an integer. An ack
   given before hydration would let a port's tag be seen without its hydration
   having happened, and a split or a retry across that boundary could mint
   twice.

   **Two things to settle first.**

   * *Do chunks cut at value boundaries?* If the ack means "hydrated", the
     sender may only cut where a value ends -- and the writer ALREADY tracks
     structure for this class of safety (`wire-table-rows` refuses unless a
     count is what the writer expects), so it is the thing that can say where
     a value ends. The alternative, arbitrary cuts plus a receiver-side partial
     tail, is easier to send and moves "fully contained" to the receiver, which
     weakens what the ack promises.
   * *It changes what `needs-host` MEANS.* Today a parked control plane makes
     it permanently true, which is why the gate fix had to be asked ahead of
     it, and why `report-deadlock` is unreachable whenever a control plane
     exists. A streaming send parks between chunks, so "needs host" becomes
     real progress. That is an improvement, and the deadlock reporter's
     reachability should be revisited in the same change.

### And the ESM bundle was five hours stale, which was hiding this

`sdks/esm/dist/flint.js` embeds its own runtime and compiler, and NOTHING in
`bin/test` rebuilds it -- `bin/build-dist` does not, and `sdks/esm/build` is a
separate script that also runs the selftest. The bundle on disk was from 07:22
while the tree had moved on all morning, so the ESM surface was being tested
against artefacts nobody had rebuilt.

Rebuilding it is what turned one failing case into four, and that is the
staleness lifting rather than a regression: the source is unchanged bar
comments. Same trap as `build-dist` not rebuilding the CLI, one layer out, and
worth a line in whatever runs before `bin/test`.

### Where a chunk may be cut, settled

The user, 2026-09-17: **chunks may split COMPOSED values -- collections,
strings, byte strings, big integers -- but never ATOMIC ones like ports.**

That resolves the boundary question above, and it resolves it the right way
round. The thing an ack must never permit twice is the minting of identity:
`K_PORT` and `K_OPAQUE` are the two tags that carry it, and `wire-port` refuses
to take an id precisely so a guest cannot manufacture one. Keep those whole and
an ack is unconditionally safe -- every port in an acked chunk was hydrated
exactly once, because it was never in two chunks to begin with.

It is also the cheap rule to honour. An atomic value's encoding is a tag and a
fixed payload, so "do not cut inside one" costs the sender a few bytes of
lookahead, where cutting only at VALUE boundaries would have meant never
splitting a megabyte string -- which is the case this whole change exists for.

**What it asks of the receiver** is the interesting half. A composed value may
straddle, so "every value fully contained in this chunk" means every value that
COMPLETES in it; a collection or string still in progress is hydrated when its
last chunk lands. So the receiver carries resumable state -- for a string or
byte string, bytes still owed; for a collection, how many elements remain at
each open level. That is the same structure the WRITER already tracks to make
`wire-table-rows` refusable, which suggests one description of "where am I in
this value" serving both ends rather than two.

**And it belongs to the TAG, not to a size.** Atomicity here is a property of
each `K_*`, so the codec should say which tags are indivisible rather than
leaving the sender to infer it from how small a payload looks. A tag added later
that carries identity and is not marked would otherwise be splittable by
default, which is the failure this rule exists to prevent.

### It was never a size limit: the writer MOVED, 2026-09-17

The hang above is fixed, and the diagnosis in the section before it is wrong in
its final step. Recorded rather than edited, because the wrong reading was
reached honestly and the way it fell apart is the useful part.

**What it actually was.** Every `wire-*` builtin took its writer with
`writer_arg`, which reads `arg(rt, a, 0)` into a Rust local, and ANSWERED that
local at the end. In between it appended -- `wire_piece` conjs a byte at a time
-- and appending allocates, allocating can collect, and the nursery is a
copying collector. So the writer moves, and what came back was **the address
the writer used to be at**.

Below a collection's worth of payload that is the same address and everything
works. It took roughly 765 KB to make a collection certain mid-append, and then
the guest got back an object that was not its writer: `wire-writer?` false, not
nil, so nothing threw. `port/send` passed it on, the bridge refused it with *"a
bridge carries an encoding -- use `flint.port/send`"*, and `serve-calls` dropped
that error, so the caller pumped to its guard.

**The fix is one line per builtin**: answer `arg(rt, a, 0)`, re-read. The value
stack is a root the collector updates, so it needs no push of its own -- the
rooting was already there and only the READ-BACK was missing. Twelve sites,
including the `wire_tag!` macro.

    reply of 1 000 000 bytes   before: the host pump made no progress
                               after:  code=0, len=1000000
    reply of 2 000 000 bytes   after:  code=0, len=2000000
    compile with :exports      before: hang     after: ok
    ESM selftest               before: red      after: green
    test/capability.clj        before: red      after: ok

**HOW THE WRONG READING SURVIVED SO LONG, and it is worth naming.** Every
observation fitted an allocation failure: the threshold ignored
`set_memory_limit`, it moved with live-set pressure, and there was a real
unchecked failure path sitting right there -- `tb_flush` answers false,
`b_conj` turns that into NIL, and `wire_piece` wrote the NIL into `WR_BUF` and
returned `true`. That path is genuinely broken and I fixed it. It was not this
bug.

What broke the theory was ONE measurement that the theory did not predict: with
the allocation path fixed, the failure was unchanged. Asking the guest three
questions instead of one then settled it --

    count=770000  fresh-writer?=true  after-str-writer?=false  same?=false  w0-still?=true

`w0-still?=true` is the whole answer. The writer the guest held was FINE; only
the one the builtin handed back was not. An allocation failure cannot produce
that, and a move is the only thing that can. "Is the object I passed in still
good?" is the question that separates them, and asking it earlier would have
saved most of a session.

**The unchecked-growth fix stays**, on its own merits and with its own case.
`kin/wirecore.kin`'s `wire-byte` now answers false when `b-conj` answers NIL,
and does NOT store it; `wire_piece` and `wire_raw` check what it answers. The
drivers case is `ok7 = 7` and the middle bit is the one that matters:

* `1` the append is refused -- weak on its own, and the broken version can be
  made to pass it;
* `2` **and `WR_BUF` is untouched** -- compared against what it held BEFORE the
  refusal. The original bug fails this, and so does a half-fix that answers
  false and stores the NIL anyway. Both were run: the bug scores 4, the
  half-fix 5, and on native the corrupted writer PANICS outright rather than
  scoring anything;
* `4` and the writer still works afterwards -- a refusal is not a kill, so a
  transient failure does not leave a permanently dead writer.

`wrote` no longer stamps "this writer has already been finished" over a
failure that is already pending, because that sentence would be false for a
buffer that was live and merely out of room.

### The prescribed fix landed and the gas row did NOT close, 2026-09-17

The section above concludes: "the way to make them agree by construction is to
generate the resume path." That has now happened -- `sched_run_one` is
generated, and all three runtimes call it (`conc::run_one` → `rt.sched_run_one`,
`Conc.runOne` → `Sched.schedRunOne`, `Conc.RunOne` → `Sched.SchedRunOne`). The
row is still red, and wider:

    documented:  gas differs by  682: 143717 native against 143035 [jvm]
    now:         gas differs by 1392: 144427 native against 143035 [jvm]

The jvm figure is unchanged to the digit. So generating `run_one` was necessary
and is not sufficient, and the remaining cost is NOT in `run_one` -- which
narrows the earlier "a `Conc` divergence in `run_one` and the interpreter's
checkpoint" to the checkpoint alone, the part still written three times and not
a kin candidate.

**The per-slice charge is still there, re-measured independently.** A pure
arithmetic loop, no allocation and no builtin call, at two sizes:

    wasm  small=266604  big=529628  diff=263024
    jvm   small=275921  big=535921  diff=260000

3 024 over roughly 64 extra slices is **47.25 steps per slice**, against the
46.4 and 46.7 the earlier micro-benchmarks got. Three measurements, one number.

**AND THE ROW'S METHODOLOGY IS SENSITIVE TO SLICE ALIGNMENT, which is new and
matters more than the number.** The difference-of-two-workloads is supposed to
cancel whatever each runtime spends starting up. It does not cancel a per-slice
charge unless both sides cross the SAME number of slice boundaries -- and
whether a workload crosses one depends on its ABSOLUTE step position, which
differs because startup differs. A pair sized to fit inside one slice shows it:

    wasm  tiny=4828   tiny2=6176   diff=1348
    jvm   tiny=17217  tiny2=18517  diff=1300

48 left over -- exactly one slice -- on workloads doing ~1 300 steps of actual
work, because the jvm starts ~12 400 steps higher and therefore sits at a
different offset within its slice. So while a preemption charge exists, this
row is partly measuring "how many slice boundaries did each side happen to
cross", which is not a property of the program. Any future reading of this row
should treat sub-50-step disagreements as alignment rather than as pricing.

**What is ruled out, added to the earlier list:**

* `reap_ports` billing the guest for housekeeping. It DOES -- 42 steps per
  `drive` with zero guest opcodes, measured earlier this session on a
  port-using program -- but the loop above has no ports at all, so
  `SC_BRIDGES` and `SC_PORTS` are the empty singleton and nothing is conj'd.
  It cannot be paying the 47 here.
* a different charge RATE. Both bill `size_for(ty, len) >> 3` on allocation and
  both gate it on whether the sandbox is counting; the formulas match branch
  for branch.
* saving and restoring thread state: `alloc_unbilled` on both, and neither
  charges to restore.

**And one claim in the section above is now STALE.** It says "The ports have
not made this change. `RtSteps` calls `runProgram` directly, and `runtimes/`
has no `system_message` at all." Both halves have since been done:
`runtimes/jvm/test/HostCall.java` installs a system port, sends `:bind`, and
calls over it, and `Conc.bootSystemThreadOnce` mirrors native's boot. Its own
comment even predicts this gap at "about 48 steps per 4096-step slice". The
divergence is no longer that one side skips the scheduler.

**The next instrument** is a count, not another reading: make `SLICE` large
enough that the loop is never preempted, rebuild, and re-measure the same four
workloads. If the 47 vanishes, it is the preemption path and the remaining
question is only which charge inside it; if it survives, the per-slice framing
is wrong for the third time and the arithmetic loop itself is priced
differently.

### And the instrument was run: the divergence IS the preemption charge

`SLICE` raised to `1 << 30` on NATIVE ONLY, so the loop is never preempted,
everything else unchanged:

    wasm, SLICE 4096     small=266604  big=529628  diff=263024
    wasm, never preempt  small=263534  big=523532  diff=259998
    jvm,  SLICE 4096                                diff=260000

**259 998 against 260 000.** Two steps apart on a 260 000-step difference, and
the gap of 3 026 is gone. So the whole divergence is what NATIVE charges to be
preempted and resumed -- 3 026 over ~64 slices, 47.3 steps each -- and the jvm
charges approximately nothing for the same preemption, since its `SLICE 4096`
figure equals native's never-preempted one. The earlier readings that called
this a pricing difference in maps, in hashing, or in allocation volume were all
looking at the wrong thing; it is one charge on one side of one path.

`SLICE` was restored and the units rebuilt, and the original 263 024 came back,
which is how we know the tree is as it was rather than as the probe left it.

**What it is NOT, now checked line by line on both sides:** every allocation in
`save_current_state` / `saveCurrentState` is `alloc_unbilled`; `restore_state`
allocates nothing; `install_bindings`, `begin_slice` and the yield branch in
the interpreter's checkpoint charge nothing; `run`/`run_with`/`run_inner` charge
nothing on entry; and `reap_ports` cannot be it here because the program has no
ports, so `SC_BRIDGES` and `SC_PORTS` are the empty singleton.

So ~47 steps per resume are charged on native by a site not yet found, and the
search is now small: it is on the path between "the checkpoint sets
`PARK_YIELD`" and "the interpreter is running again", it is billed rather than
unbilled, and it is absent from the jvm's copy of the same path. The instrument
that will name it is a counter around each candidate charge -- or simply
`steps` read immediately before the yield and immediately after the resume,
which brackets it to one span without guessing which line.

Worth noting for whoever does it: the fix is then a choice the earlier section
already framed, and the measurement now settles which side is wrong. Gas is
meant to be the PROGRAM's instruction count; a charge that depends on `SLICE`
makes the same program cost different amounts at different slice sizes, and
makes the row sensitive to startup alignment as shown above. Native charging
and the jvm not charging means native is the one to change, not the ports.

### NAMED: the charge is `reap_ports`, and the two runtimes disagree on a SENTINEL

The ~47 steps are found, and it is not a pricing difference in anything the
program does. Bracketing the span with temporary counters -- `steps` recorded
where the checkpoint sets `PARK_YIELD`, again after `settle` saves state, again
at the top of `run_one`, and again in `restore_state` -- split it three ways in
one build:

    resumes=127   settle=635   drive=5334   resume=0
    per resume:   settle=5.0   drive=42.0   resume=0.0

So 42 of the 47 are spent inside `drive`, between `settle` finishing and
`run_one` starting. Bracketing `reap_ports` itself then pinned it: **43 steps
per call**, and it is called once per drive iteration, so once per resume.

**And it charges that with no ports in the program**, which is why an earlier
elimination in this file was wrong to rule it out. The program has no ports of
its own, but it HAS a control plane, and the system port is a bridge that
`install_bridge_port` conjes onto `SC_BRIDGES`. `reap_ports` then rebuilds that
one-element vector every iteration -- `vec_conj` allocates, `alloc` bills -- so
the guest is charged for the scheduler re-deriving a list that did not change.

**Why the jvm does not pay it, which is the actual divergence.** Both runtimes
register the bridge, both run the same generated `drive`, both preempt at
`SLICE`. They differ on what `checkpoint` MEANS when it is zero:

* jvm: `alloc` charges `if (checkpoint != 0)`, and its yield sets
  `checkpoint = 0`. So from the moment it yields until the slice is re-armed,
  allocation is NOT billed -- and all of `reap_ports` falls in that window.
* native: `counting()` is `checkpoint != u64::MAX`, and its yield leaves the
  checkpoint at its slice value. Zero is not the "off" value here, so native
  bills straight through the scheduler's own work.

One field, two sentinels, opposite meanings -- the same shape as the warning
already in `sched.kin`: "`u32::MAX` against `-1` is the same thirty-two bits
read two ways". Neither side is obviously implementing a decision; the jvm's
suppression looks like a side effect of zeroing the checkpoint to stop
re-entering its own yield branch.

**What to do, and it is now a small decision rather than an open question.**
This file already states the principle: "Gas is supposed to be the PROGRAM's
instruction count." Housekeeping the scheduler does between slices is not the
program's work, so the jvm's OBSERVED behaviour is the correct one and native
is the side to change. Two pieces, and they are separable:

> SUPERSEDED IN PART, see `### DECIDED: gas charges ALL guest code, and the
> third mode is not worth it`. Piece (1) below reads "do not bill the
> scheduler"; the standing decision is that ALL GUEST CODE is billed, the
> system thread included, so the answer is not to exempt a caller but to stop
> the scheduler doing work it did not owe. That is what `kin/reapports.kin`
> did. Piece (2), THE SENTINEL, IS DONE -- see the section below, and it was
> hiding a live bug rather than an untidiness.

1. **Do not bill the scheduler.** `reap_ports` should allocate unbilled, the
   way `save_current_state` already does for exactly this reason, or `drive`
   should suspend counting around its own bookkeeping. That closes the 47 and
   removes gas's dependence on `SLICE`.
2. **Then make the sentinel one thing.** Whatever the answer to (1), "am I
   counting?" must not be spelled `!= 0` on one runtime and `!= u64::MAX` on
   another. That is a convergence bug waiting to be found a second time.

`reap_ports` rebuilding an unchanged list every iteration is worth fixing on
its own merits regardless -- it is O(ports) allocation per slice for no change
-- but the gas row only needs it to stop being billed.

**Expect the row to move, not to vanish.** Gas is asserted in several places
(module budgets, construe's gates, `test/limits.clj`'s exact counts), so
removing a per-slice charge changes numbers that are written down. The probes
were reverted and the units rebuilt, and the arithmetic loop measured 263 024
again -- the same figure as before them -- which is how we know the tree is
unchanged and the next person is measuring the same thing.

### ONE CAUSE, TWO FAILURES: the `colls` AOT gap is the same charge

The gas row and `bin/test`'s one standing known failure are the same bug. That
is the most useful thing found in this line, and it was one experiment away
from the section above.

`test/aot.clj` compares a program interpreted against the same program with
compiled arities, on the SAME runtime, and demands the same instruction count.
`colls` has been 46 steps short for as long as the row has existed. With
`SLICE` raised so nothing is ever preempted, and NOTHING else changed:

    SLICE 4096         FAIL colls -- the same instruction count
                            expected 113548  got 113502
    SLICE 1 << 30      ok   colls -- the same instruction count
                       aot: ok

Restored to 4096 the failure comes back, so the link holds both ways. And the
arithmetic is the giveaway: 46 is one resume's worth of the ~43-46 steps a
resume costs. The two runs do not preempt the same number of times -- slice
ends are set from `steps + SLICE` at each resume, so they DRIFT, and a tiny
difference early compounds into one extra or one fewer resume -- and each
resume charges the guest for `reap_ports` rebuilding lists that did not change.

So the earlier reading of `colls` in this file -- "five instructions moved into
compiled code and charged by no chunk" -- was measuring a real thing that is
not this row's 46. A per-resume charge makes the interpreted and compiled runs
of the same program differ by a multiple of ~46 whenever they land on different
slice counts, which is exactly what "the same instruction count" cannot
tolerate.

**The jvm side, measured rather than inferred.** Last reading said the jvm's
`checkpoint = 0` suppresses billing; that was read off the source, so it was
instrumented:

    jvm:  reap n=130  cost=126 steps  allocs=1040
          total allocs=2716  billed=1210

130 calls make **1 040 allocations** -- eight per call -- and are charged 126
steps for all of them, about one step per call, against native's 43. So the jvm
does the identical redundant work and is not billed for it, and 1 506 of its
2 716 allocations fall outside its counting window. The mechanism is confirmed.

**Which makes the fix better than "decide what gas means".** The rebuild is
pure waste on every runtime: eight allocations per slice to reproduce a list
whose contents are unchanged, 1 040 of them in a 130-slice program. Stop doing
it -- write `SC_BRIDGES` and `SC_PORTS` back only when a port was actually
dropped -- and three things follow at once:

* every runtime allocates less and collects less often;
* native stops charging the guest ~43 steps per resume, which closes the gas
  row without anyone having to rule on whether the scheduler may bill;
* `colls` stops depending on how the two runs land against slice boundaries,
  which closes the known failure.

The gas SEMANTICS question stays open and is still worth settling -- other
scheduler work could bill, and the `checkpoint` sentinel means `!= 0` on one
runtime and `!= u64::MAX` on another, which will be found again. But it is no
longer what blocks either row.

**Not implemented here.** `reap_ports` is hand-written three times, so the fix
is three edits or a generated one, and it moves instruction counts that are
asserted in several places -- including the two rows it fixes. It wants doing
deliberately, with the numbers re-derived rather than adjusted to fit. `SLICE`
was restored, the units rebuilt plain, and the arithmetic loop measures 263 024
again, so the tree is as it was.

> DONE, 2026-09-18, the generated way: `kin/reapports.kin` is the one
> definition and all three runtimes' `reap_ports` is a delegation to it. The
> rebuild is now guarded by a scan, so nothing is allocated when nothing was
> collected. `colls` gap 46 -> 4 and no longer thread-scaling.
> `doc/goals/kin-port.md` carries the numbers and the mutation probe that
> proves the drivers file's expect was derived rather than recorded. **The
> sentinel divergence named above is still open** -- one definition stopped the
> two ports doing the wasted WORK; it did not make them agree about what is
> billable.

### FIXED: one sentinel, and the snapshot stops carrying a derived field, 2026-09-18

The divergence named two sections above -- "am I counting?" spelled `!= 0` on
the ports and `!= u64::MAX` on native -- was not a style difference. It was a
live cross-runtime bug, and the reason nothing had caught it is that the only
thing crossing between runtimes was being checked for its HEAP and not for its
gas.

**Measured first, at the byte level.** Three live snapshots of the same program
state, written by the three runtimes:

    native  gas_limit=0 slice_end=0 checkpoint=0xffffffffffffffff
    jvm     gas_limit=0 slice_end=0 checkpoint=0x0
    clr     gas_limit=0 slice_end=0 checkpoint=0x0

Same limits -- nothing is counting -- and the field that says so disagrees.
`import_live` assigned it RAW. A snapshot is refused on MAGIC and VERSION,
which are identical on all three, so the crossing is ATTEMPTED rather than
refused (`bin/conform-hosts` has a phase for exactly that, and it passes).

**Both directions were wrong, and one of them was sharp.**

* **port -> native.** `Counting::tick` is a bare `rt.steps >= rt.checkpoint`;
  the `counting()` guard is the monomorphisation, not a second compare. A
  restored `checkpoint = 0` therefore trips on the FIRST instruction, from a
  field the writer meant as "never trip".
* **native -> port.** The ports count in a signed `long`, so `u64::MAX` arrives
  as `-1`. `steps >= -1` is true for every value `steps` can hold. Same shape,
  same severity, opposite runtime.

**The fix is two things, and only the second is the real one.**

1. **One sentinel.** The ports now spell "nothing is counting" as
   `Long.MAX_VALUE` / `long.MaxValue`, which is what the native runtime means
   by `u64::MAX`. This is a pure respelling and moved NO numbers -- the gas
   row read `174` before and after, and `bin/check` stayed green. It costs one
   comparison LESS in each port's hot loop: `checkpoint != 0 && steps >=
   checkpoint` existed only to stop `steps >= 0` firing on every instruction,
   and a value that is never reached needs no guard. It also required
   initialising the field, because the old "off" value is Java's and C#'s
   default for a `long` and the new one is not -- the old spelling's OFF is the
   new spelling's IMMEDIATELY, which is the sharpest way a respelling can go
   wrong.

2. **`checkpoint` is derived, so it is RE-DERIVED on import** -- in all three
   runtimes, in every restore path. This is the fix that holds. The bit
   patterns CANNOT be made equal: native counts in `u64` and the ports count in
   a signed `long`, so "never reached" is genuinely a different number on each
   side, and any scheme that carries the field across is one type change away
   from the same bug. Computing it from the two fields beside it makes the
   crossing safe as a property of the FORMAT rather than of three files staying
   in step.

**Gated in both directions, and both gates were made to fail first.**

    livedump (native side)   after an import, `counting()` must agree with
                             `gas_limit` and `slice_end`. Reverting the
                             re-derive: FAILS on the real jvm snapshot AND on
                             a native snapshot doctored to carry `0`.
    RtSnapshot (both ports)  the same invariant on the other side. Reverting
                             the jvm's re-derive: FAILS.

The invariant is deliberately NOT "the bits match", which would be false by
construction and would have to be relaxed the first time a type changed. It is
that a snapshot whose limits are both zero restores as a runtime that is not
counting, whoever wrote it.

**Verified:** `bin/check` green; `check-kin` green at 97; both ports compile;
the ports' gas bound still refuses at the same instruction (`29164`, over by
17) and the two still agree; `RtFoundation`/`RtMaps`/`RtSnapshot`/`RtParallel`
clean and jvm-vs-clr identical apart from the two benchmark timing lines; the
stale-push check still fires on one and stays quiet on the other; `hostreq`
identical across all three; `hostports` differing only by the known open-token
line; and the `conform-hosts` gas row unmoved at 174, which is the evidence
that piece (1) really was only a respelling.

And the full gate, `FLINT_TEST_KEEP_GOING=1 ./bin/test`, 62 of 62 sections in
3366s: THE SAME THREE REDS AS BEFORE THE CHANGE, with the same numbers --
`test/aot.clj`'s `colls` at 112 460 compiled against 112 464 interpreted,
`conform-hosts` at 174, and `test/document.clj`'s peak-memory row. Nothing new
went red. `KEEP_GOING` is what makes that claim mean anything: a plain run
stops at the first red, which is `test/aot.clj` at section 22 of 62, so it
would have said nothing at all about the 40 sections after it.

**What is still open.** The ports disarm billing between a yield and the next
`drive` -- they set the checkpoint to the never-reached value there, and `alloc`
asks `is anything counting?`, so the scheduler's own allocation goes unbilled
where native's does not. That is now a VISIBLE line with a comment on it in
both ports rather than a number hiding inside a sentinel, and it is a billing
POLICY question, not a spelling one: it is piece (1) above, which the standing
"charge all guest code" decision answers in principle and nothing has built.

**Measured 2026-09-22, and it is worth ZERO PER YIELD.** No gas fixture here
had ever yielded -- every one of them runs on a single thread, so the
scheduler never ran between two instructions and the disarmed window was never
entered under comparison. `runtimes/conform/yieldgas.cljc` puts two threads in
it, each yielding `n` times, at 10 and 40:

    native  16 229 / 16 889    delta 660
    jvm     16 141 / 16 801    delta 660
    clr     16 141 / 16 801    delta 660

Eleven gas a yield on all three, to the instruction, and the two ports agree
exactly. What remains is a CONSTANT 88, which does not move with the yield
count -- so whatever the ports leave unbilled in that window, it is not
proportional to how often the scheduler runs, and a program that yields more
does not drift further from native.

And 88 is not this fixture's number: the spawn row beside it reports **the
same 88**, on a program that yields never and spawns forty threads. A constant
that survives changing the workload is a cost of STARTING, which is what it
was always said to be -- and now with two independent programs saying so.

THE SLOPE IS THE CLAIM, not the gap; a difference of two workloads cannot tell
"agrees" from "disagrees by a constant", which is why both sizes are recorded
and both are asserted. `bin/conform-hosts` now carries the row, and the
comparison was checked against doctored numbers first: three gas a yield going
unbilled on one side reads as 88 at 10 and 268 at 40, and fails.

This does not answer the policy question -- whether the scheduler's own
allocation SHOULD be billed is still a decision, and still yours. It removes
the reason to hurry: the divergence it was feared to cause is not accumulating.

### The gas row's 174 is located, and 140 of it is gone, 2026-09-18

Picked up because the record above named "the ports' system-port entry" as the
last unbuilt piece and because `conform-hosts`'s gas row is one of three
standing reds. Probing first found the record stale: both ports have a system
port, and the jvm's `RtSteps` was moved to a bridge call some time ago. So the
prescribed fix was done and the row was still red, which means nobody had
re-diagnosed it since it was 1 536.

**Localised by splitting `gasmeter/work` into its seven parts**, each its own
entry point, each measured as `big - small` so start-up cancels -- the same
method the 1 536 was split by:

    part                        native   jvm     gap   per slice
    mapv of array-maps          25,554  25,518    36       5.8
    into a set                  16,055  16,031    24       6.1
    into a vector via map       25,524  25,518     6       1.0
    apply str over mapv str      9,730   9,716    14       5.9
    ft/build                    27,663  27,621    42       6.2
    ft/rows + mapv :id          19,390  19,360    30       6.3
    the string block            19,294  19,268    26       5.5
    sum of the seven                             178
    whole program              143,209 143,035   174

The seven account for the whole of it. **And it is not the "pricing difference
spread in proportion to the work" that the comment in `bin/conform-hosts` still
described** -- that reading dates from when the gap was 1 536. Against SLICES
rather than against work it is flat: six of the seven sit at 5.5-6.3 steps per
4 096-step slice, which is the residual an earlier entry in
`doc/goals/kin-port.md` already measured at 5.1 a slice and separated from the
42 that `reap_ports` was costing. Work and slices are proportional to each
other, so the earlier reading was not wrong; it was under-determined.

**The mechanism is the one recorded as open a firing earlier: the ports do not
bill in the yield window.** Both ports disarm the checkpoint at the courtesy
yield -- to stop re-entering that branch -- and their allocation charge asked
`checkpoint != <the never-reached value>`, which is the expression NATIVE uses
for `counting()`. Native never disarms out of band, so there the two questions
have one answer; here they diverge for exactly the window between the yield and
`drive` re-arming the slice, and the scheduler's own allocation went free.

So the charge site now asks the thing it means:

    boolean billing() { return gasLimit != 0 || sliceEnd != 0; }

which is native's semantics exactly, written so it does not depend on whether
the checkpoint happens to be armed. **Preemption is untouched** -- the
checkpoint still disarms, because that is about where to STOP, not what to
COUNT.

**Measured: 174 -> 34**, and every part shrank by about six:

    part                        was  now
    mapv of array-maps           36    6
    into a set                   24    4
    into a vector via map         6    1
    apply str over mapv str      14    2
    ft/build                     42    7
    ft/rows + mapv :id           30    5
    the string block             26    4

**THE CLR WAS A FIRING BEHIND, and the row was built so it could not tell.**
Applying the same fix to the clr changed nothing, which is how the real
divergence surfaced: its `RtSteps` still ran `img.entry` through `RunProgram`
with `rt.started = true` and the initialisers by hand -- the model from before
a sandbox became a thing you CALL -- so it never yielded and had no window to
bill in. It also took no function NAME, deriving the entry from the image,
which the jvm's own docstring calls "one rename away from measuring nothing and
still printing a number".

That had been true for as long as the jvm's move, and nothing could see it,
**because the row compares DIFFERENCES**. The door costs the same in `small`
and in `big` -- 3 185 steps, measured -- so it cancelled exactly. A difference
is the right instrument against NATIVE, which enters by genuinely different
machinery; between two runtimes that are meant to be mirrors there is nothing
legitimate for it to cancel, and what it cancelled was the whole of one port's
entry path.

The clr now has a line-for-line mirror of `HostCall.java` and takes the name as
an argument. The two ports agree at **71 962 and 215 137, absolute counts
included** -- where they previously agreed only after subtraction -- and
`bin/conform-hosts` now asserts that, with the reason written beside it. A
mirror checked only through a subtraction is a mirror checked where it cannot
differ.

**Stated precisely, because the obvious stronger claim is not true today.**
Reverting the clr to its old door on purpose, both checks now catch it: the
difference reads 143 035 against the jvm's 143 175 as well as the absolutes
reading 68 722/211 757 against 71 962/215 137. The difference only catches it
because of the OTHER fix in this section -- a `RunProgram` clr has no yield
window, so with the jvm now billing in one the door stops cancelling. Before
that fix the difference matched to the instruction while the absolutes were
3 185 apart, which is the state this was found in and is measured above.

So the absolute check is not "the only one that can catch this". It is the one
that does not depend on a second effect happening to make the door visible, and
that is the reason to keep it.

**The order was wrong too, and that is the more general fault.** The clr rows
sat AFTER the native verdict, which exits on a gap -- so for the whole history
of this row, which has never been green, the two ports were never compared at
all. A divergence between them could sit behind a red that is about something
else indefinitely, and one did. They are asked first now. A check that only
runs when an unrelated check passes is a check you do not have.

**What was left at the time: 34, about 1 step per slice.** A precise target
rather than a mystery, and the shape suggests where to look -- `aot.rs`'s `aot_tick` already
documents a one-instruction accounting difference at exactly this boundary
("the interpreter alone charges it twice: once at the tick that trips, once
after the resume"). Not chased here, and NOT assumed: that is a hypothesis with
a measurement attached to it, which is the only thing that would settle it.

### The 34 IS a double charge at the slice boundary. FIXED, and this heading said otherwise until 2026-09-23

**THE CHANGE IS IN THE TREE.** `vm.rs:336` tests `rt.steps >= rt.checkpoint`
and `vm.rs:339` charges afterwards -- test first, charge second, exactly the
swap prescribed below -- and `tick` carries the whole account in a comment.
The gasmeter row it was blocking reads `the same program costs the same gas,
to the instruction: 143035`, and `bin/conform-hosts` ran 365 rows with zero
failures on 2026-09-23.

Everything under this heading is kept as the DIAGNOSIS, which is the part
worth having: the measurement that separated "proportional to work" from
"equal to the slice count" is what named the mechanism, and the same split
would name the next one. What is not worth having is a heading that says a
landed fix is not landable, which is why it has been rewritten rather than
appended to -- the same correction this file already records for
`conform-hosts`, whose banner said IT IS FAILING above a row that passes.

**Measured on a program that allocates nothing**, so allocation billing cannot
confound it -- a bare counting loop at six sizes, differences taken against the
smallest so start-up cancels:

    entry   d-native    d-jvm   gap   d-slices
    n1k        9,013    9,011     2          3
    n5k       45,067   45,056    11         11
    n10k      90,133   90,113    20         22
    n20k     180,265  180,223    42         44
    n40k     360,529  360,443    86         88

The gap is not proportional to work. It IS the slice count.

**The mechanism, from the two loops.** Native's `Counting::tick` is

    rt.steps += 1;
    rt.steps >= rt.checkpoint

-- increment, then test, then execute -- and it is called at the TOP of the
loop, before the opcode is read. So on the iteration that trips, the
instruction has been CHARGED and NOT EXECUTED; after the resume it is
dispatched and charged again. The ports test first and charge second (`if
(steps >= checkpoint)` ... then `opcode = u8(ip); ip += 1; steps++`), so they
charge exactly the instructions they run. **The ports are right and native
double-charges one instruction per slice.**

That also makes native's gas depend on `SLICE`, which the same program should
not. Demonstrated by rebuilding with it changed:

    SLICE=2048  376,974
    SLICE=4096  376,444
    SLICE=8192  376,180

Part of that spread is the scheduler's own per-resume work, which the standing
decision says SHOULD bill -- so this measurement on its own does not separate
the two. The cross-runtime 1-per-slice does, because both sides now bill the
same scheduler work.

**The fix, and what it did.** Making native test before charging:

    if rt.steps >= rt.checkpoint { return true; }
    rt.steps += 1;
    false

takes the gas row from **+34 to -6**, and -- the part that matters more than
the number -- the residual stops scaling with slices: the spin probe's gap goes
from 2/11/20/42/86 to a flat -2.

**WHY IT IS NOT IN THE TREE.** It breaks the AOT/interpreter agreement, and I
could not derive the pairing:

    test/aot.clj    before          after
    arith           exact           -3
    colls           -4              -3
    hof/strs/handler exact          exact

One red becomes two. `aot_tick` hands one step back on a trip (`rt.steps -=
1`), tuned against the interpreter's OLD two charges; removing that
compensation overshoots badly (+63 on arith), so it is still needed and the
residual is something else. Two models of the pairing were written down here
and both were wrong against the measurement, which is the reason this stops
rather than continues.

**For the next attempt.** The trip counts are what would settle it and they
need `bin/build-units --aot --diagnostics` together -- the modules
`test/aot.clj` builds carry `--aot` only, so `stat_region` is absent and
`stat_region(80 + C_AOT_TICK_TRIPS)` cannot be read from them. With per-program
trip counts, "is the residual per-trip or a fixed three" is one subtraction.
Note also that `arith` and `colls` are the only two affected and both by
exactly 3, while three other programs stay exact -- a constant, not something
proportional, which argues against a simple per-trip story.

#### Measured 2026-09-19: NEITHER model, and the obstacle is alignment

The trip counts, both states, `--aot --diagnostics` together:

    prog      base gap  base trips   fix gap  fix trips   d-interp   d-aot
    arith            0          63        -3         63        -64     -67
    colls           -4          16        -3         17        -24     -23
    hof              0           9         0          9         -9      -9
    strs             0           1         0          1         -2      -2
    handler          0           0         0          0          0       0

**It is not per-trip.** At baseline `arith` has SIXTY-THREE trips and is exact
while `colls` has sixteen and is off by four. A per-trip error cannot produce
that.

**And the standing `colls` red is not a slice-boundary effect at all**, which
is worth saying plainly because filing it next to this work implied it was.
It is the only program with a baseline gap, and it is not the one that trips
most. Whatever it is, it is not this.

**The obstacle to the fix is ALIGNMENT, not a constant.** `colls`'s AOT trip
count CHANGES under the fix -- 16 to 17 -- while every other program's stays
put. Moving where a slice boundary falls changes how many times a chunk trips,
and each trip carries `aot_tick`'s one-step compensation. So the AOT residual
is not a fixed offset waiting for the right constant: it moves with where the
boundaries land. That is why two models of the pairing were written here and
both were refuted, and it is the thing a third attempt has to account for
rather than another compensation term.

**A blind alley worth marking so it is not walked twice.** Compiled code
allocates measurably LESS than interpreted -- `arith` -560 bytes, `colls` -824,
`hof` -184, `strs` -32 -- while three of those five cost identical gas, which
reads as "the two paths do different work and the counts match by luck". They
do not. `stat_bytes_allocated` counts `Gc::alloc`, and the gas charge sits in
`Rt::alloc` above it; collector promotion goes straight to the former and is
deliberately excluded from the latter, because gas must not depend on when a
collection ran. **`stat_bytes_allocated` is not a proxy for the allocation
charge**, and comparing it across two builds measures GC timing.

**The tree is at baseline.** `vm.rs`, `aot.rs` and `conc.rs` are byte-for-byte
as they were, verified by diff against copies taken before the experiment; the
units were rebuilt plain afterwards; `test/aot.clj` is back to its single
`colls` failure at 112 464/112 460 and the gas row back to 34. Gas accounting
is not a thing to leave half-changed.

### The `colls` AOT red is ALIGNMENT, and that unifies it with the gas row, 2026-09-19

Picked up because the entry above said this red "needs its own investigation
and should not be expected to fall out of this one". It did not fall out of it;
it turns out to be the same defect wearing different clothes.

**It does not scale with the work.** The same program at four sizes:

    n       interp      aot   gap   trips
    100     34,035   34,035     0       3
    250     62,648   62,648     0      10
    500    112,464  112,460    -4      16
    1000   217,905  217,903    -2      34

Zero at two sizes, and the two non-zero values do not order with `n` or with
trips. A per-operation pricing difference cannot produce that.

**It moves with `SLICE`, which settles it.** The SAME program, rebuilt with the
preemption quantum changed and nothing else:

    SLICE    n=500              n=1000
    4096     -4 (16 trips)      -2 (34 trips)
    2048     -2 (30 trips)      -6 (78 trips)
    8192      0 ( 9 trips)       0 (20 trips)

A property of the program cannot depend on the preemption quantum. This one
does, so it is not a property of the program: **the AOT/interpreter instruction
counts agree by ALIGNMENT, not by construction**, and `test/aot.clj`'s "the
same instruction count" is passing for four of its five programs the way a
coin lands heads.

**This CORRECTS what I wrote a firing earlier.** That entry says "the standing
`colls` red is not a slice-boundary effect", reasoning that `arith` trips 63
times and is exact while `colls` trips 16 and is off by four. The reasoning is
sound and the conclusion was too strong: it rules out a SIMPLE PER-TRIP error,
which it does, and I generalised that to "not a boundary effect at all", which
the `SLICE` sweep refutes. Non-correlation with a count is not absence of a
mechanism -- an alignment effect is exactly one that has a boundary cause and
no proportionality to boundary COUNT.

**And it unifies the two open threads.** The tick-order fix for the
`conform-hosts` gas row was blocked because its AOT residual "is a function of
where the boundaries land". That is this. One defect, showing up as a standing
red in `test/aot.clj` and as the obstacle to closing the last 34 of the gas
row. Fixing it is the thing that unblocks both; compensating for it in either
place separately is what failed twice.

**Refuted here, so it is not re-tried: frame demotion.** `aot_tick`'s trip sets
`f.aot_ip = AOT_NEVER_U32`, which reads as "this frame stops re-entering
compiled code", and would explain alignment-dependence neatly. It is not that.
`aot_ticks` -- back-edges executed IN compiled code -- barely moves while trips
vary four-fold:

    SLICE 8192   20 trips   3,088 aot_ticks
    SLICE 4096   34 trips   3,102 aot_ticks
    SLICE 2048   78 trips   3,146 aot_ticks

Compiled code keeps running across trips, so the frame is re-armed somewhere
(`vm.rs`'s `Parked::Yielded` sets `aot_ip = next_ip`). Three mechanisms have
now been proposed for this gap and all three measured false: per-trip, a fixed
constant, and demotion.

#### The candidate is REFUTED, and the mechanism is the PREEMPTION COUNT, 2026-09-19

The candidate was that a chunk's static gas fails to match the instructions
actually executed before a hand-over. It does not fail. `C_AOT_GAS` and the
six per-door counters exist for exactly this question -- their own header names
this `colls` row -- and swept across `SLICE` on the same program they give
three invariants that do not move by a single unit:

    SLICE   gap   tickgas-ntick   ntick-trips   chunkgas-trips
    4096     -4            5643          1568           53,198
    2048     -2            5643          1568           53,198
    8192      0            5643          1568           53,198

Total chunk gas is EXACTLY `53 198 + trips`. Each trip adds exactly one tick
and exactly one unit of gas. **The compiled side's accounting has no alignment
slop in it at all** -- if the static-versus-actual mapping were the mechanism,
`chunkgas - trips` would wander, and it is constant to the unit. Four
mechanisms proposed for this gap, four measured false.

**It is the number of PREEMPTIONS.** `C_RESTORES` counts them, and it is the
one thing that differs:

    SLICE   interp preempts   aot preempts   delta   gap
    4096                 24             23      -1      -4
    2048                 47             47       0      -2
    8192                 12             12       0       0

At 4096 the compiled build is preempted ONE FEWER TIME than the interpreted
one. A preemption costs about five interpreter steps -- the interpreted totals
move ~5.2 per slice across this sweep -- and the gap there is four.

**Why the counts can differ, which is the part that generalises.** Compiled
code flushes gas in LUMPS: a chunk adds its whole static count in one go, and
the slice check runs at the flush rather than at each instruction inside it.
Two slice boundaries falling inside one lump therefore collapse into a single
preemption. Lumping can only MERGE preemptions, never create them -- which is
why every gap measured here is negative or zero and never positive. Compiled
code is not mischarged for the work it does; it is billed for less SCHEDULER
work, because it was interrupted less often.

**Honestly bounded: this does not explain all of it.** At `SLICE = 2048` the
two builds preempt the same number of times and the gap is still -2. So the
preemption count is the dominant term at 4096 and there is a smaller residual
underneath it. What is now settled is where NOT to look: not the chunk gas, not
per-trip, not a fixed constant, not frame demotion.

**What this means for the assertion.** `test/aot.clj` asks that compiled and
interpreted code cost the same instruction count. Under lumping that is not a
property either implementation can have exactly, because the compiled build is
genuinely preempted a different number of times and preemption is billed. The
question the next attempt has to answer is not "where is the missing charge"
but "should scheduler work be inside the number these two are compared on" --
which is the same question `resource-limits` has open about what gas MEANS, and
it is a decision rather than a defect.

**DO NOT close this by moving `SLICE`.** The gap is 0 at 8192 for both sizes
tested, which makes "set it to 8192" look like a fix. It is the same move as
widening a tolerance to cover an unexplained difference, which
`bin/conform-hosts` already warns about at length on its gas row: a quantum
chosen to make today's programs agree will be wrong for tomorrow's, and the
red would come back as a mystery with its history erased.

**The tree is at baseline**, verified by diff of `conc.rs` and `vm.rs` against
copies taken before the sweep, `aot.rs` compensation intact, units rebuilt
plain, and `test/aot.clj` back to its single `colls` failure at 112 464/112 460.

### `test/tables.clj`'s red row is the BUILDER, not the encoding, 2026-09-17

"a constant column costs nothing per row" fails by asking for 120 000 bytes of
saving and seeing 32 288. Both halves of that turn out to be true statements
about different things.

**The encoding works, completely.** Measured on what SURVIVES a collection with
only the table live -- a one-column table of 20 000 rows, churned until the
collector had run several times, reading `old-live` from `flint.rt/gc-stats`:

    a column that varies   old-live 190 376
    a column that does not old-live  29 744   -> 160 632 saved

160 632 against the 160 000 a 20 000-row column holds. So a constant column
really does cost nothing per row to HOLD, which is what the row claims.

**The builder pays for it anyway.** `new_table` (`kgen/rt/tablebuild.rs`) does
this per chunk, per column:

    let col = self.new_obj(TY_NODE, take);   // the full 256-slot run, always
    ... fill it, type-checking each value ...
    self.set(chi, CH_BASE + id, col);
    self.collapse(chi, id);                  // and only NOW reduce to a constant

The run is garbage the moment `collapse` runs and the finished table never
keeps it -- but it was live at the peak. So peak live counts runs the table
does not hold, and the saving it can see is only what happens to be collected
before the peak: 32 288 of 160 632.

**So the row is right to be red**, and the fix is in the builder rather than in
the test. Scanning the chunk's values for the column BEFORE allocating -- they
are already being read to type-check -- lets the constant case skip the run
entirely. One extra comparison per value, no allocation, and then peak,
allocation and retained all agree with each other and with the claim.

It is `kin/tablebuild.kin`, so it is one source and three targets, and it wants
a drivers case that a builder which allocates-then-collapses would fail. The
obvious case does NOT distinguish them: both produce a table whose column reads
back constant. What separates them is bytes allocated while building, which is
what the case has to assert.

**A WRONG TURN, recorded because it looked right.** The first move was to point
the test at `old-live` instead of peak, since that is what "costs to hold"
means. It passed on the one-column probe above and then inverted on the test's
own two-column program -- `same` retained 1 488 192 against `vary`'s 356 672 --
because `old-live` depends on when the collector promoted, and a churn loop
promotes the garbage as readily as the table. A GC-timing-dependent number is
not an instrument. The edit was inverted and the test is byte-for-byte as it
was, bar a comment recording why the row stands.

### The constant column allocates no run now, 2026-09-17

`kin/tablebuild.kin` builds a chunk's column WITHOUT allocating a run until a
value differs. `test/tables.clj` is green.

**What changed.** `new_table` used to allocate the full `CHUNK`-slot node for
every column of every chunk, fill it, and hand it to `collapse`, which threw it
away again when the column turned out constant. It now remembers the first
value, compares each one against it, and allocates only at the first that
differs -- backfilling the rows already seen, which all held the first value.
One pass still, one comparison per value, and the varying case reads each row
exactly once as before.

    allocation saved by a constant column, 20 000 rows:
      before   47 224
      after   207 856

**The drivers case had to weigh the BUILD.** Both builders produce an identical
table -- that is the whole point of `collapse` -- so no field that inspects the
result can tell them apart. `saved=38` reads the probe allocator's bump either
side of two builds of the same six-row table, one with a varying column and one
without, and is DERIVED: six rows at `CHUNK` 4 is two chunks, `take` 4 then 2,
and the probe charges `n + 16` a node, so the runs not made are 20 and 18. The
old builder was reinstated to check: it scores `saved=0` and passes every other
field in the file unchanged.

**A REAL BUG THE FIRST VERSION HAD, worth recording because the old shape hid
it.** Moving the allocation inside the row loop invalidates every host local
around it -- a copying collector moves what they point at. The old builder
allocated BEFORE reading the column's name and type, so it never had to care.
The first version of this did not root them and the type came back as `:?`,
refusing a legal keyword at row 5 890. Name, declared type, the first value and
the current value are all rooted now. `val-eq` can allocate too (comparing two
ropes flattens one), which is why the current value lives in a reused root
rather than a host local across the comparison.

**AND THE TEST'S INSTRUMENT WAS WRONG, for the third time.** `stat_heap_used`
reported the heap's SIZE. `stat_peak_live` was sampled when the collector ran,
so a build that allocates less collects less often and is sampled elsewhere --
with the builder fixed, the constant column's peak came out 27 584 HIGHER than
the varying one's, which reads as a regression and is an artefact. The row now
measures ALLOCATION, which is deterministic by construction here
(`DECISIONS.md#resource-limits`) and has no dependence on when a collection
happened. Peak is still printed: watching it disagree with allocation is what
caught the builder.

The earlier note in this file that the row "is right to be red" and that the
fix belongs in the builder stands -- this is that fix. What it got wrong was
expecting peak to show it afterwards.

### A stale pointer in `check_sendable_at`, and the PORTS were already right

`test/document.clj`'s stale-pointer row was red with **6 stale pushes** (writes
0, roots 0, coverage healthy: 8 collections walked, 416 886 pushes checked).
Found, fixed, and the row is green.

**Where.** `check_sendable_at`'s map and set branches cannot call back into
themselves from inside `map_for_each` -- the borrow is already held -- so they
gather the children into a host `Vec<Value>` first and then loop:

    for it in items {
        let ii = self.push(it);                     // <- line 1396
        out = self.check_sendable_at(self.r(ii), depth + 1, carry);

That vector is host memory and no root at all, and `check_sendable_at`
ALLOCATES. So checking the first child moved every value still sitting in the
vector, and the next push handed the collector an address that had already been
forwarded. The sequential branch never had it, because it re-derives `first`
from a rooted seq each time round.

**The fix** is `check_each`: push the whole batch while it is still fresh --
nothing allocates between the gather and the pushes -- then read each child
BACK out of the shadow stack, which the collector updates. Both branches use it.

**AND THE PORTS DID NOT HAVE THIS BUG.** They build a flint VECTOR and root it
(`Sets.elementVector`, `rt.push(...)`) where native used a host `Vec`. So for
once the divergence ran the other way: two hand-written mirrors were right and
the original was wrong. Worth remembering next time a difference is assumed to
be the ports lagging.

**How it was found**, because the detector names what moved and not who moved
it. `push` was given `#[track_caller]` under `diagnostics` and the stale-push
recorder stored `Location::caller().line()` plus an FNV-1a hash of the file --
`line 1396, hash 2179327419`, which matches `runtime/src/conc.rs` and nothing
else in the tree. That is a five-minute instrument for a class of bug that
otherwise costs a day, and it is worth rebuilding rather than reasoning about
the next time `stat_stale_push` is non-zero. It was reverted afterwards:
`track_caller` on `push` adds a hidden argument to a very hot function, and
leaving it in a diagnostics build would tax every measurement taken with one.

### What is left of `test/document.clj`, characterised

One row still red: peak live 4 039 744 against 4 194 304 of content, where the
claim is under a third. The module is holding 96% of the answer, which is what
waves exist to prevent.

**It is not the guest.** The script sums byte counts and keeps nothing;
`rpc/drain-each` receives one message, calls `f`, and recurs, so each wave goes
out of scope. The port's ring does clear consumed slots -- `ring-dequeue` CASes
the taken slot back to `EMPTY`. And the test's own table shows peak flat at
~240 KB for small documents, so this is the content and not the structure.

That leaves the delivery side: if the store hands over all sixty-four waves
before the guest reads any, the inbox holds the whole answer at once and peak is
the total by construction. `DocStore` is test-side infrastructure in
`test/document.mjs` with its own `budgetBytes`, so the next step is to measure
WHEN it delivers relative to when the guest asks -- not to read more library
code, which has now been cleared twice.

### `test/document.clj`'s memory row, narrowed by elimination

One row still red: peak live 4 039 744 against 4 194 304 of content, where the
claim is under a third. What follows is what has been RULED OUT by measurement,
so the next attempt starts where this one stopped rather than where it started.

**It is not the guest, and not the library.** The script sums byte counts and
keeps nothing. `rpc/drain-each` receives one message, calls `f`, and recurs, so
each wave leaves scope. `ring-dequeue` CASes a taken slot back to `EMPTY`. And
both `PT_BYTES` release sites decrement correctly when a message leaves the
queue -- on guest receive and on host drain.

**It is not the ring count**, which is the reading the numbers invited and it
is wrong. Peak looked exactly like `RING_MESSAGES` (64) times the wave size:

    budget 65536 -> 64 waves  -> peak 4 039 744   (64 x 64 KB)
    budget  8192 -> 512 waves -> peak   807 296

So `RING_MESSAGES` was halved to 32 and rebuilt. Peak came back **4 038 976** --
768 bytes different, which is nothing. The ring is not what holds them, and the
apparent arithmetic was a coincidence of two numbers that both happened to be
64.

**The byte bound does not bind either.** `DEFAULT_BRIDGE_CAP` is 1 MB and
`host_deliver` checks it, so at 64 KB a wave it should admit about sixteen. Peak
is four times that. Since the release sites are correct, the queue itself is
within its bound at any instant -- which means what is live is RECEIVED waves,
not queued ones.

**What the two budgets actually say.** Subtracting the ~240 KB floor the test's
own table shows for a small document, the live set is 58 waves' worth at 64 KB
and 69 at 8 KB. Neither a constant count nor a constant size -- so the retainer
scales with something not yet named, and guessing which has now been wrong
three times in this row alone.

**The instrument to use next is the snapshot inspector, not more reading.** It
is repaired and it answers exactly this question: capture with the content live
and take a type census, as was done for the parked-thread hang. That says WHAT
is retained -- decoded strings, wave vectors, event records -- and the name of
the type is the name of the bug. Every reading in this entry was reached by
reasoning about the code and two of them were wrong.

### NAMED: `stat_peak_live` counts old-space garbage, and the document row is right about the code

The memory row is diagnosed. The program's memory behaviour is CORRECT -- about
530 KB is ever genuinely live against 4 MB of content -- and the number the row
reads is not live bytes.

**`peak_live` is `old_live + young_used`, sampled after every collection.**
`old_live` is incremented on every old-space allocation and only recomputed to
the truth by `sweep_old`, which runs at a MAJOR. So after a minor it counts
every old object allocated since the last major, alive or dead. `note_peak`'s
own comment says it samples "when the numbers mean something"; after a minor,
for old space, they do not.

**`LARGE_OBJECT` is 16 KB, so a 64 KB wave is born in old space.** Sixty-four of
them is 4 MB of old allocation between majors, which is the whole of the
reported peak. An 8 KB wave is born young and swept by minors, which is why the
same content measured 807 296 at that budget -- a difference the earlier reading
could not explain and this one predicts.

**Proved by a controlled change, not by argument.** `LARGE_OBJECT` raised to
256 KB so the waves are born young, nothing else touched:

    LARGE_OBJECT 16 KB    peak 4 039 744   FAIL
    LARGE_OBJECT 256 KB   peak   530 456   documents: ok

Restored, and the 4 039 744 came back. The census taken mid-run says the same
thing from the other side: at wave 40 the live set is 13 `BYTES` objects
totalling 856 856 -- about the 1 MB delivery cap, exactly as designed -- beside
2 901 576 bytes of `FREE` blocks that `old_live` was still counting.

**So the fix is in the accounting, and it is a decision rather than a defect.**
Peak LIVE cannot be known for old space between majors; that is what generational
collection means. Three shapes, and they differ in which way they are wrong:

1. sample only after a major -- honest, and misses a young spike between them;
2. after a minor, use the old_live recomputed at the last major -- under-counts
   genuinely-live new old objects, and a memory GUARD that under-counts passes
   when it should fail, which is the worse direction;
3. keep the number and rename it, recording that it is an upper bound, and give
   the memory rows a second counter sampled only after majors.

(3) is the one that breaks nothing and stops the docstring being false. Any of
them changes a published diagnostic that several memory rows assert, which is
why it is written down rather than chosen here.

**And the row itself is not wrong.** It asks that peak stay under a third of the
content, and under a truthful instrument it is an eighth. Whatever is done to
the counter, the claim stands.

### `check-builtin-coverage`: a stale exemption, and the check earning its keep

Green. `flint/request` was in `ACCOUNTED` on the grounds that covering it "means
teaching three separate drivers, not writing a conformance program" -- and then
`runtimes/conform-host` was added to the glob, which is exactly where those
drivers live. `hostreq.cljc` calls `host/request` and has been covering it ever
since; the exemption outlived its reason by however long that has been.

Verified before acting on it rather than taking the check's word: `hostreq.cljc`
requires `flint.host` and calls `host/request "clock"`, and that directory is
globbed. Removed, and the reasoning around it rewritten so the comment no longer
argues for an entry that is gone.

The check was then made to fail on purpose -- accounting for `flint/add`, which
is exercised by nearly everything -- and it reported the failure. It is doing
exactly the job it exists for: an exemption list nobody re-checks is a list that
silently stops being true.

### `test/selfhost.clj`: gen0 is built by a path that yields an uncallable module

Localised, not fixed. The fixpoint test builds gen0 (the compiler compiled by
babashka, linked by `link/compose` in `build-module!`), then asks it to compile
the compiler. The call is never answered:

    gen0  linked out/flintc-gen0.wasm 763274 bytes
    node failed: flint: the call to flint.selfhost/main was never answered

**The spec and the compiler source are fine.** The SAME spec handed to
`dist/flintc.wasm` -- same sources, built by `bin/flint` -- runs **439 527 001
steps**, which is a real compile doing real work. gen0 stops after **135 373**.

**gen0 has no running control plane.** Traced, the pump gets three iterations and
codes 2, 2, 0: the sandbox settles and closes both ports without answering, and
`OUT` is empty, so nothing failed. Status 0 means nothing was parked on a
bridge, which is precisely the shape of a module whose control plane never
started -- `boot_system_thread_once` returns silently when it cannot start one.

**It is not the exports/reachability path**, which was the obvious suspect given
`compile-image`'s comment about `test/shake.clj` getting "a module nothing could
call". Adding `:exports ['flint.system/boot]` to the spec produced a
**byte-identical** image (197 668 either way), because `extra-roots` already
adds it. So the code is kept; something else stops it running.

**The next instrument is one temporary export.** `boot_system_thread_once` has
three early returns -- no system port, `ensure_started` false, `var_named
"flint.system/boot"` absent -- and nothing currently distinguishes them from
outside. A probe reporting which one fires names the cause in one build. Worth
noting as a hint and not a finding: `strings` counts the name once in gen0 and
twice in `dist/flintc.wasm`, which would fit `var_named` finding nothing, but
string deduplication could explain it just as well and it has not been checked.

### NAMED: gen0 has no `flint.system/boot` in its var-name table

The selfhost fixpoint's uncallable module is down to one fact. A probe on
`boot_system_thread_once`'s five early returns, plus the table it consults:

    out/flintc-gen0.wasm   stage 4 (initialisers ran)
                           var_names = 721   control lookup ok = 1

Stage 4 means it got past `ensure_started` and then `var_named
"flint.system/boot"` answered None. The table is not broken -- 721 entries, and
a control lookup of `flint.selfhost/main` in the same image SUCCEEDS. So
`flint.system/boot` is specifically absent, the control plane never spawns,
nothing parks on a bridge, the sandbox settles, and the call is never answered.

**Note the probe had to be a HIGH-WATER MARK to say anything.** Recording the
last call's progress reported "stage 1, already booted" -- true and useless,
because `drive` calls this every iteration and every call after the first
short-circuits. The furthest stage reached is the question; the last one is
noise. Worth remembering for any once-only path instrumented this way.

**And `extra-roots` is not sufficient, which is the interesting half.**
`compile-image` adds `'flint.system/boot` to `extra-roots` precisely so no
caller has to, and its comment records `test/shake.clj` getting "a module
nothing could call" before that was done. That keeps the code REACHABLE. It
does not put the var's NAME in the image, which is what `var_named` reads --
and `var_named` is the only way the runtime can find the thunk to spawn.

Adding `:exports ['flint.system/boot]` to the spec makes no difference at all:
the image is **byte-identical**, 197 668 either way, because `extra-roots`
already contained it. So the exemption that was supposed to protect every
caller protects the wrong half of the requirement.

**What to check next, in one step.** `var-slot` (`src/flint/image.cljc`) records
a name for every var it gives a global slot, so a kept `def` should have one.
Either the item is not actually being kept -- `extra-roots`' symbol not matching
any item's `:defines`, in which case the comment's promise has never worked and
`bin/flint` is carried by its own `:exports` through a DIFFERENT entry point
(`compile-project`, not `compile-image`) -- or the item is kept and emitted
without a slot. Printing the `:defines` that `extra-roots` matches against, for
this spec, separates those two in one bb run and needs no rebuild.

The same probe is worth keeping in mind as a permanent fixture rather than a
temporary one: `boot_system_thread_once` has five silent early returns in every
runtime, and "the sandbox quietly cannot be called" has now cost two separate
investigations. A status byte saying which precondition failed would have
answered both in a minute.

### FIXED: the selfhost spec never compiled the control plane

`test/selfhost.clj`'s source set is built by its own `collect`, which follows
`:require` from `flint.selfhost` and `clojure.core`. **Nothing requires
`flint.system`** -- bootstrap spawns it by NAME -- so the namespace was never in
the spec at all. `serve-calls` and `unbind` appear zero times in a dumped spec.

So `extra-roots`' `'flint.system/boot` rooted a symbol with no source behind it:
the item did not exist, no var slot was made, no name reached the image, and
`var_named` answered nil. The module settled on its first drive and answered
nothing. `flint.system` added as a `collect` root, and the image grew from
197 668 to 205 688 bytes with natives from 159 to 197 -- the control plane
arriving.

**THIS IS THE FOURTH PLACE to need that root**, and `bin/flint` predicted it in
writing: "the duplication is the point worth noticing: this file has its own
`collect` and never calls `flint.project/resolve-project`, so a root added there
reached the native CLI and not this front end." A fourth `collect` was a fourth
chance to forget, and it did.

**And `extra-roots` cannot cover this**, which is worth stating plainly because
its comment implies otherwise. It roots a SYMBOL for reachability; it cannot
conjure a namespace that was never handed in. It protects callers who already
have the source and nobody else. The two halves of "callable" are the source
being present and the var being named, and only the second was centralised.

**A trap for the next person instrumenting this.** `src/flint/*.cljc` is
compiled BY FLINT during selfhost, so a probe using `binding`, `*err*` or
`System/getenv` in the compiler's own source does not fail at the probe -- it
fails as a flint compile error inside `compile-image`, naming the form and not
the reason. Probes for this path belong outside `src/`.

### The `serve-calls` diagnostic is in, and the objection was the writer bug

Recorded as held back earlier: answering a failed send grew every module and
pushed a marginal reply over the encoder's ceiling, turning a passing compile
into a failing one. That ceiling WAS the stale-writer bug in `wire-str`, since
fixed -- 2 MB replies cross now -- so the objection no longer exists. It is in:

    pure module   490 471 -> 490 600 bytes   (+129, budget 500 000)
    four compiles plain / exports / exports-one / exports-self: all ok
    ESM build and its selftest: green

129 bytes, not the thousands the first reading feared; what broke last time was
the cliff, not the size.

### And selfhost has a SECOND failure, which is not the test's

With the control plane compiled in, the fixpoint fails differently: `the host
pump made no progress`. It is not the build path, because the SAME spec fails
the same way on `dist/flintc.wasm`, the shipped compiler. And it is not a
dropped answer, because the diagnostic above is now in and reports nothing --
the send is never reached.

What the trace says: the first resume runs **439 442 900** steps, and then every
further iteration adds about **42** -- the `reap_ports` housekeeping signature of
a sandbox with nothing runnable and the control plane parked. So the compile
stops short and the call thread is gone or parked without answering.

Next step is the pump trace already used twice here, on `dist/flintc.wasm` with
this spec: resume codes and `stat_steps` per iteration, plus the host's event
list, which says whether the thread is waiting on a host request nobody answers.

### The second selfhost failure is the PRODUCTION runtime, and a failed thread is invisible

Two findings, and the second matters more than the first.

**The compile stalls three quarters of the way through, and only without
diagnostics.** Same `bin/flint` command, same sources, only the units differ:

    diagnostics units   code 0   608 154 297 steps   reply 276 891 chars
    production units    code 1   459 434 875 steps   "the host pump made no progress"

Production stops at 76% of the work, so this is NOT the reply path -- the reply
is never built. It is not the heap cap either: 200 MB, 800 MB and 3 GB all fail
identically. Small compiles succeed on production units, so it is pressure- or
timing-dependent, and the diagnostics build not reproducing it is the shape of a
memory-safety bug whose detector only exists in the build that does not hit it.

The pump trace is unambiguous: first resume runs 459 434 875 steps, then every
iteration adds ~42 -- `reap_ports` housekeeping -- with ONE host event in the
whole run (`retain`). So nothing is runnable, nothing is pending, and the
control plane is parked.

**A FAILED CALL THREAD CANNOT BE REPORTED once a control plane exists, and that
is the third time this blindness has bitten.** `flint_resume` renders a failure
only when `status == 0 && failed()`. With a control plane parked on the system
port, `sched_needs_host` is true, `drive` returns 2, and status is NEVER 0 --
so a thread that dies takes its error with it and the host sees only "needs
host" for ever. The same structural fact hid the gate's escape and made
`report_deadlock` unreachable.

That is worth fixing on its own merits, ahead of the compile bug it is currently
hiding: the runtime knows a thread failed and has nowhere to say so. Either
`flint_resume` should render a failure regardless of status, or a failed thread
with no `:tx` to answer on should be surfaced the way `settled_answer` surfaces
one at exit. Until then, every silent death in a called sandbox presents as this
same message, which is why three separate investigations have started from it.

**What is ruled out for the compile itself:** the reply path (never reached, and
the `serve-calls` diagnostic reports nothing), the heap cap (three limits),
the build path (identical command), and the source set (identical). What remains
is a difference in what the production runtime does that the diagnostics one
does not -- and since `cfg(feature = "diagnostics")` is supposed to add only
counters, any place where it changes behaviour is the first thing to audit.

### The stalling compile has TWO control planes, and the call thread is DONE

Measured with a temporary probe over thread state, production units against
diagnostics ones, same command and same sources:

    production (stalls)          diagnostics (completes)
    threads: 4                   threads: 3
      id=0 DONE                    id=0 DONE
      id=1 PARKED on port 1        id=1 PARKED on port 1
      id=2 DONE                    id=2 RUNNABLE
      id=3 PARKED on port 1

`flint_system_port()` answers **1**, so ids 1 AND 3 are both parked on the
SYSTEM port -- two `flint.system/serve` loops in one sandbox. The thread that
served the call (id 2) is **DONE**, and no reply ever reached the host, so the
sandbox sits at "needs host" for ever behind the parked pair.

**No thread failed.** A probe on `settle`'s FAILED branch, rendering the first
failure's kind and message, recorded nothing at all. So this is not an error
being swallowed -- the call thread finished and answered nobody.

**Two control planes should not be possible.** `boot_system_thread_once` guards
on `system_booted`, which is one `bool` on one `Rt` -- and the unit's `rt()` goes
through `flint_rt::abi::flint_rt_ptr()`, so the unit and the ABI share it. The
"interpreter is instantiated twice" of `DECISIONS.md#resource-limits` is two
MONOMORPHISATIONS of `run_with`, not two `Rt`s, so it cannot explain a second
flag. A host-side port collision is also out: `SYSTEM_PORT` is 1 and
`nextCallPort` starts at 2.

So a second `serve` is being spawned by a path not yet found, and the duplicate
is the thing to chase -- it is the only structural difference between the build
that completes and the build that stalls.

### AND A NEAR MISS WORTH MORE THAN THE FINDING

Reverting the probe from `abi.rs` deleted **379 lines**: the revert was written
as "cut from my probe's start to the next known anchor", and the next known
anchor was four hundred lines further down than assumed. It took out
`set_memory_limit`, `image_desc_addr`, every `stat_*` and `collect_now`.

**`bin/check` caught it immediately** -- `test/cycles.clj` failed, and the link
error named two missing exports -- which is the whole argument for running the
gate after a revert and not only after a change.

Recovery, and the shape matters given `never-checkout-in-a-dirty-tree`: a
`git diff HEAD` showed **379 deletions and zero insertions**, which proves the
working copy was a strict SUBSET of HEAD for that file, so reconstructing from
`git show HEAD:` could not lose uncommitted work. That was verified
line-by-line in the restore script before it wrote anything, rather than
assumed. The one thing genuinely lost was this session's `stat_region`
docstring correction, which lived inside the deleted span and was re-applied by
hand afterwards.

The rule to take from it: **a revert anchored at one end is not a revert.**
Delete by matching the exact inserted text, or bound the range at BOTH ends
with strings that were part of the insertion.

### The duplicate control plane is a HEISENBUG, and that is the finding

`boot_system_thread_once` spawns the control plane TWICE in the stalling build.
Measured with a bare counter -- one increment, no `track_caller`, no codegen
change -- at the spawn site inside that function:

    boot spawned the control plane 2 time(s)

**On the same `Rt`.** The pointer was recorded at each spawn and is identical
(`0x8d60`), so this is not two runtimes with two flags. `system_booted` is one
`bool`, set to true BEFORE the spawn, and the body ran twice anyway -- which
means the flag read false on the second entry. Nothing in the code writes it
back.

**And it moves when looked at.** Three probe variants, three behaviours, same
sources and same spec:

    #[track_caller] on spawn_thread   3 spawns, and the error became a DEADLOCK report
    + Rt pointer and flag fields      2 spawns, nonsense line numbers, stall returns
    + a flag read at boot's ENTRY     1 spawn, and THE COMPILE SUCCEEDS (code 0)

A bug that disappears when a `u32` is read at the top of a function is not a
logic error in that function. It is layout- or timing-sensitive memory
corruption, and `system_booted` is simply a byte near enough to whatever is
being clobbered to show it. The duplicate control plane and the lost reply are
SYMPTOMS.

**Which explains the production/diagnostics split** recorded above: the
diagnostics build has different layout and does not reproduce. That is also why
this has been so hard to see -- the runtime HAS a detector for exactly this
class, `STALE_PUSH`/`STALE_SET`/`STALE_ROOT`, and it is compiled only under
`diagnostics`, which is the build where the bug does not happen.

**So the actionable item is the detector, not the symptom.** The stale-pointer
checks should be available behind their own cfg, independent of the rest of
diagnostics, so they can run in a build that reproduces. Every instrument tried
here perturbs the bug; that one is designed to catch it and is currently locked
to the wrong build. It is a small change -- a feature flag and a handful of
`cfg` attributes -- and it is worth doing before any further guessing.

Two things NOT to repeat: instrumenting with `#[track_caller]` changes
inlining, and adding fields to a static array changes layout. Both moved the
bug. A counter alone did not, and is the only probe here whose reading can be
trusted.

### The stale detector CANNOT be separated from diagnostics, and that is the blocker

Tried, and the answer is a clean negative worth recording rather than a fix.

A `gcchecks` feature was added to run the stale-pointer checks without the rest
of `diagnostics`, so they could run in the build that actually reproduces the
corruption. Two of the three separate cleanly; the one that matters does not.

* **the stale-ROOT scan** (after a collection, walk every root for a pointer
  into the abandoned half) -- separates, needs only `in_live_half` widened;
* **the stale-PUSH check** (`check_push`/`note_stale_push`) -- separates;
* **the stale-SET check** -- DOES NOT. It reads `self.in_collect` and
  `CUR_NATIVE`, both diagnostics-only, so widening its `cfg` fails to compile.
  And it is the one that catches a stale WRITE at the instant it happens, which
  is the class this bug looks like.

**What the two separable halves found: nothing.** Built with the checks on and
the rest of diagnostics off, the compile reported:

    stale roots: 0    stale pushes: 0    collections walked: 934

So across 934 collections no root survived into the abandoned half and no stale
value was pushed. Either the corruption is a stale WRITE -- which only the
unseparable check catches -- or the checks moved the bug again, because the
failure ALSO changed: `memory limit exceeded` this time, a third distinct
symptom from the same sources.

**So the next step is concrete and small.** Separating `stale-SET` means
bringing `in_collect` and `CUR_NATIVE` along -- both are cheap bookkeeping, not
instrumentation, and neither belongs to the rest of the diagnostics surface.
With those three widened, the detector built for this class of bug can finally
run in a build that exhibits it. That is a better use of an hour than another
probe, because every probe tried so far has moved the failure:

    no probe                    stall, "host pump made no progress"
    #[track_caller]             3 boot spawns, deadlock report
    static array fields         2 boot spawns, stall
    a flag read at boot entry   1 boot spawn, COMPILE SUCCEEDS
    gcchecks (2 of 3 checks)    memory limit exceeded, 0 stale found

Five configurations, five behaviours. Nothing about this bug should be believed
from a single reading, including the zeros above.

The probe was fully reverted -- `bin/build-units` and `runtime/Cargo.toml` are
byte-identical to before it, `bin/check` is green -- and the `gcchecks` feature
is NOT in the tree. It is written up here because the shape of the separation,
and exactly which three `cfg`s have to move, is the part that took the time.

### NOT corruption: `var-named` hands `answer` a LIST, and the detector says so

The stale-pointer detector was separated from `diagnostics` and run on the
failing compile. It found nothing, and the real error came out instead:

    stale SET 0   stale ROOT 0   stale PUSH 0   collections walked 750
    SendFailed: this call's answer could not be sent back:
      value is not a function (a list, 2 args) in serve-calls

So the memory-corruption hypothesis of the previous entry is WRONG. Nothing
stale was written, rooted or pushed across 750 collections. What actually
happens is in guest code: `flint.system/answer` does

    f (flint.rt/var-named nm)
    ... (if (some? f) {:tx tx :op :return :value (apply f (or (:args m) []))} ...)

and `f` came back a LIST. `some?` is satisfied by a list, so the guard passes
and `apply` fails on it. The frame is `serve-calls`, the throw escapes `answer`,
and before the diagnostic shipped earlier today it was swallowed -- which is the
whole reason this took five ticks to read.

**The separation, for whoever needs it again.** Three checks, and the last one
is the one that took work: `STALE_SET` reads `in_collect` and `CUR_NATIVE`, and
the scan needs `in_live_half`. Fourteen `cfg` attributes across `gc.rs`,
`rt.rs` and `vm.rs` widen cleanly to `any(feature = "diagnostics", feature =
"gcchecks")`, and `stat_steps` can carry the counters packed so no export or
unit manifest has to change. It is NOT in the tree; this paragraph is the
recipe.

**AND THE ZEROS WERE NEARLY BELIEVED WHEN THEY MEANT NOTHING.** The first
attempt widened `cfg`s with a "nearest preceding attribute" heuristic, which
matched the statics and missed both check BLOCKS -- the attributes there sit
above an `unsafe {` with ordinary statements between. Everything reported 0,
including `collections walked`, and 0 collections for a compile that allocates
gigabytes is the only reason it was caught. A program allocating 200 000 maps
then confirmed coverage at 31 collections before any zero was trusted.

**A coverage field is not optional in a detector.** `STALE_ROOT[5]` -- how many
collections the scan actually walked -- is what separated "nothing is wrong"
from "nothing ran". `test/document.clj`'s stale row already asserts its own
coverage before its zero, for exactly this reason; that instinct was right and
this is the second time it has paid.

**Next step.** `var-named` returning a list is now an ordinary guest-visible
bug: find what `flint.rt/var-named "flint.selfhost/main"` resolves to in a
gen0-style image and why it is not the function. `some?` being satisfied by a
list also argues for `answer` checking callability rather than presence -- the
message it produces today names the symptom three layers from the cause.

### `var-named` is NOT the culprit, and the detector does not cover this

`answer` now asks whether the resolved value is CALLABLE rather than merely
present, and reports the kind it found when it is not:

    (if (fn? f) ... {:message "this image has no callable `nm` -- its var holds a <kind>"})

**It never fires.** So `flint.rt/var-named` hands back a genuine function for
the called name, and the previous entry's reading -- "var-named returns a list"
-- is wrong. The list is something else in that frame.

**Narrowing what is left.** The message is `value is not a function (a list, 2
args) in serve-calls`. Inside `serve-calls` the only var-resolved two-argument
call is `port/send`; everything else there is one-argument or a builtin. So a
global slot holding `flint.port/send` reads as a list at that moment.

**And the same send works seconds later**, which is the part to keep in view:
the `SendFailed` diagnostic is itself a `port/send`, and its message reached the
host. One run, same var, two different values.

**Why the stale detector's zeros do not contradict that.** It catches DANGLING
pointers -- a root or a write pointing into the abandoned half. A global slot
holding a valid list where a function belongs is not stale; it is the wrong
VALUE, correctly formed. `STALE_ROOT` walks the roots looking for bad addresses,
not for unexpected types, so 0 across 750 collections rules out a stale pointer
and says nothing about a wild write of a live object. That distinction was not
clear when the detector was unlocked and it is the reason those zeros read as
more exonerating than they were.

**Next instrument**, and it is small: `serve-calls` should report WHICH call
failed, not just that one did. Wrapping the send alone -- rather than the send
and `answer` together -- separates "the answer could not be built" from "the
answer could not be sent", and the current message conflates them. A kind read
off `flint.port/send`'s var immediately before the call would confirm or kill
the narrowing above in one run.

**The callability check stays.** It is a better question than `some?` asked in
the same place, its message names the var and the kind rather than leaving
`apply` to complain about an arity three layers away, and it costs 295 bytes of
module (490 471 -> 490 766, budget 500 000). `test/system.clj` and
`test/threads.clj` are green with it.

### SEVEN configurations, seven behaviours: stop perturbing the runtime

`serve-calls` now separates building the answer from sending it, so the message
can finally say which failed. It did not get the chance: under the plain
production build the failure is still a silent hang, and the ONE configuration
that had reported `SendFailed` -- the `gcchecks` build -- now hangs too, because
the split itself changed the module.

That is the seventh configuration and the seventh distinct behaviour from one
set of sources:

    plain production            hang, "host pump made no progress"
    diagnostics                 SUCCEEDS
    #[track_caller]             3 boot spawns, deadlock report
    static array fields         2 boot spawns, hang
    flag read at boot entry     SUCCEEDS
    gcchecks                    SendFailed: value is not a function (a list, 2 args)
    gcchecks + answer/send split  hang again

**So the method is wrong, not the effort.** Every instrument that touches the
RUNTIME moves the bug, because what is being perturbed is exactly what the bug
is sensitive to. Reading it by adding one more probe has been tried seven times
and has produced seven readings, at least two of which were wrong when written
down.

**What to do instead: shrink the INPUT, not the runtime.** The spec is 576 KB of
EDN naming ~60 namespaces, and it is DATA -- removing namespaces from it changes
nothing about the module's layout, so the bug cannot dodge the way it has been
dodging. Delta-debugging it to the smallest spec that still fails gives a
reproducer small enough to read, and if it disappears as the spec shrinks then
the threshold itself is the finding. Either outcome beats a seventh probe.

`FLINT_DUMP_SPEC` already writes the spec out, and `host/flint-file.mjs` already
takes it from a file, so the loop is: drop a namespace, rerun, keep or restore.
No rebuild between iterations -- which is also why it will be fast.

**Two improvements stay, both earned along the way.**

`answer` asks `fn?` rather than `some?` and names the kind it found -- so a var
that resolves to the wrong thing is reported against the NAME rather than left
for `apply` to complain about an arity three layers away. `serve-calls` no
longer reports "the answer could not be sent back" for a failure that happened
while BUILDING the answer; those are different faults in different code and one
`try` around both could not tell them apart. 408 bytes together
(490 471 -> 490 879, budget 500 000), `system` and `threads` green.

### The method worked: a minimal reproducer, 31 namespaces down to 8

Shrinking the INPUT instead of the runtime, as the previous entry argued. It
took one tick and produced what seven probes could not.

**First, the signal was made cheap.** The hang costs a million pump iterations
at the default guard; a HOST-SIDE copy of `guest.js` with the guard at 300 turns
it into a **3.4 second** answer. Host-side is the whole point -- the module is
untouched, so the bug cannot dodge the way it dodged every runtime probe.

**Then three findings in order, each from one run.**

* **It is not the output size.** A synthetic program of 200, 1 000 and 3 000
  functions all compile fine. Size of what is being COMPILED is not it.
* **It is not the entry.** Replacing `flint.selfhost/main` with
  `(defn main [_] "hi")` while keeping the same sources still HANGS. So the
  trigger is in ANALYSING the source set, not in compiling the program.
* **And that makes the sources freely shrinkable**, because with a trivial
  entry nothing requires them. Two greedy passes -- drop a namespace, keep the
  drop if it still hangs; then drop each namespace together with everything
  whose source mentions it -- reach a fixpoint:

      31 namespaces, 576 KB   ->   8 namespaces, 187 KB

      flint.aot  flint.regex  flint.nfa  flint.protocols
      clojure.core  flint.wasm  clojure.string  app

Every further removal breaks compilation rather than fixing the hang, so this
is minimal for this shrink axis. `app` is three lines.

**Kept for the next attempt** in the scratchpad: `min-spec.edn`, the `gen0.wasm`
it fails against, and the runner. Keep the pair together, and if it stops
reproducing, re-shrink rather than assume the bug is gone. (An earlier draft
said the binary matters because the bug moves with the module's layout. That
was never measured and is wrong: a gen0 rebuilt 723 bytes larger reproduces at
the same threshold, to the element -- see `doc/goals/kin-port.md`.)

**What it points at.** The surviving set is clojure.core's closure plus the
regex/NFA machinery and `flint.aot`/`flint.wasm` -- and none of it is REACHED by
a three-line entry, so all of it is shaken out of the result. The fault is
therefore in reading and analysing those sources, on a path whose output is then
discarded. That is a much smaller haystack than "the compiler compiles itself",
and the next shrink axis is the SOURCE TEXT of the eight, which the same loop
can chew on without a rebuild.

### A nil message is not a goodbye

`flint.port/receive` answers nil for three different things. The port is closed
and drained; or the peer sent a nil; or, on a bridge, the decode produced
nothing --

    (let [r (flint.rt/port-receive-reader p)]
      (when (some? r) (wire/read-from r)))

-- and only the first is an end of stream. `serve-calls` used to read all three
as the first and leave its loop, which is worse than it sounds: the port stays
BOUND with no server on it, so that call and every later call on it is lost,
and the host finds out only when its pump guard gives up a million iterations
later. The sandbox is fine, the control plane is fine, one door is quietly
walled up.

It needs no exotic state to see. Against any module at all:

    const c = inst.caller();
    c.call('app/main', arg);        // answers
    inst.deliver(c.port, null);     // a message, not a close
    c.call('app/main', arg);        // never answered -- the server is gone

So the loop ASKS rather than believes: nil ends it only when `port/closed?`
says nothing further can arrive -- closed, half-closed or orphaned -- and
anything else means this was a message it could not serve, so serving goes on.
It cannot spin, because the nil consumed a message and the next `receive` parks
like any other; and `unbind` still ends the thread, by closing the port, which
is what `closed?` then reports.

`test/system.cljc` carries the row, over a local channel: send nil, then call,
and require the answer. It was checked in the only way such a guard can be --
inverted on purpose, where it fails with "the program did not run: the host
pump made no progress", and restored, where it passes. 752 bytes a module,
which `flint.system` shipping everywhere makes a real number and this makes a
fair trade.

This is NOT the cause of the selfhost hang. That thread is finished by the
runtime while 25 frames deep, not by a receive; see `doc/goals/kin-port.md`.
The two were found together and are different bugs.

## the-shadow-stack-is-not-a-default

**The shadow stack is sized and placed on purpose**

**Ratified:** ☐ not signed off

**Status: shipped 2026-09-18 — `src/flint/link.cljc` links every module with
`-z stack-size=1048576 --stack-first`, and it is the only place a module is
linked.** Verified by moving the dial rather than by argument: at the old
default `test/selfhost.clj` hangs and a 178-element literal breaks the
compiler; with the flag the fixpoint passes, a 3 200-element literal compiles,
and 4 000 traps cleanly instead of corrupting.

`wasm-ld` gives a module 64 KiB of shadow stack and puts it above the static
data. Every module flint links took that default, and it is wrong twice.

**Too small.** The interpreter recurses on the Rust side in proportion to the
guest structure it walks -- measured at 320 bytes a literal element -- so
64 KiB ran out at a **178-element vector literal**. A 178-element literal is a
lookup table, not an abuse.

**Placed above the data.** An overflow then grows down into the statics and
rewrites them, and the program keeps running with its own globals corrupted.
That is how this presented, and none of it said "stack": a compile stopping
mid-analysis ten million steps short; a serving thread marked done while 25
frames deep; a second control-plane thread booting because `system_booted` had
been overwritten; `test/selfhost.clj` failing with "the host pump made no
progress". Weeks of symptoms, one cause, and the cause was in the link line.

So the link is explicit now:

    -z stack-size=1048576 --stack-first

`--stack-first` puts the stack at the bottom, so an overflow runs off address
zero and TRAPS -- `memory access out of bounds` at the instruction that did it,
rather than silent corruption discovered later by its consequences. The proof
it was always overflowing: at 64 KiB WITH `--stack-first`, a 177-element
literal traps too. It had been "passing" by scribbling something it happened
not to need.

The cost is initial memory, not file size -- 5 pages to 20, **+960 KiB a
module**, the `.wasm` unchanged. The number is a dial and the trade is
measured:

    | stack   | literal elements | initial memory |
    |---------|------------------|----------------|
    | 64 KiB  | 178              | 320 KiB        |
    | 256 KiB | ~800             | +192 KiB       |
    | 1 MiB   | ~3 400           | +960 KiB       |

1 MiB is chosen because the ceiling should be a size no one reaches by writing
ordinary code. 128 KiB is the floor -- enough for the compiler's own sources --
and it still fails a 370-element literal, which is why it is not the answer.

**There is a second stack, and it is not ours.** The recursion runs on the
EMBEDDER's native stack too, so under a stock node the ceiling is about 1 100
entries -- some 400 KiB of shadow stack -- and a `RangeError` naming wasm
frames, not our trap. A stack past ~512 KiB therefore buys nothing on stock
node, which is the honest argument for dialling this down if the memory ever
matters. It stays at 1 MiB because embedders differ and the number should not
be the thing that fails. Both limits now fail honestly, which is the part that
changed.

**This does not make the recursion bounded.** A large enough structure still
exhausts any fixed stack; what changed is that it now stops honestly instead of
corrupting the runtime.

What recurses is named, and `--stack-first` is what made it readable: a trap
carries a backtrace where silent corruption carried nothing. Forcing a lazy seq
re-enters the VM --

    flint_b_seq -> seqwalk::seq -> seqwalk::force
      -> vm::call_value -> vm::run -> vm::run_inner -> flint_b_seq -> ...

-- a six-frame unit per element, because `force` calls the thunk through a
NESTED interpreter loop rather than driving it on the frame stack it is already
standing on. Bounding it means making forcing iterative, and that is separate
work; `doc/goals/kin-port.md` has the trace and the two shapes a fix could
take.

## four-operations

**What every flint artifact exposes, on every target**
**Ratified:** ☐ not signed off
**Status: THREE FACES IN THE TREE, 2026-09-24 -- `runtimes/clr/src/rt/Artifact.cs`, `runtimes/jvm/src/com/flint/rt/Sandbox.java` and the `host` module of `units-src/flint-conc/src/lib.rs`. **FIVE STATUSES as of 2026-09-25** -- `3 Shelved` was returned by the runtime and declared by nobody, `4 Wedged` is new; see "loop had three statuses written down and four in the runtime". **ALL FOUR TARGETS HAVE THE FACE as of 2026-09-24.** Gated by `bin/check-artifact-ops` (faces against the contract, `4 of 4`) and `bin/check-four-ops` (behaviour, jvm, including compiled arities in both directions), `bin/check-clr` (behaviour, clr, same) and `bin/check-llvm` (behaviour, llvm, linked and driven from C). The `.ll` grew `flint_boot`/`flint_loop`/`flint_link` as three-line wrappers over `flint_native_boot`/`_loop`/`_link` in `nativeabi/src/lib.rs`, the way its `main` already wraps `flint_native_main`. The CLR's static surface became an instance one on 2026-09-24 -- `sealed class Artifact`, `static Artifact Boot(...)`, `Status Loop()` -- and `runtimes/clr/artifact/Check.cs` now asserts what the static version made impossible: two sandboxes booting in one load context and holding different runtimes.** This record exists FIRST and deliberately: four
implementations designed in parallel is how this project produced sixteen
version declarations, four compile-spec front ends and a `:checks` axis on one
CLI and not the other. The contract is written down before the last three are
built so they mirror it rather than each other.

An artifact IS the compiled output -- a wasm module, a JVM class, a CLR
assembly, LLVM IR. **There is no separate "image" artifact.** The bytecode is an
implementation detail carried inside it, as constant data on the class, and
never a published format.

**The artifact carries the program, NOT the runtime.** Bytecode plus AOT'd code
plus these four operations. The interpreter, the collector and the builtins come
from the host. So "self-contained" means *contains all of the program's code*,
and NOT *runs standalone* -- it requires a host carrying flint's runtime. That
distinction was stated backwards for most of a day and misled two agents; it is
the first thing to get right in any prose about an artifact.

### The three

    boot(bridge)        the bridge becomes this sandbox's SYSTEM PORT, and
                        everything is driven through it. A sandbox is ONE
                        program for its whole life (`construe-integration-bar`).
    loop()   -> Status  pump. 0 Done, 1 Threw, 2 NeedsHost -- THE EXISTING ABI
                        NUMBERS, which this project treats as the ABI itself.
    link(name, fn)      override the native called `name`.

**`prop` WAS THE FOURTH AND IS GONE, 2026-09-24.** It answered a metadata
property, and the section below is where that answer belongs instead: all three
container formats already carry namespaced metadata, readable WITHOUT executing
the artifact, which a call can never be.

The wasm implementation is what argued against it. A module cannot read its own
custom sections -- they are not in linear memory -- so `prop` needed a SECOND
copy of the metadata spliced into a data segment, a `FLINT_META_DESC` descriptor
to find it, ~100 lines of Rust to scan EDN for a key with balanced-delimiter
counting and whole-key matching, and a gate to assert the two copies agreed. All
of that to answer from inside what the container answers from outside. Written,
measured, reverted the same day.

**`loop()` returning `NeedsHost` is the RESTING state, not an error.** The
control plane is a green thread parked on the system port, so a healthy idle
sandbox reports it. A pump drains, drives, then drains AGAIN: the scheduler
reports `NeedsHost` while it holds undrained events, so a pump that skipped the
trailing drain would report work it was itself holding.

**`link` is an OVERRIDE mechanism and not a wiring one, and the reason is load
bearing.** `runtime/src/image.rs` and `Img.cs:138` leave a missing builtin NULL
rather than refusing: an image imports every builtin its namespaces MENTION, and
a program that never calls the missing one runs fine. A trivial program declares
**88** natives -- `clojure.core`'s reach, not the program's -- against 223 the
runtime carries. So the host makes ZERO `link` calls in the normal case, and a
bulk or resolver API that required all 88 would be actively WRONG: it would
reject programs that run. `link` MUST precede `boot` and refuse afterwards,
because natives resolve exactly once when the image loads.

### Metadata lives in the container, namespaced

All three formats carry arbitrary metadata a reader gets at WITHOUT executing
anything, and each has a path the platform already understands:

    wasm          a custom section        `host/modmeta.mjs` already reads it
    jvm class     a class attribute       JVMS 4.7 REQUIRES an unrecognised
                                          attribute to be silently ignored
    clr assembly  a CustomAttribute row   `GetCustomAttribute<T>()`, no reader

**NAMESPACED, and this is the part that is easy to get wrong.** All three are
flat namespaces shared with everything else in the process or the toolchain, and
JVMS 4.7's "silently ignored" rule means a collision there is QUIET. So reverse
DNS, matching the package names already in use -- `@3sln/flint` on npm,
`com._3sln.flint` on the jvm, `_3sln.Flint` on the clr. The wasm section is
`com.3sln.flint.meta`; it was the bare word `flint` until 2026-09-24.

`host/modmeta.mjs` cannot read cljc, so the name is stated twice, and
`test/modmeta.clj` asserts the two agree AND that it contains a dot -- checked
by breaking it on purpose rather than by reading it.

**Which semver relation counts as compatible is still unsettled**, and pre-1.0
the usual "same major" rule says every release breaks everything. The metadata
carries the version and nothing decides on it yet.

**NO LINKAGE REPORT.** An earlier CLR face grew `prop("natives")` and
`prop("natives-unresolved")`, because a host with a trimmed runtime would
otherwise discover a gap only when a program reached it. Dropped: the VERSION
says what must be linked, so a missing native means a version mismatch, and
`boot` FAILS rather than handing back a tally nobody reads.

*That is a real behaviour change and it is worth naming.* `runtime/src/image.rs`
and `Img.cs:138` today leave a missing builtin NULL on purpose -- "an image
imports every builtin its namespaces mention, and a program that never calls the
missing one runs fine" -- and a trivial program declares 88 of them. Under the
new rule a version-matched host carries all 88 and nothing changes; a host that
deliberately TRIMMED its builtin set would now fail to boot a program it could
have run. That trade is chosen, not overlooked.

### `loop` is called by SEVERAL REAL THREADS, except on wasm

Native, the JVM and the CLR must all expect concurrent `loop()`. wasm does not
support it today and the ABI is built so that it can later without changing
shape.

**Why wasm cannot, and why it is not a small fix.** `src/flint/link.cljc:483`
hardcodes `:memory :unshared` for every module -- a literal, not an option --
and `flint.modmeta` makes memory a COMPATIBILITY KEY, so a mismatch is a refusal
naming it. One instance therefore cannot be driven by several OS threads at all.
Flipping it invalidates every existing artifact, which is what that key is FOR,
and pulls in `SharedArrayBuffer`, atomics and host threads. Separate work.

The other three already have the machinery: `runtime/src/par.rs` is 355 lines of
"several executors inside one sandbox, one heap, K threads on it" behind
`#[cfg(feature = "parallel")]`, and `Parallel.java` mirrors it.

**What concurrency-ready costs the ABI, concretely:**

* **no implicit "current thread" anywhere.** `loop()` takes nothing and answers
  a status, which is already safe. But a `link`ed callback must carry the port
  AND the green thread explicitly -- a callback that means "the thread I happen
  to be on" cannot be called from an arbitrary executor;
* **a result travels through the BRIDGE, with its `:tx`, and never through a
  shared buffer.** `runtime/src/abi.rs` has one `static mut OUT`, and
  `finish_run` puts a run's result in it: two threads finishing at once race on
  one `Vec`. The port path is already correct -- `lib/flint/system.cljc` answers
  `{:tx n :op :call ...}` with `:return`/`:throw`, and a correlation id is what
  makes concurrent answers separable. `out_ptr`/`out_len` survive only for
  pre-`boot` `flint_load_image` refusals, which are single-threaded by
  construction;
* **re-entry is refused by a FLAG, and the flag is PER-EXECUTOR.** Set on the
  way into a callback that forbids re-entry, cleared on the way out, and every
  entry point checks it -- so a callback that calls back in is told no instead of
  corrupting a half-updated heap. A SINGLE flag would be wrong the moment
  `loop()` runs on two threads: one executor inside a callback would refuse
  another executor's legitimate entry. It belongs beside the other per-executor
  state in `gc.rs`'s `ExecRoots`, which is per-executor for the same class of
  reason -- "a shared `Vec` pushed to from the barrier would reallocate under
  another thread's push". It must also be cleared when the callback leaves by an
  ERROR path, or one refused call wedges that executor for good;
* **a `link`ed callback may not allocate, re-enter the sandbox, or block.**
  `par.rs`: "the only safepoint is the interpreter's checkpoint, between two
  bytecode instructions, where `ip` has been written back and every live value
  is on the value stack" -- because a collection MOVES objects, so a thread
  stopped elsewhere holding a `Value` in a host local resumes holding a stale
  pointer. Parking happens at a safepoint, so the park/unpark callbacks land
  legally; anything that allocated or re-entered there would not. The runtime is
  `#![no_std]` with no `Mutex` and no `Condvar`, so the guest side has atomics
  only and every blocking primitive lives host-side.

### The waiter registry is the host's, not the sandbox's

Which ports can unblock a sandbox is answered by a registry the HOST owns and
shares across sandboxes. The runtime is handed functions through `link` that
add and remove a green thread from a bridge port's waiter list; a bridge that
receives data consults that list to know what to wake.

This is the unlanded half of `bridges-are-the-only-door`'s table, arriving from
the other direction -- that table already prescribes bridge memory moving to
"the bridge's own, host-side", the executor to "one, shared", and the wake
condition to "parked thread + non-empty end".

**It means `loop` does NOT report blockers**, and that deletes two things: the
`1e6` counter in `sdks/esm/src/guest.js:67` whose message is "the host pump made
no progress" -- a magic number standing in for a signal the ABI never carried --
and a proposed fourth `Stuck` status. A host that owns the registry already
knows whether anything it holds can unblock anyone.

### MEASURED 2026-09-24: the deadlock report is unreachable where it matters

The question below was left open. Probing it found something worse than an
ambiguous status -- a four-way divergence in the most user-visible message a
runtime produces, asserted by NOTHING. A program that parks a thread on a
channel nobody writes reports:

    native, `flint run`     the host pump made no progress
    wasm, the node host     flint: the host pump made no progress
    jvm                     deadlock: 1 green thread(s) are parked and nothing
                            can wake them / thread 0 waiting on port 1
    clr                     identical to the jvm

**AND THE FIRST READING OF THIS WAS WRONG, which is worth keeping because the
wrong reading is the obvious one.** It looked like the host pump's guard was
MASKING a diagnostic the runtime had already produced. It is not. The runtime is
answering correctly and the two ports are the ones in an unusual state.

`sched-needs-host` answers true when any parked thread waits on a port that
`crosses-a-heap` -- which is `kind == K_BRIDGE` and nothing else. The control
plane is a green thread parked on the SYSTEM PORT, which is a bridge. So once a
system port exists, `sched-needs-host` is true for ever and the deadlock branch
is UNREACHABLE BY DESIGN: the host could send a call at any moment, so the
sandbox is not deadlocked, it is idle awaiting the host. Answering "deadlock"
there would be a lie.

The two ports reach the deadlock report only because their test driver installs
no system port. `bootSystemThreadOnce` returns early -- "no door yet; asked again
next drive" (`Conc.java:1534`, and `conc.rs:946` the same) -- so no control
plane, no bridge park, and a channel nobody writes really is terminal.

**So the divergence is real but it is not a runtime defect, and the fix is not in
the runtime.** Whether anything more is coming is a fact only the HOST holds, and
`guest.js`'s `1e6` counter is a guess at it. That is the argument for the
host-side waiter registry stated exactly: the runtime cannot know, the host can,
and a counter is what stands in for the answer until the host is asked.

**Nothing tests any of this.** The message is written three times -- `conc.rs`,
`Conc.java`, `Conc.cs` -- and grepping the tree for it finds no test, no
conformance row, and no assertion of any `loop` status VALUE. `kin/threadjoin.kin`
even quotes the expected output ("thread 0 waiting on thread 0") in a comment
that nothing checks. So a change to the status numbers would have broken no
test, which is not the same as being safe.

**One thing does NOT move out.** `runtime/src/conc.rs:1361`'s `report_deadlock`
detects threads waiting on EACH OTHER rather than on ports, and no host-side
port registry can see that. Today the scheduler returns status 0 for both a
clean finish and a deadlock (`kgen/rt/sched.rs:255-268`), distinguishing them
only by a diagnostic string -- so "settled" and "wedged" are the same number to
a host. That is the one case where `loop` still has to say something a host
cannot infer, and it is unresolved.

### MEASURED 2026-09-24: the two faces diverge on a STATIC vs INSTANCE surface

The first honest cross-target run found it, and it is a design question rather
than a spelling one:

    clr    static void Boot(IBridge)          static Status Loop()
    jvm    static Sandbox boot(...)           int loop()   -- an INSTANCE method

**The jvm's shape is the one that survives this record's own concurrency
requirement, and the clr's does not.** A static `Loop()` means one sandbox per
process: static state is shared, so a second sandbox in the same load context
would drive the first. The clr face already admits the symptom from the other
end -- only one flint artifact can live in a load context, because appending
cannot rename an assembly. An object per sandbox is what makes "several real
threads call `loop`" and "many sandboxes, one program each" both true at once.

So `boot` ANSWERS A SANDBOX and `loop`/`link` are that sandbox's, on every
target. On wasm, where there is no object to hand back, the equivalent is that
`boot` answers a handle the other two take -- which is also what lets one module
instance serve one sandbox rather than being a process-wide singleton.

The status values are the same three numbers spelled two ways -- `enum Status
{ Done, Threw, NeedsHost }` against `static final int DONE, THREW, NEEDS_HOST`.
The NUMBERS are the ABI and both agree on those; the contract should assert the
numbers and stop asserting the spelling.

*How this was found is the point.* `bin/check-artifact-ops` looked for the jvm
face at `.../rt/Artifact.java`, which was a GUESS; the real one is
`.../rt/Sandbox.java`. It printed "no face ..., skipping" and exited 0, so the
jvm face landed and was never checked. Each row now declares whether it is
supposed to exist, and BOTH directions fail: a built row with no face, and a
not-built row whose face exists. The moment it stopped skipping it found both
divergences above.

### Where the targets may differ, and where they may not

The SEMANTICS above are identical everywhere. The spelling is not, and forcing
it to be would be the mistake:

* the JVM and CLR pass a real bridge OBJECT. The CLR's is five methods --
  `TryTake`, `Put`, `Open`, `Answer`, `Closed` -- against wasm's fourteen
  exports, because most of those exist only to move bytes across a boundary
  that cannot pass an array or a callback;
* wasm cannot pass either. Its bridge is the host satisfying IMPORTS, and its
  `link` names a function-table slot rather than a closure -- a wasm module's
  imports are fixed at instantiation, so `link` can rebind an existing runtime
  slot and can NEVER introduce new host code;
* a class file has no data section, so the bytecode goes in the constant pool
  as modified-UTF-8 `CONSTANT_Utf8` entries, capped at 65 535 bytes each. A CLR
  assembly has `FieldRva` and takes the bytes raw at any size.

**THE `FieldRva` CHOICE PAID FOR ITSELF IN A WAY NOBODY PREDICTED.** With the
image in `.text` it contributes to no heap, so `HeapSizes` stays 0 and every
metadata heap index stays 2 bytes at ANY program size -- checked at 251 KB and
20 MB. Had it gone in `#Blob` it would cross 64 KB on any real program and widen
every blob index in every table to 4. Doing it properly came out cheaper than
the shortcut.

### What this cost, measured rather than estimated

A from-scratch ECMA-335 writer in cljc is **371 lines** (516 with comments) for
one type, one `FieldRva` array and three methods -- against a first estimate of
3 000-6 000, which was wrong by 3-6x. The artifact is **28 160 bytes for
`(ns t) (defn main [args] "x")`, 94% of it the bytecode**, against 494 302 for
the same program as wasm. Verified by the runtime loading it AND by Roslyn
compiling against it as a reference, Roslyn being the stricter reader.

The remaining cost is not the container. It is an IL assembler -- opcode table,
label fixups with short/long selection, computed `maxstack` -- at ~200-250
lines, which is the same problem `src/flint/aot.cljc` already solves in 713 for
wasm.

### Twelve checks ran nowhere in CI

CI runs `./bin/test` and nothing else (`.github/workflows/test.yml:87`,
`publish.yml:76`), and `bin/test` never ran `bin/check`. The two gates shared five
`check-*` scripts out of twenty-one, so TWELVE ran only in the fast tier -- which
is to say, only when somebody ran it by hand:

    check-aotstat-layout  check-api-review     check-artifact-ops
    check-containment     check-contracts      check-four-ops
    check-generated-reached check-lib-grants   check-port-consts
    check-snapshot-layout check-version        check-vocab-used

Among them `check-port-consts`, which compares 336 constant declarations across
the three runtimes; `check-lib-grants`, which exists because the paragraph asking
for that agreement did not produce any; `check-version`, which holds 27 manifests
to one number; and the four artifact/ABI contracts. A proposal could break any of
them and go green.

**`bin/test` runs `bin/check` now, so the slow tier is a SUPERSET.** In `bin/test`
rather than in the workflows, because that fixes every caller at once -- CI, the
publish job, and anybody running it locally -- and cannot be forgotten by the next
workflow. 70 s against 95 minutes.

**AFTER THE CLI BUILD, and not at the top, which is where it went first.**
`test/selfhost-targets.clj` skips its end-to-end rows when `target/release/flint`
is absent, which it is on a fresh checkout -- so the fail-fast placement would have
silently dropped the rows that drive every compile target, in CI only, while
printing a pass. A gate placed to fail fast is worth nothing if the placement is
what makes it skip.

Checked by running it: the banner, then `check-containment`, `check-port-consts`
and `check-four-ops` streaming inside `bin/test`, `bin/check: green in 68s`. Then
the whole file, green: 63 of 63 sections, 0 failures, 5 433 s -- so the twelve
also disturb nothing downstream of them.

**The `Test` workflow runs ON PULL REQUESTS ONLY and has never run.** It is absent
from the last forty workflow runs, because this work has gone to `main` directly.
So the twelve are CI-REACHABLE rather than CI-EXERCISED, and they become the latter
on the next proposal -- which is where `test.yml`'s own comment says the gate
belongs.

*The banner was written `== the fast gate ...` at first, which is what `sec` prints.
It impersonated a section without being counted as one, so the log showed
sixty-four `==` banners against a summary saying sixty-three -- the exact
discrepancy the section counter at the top of that file exists to catch, planted by
hand in the same file. It opens with `--` now.*

### AOT was written, tested, and called by nothing

The standing note said "AOT on neither new target ... JVM: ~450-550 lines, and
StackMapTable becomes unavoidable". Measured, that was wrong by two orders of
magnitude. `runtimes/jvm/src/com/flint/rt/AotEmit.java` is 408 lines and
`runtimes/clr/src/rt/AotEmit.cs` is 498, both complete, and StackMapTable is not a
cost at all -- `java.lang.classfile` computes it, which is why `bin/conform-hosts`
requires JDK 24+.

**What was missing was ONE CALL in each port.** `compileArities` /
`CompileArities` existed and were reached only from `runtimes/jvm/test/RtAot.java`
and `runtimes/clr/conform/Program.cs`. The rest of the chain was already there and
every link was traceable:

* the producer sets the bit -- `src/flint/image.cljc:246` -- and its comment says
  the case exists for exactly this: "an image for a PORT has the bit and an empty
  table, which is exactly the case that could not be expressed before";
* both ports DECLARE the constant (`Img.java:28`, `Img.cs:24`) and neither read it;
* both loaders PARSE it into a field (`Img.java:140`, `Img.cs:135`) that nothing
  outside the loader reads;
* and `Sandbox.boot` DISCARDED the `Loaded` carrying it --
  `if (Img.load(rt, image) == null) throw ...` -- at the exact point AOT would be
  decided.

So an artifact compiled `:optimize [perf]` was interpreted exactly like one that
was not, on both ports, and no gate said so.

**`false` for `chunkAll`**, and not a judgement call: `AotPlan`'s own comment calls
it "a bisection handle, not a mode", and the reference producer agrees --
`src/flint/aot.cljc`'s four-argument `compile-arity` delegates with `false`.

Measured after wiring, by ENTRIES into compiled code rather than by a compiled
count, because emitting a method proves less than entering one:

    jvm   plain 0 entries -> perf 961      clr   plain 0 -> perf 1997

**THE CONTROL IS THE POINT, and both gates assert it.** `entries > 0` alone would
also hold for a port that compiled unconditionally -- a different bug wearing the
same tick -- so the no-flag build must report ZERO. `bin/check-four-ops` runs the
JVM artifact twice and `bin/check-clr` builds a second assembly, and both also
require the same ANSWER: compiled arities that answered differently would be worse
than not compiling.

Both proven sensitive by removing the call: ":optimize [perf] compiled no arities
-- boot did not act on the image's perf bit".

*The CLR half was smaller than the JVM's, which I got wrong first: the CLR already
keeps its `Loaded` in a field, so only the flag needed consulting.*

### `loop` had three statuses written down and four in the runtime

`loop` answered 0 for BOTH "every thread settled" and "every thread is waiting on
another", so a host could only tell a finished sandbox from a wedged one by reading
a diagnostic string. Measured before changing: `flint_resume` on a program parked on
a channel nobody writes answered 0, exactly as one that returns a value does.

**AND THE CONTRACT WAS ALREADY WRONG BY ONE.** Both contract files and all four
faces said `{0 Done, 1 Threw, 2 NeedsHost}` while `runtime/src/snap.rs` set
`STATUS_SHELVED = 3` and `loop` returned it. That constant's own comment says it
"takes the next free code rather than overloading either" -- it was reasoned about
carefully and then written down nowhere a face could see, and
`bin/check-artifact-ops` could not catch it because it checks the faces AGAINST the
contract. A closed loop with a hole in it.

So the status set is five now -- `3 Shelved`, `4 Wedged` -- in both contracts and in
all four faces, with `kin/sched.kin`'s deadlock branch setting 4.

**WHAT I COULD NOT DEMONSTRATE, and why it is a property rather than a gap.** No
current entry point reaches status 4. Starting a program goes through the control
plane, which parks on the system port, which makes `sched-needs-host` true -- so a
sandbox with a door correctly answers 2 and lets the HOST decide it has nothing
left to send. I tried three ways in: `flint_resume` with no prior `boot` (nothing
ever starts, so every thread is trivially settled -- 0), a top-level park in the
initialisers (same), and the whole export list, which carries only `flint_boot` and
`flint_resume`. A sandbox given no bridge "runs logic and can ask for nothing,
which is a coherent thing to be" and is the one that can be wedged; nothing can
currently put a program into it.

`kin/sched.drivers` case 10 exercises the branch DIRECTLY on all three runtimes --
its field went `01` to `41`, status and deadlock flag -- so the behaviour is tested
even though no end-to-end path reaches it. The expectation was derived from the
contract, not recorded from the run.

### The 86 steps were the harness, and they were hiding 3 that were not

Every program billed 86 steps more on the Rust runtime than on either port, flat,
whatever it did -- 15 861 against 15 775 for a program that returns `""`. Split by
attribution it was 76 instructions and 10 allocation gas, and the allocation
histogram named the types: exactly one extra `TY_VEC` (62 against 61) and one extra
`TY_NODE` (66 against 65).

**One vector, with its backing node: the ARGUMENT LIST.** `sdks/esm/src/guest.js`
calls the program with ONE argument that is a vector of the args -- flint's
`(defn main [args] ..)` convention -- and the two gas harnesses,
`runtimes/jvm/test/HostCall.java` and `runtimes/clr/conform/Program.cs`, spread the
strings as N arguments instead. With one arg string that happened to work, because
`main` then received the string AS `args`; with none it threw `wrong number of
arguments (0) to main`, and with two it could not work at all. Fixed on both: all
three runtimes now bill 15 861 for that program, exactly.

`RtImage`, the CORRECTNESS harness, was already right -- which is why answers
agreed everywhere while totals differed by a constant, and why this hid so long.

**AND THE 86 WAS MASKING A RESIDUAL 3.** With it gone the gasmeter programs sit at
71 996 against 71 993 and 215 031 against 215 028 -- off by three, on both, so a
constant per call rather than per unit of work. Localised: `instrs` and `allocgas`
are IDENTICAL, `tick` and `checked` are identical, and the whole of it is
`charge_bytes`. The per-call formula is the same on both sides (`(n / 8) + 1`), so
it is the COUNT of calls, inside REGEX PATTERN COMPILATION: 6 per `re/pattern` and
3 per `str/split`. Every other string builtin was probed individually -- `subs` on
both arms, `str-index-of` found and absent, `str`, `pr-str` of a vector and of a
map, `str-bytes`, a table with a string column, a schema at init -- and all agree.

**AND THE RESIDUAL 3 IS FIXED, 2026-09-25.** It was `charge_bytes` called
UNCONDITIONALLY in `runtime/src/coll.rs`'s code-point index: `scanned` is 0 on the
indexable path, so an O(1) index into an ASCII string billed `(0 / 8) + 1` = ONE GAS
for walking nothing. The port calls it inside the non-ASCII branch only and billed
zero. A flat +1 per call -- which is why it was 6 per `re/pattern` and 3 per
`str/split` and constant in a workload of any size.

**NATIVE IS THE ONE THAT MOVED**, and not because native is usually wrong: the rule
is stated next door in `rope.rs`, "Charged where the bytes actually move. A tree join
moves none, which is what makes repeated concatenation linear in gas as well as in
time." A floor per call is a different pricing rule, and the comment on the very line
already claimed the other one -- "charged for what was walked". Verified in both
directions: two `code-point-at` on ASCII went 3/1 to 1/1, and a non-ASCII index still
bills 3 on both, so no charge was lost.

The row asserts EXACT absolute equality now -- 71 993 and 215 028 on both -- rather
than the ceiling of 3 it carried for one commit.

**THE ROW REPORTED THE ABSOLUTES BEFORE IT ASSERTED THEM.** It compares
`big - small`, and a difference cancels exactly the kind of constant that was
there: both sides' differences matched the whole time. The numbers are printed now,
with a ceiling at the known 3 so a NEW offset fails, and the equality becomes an
assertion the moment the regex residual is zero. A `within 3` with no explanation
is the slack this file already records being read as agreement once.

### Two implementations of one access check, disagreeing

`cli/src/sys.rs`'s `under()` and `sdks/cli/src/sys.mjs`'s are the fs capability's
containment rule in two languages, and the comment beside the JS one said it was
"the check `cli/src/sys.rs`'s `under()` is, COMPONENT FOR COMPONENT". Measured on
unix, it was not:

    "..\..\etc"   native CLI: OK -> /root/..\..\etc     node CLI: REFUSED
    "C:\windows"   native CLI: OK -> /root/C:\windows     node CLI: REFUSED

Neither answer is unsafe where it runs -- on unix a backslash IS a filename
character, and on Windows Rust's `Path` parses it as a separator and refuses. What
is not acceptable is the same program getting a file from one CLI and a refusal
from the other, so the stricter rule wins and the answer stops depending on the
platform. The cost is filenames containing a backslash, which the node door has
never accepted anyway.

**The two test suites are why it survived: SIX cases here against THIRTEEN there.**
Restating a table is how it drifts, and this repo already says so -- 
`sdks/cli/src/catalogue.mjs`: "AGENTS.md section 1 says to make one list read the
other rather than restate it", with `bin/check-sys-catalogue` doing exactly that
for the var lists. So `cli/containment-cases.txt` is now read by BOTH: by
`cli/src/sys.rs`'s test through `include_str!`, and by `bin/check-containment`
against the node CLI's `under`. A case added to the file fails both sides until
both are looked at.

**It moved to the FAST tier, which matters more than the fix.** An access check
FAILS OPEN -- a broken one gives no error, no warning and a passing suite -- and
its only thorough test was inside the 1.6-hour gate. `under` is a plain export
needing no build, so the rows run in milliseconds and `bin/check` carries them
now. Both sides also assert the case COUNT, because a table that parsed to nothing
would satisfy every case in it.

Proven by removing the guard: `"..\..\etc" should be refused`, 1 passed 1 failed.

### The fourth target got the face, and `main` was in the way

`:to :llvm` emitted a module whose only entry was `main`: it ran to completion and
a host could not drive it. It has the three operations now, and native turned out
to be the only target that can implement `link` PROPERLY.

**`link` TAKES A REAL FUNCTION POINTER here.** `NativeFn` is already
`extern "C" fn(*mut Rt, u32, u32) -> u64` -- flat and C-callable by construction,
because the wasm table entry needed it to be -- and `Program::load_with` takes an
`extra` slice of `(name, fn)` resolved at load. Since `link` must precede `boot`
and natives resolve exactly once inside it, hooks recorded by `link` are simply
that slice. So wasm answers 1, "this artifact cannot", and this one answers 0.
Hooks are keyed BY BRIDGE NAME, which is what the contract asks for and what a
process-global registry could not do.

**`boot` answers a REAL handle**, not ceremony: a leaked `Box<Program>`. Two
sandboxes in one process, with distinct handles, is checked -- and is the thing
wasm cannot do at all, since its `Rt` is one `static mut` per instance.

**AND `main` HAD TO BECOME `weak`.** This is the only one of the four artifacts
that defines a `main` -- a wasm module, a JVM class and a CLR assembly have none --
and a strong one made an embedder's own `main` a DUPLICATE SYMBOL at link time. So
the artifact could be run and could not be driven: the convenience entry
reintroduced exactly the gap the three operations were added to close. `weak`
keeps both, and `bin/check-llvm` links it both ways -- with a C driver, which
wins, and alone, which must still answer what `flint run` answers.

`link`'s checks also had to be REORDERED. Validating every argument up front reads
better and gets the precedence wrong: a `link` after `boot` whose other arguments
were also bad answered 1, "cannot read that", where the contract's answer is 2,
"too late". The wasm face refuses after boot before looking at anything else.

### `bin/flint` was silently emitting wasm for a target it does not have

`bin/flint ... :to :llvm` wrote a 494 KB WASM MODULE into `p.ll` and said
`wrote p.ll`. No error, the wrong artifact, and the only tell was `\0asm` where
`; flint program, as LLVM IR` should have been. A typo did the same: `:to :wsam`
compiled and shipped wasm. It refuses an unrecognised `:to` now, naming what this
door emits and saying that `:llvm` is the native CLI's.

Found while testing the llvm face, and it is the THIRD instance of the front-door
shape in two days -- after `:to :clr` never working through the native CLI and
`:checks` existing on one CLI and not the other. The pattern is not "the doors
disagree", it is that **a door that silently does something else reads as a door
that works.**

### `:to :jvm` is wired, and `:to :clr` had never worked

Both CLIs take `:to :jvm` now -- `bin/flint` and `cli/src/main.rs` -- and the two
produce an artifact of the same size that runs on `com.flint.Main`: boot ok, link
refused after boot, `loop` answering 2.

**THE BLOCKER WAS PRICED AND WAS NOT ONE.** `bin/build-jvm-artifact`'s own header
says the front door "means the Rust CLI embedding `dist/flint-rt.jar` the way it
already embeds `dist/flint-runtime.wasm`". That is half a megabyte in the binary,
and it is unnecessary: the jar is only needed for the CONVENIENCE wrapper that
carries an interpreter beside the class, and the artifact does not contain the
runtime -- the host supplies it through `link`. So `compile-to-jvm` takes an EMPTY
third argument to mean "the class alone", and the CLI embeds nothing.

**`:out` IS A CLASSPATH ROOT for this target and a file for every other one.**
The class declares itself `flint.Artifact`, and that name is a contract: both
`com.flint.Main` and `com.flint.FourOps` reach it by `Class.forName`. A JVM will
not load a class from a path disagreeing with its name, so honouring `:out
T.class` would write 39 KB that nothing can load. Both doors resolve
`<root>/flint/Artifact.class`, print what they wrote, and refuse a `.class` path
that is not already that -- with a message saying why, since the refusal is the
only place a caller learns the contract.

**`:to :clr` HAD NEVER WORKED THROUGH THE NATIVE CLI.** `compile-to-clr` answers
`{:clr bytes}` and the output `cond` in `selfhost.cljc` had no arm for it, so
every `flint compile :to :clr` fell through to `:else`, read `(:image r)` as nil
and died with `ClassCastException: str-join wants strings` -- four frames from
anything named `clr`. Found only because wiring `:to :jvm` beside it ran the same
door.

It survived because **`bin/flint :to :clr` works and always did**: that door calls
`clr/assemble` itself and never enters `selfhost`, so the target was sound from
the door a person tries first and broken from the one the CLI uses. And the only
test naming it asserted that the UNKNOWN-TARGET MESSAGE lists `:to :clr` -- the
help text, not the target. A three-line status saying "`:to :clr` IS wired" was
true of one door out of two.

`test/selfhost-targets.clj` now drives every artifact target end to end and
asserts the ARTIFACT'S MAGIC rather than an exit code. `selfhost.cljc` had cited
that filename for some time before the file existed, which is the smaller lesson
here: a comment naming a test is not a test. It checks THREE lists -- `known?`,
the dispatch `cond`, and the output `cond` -- because the third is the one that
was wrong, and a target can be in the first two and still not work. In
`bin/check` at 16 s; the source half needs no build and fires on the missing arm
by itself, checked by removing the arm.

**Two further things measured on the way, neither fixed here:**

* The two emitters own their metadata at OPPOSITE ends. `clr/assemble` takes the
  describe map verbatim from its caller ("ONE PRODUCER", its comment says);
  `jvm/emit` calls its own `jvm/describe`, which exists because the JVM's `:abi`
  is a version map rather than wasm's linear-memory key and the two keys must not
  collide. Mirroring the clr branch literally therefore DOUBLE-DESCRIBED the
  class: a `:compat` wrapping a second whole describe map as a string under
  `:meta`, each with a `:features` key meaning something different.

  **SETTLED 2026-09-25: THE EMITTER OWNS `describe`.** The argument is not taste,
  it is a count. Caller-describes has four call sites -- `link.cljc:481` and
  `bundle.cljc:155` for wasm, `selfhost.cljc:393` and `bin/flint` for clr -- and
  the two wasm ones ALREADY DIVERGE: `link.cljc` derives `:abi` from the linked
  units and `:units` from the artifact, `bundle.cljc` hardcodes both
  `{:runtime 1 :value 1 :image 1}` and `[{:name "flint.rt" ..}]`. Emitter-describes
  has one call site and has diverged nowhere.

  That is this project's own rule applied to itself: put a mandatory fact where it
  is RESOLVED, not in each front end. A target's `:abi` shape is a property of the
  target, so it belongs in the file that emits that target -- which is also why
  `jvm/describe` could carry the "not wasm's key" reasoning in one comment instead
  of in every caller.

  **WASM CONVERGED TOO, 2026-09-25, and it was the caller that proved the rule.**
  `flint.wasm/describe` owns `:memory :unshared` -- a COMPATIBILITY KEY input,
  stated twice until now -- the identical `:imports` derivation, and the five
  feature probes. `link.cljc` and `bundle.cljc` pass only what each alone knows: a
  linked module takes `:abi` and `:units` from the units it linked, a bundled one
  has a single known runtime, and that difference is real rather than duplication.

  **The two had already diverged on a probe name.** `link.cljc` tested
  `flint_snapshot_capture`; `bundle.cljc` tested `snapshot_export`. Exports keep
  their Rust symbol names -- `collect_now` is declared unprefixed in
  `runtime/src/abi.rs` and probed unprefixed in both -- and the snapshot unit
  declares `flint_snapshot_capture` and `flint_snapshot_export`, nothing named
  `snapshot_export`. So the bundler's `:snapshots` could never be true whatever the
  module carried. Not a live failure, because the snapshot unit is not in the
  default build and `false` was right by accident; wrong by construction, which is
  the kind a count finds and a reading does not.

  *I nearly reported the opposite.* A built module contains the bare string
  `snapshot_export` and NOT `flint_snapshot_export`, which reads as the linker
  stripping the prefix and the bundler being right. The module's own `:exports` list
  settled it -- no snapshot export at all, so the string was a name-section
  artefact -- and `collect_now` being unprefixed in Rust is what proves no stripping
  happens.

  Verified behaviour-preserving on the link path: the features map is byte-identical
  and the compatibility key unchanged at `6cebaa00`.
* The two doors do not produce IDENTICAL images. Same length, same string set,
  54 bytes of 26 715 differing -- a constant-pool ORDERING difference between
  babashka's Clojure and the self-hosted runtime. It shows on `:to :clr` too, so
  it predates this and is not JVM-specific. Artifacts are not reproducible across
  doors; nothing depends on that yet.

### The 33 KB the compiler image grew, and why it does not bite

Requiring `flint.clr`, `flint.jvm` and `flint.modmeta` into `flint.selfhost` --
which is what `:to :clr` and `:to :jvm` through the SELF-HOSTED compiler cost --
takes `dist/flintc.bytecode` from **206 407 to 239 955 bytes**, +33 548.

A subagent measured the smaller half of that (+21 682, clr alone) and reported
that it BREAKS `sdks/cli`'s selftest: `flint.ception`'s "a sandbox lent a
capability can use it" failing with `wire-str: this writer has already been
finished`. It bisected three full build cycles, so the report was careful. And
`lib/flint/system.cljc:129` describes exactly that failure mode as a known
ceiling -- "a reply within a few kilobytes of the encoder's ceiling crossed it
and a passing compile started failing ... since fixed, a 2 MB reply crosses now".

**It does not reproduce on the merged tree at the LARGER size.** Both arms in,
239 955 bytes, the named case passes and the selftest is clean. So whatever the
subagent hit was not the ceiling as a function of image size -- the merge is
bigger and fine.

*It took two stale artifacts to find that out, both mine, and they are worth
writing down because each read as the opposite of the truth.*

* `FLINT_DIST_FRESH=1 ./sdks/cli/build` reported "all good" against a compiler
  image **22 hours old**. The flag means "the caller JUST built them" and so
  SKIPS the rebuild -- `bin/test` sets it because it builds them first. Copied
  from there without reading the line above it, it turns the gate into a test of
  yesterday's artifact that cannot fail on today's change.
* With a fresh image, five "byte-identical to the native CLI" rows failed --
  because `build-dist` does not rebuild `target/release/flint`, so the npm CLI
  was new and the native one old. The comparison was right and both inputs were
  not.

Neither failure was in the tree. The lesson is the one already written down as
"a flint size or result read without rebuilding first is a stale artefact", and
it now has a second instance: a FRESHNESS FLAG can make a gate skip the very
build it is supposed to be checking.

### A status line that named files nobody could open

This record shipped saying the CLR face "is written and runs" and naming two
paths. Neither was in the tree: both were in a subagent's worktree, unmerged.
**The other subagent caught it, and `bin/check-decisions` did not** -- it
validated decision slugs and `sym (path:line)` pairs and never asked whether a
bare path in a STATUS line resolved.

The cost was not the wrong sentence. The JVM agent, told to mirror a working
design, read the record, could not find the files, and **mirrored the prose
instead of a working implementation** -- which is the exact failure this record
was created to prevent, arriving through the record itself.

`bin/check-decisions` now refuses a status line naming a file that is not in the
tree, scoped to the status line on purpose: a decision's BODY cites paths that
have moved or been deleted, and requiring those to resolve would make the record
unwritable. A status is a claim about now. Verified by putting the old sentence
back and watching it fail.

*The general lesson, which this project keeps relearning:* a claim that a file
exists is exactly the kind a script can settle, so it should never be a claim a
reader has to take on trust.

## apply-loses-its-callee

**Both ports mis-resolve `apply`'s callee above ~8 200 arguments**
**Ratified:** ☐ not signed off
**Status: FOUND AND BISECTED 2026-09-24, NOT FIXED. Reproduced against `runtimes/jvm/src/com/flint/rt/Builtins.java` and `runtimes/clr/src/rt/Builtins.cs`; native is correct.**

    (defn main [_] (str (count (apply str (repeat K "x")))))

    K = 8193   native 8193, jvm agrees
    K = 9000   native 9000, jvm: ArityException: wrong number of arguments (9000) to fn
    K = 12500  native 12500, jvm: ArityException ... (12500) to not
    K = 12500 with 8-char strings, jvm: ArrayIndexOutOfBoundsException:
              Index 352321536 out of bounds for length 308

**THE CALLEE IS WRONG, AND DIFFERENTLY EACH TIME** -- `fn` at 9 000, `not` at
12 500, and an out-of-bounds read of an array of about 308 entries, which is the
size of this image's function table. So a large `apply` is writing past
something and a later read takes a clobbered value as a function index. That is
a state-corruption shape, not an arity check doing its job: the count in the
message is correct and the FUNCTION is not.

Both ports; native takes all of it. **BISECTED 2026-09-25: the last good count is
8 803 and the first bad one is 8 804.**

That number is the useful part, because it rules out the obvious cause. The value
stack starts at 1 024 and DOUBLES (`Roots.stack`), so every candidate boundary is a
power of two, and 8 804 is not one and is not near one -- 8 193 through 8 803 all
pass, including the crossing of 8 192. `Roots.vpush` grows the array itself and
`Roots.forEach` walks `0..stackTop` writing forwarded values back, so neither an
unchecked push nor an unscanned root explains it either: both were checked and both
are sound.

So the remaining suspects are what SCALES WITH THE COUNT other than the stack --
the nursery filling during the spread (each `Seqwalk.next` on a `repeat` allocates)
and whatever `callValue` does with an argument count that large. The arithmetic in
the `APPLY` handler was hand-checked against the native path and lands exactly on
the copied callee, so the index is right and something moves under it.

**THIS INVALIDATED ANOTHER FINDING OF MINE, which is why it is recorded
separately.** `index-past-the-fixnum` below originally reported that the jvm
answered an `ArrayIndexOutOfBoundsException` for `(b-at bs BIG)`. That probe
built its 100 000-byte string with `(apply str (repeat 12500 "abcdefgh"))` --
above this threshold -- so the crash was THIS bug and says nothing about `b-at`.
The native half of that record stands, because native has no such bug. The jvm
half is withdrawn and needs re-probing with a fixture built some other way.

*A probe's SCAFFOLDING can be the thing that fails.* The construction used to
reach the interesting case was itself broken on two of the four runtimes, and it
failed in a way that looked exactly like the bug being hunted -- an
out-of-bounds index on a small internal array.

## index-past-the-fixnum

**An index that does not fit a fixnum is wrong on all four runtimes, differently**
**Ratified:** ☐ not signed off
**Status: `b-at` FIXED on all three runtimes 2026-09-24 and gated by a `bin/conform-hosts` row; the other sites below are measured-and-open. `runtime/src/builtins.rs`, `runtimes/jvm/src/com/flint/rt/Builtins.java` and `runtimes/clr/src/rt/Builtins.cs` now bound the index BEFORE narrowing it, and all three refuse.**

`(b-at bs 281474976710657)` -- 2^48+1, one past the signed 48-bit fixnum
payload, so a BIGINT -- on a 100 000-byte string:

    native   returns the byte at index 1. Wrong DATA, silently.
    jvm/clr  returns the byte at index 97. Wrong DATA, silently, and the two
             ports AGREE with each other.
    correct  a flint IndexOutOfBoundsException.

**RE-PROBED 2026-09-24 with a fixture that does not use `apply`**, after the
first one was withdrawn: it built its string with `(apply str (repeat 12500 ...))`
and that corrupts both ports above ~8 200 arguments
(`apply-loses-its-callee`), producing an `ArrayIndexOutOfBoundsException` that
looked exactly like the bug being hunted. The replacement DOUBLES -- `(loop [s
"abcdefgh" i 0] (if (< i 14) (recur (str s s) (inc i)) s))` -- 131 072 bytes with
no arity above two:

    native   len=131072 at1=98 atBIG=98
    jvm/clr  len=131072 at1=98 atBIG=97

Three answers, none of them a refusal. The doubling fixture is also the right
test article for a conformance row, because it cannot trip the `apply` bug.

**Three more native sites do the same thing**, measured with the same BIG:

    (b/slice bs BIG 10)          size 9   -- `from` truncated to 1, returns [1,10)
    (b/slice bs 0 BIG)           size 1   -- `to` truncated to 1, returns [0,1)
    (code-point-at s BIG)        98       -- index truncated to 1, returns `b`
    (from-code-point BIG)        THREW    -- the only one that refuses correctly

**Native is not the reference here, which is the part that matters.** For the
bitwise family it was -- `bitop` uses `as_i64` end to end and never narrows. Here
`builtins.rs` reads the index correctly with `as_i64`, checks `i >= 0`, and then
hands `i as u32` to `b_at`, which range-checks the TRUNCATED value. 2^48+1 as a
`u32` is 1, and 1 is in range, so the check passes and the wrong byte comes back.
A guard after a narrowing is not a guard.

**The jvm's failure is the worse kind.** `Val.asFixnum` reads a heap value's
ADDRESS -- 100663296 here -- and the resulting index escapes as a Java
`ArrayIndexOutOfBoundsException` from inside an internal array. That is a host
exception crossing the guest boundary, so a flint `catch` cannot see it and the
message names flint's internals rather than the program's mistake.

### Sixteen sites, one shape

Sixteen per port read a user argument with `Val.asFixnum`/`Val.AsFixnum` where
native uses `as_i64`, across nine builtins: `table-slice`, `subs`,
`str-index-of`, `code-point-at`, `from-code-point`, `b-at`, `b-slice`, `b-conj`,
`re-run`, `re-find-all`. Three were probed and agree -- `subs` twice and
`index-of` -- because their bounds reject an address-scale number for a short
string. **They agree by accident of size, not by construction.**

`Builtins.java:779` already describes this exact hazard for a DIFFERENT family
-- "`asFixnum` IS NOT `asI64` ... for a BIGINT it sign-extends the heap address
and does arithmetic on a pointer", recording `unchecked-add` answering 70969
against native's -9223372036854775808. That family was fixed; the index family
was not, and the warning sits 600 lines from the code it describes.

### What a fix has to do, and why it is not an accessor swap

* **bound BEFORE narrowing, on every runtime.** Native's `as_i64` is already
  right; what is missing is refusing an index that does not fit the width
  `b_at` takes. Swapping the ports to `Num.asI64` alone would make them agree
  with native's WRONG answer.
* **refuse as flint, not as the host.** The jvm must answer a flint
  `IndexOutOfBoundsException` rather than let an `ArrayIndexOutOfBounds` escape.
* **and the conformance row has to use a LONG input.** The probe only
  distinguishes the three outcomes because the string is 100 000 bytes: at
  75 696 the answer is `a` and at 1 it is `b`, so "wrong byte" and "refused" are
  different observations. A short fixture passes either way, which is why 366
  rows missed this.

## emitters-are-cljc

**Every target's emitter is one cljc implementation**
**Ratified:** ☐ not signed off
**Status: settled 2026-09-24. `src/flint/wasm.cljc` and `src/flint/llvm.cljc` already work this way; the CLR and JVM writers exist in subagent worktrees, unmerged.**

An emitter is written ONCE, in cljc, in the compiler. Not in kin, and not by
calling the platform's own bytecode API.

**Not the platform's API, because the compiler does not run on the platform.**
flint's CLI is babashka or native. It cannot call `java.lang.classfile`, and it
cannot call `System.Reflection.Metadata` or `PersistedAssemblyBuilder` -- which
was verified as PRESENT on .NET 10 before being ruled out as unreachable. Both
remain legitimate as a FAST PATH for an SDK running on that platform, and if one
is ever added it needs a byte-identity gate against the cljc path from the first
commit. `bin/check-llvm` is the shape: two arms differing only in compilation,
with the equivalence asserted rather than assumed.

**Not kin, and this is the part worth arguing.** kin exists because the same
LOGIC must exist in three HOST LANGUAGES -- the runtimes have to behave
identically, so `portring.kin` becomes three copies that cannot drift. An emitter
has no such requirement: **a JVM class file is the same bytes whoever wrote
them.** There is one output, so one implementation suffices, and kin's whole
value buys nothing. Two further costs if it went there anyway:

* a LAYERING INVERSION -- the emitter would live in the runtime, so every
  shipped artifact would carry a class-file writer it will never use, which is
  the kind of weight `two-builds` and `calls-are-ports` both measured;
* "built-ins when available" would mean two implementations obliged to produce
  byte-identical output, which is this project's signature defect (sixteen
  version declarations, four compile-spec front ends, a `:checks` axis on one
  CLI and not the other).

**The precedent was already here.** `src/flint/wasm.cljc` is 460 lines and writes
WASM MODULES; `src/flint/image.cljc` is 277 and writes the bytecode format. A
class file is a simpler container than either.

### What it cost, measured rather than estimated

A first estimate of 3 000-6 000 lines for ECMA-335 was wrong by 3-6x:

    clr, from scratch, cljc     371 lines (516 with comments)
    jvm, from scratch, cljc     198 lines (288 with comments)
    for comparison, wasm.cljc   460
    for comparison, image.cljc  277

The CLR writer was verified by the runtime loading it AND by Roslyn compiling
against it as a reference, Roslyn being the stricter reader. The JVM class needs
**no `StackMapTable` at all** -- frames are required only at branch targets, and
a delegating shim with a branch-free `anewarray`/`dup`/`ldc_w`/`aastore` run has
none. Avoiding a `<clinit>` loop is what avoids the hardest part of a class-file
writer.

**AOT is the separate and larger half**, and it is where frames become
unavoidable: ~450-550 lines on the JVM (dataflow for `maxstack` and frames, plus
label fixups with short/long selection), ~200-250 for an IL assembler on the CLR.
Both are the same problem `src/flint/aot.cljc` already solves in 713 lines for
wasm.

### What each container does with the bytecode

Constant data ON the class, never a side file and never a jar resource:

    clr    a `FieldRva` row pointing at raw bytes in `.text`. No encoding, no
           chunking, any size -- 20 MB loaded and read correctly.
    jvm    `CONSTANT_Utf8` entries in the constant pool, because a class file has
           NO data section. Modified UTF-8, capped at 65 535 bytes per entry.

**The `FieldRva` choice paid for itself twice.** Measured expansion on real flint
images is 1.417x for a small one and 1.323x for the compiler's -- not the 1.5x a
uniform byte distribution predicts, because an image is 32.7% zero bytes (two
each) and only 9.1% at or above 0x80. The CLR pays none of that. And on the CLR,
keeping the image OUT of `#Blob` keeps `HeapSizes` at 0, so every metadata heap
index stays 2 bytes at any program size -- in `#Blob` it would cross 64 KB on any
real program and widen every blob index in every table.

**A chunk boundary is counted in ENCODED bytes.** Cutting on source bytes
overflows the `u2` length on zero-heavy data and the class is rejected for a
wrapped length field -- found by forcing the cap to 997 and getting 39 correct
chunks.

## one-image-per-sandbox

**A sandbox holds one image for its whole life**
**Ratified:** ☐ not signed off
**Status: BUILT 2026-09-24, in `runtime/src/abi.rs` and `test/loader.clj`.**

A second `flint_load_image` answers 3 and says to create another sandbox. A host
running many programs creates many sandboxes; booting one is not expensive
enough for sharing to buy anything, and a sandbox that can be re-imaged has a
lifetime nobody can reason about.

What went with the rule: a block clearing `frames`, `handlers`, the started flag,
`stack_top` and `thrown` so a swap would not inherit the last image's state. It
carried its own bug history -- a swapped image found the started flag already
set, never bound its vars, and answered "`two/main` is not a function" -- which
is the defect shape a re-imageable sandbox keeps producing.

**The test that claimed to cover this never did.** `test/loader.clj` asserted "a
second image replaces it cleanly ... in either order, any number of times", and
its driver takes a FRESH INSTANCE PER IMAGE -- its own comment says why. So those
rows were evidence that one module serves many sandboxes, which is the intended
model, and never that replacement worked. The missing case is now there: two
loads into ONE instance, wanting 0 then 3, the message naming the rule, and the
first image still running afterwards.

**`:to :native` is not being built.** A user compiles native from the LLVM IR
`:to :llvm` already emits. An attempt at a `:native` target was written and
reverted the same day: it needs `libflintnative.a`, which nothing ships, and its
entry point is `flint_native_main(image, len, argc, argv)` -- a command line and
an exit code, which is not `four-operations` and would have had to be redone.

## version-is-semver

**One semver string, in `meta.edn`, written out and gated**
**Ratified:** ☐ not signed off
**Status: BUILT 2026-09-24. `meta.edn` holds it, `bin/set-version` writes it, `bin/check-version` gates it, and `bin/check` runs that first.**

Sixteen places stated a version and they disagreed EIGHT TO EIGHT -- `0.0.1` in
the CLI, both SDK crates and every `package.json`; `0.1.0` in the runtime, the
six units, and `bin/flint`'s fallback, which is the one that actually reaches an
artifact through `modmeta`. Nothing compared them. 0.1.0 was chosen because it is
the value already embedded, so nothing a host can read changed.

**A COMPATIBILITY DATE WAS TRIED FIRST AND CANNOT BE SPELLED.** Tested against
cargo and npm's own semver rather than assumed:

    2026-09-24      cargo REJECTS, npm semver INVALID (a hyphen opens a prerelease)
    2026.09.24      cargo REJECTS, npm semver INVALID (leading zeros)
    2026.9.24       legal in both
    20260924.1.0    legal in both, and `^` then means "same date, any same-day build"

So a date IS expressible, as `YYYYMMDD.SEQ.PATCH`, and it was still declined:
date-as-major makes every release a breaking change unless the RUNTIME separately
promises a floor, which is the actual commitment and is not what a version
number says.

**The gate had a hole for an hour and a subagent found it.** `Cargo.lock` states
a version per workspace member, `check-version` did not look at lockfiles, and it
went green while the lockfile still said `0.0.1`. 16 manifests checked became 21.
Both probes are run rather than reasoned: bumping `meta.edn` alone reports all 21
as drifted, and a non-semver value is refused BEFORE propagation.

## what-a-thread-can-wait-on

**Three wake keys, and nothing else**
**Ratified:** ☐ not signed off
**Status: describes the tree as it is, 2026-09-24. Nothing to build; recorded because the waiter registry in `four-operations` depends on it.**

Every park goes through one primitive, `park(rt, on)` in `kin/sched.kin`, and
`on` is only ever one of three things:

    a PORT            via `park-on-port` -- woken by whoever writes it, a bridge
                      (the host) or a channel (the guest)
    a THREAD OBJECT   via `kin/threadjoin.kin` -- woken by that thread's `settle`
    PARK_YIELD        a yield; nothing wakes it and it is immediately re-runnable

**There is no mutex, no condition variable, no sleep and no arbitrary wait**, and
the runtime is `#![no_std]` so there could not be: `par.rs` says atomics are in
`core` and blocking primitives are not.

**Join is not a second wait primitive**, which is the part worth keeping.
`threadjoin.kin`: "A thread is a wake key like any port; that is why `wake-on`
takes a value rather than a port, and why joining needs no machinery of its own."
One primitive with a different key, rather than a second mechanism to keep in
step across four runtimes.

### So threads CAN wait on each other, in exactly two shapes

* **transitively through channel ports** -- each is parked on a PORT and the
  cycle is emergent, never expressed;
* **directly through join**, where the wake key genuinely is the other thread.
  Self-join is refused BY NAME at the call, because "left to run, it becomes a
  deadlock report one scheduler turn later, naming the symptom rather than the
  mistake".

**This is what makes the host-side waiter registry clean rather than partial.**
Every wait is "parked on a key", so a registry keyed by value covers bridge ports
natively, and the two cases it cannot see are both entirely internal to the
sandbox. The split has no overlap: the HOST knows what it can unblock; the
RUNTIME owns channel cycles and join cycles, which `report_deadlock` already
finds by walking `TH_PARK_ON` for every thread.

### What the fix was, and what is still open

`b-at`, on all three: bound the index before narrowing it, using the
bigint-aware read on each side -- `as_i64` was already right on native, the ports
moved from `Val.asFixnum` to `Num.asI64`, which is nullable exactly as native's
answers `None`. All three now throw `IndexOutOfBoundsException`.

The conformance row uses the DOUBLING fixture. It asserted by arithmetic at
first -- a refusal contributed 7 to the sum -- and that scoring was REPLACED once
it turned out to be blind to `table-slice`; see "the row had to change shape"
below. The doubling fixture stayed, because a short one leaves a truncated index
in range and so passes either way.

**THREE MORE FIXED 2026-09-24, and the fix was NOT the same shape at each**,
which is the finding worth keeping:

    b-at             wrong on ALL THREE.  native 98, ports 97.
    b-slice from/to  wrong on NATIVE ONLY. native 9 and 1; both ports already
                     refused, correctly.
    code-point-at    wrong on ALL THREE.  native 98, ports 97.

So "the ports read with `asFixnum` and native is right" is false as a general
rule. For `b-slice` native was the only broken runtime, and a fix applied by
assuming otherwise would have changed two correct implementations. Each site was
measured on all three before being touched.

**THE SWEEP WAS DECLARED COMPLETE AND WAS NOT.** That claim is left standing
here because the way it was wrong is the useful part: I enumerated the family as
NINE BUILTINS, checked them off, and missed `from-code-point` entirely -- and the
unit was wrong as well as the list. A builtin is not a site; an ARGUMENT POSITION
is. `subs` reads two of them on each of TWO ARMS, `re-run` reads three, and
"`subs` agrees" was recorded from a fixture that exercised one arm.

Enumerating what the code actually does, rather than working from the list, found
four more defects after the "complete" claim. The table below is by argument
position.

**The measured state, 2026-09-24:**

    b-at              wrong on ALL THREE.  native 98, ports 97.
    b-slice from/to   wrong on NATIVE ONLY. both ports already refused.
    code-point-at     wrong on ALL THREE.  native 98, ports 97.
    re-run  (start)   wrong on ALL THREE.  native [8 9], ports [81808 81809].
    table-slice       wrong on THE TWO PORTS. native was right.
    from-code-point   wrong on THE TWO PORTS, and the WORST of the family.
    subs (rope st/en) wrong on THE TWO PORTS, value AND exception class.
    subs (flat st/en) the same defect again, on the other arm.
    re-run  (entry)   wrong on ALL THREE, and a HOST crash on the ports.
    b-conj            ALREADY RIGHT on all three. Not touched.
    re-find-all       ALREADY RIGHT on all three. Not touched.
    str-index-of      ALREADY RIGHT on all three. Not touched.

**`from-code-point` is the only site where the ports ANSWERED.** Every other one
refused or returned a wrong number; this one succeeded. `asFixnum` handed it the
bigint's heap ADDRESS, and a heap address is ordinarily BELOW 0x10FFFF -- so the
code-point bound underneath it PASSED, and both ports answered the cuneiform sign
U+12778 where native refused. The heap layout became program-visible data. A
bound that the wrong value happens to satisfy is worse than no bound, because it
reads as a check.

**`subs` diverged in its exception CLASS, which is control flow and not wording.**
flint matches a `catch` by EXACT NAME with no hierarchy -- measured: on native,
`catch StringIndexOutOfBoundsException` caught and `catch IndexOutOfBoundsException`
fell through. Both ports were the exact inverse, because they threw the general
name. So the same program's error handling ran on one runtime and not the other
two, in both directions, with no bigint involved. A row comparing MESSAGES cannot
see this, since the class is not in the message; the row now catches both names
and prints which one fired.

Converging it took the better half from each side: the ports adopted native's
more precise class, and native adopted the ports' informative message, which is
now `subs 5..1 of 3` on all three instead of `bad substring range`.

**The charge had to be left alone while the value was fixed.** Native charges
`(e - start).max(0) as u32` BEFORE the bounds check, so a REFUSED `subs` is still
billed, and that `as u32` truncates. Computing the ports' charge from a value
already clamped into range -- the obvious way to write the fix -- silently moves
the bill on the refusal path, and gas is program-visible through a step limit. So
both ports mask to 32 bits explicitly. Verified at two workload sizes: the slope
is 1250 steps on all three and the residual is a flat 86, which is present in a
program containing no `subs` at all and so predates this.

**THE FIX FOR `subs` INTRODUCED A DIVERGENCE OF ITS OWN, in the other
direction.** Bounding the argument by REFUSING a non-integer is the obvious
reading of "bound it", and it is wrong: native reads
`as_i64(..).unwrap_or(0)` for the start and `None` for the end, so
`(subs s "x" 2)` answers `"ab"` and `(subs s 0 "x")` answers the whole string.
For half an hour both ports threw where native answered -- on an input with no
bigint anywhere in it, which is a wider blast radius than the bug being fixed.
Caught by testing the arm I had just written rather than only the one that was
broken. Four cases for it are in the row now.

It also decides which branch the CHARGE takes: native bills by whether it HAS an
end, so a non-integer end takes the same branch as no end argument at all, and
the ports test `endv != null` rather than `n > 2` for that reason.

**`re-run`'s `entry` is a PROGRAM COUNTER and nothing checked it.** `add-thread`
indexes its `seen` flags by it, so an entry past the program read off the end:
both ports raised a HOST exception out of `addThread` -- a Java
`ArrayIndexOutOfBoundsException`, not a flint throw, so nothing inside the sandbox
could catch it -- and native escaped only because `as u32` truncated that
particular bigint to 1, which is in range for any program of two or more
instructions. A different bigint would have panicked there too.

That guard went into `kin/pike.kin`, not into the three builtins: one guard in the
shared source cannot disagree across runtimes and three copies would. It is
`(>= entry ninstrs)` and was first written `(or (< entry 0) (> entry (- ninstrs 1)))`,
which does not compile -- kin's `I32` is UNSIGNED in Rust, so `< 0` is never true
there, and `(- ninstrs 1)` underflows to a huge value for an empty program. That
spelling would have let everything through on the one input it most needed to
stop.

Nine positions fixed, three found correct. **Three needed no change at all**,
which is the argument for measuring each rather than applying the family's fix
across the family: a bound added to `str-index-of` on one runtime would have
CREATED a divergence where all three already agreed. That nearly happened -- see
below.

`re-run` is the one whose narrowing is per-TARGET rather than per-port:
`pike_run` takes a `usize`, 32 bits on wasm and 64 natively, so the same native
source truncates differently depending on what it is compiled for.

`table-slice` is the one that is NOT the family's shape. It takes an `I64` on
every target, so there is no narrowing to bound -- only the decode was wrong, and
the two ports were the wrong half. Its third case is a non-integer rather than a
huge one: native reads `as_i64(..).unwrap_or(-1)` and refuses, where `asFixnum`
read a string's bits as 1099511627896 and sliced from there.

### The row had to change shape to see the last one

The row scored 7 per refusal, and THAT IS WHY IT COULD NOT HAVE CAUGHT
`table-slice`. All three runtimes refused; the two ports refused with the wrong
number in the message -- `slice [79416 2)` against native's
`slice [281474976710657 2)`, 79416 being the bigint's heap address. Refused-or-not
is one bit, and the divergence was not in it.

So each of the eight sites now reports its refusal TEXT and the three runtimes
are compared on that, with `=` prefixing an answer so a site that stops refusing
is louder than one that never did. Verified sensitive by injection: dropping the
jvm's `b-at` upper bound turns `b-at!byte index out of range` into `b-at=98` and
the row fails. An arithmetic score could not have expressed this check at all.
