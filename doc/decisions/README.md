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
| [0038](0038-kin.md) | kin: write a runtime's shared logic once | **Roadmap** — a spike. Four runtimes kept as verbatim mirrors BY HAND, and the cost is measured rather than felt -- fixing the `apply` park bug meant writing the same thirty lines three times and the gate caught the port not yet done. A kin source is an ordinary `ns` with `:require`, so what a file may say is what it asked for; a VOCABULARY implements each form per target as a FUNCTION, and a TAG carries both the type each target spells it as and a dispatch table, so `(invoke ^Val x nil?)` asks the tag. Rules are code rather than data because a first version made them data and leaked -- four things were not expressible and each got special-cased in the translator, which makes it a compiler for three languages wearing a config file. THE GENERATED RUST COMPILES VERBATIM, asserted by the build. Four things turned out not to be naming and all four are expressible: Rust needs hoisted temporaries where Java and C# do not (two mutable borrows), a loop test cannot be hoisted out (rewritten as `while(true){if(!t)break;}`), mutability is inferred rather than declared, and numeric width is a per-call cast. Deliberately cannot do the divergent parts -- write barrier, wasm ABI, unsafe heap -- where pretending would give three subtly wrong things instead of three honestly separate ones. Undecided: whether the rules stay DATA (four structural transforms already, and a couple more makes it a compiler with a config file), where generated code lives, and how much is actually shared |
| [0029](0029-jvm-runtime.md) | The JVM runtime | **Partly shipped.** Self-hosts byte for byte, all 155 builtins, threads, AOT to bytecode at 12x, all nine conformance cases |
| [0028](0028-drivers.md) | A driver: ports are the only way to drive a sandbox | **Partly shipped.** Rust has `Driver`/`ThreadPool`/async `call`, 200 requests coalesced into 1 dispatch; the parallel collector does not exist |
| [0024](0024-no-runtime-linking.md) | No linking at compile time; byte strings and transient ropes | **Partly shipped.** The splice and the tree shaker work with no linker; the byte strings do not exist |

## What is actually next

Items 1–6 of the user's original ordering are done. What is recorded below is
what remains, and what each thing is waiting on.

**Open, in the order the last measurement left them:**

0a. **Port the runtimes' shared logic to kin** — `doc/goals/kin-port.md`
   is the plan and its OPEN ITEMS section is the detail, `0038` the design.
   FOUR FILES SHIP: murmur3, `Eq.category`, the CHAMP node accessors and
   `Seqs.rangeEmpty`, each generated once and emitted into Rust, Java and C#
   with conform and `bin/test` green. The 4,500-line estimate came from a
   `jvm`-against-`clr` table with Rust absent, and every blocker actually met
   has been a Rust divergence, so the remaining phases want re-ranking against
   Rust before they are trusted. Code before tests, because the tests are the
   oracle.

0d. **Two gates that could not fail** — found while porting, fixed, and worth
   keeping together because they are one mistake at three layers. Silence and
   success are indistinguishable unless something is built to tell them apart:
   `bin/check-builtins` crashed instead of checking for 210 commits and hid two
   missing builtins; `bin/test` piped it through `sed`, so a real FAIL could
   not fail the suite; and `conform` compared four runtimes that truncated
   IDENTICALLY and called it agreement, which hid a byte-wide argument count
   and a missing wide `set-local`. All fixed.

   **Still open from it:** `check-builtins` treats all 166 builtins alike. A
   COMPILER-EMITTED builtin is mandatory on every runtime -- `flint/check-tag`
   is emitted for every unproven `^int`, and no guest names it, so no feature
   flag could gate it -- while a GUEST-NAMED one is optional and should be
   declared. Splitting the two is what stops the next one going missing.

