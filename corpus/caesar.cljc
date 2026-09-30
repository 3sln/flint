(ns ^{:corpus/kind :strings
      :corpus/diverges {:flint "chars are one-character strings; (int \\a) is not a number (README.md, \"Chars are not a type\")"}
      :corpus/source "Caesar cipher by character arithmetic, the way it is usually written"}
  caesar
  (:require [clojure.string :as str]))

;; Written the JVM way on purpose: `(int c)` and `(char n)`. flint has no char
;; type, so this is a DECLARED divergence -- the corpus keeps a program that
;; real code looks like and says why flint does not run it, rather than quietly
;; rewriting it into the portable form below.

(defn shift [n c]
  (cond
    (Character/isUpperCase c) (char (+ 65 (mod (+ (- (int c) 65) n) 26)))
    (Character/isLowerCase c) (char (+ 97 (mod (+ (- (int c) 97) n) 26)))
    :else c))

(defn encode [n s] (apply str (map #(shift n %) s)))

(defn main [_]
  (let [msg "The Quick Brown Fox Jumps Over The Lazy Dog"
        enc (encode 13 msg)]
    (str enc " " (= msg (encode 13 enc)))))
