(ns types
  "Checked type annotations, and the write barrier that makes them sound.

  A tag is not a hint here. `(let [^int x e] ...)` tests once at the write, and
  every read afterwards is known without testing -- which is the property the
  optimiser needs and the property a hint cannot give. So the checks below are
  mostly about the barrier being in the right places, and about it being there
  on the SECOND iteration of a loop, which is where an annotation that is
  merely recorded stops being true.")

(defn caught [f]
  (try (f) (catch Exception e (str "!" (ex-message e)))))

;; --- the barrier at each kind of binding -----------------------------------

(defn let-ok  [x] (let [^int n x] (flint.rt/add n 1)))
(defn let-bad [x] (let [^int n x] (flint.rt/add n 1)))

(defn param-ok  [^int n] (flint.rt/add n 1))

(defn ^int ret-ok  [x] x)

;; The loop case Ray names: enforced on initialisation AND on every recur.
(defn loop-init [start]
  (loop [^int i start] (if (flint.rt/lt i 3) (recur (flint.rt/add i 1)) i)))

(defn loop-recur [n]
  ;; The first iteration is fine and the second is not -- the shape that makes
  ;; an unchecked recur so dangerous, because a test that runs one iteration
  ;; passes.
  (loop [^int i 0]
    (if (flint.rt/lt i 2)
      (recur (if (flint.rt/lt i n) (flint.rt/add i 1) "not an int"))
      i)))

;; --- use-site annotations ---------------------------------------------------

(defn use-site [x y] (flint.rt/add ^int x ^int y))

;; --- the lattice ------------------------------------------------------------

(defn widen [x] (let [^number n x] n))

;; --- what elision is worth --------------------------------------------------
;;
;; The point of the barrier being at the WRITE is that reads are then free. So
;; annotating a value that is already known must cost exactly nothing, and the
;; three loops below say whether it does: `bare` and `proven` must have
;; IDENTICAL instruction counts, while `opaque` pays one check per iteration
;; because nothing upstream established the type.

(defn bare-loop [n]
  (loop [i 0 acc 0]
    (if (flint.rt/lt i n) (recur (flint.rt/add i 1) (flint.rt/add acc i)) acc)))

(defn proven-loop [n]
  ;; `i` is annotated and so is every use of it. The initialiser is a literal
  ;; and the recur argument is an add on a known int, so no check is emitted.
  (loop [^int i 0 ^int acc 0]
    (if (flint.rt/lt i n)
      (recur (flint.rt/add ^int i 1) (flint.rt/add ^int acc ^int i))
      acc)))

(defn opaque-loop [n xs]
  ;; `x` comes out of a vector, so nothing is known about it and the annotation
  ;; is a real test every iteration. This is the cost being measured against.
  (loop [i 0 acc 0]
    (if (flint.rt/lt i n)
      (let [^int x (flint.rt/nth xs 0)]
        (recur (flint.rt/add i 1) (flint.rt/add acc x)))
      acc)))

;; --- occurrence narrowing ---------------------------------------------------
;;
;; `(if (int? x) ...)` compiles the then-branch knowing `x`. Nobody writes an
;; annotation; the test they already wrote is the annotation. The measurement
;; below is the one that matters: `narrowed` writes an ordinary `int?` guard
;; and then annotates inside it, and must cost the SAME as writing no
;; annotation at all -- because the guard already established the fact.

(defn narrowed-loop [n xs]
  (loop [i 0 acc 0]
    (if (flint.rt/lt i n)
      (let [x (flint.rt/nth xs 0)]
        (if (int? x)
          ;; Inside the guard, `^int x` is free.
          (recur (flint.rt/add i 1) (flint.rt/add acc ^int x))
          (recur (flint.rt/add i 1) acc)))
      acc)))

(defn unnarrowed-loop [n xs]
  ;; The control, and it has to be the SAME program with the annotation
  ;; removed -- not the same annotation under a different guard. The first
  ;; version of this compared `(if (int? x) ..)` against `(if (lt 0 1) ..)`,
  ;; so the two differed by the cost of the guard as well as by the narrowing,
  ;; and the assertion still passed with narrowing switched off. Against this
  ;; control the claim is exact: if the guard establishes the fact, annotating
  ;; inside it costs nothing at all.
  (loop [i 0 acc 0]
    (if (flint.rt/lt i n)
      (let [x (flint.rt/nth xs 0)]
        (if (int? x)
          (recur (flint.rt/add i 1) (flint.rt/add acc x))
          (recur (flint.rt/add i 1) acc)))
      acc)))

;; The `and` path needs its own measurement. Removing the propagation through
;; the let that `and` expands to leaves every answer in this file correct and
;; costs one check per iteration -- so only a count can see it.
(defn and-narrowed-loop [n xs]
  (loop [i 0 acc 0]
    (if (flint.rt/lt i n)
      (let [x (flint.rt/nth xs 0)]
        (if (and (int? x) (flint.rt/lt i n))
          (recur (flint.rt/add i 1) (flint.rt/add acc ^int x))
          (recur (flint.rt/add i 1) acc)))
      acc)))

(defn and-plain-loop [n xs]
  (loop [i 0 acc 0]
    (if (flint.rt/lt i n)
      (let [x (flint.rt/nth xs 0)]
        (if (and (int? x) (flint.rt/lt i n))
          (recur (flint.rt/add i 1) (flint.rt/add acc x))
          (recur (flint.rt/add i 1) acc)))
      acc)))

;; `and` expands to a let plus an if, so the projection has to survive one
;; binding to reach the branch. This is the case that would silently not work.
(defn and-narrowed [a b]
  (if (and (int? a) (int? b)) (flint.rt/add ^int a ^int b) :nope))

;; A user-declared projection: as good as a builtin predicate.
(defn ^{:result-projected-meta {true {v {:tag int}}}}
  my-int? [v] (int? v))

(defn user-narrowed [x]
  (if (my-int? x) (flint.rt/add ^int x 1) :nope))

;; `or` proves only what BOTH arms prove. `(or (int? x) (string? x))` says
;; nothing about `x`, and narrowing it to int here would be the unsound
;; direction -- so this must still throw for a string.
(defn or-disagrees [x]
  (if (or (int? x) (string? x)) (flint.rt/add ^int x 1) :nope))

;; ... and when both arms agree, it does narrow.
(defn or-agrees [x y]
  (if (or (int? x) (int? x)) (flint.rt/add ^int x (if y 1 2)) :nope))

;; Soundness: narrowing must reach the THEN branch and nothing else. If it
;; leaked into the else branch, this would add 1 to a string instead of
;; throwing -- and it would do it silently, which is the whole risk of the
;; feature.
(defn narrow-else [x] (if (int? x) :yes (flint.rt/add ^int x 1)))

;; And a projection must not survive a rebinding. `x` is a loop binding, so
;; `recur` can put something else in the slot; a projection recorded on the
;; first value would be a claim about a value that is gone.
(defn narrow-rebound [n]
  (loop [x 1 i 0]
    (if (flint.rt/lt i n)
      (recur "no longer an int" (flint.rt/add i 1))
      (if (int? x) (flint.rt/add ^int x 1) :not-int))))

(defn main [args]
  (let [mode (first args)]
   (cond
    (= mode "bare")   (pr-str (bare-loop (flint.rt/str->num (second args))))
    (= mode "proven") (pr-str (proven-loop (flint.rt/str->num (second args))))
    (= mode "opaque") (pr-str (opaque-loop (flint.rt/str->num (second args)) [1]))
    (= mode "narrowed") (pr-str (narrowed-loop (flint.rt/str->num (second args)) [1]))
    (= mode "unnarrowed") (pr-str (unnarrowed-loop (flint.rt/str->num (second args)) [1]))
    (= mode "and-narrowed") (pr-str (and-narrowed-loop (flint.rt/str->num (second args)) [1]))
    (= mode "and-plain") (pr-str (and-plain-loop (flint.rt/str->num (second args)) [1]))
    :else
  (pr-str
   {:let-ok      (let-ok 41)
    :let-bad     (caught (fn [] (let-bad "no")))
    :param-ok    (param-ok 41)
    :param-bad   (caught (fn [] (param-ok :no)))
    :ret-ok      (ret-ok 7)
    :ret-bad     (caught (fn [] (ret-ok "no")))
    :loop-init   (loop-init 0)
    :loop-init-bad (caught (fn [] (loop-init "no")))
    :loop-recur-bad (caught (fn [] (loop-recur 0)))
    :use-site    (use-site 1 2)
    :use-site-bad (caught (fn [] (use-site 1 "no")))
    :widen-int   (widen 3)
    :widen-float (widen 1.5)
    :widen-bad   (caught (fn [] (widen "no")))
    :loops-agree (= (bare-loop 50) (proven-loop 50))
    :and-narrowed (and-narrowed 2 3)
    :and-refuses  (and-narrowed 2 "x")
    :user-narrowed (user-narrowed 41)
    :user-refuses  (user-narrowed "x")
    :narrow-else-yes (narrow-else 1)
    :narrow-else-throws (caught (fn [] (narrow-else "s")))
    :narrow-rebound-0 (narrow-rebound 0)
    :narrow-rebound-1 (narrow-rebound 1)
    :or-disagrees-int (or-disagrees 1)
    :or-disagrees-str (caught (fn [] (or-disagrees "s")))
    :or-agrees        (or-agrees 41 true)}))))
