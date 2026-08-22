# flint

**Pure Clojure logic, compiled to a self-contained WebAssembly module.**

```
flint :src examples/ :fn demo/main   ->   out.wasm
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
wrote out/demo.wasm (175415 bytes)

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
- [Modularity](#modularity) — only reachable code ships
- [The compiler and its bootstrap](#the-compiler-and-its-bootstrap)
- [Library coverage](#library-coverage) — the per-namespace deficiency lists
- [Where flint differs from Clojure](#where-flint-differs-from-clojure)
- [Benchmarks](#benchmarks)
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

Not working, and named as such: no protocols, records or types; no transducers;
no sorted collections; no `eval` at runtime; the regex engine is 84× slower than
Java's. All of this is in [Limits](#limits).

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
| minor collections | 84 | 12.8 µs | 14.1 µs | 26.6 µs |
| major collections | 6 | 168.1 µs | 259.6 µs | 305.2 µs |

6.7% of wall clock, with 3.4 MiB promoted out of 89 MiB allocated. A major
collection scales with the live set: 10.5 µs at 10 000 live objects, 72.7 µs at
100 000, 304.7 µs at 400 000.

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
instruction** on a tight arithmetic loop. On workloads that touch data
structures it is 7–19 ns per instruction, which is the same dispatch cost
diluted by real work. So dispatch is roughly a third of the time on
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
| no parsers | 175 073 | — |
| JSON only | 256 923 | +81 850 |
| XML only | 259 392 | +84 319 |
| HTML only | 225 962 | +50 889 |

### The floor, honestly

Every module carries, whatever it does: the allocator and the generational
collector, the value encoding, hashing and equality, the number tower, UTF-8 and
string interning, the persistent collection internals with their transients, and
the interpreter. That is **~175 KB** stripped. It is all genuine runtime code —
the largest single function is the interpreter loop at 15 KB, then the CHAMP
insert path at 11 KB — with no surprise dependency: `libm` is the only crate the
core links, and it is what makes `clojure.math` possible at all in a bare wasm
build.

On top of the floor, each cljc namespace you reach adds its bytecode, and each
Rust parser you reach adds its object and its crate. Measured, same method as
the table above:

| what you reach | module | over the floor |
|---|---:|---:|
| nothing (a string literal) | 175 072 | — |
| `pr-str` of a nested structure | 175 131 | +59 |
| `clojure.math` (6 functions) | 192 636 | +17 564 |
| one `#"…"` regex | 228 164 | +53 092 |
| `clojure.edn/read-string` | 250 066 | +74 994 |
| `flint.data.json` | 256 923 | +81 850 |
| `flint.data.xml` | 259 392 | +84 319 |
| `flint.data.html` | 225 962 | +50 889 |

### Dependencies, and what each one bought

Four crates, all `no_std` + `alloc`, each earning its place:

| crate | where | what it gave | what it cost |
|---|---|---|---|
| `libm` | the floor | `sqrt`, `pow`, the trigs — pure Rust, no libc. Without it `clojure.math` cannot exist in a bare wasm build at all. | +17.6 KB, and only for programs that call it |
| `serde_json` + `serde` | `flint.data.json` | a correct JSON parser with `float_roundtrip`, driven through `DeserializeSeed`/`Visitor` so no `serde_json::Value` is ever built | +81.9 KB |
| `xmlparser` | `flint.data.xml` | a streaming XML tokenizer that is already `no_std` | +84.3 KB |
| `htmlparser` | `flint.data.html` | the same, tolerant of real markup — unquoted attributes, bare `&`, mixed case | +50.9 KB |

Nothing else. No `hashbrown`, no `regex`, no `dlmalloc`: the hash tables, the
regex engine and the allocator are flint's own, because the first two are what
this language is *for* and the third has to know about the collector.

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
| `clojure.core` | 334 | 43 | 314 | 24 |
| `clojure.edn` | 2 | 0 | 1 | 1 |
| `clojure.math` | 32 | 0 | 14 | 1 |
| `clojure.set` | 12 | 0 | 0 | 0 |
| `clojure.string` | 22 | 0 | 0 | 1 |
| `clojure.walk` | 7 | 0 | 3 | 0 |
| `flint.data.html` | 12 | 0 | n/a | n/a |
| `flint.data.json` | 3 | 0 | n/a | n/a |
| `flint.data.xml` | 9 | 0 | n/a | n/a |
| `flint.regex` | 10 | 0 | n/a | n/a |

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

