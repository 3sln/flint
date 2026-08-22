(ns flint.canon
  "A canonical ordering for map and set literals.

  Maps and sets are unordered, and their iteration order genuinely differs
  between hosts -- babashka's HAMT and flint's CHAMP visit entries in different
  orders. Anywhere that order reaches the output bytes, the same source compiles
  to two different images depending on who compiled it, and the self-hosting
  fixpoint test fails for a reason that has nothing to do with the compiler.

  So map and set literals are sorted by a key that is identical on every host.
  Doubles go through their raw bits rather than their printed form, because
  printing a double is exactly the sort of thing two runtimes disagree about."
  (:require [flint.rt]))

(declare ckey)

(def SEP "\u001f")

(defn- join-keys [xs] (flint.rt/str-join (map (fn [x] (str (ckey x) SEP)) xs)))

(defn ckey
  "A total, host-independent ordering key for any form or constant."
  [v]
  (cond
    (nil? v) "0"
    (true? v) "1t"
    (false? v) "1f"
    (int? v) (str "2" (flint.rt/num->str v))
    (float? v) (str "3" (flint.rt/num->str (flint.rt/double-bits v)))
    (string? v) (str "4" v)
    (keyword? v) (str "5" (namespace v) "/" (name v))
    (symbol? v) (str "6" (namespace v) "/" (name v))
    (vector? v) (str "7" (join-keys v))
    (set? v) (str "8" (flint.rt/str-join (sort (map (fn [x] (str (ckey x) SEP)) v))))
    (map? v) (str "9" (flint.rt/str-join
                       (sort (map (fn [e] (str (ckey (key e)) SEP (ckey (val e)) SEP)) v))))
    (seq? v) (str "a" (join-keys v))
    :else (str "z" (str v))))

(defn sorted-entries
  "Map entries as [k v] pairs in canonical order."
  [m]
  (sort-by (fn [p] (str (ckey (first p)) SEP (ckey (second p))))
           (map (fn [e] [(key e) (val e)]) m)))

(defn sorted-elements
  "Set elements in canonical order."
  [s]
  (sort-by ckey (seq s)))
