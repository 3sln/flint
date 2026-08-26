(ns narrow
  "Does occurrence narrowing compose with the forms people actually write?

  Every function here is the same program: guard a value, then annotate it
  inside the guard. If narrowing reached that branch the annotation is free and
  NO `flint/check-tag` is emitted; if it did not, one is. The harness counts
  the calls, so each shape gets a yes or a no rather than an argument -- and a
  shape that does not compose shows up as a number, not as a wrong answer.")

(defn s-plain      [x] (if (int? x)                        (flint.rt/add ^int x 1) 0))
(defn s-and2       [x] (if (and (int? x) true)             (flint.rt/add ^int x 1) 0))
(defn s-and3       [x] (if (and true (int? x) true)        (flint.rt/add ^int x 1) 0))
(defn s-and-nested [x] (if (and (and (int? x) true) true)  (flint.rt/add ^int x 1) 0))
(defn s-or-same    [x] (if (or (int? x) (int? x))          (flint.rt/add ^int x 1) 0))
(defn s-or-in-and  [x] (if (and (or (int? x) (int? x)) true) (flint.rt/add ^int x 1) 0))
(defn s-when       [x] (or (when (int? x)                  (flint.rt/add ^int x 1)) 0))
(defn s-cond       [x] (cond (int? x)                      (flint.rt/add ^int x 1) :else 0))
(defn s-if-not     [x] (if-not (int? x) 0                  (flint.rt/add ^int x 1)))
(defn s-when-not   [x] (or (when-not (int? x) 0)           (flint.rt/add ^int x 1)))
(defn s-not        [x] (if (not (int? x)) 0                (flint.rt/add ^int x 1)))
(defn s-else       [x] (if (int? x) 0                      1))
(defn s-let        [x] (let [g (int? x)] (if g             (flint.rt/add ^int x 1) 0)))
(defn s-nested-if  [x] (if (int? x) (if true               (flint.rt/add ^int x 1) 0) 0))
(defn s-and-two    [x y] (if (and (int? x) (int? y))
                           (flint.rt/add ^int x ^int y) 0))

;; The other side of each, which must NOT narrow. An inversion that went the
;; wrong way would show up here as a zero, and nowhere else -- every answer in
;; this file stays correct either way, because the guard happens to hold.
(defn s-not-wrong-side  [x] (if (not (int? x)) (flint.rt/add ^int x 1) 0))
(defn s-and-wrong-side  [x] (if (and (int? x) true) 0 (flint.rt/add ^int x 1)))

(def shapes
  ["plain" "and2" "and3" "and-nested" "or-same" "or-in-and" "when" "cond"
   "if-not" "when-not" "not" "else" "let" "nested-if" "and-two"
   "not-wrong-side" "and-wrong-side"])

(defn run1 [s x]
  (cond
    (= s "plain") (s-plain x)      (= s "and2") (s-and2 x)
    (= s "and3") (s-and3 x)        (= s "and-nested") (s-and-nested x)
    (= s "or-same") (s-or-same x)  (= s "or-in-and") (s-or-in-and x)
    (= s "when") (s-when x)        (= s "cond") (s-cond x)
    (= s "if-not") (s-if-not x)    (= s "when-not") (s-when-not x)
    (= s "not") (s-not x)          (= s "else") (s-else x)
    (= s "let") (s-let x)          (= s "nested-if") (s-nested-if x)
    (= s "not-wrong-side") (s-not-wrong-side x)
    (= s "and-wrong-side") (s-and-wrong-side x)
    :else (s-and-two x x)))

(defn main [args]
  (let [s (first args)]
    (if (= s "list")
      (pr-str shapes)
      ;; 41 satisfies every guard, so a check that RUNS is a check that was not
      ;; needed -- which is what is being counted. The `-wrong-side` shapes are
      ;; the opposite question: they get a value that takes the branch which
      ;; proves nothing, so the check there must fire. Counting executed checks
      ;; cannot see a check in a branch nobody enters, and the first version of
      ;; this file reported those two as narrowed for exactly that reason.
      (if (flint.rt/str-index-of s "wrong-side")
        (try (pr-str (run1 s "not an int"))
             (catch Exception e (str "threw: " (ex-message e))))
        (pr-str (run1 s 41))))))
