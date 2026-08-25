(ns cc
  "Repeated concatenation -- the case doc/decisions/0011 names as O(n^2) with
  flat strings, and the reason `str` should be a tree join.")
(defn build [n]
  (loop [i 0 acc ""] (if (< i n) (recur (inc i) (str acc "0123456789abcdef")) acc)))
(defn main [args]
  (let [n (if (seq args) (parse-long (first args)) 4000)
        s (build n)]
    (str [(count s) (subs s 0 8) (subs s (- (count s) 8))])))
