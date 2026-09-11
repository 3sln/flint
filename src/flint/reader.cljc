(ns flint.reader
  "The Clojure reader: source text to data.

  flint reads its own source rather than borrowing the host's reader. That is
  not duplicated work, it is the point: the compiler must read the same way on
  babashka and on flint, and it must control reader conditionals, syntax quote
  and metadata itself. Anything the host's reader would do differently would
  show up as a self-hosting divergence.

  Returns ordinary Clojure data, with `:line`/`:column`/`:file` metadata on
  every form that can carry it."
  (:require [clojure.string :as str]
            [flint.canon :as canon]
            [flint.rt]))

;; Characters are one-character strings throughout: flint has no char type, and
;; using strings here is what lets the reader read itself.
(def NL "\n")

(defn ch
  "The one-character string at index `i`. Goes through `flint.rt/nth` rather than
  `nth` on purpose: on a host `nth` of a string yields a Character, and every
  comparison against a one-character string below would silently be false."
  [s i]
  (flint.rt/nth s i))

(defn- make-state [s file]
  (volatile! {:s s :i 0 :n (count s) :line 1 :col 1 :file file
              :gensyms nil :features #{:flint} :ns nil :aliases {} :elided []}))

(defn- peek-ch [st]
  (let [m @st] (when (< (:i m) (:n m)) (ch (:s m) (:i m)))))

(defn- peek2 [st]
  (let [m @st] (when (< (inc (:i m)) (:n m)) (ch (:s m) (inc (:i m))))))

(defn- next-ch! [st]
  (let [m @st i (:i m)]
    (when (< i (:n m))
      (let [c (ch (:s m) i)]
        (vswap! st (fn [m]
                     (if (= c NL)
                       (assoc m :i (inc i) :line (inc (:line m)) :col 1)
                       (assoc m :i (inc i) :col (inc (:col m))))))
        c))))

(defn- err [st msg & [data]]
  (throw (ex-info (str "read error: " msg
                       " (" (:file @st) ":" (:line @st) ":" (:col @st) ")")
                  (merge {:type :reader :line (:line @st) :column (:col @st)
                          :file (:file @st)} data))))

(def ^:private whitespace #{" " "\t" "\n" "\r" "\f" ","})
;; Clojure's `isTerminatingMacro` excludes #, ' and %, which is why `acc'` and
;; `x#` and `%1` are single tokens.
(def ^:private terminating #{"\"" ";" "@" "^" "`" "~" "(" ")" "[" "]" "{" "}" "\\"})

(defn- skip-ws! [st]
  (loop []
    (let [c (peek-ch st)]
      (cond
        (nil? c) nil
        (whitespace c) (do (next-ch! st) (recur))
        (= c ";") (do (loop [] (let [c (next-ch! st)]
                                 (when (and c (not= c NL)) (recur))))
                      (recur))
        :else nil))))

(declare read-form read-form*)

(defn meta-able?
  "Which values can carry metadata. Numbers, strings and keywords cannot, here
  or in Clojure."
  [v]
  (or (symbol? v) (vector? v) (map? v) (set? v) (seq? v) (list? v)))


;; The end-of-input sentinel must be a value that CANNOT appear in source.
;; It used to be the keyword `::eof`, which worked until the reader read its own
;; source -- where `::eof` appears as a literal, and `read-delimited` silently
;; dropped it as "no form here". The symptom was a mis-shaped `if` a long way
;; downstream. A fresh volatile has identity nothing can forge, and `identical?`
;; is the only comparison used against it.
(def EOF (flint.rt/volatile "flint.reader/eof"))
(defn eof? [v] (identical? v EOF))

;; Same reasoning for the "this reader conditional matched nothing" marker.
(def SPLICE-NONE (flint.rt/volatile "flint.reader/splice-none"))

;; And the same reasoning again for a MATCHED `#?@`, which has to carry a value
;; out to the enclosing collection. It was a map, `{::splice v}` -- and this
;; file contains that map as a literal, so reading flint's own reader spliced
;; it. A marker that source can spell is a marker source can forge; the tag is
;; a fresh volatile, and `identical?` is the only comparison made against it.
(def SPLICE-TAG (flint.rt/volatile "flint.reader/splice"))
(defn- splice [v] [SPLICE-TAG v])
(defn- spliced? [x]
  (and (vector? x) (= 2 (count x)) (identical? (nth x 0) SPLICE-TAG)))

(defn peek-ch* [st] (peek-ch st))

(defn- read-token [st]
  (loop [acc []]
    (let [c (peek-ch st)]
      (if (or (nil? c) (whitespace c) (terminating c))
        (flint.rt/str-join acc)
        (do (next-ch! st) (recur (conj acc c)))))))

;; ------------------------------------------------------------------ numbers

;; Numbers are recognised by hand rather than by regex. The reader has to work
;; before `flint.regex` does -- the regex engine is itself compiled by this
;; reader -- and a digit scan is clearer than the pattern it replaces.

(defn- digit? [c] (and (some? c) (<= 48 (flint.rt/code-point-at c 0) 57)))
(defn- hex-digit-value [c]
  (let [v (flint.rt/code-point-at c 0)]
    (cond (and (>= v 48) (<= v 57)) (- v 48)
          (and (>= v 97) (<= v 102)) (- v 87)
          (and (>= v 65) (<= v 70)) (- v 55)
          :else nil)))

;; TAKES THE SIGN, and builds the value DOWNWARD when it is negative.
;;
;; Reading the digits as a magnitude and negating afterwards cannot represent
;; the most negative integer -- it has no positive counterpart -- so
;; `-9223372036854775808` was a valid literal that flint refused to read, with
;; "long overflow", while Clojure reads it. The overflow happened before the
;; sign was ever applied.
;;
;; Same edge as `numdiv.kin`'s `quot MIN -1` and `strnum.kin`'s integer arm,
;; and the same answer in all three: accumulate on the side that has room.
(defn parse-int-radix
  ([s radix] (parse-int-radix s radix false))
  ([s radix neg?]
   (loop [i 0 acc 0]
     (if (>= i (count s))
       (when (> (count s) 0) acc)
       (let [d (hex-digit-value (ch s i))]
         (when (and (some? d) (< d radix))
           (recur (inc i)
                  (if neg? (- (* acc radix) d) (+ (* acc radix) d)))))))))

(defn- parse-number [t]
  (let [n (count t)]
    (when (> n 0)
      (let [c0 (ch t 0)
            signed? (or (= c0 "-") (= c0 "+"))
            neg? (= c0 "-")
            body (if signed? (subs t 1) t)
            bn (count body)]
        (when (and (> bn 0) (digit? (ch body 0)))
          (cond
            ;; hex
            (and (> bn 2) (= "0" (ch body 0))
                 (or (= "x" (ch body 1)) (= "X" (ch body 1))))
            (parse-int-radix (subs body 2) 16 neg?)

            :else
            (let [body (if (and (> bn 0) (= "N" (ch body (dec bn)))) (subs body 0 (dec bn)) body)
                  body (if (and (> (count body) 0) (= "M" (ch body (dec (count body)))))
                         (subs body 0 (dec (count body))) body)
                  ;; integer if every character is a digit
                  int? (loop [i 0] (cond (>= i (count body)) true
                                         (digit? (ch body i)) (recur (inc i))
                                         :else false))]
              (if int?
                (parse-int-radix body 10 neg?)
                (let [v (flint.rt/str->num (if neg? (flint.rt/str2 "-" body) body))]
                  ;; `* 1.0` AND NOT `+ 0.0`, which is the same coercion and
                  ;; loses NEGATIVE ZERO: IEEE 754 says -0.0 + 0.0 is +0.0, so
                  ;; the literal `-0.0` read back as `0.0` while a COMPUTED
                  ;; negative zero printed correctly. Multiplying keeps the
                  ;; sign, and Clojure answers "-0.0" for the literal.
                  (when (number? v) (* 1.0 v)))))))))))

;; ------------------------------------------------------------------ strings

(defn- read-escape [st]
  (let [c (next-ch! st)]
    (cond
      (= c "t") "\t" (= c "r") "\r" (= c "n") "\n"
      (= c "\\") "\\" (= c "\"") "\"" (= c "b") "\b" (= c "f") "\f"
      (= c "u") (let [hex (flint.rt/str-join [(next-ch! st) (next-ch! st) (next-ch! st) (next-ch! st)])]
                  (flint.rt/from-code-point (parse-int-radix hex 16)))
      (and (some? c) (<= 48 (flint.rt/code-point-at c 0) 55))
      (loop [acc c k 1]
        (let [p (peek-ch st)]
          (if (and (< k 3) (some? p) (<= 48 (flint.rt/code-point-at p 0) 55))
            (do (next-ch! st) (recur (flint.rt/str2 acc p) (inc k)))
            (flint.rt/from-code-point (parse-int-radix acc 8)))))
      :else (err st (str "unsupported escape \\" c)))))

(defn- read-string* [st]
  (next-ch! st)                                              ; opening quote
  (loop [acc []]
    (let [c (next-ch! st)]
      (cond
        (nil? c) (err st "unterminated string")
        (= c "\"") (flint.rt/str-join acc)
        (= c "\\") (recur (conj acc (read-escape st)))
        :else (recur (conj acc c))))))

;; flint has no char type: a char is a one-character string (see the value
;; encoding in the README -- every character of Unicode fits inline in a Value).
;; So the reader produces strings here, and `\a` really is `"a"`.
(def ^:private named-chars
  {"newline" "\n" "space" " " "tab" "\t" "return" "\r"
   "formfeed" "\f" "backspace" "\b"})

(defn- read-char* [st]
  (next-ch! st)                                              ; backslash
  (let [c (next-ch! st)]
    (when (nil? c) (err st "unterminated character"))
    (let [rest-tok (if (or (whitespace c) (terminating c)) "" (read-token st))
          tok (str c rest-tok)]
      (cond
        (= 1 (count tok)) tok
        (named-chars tok) (named-chars tok)
        (str/starts-with? tok "u") (flint.rt/from-code-point (parse-int-radix (subs tok 1) 16))
        (str/starts-with? tok "o") (flint.rt/from-code-point (parse-int-radix (subs tok 1) 8))
        :else (err st (str "unknown character literal \\" tok))))))

;; --------------------------------------------------------------- collections

(defn- read-delimited
  "The elements up to `closer`, and WHERE EACH ONE STARTED.

  The positions go into `:child-pos` on the reader state, as a flat
  `[line col line col ...]`, and the caller picks them up immediately -- see
  `with-pos`. They exist because numbers, strings and keywords cannot carry
  metadata, here or in Clojure, so a literal has nowhere to record its own
  position. Its PARENT can record it instead, which is the difference between
  `(expect string? 42)` being able to point at the 42 and not.

  A nested read clobbers the key, so the caller must read it back before doing
  anything else -- which is why this is a stash rather than a return value: the
  three call sites want the elements, and only two want the positions."
  [st closer]
  (next-ch! st)
  (loop [acc [] poss []]
    (skip-ws! st)
    (let [c (peek-ch st)
          cl (:line @st) cc (:col @st)]
      (cond
        (nil? c) (err st (str "unterminated, expecting " closer))
        (= c closer) (do (next-ch! st)
                         (vswap! st assoc :child-pos poss)
                         acc)
        :else (let [v (read-form* st)]
                (cond
                  (eof? v) (recur acc poss)
                  ;; `#?@(:cljs [a b])` splices its elements into the
                  ;; surrounding collection; that is the whole difference from
                  ;; `#?`, and it was not happening ANYWHERE. A matched splice
                  ;; left the `::splice` marker map sitting in the collection,
                  ;; and an unmatched one left the `SPLICE-NONE` sentinel -- so
                  ;; `(ns s (:require [a] #?@(:cljs [[b]])))` asked for a
                  ;; namespace literally called
                  ;; `[:flint.reader/splice [[b]]]`, and the unmatched case
                  ;; reached the compiler as a Volatile.
                  ;;
                  ;; This is how a library conditionally adds a `:require`, so
                  ;; it is on the path of most real `.cljc`.
                  (identical? v SPLICE-NONE) (recur acc poss)
                  ;; Every spliced element gets the SPLICE's position: they were
                  ;; all written at `#?@`, and claiming otherwise would point a
                  ;; caret at source that is not there.
                  (spliced? v)
                  (let [xs (nth v 1)]
                    (recur (into acc xs)
                           (into poss (mapcat (fn [_] [cl cc]) xs))))
                  :else (recur (conj acc v) (conj poss cl cc))))))))

;; ------------------------------------------------------------- syntax quote

(defn- unquote? [f] (and (seq? f) (= 'clojure.core/unquote (first f))))
(defn- unquote-splicing? [f] (and (seq? f) (= 'clojure.core/unquote-splicing (first f))))

(def special-forms
  "Kept unqualified by syntax quote, exactly as Clojure does."
  '#{if do let* loop* recur fn* quote var throw try catch finally def
     new set! . & monitor-enter monitor-exit deftype* reify* case* letfn* ns})

(defn- resolve-sym
  "Resolve a symbol for syntax quote. An alias expands to the namespace it
  names; an unqualified name goes through the caller's `:resolve` hook, which is
  how `` `(fn [] x) `` becomes `clojure.core/fn` rather than `this.ns/fn`.
  Without that hook a macro defined outside clojure.core would emit calls to
  vars in its own namespace that do not exist -- the failure is confusing and
  arrives late, so the hook is not optional."
  [st sym]
  (let [{:keys [ns aliases resolve]} @st
        n (name sym)]
    (cond
      (str/ends-with? n "#") sym                             ; handled by gensym
      (namespace sym) (let [a (symbol (namespace sym))]
                        (symbol (str (get aliases a (namespace sym))) n))
      (str/starts-with? n ".") sym
      (contains? special-forms sym) sym
      resolve (or (resolve sym) (symbol (str (or ns "user")) n))
      :else (symbol (str (or ns "user")) n))))

(declare sq-form sq-expand-seq)

(defn- sq-gensym [st sym]
  (let [n (name sym)
        base (subs n 0 (dec (count n)))
        g (:gensyms @st)]
    (if-let [s (get @g sym)]
      (list 'quote s)
      (let [s (gensym (str base "__"))]
        (vswap! g assoc sym s)
        (list 'quote s)))))

(defn- sq-form [st form]
  (cond
    (unquote? form) (second form)

    (symbol? form)
    (if (str/ends-with? (name form) "#")
      (sq-gensym st form)
      (list 'quote (resolve-sym st form)))

    (seq? form)
    (if (empty? form)
      (list 'clojure.core/list)
      (list 'clojure.core/seq (cons 'clojure.core/concat (sq-expand-seq st form))))

    (vector? form)
    (list 'clojure.core/vec (cons 'clojure.core/concat (sq-expand-seq st form)))

    (map? form)
    ;; Canonical entry order, for the same reason as everywhere else: a
    ;; syntax-quoted map literal must expand identically on every host.
    (list 'clojure.core/apply 'clojure.core/hash-map
          (cons 'clojure.core/concat
                (sq-expand-seq st (apply concat (canon/sorted-entries form)))))

    (set? form)
    (list 'clojure.core/set (cons 'clojure.core/concat (sq-expand-seq st form)))

    :else form))

(defn- sq-expand-seq [st s]
  (mapv (fn [item]
          (cond
            (unquote? item) (list 'clojure.core/list (second item))
            (unquote-splicing? item) (second item)
            :else (list 'clojure.core/list (sq-form st item))))
        s))

(defn- syntax-quote [st form] (sq-form st form))

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
    (if (meta-able? target) (with-meta target (merge (meta target) m)) target)))

(defn- read-regex [st]
  (next-ch! st)                                              ; opening quote
  (loop [acc []]
    (let [c (next-ch! st)]
      (cond
        (nil? c) (err st "unterminated regex")
        ;; Built by call, not as a literal: a literal here is indistinguishable
        ;; from a regex literal to the analyzer, which would rewrite it.
        (= c "\"") (flint.rt/array-map [:flint/regex (flint.rt/str-join acc)])
        (= c "\\") (let [d (next-ch! st)] (recur (conj acc c d)))
        :else (recur (conj acc c))))))

(defn- all-digits? [s]
  (and (> (count s) 0)
       (loop [i 0] (cond (>= i (count s)) true
                         (digit? (ch s i)) (recur (inc i))
                         :else false))))

(defn- read-arg-fn
  "#(...) -- rewritten to (fn* [p1 p2 ...] body) with the arity implied by the
  highest %n used."
  [st]
  (let [body (read-delimited st ")")
        maxn (volatile! 0)
        rest? (volatile! false)
        walk (fn walk [f]
               (cond
                 (symbol? f)
                 (let [n (name f)]
                   (cond
                     (= n "%") (do (vswap! maxn max 1) (symbol "p1__flint#"))
                     (= n "%&") (do (vreset! rest? true) (symbol "prest__flint#"))
                     (and (str/starts-with? n "%") (all-digits? (subs n 1)))
                     (let [k (parse-long (subs n 1))]
                       (vswap! maxn max k)
                       (symbol (str "p" k "__flint#")))
                     :else f))
                 (seq? f) (apply list (map walk f))
                 (vector? f) (mapv walk f)
                 (set? f) (into #{} (map walk f))
                 ;; `flint.rt/array-map`, not `into {}`: `into` goes through a
                 ;; transient, and a transient map does not preserve insertion
                 ;; order. A map literal inside #() would come out reordered,
                 ;; and its values may have side effects.
                 (map? f) (flint.rt/array-map
                           (apply concat (map (fn [e] [(walk (key e)) (walk (val e))]) f)))
                 :else f))
        body (walk (apply list body))
        params (into (mapv #(symbol (str "p" % "__flint#")) (range 1 (inc @maxn)))
                     (when @rest? ['& (symbol "prest__flint#")]))]
    (list 'fn* params body)))

(defn- read-cond
  "#?(:clj a :flint b) and the splicing form #?@(...).

  A conditional that matches NOTHING is recorded in `:elided`. That is not
  pedantry: the form it stood in simply vanishes, so `(defn f [x] #?(:clj ..))`
  reads as `(defn f [x])` -- a function returning nil -- and a `:require`
  wrapped in one becomes a dependency the compiler never learns about. Across
  20 real libraries, 16 of the 28 namespaces that COMPILED had been silently
  cut this way -- so `it compiled` and `flint compiled that library` were not
  the same claim. Whoever reads this list decides; the reader's job is to stop
  it being invisible."
  [st splicing?]
  (let [line (:line @st)]
    (next-ch! st)                                            ; ?
    (when splicing? (next-ch! st))                           ; @
    (skip-ws! st)
    (when-not (= "(" (peek-ch st)) (err st "reader conditional wants a list"))
    (let [clauses (read-delimited st ")")
          features (:features @st)]
      (loop [[k v & more] clauses]
        (cond
          (nil? k)
          (do (vswap! st update :elided (fnil conj [])
                      {:file (:file @st) :line line
                       :offered (vec (take-nth 2 clauses))})
              (if splicing? SPLICE-NONE EOF))
          (or (features k) (= k :default))
          (if splicing? (splice v) v)
          :else (recur more))))))

(defn- qualify-keys
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

(defn- read-ns-map
  "`#:ns{...}`, `#::{...}`, `#::alias{...}` -- a namespaced map.

  This used to read the map and return it with its keys UNQUALIFIED, which is
  a silently wrong answer rather than a loud one. `pr-str` emits this form for
  any map with qualified keys, so every `deps.edn` a Clojure tool writes lands
  here."
  [st]
  (let [tok (read-token st)                                  ; ":ns" / "::" / "::alias"
        ns (cond
             (str/starts-with? tok "::")
             (let [a (subs tok 2)]
               (if (= a "")
                 (str (or (:ns @st) "user"))
                 (str (get (:aliases @st) (symbol a) a))))
             (str/starts-with? tok ":") (subs tok 1)
             :else "")
        _ (skip-ws! st)
        m (read-form* st)]
    (cond
      (= ns "") (err st "#: wants a namespace")
      (not (map? m)) (err st "#: wants a map")
      :else (qualify-keys ns m))))

(def builtin-tags
  "Reader tags every project can read without asking.

  Empty for now, and named rather than left implicit because the list is the
  thing `DECISIONS.md#reader-tags` is about: a tag is a NAME BOUND IN A SCOPE, the
  built-ins are the ones bound in every scope, and they are the ones a value may
  PRINT as -- a project-local rename is an addition, never a replacement, so
  that a printed form reads back everywhere rather than only where it was made. `#flint/table` joins it when a
  table can be a constant (`tables` step 9); `#\"...\"`, `#{}`, `#()`, `#_` and
  `#?` are reader SYNTAX rather than tags and are handled above."
  {'flint/table 'flint.table/read-table})

(defn- tag-error [tag readers]
  (let [known (sort (map str (keys readers)))]
    (str "no reader for the tag #" tag
         (if (seq known)
           (str "; this project can read "
                (str/join ", " (map (fn [k] (str "#" k)) known)))
           "; this project has no reader tags")
         ". A tag is bound per PROJECT, not per namespace: add it under"
         " :flint/tag-readers in deps.edn, mapping the tag NAME to the VAR that"
         " reads it -- {" tag " my.ns/read-it}. Using another project's tag is"
         " opt-in, which is why yours are not in scope here by default"
         " (DECISIONS.md#reader-tags). To make a tagged literal as a VALUE instead,"
         " call (tagged-literal '" tag " form); to read one from data, use"
         " clojure.edn/read-string with :readers or :default.")))

(defn- read-dispatch [st]
  (next-ch! st)                                              ; #
  (let [c (peek-ch st)]
    (cond
      (= c "{") (set (read-delimited st "}"))
      (= c "(") (read-arg-fn st)
      (= c "\"") (read-regex st)
      (= c "'") (do (next-ch! st) (list 'var (read-form* st)))
      (= c "_") (do (next-ch! st) (read-form* st) EOF)
      (= c "?") (read-cond st (= "@" (peek2 st)))
      (= c "#") (do (next-ch! st)
                    (let [t (read-token st)]
                      (cond (= t "Inf") ##Inf
                            (= t "-Inf") ##-Inf
                            (= t "NaN") ##NaN
                            :else (err st (str "unknown ## literal: " t)))))
      (= c ":") (read-ns-map st)
      (or (nil? c) (whitespace c)) (err st "unexpected #")
      :else
      (let [tag (read-form* st)
            _ (skip-ws! st)
            v (read-form* st)]
        (when-not (symbol? tag)
          (err st "reader tag must be a symbol"))
        ;; AN UNKNOWN TAG IS AN ERROR, as it is in Clojure. It used to build a
        ;; tagged literal out of anything, which meant a typo in a tag name --
        ;; `#inst` for `#instant`, a namespace misremembered -- read as a
        ;; perfectly good value and failed somewhere else entirely, or not at
        ;; all. `#a/b [1]` is not a value the reader may invent; it is a
        ;; request for a reader that has to have been registered.
        ;;
        ;; Reading DATA is the other case and is `clojure.edn/read-string`,
        ;; which takes `:readers` and `:default` and has always refused an
        ;; unknown tag. That is where a tagged literal comes from at runtime.
        (let [readers (:tags @st)
              target (get readers tag)]
          (if-not target
            (err st (tag-error tag readers))
            ;; A REWRITE, not a call. The reader evaluates nothing: it emits
            ;; `(the-var form)` and the ordinary pipeline takes it from there --
            ;; a macro expands at compile time and can fold to a constant, a
            ;; function is an ordinary call. That is why there is no
            ;; bootstrapping problem here and why a tag reader need not be a
            ;; macro (`DECISIONS.md#reader-tags`).
            ;;
            ;; It carries what it WAS. A rewrite that forgets its origin reports
            ;; errors against code nobody wrote, which is the `#?` bug one layer
            ;; down: reader conditionals used to relabel their result with the
            ;; position of the `#?`, and every failure inside one pointed at the
            ;; conditional. `:flint/read-form` is a `tagged-literals` tagged literal
            ;; holding the tag AS WRITTEN, so `pr-str` gives back what was
            ;; typed; `:flint/read-var` is what it resolved to, because "no
            ;; reader for #x" and "lib.a/read-x threw" name different things.
            ;;
            ;; The position sits on the SAME metadata map, so anything holding
            ;; the read form holds where it came from.
            (with-meta (list target v)
              {:flint/read-form (flint.rt/tagged-literal tag v)
               :flint/read-tag tag
               :flint/read-var target
               :line (:line @st) :column (:col @st) :file (:file @st)})))))))

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

(defn- with-pos
  "Attach `line`/`col`/`file`, and the CHILDREN's positions when the form has
  them.

  `:child-pos` is a flat `[line col line col ...]`, one pair per element, and it
  is what lets a caret point at a literal: numbers, strings and keywords cannot
  carry metadata -- here or in Clojure -- so the only place their position can
  live is the collection they are in."
  ([st line col v] (with-pos st line col v nil))
  ([st line col v children]
   (if (meta-able? v)
     ;; A POSITION ALREADY THERE WINS.
     ;;
     ;; `#?(:flint/check (expect string? 42))` returns the inner list, and the
     ;; conditional's own `read-form*` then stamps it -- so the form ended up
     ;; claiming the column of the `#?` and carrying the CONDITIONAL's children
     ;; rather than its own. Every check failure inside a reader conditional
     ;; pointed at the `#?` and could not find its arguments.
     ;;
     ;; The rule is simply that a form which already knows where it is does not
     ;; get relabelled by whatever it came out of. It was invisible while only
     ;; sequences carried a position and nothing read `:child-pos`.
     (with-meta v (merge {:line line :column col :file (:file @st)}
                         (when (seq children) {:child-pos children})
                         (meta v)))
     v)))

(defn- read-form* [st]
  (skip-ws! st)
  (let [line (:line @st) col (:col @st)
        c (peek-ch st)]
    (if (nil? c)
      EOF
      (let [v (cond
                (= c "(") (let [xs (read-delimited st ")")]
                            (with-pos st line col (apply list xs) (:child-pos @st)))
                (= c "[") (let [xs (read-delimited st "]")]
                            (with-pos st line col xs (:child-pos @st)))
                ;; An ORDERED map: the compiler's own map literals have side
                ;; effects in their values (each one analyses a sub-form), so
                ;; source order has to survive the reader on every host.
                (= c "{") (let [kvs (read-delimited st "}")]
                            (when (odd? (count kvs))
                              (err st "map literal needs an even number of forms"))
                            (flint.rt/array-map kvs))
                (= c ")") (err st "unexpected )")
                (= c "]") (err st "unexpected ]")
                (= c "}") (err st "unexpected }")
                (= c "\"") (read-string* st)
                (= c "\\") (read-char* st)
                (= c "'") (do (next-ch! st) (list 'quote (read-form* st)))
                (= c "@") (do (next-ch! st) (list 'clojure.core/deref (read-form* st)))
                (= c "^") (read-meta st)
                (= c "`") (do (next-ch! st)
                              (vswap! st assoc :gensyms (volatile! {}))
                              (let [f (read-form* st)
                                    r (syntax-quote st f)]
                                (vswap! st assoc :gensyms nil)
                                r))
                (= c "~") (do (next-ch! st)
                              (if (= "@" (peek-ch st))
                                (do (next-ch! st)
                                    (list 'clojure.core/unquote-splicing (read-form* st)))
                                (list 'clojure.core/unquote (read-form* st))))
                (= c "#") (read-dispatch st)
                :else (read-symbolic st))]
        ;; EVERY meta-able form, not just sequences.
        ;;
        ;; It used to be `(seq? v)`, so a symbol had no position -- and the
        ;; analyser already asks for one: `{:sym sym :line (:line (meta sym))}`
        ;; was reaching for a key that was never written, so every "unable to
        ;; resolve symbol" reported a nil line. It is also what a caret needs:
        ;; `(expect string? x)` can only point at `x` if `x` knows its column.
        ;;
        ;; The cost is that a symbol carrying metadata is no longer the
        ;; INTERNED one -- `with-meta` copies. Symbols compare by name, so
        ;; nothing about equality changes; what changes is compile-time
        ;; allocation, which is why this is measured rather than assumed.
        (if (and (meta-able? v) (not (eof? v)))
          (with-pos st line col v (:child-pos (when (seq? v) @st)))
          v)))))

(defn read-form
  "Read one form, or the `EOF` sentinel. Use `eof?` to test the result: the
  sentinel is deliberately not a value any source text can produce."
  [st]
  (loop []
    (let [v (read-form* st)]
      (cond
        (eof? v) (if (nil? (peek-ch st)) EOF (recur))
        (spliced? v) (err st "#?@ outside a collection")
        :else v))))

(def default-features
  "Which reader-conditional branches are selected, unless a caller says
  otherwise.

  Not `#{:clj}`: flint is not the JVM, and a `:clj` branch is host interop we
  cannot compile. Ported code needs a `:flint` or `:default` branch.

  It lives here, once, because it did not: `bin/flint` read every source twice
  more -- to find its requires and to order them -- each with its own literal
  `#{:flint}`, so overriding the compiler's set changed nothing. That is the
  same shape as the two EDN readers that both had to learn `#:ns{...}`.

  `:flint/check` is ON by default and removed by `:optimize [perf]`
  (`DECISIONS.md#checks`). A check that has to be asked for is a check nobody
  turns on, and one that survives into production is a tax on every call --
  so the default is the developer's build and the release build is the
  exception. Everything inside `#?(:flint/check ...)` then does not merely
  compile to nothing: the reader never hands it to the analyzer, so it costs no
  image bytes, no constants, and no shaking."
  #{:flint :flint/check})

(defn reader
  "A reader state over `src`. `opts` may set `:file`, `:ns`, `:aliases` and
  `:features` (default `default-features`)."
  ([src] (reader src {}))
  ([src opts]
   (let [st (make-state src (:file opts "<string>"))]
     (vswap! st merge (select-keys opts [:ns :aliases :features :resolve :tags]))
     (vswap! st assoc :features (or (:features opts) default-features))
     ;; PER PROJECT, merged over the built-ins. A dependency is read with its
     ;; own `:tags` and not with this project's, which is the whole point:
     ;; using a library's tag has to be something a project opts into
     ;; (`DECISIONS.md#reader-tags`).
     (vswap! st assoc :tags (merge builtin-tags (:tags opts)))
     st)))

(defn elided
  "Every reader conditional in this read that matched no feature, as
  `{:file :line :offered}`."
  [st]
  (:elided @st []))

(defn set-ns! [st ns aliases]
  (vswap! st assoc :ns ns :aliases aliases))

(defn read-all
  "Read every form in `src`."
  ([src] (read-all src {}))
  ([src opts]
   (let [st (reader src opts)]
     (loop [acc []]
       (let [v (read-form st)]
         (if (eof? v) acc (recur (conj acc v))))))))

(defn read-one [src] (first (read-all src)))
