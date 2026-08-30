# 0032 — Checks that cost nothing in the build that ships

> **BUILT — 2026-08-30.** `#?(:flint/check ...)` is on by default and removed by
> `:optimize [perf]`. `flint.check/expect`, a `Predicate` protocol that dispatches
> on the value rather than on its name, `^:flint.check/test` collected by the
> compiler into a generated registry, and `flint test` on both CLIs. Fifteen core
> predicates carry their own explanation. `test/common` is 53 checks running on
> all four runtimes.

## The problem

Good error messages and cheap production code pull in opposite directions. A
library that validates its arguments pays for that validation on every call
forever; a library that does not hands you a null three frames away from the
mistake.

The usual answers are both bad. Assertions behind a runtime flag still cost the
branch and still ship the message strings. A separate "debug build" means the
thing you tested is not the thing you shipped.

## The decision

**A check is a reader conditional, so a release build never reads it.**

```clojure
(defn take [n coll]
  #?(:flint/check (expect nat-int? n))
  ...)
```

`:flint/check` is in `reader/default-features`. Under `:optimize [perf]` it is
removed *before the sources are collected*, so the branch is never read, never
analysed, never in the image, and `flint.check` is not in the program at all.
Not "compiled away" — absent. That is what lets checks be **on by default**, and
being on by default is the whole point: a check nobody turns on is a check
nobody has.

## The predicate protocol, and why it is not a lookup table

The first version recognised standard-library predicates by NAME and looked up a
canned message. That handles `(expect string? x)` and fails on:

```clojure
(let [x =] (expect x 1 2))
```

which is the case that matters, because a predicate is a **value**. So the
explanation travels on the function:

```clojure
(defprotocol Predicate
  (check [p args])
  (explain [p args]))
```

Dispatch asks the value's metadata first and its `kind` second, so a plain
function works (`:fn` implements `check` as `apply`) and a function *carrying*
`:flint.check/explain` explains itself. Two consequences fell out of it:

* **Closures had to be able to carry metadata.** They could not — `with-meta` on
  a closure silently returned it unchanged. `TY_CLOSURE` is now
  `[fnidx, ...upvals, meta]`, with the metadata at the END so every `1 + i`
  upvalue index in two interpreter loops and three emitters stayed unchanged.
* **A `defn`'s metadata lands on the VAR**, which the callee never sees.
  `:flint/value-meta` is the key that routes it onto the function instead.

`explain` is called only after `check` has already said no, so it may do as much
work as a good message needs while `check` stays allocation-free.

## What `expect` reports, and where it gets it

Everything comes from `&form` at macro-expansion. No source text is embedded in
the image and none is read back at run time.

```text
check failed in main
  /tmp/ck/main.cljc:4:5

  p 42
    ^^

  expected  a string
    actual  42
            got number
```

Getting the caret there needed the reader changed twice, and both changes are
worth keeping regardless:

* **Symbols, vectors, maps and sets now carry `:line`/`:column`.** Not doing that
  for symbols is a JVM Clojure limitation there is no reason to reproduce.
* **A literal cannot carry metadata**, so its parent collection carries
  `:child-pos` — a flat `[line col line col ...]` — and `expect` reads the caret
  column out of it.

Making positions universal exposed a real pre-existing bug: a form returned from
`#?(...)` was stamped with the *conditional's* position. Existing metadata now
wins in `with-pos`.

## Tests are the same mechanism

```clojure
(defn ^:flint.check/test round-trips [] (expect = x (decode (encode x))))
```

The compiler indexes **every** var's metadata rather than this one key, and
`flint test` is one client of that index — a doc generator or a lint pass would
be another asking the same map a different question. From it the compiler
generates `flint.check.registry` as source.

A test passes by returning and fails by throwing, which is what `expect` does.
There is no assertion count and no framework: a check that has to be registered
with something is a check that can be forgotten to register.

## What this cost elsewhere

Two things that look unrelated and are not:

* **A macro cannot expand to a `def`.** Names are collected in a pass that runs
  before any macro is evaluated, so a `defpred` macro defined vars the compiler
  never recorded — invisible until a *macro body in another namespace* referenced
  one at compile time. The fifteen core predicates are written out longhand
  because of it, and `def-form-names` says so.
* **`flint test`'s entry does not exist when the program is resolved.**
  `flint.check.registry` is generated, so resolving from the entry outwards
  reports the entry missing. `resolve-project` takes a roots override, and every
  namespace on the path is a root — which is independently correct, because a
  test that nothing requires is still a test.

## The suite this made possible

`test/common` is written in flint and asserts its answers. That is the half
`runtimes/conform` structurally cannot cover: that harness diffs transcripts
across runtimes, so two ports wrong the same way agree and pass — which
happened, when both read a double's mantissa as an integer.

53 checks, one image, four runtimes, compared check by check. Three of them were
wrong on the first run and the runtime was right every time.
