# Modularity

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
