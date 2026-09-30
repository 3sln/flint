(ns ^{:corpus/kind :strings
      :corpus/source "LZW compression and decompression, round-tripped"}
  lzw
  (:require [clojure.string :as str]))

;; Dictionary keys are strings, so this is string building, string hashing and
;; map growth. Initial dictionary is the characters of the input, sorted, so
;; nothing depends on a character's code point.

(defn compress [s alphabet]
  (let [dict0 (zipmap (map str alphabet) (range))]
    (loop [dict dict0 w "" [c & more :as cs] (map str s) out []]
      (if (empty? cs)
        (if (= w "") out (conj out (dict w)))
        (let [wc (str w c)]
          (if (contains? dict wc)
            (recur dict wc more out)
            (recur (assoc dict wc (count dict)) c more (conj out (dict w)))))))))

(defn decompress [codes alphabet]
  (let [dict0 (zipmap (range) (map str alphabet))
        w0 (dict0 (first codes))]
    (loop [dict dict0 w w0 [k & more :as ks] (rest codes) out [w0]]
      (if (empty? ks)
        (apply str out)
        (let [entry (or (dict k) (str w (subs w 0 1)))]
          (recur (assoc dict (count dict) (str w (subs entry 0 1)))
                 entry more (conj out entry)))))))

(def text
  (str/join " " (repeat 60 "TOBEORNOTTOBEORTOBEORNOT to be or not to be that is the question")))

(defn main [_]
  (let [alphabet (sort (distinct (map str text)))
        codes (compress text alphabet)]
    (str (count text) " " (count codes) " " (reduce + codes) " "
         (= text (decompress codes alphabet)))))
