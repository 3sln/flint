(ns demo.util
  (:require [clojure.string :as str]))

(defn greet [who] (str "hello, " (str/capitalize who)))

(defn sum [xs] (reduce + 0 xs))
