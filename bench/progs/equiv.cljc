(ns equiv
  "THE BASELINE THAT `doc/goals/equiv-hash.md` ASKS FOR BEFORE ITS FIRST LINE
  OF IMPLEMENTATION -- every cost claim in that document is read rather than
  run, and its last open bullet says so.

  The design puts a bit in the object header saying `this value carries a
  runtime-honoured equality`, and the union of that bit in every CHAMP node.
  The whole argument for it is that a program with NO extended values pays
  nothing measurable. That is a claim about the numbers below, so the numbers
  below have to exist first, and they have to exist BEFORE the bit -- a
  baseline taken afterwards is not a baseline.

  WHAT IS ACTUALLY AT RISK, which is narrower than `= is hot`. `mapeq` has
  three exits and the bit threatens exactly one of them:

    exit 1, THE SAME NODE ON BOTH SIDES, is untouched. Pointer equality prunes
      the subtree before anything reads a header. `share` measures it anyway,
      because the 840us -> 1.2us in `mapeq.kin` is the number most often
      quoted at this design and a regression there would be the loudest thing
      that could go wrong.
    exit 2, A BITMAP MISMATCH, is untouched for the same reason. `reject-hi`.
      `reject-shared` is the same exit reached after a one-path descent, and
      it is the cheapest row in the file -- a rejection can beat an
      acceptance, because it stops at the first thing that differs.
    exit 3, THE PAIRWISE WALK, is where a per-leaf test would land, and
      `noshare` is the mode that would show it. This is the row to watch.

  `nested` is the second row to watch. The design's real claim is that the
  question is answered ONCE AT THE ROOT rather than once per level, so a
  shape with many levels and few entries per level is where `once per level`
  would become visible and a flat shape would hide it.

  WHAT THIS DOES NOT COVER, said plainly rather than left to be discovered:

    COLLISION NODES. `mapeq` compares them unordered and that is a real path,
    still not measured here. This paragraph USED TO SAY that engineering a
    collision needed flint's hash rather than a guess, and that `Aa`/`BB`
    \"collide under Java's `String.hashCode` and mean nothing here\". The first
    half was right and the second was a guess -- flint's string hash is a
    base-31 polynomial too, every such pair collides, and asking flint for the
    hashes took one probe. That unverified aside was hiding a metering hole:
    a collision scan was billed a flat 16 steps whatever its width
    (`doc/goals/hash-flooding.md`). The row still is not here, but the reason
    is now scheduling rather than impossibility.
    THE PORTS. Native and the two ports differ on which of these paths they
    even run, and this file measures wasm. The design's third open bullet --
    that all three runtimes read the header the same way -- stays unverified.

  ON REPETITION. A single `=` over a shared trie is ~1.2us and not
  measurable, so every mode loops `r` times and the BASE twin runs the SAME
  loop with the same accumulate and no comparison. If a mode ever reports a
  delta of exactly zero, suspect the compiler hoisted the invariant call out
  of the loop before believing the operation is free.")

(defn- pairs [n] (mapv (fn [i] [(flint.rt/num->str i) i]) (range n)))
(defn- big [n] (into {} (pairs n)))

;; Two equal maps built independently: no structural sharing anywhere, so a
;; comparison has to walk. `into` twice over two separately-built pair vectors
;; -- building from ONE vector twice would still give distinct tries, but this
;; keeps the two sides independent all the way down.
(defn- twin [n] [(big n) (big n)])

;; Equal to its input and sharing all but one path with it, which is the
;; case `mapeq` was written for: derived by an assoc and a dissoc, then back
;; to canonical form. CHAMP is canonical, so the result is structurally
;; identical to the original and shares almost every node with it.
(defn- derived [m] (dissoc (assoc m "__probe__" 1) "__probe__"))

;; Nested to `d` levels with one map per level, so a descent has depth and
;; almost no width. This is the shape that separates `once at the root` from
;; `once per level`.
(defn- deep [d]
  (loop [i 0 m {:leaf 1}]
    (if (flint.rt/lt i d) (recur (flint.rt/add i 1) {:k m :i i}) m)))

(defn main [args]
  (let [what (first args)
        n (flint.rt/str->num (second args))
        r (flint.rt/str->num (flint.rt/nth args 2))
        base? (if (flint.rt/lt (flint.rt/count what) 5)
                false
                (flint.rt/= "base-" (flint.rt/subs what 0 5)))
        op (if base? (flint.rt/subs what 5 (flint.rt/count what)) what)
        ;; Every arm is this loop. The BASE twin accumulates 1 where the
        ;; measured arm accumulates the comparison, so the delta is the
        ;; comparison and one branch, and the loop itself cancels.
        spin (fn [f] (loop [i 0 acc 0]
                       (if (flint.rt/lt i r)
                         (recur (flint.rt/add i 1)
                                (flint.rt/add acc (if base? 1 (if (f) 1 0))))
                         acc)))
        out
        (cond
          ;; --- the three exits of mapeq -----------------------------------
          (flint.rt/= op "share")
          (let [a (big n) b (derived a)] (spin (fn [] (flint.rt/= a b))))
          (flint.rt/= op "noshare")
          (let [t (twin n)] (spin (fn [] (flint.rt/= (nth t 0) (nth t 1)))))
          ;; Rejected at the root on a count/bitmap mismatch: O(1), and the
          ;; floor for what a comparison can cost at all.
          (flint.rt/= op "reject-hi")
          (let [a (big n) b (assoc a "__extra__" 1)]
            (spin (fn [] (flint.rt/= a b))))
          ;; A REJECTION THAT STILL SHARES. `b` is `a` with one leaf changed,
          ;; so it shares every node except the path to that leaf and the
          ;; walk prunes the rest -- measured FASTER than the O(1) root
          ;; rejection above, because it never even compares two bitmaps at
          ;; the root before descending a single path.
          ;;
          ;; This row was first written as `reject-lo` and called the worst
          ;; case, on the reasoning that a difference at the last leaf forces
          ;; the walk to the bottom. It does -- down ONE path. The number said
          ;; so immediately: 40ns against noshare's 419us. Sharing was the
          ;; whole story and the label was the wrong one.
          (flint.rt/= op "reject-shared")
          (let [a (big n) b (assoc a (flint.rt/num->str (flint.rt/sub n 1)) -1)]
            (spin (fn [] (flint.rt/= a b))))
          ;; THE ACTUAL WORST CASE: two maps that share nothing and differ in
          ;; one leaf, so every exit fails and the walk covers the whole trie
          ;; before it can answer. This is the row a per-leaf test would tax
          ;; hardest, and it should sit alongside `noshare` rather than below
          ;; `reject-hi`.
          (flint.rt/= op "reject-deep")
          (let [t (twin n)
                a (nth t 0)
                b (assoc (nth t 1) (flint.rt/num->str (flint.rt/sub n 1)) -1)]
            (spin (fn [] (flint.rt/= a b))))
          ;; --- depth against width ----------------------------------------
          ;; DEPTH IS FIXED AT 12 AND DOES NOT SCALE WITH `n`, because the
          ;; question this row asks is about levels rather than size, and a
          ;; nesting 20 000 deep would ask a different one -- whether the
          ;; comparison recurses far enough to exhaust the frame budget.
          (flint.rt/= op "nested")
          (let [a (deep 12) b (deep 12)] (spin (fn [] (flint.rt/= a b))))
          ;; --- the tier below CHAMP ---------------------------------------
          ;; Under ARRAY_MAP_MAX a map is a flat array and `mapeq` falls back
          ;; to a lookup loop. Most maps in a real program are this one.
          (flint.rt/= op "arraymap")
          (let [a {:a 1 :b 2 :c 3 :d 4} b {:a 1 :b 2 :c 3 :d 4}]
            (spin (fn [] (flint.rt/= a b))))
          ;; --- vectors ----------------------------------------------------
          (flint.rt/= op "vec")
          (let [a (vec (range n)) b (vec (range n))]
            (spin (fn [] (flint.rt/= a b))))
          ;; --- the early-outs ---------------------------------------------
          ;; Fixnums return before the header word is ever loaded. If the bit
          ;; ever costs anything HERE, it has been put in the wrong place.
          (flint.rt/= op "fixnum")
          (spin (fn [] (flint.rt/= 7 7)))
          ;; Identity: the same pointer on both sides.
          (flint.rt/= op "identical")
          (let [a (big n)] (spin (fn [] (flint.rt/= a a))))
          ;; --- hash -------------------------------------------------------
          ;; A collection hash is CACHED, so hashing one map r times measures
          ;; the cache. These hash r DISTINCT small maps, which is what a
          ;; program putting values in a set actually does.
          ;; THE COLLECTION IS BUILT IN BOTH ARMS. The first version built it
          ;; only in the measured arm, so the row reported construction PLUS
          ;; hashing and said so itself: two allocations per op for a map and
          ;; six for a vector, on an operation that should allocate nothing.
          ;; Binding it before the branch puts the construction in both arms,
          ;; where it cancels.
          (flint.rt/= op "hash-map")
          (loop [i 0 acc 0]
            (if (flint.rt/lt i r)
              (let [m {:k i :j (flint.rt/add i 1)}]
                (recur (flint.rt/add i 1)
                       (if base? (flint.rt/add acc (count m))
                           (flint.rt/add acc (flint.rt/hash m)))))
              acc))
          (flint.rt/= op "hash-vec")
          (loop [i 0 acc 0]
            (if (flint.rt/lt i r)
              (let [v [i (flint.rt/add i 1)]]
                (recur (flint.rt/add i 1)
                       (if base? (flint.rt/add acc (count v))
                           (flint.rt/add acc (flint.rt/hash v)))))
              acc))
          ;; A BIG vector, hashed once each time. `assoc` gives a fresh vector
          ;; with an empty hash slot, so every round does the walk rather than
          ;; reading the cache -- which is the case a set insertion is, and
          ;; the one the cache never helps.
          (flint.rt/= op "hash-bigvec")
          (let [v (vec (range n))]
            (loop [i 0 acc 0]
              (if (flint.rt/lt i r)
                (let [w (assoc v 0 i)]
                  (recur (flint.rt/add i 1)
                         (if base? (flint.rt/add acc (count w))
                             (flint.rt/add acc (flint.rt/hash w)))))
                acc)))
          ;; --- hash AND equiv together, which is the hot path -------------
          ;; Bucket selection hashes the key and then compares it. This is the
          ;; path §2 of the design calls the cross-runtime budget's own
          ;; measurement, and the one a callback would be re-entered from.
          (flint.rt/= op "map-lookup")
          (let [m (big n)]
            (loop [i 0 acc 0]
              (if (flint.rt/lt i r)
                (recur (flint.rt/add i 1)
                       (if base? (flint.rt/add acc 1)
                           (flint.rt/add acc (get m (flint.rt/num->str (flint.rt/rem i n)) 0))))
                acc)))
          (flint.rt/= op "set-member")
          (let [s (set (mapv (fn [i] (flint.rt/num->str i)) (range n)))]
            (loop [i 0 acc 0]
              (if (flint.rt/lt i r)
                (recur (flint.rt/add i 1)
                       (if base? (flint.rt/add acc 1)
                           (flint.rt/add acc (if (contains? s (flint.rt/num->str (flint.rt/rem i n))) 1 0))))
                acc)))
          :else -1)]
    (flint.rt/num->str out)))
