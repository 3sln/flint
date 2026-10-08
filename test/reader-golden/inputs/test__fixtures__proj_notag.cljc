(ns proj-notag
  "A declaration that would do nothing. The compiler must say so.")

(defn ^{:flint/result-projected-meta {true {v {:non-nil true}}}} p [v] (int? v))

(defn main [_] (pr-str (p 1)))
