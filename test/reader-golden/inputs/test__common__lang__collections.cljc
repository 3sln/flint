(ns lang.collections
  "Vectors, maps, sets and the representation changes underneath them.

  A flint map is a flat array up to 8 entries and a CHAMP past that, and the
  promotion is the sort of thing that works for a while and then stops -- the
  JVM port once produced the right pairs in the wrong ORDER, which nothing
  noticed until a printed map was compared. Both sides of every such boundary
  are checked here."
  (:require [flint.check :refer [expect]]))

(def ^:private ARRAY-MAP-MAX 8)

(defn- map-of [n]
  (reduce (fn [m i] (assoc m (str "k" i) i)) {} (range n)))

(defn ^:flint.check/test vector-basics []
  (expect = [1 2 3] (conj [1 2] 3))
  (expect = 3 (count [1 2 3]))
  (expect = 1 (first [1 2]))
  (expect = [2 3] (vec (rest [1 2 3])))
  (expect = 2 (nth [1 2 3] 1))
  (expect = :missing (nth [1 2 3] 9 :missing)))

(defn ^:flint.check/test assoc-on-a-vector-stays-a-vector []
  ;; Both ports fell through to the map branch here and answered `{1 :B}` --
  ;; the right value under the right key, and the wrong KIND of collection,
  ;; which then failed several calls later on something no longer indexed.
  (let [v [:a :b :c]
        w (assoc v 1 :B)]
    (expect = [:a :B :c] w)
    (expect vector? w)
    (expect false? (map? w))
    (expect = 3 (count w))
    ;; An index equal to the count appends rather than throwing.
    (expect = [:a :b :c :d] (assoc v 3 :d))
    (expect = [:A :b :c] (update v 0 (fn [_] :A)))))

(defn ^:flint.check/test map-basics []
  (expect = {:a 1 :b 2} (assoc {:a 1} :b 2))
  (expect = 1 (get {:a 1} :a))
  (expect nil? (get {:a 1} :z))
  (expect = :none (get {:a 1} :z :none))
  (expect = 1 (count {:a 1}))
  (expect = {} (dissoc {:a 1} :a)))

(defn ^:flint.check/test map-promotion-is-invisible []
  ;; Eight entries is the last array-map and nine is the first CHAMP. Equality,
  ;; count, lookup and hash all have to survive crossing that line, and a map
  ;; on one side has to equal the same map on the other.
  (let [small (map-of ARRAY-MAP-MAX)
        big (map-of (inc ARRAY-MAP-MAX))]
    (expect = ARRAY-MAP-MAX (count small))
    (expect = (inc ARRAY-MAP-MAX) (count big))
    (expect = 0 (get small "k0"))
    (expect = ARRAY-MAP-MAX (get big (str "k" ARRAY-MAP-MAX)))
    ;; Built up, then cut back down: a CHAMP that shrinks past the boundary has
    ;; to equal the array-map with the same entries, whichever way each was
    ;; reached. This is the property CHAMP is CHOSEN for -- canonical form --
    ;; and a trie that merely stored the pairs would fail it.
    (expect = small (dissoc big (str "k" ARRAY-MAP-MAX)))
    (expect = (hash small) (hash (dissoc big (str "k" ARRAY-MAP-MAX))))))

(defn ^:flint.check/test large-maps-keep-every-key []
  (let [m (map-of 40)]
    (expect = 40 (count m))
    (expect = 17 (get m "k17"))
    (expect = 39 (get m "k39"))
    (expect nil? (get m "k40"))
    ;; Every key found by lookup, so a trie that lost one to a collision node
    ;; cannot pass by having the right count.
    (expect = 40 (count (filterv (fn [i] (= i (get m (str "k" i)))) (range 40))))))

