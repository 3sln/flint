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
| [0005](0005-threads-and-ports.md) | Green threads, ports, protocols | **Shipped.** The port bug is closed; `../HANDOFF.md` is its post-mortem |
| [0006](0006-host-abi.md) | Tokens, one event queue, two lifetimes | **Shipped** |
| [0007](0007-construe-benchmarks.md) | Benchmark the decision, not the runtime | **Done**; results in the README |
| [0008](0008-document-resource.md) | Structure eagerly, content on demand | **Shipped.** Both wave assertions pass: 64 waves, 4 194 304 bytes |
| [0009](0009-resource-limits.md) | Hard limits; the loop that does not count | **Shipped** |
| [0010](0010-other-hosts.md) | SDKs, and JVM/CLR ports | **Partly shipped.** The native target runs images with no wasm engine; the CLI is built on it. JVM/CLR not started |
| [0011](0011-strings-and-matching.md) | Rope strings; what to do about regex | **Shipped** (§1–2). §5's conclusion superseded by 0012 |
| [0012](0012-matching-over-ropes.md) | The matcher must consume a rope → Pike VM | **Shipped.** `re-find`/`re-matches`/`re-seq`; linear on the catastrophic case |
| [0013](0013-emit-wasm-instead-of-dispatch.md) | AOT regions instead of dispatching | **SHELVED.** Built and measured at 1.07–1.25×; parked for strings/regex. Why it lost is recorded |
| [0014](0014-debug-runner.md) | DAP, nREPL, and `(break)` | **Roadmap, not next.** Cheap because a breakpoint is a park |
| [0015](0015-snapshots.md) | VM snapshots: instant, exportable, inspectable | **Shipped.** Capture, import, inspector; opt-in, +18 569 bytes |
| [0016](0016-two-builds.md) | A stripped production VM; diagnostics optional | **Shipped.** Both builds tested every run; supersedes the clauses in 0009/0014/0015 |
| [0019](0019-thread-pool.md) | A thread pool: shared heap vs heap-per-worker | **Roadmap.** Model B is close; Model A is a collector rewrite and spends determinism |
| [0018](0018-cross-runtime-benchmarks.md) | Benchmark across wasm runtimes | **Done**, eight engines. Decided 0010's JVM tier; the README's numbers now say V8 on them |
| [0017](0017-profiler.md) | Profiler: named blocks, CPU vs waiting | **Roadmap, not next.** Deterministic because instruction counts are; gives 0013 its measurement |
| [0020](0020-module-metadata-and-shards.md) | A module declares its build; shards as library modules | **Part 1 shipped** (custom section, `flint inspect`). Part 2 — shards — not built |
| [0021](0021-cli.md) | A native CLI: cross compiler, interpreter, capabilities | **Partly shipped.** The native binary, the `0025` surface and `deps.edn` work; `:to :llvm` and the entry map do not |
| [0022](0022-opaque-values.md) | Opaque values: identity without structure | **Shipped.** `(opaque)`, host-minted capabilities, invalidated on snapshot import |
| [0023](0023-construe-integration-bar.md) | What "ready for construe" means, concretely | **Live.** The milestone the current work is aimed at |
| [0025](0025-structured-ports.md) | A wire codec, and structured ports | **Roadmap.** Reverses 0006's no-transfer rule, which left the door open |
| [0026](0026-tables.md) | Tables: columnar storage that is a value | **Queued.** A vector of maps outside, a columnar B-tree inside |
| [0027](0027-ports-are-the-hosts.md) | Ports belong to the host, not to a sandbox | **Queued.** Local ports stay by-reference; global ones encode, and the encode is the GC boundary |
| [0028](0028-drivers.md) | A driver: ports are the only way to drive a sandbox | **Partly shipped.** Rust has `Driver`/`ThreadPool`/async `call`, 200 requests coalesced into 1 dispatch; the parallel collector does not exist |
| [0024](0024-no-runtime-linking.md) | No linking at compile time; byte strings and transient ropes | **Partly shipped.** The splice and the tree shaker work with no linker; the byte strings do not exist |

## What is actually next

Items 1–6 of the user's original ordering are done. What is recorded below is
what remains, and what each thing is waiting on.

**Open, in the order the last measurement left them:**

