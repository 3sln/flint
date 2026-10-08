;; See `flint/system.cljc` beside this. `boot` is named so reachability keeps
;; it in the image -- the question is whether anything RUNS it.
(ns probe (:require [flint.system]))
(defn hi [] "hi")
(defn main [_] (str (count [hi flint.system/boot])))
