(ns numbers)
(defn main [_]
  (pr-str
   {:int [(+ 1 2) (- 10 3) (* 6 7) (quot 7 2) (rem 7 2)]
    :cmp [(< 1 2 3) (<= 2 2) (> 3 1) (>= 2 3) (= 1 1) (= 1 2)]
    :float [(+ 1.5 2.5) (* 2.0 3) (/ 1.0 4)]
    ;; A whole double prints with its `.0`, which is a classic silent
    ;; divergence between hosts (`doc/decisions/0010`).
    :printing [(str 4.0) (str 1) (pr-str 2.5)]
    :mixed [(+ 1 2.0) (= 1 1.0)]}))
