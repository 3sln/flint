# Working in this repo

flint is a compiler and runtime for a Clojure-like language, with **four
runtimes** — native Rust, wasm, JVM Java, CLR C# — that are expected to agree.
`kin/` is a source-to-source generator: one `.kin` file emits Rust, Java and C#
so the three cannot drift by hand.

This file is about *how to work here*, not about what the code does. Most of it
exists because the failure it prevents has already happened.

---

## 1. The rule behind most of the others: one list, not two

**When you add a case to a concept, find every place that enumerates it.**

The strongest example is recent. Dependency-coordinate kinds were listed in two
places — `flint.deps/dep-kind` and `flint.deps.resolve/coord-kind`. They had
drifted: maven was `:maven` in one and `:mvn` in the other, and a git
coordinate written as `:git/tag` with no `:git/url` was git to one and unknown
to the other. Neither disagreement was reachable through a supported path,
**which is exactly why they survived** — two tables that agree on the common
cases look like one table until something uncommon arrives.

Merging them turned up a *third* copy, in `bin/flint`'s fetch dispatch. Nothing
found it by reading; it surfaced because unifying the keyword broke maven
fetching and a test caught it.

So:

- Before adding a kind, capability, builtin, or opcode, **grep for the existing
  members by name** and fix every list you find, in one change.
- `bin/flint` is babashka running the compiler's Clojure source and
  **deliberately duplicates** logic from `src/`. When you change one, check the
  other. They are meant to stay identical.
- If two lists must exist, make one *read* the other rather than restate it.

## 2. Recording decisions

`DECISIONS.md` is the single decision record. One `## slug` section per
decision, cited from code and docs as `` `DECISIONS.md#strings-and-matching` ``.
`bin/check-decisions` asserts every citation in `git ls-files` resolves — a
renamed or deleted section is a build failure, not a dangling pointer nobody
notices.

Numbers are **not** used for decisions. Slugs are.

Every section carries:

```
**Ratified:** ☐ not signed off
```

**Only the human maintainer may tick that box.** An agent records a decision,
states the rationale, and leaves it unsigned. Many existing entries were
written without sign-off, which is why the box exists.

**What a script cannot check, and you must.** `check_decisions.py` verifies that
citations *resolve*, not that they are *true*. Its own docstring records why: an
earlier version compared a status banner against an index row that was a
near-verbatim copy of it, so the two agreed by construction — including when
both were false. A verification pass found a decision opening "NOT BUILT —
nothing in the tree uses it yet" beside 89 sources generating 88 modules. **A
check that compares two copies of one claim is not a check.** When you cite a
decision, read the code it describes.

`ROADMAP.md` holds state: sections, checklists, what is built and what is not.
A decision explains *why*; the roadmap tracks *whether*.

## 3. Verify before claiming

**Rebuild both arms the same way before comparing anything.** A stale artefact
never announces itself — it looks like a real finding.

| What you changed | What must be rebuilt |
|---|---|
| `src/` or `lib/` | `cargo build --release -p flint-cli` — `lib/` is embedded by `cli/build.rs` |
| anything, before `flint run` | `cargo build --release -p flint-cli` |
| runtime/compiler artifacts | `bin/build-dist` |
| unit modules | `bin/build-units` |

`bin/build-dist` does **not** rebuild `target/release/flint`. This has produced
false results more than once: a clean PASS that was entirely a binary from
before the change, and a "port disagrees with native" that was a stale arm.

Two symptoms that mean *check freshness* before believing a result: the two arms
agree **exactly** (a measurement that cannot tell its arms apart is not evidence
they are equal), and a test that fails on a port but passes on native right
after a native change.

**Measure at the boundary the decision is about.** A question about a shipped
artefact is answered by invoking that artefact. Finer-grained units are not
automatically more rigorous — `ns/instruction` is right for throughput and wrong
for latency, because it factors out the process start and warmup that dominate a
short run. Before believing any A/B, state what each arm does end to end; if the
responsibilities differ, the ratio is between pipelines, not between the things
being compared.

## 4. The gate is the last check, not the first

`bin/test` and `bin/conform-hosts` together take roughly **forty minutes**.

