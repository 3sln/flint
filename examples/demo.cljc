(ns demo)

(defn main [args]
  (str "hello, " (if (seq args) (first args) "world")))
