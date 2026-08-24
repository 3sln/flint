# What flint cannot do

## Limits

The honest list. Nothing here is stubbed and reported as working.

### Not implemented

- **Records and types.** No `deftype`, `defrecord`, `reify`, `extend-type`.
  `defprotocol`, `extend-protocol`, `extend` and `satisfies?` **do** exist and
  dispatch on kind or metadata — see
  [Protocols](#protocols-and-metadata-dispatch-as-the-main-road) — but there is
  no way to make a new *type*, so a port of code that leans on `defrecord` still
  needs reshaping into maps with metadata.
- **Transducers.** No `transduce`, `eduction`, `cat`, `completing`,
  `halt-when`, and no 1-arity transducer forms of `map`/`filter`/`take`/…. The
  eager and lazy forms all work.
- **Sorted collections.** No `sorted-map`, `sorted-set`, `subseq`, `rsubseq`.
  `sort` and `sort-by` work and are a stable merge sort.
- **Hierarchies.** Multimethods dispatch on `=` against the dispatch value with
  `:default` as the fallback. No `derive`, `isa?`, `parents`, `prefer-method`.
- **`eval` and `read-string`.** A flint module carries no compiler. `clojure.edn`
  is how text becomes data.
- **Var objects.** `#'x` is the value. No `with-redefs`, `alter-var-root` or
  `set!`. Dynamic vars and `binding` do exist, per green thread.
- **`letfn` is a macro over volatiles**, not a compiler feature, so mutually
  recursive local functions pay one indirection per call.
- **The numeric tower stops at i64 and f64.** No `BigInt`, `Ratio`, `BigDecimal`;
  no `+'`/`-'`/`*'`. Overflow throws, as Clojure's checked operators do.
- **Metadata on functions, numbers and inline values.** A closure, a number and
  a short string have nowhere to put it, so `with-meta` returns them unchanged.
  This matters more than it used to, because metadata is how protocols dispatch
  on user-defined abstractions: see the table under
  [Protocols](#what-can-carry-metadata-and-what-cannot). (It is also why
  `defmulti` keeps its method table in a second var.)
- **No host threads, agents, refs or host interop**, by design. Green threads
  and ports exist and are cooperative and deterministic; nothing here is
  parallel, and nothing preempts across a native call.
- **A capability cannot be delegated at run time.** Ports are not transferable
  and cannot be sent through a port, so a program cannot hand a resource it was
  lent to another part of itself over a channel — it has to pass the port by
  ordinary reference, within the runtime. That buys no ownership transfer to
  reason about, no capability leaking through a message, and a wire format that
  never has to represent a port. Transfer can be added later; it could not be
  removed. (`doc/decisions/0006`.)
- **A parked thread cannot be inside native code.** `map`, `sort`, a comparator
  and a lazy-seq force re-enter the interpreter with Rust frames underneath, and
  those are not a continuation anybody can save. Parking there is a clean error,
  not a corruption.
- **No I/O without a host.** A module still imports nothing; a port is the host
  *lending* a capability, and a host that grants nothing runs a pure program.

### Slow, and by how much

- **The regex engine is 86× slower than babashka's** on the word-frequency
  benchmark, and **275× slower than cherry-compiled JS** on the construe
  workload — see [What this means for construe](#what-this-means-for-construe),
  where it is the one result that would rule flint out of a job. It is a backtracking matcher written in cljc — which is what makes
  it tree-shake away when unused — using continuation closures, so it allocates
  per match step. A first-character skip cut a third off; the remaining cost is
  structural. [`doc/decisions/0002`](doc/decisions/0002-modularity.md) said to
  measure before moving something to Rust, and this is the measurement that
  would justify it: a Rust regex unit would cost nothing for programs that do
  not use one, because the unit mechanism already exists — and now that
  `clojure.string` no longer drags the engine in, `:exclude [flint.regex]` is a
  one-line way to prove a build does not depend on it either way. Not done: the
  two options in [Modularity](#modularity) came first, as the brief asked.
- **Everything else is ~2.5× babashka**, which is a reasonable place for an
  interpreter that brings its own collector.
- **`case` is O(n)**, a chain of `=`. Keys are usually keywords or short
  strings, which compare as a single 64-bit word, so the chain is cheaper than
  it looks — but it is not a jump table.
- **`seq` over a map or set is O(n) at the first call.** Bulk operations avoid
  it entirely.
- **Non-ASCII string indexing is O(n).** Strings record whether they are pure
  ASCII, and if they are, a code-point index is a byte index and `subs`/`nth`
  are O(1). For strings with any multi-byte character they walk. Fixing this
  properly needs an index or a rope, and nothing yet demands it. (Before the
  ASCII flag existed, splitting a string was quadratic and the word-frequency
  benchmark took 762 ms instead of 62 ms.)
- **Function values are poor map keys.** Identity hashing under a moving
  collector needs a stored hash; flint returns a per-type constant instead,
  which is correct but degrades those lookups to linear probing.

### Unfinished

- **`flint.data.html` is a documented subset, not HTML5.** It handles void
  elements, unclosed elements, an end tag closing the nearest match, and a named
  table of implied end tags (so `<p>a<p>b` and `<li>x<li>y` come out as
  siblings). It does **not** do implied `<html>`/`<body>`, `<table>` foster
  parenting, `<script>`/`<style>` raw-text mode, or the adoption agency
  algorithm for misnested inline elements. If you need a conforming parse tree,
  this is the wrong tool, and the namespace docstring says so before you start.
- **XML drops the declaration, comments, DOCTYPE and processing instructions**,
  and does not *resolve* namespaces — a prefixed name arrives as `:prefix/local`.
- **`format` is `%s`, `%d`, `%f` and `%%` only.** `%f` prints six decimal places
  as Java's does, but loses precision at very large magnitudes where Java's does
  not.
- **The linker driver is host-side only.** The compiler self-hosts; `flint.wasm`
  and `flint.link` run next to `rust-lld` and are not part of that requirement
  (a flint module has no processes to spawn).
- **Transit's caching is not implemented.** The writer never emits cache codes,
  so a message with many repeated keys is larger than a caching writer's would
  be. It is an optimisation rather than part of the data model, and a reader
  that ignores it still reads correct data.
- **A binary port's payload is a vector of byte-sized integers** on the flint
  side, because flint has no byte-array type. That is correct and it is slow:
  one boxed fixnum per byte through the codec. A `bytes` value type is the
  obvious fix and is not done.
- **The self-hosted compiler is slower than the bootstrap one**, which is
  expected: `--self` takes 2.5 s where babashka takes 0.17 s for the same
  program, most of it node startup plus flint being ~2.5× babashka. Both produce
  byte-identical modules. babashka remains the default for that reason.

### Where the brief turned out to be wrong

The brief says the EDN reader should be "in the runtime". It is not — it is a
cljc namespace. Putting it in Rust would have meant every module carried an EDN
reader whether or not it read EDN, which the same brief's modularity requirement
forbids. As a cljc namespace it tree-shakes per var, and it is available to a
running program exactly as intended. Recorded here rather than quietly worked
around.
