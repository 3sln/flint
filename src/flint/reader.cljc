(ns flint.reader
  "The Clojure reader: source text to data.

  flint reads its own source rather than borrowing the host's reader. That is
  not duplicated work, it is the point: the compiler must read the same way on
  babashka and on flint, and it must control reader conditionals, syntax quote
  and metadata itself. Anything the host's reader would do differently would
  show up as a self-hosting divergence.

  Returns ordinary Clojure data, with `:line`/`:column`/`:file` metadata on
  every form that can carry it."
  (:require [clojure.string :as str]))

(defn- make-state [^String s file]
  (volatile! {:s s :i 0 :n (count s) :line 1 :col 1 :file file
              :gensyms nil :features #{:clj :flint} :ns nil :aliases {}}))

(defn- peek-ch [st]
  (let [{:keys [^String s i n]} @st]
    (when (< i n) (.charAt s i))))

(defn- peek2 [st]
  (let [{:keys [^String s i n]} @st]
    (when (< (inc i) n) (.charAt s (inc i)))))

(defn- next-ch! [st]
  (let [{:keys [^String s i n]} @st]
    (when (< i n)
      (let [c (.charAt s i)]
        (vswap! st (fn [m]
                     (if (= c \newline)
                       (assoc m :i (inc i) :line (inc (:line m)) :col 1)
                       (assoc m :i (inc i) :col (inc (:col m))))))
        c))))

(defn- err [st msg & [data]]
  (throw (ex-info (str "read error: " msg
                       " (" (:file @st) ":" (:line @st) ":" (:col @st) ")")
                  (merge {:type :reader :line (:line @st) :column (:col @st)
                          :file (:file @st)} data))))

(def ^:private whitespace #{\space \tab \newline \return \formfeed \,})
;; Clojure's `isTerminatingMacro` excludes #, ' and %, which is why `acc'` and
;; `x#` and `%1` are single tokens.
(def ^:private terminating #{\" \; \@ \^ \` \~ \( \) \[ \] \{ \} \\})

(defn- skip-ws! [st]
  (loop []
    (let [c (peek-ch st)]
      (cond
        (nil? c) nil
        (whitespace c) (do (next-ch! st) (recur))
        (= c \;) (do (loop [] (let [c (next-ch! st)]
                                (when (and c (not= c \newline)) (recur))))
                     (recur))
        :else nil))))

(declare read-form read-form* macros)

(def ^:private EOF ::eof)

(defn- read-token [st]
  (loop [acc (StringBuilder.)]
    (let [c (peek-ch st)]
      (if (or (nil? c) (whitespace c) (terminating c))
        (str acc)
        (do (next-ch! st) (recur (.append acc c)))))))

;; ------------------------------------------------------------------ numbers

(def ^:private int-re #"^([-+]?)(?:(0)|([1-9][0-9]*)|0[xX]([0-9A-Fa-f]+)|0([0-7]+)|([1-9][0-9]?)[rR]([0-9A-Za-z]+))$")
(def ^:private float-re #"^([-+]?[0-9]+(\.[0-9]*)?([eE][-+]?[0-9]+)?)(M)?$")

(defn- parse-number [^String t]
  (cond
    (re-matches #"^[-+]?[0-9]+$" t) (parse-long t)
    (re-matches #"^[-+]?0[xX][0-9A-Fa-f]+$" t)
    (let [neg? (str/starts-with? t "-")
          body (subs t (if (or neg? (str/starts-with? t "+")) 3 2))
          v #?(:clj (Long/parseLong body 16) :cljs (js/parseInt body 16))]
      (if neg? (- v) v))
    (re-matches #"^[-+]?[0-9]+N$" t) (parse-long (subs t 0 (dec (count t))))
    (re-matches #"^[-+]?[0-9]+(\.[0-9]*)?([eE][-+]?[0-9]+)?M?$" t)
    (parse-double (str/replace t #"M$" ""))
    (re-matches #"^[-+]?\.[0-9]+([eE][-+]?[0-9]+)?M?$" t)
    (parse-double (str/replace t #"M$" ""))
    :else nil))

;; ------------------------------------------------------------------ strings

(defn- read-escape [st]
  (let [c (next-ch! st)]
    (case c
      \t \tab \r \return \n \newline \\ \\ \" \" \b \backspace \f \formfeed
      \u (let [hex (str (next-ch! st) (next-ch! st) (next-ch! st) (next-ch! st))]
           (char #?(:clj (Integer/parseInt hex 16) :cljs (js/parseInt hex 16))))
      (if (and c (<= (int \0) (int c) (int \7)))
        (loop [acc (str c) k 1]
          (let [p (peek-ch st)]
            (if (and (< k 3) p (<= (int \0) (int p) (int \7)))
              (do (next-ch! st) (recur (str acc p) (inc k)))
              (char #?(:clj (Integer/parseInt acc 8) :cljs (js/parseInt acc 8))))))
        (err st (str "unsupported escape \\" c))))))

(defn- read-string* [st]
  (next-ch! st)                                              ; opening quote
  (loop [acc (StringBuilder.)]
    (let [c (next-ch! st)]
      (cond
        (nil? c) (err st "unterminated string")
        (= c \") (str acc)
        (= c \\) (recur (.append acc (read-escape st)))
        :else (recur (.append acc c))))))

(def ^:private named-chars
  {"newline" \newline "space" \space "tab" \tab "return" \return
   "formfeed" \formfeed "backspace" \backspace})

(defn- read-char* [st]
  (next-ch! st)                                              ; backslash
  (let [c (next-ch! st)]
    (when (nil? c) (err st "unterminated character"))
    (let [rest-tok (if (or (whitespace c) (terminating c)) "" (read-token st))
          tok (str c rest-tok)]
      (cond
        (= 1 (count tok)) (first tok)
        (named-chars tok) (named-chars tok)
        (str/starts-with? tok "u") (char #?(:clj (Integer/parseInt (subs tok 1) 16)
                                            :cljs (js/parseInt (subs tok 1) 16)))
        (str/starts-with? tok "o") (char #?(:clj (Integer/parseInt (subs tok 1) 8)
                                            :cljs (js/parseInt (subs tok 1) 8)))
        :else (err st (str "unknown character literal \\" tok))))))

;; --------------------------------------------------------------- collections

(defn- read-delimited [st closer]
  (next-ch! st)
  (loop [acc []]
    (skip-ws! st)
    (let [c (peek-ch st)]
      (cond
        (nil? c) (err st (str "unterminated, expecting " closer))
        (= c closer) (do (next-ch! st) acc)
        :else (let [v (read-form* st)]
                (if (= v EOF) (recur acc) (recur (conj acc v))))))))

;; ------------------------------------------------------------- syntax quote

(defn- unquote? [f] (and (seq? f) (= 'clojure.core/unquote (first f))))
(defn- unquote-splicing? [f] (and (seq? f) (= 'clojure.core/unquote-splicing (first f))))

(defn- resolve-sym
  "Resolve a symbol for syntax quote. Unqualified names that are not locals get
  the current namespace, matching Clojure; an alias is expanded to the namespace
  it names."
  [st sym]
  (let [{:keys [ns aliases]} @st
        n (name sym)]
    (cond
      (str/ends-with? n "#") sym                             ; handled by gensym
      (namespace sym) (let [a (symbol (namespace sym))]
                        (symbol (str (get aliases a (namespace sym))) n))
      (str/starts-with? n ".") sym
      :else (symbol (str (or ns "user")) n))))

(defn- syntax-quote [st form]
  (letfn [(sq [form]
            (cond
              (unquote? form) (second form)

              (symbol? form)
              (let [n (name form)]
                (if (str/ends-with? n "#")
                  (let [base (subs n 0 (dec (count n)))
                        g (:gensyms @st)]
                    (if-let [s (get @g form)]
                      (list 'quote s)
                      (let [s (gensym (str base "__"))]
                        (vswap! g assoc form s)
                        (list 'quote s))))
                  (list 'quote (resolve-sym st form))))

              (seq? form)
              (if (empty? form)
                (list 'clojure.core/list)
                (list 'clojure.core/seq (cons 'clojure.core/concat (expand-seq form))))

              (vector? form)
              (list 'clojure.core/vec (cons 'clojure.core/concat (expand-seq form)))

              (map? form)
              (list 'clojure.core/apply 'clojure.core/hash-map
                    (cons 'clojure.core/concat (expand-seq (apply concat form))))

              (set? form)
              (list 'clojure.core/set (cons 'clojure.core/concat (expand-seq form)))

              (or (keyword? form) (number? form) (string? form) (nil? form)
                  (true? form) (false? form) (char? form))
              form

              :else (list 'quote form)))
          (expand-seq [s]
            (mapv (fn [item]
                    (cond
                      (unquote? item) (list 'clojure.core/list (second item))
                      (unquote-splicing? item) (second item)
                      :else (list 'clojure.core/list (sq item))))
                  s))]
    (sq form)))

;; --------------------------------------------------------- reader dispatch

(defn- read-meta [st]
  (next-ch! st)                                              ; ^
  (skip-ws! st)
  (let [m (read-form* st)
        m (cond
            (keyword? m) {m true}
            (symbol? m) {:tag m}
            (string? m) {:tag m}
            (map? m) m
            :else (err st "metadata must be a symbol, keyword, string or map"))
        _ (skip-ws! st)
        target (read-form* st)]
    (if #?(:clj (instance? clojure.lang.IObj target) :cljs (satisfies? IWithMeta target))
      (vary-meta target merge m)
      target)))

(defn- read-regex [st]
  (next-ch! st)                                              ; opening quote
  (loop [acc (StringBuilder.)]
    (let [c (next-ch! st)]
      (cond
        (nil? c) (err st "unterminated regex")
        (= c \") {:flint/regex (str acc)}
        (= c \\) (let [d (next-ch! st)] (recur (.append (.append acc c) d)))
        :else (recur (.append acc c))))))

(defn- read-arg-fn
  "#(...) -- rewritten to (fn* [p1 p2 ...] body) with the arity implied by the
  highest %n used."
  [st]
  (let [body (read-delimited st \))
        maxn (volatile! 0)
        rest? (volatile! false)
        walk (fn walk [f]
               (cond
                 (symbol? f)
                 (let [n (name f)]
                   (cond
                     (= n "%") (do (vswap! maxn max 1) (symbol "p1__flint#"))
                     (= n "%&") (do (vreset! rest? true) (symbol "prest__flint#"))
                     (and (str/starts-with? n "%") (re-matches #"%[0-9]+" n))
                     (let [k (parse-long (subs n 1))]
                       (vswap! maxn max k)
                       (symbol (str "p" k "__flint#")))
                     :else f))
                 (seq? f) (apply list (map walk f))
                 (vector? f) (mapv walk f)
                 (set? f) (into #{} (map walk f))
                 (map? f) (into {} (map (fn [[k v]] [(walk k) (walk v)]) f))
                 :else f))
        body (walk (apply list body))
        params (into (mapv #(symbol (str "p" % "__flint#")) (range 1 (inc @maxn)))
                     (when @rest? ['& (symbol "prest__flint#")]))]
    (list 'fn* params body)))

(defn- read-cond
  "#?(:clj a :flint b) and the splicing form #?@(...)."
  [st splicing?]
  (next-ch! st)                                              ; ?
  (when splicing? (next-ch! st))                             ; @
  (skip-ws! st)
  (when-not (= \( (peek-ch st)) (err st "reader conditional wants a list"))
  (let [clauses (read-delimited st \))
        features (:features @st)]
    (loop [[k v & more] clauses]
      (cond
        (nil? k) (if splicing? ::splice-none EOF)
        (or (features k) (= k :default))
        (if splicing? {::splice v} v)
        :else (recur more)))))

(defn- read-dispatch [st]
  (next-ch! st)                                              ; #
  (let [c (peek-ch st)]
    (case c
      \{ (set (read-delimited st \}))
      \( (read-arg-fn st)
      \" (read-regex st)
      \' (do (next-ch! st) (list 'var (read-form* st)))
      \_ (do (next-ch! st) (read-form* st) EOF)
      \? (read-cond st (= \@ (peek2 st)))
      \# (do (next-ch! st)
             (let [t (read-token st)]
               (case t
                 "Inf" ##Inf "-Inf" ##-Inf "NaN" ##NaN
                 (err st (str "unknown ## literal: " t)))))
      \: (do (next-ch! st)
             (let [m (read-form* st)]
               (if (map? m) m (err st "#: wants a map"))))
      (if (or (nil? c) (whitespace c))
        (err st "unexpected #")
        ;; tagged literal
        (let [tag (read-form* st)
              _ (skip-ws! st)
              v (read-form* st)]
          (if (symbol? tag)
            {:flint/tagged tag :flint/value v}
            (err st "reader tag must be a symbol")))))))

(defn- read-symbolic [st]
  (let [tok (read-token st)]
    (cond
      (= tok "") (err st "empty token")
      (= tok "nil") nil
      (= tok "true") true
      (= tok "false") false
      (= tok "/") '/
      :else
      (or (parse-number tok)
          (if (str/starts-with? tok ":")
            (let [t (subs tok 1)]
              (if (str/starts-with? t ":")
                ;; ::kw -- auto-resolved to the current namespace
                (let [t (subs t 1)]
                  (if (str/includes? t "/")
                    (let [[a n] (str/split t #"/" 2)
                          al (get (:aliases @st) (symbol a) a)]
                      (keyword (str al) n))
                    (keyword (str (or (:ns @st) "user")) t)))
                (if (str/includes? t "/")
                  (let [i (str/index-of t "/")]
                    (if (zero? i)
                      (keyword t)
                      (keyword (subs t 0 i) (subs t (inc i)))))
                  (keyword t))))
            (if (str/includes? tok "/")
              (let [i (str/index-of tok "/")]
                (if (or (zero? i) (= i (dec (count tok))))
                  (symbol tok)
                  (symbol (subs tok 0 i) (subs tok (inc i)))))
              (symbol tok)))))))

(defn- with-pos [st line col v]
  (if #?(:clj (instance? clojure.lang.IObj v) :cljs (satisfies? IWithMeta v))
    (vary-meta v merge {:line line :column col :file (:file @st)})
    v))

(defn- read-form* [st]
  (skip-ws! st)
  (let [line (:line @st) col (:col @st)
        c (peek-ch st)]
    (if (nil? c)
      EOF
      (let [v (case c
                \( (with-pos st line col (apply list (read-delimited st \))))
                \[ (read-delimited st \])
                \{ (let [kvs (read-delimited st \})]
                     (when (odd? (count kvs)) (err st "map literal needs an even number of forms"))
                     (apply array-map kvs))
                \) (err st "unexpected )")
                \] (err st "unexpected ]")
                \} (err st "unexpected }")
                \" (read-string* st)
                \\ (read-char* st)
                \' (do (next-ch! st) (list 'quote (read-form* st)))
                \@ (do (next-ch! st) (list 'clojure.core/deref (read-form* st)))
                \^ (read-meta st)
                \` (do (next-ch! st)
                       (vswap! st assoc :gensyms (volatile! {}))
                       (let [f (read-form* st)
                             r (syntax-quote st f)]
                         (vswap! st assoc :gensyms nil)
                         r))
                \~ (do (next-ch! st)
                       (if (= \@ (peek-ch st))
                         (do (next-ch! st)
                             (list 'clojure.core/unquote-splicing (read-form* st)))
                         (list 'clojure.core/unquote (read-form* st))))
                \# (read-dispatch st)
                (read-symbolic st))]
        (if (and (seq? v) (not= v EOF))
          (with-pos st line col v)
          v)))))

(defn read-form
  "Read one form, or `:flint.reader/eof`."
  [st]
  (loop []
    (let [v (read-form* st)]
      (cond
        (= v EOF) (if (nil? (peek-ch st)) ::eof (recur))
        (and (map? v) (contains? v ::splice)) (err st "#?@ outside a collection")
        :else v))))

(defn reader
  "A reader state over `src`. `opts` may set `:file`, `:ns`, `:aliases` and
  `:features` (default #{:clj :flint})."
  ([src] (reader src {}))
  ([src opts]
   (let [st (make-state src (:file opts "<string>"))]
     (vswap! st merge (select-keys opts [:ns :aliases :features]))
     (when (:features opts) (vswap! st assoc :features (:features opts)))
     st)))

(defn set-ns! [st ns aliases]
  (vswap! st assoc :ns ns :aliases aliases))

(defn read-all
  "Read every form in `src`."
  ([src] (read-all src {}))
  ([src opts]
   (let [st (reader src opts)]
     (loop [acc []]
       (let [v (read-form st)]
         (if (= v ::eof) acc (recur (conj acc v))))))))

(defn read-one [src] (first (read-all src)))
