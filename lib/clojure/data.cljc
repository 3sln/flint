(ns clojure.data
  "Non-core data functions: `diff`, which reports what two values have to
  themselves and what they share.

  The shape is Clojure's, down to the two protocols a caller can extend. What
  differs is how an extension attaches: flint has no classes, so a value that
  wants its own diff carries ``clojure.data/equality-partition`` and
  ``clojure.data/diff-similar`` as metadata -- fully-qualified symbols, which
  a syntax quote writes. That is the main road here rather
  than the side road it is in Clojure -- see `clojure.core/kind`.

  Ported because the Clojars survey named it."
  (:require [clojure.set :as set]))

(defprotocol EqualityPartition
  "Implementation detail. The partition a value diffs within."
  (equality-partition [x] "One of `:atom`, `:set`, `:sequential`, `:map`."))

(defprotocol Diff
  "Implementation detail. Diffs two values known to share a partition."
  (diff-similar [a b] "The three-part diff of two values in the same partition."))

(declare diff)

(defn- atom-diff
  "Two values with no interior worth walking: either they are equal and it is
  all common, or neither has anything in common with the other."
  [a b]
  (if (= a b) [nil nil a] [a b nil]))

(defn- vectorize
  "An index->value map back to a vector, with nil in the gaps. `nil` for an
  empty map, so that `diff` reports nothing rather than an empty vector."
  [m]
  (when (seq m)
    (reduce (fn [result e] (assoc result (key e) (val e)))
            (vec (repeat (apply max (keys m)) nil))
            m)))

(defn- diff-associative-key
  "The three-part diff of `a` and `b` at one key.

  The subtlety is a key present in both with value nil: `ab` is then nil and
  says nothing, so presence has to be asked separately. Getting this wrong
  reports a shared nil as a difference."
  [a b k]
  (let [va (get a k)
        vb (get b k)
        d (diff va vb)
        a* (nth d 0)
        b* (nth d 1)
        ab (nth d 2)
        in-a (contains? a k)
        in-b (contains? b k)
        same (and in-a in-b (or (some? ab) (and (nil? va) (nil? vb))))]
    [(when (and in-a (or (some? a*) (not same))) {k a*})
     (when (and in-b (or (some? b*) (not same))) {k b*})
     (when same {k ab})]))

(defn- diff-associative
  "The three-part diff of `a` and `b` over `ks`, merged key by key."
  ([a b] (diff-associative a b (set/union (set (keys a)) (set (keys b)))))
  ([a b ks]
   (reduce (fn [d1 d2] (doall (map merge d1 d2)))
           [nil nil nil]
           (map (fn [k] (diff-associative-key a b k)) ks))))

(defn- diff-sequential
  "Sequentials diff by position: index is the key, and the result is put back
  into vectors so a caller sees positions, not an index map."
  [a b]
  (vec (map vectorize
            (diff-associative (if (vector? a) a (vec a))
                              (if (vector? b) b (vec b))
                              (range (max (count a) (count b)))))))

(defn- diff-set [a b]
  [(not-empty (set/difference a b))
   (not-empty (set/difference b a))
   (not-empty (set/intersection a b))])

(extend-protocol EqualityPartition
  :nil (equality-partition [_] :atom)
  :map (equality-partition [_] :map)
  :set (equality-partition [_] :set)
  :vector (equality-partition [_] :sequential)
  :list (equality-partition [_] :sequential))

(extend-protocol Diff
  :nil (diff-similar [a b] (atom-diff a b))
  :map (diff-similar [a b] (diff-associative a b))
  :set (diff-similar [a b] (diff-set a b))
  :vector (diff-similar [a b] (diff-sequential a b))
  :list (diff-similar [a b] (diff-sequential a b)))

;; Every other kind -- numbers, strings, keywords, symbols, booleans,
;; functions, ports -- is an atom. Clojure gets this from an `Object` default;
;; here the kinds are a closed set, so it is written out.
(doseq [k [:boolean :number :string :keyword :symbol :fn :port :thread
           :atom :var :regex :exception :other]]
  (extend EqualityPartition k {`equality-partition (fn [_] :atom)})
  (extend Diff k {`diff-similar (fn [a b] (atom-diff a b))}))

(defn diff
  "What `a` has that `b` does not, what `b` has that `a` does not, and what
  they share, as `[only-in-a only-in-b both]`.

  Maps and sets recurse; sequentials recurse by position; anything else is
  compared whole. Two values of different partitions have nothing in common."
  [a b]
  (if (= a b)
    [nil nil a]
    (if (= (equality-partition a) (equality-partition b))
      (diff-similar a b)
      (atom-diff a b))))
