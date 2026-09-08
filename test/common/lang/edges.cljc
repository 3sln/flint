;; THE BOUNDARIES, and the invariants that must hold across them.
;;
;; Every bug this file exists because of was at an edge nobody had written
;; down: `(quot MIN -1)`, a literal past the fixnum range, a rope handed to
;; `compare`. The suite covered what people thought to test, which is the
;; middle of every range.
;;
;; THESE ARE INVARIANTS AND NOT ANSWERS. `(= (hash a) (hash b))` whenever
;; `(= a b)` is checkable without knowing what the hash is -- and it is
;; exactly what a tier boundary breaks. The harness compares each port's
;; transcript against the native runtime's, so a disagreement names itself
;; whichever side is wrong.
;;
;; The boundaries, all four of them:
;;
;;     FIXNUM      +/- 2^47      past it an integer is BOXED
;;     INLINE      5 bytes       past it a string is on the heap
;;     INTERNED    32 bytes      past it a string is not canonical
;;     FLAT        1024 bytes    past it a concatenation is a ROPE
;;     ARRAY-MAP   8 entries     past it a map is a CHAMP
(ns lang.edges
  (:require [flint.bytes :as b]
            [flint.table :as ft]
            [clojure.edn :as edn]
            [flint.check :refer [expect]]))

(defn- rep [n s] (loop [i 0 acc ""] (if (< i n) (recur (inc i) (str acc s)) acc)))

;; Straddling the fixnum boundary in both directions, and the two integers
;; that have no counterpart.
(def fixnum-max 140737488355327)
(def fixnum-min -140737488355328)

