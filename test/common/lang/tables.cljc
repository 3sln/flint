(ns lang.tables
  "Row refs, and the rooting they depend on.

  `runtimes/conform/tables.cljc` already diffs table behaviour across the
  runtimes. It cannot catch what is here: all four runtimes held the SAME
  rooting bug, so all four gave the same wrong answer and the diff saw
  nothing. That is the blind spot `test/common/README.md` describes, and the
  reason a check with an answer of its own belongs in this directory."
  (:require [flint.check :refer [expect]]
            [flint.table :as ft]))

(def S (ft/schema [[:a :int] [:b :int] [:c :int] [:d :int] [:e :int]]))

;; WIDE on purpose. `refToMap` allocates one map per column, so the number of
;; columns is how much collection pressure a single comparison creates -- and
;; the bug needs a collection to land inside that call, not merely somewhere.
(def W (ft/schema (mapv (fn [i] [(keyword (str "c" i)) :int]) (range 24))))
(def WROW (into {} (mapv (fn [i] [(keyword (str "c" i)) i]) (range 24))))

(defn ^:flint.check/test a-row-ref-is-equal-to-its-map []
  (let [t (ft/table S [{:a 1 :b 2 :c 3 :d 4 :e 5}])
        row (first (ft/rows t))]
    (expect = true (= row {:a 1 :b 2 :c 3 :d 4 :e 5}))
    (expect = true (= {:a 1 :b 2 :c 3 :d 4 :e 5} row))
    (expect = false (= row {:a 1 :b 2 :c 3 :d 4 :e 6}))))

(defn ^:flint.check/test row-ref-equality-survives-a-collection-inside-eq []
  ;; Comparing a row ref to a map materialises the ref with `refToMap`, which
  ;; allocates a map and assoc's every column into it -- so it can COLLECT
  ;; part way through the comparison. `a-vec-of-values-is-not-a-root`: a value in a host local does not
  ;; survive an allocation. Reading the second operand after materialising the
  ;; first was reading the address it used to be at.
  ;;
  ;; It took four thousand comparisons to see once, and only when the map is
  ;; young enough for the nursery to move it -- which is why the map is built
  ;; fresh inside the loop and the allocation before each comparison varies.
  ;; A top-level `def` is the wrong shape: it gets promoted, and a promoted
  ;; object does not move when the nursery collects, so the test would pass
  ;; for a reason that has nothing to do with the question.
  (let [wrong (loop [i 0 bad 0]
                (if (>= i 4000)
                  bad
                  (let [t (ft/table W [WROW])
                        row (first (ft/rows t))
                        _ (vec (range (mod i 97)))
                        m (into {} (mapv (fn [j] [(keyword (str "c" j)) j])
                                         (range 24)))]
                    (recur (inc i) (if (= row m) bad (inc bad))))))]
    (expect = 0 wrong)))

(defn ^:flint.check/test a-row-ref-answers-every-map-operation []
  ;; `map?` says true for a row ref, so every map operation must work on one
  ;; -- and `dissoc` did not: it returned NIL on all four runtimes while
  ;; `assoc`, `get`, `count` and `=` on the same ref were all correct.
  ;;
  ;; The cause was `dissoc`'s builtin guarding on `is_map`, which includes a
  ;; ref, and then calling a `map_dissoc` with no ref arm -- so it fell into
  ;; the default and returned nil. A differential test across runtimes could
  ;; not see it, because all four fell the same way.
  ;;
  ;; It materialises rather than dropping a column: the schema is CLOSED, so
  ;; there is no row ref with one column missing.
  (let [t (ft/table S [{:a 1 :b 2 :c 3 :d 4 :e 5}])
        row (first (ft/rows t))]
    (expect = true (map? row))
    (expect = 5 (count row))
    (expect = {:a 1 :c 3 :d 4 :e 5} (dissoc row :b))
    (expect = {:a 1 :b 2 :c 3 :d 4 :e 5} (dissoc row :zz))
    (expect = {:a 1 :b 2 :c 3 :d 4 :e 5 :f 6} (assoc row :f 6))
    (expect = 2 (get row :b))
    (expect = true (contains? row :b))
    ;; dissoc twice: the first result is a plain map, so the second is the
    ;; ordinary path -- worth pinning that the materialised value behaves.
    (expect = {:a 1 :d 4 :e 5} (dissoc (dissoc row :b) :c))))

(defn ^:flint.check/test a-row-ref-can-be-a-transient-source []
  ;; `merge` builds a transient from its FIRST source, so merging onto a row
  ;; required a row to be transientable. It was not, and the two answers were
  ;; wrong in different ways: native threw "not transientable", while both
  ;; ports guarded with `isMap` -- true for a ref -- and then read the ref's
  ;; slots as an array-map's, answering `{:z 9, 1 2}`. Garbage rather than a
  ;; refusal, which is the worse of the two failures.
  (let [t (ft/table S [{:a 1 :b 2 :c 3 :d 4 :e 5}])
        row (first (ft/rows t))]
    (expect = {:a 1 :b 2 :c 3 :d 4 :e 5 :z 9} (merge row {:z 9}))
    (expect = {:a 1 :b 2 :c 3 :d 4 :e 5} (merge row {}))
    (expect = {:a 1 :b 2 :c 3 :d 4 :e 5 :z 9} (merge {} row {:z 9}))
    ;; A value the row already has must be OVERWRITTEN by the later source,
    ;; which is what `merge` means and what a wrong transient would lose.
    (expect = 99 (:b (merge row {:b 99})))
    (expect = {:a 1 :b 2 :c 3 :d 4 :e 5} (into {} row))
    (expect = 15 (reduce-kv (fn [acc _ v] (+ acc v)) 0 row))))
