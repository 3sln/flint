(ns clojure.datafy
  "Turn values into data, and follow the data further.

  `datafy` asks a value to present itself as data; `nav` asks a collection how
  to follow one of its entries. Both are open: a value supplies its own by
  carrying ``clojure.core.protocols/datafy`` or the same for `nav` in its
  metadata -- the fully-qualified symbol, which is what a syntax quote writes.

  One divergence from Clojure, recorded in the README: when `datafy` returns
  something other than what it was given, Clojure records the original under
  `:clojure.datafy/obj` and the original's CLASS NAME under
  `:clojure.datafy/class`. flint has no classes, so the second is
  `:clojure.datafy/kind` and holds a `clojure.core/kind` keyword."
  (:require [clojure.core.protocols :as p]))

;; Not every value can carry metadata: numbers, and the short strings,
;; keywords and chars that live in the value word itself, have nowhere to hang
;; a map. Clojure's guard here is `(instance? IObj v)`; this is the same guard
;; written in kinds.
(defn- metable? [v]
  (let [k (kind v)]
    (or (= k :vector) (= k :map) (= k :set) (= k :list) (= k :fn))))

(defn datafy
  "`x` as data.

  The default is `x` itself. When a value transforms, the result carries
  `:clojure.datafy/obj` (the original) and `:clojure.datafy/kind` in its
  metadata, so a caller that walked into the data can find its way back out."
  [x]
  (let [v (p/datafy x)]
    (if (identical? v x)
      v
      (if (metable? v)
        (vary-meta v (fn [m] (assoc m :clojure.datafy/obj x
                                    :clojure.datafy/kind (kind x))))
        v))))

(defn nav
  "The value `v`, found at `k` in `coll`, in that context.

  Callers should pass the key or index when the collection has one and `nil`
  when it does not -- a sequence has no key worth inventing."
  [coll k v]
  (p/nav coll k v))
