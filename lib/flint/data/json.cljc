(ns flint.data.json
  "JSON, ours -- not `clojure.data.json`, but shaped so a Clojure programmer can
  guess it. `read-str` and `write-str` with a `:key-fn` are what people reach
  for, and there is no prize for renaming them.

  ## Numbers
  JSON does not distinguish integers from decimals, so we choose: a number with
  **no fraction and no exponent** reads as a long, anything else as a double.
  `1` is `1`, `1.0` and `1e3` are doubles.

  ## Options
  `read-str`  : `:key-fn` (applied to every key, e.g. `keyword`),
                `:value-fn` (called with key and value, may return the value or
                `:flint.data.json/omit` to drop the entry).
  `write-str` : `:key-fn`, `:value-fn`, `:escape-unicode` (default false),
                `:escape-slash` (default false), `:indent` (a string, or nil)."
  (:require [clojure.string :as str]))

(def omit ::omit)

(defn- transform [x key-fn value-fn]
  (cond
    (map? x)
    (persistent!
     (reduce-kv (fn [acc k v]
                  (let [k' (if key-fn (key-fn k) k)
                        v' (transform v key-fn value-fn)
                        v' (if value-fn (value-fn k' v') v')]
                    (if (= v' omit) acc (assoc! acc k' v'))))
                (transient {}) x))
    (vector? x) (mapv (fn [e] (transform e key-fn value-fn)) x)
    :else x))

(defn read-str
  "Parse a JSON string. Options as keyword arguments, as in clojure.data.json:
  `(read-str s :key-fn keyword)`."
  [s & opts]
  (let [o (apply hash-map opts)
        raw (flint.rt/json-parse s)
        key-fn (:key-fn o)
        value-fn (:value-fn o)]
    (if (or key-fn value-fn) (transform raw key-fn value-fn) raw)))

;; --------------------------------------------------------------------- writing

(defn- hex4 [n]
  (let [digits "0123456789abcdef"]
    (flint.rt/str-join
     [(nth digits (bit-and (bit-shift-right n 12) 15))
      (nth digits (bit-and (bit-shift-right n 8) 15))
      (nth digits (bit-and (bit-shift-right n 4) 15))
      (nth digits (bit-and n 15))])))

(defn- escape [s escape-unicode? escape-slash?]
  (flint.rt/str-join
   (loop [acc ["\""] i 0]
     (if (>= i (count s))
       (conj acc "\"")
       (let [c (nth s i)
             cp (flint.rt/code-point-at c 0)]
         (recur (conj acc
                      (cond
                        (= c "\"") "\\\""
                        (= c "\\") "\\\\"
                        (= c "\n") "\\n"
                        (= c "\r") "\\r"
                        (= c "\t") "\\t"
                        (= c "\b") "\\b"
                        (= c "\f") "\\f"
                        (and escape-slash? (= c "/")) "\\/"
                        (< cp 32) (str "\\u" (hex4 cp))
                        (and escape-unicode? (> cp 126))
                        (if (> cp 0xFFFF)
                          ;; Outside the BMP, JSON escapes are a surrogate pair.
                          (let [v (- cp 0x10000)]
                            (str "\\u" (hex4 (+ 0xD800 (bit-shift-right v 10)))
                                 "\\u" (hex4 (+ 0xDC00 (bit-and v 0x3FF)))))
                          (str "\\u" (hex4 cp)))
                        :else c))
                (inc i)))))))

(defn- key->str [k]
  (cond
    (string? k) k
    (keyword? k) (if (namespace k) (str (namespace k) "/" (name k)) (name k))
    (symbol? k) (str k)
    (number? k) (flint.rt/num->str k)
    (nil? k) ""
    :else (str k)))

(declare write-value)

(defn- write-coll [open close items indent depth acc]
  (if (empty? items)
    (conj acc open close)
    (let [nl (if indent "\n" "")
          pad (fn [d] (if indent (apply str (repeat d indent)) ""))]
      (loop [acc (conj acc open nl) xs (seq items) first? true]
        (if xs
          (recur (conj (if first? acc (conj acc "," nl)) (pad (inc depth)) (first xs))
                 (next xs) false)
          (conj acc nl (pad depth) close))))))

(defn- write-value [x opts depth acc]
  (let [{:keys [key-fn value-fn escape-unicode escape-slash indent]} opts]
    (cond
      (nil? x) (conj acc "null")
      (true? x) (conj acc "true")
      (false? x) (conj acc "false")
      (string? x) (conj acc (escape x escape-unicode escape-slash))
      (number? x) (conj acc (flint.rt/num->str x))
      (or (keyword? x) (symbol? x)) (conj acc (escape (key->str x) escape-unicode escape-slash))
      (map? x)
      (let [parts (reduce-kv
                   (fn [ps k v]
                     (let [k' (if key-fn (key-fn k) k)
                           v' (if value-fn (value-fn k' v) v)]
                       (if (= v' omit)
                         ps
                         (conj ps (flint.rt/str-join
                                   (write-value v' opts (inc depth)
                                                [(escape (key->str k') escape-unicode escape-slash)
                                                 (if indent ": " ":")]))))))
                   [] x)]
        (write-coll "{" "}" parts indent depth acc))
      (or (vector? x) (sequential? x) (set? x))
      (let [parts (mapv (fn [e] (flint.rt/str-join (write-value e opts (inc depth) []))) x)]
        (write-coll "[" "]" parts indent depth acc))
      :else (conj acc (escape (str x) escape-unicode escape-slash)))))

(defn write-str
  "Render `x` as JSON. `(write-str x :key-fn name :escape-unicode true)`."
  [x & opts]
  (let [o (apply hash-map opts)]
    (flint.rt/str-join (write-value x o 0 []))))
