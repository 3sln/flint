(ns demo.checks
  "Nothing requires this namespace, which is the point: `flint test` collects
  from every file on the path rather than from the entry outwards, and a runner
  that silently ran a subset and reported the subset as the total would pass
  this file by."
  (:require [demo.util :as util]))

(defn ^:flint.check/test sum-adds-up []
  (when (not= 15 (util/sum [1 2 3 4 5]))
    (throw (ex-info "sum is wrong" {:got (util/sum [1 2 3 4 5])}))))

(defn ^:flint.check/test greeting-is-capitalised []
  (when (not= "hello, World" (util/greet "world"))
    (throw (ex-info "greet is wrong" {:got (util/greet "world")}))))
