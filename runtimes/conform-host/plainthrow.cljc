(ns plainthrow
  "A probe: main throws. Is a failure from `main` reported the same three ways?")
(defn main [_] (throw (ex-info "boom" {})))