0g. **Visibility is enforced, and `^:internal` is new** — three visibilities,
   two boundaries: `^:private` (or `defn-`) is the defining NAMESPACE,
   `^:internal` the defining WORKSPACE, neither is public. Checked at the
   reference in `record-dep!`, beside `0036`'s capability guard, which sits on
   symbol RESOLUTION and so covers a call, a local binding and a collection
   literal alike.

   `^:internal` reuses the boundary `0036` already draws, and the two compose
   without interfering: internal asks WHO MAY NAME THIS, a guard asks WHAT MAY
   THIS CODE DO.

   **Still open:** the CLI emits `:workspaces` only for virtual and pod
   namespaces, so `^:internal` is enforced where workspaces are declared and
   behaves as public where none are -- which is most code today. Emitting
   source workspaces from deps is part of `0036` steps 4-6, and finishing that
   is what gives this mark its reach.

   **DECIDED as future work: a namespace-level mark.** `^:internal` or
   `^:private` on a namespace declaration or symbol marks the WHOLE namespace
   workspace-local -- an outside workspace cannot `:require` it at all, rather
   than being refused var by var.

   Worth noticing why the two spellings mean the same thing HERE when they
   differ on a var. `^:private` is the namespace boundary and `^:internal` the
   workspace boundary; applied to a namespace, "namespace-local" has nothing
   left to mean, because the namespace IS the unit being marked. The next
   boundary outward is the workspace, so both land there. That is a coherence
   worth writing down rather than an ambiguity to resolve.

   `refused-requires` is where it goes, and the work is that the check is
   capability-shaped today rather than visibility-shaped -- it knows how to
   refuse a require for what a namespace may DO, not for who may name it.

   Two things to get right when it is built. The refusal must name the
   workspace boundary it crossed, not just say "refused", or it reads as a
   missing dependency. And a var-level mark inside an internal namespace
   becomes redundant but must not become an ERROR -- a namespace that is
   internal today may be published tomorrow, and the var marks are what would
   still be true afterwards.

0h. **A rooting bug in `eq`, in all four runtimes at once** — FIXED, with a
   regression check. Comparing a row ref to a map materialises the ref with
   `refToMap`, which allocates a map and assoc's every column into it, so it
   COLLECTS part way through the comparison. The second operand was a host
   local across that call, and `0031` is that a value in a host local does not
   survive an allocation: the `isTableRef` immediately after was reading the
   address the operand used to be at.

   Measured before believed. On the wasm runtime, one miscompare in four
   thousand comparisons of EQUAL values, deterministically, every run --
   `wrong=1` with the bug, `wrong=0` with both operands rooted, A/B'd by
   reverting and rebuilding rather than by reading the diff.

   **Why nothing caught it.** `runtimes/conform/tables.cljc` diffs table
   behaviour across the runtimes, and all four held the SAME bug, so all four
   gave the same wrong answer and the diff had nothing to diverge from. That
   is precisely the blind spot `test/common/README.md` was written about, now
   demonstrated a second time. The check therefore went to `test/common`,
   where the expected answer is written down.

   **The test had to be made to fail first.** The first version passed with
   AND without the fix on the native runtime -- 4,000 comparisons over a
   5-column table never landed a collection inside `refToMap` there, though
   the same source caught it on wasm. A regression test that passes either way
   is not one. Widening the table to 24 columns makes the pressure per
   comparison large enough to be near-certain rather than lucky, and the check
   is now red without the fix and green with it on native as well.

   Found by the kin port: `coll_assoc` was next in line, Rust roots the
   scanned key across its `eq` call and the ports do not, and asking which
   side was right is what led into `eq` itself.

