# Working in this repo

flint is a compiler and runtime for a Clojure-like language, with **four
runtimes** — native Rust, wasm, JVM Java, CLR C# — that are expected to agree.
`kin/` is a source-to-source generator: one `.kin` file emits Rust, Java and C#
so the three cannot drift by hand.

This file is about *how to work here*, not about what the code does.

---

## 1. One list, not two

**When you add a case to a concept, find every place that enumerates it.**
Two tables that agree on the common cases are indistinguishable from one
table until an uncommon case arrives — which is why the drift is invisible
until something unusual is asked.

- Before adding a kind, capability, builtin or opcode, grep for the existing
  members by name and fix every list you find, in one change.
- If two lists must exist, make one *read* the other rather than restate it.
- `bin/flint` is a thin `sh` wrapper over a JVM Clojure driver
  (`driver/flint/driver/main.clj`), not babashka, and no longer a second copy
  of `flint.compiler.resolve`'s wave walk. What it still owns is finding files on a
  search path, reading `deps.edn` for grants and tags, and reading source
  text through the kin-generated Java reader -- the compiler reads none
  (`DECISIONS.md#one-reader-and-no-other`).
- Toolchain versions are pinned once, not per caller: `bin/nightly-toolchain`,
  `bin/bb-version` and `bin/clojure-version` are the single source CI reads
  too (`DECISIONS.md#pin-the-nightly-toolchain`,
  `DECISIONS.md#pin-the-babashka-version`).

## 2. Recording decisions

`DECISIONS.md` is the single decision record: one `## slug` section per
decision, cited as `` `DECISIONS.md#strings-and-matching` ``.
`bin/check-decisions` asserts every citation in `git ls-files` resolves, so a
renamed or deleted section fails the build rather than leaving a dangling
pointer. Slugs, never numbers. Every section carries `**Ratified:** ☐ not
signed off` — **only the human maintainer ticks that box**; an agent records
the decision and its rationale, and leaves it unsigned. The checker verifies
citations *resolve*, not that they are *true*: when you cite a decision, read
the code it describes and correct the record if they disagree — a status
line is a claim about the tree, and it decays silently.

**Record how a claim was obtained, next to the claim.** Four shapes read as
true forever and are not: a benchmark ratio with no host/machine/method; a
count produced by a grep, which counts what the pattern understood rather
than what was asked; a phrase true of one subject read as another
("compiled through LLVM" is ordinary of anything in Rust, and reads as a
backend); a feature recorded BUILT whose only exercise was a path nobody ran.
A number carries its method or it is folklore; a status that says BUILT names
the test that proves it.

`ROADMAP.md` holds state — what is built and what is not. A decision explains
*why*; the roadmap tracks *whether*.

## 3. Verify before claiming

**Rebuild both arms the same way before comparing anything.** A stale
artefact does not announce itself; it presents as a real finding.

| What you changed | What must be rebuilt, in order |
|---|---|
| `src/` — the COMPILER | `bin/build-dist`, then `bin/build-cli`, then `FLINT_DIST_FRESH=1 ./sdks/cli/build`, then `./sdks/esm/build` |
| `lib/` — the stdlib | same order as `src/`, above |
| unit modules | `bin/build-units` |

`bin/build-dist` writes `dist/slots.json` (embedded by `cli/src/main.rs`) and
is copied, not produced, by cargo as `dist/flintc.bytecode` — skip it and the
CLI silently keeps the old slot table or the old compiler. Neither step
rebuilds the other. **A third door has its own copy:** `sdks/cli/build`
copies the wasm compiler, runtimes and stdlib into `sdks/cli/dist/`,
untouched by the other two steps (`FLINT_DIST_FRESH=1` skips only its own
redundant re-run of `bin/build-dist`). `./sdks/esm/build` and
`./bin/check-ports` build the two remaining gaps (`sdks/esm/dist/flint.js`,
`runtimes/jvm/classes`).

Check freshness before believing a result that looks too clean: the two arms
agreeing **exactly**, or a port-only test failing right after a native-only
change. Two incidents that read like real divergences and were stale builds
instead: `DECISIONS.md#rebuild-order-pitfalls`.

