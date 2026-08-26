(ns proj-empty
  "A declaration that would do nothing. The compiler must say so.")

(defn ^{:flint/result-projected-meta {}} p [v] (int? v))

(defn main [_] (pr-str (p 1)))
