(ns conjshapes
  "`conj` on every shape it can meet, including the ones it should refuse.

  WHAT PROMPTED IT: `conj`'s three implementations are not the same shape.
  Native has a named `conj` in `coll.rs` that dispatches on the type tag and
  falls through to CONS for any heap type it does not recognise. Both ports
  inline the same decision into the builtin table and fall through to a THROW
  unless the value is a seq.

  Those two fallbacks agree only if every heap type reaching them is a seq.
  Nothing checked that. `collections.cljc` conjes onto a vector and a set --
  the two cases that were never in doubt -- and stops.

  So this asserts the EDGES: what conj does to a string, to a lazy seq, to a
  map given something that is not an entry, to a number. Each answer is the
  value or the exception CLASS AND MESSAGE, because a divergence in the class
  changes which `catch` runs and a divergence in the message is what a person
  reads.

  A case that throws on all three is not a failure here. A case that throws on
  one is the whole point.

  WHAT IT FOUND, first run: both ports answered `{:a 1, nil nil}` for
  `(conj {:a 1} 7)` and `{:a 1, [:b 2] nil}` for `(conj {:a 1} {:b 2})`. They
  took `first` and `first (rest ..)` of whatever arrived without checking it
  was an entry, so a non-entry SILENTLY CORRUPTED the map instead of being
  refused, and merging a map did not merge.

  ONE DELIBERATE DIVERGENCE FROM CLOJURE is recorded rather than matched.
  Clojure throws `IllegalArgumentException` for `(conj {:a 1} 7)` and
  `ClassCastException` for `(conj {:a 1} \"b\")` -- the class depends on
  whether the value happens to be seqable, which is its implementation
  showing through. flint answers `IllegalArgumentException` for both."
  (:require [clojure.string :as str]))

(defn- try-conj
  "`(conj coll x)` as text: the value, or the exception class and message."
  [coll x]
  (try (pr-str (conj coll x))
       (catch Exception e (str (flint.rt/ex-kind e) ": " (ex-message e)))))

(defn main [_]
  (pr-str
   {;; THE CASES NOBODY DOUBTED, here so a failure elsewhere is not blamed on
    ;; the harness.
    :vector    (try-conj [1 2] 3)
    :set       (try-conj #{1 2} 3)
    :list      (try-conj '(1 2) 3)
    :nil       (try-conj nil 3)

    ;; A MAP takes an entry or a two-element vector, and merges another map.
    :map-vec   (try-conj {:a 1} [:b 2])
    :map-entry (try-conj {:a 1} (first {:b 2}))
    :map-map   (try-conj {:a 1} {:b 2})
    :map-bad   (try-conj {:a 1} 7)
    :map-str   (try-conj {:a 1} "b")

    ;; A MAP ENTRY is a vector, so conj APPENDS rather than consing --
    ;; `(conj (first {:a 1}) 9)` is `[:a 1 9]` in Clojure. It is ALSO
    ;; sequential, so the other reading exists and is not the one taken.
    :entry     (try-conj (first {:a 1}) 9)

    ;; THE FALLBACK ARMS, which is what this file is for. A lazy seq is a heap
    ;; type that IS a seq; a string is a heap type that is seqABLE but is not
    ;; a seq; a number is not a heap value at all.
    :lazy      (try-conj (map inc [1 2]) 9)
    :range     (try-conj (range 3) 9)
    :string    (try-conj "ab" \c)
    :keyword   (try-conj :kw 1)
    :number    (try-conj 1 2)
    :bool      (try-conj true 1)

    ;; MULTIPLE ARGUMENTS fold left, and the order matters on a seq where
    ;; conj prepends.
    :many-vec  (pr-str (conj [1] 2 3 4))
    :many-list (pr-str (conj '(1) 2 3 4))}))
