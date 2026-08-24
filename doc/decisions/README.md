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
| [0012](0012-matching-over-ropes.md) | The matcher must consume a rope → Pike VM | **Roadmap — next work after the open bugs** |
| [0013](0013-emit-wasm-instead-of-dispatch.md) | AOT regions instead of dispatching | **Deferred**, pending one cheap measurement |
| [0014](0014-debug-runner.md) | DAP, nREPL, and `(break)` | **Roadmap, not next.** Cheap because a breakpoint is a park |
| [0015](0015-snapshots.md) | VM snapshots: instant, exportable, inspectable | **Roadmap.** The answer to instruments that lie; shares its reader with 0014 |
| [0016](0016-two-builds.md) | A stripped production VM; diagnostics optional | **Roadmap.** Cross-cutting; supersedes the per-feature clauses in 0009/0014/0015 |
| [0019](0019-thread-pool.md) | A thread pool: shared heap vs heap-per-worker | **Roadmap.** Model B is close; Model A is a collector rewrite and spends determinism |
| [0018](0018-cross-runtime-benchmarks.md) | Benchmark across wasm runtimes | **Roadmap.** Every current number is V8; decides 0013 per engine and 0010's SDK-vs-port |
| [0017](0017-profiler.md) | Profiler: named blocks, CPU vs waiting | **Roadmap, not next.** Deterministic because instruction counts are; gives 0013 its measurement |

## What is actually next

1. **Close the open runtime bugs** — the dangling `stack[1]` root and
   `document.clj`'s two wave assertions. `../HANDOFF.md` is the live state.
2. **Ropes** (`0011` §1–2), then **the Pike VM** (`0012`): shared cljc pattern
   compiler, a cljc reference simulator, and the native wasm simulator — the
   native one is what makes the route feasible, not a later optimisation. No
   Rust regex crate and no delegation to host engines.
3. Everything else is unscheduled.

## How to read this directory

- A **superseded** decision keeps its analysis and loses its conclusion. Nothing
  here is deleted, because the reasoning that produced a wrong answer is usually
  the reasoning somebody needs to not repeat it.
- A **roadmap** file carries a NOT BUILT banner at the top. If it does not, and
  it is not in the table above as shipped, treat that as a bug in this index.
