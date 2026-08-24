# Clojure coverage

## Library coverage

Every namespace flint ships comes with a stated deficiency list. The lists are
**generated and checked**, not written: `bin/manifest` derives them from the
source and by diffing against real Clojure's `ns-publics`, and
`test/manifest.clj` regenerates them, compiles a program referencing every var
they claim present, runs it, and expands every macro they claim present. A false
claim fails the build.

<!-- BEGIN GENERATED COVERAGE -->
| namespace | vars | macros | missing vs Clojure | flint-only |
|---|---:|---:|---:|---:|
| `clojure.core` | 341 | 45 | 310 | 27 |
| `clojure.edn` | 2 | 0 | 1 | 1 |
| `clojure.math` | 32 | 0 | 14 | 1 |
| `clojure.set` | 12 | 0 | 0 | 0 |
| `clojure.string` | 23 | 0 | 0 | 2 |
| `clojure.walk` | 7 | 0 | 3 | 0 |
| `flint.data.html` | 12 | 0 | n/a | n/a |
| `flint.data.json` | 3 | 0 | n/a | n/a |
| `flint.data.xml` | 9 | 0 | n/a | n/a |
| `flint.doc` | 11 | 0 | n/a | n/a |
| `flint.port` | 13 | 1 | n/a | n/a |
| `flint.port.edn` | 3 | 0 | n/a | n/a |
| `flint.port.json` | 3 | 0 | n/a | n/a |
| `flint.port.transit` | 3 | 0 | n/a | n/a |
| `flint.regex` | 10 | 0 | n/a | n/a |
| `flint.rpc` | 6 | 0 | n/a | n/a |
| `flint.thread` | 8 | 0 | n/a | n/a |

Full lists, machine readable, in [`doc/manifest.edn`](doc/manifest.edn).
`test/manifest.clj` regenerates that file and fails if it differs, compiles a
program referencing every var it claims present, runs it, and expands every
macro it claims present. A false claim fails the build.

#### `clojure.core`

Absent for a reason, by group: I/O (`print`, `slurp`, `read-line`, `*out*`);
concurrency (`future`, `agent`, `ref`, `promise`, `send`, `locking`, `deliver`);
mutable references beyond atoms (`ref`, `var-set`, `alter-var-root`, `binding`, `with-redefs`);
host interop (`class`, `instance?`, `proxy`, `bean`, `aget`, `make-array`, every `*-array`);
reflection and namespace surgery (`ns-publics`, `resolve`, `intern`, `find-var`, `the-ns`);
a compiler at runtime (`eval`, `read-string`, `load-string`, `macroexpand`);
nondeterminism (`rand`, `shuffle`, `random-uuid`, `random-sample`);
the numeric tower flint does not have (`bigint`, `bigdec`, `ratio`, `numerator`, `+'`, `-'`, `*'`);
protocols and types (`defprotocol`, `deftype`, `defrecord`, `reify`, `extend`, `satisfies?`);
hierarchies (`derive`, `isa?`, `parents`, `prefer-method`);
transducers (`transduce`, `eduction`, `cat`, `completing`, `halt-when`, and the 1-arity transducer forms of `map`/`filter`/`take`/...);
and sorted collections (`sorted-map`, `sorted-set`, `subseq`, `rsubseq`).

*Added by flint:* `->str-builder` `apply2` `bigdec?` `bytes->str` `cond-chain` `count-matching` `find-protocol-method` `int-of-char` `interleave-all` `interleave2` `keep2` `kind` `map2` `mapcat2` `methods-of` `nil-or` `println-str` `re-quote-replacement` `repeat-forever` `repeat2` `sb-append!` `sb-str` `spread` `str-bytes` `str-join` `subvec2` `volatile?`

*Absent:* 310 names -- see `doc/manifest.edn` for all of them.

#### `clojure.edn`

`read` takes a stream;
flint has no stream type. `read-string` is the whole surface, plus `read-all` which flint adds for the same reason.

*Absent:* `read`

*Added by flint:* `read-all`

#### `clojure.math`

