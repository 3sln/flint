(ns lintme
  "Three things written for flint that flint does not read. None of them is an
  error -- metadata is an open map -- and all three are the shape that would
  otherwise never be noticed, because the program stays correct.")

;; The bare spelling. flint reads the namespaced one.
(defn ^{:result-inverts v} a [v] (not v))

;; A typo inside flint's own namespace, which cannot belong to anyone else.
(defn ^{:flint/result-invert v} b [v] (not v))

;; A type hint flint does not know: carried, and never checked.
(defn c [^Widget y] y)

;; And the same on a let binding, which is where it matters most -- the author
;; believes this is enforced.
(defn d [y] (let [^Gadget g y] g))

;; These are correct and must NOT be reported.
(defn ^{:flint/result-inverts v} ok1 [v] (not v))
(defn ok2 [^int n] (flint.rt/add n 1))

(defn main [_] (pr-str [(a 1) (b 1) (c 1) (d 1) (ok1 1) (ok2 1)]))
