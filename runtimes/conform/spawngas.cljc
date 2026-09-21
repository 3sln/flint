(ns spawngas
  "Spawning costs the same gas on every runtime.

  Every other gas fixture here exercises collections, strings and loops. NONE
  of them spawns a thread -- so the size of a thread OBJECT has never been
  billed under comparison, and allocation is charged by size.

  Two workloads at a 4x ratio, so a per-spawn difference shows as a gap that
  scales while a fixed startup cost does not."
  (:require [flint.thread :as th]))

(defn- spawn-n [n]
  (loop [i 0 acc 0]
    (if (< i n)
      (let [t (th/spawn (fn [] 1))]
        (recur (inc i) (+ acc (th/join t))))
      acc)))

(defn small [_] (str (spawn-n 10)))
(defn big [_] (str (spawn-n 40)))
