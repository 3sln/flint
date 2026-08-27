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

(defn depth
  "How deep the tree is. 0 for a flat byte string. A test hook: a rope that
  degenerates into a spine answers every question correctly and only gets
  slower, so the shape has to be observable."
  [b]
  (flint.rt/b-depth b))
(defn at [b i] (flint.rt/b-at b i))

(defn eq?
  "Content equality. `=` answers this too, but code that also runs on the
  bootstrap host cannot use it there: a host byte string is a Java array, and
  `=` on two arrays is identity."
  [a b]
  (flint.rt/b-eq? a b))

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

;; --- building one ----------------------------------------------------------
;;
;; A concatenation below the flat threshold COPIES, which is what keeps small
;; byte strings cheap and what makes building one piece by piece quadratic. A
;; transient owns a tail buffer and writes into it, so appending amortises to
;; O(1) and the copy happens once per full tail rather than once per append.

(defn transient-bytes
  "A transient starting from `b` (use `(empty-bytes)` to start from nothing)."
  [b]
  (flint.rt/b-transient b))

(defn conj-byte!
  "Append one byte."
  [t x]
  (flint.rt/b-conj! t x))

(defn append!
  "Append a whole byte string."
  [t b]
  (flint.rt/b-append! t b))

(defn size!
  "How many bytes are in the transient so far."
  [t]
  (flint.rt/b-tcount t))

(defn persist!
  "Freeze it. The transient cannot be used afterwards, and says so rather than
  writing into a buffer somebody else can now see."
  [t]
  (flint.rt/b-persistent! t))

(defn build
  "The common shape: run `f` over a fresh transient and freeze the result."
  [f]
  (persist! (f (transient-bytes (empty-bytes)))))
