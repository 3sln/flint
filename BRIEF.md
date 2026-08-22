# flint — a pure cljc logic executor, built for and distributed in wasm

You are building this from nothing, autonomously. This file is the whole brief.
Read it once, plan, then work. When you finish, `README.md` replaces this file as
the description of what exists.

## What it is

A compiler and runtime that turn **pure Clojure logic** into a **self-contained
wasm module** you can run anywhere — a browser, a worker, a server, an embedded
host. No JVM, no JS engine, no host runtime, no network. The module carries its
own heap, its own garbage collector, and its own copy of the core library.

The point is portability of *logic*. Somebody writes pure `.cljc`, and gets back
an artifact that runs identically wherever wasm runs.

## The interface, exactly

```
flint :src <the-src-dir> :fn the-namespace/the-fn
```

produces a wasm module. The module exports a `main` which is called with a
**vararg list of strings**. The named `:fn` must accept **exactly one argument**:
a vector of strings. `main` is the wrapper that converts the incoming varargs
into that vector and calls it.

Keep this interface. It is small on purpose.

## The runtime — bare Rust, compiled to wasm

- **`wasm32-unknown-unknown`.** `rustup target add wasm32-unknown-unknown` first.
- **Bare.** No full standard library — `#![no_std]` with `alloc` on your own
  allocator, or `std` with almost nothing pulled in, whichever you can defend.
  Link only the minimum that genuinely helps: UTF-8 handling, regex, and similar
  leaf utilities. Every dependency you add must be justified in the README.
- **64-bit values, NaN-boxed.** wasm32 pointers are 32 bits, which leaves the
  payload comfortable. Write down the encoding table in the README.
- **Strings are UTF-8.** Intern small strings. **Chars are tiny strings, always
  interned into the value itself (inlined)** — there is no separate char type.
- **Your own generational GC.** The heap lives in linear memory and nothing else
  manages it.

### The trap to solve first, not last

**wasm has no scannable stack.** A GC in linear memory cannot find roots by
walking the machine stack the way a native collector does. You must decide the
rooting strategy *before* you write the collector — a shadow stack, an explicit
handle/root registry, or a design where the interpreter's own value stack lives
in linear memory and *is* the root set. The last is usually the cleanest for an
interpreter and costs the least.

Get this wrong and everything above it has to be rewritten. Decide it in writing,
in the README, with the reasoning.

## Data structures — and they must be fast

Persistent list, vector, map, set. **Transients, and they need to be genuinely
fast** — the compiler is written in cljc and compiles itself, so transient
performance is on your own critical path, not a nice-to-have.

Borrow the good work that never landed in canonical Clojure because of backward
compatibility. **ClojureDart's map/merge work in particular is worth reading and
taking from.** We want compatibility with *portable* Clojure; we owe JVM Clojure
nothing.

## The core library

`clojure.core` (the pure parts), `clojure.string`, `clojure.set`, and the other
simple pure-data namespaces. Nothing with I/O, threads, agents, refs, or host
interop. If a function cannot be pure, it does not exist here.

**Also `clojure.math`, as much of it as comes easily.** It is a thin layer over
`java.lang.Math` and almost all of it is pure numeric work — `sqrt`, `pow`,
`sin`/`cos`/`tan`, `log`, `exp`, `floor`, `ceil`, `round`, `abs`, `atan2`,
`hypot`, the constants. In a bare wasm build the `libm` crate is the obvious way
to get these: pure Rust, `no_std`, no libc. That is exactly the kind of leaf
dependency the brief means by "the bare minimum needed to get some help".

Take the easy majority and stop where it stops being easy. Some of it is
genuinely awkward without the JVM's guarantees — exact rounding modes,
`ulp`, `nextAfter`, `IEEEremainder`, the `*Exact` overflow-checking integer
functions — and half-implementing those is worse than leaving them out.

### And our own data readers: `flint.data.json`, `flint.data.html`, `flint.data.xml`

Ours, not `clojure.data.*`. We are not bound to those APIs — but do not differ
gratuitously either: a Clojure programmer should be able to guess the shape.
`read-str` / `write-str` and a `:key-fn` are what people reach for, and there is
no prize for renaming them.

They belong here for the same reason EDN does: they are how data gets into a
script, and they are pure.

Two warnings. JSON numbers make no integer/decimal distinction, so decide what an
integer reads as versus a decimal and write it down. And **HTML is not XML** —
a spec-complete HTML5 parser is weeks of error recovery and implied tags. Build a
sane, documented subset that handles real-world markup, and say plainly in the
README where it gives up. That is worth far more than a half-finished attempt at
the full spec.

### Only what is needed goes into the build

**Do not glue the whole runtime together.** A module compiled from a program that
never mentions XML must not carry an XML parser. Keep it modular, and let the
entry point's reachable set decide what ships.

