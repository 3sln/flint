#!/usr/bin/env flint
;; Reader fixtures for the kin reader's conformity guard
;; (`cli/src/kin_reader_test.rs`). Every construct `flint.reader` has a rule
;; for, read by the guest and by the kin reader and compared byte for byte.
(ns test.reader.tricky
  (:require [clojure.string :as str :refer [join]]
            [flint.check :as chk]
            #?(:flint [flint.rt :as frt])))

(def strings ["plain" "esc \t\r\n\\\"\b\f" "\u00e9\u0041" "\101\0\7" "é ü 中文 😀" ""])
(def chars [\a \newline \space \tab \return \formfeed \backspace \u00e9 \o101 \( \" \\ \😀])
(def numbers [0 1 -1 42 0xff 0X1F -0x10 1N 7M 1.5M 1.5 -2.75 1e3 1E-5 -0.0 0.0
              ##Inf ##-Inf ##NaN 140737488355327 140737488355328 -140737488355327
              -140737488355328 9223372036854775807 -9223372036854775808 +5 +1.5 1x 0xZZ 1/2])
(def names [:a :a/b ::local ::str/x :/x foo foo/bar / a/ x# %1 acc' nil true false])
(def colls [() [] {} #{} '(1 2) [1 [2 [3]]] {:a 1 "b" [2] 3 #{4}}
            #{:a :b :c :d :e :f :g :h :i :j} {1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18}])
(def quoted ['x '(a b) `x `(a ~b ~@c) `[a ~b] `{:k ~v b c} `#{a b} `(if .m & quote)
             ``a `(flint.reader/syntax-quoted q) `() `{a {b c}} `{1.5 x "s" y nil z}])
(def misc [@a #'b #_ignored c #"a\d+\"b" #(+ % %2) #(apply f %&) #(vector %1 [%3 {:k %2}] #{%4})])
(def nsmaps [#:p{:a 1 :b/c 2 d 3} #::{:a 1} #::str{:x 1}])
(def ^:private ^{:doc "doc" :a 1 :b 2 :c 3 :d 4 :e 5 :f 6} meta-heavy [1 ^:k [2] ^String s ^"T" t])
(def conds [#?(:clj 1 :flint 2) #?(:cljs 3) #?@(:flint [4 5] :default [6])
            #?@(:none [7]) {:k #?(:flint 8 :default 9)} #?(:flint/check :checked :default :unchecked)
            #{#?(:flint 10)} #?(:default 11)])
(defn f [x] #?(:flint (inc x) :clj (dec x)))
(def last-one {:file "x" :line -1 :column 0})
