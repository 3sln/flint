(ns demo.main
  "A project of more than one namespace, which is the point: a compile that
  never resolves a `:require` proves nothing about a CLI."
  (:require [demo.util :as util]
            [clojure.string :as str]))

(defn main [args]
  (str "args=" (pr-str args) "\n"
       "greet=" (util/greet "world") "\n"
       "upper=" (str/upper-case "abc") "\n"
       "sum=" (util/sum [1 2 3 4 5]) "\n"))
