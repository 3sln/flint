(ns lang.functions
  "Functions: arities, closures, variadics and destructuring.

  Nothing here is about a runtime. It is about what a `fn` MEANS, and the
  reason it is checked once rather than four times is that a port which got
  any of it wrong would be a port of a different language."
  (:require [flint.check :refer [expect]]))

(defn- multi
  ([] :none)
  ([a] [:one a])
  ([a b] [:two a b])
  ([a b & more] [:many a b (vec more)]))

(defn ^:flint.check/test arity-selection []
  (expect = :none (multi))
  (expect = [:one 1] (multi 1))
  (expect = [:two 1 2] (multi 1 2))
  (expect = [:many 1 2 [3 4]] (multi 1 2 3 4)))

(defn ^:flint.check/test variadic-rest-is-nil-when-empty []
  ;; Not an empty seq. Clojure hands `nil`, and code that tests the rest arg
  ;; with `if` rather than `seq` depends on it.
  (let [f (fn [a & more] [a more])]
    (expect = [1 nil] (f 1))
    (expect = [1 [2]] (let [[a m] (f 1 2)] [a (vec m)]))))

(defn ^:flint.check/test closures-capture-values []
  (let [make (fn [n] (fn [x] (+ x n)))
        add3 (make 3)
        add10 (make 10)]
    (expect = 8 (add3 5))
    (expect = 15 (add10 5))
    ;; Two closures over the same `fn`, each with its own upvalue -- a shared
    ;; slot would make the second overwrite the first.
    (expect = 8 (add3 5))))

(defn ^:flint.check/test closures-capture-loop-variables-per-iteration []
  ;; Each iteration's binding is its own, so the collected closures answer
  ;; differently. A single shared slot -- the classic bug -- makes them all
  ;; answer with the last value.
  (let [fs (mapv (fn [i] (fn [] i)) (range 3))]
    (expect = [0 1 2] (mapv (fn [f] (f)) fs))))

(defn ^:flint.check/test destructuring-vectors []
  (let [[a b] [1 2]] (expect = [1 2] [a b]))
  (let [[a b c] [1 2]] (expect nil? c))
  (let [[a & r] [1 2 3]] (expect = [1 [2 3]] [a (vec r)]))
  (let [[[a] [b]] [[1] [2]]] (expect = [1 2] [a b])))

(defn ^:flint.check/test destructuring-maps []
  (let [{:keys [a b]} {:a 1 :b 2}] (expect = [1 2] [a b]))
  (let [{:keys [a z]} {:a 1}] (expect nil? z))
  (let [{a :a :or {a 9}} {}] (expect = 9 a))
  (let [{:keys [a]} nil] (expect nil? a)))

(defn ^:flint.check/test apply-spreads-the-last-argument []
  (expect = 6 (apply + [1 2 3]))
  (expect = 6 (apply + 1 [2 3]))
  (expect = 0 (apply + []))
  (expect = [:many 1 2 [3]] (apply multi [1 2 3])))

(defn ^:flint.check/test higher-order-composition []
  (expect = [2 3 4] (mapv (comp inc identity) [1 2 3]))
  (expect = 5 ((partial + 2) 3))
  (expect = [1 2 3] (mapv identity [1 2 3])))
