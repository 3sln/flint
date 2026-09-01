(ns aot-try
  "TWO `try` REGIONS IN ONE FUNCTION, under a compiled arity.

  A KNOWN-OPEN DEFECT in both ports, reduced to the smallest program that shows
  it. The native runtime answers; the JVM and CLR AOT layers do not.

  One `try` is fine. Two in the same function is not: the second re-entry into
  compiled code after an unwind lands with the handler stack out of step, and it
  surfaces either as `POP_HANDLER` on an empty list or as `value is not a
  function` when the value stack has come apart.

  It is pre-existing -- confirmed by building the ports at the previous commit --
  and it survived because no conformance program had a `try` inside an arity the
  AOT layer compiles. `runtimes/conform/tables.cljc` was the first, which is how
  it was found: not by looking, but by writing a program that did an ordinary
  thing nothing had done here before.

  `unwind` was missing half the fix already present in the Rust -- re-pointing
  the frame's compiled re-entry at the handler target with the `LOOKUP` marker,
  because \"a handler target is a jump target, so it is a chunk start, but only
  the compiled arity knows which chunk\". That is now ported and is necessary but
  not sufficient; the rest is unfound."
  (:require [flint.check :refer [expect]]))

(defn- t [f] (try (str (f)) (catch Exception e (str "THREW " (ex-message e)))))

(defn main [_]
  (pr-str {:one (t (fn [] (nth [1 2] 99)))
           :two (t (fn [] (/ 1 0)))}))
