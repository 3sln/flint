(ns proj-key
  "A declaration that would do nothing. The compiler must say so.")

(defn ^{:result-projected-meta {:maybe {v {:tag int}}}} p [v] (int? v))

(defn main [_] (pr-str (p 1)))
