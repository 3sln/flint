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
