(ns assocshapes
  "`assoc` and `assoc!` on every shape, including the refused.

  THE GAP IN MY OWN PROBING. `mapshapes` covered `dissoc`, `merge`, `disj` and
  `into` -- and left out `assoc`, the most-used member of the same write
  family that produced five bugs. What coverage it had was the happy path:
  assoc onto a map, and onto a vector at an index that exists.

  `assoc` ON A VECTOR IS THE INTERESTING ONE. It writes at an index, and the
  index one PAST the end appends -- `(assoc [1 2] 2 :c)` is `[1 2 :c]` -- while
  two past throws. That boundary is decided separately in three
  implementations and is exactly the shape the other five bugs had."
  (:require [clojure.string :as str]))

(defn- try* [f]
  (try (pr-str (f))
       (catch Exception e (str (flint.rt/ex-kind e) ": " (ex-message e)))))

(defn main [_]
  (pr-str
   {;; A MAP: new key, existing key, nil key, several pairs.
    :map-new    (try* (fn [] (assoc {:a 1} :b 2)))
    :map-over   (try* (fn [] (assoc {:a 1} :a 9)))
    :map-nilkey (try* (fn [] (assoc {} nil 1)))
    :map-nilval (try* (fn [] (assoc {} :a nil)))
    :map-many   (try* (fn [] (assoc {} :a 1 :b 2)))
    :map-odd    (try* (fn [] (assoc {:a 1} :b)))

    ;; NIL becomes a map, which is Clojure's rule and easy to miss.
    :nil-one    (try* (fn [] (assoc nil :a 1)))

    ;; A VECTOR: in range, AT the end (appends), past the end (throws),
    ;; negative, and a non-integer index.
    :vec-in     (try* (fn [] (assoc [1 2 3] 1 :B)))
    :vec-end    (try* (fn [] (assoc [1 2] 2 :c)))
    :vec-past   (try* (fn [] (assoc [1 2] 3 :d)))
    :vec-far    (try* (fn [] (assoc [1 2] 99 :d)))
    :vec-neg    (try* (fn [] (assoc [1 2] -1 :d)))
    :vec-kw     (try* (fn [] (assoc [1 2] :a :d)))

    ;; SHAPES THAT ARE NOT ASSOCIATIVE.
    :set-one    (try* (fn [] (assoc #{1} 0 :a)))
    :list-one   (try* (fn [] (assoc '(1 2) 0 :a)))
    :str-one    (try* (fn [] (assoc "ab" 0 \c)))
    :num-one    (try* (fn [] (assoc 1 0 :a)))

    ;; THE TRANSIENT DOOR, which is where `conj!` was wrong.
    :t-map      (try* (fn [] (persistent! (assoc! (transient {}) :a 1))))
    :t-vec-in   (try* (fn [] (persistent! (assoc! (transient [1 2]) 0 :A))))
    :t-vec-end  (try* (fn [] (persistent! (assoc! (transient [1 2]) 2 :c))))
    :t-vec-past (try* (fn [] (persistent! (assoc! (transient [1 2]) 5 :d))))
    :t-set      (try* (fn [] (persistent! (assoc! (transient #{}) 0 :a))))
    :t-dead     (try* (fn [] (let [t (transient [1])
                                   _ (persistent! t)]
                               (assoc! t 0 :x))))}))
