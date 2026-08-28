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
                  (assoc v 3 :d) (update v 0 (fn [_] :A))])}))
