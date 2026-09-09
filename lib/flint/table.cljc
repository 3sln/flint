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
  row ref answers them as the map it is."
  (:require [flint.protocols]))

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
(extend-protocol flint.protocols/Printable
  ;; As DATA: the form that reads back.
  :table (print-data [t]
                     (clojure.core/str "#flint/table {:schema "
                                       (pr-str (mapv (fn [i] [(nth (columns (table-schema t)) i)
                                                              (nth (types (table-schema t)) i)])
                                                     (range (count (columns (table-schema t))))))
                                       " :rows " (pr-str (vec (rows t))) "}"))
  ;; FOR A PERSON: the rows without their quoting, and the row COUNT, because a
  ;; hundred-thousand-row table printed in full is not something a person reads.
  ;; This is the difference the two hooks exist for -- with one method and a
  ;; flag, the honest human form would have had to be branched into the same
  ;; function that has to produce a readable one.
  :table (print-human [t]
                      (let [n (count t)
                            shown (if (> n 5) (vec (take 5 (rows t))) (vec (rows t)))]
                        (clojure.core/str "#flint/table " (print-str shown)
                                          (if (> n 5)
                                            (clojure.core/str " (" n " rows)")
                                            "")))))

;; ----------------------------------------------------------------- migration
;;
;; A schema change makes a NEW TABLE. There is no in-place evolution and no
;; inference: what the new table's columns are is said, not guessed
;; (`doc/decisions/0026`).

(defn migrate
  "`t` under `s`, a new schema.

  A column both schemas name is CARRIED -- the same column objects, shared, not
  copied -- so dropping a column or adding a defaulted one is a head-only edit
  however many rows the table has. That is what stable column ids buy.

  Three forms:

      (migrate t s)            every column of `s` is already in `t`
      (migrate t s {:c 0})     `:c` is new; every row gets 0, stored ONCE
                               per chunk as a constant column
      (migrate t s (fn [row] {...}))
                               every row is recomputed from a ref, which is
                               the case that costs -- it rewrites the chunks

  A column the new schema adds with no default and no function is refused by
  name, and so is a type change without a function: changing what a column
  holds means computing new values for it, which only the third form does."
  ([t s] (flint.rt/table-migrate t s {}))
  ([t s defaults-or-fn]
   (if (fn? defaults-or-fn)
     ;; The rewriting case, and honestly a rebuild: every row is a new row, so
     ;; there is nothing to share and no cheaper path hiding here.
     (table s (mapv defaults-or-fn (rows t)))
     (flint.rt/table-migrate t s defaults-or-fn))))

;; ----------------------------------------------------------------- building
;;
;; `transient`, `conj!` and `persistent!` work on a table, so building one row
;; by row is the ordinary Clojure shape rather than a table-specific API.
;;
;; It exists because the persistent path copies the whole chunk per row -- 256
;; copies to fill one chunk. A transient writes into an open chunk in place and
;; seals it when full, and `conj!` still CHECKS the row: a transient is a faster
;; way to build a table, not a way to build one that is not closed.

(defn build
  "A table over `s` built by feeding each of `xs` through `f` to a row map.

  The transient shape written out, since it is the same three lines every time:

      (build S rows identity)
      (build S (range 1000) (fn [i] {:id i :score (* 2 i)}))"
  ([s xs] (build s xs identity))
  ([s xs f]
   (persistent! (reduce (fn [t x] (conj! t (f x))) (transient (table s [])) xs))))

;; ------------------------------------------------------------------ columns
;;
;; The half that makes a column store worth having rather than merely compact.
;; Everything here reaches the COLUMN and never builds a row: scanning one field
;; of a million-row table should touch a million values and nothing else.

(defn column
  "Column `k` of `t`, as a vector. Read straight out of the chunk runs -- no row
  is built and no ref is made."
  [t k]
  (flint.rt/table-column t k))

(defn reduce-column
  "`(f acc v)` over column `k`, without materialising a row for any of them.

  This is what a table is FOR. `(reduce + 0 (map :score (rows t)))` reads the
  same field and pays for a ref per row on the way."
  [t k f init]
  (flint.rt/table-reduce-column t k f init))

(defn slice
  "Rows `[from to)` of `t`, as a table.

  SHARES every chunk the range spans -- the row offset lives in the head, so a
  range that starts anywhere costs the chunks it spans and not the rows.
  Chunks outside the range are dropped, so a slice does not retain the table."
  [t from to]
  (flint.rt/table-slice t from to))

(defn select
  "`t` with only the columns named in `ks`, in that order.

  A `migrate` to a narrower schema, which is a head-only edit: the columns kept
  are the SAME column objects, and the ones dropped are simply not named any
  more."
  [t ks]
  (let [s (table-schema t)
        want (mapv (fn [k]
                     (let [i (loop [j 0]
                               (cond (>= j (count (columns s))) nil
                                     (= k (nth (columns s) j)) j
                                     :else (recur (inc j))))]
                       (when (nil? i)
                         (throw (ex-info (str "no column " k "; the columns are " (columns s))
                                         {:column k :columns (columns s)})))
                       [k (nth (types s) i)]))
                   ks)]
    (migrate t (schema want))))


(defn read-table
  "The reader for `#flint/table`, bound in every project (`doc/decisions/0035`).

  It takes what the printer wrote -- `{:schema [[:name :type] ...] :rows [...]}`
  -- and not a bare vector of rows. `0026` originally said the form was
  `#flint/table [{:a 1}]`, and that CANNOT read back: the types are half of a
  table's identity, an `:int` column and an `:any` column holding the same
  values are different tables, and nothing in a row says which it was. A form
  that loses that is a record of the value rather than the value, which is the
  same overclaim this file already had to withdraw once."
  [m]
  (table (schema (:schema m)) (:rows m)))
