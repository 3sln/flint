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
      [:exclude [ns ...]] [:wasm-ld <dir> ...]
```

`:exclude` drops namespaces, **built-in ones included** — and it is an ASSERTION
rather than a suggestion: if excluded code is genuinely reachable that is a
compile error naming the reference chain, never a module that ships and dies at
runtime. `:wasm-path` is a search path for **precompiled wasm namespace units**,
resolved by namespace the same way `:src` resolves source, by directory
hierarchy. `doc/decisions/0004-exclude-and-unit-path.md` is the decision.

## Green threads, ports, and protocols

A later phase, specified in `doc/decisions/0005-threads-and-ports.md`. The short
version, because one point governs the rest:

**`open` must not block wasm — it parks a green thread.** A synchronous wasm
export cannot be suspended, and the usual escapes (JSPI, Asyncify) cost either
portability or size on every function forever. We need neither, because flint is
an INTERPRETER: a green thread is a VM state, the scheduler picks runnable ones,
and "blocking" means "not runnable yet". Nothing suspends a wasm frame.

Ports carry data and other ports, by value, with back-pressure. Within one
runtime, passing by reference is a sound optimisation **because flint values are
immutable** — which is the property that makes this cheap here.

Protocols are the basis for all polymorphism, and since flint has no types,
**metadata dispatch is the main road rather than a corner feature**.

And none of it may grow a pure module: threads and ports are namespace units like
any other, absent unless reached.

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

Ours, not `clojure.data.*`. We owe those APIs nothing — but do not differ
gratuitously: a Clojure programmer should be able to guess the shape.

**Adapt existing Rust crates rather than writing parsers.** Two constraints on
the choice: we are `no_std`-ish with our own allocator, so prefer crates that
work with `alloc` and no `std`; and **use the streaming/event API to build flint
values directly** rather than materialising the crate's own document tree and
converting it — that is two allocations of everything and drags in the parts of
the crate we least want.

HTML is the one to be careful about. Spec-complete HTML5 parsing is weeks of
error recovery and implied-tag rules; take a crate that already did that work, or
document the subset honestly. Do not half-write one.

### Only REACHABLE code ships — built-ins included

A module compiled from a program that never mentions XML must not carry an XML
parser. Not "should mostly not": must not, and there should be a **test that
asserts it** rather than a claim in prose.

**A namespace is a compilation unit.** Each one is precompiled — Rust namespaces
to a relocatable wasm object, cljc namespaces to a bytecode image fragment — and
`flint` links only the reachable set into one module with `wasm-ld
--gc-sections`. No `rustc` on the compile path, true reachability rather than a
discipline nothing enforces, and a single module out.

`doc/decisions/0003-namespace-units.md` is the decision, and **the crux is that
the builtin registry must be assembled BY THE LINKER**: if the runtime holds a
static table naming every builtin, that table is itself the reference keeping
them all live and `--gc-sections` removes nothing. Each namespace object
contributes its entries to a section the runtime walks at startup.

That is a property of how the runtime finds its builtins at all, so settle it
long before the parsers arrive.

**And this is a composition system, not a tree-shaking trick** — the built-ins
are its first customer. Keep "built-in" out of the unit format and independently
compiled user namespaces, incremental builds and distributable pre-compiled
libraries all become the same feature later rather than a rewrite. You are not
asked to build that now, only not to make it impossible.

**Report module size in the benchmarks for a trivial program and a realistic
one**, so "modular" is a number rather than a claim, and name the unavoidable
floor in the README honestly.

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
