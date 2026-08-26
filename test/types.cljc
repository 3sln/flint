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

(defn main [args]
  (let [mode (first args)]
   (cond
    (= mode "bare")   (pr-str (bare-loop (flint.rt/str->num (second args))))
    (= mode "proven") (pr-str (proven-loop (flint.rt/str->num (second args))))
    (= mode "opaque") (pr-str (opaque-loop (flint.rt/str->num (second args)) [1]))
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
    :loops-agree (= (bare-loop 50) (proven-loop 50))}))))
