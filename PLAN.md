# flint — build plan

Layers, each testable before the next:

1. **platform + heap region** — page allocator over `memory.grow` (wasm) / `std` (host).
   GC pointers are `u32` offsets relative to a heap base, so the same code runs
   natively for tests and in wasm for real.
2. **values** — NaN-boxed u64. Immediates: fixnum(48-bit), nil/true/false,
   inline strings (<=5 bytes, so every char is inline), inline keywords (<=5 bytes,
   no namespace). Everything else is a heap offset.
3. **GC** — young: copying semispace w/ age-based promotion. old: non-moving
   mark-sweep w/ segregated free lists. Old objects never move => remembered set
   and pointer fixup stay cheap. Roots: VM value stack + var table + const pool +
   an explicit shadow root stack for native code. Weak intern tables.
4. **data structures** — list/cons, PersistentVector (32-way trie + tail),
   HAMT map (ClojureDart-style collision/merge handling), array-map, set over map,
   transients for vector/map/set.
5. **hash + equality** — Clojure-compatible-ish hashing (murmur3 mix), `=` semantics.
6. **text-to-data, all in cljc** (see `doc/decisions/0002-modularity.md`):
   printer (`pr-str`/`str`), `clojure.edn` with reader tags, `flint.regex`,
   `flint.data.json`, `flint.data.xml`, `flint.data.html`. Same checkable
   manifest as every other namespace.
7. **numerics** — long/double tower, libm for clojure.math.
8. **regex** — own backtracking engine, no dependency, documented subset --
   written in **cljc** (`flint.regex`), so a program without regex literals does
   not carry it. `#"..."` literals hoist to a lazily-compiled top-level const.
9. **VM** — stack machine, bytecode, frames in one linear value stack (the root set),
   closures, multi-arity, varargs, try/catch/throw, tail calls, loop/recur.
   See `doc/decisions/0001-dispatch.md`. Carried forward from it:
   - interpreter over AOT-to-wasm *because* wasm locals are not scannable — the
     same constraint that fixed the rooting design. Argued in the README.
   - stack over register, to keep the bootstrap critical path short; the
     stale-slot-retains-references hazard of registers is a GC argument, not just
     an engineering one.
   - **opcode space is split up front**: 0x00-0x7F base ops, 0x80-0xFF reserved
     for fused superinstructions, so fusing later is not a format break.
   - dispatch cost gets its own benchmark, separate from data-structure cost, so
     the choice is a number and not an opinion.
10. **program image** — binary format: const pool + fn table + var table + entry.
11. **builtins + clojure.core/string/set/math/walk** written in cljc on top.
12. **compiler** — cljc: reader -> analyzer -> emitter, plus an AST evaluator so
    `defmacro` works at compile time on any host.
13. **bootstrap** — bb runs the compiler to compile the compiler; wasm patcher
    splices the program image into the runtime `.wasm` as a data segment. The
    splice design survives 0002 because tree-shaking happens in Tier 2, which
    is *in* the image, not in the prebuilt binary.
14. **fixpoint test**, conformance suite, benchmarks, README.

## Tiering (doc/decisions/0002-modularity.md)

Two tiers, and only one of them ships unconditionally.

- **Tier 1, Rust, in every module.** Only what cannot be expressed in the
  language: `mem`/`value`/`obj`/`gc`, hashing, equality/compare, the number
  tower and number<->string, UTF-8 and string primitives, the persistent
  collection internals and their transients, and the VM. This is *the floor*,
  and the README names it and reports its size.
- **Tier 2, cljc, tree-shaken.** Everything else, including all of
  `clojure.core`'s composite functions, `clojure.string`, `clojure.set`,
  `clojure.math` (thin over Tier-1 libm calls), `clojure.walk`, `clojure.edn`,
  the printer, the regex engine, and `flint.data.{json,xml,html}`.

Tree-shaking is **per var, not per namespace**: start from `:fn`, take the
transitive reachable set of vars (plus the implicit roots the emitter needs,
e.g. `apply`/`seq` for varargs and destructuring), compile only those.

The pull toward Rust is speed. The rule is: move something to Rust only with a
benchmark in hand, and keep the benchmark. A trivial `clojure.core` fn that is
exactly `(fn [a b] (flint.rt/prim a b))` is detected by the emitter and called
directly, so the wrapper layer costs nothing at the call site.

## Decisions that are expensive to get wrong (settled up front)

- **Rooting**: no stack scanning. The VM's value stack is a single contiguous
  `Vec<Value>` owned by the runtime struct; frames are windows into it. The GC is
  handed `&mut Runtime` and walks it precisely. Native Rust code that must hold a
  value across an allocation pushes it on `rt.roots` (shadow stack) via a guard.
- **Bootstrap**: babashka. Verified `bb` 1.3.190 runs deftype/defprotocol/
  defrecord/transients/`clojure.math`, and is fast enough (200k transient conj in
  16ms). The compiler will avoid deftype/defprotocol anyway and use plain maps,
  so the portable subset is small.
- **Embedding**: the runtime `.wasm` is built once; the CLI splices the compiled
  program image in as an extra active data segment placed at `__heap_base`, and a
  second segment overwrites an exported descriptor slot with (ptr,len). Verified
  that `rustc` exports `__heap_base` and named statics as wasm globals, so the
  patcher needs no symbol table.

## Decisions

- `doc/decisions/0001-dispatch.md` — interpreter vs AOT, and stack vs register.
  Read before building layer 9.
