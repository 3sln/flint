(ns clojure.edn
  "An EDN reader with reader-tag support.

  Written fresh rather than reusing the compiler's reader: this one has no
  syntax quote, no reader conditionals, no anonymous-fn literals and no metadata
  on forms, so a program that reads EDN does not drag the compiler's reader in
  behind it.

  `#inst` and `#uuid` have **no** built-in reader, because flint has no date or
  UUID type. An unknown tag calls `:default` if you gave one, and otherwise
  throws -- the same behaviour Clojure has for a tag with no registered reader.
  Pass `:readers {'inst my-fn}` to handle them yourself.")

(def ^:private whitespace #{" " "\t" "\n" "\r" "\f" ","})
(def ^:private delimiters #{"(" ")" "[" "]" "{" "}" "\"" ";" "\\"})

(defn- err [msg data] (throw (ex-info (str "edn: " msg) (assoc data :type :edn))))

(defn- st [s] (volatile! {:s s :i 0 :n (count s)}))
(defn- pk [v] (let [m @v] (when (< (:i m) (:n m)) (flint.rt/nth (:s m) (:i m)))))
(defn- nx! [v] (let [m @v] (when (< (:i m) (:n m))
                             (let [c (flint.rt/nth (:s m) (:i m))]
                               (vswap! v assoc :i (inc (:i m)))
                               c))))

(defn- skip! [v]
  (loop []
    (let [c (pk v)]
      (cond
        (nil? c) nil
        (whitespace c) (do (nx! v) (recur))
        (= c ";") (do (loop [] (let [d (nx! v)] (when (and d (not= d "\n")) (recur)))) (recur))
        :else nil))))

(defn- token [v]
  (loop [acc []]
    (let [c (pk v)]
      (if (or (nil? c) (whitespace c) (delimiters c))
        (flint.rt/str-join acc)
        (do (nx! v) (recur (conj acc c)))))))

(defn- digit? [c] (and (some? c) (<= 48 (flint.rt/code-point-at c 0) 57)))

(defn- read-num [t]
  (let [v (flint.rt/str->num t)]
    (if (nil? v) (err "not a number" {:token t}) v)))

(defn- read-escape [v]
  (let [c (nx! v)]
    (cond
      (= c "n") "\n" (= c "t") "\t" (= c "r") "\r"
      (= c "\\") "\\" (= c "\"") "\"" (= c "b") "\b" (= c "f") "\f"
      (= c "u") (let [h (flint.rt/str-join [(nx! v) (nx! v) (nx! v) (nx! v)])]
                  (flint.rt/from-code-point
                   (loop [i 0 acc 0]
                     (if (>= i 4)
                       acc
                       (let [d (flint.rt/code-point-at h i)
                             n (cond (and (>= d 48) (<= d 57)) (- d 48)
                                     (and (>= d 97) (<= d 102)) (- d 87)
                                     (and (>= d 65) (<= d 70)) (- d 55)
                                     :else (err "bad unicode escape" {:seq h}))]
                         (recur (inc i) (+ (* acc 16) n)))))))
      :else (err "unsupported escape" {:char c}))))

(defn- read-str [v]
  (nx! v)
  (loop [acc []]
    (let [c (nx! v)]
      (cond
        (nil? c) (err "unterminated string" {})
        (= c "\"") (flint.rt/str-join acc)
        (= c "\\") (recur (conj acc (read-escape v)))
        :else (recur (conj acc c))))))

(def ^:private named-chars
  {"newline" "\n" "space" " " "tab" "\t" "return" "\r" "formfeed" "\f" "backspace" "\b"})

(defn- read-char* [v]
  (nx! v)
  (let [c (nx! v)
        rest-tok (if (or (nil? c) (whitespace c) (delimiters c)) "" (token v))
        t (str c rest-tok)]
    (cond
      (= 1 (count t)) t
      (named-chars t) (named-chars t)
      :else (err "unknown character literal" {:token t}))))

(declare read-form)

(defn- read-delim [v closer opts]
  (nx! v)
  (loop [acc []]
    (skip! v)
    (let [c (pk v)]
      (cond
        (nil? c) (err "unterminated collection" {:expecting closer})
        (= c closer) (do (nx! v) acc)
        :else (let [x (read-form v opts)]
                (if (identical? x ::skip) (recur acc) (recur (conj acc x))))))))

(defn- read-symbolic [v opts]
  (let [t (token v)]
    (cond
      (= t "") (err "unexpected end of input" {})
      (= t "nil") nil
      (= t "true") true
      (= t "false") false
      (= t "/") '/
      (or (digit? (flint.rt/nth t 0))
          (and (> (count t) 1)
               (or (= "-" (flint.rt/nth t 0)) (= "+" (flint.rt/nth t 0)))
               (digit? (flint.rt/nth t 1))))
      (read-num t)
      (= "::" (if (> (count t) 1) (subs t 0 2) ""))
      (err "auto-resolved keywords are not valid EDN" {:token t})
      (= ":" (flint.rt/nth t 0))
      (let [n (subs t 1) i (flint.rt/str-index-of n "/" 0)]
        (if (and i (> i 0)) (keyword (subs n 0 i) (subs n (inc i))) (keyword n)))
      :else
      (let [i (flint.rt/str-index-of t "/" 0)]
        (if (and i (> i 0) (< i (dec (count t))))
          (symbol (subs t 0 i) (subs t (inc i)))
          (symbol t))))))

(defn- apply-tag [tag value opts]
  (let [readers (get opts :readers {})
        f (get readers tag)]
    (cond
      f (f value)
      (contains? opts :default) ((get opts :default) tag value)
      :else (err "no reader for tag" {:tag tag}))))

(defn- qualify
  "Give every unqualified keyword or symbol key the map's namespace, which is
  what `#:ns{...}` means. A key that already carries one keeps it."
  [ns m]
  (reduce (fn [acc e]
            (let [k (key e)]
              (assoc acc
                     (cond
                       (and (keyword? k) (nil? (namespace k))) (keyword ns (name k))
                       (and (symbol? k) (nil? (namespace k))) (symbol ns (name k))
                       :else k)
                     (val e))))
          {} m))

(defn- read-dispatch [v opts]
  (nx! v)
  (let [c (pk v)]
    (cond
      (= c "{") (set (read-delim v "}" opts))
      (= c "_") (do (nx! v) (read-form v opts) ::skip)
      (nil? c) (err "unexpected end of input after #" {})
      ;; `#:ns{...}` -- a namespaced map. Standard EDN since Clojure 1.9, and
      ;; the form `pr-str` produces for ANY map with qualified keys, so a
      ;; `deps.edn` written by a Clojure tool round-trips into it. Reading one
      ;; back failed with "reader tag must be a symbol", which is true and
      ;; unhelpful.
      (= c ":")
      (let [kw (read-form v opts)
            _ (skip! v)
            m (read-form v opts)]
        (cond
          (not (keyword? kw)) (err "expected a namespace after #:" {:got kw})
          (namespace kw) (err "#::alias{...} needs an alias resolver, which EDN has no notion of"
                              {:got kw})
          (not (map? m)) (err "#:ns must be followed by a map" {:got m})
          :else (qualify (name kw) m)))
      :else (let [tag (read-form v opts)
                  _ (skip! v)
                  value (read-form v opts)]
              (if (symbol? tag)
                (apply-tag tag value opts)
                (err "reader tag must be a symbol" {:tag tag}))))))

(defn- read-form [v opts]
  (skip! v)
  (let [c (pk v)]
    (cond
      (nil? c) ::eof
      (= c "(") (apply list (read-delim v ")" opts))
      (= c "[") (read-delim v "]" opts)
      (= c "{") (let [kvs (read-delim v "}" opts)]
                  (when (odd? (count kvs)) (err "map needs an even number of forms" {}))
                  (apply hash-map kvs))
      (= c "\"") (read-str v)
      (= c "\\") (read-char* v)
      (= c "#") (read-dispatch v opts)
      (or (= c ")") (= c "]") (= c "}")) (err "unexpected delimiter" {:char c})
      :else (read-symbolic v opts))))

(defn read-string
  "Read one EDN value from `s`. Options: `:readers` (a map of tag symbol to
  function), `:default` (called with tag and value for unknown tags), and
  `:eof` (returned at end of input instead of nil)."
  ([s] (read-string {} s))
  ([opts s]
   (if (or (nil? s) (= "" s))
     (get opts :eof)
     (let [v (st s)
           r (loop [] (let [x (read-form v opts)] (if (identical? x ::skip) (recur) x)))]
       (if (identical? r ::eof) (get opts :eof) r)))))

(defn read-all
  "Every EDN value in `s`, as a vector. Not in clojure.edn; flint adds it because
  there is no stream type here to read from repeatedly."
  ([s] (read-all {} s))
  ([opts s]
   (let [v (st s)]
     (loop [acc []]
       (let [x (read-form v opts)]
         (cond
           (identical? x ::eof) acc
           (identical? x ::skip) (recur acc)
           :else (recur (conj acc x))))))))
