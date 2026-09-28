# Working in this repo

flint is a compiler and runtime for a Clojure-like language, with **four
runtimes** — native Rust, wasm, JVM Java, CLR C# — that are expected to agree.
`kin/` is a source-to-source generator: one `.kin` file emits Rust, Java and C#
so the three cannot drift by hand.

This file is about *how to work here*, not about what the code does.

---

## 1. One list, not two

**When you add a case to a concept, find every place that enumerates it.**

Two tables that agree on the common cases are indistinguishable from one table
until an uncommon case arrives. That is what makes this kind of drift expensive:
it is invisible for as long as nothing unusual is asked.

- Before adding a kind, capability, builtin or opcode, grep for the existing
  members by name and fix every list you find, in one change.
- If two lists must exist, make one *read* the other rather than restate it.
- `bin/flint` is babashka running the compiler's Clojure source and
  deliberately duplicates logic from `src/`. Change one, change the other; they
  are meant to stay identical.

## 2. Recording decisions

`DECISIONS.md` is the single decision record. One `## slug` section per
decision, cited from code and docs as `` `DECISIONS.md#strings-and-matching` ``.
`bin/check-decisions` asserts every citation in `git ls-files` resolves, so a
renamed or deleted section fails the build rather than leaving dangling
pointers. Slugs, never numbers.

Every section carries:

```
**Ratified:** ☐ not signed off
```

**Only the human maintainer ticks that box.** An agent records the decision and
its rationale, and leaves it unsigned.

The checker verifies that citations *resolve*, not that they are *true*. No
script can check the second. When you cite a decision, read the code it
describes and correct the record if they disagree — a status line is a claim
about the tree, and it decays silently.

**Record how a claim was obtained, next to the claim.** The failures here are
not lies; they are statements that were true once, or true of something
adjacent, written in a form that cannot be checked later:

* a benchmark ratio with no host, machine, or method — unreproducible, so it
  can only be believed or ignored, and it gets believed;
* a count produced by a grep, which counts what the pattern understood and not
  what was asked;
* a phrase true of one subject and read as another ("compiled through LLVM" is
  ordinary of anything written in Rust, and reads as a backend);
* a feature recorded as built whose only exercise was a path nobody ran.

A number carries its method or it is folklore. A count says what produced it. A
status line that says BUILT names the test that proves it. The cost of the
sentence is seconds; the cost of its absence is that the next person plans
around it.

`ROADMAP.md` holds state: sections, checklists, what is built and what is not.
A decision explains *why*; the roadmap tracks *whether*.

## 3. Verify before claiming

**Rebuild both arms the same way before comparing anything.** A stale artefact
does not announce itself; it presents as a real finding.

| What you changed | What must be rebuilt, in order |
|---|---|
| `src/` — the COMPILER | `bin/build-dist`, then `cargo build --release -p flint-cli`, then `sdks/cli/build` |
| `lib/` — the stdlib | `cargo build --release -p flint-cli`, then `sdks/cli/build` |
| unit modules | `bin/build-units` |

The two halves are embedded by different routes, and that is the trap.
`cli/build.rs` reads `lib/` from source at build time, so a stdlib change needs
only the cargo build. The compiler is embedded as `dist/flintc.bytecode`, which
cargo copies but does not produce — so a `src/` change that skips
`bin/build-dist` gets re-embedded unchanged, and the binary runs the old
compiler while reporting success.

`bin/build-dist` does **not** rebuild `target/release/flint`, and
`cargo build` does **not** rebuild `dist/`. Neither step implies the other.

**THERE IS A THIRD DOOR AND IT HAS ITS OWN COPY.** `sdks/cli/dist/` holds the
npm CLI's `flintc.wasm`, the runtimes and the stdlib, copied there by
`sdks/cli/build`. Nothing in `bin/build-dist` or `cargo build` touches it, so
after a `src/` change the npm CLI keeps running the previous compiler — and it
reports success, because a stale compiler is a working one. It presents as a
DISAGREEMENT between the npm door and the other two: measured 2026-09-28, the
npm door's `:to :clr` assembly differed from the other two doors' for five
basenames out of five, which reads exactly like the bug being chased.