**A verification expires:** a later refactor, or a fix landed in one reader
and not its twin, can move the ground under an earlier check with no failing
test to show it — the test was written against the old behaviour. Confirming
a bug and confirming its fix are different acts, and measuring at the
boundary the decision is about (the shipped artefact, not `ns/instruction`,
which factors out process start and warmup) is what tells you which you did.

`bin/build-cli` is `cargo build --release -p flint-cli` with the checkout path
and cargo-registry path remapped to stable placeholders
(`DECISIONS.md#reproducible-build-paths`) — call it instead of the bare
command, and everything else in this tree that builds the same crate
(`bin/test`, `bin/conform-hosts`, CI's `binaries.yml`) does the same, because a
second invocation of the same package with different `RUSTFLAGS` invalidates
cargo's cache for it rather than reusing what the first one built.


## 4. The gate is the last check, not the first

**THE GATE IS TIERED. Do not run the expensive one on every change.**

    bin/check          ~51 s     every change
    bin/test           39–50 min before pushing a branch, and on a PR
    bin/release-gate   > bin/test  before a versioned release

The derivations behind these figures, and two earlier figures that were
wrong, are in `DECISIONS.md#gate-timings`. A single number is folklore; what
matters is whether a change added a section's *worth*, which the per-section
itemisation both commands print at the end answers directly.

**`bin/test`/`bin/release-gate` run on GitHub by manual dispatch, never on
push** (`.github/workflows/test.yml` is `workflow_dispatch`-only):

    gh workflow run test.yml --ref main -f keep_going=true -f selfhost=false

Inputs: `keep_going` (`FLINT_TEST_KEEP_GOING`), `selfhost` (`FLINT_SELFHOST`,
also self-hosts the JVM/CLR ports, default false), `kin_ref` (the `3sln/kin`
ref for the `../kin` sibling). CI runs `FLINT_DOORS_CORPUS=all`.

**Fallback, only when GitHub can't run it** (minutes exhausted, dispatch
refused, stuck queued, an outage): run locally, detached, in the main
checkout, one gate at a time across every tree on the machine (§7), nothing
editing it meanwhile —

    FLINT_TEST_KEEP_GOING=1 nohup sh -c './bin/test; echo "exit=$?"' > /tmp/flint-test.log &

Every gate report says which way it ran. Pushing `main` is fine; check your
diff doesn't touch a push-triggered workflow's own paths first (`publish.yml`
is manual-only; `deploy-deck.yml` triggers only on `deck/**` and itself).

- Run the suite your change touches first (`bb test/<name>.clj`, seconds
  each); the static checks (`bin/check-decisions`, `bin/check-kin`,
  `bin/check-flattens`, `bin/check-builtins`) catch real breakage just as fast.
- **Read the output already on your screen** before asking again, and check
  `uptime` before treating a gate's wall time as a measurement — load on the
  machine inflates timing without making a green run less green.
- **`bin/test` stops at the first red section, so its tail is not a
  verdict.** `FLINT_TEST_KEEP_GOING=1` lists every red at the end; use it
  whenever a change could plausibly break more than one thing.
- **Never edit the tree while a gate is running** — kill it, or wait.

## 5. Access checks fail open

Capability guards, workspace grants and path containment produce no error, no
warning and a passing suite when they are broken — the same output as a
working one. Reading such code and finding it sensible is not evidence, since
the sensible-sounding version is the one that gets written.

- Write the bypass **as a program and compile it**, paired with a control
  that differs in exactly one thing, so an unrelated failure isn't mistaken
  for the check working.
- After fixing, probe the other routes: alias, value position, macro
  expansion, `:inline` expansion, the unnamed/default configuration.
- A **grant** is conferred from outside; a **guard** is written by an author
  about their own var. A check must read only what was granted, never an
  assertion the caller made about itself.
- **The trust boundary is the control plane, not a call thread.** Runtime
  code (`:bind`/`:unbind`/`:close`/`:snapshot`, the loop serving a bound port)
  is trusted; a call thread only runs a guest function the loop looked up by
  name, inside a `try` that already catches whatever it does — it is not a
  boundary, so control-plane restrictions don't belong on it
  (`DECISIONS.md#the-control-plane-is-the-runtimes`).

## 6. The four runtimes converge

