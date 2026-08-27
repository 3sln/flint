# SDKs

One directory per language, each with its own build command producing its own
distributable. They are siblings rather than one polyglot package because what
they have in common is small and what differs is everything else.

| | | | |
| --- | --- | --- | --- |
| [`esm/`](esm/) | JavaScript, any runtime | wasm engine | **built** — `sdks/esm/build` |
| [`rust/`](rust/) | Rust | native | **built** — `cargo test -p flint --release` |
| [`c/`](c/) | C and C++ | native | **built** — `sdks/c/build` |
| `csharp/` | | CLR port | not started |
| `java/` | | JVM port | not started |

`c/` is one directory for both languages because it is one library: `flint.h`
is the ABI, `flint.hpp` is a header over the same symbols adding RAII and
exceptions, and there is no second implementation to keep in step.

## The shape they share

Every SDK is the same four nouns (`doc/decisions/0025`):

* a **resolver** — namespace to source. There is no filesystem in any SDK, so a
  caller can compile out of a database, a zip, or a string.
* a **Compiler** — with `fn`, `exports`, `optimize` and arbitrary `meta`.
* an **Image** — the artifact, plus what the compiler was told to record.
* a **Sandbox** — `call(fn, args)`, `grant`, a step limit, and gas.

A fifth is coming and will be in every SDK by the same names: a **Driver**,
which owns how many threads a sandbox gets and when a runnable one runs
(`doc/decisions/0028`). Targets differ in what they can honour — native gets
several threads in one sandbox first, wasm stays at one for now — so a
`ThreadPool(4)` on a single-threaded target hands back a driver whose
parallelism reads 1. Ask for what you want, read what you got. Same rule as
`:optimize`.

`call` takes a function name and positional arguments and nothing more. An
argument map, capabilities-as-arguments and `--` are a CLI's conventions, not
the SDK's.

## What every SDK is a wrapper around

The same two artifacts, built by `bin/build-dist`:

* **`flintc.wasm`** — the compiler. Clojure source in; a bytecode **image** or a
  standalone **module** out.
* **`flint-runtime.wasm`** — what a compiled module is spliced into, plus
  `flint-runtime-aot.wasm` carrying the compiled-arity helpers.

An SDK's job is to hand the compiler its input, carry the artifacts, and give
the host something idiomatic. The compiler is the same everywhere.

## Two kinds of SDK, and the split is not by language

**Through an existing wasm engine.** JavaScript has one in every runtime it
ships in, so `esm/` instantiates the module and marshals across the boundary.

**Against the runtime compiled natively.** flint's runtime is Rust, so it
already compiles through LLVM for every target cargo has — the collector, the
interpreter and every builtin are the same code the wasm module is built from
(`doc/decisions/0010`). `rust/` and `c/` take that road and carry no wasm
engine at all, which is why they are smaller and start faster despite doing
more. They still READ a `.wasm` artifact: the module carries its program as a
data segment, and running it is a matter of finding it rather than of executing
wasm.

**By porting the VM.** The JVM and the CLR want the runtime written for them.
That is a much larger job than either of the above and is what makes the
compiler's output useful where neither a wasm engine nor LLVM fits.

## The rule about artifacts

`dist/` is **generated**, never committed. Every SDK build starts by running
`bin/build-dist`, so a distributable can only ever contain artifacts built from
the source beside it.
