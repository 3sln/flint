(ns gcstress
  "Collections with COMPOUND keys, big enough to collect several times while
  building.

  This is a regression test for a specific class of bug rather than a smoke
  test: `=` and `hash` on a vector, map or set allocate (they walk the value
  through `seq`/`first`/`next`), so a collection can run *in the middle of* a
  map lookup or insert. Every raw address the surrounding code was holding is
  then stale. The symptoms were a key silently missing from a map whose `count`
  said it was there, and a hash cached into a moved object -- a corrupted heap.
  Scalar keys never showed it, because hashing a fixnum allocates nothing.")

(defn probe-map [kf n]
  (let [m (loop [i 0 m {}] (if (< i n) (recur (inc i) (assoc m (kf i) i)) m))
        missing (count (filter (fn [i] (not= i (get m (kf i)))) (range n)))]
    [(count m) missing]))

(defn probe-set [kf n]
  (let [s (loop [i 0 s #{}] (if (< i n) (recur (inc i) (conj s (kf i))) s))]
    [(count s) (count (filter (fn [i] (not (contains? s (kf i)))) (range n)))]))

(defn probe-transient [kf n]
  (let [m (persistent! (loop [i 0 t (transient {})]
                         (if (< i n) (recur (inc i) (assoc! t (kf i) i)) t)))]
    [(count m) (count (filter (fn [i] (not= i (get m (kf i)))) (range n)))]))

(defn main [_]
  (let [n 30000
        vec-key (fn [i] [:sym i])
        mix-key (fn [i] [:sym i (str "name-" i)])
        deep-key (fn [i] [:sym i (str "name-" i) {:a i :b (str i)} #{i (str i)}])]
    (pr-str {:vec        (probe-map vec-key n)
             :mixed      (probe-map mix-key n)
             :deep       (probe-map deep-key 20000)
             :list       (probe-map (fn [i] (list :sym i (str i))) n)
             :set        (probe-set vec-key n)
             :transient  (probe-transient mix-key n)
             ;; = with three compound arguments: the first was read once and
             ;; then compared repeatedly across allocations.
             :eq3        (= [1 [2 3]] [1 [2 3]] [1 [2 3]])
             :sorted     (= [[1 2] [1 3] [2 0]] (sort (list [2 0] [1 3] [1 2])))})))
