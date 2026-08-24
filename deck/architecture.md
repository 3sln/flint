# How it fits together

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
