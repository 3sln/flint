(ns flint.disasm
  "Bytecode disassembler. Written because 'it takes the wrong branch' is not a
  bug you can reason your way out of -- you have to look at the instructions."
  (:require [flint.emitter :as emit]
            [clojure.string :as str]))

(def ^:private by-code (into {} (map (fn [[k v]] [v k]) emit/op)))

(def ^:private operands
  {:const [:u16] :int [:i16] :local [:u8] :local-w [:u16] :set-local [:u8]
   :set-local-keep [:u8] :upval [:u8] :var [:u16] :set-var [:u16]
   :jump [:i16] :jump-if-false [:i16] :jump-if-true [:i16]
   :jump-if-false-keep [:i16] :jump-if-true-keep [:i16]
   :call [:u8] :tail-call [:u8] :closure [:u16 :u8] :native [:u16 :u8]
   :try [:i16] :vector [:u16] :map [:u16] :set [:u16] :list [:u16]
   :apply [:u8] :pop-n [:u8]})

(defn- u8 [code i] (nth code i))
(defn- u16 [code i] (+ (nth code i) (* 256 (nth code (inc i)))))
(defn- i16 [code i] (let [v (u16 code i)] (if (> v 32767) (- v 65536) v)))

(defn disasm
  "Render one code range as text."
  [code from len]
  (loop [i from out []]
    (if (>= i (+ from len))
      (str/join "\n" out)
      (let [op (u8 code i)
            nm (get by-code op :UNKNOWN)
            ops (get operands nm [])
            [args width]
            (loop [os ops at (inc i) acc [] w 1]
              (if (empty? os)
                [acc w]
                (case (first os)
                  :u8 (recur (rest os) (inc at) (conj acc (u8 code at)) (inc w))
                  :u16 (recur (rest os) (+ at 2) (conj acc (u16 code at)) (+ w 2))
                  :i16 (recur (rest os) (+ at 2) (conj acc (i16 code at)) (+ w 2)))))
            target (when (contains? #{:jump :jump-if-false :jump-if-true :try
                                      :jump-if-false-keep :jump-if-true-keep} nm)
                     (+ i width (first args)))]
        (recur (+ i width)
               (conj out (str (format "%5d" i) "  " (name nm)
                              (when (seq args) (str " " (str/join " " args)))
                              (when target (str "   -> " target)))))))))