Two symptoms that mean *check freshness* before believing a result: the two arms
agree **exactly** — a measurement that cannot tell its arms apart is not
evidence they are equal — and a test that fails on a port but passes on native
right after a native change.

**A VERIFICATION EXPIRES.** Checking a feature when it lands says it worked
against that tree. Every merge after it may have moved the ground underneath —
a refactor that rebuilds a record field by field drops the field you added; a
lookup rule fixed in one reader and not its twin; a fix landed in machinery one
front end drives and the other does not. None of that shows up as a failing
test, because the test was written against the behaviour that changed.

So before calling something done, ask what you have claimed and not re-checked
since. Confirming a bug and confirming its fix are different acts, and only the
second one tells you what shipped.

**Measure at the boundary the decision is about.** A question about a shipped
artefact is answered by invoking that artefact. A finer-grained unit is not
automatically more rigorous: `ns/instruction` is right for throughput and wrong
for latency, because it factors out the process start and warmup that dominate a
short run. Before believing any A/B, state what each arm does end to end; if
their responsibilities differ, the ratio is between pipelines, not between the
things being compared.

## 4. The gate is the last check, not the first

**THE GATE IS TIERED. Do not run the half-hour one on every change.**

    bin/check          ~51 s     every change
    bin/test           39–50 min before pushing a branch, and on a PR
    bin/release-gate   > bin/test  before a versioned release

*Re-measured 2026-09-28 at `c17c0f1e`, load averages 2.64/3.07/4.80: `bin/check`
is 52.3 s, 48.6 s and 53.4 s by `time -p ./bin/check` on three consecutive runs —
call it ~51 s, up from 26.8 s two days earlier. The increase is checks ADDED, not
a slowdown: `bin/check-clr` gained a native-door `:optimize [perf]` arm (two more
CLR loads), `bin/check-wedged` and `check-four-ops` are unchanged, and
`test/selfhost-targets.clj` went from 4.7 s to 11.5 s. A door-agreement matrix
took that suite to 40.8 s, which is why it moved out to `test/door-agreement.clj`
and `bin/test` — the fast gate keeps one row of it. `bin/test` was 2 319 s
(38.6 min) and then 3 008 s (50.1 min) on two full green runs hours apart, which
is why the table gives a RANGE and not a number.

The 689 s between them is machine load, not the 74 s this session added: the
spread is in sections nothing here touched — `units: the DIAGNOSTICS build`
262→415 s, `rust: runtime unit tests` 151→282 s, `rust: green threads` 94→208 s,
`kin` 101→155 s — while the new `doors` section cost 74 s, close to the 67 s
measured standalone. So a single figure for this gate is folklore whatever care
goes into producing it; what it is good for is "did my change add a section's
worth", and the per-section itemisation it prints answers that and a total does
not.*

*The figures this replaces were right when written: 26.8 s, and 2 139 s for
`bin/test`. THE
TABLE SAID `~5 s` AND THE PARAGRAPH BELOW SAID 23 MINUTES WHERE THE TABLE SAID
20 — two numbers for one gate, fifteen lines apart, which is the shape that says
a section was spliced rather than reasoned about. `bin/release-gate` said `~25 min`, BELOW `bin/test`'s own
figure, which cannot be right for a superset: it runs `./bin/test` and then
`FLINT_SELFHOST=1 ./bin/conform-hosts` from the top, so it is `bin/test` plus a
SECOND full conform-hosts run — not plus the 8.85 s self-hosting step, which was
this correction's own first mistake. That second run is unmeasured, so the table
says `> bin/test` rather than a number nobody produced.*

