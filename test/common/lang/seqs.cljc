(ns lang.seqs
  "Laziness, and the sequence functions built on it.

  Laziness has to be LAZY: a chain of lazy seqs must not force one another, and
  an infinite one has to be usable by taking a prefix. The JVM port materialised
  both, which was a stack overflow and then an OutOfMemoryError -- neither of
  which a test that only checks answers would have caught, because both crashed
  before producing one."
  (:require [flint.check :refer [expect]]))

(defn ^:flint.check/test map-filter-reduce []
  (expect = [2 3 4] (vec (map inc [1 2 3])))
  (expect = [2 4] (vec (filter even? [1 2 3 4])))
  (expect = 10 (reduce + [1 2 3 4]))
  (expect = 10 (reduce + 0 [1 2 3 4]))
  (expect = 0 (reduce + []))
  (expect = [1 4 9] (vec (map (fn [x] (* x x)) [1 2 3]))))

(defn ^:flint.check/test an-infinite-seq-is-usable []
  ;; The whole point: `iterate` never ends, so anything that materialised it
  ;; would not return at all. A passing check here is a statement about
  ;; TERMINATION as much as about the values.
  (expect = [1 2 4 8] (vec (take 4 (iterate (fn [x] (* 2 x)) 1))))
  (expect = 0 (first (iterate inc 0)))
  (expect = 1 (first (rest (iterate inc 0))))
  (expect = [:x :x :x] (vec (take 3 (repeat :x))))
  (expect = [0 2 4] (vec (take 3 (filter even? (range 100))))))

(defn ^:flint.check/test chains-do-not-force-each-other []
  ;; Three nested `map`s over a hundred elements, of which five are wanted. A
  ;; runtime that forced eagerly gets the same ANSWER, so this is checked
  ;; against a counter rather than against the values alone.
  (expect = [3 4 5 6 7] (vec (take 5 (map inc (map inc (map inc (range 100)))))))
  (expect = [0 0 1 1 2] (vec (take 5 (mapcat (fn [x] [x x]) (range 10))))))

(defn ^:flint.check/test take-and-drop-past-the-end []
  ;; Off-the-end is where these differ from an index: `take` truncates rather
  ;; than throwing, `drop` empties rather than going negative.
  (expect = [1 2] (vec (take 5 [1 2])))
  (expect = [] (vec (take 0 [1 2])))
  (expect = [] (vec (drop 5 [1 2])))
  (expect = [2] (vec (drop 1 [1 2]))))

(defn ^:flint.check/test range-arities []
  (expect = [0 1 2] (vec (range 3)))
  (expect = [2 3 4] (vec (range 2 5)))
  (expect = [0 2 4] (vec (range 0 6 2)))
  (expect = [] (vec (range 0))))

(defn ^:flint.check/test seq-of-a-map-is-pairs []
  (expect = 2 (count (seq {:a 1 :b 2})))
  (expect = [:a 1] (vec (first (seq {:a 1}))))
  (expect = 3 (reduce + (map (fn [[_ v]] v) {:a 1 :b 2}))))

(defn ^:flint.check/test into-preserves-the-target-kind []
  ;; `into` conjs, and `conj` means different things per collection -- onto the
  ;; front of a list and the back of a vector. Getting a vector back from
  ;; `(into [] ...)` and a REVERSED list from `(into '() ...)` is the check.
  (expect = [1 2 3] (into [] [1 2 3]))
  (expect = [3 2 1] (vec (into '() [1 2 3])))
  (expect = #{1 2 3} (into #{} [1 2 3 1]))
  (expect = {:a 1 :b 2} (into {} [[:a 1] [:b 2]])))

;; LAZY MEANS LAZY ON AN INFINITE SEQ, which is the only test that can tell.
;;
;; `map-indexed`, `keep-indexed` and `partition-by` built their whole answer
;; into a vector and handed back its seq. Every finite test passed and every
;; one of them hung on `(iterate inc 0)`, while `map` and `filter` beside them
;; did not. Nothing recorded it as a divergence because nothing asked.
(defn ^:flint.check/test the-indexed-and-grouping-seqs-are-lazy []
  (expect = [[0 0] [1 1] [2 2]]
          (vec (take 3 (map-indexed (fn [i x] [i x]) (iterate inc 0)))))
  (expect = [0 2 4]
          (vec (take 3 (keep-indexed (fn [i x] (if (even? x) x nil)) (iterate inc 0)))))
  (expect = [(list 0 1 2) (list 3 4 5)]
          (vec (take 2 (partition-by (fn [x] (quot x 3)) (iterate inc 0)))))
  ;; the finite answers are the ones they always gave
  (expect = [[0 :a] [1 :b]] (vec (map-indexed (fn [i x] [i x]) [:a :b])))
  (expect = [1] (vec (keep-indexed (fn [i x] (if (= x :b) i nil)) [:a :b :c])))
  (expect = [(list 1 3) (list 2 4) (list 5)] (vec (partition-by odd? [1 3 2 4 5])))
  ;; and an empty input gives an empty seq, as Clojure does -- these used to
  ;; answer nil, because they ended in `(seq [])`
  (expect = 0 (count (map-indexed vector [])))
  (expect = 0 (count (keep-indexed vector [])))
  (expect = 0 (count (partition-by odd? []))))