0i. **The CLR had not compiled since `seqs.kin` shipped, and every gate
   passed anyway** — FIXED, both the break and the reason it was invisible.

   `LS_THUNK` and `LS_SEQ` were not in the name table, so they passed through
   VERBATIM. Rust and Java both spell them `LS_THUNK`; C# spells the constant
   `LsThunk`. The generated C# named something that does not exist.

   **`kin/verify` said ok the whole time.** The driver fixture declares its own
   `const int LS_THUNK = 0` in the C# head, so the generated code compiled
   against a harness that defined the name the way the source assumed. Verify
   proves the three targets AGREE; it does not prove the output compiles where
   it lands, because the fixture -- not the runtime -- supplies the
   surroundings. `kin/seqs.drivers` now spells it as the runtime does, so the
   fixture cannot paper over this again.

   **And `bin/conform-hosts` exited 0.** A failed `dotnet build` was routed to
   `missing`, which is skippable so that somebody without dotnet can still run
   the suite -- and so it also absorbed "dotnet is right here and the C# does
   not compile". Every CLR row skipped, a whole runtime unverified, green the
   entire time. The two are now distinguished: no dotnet on PATH still skips, a
   broken build is a hard FAIL that prints the errors. With the CLR live again
   the run went from 0 CLR rows to 92.

   Two of the four runtimes were unchecked for this stretch, which is worth
   weighing against anything measured on "all four" in that window.

0j. **A second rooting bug, in the ports' map equality** — FIXED. Found when
   `0h`'s regression check passed on native and failed on the JVM, 18 wrong in
   4,000. `Maps.eq` read the second operand across `refToMap(a)` and read the
   entry VALUE across the `get` lookup; `refToMap`, `get` and `eq` all
   allocate.

   Rust's `map_eq` already roots the key and value before the lookup and says
   why in a comment. The ports never picked that up. This is the standing rule
   in practice: **a divergence between runtimes is not evidence they should
   diverge** -- here one side had simply learned something the others had not,
   and the fix is to carry it across, not to record a difference.

0k. **A MAP ENTRY is barely implemented on the ports, and is not a vector on
   any runtime** — two defects, measured, neither fixed.

   `(seq some-map)` yields map entries, so this is ordinary guest code and not
   a corner. No tables are involved; the probe uses `(first (seq {:a 1 :b 2}))`.

   | | native | JVM / CLR | Clojure |
   | --- | --- | --- | --- |
   | `count` | 2 | **throws** | 2 |
   | `conj` | `(9 :a 1)` | **throws** | `[:a 1 9]` |
   | `(get e 0)` | `:a` | **throws** | `:a` |
   | `nth` | `:a` / `1` | ok | same |
   | `sequential?` | true | **false** | true |
   | `vector?` | false | false | **true** |
   | `pr-str` | `(:a 1)` | **`#<unprintable>`** | `[:a 1]` |
   | `map-entry?`, `key`, `val`, `=` | ok | ok | same |

   **(a) The ports do not implement it.** `Builtins.java` mentions
   `TY_MAPENTRY` twice and `Builtins.cs` not at all, where `coll.rs` handles it
   in `count`, `nth`, `conj`, `seq` and `get`. The errors are at least honest
   -- "count over a map entry needs more of the data structures" -- so this is
   a known-unfinished port rather than a silent wrong answer. Native is right
   and the ports are missing; a divergence is not evidence the runtimes should
   diverge, so the fix is to carry it across.

   **(b) All four agree with each other and disagree with Clojure.** A
   `MapEntry` in Clojure IS a vector: `vector?` is true, it prints `[:a 1]`,
   and `conj` APPENDS. Flint treats it as a list on every runtime -- `conj`
   prepends, printing is parenthesised. Because all four agree, no differential
   test can see it, and `test/common` does not assert it either.

   (b) is a LANGUAGE SURFACE change -- it alters what `pr-str` emits for a form
   people read in test output -- so it wants a decision rather than a quiet
   fix, which is why this entry records it instead of changing it.

   **(a) IS DONE.** `count`, `conj` and `(get e 0)` now answer on both ports,
   mirroring `coll.rs` rather than inventing anything: a map entry counts 2,
   conses like the default arm, and indexes 0 and 1. Printing fixed itself
   with the `sequential?` repair, because the printer dispatches on it -- one
   cause behind two symptoms, which is why the `#<unprintable>` in the table
   above is gone without anything touching the printer.

   All four runtimes now give the same answer to every row of that table.

   **(b) IS DONE TOO** -- decided by the author: follow Clojure. A map entry is
   now a vector on all four. `vector?` is true, it prints `[:a 1]`, `conj`
   APPENDS (`[:a 1 9]`), and `assoc` indexes it, while `map-entry?`, `key`,
   `val` and `(into {} [e])` keep working: it is a vector AND an entry.

   Two things fell out of doing it, both worth more than the change itself.

   **`vector?` had THREE answers in the native runtime** -- the builtin, the
   match inside `flint/check-tag`, and `Rt::type_p` in `vm.rs`. Widening one
   left the others, and the probe kept saying false while the code plainly
   said true. `check-tag` now delegates to `type_p`, so the runtime has one
   table. The ports had one already and their builtin called it; the
   duplication was native's alone, which is not where I would have guessed.

   **`is_vector` was the wrong thing to widen.** Twenty callers, most of them
   the guard before `vec_count` or `vec_nth`, and a map entry does not have
   the vector layout. The guest question and the layout question share an
   answer for real vectors and part company here, so they are now two
   predicates: `is_vector_like` and `is_vector`.

   Found the same way as `0h` and `0j`: probing what a value can DO rather
   than reading what its type suggests.

