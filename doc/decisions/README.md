# Decisions — index and status

Each file is one decision with its reasoning. **Status matters as much as
content**: this directory mixes descriptions of shipped behaviour with plans for
work that does not exist, and an agent reading it once already mistook the second
for the first.

| # | Decision | Status |
|---|---|---|
| [0001](0001-dispatch.md) | Interpreter vs AOT; stack vs register | **Shipped.** Stack machine; dispatch measured at 6.2 ns/instruction |
| [0002](0002-modularity.md) | Only reachable code ships, builtins included | **Requirement stands; mechanism superseded by 0003** |
| [0003](0003-namespace-units.md) | A namespace is a compilation unit; linking composes them | **Shipped** |
| [0004](0004-exclude-and-unit-path.md) | `:exclude` as an assertion; `:wasm-path` | **Shipped** |
| [0005](0005-threads-and-ports.md) | Green threads, ports, protocols | **Shipped**, one open bug (see `../HANDOFF.md`) |
| [0006](0006-host-abi.md) | Tokens, one event queue, two lifetimes | **Shipped** |
| [0007](0007-construe-benchmarks.md) | Benchmark the decision, not the runtime | **Done**; results in the README |
| [0008](0008-document-resource.md) | Structure eagerly, content on demand | **Shipped**, two wave assertions failing |
| [0009](0009-resource-limits.md) | Hard limits; the loop that does not count | **Shipped** |
| [0010](0010-other-hosts.md) | SDKs, and JVM/CLR ports | **Roadmap** |
| [0011](0011-strings-and-matching.md) | Rope strings; what to do about regex | **Roadmap.** §1–2 current; §5's conclusion superseded by 0012 |
| [0012](0012-matching-over-ropes.md) | The matcher must consume a rope → Pike VM | **Roadmap.** After AOT; ropes first, since the matcher runs over a rope cursor |
| [0013](0013-emit-wasm-instead-of-dispatch.md) | AOT regions instead of dispatching | **Next after the open bugs.** Guard-only per 0016, so chunks can be large |
| [0014](0014-debug-runner.md) | DAP, nREPL, and `(break)` | **Roadmap, not next.** Cheap because a breakpoint is a park |
| [0015](0015-snapshots.md) | VM snapshots: instant, exportable, inspectable | **Roadmap.** The answer to instruments that lie; shares its reader with 0014 |
| [0016](0016-two-builds.md) | A stripped production VM; diagnostics optional | **Roadmap.** Cross-cutting; supersedes the per-feature clauses in 0009/0014/0015 |
| [0019](0019-thread-pool.md) | A thread pool: shared heap vs heap-per-worker | **Roadmap.** Model B is close; Model A is a collector rewrite and spends determinism |
| [0018](0018-cross-runtime-benchmarks.md) | Benchmark across wasm runtimes | **Roadmap.** Every current number is V8; decides 0013 per engine and 0010's SDK-vs-port |
| [0017](0017-profiler.md) | Profiler: named blocks, CPU vs waiting | **Roadmap, not next.** Deterministic because instruction counts are; gives 0013 its measurement |
| [0020](0020-module-metadata-and-shards.md) | A module declares its build; shards as library modules | **Roadmap.** Custom section for runners; a shard is a third `:kind` |
| [0021](0021-cli.md) | A native CLI: cross compiler, interpreter, capabilities | **Roadmap.** After AOT and ropes/regex. Removes the babashka dependency |

## What is actually next

The user's stated ordering, after the open bugs close.

1. **Close the open runtime bugs** — the dangling `stack[1]` root and
   `document.clj`'s two wave assertions. `../HANDOFF.md` is the live state.
2. **AOT** (`0013`), on the guard-only design in `0016` — a closure call that
   checks a blocked flag and branches out, rather than colouring functions by
   whether they block. That is what allows large chunks; chain them so the wasm
   engine can still optimise across them while keeping granular re-entry points.
3. **Ropes** (`0011` §1–2), then **the Pike VM** (`0012`): shared cljc pattern
   compiler, a cljc reference simulator, and the native wasm simulator — the
   native one is what makes the route feasible, not a later optimisation. No
   Rust regex crate and no delegation to host engines. Ropes first because the
   matcher runs over a rope cursor.
4. **Thorough benchmarks and testing**, including `0018` across wasm runtimes.
5. **Profiler** (`0017`).
6. **The CLI** (`0021`) — a native binary with the compiler and the interpreter,
   cross compiling to every supported target, with capability injection and
   `deps.edn`. The user placed it after AOT and the string work; it is listed
   here because it is the piece that makes flint installable by someone who does
   not already have a Clojure toolchain.
7. **Thread pool** (`0019`) — expected to be taken up, strictly opt-in and free
   when declined; gas drawn in per-thread blocks; snapshots halt the whole app
   at a safe point.

Unscheduled but specified: **`0020`** — build metadata in a wasm custom section
so a runner can inspect a pre-built module, and **shards**: an entry namespace
compiled as a loadable library module with no runtime of its own. Its
compatibility key must be drawn so that `0016`'s two builds stay
shard-compatible, and its hard problem is that *self-contained* conflicts with
protocol identity.

## How to read this directory

- A **superseded** decision keeps its analysis and loses its conclusion. Nothing
  here is deleted, because the reasoning that produced a wrong answer is usually
  the reasoning somebody needs to not repeat it.
- A **roadmap** file carries a NOT BUILT banner at the top. If it does not, and
  it is not in the table above as shipped, treat that as a bug in this index.
