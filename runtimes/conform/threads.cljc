(ns threads)
(def counter (atom 0))
(defn tally [] (swap! counter inc))
(defn work [n] (reduce + 0 (map (fn [i] (* i i)) (range n))))
;; `main` has to REACH both, or tree shaking is right to remove them
;; (`DECISIONS.md#modularity`) and the host finds nothing to call.
(defn main [_] (str (work 100) " " (tally)))