*Added by flint:* `->str-builder` `apply2` `bigdec?` `cond-chain` `count-matching` `int-of-char` `interleave-all` `interleave2` `keep2` `map2` `mapcat2` `methods-of` `nil-or` `println-str` `re-quote-replacement` `repeat-forever` `repeat2` `sb-append!` `sb-str` `spread` `str-bytes` `str-join` `subvec2` `volatile?`

*Absent:* 314 names -- see `doc/manifest.edn` for all of them.

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

*Added by flint:* `split-literal`

#### `clojure.walk`

`macroexpand-all` needs a compiler at runtime, and a flint module carries none. `postwalk-demo`/`prewalk-demo` print.

*Absent:* `macroexpand-all` `postwalk-demo` `prewalk-demo`

<!-- END GENERATED COVERAGE -->

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

Two of those need more than a row:

**`(/ 1 2)` is `0.5`.** flint has no `Ratio`, so inexact integer division yields
a double. `(* 3 (/ 1 3))` is `1.0` here and `1` in Clojure. `quot` and `rem` are
exact and behave as Clojure's. This is the most visible numeric divergence.

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
  objects, so no `alter-var-root`, `binding` or `with-redefs`.
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
| hello (trivial) | 175 416 | 0.05 ms | 0.11 ms | 0.01 ms |
| tight loop, 10⁶ iterations | 199 733 | 0.08 ms | 168.42 ms | 168.28 ms |
| transient map, 10⁵ inserts | 213 690 | 0.09 ms | 46.64 ms | 45.81 ms |
| word frequency (string split) | 254 365 | 0.09 ms | 63.02 ms | 62.02 ms |
| word frequency (regex split) | 261 251 | 0.09 ms | 112.68 ms | 112.22 ms |
| JSON round trip, 2000 records | 283 877 | 0.10 ms | 101.95 ms | 100.86 ms |

Cold start is dominated by the work itself: the module's own startup — reserving
the heap, loading the image, running every top-level initialiser — is the 0.10 ms
gap between `compile` and `cold` on the trivial program.

### Against babashka

The fairest available baseline: another non-JIT Clojure, same machine, same
source, same input. It is **not** a claim about JVM Clojure.

| program | flint | babashka | ratio |
|---|---:|---:|---:|
| tight loop, 10⁶ iterations | 168.28 ms | 76.43 ms | 2.20× |
| transient map, 10⁵ inserts | 45.81 ms | 17.78 ms | 2.58× |
| word frequency (regex split) | 112.22 ms | 1.34 ms | **84×** |