0l. **The whole `pop` family was missing or wrong** — FIXED. `clojure.core`
   had `(defn pop! [t] (flint.rt/pop t))`, the PERSISTENT pop, so `pop!` on a
   transient vector silently answered `()`; there was no `pop!` builtin at all
   and `tvec_pop` was reached by nothing but its own unit test. Chasing it
   found the larger half: neither port had `popTail`, `pop` or `tpop` in
   `Vec`, and the `pop` builtin rebuilt the vector with a conj loop — O(n)
   where native unwinds the trie in O(log n). The answers matched every time,
   which is why no gate saw it: a conformance suite compares answers, and this
   was only visible in the shape of the work.

   Found while adding transient coverage to `runtimes/conform/collections.cljc`,
   which had none — the same gap that had been hiding a node-width divergence
   between native and the ports.

0m. **`seq` of a map entry allocated a vector on both ports** — FIXED — the native
   runtime makes a vecseq over the ENTRY itself and reads it directly; the
   ports copy the entry into a two-element vector first, on every entry of
   every map walked.

   Converging them onto the native shape was tried and REVERTED: it breaks one
   row of the `tables` conformance suite — `build-eq`, table equality at 600
   rows — deterministically, and does not reproduce in a standalone program or
   in any trimmed copy of the suite. Any perturbation hides it.

   **It is NOT a rooting bug, and it is not a GC bug at all.** Two hypotheses
   were tested and both are dead:

   * A stale-push detector was built for the JVM for this (see `Rt.push`), and
     it reports ZERO stale pushes on the failing run.
   * The CLR fails IDENTICALLY — `build-eq false` on both ports. Two
     independently written runtimes with different host GCs producing the same
     wrong answer is not a GC bug; it is a semantic difference in the shared
     logic.

   Instrumentation confirms the new branches are reached exactly as designed:
   over 600 rows × 3 columns, `seq` of an entry fires 1 806 times, `first` on
   the resulting vecseq 3 612 (a key and a value each), `next` 1 806. So the
   mechanism works and something ELSE observes the difference.

   What is left to find is which reader distinguishes a vecseq over a map
   entry from a vecseq over a two-element vector. Only `Seqs.first`,
   `Seqs.next` and `Seqs.seq` read a vecseq's collection in either port, and
   all three were patched — so the difference is reached through something
   that does not go through them.

   **DONE.** The convergence broke table equality when first tried, and that
   turned out to be nothing to do with map entries: three pre-existing rooting
   bugs in `Eq`, whose timing the change only perturbed. They are item 0n and
   they are fixed, so this went in unchanged.

   MEASURED at 8 400 steps on a 200-entry walk — 60 531 against 52 131, 13.9%,
   or 42 steps per entry. `Seqs.entryAsVec` is deleted on both ports, nothing
   else having wanted it. (`Builtins.mapEntryAsVec` is a different function and
   stays: it gives `conj` on a map entry vector semantics.)

   The general lesson is the one 0n carries: an optimisation that "breaks
   something unrelated" may be reporting a latent bug rather than causing one,
   and the way to tell is a tool that makes the latent one deterministic.

   The saving is one allocation per map entry seq'd — every entry of every map
   walked — and the divergence blocks porting `first` and `next` to kin.

