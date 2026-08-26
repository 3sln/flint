(ns proj-noparam
  "A declaration that would do nothing. The compiler must say so.")

(defn ^{:result-projected-meta {true {zz {:tag int}}}} p [v] (int? v))

(defn main [_] (pr-str (p 1)))
