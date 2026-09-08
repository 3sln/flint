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

;; `distinct` and `dedupe` were the other two, found by sweeping every
;; Clojure-lazy function over a two-million-element source and watching which
;; ones took longer than the rest. `distinct` took 4.4x the baseline because it
;; built a set of two million entries to answer a question about three.
;;
;; The `seen` set is PERSISTENT rather than transient now, and that is not a
;; detail: a transient belongs to one thread of control, and a lazy seq hands
;; its state across a suspension that may be forced later, elsewhere, or never.
(defn ^:flint.check/test distinct-and-dedupe-are-lazy []
  (expect = [0 1 2 3]
          (vec (take 4 (distinct (mapcat (fn [x] [x x x]) (iterate inc 0))))))
  (expect = [0 1 2 3]
          (vec (take 4 (dedupe (mapcat (fn [x] [x x x]) (iterate inc 0))))))
  ;; the answers they always gave
  (expect = [1 2 3 4] (vec (distinct [1 2 1 3 2 4])))
  (expect = [3 1 2] (vec (distinct [3 1 3 2 1])))
  (expect = ["a" "b"] (vec (distinct ["a" "b" "a"])))
  (expect = [1 2 3 1] (vec (dedupe [1 1 2 2 2 3 1 1])))
  (expect = 0 (count (distinct [])))
  (expect = 0 (count (dedupe [])))
  ;; a long run of duplicates costs one thunk, not one per element
  (expect = [:a] (vec (distinct (repeat 1000 :a))))
  (expect = [:a] (vec (dedupe (repeat 1000 :a)))))

;; A RANGE KNOWS ITS SIZE. Clojure's Range is Counted; this walked, so
;; `(count (range 2000000))` cost 11.92s where every other accessor cost 1.40s.
;;
;; Each case is checked against `(count (vec r))` as well as against a literal,
;; because the walk is the definition and the arithmetic is the shortcut. A
;; shortcut that disagrees with the thing it replaces is the bug this shape
;; invites.
(defn ^:flint.check/test a-range-counts-without-walking []
  (expect = [0 1 10] [(count (range 0)) (count (range 1)) (count (range 10))])
  ;; a step that does not divide the span evenly
  (expect = [4 5 3] [(count (range 0 10 3)) (count (range 0 10 2)) (count (range 1 10 4))])
  ;; DESCENDING, which the kin probe cannot spell -- its mock has no negative
  ;; fixnums -- so it is asked here instead
  (expect = [10 4 0] [(count (range 10 0 -1)) (count (range 10 0 -3)) (count (range 0 10 -1))])
  ;; empty in three ways: equal ends, backwards, and a step pointing away
  (expect = [0 0 0] [(count (range 5 5)) (count (range 5 3)) (count (range 3 5 -1))])
  (expect = [10 4 5] [(count (range -5 5)) (count (range -5 -1)) (count (range 5 -5 -2))])
  ;; and every one of them agrees with walking it
  (expect = [true true true true true true]
          (vec (map (fn [r] (= (count r) (count (vec r))))
                    [(range 10) (range 0 10 3) (range 10 0 -1)
                     (range -5 5) (range 5 -5 -2) (range 5 5)]))))

;; `keys` AND `vals` AGREE WITH `seq`, which is the contract that made it safe
;; to stop building them with `map`.
;;
;; They were `(map2 first m)` and `(map2 second m)`, which pays the lazy layer
;; twice -- once for the map's own seq, a vecseq over the entry vector, and
;; once for the `map` above it. They now walk that vector by index, still one
;; cell at a time. 223,084 allocations to 141,867 on a 20,000-entry map.
(defn ^:flint.check/test keys-and-vals-agree-with-seq []
  (let [m (into {} (map (fn [i] [(keyword (str "k" i)) i]) (range 40)))]
    (expect = (vec (map first (seq m))) (vec (keys m)))
    (expect = (vec (map second (seq m))) (vec (vals m)))
    (expect = (count m) (count (keys m)))
    (expect = (count m) (count (vals m))))
  ;; the array-map tier as well as the CHAMP
  (let [m {:a 1 :b 2 :c 3}]
    (expect = (vec (map first (seq m))) (vec (keys m)))
    (expect = (vec (map second (seq m))) (vec (vals m))))
  ;; STILL LAZY: taking two from a hundred must not walk a hundred
  (expect = 2 (count (take 2 (keys (into {} (map (fn [i] [i i]) (range 100)))))))
  ;; and the empty answers Clojure gives
  (expect = [nil nil] [(keys {}) (vals {})])
  (expect = [nil nil] [(keys nil) (vals nil)]))
