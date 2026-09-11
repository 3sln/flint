# flint roadmap

This is a single organised view over `DECISIONS.md` (39 numbered decision
records), `doc/goals/README.md` (the live investigation log), `doc/ports.md`,
`doc/jank.md`, `doc/unit-format.md`, `doc/kin-specialisation-cases.md`, the root
`BRIEF.md`/`PLAN.md`/`CHANGELOG.md`, and `git log`. It does not replace any of
those — it points at them. **Every decision number below (`strings-and-matching`, `tables`, …)
is a live link to `DECISIONS.md#<slug>`; read the source for the reasoning.**

Status values used throughout:

- **done** — shipped, measured or tested, and (where checked below) confirmed
  against the current tree, not just the doc's own banner.
- **in progress** — real code exists and passes gates, but the decision's own
  "what must be true" list has open items.
- **decided, not started** — the design is settled and recorded; no code yet.
- **open question** — genuinely undecided, sometimes with a stated lean.
- **abandoned / superseded** — started, then explicitly dropped or replaced.
- **unknown** — I could not confirm either way from the doc or the tree; flagged
  rather than guessed.

A general honesty note up front: several decision-doc banners are **stale
relative to the tree** — they undersell what actually shipped, usually because
a *later* decision or a burst of `kin`-port commits built the thing without
anyone circling back to update the earlier banner. Three cases are called out
explicitly where found (`tables`, `workspace-capabilities`/`system-namespaces-and-deps`, `kin`); there may be others.
the decision index (now folded into `DECISIONS.md`) says exactly this is the recurring failure mode
("`ports-are-the-hosts` is the worked example... it read 'QUEUED' through the whole period in
which half of it was built") and keeps its own status table for this reason —
treat that file as the up-to-date index and this roadmap as the organised,
by-area view on top of it.

---

## 1. Language semantics

| item | status | decision |
|---|---|---|
| Stack-machine interpreter, dispatch measured at 6.2 ns/instr | done | [`dispatch`](DECISIONS.md#dispatch) |
| Ropes for strings (3 tiers: inline/flat/rope, balanced B-tree) | done | [`strings-and-matching`](DECISIONS.md#strings-and-matching) §1–2 |
| Pike VM / Thompson-NFA regex over a rope cursor, no backtracking | done | [`matching-over-ropes`](DECISIONS.md#matching-over-ropes) |
| Byte strings + their transient (rope treatment for bytes) | done | [`no-runtime-linking`](DECISIONS.md#no-runtime-linking) |
| Transient rope for **text** (byte-string transient's sibling) | decided, not started | [`no-runtime-linking`](DECISIONS.md#no-runtime-linking) step 6 |
| Protocols: closed-set dispatch on `kind`, metadata dispatch as the main road for everything else | done | [`threads-and-ports`](DECISIONS.md#threads-and-ports) §6 |
| Checks (`#?(:flint/check ...)`), zero-cost when optimised for perf; `flint test` | done | [`checks`](DECISIONS.md#checks) |
| Tagged literals as a real value type (`TY_TAGGED`), not a two-key map | done | [`tagged-literals`](DECISIONS.md#tagged-literals) |
| Reader tags bound per-project (`deps.edn` → var), not Clojure's global registry | in progress | [`reader-tags`](DECISIONS.md#reader-tags) — `reader-tag-of` (build-time printing name) not built |
| Tables: columnar vector-of-maps, closed schema | in progress | [`tables`](DECISIONS.md#tables) — value type, refs, `assoc`/`migrate`/transient/`flint.table`/codec tag all shipped per the doc's own step list; **only the JVM/CLR port is left**. *(Banner says "steps 1–6"; the doc's own later step entries mark 7, 8, 9 done — the banner is stale, not the content.)* |
| `Equiv`/`Hash`/`EquivHash` — a value bringing its own equality (for extern/host types) | decided, not started | [`doc/goals/equiv-hash.md`](doc/goals/equiv-hash.md) — first step is a benchmark proving the unextended path is unchanged, not code (a wasm baseline is taken: 73ns–420µs across modes, zero allocations; the two ports have none yet). **The one open decision blocking a start:** does `TY_TAGGED` gain a metadata slot? It can't hold metadata today and is the natural carrier for a domain value in a language with no `deftype`, so without it the mechanism has little to attach to — the doc names this explicitly as "the first thing to decide." |
| Extern refs — host references crossing into a sandbox with no heap to hold them | open question → mostly decided | [`doc/goals/extern-refs.md`](doc/goals/extern-refs.md) — the three open questions it posed (the `map` cliff, determinism, the capability hole, protocol extension) are each now decided in `doc/goals/README.md` §3; what remains is implementation. One idea was **explicitly rejected**: morphing a collection node to carry an externs chunk directly (5 named reasons — CHAMP layout, canonicality, transient in-place writes among them). Notable because `equiv-hash.md`'s node-bit design above uses a structurally similar per-node trick *successfully*, for a different purpose (a 1-bit union flag vs. a data-carrying chunk) — the two files cross-reference each other rather than disagreeing. |
| Divergences from Clojure fixed to match (`pop nil`, `subvec` bounds, `peek` on non-stacks, `nth` on maps, `contains?` on lists, map-entry-is-a-vector) | done | `doc/goals/README.md` — several rounds, all pinned in `runtimes/conform/seqshapes.cljc` etc. |
| String hash unified to one byte-walk across all three tiers (was silently 3 different bases) | done | `doc/goals/README.md` §1 |
| `clojure.zip`, `clojure.data`, `clojure.datafy` shipped | done | the decision index (now folded into `DECISIONS.md`) item `0p` |
| Regex delegation to host engines (JS/JVM/CLR `RegExp`/`Pattern`/`Regex`) | abandoned | [`strings-and-matching`](DECISIONS.md#strings-and-matching) §5, explicitly superseded by [`matching-over-ropes`](DECISIONS.md#matching-over-ropes) — stock engines can't consume a rope |
| `letfn*` (mutual recursion in `let`) | decided, not started | [`doc/jank.md`](doc/jank.md) — the one clean gap in the jank-suite comparison, 14 tests, one missing special form |
| `recur` refused at top level (currently loops forever instead) | open question, deliberately deferred | [`doc/jank.md`](doc/jank.md) — "one test is a poor reason to add a special case" |
| jank suite, the rest of the 104 failures: 38 are flint's analyser being *more permissive* than jank's (accepts a program jank rejects — duplicate `case` keys, two variadic arities on one `fn`, etc.), 12 fail with no message, 7 are C++ interop tests that leaked into language-test directories and were counted anyway rather than quietly excluded | open question, unranked | [`doc/jank.md`](doc/jank.md) — the 38 are explicitly not "fixed": "a compiler that refuses more is better, but 'refuses more' is a long tail of individually small rules," so each is reported rather than patched. The suite's 514 C++-interop tests (`test/jank/cpp`) are excluded outright as out of scope by design — flint has no host classes at all, so there is nothing to be "close to" there. |
| Numeric tower (bigint/ratio), sorted collections, transducers, `eval` at runtime, records/types (`deftype`/`defrecord`/`reify`), hierarchies (`derive`/`isa?`), var objects/`with-redefs`, host threads/agents/refs, metadata on fns/numbers/short strings | decided, not started (by design) | `README.md` §Limits, `CHANGELOG.md` Known Limits, `doc/coverage.md` |
| Regex engine speed: 12× slower than babashka's, 275× slower than cherry-compiled JS on the construe workload | in progress, named fix not started | `README.md` §Limits — the fix (`modularity`) is a Rust regex unit, gated by the existing tree-shaking mechanism so a program without regex literals pays nothing for it; two other options were tried first per the brief, this is next |
| Non-ASCII string indexing O(n) (ASCII strings get an O(1) fast path via a stored flag; any multi-byte character walks) | decided, not started | `README.md` §Limits — before the ASCII flag existed, splitting a string was quadratic and the word-frequency benchmark took 762ms instead of 62ms |
| Function values as map keys degrade to linear probing (no stored identity hash under a moving collector; flint returns a correct-but-constant per-type hash instead) | decided, not started | `README.md` §Limits |
| `extend-method` moved from `clojure.core` to `flint.protocols`, so `clojure.core` publishes exactly what Clojure does | done, landed 2026-09-11 after a same-day revert | `doc/goals/README.md` — root cause was load order, not emission: `defprotocol`'s expansion calls a name the using namespace never `:require`s, so it only ever worked because `clojure.core` is pinned first. A first attempt shipped without reordering that pin list and broke a two-file build; it was reverted same-day as an unreviewed API change (direct callers now need the require), then redone once accepted. Worth citing as a compressed, contained example of the "started, reverted, redone" pattern the user asked to see surfaced. |
| Compiler-emitted references (`flint.regex/pattern` for any regex literal, `flint.protocols/extend-method` for `defprotocol`, `flint.virtual/call` for a virtual-namespace reference) are not `:require` edges, and previously failed silently or with an unhelpful `nil, N args` error | done (`implied-requires`) | `doc/goals/README.md` — fixed generally, by walking the read forms before analysis and deriving the missing edge, rather than hand-listing namespaces in `core-first` (which doesn't scale: the next emitted reference reopens it). A companion bug — a virtual-namespace call at load time silently discarding its result instead of erroring — was found and fixed alongside it. |

## 2. Runtime & GC

| item | status | decision |
|---|---|---|
| Generational moving collector, value stack IS the root set | done | [`dispatch`](DECISIONS.md#dispatch), README |
| Hard resource limits: deterministic gas, natives charge for real work, catchable memory cap | done | [`resource-limits`](DECISIONS.md#resource-limits) |
| Two builds: stripped production VM vs. everything-optional diagnostics build | done | [`two-builds`](DECISIONS.md#two-builds) |
| VM snapshots — verbatim memcpy (post-mortem) **and** a live-set traversal (shelving a running sandbox) | done | [`snapshots`](DECISIONS.md#snapshots) |
| AOT: compile contiguous non-parking regions to real wasm functions | **shipped as an opt-in cargo feature, off by default** — see note | [`emit-wasm-instead-of-dispatch`](DECISIONS.md#emit-wasm-instead-of-dispatch) |
| A `Vec<Value>` is not a root — GC correctness bug (self-hosting trap) | done (fixed) | [`a-vec-of-values-is-not-a-root`](DECISIONS.md#a-vec-of-values-is-not-a-root) |
| A debug runner: DAP, nREPL, `(break)` | decided, not started | [`debug-runner`](DECISIONS.md#debug-runner) — explicitly "not next"; cheap because a breakpoint is just a park |
| A profiler: named blocks, CPU told apart from waiting, deterministic instruction counts | decided, not started | [`profiler`](DECISIONS.md#profiler) |
| A thread pool over one sandbox (real parallel execution) | decided, not started | [`thread-pool`](DECISIONS.md#thread-pool) — Model B (heap-per-worker, ports between) recommended over Model A (shared heap, collector rewrite) |
| A driver: ports as the only way to drive a sandbox, so a pool can schedule it | in progress | [`drivers`](DECISIONS.md#drivers) — Rust SDK shape (`Driver`, `ThreadPool`, async `call`, coalesced dispatch) done; **two executors now share one heap through collections**, but intern tables / remembered set / `globals` are not yet protected, so K>1 is correct but not faster |

**Note on AOT (`emit-wasm-instead-of-dispatch`):** this is the one item where "shelved" (the doc's own
banner language) undersells what exists. It was built, measured, and then the
user explicitly parked it ("drop aot for now, focus on strings and regex") —
that's a real decision, not abandonment. It is off by default, behind a cargo
feature, and the correctness bug that prompted shelving it is now fixed. The
measured win is 8–25%, smaller than the 88–91% originally estimated, because
the emitter removes dispatch but keeps the interpreter's boxed representation
and calling convention. `emit-wasm-instead-of-dispatch` names the actual ceiling: **type specialisation
and inlining**, not the emission shape, are the next lever, and one of those
(inlining `+`/`<`/`inc` per-arity) already made the *interpreter* 1.85× faster
with no AOT involved. Native-target AOT (LLVM) and the JVM/CLR AOT emitters
are separate, further-along efforts — see §4.

## 3. Concurrency & ports

| item | status | decision |
|---|---|---|
| Green threads, ports, protocols, dynamic vars (per green thread) | done | [`threads-and-ports`](DECISIONS.md#threads-and-ports) — `../HANDOFF.md` is the post-mortem of the bug that shipped alongside this |
| Host ABI: continuation tokens, one event queue, two lifetimes | done, then largely superseded | [`host-abi`](DECISIONS.md#host-abi) — mechanically replaced by `structured-ports`/`ports-are-the-hosts`'s system-port model; the *concepts* (token → `tx`, event queue → system port) carried forward |
| Ports belong to the **host**, not a sandbox (enables inter-sandbox messaging) | done, all four runtimes, with two named gaps | [`ports-are-the-hosts`](DECISIONS.md#ports-are-the-hosts) — weak-table fixup through a nursery copy, and codec back-references, are not built. *(This file's banner said "QUEUED — nothing exists" for a long period after half of it shipped and went unreachable — the worked example the decision index (now folded into `DECISIONS.md`) uses for "check the banner against the code".)* |
| A wire codec + structured ports (one codec for everything crossing the boundary, `Sandbox`/`Image` nouns, ports can carry ports) | **doc says "NOT BUILT — a proposal"; substantial parts have actually shipped** — see note | [`structured-ports`](DECISIONS.md#structured-ports) |
| A bridge owns its messages (arena-per-participant, six-verb port contract, survives SIGKILL of a peer) | decided, not started | [`bridges`](DECISIONS.md#bridges) — distinct from, and more ambitious than, what `ports-are-the-hosts` actually shipped; overlaps the same two open items `ports-are-the-hosts` lists |
| Virtual namespaces (a namespace served over RPC instead of linked in) | done | [`workspace-capabilities`](DECISIONS.md#workspace-capabilities) step 4, actually shipped via `system-namespaces-and-deps`'s work — see capabilities section for the banner mismatch |
| A park across a Rust frame crashes (`apply`, lazy-seq force) — general park-resumption bug | done (fixed) | the decision index (now folded into `DECISIONS.md`) — found while building `system-namespaces-and-deps`; root cause was "a rewind may only land before the call-back-in"; fixed with a per-thread re-entry stack |

**Note on `structured-ports`:** the file's own banner says nothing in it exists. That's no
longer accurate. `ports-are-the-hosts` (built) explicitly says "the wire codec already exists
for exactly this shape" and reuses `structured-ports`'s tag vocabulary (`K_PORT`,
`K_SENTINEL`); `tables` step 9 and `bridges`/`tagged-literals` describe and ship `K_TAGGED`,
`K_TABLE`, and per-format codecs (`:json`, `:json-strict`, `:edn`, `:cbor`)
that are exactly `structured-ports`'s proposal. What is genuinely still open from `structured-ports`:
the **entry-map breaking change** (`(defn main [{:keys [args capabilities]}])`
as the CLI convention) and the full `Sandbox`/`Image` SDK vocabulary as a
*named, stable* surface. the decision index (now folded into `DECISIONS.md`)'s own priority list still
carries this as item **1**, ahead of shards, native AOT, and the rest of the
CLI — worth re-reading against the actual tree before trusting either the
"not built" banner or this note.

## 4. The four-runtime port story

flint targets wasm (browser + server), a native build (LLVM, no wasm engine),
the JVM, and the CLR. All four are meant to be behaviourally identical, checked
by a conformance harness (`bin/conform-hosts`) that diffs transcripts byte for
byte.

| item | status | decision |
|---|---|---|
| wasm/native reference runtime | done | README, throughout |
| Native (LLVM) target: `flint_rt::native::Program`, no wasm engine anywhere, single binary CLI | done | [`other-hosts`](DECISIONS.md#other-hosts) |
| **AOT for the native target** | decided, not started | the decision index (now folded into `DECISIONS.md`) priority item 4 — "the native interpreter gives up most of the benefit without it" |
| JVM runtime (`runtimes/jvm`, 132 files): all 46 opcodes, all 155 builtins, self-hosts byte-for-byte, AOT to real bytecode (12×), parallel executors on one heap | in progress | [`jvm-runtime`](DECISIONS.md#jvm-runtime) — collector is ported verbatim from Rust (`gc.rs`), not "leaning on the host's" as first planned; **nine defects found late by ranking against Rust** (gas/alloc parity gaps in `Maps.eq`/`Pike`/`Seqs`, perf drift, two dead branches, a false claim in a snapshot header) — the decision index (now folded into `DECISIONS.md`) item `0f`, not yet all fixed |
| CLR runtime (`runtimes/clr`, 119 files): same shape as JVM, passed conformance on first run | in progress | [`clr-runtime`](DECISIONS.md#clr-runtime) — same `0f` defect list applies (both ports share the bugs; a runtime-vs-runtime diff can't see a bug both sides have) |
| Cross-runtime benchmarks across 8 wasm engines (node, deno, bun, workerd, wasmtime, SpiderMonkey, wasm3, Chicory) | done | [`cross-runtime-benchmarks`](DECISIONS.md#cross-runtime-benchmarks) — decided the JVM tier question (Chicory at 39–500× V8 rules out an embedded-wasm SDK for the JVM) |
| construe integration bar: real parser candidate runs and agrees, flint compiles **inside** a deployed Worker, gas comparable across candidates, library surface, memory bounded | in progress / live milestone | [`construe-integration-bar`](DECISIONS.md#construe-integration-bar) — both compile-in-Worker halves demonstrated; regex/string bottleneck resolved (56× → 11.6× babashka via ropes + Pike VM) |
| No compile-time linking: one prebuilt runtime per target, compiler embeds it as data, `flint compile wasm`/`wasm-aot` splices | in progress | [`no-runtime-linking`](DECISIONS.md#no-runtime-linking) — splice + tree-shaker (57–79% of `wasm-ld`'s result, no linker) done; byte strings done (the blocker); JVM/CLR as further embedded targets not started |
| jank dialect-suite comparison (language conformance against a different Clojure-on-LLVM implementation) | done (measurement) | [`doc/jank.md`](doc/jank.md) — 64–65% pass; the native/port `pass-*` sets are **test-for-test identical**, which is the strongest evidence the ports are faithful mirrors |
| kin: shared runtime logic written once, generated into Rust/Java/C# | in progress, heavily — see §5 | [`kin`](DECISIONS.md#kin) |

**Another stale note, this one inside a single file rather than between two.**
the decision index (now folded into `DECISIONS.md`)'s own "What is actually next" numbered list (item 5,
below its dated `0a`–`0v` entries) reads "The JVM, tier 2 (`other-hosts`) — the route
is decided and not built... `cross-runtime-benchmarks` is what settled it." That directly
contradicts the status table at the **top of the same file**, which already
says (row for `other-hosts`) "JVM and CLR shipped — see `jvm-runtime`, `clr-runtime`" and (row for
`jvm-runtime`) "Partly shipped... self-hosts byte for byte." The numbered list reads
as an older planning list nobody pruned once the JVM/CLR ports actually
landed. The table above it — the one `bin/check-decisions` gates — is
correct; the "native AOT" and other numbered items may be similarly worth
re-checking against the table before trusting the list's ordering as current
priority.

## 5. kin — the shared-logic code generator

`kin`'s own banner says "NOT BUILT — a spike... nothing in the tree uses it
yet." **That is now the most out-of-date banner in the whole decision set.**
`kin/` is a real, separate tool (89 `.kin` sources) with its own driver
scripts, and `git log` shows dozens of commits shipping real generated logic
into all three non-wasm-only runtimes: murmur3, `Eq.category`, CHAMP node
accessors, `Seqs.rangeEmpty`, the whole Maps/Vec/Table/Bytes/Str/Seqs/Interns
families, the transient dispatchers (`conj!`/`assoc!`/`dissoc!`), tagged
values, opaque values, the closed `kind` set, and more. This is **the single
largest piece of active work in the repository right now** and the decision
doc describing it has not been updated to say so.

| item | status |
|---|---|
| The spike itself: vocabulary-as-functions, tags-as-values, anchors, position-pushed-down | done, and validated — generated Rust "compiles verbatim", generated Java/C# match hand-written code modulo whitespace |
| Real ported opcodes (`TYPE_P`, `LIST`, `apply`'s spread loop) | done — both were found to be *incidental* divergences (not forced by the target language), and kin's generated code is now the argument for converging two of the three hand-written runtimes onto it |
| Opcode coverage census before porting (36 of 45 opcodes reachable; 7 dead, deleted rather than ported) | done |
| Maps, Sets, CHAMP structural ops, Vec (read+write+transient), Table, Str (rope half), Bytes, Interns, Hash, Codec, Seqs, Eq | in progress, most phases shipped per `git log` (`kin-port.md` tracks phase-by-phase); ordering was **re-derived against Rust** after the original 4,500-line estimate came from a JVM-vs-CLR table with Rust absent — nearly every blocker actually hit has been a Rust-specific divergence |
| `Pike` (the regex simulator core) | **decided not to port** | `matching-over-ropes` itself says "per host, native — the simulator"; the NFA *compiler* is the shared half and already is |
| `coll.rs`'s `Value → Value`-shaped functions (str-join, str-index-of, compare) | **decided not to port**, three ways, deliberately — each runtime uses what its host actually has (rope vs. `StringBuilder` vs. native `String`); unifying would be a performance regression wearing the clothes of deduplication |
| `conj`'s dispatch (~70 lines, triplicated, genuinely the same algorithm drifted) | identified as the next real candidate, not yet ported |
| A kin macro/specialisation facility (parameterise over accessors+constants, override/extend a step, differ in result, differ in iteration or arity) | open question, with an evidence file rather than a design | [`doc/kin-specialisation-cases.md`](doc/kin-specialisation-cases.md) — five concrete duplication cases gathered from real generated code; explicitly "input for designing that system, not a request for a particular design" |
| Whether `:require` should resolve kin vocabularies the way flint resolves namespaces (`workspace-capabilities`'s resolver) | open question | `kin` "What is undecided" |
| Whether kin should be `flint` or its own tool | open question, leaning "its own thing" | `kin` |
| `codec.kin`/`reader.kin` — the wire codec's primitive readers/writers | **abandoned, generated but refused** | `doc/goals/kin-port.md` — generated, and verified byte-identical across all three targets, then explicitly refused: measured at 3× more instructions than the hand-written version, plus two integer-overflow slice-panic safety holes the hand-written version didn't have. Proved the generator works end to end; shipped nowhere. Worth keeping precisely because it's a case where "the generator worked" and "ship it" were different questions. |
| Converging `first`'s map-entry-vecseq shape onto native's shape | **abandoned, then redone once the real cause was found** | `doc/goals/kin-port.md`, the decision index (now folded into `DECISIONS.md`) item `0m` — first attempt broke one conformance row (`build-eq`) nondeterministically and was reverted; the cause turned out to be three *unrelated* pre-existing rooting bugs in `Eq` that the change had only perturbed the timing of (item `0n`, fixed independently). Once those were fixed, the identical convergence landed clean: 13.9% fewer steps on a 200-entry walk, one allocation removed per map-entry seq'd. |
| Two GC remset-audit checks, ported from native's `check_remset` to the JVM/CLR | **abandoned** | `doc/goals/kin-port.md` — written, measured, and thrown away: they surfaced a real, still-unexplained divergence (the JVM holds old-space garbage the native runtime doesn't) but the checks themselves were unsound at every placement tried. Recorded as a live open question (why does the JVM hold that garbage?) rather than a closed one. |
| `subs` charges gas on native and charges **nothing** on either port for the same operation | open question, explained but not fixed | the decision index (now folded into `DECISIONS.md`) item `0v` — measured at roughly one gas-step-per-call worth; blocks adding a regex row to `runtimes/conform/gasmeter.cljc` until the ports' hand-written string builtins are priced the same way the generated code already is |

Several real bugs were found *by* the kin port rather than merely worked
around by it — worth keeping as evidence the effort is paying for itself, not
just relocating code: a rooting bug in `eq` present in **all four runtimes at
once** (found because a differential test can't see a bug every side shares);
a UTF-8 character silently split in half on both ports; a hash-flooding gas
hole (a colliding-key map scan billed 16 steps flat regardless of how many
keys actually collided — 105× underbilled at 16,384 colliding keys); a plain
vector walked as a seq by `=`/`hash` instead of by index (allocating a seq
cell per element); and map equality ignoring everything a CHAMP is for — all
three runtimes did a full re-descent into the second map instead of comparing
structure, so two maps sharing structure cost the same as two independent ones
(877µs either way on 20,000 entries); now `kin/mapeq.kin`, generated for all
three, and sharing structure drops to 1.15µs. All are listed with fix commits
in the decision index (now folded into `DECISIONS.md`) items `0h`–`0v`, `doc/goals/README.md`, and
`doc/goals/data-structures.md`.

**One more stale banner, in the same family as `kin`'s.** `doc/goals/kin-port.md`
carries its own status line at the top of the file — "phase 1 begun — the
codec's primitive writers are generated and verified byte-identical" — which
undersells its own body: the same file's "Where things stand" section, and the
git log, both show Hash, `Eq.category`, `Num`, the whole transient family, Vec,
Table, Str's rope half, Bytes, Interns and Seqs already shipped. Flagged here,
not fixed — it's a live working log, out of scope for this pass to edit.

## 6. Capabilities & sandboxing

| item | status | decision |
|---|---|---|
| Opaque values: identity without structure, guest-minted vs. host-minted, unforgeable by construction | done | [`opaque-values`](DECISIONS.md#opaque-values) |
| Capability = a host-minted opaque value, presented (not compared) at `open` | done | [`cli`](DECISIONS.md#cli), [`opaque-values`](DECISIONS.md#opaque-values) — **capabilities are explicitly a pattern the CLI implements, not a concept the runtime has** |
| Capabilities per workspace, grants/guards on `:require` edges | in progress | [`workspace-capabilities`](DECISIONS.md#workspace-capabilities) — resolver, grants, workspace guards, var guards, and the request primitive are built |
| Virtual namespaces (the mechanism a pod, or any served namespace, is built on) | **built — banner says otherwise** | `workspace-capabilities` step 4; its own banner lists this under "not built", but `system-namespaces-and-deps`'s banner and the `git log` (`fba2ba1 0036 step 4: virtual namespaces...`) both confirm it shipped. **Cross-reference these two files before trusting either banner alone.** |
| Pods (babashka pod protocol) as one virtual-namespace implementation | done | `system-namespaces-and-deps` — bencode over stdio, `describe`→`:list`, same compile-time checking as any namespace |
| Reference guards (`:flint/capabilities-guard` on a var, checked at the reference, compile-time only) | done | `workspace-capabilities` step 8 |
| Load-time slot binding (covers an image loaded at runtime that no trusted compiler checked) | decided, not started | `workspace-capabilities` step 9 — this is the one case compile-time-only guarding cannot cover |
| Host-facing token half (acquired-once, never the same value as a compile-time slot sentinel) | decided, not started | `workspace-capabilities` step 10 |
| `flint.sys.*` (`fs`, `env`, `slurp` done; `net`/`proc`/`clock` not) served by the CLI over RPC | in progress | [`system-namespaces-and-deps`](DECISIONS.md#system-namespaces-and-deps) |
| `flint.deps.*` (npm, mvn, git — real registries, real fetches) | in progress | `system-namespaces-and-deps` — `flint deps add` built; `bump`/`pin`/`tree`/`why` not |
| **`flint.deps.mvn` is served but UNWIRED** | partial, untracked until now | The CLI serves `versions`, `pom` and `fetch` as a working Rust service with real fetches and caching (`cli/src/deps.rs`). **No `.cljc` calls any of it** — `lib/flint/deps/resolve.cljc` dispatches npm and git only, with no maven clause. Maven resolution today runs through the deprecated babashka shell-out that `system-namespaces-and-deps` says to delete. Either wire it or drop the service; leaving a fetcher nothing calls is the worst of the three |
| **`(snap "name")` — named snapshots from inside a program** | not built, and was RECORDED as built | A per-name ring buffer keeping the latest hit with a count, compiling to nothing in a production build. Two commits (`1c7a061`, `426af38`) whose subjects read as shipping it changed only documentation — zero lines of code. Removed from `DECISIONS.md`; kept here because the idea may still be worth building |
| Capability delegation on a dependency entry (grants narrow as they descend, never widen) | done | `system-namespaces-and-deps` — "you cannot lend what you do not hold" |
| Per-workspace policy enforcement bound to the *port*, not a wrapped closure | done | `system-namespaces-and-deps` — the wrapper approach was tried and rejected (crashes through `apply`) |
| Deleting the old babashka-shells-out dependency path | decided, not started (deliberately left standing while the new path is young) | `system-namespaces-and-deps` step 10 |
| `^:internal` / `^:private` visibility, two boundaries (namespace vs. workspace) | done, var-level; namespace-level mark decided but not started | the decision index (now folded into `DECISIONS.md`) item `0g` |

**Note on the `workspace-capabilities`/`system-namespaces-and-deps` banner mismatch:** `workspace-capabilities`'s own top banner lists
"virtual namespaces, pods, load-time binding and the host-facing half" as **not
built**, in the same sentence as saying the resolver/grants/guards **are**
built. But `system-namespaces-and-deps` — which depends on virtual namespaces existing — has its own
"What is built" section listing virtual namespaces, `flint.sys.*`, and pods as
shipped, with commits to match. Read `system-namespaces-and-deps`'s banner as the current truth on
virtual namespaces and pods; `workspace-capabilities`'s banner is accurate only for load-time
binding and the host-facing token half, which really are still open.

## 7. The CLI

| item | status | decision |
|---|---|---|
| Single native binary (compiler + interpreter), no babashka/JVM/node required | done | [`cli`](DECISIONS.md#cli) |
| `run`/`compile` with `:with`/`:path`/`:fn`/`:args`/`:to`/`:optimize`/`:meta` | done | `cli` |
| `:to :llvm` (native-target artifact output) | decided, not started | `cli` |
| The remaining cross-compilation backends (`:to :jvm`, `:to :clr`) | decided, not started, blocked on nothing technical — the runtimes exist, the CLI wiring doesn't | `cli`, `other-hosts` |
| nREPL | decided, not started — overlaps `debug-runner`'s debug runner design, "these should be one implementation" | `cli` §nREPL |
| `{:args :capabilities}` entry map (vs. today's bare `:args` vector) | decided, not started — explicitly the breaking change to make exactly once, before anything is published | `cli`, `structured-ports` |

### Design: unify dependency resolution behind pods

Recorded 2026-09-11. Today there are three mechanisms and one dead service
(see the row below); this is the shape they should collapse into.

**Each `deps.*` module parses that ecosystem's OWN manifest and answers in one
standard shape.** npm reads `package.json`, Maven reads a POM, flint reads
`deps.edn` — the parsing lives where the format knowledge is, in Rust, and what
comes back out is the same structure regardless of where it came from. The
`.cljc` side stops knowing that npm has a manifest and Maven has a POM.

**The `deps.*` modules become PODS.** That makes them usable from babashka and
from flint alike, rather than being a CLI-only service. The shape already
exists and is already decided: `workspace-capabilities` describes a pod as an
ordinary dependency carrying `:pod/version` with a virtual namespace
underneath, and `flint.deps.mvn` is already served as a virtual namespace — so
this is a short step from where it is, not a new mechanism.

**Then the merge is ordinary `.cljc`.** Given native dependencies from the pod
AND the pod's package asset/file/VFS reader, the Clojure side reads `deps.edn`
and merges it with what the ecosystem declared. One walk, one merge, every
kind.

**Git has no native manifest, and does not need one.** A cloned repository can
CARRY a `package.json`, a `pom.xml` or a `deps.edn`, and after the clone any of
them can be read the same way as a registry's. Worth one correction to the
premise: npm genuinely does accept git dependencies (`git+https://…` in
`package.json`), but Maven does not resolve from git natively — JitPack and
similar are third-party services that build a repo into an artifact. That does
not weaken the design, because it does not depend on the ecosystem accepting
git; it depends only on the repository containing a manifest we can read once
it is on disk.

| **Transitive resolution, EVERY dependency kind** | git/`:local/root` resolve transitives one way, npm another, Maven not at all | THREE mechanisms where there should be one. `lib/flint/deps.cljc`'s fetch walk reads a fetched dependency's own `deps.edn` and recurses (`(recur (vec (concat (rest todo) (or (:deps sub) {}))))`) — that covers git and `:local/root`, whose deps live in a `deps.edn`. `resolve.cljc`'s `plan`/`deps-of` reads an npm MANIFEST and recurses separately. Maven has neither: a jar's deps are in a POM, and nothing parses one, though `flint.deps.mvn` serves `pom` ready to be called. **`deps-of`'s docstring also describes a caller-side git mechanism that is not what happens** — the git path works, but through the other walk entirely. Every kind needs transitive resolution and it should be ONE walk |
| ~~Transitive resolution for Maven~~ (superseded by the row above) | not built — npm has it, Maven silently does not | `deps-of` (`lib/flint/deps/resolve.cljc`) returns real transitives for `:npm` from its manifest; `:mvn` falls through to `{}` with no clause and no comment. `flint.deps.mvn` already serves `pom` with real fetches and caching and has never been called. The cancellation recorded under `cli` was **revoked 2026-09-11**: it measured a benefit against a standard library that was missing `spec.alpha`/`zip`/`data`/`datafy`, which were implemented right afterwards |
| `deps.edn` support: git/npm/maven at exact versions | done | `cli` — **maven's transitive resolution was explicitly cancelled, and that is now revoked**, on a measured survey (8.9% of a 135-namespace Clojars sample compiles cleanly on flint; transitive resolution would fix ~2 of 135) |
| `flint build`/`tasks`/`task`/`deps`/`fetch`/`paths`/`targets` (babashka CLI) | done | `cli` |
| Capability injection on the CLI (`:with [...]`) | done | `cli`, `workspace-capabilities` |
| Native binary size, now that it fetches real dependencies (HTTP/git/zip/tar/semver) | measured, not a gap — 2.6 MB → 4.4 MB with all of `system-namespaces-and-deps`'s crates | `system-namespaces-and-deps` — "each crate is measured as it lands, not predicted" |

## 8. SDKs

| item | status | decision |
|---|---|---|
| Three SDKs (JS/ESM, Rust, C) sharing five nouns | done | [`other-hosts`](DECISIONS.md#other-hosts), [`structured-ports`](DECISIONS.md#structured-ports) |
| Tier 1 (thin wrapper over the wasm ABI) as the default SDK shape | done, everywhere except the JVM | `other-hosts` |
| Tier 2 (port the VM) for the JVM and CLR | done | `other-hosts`, `jvm-runtime`, `clr-runtime` — decided **by measurement** (Chicory at 39–500× V8), "much cheaper to learn from a benchmark than from a finished SDK" |
| Tier 3 (emit host bytecode/IL directly) for JVM and CLR | done, as AOT — see §4 | `other-hosts`, `jvm-runtime`, `clr-runtime` |
| Namespace resolver (`{workspace, identity, reader-or-virtual}`) as the shared concept both the CLI and every SDK build from | done | `workspace-capabilities` — this was the actual blocker on `reader-tags` step 3 and much of `workspace-capabilities`/`system-namespaces-and-deps`, for a long time |
| A resolver that hands back closures instead of one flat source blob (incremental/streaming source) | decided, not started, costed separately | `workspace-capabilities` — "worth doing anyway... but it should be costed separately" |
| `call` as asynchronous from the first version (not bolted on later) | done | `drivers` — "nothing is published yet... costs a rewrite of three SDK surfaces today and an ecosystem-wide break later" if deferred |
| `Sandbox`/`Image` as the two nouns (Docker-style: artifact vs. instantiated) | decided in `structured-ports`, partially reflected in code — see §3 note | `structured-ports` |

## 9. Tooling, gates & benchmarks

| item | status | decision |
|---|---|---|
| Benchmark the decision, not the runtime (construe as first customer, honest losses reported) | done | [`construe-benchmarks`](DECISIONS.md#construe-benchmarks) |
| Cross-runtime benchmark suite, 8 wasm engines | done | [`cross-runtime-benchmarks`](DECISIONS.md#cross-runtime-benchmarks) |
| A module says what it is without being instantiated (custom wasm section, `flint inspect`) | done (part 1) | [`module-metadata-and-shards`](DECISIONS.md#module-metadata-and-shards) |
| Shards (a compiled library module with no runtime of its own, importing the program's memory) | decided, not started — the format exists, the classification work (which namespaces may be bundled vs. must be imported) is the real remaining cost | `module-metadata-and-shards` part 2 |
| The unit format (`.unit.edn` + artifact), search path, ABI compatibility check | done | [`doc/unit-format.md`](doc/unit-format.md) — a live reference doc, not a scratch file |
| Coverage manifest (`doc/manifest.edn`, generated) and its README tables | done, generated — **do not hand-edit** | `bin/manifest`, `bin/readme-tables` |
| `bin/conform-hosts` (4-way transcript diff) | done, and its own reliability was a bug source (see below) | throughout |
| `bin/check-builtins`, `bin/check-decisions`, `bin/check-dist`, `bin/check-kin`, `bin/check-names` | done, several found real bugs on their first correct run | the decision index (now folded into `DECISIONS.md`) items `0d`, `0u` |
| GC stress mode extended to run on the ports' own runtime suites, not just conform fixtures | done (fixed a real gap — stress had been running on fixtures only) | the decision index (now folded into `DECISIONS.md`) item `0n` |
| jank dialect-suite harness (`bin/jank-suite`) | done | [`doc/jank.md`](doc/jank.md) |
| "Reached is not exercised" — coverage gates that see a builtin ran but not which branch | open, ongoing methodology fix | `doc/goals/README.md` — `dissoc`/`conj` were both "reached" and silently wrong on two runtimes for a case nobody tried; fix is building probes the other way round (every case that *should* refuse, listed, and the refusal asserted as the answer) |
| Registering a new `lib/` namespace requires three separate registration points (manifest, ESM stdlib bundle, native runtime rebuild) | known friction, not yet a single mechanism | `doc/goals/README.md` |

**A recurring lesson worth surfacing rather than burying in a table:** several
of the sharpest bugs found in the last two months were *gates that could not
fail* — `bin/check-builtins` crashing instead of checking (hid two missing
builtins for 210 commits), `bin/test` piping a real failure through `sed` so
it couldn't fail the suite, a CLR build silently routed to "skipped" for a
stretch long enough that 0 CLR rows ran and nobody noticed. the decision index (now folded into `DECISIONS.md`)
items `0d` and `0i` are the fullest write-ups; the standing rule that came out
of it is to make silence loud — an extraction, count, or comparison that finds
nothing should fail by default, not report a comfortable zero.

## 10. Documentation

| item | status |
|---|---|
| 39 numbered decision records (`DECISIONS.md`) | live, actively maintained, and self-indexing (the decision index (now folded into `DECISIONS.md`)) |
| Goals directory (`doc/goals/`) as the live investigation log | live; all six files (`README.md` plus `data-structures.md`, `kin-port.md`, `equiv-hash.md`, `extern-refs.md`, `hash-flooding.md`) read in full for this pass. `README.md` is where the `extend-method` saga, the `implied-requires` fix (§1), and the "reached is not exercised" bug family (§9) came from; `kin-port.md` (2,800 lines) fed §5's abandoned-work items and its own stale status line noted there; `equiv-hash.md` and `extern-refs.md` fed the `TY_TAGGED`/collection-node-morph cross-reference in §1; `data-structures.md` is mostly a reference doc (existing layouts, measured against Clojure) rather than a plan, with one real backlog worth naming: **chunked seqs** — `TY_CHUNKSEQ` exists in the type table and nothing constructs one, the largest measured lazy-seq cost (a `map` stage roughly triples a `reduce`'s time vs. Clojure's 32-per-chunk amortization) |
| This reorganisation (`ROADMAP.md`) | done, this document |
| Folding the 39 decisions into a smaller number of living reference docs | open question, explicitly deferred by the task that produced this roadmap — "the roadmap points AT the decisions; it does not replace them yet" |
| A `deck/` slide presentation | in progress, by a separate concurrent effort — out of scope here entirely |

---

## Cleanup proposal

Several root-level and `doc/`-level files look like scratch or superseded
planning material. Recorded here as a **proposal only** — nothing has been
deleted or moved as part of producing this roadmap, and the user is making the
deletion calls once this and the `deck/` work both land.

| file | what it is | recommendation | why |
|---|---|---|---|
| `BRIEF.md` | The original from-nothing project brief ("You are building this from nothing, autonomously"). Says outright: "When you finish, `README.md` replaces this file." | **Delete, or keep — but not silently either way.** If deleting: `README.md`'s own Decisions section currently says, present tense, "`BRIEF.md` is kept for provenance" and links to it — that line has to be removed in the *same* change, or it becomes a dead citation exactly like an orphaned decision number. | It says its own successor exists and has existed for a long time. Nothing in it is a live decision — it's fully absorbed into `dispatch`–`kin` and the README. But `README.md` currently treats it as a deliberately-retained record, not scratch, so "delete" is a two-file change (this file *and* the README line citing it), not a one-file cleanup. |
| `PLAN.md` | The original 14-layer build plan, written before any of layers 1–14 existed. | **Delete, or keep — same caveat as `BRIEF.md`.** `README.md`'s Decisions section links `PLAN.md` too, described there as "the build order, and what was settled before any code depended on it." | Every layer is long since built; the plan is historical intent, not a live tracker, and its content is fully superseded by what actually shipped (tracked in this roadmap and `CHANGELOG.md`). But like `BRIEF.md`, it's currently a live, deliberate citation from `README.md`, not an orphaned draft — deleting it cleanly means editing that link too. |
| `CHANGELOG.md` | A draft "0.0.1 — unreleased" changelog, citing decision numbers. | **Keep — it's the right artifact — but it needs a content refresh, not cleanup.** Fold nothing into the roadmap; it's a different genre (user-facing, chronological, not organised-by-area). | Checked directly: it cites decisions up through `a-vec-of-values-is-not-a-root` but has **no mention at all** of `structured-ports`–`ports-are-the-hosts` or `checks`–`kin` — no wire codec, no tables, no "ports belong to the host", no checks, no tagged literals, no reader tags, no workspace capabilities, no system-namespaces/deps, no kin. That's most of the shipped feature surface by volume over the last two months. Its own "Known limits" section is still accurate, but the changelog body itself is stale relative to the tree, not merely a different genre from this roadmap. |
| `doc/HANDOFF.md` | Post-mortem of the port-message-loss bug, explicitly referenced as authoritative by `threads-and-ports` and `drivers` ("the source of the five standards the work is now held to"). | **Keep as historical record**, exactly where it is. | It's cited by name from live decision docs as the origin of standing engineering discipline. Deleting or folding it would orphan those references the same way renumbering `DECISIONS.md` would. |
| `doc/jank.md` | A real, reproducible measurement (flint vs. the jank dialect suite), with a `bin/jank-suite` script behind it. | **Keep as a living reference doc.** Not scratch — it's dated, cites a specific jank revision, and states its own reproduction method. | It's the strongest evidence in the repo that the native and ported runtimes are faithful mirrors of each other (identical `pass-*` sets), which is a claim worth being able to re-run and re-cite. |
| `doc/ports.md` | Explains what's real vs. historical about the JVM/CLR ports (there used to be two implementations per host; now one), with current measurements. | **Keep as a living reference doc.** | Actively corrected in place ("An earlier version of this file said AOT was gone on these hosts... that reasoning was about the wrong artifact") — it's being maintained as ground truth, not left to rot. |
| `doc/coverage.md` | Generated by `bin/readme-tables` from `doc/manifest.edn`. | **Leave alone — do not touch, generated.** | Already covered by the hard constraint on `doc/manifest.edn`/README tables; flagging here only so nobody mistakes it for a hand-written doc worth folding into anything. |
| `doc/unit-format.md` | Hand-written reference for the `.unit.edn` format, cites `namespace-units` and stays current with what's built. | **Keep as a living reference doc.** | Genuinely a spec, not a decision-in-progress — decisions cite it rather than restate it. |
| `doc/kin-specialisation-cases.md` | An evidence file for a not-yet-designed kin macro system — five real duplication cases gathered from generated code. | **Keep, next to `kin` and `doc/goals/kin-port.md`.** | Explicitly framed as "input for designing that system... not a request for a particular design" — it's doing its job as raw material for an open question (§5 above), not as a finished decision. |
| `doc/benchmarks.txt`, `doc/construe-benchmarks.txt` | Raw captured benchmark output. | **Keep as historical/reference record.** | Not generated by any `bin/` script (committed run output, not a build artifact), and actually linked from `README.md` (lines 1427, 1582) as the full-detail backing for its summary tables — checked directly. `deck/benchmarks.md` (the concurrent slide-deck effort) already links `benchmarks.txt` too. Deleting either would break a live link. |
| `doc/benchmarks-vs-clojure.txt` | Raw benchmark output comparing flint to canonical Clojure across all three AOT targets, plus process-startup cost. | **Orphaned — needs an explicit decision, unlike its two siblings above.** | Checked directly: `README.md` links `benchmarks.txt` and `construe-benchmarks.txt` but **not this file**, and nothing under `deck/` links it either as of this pass. The content (flint vs. real Clojure, per-target AOT numbers) reads like strong deck material that nobody has drawn on yet. Either link it from somewhere (or fold its numbers into `deck/benchmarks.md`), or delete it once satisfied its numbers are reproduced/superseded elsewhere — right now it's the one benchmark file nothing points at. |

**Not proposed for any action:** `DECISIONS.md`, `doc/manifest.edn`,
`README.md`'s generated coverage tables, anything under `src/`, `lib/`,
`runtime/`, `runtimes/`, `kin/`, `test/`, `bin/`, or `deck/` — all out of scope
for this task by the hard constraints above.
