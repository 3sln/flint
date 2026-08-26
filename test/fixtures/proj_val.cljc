(ns proj-val
  "A declaration that would do nothing. The compiler must say so.")

(defn ^{:result-projected-meta {true {v [1 2]}}} p [v] (int? v))

(defn main [_] (pr-str (p 1)))