1. **The wire codec and structured ports** (`0025`) — one encoding for
   everything crossing the host boundary, an entry that takes a map, ports
   carrying values rather than text, and a transfer table so a capability can
   be delegated. The entry change is breaking and is affordable exactly once,
   before anything is published.
2. **No compile-time linking** (`0024`) — `wasm-ld` once when flint is built,
   a prebuilt runtime embedded in the compiler, and `flint compile wasm` /
   `wasm-aot` splicing into it. Blocked on one thing: `flint.wasm` is built on
   Java byte arrays, so it does not compile under flint. Which needs **byte
   strings** -- the rope treatment for bytes -- and a **transient** for them,
   measured at 9.7x on the shape that wants it.
3. **Shards** (`0020` part 2) — the format is in place; the cost is the
   classification. A shard may privately bundle pure code, but must *import*
   anything carrying identity or mutable state, because a second copy is a
   second identity. Protocols are the hard case, and deciding which namespaces
   fall on which side is the work.
4. **AOT for the native target** (`0010`) — the native interpreter is built and
   the CLI runs on it (3.5 s against 15.6 s), but without AOT a native
   interpreter gives up most of the benefit. The wasm AOT emitter's analysis
   carries over; what differs is emission, and memory is `base + u32` rather
   than a linear memory, which is one base register.
5. **The JVM, tier 2** (`0010`) — the route is **decided** and not built.
   Chicory measured 39× V8 at its best, which rules tier 1 out; tier 2 is
   porting the VM. `0018` is what settled it.
6. **`clojure.zip`, `clojure.data`, `clojure.datafy`** — the Clojars survey
   said implementing these unblocks more third-party code than maven's
   transitive resolution would have, which is why that half of `0021` is
   cancelled.
7. **The rest of `0021`** — the native binary itself (wasmer `create-exe` or a
   small Rust host), cross-compilation backends, nREPL, and capability
   injection on the command line.
8. **Thread pool** (`0019`) — strictly opt-in and free when declined; gas drawn
   in per-thread blocks; snapshots halt the whole app at a safe point.
9. **Profiler** (`0017`) — deterministic, because instruction counts are.
10. **Debug runner** (`0014`) — cheap because a breakpoint is a park, and it
    shares its reader with `0015`, which is now built.
11. **Tables** (`0026`) — a vector of maps outside, a columnar B-tree inside.
    Queued; its codec tag wants adding while `0025`'s format is still open.

12. **A driver** (`0028`) — the host stops advancing a sandbox directly, so a
    pool can, and the destination is K threads over ONE sandbox. Debouncing
    and concurrency live in one seam, `call` becomes asynchronous, and `Rt`
    splits into a shared sandbox and per-executor contexts while that is still
    a no-op refactor. Wanted BEFORE the SDKs are published, because `call`
    cannot stop being synchronous afterwards.

13. **Ports belong to the host** (`0027`) — a global port's registry moves out
    of the sandbox, the system channel is passed IN, and creating one becomes a
    dispatch. Local ports are untouched and still pass by reference. Needed
    before `0025`'s system port is built, because building it sandbox-local
    would be building the thing this replaces.

**Closed, with the result rather than the plan:**

- ~~**AOT** (`0013`)~~ — **built, measured, shelved.** 1.07–1.25×, for +98%
  module and +12% cold start. The lever turned out to be elsewhere: extending
  `register-native-aliases!` per-arity made the **interpreter** 1.85× faster
  and cost nothing. `0018` later found AOT *helps a JIT and does nothing for an
  interpreter* — you trade interpreted dispatch for interpreted execution.
- ~~**Ropes and the Pike VM**~~ (`0011` §1–2, `0012`) — both shipped, matcher
  over a rope cursor, no Rust regex crate and no delegation to host engines.
- ~~**Cross-runtime benchmarks**~~ (`0018`) — eight engines.
- ~~**The port bug**~~ — `../HANDOFF.md` is now a post-mortem, not live state.
  Its five rules are the standard the work is held to.

## How to read this directory

- A **superseded** decision keeps its analysis and loses its conclusion. Nothing
  here is deleted, because the reasoning that produced a wrong answer is usually
  the reasoning somebody needs to not repeat it.
- A **roadmap** file carries a NOT BUILT banner at the top, and a **shipped**
  one says so in the same place. Either kind of disagreement between a file's
  banner and its row here is a bug in this index — including the direction that
  understates what exists, which is the one that gets something rebuilt.
