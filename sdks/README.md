# SDKs

One directory per language, each with its own build command producing its own
distributable. They are siblings rather than one polyglot package because what
they have in common is small and what differs is everything else.

| | | |
| --- | --- | --- |
| [`esm/`](esm/) | JavaScript, any runtime | **built** |
| `rust/` | | not started |
| `c/`, `cpp/` | | not started |
| `csharp/` | | not started |
| `java/` | | not started |

## What every SDK is a wrapper around

The same two artifacts, built by `bin/build-dist`:

* **`flintc.wasm`** — the compiler. Clojure source in; a bytecode **image** or a
  standalone **module** out.
* **`flint-runtime.wasm`** — what a compiled module is spliced into, plus
  `flint-runtime-aot.wasm` carrying the compiled-arity helpers.

An SDK's job is to hand the compiler its input, carry the artifacts, and give
the host something idiomatic. The compiler is the same everywhere.

## Two kinds of SDK, and the split is not by language

**Through an existing wasm integration.** JavaScript, Rust, C, C++, Python and
Go all have a wasm runtime already, so the SDK is a thin wrapper: instantiate
the module, marshal strings, done.

**With its own runtime.** The JVM, the CLR and native each want flint's runtime
compiled FOR them rather than a wasm engine embedded in them — that is
`doc/decisions/0010`, and it is a port of the VM rather than a wrapper. Those
SDKs are much larger, and they are what makes the compiler's output useful
somewhere a wasm engine is a bad fit.

## The rule about artifacts

`dist/` is **generated**, never committed. Every SDK build starts by running
`bin/build-dist`, so a distributable can only ever contain artifacts built from
the source beside it.