(defn ^:flint.check/test integers-straddle-the-fixnum-boundary []
  (doseq [n [0 1 -1 fixnum-max fixnum-min
             (+ fixnum-max 1) (- fixnum-min 1)
             (* fixnum-max 2) (* fixnum-min 2)]]
    ;; IDENTITY under arithmetic, which is what boxing must not disturb.
    (expect = n (+ n 0))
    (expect = n (- n 0))
    (expect = n (* n 1))
    (expect = n (- 0 (- 0 n)))
    ;; A BOXED integer and a fixnum of the same value are ONE KEY.
    (expect = true (= n (+ n 0)))
    (expect = (hash n) (hash (+ n 0)))
    (expect = 0 (compare n (+ n 0)))
    ;; And it survives a collection round trip.
    (expect = n (nth [n] 0))
    (expect = n (get {n n} n))
    (expect = true (contains? #{n} n))))

(defn ^:flint.check/test division-holds-at-the-boundary []
  (doseq [n [1 -1 7 -7 fixnum-max fixnum-min (+ fixnum-max 1)]]
    (doseq [d [1 -1 2 -2 3]]
      ;; `quot` and `rem` RECONSTRUCT the dividend. This is the one identity
      ;; that pins truncation direction and remainder sign together.
      (expect = n (+ (* (quot n d) d) (rem n d))))))

(defn ^:flint.check/test strings-are-equal-across-every-tier []
  ;; One string per tier, each built two ways: as a literal and by
  ;; concatenation. The tier is supposed to be INVISIBLE.
  (doseq [n [1 5 6 32 33 200 1024 1025 2000]]
    (let [lit   (rep n "a")
          built (str (rep (quot n 2) "a") (rep (- n (quot n 2)) "a"))]
      (expect = n (count lit))
      (expect = n (count built))
      (expect = true (= lit built))
      (expect = (hash lit) (hash built))
      (expect = 0 (compare lit built))
      (expect = true (= (subs lit 0 1) (subs built 0 1)))
      ;; ... and as a MAP KEY, which is where `=` and `hash` have to agree.
      (expect = :found (get {lit :found} built))
      (expect = true (contains? #{built} lit)))))

(def ^:private wide-literal
  ;; 600 two-byte characters = 1200 bytes: NOT ascii, and past `FLAT_MAX`.
  "éééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééééé")

(defn ^:flint.check/test an-indexed-literal-splits-on-character-boundaries []
  ;; THE TIER THE WALK ABOVE CANNOT REACH. `rep` CONCATENATES, and a
  ;; concatenation past `FLAT_MAX` is a rope; a LITERAL past `FLAT_MAX` that is
  ;; not ascii is built by a different constructor with its own leaf-splitting
  ;; rule. The walk above crosses 1024 and 2000 with `"a"`, so `ascii` is true
  ;; and that constructor is never entered -- the right boundary with the wrong
  ;; character.
  ;;
  ;; Both ports cut the leaf one byte late, between a lead byte and its
  ;; continuation. `count` stayed RIGHT -- an orphan lead counts as one code
  ;; point and its orphan continuations as none -- so every check here is about
  ;; reading a character back, which is where it showed.
  (expect = 600 (count wide-literal))
  ;; INDEX_LEAF is 128 bytes = 64 of these characters, so 63/64 and 127/128 sit
  ;; either side of a leaf edge. That is the whole test.
  (doseq [i [0 62 63 64 65 126 127 128 129 599]]
    (expect = "é" (nth wide-literal i)))
  (expect = "ééé" (subs wide-literal 63 66))
  (expect = "éééé" (subs wide-literal 126 130))
  ;; And the two constructors must agree, which is the tier-invisibility this
  ;; file exists to assert.
  (expect = true (= wide-literal (rep 600 "é")))
  (expect = (hash (rep 600 "é")) (hash wide-literal)))

(defn ^:flint.check/test strings-order-across-every-tier []
  (doseq [n [1 5 6 32 33 200 1024 1025 2000]]
    (let [lo (rep n "a")
          hi (str (rep (dec n) "a") "b")]
      (expect = true (neg? (compare lo hi)))
      (expect = true (pos? (compare hi lo)))
      (expect = false (= lo hi)))))

(defn ^:flint.check/test maps-straddle-the-champ-boundary []
  ;; Around ARRAY_MAP_MAX, where a map changes representation. Promotion is
  ;; supposed to be invisible: same count, same lookups, same equality, same
  ;; hash as one built in the other order.
  (doseq [n [0 1 7 8 9 16]]
    (let [ks (range n)
          up (reduce (fn [m i] (assoc m i (* i 10))) {} ks)
          down (reduce (fn [m i] (assoc m i (* i 10))) {} (reverse ks))]
      (expect = n (count up))
      (expect = n (count down))
      (expect = true (= up down))
      (expect = (hash up) (hash down))
      (doseq [i ks]
        (expect = (* i 10) (get up i))
        (expect = true (contains? down i)))
      ;; DISSOC BACK DOWN through the boundary and the map is empty again.
      (expect = 0 (count (reduce dissoc up ks))))))

(defn ^:flint.check/test vectors-and-sets-agree-with-their-hashes []
  (doseq [n [0 1 8 33]]
    (let [v (vec (range n))
          v2 (reduce conj [] (range n))
          s (set (range n))
          s2 (reduce conj #{} (range n))]
      (expect = true (= v v2))
      (expect = (hash v) (hash v2))
      (expect = true (= s s2))
      (expect = (hash s) (hash s2))
      (expect = n (count v))
      (expect = n (count s)))))

(defn ^:flint.check/test named-things-cross-the-inline-boundary []
  ;; A keyword up to INLINE_MAX bytes lives in the VALUE; past it, on the
  ;; heap with its hash in a slot. Both tiers have to be one key, and a
  ;; QUALIFIED one is always on the heap whatever its name is.
  (doseq [n [1 5 6 32 33]]
    (let [nm (rep n "a")
          k  (keyword nm)
          k2 (keyword (str nm))
          q  (keyword "ns" nm)
          q2 (keyword "ns" (str nm))
          s  (symbol nm)
          s2 (symbol (str nm))]
      (expect = true (= k k2))
      (expect = (hash k) (hash k2))
      (expect = 0 (compare k k2))
      (expect = true (= q q2))
      (expect = (hash q) (hash q2))
      (expect = true (= s s2))
      (expect = (hash s) (hash s2))
      ;; A BARE name sorts before a QUALIFIED one, at every tier.
      (expect = true (neg? (compare k q)))
      (expect = true (pos? (compare q k)))
      ;; A keyword and a symbol of the same name are NOT equal.
      (expect = false (= k s))
      ;; ... and each is a distinct map key.
      (expect = :k (get {k :k s :s} k2))
      (expect = :s (get {k :k s :s} s2)))))

(defn ^:flint.check/test transients-cross-the-champ-boundary []
  ;; `transient` promotes an array-map to a CHAMP up front, so the boundary
  ;; is crossed on the way in as well as by `assoc!`. The persistent result
  ;; has to equal one built without a transient at all.
  (doseq [n [0 1 7 8 9 16]]
    (let [ks (range n)
          direct (reduce (fn [m i] (assoc m i (* i 10))) {} ks)
          via (persistent! (reduce (fn [m i] (assoc! m i (* i 10))) (transient {}) ks))
          sv (persistent! (reduce conj! (transient #{}) ks))
          vv (persistent! (reduce conj! (transient []) ks))]
      (expect = true (= direct via))
      (expect = (hash direct) (hash via))
      (expect = n (count via))
      (expect = n (count sv))
      (expect = n (count vv))
      (expect = true (= (set ks) sv))
      (expect = true (= (vec ks) vv)))))

(defn ^:flint.check/test byte-strings-cross-their-tiers []
  ;; The same rule ropes have: a byte string built by concatenation past the
  ;; flat boundary is a TREE, and the tier must be invisible to `=`, `hash`
  ;; and `size`.
  (doseq [n [0 1 64 100]]
    (let [chunk (b/of-string (rep 16 "a"))
          built (reduce b/cat (b/of-string "") (mapv (fn [_] chunk) (range n)))
          flat  (b/of-string (rep (* 16 n) "a"))]
      (expect = (* 16 n) (b/size built))
      (expect = (* 16 n) (b/size flat))
      (expect = true (= flat built))
      (expect = (hash flat) (hash built))
      (expect = :found (get {flat :found} built)))))

(defn ^:flint.check/test str-to-bytes-takes-every-tier []
  ;; `string?` said TRUE for a rope and `str->b` said "wants a string": it
  ;; asked the BORROWING accessor, which cannot materialise a rope and
  ;; returns nothing by design. Both ports used their general accessor and
  ;; were right. `join_strings` carries a comment about this exact trap and
  ;; was fixed for it; this call site was not.
  (doseq [n [0 1 5 6 32 1024 1600]]
    (let [s (rep n "a")]
      (expect = true (string? s))
      (expect = n (b/size (b/of-string s)))
      ;; ... and back again, so the round trip holds at every tier.
      (expect = true (= s (b/to-string (b/of-string s)))))))

(defn ^:flint.check/test tables-cross-the-chunk-boundary []
  ;; A table stores rows in CHUNKS of 256, so 255/256/257 is where a descent
  ;; off by one shows. Equality is COLUMNAR and row-by-row, and a ref is `=`
  ;; to the map it stands for -- both have to hold across the boundary.
  (let [s (ft/schema [[:id :int]])]
    (doseq [n [0 1 255 256 257 512]]
      (let [rows (mapv (fn [i] {:id i}) (range n))
            t (ft/table s rows)
            t2 (ft/table s rows)]
        (expect = n (count t))
        (expect = true (= t t2))
        (expect = (hash t) (hash t2))
        ;; The FIRST and LAST rows, and the two either side of the boundary.
        (when (pos? n)
          (expect = 0 (:id (get t 0)))
          (expect = (dec n) (:id (get t (dec n))))
          (expect = true (= {:id 0} (get t 0))))
        (when (> n 256)
          (expect = 255 (:id (get t 255)))
          (expect = 256 (:id (get t 256))))
        ;; Past the end is a MISS, not a nil row.
        (expect = :none (get t n :none))))))

(defn ^:flint.check/test nesting-composes-across-tiers []
  ;; Hash and equality COMPOSE. A rope inside a vector inside a set inside a
  ;; map key has to behave as its content, at every level -- which is what
  ;; makes `=` and `hash` agree recursively rather than only at the top.
  (doseq [n [5 33 1025]]
    (let [lit   (rep n "a")
          built (str (rep (quot n 2) "a") (rep (- n (quot n 2)) "a"))
          a {:k [#{lit} {lit lit}]}
          b {:k [#{built} {built built}]}]
      (expect = true (= a b))
      (expect = (hash a) (hash b))
      (expect = 0 (compare [lit] [built]))
      (expect = :found (get {a :found} b)))))

(defn ^:flint.check/test printing-round-trips-at-every-tier []
  ;; `(= x (read-string (pr-str x)))` crosses the PRINTER and the READER, both
  ;; of which are per-runtime code, and it holds for every value edn can
  ;; carry. A value that prints on one runtime and cannot be read back on
  ;; another is an interop bug, and nothing was asking.
  ;;
  ;; The large integers are here on purpose: the image loader truncated a
  ;; literal past the fixnum range, and printing is the OTHER path a big
  ;; number takes through text.
  (doseq [x [nil true false 0 1 -1 7
             fixnum-max fixnum-min (+ fixnum-max 1) (- fixnum-min 1)
             0.0 1.5 -1.5
             "" "a" (rep 6 "a") (rep 33 "a") (rep 1025 "a")
             :k :ns/k (keyword (rep 33 "a")) (keyword "ns" (rep 33 "a"))
             'sym 'ns/sym
             [] [1 2 3] {} {:a 1} #{} #{1 2 3}
             {:a [1 #{2}] :b {:c "d"}}
             [(rep 1025 "a") (keyword (rep 33 "b"))]]]
    (expect = x (edn/read-string (pr-str x)))
    ;; ... and what comes back is the SAME KEY, which is the half `=` alone
    ;; does not cover.
    (expect = (hash x) (hash (edn/read-string (pr-str x))))))

(defn ^:flint.check/test a-rope-hashes-as-a-flat-string []
  ;; STRONGER than the round trip above, which only compares whatever tier
  ;; the reader happens to hand back. Going through BYTES builds a flat
  ;; string whatever came in, so this pins rope-against-flat directly.
  ;;
  ;; `rope-hash` is the raw 31-walk and `string-hash` is that walk through
  ;; `hash-int`. Without the finaliser the two tiers hashed differently and a
  ;; map keyed by one did not find the other -- the one rule `0011` states
  ;; about tiers. All three runtimes agreed, so no cross-runtime check could
  ;; see it.
  (doseq [n [10 1024 1025 2000]]
    (let [rope (rep n "a")
          flat (b/to-string (b/of-string rope))]
      (expect = true (= rope flat))
      (expect = (hash rope) (hash flat))
      (expect = :found (get {rope :found} flat))
      (expect = :found (get {flat :found} rope))
      (expect = true (contains? #{rope} flat)))))

(defn ^:flint.check/test rebuilding-a-collection-gives-the-same-value []
  ;; `(= v (into (empty v) v))` exercises `seq`, `conj`, `empty` and `=` at
  ;; once, and it compares a value AGAINST ITSELF -- which is the shape that
  ;; survives a runtime being consistently wrong. A cross-runtime diff cannot
  ;; see a bug all three share; this can.
  (doseq [n [0 1 7 8 9 33 100]]
    (let [v (vec (range n))
          m (into {} (mapv (fn [i] [i (* i 10)]) (range n)))
          s (set (range n))]
      (doseq [c [v m s]]
        (expect = (count c) (count (seq c)))
        (expect = c (into (empty c) c))
        (expect = (hash c) (hash (into (empty c) c)))))))

(defn ^:flint.check/test writing-and-unwriting-returns-the-original []
  ;; `assoc` then `dissoc`, and `conj` then `pop`, across the representation
  ;; boundaries -- so a promotion that does not demote cleanly shows as a
  ;; value that is no longer `=` to what it started as.
  (doseq [n [0 1 7 8 9 33]]
    (let [m (into {} (mapv (fn [i] [i i]) (range n)))
          v (vec (range n))]
      (expect = m (dissoc (assoc m :extra 1) :extra))
      (expect = (hash m) (hash (dissoc (assoc m :extra 1) :extra)))
      (expect = v (pop (conj v :extra)))
      (expect = (hash v) (hash (pop (conj v :extra))))
      ;; ... and re-assoc'ing a key it already has changes nothing.
      (when (pos? n)
        (expect = m (assoc m 0 0))
        (expect = (hash m) (hash (assoc m 0 0)))))))

(defn ^:flint.check/test equality-and-comparison-agree []
  ;; `=` and `compare` are SEPARATE implementations of "the same value", and
  ;; the rope hash showed what two implementations of one meaning are worth.
  ;; Within a type they must agree: `(= a b)` exactly when `(compare a b)` is
  ;; zero. (Across int and float they legitimately do NOT -- `(= 1 1.0)` is
  ;; false and `(compare 1 1.0)` is 0 -- so this stays within a type.)
  ;;
  ;; ANTISYMMETRY is the other half, and it is what catches a sign bug: the
  ;; boolean arm of `compare` used to subtract two UNSIGNED bits, so `false`
  ;; against `true` was four billion rather than -1.
  (let [groups [[false true]
                [0 1 -1 7 fixnum-max fixnum-min (+ fixnum-max 1)]
                ["" "a" (rep 6 "a") (rep 33 "a") (rep 1025 "a")
                 (b/to-string (b/of-string (rep 1025 "a")))]
                [:a :b :ns/a :ns/b (keyword (rep 33 "a"))]
                ['a 'b 'ns/a]
                [[] [1] [1 2] [2] [1 2 3]]]]
    (doseq [g groups
            a g
            b g]
      (expect = (= a b) (zero? (compare a b)))
      ;; The sign flips and nothing else: not `(- x)`, because -0 is 0.
      (let [ab (compare a b) ba (compare b a)]
        (expect = true (or (and (zero? ab) (zero? ba))
                           (and (neg? ab) (pos? ba))
                           (and (pos? ab) (neg? ba))))))))

(defn ^:flint.check/test a-map-entry-is-callable-like-any-vector []
  ;; `vector?` is true for a map entry and `nth`, `get`, `conj` and `assoc`
  ;; all treat it as a vector -- but CALLING one threw, on all three runtimes,
  ;; while `([:x :y] 1)` worked. One operation out of step with every other.
  (let [me (first {:a 1})]
    (expect = true (vector? me))
    (expect = :a (me 0))
    (expect = 1 (me 1))
    (expect = (nth me 0) (me 0))
    (expect = (get me 1) (me 1))
    ;; ... and the bound is checked, which the slot read behind `get` is not.
    (expect = :threw (try (me 2) (catch Throwable e :threw)))
    (expect = :threw (try (me -1) (catch Throwable e :threw)))
    (expect = :threw (try (me :k) (catch Throwable e :threw)))))

;; A LITERAL and the same value BUILT are two different implementations of one
;; meaning: the literal comes back through the image writer and a loader, the
;; built one comes out of a constructor. That is the shape every bug this file
;; found had, and it is why `2^62` written down read back as 0 on both ports
;; while `2^62` computed was fine -- every arithmetic test built its big
;; values rather than writing them down.
;;
;; `str` is compared only where order is defined: a set or a map may
;; legitimately lay itself out differently depending on how it was made.
(defn- same-value [lit built]
  (expect = lit built)
  (expect = (hash lit) (hash built)))

(defn- same [lit built]
  (same-value lit built)
  (expect = (str lit) (str built)))

(defn ^:flint.check/test every-literal-agrees-with-the-same-value-built []
  (same nil (first []))
  (same true (= 1 1))
  (same false (= 1 2))
  ;; INT, both sides of the fixnum boundary and the two integers with no
  ;; counterpart.
  (same 7 (+ 3 4))
  (same 140737488355327 (- (* 70368744177663 2) -1))
  (same -140737488355328 (* -70368744177664 2))
  (same 140737488355328 (* 70368744177664 2))
  (same 4611686018427387904 (* 2305843009213693952 2))
  (same 9223372036854775807 (- (* 4611686018427387903 2) -1))
  (same -9223372036854775807 (* -9223372036854775807 1))
  ;; DOUBLE. The built side is the RUNTIME reader, which is a different path
  ;; from the compile-time reader that put the literal in the image.
  (same 0.5 (/ 1.0 2.0))
  (same -3.25 (- 0.0 3.25))
  (same 1.0e300 (edn/read-string "1.0e300"))
  ;; STRING, every tier including past FLAT_MAX -- where the literal must load
  ;; as a string longer than anything `str` leaves flat, and still equal the
  ;; ROPE that concatenation produces for the same characters.
  (same "" (rep 0 "x"))
  (same "abcde" (str "ab" "cde"))
  (same "abcdef" (str "abc" "def"))
  (same "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" (rep 32 "a"))
  (same "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" (rep 33 "a"))
  (same "\u00e9\u4e2d" (str "\u00e9" "\u4e2d"))
  (same "qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq" (rep 1100 "q"))
  ;; NAMED, across the inline boundary.
  (same :ab (keyword "ab"))
  (same :xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx (keyword (rep 35 "x")))
  (same 'ab (symbol "ab"))
  (same 'xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx (symbol (rep 35 "x")))
  ;; SEQUENTIAL, and a vector past 32.
  (same [1 2 3] (vec (list 1 2 3)))
  (same '(1 2 3) (apply list [1 2 3]))
  (same [0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30 31 32 33 34 35 36 37 38 39] (vec (range 40)))
  ;; UNORDERED, both sides of the array-map boundary.
  (same-value #{1 2 3} (set [1 2 3]))
  (same-value {:a 1 :b 2} (into {} [[:a 1] [:b 2]]))
  (same-value {:k0 0 :k1 1 :k2 2 :k3 3 :k4 4 :k5 5 :k6 6 :k7 7 :k8 8}
              (into {} (map (fn [i] [(keyword (str "k" i)) i]) (range 9))))
  ;; One of every tier nested inside a single literal.
  (same-value [nil true 1 4611686018427387904 0.5 "abcdef" :k 'p [1] #{2} {:m 3}]
              (vec (list nil true 1 (* 2305843009213693952 2) (/ 1.0 2.0)
                         (str "abc" "def") (keyword "k") (symbol "p")
                         (vec [1]) (set [2]) (into {} [[:m 3]])))))

;; A collection that came back DOWN across a boundary is not the same shape as
;; one that never went up: eight entries reached by growing to nine and
;; dissoc-ing stay a CHAMP, while eight entries built directly are an
;; array-map. Both are the same VALUE, so `=` and `hash` must not be able to
;; tell -- and the tier they land in depends on the path taken, not on the
;; contents.
;;
;; Everything above this line grows. Nothing shrank, so nothing ever compared
;; two equal collections that had settled in different tiers, which is the
;; same blind spot that let a rope and a flat string hash differently.
(defn- kmap [n] (reduce (fn [m i] (assoc m (keyword (str "k" i)) i)) {} (range n)))

(defn ^:flint.check/test coming-back-down-a-boundary-changes-nothing []
  ;; MAPS, across ARRAY-MAP/CHAMP.
  (let [down (dissoc (kmap 9) :k8)
        flat (kmap 8)]
    (expect = flat down)
    (expect = down flat)
    (expect = (hash flat) (hash down))
    (expect = (count flat) (count down))
    (expect = (set (keys flat)) (set (keys down)))
    (expect = flat (into {} down))
    ;; and a map with one entry, reached from nine
    (expect = {:k0 0} (reduce dissoc (kmap 9) [:k1 :k2 :k3 :k4 :k5 :k6 :k7 :k8]))
    (expect = (hash {:k0 0})
             (hash (reduce dissoc (kmap 9) [:k1 :k2 :k3 :k4 :k5 :k6 :k7 :k8]))))
  ;; SETS, which are their backing map and so cross the same boundary.
  (let [down (disj (set (range 9)) 8)
        flat (set (range 8))]
    (expect = flat down)
    (expect = (hash flat) (hash down))
    (expect = (count flat) (count down)))
  ;; VECTORS, across the trie boundary at 32.
  (let [down (reduce (fn [v _] (pop v)) (vec (range 40)) (range 8))
        flat (vec (range 32))]
    (expect = flat down)
    (expect = (hash flat) (hash down))
    (expect = (str flat) (str down))
    (expect = (nth down 31) 31))
  ;; STRINGS: a rope cut back below FLAT_MAX against one that was never joined.
  (let [down (subs (str (rep 800 "a") (rep 800 "a")) 0 40)
        flat (rep 40 "a")]
    (expect = flat down)
    (expect = (hash flat) (hash down))
    (expect = (count flat) (count down))
    (expect = (compare flat down) 0)))

;; Two ropes with the same characters but DIFFERENT TREE SHAPES. `str` builds
;; a tree, so `(str (str a b) c)` and `(str a (str b c))` hold the same text
;; in different trees, and a walk that depends on shape rather than content
;; answers differently for the two. `compare` already did exactly that once,
;; reading a rope's slots as if they were UTF-8.
;;
;; The same question as the tier boundaries above, asked of shape instead of
;; size: the value is the characters, and nothing about the tree may show.
(defn ^:flint.check/test rope-shape-does-not-show []
  (let [a (rep 700 "a") b (rep 700 "b") c (rep 700 "c")
        left  (str (str a b) c)
        right (str a (str b c))
        many  (reduce str "" [a b c])
        one   (str a b c)]
    (expect = left right)
    (expect = (hash left) (hash right))
    (expect = (compare left right) 0)
    (expect = left many)
    (expect = (hash left) (hash many))
    (expect = left one)
    (expect = (hash left) (hash one))
    (expect = (count left) (count right))
    (expect = (subs left 0 10) (subs right 0 10))
    (expect = (subs left 1000 1010) (subs many 1000 1010))
    (expect = (str left) (str right)))
  ;; A DEEP rope, grown one character at a time, against a shallow one.
  (let [a (rep 700 "a")
        deep (reduce (fn [s _] (str s "x")) a (range 300))
        shallow (str a (rep 300 "x"))]
    (expect = deep shallow)
    (expect = (hash deep) (hash shallow))
    (expect = (compare deep shallow) 0)
    (expect = (count deep) (count shallow)))
  ;; BYTE strings have their own tree and their own walk.
  (let [x (b/of-string (rep 700 "a"))
        y (b/of-string (rep 700 "b"))
        z (b/of-string (rep 700 "c"))
        bleft (b/cat (b/cat x y) z)
        bright (b/cat x (b/cat y z))]
    (expect = bleft bright)
    (expect = (hash bleft) (hash bright))
    (expect = (b/size bleft) (b/size bright))
    (expect = (b/to-string bleft) (b/to-string bright))))

;; MAP EQUALITY IS A STRUCTURAL WALK NOW (`kin/mapeq.kin`), and these are the
;; three places where the structure and the value disagree.
;;
;; A CHAMP is canonical -- the same entries give the same trie whatever order
;; they arrived in -- which is what makes a parallel walk valid at all. Two
;; things are not canonical, and both are handled rather than assumed:
;;
;;   COLLISION NODES hold keys that share a hash in INSERTION order, so
;;   `{"Aa" 1 "BB" 2}` and the same map built the other way round differ in
;;   exactly one node. "Aa" and "BB" are the classic Java collision and the
;;   reason they are here.
;;
;;   THE TIER is path-dependent, so equal maps can be different shapes --
;;   covered above by `coming-back-down-a-boundary-changes-nothing`.
(defn ^:flint.check/test colliding-keys-are-equal-in-any-order []
  (let [pad (reduce (fn [m i] (assoc m (str "pad" i) i)) {} (range 12))
        a (assoc (assoc pad "Aa" 1) "BB" 2)
        b (assoc (assoc pad "BB" 2) "Aa" 1)]
    (expect = a b)
    (expect = b a)
    (expect = (hash a) (hash b))
    (expect = (count a) (count b))
    (expect = 1 (get a "Aa"))
    (expect = 1 (get b "Aa"))
    (expect = 2 (get a "BB"))
    (expect = 2 (get b "BB"))
    ;; and a differing value under a colliding key must still be caught
    (expect = false (= a (assoc b "Aa" 99)))
    (expect = false (= (assoc a "BB" 99) b)))
  ;; the same, as sets, which are their backing map
  (let [pad (reduce conj #{} (map (fn [i] (str "pad" i)) (range 12)))
        a (conj (conj pad "Aa") "BB")
        b (conj (conj pad "BB") "Aa")]
    (expect = a b)
    (expect = (hash a) (hash b))
    (expect = true (contains? a "Aa"))
    (expect = true (contains? b "Aa"))))

(defn ^:flint.check/test maps-that-share-structure-still-compare []
  ;; `b` is `a` with one entry added and removed, so it shares every node but
  ;; the spine. The walk prunes shared subtrees on identity; the answer must
  ;; not depend on that.
  (let [a (reduce (fn [m i] (assoc m [:k i] i)) {} (range 300))
        b (dissoc (assoc a [:k :extra] 1) [:k :extra])
        c (reduce (fn [m i] (assoc m [:k i] i)) {} (range 300))]
    (expect = a b)
    (expect = a c)
    (expect = (hash a) (hash b))
    (expect = (hash a) (hash c))
    (expect = false (= a (assoc b [:k 0] 999)))
    (expect = false (= a (dissoc b [:k 0])))))

;; SORT IS BOTTOM-UP AND STABLE, and the pass boundaries are where a merge
;; sort goes wrong: runs of 1, 2, 4, 8 and the ragged last run of each pass.
;;
;; Stability is not decoration -- `sort-by` is worth nothing without it, and a
;; merge that takes from the RIGHT on a tie loses it while still returning a
;; correctly ordered answer, which no ordering check would catch.
(defn ^:flint.check/test sorting-holds-at-every-run-boundary []
  (expect = '() (sort []))
  (expect = '(1) (sort [1]))
  (expect = '(1 2) (sort [2 1]))
  (expect = '(1 1 2 2 3) (sort [2 1 3 2 1]))
  (expect = '(5 4 3 2 1) (sort > [1 2 3 4 5]))
  (doseq [n [3 4 5 7 8 9 15 16 17 33 100]]
    (let [xs (vec (map (fn [i] (mod (* i 37) 19)) (range n)))
          s (vec (sort xs))]
      (expect = n (count s))
      (expect = (frequencies xs) (frequencies s))
      (expect = true (apply <= s))))
  ;; equal keys keep their input order
  (let [pairs [[1 :a] [0 :b] [1 :c] [0 :d] [1 :e]]]
    (expect = [[0 :b] [0 :d] [1 :a] [1 :c] [1 :e]]
             (vec (sort-by (fn [p] (nth p 0)) pairs))))
  ;; a comparator that answers with a number rather than a boolean
  (expect = '(3 2 1) (sort (fn [a b] (- b a)) [1 3 2]))
  (expect = '(1 2 3) (sort (list 3 1 2)))
  (expect = '(0 1 2) (sort (range 3))))
