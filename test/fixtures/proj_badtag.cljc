(ns proj-badtag
  "A declaration that would do nothing. The compiler must say so.")

(defn ^{:result-projected-meta {true {v {:tag Widget}}}} p [v] (int? v))

(defn main [_] (pr-str (p 1)))
