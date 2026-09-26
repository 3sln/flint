<!-- BEGIN GENERATED COVERAGE -->
| namespace | vars | macros | missing vs Clojure | flint-only |
|---|---:|---:|---:|---:|
| `clojure.core` | 321 | 45 | 376 | 0 |
| `clojure.core.protocols` | 4 | 0 | n/a | n/a |
| `clojure.data` | 5 | 0 | n/a | n/a |
| `clojure.datafy` | 2 | 0 | n/a | n/a |
| `clojure.edn` | 2 | 0 | 1 | 1 |
| `clojure.math` | 32 | 0 | 14 | 1 |
| `clojure.set` | 12 | 0 | 0 | 0 |
| `clojure.string` | 23 | 0 | 0 | 2 |
| `clojure.walk` | 7 | 0 | 3 | 0 |
| `clojure.zip` | 28 | 0 | 0 | 0 |
| `flint.core` | 14 | 0 | n/a | n/a |
| `flint.data.html` | 12 | 0 | n/a | n/a |
| `flint.data.json` | 3 | 0 | n/a | n/a |
| `flint.data.transit` | 2 | 0 | n/a | n/a |
| `flint.data.xml` | 9 | 0 | n/a | n/a |
| `flint.doc` | 11 | 0 | n/a | n/a |
| `flint.port` | 13 | 1 | n/a | n/a |
| `flint.protocols` | 13 | 0 | n/a | n/a |
| `flint.protocols.io` | 17 | 0 | n/a | n/a |
| `flint.regex` | 11 | 0 | n/a | n/a |
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

*Absent:* 376 names -- see `doc/manifest.edn` for all of them.

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

#### `clojure.zip`

<!-- END GENERATED COVERAGE -->