# The compiler

## The compiler and its bootstrap

The compiler is portable `.cljc` — reader, analyzer, emitter, driver — and it
compiles itself.

**The bootstrap host is babashka**, as the brief specifies, and it works: `bb`
1.3.190 runs the subset flint's compiler needs. The compiler avoids `deftype`
and `defprotocol` entirely and uses plain maps, so the portable subset is small.

```
gen0   bb compiled the compiler        88 089 image bytes
gen1   flint compiled the compiler     88 089 image bytes   -- IDENTICAL
gen2   reproduces itself byte for byte
```

**`defmacro` works by running the macro body through `flint.eval`**, an
interpreter for the compiler's own AST. Handing the form to the host's `eval`
would have been less code and would have made the compiler behave differently on
babashka than on flint — exactly the divergence a fixpoint test exists to catch.
It also means the bootstrap needs no second compiler in Rust, which
`doc/decisions` warned would be the implementation that drifts.

flint reads its own source rather than borrowing the host's reader, for the same
reason: reader conditionals, syntax quote and metadata have to behave
identically in both places.

### What the fixpoint test caught

Three bugs, and none of them would have been found any other way:

- **The reader's end-of-input sentinel was the keyword `::eof`.** That worked
  until the reader read its own source, where `::eof` appears as a literal and
  was silently dropped as "no form here". The symptom was a mis-shaped `if` in a
  function far downstream. The sentinel is now a fresh volatile that source text
  cannot forge.
- **A VM frame cached its closure**, and the collector could not see the copy
  (above).
- **Map iteration order reached the output bytes** in four places, so the same
  source compiled to two different images depending on the host. Constants, sets
  and destructuring now use a host-independent canonical order; map literals keep
  *source* order, which needed an ordered array-map builtin because `into {}`
  goes through a transient and a transient map does not preserve order.

And one self-application bug: the analyzer rewrites a `#"…"` literal — which the
reader represents as `{:flint/regex src}` — into a call to the regex compiler,
and that rewrite also matched the *reader's own construction of that marker*. A
flint-hosted reader returned compiled patterns where a host-hosted one returned
markers. Fixed by rewriting only when the value is a literal string.

### Getting data in

`clojure.edn` is a full EDN reader with reader-tag support, written fresh rather
than reusing the compiler's reader so that a program that reads EDN does not drag
syntax quote and reader conditionals in behind it. `:readers` and `:default` work
as they do in Clojure. There are no built-in `#inst` or `#uuid` readers, because
flint has no date or UUID type; an unknown tag calls `:default` if you gave one
and otherwise throws, which is what Clojure does for a tag with no registered
reader.

`flint.data.json`, `flint.data.xml` and `flint.data.html` are ours — not
`clojure.data.*` — but shaped so a Clojure programmer can guess them:
`read-str`/`write-str` with a `:key-fn` for JSON, and
`{:tag :div :attrs {…} :content […]}` for XML and HTML.

The parsers are **adapted crates**, not written here:

- **XML** — `xmlparser`: already `no_std`, already a tokenizer, so flint values
  are built as tokens arrive with no intermediate document tree.
- **HTML** — `htmlparser`: the same design, tolerant of real markup.
- **JSON** — `serde_json` with `default-features = false, features = ["alloc",
  "float_roundtrip"]`, through `DeserializeSeed` + `Visitor`, which is a
  streaming interface: no `serde_json::Value` is ever built.

Two other JSON crates were tried and rejected, and the reasons are the
interesting part: **actson** is a genuine push parser but is not `no_std` and its
`panic_impl` collides with the runtime's; **microjson** *is* `no_std` but reads
integers as `isize` — 32 bits on wasm32 — and floats as `f32`, and JSON needs 64
bits of both.

**JSON number policy**, since JSON has none: a number with no fraction and no
exponent reads as a **long**, anything else as a **double**. `1` is `1`; `1.0`
and `1e3` are doubles.
