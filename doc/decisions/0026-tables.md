# 0026 — Tables: columnar storage that is a value

> **QUEUED — not built.** Nothing in this file exists. Recorded now because
> `0025`'s wire codec has to carry a table, and a tag is cheaper to add before
> that format ships than after.
>
> REVISED once since: a table is CLOSED, its schema fixed at construction, and
> that one decision deleted most of what this file used to call hard.
>
> `0033` is the second half of that argument: a table needs a JSON encoding
> too, and a COLUMNAR one, because a vector of maps repeats every key name on
> every row and throws away the compactness a table exists for.

## What a table is

A **vector of maps**, from the outside. **Columnar chunks in a trie**, underneath,
with a **closed schema** fixed when it is built.

```clojure
(get table 0)                    ;; {:a 1 :b 2}
(get-in table [0 :a])            ;; 1
(assoc table 0 {:a 1 :b 3})
(update table 0 assoc :a 2)
(count table)
```

Everything a vector answers, a table answers, so code that does not know it has
one keeps working. What it is *for* lives in `flint.table`: bulk construction,
column selection, ranges, splits, and the operations that are the point of
storing data by column rather than by row.

## Why, in one paragraph

A vector of maps stores every key in every row. Ten thousand rows of
`{:a int :b int}` is ten thousand map objects, twenty thousand key references
and a hash lookup per field access. The same data by column is two arrays and
an index, and a scan over one field touches one array.

That is Arrow's argument and it is not new. What is new here is that it has to
be a **value**: persistent, structurally shared, `=` by content, safe to hold
across a collection. Arrow is a mutable buffer with a schema; this is flint's own
persistent trie with columnar leaves, which is the same trick its vectors and
maps already use for the same reason.

## Closed, and that is the whole design

**A map is open; a table is closed.** The schema is fixed at construction: the
column names and the type of each. Everything else follows from that one
decision, and most of what used to be hard here stops existing.

* `assoc` to a key NOT IN THE SCHEMA is rejected, naming the key and the
  columns there are.
* `assoc` with a value of the WRONG TYPE is rejected, naming the column, the
  type it holds and the type it was given.
* There are therefore no ragged rows, no null bitmap standing in for absent
  fields, and no overflow map for keys outside the schema. An earlier draft of
  this file spent a section on which of those to pick; a closed schema means
  none of them.
* And a column's type is DECLARED rather than inferred per chunk. A `:int`
  column is a run of `i64` in every chunk, always, because nothing can put
  anything else in it.

The rejections are ordinary errors, not `#?(:flint/check ...)` -- a closed table
that accepted a bad row in a release build would not be closed. What they borrow
from `0032` is the QUALITY of the message: expected, actual, and the column,
rather than a class cast three frames away.

## The shape

* **The vector trie, with a different leaf.** flint's persistent vector is
  already a 32-way path-copying trie that indexes and counts; a table is that
  trie whose leaves are columnar chunks. `get` descends to a chunk and then
  offsets within it, `assoc` gets its path copy for free, and no new tree has to
  be written.
* **A chunk is a Vals array of COLUMN OBJECTS**, each uniformly one layout. An
  unboxed `i64` run is a `Raw` object the collector never traces; a boxed column
  is `Vals` and traced normally. A chunk that mixed the two inline would need a
  layout the runtime does not have, so pointing at per-column objects is both
  the clean decomposition and the one the collector allows.
* **Columns are addressed by a stable ID, not by position.** The schema maps
  name to id; a chunk stores columns by id. This is what makes migration cheap
  -- see below -- and it costs one indirection that a small map or a linear scan
  over a short vector answers.
* **A row is never materialised.** `(get table 0)` hands back a REF into the
  chunk; a map is built only if someone asks for one. See below.

## Chunks optimise; the schema does not care

The schema decides what a column MEANS. The chunk decides how those values are
written down, and may change its mind per chunk without the table's meaning
moving at all:

* a column holding one value in every row collapses to that value;
* runs collapse to run-lengths;
* a small set of distinct values becomes a dictionary and a run of indices.

None of this is visible through `get`. It is the same separation Arrow and
Parquet draw between a logical type and an encoding, and it is only sound
because the schema is closed: an open table would have to re-derive the encoding
every time a row arrived with a new shape.

## Migration is explicit, and usually free

A schema change makes a NEW TABLE. There is no in-place evolution and no
inference: `(migrate t new-schema)`, optionally with a mapper function per row.

Because chunks address columns by id:

* **Removing a column** is a head-only edit. The new schema omits the id; every
  chunk is shared unchanged. The data stays resident and invisible until a chunk
  is next rewritten, which is the trade -- a fast migration against delayed
  reclamation.
* **Adding a column with a constant default** is also head-only, because a
  constant column is exactly the chunk encoding above: one value, not one per
  row. Adding `:c` defaulting to 0 to a million-row table writes one value per
  chunk.
* **Adding a column computed by a mapper** has to rewrite the chunks it touches,
  because the values differ per row. That is the case that costs, and it is
  explicit rather than something a table does behind an `assoc`.

## A table is not a vector of maps

`(= table [{:a 1}])` is **false**. A table is its own kind of thing -- a schema'd
column store -- and it prints as its own literal:

    #flint/table [{:a 1 :b 2} {:a 3 :b 4}]

