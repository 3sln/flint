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

`ROADMAP.md` holds state: sections, checklists, what is built and what is not.
A decision explains *why*; the roadmap tracks *whether*.

## 3. Verify before claiming

**Rebuild both arms the same way before comparing anything.** A stale artefact
does not announce itself; it presents as a real finding.

| What you changed | What must be rebuilt |
|---|---|
| `src/` or `lib/` | `cargo build --release -p flint-cli` — `lib/` is embedded by `cli/build.rs` |
| anything, before `flint run` | `cargo build --release -p flint-cli` |
| runtime/compiler artifacts | `bin/build-dist` |
| unit modules | `bin/build-units` |

`bin/build-dist` does **not** rebuild `target/release/flint`.

Two symptoms that mean *check freshness* before believing a result: the two arms
agree **exactly** — a measurement that cannot tell its arms apart is not
evidence they are equal — and a test that fails on a port but passes on native
right after a native change.

**Measure at the boundary the decision is about.** A question about a shipped
artefact is answered by invoking that artefact. A finer-grained unit is not
automatically more rigorous: `ns/instruction` is right for throughput and wrong
for latency, because it factors out the process start and warmup that dominate a
short run. Before believing any A/B, state what each arm does end to end; if
their responsibilities differ, the ratio is between pipelines, not between the
things being compared.

## 4. The gate is the last check, not the first

`bin/test` and `bin/conform-hosts` together take roughly **forty minutes**.

- Run the suite your change touches first: `bb test/<name>.clj`, seconds each.
- The static checks are seconds and catch real breakage: `bin/check-decisions`,
  `bin/check-kin`, `bin/check-flattens`, `bin/check-builtins`.
- **Read the output already on your screen.** A failure count a command just
  printed is the answer; do not spend forty minutes asking again.
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
cd ../flint-<topic> && ./bin/build-dist && cargo build --release -p flint-cli
```

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
- One gate at a time, in one tree. See §4.

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
