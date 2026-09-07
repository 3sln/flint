(ns colls
  "The bulk collection operations, one per mode, each with a matching BASE mode
  that does the same setup and skips the operation. The first version of this
  built every input in every mode -- two 20 000-entry maps and 20 000 strings --
  so the setup swamped the thing being measured and `transient` looked 1.05x
  better than `persistent`, which is not what a transient is for.

  Measured on ALLOCATIONS as well as time. A `reduce` that walks a vector
  through `seq`/`first`/`next` allocates a seq step per element; in a benchmark
  that collects a couple of times, wall-clock cannot see it, and in a 128 MiB
  isolate it is entirely real.")

(defn- ints [n] (vec (range n)))
(defn- pairs [n] (mapv (fn [i] [(flint.rt/num->str i) i]) (range n)))

(defn main [args]
  (let [what (first args)
        n (flint.rt/str->num (second args))
        ;; `subs` on a name shorter than the prefix is an error, not nil, so
        ;; the length is checked first.
        base? (if (flint.rt/lt (flint.rt/count what) 5)
                false
                (flint.rt/= "base-" (flint.rt/subs what 0 5)))
        op (if base? (flint.rt/subs what 5 (flint.rt/count what)) what)
        r (cond
            ;; --- transients against persistents, the thing itself -----------
            (flint.rt/= op "conj") (let [v (ints n)]
                                     (if base? (count v)
                                         (count (reduce (fn [t x] (conj t x)) [] v))))
            (flint.rt/= op "conj!") (let [v (ints n)]
                                      (if base? (count v)
                                          (count (persistent!
                                                  (reduce (fn [t x] (conj! t x))
                                                          (transient []) v)))))
            (flint.rt/= op "assoc") (let [v (ints n)]
                                      (if base? (count v)
                                          (count (reduce (fn [t x] (assoc t x x)) {} v))))
            (flint.rt/= op "assoc!") (let [v (ints n)]
                                       (if base? (count v)
                                           (count (persistent!
                                                   (reduce (fn [t x] (assoc! t x x))
                                                           (transient {}) v)))))
            ;; --- the bulk builders ------------------------------------------
            (flint.rt/= op "merge") (let [a (into {} (pairs n)) b (into {} (pairs n))]
                                      (if base? (count a) (count (merge a b))))
            (flint.rt/= op "merge-with")
            (let [a (into {} (pairs n)) b (into {} (pairs n))]
              (if base? (count a) (count (merge-with (fn [x y] x) a b))))
            (flint.rt/= op "select-keys")
            (let [ps (pairs n) m (into {} ps) ks (mapv first ps)]
              (if base? (count ks) (count (select-keys m ks))))
            (flint.rt/= op "group-by") (let [v (ints n)]
                                         (if base? (count v)
                                             (count (group-by (fn [x] (flint.rt/rem x 97)) v))))
            (flint.rt/= op "distinct")
            (let [v (mapv (fn [x] (flint.rt/rem x 1000)) (ints n))]
              (if base? (count v) (count (distinct v))))
            (flint.rt/= op "zipmap") (let [ps (pairs n) ks (mapv first ps) v (ints n)]
                                       (if base? (count ks) (count (zipmap ks v))))
            (flint.rt/= op "dedupe")
            (let [v (mapv (fn [x] (flint.rt/quot x 3)) (ints n))]
              (if base? (count v) (count (dedupe v))))
            ;; Building a string by repeated `str`. A rope makes each join
            ;; O(1) instead of a copy, but it still allocates a NODE per join
            ;; and leaves a right-leaning tree. This is the case a transient
            ;; byte/char string would coalesce into a tail buffer.
            (flint.rt/= op "str-build")
            (let [v (mapv (fn [i] (flint.rt/num->str i)) (ints n))]
              (if base? (count v)
                  (count (reduce (fn [acc x] (flint.rt/str2 acc x)) "" v))))
            ;; The same thing through a vector and one join at the end, which
            ;; is what a transient would approximate.
            (flint.rt/= op "str-join")
            (let [v (mapv (fn [i] (flint.rt/num->str i)) (ints n))]
              (if base? (count v) (count (flint.rt/str-join v))))
            ;; The reason byte strings exist: the same bytes, held as a vector
            ;; of integers and as a byte string. A flint vector holds NaN-boxed
            ;; 64-bit values, so a byte costs eight bytes plus trie overhead.
            (flint.rt/= op "bytes-as-vec")
            (let [s (flint.rt/str-join (mapv (fn [i] "0123456789abcdef") (range (flint.rt/quot n 16))))]
              (if base? (flint.rt/count s) (count (flint.rt/str-bytes s))))
            (flint.rt/= op "bytes-as-bytes")
            (let [s (flint.rt/str-join (mapv (fn [i] "0123456789abcdef") (range (flint.rt/quot n 16))))]
              (if base? (flint.rt/count s) (flint.rt/b-count (flint.rt/str->b s))))
            ;; Appending a byte at a time, persistently and through a
            ;; transient. This is what an output builder does, and the case
            ;; where every join is under the flat threshold so every one copies.
            (flint.rt/= op "byte-cat")
            (if base? n
                (flint.rt/b-count
                 (loop [i 0 acc (flint.rt/str->b "")]
                   (if (flint.rt/lt i n)
                     (recur (flint.rt/add i 1)
                            (flint.rt/b-concat acc (flint.rt/vec->b [(flint.rt/rem i 256)])))
                     acc))))
            (flint.rt/= op "byte-trans")
            (if base? n
                (flint.rt/b-count
                 (flint.rt/b-persistent!
                  (loop [i 0 t (flint.rt/b-transient (flint.rt/str->b ""))]
                    (if (flint.rt/lt i n)
                      (recur (flint.rt/add i 1) (flint.rt/b-conj! t (flint.rt/rem i 256)))
                      t)))))
            ;; A SWEEP, not a claim about any one of these. `sort` was found
            ;; at 74.7 allocations per element while its neighbours sat near
            ;; one, so the useful question is which of the bulk operations is
            ;; an outlier -- and the answer is only visible with a matching
            ;; BASE mode subtracting the same setup.
            (flint.rt/= op "mapv") (let [v (ints n)]
                                     (if base? (count v) (count (mapv inc v))))
            (flint.rt/= op "filterv") (let [v (ints n)]
                                        (if base? (count v)
                                            (count (filterv even? v))))
            (flint.rt/= op "into-vec") (let [v (ints n)]
                                         (if base? (count v) (count (into [] v))))
            (flint.rt/= op "reverse") (let [v (ints n)]
                                        (if base? (count v) (count (reverse v))))
            (flint.rt/= op "concat") (let [v (ints n)]
                                       (if base? (count v)
                                           (count (vec (concat v v)))))
            (flint.rt/= op "interleave") (let [v (ints n)]
                                           (if base? (count v)
                                               (count (vec (interleave v v)))))
            (flint.rt/= op "partition") (let [v (ints n)]
                                          (if base? (count v)
                                              (count (vec (partition 4 v)))))
            (flint.rt/= op "keys") (let [m (into {} (pairs n))]
                                     (if base? (count m) (count (vec (keys m)))))
            (flint.rt/= op "vals") (let [m (into {} (pairs n))]
                                     (if base? (count m) (count (vec (vals m)))))
            (flint.rt/= op "frequencies") (let [v (ints n)]
                                            (if base? (count v)
                                                (count (frequencies v))))
            (flint.rt/= op "map-lazy") (let [v (ints n)]
                                         (if base? (count v)
                                             (count (vec (map inc v)))))
            (flint.rt/= op "filter-lazy") (let [v (ints n)]
                                            (if base? (count v)
                                                (count (vec (filter even? v)))))
            ;; SORT, on allocations. `merge-sort` is the only caller of
            ;; `subvec`, and the interesting number is not the clock -- the
            ;; top-down version sliced a fresh vector per level and merged
            ;; through `first`/`next`, so it allocated per element per pass.
            (flint.rt/= op "sort")
            (let [v (vec (map (fn [i] (flint.rt/rem (flint.rt/mul i 37) 19)) (range n)))]
              (if base? (count v) (count (sort v))))
            (flint.rt/= op "reduce") (let [v (ints n)]
                                       (if base? (count v)
                                           (reduce (fn [a x] (flint.rt/add a x)) 0 v)))
            :else 0)]
    (flint.rt/num->str r)))
