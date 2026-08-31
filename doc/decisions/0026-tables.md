# 0026 — Tables: columnar storage that is a value

> **QUEUED — not built.** Nothing in this file exists. Recorded now because
> `0025`'s wire codec has to carry a table, and a tag is cheaper to add before
> that format ships than after.
>
> `0033` is the second half of that argument: a table needs a JSON encoding
> too, and a COLUMNAR one, because a vector of maps repeats every key name on
> every row and throws away the compactness a table exists for.

## What a table is

A **vector of maps**, from the outside. A **columnar B-tree**, underneath.

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
across a collection. Arrow is a mutable buffer with a schema; this is a B-tree
whose leaves are columnar chunks, which is the same trick flint's vectors and
maps already use for the same reason.

## The shape

* **A B-tree keyed by row index**, so `get` is a descent and `assoc` copies a
  path rather than the table.
* **Leaves are chunks of rows stored BY COLUMN** — a chunk of 256 rows with
  four fields is four runs of 256, not 256 maps.
* **A schema per table**: the field names, once, rather than per row. This is
  where the space goes.
* **A row is materialised on demand.** `(get table 0)` builds a map; a scan
  that only wants one column never builds one. `get-in` is the case that must
  not materialise, because it is the common one.

## What is hard, and worth deciding early

**Ragged rows.** A vector of maps allows every row a different shape; a column
store wants one schema. Options: refuse a row that does not match, store
absent fields as a null column, or keep an overflow map per row. Refusing is
simplest and wrong for real data; a null bitmap per column is the Arrow answer
and probably right.

**`assoc` on one field of one row** copies a path to a leaf and one column
chunk within it. That is more than a vector-of-maps copies for the same edit,
and it is the price of the scan being fast. A transient is the answer for
building and for bulk edits, which is why they are in scope rather than later.

**Type per column.** Homogeneous columns are where the win is — a column of
fixnums can be a run of i64 rather than a run of boxed values. A column that
turns out heterogeneous has to fall back to boxed. Deciding that per chunk
rather than per table means one odd row does not deoptimise the whole thing.

## Transients

In scope, for the same reason `0024`'s byte strings needed one: building a
table row by row through the persistent path copies a path per row, which is
quadratic in the same way repeated `str` was, and for the same reason. A
transient table appends into an open chunk and seals it when full.

## Order

1. The value type, the schema, and a B-tree whose leaves are columnar.
2. `get`, `get-in`, `count`, `=`, and printing — the surface that makes it a
   value rather than a library.
3. `assoc`, `update`, and the path copy.
4. The transient, measured against the persistent path on a build loop.
5. `flint.table`: bulk construction, column selection, ranges, splits.
6. The `0025` codec tag, so a table crosses a port as a table rather than as a
   vector of maps.
