# flint

**Pure Clojure logic, compiled to a self-contained WebAssembly module.**

```
flint :src examples/ :fn demo/main   ->   out.wasm
       [:exclude [ns ...]]      assert these namespaces are NOT reachable
       [:wasm-path <dir> ...]     where to find precompiled namespace units
```

The module carries its own heap, its own garbage collector, its own copy of the
core library, and the bytecode of your program. It imports nothing. Give it to a
browser, a worker, a server, an embedded host — anything with a WebAssembly
engine — and it computes the same answer.

```console
$ cat examples/demo.cljc
(ns demo)

(defn main [args]
  (str "hello, " (if (seq args) (first args) "world")))

$ ./bin/flint :src examples :fn demo/main :out out/demo.wasm
wrote out/demo.wasm (179538 bytes)

$ node host/flint.mjs out/demo.wasm flint
hello, flint
```

There is a slightly bigger one in [`examples/wordcount.cljc`](examples/wordcount.cljc)
— EDN configuration in, word frequencies out, in ordinary portable Clojure:

```console
$ ./bin/flint :src examples :fn wordcount/main :out out/wc.wasm
$ node host/flint.mjs out/wc.wasm '{:top 3 :min-length 3}' 'the cat sat on the mat and the cat sat again'
{:words 10, :distinct 6, :top [["the" 3] ["cat" 2] ["sat" 2]]}
```

(That is character-for-character what Clojure prints for the same program.)

The module exports `main`, which takes a vararg list of strings, wraps them in a
vector, and calls the function you named. That function takes exactly one
argument — a vector of strings — and returns a value, which comes back as text.
`host/flint.mjs` is a 40-line wrapper that turns that into `main("a","b")`; the
module itself needs no host support at all.

---

## Contents