Disagreement between native, wasm, JVM and CLR gets **fixed**, not escalated,
when the price is reasonable; escalate genuinely open design decisions only.
When a language-semantics question has no recorded answer here, default to
canonical Clojure behaviour rather than guessing.

- Anything conceptually portable belongs in `kin/` — a missing kin feature is
  a reason to extend kin, not to hand-write the same function three times.
  `./kin/scripts/verify <file>` requires byte-identical stdout from all three
  targets; `bin/check-kin` gates drift.
- Do not port JVM/Clojure *implementation details*: flint strings are UTF-8,
  and simulating another platform's internals to match its observable
  behaviour is a bug to remove, not a property to reproduce.
- A source may only declare and define the namespace it was *resolved as* —
  never a different one, whatever its filename claims — enforced the same way
  by every resolver on every door
  (`DECISIONS.md#a-source-defines-only-its-own-namespace`).

## 7. Parallelising with subagents and worktrees

**Worktrees**, for work that writes files:

    git worktree add ../flint-<topic> -b <branch>

A fresh worktree can't build until its generated artefacts exist (several
`dist/` files, `sdks/esm/dist/`, `runtimes/jvm/classes` are gitignored). In
order:

    export JAVA_HOME=/opt/homebrew/opt/openjdk   # bin/build-dist shells out to a JVM; /usr/bin/java on macOS is a stub
    cd ../flint-<topic>
    ./bin/build-dist && bin/build-cli
    ./sdks/esm/build && ./bin/check-ports

`bin/build-dist` also requires the `clojure` CLI, pinned by
`bin/clojure-version`: it calls `bin/flint`, which runs `clojure -M -m
flint.driver.main` rather than babashka
(`DECISIONS.md#namespaces-over-the-system-port`). The first call in a fresh
worktree is slow (writes `.cpcache/`, compiles the kin Java reader); later
calls are faster. Each worktree carries its own `target/` — use one when
work genuinely needs isolation, not for a two-file edit.

**Finishing worktree work:**

- **Commit before reporting done, and confirm `git log origin/main..HEAD`
  shows it** — uncommitted work has been lost when a worktree was removed
  before this was checked.
- **If a merge stops partway, stop the command chain there** rather than
  chaining `&&` past it and assuming the rest ran, and never
  `git worktree remove --force` a tree you haven't confirmed clean.
- **Wait on your own PID or output file, in a bounded poll loop — never
  `pgrep -f <pattern>`.** Parallel worktrees run the same commands under the
  same names, and a pattern match can wait on, or signal, the wrong tree's
  process.

**Subagents**, for work that only reads — fan-out pays when answering means
sweeping many files and you want the conclusion, not the file dumps.

- **Give each agent a falsifiable question**, not a topic, and don't accept
  its conclusion about security or whether something is built — verify those
  yourself.
- **Never let two agents write the same files**; parallel writing needs
  separate worktrees.
- **One gate at a time, across every tree on the machine**:
  `bin/conform-hosts` writes fixed `/tmp` names, so two runs in two worktrees
  produce failures belonging to neither.

`bin/agent-tail <log>` summarises a `claude --output-format stream-json` log
for headless runs.

## 8. Commits

The subject is a **declarative sentence** — what is now true, not what was
done. The body explains **why**, and what was wrong before.

Record the failures that shaped the change, including your own — that record
is what stops the next reader repeating it.

Commit or push only when asked. If on `main`, branch first.

## 9. A new API surface is not finished until it is signed off

`doc/api-review.md` carries one section per published surface, each with a
`**Reviewed:**` box — the maintainer's to tick, for the same reason
`DECISIONS.md`'s box is. **Adding a surface means adding its section.** Three
kinds count: **guest-facing** (a namespace a program can `:require`),
**sdk-facing** (anything under `sdks/`), **cli-facing** (a command a user
types).

`bin/check-api-review` fails when a surface has no section, names something
gone, or the two CLIs dispatch different command sets — it never ticks a box.
**Leave a change request rather than an unticked box where you can** — an
unticked box says "not looked at"; a recorded request says what is wrong.

**What a review is for:** not whether the code works — the gate answers
that — but whether the NAMES, ARITIES and BOUNDARY are right: that this
surface should exist, its parts belong to it, and nothing published is
private or the reverse.