`bin/check` is SIXTEEN static checks plus four heavier items, and it runs
`check-kin` (74 s) only when kin sources or the generated trees actually moved.
Measured 2026-09-28, the heavy four are still where the time is: `check-clr`
(which grew a native-door perf arm), `test/selfhost-targets.clj` 11.5 s,
`check-wedged` 9.4 s, `check-four-ops` 6.0 s, `check-api-review` 0.05 s. It read "the four sub-second static checks plus
two suites that are 1 s and 3 s", which was true of a smaller gate. Its contents were chosen by measuring: two suites that
looked like candidates are not — one is 15 s, and the other is 16 s AND fails
standalone because it needs build state the full gate happens to produce.

`bin/release-gate` adds self-hosting on both ports, which `bin/test` skips.

**`bin/test` already runs `bin/conform-hosts` in full** (see its `hosts:`
section), so running both is running the four-runtime matrix twice — about 3½
minutes of pure duplication.

*CHECKED 2026-09-26 at `be0aee8c`, and this one was right: `bin/conform-hosts`
alone is 220 s (3.67 min), exit 0, load average 1.71, nothing else in flight —
which matters because it writes fixed `/tmp` names and two runs would produce
evidence belonging to neither. The estimate I came to this measurement carrying
was about 11 minutes, from a remembered phase count rather than a clock, and it
was the wrong number. So `bin/release-gate` is `bin/test` (2 139 s) plus a second
full conform-hosts with self-hosting (220 s + 8.85 s) ≈ **39.5 min** — DERIVED
from three measurements, not timed end to end, which is why the table says
`> bin/test` rather than this figure.*

It prints its own itemisation at the end,
slowest section first. Use that before optimising anything: the first
measurement put 71% of the time in 12 of 60 sections, and the largest was a
CHECK rather than a suite.

- Run the suite your change touches first: `bb test/<name>.clj`, seconds each.
- The static checks are seconds and catch real breakage: `bin/check-decisions`,
  `bin/check-kin`, `bin/check-flattens`, `bin/check-builtins`.
- **Read the output already on your screen.** A failure count a command just
  printed is the answer; do not spend forty minutes asking again.
- **`bin/test` STOPS AT THE FIRST RED SECTION, so its tail is not a verdict.**
  Measured 2026-09-28: it failed at section 36 of 63, and the last twenty lines
  of the log were `ok` rows from the section before the red — 27 sections never
  ran. It says so itself ("A green-looking tail is absent, not passing"), three
  lines after a `grep FAIL` over the whole log had already returned 0, because
  the failure was still ahead of where the log ended. `FLINT_TEST_KEEP_GOING=1`
  runs the rest and lists every red at the end; use it whenever a change could
  plausibly break more than one thing.
- **Never edit the tree while a gate is running.** `bin/test` builds from the
  working tree, so an edit mid-run makes the result a mixture of two states —
  meaningless whether it passes or fails. Kill the run, or wait.

## 5. Access checks fail open

Capability guards, workspace grants and path containment produce no error, no
warning and a passing suite when they are broken — the same output as a working
one. Reading such code and finding it sensible is therefore not evidence, since
the sensible-sounding version is the one that gets written.

- Write the bypass **as a program and compile it**. Pair it with a control that
  differs in exactly one thing, so a failure for an unrelated reason cannot be
  mistaken for the check working.
- After fixing, probe the other routes rather than reasoning about them: alias,
  value position, macro expansion, `:inline` expansion, and the
  unnamed/default configuration.
- Keep the distinction: a **grant** is conferred from outside; a **guard** is
  written by an author about their own var. A check must read only what was
  granted, never an assertion the caller made about itself.

## 6. The four runtimes converge

Disagreement between native, wasm, JVM and CLR gets **fixed**, not escalated,
when the price is reasonable. Escalate genuinely open design decisions only.

Anything conceptually portable belongs in `kin/`. A missing kin feature is a
reason to extend kin, not to hand-write the same function three times.
`./kin/scripts/verify <file>` requires byte-identical stdout from all three
targets; `bin/check-kin` gates drift.

Do not port JVM/Clojure *implementation details*. flint strings are UTF-8, and
simulating another platform's internals to match its observable behaviour is a
bug to remove rather than a property to reproduce.

## 7. Parallelising with subagents and worktrees

**Worktrees**, for work that writes files:

```
git worktree add ../flint-<topic> -b <branch>
```

