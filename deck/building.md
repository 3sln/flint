# Building and testing

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
