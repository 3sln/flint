(ns transhapes
  "Transients: arity, and what a handle does after `persistent!`.

  TWO SUSPECTED DIVERGENCES, neither previously compared. Native's `conj!`
  builtin is `let _ = n; rt.transient_conj(t, x)` -- it ignores every argument
  past the second. Both ports fold over all of them. And the ports check that
  a transient is still ALIVE before writing to it, throwing
  `IllegalStateException` on a handle already made persistent; native
  delegates to the generated `transient-conj`, which does not check.

  A transient used after `persistent!` is a real mistake to make -- it is what
  `(reduce conj! (transient []) ..)` does if the accumulator is captured
  wrong -- and three runtimes answering differently means a program that
  works on one and corrupts on another."
  (:require [clojure.string :as str]))

(defn- try* [f]
  (try (pr-str (f))
       (catch Exception e (str (flint.rt/ex-kind e) ": " (ex-message e)))))

(defn main [_]
  (pr-str
   {;; THE ORDINARY PATH, so a failure below is not blamed on the harness.
    :vec-one   (try* (fn [] (persistent! (conj! (transient []) 1))))
    :vec-fold  (try* (fn [] (persistent! (reduce conj! (transient []) [1 2 3]))))
    :map-one   (try* (fn [] (persistent! (conj! (transient {}) [:a 1]))))
    :set-one   (try* (fn [] (persistent! (conj! (transient #{}) 1))))

    ;; ARITY. `(conj! t 1 2)` is two elements or one, depending on whether the
    ;; builtin reads `n`.
    :vec-two   (try* (fn [] (persistent! (conj! (transient []) 1 2))))
    :vec-three (try* (fn [] (persistent! (conj! (transient []) 1 2 3))))
    :set-two   (try* (fn [] (persistent! (conj! (transient #{}) 1 2))))

    ;; AFTER `persistent!`, the handle is spent.
    :dead-conj (try* (fn [] (let [t (transient [1])
                                  _ (persistent! t)]
                              (conj! t 2))))
    :dead-twice (try* (fn [] (let [t (transient [1])
                                   _ (persistent! t)]
                               (persistent! t))))
    :dead-assoc (try* (fn [] (let [t (transient {})
                                   _ (persistent! t)]
                               (assoc! t :a 1))))

    ;; AND `assoc!`/`dissoc!` arity, the same question one door along.
    :assoc-one (try* (fn [] (persistent! (assoc! (transient {}) :a 1))))
    :assoc-two (try* (fn [] (persistent! (assoc! (transient {}) :a 1 :b 2))))}))
