# The language

Flint reads like Clojure because it mostly is: the reader, `def`/`defn`/`fn`,
`let`/`loop`/`recur`, destructuring, multi-arity functions, variadics,
`->`/`->>`, immutable persistent vectors/maps/sets/lists, atoms, protocols and
multimethods, and reader conditionals (`#?(:flint ...)`) all work as they do in
Clojure. This card covers the data types and syntax, the standard library
surface, and — in detail, because it's the part worth being careful about —
where flint deliberately diverges from Clojure and why.

Every example below is copied verbatim from flint's own test suite
(`test/common/lang/*.cljc`) or from a library docstring, not invented.

## Data types

**Numbers** are `i64` fixnums and `f64` doubles — there is no `Ratio`,
`BigInt` or `BigDecimal`, and no `+'`/`-'`/`*'`. `(/ 1 4)` is `0.25`, a double,
not a ratio. `quot` and `rem` are exact. Arithmetic **overflow throws** rather
than wrapping or promoting:

```clojure
(let [most-negative (- -9223372036854775807 1)]
  (expect = "integer overflow"
          (try (quot most-negative -1) nil (catch Throwable e (ex-message e))))
  (expect = "integer overflow"
          (try (/ most-negative -1) nil (catch Throwable e (ex-message e))))
  (expect = "integer overflow"
          (try (- most-negative) nil (catch Throwable e (ex-message e)))))
```
— `test/common/lang/numbers.cljc`. This is deliberate: the same expression has
to mean the same thing on all four runtimes, and the JVM's `long` wraps
silently on overflow while the CLR raises a non-flint `OverflowException` —
neither is a fact about the *program*, both are facts about the host. Flint
picks one behavior and makes it a checked, catchable `Throwable` everywhere.

**Strings** are UTF-8 and stored in one of four internal shapes (inline in the
value, interned on the heap, plain heap, or a rope for long concatenations) —
invisible to a program; the same operation gives the same kind of answer
regardless of which shape a string happens to be in. `count`, `nth` and `subs`
index by **code point**, not by UTF-16 unit or byte:

