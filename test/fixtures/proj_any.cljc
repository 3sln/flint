(ns proj-any
  "A projection of ^Object, which states nothing. The compiler must say so.")

(defn ^{:result-projected-meta {true {v {:tag Object}}}} p [v] (int? v))

(defn main [_] (pr-str (p 1)))
