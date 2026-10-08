(ns ^{:corpus/kind :graph
      :corpus/source "Kahn's algorithm over a keyword dependency graph"}
  topo-sort
  (:require [clojure.string :as str]))

;; Build-system shaped: keywords naming tasks, sets of prerequisites, ties
;; broken by name so the order is deterministic.

(defn task [i] (keyword (str "t" i)))

(defn deps-graph [n]
  (into {} (for [i (range n)]
             [(task i) (set (for [j [(quot i 2) (quot i 3) (- i 7)] :when (and (>= j 0) (< j i))]
                              (task j)))])))

(defn topo [g]
  (loop [order [] g g]
    (if (empty? g)
      order
      (let [ready (sort (for [[k ds] g :when (empty? ds)] k))]
        (if (empty? ready)
          (throw (ex-info "cycle" {:left (keys g)}))
          (let [done (set ready)]
            (recur (into order ready)
                   (into {} (for [[k ds] g :when (not (done k))]
                              [k (reduce disj ds ready)])))))))))

(defn main [_]
  (let [order (topo (deps-graph 400))]
    (str (count order) " " (str/join "," (map name (take 8 order))) " "
         (name (last order)))))
