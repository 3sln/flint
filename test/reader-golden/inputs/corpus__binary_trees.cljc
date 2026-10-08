(ns ^{:corpus/kind :alloc
      :corpus/source "Computer Language Benchmarks Game, binary-trees"}
  binary-trees
  (:require [clojure.string :as str]))

;; Allocation and the collector, and nothing else: build complete binary trees,
;; walk them, throw them away. Nodes are two-element vectors, leaves are nil.

(defn make [d]
  (if (zero? d) [nil nil] [(make (dec d)) (make (dec d))]))

(defn check [node]
  (let [l (first node) r (second node)]
    (if l (+ 1 (check l) (check r)) 1)))

(defn main [_]
  (let [max-d 12
        stretch (inc max-d)
        long-lived (make max-d)
        lines (concat
               [(str "stretch tree of depth " stretch " check: " (check (make stretch)))]
               (for [d (range 4 (inc max-d) 2)]
                 (let [iters (bit-shift-left 1 (+ (- max-d d) 4))
                       c (reduce + (map (fn [_] (check (make d))) (range iters)))]
                   (str iters " trees of depth " d " check: " c)))
               [(str "long lived tree of depth " max-d " check: " (check long-lived))])]
    (str/join "\n" lines)))
