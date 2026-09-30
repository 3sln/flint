(ns ^{:corpus/kind :graph
      :corpus/diverges {:flint "no sorted collections: `sorted-set` is unresolved (README.md \"Sorted collections\", ROADMAP.md: decided, not started)"}
      :corpus/source "Dijkstra's shortest paths with a sorted-set priority queue"}
  dijkstra)

;; A deterministic pseudo-random graph (an LCG, so every runtime builds the
;; same one), then single-source shortest paths using `sorted-set` of
;; [dist node] as the queue -- the standard persistent-collections idiom.

(defn lcg [s] (mod (+ (* s 1103515245) 12345) 2147483648))

(defn graph [n degree]
  (loop [g {} node 0 s 42]
    (if (= node n)
      g
      (let [[edges s] (loop [es [] k 0 s s]
                        (if (= k degree)
                          [es s]
                          (let [s1 (lcg s) s2 (lcg s1)]
                            (recur (conj es [(mod s1 n) (inc (mod s2 100))]) (inc k) s2))))]
        (recur (assoc g node edges) (inc node) s)))))

(defn shortest [g src]
  (loop [dist {src 0} q (sorted-set [0 src])]
    (if-let [[d u :as top] (first q)]
      (let [q (disj q top)
            [dist q] (reduce (fn [[dist q] [v w]]
                               (let [nd (+ d w)]
                                 (if (< nd (get dist v Long/MAX_VALUE))
                                   [(assoc dist v nd) (conj q [nd v])]
                                   [dist q])))
                             [dist q] (get g u))]
        (recur dist q))
      dist)))

(defn main [_]
  (let [g (graph 2000 5)
        dist (shortest g 0)]
    (str (count dist) " " (reduce + (vals dist)) " " (apply max (vals dist)))))
