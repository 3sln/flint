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

;; `flint.rt/array-map` over a LAZY seq, big enough to span a collection.
;;
;; This is the shape that hid a stale-value bug for the life of the runtime.
;; `ordered_map` gathered its values into a Rust `Vec<Value>` -- which the
;; collector does not scan -- and both calls in its walk can collect: `first`
;; forces a lazy seq and `next` forces the tail, which runs arbitrary flint
;; code. Everything gathered before the first collection went stale, and was
;; then written into the map.
;;
;; It needs THREE things at once and that is why nothing caught it: a lazy
;; input, so the walk allocates; enough pairs to span a collection; and a
;; runtime that notices, which is `stat_stale_set`/`stat_stale_push` in
;; `test/gc_stress.clj`. A literal map, or a short one, or a realised seq is
;; correct either way.
(defn probe-ordered [n]
  (let [kvs (mapcat (fn [i] [(str "k" i) [:v i]]) (range n))
        m (flint.rt/array-map kvs)]
    [(count m) (count (filter (fn [i] (not= [:v i] (get m (str "k" i)))) (range n)))]))

;; The codec has the SAME walk on the other side of the boundary and had the
;; same fault, but `encode` is internal to the port boundary rather than a
;; builtin, so it cannot be reached from here. `test/threads.clj` and
;; `test/capability.clj` send collections through ports; what neither does is
;; send a LAZY one under collection pressure, which is the combination that
;; matters. Named here so the gap is on the record.

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
             :sorted     (= [[1 2] [1 3] [2 0]] (sort (list [2 0] [1 3] [1 2])))
             :ordered    (probe-ordered 4000)})))
