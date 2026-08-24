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