`ulp`, `nextAfter`, `nextUp`, `nextDown`, `IEEEremainder`, `getExponent`, `scalb` and the `*Exact` integer functions are absent deliberately: they need rounding-mode and exponent guarantees flint does not have, and the brief is right that half-implementing them is worse than leaving them out. `random` is absent because it is not pure.

*Absent:* `IEEE-remainder` `add-exact` `decrement-exact` `get-exponent` `increment-exact` `multiply-exact` `negate-exact` `next-after` `next-down` `next-up` `random` `scalb` `subtract-exact` `ulp`

*Added by flint:* `abs`

#### `clojure.set`

#### `clojure.string`

*Added by flint:* `register-regex-ops!` `split-literal`

#### `clojure.walk`

`macroexpand-all` needs a compiler at runtime, and a flint module carries none. `postwalk-demo`/`prewalk-demo` print.

*Absent:* `macroexpand-all` `postwalk-demo` `prewalk-demo`

<!-- END GENERATED COVERAGE -->

## Where flint differs from Clojure

These are behaviour differences, not absences — code that compiles but does
something else. Each one is a case in `test/conform/basics.cljc` carrying *both*
answers, so the differential test against real Clojure stays green and this list
cannot go stale.

| | Clojure | flint |
|---|---|---|
| `\a` | a `Character` | the string `"a"` |
| `(nth "abc" 1)` | `\b` | `"b"` |
| `(count "aé😀")` | `4` (UTF-16 units) | `3` (code points) |
| `(subs s a b)`, `(nth s i)` | UTF-16 indices | code-point indices |
| `(/ 1 2)` | `1/2`, a `Ratio` | `0.5`, a double |
| `(hash \a)` | `97` | `1455541201` — the hash of `"a"` |
| `case` | O(1) jump table | O(n) chain of `=` |
| map literal value order | source order | source order (but see below) |
| `(into {} …)` on ≤8 entries | insertion-ordered array-map | hash map, unordered |
| `clojure.string/split` | regex only | regex **or** a literal string |
| protocol dispatch | on type, with `extend-via-metadata` as an opt-in corner | on **kind** or **metadata** — there are no types, so metadata is the main road |
| `binding` | per host thread | per **green** thread; a spawn inherits a snapshot |
| a port | — | not transferable, and cannot be sent through a port |

Three of those need more than a row:

**`(/ 1 2)` is `0.5`.** flint has no `Ratio`, so inexact integer division yields
a double. `(* 3 (/ 1 3))` is `1.0` here and `1` in Clojure. `quot` and `rem` are
exact and behave as Clojure's. This is the most visible numeric divergence.

**Protocols dispatch on kind or metadata.** flint has no types, so there is
nothing for `extend-type` to name. See [Protocols](#protocols-and-metadata-dispatch-as-the-main-road).

**Small-map ordering.** flint preserves the source order of a map *literal* (the
reader builds an insertion-ordered array-map, which is what lets the compiler
compile itself deterministically), but `(into {} …)` goes through a transient,
and a transient map does not preserve insertion order. Clojure's array-map
happens to. Neither language *promises* an order beyond the literal, but code
that relies on it will see a difference in `keys`, `seq` and `pr-str` output.

Also worth knowing:

- `#""` regex: `.` matches any character **including** newline, where Java's
  default does not.
- `seq` over a map or set materialises the entries into a vector once, then walks
  it: O(n) at the first `seq`, O(1) per step. Bulk operations (`reduce`, `into`,
  `merge`, `count`, `map`) walk the trie directly and never build it.
- Reader conditionals default to the feature set `#{:flint}`. flint is not the
  JVM, so a `:clj` branch would be host interop it cannot compile. Ported code
  needs a `:flint` or `:default` branch.
- `(var x)` / `#'x` yields the *value*, not a Var object. There are no Var
  objects, so no `alter-var-root` and no `with-redefs`. `binding` **does** work,
  on vars defined `^:dynamic`; it is a stack discipline per green thread rather
  than anything to do with Var objects.
- A bare top-level expression (not a `def`) is compiled if its namespace is
  reached at all, because there is nothing to reach it *by*. `defmethod` relies
  on this.
