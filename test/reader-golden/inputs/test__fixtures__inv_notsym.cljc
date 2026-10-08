(ns inv-notsym
  "A declaration that would do nothing. The compiler must say so.")

(defn ^{:flint/result-inverts 3} p [v] (not v))

(defn main [_] (pr-str (p 1)))
