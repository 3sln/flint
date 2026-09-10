(ns seqshapes
  "`contains?`, `peek`, `pop`, `subvec` and `nth` on every shape.

  THE THIRD PROBE IN THIS FAMILY. `conjshapes` found `conj` corrupting a map
  on two runtimes; `mapshapes` found `into` doing the same on the transient
  path and `dissoc`/`disj` falling through silently on all four. Each was a
  branch that produced a VALUE where it should have refused -- `[1 2]`,
  `nil`, `#{}`, `{nil nil}` -- which is why none of them crashed and none was
  found.

  These five had zero cross-runtime coverage between them before this file:
  `contains?`, `peek` and `subvec` had none at all, `pop` four calls and
  `nth` fourteen, none of them on a shape that should be refused.

  `contains?` IS THE ONE TO WATCH. On a vector it asks about the INDEX and
  not the value -- `(contains? [10 20] 0)` is true and `(contains? [10 20]
  10)` is false -- which is the single most surprised-by rule in Clojure and
  exactly the kind of thing three implementations decide separately.

  ALL FOUR RUNTIMES AGREE ON EVERY CASE HERE, which is what this file is for
  and is not the same as being right. Five of the answers differ from
  Clojure, consistently, and they are recorded rather than changed because a
  language decision is not a probe's to make:

    (subvec [1 2 3] 2 1)   Clojure IndexOutOfBounds     flint []
    (peek #{1})            Clojure ClassCastException   flint 1
    (nth {:a 1} 0)         Clojure UnsupportedOperation flint [:a 1]
    (contains? '(1 2) 0)   Clojure IllegalArgument      flint false
    (pop nil)              Clojure nil                  flint throws

  `(pop nil)` is the one a program hits by accident: Clojure answers `nil`
  and flint throws `IllegalStateException`. The other four are flint being
  PERMISSIVE where Clojure refuses -- a set peeked, a map nth'd, a backwards
  subvec -- which is the safer direction to be wrong in but is still a
  difference somebody will meet.

  See `doc/goals/README.md`; these need deciding, not patching."
  (:require [clojure.string :as str]))

(defn- try* [f]
  (try (pr-str (f))
       (catch Exception e (str (flint.rt/ex-kind e) ": " (ex-message e)))))

(defn main [_]
  (pr-str
   {;; CONTAINS? -- by KEY on a map, by INDEX on a vector, by value on a set.
    :has-map     (try* (fn [] (contains? {:a 1} :a)))
    :has-map-no  (try* (fn [] (contains? {:a 1} :zz)))
    :has-map-nil (try* (fn [] (contains? {nil 1} nil)))
    :has-set     (try* (fn [] (contains? #{1 2} 1)))
    :has-vec-ix  (try* (fn [] (contains? [10 20] 0)))
    :has-vec-val (try* (fn [] (contains? [10 20] 10)))
    :has-vec-out (try* (fn [] (contains? [10 20] 5)))
    :has-vec-neg (try* (fn [] (contains? [10 20] -1)))
    :has-nil     (try* (fn [] (contains? nil :a)))
    :has-str     (try* (fn [] (contains? "ab" 0)))
    :has-list    (try* (fn [] (contains? '(1 2) 0)))

    ;; PEEK and POP -- opposite ends for a vector and a list, and both
    ;; refuse an empty collection.
    :peek-vec    (try* (fn [] (peek [1 2])))
    :peek-list   (try* (fn [] (peek '(1 2))))
    :peek-empty  (try* (fn [] (peek [])))
    :peek-nil    (try* (fn [] (peek nil)))
    :peek-set    (try* (fn [] (peek #{1})))
    :pop-vec     (try* (fn [] (pop [1 2])))
    :pop-list    (try* (fn [] (pop '(1 2))))
    :pop-empty   (try* (fn [] (pop [])))
    :pop-nil     (try* (fn [] (pop nil)))

    ;; SUBVEC -- the bounds, which is where an off-by-one lives.
    :sub-mid     (try* (fn [] (subvec [1 2 3 4] 1 3)))
    :sub-open    (try* (fn [] (subvec [1 2 3] 1)))
    :sub-all     (try* (fn [] (subvec [1 2] 0 2)))
    :sub-empty   (try* (fn [] (subvec [1 2] 1 1)))
    :sub-past    (try* (fn [] (subvec [1 2] 0 5)))
    :sub-rev     (try* (fn [] (subvec [1 2 3] 2 1)))
    :sub-neg     (try* (fn [] (subvec [1 2] -1 1)))

    ;; NTH -- present, absent with and without a default, and on shapes that
    ;; are not indexed.
    :nth-vec     (try* (fn [] (nth [1 2] 1)))
    :nth-out     (try* (fn [] (nth [1 2] 5)))
    :nth-dflt    (try* (fn [] (nth [1 2] 5 :none)))
    :nth-neg     (try* (fn [] (nth [1 2] -1)))
    :nth-list    (try* (fn [] (nth '(1 2) 1)))
    :nth-str     (try* (fn [] (nth "ab" 1)))
    :nth-nil     (try* (fn [] (nth nil 0 :none)))
    :nth-map     (try* (fn [] (nth {:a 1} 0)))}))
