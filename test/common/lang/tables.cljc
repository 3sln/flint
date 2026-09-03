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
  ;; part way through the comparison. `0031`: a value in a host local does not
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
