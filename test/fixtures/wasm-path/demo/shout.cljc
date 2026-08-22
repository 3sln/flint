(ns demo.shout
  "The Clojure half of a namespace whose native half is a precompiled unit.

  It sits beside `shout.unit.edn` on purpose: a `:wasm-path` directory supplies
  both halves of a namespace, resolved by namespace exactly the way `:src`
  resolves source."
  (:require [flint.rt]))

(defn shout [s] (flint.rt/demo-shout s))
