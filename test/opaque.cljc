(ns opaque
  "Opaque values: identity without structure (`doc/decisions/0022`).

  flint has no host classes, so it had no `(Object.)` -- and no way to say the
  things `(Object.)` says in Clojure: this key is absent rather than present
  and nil, this marker is mine and nobody can collide with it.

  The test that matters most here is the collection one. The nursery is a
  copying collector, so an identity hash derived from the address would change
  when the object moved, and a value used as a map key would stop being findable
  by the key that put it there. 0022 names it as the single most likely thing to
  get wrong, and the reason is that it fails intermittently and under load."
  (:require [clojure.string :as str]))

(defn- churn
  "Allocate enough to force several collections, and return something so the
  work cannot be elided."
  [n]
  (loop [i 0 acc 0]
    (if (< i n)
      (recur (inc i) (+ acc (count (str "filler-" i))))
      acc)))

(defn main [_]
  (let [a (opaque)
        b (opaque)
        labelled (opaque "fs")
        same-label (opaque "fs")]
    (pr-str
     {;; Equality is identity, and there is no structure to compare.
      :self-equal (= a a)
      :distinct (not= a b)
      :identical (identical? a a)
      :label-is-not-identity (not= labelled same-label)
      :is-opaque [(opaque? a) (opaque? "x") (opaque? nil) (opaque? 42)]

      ;; The idiom the type exists for: absent vs present-and-nil.
      :absent-vs-nil (let [miss (opaque)
                           m {:k nil}]
                       [(identical? miss (get m :k miss))
                        (identical? miss (get m :missing miss))])

      ;; Printing reveals the label and nothing else. There is no read syntax,
      ;; deliberately -- a printed form that reads back is forgeable.
      :printing [(pr-str a) (pr-str labelled) (str labelled)]

      ;; THE ONE THAT MATTERS. 300 opaque keys in a map, with enough allocation
      ;; between building and looking up to move every one of them.
      ;; The stats are part of the ASSERTION, not decoration: 0 missing from a
      ;; heap that never moved anything is not evidence. `bytes-copied` counts
      ;; live data that was relocated, so it has to exceed the size of the keys
      ;; themselves before the zero means what it looks like.
      :survives-collection
      (let [before (flint.rt/gc-stats)
            ks (mapv (fn [i] (opaque (str "k" i))) (range 300))
            m (loop [i 0 m {}]
                (if (< i 300) (recur (inc i) (assoc m (nth ks i) i)) m))
            _ (churn 40000)
            after (flint.rt/gc-stats)
            missing (count (filter (fn [i] (not= i (get m (nth ks i)))) (range 300)))]
        [(count m) missing
         (- (:minor after) (:minor before))
         (- (:bytes-copied after) (:bytes-copied before))])

      ;; And in a set, which hashes by the same path.
      :survives-in-set
      (let [ks (mapv (fn [_] (opaque)) (range 200))
            s (reduce conj #{} ks)
            _ (churn 40000)]
        [(count s) (count (filter (fn [k] (not (contains? s k))) ks))])

      ;; A distinct hash, not one constant per type: 300 keys must not all land
      ;; in one bucket. Checked by their hashes being mostly different.
      :hashes-differ (let [hs (map hash (mapv (fn [_] (opaque)) (range 100)))]
                       (> (count (set hs)) 90))

      ;; Stable across a collection, which is the property the stored hash buys.
      :hash-stable (let [o (opaque) h1 (hash o) _ (churn 40000)] (= h1 (hash o)))

      ;; And across a MAJOR collection. The old generation is non-moving
      ;; mark-sweep, so what a major tests is not relocation but survival of the
      ;; sweep -- an opaque value is `Layout::Vals` with a label reference, and
      ;; a type the marker did not know about would be swept as garbage.
      :survives-major
      (let [before (flint.rt/gc-stats)
            ks (mapv (fn [i] (opaque (str "m" i))) (range 400))
            m (loop [i 0 m {}]
                (if (< i 400) (recur (inc i) (assoc m (nth ks i) i)) m))
            ;; RETAINED, not churned: garbage never reaches the old generation,
            ;; so filling it needs data that survives. Without this the test ran
            ;; 16 minor collections and zero majors, and asserted nothing about
            ;; the sweep.
            ballast (loop [i 0 acc []]
                      (if (< i 24000) (recur (inc i) (conj acc (str "ballast-" i))) acc))
            _ (count ballast)
            after (flint.rt/gc-stats)
            missing (count (filter (fn [i] (not= i (get m (nth ks i)))) (range 400)))
            ;; The LABEL is a heap string reachable only through the opaque
            ;; value, so this also proves the collector traced through one.
            bad-labels (count (filter (fn [i] (not= (str "m" i) (opaque-label (nth ks i))))
                                      (range 400)))]
        [(count m) missing bad-labels
         (- (:major after) (:major before))
         (- (:minor after) (:minor before))])})))
