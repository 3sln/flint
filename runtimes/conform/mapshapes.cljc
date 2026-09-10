(ns mapshapes
  "`dissoc`, `merge`, `disj` and `into` on every shape, including the refused.

  WHY THIS EXISTS: `conjshapes.cljc` found that `conj` onto a map silently
  produced a corrupt map on both ports -- `(conj {:a 1} 7)` answered
  `{:a 1, nil nil}` -- because nothing had ever compared the three
  implementations on anything but the two cases that were never in doubt.

  These four are the same family and had the same exposure. Before this file,
  `dissoc` had twelve calls in `test/common`, `merge` four and `disj` one, and
  ZERO between them in any conformance program: exercised on wasm, never
  compared against the ports.

  A function can be well tested on one runtime and wrong on two others, and
  nothing notices, because the tests that cover it and the harness that
  compares runtimes are different things."
  (:require [clojure.string :as str]))

(defn- try* [f]
  (try (pr-str (f))
       (catch Exception e (str (flint.rt/ex-kind e) ": " (ex-message e)))))

(defn main [_]
  (pr-str
   {;; DISSOC -- present, absent, several at once, down to empty, and on
    ;; things that are not maps.
    :dis-one     (try* (fn [] (dissoc {:a 1 :b 2} :a)))
    :dis-absent  (try* (fn [] (dissoc {:a 1} :zz)))
    :dis-many    (try* (fn [] (dissoc {:a 1 :b 2 :c 3} :a :c)))
    :dis-all     (try* (fn [] (dissoc {:a 1} :a)))
    :dis-empty   (try* (fn [] (dissoc {} :a)))
    :dis-nil     (try* (fn [] (dissoc nil :a)))
    :dis-none    (try* (fn [] (dissoc {:a 1})))
    :dis-vec     (try* (fn [] (dissoc [1 2] 0)))
    :dis-nilkey  (try* (fn [] (dissoc {nil 1 :a 2} nil)))

    ;; A BIG MAP, past the array-map to hash-map promotion, so the CHAMP path
    ;; is the one under test rather than the flat one.
    :dis-big     (try* (fn [] (count (reduce dissoc
                                             (into {} (map (fn [i] [i i]) (range 40)))
                                             (range 0 40 2)))))

    ;; MERGE -- later wins, nil is skipped, no args, one arg, and a non-map.
    :mrg-two     (try* (fn [] (merge {:a 1} {:b 2})))
    :mrg-over    (try* (fn [] (merge {:a 1} {:a 2})))
    :mrg-three   (try* (fn [] (merge {:a 1} {:b 2} {:a 3})))
    :mrg-nil     (try* (fn [] (merge {:a 1} nil)))
    :mrg-nil1    (try* (fn [] (merge nil {:a 1})))
    :mrg-none    (try* (fn [] (merge)))
    :mrg-one     (try* (fn [] (merge {:a 1})))
    :mrg-empty   (try* (fn [] (merge {} {})))

    ;; DISJ -- present, absent, several, to empty, and on a non-set.
    :dsj-one     (try* (fn [] (disj #{1 2} 1)))
    :dsj-absent  (try* (fn [] (disj #{1} 9)))
    :dsj-many    (try* (fn [] (disj #{1 2 3} 1 3)))
    :dsj-all     (try* (fn [] (disj #{1} 1)))
    :dsj-nil     (try* (fn [] (disj nil 1)))
    :dsj-vec     (try* (fn [] (disj [1 2] 1)))

    ;; INTO, which is `conj` folded and therefore inherits every arm of it.
    :into-vec    (try* (fn [] (into [] [1 2])))
    :into-set    (try* (fn [] (into #{} [1 1 2])))
    :into-map    (try* (fn [] (into {} [[:a 1] [:b 2]])))
    :into-mapmap (try* (fn [] (into {:a 1} {:b 2})))
    :into-badmap (try* (fn [] (into {} [7])))
    :into-nil    (try* (fn [] (into nil [1 2])))}))
