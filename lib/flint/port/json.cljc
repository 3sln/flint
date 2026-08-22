(ns flint.port.json
  "The JSON codec for a host port.

      (:require [flint.port :as p] [flint.port.json :as json])
      (p/open \"thing\" {:codec json/codec})

  **JSON cannot represent EDN.** Keywords, symbols, sets and non-string map keys
  have no JSON form, and converting them quietly is how `:a` comes back `\"a\"`
  and a set comes back an array. So this codec is **strict**: a value JSON
  cannot carry is an error at the send, naming the value.

  Where the convenience really is wanted, ask for it explicitly, the way
  `clojure.data.json` makes `:key-fn` the caller's decision:

      (p/open \"thing\" {:codec json/codec :key-fn name})"
  (:require [flint.data.json :as json]))

(defn- pairs [opts ks] (mapcat identity (select-keys opts ks)))

(defn encode [v opts]
  (apply json/write-str v :strict true
         (pairs opts [:key-fn :value-fn :escape-unicode :escape-slash :indent])))

(defn decode [s opts]
  (apply json/read-str s (pairs opts [:key-fn :value-fn])))

(def codec {:format :json :encode encode :decode decode})