Two and a half times slower than babashka on interpreter-bound and
data-structure-bound work is a fair place to be for a self-contained module with
its own collector. The regex number is not; see [Limits](#limits).

### Dispatch, isolated from data-structure cost

| program | instructions | warm | ns / instruction |
|---|---:|---:|---:|
| tight loop, 10⁶ iterations | 27 000 226 | 168.28 ms | 6.2 |
| JSON round trip | 12 066 852 | 100.86 ms | 8.4 |
| transient map, 10⁵ inserts | 3 000 323 | 45.81 ms | 15.3 |
| word frequency (string split) | 3 258 076 | 62.02 ms | 19.0 |

Read down the column: 6.2 ns is what a dispatched instruction costs when it does
almost nothing, and the rising numbers are the same dispatch diluted by real
work. Dispatch is about a third of the time on allocation-light code and much
less on anything that touches the heap.

### Transients versus persistents (native, no interpreter)

| operation | persistent | transient | speedup |
|---|---:|---:|---:|
| vector `conj`, 10⁵ | 44.6 ns/op | 4.4 ns/op | **10.1×** |
| map `assoc`, 10⁵ | 417.9 ns/op | 188.7 ns/op | 2.2× |
| map `assoc`, 10³ | 150.4 ns/op | 78.6 ns/op | 1.9× |
| map `assoc`, 64 | 86.6 ns/op | 58.6 ns/op | 1.5× |
| map `assoc`, 8 | 31.2 ns/op | 67.8 ns/op | 0.5× |

Transients are genuinely fast, which matters because the compiler is written in
cljc and compiles itself — transient performance is on flint's own critical
path, not a nice-to-have. The 8-entry row is the exception and is expected:
`transient` on an array-map promotes it to a CHAMP trie, so for a map that small
the promotion costs more than it saves.

### Collections at several sizes (native)

| operation | 8 | 64 | 10³ | 10⁵ |
|---|---:|---:|---:|---:|
| map `assoc` | 31.2 ns | 86.6 ns | 150.4 ns | 417.9 ns |
| map `get` | 15.6 ns | 11.1 ns | 20.0 ns | 24.6 ns |
| vector `nth` | | | | 1.9 ns |
| vector `conj` | | | | 44.6 ns |
| set `conj` | | | | 433.1 ns |
| keyword intern lookup | | | | 10.2 ns |

`get` staying flat from 64 to 100 000 entries is the trie doing its job.

### Collector

Given above: median minor pause 12.8 µs, median major pause 168 µs, 6.7% of wall
clock on an allocation-heavy workload.

---

## Limits

The honest list. Nothing here is stubbed and reported as working.

### Not implemented

- **Protocols, records and types.** No `defprotocol`, `deftype`, `defrecord`,
  `reify`, `extend-type`, `satisfies?`. This is the largest single gap and the
  one most likely to block a port. Multimethods are the substitute flint does
  have.
- **Transducers.** No `transduce`, `eduction`, `cat`, `completing`,
  `halt-when`, and no 1-arity transducer forms of `map`/`filter`/`take`/…. The
  eager and lazy forms all work.
- **Sorted collections.** No `sorted-map`, `sorted-set`, `subseq`, `rsubseq`.
  `sort` and `sort-by` work and are a stable merge sort.
- **Hierarchies.** Multimethods dispatch on `=` against the dispatch value with
  `:default` as the fallback. No `derive`, `isa?`, `parents`, `prefer-method`.
- **`eval` and `read-string`.** A flint module carries no compiler. `clojure.edn`
  is how text becomes data.
- **Var objects.** `#'x` is the value. No `binding`, `with-redefs`,
  `alter-var-root`, `set!`, dynamic vars.
- **`letfn` is a macro over volatiles**, not a compiler feature, so mutually
  recursive local functions pay one indirection per call.
- **The numeric tower stops at i64 and f64.** No `BigInt`, `Ratio`, `BigDecimal`;
  no `+'`/`-'`/`*'`. Overflow throws, as Clojure's checked operators do.
- **Metadata on functions.** A closure has nowhere to put it, so `with-meta` on
  a function returns it unchanged. (This is why `defmulti` keeps its method table
  in a second var.)
- **No I/O, threads, agents, refs or host interop**, by design — that is what
  "pure logic" means here. Atoms and volatiles exist because they are neither
  I/O nor coordination, and a compiler needs them.

### Slow, and by how much

- **The regex engine is 84× slower than Java's** on the word-frequency
  benchmark. It is a backtracking matcher written in cljc — which is what makes
  it tree-shake away when unused — using continuation closures, so it allocates
  per match step. A first-character skip cut a third off; the remaining cost is
  structural. [`doc/decisions/0002`](doc/decisions/0002-modularity.md) said to
  measure before moving something to Rust, and this is the measurement that
  would justify it: a Rust regex unit would cost nothing for programs that do
  not use one, because the unit mechanism already exists.
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
- **Unit compatibility checking is an assert, not a message.** The `:abi` field
  exists and is documented; rejecting an incompatible unit politely is not done.
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
                        # end-to-end linking, modularity, manifest, self-hosting
$ ./bin/bench           # the benchmark tables above
$ ./bin/manifest        # regenerate doc/manifest.edn
```

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
units-src/        the three parser units (adapted crates)
units/            built units: wasm objects + manifests  (bin/build-units)
src/flint/        the compiler, in portable cljc
lib/              the library, in cljc: clojure.*, flint.regex, flint.data.*
host/             flint.mjs -- 40 lines of JS to call a module
bench/            benchmark programs and the wasm timing harness
test/             conformance, modularity, manifest, self-hosting fixpoint
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
- [`doc/unit-format.md`](doc/unit-format.md) — what a unit is, and what would
  have to change to admit a user-compiled one.
- [`PLAN.md`](PLAN.md) — the build order, and what was settled before any code
  depended on it.

`BRIEF.md` is kept for provenance. This file supersedes it as the description of
what exists.
