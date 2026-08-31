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
* **A row is materialised on demand.** `(get table 0)` builds a map; a scan that
  wants one column never builds one. `get-in` must not materialise, because it
  is the common case.

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

## What has to be decided before any of it

**Is `(= table [{:a 1}])` true?** Everything a vector answers, a table answers
-- but if EQUALITY is included then `hash` must agree, so hashing a table has to
materialise every row and no columnar shortcut is available. That is affordable,
since hashing is O(n) either way, but it forecloses hashing column runs directly
and so has to be a decision rather than a discovery.

## Transients

In scope, for the same reason `0024`'s byte strings needed one: building a
table row by row through the persistent path copies a path per row, which is
quadratic in the same way repeated `str` was, and for the same reason. A
transient table appends into an open chunk and seals it when full.

## Order

1. The schema, the value type, and the vector trie with columnar leaves.
2. `get`, `get-in`, `count`, `=` and printing -- the surface that makes it a
   value rather than a library.
3. **MEASURE, before going further.** The entire justification is memory and
   scan speed: 10 000 rows of `{:a int :b int}` against the vector of maps, for
   size and for the cost of scanning one field. If the win is not dramatic the
   design is wrong, and this is the cheapest moment to learn it -- before the
   path copy and the transient, which are the expensive things to build and the
   hard things to undo.
4. `assoc` and `update`, with the rejections and their messages.
5. The chunk encodings: constant first, since migration leans on it.
6. `migrate`, sharing chunks for add-constant and remove.
7. The transient, measured against the persistent path on a build loop.
8. `flint.table`: bulk construction, column selection, ranges, splits.
9. The `0025` codec tag and `0033`'s columnar JSON, so a table crosses a port as
   a table rather than as a vector of maps.
