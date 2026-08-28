(ns numbers)
(defn main [_]
  (pr-str
   {:int [(+ 1 2) (- 10 3) (* 6 7) (quot 7 2) (rem 7 2)]
    :cmp [(< 1 2 3) (<= 2 2) (> 3 1) (>= 2 3) (= 1 1) (= 1 2)]
    :float [(+ 1.5 2.5) (* 2.0 3) (/ 1.0 4)]
    ;; A whole double prints with its `.0`, which is a classic silent
    ;; divergence between hosts (`doc/decisions/0010`).
    :printing [(str 4.0) (str 1) (pr-str 2.5)]
    :mixed [(+ 1 2.0) (= 1 1.0)]

    ;; A loop counter's type is HYPOTHESISED from its initialiser and kept only
    ;; if every recur proves it. These pin both halves of that.
    ;;
    ;; `counted` holds the hypothesis: it starts at an int and stays one, so
    ;; the specialised integer opcodes apply and the answer had better be the
    ;; same as it was without them.
    :counted [(loop [i 0 acc 0]
                (if (< i 10) (recur (+ i 1) (+ acc (* i i))) acc))]

    ;; `widened` BREAKS it: the slot starts as an int and a recur puts a float
    ;; in it. That is legal, so the hypothesis has to be thrown away rather
    ;; than enforced -- if it were enforced this would throw, and if it were
    ;; believed anyway the arithmetic would be wrong.
    :widened [(loop [x 0 n 0]
                (if (< n 3) (recur (+ x 0.5) (+ n 1)) x))]

    ;; And the same slot reached from two different recurs, only one of which
    ;; is an int. The hypothesis has to fail on the strength of the worse one.
    :branching [(loop [v 1 n 0]
                  (cond (>= n 4) v
                        (= 0 (rem n 2)) (recur (* v 2) (+ n 1))
                        :else (recur (/ v 4.0) (+ n 1))))]}))
