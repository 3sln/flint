(ns m
  "The open `--aot` bug, at its smallest known size (`doc/decisions/0013`).

  Compiled, this answers `ClassCastException: value is not a function
  (object type 13, 2 args)` -- a RANGE where a function should be. Interpreted
  it answers 33, and so does the compiled version with the `p/receive` removed.

  Every part is load-bearing, checked one at a time:

  * the PARK. Without it, compiled and interpreted agree.
  * the SIZE. 32 passes and 33 fails, which is flint's vector tail: at 33 the
    tail spills into the trie.
  * the map literal AND the nested `(vec (range ...))`. Removing either passes.

  Delta-debugged to four arities that must all be compiled -- `reduce-seq`,
  `vec`, and two lambdas -- so it is an interaction and not a bad instruction.
  Not in the suite, because it FAILS; it is here so the next person starts from
  ten lines instead of a document store."
(defn- go [] (count (mapv (fn [i] {:id i :kids (vec (range (rem i 4)))}) (range 33))))
(defn main [_]
  (let [[tx rx] (p/channel 1 "test")]
    (t/spawn (fn [] (p/send tx :go)))
    (p/receive rx)
    (pr-str (go))))
