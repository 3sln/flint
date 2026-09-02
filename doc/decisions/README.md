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
| [0035](0035-reader-tags.md) | A reader tag is a name; the var it names is the identity | **Partly shipped.** An unknown tag in source is now an ERROR, as in Clojure -- it used to invent a tagged literal for anything, so a typo read as a good value. `deps.edn` binds tag NAME to reader VAR, per project, applying only to that project's own roots -- so two libraries wanting `#x` no longer collide and using one is opt-in. The reader REWRITES `#x form` to `(var form)` and runs nothing, carrying the form as written. `#flint/table` is built in. The SDK carries the same map, as `workspaces` on `compile` or a resolver answering `{source, workspace, tags}` -- it rides `0036`'s namespace resolver, which is what step 3 was blocked on, and two workspaces binding the same tag to different readers is asserted in the selftest. `reader-tag-of` is not built |
| [0036](0036-workspace-capabilities.md) | Capabilities per workspace, guarded per dependency | **Partly shipped.** Resolver, grants, workspace and var guards, and the request primitive are in; virtual namespaces, pods, load-time binding and the host-facing half are not. A namespace resolver answering `{workspace, identity, reader-or-virtual}`: a grant says what a dependency may do, a guard says who may require it, and a VIRTUAL namespace -- flagged by the resolver, compiled to `flint.virtual/call`, spoken to over rpc on a port the library opens lazily -- is what a pod is underneath, with an OPTIONAL var list (the protocol's own `:list`, from a pod booted before the compile) buying compile-time checking. Blocked on the SDK having no notion of a project at all, which is also why `0035` step 3 was never done . Also retires `open` onto a general host-request primitive, and splits the compile-time GUARD (may this code call that code) from the run-time TOKEN (may this program do that thing) -- the first is answered by guarding the REFERENCE rather than the invocation, which is compile-time and free and makes closures ordinary values again; the second by the host once at acquisition. Load-time slot binding covers the one case compile time cannot -- an image loaded at run time whose call sites no trusted compiler checked |
| [0037](0037-system-namespaces-and-deps.md) | System access and dependencies are virtual namespaces the CLI serves | **Partly shipped** — virtual namespaces, `flint.sys.fs`/`env`/`slurp`, `flint.deps.npm`/`mvn`/`git`, the `.cljc` plan and `flint deps add` are in; pods, the rest of the `deps` surface and deleting the babashka path are not. `flint.sys.*` (`slurp`, `fs`, `net`, `env`, `proc`) and `flint.deps.*` (`npm`, `mvn`, `git`, `pod`) become VIRTUAL namespaces served over RPC by the Rust binary, so a reader can tell from the name that the thing on the other end is the CLI's decision and not the language's -- `lib/flint/fs.cljc` looks like a language namespace and is not one, there is no networking at all, and the shipped 2.6 MB binary has no deps surface whatever (it is all babashka shelling out to git/curl/tar/unzip). `slurp` is split from `fs` BY AUTHORITY: bytes-at-a-name covers `file://` and `https://` together under one coarse guard with the host enforcing scheme and host allowlists, while `:fs` guards structure and mutation. Git becomes a first-class package manager -- `:git/version` semver over tags, `:git/sha` as prefix INTEGRITY rather than identity, canonical `:git/tag` unchanged, and transitives resolved to a fixpoint. npm needs no `package.json`; everything is pinned by default and transitives pin into `:flint/overrides` rather than a second lockfile. A dependency entry may carry a `:flint/capabilities-grant`, which is a DELEGATION -- you cannot lend what you do not hold, a dependency's guard must be granted or the build is refused, and `flint deps add` writes the grant it found after asking. Costs the binary its size claim: `ureq`, `gix`, `zip`, `tar`, `semver`, `sha2`, measured in the commit that adds them. Version arithmetic is duplicated on purpose and the `.cljc` plan is authoritative -- Rust matches, flint decides. Blocked on `0036` steps 4-6: every namespace here is virtual |
| [0038](0038-splint.md) | splint: write a runtime's shared logic once | **Roadmap** — a spike. Four runtimes kept as verbatim mirrors BY HAND, and the cost is measured rather than felt -- fixing the `apply` park bug meant writing the same thirty lines three times and the gate caught the port not yet done. A splint source is an ordinary `ns` with `:require`, so what a file may say is what it asked for; a VOCABULARY implements each form per target as a FUNCTION, and a TAG carries both the type each target spells it as and a dispatch table, so `(invoke ^Val x nil?)` asks the tag. Rules are code rather than data because a first version made them data and leaked -- four things were not expressible and each got special-cased in the translator, which makes it a compiler for three languages wearing a config file. THE GENERATED RUST COMPILES VERBATIM, asserted by the build. Four things turned out not to be naming and all four are expressible: Rust needs hoisted temporaries where Java and C# do not (two mutable borrows), a loop test cannot be hoisted out (rewritten as `while(true){if(!t)break;}`), mutability is inferred rather than declared, and numeric width is a per-call cast. Deliberately cannot do the divergent parts -- write barrier, wasm ABI, unsafe heap -- where pretending would give three subtly wrong things instead of three honestly separate ones. Undecided: whether the rules stay DATA (four structural transforms already, and a couple more makes it a compiler with a config file), where generated code lives, and how much is actually shared |
| [0029](0029-jvm-runtime.md) | The JVM runtime | **Partly shipped.** Self-hosts byte for byte, all 155 builtins, threads, AOT to bytecode at 12x, all nine conformance cases |
| [0028](0028-drivers.md) | A driver: ports are the only way to drive a sandbox | **Partly shipped.** Rust has `Driver`/`ThreadPool`/async `call`, 200 requests coalesced into 1 dispatch; the parallel collector does not exist |
| [0024](0024-no-runtime-linking.md) | No linking at compile time; byte strings and transient ropes | **Partly shipped.** The splice and the tree shaker work with no linker; the byte strings do not exist |

## What is actually next

Items 1–6 of the user's original ordering are done. What is recorded below is
what remains, and what each thing is waiting on.

**Open, in the order the last measurement left them:**

0. **Pods** (`0036` step 6, `0037` step 9) — one implementation of the virtual
   namespace interface, speaking babashka's pod protocol, answering `:list` from
   `describe`. The interface it plugs into is built and proven by five served
   namespaces; what is left is the protocol itself and the decision about
   booting one at build time (a live process in a build, so opt-in and cached).

0b. **The rest of `flint deps`** — `bump`, `pin`, `tree`, `why`. `add` is built
   and the others follow from it; `pins` in `flint.deps.resolve` already
   produces what `pin` writes.

0c. **Delete the old dependency path** (`0037` step 10) — `bin/flint`'s
   `git!`/`npm!`/`mvn!` shell-outs and the parts of `lib/flint/deps.cljc` they
   served. Left standing on purpose while the new path is young: two paths for
   one job is exactly what this repository keeps finding bugs in, so this is a
   debt with a name rather than an oversight. — the
   resolver flags a namespace as virtual, the compiler emits `flint.virtual/call`
   and `flint.virtual/get` into it rather than a var reference, and a library
   does the namespace-to-port resolution, waiting and memoisation. The optional
   var list (the protocol's own `:list`, from a pod booted before the compile)
   buys compile-time checking and is separable. This is what makes a babashka
   pod expressible.

   Its blocker is gone: the namespace resolver both front doors produce is
   built, and `0035` step 3 landed on it as the proof — two workspaces binding
   the same reader tag to different readers, each seeing its own. Grants,
   workspace guards, var guards and the request primitive came with it.

0b. **Retire `open` onto `request`** (`0036` step 7, second half) — the general
   primitive exists and `flint.port/open` still has its own event and its own
   path. Collapsing them is not free: a port is granted by id and never encoded,
   so what would go is the duplication in the two builtins and not the two
   events. Worth doing when something else touches that code.

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
