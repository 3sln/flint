(ns clojure.string
  "clojure.string, in cljc.

  Indices are **code points**, not UTF-16 code units, which is the same
  divergence as `count` on a string.

  `split`, `replace` and `replace-first` take a string or a pattern, and this
  namespace **does not name `flint.regex`**. It cannot: reachability is per var,
  so a static call to `flint.regex/split` inside `split` would be live for every
  program that splits on a comma, and would drag ~27 KB of regex engine in with
  it. Instead `flint.regex` registers its operations here when it is part of the
  build (see `regex-ops`), and a pattern is recognised structurally. So
  `(str/split s \",\")` carries no engine, and `flint.regex` can be named in
  `:exclude` to prove it."
  (:refer-clojure :exclude [replace reverse]))

(def ^:private regex-ops
  "Set by `flint.regex` at load time; nil in a build that has no regex engine.
  You can only obtain a pattern from `re-pattern` or a `#\"...\"` literal, both
  of which reach `flint.regex/pattern`, which makes that namespace part of the
  build, which is what keeps this filled in exactly when it is needed."
  (atom nil))

(defn register-regex-ops! [m] (reset! regex-ops m))

(defn- pattern?
  "Structural, so that recognising a pattern does not reference the engine."
  [x]
  (and (map? x) (contains? x :flint/pattern)))

(defn- rop [k]
  (or (get @regex-ops k)
      (throw (ex-info "no regex engine in this build" {:op k}))))

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
  "Splits on a string separator or a pattern."
  ([s sep]
   (if (pattern? sep)
     ((rop :split) sep s)
     (split-literal s sep)))
  ([s sep limit]
   (if (pattern? sep)
     ((rop :split) sep s limit)
     (split-literal s sep limit))))

(defn split-literal
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

(defn split-lines [s] (split-literal s "\n"))

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
  "Replaces the first occurrence. `match` may be a string or a pattern."
  [s match replacement]
  (if (pattern? match)
    ((rop :replace-first) match s replacement)
    (let [i (flint.rt/str-index-of s match 0)]
      (if (nil? i)
        s
        (flint.rt/str-join [(subs s 0 i) replacement (subs s (+ i (count match)))])))))

(defn replace
  "Replaces every occurrence. `match` may be a string or a pattern; with a
  pattern, `replacement` may be a function of the match."
  [s match replacement]
  (cond
    (pattern? match) ((rop :replace-all) match s replacement)
    (= "" match) s
    :else
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

(defn re-quote-replacement [s] s)

(defn escape [s cmap]
  (flint.rt/str-join
   (loop [acc [] i 0]
     (if (< i (count s))
       (let [c (nth s i) r (get cmap c)]
         (recur (conj acc (if (nil? r) c (str r))) (inc i)))
       acc))))