(defn ^:flint.check/test set-basics []
  (expect = #{1 2 3} (conj #{1 2} 3))
  (expect = #{1 2} (conj #{1 2} 2))
  (expect = 2 (count #{1 2}))
  (expect true? (contains? #{1 2} 1))
  (expect false? (contains? #{1 2} 9)))

(defn ^:flint.check/test count-of-nil-is-zero []
  ;; Not an error and not nil. Every collection function has a nil case and
  ;; they are individually easy to get wrong.
  (expect = 0 (count nil))
  (expect nil? (first nil))
  (expect nil? (seq []))
  (expect nil? (seq nil))
  (expect = [] (vec nil)))

(defn ^:flint.check/test nesting-round-trips []
  (let [v [[1 [2 [3]]] {:a {:b [1 2]}}]]
    (expect = v (vec (seq v)))
    (expect = 3 (get-in v [0 1 1 0]))
    (expect = [1 2] (get-in v [1 :a :b]))))

(defn ^:flint.check/test equality-ignores-how-it-was-built []
  ;; A collection assembled by `conj` in a different order, or through a
  ;; different representation, is still the same collection -- and hashes the
  ;; same, which is what lets it be used as a key.
  (expect = [1 2 3] (reduce conj [] [1 2 3]))
  (expect = #{1 2 3} (reduce conj #{} [3 1 2]))
  (expect = (hash #{1 2 3}) (hash (reduce conj #{} [3 1 2])))
  (expect = {:a 1 :b 2} (reduce (fn [m [k v]] (assoc m k v)) {} [[:b 2] [:a 1]]))
  (expect = (hash {:a 1 :b 2})
          (hash (reduce (fn [m [k v]] (assoc m k v)) {} [[:b 2] [:a 1]]))))

(defn ^:flint.check/test a-map-entry-is-sequential-and-so-is-a-coll []
  ;; `sequential?` compiles to a TYPE-TEST OPCODE, not to a builtin call, so
  ;; the runtimes answer it from a switch on the type code rather than from
  ;; `isSequential`. Both ports' switches restated that predicate and left out
  ;; map entries, so `(sequential? (first (seq m)))` was false on the JVM and
  ;; CLR and true on native -- and `coll?`, which is built on it, went with it.
  ;;
  ;; The switch had already been fixed once for exactly this: the comment on
  ;; its `map?` case records `(map? row)` being false because `Maps.isMap`
  ;; was wired and the switch was not. Restating a predicate is what drifts;
  ;; both now call the one definition.
  (let [e (first (seq {:a 1}))]
    (expect = true (map-entry? e))
    (expect = true (sequential? e))
    (expect = true (coll? e))
    (expect = :a (key e))
    (expect = 1 (val e))
    (expect = :a (nth e 0))
    (expect = 1 (nth e 1))
    (expect = true (= e [:a 1])))
  ;; The rest of the family, so a future edit to the switch has to keep them.
  (expect = true (sequential? [1 2]))
  (expect = true (sequential? (list 1 2)))
  (expect = true (sequential? (seq [1 2])))
  (expect = false (sequential? {:a 1}))
  (expect = false (sequential? #{1 2}))
  (expect = false (sequential? "ab"))
  (expect = false (sequential? nil))
  (expect = true (coll? {:a 1}))
  (expect = true (coll? #{1 2})))

(defn ^:flint.check/test a-map-entry-is-a-vector-as-in-clojure []
  ;; Clojure's `MapEntry` IS a vector. Flint treated it as a list on all four
  ;; runtimes -- `vector?` false, printing `(:a 1)`, `conj` prepending -- so
  ;; they agreed with each other and with nothing else, which is the one shape
  ;; a cross-runtime diff structurally cannot catch.
  (let [e (first (seq {:a 1 :b 2}))]
    (expect = true (vector? e))
    (expect = true (map-entry? e))
    (expect = true (sequential? e))
    (expect = true (associative? e))
    (expect = "[:a 1]" (pr-str e))
    ;; `conj` APPENDS, where a list would have prepended. A map entry is both
    ;; a vector and sequential, so this is the assertion that says which
    ;; reading wins.
    (expect = [:a 1 9] (conj e 9))
    (expect = [:z 1] (assoc e 0 :z))
    (expect = [:a 99] (assoc e 1 99))
    (expect = 2 (count e))
    (expect = :a (nth e 0))
    (expect = :a (get e 0))
    (expect = true (= e [:a 1]))
    (expect = "{:k [:a 1]}" (pr-str {:k e}))
    ;; And it is still an entry: `into` a map must not see a plain vector and
    ;; lose the key/value reading.
    (expect = {:a 1} (into {} [e]))
    (expect = :a (key e))
    (expect = 1 (val e))))
