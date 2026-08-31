# 0034 — A tagged literal is a value, not a map

> **QUEUED — not built.** Today the reader turns `#my.ns/thing v` into
> `{:flint/tagged my.ns/thing :flint/value v}`, an ordinary map. That is fine
> until something has to serialise it, which `0033` now does.

## Why a map is not good enough

**A tag is ambiguous with a map in every format that has both.** EDN and CBOR
carry tags natively, so a codec meeting `{:flint/tagged x :flint/value v}` has
to guess: is this a tagged literal to be written `#x v`, or a map that happens
to have those two keys? Guessing by shape is wrong either way round -- write the
map as a tag and an ordinary map stops round-tripping; write the tag as a map
and a tagged literal stops round-tripping.

**JSON loses the namespace.** `#my.ns/thing` is a NAMESPACED SYMBOL. Degraded
through a map whose key is a string, the namespace is either flattened into the
name or dropped, and the tag no longer identifies what it identified.

**And it is what made the forgery guard a heuristic.** `0033` refuses a guest
value that would serialise to a reserved tag, and with a map that refusal has to
match on SHAPE -- inspect every map for two particular keys. With a real type
the check is on the tag SYMBOL of a value that is unambiguously a tagged
literal, which is one comparison and no false positives. The guard does not go
away -- a guest could still construct a tagged literal named `flint/port` -- but
it stops being a shape heuristic and starts being a name check.

## The decision

A tagged literal is its own value: `TY_TAGGED`, two slots, a namespaced SYMBOL
and a value. `#my.ns/thing v` reads to one, `pr-str` writes one back, and two
are equal when both halves are. Two slots rather than an array-map's boxed pairs
plus a count, because the shape is fixed and known.

**It still answers the map protocols.** `(:tag x)`, `(get x :form)`, `count`,
`keys`, `vals`, `seq` and `contains?` all work, so nothing that treats one as a
map has to learn a new way to read it. That is the point of making it a type
rather than a wrapper: the ambiguity a codec suffers from goes away without
costing the reader anything.

**`:tag` and `:form`, matching Clojure**, whose `tagged-literal` gives a value
answering exactly those two and a `tagged-literal?` beside it. The current
reader emits `:flint/tagged` and `:flint/value`, and nothing in the tree reads
them -- checked -- so the names are free and compatibility should have them.

**`assoc` on either key preserves the type**; on any other key it REFUSES,
naming the two it has. The object is two slots and there is nowhere for a third
to go, so the alternative is silently promoting to a map and losing the
taggedness, which is the sort of quiet coercion this codebase refuses elsewhere.
`dissoc` refuses for the same reason: a tagged literal with one half is not a
smaller tagged literal, it is not one.

**`map?` is FALSE and `kind` is `:tagged`.** Lookup working is not the same as
being a map, and Clojure agrees -- `map?` on a `TaggedLiteral` is false. It
matters more here than there, because `kind` is the closed set protocol dispatch
runs on (`0005`): if it answered `:map`, every `extend-protocol :map` in every
program would silently start catching tagged literals.

## What this touches

* **The reader** stops building the map (`reader.cljc`, the `#` branch).
* **`tagged-literal`, `tagged-literal?`, `tag` and `form`** in `clojure.core`,
  as Clojure has them.
* **`flint.rt/kind` gains `:tagged`**, which is a change to the CLOSED SET that
  protocol dispatch runs on (`0005`). `test/common/lang/protocols.cljc` pins
  that set deliberately, so it fails until updated -- which is the test doing
  its job rather than an obstacle.
* **Equality and hash**, structurally over both halves.
* **The printer**, `#ns/name value`.
* **The wire codec** gains a tag, and `0033`'s formats each get a row:
  * `:flint` -- a new `K_TAGGED`;
  * `:edn` -- native, `#ns/name value`;
  * `:cbor` -- a tag carrying the name and the value;
  * `:json` -- `{"$flintTag": "ns/name", "$flintVal": v}`, which is ALREADY the
    convention `0033` describes. Identities become reserved names within one
    general facility rather than a special case beside it;
  * `:json-strict` -- REFUSED. Any representation either drops the tag, which is
    value loss, or invents a convention, which is what strict exists not to do.
* **All four runtimes**, since it is a heap type: the collector's type table, the
  ported `Obj`/`Eq`/`Hash` in the JVM and the CLR.

## What it buys `0033`

One mechanism instead of two. `$flintTag` stops being a private convention for
identities and becomes the JSON spelling of a tagged literal, with `flint/port`
and `flint/opaque` as reserved names in it. A reader can then be told one rule
-- "a tagged literal crosses as a tag, and these names are ours" -- rather than
a convention plus an exception.
