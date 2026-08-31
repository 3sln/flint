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

(defn rows
  "The rows of `t`, as a seq of ROW REFS -- materialising nothing.

  A ref reads as the map it is: `count`, `get`, `(:col row)`, `keys`, `vals`
  and `=` against a map all work, so a function that does not know it was
  handed a table keeps working."
  [t]
  (seq t))

(defn add-row
  "`t` with `row` appended. The row must have exactly the schema's columns,
  with values of the declared types; anything else is refused by name."
  [t row]
  (conj t row))

(defn set-row
  "`t` with row `i` replaced by `row`. `i` may be `(count t)`, which appends --
  the same rule a vector follows."
  [t i row]
  (assoc t i row))

(defn update-row
  "`t` with row `i` replaced by `(f row & args)`. `f` is handed a row REF and
  may return a map; the result is checked against the schema like any other."
  [t i f & args]
  (assoc t i (apply f (get t i) args)))

;; ------------------------------------------------------------------ printing
;;
;; A table prints as `#flint/table [...]` and reads back, because a table is
;; NOT `=` to a vector of maps and must not print as one (`doc/decisions/0026`).
;;
;; It is registered HERE rather than branched on in `clojure.core`'s printer.
;; The printer is linked by every program; a branch there would make every
;; program know what a table is, which is the coupling a protocol exists to
;; remove. The consequence is worth stating plainly: a program that never
;; requires this namespace prints a table as `#<unprintable>`. That is correct
;; -- it is a program that cannot have made one -- but a table arriving over a
;; PORT will be able to, and the codec that decodes one lives here too, so
;; requiring the decoder is what brings the printer with it.
(extend-protocol clojure.core/Printable
  :table (print-form [t readable?]
                     (clojure.core/str "#flint/table "
                                       (clojure.core/pr-str* (vec (rows t)) readable?))))