That is `0034`'s tagged literal doing the work, and it is why `0034` comes
first.

An earlier draft of this file said the form "reads back as a table, so `pr-str`
round-trips". **It does not, and did not.** `#flint/table [...]` reads as a
tagged literal whose form is a vector of maps -- which is a faithful record of
the value and not the value. Reading it back as a table needs the mirror of what
printing just got: a tag registry a library can register into, so `flint.table`
supplies the reader for its own tag exactly as it supplies the printer. That
belongs with the codec tag in step 9, because the two are the same question
asked of a file and of a port. Until then the round trip is print-only, and
saying so is better than a claim nobody had run.

**The printer does not know about tables.** `#flint/table` is an implementation
of `clojure.core/Printable` registered by `flint.table`, not a branch in
`pr-str*`. The first cut was a branch, and it cost **15 832 bytes in every
module that prints anything** -- `pr-str*` is linked by nearly everything, and
the closure inside the branch is a `call_indirect` target the shaker has to root
conservatively, so it dragged the whole table `get` path in behind it. A type
specialising its own printing is what a protocol is for, and here it is worth
16 KB of floor. The consequence to state plainly: a program that never requires
`flint.table` prints a table as `#<unprintable>`. That is right -- it is a
program that could not have built one -- and when a table can arrive over a
PORT, the decoder that admits one lives in `flint.table` too, so requiring the
decoder brings the printer with it.

`flint/table` is NOT a reserved tag in `0033`'s sense. Reserved means CONFERS
AUTHORITY -- `flint/port` and `flint/opaque` are refused from guest code because
forging one fabricates a claim on something. Forging a table fabricates data,
which anyone can do by writing a literal. The reserved set is about identity,
not about the namespace.

Refusing the equality buys the thing that made it worth asking: **`hash` is free
to be columnar**, because it no longer has to agree with what a vector of maps
would produce. Hashing a million-row table can hash column runs rather than
materialise a million maps.

## A row is a REF, not a map

Iterating a table yields **table refs**: schema, chunk, row index. Three slots,
and materialising nothing.

* **It behaves as a map.** `get`, keyword lookup, `count`, `keys`, `vals`, `seq`
  and `contains?` all work, `map?` is TRUE and `kind` is `:map` -- so code that
  does not know it has a table keeps working, which is the whole promise at the
  top of this file. `kind` being many-to-one is not new: three string tiers all
  answer `:string`.
* **It equals a map with the same entries**, and hashes the same. That is the
  opposite call to table-versus-vector above, and coherently so: a table is a
  distinct kind of thing, a row IS just a map, seen cheaply.
* **`assoc` on a ref produces a MAP.** A ref is a view; changing it makes an
  independent value, and neither the chunk nor the table moves.
* **It does NOT hold the table.** Only the schema and the chunk -- so keeping
  one row out of a million-row table retains one chunk, not the table. That is
  the point of the type rather than a detail of it.
* **It cannot dangle.** Chunks are persistent, so a ref stays valid for ever,
  and a later `assoc` on the table path-copies rather than editing the chunk the
  ref is looking at. The ref keeps seeing the row it was made from, which is
  what a persistent structure should do.

This is also what deletes a special case. An earlier draft required `get-in` not
to materialise, because it is the common path. With refs `(get table 0)`
materialises nothing either, so the general path IS the fast path and `get-in`
needs no special handling at all.

## Transients

In scope, for the same reason `0024`'s byte strings needed one: building a
table row by row through the persistent path copies a path per row, which is
quadratic in the same way repeated `str` was, and for the same reason. A
transient table appends into an open chunk and seals it when full.

## Order

1. The schema, the value type, and the vector trie with columnar leaves.
2. The table ref, then `get`, `get-in`, `count`, `=` and printing -- the surface
   that makes it a value rather than a library. The ref comes first because
   `get` returns one.
3. **MEASURE, before going further.** The entire justification is memory and
   scan speed: 10 000 rows of `{:a int :b int}` against the vector of maps, for
   size and for the cost of scanning one field. If the win is not dramatic the
   design is wrong, and this is the cheapest moment to learn it -- before the
   path copy and the transient, which are the expensive things to build and the
   hard things to undo.
4. `assoc` and `update`, with the rejections and their messages. **Done**, with
   `conj`, iteration by ref, and printing through `Printable`. Two things had to
   be fixed underneath: `extend-protocol` built its method key from the
   EXTENDING namespace rather than the defining one, so extending a protocol
   across namespaces was a silent no-op; and `kind` answered `:other` for
   opaque values, byte strings, delays and volatiles, which means they could not
   be dispatched on at all.
5. The chunk encodings: constant first, since migration leans on it.
6. `migrate`, sharing chunks for add-constant and remove.
7. The transient, measured against the persistent path on a build loop.
8. `flint.table`: bulk construction, column selection, ranges, splits.
9. The `0025` codec tag and `0033`'s columnar JSON, so a table crosses a port as
   a table rather than as a vector of maps -- and, the same question asked of
   source rather than of a port, a reader tag registry so `#flint/table [...]`
   reads back as a table. Printing is already a library's own business
   (`Printable`); reading is the half still hard-coded.