This cuts against the "build the runtime `.wasm` once, splice the program image
in" plan, and the tension is worth resolving deliberately rather than discovering:

- **Anything written in cljc tree-shakes for free.** Compile only the namespaces
  reachable from `:fn` and the problem solves itself.
- **Anything written in Rust is in the prebuilt runtime whether used or not** —
  unless the runtime is rebuilt per compile, or you keep feature-gated variants.

So the default should be: **write library namespaces in cljc**, and reserve Rust
for primitives that genuinely cannot be expressed in the language — allocation,
hashing, arithmetic, UTF-8, the collection internals. JSON, HTML, XML and much of
`clojure.string` are pure text-to-data work: they are exactly what this language
is FOR, and putting them in cljc dogfoods the compiler as a bonus.

Where something must be Rust-side and optional, say so and design for it — a
feature-gated runtime build, or a documented floor of what every module carries.
**Report module size in the benchmarks for a trivial program and a realistic
one**, so "modular" is a number rather than a claim.

### Say what is missing, per namespace

Every standard namespace you ship must come with a **stated deficiency list** in
the README: which functions are present, which are absent, and — where it
matters — which are present but differ from Clojure's behaviour and how.

This is not paperwork. Somebody porting pure logic to flint needs to know what
will fail before they try it, and "we implemented most of `clojure.string`" is
not something they can act on. A table per namespace, or a machine-checkable
manifest, is better than prose.

Prefer to make it CHECKABLE rather than written by hand: a test that reads the
list of what you claim to implement and asserts the runtime really exposes
exactly that will not drift, where a hand-maintained table in a README will.

## Getting data in: EDN

An **EDN reader with reader-tag support**, in the runtime. This is the primary
way data reaches a script. Tagged literals are part of the contract, not an
extra.

## The compiler — cljc, self-hosting

The compiler is written in portable `.cljc` and compiles itself.

**The bootstrap host is `babashka`.** I checked this machine: `bb` 1.3.190 is
installed and **there is no JVM** — `java` is the macOS stub that offers to
install one. So the seed path is: babashka runs the cljc compiler once to
compile the compiler, and from then on flint compiles flint.

Verify early that babashka can actually run the subset you need (`deftype`,
`defprotocol`, whatever you lean on). If it cannot, say so and choose the next
approach deliberately — a seed pass in Rust is the fallback, but it is a second
compiler implementation and the one that drifts, so avoid it if you can.

**A self-hosting fixpoint test is not optional**: compiler-compiled-by-bb and
compiler-compiled-by-flint must agree, and the second generation must reproduce
itself byte for byte.

## Toolchain on this machine, already checked

- `rustup` 1.27.1, `rustc`/`cargo` 1.92.0 (Homebrew). Only `aarch64-apple-darwin`
  installed — add the wasm target yourself.
- `bb` (babashka) 1.3.190. `clojure`/`clj` are present but **useless without a
  JVM**.
- **`node` v24 has `WebAssembly` built in.** Use it as the test host: zero
  install, and it is honest about "runs anywhere".
- No `wasmtime`, `wasm-pack`, `wabt` or `binaryen`. Install what you need via
  homebrew/cargo if it earns its place; prefer node for running tests.

## What "done" looks like

1. **It works.** `flint :src examples/ :fn demo/main` produces a `.wasm` that
   node loads and runs, and the answer is right.
2. **Tested well.** Rust unit tests for the value representation, the GC (including
   collection under pressure and generational promotion), and every data
   structure. A conformance suite that runs real Clojure expressions and checks
   the results. The self-hosting fixpoint test. Tests that fail loudly rather
   than skipping.
3. **Benchmarked.** Real numbers, in a table, with the method stated and the
   machine named. Transients vs persistents. Map/set operations at several sizes.
   GC pause behaviour. Module size. Cold start. Compare against something honest
   where you can.
4. **A comprehensive README** describing what it is, the value encoding, the GC
   design and its rooting strategy, the data structures and where they came from,
   the core library's coverage **including the per-namespace deficiency lists**,
   what every module carries as its floor and how much a program adds,
   the EDN reader, the compiler and its bootstrap,
   the benchmarks, and — importantly — **the limits**: what it does not support,
   what is slow, what is unfinished, where it differs from Clojure.

## How to work

- **Commit often**, with messages that explain *why*, not what.
- **Do not fake progress.** A test that is skipped, a benchmark that is estimated,
  a feature stubbed and reported as working — all worse than an honest gap. If
  something does not work, the README says so.
- **Sequence it so each layer is testable before the next.** Values → GC → data
  structures → core → EDN → interpreter/compiler → self-hosting → CLI.
- **Make sane decisions and write them down.** Where you hit a fork this brief
  does not settle, choose, and record the reasoning where somebody will find it.
- **If a decision in this brief turns out to be wrong, say so** in the README
  rather than quietly working around it. That is more useful than compliance.

Name the module output whatever reads well. The binary is `flint`.
