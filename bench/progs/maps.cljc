(ns maps
  "Data-structure-bound: every iteration does a CHAMP insert, so dispatch is a
  small share of the time.")
(defn build [n]
  (loop [i 0 m (transient {})]
    (if (< i n) (recur (inc i) (assoc! m i (* i 2))) (persistent! m))))
(defn main [args]
  (let [n (if (seq args) (parse-long (first args)) 100000)
        m (build n)]
    (str (count m) " " (get m (quot n 2)))))
