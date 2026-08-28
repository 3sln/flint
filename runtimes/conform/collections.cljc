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
    :nth [(nth [1 2 3] 1) (nth [1 2 3] 9 :missing)]}))
