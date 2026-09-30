(ns ^{:corpus/kind :strings
      :corpus/source "Caesar cipher by alphabet lookup: `caesar` in the portable form"}
  caesar-portable
  (:require [clojure.string :as str]))

;; What `caesar` becomes when it has to run everywhere: index into an alphabet
;; string instead of doing arithmetic on code points.

(def upper "ABCDEFGHIJKLMNOPQRSTUVWXYZ")
(def lower "abcdefghijklmnopqrstuvwxyz")

(defn shift [n c]
  (let [c (str c)
        u (str/index-of upper c)
        l (str/index-of lower c)]
    (cond u (subs upper (mod (+ u n) 26) (inc (mod (+ u n) 26)))
          l (subs lower (mod (+ l n) 26) (inc (mod (+ l n) 26)))
          :else c)))

(defn encode [n s] (apply str (map #(shift n %) s)))

(defn main [_]
  (let [msg "The Quick Brown Fox Jumps Over The Lazy Dog"
        enc (encode 13 msg)]
    (str enc " " (= msg (encode 13 enc)))))