0n. **The ports have pre-existing rooting bugs that GC stress exposes** —
   found while chasing 0m, and independent of it. Under `-Dflint.gcstress=1`
   (collect at every allocation) a two-column, EIGHT-ROW table comparison
   fails on the JVM at HEAD:

       (= T (ft/build S (range 8) row))     ; true native, false ported

   Two are FIXED here, both in `Eq.seqEq`, both the same shape — a value held
   in a host local across a call that allocates:

   * `int x = push(seq(a)), y = push(seq(b))` holds `b` across the allocation
     inside `seq(a)`. A collection there leaves `b` pointing at a moved object
     and `seq` is handed a forwarding pointer: *"seq over object type 1"*, and
     type 1 is `TY_FWD`.
   * `long nx = next(x), ny = next(y)` holds `nx` across the allocation inside
     `next(y)`, and then stores the stale `nx` with `setR`.

   The THIRD is fixed too, and it was not the value stack. Chasing "`a` is
   already a forwarding pointer when `Eq.eq` is entered" through a stack trace
   found `Eq.eq`'s TABLE branch:

       int ri = rt.push(Table.tableRef(rt, rt.r(ai), i));
       boolean same = eq(rt, rt.r(ri), Table.tableRef(rt, rt.r(bi), i));

   The a-side ref is read into the argument slot BEFORE the b-side `tableRef`
   allocates. The comment directly above that loop reads *"BOTH SIDES ROOTED.
   `tableRef` allocates"* — it roots the two TABLES and then does the very
   thing it warns about, one level down, with the two REFS.

   ALL THREE FIXED, and every conformance program now passes under stress.

   The tools that found them are new and stay: the stale-push detector on
   `Rt.push` (`-Dflint.stale=1`) and the GC stress switch
   (`-Dflint.gcstress=1`). Neither existed on the ports; the native runtime
   has had both since `0031`. The detector did NOT catch any of the three — it
   fires at `push`, and a value that is never pushed never reaches it. Stress
   found all three, so `conform-hosts` runs every suite under it now.

0o. **`seq` over a ROPE was silently wrong on the NATIVE runtime** — FIXED,
   and found by chasing a divergence that turned out to be pointing the other
   way.

   A string seq holds an index. Native's is a BYTE OFFSET; both ports' is a
   CODE POINT index. Same answers on a flat string, so nothing noticed — and
   the assumption while porting was that native's byte offset was the better
   shape to converge on, because a code-point index has to be resolved.

   It is the broken one. `is_string` is true for `TY_ROPE`, so `seq` builds a
   strseq over a rope, and `char_width_at`/`char_at_byte` read
   `STR_DATA + byte` — which for a rope is the header, not text. Measured on
   an 8 000-character rope, native only:

       (first (seq r))        " "     where both ports say "x"
       (count (vec (seq r)))  5 386   where both ports say 8 000

   Silent, not an error. Fixed by giving both helpers a rope arm through
   `rope_bytes_at`, which existed and nothing called from here.

   `runtimes/conform/strings.cljc` has `:rope-seq` and `:rope-seq-utf8` now.
   The sizes matter: long enough to BE a rope, and the mixed case crosses from
   an ASCII leaf into a non-ASCII one, which is where a byte offset and a
   code-point index diverge.

   The index shape is CONVERGED too, onto the ports' code point. Both designs
   were made to work first, so the choice was between two working ones, and
   performance did not decide it: an 8 000-character rope walk measured
   1 381 506 steps on native against 1 381 501 on the JVM, and after the
   convergence native measured 1 381 506 again. Identical, three ways.

   What decided it is the bug above. A byte offset has to reach past the
   string abstraction to raw bytes, and that is exactly how the rope hole
   appeared; a code-point index goes through `cp_bytes_at`, which knows all
   three tiers in one place. The design that is harder to get wrong wins when
   nothing else separates them.

   `char_width_at` and `char_at_byte` are deleted -- 49 lines, and strseq was
   their only caller.

