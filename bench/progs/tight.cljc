(ns tight
  "Dispatch-bound: almost no allocation and no data structure work, so nearly
  all of the time is the interpreter's instruction dispatch. Pairs with
  `maps.cljc`, which is the opposite, to separate the two costs.")
(defn run [n]
  (loop [i 0 acc 0]
    (if (< i n) (recur (inc i) (+ acc i)) acc)))
(defn main [args]
  (str (run (if (seq args) (parse-long (first args)) 1000000))))