A fresh worktree **cannot build until `dist/` is generated in it**. Five files
in `dist/` are tracked, but `flintc.bytecode`, `flintc.wasm`,
`flint-runtime.wasm`, `flint-runtime-aot.wasm` and `flint-loader.wasm` are
gitignored, and `cli/build.rs` panics naming the first. So:

```
export JAVA_HOME=/opt/homebrew/opt/openjdk        # see below
cd ../flint-<topic> && ./bin/build-dist && cargo build --release -p flint-cli
```

**`JAVA_HOME` is not optional in a fresh worktree.** `bin/build-dist` runs
babashka, which resolves `bb.edn`'s `:deps` through the clojure CLI, which
shells out to a JVM — and `/usr/bin/java` on macOS is a stub that reports no
runtime. Without it the build dies at `builtins.json` with "Unable to locate a
Java Runtime", which names neither babashka nor the cause. Two agents hit this
independently before it was written down.

Each worktree carries its own `target/` and its own build time. Use one when
work genuinely needs isolation — a long refactor, a risky experiment, doc
reorganisation alongside code — not for a two-file edit.

**Subagents**, for work that only reads. Fan-out pays when answering means
sweeping many files and you want the conclusion rather than the file dumps:
auditing what is left to port, finding every site that enumerates a concept,
checking whether a recorded decision still matches the code.

- **Give each agent a falsifiable question**, not a topic. "Does `flint.rt/open`
  demand a capability, and where is that enforced?" beats "look into ports".
- **Do not accept a subagent's conclusion about security, or about whether
  something is built.** Those are the categories where a confident wrong answer
  is most likely and most costly. Verify the claim yourself.
- **Never let two agents write the same files.** Parallel reading is free;
  parallel writing needs separate worktrees.
- One gate at a time, **across every tree on the machine**. `bin/conform-hosts`
  writes fixed `/tmp` names (`/tmp/flint-rt-jvm.out` and about twenty more), so
  two runs in two worktrees overwrite each other's evidence and produce
  failures belonging to neither. Worktrees isolate the SOURCE, not the
  scratch space. An agent that needs the gate waits for the one in flight.

For headless runs, `bin/agent-tail <log>` summarises a
`claude --output-format stream-json` log — `--say` for what it said, `--stats`
for counts, cost, and whether it is still going.

## 8. Commits

The subject is a **declarative sentence** — what is now true, not what was done.
The body explains **why**, and what was wrong before.

Record the failures that shaped the change, including your own: an invalid
measurement, a wrong first approach, a fix that had to be reverted. That record
is what stops the next reader repeating it.

Commit or push only when asked. If on `main`, branch first.

## 9. A new API surface is not finished until it is signed off

`doc/api-review.md` carries one section per published surface, each with a
`**Reviewed:**` box. **Adding a surface means adding its section.** The box is
the maintainer's to tick — the same rule `DECISIONS.md` follows, and for the
same reason: a sign-off a script can produce is not a sign-off. These are the
counterpart to a roadmap tick.

**Three kinds count, because a surface is a surface whichever way it faces:**

* **guest-facing** — a namespace a program can `:require`, whether it is linked
  from `lib/` or served over a port;
* **sdk-facing** — anything under `sdks/`, which other people build against, so
  a change there is a change to somebody else's build;
* **cli-facing** — a command a user types.

`bin/check-api-review` fails when a surface has no section, when a section
names something that no longer exists, and when the two CLIs dispatch
different command sets. It never ticks a box.

**Leave a change request rather than an unticked box where you can.** An
unticked box says only "not looked at yet"; a recorded request says what is
wrong, which is the durable half.

**What a review is for.** Not whether the code works — the gate answers that.
Whether the NAMES, the ARITIES and the BOUNDARY are right: that this surface
should exist, that its parts belong to it rather than somewhere else, and that
nothing published is private or the reverse. Fourteen `lib/flint` namespaces
were outside `doc/manifest.edn` for a long time with nothing recording whether
that was deliberate, which is the state this file exists to prevent.