```clojure
(expect = 0 (count ""))
(expect = 3 (count "abc"))
(expect = 5 (count "hello"))
```
— and on a string with astral or multi-byte characters, flint's `count`
disagrees with Clojure's: `(count "aé😀")` is `3` in flint (three code points)
against `4` in Clojure (UTF-16 units). See [Where flint differs from
Clojure](#where-flint-differs-from-clojure).

**Collections** are the familiar persistent vector/map/set/list, plus lazy
seqs and ranges — all reporting kind `:list` under `flint.rt/kind` (see
[Protocols](#protocols)). `assoc` on a vector stays a vector:

```clojure
(let [v [:a :b :c]
      w (assoc v 1 :B)]
  (expect = [:a :B :c] w)
  (expect vector? w)
  (expect false? (map? w))
  ;; an index equal to the count appends rather than throwing
  (expect = [:a :b :c :d] (assoc v 3 :d)))
```
— `test/common/lang/collections.cljc`. Small maps (up to 8 entries) are an
insertion-ordered array-map; larger ones are CHAMP hash maps. A map entry
(what `(first (seq {:a 1}))` gives you) is a vector, as in Clojure —
`vector?` is true, it prints `[:a 1]`, and `conj` appends rather than
prepends.

**Truthiness**: only `nil` and `false` are falsy. `0`, `""` and `[]` are all
truthy — a runtime that borrowed its host's notion of falsy would fail this:

```clojure
(expect false? (if nil true false))
(expect false? (if false true false))
```

**Destructuring** works as in Clojure, vectors and maps, with `:or` defaults:

```clojure
(let [[a & r] [1 2 3]] (expect = [1 [2 3]] [a (vec r)]))
(let [{:keys [a b]} {:a 1 :b 2}] (expect = [1 2] [a b]))
(let [{a :a :or {a 9}} {}] (expect = 9 a))
```
— `test/common/lang/functions.cljc`.

**Tagged literals** (`#my.ns/thing [1 2]`) are their own value kind
(`:tagged`), not a two-key map — deliberately, so a codec can tell a tagged
literal apart from a map that happens to have `:tag`/`:form` keys
(the `tagged-literals` decision, shipped):

```clojure
(let [t (tagged-literal 'my.ns/thing [1 2])]
  (expect = 'my.ns/thing (tag t))
  (expect = [1 2] (form t))
  (expect = 'my.ns/thing (:tag t))   ; reads like a map for convenience
  (expect = 2 (count t))
  (expect false? (map? t)))          ; but genuinely isn't one
```
— `test/common/lang/tagged.cljc`. `#flint/table` is a built-in reader tag; a
project can bind its own tag names to reader functions in `deps.edn`, scoped
per-project so two libraries can each own `#x` without colliding
(the `reader-tags` decision — partly shipped:
an unknown tag is a compile error, and per-project tag binding works; a
runtime `reader-tag-of` lookup is not yet built).

## Protocols

Flint has no `deftype`, `defrecord`, `reify` or `class` — there are no host
types to extend. Protocol dispatch instead checks, in order, a value's
**metadata**, then its **kind** — a small, closed set of tags returned by
`(flint.rt/kind x)`. `lib/flint/core.cljc`'s docstring for `kind` names
`:nil :boolean :number :string :keyword :symbol :vector :map :set :list :fn
:port :thread :atom :var :regex :exception :other`; the set has grown since —
`test/common/lang/tagged.cljc` confirms `(flint.rt/kind (tagged-literal ...))`
is `:tagged`, for instance — so treat this as a representative sample of a
closed set rather than an exhaustive one:

```clojure
(defprotocol Greet
  (greet [x] "A greeting, however this value gives one."))

(extend-protocol Greet
  :string (greet [s] (str "hello " s))
  :number (greet [n] (str "hello #" n))
  :vector (greet [v] (str "hello " (count v) " things")))

(greet "world")  ;; => "hello world"
(greet 7)        ;; => "hello #7"
(greet [:a :b])  ;; => "hello 2 things"
```
— `test/common/lang/protocols.cljc`. Checking metadata first is what lets an
*ordinary value* — a plain map or vector with metadata attached — get its own
implementation with no new type at all:

```clojure
(def circle (with-meta {:r 2} {:shapes/area (fn [s] (* 3 (:r s) (:r s)))}))
(area circle)  ;; => dispatches via metadata, not kind
```

A value with no implementation fails with a message naming the protocol, the
kind, and what to do about it — "extend the protocol to that kind, or attach
`:shapes/area` as metadata on the value." Kind is a closed set on purpose: a
short string, a long string and a concatenated string are three different
internal representations reporting one kind (`:string`), because dispatch has
to agree regardless of how a value happened to be built. Only values that can
carry metadata (vectors, maps, sets, lists/seqs, symbols, atoms) get to use
the metadata road; inline values (short strings, unqualified keywords, chars,
numbers, `nil`) and interned heap strings/keywords have nowhere to put it and
fall back to kind dispatch.

`extend-protocol` can be written from a namespace other than the one that
declared the protocol (`test/common/lang/extending.cljc`), and
`flint.protocols/extend-method` (moved out of `clojure.core`, so it must be
required explicitly) attaches one method at a time.

## The standard library surface

`clojure.*` namespaces are ported from Clojure, closely enough that the
differences are usually the interesting part: `clojure.core`, `clojure.string`,
`clojure.set`, `clojure.walk`, `clojure.zip`, `clojure.data`, `clojure.datafy`,
`clojure.edn`, `clojure.math`. Absent by group, and deliberately: I/O
(`print`, `slurp`, `read-line`), concurrency primitives other than atoms and
green threads (`future`, `agent`, `ref`, `promise`), host interop (`class`,
`instance?`, `aget`), a compiler at runtime (`eval`, `read-string`,
`macroexpand`), the numeric tower flint doesn't have (`bigint`, `bigdec`,
`ratio`, `+'`), types (`defrecord`, `deftype`, `reify`), hierarchies
(`derive`, `isa?`), transducers, and sorted collections.

`flint.*` namespaces are flint-specific — real library code in `lib/flint/`,
each with its own docstring explaining what it's for:

- **`flint.core`** — primitives with no Clojure counterpart: `opaque`/
  `opaque?` (capability values, see
  [Capabilities and the sandbox](capabilities-and-the-sandbox.md)), `kind`,
  `tag`/`form` (for tagged literals), `str-join`, `str-bytes`.
- **`flint.thread`** — green threads: `spawn`, `yield`, `join`. See
  [Concurrency](concurrency.md).
- **`flint.port`** — channels and host-backed ports: `channel`, `open`,
  `send`, `receive`. See [Concurrency](concurrency.md).
- **`flint.host`** — the general "ask the host for a value" primitive,
  `request`/`ask`, gated by a per-workspace capability guard.
- **`flint.rpc`** — request/response messaging over a port.
- **`flint.bytes`** — byte strings, structured like text ropes (flat/tree
  tiers) rather than a vector of boxed integers.
- **`flint.table`** — columnar tables with a closed schema that present as a
  vector of maps from the outside.
- **`flint.regex` / `flint.nfa` / `flint.pike`** — the shared regex engine (an
  NFA compiler and a Pike VM), so matching behaves identically on every
  runtime rather than delegating to each host's own regex dialect.
- **`flint.snapshot`** — whole-VM-state capture and restore, as bytes.
- **`flint.check`** — the `expect`/`^:flint.check/test` machinery behind
  `flint test` (see [The CLI](cli.md)); stripped entirely by
  `:optimize [perf]`, so it costs nothing in a production build.
- **`flint.doc`** — document resources, structured eagerly with content
  fetched on demand.
- **`flint.fs`** — the language-level filesystem namespace (distinct from the
  CLI-served `flint.sys.fs` — see [Capabilities and the
  sandbox](capabilities-and-the-sandbox.md)).

## Where flint differs from Clojure

Each row below is backed by a real test — most are cases in flint's own
conformance suite that carry *both* answers, so a differential test against
real Clojure stays green and this table can't quietly go stale.

| | Clojure | flint |
|---|---|---|
| a char literal `\a` | a `Character` | the string `"a"` |
| `(nth "abc" 1)` | `\b` | `"b"` |
| `(count "aé😀")` | `4` (UTF-16 units) | `3` (code points) |
| `(subs s a b)`, `(nth s i)` | UTF-16 indices | code-point indices |
| `(/ 1 2)` | `1/2`, a `Ratio` | `0.5`, a double |
| integer overflow | promotes to bignum | **throws** |
| `(hash \a)` | `97` | the hash of `"a"` |
| protocol dispatch | on host type | on **kind** or **metadata** — flint has no types |
| `binding` | per host thread | per **green thread**; a spawn inherits a snapshot |
| a port/channel | — | **not** transferable through another port |
| `(var x)` / `#'x` | a `Var` object | the value itself — no `alter-var-root`, no `with-redefs` |

Three of those are worth a paragraph:

**`count`, `nth` and `subs` on strings use code points, not UTF-16 units or
bytes.** Clojure inherits the JVM's UTF-16 `char`, so `(count "aé😀")` is `4`
there (the emoji is a surrogate pair) and `3` in flint. This follows directly
from flint having no JVM under it — strings and hashing are defined over
UTF-8, independent of any host's internal representation
(the `strings-and-matching` decision), so a
value hashes the same on the wasm, native, JVM and CLR runtimes.

**Arithmetic overflow throws.** flint's number tower stops at `i64`/`f64` —
there is no bignum to promote into. `(- Long/MIN_VALUE)` and
`(quot Long/MIN_VALUE -1)` throw `"integer overflow"` as a catchable
`Throwable`, everywhere, rather than silently wrapping (as a raw JVM `long`
would) or throwing a host-specific, non-flint exception (as the CLR does
without this convergence) — see
the `other-hosts` decision.

**Protocols dispatch on kind or metadata, not on type.** There is nothing for
`extend-type` to name, because there are no types — see
[Protocols](#protocols) above. This is the one divergence that reaches into
everyday code: a library author reaching for `extend-type` in ported Clojure
code needs to reshape it as `extend-protocol` over a kind, or as metadata on
the value.

Also worth knowing: `(into {} …)` goes through a transient and does not
preserve insertion order, even for maps small enough that the map *literal*
would (the reader's array-map does; the transient path doesn't; neither
language promises an order beyond the literal, but code relying on it will
see a difference). `clojure.string/split` also accepts a literal string, not
only a regex. Reader conditionals default to feature `#{:flint}` rather than
`:clj`, since flint is not the JVM.
