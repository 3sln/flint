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
