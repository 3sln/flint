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
| [0013](0013-emit-wasm-instead-of-dispatch.md) | AOT regions instead of dispatching | **SHELVED, and now CORRECT.** Built and measured; parked for strings/regex. The two resume-point bugs that shelving named are fixed and tested. Why it lost is recorded |
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
| [0026](0026-tables.md) | Tables: columnar storage that is a value | **Partly shipped.** Steps 1-6: the value type, row refs, `assoc`/`conj`/`migrate`, the constant column encoding. 12x smaller resident than a vector of maps and 2.6x cheaper to scan; a constant column costs nothing per row; dropping a column from 50 000 rows costs 218 gas. The transient, the column API and the codec tag are not built, and nor is the JVM/CLR port |
| [0027](0027-ports-are-the-hosts.md) | Ports belong to the host, not to a sandbox | **Shipped**, all four runtimes. One `K_BRIDGE` and no host port; `open` is a request on the system port; handles interned per host id and reference-counted by HOLDERS; the codec runs at the bridge and the guest has none. Weak-table fixup through a copy, and codec back-references, are not built |
| [0030](0030-clr-runtime.md) | The CLR runtime | **Partly shipped.** Self-hosts byte for byte, all 155 builtins, threads, AOT to IL at 1.8x, all nine conformance cases |
| [0031](0031-a-vec-of-values-is-not-a-root.md) | A `Vec<Value>` is not a root | **Shipped.** `array-map` gathered values into a Rust vector across calls that collect, so a big map literal from a lazy seq wrote stale pointers. It broke self-hosting. Same fault found and fixed in the codec |
| [0032](0032-checks.md) | Checks that cost nothing in the build that ships | **Shipped.** `#?(:flint/check ...)` on by default, removed by `:optimize [perf]` before the source is read. A `Predicate` protocol that dispatches on the value, so a predicate in a local explains itself. `^:flint.check/test` collected by the compiler; `flint test` on both CLIs; 53 checks on all four runtimes |
| [0033](0033-bridges.md) | A bridge owns its messages | **Roadmap.** Messages in flight live in the sandbox heap and die with it, so a bridge cannot outlive its writer. Bridge-owned arenas, symmetric ends, a port as six verbs that name no sandbox, and per-port formats the WRITER serialises into |
| [0034](0034-tagged-literals.md) | A tagged literal is a value, not a map | **Shipped.** `TY_TAGGED` on all four runtimes: `:tag` and `:form` read like a map, `=` and `hash` are structural, `assoc` keeps the type or refuses by name. The two-key map was ambiguous with a map in every format that has tags and lost the namespace in JSON |
| [0035](0035-reader-tags.md) | A reader tag is a name; the var it names is the identity | **Partly shipped.** An unknown tag in source is now an ERROR, as in Clojure -- it used to invent a tagged literal for anything, so a typo read as a good value. `deps.edn` binds tag NAME to reader VAR, per project, applying only to that project's own roots -- so two libraries wanting `#x` no longer collide and using one is opt-in. The reader REWRITES `#x form` to `(var form)` and runs nothing, carrying the form as written. `#flint/table` is built in. `reader-tag-of` and the SDK's equivalent key are not built |
| [0036](0036-workspace-capabilities.md) | Capabilities per workspace, guarded per dependency | **Roadmap.** A namespace resolver answering `{workspace, identity, reader-or-virtual}`: a grant says what a dependency may do, a guard says who may require it, and a VIRTUAL namespace -- flagged by the resolver, compiled to `flint.virtual/call`, spoken to over rpc on a port the library opens lazily -- is what a pod is underneath, with an OPTIONAL var list (the protocol's own `:list`, from a pod booted before the compile) buying compile-time checking. Blocked on the SDK having no notion of a project at all, which is also why `0035` step 3 was never done . Also retires `open` onto a general host-request primitive, and splits the compile-time GUARD (may this code call that code) from the run-time TOKEN (may this program do that thing) -- the first is answered inside the sandbox by loader-minted SENTINEL SLOTS in the image (constant time, no host round trip, and nothing forgeable because a sentinel has no written form), the second by the host once at acquisition -- never the same value, or the artifact is a bearer token |
| [0029](0029-jvm-runtime.md) | The JVM runtime | **Partly shipped.** Self-hosts byte for byte, all 155 builtins, threads, AOT to bytecode at 12x, all nine conformance cases |
| [0028](0028-drivers.md) | A driver: ports are the only way to drive a sandbox | **Partly shipped.** Rust has `Driver`/`ThreadPool`/async `call`, 200 requests coalesced into 1 dispatch; the parallel collector does not exist |
| [0024](0024-no-runtime-linking.md) | No linking at compile time; byte strings and transient ropes | **Partly shipped.** The splice and the tree shaker work with no linker; the byte strings do not exist |

## What is actually next

Items 1–6 of the user's original ordering are done. What is recorded below is
what remains, and what each thing is waiting on.

**Open, in the order the last measurement left them:**

0. **A namespace resolver both front doors produce** (`0036` step 1) — it
   answers `{workspace, identity, reader}` per namespace, where `workspace`
   carries reader tags today and capability grants and guards next. The CLI has
   source roots and reads each one's own `deps.edn`; the SDK's `compile` takes a
   FLAT map of path to source with no boundary in it at all, which is why `0035`
   step 3 was never done. With the resolver the COMPILER enforces the guard at
   require-resolution time, so neither front door can be right while the other
   is not. `0035` step 3 completed on top of it is the proof the shape works.

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

**Closed, with the result rather than the plan:**

- ~~**AOT** (`0013`)~~ — **built, measured, shelved; correct as of 2026-08-28.**
  1.07–1.25×, for +98% module and +12% cold start. The lever turned out to be
  elsewhere: extending `register-native-aliases!` per-arity made the
  **interpreter** 1.85× faster and cost nothing. `0018` later found AOT *helps a
  JIT and does nothing for an interpreter* — you trade interpreted dispatch for
  interpreted execution. The correctness bug the shelving carried was two
  faults, both the same mistake about resume points; both are fixed, and
  `test/aot.clj` holds the ten-line reproducer.
- ~~**Ropes and the Pike VM**~~ (`0011` §1–2, `0012`) — both shipped, matcher
  over a rope cursor, no Rust regex crate and no delegation to host engines.
- ~~**Cross-runtime benchmarks**~~ (`0018`) — eight engines.
- ~~**The port bug**~~ — `../HANDOFF.md` is now a post-mortem, not live state.
  Its five rules are the standard the work is held to.
- ~~**Ports belong to the host** (`0027`)~~ — shipped on all four runtimes. One
  `K_BRIDGE` and no host port at all; `open` is a request on the system port;
  handles interned per host id so a port delivered four times is one object with
  one retain and one release; the codec runs at the bridge in both directions
  and the guest has none. **It was marked "Queued — nothing exists yet" while
  half of it was already built and unreachable** (`system_port()` had zero
  callers on three runtimes), which is the understating direction the note below
  warns about, and it cost a wire-port path that was granted permission on the
  JVM and CLR and then refused by their own send.

## How to read this directory

- A **superseded** decision keeps its analysis and loses its conclusion. Nothing
  here is deleted, because the reasoning that produced a wrong answer is usually
  the reasoning somebody needs to not repeat it.
- A **roadmap** file carries a NOT BUILT banner at the top, and a **shipped**
  one says so in the same place. Either kind of disagreement between a file's
  banner and its row here is a bug in this index — including the direction that
  understates what exists, which is the one that gets something rebuilt. `0027`
  is the worked example: it read "QUEUED — nothing in this file exists yet"
  through the whole period in which half of it was built, so the tree carried
  two generations of the port model at once and the newer one had no callers.
  **Check the banner against the code when you touch a file, not when you
  finish it.**
