(ns flint.bytes
  "Byte strings (`doc/decisions/0024`).

  A string of bytes, in the same two tiers as text: flat below the threshold, a
  shallow tree above it, with structure sharing so concatenation is a tree join
  and a slice of a large range shares subtrees.

  Not a vector of integers, which is what `flint.rt/str-bytes` answers with. A
  flint vector holds NaN-boxed 64-bit values, so a byte costs eight bytes plus
  trie overhead: 200 000 bytes held that way is 43.5 MB and 28 collections,
  against 0.2 MB and none held as a byte string.

  Its own namespace rather than `clojure.core`, because only `bytes?` is
  Clojure's. The rest of this is flint's, and `clojure.core` is a namespace
  people expect to match.

  `count`, `nth` and `get` work on one through the generic collection surface,
  so a byte string is indexable without anything here."
  (:require [flint.rt]))

(defn of-string
  "A string's UTF-8, as bytes. Note the units change: `count` of the result is
  bytes, not characters."
  [s]
  (flint.rt/str->b s))

(defn to-string
  "The bytes decoded as UTF-8. Refuses bytes that are not."
  [b]
  (flint.rt/b->str b))

(defn of-vector [v] (flint.rt/vec->b v))
(defn to-vector [b] (flint.rt/b->vec b))

(defn size [b] (flint.rt/b-count b))
(defn at [b i] (flint.rt/b-at b i))

(defn cat
  "Concatenate. O(1) once the pieces are big enough for a node to be worth more
  than the copy; below that it copies, which is what makes building one a byte
  at a time quadratic and what the transient is for."
  ([] (flint.rt/str->b ""))
  ([a] a)
  ([a b] (flint.rt/b-concat a b))
  ([a b & more] (reduce flint.rt/b-concat (flint.rt/b-concat a b) more)))

(defn slice
  "`[from to)`."
  [b from to]
  (flint.rt/b-slice b from to))

(defn empty-bytes [] (flint.rt/str->b ""))