- [Status](#status) — what works, what does not
- [How it fits together](#how-it-fits-together)
- [The value encoding](#the-value-encoding)
- [The collector](#the-collector) — and the rooting decision everything rests on
- [Data structures](#data-structures) — CHAMP, and why not Clojure's HAMT
- [The interpreter](#the-interpreter) — and why an interpreter at all
- [Modularity](#modularity) — only reachable code ships, and the two options that make it checkable
- [Green threads](#green-threads-and-why-nothing-suspends) — and why nothing suspends
- [Ports](#ports) — capabilities, back-pressure, two lifetimes, the host ABI
- [Resource limits](#resource-limits) — a wall clock bounds time; this bounds work
- [Protocols](#protocols-and-metadata-dispatch-as-the-main-road) — metadata dispatch as the main road
- [The compiler and its bootstrap](#the-compiler-and-its-bootstrap)
- [Library coverage](#library-coverage) — the per-namespace deficiency lists
- [Where flint differs from Clojure](#where-flint-differs-from-clojure)
- [Benchmarks](#benchmarks)
- [What this means for construe](#what-this-means-for-construe) — the decision, not the runtime
- [Limits](#limits) — what is slow, missing, or unfinished
- [Building and testing](#building-and-testing)
- [Decisions](#decisions)

---

## Status

Working, end to end, and tested:

- **It compiles and runs.** `flint :src … :fn ns/fn` produces a `.wasm` that
  node loads and runs, and the answer is right.
- **It compiles itself.** The compiler is portable `.cljc`. babashka compiles it
  once; from then on flint compiles flint, and the images are **byte identical**
  — bb-compiled and flint-compiled agree, and generation 2 reproduces itself
  exactly.
- **It is conformance tested against real Clojure.** 130 expressions run on
  flint, and the *same file* runs under babashka so the expectations are checked
  against a real Clojure rather than against my memory of one.
- **Only reachable code ships.** A program that never mentions XML contains no
  XML parser — asserted by a test, by symbol name, not by prose.
- **The deficiency lists are checked, not written.** `doc/manifest.edn` is
  generated from the source; a test regenerates it, compiles a program
  referencing every var it claims, runs it, and expands every macro it claims.

- **Green threads and ports work, and cost a pure program nothing.** `open`
  parks a green thread rather than suspending wasm — no JSPI, no Asyncify — and
  a program that never mentions them produces a module 54 bytes *smaller* than
  before they existed, asserted by symbol name in `test/threads.clj`.

- **It is benchmarked against the incumbent it would replace.** construe's real
  seed interpreter and real annotated contexts, compiled from one source by both
  cherry and flint: 1.4× on parse, 15× faster to first answer than a V8 isolate,
  and 275× slower on regex. All of it, including the losses, in
  [What this means for construe](#what-this-means-for-construe).

Not working, and named as such: no records or types (protocols exist, and
dispatch on kind or metadata); no transducers; no sorted collections; no `eval`
at runtime; a capability cannot be delegated at run time; the regex engine is
86× slower than babashka's. All of this is in [Limits](#limits).

---

## How it fits together

```
  your .cljc  ──┐
                │   flint (babashka, or flint itself)
  lib/*.cljc  ──┼──▶ read ─▶ analyze ─▶ emit ─▶ program image (bytecode)
                │                                      │
  units/*.o   ──┴──▶ rust-lld --gc-sections ─────▶ module ◀── spliced in
                     (only the units you reach)         │
                                                        ▼
                                                   one .wasm
```

Two tiers, and the split is deliberate
([`doc/decisions/0002`](doc/decisions/0002-modularity.md),
[`0003`](doc/decisions/0003-namespace-units.md)):

- **Tier 1 — Rust, precompiled to relocatable wasm objects.** Only what cannot
  be expressed in the language: memory, the collector, the value encoding,
  hashing, equality, the number tower, UTF-8, the persistent collection
  internals and their transients, the interpreter, and the three adapted
  parsers.
- **Tier 2 — cljc, compiled to bytecode.** Everything else, including all of
  `clojure.core`'s composite functions, `clojure.string`, `clojure.set`,
  `clojure.math`, `clojure.walk`, `clojure.edn`, the printer, the regex engine,
  and `flint.data.{json,xml,html}`'s public API.

Both tiers are shaken per name, not per file. See [Modularity](#modularity).

---

## The value encoding

A `Value` is 64 bits, NaN-boxed. Every IEEE-754 double is stored as its own bit
pattern *except* the negative-NaN range from `0xFFF9…` upward, which is stolen
for tags. Any NaN produced by arithmetic is canonicalised to the positive quiet
NaN `0x7FF8_0000_0000_0000`, which is not in the stolen range, so no observable
double is lost — NaN payloads are not observable in Clojure.

| `bits[63:48]` | meaning |
|---|---|
| `< 0xFFF9` | an IEEE-754 double, verbatim |
| `0xFFF9` | **heap** — `payload[31:0]` is a byte address in the GC heap |
| `0xFFFA` | **fixnum** — `payload[47:0]`, 48-bit two's complement |
| `0xFFFB` | **special** — `nil`, `false`, `true`, and internal sentinels |
| `0xFFFC` | **inline string** — `payload[47:40]` length 0–5, `payload[39:0]` UTF-8 bytes |
| `0xFFFD` | **inline keyword** — no namespace, name ≤ 5 bytes, same shape |
| `0xFFFE`, `0xFFFF` | reserved |

Three consequences worth stating:

**Chars are not a type.** A char is a one-character string, and every character
of Unicode is at most 4 UTF-8 bytes, so every char is an immediate. `\a` reads
as `"a"`, `(nth "abc" 1)` is `"b"`, and `(hash \a)` is `(hash "a")`.

**Equality is usually one compare.** Inline form is canonical, and every string
up to 32 bytes is interned, so two short strings are equal iff their bits are;
keywords are *always* equal iff their bits are. Only strings longer than 32
bytes need a byte comparison. Symbols compare `(ns, name)` instead of identity,
because `with-meta` must produce a distinct object that is still `=` — and those
two slots are themselves inline-or-interned, so it is still two 64-bit compares.

**Integers are 64-bit, in two representations.** Up to 48 bits they are
immediate; beyond that they are heap-boxed `i64`. The representation is
canonical — an `i64` in fixnum range is *always* a fixnum — so integer equality
never has to consider two forms of the same value. There is no `BigInt`:
`+`/`-`/`*` throw on `i64` overflow exactly as Clojure's do, but there is no
`+'` to promote to.

Heap objects have an 8-byte header and one of three layout classes: `Vals`
(every slot is a `Value`, which makes tracing one loop with no per-type
knowledge — the single biggest source of GC bugs removed), `Str` (a cached hash
and an ASCII flag, then UTF-8 bytes), and `Raw` (opaque bytes).

---

## The collector

### Rooting: the decision everything else rests on

wasm has no scannable machine stack. The operand stack is not in linear memory
and locals are not addressable, so a collector **cannot** find roots
conservatively. flint therefore never tries. The root set is exact, explicit,
and lives in one struct:

- **the VM's value stack** — interpreter frames are windows into one
  `Vec<Value>`, so every local, argument and intermediate of every active
  Clojure frame is in it. This is the primary root set and it costs nothing: the
  interpreter needs the stack anyway.
- **vars and the image's constant pool**;
- **a shadow root stack for native code** — a Rust builtin holding a `Value`
  across an allocation must push it here, because Rust locals are invisible to
  the collector and an allocation can move the object out from under them;
- **the intern tables**, which are *weak*: entries are dropped when their object
  dies.

This is also why flint is an interpreter rather than an ahead-of-time compiler
to wasm functions. Under AOT, live references sit in wasm locals where the
collector cannot see them, and you need a shadow-stack spill around every
allocation site — which hands back most of the speed. WasmGC exists to close
this gap; until it can be relied on, the interpreter is the honest choice.
([`doc/decisions/0001`](doc/decisions/0001-dispatch.md).)

The design has already paid for itself once. A VM frame used to *cache* its
closure, and that copy was a root the collector could not see; after a collection
moved the closure, `UPVAL` read a stale address. The fix was to delete the copy —
`stack[ret_to]` **is** the frame's closure — which keeps the invariant true with
no second mechanism. There is a regression test that forces a collection while an
upvalue-using frame is live.

**And once more, in a place that was much easier to get wrong.** `=` and `hash`
on a *compound* value allocate: comparing two vectors walks both through
`seq`/`first`/`next`, and each of those is an allocation. So a collection can run
in the middle of a map lookup or insert — and the CHAMP code was holding raw
addresses across exactly those calls. The symptoms were a key silently missing
from a map whose `count` said it was there, a hash cached into a moved object,
and eventually an out-of-bounds trap. Scalar keys never showed any of it, because
hashing a fixnum allocates nothing; it took the self-hosting fixpoint test
failing (the compiler interns its constants in a map keyed by vectors) to expose
it. Every path that calls `=` or `hash` now roots what it is holding, and
`test/gcstress.cljc` builds 30 000-entry maps and sets with vector, list, map and
set keys and asserts every one is findable afterwards. Lookups with a scalar key
take a version with no rooting at all, so `get` keeps its old cost.

### Generations

- **young**: two equal semispaces, allocation is a bump pointer, collection is a
  copy (Cheney with an explicit worklist so tracing is iterative, not recursive).
  Objects surviving two copies are promoted.
- **old**: chunks of pages, **non-moving**, mark-sweep with segregated free
  lists rebuilt with coalescing on every sweep. Objects ≥ 16 KiB skip the
  nursery entirely.

Old objects never move. That is the reason the write barrier and the remembered
set stay simple: a minor collection only ever rewrites pointers that point *into
the young semispace*, and there is exactly one contiguous range to test against.

Freshly allocated slots are initialised to `nil`, not zero. Zero bits are the
double `0.0`, and "unset trie slot" reading as a number instead of `nil` is a bug
that surfaces a long way from its cause.

Measured, building a 400 000-element vector (Apple M1 Pro):

| | count | median | p95 | max |
|---|---:|---:|---:|---:|
| minor collections | 84 | 12.9 µs | 14.2 µs | 24.1 µs |
| major collections | 6 | 165.2 µs | 257.2 µs | 305.0 µs |

6.7% of wall clock, with 3.4 MiB promoted out of 89 MiB allocated. A major
collection scales with the live set: 10.4 µs at 10 000 live objects, 82.2 µs at
100 000, 316.8 µs at 400 000.

---

## Data structures

Persistent list, vector, map and set, all with transients.

**Vectors** are Clojure's shape, because Clojure's is right: a 32-way trie with a
tail, so `conj` is a 32-element array copy amortised to O(1) and `nth` is at most
`depth` indexed loads. What differs is bookkeeping — a node is a normal GC object
whose slot 0 is the transient ownership token.

**Maps and sets are CHAMP**, not Clojure's HAMT (Steindorfer & Vinju, OOPSLA
2015; the same line of work ClojureDart's map improvements draw on). A CHAMP node
carries **two** bitmaps — `datamap` for entries stored inline, `nodemap` for
sub-nodes — with entries packed at the front and sub-nodes at the back:

```
  TY_BMNODE   [edit, datamap, nodemap, k0,v0, k1,v1, …, nodeN…node0]
  TY_COLLNODE [edit, hash, k0,v0, …]
```

Three things fall out, and all three matter here:

1. **Nodes are smaller and denser.** Clojure's `BitmapIndexedNode` stores a
   `null` key beside every sub-node pointer, wasting a slot per child, and
   promotes to a 32-wide `ArrayNode` at 16 children. CHAMP needs neither.
2. **The representation is canonical.** Clojure's HAMT can represent the same map
   two ways depending on insertion and deletion history, because deleting does
   not un-inline a node that has shrunk back to one entry. CHAMP always
   collapses. There is a test that builds the same 400-key map forwards,
   backwards, and via 200 insert-then-delete round trips, and asserts all three
   have byte-identical trie shape.
3. **Iteration needs no per-slot type test.** Entries are exactly the first
   `2·popcount(datamap)` slots.

Below `ARRAY_MAP_MAX` (8) entries a map is a flat insertion-ordered array-map, as
in Clojure. For a compiler — the workload on flint's own critical path — most
maps are AST nodes with a handful of bit-comparable keyword keys, and a linear
scan beats descending a trie.

**Transients** own nodes by object identity: the edit token is a freshly
allocated object used only for its identity, so "this transient owns that node"
is a pointer compare that survives a moving collector. `persistent!` nulls the
token, which turns the classic transient bug — a stale handle mutating a value
somebody else now holds — into a detectable state rather than silent corruption.

Sets are maps from element to itself. That costs a slot per element compared with
a set-specific node layout; it buys `get` returning the *stored* element (which
is what Clojure does, and what makes sets usable for canonicalisation) and one
trie implementation instead of two.

**Hashing is bit-compatible with JVM Clojure.** `(hash [1 2 3])` is 736442005
here as there. The formulas were derived by solving against real Clojure values
rather than from memory, which caught two things: strings hash as
`Murmur3.hashInt(String.hashCode())` rather than `hashUnencodedChars`, and a
symbol's *namespace* contributes its raw Java string hash while its *name*
contributes the murmur'd one. Both are pinned by tests, including an
astral-plane case for the UTF-16 view over our UTF-8 strings.

---

## The interpreter

A stack machine. Opcodes `0x00–0x7F` are base instructions; `0x80–0xFF` are
reserved for fused superinstructions, so fusing hot pairs later is not a format
break.

**Why a stack machine and not registers.** The literature is clear that
registers dispatch fewer instructions (Shi, Casey, Ertl & Gregg, VEE 2005 /
TACO 2008: ~47% fewer dispatches, ~32% faster, ~25% larger bytecode), and the
case is *stronger* on wasm than natively, because there is no computed goto and
no tail-call threading — a `br_table` loop is what you get and the branch is
unpredictable. Two things outweighed it:

- **Codegen is a post-order walk.** Register allocation is real work sitting on
  the bootstrap critical path: the compiler has to compile itself before
  anything runs at all, and there was never a point where it could not.
- **Stale register slots retain references.** A stack drops a reference when it
  pops; a register slot holds whatever was last written until overwritten, so
  dead slots keep objects alive unless the compiler emits liveness or clears
  them. That is floating garbage, invisible, and miserable to diagnose — a GC
  argument, not just an engineering one.

**And the measurement, rather than the opinion.** Dispatch costs **6.2 ns per
instruction on V8** on a tight arithmetic loop. On workloads that touch data
structures it is 7–19 ns per instruction, which is the same dispatch cost
diluted by real work.

> **Every figure in this section is V8** (node), which is one engine out of
> eight flint runs on. The same construe workload costs 9.7 ns/instruction on
> wasmtime, 10.8 on JavaScriptCore, 11.0 on V8, 15.7 on SpiderMonkey, **165 on
> wasm3** (a pure interpreter) and **426 on Chicory** (a wasm engine written in
> Java). The spread between JIT engines is about 1.5×, so a browser gets within
> 1.5× wherever it runs; an embedded interpreter costs 15×. `bin/bench-xruntime`,
> and `doc/decisions/0018` for the tables and what they decide. So dispatch is roughly a third of the time on
allocation-light code and much less elsewhere: superinstructions would help the
tight-loop case and little else. The number comes from timing a workload with
the step counter off and counting it with the counter on — the counter is gated
on a step limit, so it costs nothing in a normal run.

Other properties:

- **Clojure recursion runs on the VM's frame stack, not Rust's.** 3000-deep
  non-tail recursion works, and unbounded recursion throws a catchable
  `StackOverflowError` instead of smashing the wasm stack.
- **Tail calls are constant space** (tested at a million deep), so mutual
  recursion in tail position works even though Clojure's `recur` only handles
  self-recursion.
- **Exceptions unwind frames to a handler stack.** Native builtins signal
  failure by setting `rt.thrown` and returning `nil`, so there is no `Result`
  plumbing on the hot path.
- **Diagnostics exist**, because they had to: a step limit that reports a frame
  trace (turning "it hangs" into "it hangs in `read-form`"), function names in
  arity and call errors, and a bytecode disassembler (`bin/flint --disasm`).

---

## Modularity

> A module compiled from a program that never mentions XML must not carry an XML
> parser.

This is harder than it sounds, because the program is *interpreted*: every
builtin is reached through a dispatch table, so no linker can prove one dead —
they are all live by construction. Two obvious routes (rebuild the runtime per
compile with cargo features; null the table entries and run wasm DCE) were
rejected in [`doc/decisions/0003`](doc/decisions/0003-namespace-units.md) in
favour of a third:

**A namespace is a compilation unit.** Each is precompiled — Rust namespaces to
a relocatable wasm object, cljc namespaces to bytecode — and `flint` hands only
the reachable ones to `rust-lld --gc-sections`, which emits one module. No
`rustc` on the compile path; a link is milliseconds.

The detail it turns on: **nothing may call a builtin directly.** Every builtin is
a body plus a thin exported wrapper, reached only through `call_indirect` on a
table that `flint` fills *after* linking, by reading the module's export section
and appending an element segment. So granularity is per **builtin**, not per
namespace: an unexported builtin is `--gc-section`ed away even when its
namespace object is linked.

(A linker-assembled registry inside the module was the original plan and does not
work: rustc rejects relocations — and therefore function pointers — in a custom
`link_section` on wasm. Assembling the registry in `flint` after the link is
better anyway, for the per-builtin granularity, and it generalises to
user-compiled namespaces without change.)

Tier 2 shakes the same way, per **var**: start from `:fn`, take the transitive
closure over the reference graph, emit only those. This is what makes writing
`clojure.core` in cljc affordable — a hello-world keeps 31 of 375 top-level
items. A core function whose whole body is one `flint.rt/…` call is detected and
called directly at the call site, so the wrapper layer costs nothing.

Measured, with `test/modularity.clj` asserting each by symbol name:

| program | bytes | over the floor |
|---|---:|---:|
| no parsers | 179 196 | — |
| JSON only | 263 422 | +84 226 |
| XML only | 252 093 | +72 897 |
| HTML only | 230 119 | +50 923 |

### The floor, honestly

Every module carries, whatever it does: the allocator and the generational
collector, the value encoding, hashing and equality, the number tower, UTF-8 and
string interning, the persistent collection internals with their transients, and
the interpreter. That is **~179 KB** stripped. It is all genuine runtime code —
the largest single function is the interpreter loop at 15 KB, then the CHAMP
insert path at 11 KB — with no surprise dependency: `libm` is the only crate the
core links, and it is what makes `clojure.math` possible at all in a bare wasm
build.

On top of the floor, each cljc namespace you reach adds its bytecode, and each
Rust parser you reach adds its object and its crate. Measured, same method as
the table above:

| what you reach | module | over the floor |
|---|---:|---:|
| nothing (a string literal) | 179 196 | — |
| `pr-str` of a nested structure | 179 250 | +54 |
| a `binding` of a dynamic var | 183 428 | +4 232 |
| a protocol with one implementation | 195 604 | +16 408 |
| `clojure.math` (6 functions) | 197 971 | +18 775 |
| `clojure.string/split` on a literal | 204 003 | +24 807 |
| green threads (`spawn`/`join`) | 226 207 | +47 011 |
| a `channel` as well | 239 074 | +59 878 |
| one `#"…"` regex | 235 242 | +56 046 |
| `clojure.edn/read-string` | 254 990 | +75 794 |
| a host port, Transit+msgpack | 321 434 | +142 238 |
| a host port, EDN | 322 026 | +142 830 |
| a host port, JSON | 338 596 | +159 400 |
| `flint.data.json` | 263 422 | +84 226 |
| `flint.data.xml` | 252 093 | +72 897 |
| `flint.data.html` | 230 119 | +50 923 |

A host port costs what its **codec** costs, which is why the codec is a value
you pass rather than a format you name: EDN brings the reader *and* the printer,
JSON brings `flint.data.json`, Transit brings a msgpack reader and writer. A
program that only sends never links the decoder half of any of them.

The `split` row is there because it used to be the regex row. `clojure.string`
takes a string *or* a pattern, and the obvious way to write that — call
`flint.regex/pattern?` and branch — makes the reference to the regex engine live
for every program that splits on a comma, because shaking is per var and the
call is right there in `split`. `clojure.string` therefore does not name
`flint.regex` at all: it recognises a pattern structurally, and `flint.regex`
registers its operations into `clojure.string/regex-ops` at load time, which
happens exactly when something reaches `flint.regex/pattern` — and the only ways
to reach that are `re-pattern` and a `#"…"` literal. Splitting on a literal is
**31 KB smaller** as a result, and `:exclude [flint.regex]` will prove it.

### Dependencies, and what each one bought

Four crates, all `no_std` + `alloc`, each earning its place:

| crate | where | what it gave | what it cost |
|---|---|---|---|
| `libm` | the floor | `sqrt`, `pow`, the trigs — pure Rust, no libc. Without it `clojure.math` cannot exist in a bare wasm build at all. | +18.7 KB, and only for programs that call it |
| `serde_json` + `serde` | `flint.data.json` | a correct JSON parser with `float_roundtrip`, driven through `DeserializeSeed`/`Visitor` so no `serde_json::Value` is ever built | +82.5 KB |
| `xmlparser` | `flint.data.xml` | a streaming XML tokenizer that is already `no_std` | +72.8 KB |
| `htmlparser` | `flint.data.html` | the same, tolerant of real markup — unquoted attributes, bare `&`, mixed case | +50.9 KB |

Nothing else. No `hashbrown`, no `regex`, no `dlmalloc`: the hash tables, the
regex engine and the allocator are flint's own, because the first two are what
this language is *for* and the third has to know about the collector.

### `:exclude` — an assertion, not a pruning

```
flint :src src :fn app/main :exclude [flint.regex flint.data.xml]
```

`:exclude` names namespaces — **including built-in ones** — that the build must
not contain. The important word is *must*: it is a claim the compiler checks,
not an instruction to leave something out.

The difference matters. "Leave these out" has a failure mode where the module
compiles, links, ships, and then dies at run time on the one path nobody tested.
So if excluded code turns out to be reachable, that is a **compile error**, and
no module is written:

```console
$ flint :src src :fn splitpat/main :exclude [flint.regex]
namespace flint.regex is excluded, but it is reachable.

  flint.regex/pattern is reached by:
       flint.main/-main
    -> splitpat/main
    -> tokenize/tokens
    -> flint.regex/pattern

  also reachable in flint.regex: flint.regex/apply-lazy, flint.regex/cache, …

Either stop reaching it, or drop it from :exclude.
$ echo $?
1
```

The **chain** is the whole point. "`flint.regex` is reachable" sends somebody
grepping; naming `tokenize/tokens` in the middle tells them what to change. It
falls out of the reachability pass that already exists: while computing the
transitive closure from `:fn`, flint keeps the edge that first reached each var,
and walking those predecessors backwards is the chain. Where several excluded
vars are reachable it prints the one whose chain runs all the way from the entry
point, and prefers a real var over the synthetic id a bare top-level form gets.

Built-ins get a chain from the same edges: a Rust builtin is recorded as a
reference to `flint.native/<name>`, so `:exclude [flint.data.json]` on a program
that calls `json/read-str` names ``the builtin `flint/json-parse` `` too.

What it is for:

- **Guaranteeing an absence.** "This module must not contain an XML parser"
  becomes something the build enforces rather than something a reviewer eyeballs.
- **Finding out what drags something in.** Exclude it and read the chain. That
  is exactly how the `clojure.string` → `flint.regex` edge above was found.
- **Keeping a module small on purpose**, with a build failure if a refactor
  quietly reintroduces the dependency.

One honest caveat, because [`doc/decisions/0004`](doc/decisions/0004-exclude-and-unit-path.md)
asks for "excluding something unreachable makes the module smaller" and that is
not quite true here. flint already shakes **per var**, so a namespace nothing
reaches was never in the module and excluding it removes nothing: the flag's
value is the assertion, not the pruning. The size drop is on the other side of
the check — the same program written so the exclusion *holds* is smaller, and
`test/options.clj` asserts that difference (31 KB for `flint.regex`) rather than
a difference that does not exist.

### `:wasm-path` — precompiled units on a search path

```
flint :src src :fn app/main :wasm-path vendor/units
```

A search path for **precompiled wasm namespace units**, resolved by namespace
exactly the way `:src` resolves source: `demo.shout` →
`<dir>/demo/shout.unit.edn`, by directory hierarchy. A unit is a manifest, a
relocatable object, and optionally the rlibs it needs; the format is described in
[`doc/unit-format.md`](doc/unit-format.md).

**flint's own units are the last entry on that path**, not a special case. Every
compile you run already exercises the mechanism a user-supplied unit uses, and
`units/` can be shadowed like anything else. Three things this had to settle:

- **Precedence.** A unit and a `.cljc` file for the same namespace are not
  alternatives — a unit is the namespace's *native* half and the source its
  Clojure half, and `flint.data.json` ships as both. What can genuinely conflict
  is two of a kind, and both resolve the same way: **earlier on the path wins**,
  and the loser is reported rather than silently dropped. Source is searched
  `:src` dirs first, then `:wasm-path` dirs, then flint's own `lib/` — your code
  beats a copy vendored beside a unit, which beats flint's.
- **Compatibility.** Every unit on the path declares `:flint/unit` and an `:abi`
  map, and one flint cannot link is **refused by name and version** before the
  compile starts, rather than linked and left to trap:
  `refusing unit demo.shout at vendor/units/demo/shout.unit.edn: runtime 2 (need 1)`.
- **Reporting.** `--stats` prints which manifest each linked unit came from.

```console
$ flint :src app :fn app/main :wasm-path test/fixtures/wasm-path --stats
units linked demo.shout <- test/fixtures/wasm-path/demo/shout.unit.edn, flint.rt <- units/flint/rt.unit.edn
compile (on bb) 185ms  link 134ms  module 195356 bytes  image 4485 bytes  vars 31/404  builtins 24
```

`test/options.clj` builds a toy unit (`units-src/flint-demo-shout`, one builtin),
puts it on the path, links it, runs the module, and checks the answer — and then
does the same with a deliberately incompatible copy and checks the refusal.

---

## Green threads, and why nothing suspends

> A blocking `open` looks like it needs JSPI or Asyncify. It needs neither.

A synchronous wasm export **cannot be suspended** mid-execution to wait for a
host answer. The two usual escapes both cost something this project is not
willing to spend: **JSPI** is JavaScript-hosts-only, and portability of logic is
the entire point; **Asyncify** rewrites every function so the stack can be
unwound and rewound, and charges size and speed forever, on every program,
including the ones with no ports at all.

flint needs neither, because **it is an interpreter**. A green thread is a VM
state — its own value stack and frame stack. The scheduler is a loop *inside*
the interpreter that picks a runnable thread and runs it for a fixed slice.
"Blocked" means "not runnable yet", which an interpreter can simply say.
**Nothing suspends a wasm frame and nothing blocks the host**, because the
interpreter never left its own loop. This is the leverage the dispatch decision
in [`doc/decisions/0001`](doc/decisions/0001-dispatch.md) was already paying for.

```clojure
(require '[flint.thread :as t] '[flint.port :as p])

(let [[a b] (p/channel 1)                     ; a one-slot buffer
      w (t/spawn (fn [] (dotimes [i 5] (p/send a i)) :sent))]
  [(repeatedly 5 #(p/receive b)) (t/join w)])
;; => [(0 1 2 3 4) :sent]
```

Both directions park there: the sender on a full buffer, the receiver on an
empty one, five times each, and the whole thing is one `main()` call.

**Parking costs the interpreter's hot path nothing.** A park travels as a
distinguished value in `Rt::thrown`, so the check the VM already makes after
every native call is the whole mechanism. On resume the interpreter rewinds to
the call instruction and re-executes it — which is why a parking builtin must
decide to park *before* it changes anything.

**A thread cannot park inside native code.** `map`, `sort`, a comparator and a
lazy-seq force all re-enter the interpreter with Rust frames underneath, and
those frames are not a continuation anybody can save. Trying says so:
`cannot park here: this call is nested inside native code`.

### The scheduler is deterministic

Round-robin from the thread that just ran, with a fixed instruction slice, no
randomness and no clock. The same program with the same host answers in the same
order gives the same result, every time — `test/threads.clj` runs one five times
and asserts a single answer. A pure logic executor whose answer depends on
scheduling order would not be worth the name.

Preemption reuses the interpreter's existing step budget, so it costs no new
check: when a thread's slice runs out the VM yields instead of throwing. (In a
threaded program that budget belongs to the scheduler, so `set_step_limit` is not
available as a debugging aid there.)

### `binding` is per green thread

Dynamic vars work, and they are per green thread rather than per host thread:

```clojure
(def ^:dynamic *level* :info)

(binding [*level* :debug] (log "..."))       ; :debug in this thread only
(binding [*level* :trace] (t/spawn f))       ; f sees :trace
```

**A spawned thread inherits a snapshot of its spawner's bindings**, which is what
Clojure conveys to `future` and to agents. A *snapshot*: rebinding in the spawner
afterwards does not reach the child, and rebinding in one thread is never visible
in another. The scheduler saves and restores the whole binding map when it
switches, which is what makes it per-thread — and why `binding` costs a
single-threaded program nothing but the map.

Rebinding a var that was not defined `^:dynamic` is a compile error naming the
fix, not a silent set.

---

## Ports

A port is the unit of impurity. flint is a pure logic executor; a port is how a
host *lends* it a capability, and how two green threads talk.

```clojure
(let [[a b] (p/channel "label")]  (p/send a :hello) (p/receive b))   ; => :hello

(p/with-open [r (p/open "the-thing" {:codec edn/codec})]
  (p/send r :now)
  (p/receive r))
```

`open` signals the host, which **allows or refuses**. A refusal is a normal,
expected outcome and arrives as a catchable `SecurityException` — not a crash,
and not something a program has to guess at.

### What may cross, and why by reference is sound

**Data only.** A function is refused *by name* at the send —
`helper is a function, and a closure's meaning is its environment — which does
not travel` — and the check is deep, so a function nested inside a map is caught
too.

**Ports are not transferable and cannot be sent through a port.** That is a
deliberate simplification: no ownership transfer to reason about, no capability
leaking through a message, and a wire format that never has to represent a port.
The cost is real and named in [Limits](#limits): a capability cannot be delegated
at run time. Transfer can be added later; it could not be removed.

Transfer is **by value**. Inside one runtime the value is passed **by reference**
as an optimisation — and that is sound *precisely because flint values are
immutable*. There is no way for the sender to observe a later change, because
there are no later changes; sender and receiver cannot disagree about what was
sent. A mutable-object language could not take this shortcut, and would have to
copy or freeze. It is worth saying plainly because it is the property that makes
message passing cheap here rather than merely possible.

### Back-pressure

Every port has a bounded buffer and a send to a full one **parks the sender** —
the same parking mechanism as `open`, not a second one. A channel is bounded in
*messages*; a host port is bounded in **bytes**, because the point of
back-pressure is to bound memory and one 4 MB message is not one message's worth
of memory.

### Two ends, two lifetimes

This is the part that took two goes to get right, and the first answer was wrong
in an instructive way. Making the host end a root is necessary — without it every
handle the host is holding is a use-after-free waiting for a collection — but on
its own it leaves **explicit `close` as the only way a script can say it is
finished**, which is the `free()` problem. The common case is not a script that
forgets; it is a script that throws, or returns having simply dropped its last
reference.

So the two ends have **separate lifetimes**:

- **The host end is a strong root.** The port cannot be collected while the host
  holds a handle.
- **The flint end is ordinary reachable memory.** When the collector finds it
  unreachable, that is semantically identical to `close`, so the runtime raises
  `{:kind :closed}` on the script's behalf and the port lives on, half-closed,
  until the host lets go.

Which is why the two ends hold each other's *id* rather than each other: a strong
peer link would keep a dropped end alive forever, and would also defeat the
liveness check below. Ids resolve through a weak table — the same machinery the
string interner already uses.

A channel is finished only when **both** ends are, which is what makes
`:half-closed` a state you can see rather than a race you cannot:

| state | meaning |
|---|---|
| `:open` | both ends live |
| `:half-closed` | the peer closed cleanly — drain what is buffered, then end of stream |
| `:closed` | this end is closed |
| `:orphaned` | the peer went away *without* closing; receiving **errors** |
| `:refused` | the host would not lend this capability |

`:orphaned` and `:half-closed` are deliberately different. One is a tidy goodbye
and reads as end of stream; the other is a hang-up and says so.

**`with-open` is the good path**, and the collector is the net. Collection is
deterministic but it is not *prompt*, and a host holding a socket open until a
collection happens is a real cost.

Two things fall out of the same reachability, free:

- **A thread parked on a port whose peer has become unreachable is woken with an
  error** rather than hanging. That receive can never succeed, and the collector
  has already worked out that it cannot. (A parked thread is a root, so the port
  it is parked *on* is never collected; only its peer can vanish, which is
  exactly the case worth catching.)
- **Program exit closes every flint end and leaves the events for one last
  drain**, so a host never has to guess whether more is coming.

### The host interface

A module with no ports is exactly what it was: `main()` returns 0 or 1 and there
is no pump. When there *are* ports, `main` may return **2 — "I need the host"**.
Nothing is suspended; the interpreter simply has nothing runnable.

```js
let code = main();
while (code === 2) {
  for (const ev of drain()) handle(ev);   // one call, everything pending
  code = flint_resume();
}
```

One outbound queue, drained in one call, in a deterministic order:

| event | carries |
|---|---|
| `open-request` | a **token** to answer with, the port id you will hold, the capability name |
| `message` | the port id and the bytes |
| `closed` | the port id — the flint end has gone |

Three kinds through one export rather than three exports: one call per pump, one
ordering rule, and no chance of forgetting one.

**The token is a continuation, not an id.** It is `(generation << 16) | slot`,
and the generation is bumped when the slot is freed, so a late or duplicated
reply is *rejected* rather than resuming whatever thread now occupies that slot —
a wrong thread woken with a stranger's value is the kind of bug that is never
found in production. `flint_continue` returns 0 when it refuses a token, and
`test/host_abi.mjs` asserts every way of getting it wrong. Token 0 is never
valid.

**`flint_continue` enqueues; it never re-enters.** It records the answer and
marks the thread runnable, and the scheduler runs at the next pump. A host may
well call it from inside a host function that wasm invoked, and a naive
implementation would run the scheduler on top of itself.

**The runtime creates the port pair**, keeps the flint end, and tells the host
the id of the end it holds. The host never holds two ends and never hands one
back.

**The event is a notification; the state is the truth.** `flint_port_state(id)`
answers *what is the runtime end of this port doing?* at any time. That is not a
convenience: if an event were the only way to learn a durable fact, then an event
dropped, missed, or simply not drained yet would be an **unrecoverable leak** — a
host handle to a port nobody will ever mention again. The pushed `:closed` is an
optimisation over polling, not the sole carrier of the fact, and
`test/host_abi.mjs` proves it by throwing every `:closed` away and asking
instead. The principle generalises: never let a transient notification be the
only record of a durable state.

It is symmetric. A script can ask its own end (`closed?`), and a send or receive
against a port whose peer is gone **errors rather than parking** — a script
blocked forever on a host that hung up is the same failure as a leaked handle,
seen from the other side.

### Where the cost is, and therefore what is batched

A wasm↔host call is tens of nanoseconds. The expensive part is **marshalling**.
So a message is serialised into linear memory **at send time**, the host reads
byte ranges, and one drain hands over everything pending. Measured, on the
machine named under [Benchmarks](#benchmarks):

| batch size | per message |
|---:|---:|
| 1 | 11 833 ns |
| 1000 | 1 483 ns |

Eager serialisation does cost work when the host never reads. That is the trade:
it is what makes the drain cheap and the byte budget mean anything.

### Formats, and the conversion that is allowed to fail

A host port carries bytes, so a value has to be encoded. The codec is a **value
you pass**:

```clojure
(:require [flint.port :as p] [flint.port.edn :as edn])
(p/open "thing" {:codec edn/codec})
```

| codec | carries | notes |
|---|---|---|
| `flint.port.edn` | everything | flint's own notation; nothing is lost |
| `flint.port.json` | JSON's data model | **strict**: see below |
| `flint.port.transit` | everything | Transit over msgpack, binary |
| *(none)* | raw bytes | `send` takes a string; driving a resource raw has to work |

Passing the codec rather than naming a format is deliberate twice over. A `cond`
over every format inside `flint.port` would make all of them reachable from any
program that opens any port, so a JSON program would carry an EDN reader it never
uses. And a registry filled by requiring a namespace for its side effect is a
load-order trap.

**JSON cannot represent EDN**, and that is not a detail to paper over. Keywords,
symbols, sets and non-string map keys have no JSON form. "The runtime will try to
convert" hides exactly the failures that bite later: a keyword that comes back a
string, a set that comes back an array, `{:a 1}` that becomes `{"a": 1}` and
never comes home. So a value JSON cannot carry is **an error at the send, naming
the value**:

```
JSON cannot represent a keyword: :nope. JSON has no keywords, symbols, sets or
non-string map keys, and converting silently is how a :a comes back a "a".
```

Where the coercion genuinely is wanted, ask for it, the way `clojure.data.json`
makes `:key-fn` the caller's decision: `(p/open "x" {:codec json/codec :key-fn name})`.

**Transit rather than a fourth format**, because it exists for this, it is
self-describing, and it already has the extension mechanism tagged values need.
This implementation leaves out Transit's *caching* — an optimisation, not part of
the data model — so messages are larger than a caching writer's would be, and
says so in the namespace docstring rather than leaving it to be discovered.

### None of it is in a pure module

Threads and ports are namespace units like any other
([`doc/decisions/0003`](doc/decisions/0003-namespace-units.md)), so a program
that never mentions `spawn`, `channel` or `open` carries **no scheduler, no port
machinery and no host-callback surface**. `test/threads.clj` asserts that by
symbol name and reports the number:

| | bytes |
|---|---:|
| pure module before threads | 179 250 |
| pure module with threads linked out | **179 196** |

Fifty-four bytes *smaller*, which is noise in the right direction: the hooks the
scheduler needs cost a few hundred bytes and removing a redundant entry path paid
for them. The requirement was that a pure program not be made worse, and it was
not.

That floor has since moved, and deliberately — see
[Resource limits](#resource-limits), which reports what bought the difference.
Threads and ports are still not in it.

### Two builds

A production module carries **no diagnostic machinery** — absent, not disabled
(`doc/decisions/0016`). Not a runtime flag: a flag leaves the code linked, still
costing bytes and still branching somewhere hot. It is a cargo feature, so the
code is not there at all.

| | bytes |
|---|---:|
| production module | **203 360** |
| the same with `--diagnostics` | 203 884 |
| what turning diagnostics on costs | **+524** |

Absent from production: snapshots and their export format, the inspector, GC
stress mode, the `forward()` plausibility check, the `slot()` forwarded-pointer
assertion, and the heap statistics exports. `test/twobuilds.clj` asserts each by
name.

Present in production, and also asserted, because these are the ones most likely
to be cut by mistake: **gas, the memory cap and the deterministic scheduler**.
They are resource control, not instrumentation, and construe's gates depend on a
reproducible instruction count — the test does not merely check the symbol is
exported, it runs a program under a limit and checks the count is non-zero and
the limit still fires.

It is a security argument as much as a size one. flint's strongest measured case
is sandboxing code somebody else wrote, and a module that ships snapshot export
is a module that can be asked to dump its heap. *Absent* is a different
guarantee from *disabled*.

---

## Resource limits

An interpreter loses to a JIT on speed; see [Benchmarks](#benchmarks), where it
loses by 1.4× on parsing and by 275× on regex. Deterministic resource limits are
a large part of what it buys back, and they are a **feature rather than a knob**.

The argument is the same one as the sandbox argument, and it is about being a
*better boundary* rather than a faster one:

> An isolate gives you a **wall-clock timeout**. That bounds *time*, and time
> varies with machine load, with what else is on the box, with whether the JIT
> tiered up. A gate built on one is flaky by construction — the same program
> passes on a quiet machine and fails on a busy one.
>
> An instruction count bounds **work**, and work is the same on every machine.
> That turns "did this candidate hang?" from a flaky timeout into a reproducible
> fact.

For a system whose whole premise is gates measuring model-written code and being
believed, the difference is the product.

```js
inst.exports.set_step_limit(hi, lo);      // gas, in bytecode instructions
inst.exports.set_memory_limit(bytes);     // heap ceiling
```

### The same program costs the same every time

`test/limits.clj` runs one program five times and reads the counter back:

    16693 instructions, five times over

Runaway loops stop **at** the limit rather than near it — a 500 000 budget
reports `spent 500000 of 500000`. Exceeding either limit is a *catchable error*
carrying `{:spent :limit :thread}` as ex-data, not a trap: a host that wants to
report which candidate ran away can.

Catching it does not defeat it. A program that swallows its own budget error and
starts another runaway loop is stopped by an error that escapes every handler,
because a budget a candidate can catch its way out of is not a budget.

### The hole that would have quietly not worked

Instruction counting bounds **bytecode**, and a native call is one instruction
however much work it does. Left there, `(= big-vector-a big-vector-b)` would have
cost 1 against the budget while touching a million elements — a gas limit with a
hole exactly where the expensive operations live.

So every builtin whose cost is not O(1) charges the same counter in proportion to
what it touched. Doubling the work doubles the charge:

| one native call | 10 000 elements | 20 000 | ratio |
|---|---:|---:|---:|
| `=` over two big vectors | 550 730 | 1 100 730 | **2.00×** |
| `hash` of a big vector | 280 671 | 560 671 | **2.00×** |
| `seq` over a big map | 550 702 | 1 100 702 | **2.00×** |
| `str-join` over many pieces | 310 673 | 620 673 | **2.00×** |

**Where a native still cannot cheaply account for itself**, so you know the
shape of what is left: map and set lookup (`get`, `contains?`) is O(log₃₂ n) and
is deliberately *not* charged on the hot path, because the accounting would cost
more than the operation. The bound is therefore exact for linear work and
optimistic by a logarithmic factor for point lookups in a loop. Sorting,
merging and `into` are cljc, so they are bytecode and counted instruction by
instruction with no native shortcut at all.

### Catastrophic backtracking is bounded exactly

`#"(a+)+$"` against a failing subject is the textbook ReDoS pattern. flint's
regex engine is written in cljc, so its backtracking **is bytecode** and every
step was already on the counter. The gate is exact rather than heuristic, and it
needed no special case:

    a known catastrophic regex is stopped by the gas limit

### Memory: collect first, then fail

Hitting the memory cap runs a collection before giving up, so a program is never
killed for garbage it was about to drop. Only if the heap is still full does it
raise — again catchable, naming what was held against what was allowed.

This closed a genuine silent-wrong-answer bug: a failed allocation used to return
`nil` and the program **carried on**. Under an 8 MB cap a run reported
`:total 3932160` where the answer was `13107200`. A wrong answer is worse than an
error, and allocation now raises.

### What it costs, both halves

The loop is *swapped* rather than branched in: the budget policy is a zero-sized
type whose `tick` either counts or compiles away entirely, and `run` picks the
instantiation once at entry rather than testing per instruction. The nice part
is the interaction with the scheduler — the step budget *is* the green-thread
time slice, so counting is already required wherever concurrency is. Gas is free
exactly where threads exist, and the uncounted loop is for single-threaded
unlimited runs.

| | |
|---|---|
| counting, on an unlimited single-threaded run | **6.7–7.6% slower** |
| one interpreter instantiation | 184 936 bytes |
| two instantiations | **201 271 bytes** |
| what the free loop costs in module size | **+16 335 bytes** (~9%) |

Both halves are reported because only one of them is flattering. Buying back
7% of interpreter speed with 16 KB of module is a real trade, not a free win,
and on a size-constrained target it may be the wrong one.

---

## The compiler and its bootstrap

The compiler is portable `.cljc` — reader, analyzer, emitter, driver — and it
compiles itself.

**The bootstrap host is babashka**, as the brief specifies, and it works: `bb`
1.3.190 runs the subset flint's compiler needs. The compiler avoids `deftype`
and `defprotocol` entirely and uses plain maps, so the portable subset is small.

```
gen0   bb compiled the compiler        88 089 image bytes
gen1   flint compiled the compiler     88 089 image bytes   -- IDENTICAL
gen2   reproduces itself byte for byte
```

**`defmacro` works by running the macro body through `flint.eval`**, an
interpreter for the compiler's own AST. Handing the form to the host's `eval`
would have been less code and would have made the compiler behave differently on
babashka than on flint — exactly the divergence a fixpoint test exists to catch.
It also means the bootstrap needs no second compiler in Rust, which
`doc/decisions` warned would be the implementation that drifts.

flint reads its own source rather than borrowing the host's reader, for the same
reason: reader conditionals, syntax quote and metadata have to behave
identically in both places.

### What the fixpoint test caught

Three bugs, and none of them would have been found any other way:

- **The reader's end-of-input sentinel was the keyword `::eof`.** That worked
  until the reader read its own source, where `::eof` appears as a literal and
  was silently dropped as "no form here". The symptom was a mis-shaped `if` in a
  function far downstream. The sentinel is now a fresh volatile that source text
  cannot forge.
- **A VM frame cached its closure**, and the collector could not see the copy
  (above).
- **Map iteration order reached the output bytes** in four places, so the same
  source compiled to two different images depending on the host. Constants, sets
  and destructuring now use a host-independent canonical order; map literals keep
  *source* order, which needed an ordered array-map builtin because `into {}`
  goes through a transient and a transient map does not preserve order.

And one self-application bug: the analyzer rewrites a `#"…"` literal — which the
reader represents as `{:flint/regex src}` — into a call to the regex compiler,
and that rewrite also matched the *reader's own construction of that marker*. A
flint-hosted reader returned compiled patterns where a host-hosted one returned
markers. Fixed by rewriting only when the value is a literal string.

### Getting data in

`clojure.edn` is a full EDN reader with reader-tag support, written fresh rather
than reusing the compiler's reader so that a program that reads EDN does not drag
syntax quote and reader conditionals in behind it. `:readers` and `:default` work
as they do in Clojure. There are no built-in `#inst` or `#uuid` readers, because
flint has no date or UUID type; an unknown tag calls `:default` if you gave one
and otherwise throws, which is what Clojure does for a tag with no registered
reader.

`flint.data.json`, `flint.data.xml` and `flint.data.html` are ours — not
`clojure.data.*` — but shaped so a Clojure programmer can guess them:
`read-str`/`write-str` with a `:key-fn` for JSON, and
`{:tag :div :attrs {…} :content […]}` for XML and HTML.

The parsers are **adapted crates**, not written here:

- **XML** — `xmlparser`: already `no_std`, already a tokenizer, so flint values
  are built as tokens arrive with no intermediate document tree.
- **HTML** — `htmlparser`: the same design, tolerant of real markup.
- **JSON** — `serde_json` with `default-features = false, features = ["alloc",
  "float_roundtrip"]`, through `DeserializeSeed` + `Visitor`, which is a
  streaming interface: no `serde_json::Value` is ever built.

Two other JSON crates were tried and rejected, and the reasons are the
interesting part: **actson** is a genuine push parser but is not `no_std` and its
`panic_impl` collides with the runtime's; **microjson** *is* `no_std` but reads
integers as `isize` — 32 bits on wasm32 — and floats as `f32`, and JSON needs 64
bits of both.

**JSON number policy**, since JSON has none: a number with no fraction and no
exponent reads as a **long**, anything else as a **double**. `1` is `1`; `1.0`
and `1e3` are doubles.

---

## Library coverage

Every namespace flint ships comes with a stated deficiency list. The lists are
**generated and checked**, not written: `bin/manifest` derives them from the
source and by diffing against real Clojure's `ns-publics`, and
`test/manifest.clj` regenerates them, compiles a program referencing every var
they claim present, runs it, and expands every macro they claim present. A false
claim fails the build.

<!-- BEGIN GENERATED COVERAGE -->
| namespace | vars | macros | missing vs Clojure | flint-only |
|---|---:|---:|---:|---:|
| `clojure.core` | 344 | 45 | 310 | 30 |
| `clojure.edn` | 2 | 0 | 1 | 1 |
| `clojure.math` | 32 | 0 | 14 | 1 |
| `clojure.set` | 12 | 0 | 0 | 0 |
| `clojure.string` | 23 | 0 | 0 | 2 |
| `clojure.walk` | 7 | 0 | 3 | 0 |
| `flint.data.html` | 12 | 0 | n/a | n/a |
| `flint.data.json` | 3 | 0 | n/a | n/a |
| `flint.data.xml` | 9 | 0 | n/a | n/a |
| `flint.doc` | 11 | 0 | n/a | n/a |
| `flint.port` | 13 | 1 | n/a | n/a |
| `flint.port.edn` | 3 | 0 | n/a | n/a |
| `flint.port.json` | 3 | 0 | n/a | n/a |
| `flint.port.transit` | 3 | 0 | n/a | n/a |
| `flint.regex` | 11 | 0 | n/a | n/a |
| `flint.rpc` | 6 | 0 | n/a | n/a |
| `flint.thread` | 8 | 0 | n/a | n/a |

Full lists, machine readable, in [`doc/manifest.edn`](doc/manifest.edn).
`test/manifest.clj` regenerates that file and fails if it differs, compiles a
program referencing every var it claims present, runs it, and expands every
macro it claims present. A false claim fails the build.

#### `clojure.core`

Absent for a reason, by group: I/O (`print`, `slurp`, `read-line`, `*out*`);
concurrency (`future`, `agent`, `ref`, `promise`, `send`, `locking`, `deliver`);
mutable references beyond atoms (`ref`, `var-set`, `alter-var-root`, `binding`, `with-redefs`);
host interop (`class`, `instance?`, `proxy`, `bean`, `aget`, `make-array`, every `*-array`);
reflection and namespace surgery (`ns-publics`, `resolve`, `intern`, `find-var`, `the-ns`);
a compiler at runtime (`eval`, `read-string`, `load-string`, `macroexpand`);
nondeterminism (`rand`, `shuffle`, `random-uuid`, `random-sample`);
the numeric tower flint does not have (`bigint`, `bigdec`, `ratio`, `numerator`, `+'`, `-'`, `*'`);
protocols and types (`defprotocol`, `deftype`, `defrecord`, `reify`, `extend`, `satisfies?`);
hierarchies (`derive`, `isa?`, `parents`, `prefer-method`);
transducers (`transduce`, `eduction`, `cat`, `completing`, `halt-when`, and the 1-arity transducer forms of `map`/`filter`/`take`/...);
and sorted collections (`sorted-map`, `sorted-set`, `subseq`, `rsubseq`).

*Added by flint:* `->str-builder` `apply2` `bigdec?` `bytes->str` `cond-chain` `count-matching` `find-protocol-method` `int-of-char` `interleave-all` `interleave2` `keep2` `kind` `map2` `mapcat2` `methods-of` `nil-or` `opaque` `opaque-label` `opaque?` `println-str` `re-quote-replacement` `repeat-forever` `repeat2` `sb-append!` `sb-str` `spread` `str-bytes` `str-join` `subvec2` `volatile?`

*Absent:* 310 names -- see `doc/manifest.edn` for all of them.

#### `clojure.edn`

`read` takes a stream;
flint has no stream type. `read-string` is the whole surface, plus `read-all` which flint adds for the same reason.

*Absent:* `read`

*Added by flint:* `read-all`

#### `clojure.math`

`ulp`, `nextAfter`, `nextUp`, `nextDown`, `IEEEremainder`, `getExponent`, `scalb` and the `*Exact` integer functions are absent deliberately: they need rounding-mode and exponent guarantees flint does not have, and the brief is right that half-implementing them is worse than leaving them out. `random` is absent because it is not pure.

*Absent:* `IEEE-remainder` `add-exact` `decrement-exact` `get-exponent` `increment-exact` `multiply-exact` `negate-exact` `next-after` `next-down` `next-up` `random` `scalb` `subtract-exact` `ulp`

*Added by flint:* `abs`

#### `clojure.set`

#### `clojure.string`

*Added by flint:* `register-regex-ops!` `split-literal`

#### `clojure.walk`

`macroexpand-all` needs a compiler at runtime, and a flint module carries none. `postwalk-demo`/`prewalk-demo` print.

*Absent:* `macroexpand-all` `postwalk-demo` `prewalk-demo`

<!-- END GENERATED COVERAGE -->

---

## Protocols, and metadata dispatch as the main road

All polymorphism is built on protocols. They work differently from Clojure's for
a reason that is not a shortcut: **flint has no types.** No `deftype`, no
`defrecord`, no classes. So "which type is this?" has no general answer, and
dispatch has two roads:

```clojure
(defprotocol Shape
  (area [s])
  (describe [s prefix]))

;; 1. built-in kinds -- a small closed set
(extend-protocol Shape
  :vector (area [s] (* (nth s 0) (nth s 1)))
  :number (area [s] (* s s)))

;; 2. metadata -- for everything a user defines
(def circle (with-meta {:r 2} {:shapes/area (fn [s] (* 3 (:r s) (:r s)))}))

(area [3 4])   ; => 12   by kind
(area circle)  ; => 12   by metadata
```

The kinds are `:nil :boolean :number :string :keyword :symbol :vector :map :set
:list :fn :port :thread :atom :var :regex :exception :other`, and `(kind x)`
returns one. It is a closed set because it can be: those are all the things a
flint value *is*.

**Metadata is the primary mechanism here**, not the corner it is in Clojure,
where `extend-via-metadata` is opt-in and slightly out of the way. There is
nothing else a user-defined abstraction can be, so this is the road to reach for
rather than the fallback. A method attached by metadata is keyed by the method's
fully-qualified keyword, exactly as Clojure keys `extend-via-metadata`.

A value with no implementation fails with a message naming the protocol, the
kind, and what to do:

```
no implementation of shapes/area (protocol shapes/Shape) for a value of kind
:string. Extend the protocol to that kind, or attach :shapes/area as metadata
on the value.
```

### What can carry metadata, and what cannot

Since metadata is load-bearing, it matters which values have anywhere to put it.
This falls out of the value encoding rather than being a policy:

| carries metadata | does not |
|---|---|
| vectors, maps, sets, lists and seqs, symbols, atoms | **inline values** — strings of ≤5 bytes, unqualified keywords, and chars, which live *in the value word itself* |
| | numbers, booleans, `nil` — likewise immediate |
| | heap strings and keywords, which are **interned**: metadata would break the invariant that makes `=` on them a single compare |
| | functions and ports |

Those dispatch by kind, which is what kinds are for. `with-meta` on a value that
cannot carry it returns the value unchanged rather than pretending.

---

## Where flint differs from Clojure

These are behaviour differences, not absences — code that compiles but does
something else. Each one is a case in `test/conform/basics.cljc` carrying *both*
answers, so the differential test against real Clojure stays green and this list
cannot go stale.

| | Clojure | flint |
|---|---|---|
| `\a` | a `Character` | the string `"a"` |
| `(nth "abc" 1)` | `\b` | `"b"` |
| `(count "aé😀")` | `4` (UTF-16 units) | `3` (code points) |
| `(subs s a b)`, `(nth s i)` | UTF-16 indices | code-point indices |
| `(/ 1 2)` | `1/2`, a `Ratio` | `0.5`, a double |
| `(hash \a)` | `97` | `1455541201` — the hash of `"a"` |
| `case` | O(1) jump table | O(n) chain of `=` |
| map literal value order | source order | source order (but see below) |
| `(into {} …)` on ≤8 entries | insertion-ordered array-map | hash map, unordered |
| `clojure.string/split` | regex only | regex **or** a literal string |
| protocol dispatch | on type, with `extend-via-metadata` as an opt-in corner | on **kind** or **metadata** — there are no types, so metadata is the main road |
| `binding` | per host thread | per **green** thread; a spawn inherits a snapshot |
| a port | — | not transferable, and cannot be sent through a port |

Three of those need more than a row:

**`(/ 1 2)` is `0.5`.** flint has no `Ratio`, so inexact integer division yields
a double. `(* 3 (/ 1 3))` is `1.0` here and `1` in Clojure. `quot` and `rem` are
exact and behave as Clojure's. This is the most visible numeric divergence.

**Protocols dispatch on kind or metadata.** flint has no types, so there is
nothing for `extend-type` to name. See [Protocols](#protocols-and-metadata-dispatch-as-the-main-road).

**Small-map ordering.** flint preserves the source order of a map *literal* (the
reader builds an insertion-ordered array-map, which is what lets the compiler
compile itself deterministically), but `(into {} …)` goes through a transient,
and a transient map does not preserve insertion order. Clojure's array-map
happens to. Neither language *promises* an order beyond the literal, but code
that relies on it will see a difference in `keys`, `seq` and `pr-str` output.

Also worth knowing:

- `#""` regex: `.` matches any character **including** newline, where Java's
  default does not.
- `seq` over a map or set materialises the entries into a vector once, then walks
  it: O(n) at the first `seq`, O(1) per step. Bulk operations (`reduce`, `into`,
  `merge`, `count`, `map`) walk the trie directly and never build it.
- Reader conditionals default to the feature set `#{:flint}`. flint is not the
  JVM, so a `:clj` branch would be host interop it cannot compile. Ported code
  needs a `:flint` or `:default` branch.
- `(var x)` / `#'x` yields the *value*, not a Var object. There are no Var
  objects, so no `alter-var-root` and no `with-redefs`. `binding` **does** work,
  on vars defined `^:dynamic`; it is a stack discipline per green thread rather
  than anything to do with Var objects.
- A bare top-level expression (not a `def`) is compiled if its namespace is
  reached at all, because there is nothing to reach it *by*. `defmethod` relies
  on this.

---

## Benchmarks

Apple M1 Pro, Darwin 23.6.0, node v24.6.0. Method: best of 5 runs per
measurement, one node process per program; `cold` is `WebAssembly.compile` plus
instantiate plus the first `main()`, `warm` is a second `main()` on the same
instance. Full output, including the native runs, in
[`doc/benchmarks.txt`](doc/benchmarks.txt); reproduce with `./bin/bench`.

### Module size and cold start

| program | bytes | compile | cold | warm |
|---|---:|---:|---:|---:|
| hello (trivial) | 179 539 | 0.05 ms | 0.11 ms | 0.01 ms |
| tight loop, 10⁶ iterations | 203 861 | 0.11 ms | 169.71 ms | 169.37 ms |
| transient map, 10⁵ inserts | 218 531 | 0.10 ms | 47.33 ms | 46.76 ms |
| word frequency (string split) | 250 689 | 0.10 ms | 63.17 ms | 62.44 ms |
| word frequency (regex split) | 269 013 | 0.08 ms | 113.36 ms | 113.15 ms |
| JSON round trip, 2000 records | 290 398 | 0.10 ms | 104.36 ms | 103.50 ms |

And the cost of the host boundary, from `test/host_abi.mjs` on the same machine:
a message drained one at a time costs **11 833 ns**; a thousand drained in one
call cost **1 483 ns each**. The boundary crossing is nothing; the marshalling
is everything, which is why messages are serialised at send time and the queue
is drained whole.

Cold start is dominated by the work itself: the module's own startup — reserving
the heap, loading the image, running every top-level initialiser — is the 0.10 ms
gap between `compile` and `cold` on the trivial program.

### Against babashka

The fairest available baseline: another non-JIT Clojure, same machine, same
source, same input. It is **not** a claim about JVM Clojure.

| program | flint | babashka | ratio |
|---|---:|---:|---:|
| tight loop, 10⁶ iterations | 169.37 ms | 76.58 ms | 2.21× |
| transient map, 10⁵ inserts | 46.76 ms | 18.32 ms | 2.55× |
| word frequency (regex split) | 113.15 ms | 1.32 ms | **86×** |

Two and a half times slower than babashka on interpreter-bound and
data-structure-bound work is a fair place to be for a self-contained module with
its own collector. The regex number is not; see [Limits](#limits).

### Dispatch, isolated from data-structure cost

| program | instructions | warm | ns / instruction |
|---|---:|---:|---:|
| tight loop, 10⁶ iterations | 27 000 226 | 169.37 ms | 6.3 |
| JSON round trip | 12 330 891 | 103.50 ms | 8.4 |
| transient map, 10⁵ inserts | 3 000 323 | 46.76 ms | 15.6 |
| word frequency (string split) | 3 257 997 | 62.44 ms | 19.2 |

Read down the column: 6.2 ns is what a dispatched instruction costs **on V8**
when it does almost nothing, and the rising numbers are the same dispatch
diluted by real work. Dispatch is about a third of the time on allocation-light code and much
less on anything that touches the heap.

### Transients versus persistents (native, no interpreter)

| operation | persistent | transient | speedup |
|---|---:|---:|---:|
| vector `conj`, 10⁵ | 44.6 ns/op | 4.4 ns/op | **10.1×** |
| map `assoc`, 10⁵ | 422.5 ns/op | 189.2 ns/op | 2.2× |
| map `assoc`, 10³ | 154.5 ns/op | 81.2 ns/op | 1.9× |
| map `assoc`, 64 | 87.2 ns/op | 63.8 ns/op | 1.4× |
| map `assoc`, 8 | 41.6 ns/op | 57.4 ns/op | 0.7× |

Transients are genuinely fast, which matters because the compiler is written in
cljc and compiles itself — transient performance is on flint's own critical
path, not a nice-to-have. The 8-entry row is the exception and is expected:
`transient` on an array-map promotes it to a CHAMP trie, so for a map that small
the promotion costs more than it saves.

### Collections at several sizes (native)

| operation | 8 | 64 | 10³ | 10⁵ |
|---|---:|---:|---:|---:|
| map `assoc` | 41.6 ns | 87.2 ns | 154.5 ns | 422.5 ns |
| map `get` | 20.8 ns | 12.4 ns | 21.4 ns | 26.8 ns |
| vector `nth` | | | | 1.9 ns |
| vector `conj` | | | | 44.6 ns |
| set `conj` | | | | 437.5 ns |
| keyword intern lookup | | | | 10.2 ns |

`get` staying flat from 64 to 100 000 entries is the trie doing its job.

### Collector

Given above: median minor pause 12.9 µs, median major pause 165 µs, 6.7% of wall
clock on an allocation-heavy workload.

---

## What this means for construe

flint's first real customer is [construe](https://github.com/), which compiles
model-written `.cljc` to JavaScript with cherry and runs it in a V8 isolate.
So the question is not "is flint fast" — it is **for each place construe runs
code, is flint better, worse, or irrelevant, and what does that do to the bill?**
Two facts from construe's own spec make it sharp: **CPU is 96% of what a session
costs**, and **cherry cannot compile inside the deployed Worker**, which is a
live blocker on promoting a candidate.

Method: construe's actual 258-line seed interpreter and four real annotated
contexts from its own annotator, in `bench/construe/`. Both compilers are handed
**one source file** and both are checked to compute the same answer before
anything is timed. Apple M1 Pro, node v24.6.0; reproduce with
`./bin/bench-construe`, full output in
[`doc/construe-benchmarks.txt`](doc/construe-benchmarks.txt).

### Parse latency — closer than expected

| | per parse | vs cherry |
|---|---:|---:|
| cherry → JS in node (JIT) | 0.063 ms | 1.0× |
| flint (wasm interpreter) | 0.085 ms | **1.4×** |

I expected to lose this badly and did not. The reason is that only half the work
is interpretation: the other half is persistent maps and vectors, and cherry's
are JavaScript objects while flint's are Rust compiled to wasm. The interpreter
loses; the data structures win; they very nearly cancel.

Per *invocation* — which is the shape of construe's read path, one parse per
request — flint is 0.381 ms against cherry's 0.248 ms, the difference being the
module's top-level initialisers running again on every `main()`.

### Cold start and footprint — where flint wins, and by a lot

| | first answer | memory |
|---|---:|---:|
| flint: compile + instantiate + run | **1.00 ms** | 6.4 MB reserved, 170 KB live |
| V8 isolate: create + load + run | 14.59 ms | 6.2 MB heap |

Both rows are **V8**, and the size of this win is the least portable number
here: it is 6.4 ms of fixed cost on wasmtime and 4.3 ms on wasm3, against 32 ms
for a node process. What *is* portable is the resident cost — flint adds 0.5 MB
on an engine that interprets its module and 20–27 MB on ones that compile it,
because it is the compiled code and not the 6.4 MB reservation that gets paid
for (`doc/decisions/0018`).

**15× faster to first answer**, and it lines up with construe's own measurement
of 23 ms cold on workerd. This is an argument about *sandboxing cost*, not
throughput, and it is probably the strongest economic case: construe runs
untrusted evolved artifacts, and a wasm module with its own collector and no
host access is a stronger boundary than an isolate as well as a cheaper one.

The memory column is **not** a flint win. 6.4 MB is reserved linear memory, most
of it untouched — the live set is 170 KB — but a host that counts reservations
rather than residency will not see a difference.

### The suite run — 500 contexts through one warm module

| | total | per case |
|---|---:|---:|
| flint | 42.53 ms | 0.085 ms |
| cherry → JS | 31.66 ms | 0.063 ms |

30 collections across five runs and a peak live set of 170 KB. **No major
collection pause appears mid-suite**, which was the risk worth checking: an
agent running the gates after every edit is how a round consumes a month of
sandbox time, and a collector that stalled would show up here.

### Compilation — against a compiler that does not work in production

| | time | output |
|---|---:|---:|
| cherry → JS | 16.68 ms | 35 KB + its ~300 KB runtime |
| flint → wasm, compiled by babashka | 935 ms | 290 KB, complete |
| flint → wasm, **compiled by flint** | 3.71 s | 290 KB, complete |

The sizes are not like for like: cherry emits a module that needs its runtime
beside it, and the flint number is a whole module — runtime, collector, core
library and bytecode.

The last row is the one that matters. flint's compiler is `.cljc` and
self-hosts, so it *can* run inside a deployed artifact, where cherry
demonstrably cannot. **3.7 seconds does not fit a Worker's CPU budget**, so this
unblocks nothing today. It is a different kind of number from the others: not
"is it faster" but "does it fit", and the answer is not yet.

### Prefix scan — construe's own unmeasured number

| | per scan |
|---|---:|
| cherry → JS | 1.14 ms |
| flint | 3.37 ms |

construe's spec calls this "the most expensive unmeasured number": assumed at
1 ms, suspected nearer 0.2 ms. On a 4000-term lexicon **neither runtime is
anywhere near 0.2 ms** — cherry is at the assumed 1 ms and flint is three times
that. This is a *representative* implementation rather than construe's annotator
— the fixtures here are the interpreter and the contexts — so read it as the
shape of the cost, not as construe's own figure. It is worth them measuring.

### The workload in parts — and where flint loses badly

| operation | flint | cherry | ratio |
|---|---:|---:|---:|
| deep nested map/vector build | 1 453 ns | 2 984 ns | **0.5×** |
| keyword-keyed map access | 861 ns | 620 ns | 1.4× |
| reduce over spans | 1 392 ns | 1 108 ns | 1.3× |
| `into {}` over pairs | 1 190 ns | 519 ns | 2.3× |
| merge two 5 000-key maps | 3 089 ns | 1 524 ns | 2.0× |
| `clojure.string/split`, literal | 4 472 ns | 249 ns | **18×** |
| `clojure.string/split`, regex | 164 425 ns | 598 ns | **275×** |

flint is *faster* at building nested structure and within 2.3× on every map
operation. Then string handling falls off a cliff. The regex engine is written
in cljc so that it tree-shakes away when unused, and against a JIT running
JavaScript's native `RegExp` it is 275× slower. An annotator built on regular
expressions would be unusable on flint today.

### So: the four questions, answered plainly

**Can flint serve the read path?** Yes, on this evidence — 1.4× on parse is well
inside the "few milliseconds of CPU" construe budgets, and 0.085 ms per parse
leaves room. With one caveat that is not small: if the annotator is
regex-shaped, no. That is the number to check before committing.

**Is it a cheaper sandbox than an isolate?** Yes, and this is the strongest case.
15× faster to first answer, a smaller live set, and a boundary that is stronger
by construction — no host objects, no shared heap, no `eval`. If construe pays
per-request isolate spin-up anywhere, that is where the money is.

It is also a *better* boundary, not just a cheaper one, and for a gate that is
the larger point: an isolate bounds a candidate with a wall clock, which varies
with machine load, while flint bounds it with an instruction count, which does
not. See [Resource limits](#resource-limits).

**Does it unblock compiling in production?** Not yet. flint's compiler self-hosts
and *runs* where cherry's does not, which removes the architectural blocker — but
3.7 seconds does not fit a Worker's CPU budget. It is a route rather than a fix,
and closing it means making the self-hosted compiler several times faster.

**Where would adopting flint cost more than it saves?**

- **Anything regex-heavy.** 275× is not a gap you optimise around; it needs the
  Rust regex unit that [Limits](#limits) already names as undone.
- **String-shaped work generally** — even splitting on a literal is 18×.
- **A read path that spins a fresh instance per request** would pay flint's
  top-level initialisers each time. It is small here and grows with the number
  of constants an artifact holds.
- **Anywhere memory is charged by reservation** rather than by residency: flint
  reserves 6.4 MB up front to hold a 170 KB live set.
- **The library gap.** No records, no transducers, no sorted collections. Ported
  code that leans on `defrecord` needs reshaping, and that is engineering time
  that does not show up in any table here.

---

## Limits

The honest list. Nothing here is stubbed and reported as working.

### Not implemented

- **Records and types.** No `deftype`, `defrecord`, `reify`, `extend-type`.
  `defprotocol`, `extend-protocol`, `extend` and `satisfies?` **do** exist and
  dispatch on kind or metadata — see
  [Protocols](#protocols-and-metadata-dispatch-as-the-main-road) — but there is
  no way to make a new *type*, so a port of code that leans on `defrecord` still
  needs reshaping into maps with metadata.
- **Transducers.** No `transduce`, `eduction`, `cat`, `completing`,
  `halt-when`, and no 1-arity transducer forms of `map`/`filter`/`take`/…. The
  eager and lazy forms all work.
- **Sorted collections.** No `sorted-map`, `sorted-set`, `subseq`, `rsubseq`.
  `sort` and `sort-by` work and are a stable merge sort.
- **Hierarchies.** Multimethods dispatch on `=` against the dispatch value with
  `:default` as the fallback. No `derive`, `isa?`, `parents`, `prefer-method`.
- **`eval` and `read-string`.** A flint module carries no compiler. `clojure.edn`
  is how text becomes data.
- **Var objects.** `#'x` is the value. No `with-redefs`, `alter-var-root` or
  `set!`. Dynamic vars and `binding` do exist, per green thread.
- **`letfn` is a macro over volatiles**, not a compiler feature, so mutually
  recursive local functions pay one indirection per call.
- **The numeric tower stops at i64 and f64.** No `BigInt`, `Ratio`, `BigDecimal`;
  no `+'`/`-'`/`*'`. Overflow throws, as Clojure's checked operators do.
- **Metadata on functions, numbers and inline values.** A closure, a number and
  a short string have nowhere to put it, so `with-meta` returns them unchanged.
  This matters more than it used to, because metadata is how protocols dispatch
  on user-defined abstractions: see the table under
  [Protocols](#what-can-carry-metadata-and-what-cannot). (It is also why
  `defmulti` keeps its method table in a second var.)
- **No host threads, agents, refs or host interop**, by design. Green threads
  and ports exist and are cooperative and deterministic; nothing here is
  parallel, and nothing preempts across a native call.
- **A capability cannot be delegated at run time.** Ports are not transferable
  and cannot be sent through a port, so a program cannot hand a resource it was
  lent to another part of itself over a channel — it has to pass the port by
  ordinary reference, within the runtime. That buys no ownership transfer to
  reason about, no capability leaking through a message, and a wire format that
  never has to represent a port. Transfer can be added later; it could not be
  removed. (`doc/decisions/0006`.)
- **A parked thread cannot be inside native code.** `map`, `sort`, a comparator
  and a lazy-seq force re-enter the interpreter with Rust frames underneath, and
  those are not a continuation anybody can save. Parking there is a clean error,
  not a corruption.
- **No I/O without a host.** A module still imports nothing; a port is the host
  *lending* a capability, and a host that grants nothing runs a pure program.

### Slow, and by how much

- **The regex engine is 86× slower than babashka's** on the word-frequency
  benchmark, and **275× slower than cherry-compiled JS** on the construe
  workload — see [What this means for construe](#what-this-means-for-construe),
  where it is the one result that would rule flint out of a job. It is a backtracking matcher written in cljc — which is what makes
  it tree-shake away when unused — using continuation closures, so it allocates
  per match step. A first-character skip cut a third off; the remaining cost is
  structural. [`doc/decisions/0002`](doc/decisions/0002-modularity.md) said to
  measure before moving something to Rust, and this is the measurement that
  would justify it: a Rust regex unit would cost nothing for programs that do
  not use one, because the unit mechanism already exists — and now that
  `clojure.string` no longer drags the engine in, `:exclude [flint.regex]` is a
  one-line way to prove a build does not depend on it either way. Not done: the
  two options in [Modularity](#modularity) came first, as the brief asked.
- **Everything else is ~2.5× babashka**, which is a reasonable place for an
  interpreter that brings its own collector.
- **`case` is O(n)**, a chain of `=`. Keys are usually keywords or short
  strings, which compare as a single 64-bit word, so the chain is cheaper than
  it looks — but it is not a jump table.
- **`seq` over a map or set is O(n) at the first call.** Bulk operations avoid
  it entirely.
- **Non-ASCII string indexing is O(n).** Strings record whether they are pure
  ASCII, and if they are, a code-point index is a byte index and `subs`/`nth`
  are O(1). For strings with any multi-byte character they walk. Fixing this
  properly needs an index or a rope, and nothing yet demands it. (Before the
  ASCII flag existed, splitting a string was quadratic and the word-frequency
  benchmark took 762 ms instead of 62 ms.)
- **Function values are poor map keys.** Identity hashing under a moving
  collector needs a stored hash; flint returns a per-type constant instead,
  which is correct but degrades those lookups to linear probing.

### Unfinished

- **`flint.data.html` is a documented subset, not HTML5.** It handles void
  elements, unclosed elements, an end tag closing the nearest match, and a named
  table of implied end tags (so `<p>a<p>b` and `<li>x<li>y` come out as
  siblings). It does **not** do implied `<html>`/`<body>`, `<table>` foster
  parenting, `<script>`/`<style>` raw-text mode, or the adoption agency
  algorithm for misnested inline elements. If you need a conforming parse tree,
  this is the wrong tool, and the namespace docstring says so before you start.
- **XML drops the declaration, comments, DOCTYPE and processing instructions**,
  and does not *resolve* namespaces — a prefixed name arrives as `:prefix/local`.
- **`format` is `%s`, `%d`, `%f` and `%%` only.** `%f` prints six decimal places
  as Java's does, but loses precision at very large magnitudes where Java's does
  not.
- **The linker driver is host-side only.** The compiler self-hosts; `flint.wasm`
  and `flint.link` run next to `rust-lld` and are not part of that requirement
  (a flint module has no processes to spawn).
- **Transit's caching is not implemented.** The writer never emits cache codes,
  so a message with many repeated keys is larger than a caching writer's would
  be. It is an optimisation rather than part of the data model, and a reader
  that ignores it still reads correct data.
- **A binary port's payload is a vector of byte-sized integers** on the flint
  side, because flint has no byte-array type. That is correct and it is slow:
  one boxed fixnum per byte through the codec. A `bytes` value type is the
  obvious fix and is not done.
- **The self-hosted compiler is slower than the bootstrap one**, which is
  expected: `--self` takes 2.5 s where babashka takes 0.17 s for the same
  program, most of it node startup plus flint being ~2.5× babashka. Both produce
  byte-identical modules. babashka remains the default for that reason.

### Where the brief turned out to be wrong

The brief says the EDN reader should be "in the runtime". It is not — it is a
cljc namespace. Putting it in Rust would have meant every module carried an EDN
reader whether or not it read EDN, which the same brief's modularity requirement
forbids. As a cljc namespace it tree-shakes per var, and it is available to a
running program exactly as intended. Recorded here rather than quietly worked
around.

---

## Building and testing

Prerequisites, all already present on the machine this was built for:
`rustup` with the `wasm32-unknown-unknown` target, `babashka`, and `node`.

```console
$ ./bin/build-units     # compile the Rust units to wasm objects (once)
$ ./bin/flint :src examples :fn demo/main :out out/demo.wasm
$ node host/flint.mjs out/demo.wasm

$ ./bin/flint :src examples :fn demo/main :out out/demo.wasm --self
                        # the same, compiled BY flint instead of by babashka;
                        # the two modules are byte identical

$ ./bin/test            # everything: rust tests, reader, conformance both ways,
                        # end-to-end linking, gc stress, modularity, :exclude
                        # and :wasm-path, threads and ports, the host ABI,
                        # manifest, self-hosting
$ ./bin/bench           # the benchmark tables above
$ ./bin/bench-construe  # the decision benchmark, against cherry and a V8 isolate
$ ./bin/manifest        # regenerate doc/manifest.edn
$ ./bin/build-test-unit # the toy unit test/options.clj puts on the :wasm-path path
```

`units/` and `test/fixtures/` are build output and are not tracked; both scripts
above regenerate them.

Useful flags: `--stats` (compile/link timings and tree-shaking counts),
`--disasm <fn>` (bytecode), `--explain <var>` (why a var was or was not kept),
`--keep-names` (leave the wasm name section in), `FLINT_STEP_LIMIT=n` on the
runner (turn a hang into a frame trace).

### Toolchain notes

- `rustc --emit=obj --target wasm32-unknown-unknown` needs **no nightly
  features**. The nightly toolchain is used only because it is the one with the
  wasm32 target installed here; Homebrew's `rustc` 1.92.0 has no wasm std.
- The linker is `rust-lld -flavor wasm` from the rustup toolchain.
  `/opt/homebrew/bin/wasm-ld` is present but **does not run on this machine** —
  it is lld 19.1.7 built against llvm 21.1.8 and dies with a dyld symbol error.
  `rust-lld` is not a fallback; it ships with the compiler.
- Because `flint` drives the final link itself, the runtime supplies the three
  symbols rustc's allocator shim would otherwise generate.
- Link with `--strip-all`. Debug info dominates otherwise: a validation module
  measured 1 386 bytes of code and 755 KB of `.debug_*` sections.

### Layout

```
runtime/          the Rust core: mem, value, obj, gc, hash, collections, vm, abi
units-src/        the parser units (adapted crates), the concurrency unit, and a
                  toy unit for tests
units/            built units: wasm objects + manifests  (bin/build-units)
src/flint/        the compiler, in portable cljc
lib/              the library, in cljc: clojure.*, flint.regex, flint.data.*,
                  flint.thread, flint.port and its codecs
host/             flint.mjs -- calling a module, and the pump a port needs
bench/            benchmark programs, the wasm timing harness, and construe's
                  real fixtures for the decision benchmark
test/             conformance, gc stress, modularity, options, threads, the
                  host ABI, manifest, self-hosting fixpoint, and fixtures
doc/              decisions, unit format, generated manifest, benchmark output
```

---

## Decisions

Written down where somebody will find them, with the reasoning:

- [`doc/decisions/0001-dispatch.md`](doc/decisions/0001-dispatch.md) — interpreter
  vs AOT, stack vs register. Both argued, and the dispatch cost measured.
- [`doc/decisions/0002-modularity.md`](doc/decisions/0002-modularity.md) — only
  reachable code ships, builtins included.
- [`doc/decisions/0003-namespace-units.md`](doc/decisions/0003-namespace-units.md)
  — a namespace is a compilation unit, and linking composes them.
- [`doc/decisions/0004-exclude-and-unit-path.md`](doc/decisions/0004-exclude-and-unit-path.md)
  — `:exclude` as an assertion with a reference chain, and `:wasm-path` as a
  namespace-resolved search path with `units/` as its last entry.
- [`doc/decisions/0005-threads-and-ports.md`](doc/decisions/0005-threads-and-ports.md)
  — green threads, ports and protocols, and the point that governs them: `open`
  parks a thread, it does not suspend wasm.
- [`doc/decisions/0006-host-abi.md`](doc/decisions/0006-host-abi.md) — the host
  ABI: continuation tokens with generations, one event queue, where the cost
  really is, and the two lifetimes of a port's two ends.
- [`doc/decisions/0007-construe-benchmarks.md`](doc/decisions/0007-construe-benchmarks.md)
  — benchmark the decision, not the runtime: what the numbers have to answer
  for somebody choosing whether to adopt this.
- [`doc/decisions/0008-document-resource.md`](doc/decisions/0008-document-resource.md)
  — documents: structure eagerly, content on demand, and the fetch planning
  that follows from measuring latency against bandwidth.
- [`doc/unit-format.md`](doc/unit-format.md) — what a unit is, and what would
  have to change to admit a user-compiled one.
- [`PLAN.md`](PLAN.md) — the build order, and what was settled before any code
  depended on it.

`BRIEF.md` is kept for provenance. This file supersedes it as the description of
what exists.
