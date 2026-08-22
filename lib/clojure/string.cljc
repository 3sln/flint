(ns clojure.string
  "clojure.string, in cljc.

  Indices are **code points**, not UTF-16 code units, which is the same
  divergence as `count` on a string. The regex-taking arities of `replace` and
  `split` live here too but are only present when `flint.regex` is reachable --
  they call it, so a program that never uses them does not carry a regex engine."
  (:refer-clojure :exclude [replace reverse]))

(defn blank? [s]
  (if (nil? s)
    true
    (loop [i 0]
      (if (< i (count s))
        (let [c (nth s i)]
          (if (or (= c " ") (= c "\t") (= c "\n") (= c "\r"))
            (recur (inc i))
            false))
        true))))

(defn starts-with? [s prefix]
  (let [n (count prefix)]
    (if (> n (count s)) false (= prefix (subs s 0 n)))))

(defn ends-with? [s suffix]
  (let [n (count suffix) m (count s)]
    (if (> n m) false (= suffix (subs s (- m n))))))

(defn index-of
  ([s value] (flint.rt/str-index-of s value 0))
  ([s value from] (flint.rt/str-index-of s value from)))

(defn last-index-of
  ([s value]
   (loop [best nil from 0]
     (let [i (flint.rt/str-index-of s value from)]
       (if (nil? i) best (recur i (inc i))))))
  ([s value from]
   (loop [best nil at 0]
     (let [i (flint.rt/str-index-of s value at)]
       (if (or (nil? i) (> i from)) best (recur i (inc i)))))))

(defn includes? [s substr] (some? (flint.rt/str-index-of s substr 0)))

(defn join
  ([coll] (flint.rt/str-join (map str coll)))
  ([sep coll]
   (loop [acc [] s (seq coll) first? true]
     (if s
       (recur (if first? (conj acc (str (first s))) (conj acc (str sep) (str (first s))))
              (next s) false)
       (flint.rt/str-join acc)))))

(defn split
  "Splits on a literal string separator. The regex arity is in `flint.regex`."
  ([s sep]
   (loop [acc [] from 0]
     (let [i (flint.rt/str-index-of s sep from)]
       (if (nil? i)
         (conj acc (subs s from))
         (recur (conj acc (subs s from i)) (+ i (count sep)))))))
  ([s sep limit]
   (loop [acc [] from 0 n 1]
     (if (>= n limit)
       (conj acc (subs s from))
       (let [i (flint.rt/str-index-of s sep from)]
         (if (nil? i)
           (conj acc (subs s from))
           (recur (conj acc (subs s from i)) (+ i (count sep)) (inc n))))))))

(defn split-lines [s] (split s "\n"))

(defn- ws? [c] (or (= c " ") (= c "\t") (= c "\n") (= c "\r")))

(defn triml [s]
  (loop [i 0] (if (and (< i (count s)) (ws? (nth s i))) (recur (inc i)) (subs s i))))

(defn trimr [s]
  (loop [i (count s)] (if (and (> i 0) (ws? (nth s (dec i)))) (recur (dec i)) (subs s 0 i))))

(defn trim [s] (triml (trimr s)))

(defn trim-newline [s]
  (loop [i (count s)]
    (if (and (> i 0) (let [c (nth s (dec i))] (or (= c "\n") (= c "\r"))))
      (recur (dec i))
      (subs s 0 i))))

(defn replace-first
  "Replaces the first occurrence of the literal `match`."
  [s match replacement]
  (let [i (flint.rt/str-index-of s match 0)]
    (if (nil? i)
      s
      (flint.rt/str-join [(subs s 0 i) replacement (subs s (+ i (count match)))]))))

(defn replace
  "Replaces every occurrence of the literal `match`."
  [s match replacement]
  (if (= "" match)
    s
    (loop [acc [] from 0]
      (let [i (flint.rt/str-index-of s match from)]
        (if (nil? i)
          (flint.rt/str-join (conj acc (subs s from)))
          (recur (conj acc (subs s from i) replacement) (+ i (count match))))))))

(defn upper-case [s]
  (flint.rt/str-join
   (loop [acc [] i 0]
     (if (< i (count s))
       (let [c (nth s i) cp (flint.rt/code-point-at c 0)]
         (recur (conj acc (if (and (>= cp 97) (<= cp 122)) (flint.rt/from-code-point (- cp 32)) c))
                (inc i)))
       acc))))

(defn lower-case [s]
  (flint.rt/str-join
   (loop [acc [] i 0]
     (if (< i (count s))
       (let [c (nth s i) cp (flint.rt/code-point-at c 0)]
         (recur (conj acc (if (and (>= cp 65) (<= cp 90)) (flint.rt/from-code-point (+ cp 32)) c))
                (inc i)))
       acc))))

(defn capitalize [s]
  (if (= 0 (count s))
    s
    (flint.rt/str2 (upper-case (subs s 0 1)) (lower-case (subs s 1)))))

(defn reverse [s]
  (flint.rt/str-join (loop [acc [] i (count s)]
                       (if (> i 0) (recur (conj acc (nth s (dec i))) (dec i)) acc))))

(defn escape [s cmap]
  (flint.rt/str-join
   (loop [acc [] i 0]
     (if (< i (count s))
       (let [c (nth s i) r (get cmap c)]
         (recur (conj acc (if (nil? r) c (str r))) (inc i)))
       acc))))
