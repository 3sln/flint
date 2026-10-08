(ns inline-loop
  "An `:inline` that re-emits its own name. This is the easy mistake -- it looks
  like a fallback and it expands forever -- and the compiler must name it rather
  than dying with a host stack overflow.")

(defn ^{:inline (fn [x] `(spin ~x))} spin [x] x)

(defn main [_] (pr-str (spin 1)))