- Run the suite your change touches first: `bb test/<name>.clj`, seconds each.
- Static checks are seconds and catch real breakage: `bin/check-decisions`,
  `bin/check-kin`, `bin/check-flattens`, `bin/check-builtins`.
- **Read the output already on your screen.** A failure count that a command
  just printed is the answer; do not launch a forty-minute gate to ask it again.
- **Never edit the tree while a gate is running.** `bin/test` builds from the
  working tree, so an edit mid-run makes the result a mixture of two states —
  meaningless whether it passes or fails. Kill the run, or wait.

## 5. Security-ish code fails open

Access checks — capability guards, workspace grants, path containment — produce
**no error, no warning, and a passing suite** when they are broken. That is the
same output as a working one, so reading the code and finding it sensible is not
evidence. The most persuasive-looking version of one such fix was the worst: it
let a var's own guard authorise its own body, so any workspace could mint
authority.

- Write the bypass **as a program and compile it**. Pair it with a control that
  differs in exactly one thing, so a failure for an unrelated reason cannot be
  mistaken for the check working.
- After fixing, probe the other routes rather than reasoning about them: alias,
  value position, macro expansion, `:inline` expansion, and the
  unnamed/default configuration.
- Keep the distinction: a **grant** is conferred from outside; a **guard** is
  written by an author about their own var. A check must read only what was
  granted.

## 6. The four runtimes converge

Disagreement between native, wasm, JVM and CLR gets **fixed**, not escalated,
when the price is reasonable. Escalate only genuinely open design decisions.

Anything conceptually portable belongs in `kin/`. A missing kin feature is a
reason to extend kin, not a reason to hand-write the same function three times.
`./kin/scripts/verify <file>` requires byte-identical stdout from all three
targets; `bin/check-kin` gates drift.

Do not port JVM/Clojure *implementation details* — flint strings are UTF-8, and
simulating UTF-16 ordering to match Clojure's hashing was removed rather than
ported.

## 7. Parallelising with subagents and worktrees

**Worktrees**, for work that changes files. Two exist already:

```
git worktree add ../flint-<topic> -b <branch>
```

**A fresh worktree cannot build until you generate `dist/` in it.** Five files
in `dist/` are tracked, but `flintc.bytecode`, `flintc.wasm`,
`flint-runtime.wasm`, `flint-runtime-aot.wasm` and `flint-loader.wasm` are
gitignored, and `cli/build.rs` panics with *"dist/flintc.bytecode is missing. It
is generated: run `bin/build-dist` first."* So in any new worktree:

```
cd ../flint-<topic> && ./bin/build-dist && cargo build --release -p flint-cli
```

Budget for it: each worktree carries its own `target/` and its own build time.
Use one when work genuinely needs isolation — a long refactor, a risky
experiment, doc reorganisation alongside code — not for a two-file edit.

**Subagents**, for work that only needs to *read*. Fan-out pays when answering
means sweeping many files and you want the conclusion rather than the file
dumps: auditing what is left to port, finding every site that enumerates a
concept, verifying whether a recorded decision matches the code. A six-agent
sweep over the decision record found six stale banners, six diverged entries
and four superseded ones — work that would have been prohibitive serially.

Rules that keep it honest:

- **Give each agent a falsifiable question**, not a topic. "Does `flint.rt/open`
  demand a capability, and where is that enforced?" beats "look into ports".
- **Do not trust a subagent's conclusion about security or about whether
  something is built.** Both are the categories where a confident wrong answer
  is most likely and most costly. Verify the specific claim yourself.
- **Never let two agents write the same files.** Parallel *reading* is free;
  parallel writing needs separate worktrees.
- One gate at a time, in one tree. See §4.

For headless runs, `bin/agent-tail <log>` summarises a
`claude --output-format stream-json` log — `--say` for what it said, `--stats`
for counts, cost, and whether it is still going.

## 8. Commits

The convention here is a **declarative sentence** as the subject — what is now
true, not what was done: *"The metadata is read, not merely carried"*, *"Pods
are an ordinary dependency, driven by a manifest"*.

The body explains **why**, and what was wrong before. Record the failures that
shaped the change, including your own — a commit here says a measurement was
invalid and why, rather than quietly omitting it. That record is what stops the
next agent repeating it.

Commit or push only when asked. If on `main`, branch first.
