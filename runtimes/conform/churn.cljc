(ns churn
  "The same answers, UNDER COLLECTION (`doc/decisions/0031`).

  Every other conformance program is small enough to run inside one nursery.
  That is a hole: a rooting bug -- a value held in a HOST local across an
  allocation -- is invisible until a collection actually moves the object, so
  the suite could be entirely green while a port wrote through forwarded
  pointers.

  It was. `(mapv #(into {} m) (range 600))` answered on the native runtime and
  threw `object type 1 is not a transient` on both ports, type 1 being `TY_FWD`.
  The cause was `conj!` onto a transient map: the accumulator was read as an
  argument, and the two argument expressions after it allocated.

  So this program does ordinary things MANY TIMES, to make the collector run
  during them. Nothing here is exotic; the volume is the test."
  (:require [flint.table :as ft]))

(def N 2000)
(defn- m [i] {:a i :b (str "s" i) :c :k :d [i i] :e {:n i}})

(defn main [_]
  (pr-str
   {;; `into` a map, over and over: the case that found the bug.
    :into-maps   (count (mapv (fn [i] (into {} (m i))) (range N)))
    :into-seq    (count (mapv (fn [i] (into {} (seq (m i)))) (range N)))
    :into-vecs   (count (mapv (fn [i] (into [] (vals (m i)))) (range N)))
    :into-sets   (count (mapv (fn [i] (into #{} (keys (m i)))) (range N)))
    ;; Transients threaded through a reduce, which is what `into` compiles to.
    :assoc-bang  (count (persistent! (reduce (fn [t i] (assoc! t i (str i)))
                                             (transient {}) (range N))))
    :conj-bang   (count (persistent! (reduce (fn [t i] (conj! t (m i)))
                                             (transient []) (range N))))
    ;; Strings and byte strings churn the other allocators.
    :strs        (count (reduce (fn [acc i] (str acc i)) "" (range 400)))
    :merged      (count (reduce merge {} (mapv m (range 400))))
    :sorted      (count (sort (mapv (fn [i] (- N i)) (range N))))
    :grouped     (count (group-by (fn [x] (mod x 7)) (range N)))
    :freqs       (count (frequencies (mapv (fn [i] (mod i 13)) (range N))))
    ;; And the same pressure with tables in the mix, since a row ref is a map
    ;; that materialises on demand and so allocates where a map does not.
    :table-rows  (let [t (ft/build (ft/schema [[:id :int] [:name :string]])
                                   (range N) (fn [i] {:id i :name (str i)}))]
                   [(count t)
                    (count (mapv (fn [r] (into {} r)) (ft/rows t)))
                    (reduce + 0 (mapv :id (ft/rows t)))
                    (ft/reduce-column t :id + 0)])}))
