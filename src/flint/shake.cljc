(ns flint.shake
  "Tree shaking, as a pass over a FINISHED module.

  `--gc-sections` does this at link time, and it is worth 306 KB of code on
  flint's own runtime -- 777 functions down to 273. `doc/decisions/0024` moves
  linking out of the compile path entirely, so the shaking has to move with it,
  and the place it lands is here: a mark from a set of roots over a call graph,
  followed by removing what was not marked.

  **The mark is target-independent, and that is the point of this namespace.**
  A call graph is a call graph whether it came out of a wasm code section, a
  JVM constant pool or a CLR metadata table. What differs is how the edges are
  extracted and how the dead entries are removed, and those are the two
  functions a target supplies.

  It can also be MORE precise than the linker was. `--gc-sections` is told a
  conservative export list before anything is known about the program; by the
  time this runs, the image exists and the exact set of builtins it reaches is
  a fact rather than a guess."
  (:require [clojure.set :as set]))

(defn reachable
  "Everything reachable from `roots` under `edges`.

  `edges` takes a node and returns its successors. Shared by every target:
  nothing here knows what a node is."
  [roots edges]
  (loop [seen #{} todo (vec roots)]
    (if (empty? todo)
      seen
      (let [n (peek todo)
            todo (pop todo)]
        (if (contains? seen n)
          (recur seen todo)
          (recur (conj seen n) (into todo (edges n))))))))

(defn dead
  "The complement: everything in `all` that `roots` cannot reach."
  [all roots edges]
  (set/difference (set all) (reachable roots edges)))

(defn report
  "What a shake did, in the terms a size budget is argued in."
  [{:keys [total kept before after]}]
  {:functions {:total total :kept kept :removed (- total kept)}
   :bytes {:before before :after after :removed (- before after)}
   :share (if (pos? before) (double (/ (- before after) before)) 0.0)})