0f. **Nine defects in the JVM and CLR runtimes, found by ranking the port
   against Rust.** None is a port problem; all were invisible to the old
   `jvm`-against-`clr` similarity table because BOTH ports share them. Two
   kinds, and the first kind matters more than its size suggests.

   **Gas and allocation parity — a budget that fits on one runtime must fit
   on the others** (`Rt.java:214` already says exactly this, with numbers,
   about a defect that was fixed for `Maps`, `Vec` and `Sets`):

   * `Seqs.emptyList` ALLOCATES on every call where Rust returns a
     singleton. `initSingletons` writes `SING_EMPTY_LIST` and nothing ever
     reads it -- `Seqs` was missed when the others were fixed.
   * `Maps.eq` and `Maps.hash` charge NO GAS on either port; Rust charges per
     entry. `(= m1 m2)` is unbounded on two runtimes.
   * `Pike.compile`, `run` and `findAll` charge no gas; Rust charges
     `charge_work(n)` and `charge_bytes(n)`. A regex is exactly where an
     unbounded budget matters.
   * `Seqs.entryAsVec` materialises a two-element vector Rust never builds --
     two allocations per `(first {:a 1})`.

   **Performance and correctness drift:**

   * `Str.cpBytesAt` takes the FLATTEN path on an ASCII rope, which is the
     exact regression `coll.rs` says it fixed, citing 3.05x on
     `test/scaling.clj`. Live on both ports.
   * The vector header is FIVE slots on the ports and SIX in Rust -- the
     hash cache. `Eq.java` rewalks every element on every `hash`. `Vec.java`'s
     own comment says an object of a different length between runtimes is
     exactly the divergence a snapshot carries silently.
   * `pop` on a vector is O(n) on both ports: `n-1` `conj` calls, each
     allocating. Rust's `vec_pop` allocates about `2*depth + 2`.
   * `Seqs.count` walks the seq while ignoring the `C_COUNT` cache it
     maintains; Rust reads it. O(n) against O(1).
   * `Bytes.at` ignores the `BB_FLAT` cache Rust follows.
   * `Pike.codePoints` flattens a rope, breaking `0012`'s documented
     `stat_flattens == 0` invariant, which Rust upholds by walking leaves.
   * `Eq.java` has two DEAD branches -- the table-ref arm and the `TY_STR`
     arm are both unreachable because `category` dispatched first. Rust
     reaches both, because it tests refs BEFORE the category dispatch.
   * `Snap.java`'s header claims it "writes the same fields in the same order
     at the same widths as the Rust". It writes a literal `0` for the intern
     tables. The claim is false, and a snapshot is where the header itself
     says a divergence would surface as silent nonsense.

0e. **A debug feature for `gc-stats` and `snap`** — `flint.rt/gc-stats` is
   guest-reachable and probably should not be, and snapshotting is the same
   shape. `default-features` is `#{:flint :flint/check}` and `:flint/check` is
   the precedent: on by default, removed by `:optimize [perf]`, and dropped by
   the READER so it costs no image bytes. This one would be off by default.
   Wants a decision because gating a guest-named var is a change to the
   language surface: a program naming it stops compiling. `break` does not
   exist yet.

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
