(ns inline-bad
  "An `:inline` that is not a function. The author gets told; they do not get a
  call that is merely slower than they think it is.")

(defn ^{:inline 42} nope [x] x)

(defn main [_] (pr-str (nope 1)))
