# Changelog

Notable changes, newest first. Versions follow [semver](https://semver.org);
before 1.0 the minor version is where breaking changes live.

Every claim here is a measurement or a test, not a summary of intent. Where a
number appears, `bin/test` or a named script produces it.

## 0.0.1 — unreleased

The first release. flint compiles pure Clojure (`.cljc`) to a self-contained
wasm module with its own heap, collector and core library, and runs the same
bytecode on the JVM and the CLR.

### The compiler

- **Self-hosting.** babashka compiles the compiler once; from then on flint
  compiles flint, and the images are byte-identical. Generation 2 reproduces
  itself exactly (`test/selfhost.clj`).
- **Only reachable code ships.** A program that never mentions XML contains no
  XML parser, asserted by symbol name rather than by prose
  ([`0002`](doc/decisions/0002-modularity.md)).
- **`:exclude` is an assertion, not a pruning**, and `:wasm-path` is a search
  path ([`0004`](doc/decisions/0004-exclude-and-unit-path.md)).
- **`:optimize` is an ordered preference**, not a switch. `[perf]` compiles
  arities ahead of time; `[size]` is a pure interpreter; unrecognised tokens are
  stepped over so a list written against a newer flint still gets this one's
  best effort.

### Runtimes

- **wasm/native**, the reference implementation: NaN-boxed values, a generational
  moving collector with a write barrier, green threads and ports.
- **The JVM** ([`0029`](doc/decisions/0029-jvm-runtime.md)) and **the CLR**
  ([`0030`](doc/decisions/0030-clr-runtime.md)). Both carry all 155 builtins,
  both agree with the native runtime on all nine conformance programs
  interpreted and compiled, and **both run the compiler and emit byte-identical
  images**.
- **Ahead-of-time compilation** behind `:optimize [perf]`
  ([`0013`](doc/decisions/0013-emit-wasm-instead-of-dispatch.md)): 2.97× over the
  wasm interpreter, 12× over the JVM's, 1.8× over the CLR's. One config; each
  runtime honours it the way it can, and the image carries the decision so the
  ports can act on it.

### Concurrency

- **Green threads and ports** ([`0005`](doc/decisions/0005-threads-and-ports.md),
  [`0006`](doc/decisions/0006-host-abi.md)). Nothing suspends a wasm
  frame — no JSPI, no Asyncify — and a program that never mentions them produces
  a module *smaller* than before they existed.
- **The scheduler is deterministic**: five runs, one answer.
- **`binding` is a stack discipline per green thread**, and a spawn inherits a
  snapshot.
- **`swap!` is atomic** — a retry loop over `compare-and-set!`, verified under
  contention on all three runtimes.

### Library and data

- **Ropes** ([`0011`](doc/decisions/0011-strings-and-matching.md)): `str` is a
  tree join, so repeated concatenation is not quadratic. Against Clojure 1.12.1
  on JDK 25, string building is **8× faster** — an algorithm, not a constant.
- **A Pike VM for regex** ([`0012`](doc/decisions/0012-matching-over-ropes.md)), with
  catastrophic backtracking bounded exactly.
- **Byte strings and their transient**
  ([`0024`](doc/decisions/0024-no-runtime-linking.md)), carrying a binary port's
  payload. Encoding and decoding 400 Transit records: 55.5 ms to 39.4 ms,
  198 728 allocations to 104 951, 11.2 MB allocated to 4.1 MB, same bytes on the
  wire.
- **EDN, JSON, XML, HTML and Transit** codecs, each a namespace unit that only
  ships when reached.
- **VM snapshots** ([`0015`](doc/decisions/0015-snapshots.md)) in two formats,
  because there are two jobs. A verbatim copy of the heap, for post-mortems —
  the one capture that still works when the pointers are already wrong. And a
  **live set**, walked rather than copied, for shelving a running sandbox: the
  same state is 38 524 bytes against 5 275 808, and it imports into an instance
  that has never run. Both carry the fingerprint of the image they belong to and
  refuse a mismatch by name, because a snapshot holds no code and every index in
  one means something only against the program it came from.

### Tooling

- **SDKs** for JavaScript (through any wasm engine), Rust and C/C++ (against the
  runtime compiled natively) — the same five nouns in all three
  ([`0010`](doc/decisions/0010-other-hosts.md)), with a driver deciding
  how many threads a sandbox gets ([`0028`](doc/decisions/0028-drivers.md)).
- **Resource limits** ([`0009`](doc/decisions/0009-resource-limits.md)): gas, memory,
  and the same program costing the same every time. Two builds, so production
  carries none of the diagnostic machinery that measures it
  ([`0016`](doc/decisions/0016-two-builds.md)).
- **`flint check`**, which reports metadata flint ignores rather than failing on
  it.
- **A module says what it is without being instantiated**
  ([`0020`](doc/decisions/0020-module-metadata-and-shards.md)).

### Fixed late, and worth naming

- **A Rust `Vec<Value>` is not a root**
  ([`0031`](doc/decisions/0031-a-vec-of-values-is-not-a-root.md)). `array-map`
  gathered values into a Rust vector across calls that can collect, so a large
  map literal built from a lazy seq wrote stale pointers into the heap. It broke
  self-hosting. The same fault was found in the codec by reading for the shape
  rather than the symptom.
- **Two AOT resume-point bugs** ([`0013`](doc/decisions/0013-emit-wasm-instead-of-dispatch.md)).
  A resume point is a pair — a bytecode offset and the block it names — and both
  faults carried one half and inferred the other. A tail call named a resume
  point it could not have; a thread restore paired a saved block with the wrong
  offset.

### Known limits

Named in full in the README's [Limits](README.md#limits). The ones most likely
to matter: no records or types (protocols dispatch on kind or metadata), no
transducers, no sorted collections, no `eval` at run time, and a regex engine
12× slower than babashka's.
