# Introduction

Flint is a Clojure-like language with its own runtime, implemented four times
over — native Rust, WebAssembly, JVM (Java) and CLR (C#) — from one shared
source tree. A flint program is written in a `.cljc`-flavoured dialect, compiled
by a small compiler that is itself written in flint, and run either by the
native binary, embedded in a host via an SDK, or shipped as a standalone `.wasm`
module.

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

The module in that example carries its own heap, its own garbage collector, its
own copy of the core library, and the bytecode of the program — it imports
nothing. Give it to a browser, a worker, a server, an embedded host, anything
with a WebAssembly engine, and it computes the same answer. The same source
also runs directly, with no `.wasm` step at all, through `flint run` (see the
[CLI](cli.md)) or through one of the four runtimes.

## Why it exists

Flint's premise is portability *of logic*: somebody writes pure `.cljc`, and
gets back an artifact — a wasm module, a JVM class, a CLR assembly, or a run
against the native interpreter — that behaves identically wherever it lands. A
JVM or JS runtime is not a neutral substrate for that: it carries a garbage
collector, a number tower, string encoding and threading model that are the
host's, not the program's. Flint brings all of that with it. Two programs are
"the same" only if the numbers, the string comparisons and the hashes agree on
every host — so flint defines those independent of any one host's conventions
rather than borrowing the JVM's (see [Where flint differs from Clojure](language.md#where-flint-differs-from-clojure)).

The other premise is resource control as a first-class feature rather than an
afterthought. Because flint is a bytecode interpreter with its own scheduler,
an embedding host can bound a running program by *instructions and bytes*
rather than by wall-clock time — a reproducible fact rather than a flaky
timeout — and can grant it *capabilities* rather than ambient authority: a
sandboxed program can do nothing beyond compute until a host lends it a port.
See [Capabilities and the sandbox](capabilities-and-the-sandbox.md).

## How it differs from Clojure, in one paragraph

If you know Clojure, most of what you already know reads and runs unchanged:
immutable persistent collections, the reader, `let`/`fn`/`loop`/`recur`,
multi-arity `defn`, destructuring, protocols, atoms, `->`/`->>`, and most of
`clojure.core`, `clojure.string`, `clojure.set`, `clojure.walk`, `clojure.zip`,
`clojure.data`, `clojure.datafy` and `clojure.edn`. What's different follows
from flint having its own runtime rather than the JVM's: there are no host
types (`deftype`/`defrecord`/`reify` don't exist — protocols dispatch on a
closed set of value *kinds* or on metadata instead), no ratios or bignums
(arithmetic is `i64`/`f64` and overflow throws rather than promoting or
wrapping), strings are UTF-8 and `count`/`nth`/`subs` index by code point
rather than UTF-16 unit, and concurrency is cooperative green threads with
message-passing ports rather than host threads, futures, refs or agents. The
full, evidence-backed list is in [The language](language.md).

## What's here

- **[The language](language.md)** — syntax, data types, the standard library
  surface, and where flint deliberately diverges from Clojure, with reasons.
- **[The CLI](cli.md)** — `flint run`, `flint compile`, `flint test`,
  `flint deps`, and the flags that go with them.
- **[Capabilities and the sandbox](capabilities-and-the-sandbox.md)** — how a
  program is granted authority to do anything beyond computing, and what a
  port is.
- **[Concurrency](concurrency.md)** — green threads, ports and channels.
- **[The SDKs](sdks.md)** — how a host application embeds flint.

There's also a set of cards written earlier that go deeper into the runtime's
own internals — [architecture](architecture.md), [the value
encoding](values.md), [the collector](collector.md), [the
interpreter](interpreter.md), [modularity](modularity.md), [the
compiler](compiler.md), [resource limits](limits-api.md), [what flint cannot
do yet](limits.md), [benchmarks](benchmarks.md) and [building from
source](building.md) — for readers who want to know how flint is built rather
than only how to use it.
