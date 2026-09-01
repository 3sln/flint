(ns gasmeter
  "The same program costs the same GAS on every runtime (`doc/decisions/0009`).

  Two workloads, one a scaled-up version of the other. A caller reports both,
  and what is compared across runtimes is the DIFFERENCE -- which removes
  whatever each runtime spends getting started and leaves only what the program
  did. An exact claim rather than an approximate one.

  It exists because the answer was no. The ports allocated a FRESH EMPTY
  collection every time `empty` was called where the Rust runtime returns a
  singleton, so a map literal cost 24 bytes more on them and an empty vector
  cost three objects. With allocation charged as gas that made the same program
  bill 143 247 steps on the JVM against 137 207 native -- a 37% gap on
  allocation-heavy code, invisible on loop-heavy code, and invisible to every
  other row here because they all diff ANSWERS.

  A budget that fits on one runtime has to fit on the others, or the mirror
  stops at the answers."
  (:require [flint.table :as ft]))

(def S (ft/schema [[:id :int] [:name :string]]))

(defn- work [n]
  ;; Deliberately across the allocators that differ most: array-maps, vectors,
  ;; sets, strings, seqs and tables.
  [(count (mapv (fn [i] {:a i :b (str i)}) (range n)))
   (count (into #{} (range n)))
   (count (into [] (map inc (range n))))
   (count (apply str (mapv str (range (quot n 4)))))
   (count (ft/build S (range n) (fn [i] {:id i :name (str i)})))
   (reduce + 0 (mapv :id (ft/rows (ft/build S (range (quot n 2))
                                            (fn [i] {:id i :name "x"})))))])

; TWO ENTRY POINTS, compiled and measured separately. The comparison is
;; `big - small`, so whatever each runtime spends starting up cancels and what
;; is left is the work -- which is the only part that has to agree.
(defn small [_] (pr-str (work 100)))
(defn big [_] (pr-str (work 400)))
(defn main [_] (pr-str [(work 100) (work 400)]))
