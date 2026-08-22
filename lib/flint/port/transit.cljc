(ns flint.port.transit
  "The binary codec for a host port: **Transit over msgpack**.

      (:require [flint.port :as p] [flint.port.transit :as transit])
      (p/open \"thing\" {:codec transit/codec})

  Transit rather than a fourth format of our own, for the reason
  `doc/decisions/0006` gives: it exists for exactly this, it is self-describing,
  and it already has the extension mechanism tagged values need. Inventing one
  would be a week that bought little.

  ## What it carries

  Everything EDN does, which is the point of choosing it: nil, booleans,
  integers, floats, strings, keywords, symbols, lists, vectors, maps (including
  non-string keys), sets, and tagged values.

  ## What this implementation leaves out, deliberately

  * **The caching mechanism.** Transit lets a writer replace a repeated key with
    a short back-reference. It is an optimisation, not part of the data model,
    and a reader that ignores it still reads correct data — so this writer never
    emits cache codes and the reader accepts them only by not needing to.
    Messages are therefore larger than a caching writer's would be. Measured
    rather than assumed: see the README.
  * **`bigint`/`bigdec`/`uri`/`uuid`/`instant` ground types**, which flint has no
    values for. They read as tagged values (`#tag \"rep\"`) rather than failing,
    so nothing is silently lost.

  ## Encoding, in one paragraph

  msgpack carries the shape; Transit's string tagging carries the types msgpack
  has no room for. A string beginning `~` is escaped: `~:kw` is a keyword,
  `~$sym` a symbol, `~i123` an integer that would not fit, `~~` a literal `~`.
  A composite that is not a plain map or array is an array whose first element
  is `~#tag`: `[\"~#set\" [...]]`, `[\"~#list\" [...]]`, `[\"~#cmap\" [k v ...]]`
  for a map with non-string keys."
  (:require [clojure.string :as str]))

;; ------------------------------------------------------------------ msgpack

(defn- u8 [acc b] (conj acc (bit-and b 0xff)))

(defn- be [acc n width]
  (loop [acc acc i (dec width)]
    (if (< i 0) acc (recur (u8 acc (bit-shift-right n (* 8 i))) (dec i)))))

(defn- pack-int [acc n]
  (cond
    (and (>= n 0) (< n 128)) (u8 acc n)
    (and (< n 0) (>= n -32)) (u8 acc (+ 256 n))
    (and (>= n 0) (< n 256)) (be (u8 acc 0xcc) n 1)
    (and (>= n 0) (< n 65536)) (be (u8 acc 0xcd) n 2)
    (and (>= n 0) (< n 4294967296)) (be (u8 acc 0xce) n 4)
    (>= n 0) (be (u8 acc 0xcf) n 8)
    (>= n -128) (be (u8 acc 0xd0) (+ 256 n) 1)
    (>= n -32768) (be (u8 acc 0xd1) (+ 65536 n) 2)
    (>= n -2147483648) (be (u8 acc 0xd2) (+ 4294967296 n) 4)
    :else (be (u8 acc 0xd3) n 8)))

(defn- pack-str [acc s]
  (let [bs (vec (flint.rt/str-bytes s))
        n (count bs)
        acc (cond
              (< n 32) (u8 acc (+ 0xa0 n))
              (< n 256) (be (u8 acc 0xd9) n 1)
              (< n 65536) (be (u8 acc 0xda) n 2)
              :else (be (u8 acc 0xdb) n 4))]
    (into acc bs)))

(defn- pack-array-header [acc n]
  (cond
    (< n 16) (u8 acc (+ 0x90 n))
    (< n 65536) (be (u8 acc 0xdc) n 2)
    :else (be (u8 acc 0xdd) n 4)))

(defn- pack-map-header [acc n]
  (cond
    (< n 16) (u8 acc (+ 0x80 n))
    (< n 65536) (be (u8 acc 0xde) n 2)
    :else (be (u8 acc 0xdf) n 4)))

(defn- pack-double [acc d]
  ;; 0xcb + IEEE-754 big-endian, straight from the value's own bits.
  (let [b (flint.rt/double-bits d)]
    (loop [acc (u8 acc 0xcb) i 7]
      (if (< i 0) acc (recur (u8 acc (bit-shift-right b (* 8 i))) (dec i))))))

;; ------------------------------------------------------------------ writing

(declare write-any)

(defn- esc
  "Transit escaping: a string that would otherwise look like a tag."
  [s]
  (if (and (> (count s) 0) (= "~" (subs s 0 1))) (str "~" s) s))

(defn- tag-str [x]
  (cond
    (keyword? x) (str "~:" (if (namespace x) (str (namespace x) "/" (name x)) (name x)))
    (symbol? x) (str "~$" (str x))
    :else nil))

(defn- string-key? [k] (or (string? k) (keyword? k) (symbol? k)))

(defn- write-map [acc m]
  (if (every? string-key? (keys m))
    ;; The key is packed directly: it is already in its tagged form, and running
    ;; it back through `write-any` would escape the tag it just grew.
    (reduce-kv (fn [a k v] (write-any (pack-str a (or (tag-str k) (esc k))) v))
               (pack-map-header acc (count m)) m)
    ;; A map whose keys are not strings has no msgpack-map form: Transit calls
    ;; that a cmap and writes it as a flat array of alternating keys and values.
    (let [a (pack-array-header acc 2)
          a (write-any a "~#cmap")
          a (pack-array-header a (* 2 (count m)))]
      (reduce-kv (fn [a k v] (write-any (write-any a k) v)) a m))))

(defn- write-any [acc x]
  (cond
    (nil? x) (u8 acc 0xc0)
    (true? x) (u8 acc 0xc3)
    (false? x) (u8 acc 0xc2)
    (string? x) (pack-str acc (esc x))
    (integer? x) (pack-int acc x)
    (number? x) (pack-double acc x)
    (or (keyword? x) (symbol? x)) (pack-str acc (tag-str x))
    (map? x) (write-map acc x)
    (set? x) (let [a (pack-array-header acc 2)
                   a (write-any a "~#set")]
               (reduce write-any (pack-array-header a (count x)) x))
    (vector? x) (reduce write-any (pack-array-header acc (count x)) x)
    (sequential? x) (let [items (vec x)
                          a (pack-array-header acc 2)
                          a (write-any a "~#list")]
                      (reduce write-any (pack-array-header a (count items)) items))
    :else (throw (ex-info (str "transit cannot represent " (pr-str x)) {:value x}))))

;; ------------------------------------------------------------------ reading

(declare read-at)

(defn- be-read [bs i width]
  (loop [k 0 n 0] (if (= k width) n (recur (inc k) (+ (* n 256) (nth bs (+ i k)))))))

(defn- signed [n bits]
  (let [half (bit-shift-left 1 (dec bits))]
    (if (>= n half) (- n (bit-shift-left 1 bits)) n)))

(defn- read-str [bs i n]
  [(flint.rt/bytes->str (subvec bs i (+ i n))) (+ i n)])

(defn- untag
  "Turn a Transit-tagged string back into the value it stands for."
  [s]
  (if (and (> (count s) 1) (= "~" (subs s 0 1)))
    (let [c (subs s 1 2) rest (subs s 2)]
      (cond
        (= c ":") (let [i (str/index-of rest "/")]
                    (if i (keyword (subs rest 0 i) (subs rest (inc i))) (keyword rest)))
        (= c "$") (let [i (str/index-of rest "/")]
                    (if i (symbol (subs rest 0 i) (subs rest (inc i))) (symbol rest)))
        (= c "~") (subs s 1)
        (= c "i") (flint.rt/str->num rest)
        (= c "d") (flint.rt/str->num rest)
        :else s))
    s))

(defn- read-array [bs i n]
  (loop [k 0 i i acc []]
    (if (= k n) [acc i]
        (let [[v i'] (read-at bs i)] (recur (inc k) i' (conj acc v))))))

(defn- read-map-entries [bs i n]
  (loop [k 0 i i acc {}]
    (if (= k n) [acc i]
        (let [[kk i1] (read-at bs i)
              [vv i2] (read-at bs i1)]
          (recur (inc k) i2 (assoc acc kk vv))))))

(defn- composite
  "An array whose first element is a `~#tag` is a tagged composite."
  [items]
  (let [t (first items)]
    (cond
      (= t "~#set") (set (second items))
      (= t "~#list") (apply list (second items))
      (= t "~#cmap") (apply hash-map (second items))
      :else (if (and (string? t) (str/starts-with? t "~#"))
              {:flint/tag (subs t 2) :value (second items)}
              items))))

(defn- read-at [bs i]
  (let [b (nth bs i)]
    (cond
      (= b 0xc0) [nil (inc i)]
      (= b 0xc2) [false (inc i)]
      (= b 0xc3) [true (inc i)]
      (< b 0x80) [b (inc i)]
      (>= b 0xe0) [(- b 256) (inc i)]
      (and (>= b 0xa0) (< b 0xc0)) (let [[s i'] (read-str bs (inc i) (- b 0xa0))] [(untag s) i'])
      (= b 0xd9) (let [n (nth bs (inc i)) [s i'] (read-str bs (+ i 2) n)] [(untag s) i'])
      (= b 0xda) (let [n (be-read bs (inc i) 2) [s i'] (read-str bs (+ i 3) n)] [(untag s) i'])
      (= b 0xdb) (let [n (be-read bs (inc i) 4) [s i'] (read-str bs (+ i 5) n)] [(untag s) i'])
      (= b 0xcc) [(be-read bs (inc i) 1) (+ i 2)]
      (= b 0xcd) [(be-read bs (inc i) 2) (+ i 3)]
      (= b 0xce) [(be-read bs (inc i) 4) (+ i 5)]
      (= b 0xcf) [(be-read bs (inc i) 8) (+ i 9)]
      (= b 0xd0) [(signed (be-read bs (inc i) 1) 8) (+ i 2)]
      (= b 0xd1) [(signed (be-read bs (inc i) 2) 16) (+ i 3)]
      (= b 0xd2) [(signed (be-read bs (inc i) 4) 32) (+ i 5)]
      (= b 0xd3) [(signed (be-read bs (inc i) 8) 64) (+ i 9)]
      (= b 0xcb) [(flint.rt/bits->double (be-read bs (inc i) 8)) (+ i 9)]
      (and (>= b 0x90) (< b 0xa0))
      (let [[items i'] (read-array bs (inc i) (- b 0x90))]
        [(if (and (= 2 (count items)) (string? (first items))
                  (str/starts-with? (first items) "~#"))
           (composite items) items) i'])
      (= b 0xdc) (let [n (be-read bs (inc i) 2) [items i'] (read-array bs (+ i 3) n)]
                   [(if (and (= 2 (count items)) (string? (first items))
                             (str/starts-with? (first items) "~#"))
                      (composite items) items) i'])
      (= b 0xdd) (let [n (be-read bs (inc i) 4) [items i'] (read-array bs (+ i 5) n)]
                   [(if (and (= 2 (count items)) (string? (first items))
                             (str/starts-with? (first items) "~#"))
                      (composite items) items) i'])
      (and (>= b 0x80) (< b 0x90)) (read-map-entries bs (inc i) (- b 0x80))
      (= b 0xde) (read-map-entries bs (+ i 3) (be-read bs (inc i) 2))
      (= b 0xdf) (read-map-entries bs (+ i 5) (be-read bs (inc i) 4))
      :else (throw (ex-info (str "unsupported msgpack byte 0x" (flint.rt/num->str b)) {:byte b})))))

;; ------------------------------------------------------------------- codec

(defn encode [v _opts] (write-any [] v))
(defn decode [bs _opts] (first (read-at (vec bs) 0)))

(def codec {:format :transit+msgpack :binary true :encode encode :decode decode})
