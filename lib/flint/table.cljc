(ns flint.table
  "Tables: columnar storage that is a value (`doc/decisions/0026`).

  A vector of maps from the outside, columnar chunks underneath, and a CLOSED
  schema fixed when the table is built. Closed is the whole design: a key
  outside the schema is refused and so is a value of the wrong type, and from
  that one decision the ragged-row problem, the null bitmap and per-chunk type
  guessing all stop existing.

  It lives HERE and not in `clojure.core`. Clojure's namespace is Clojure's --
  a program ported from Clojure must not find vars there that Clojure does not
  have, and a program that defines its own `table` must not collide with one it
  never asked for. The row API needs nothing from this namespace anyway:
  `count`, `get` and `(:col row)` are the ordinary collection functions, and a
  row ref answers them as the map it is.")

(defn schema
  "`[[:name :type] ...]` -> a schema.

  Types are `:int`, `:double`, `:string`, `:bool`, `:keyword` and `:any`.
  Names must be keywords and distinct -- two columns of one name would make
  `get` ambiguous and the index would silently keep the later one."
  [pairs]
  (flint.rt/schema pairs))

(defn table
  "A table over `s`, built from `rows` -- a vector of maps.

  Every row must have exactly the schema's columns, with values of the declared
  types. A row that does not is refused, naming the row, the column, the type
  it holds and the type it was given."
  [s rows]
  (flint.rt/table s rows))

(defn table? [x] (flint.rt/table? x))

(defn table-schema
  "The schema of a table, or of a row ref."
  [x]
  (flint.rt/table-schema x))

(defn columns
  "The column names, in order."
  [s]
  (flint.rt/schema-columns s))

(defn types
  "The column types, in the same order as `columns`."
  [s]
  (flint.rt/schema-types s))
