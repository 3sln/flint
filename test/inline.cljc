(ns inline
  "`:inline` metadata, which the compiler applies at the call site.

  Every check here is about a difference the RESULT can show, not about the
  bytecode: an inline that silently does not fire produces the same answer as
  one that does, so the counter-examples below are written so that the two
  answers differ. `test/inline.clj` reads the instruction counts beside these,
  because a correct answer through the slow path is the failure mode.")

;; The plain shape: an inline that expands to the builtin the wrapper calls.
(defn ^{:inline (fn [a b] (list 'flint.rt/add a b))
        :inline-arities #{2}}
  add2
  ([a b] (flint.rt/add a b))
  ([a b c] (flint.rt/add (flint.rt/add a b) c)))

;; The inline is allowed to say something DIFFERENT from the function. Nothing
;; else in this file can tell whether inlining happened; this can.
(defn ^{:inline (fn [x] (list 'quote :inlined))} tattle [x] :called)

;; Arity gating. Only the 2-arity has an inline, so the 3-arity must run the
;; real function -- inlining `(gated 1 2 3)` with a two-argument body would
;; silently drop an argument, which is the failure this gate exists to stop.
(defn ^{:inline (fn [a b] (list 'quote :inline-2))
        :inline-arities #{2}}
  gated
  ([a b] :fn-2)
  ([a b c] :fn-3))

;; An inline that evaluates its argument form twice. That is a real hazard of
;; the feature, not a defect of it -- Clojure has it too -- and it is recorded
;; here so the behaviour is pinned rather than discovered.
(defn ^{:inline (fn [x] (list 'flint.rt/add x x))} twice [x] (flint.rt/add x x))

;; Used as a VALUE rather than called: no inline applies, the function runs.
(def as-value tattle)

;; Nesting: the argument of an inlined call is itself an inlined call.
(defn nested [] (add2 (add2 1 2) (add2 3 4)))

;; Syntax quote, because that is how anyone would actually write one.
(defn ^{:inline (fn [x] `(flint.rt/mul ~x ~x))} square [x] (flint.rt/mul x x))

;; A local shadowing the var must win: `(add2 ...)` here is the parameter.
(defn shadowed [add2] (add2 10))

;; --- the measurement -------------------------------------------------------
;;
;; Two loops that compute the same thing, one through inlined vars and one
;; through identical vars with no `:inline`. The instruction count is
;; deterministic (0009), so the difference between them is the feature's effect
;; and not a timing. A run where the two counts are EQUAL means the inline did
;; not fire, which is the failure this pair exists to catch -- and the answers
;; are compared too, because an inline that fires and is wrong is worse.

;; The bodies here are deliberately NOT one native call with the parameters in
;; order, because that shape is already inlined by `register-native-aliases!`
;; and a comparison against it measures nothing. The first version of this test
;; compared two one-native wrappers and reported a saving of FOUR instructions
;; out of thirty thousand, which is what the alias mechanism leaving nothing to
;; do looks like. A two-operation body is the case only `:inline` reaches.

(defn ^{:inline (fn [a b] `(flint.rt/add (flint.rt/mul ~a 3) ~b))}
  i-poly [a b] (flint.rt/add (flint.rt/mul a 3) b))
(defn p-poly [a b] (flint.rt/add (flint.rt/mul a 3) b))

(defn ^{:inline (fn [x] `(flint.rt/lt (flint.rt/sub ~x 1) 2000))}
  i-more? [x] (flint.rt/lt (flint.rt/sub x 1) 2000))
(defn p-more? [x] (flint.rt/lt (flint.rt/sub x 1) 2000))

(defn inlined-loop [n]
  (loop [i 0 acc 0]
    (if (and (flint.rt/lt i n) (i-more? i))
      (recur (flint.rt/add i 1) (i-poly i acc))
      acc)))

(defn plain-loop [n]
  (loop [i 0 acc 0]
    (if (and (flint.rt/lt i n) (p-more? i))
      (recur (flint.rt/add i 1) (p-poly i acc))
      acc)))

(defn main [args]
  (let [mode (first args)]
  (cond
    (= mode "inlined") (pr-str (inlined-loop (flint.rt/str->num (second args))))
    (= mode "plain")   (pr-str (plain-loop (flint.rt/str->num (second args))))
    :else
    (let [n (volatile! 0)]
      (pr-str
       {:add2-2       (add2 3 4)
        :add2-3       (add2 3 4 5)
        :tattle       (tattle 1)
        :as-value     (as-value 1)
        :map-value    (mapv tattle [1 2])
        :gated-2      (gated 1 2)
        :gated-3      (gated 1 2 3)
        :twice-pure   (twice 21)
        ;; The double-evaluation hazard, made visible: the argument form has an
        ;; effect, and the inline runs it twice. Clojure has this too; it is
        ;; pinned here so it is a recorded property rather than a discovery.
        :twice-effect (do (vreset! n 0)
                          (let [r (twice (do (vswap! n inc) 5))]
                            [r @n]))
        :nested       (nested)
        :square       (square 7)
        :shadowed     (shadowed (fn [x] (flint.rt/add x 1)))
        :loops-agree  (= (inlined-loop 50) (plain-loop 50))})))))
