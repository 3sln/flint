(ns collections)
(defn main [_]
  (pr-str
   {:vec (conj [1 2] 3)
    :nested [[1 [2 [3]]] {:a {:b [1 2]}}]
    :set (conj #{1 2} 3)
    :map (assoc {:a 1} :b 2)
    :get [(get {:a 1} :a) (get {:a 1} :z) (get {:a 1} :z :none)]
    :count [(count [1 2 3]) (count {:a 1}) (count "abc") (count nil)]
    :seq [(first [1 2]) (rest [1 2 3]) (first nil) (seq [])]
    :nth [(nth [1 2 3] 1) (nth [1 2 3] 9 :missing)]

    ;; `assoc` on a VECTOR replaces at an index and answers a vector. Both ports
    ;; fell through to the map branch and answered `{1 :B}` -- the right value
    ;; under the right key, and the wrong kind of collection, which then failed
    ;; several calls later on something that was no longer indexed. An index
    ;; equal to the count appends.
    :assoc-vec (let [v [:a :b :c] w (assoc v 1 :B)]
                 [w (vector? w) (map? w) (count w)
                  (assoc v 3 :d) (update v 0 (fn [_] :A))])

    ;; TRANSIENTS, which this suite had none of. That gap is what let a real
    ;; divergence sit: persisting an EMPTY transient asks `nodeClone` for a
    ;; zero-length tail, and both ports had a `max(n, 1)` floor that native
    ;; did not, so the same empty vector was one object width on native and
    ;; another on the ports. Nothing here could reach the line.
    ;;
    ;; The empty case first because it is the one that was wrong, then the
    ;; boundaries the trie actually turns on -- a full tail at 32, one past it
    ;; at 33, and a second level at 1025.
    :transient-vec [(persistent! (transient []))
                    (count (persistent! (reduce conj! (transient []) (range 32))))
                    (count (persistent! (reduce conj! (transient []) (range 33))))
                    (count (persistent! (reduce conj! (transient []) (range 1025))))
                    (let [t (transient [:a :b :c])]
                      [(count (persistent! (assoc! t 1 :B)))
                       (persistent! (transient [:x]))])]

    ;; POP, both kinds. `pop!` had no builtin on any runtime and answered `()`;
    ;; the ports had no vector `pop` either and REBUILT the vector with a conj
    ;; loop, O(n) against native's O(log n). Same answers, so nothing failed.
    ;;
    ;; The interesting inputs are the boundaries where the trie actually
    ;; changes shape: 33 -> 32 empties the tail and pulls the previous leaf
    ;; back out, and 1025 -> 1024 drops a whole level.
    :pop-vec [(pop [1 2 3]) (pop [1])
              (count (pop (vec (range 33))))
              (count (pop (vec (range 1025))))]
    :pop-transient [(persistent! (pop! (transient [:x])))
                    (persistent! (pop! (reduce conj! (transient []) (range 3))))
                    (count (persistent! (pop! (reduce conj! (transient []) (range 33)))))
                    (count (persistent! (pop! (reduce conj! (transient []) (range 1025)))))]

    :transient-map [(persistent! (transient {}))
                    (persistent! (assoc! (transient {}) :a 1))
                    (count (persistent! (reduce (fn [m i] (assoc! m i i))
                                                (transient {}) (range 40))))
                    (persistent! (dissoc! (transient {:a 1 :b 2}) :a))]

    :transient-set [(persistent! (transient #{}))
                    (count (persistent! (reduce conj! (transient #{}) (range 40))))
                    (persistent! (disj! (transient #{:a :b}) :a))]}))
