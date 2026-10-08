(ns ^:internal flint.compiler.forms
  "READ FORMS: what they carry, how a deferred read is resolved, and a compact
  encoding for them, so a file can travel already read
  (`DECISIONS.md#stdlib-preread`). Every host reads with the kin reader and
  ENCODES; the compiler only DECODES (`DECISIONS.md#one-reader-and-no-other`).

  Not the wire codec, and deliberately not a mode of it. The wire codec
  (`flint.wire`, `runtime/src/codec.rs`) carries ANY value across a boundary
  and every runtime and port speaks it; this carries one shape -- forms with
  the reader's position metadata -- between two halves of the compiler, and
  both halves are this file. The things that make it small are facts about
  that shape and nothing else:

  * every symbol, keyword and string is written ONCE per file, in a table, and
    referred to by index -- a file names `defn` and `clojure.core/let`
    hundreds of times;
  * the reader's own metadata, `{:line :column :file :child-pos}`, which every
    meta-able form carries, is written as numbers -- the line as a delta from
    the previous one, the file not at all -- and rebuilt by the decoder;
  * counts and integers are varints.

  The ENCODER that runs is the kin one, in every host (`kin/formsenc.kin`,
  rule for rule this file's `encode`, which stays as its specification and as
  `test/reader_test.clj`'s check of it); the decoder runs in every compile that
  is handed a file, and only for the files it reaches.

  EXACTNESS IS THE CONTRACT: `decode` of `encode` is the same forms with the
  same metadata, and `encode` of what the kin reader wrote, decoded, is the
  kin reader's bytes -- held by `test/reader_test.clj` over every file in
  `lib/` and `cli/lib/`. Anything the
  compact meta form cannot say exactly -- keys in another order, another file
  -- falls back to writing the map as a value."
  (:require [flint.rt]))

;; ------------------------------------------------------- what a read leaves
;;
;; EVERYTHING ABOUT READ FORMS THAT IS NOT READING TEXT, moved here from
;; `flint.compiler.reader` when that namespace was deleted
;; (`DECISIONS.md#one-reader-and-no-other`): the compiler READS NOTHING -- every
;; host reads source with the one kin-generated reader and hands over these
;; bytes -- but it still has to know what the reader's own metadata is, what a
;; deferred conditional and a syntax-quoted symbol look like, and how to resolve
;; a deferred read for a compile's features. Those are facts about the FORMS,
;; and the decoder that rebuilds them is in this file.

(def bookkeeping-meta
  "Metadata keys the READER writes, which are not the program's.

  Every meta-able form gets `:line`, `:column` and `:file`; a sequence also gets
  `:child-pos`; and a reader-tag expansion gets the three `:flint/read-*` keys
  that say what was written, what it resolved to, and the form it produced.

  PUBLISHED, BECAUSE THE ANALYSER HAS TO SUBTRACT IT. `flint.compiler.analyzer` carries an
  author's metadata onto a literal, and anything left in this map would ride along
  -- so the two must agree about what is bookkeeping. They did not: the analyser
  knew the four position keys and not the three `:flint/read-*` ones, so every
  reader-tag expansion was wrapped in a `with-meta` whose map held the tag SYMBOL,
  which then resolved as a var. `#x \"one\"` answered \"unable to resolve symbol:
  x\", and four suites failed on it -- `test/tags.clj`, `test/sysns.clj`,
  `test/tables.clj` (`#flint/table` is a reader tag too) and `sdks/esm/build`.
  One list, read by both (AGENTS.md sec. 1)."
  #{:line :column :file :child-pos
    :flint/read-form :flint/read-tag :flint/read-var})

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
(def EOF (flint.rt/volatile "flint.compiler.forms/eof"))
(defn eof? [v] (identical? v EOF))

;; Same reasoning for the "this reader conditional matched nothing" marker.
(def SPLICE-NONE (flint.rt/volatile "flint.compiler.forms/splice-none"))

;; And the same reasoning again for a MATCHED `#?@`, which has to carry a value
;; out to the enclosing collection. It was a map, `{::splice v}` -- and this
;; file contains that map as a literal, so reading flint's own reader spliced
;; it. A marker that source can spell is a marker source can forge; the tag is
;; a fresh volatile, and `identical?` is the only comparison made against it.
(def SPLICE-TAG (flint.rt/volatile "flint.compiler.forms/splice"))
(defn- splice [v] [SPLICE-TAG v])
(defn- spliced? [x]
  (and (vector? x) (= 2 (count x)) (identical? (nth x 0) SPLICE-TAG)))

;; A READER CONDITIONAL KEPT AS DATA, for a read that does not yet know its
;; features (`read-deferred`, `DECISIONS.md#stdlib-preread`). A tagged literal
;; whose tag is a symbol NO SOURCE CAN SPELL -- `#` cannot begin a symbol token
;; -- so, like the two markers above, it is a value only this reader makes; and
;; unlike them it must survive the host codec, which a volatile cannot.
;;
;; Its form is `[kind line col child-pos items]`: `kind` is `:one` (`#?`),
;; `:splice` (`#?@`), or `:map` / `:set` for a map or set literal that holds a
;; conditional where resolving it can change the literal's SIZE -- a key, or a
;; splice -- and so cannot be built until it is resolved. `line`/`col` are
;; where the `#` (or `{`) was, `child-pos` the clause list's own positions,
;; and `items` the clauses (or the literal's elements) as read.
(def conditional-tag (symbol "flint.reader" "#?"))
(defn conditional?
  "Is `x` a reader conditional a deferred read left unresolved?"
  [x]
  (and (flint.rt/tagged-literal? x) (= conditional-tag (:tag x))))

(defn- choose
  "The branch of `clauses` that `features` selects: the value, a `splice` of
  it, or a marker for \"matched nothing\" -- `SPLICE-NONE` for a splice and
  `EOF` otherwise. ONE RULE for both the reader, which chooses as it reads
  when it knows the features, and `resolve-conditionals`, which chooses later
  when it did not."
  [clauses features splicing?]
  (loop [[k v & more] clauses]
    (cond
      (nil? k) (if splicing? SPLICE-NONE EOF)
      (or (features k) (= k :default)) (if splicing? (splice v) v)
      :else (recur more))))

(def syntax-quoted
  "The head of the form syntax quote leaves where a symbol needs RESOLVING:
  `` `(f x) `` reads as `(clojure.core/seq (clojure.core/concat
  (clojure.core/list (flint.reader/syntax-quoted f)) ..))`, and the ANALYZER
  turns `(flint.reader/syntax-quoted f)` into the quoted symbol `f` names in
  the namespace being compiled.

  NOT HERE, because what `f` names is compile state -- the namespace's
  aliases, what `clojure.core` declares -- and a reader that consults it reads
  the same file differently depending on when it is asked. That is what made
  every file get read TWICE in a compile: once context-free to find its
  requires, and again with the compiler's state to hand, which was 3.9 M of a
  trivial `flint run`'s 8.2 M instructions. The reader is now a function of
  the text and the file's own `ns` form, so the first read is the only one
  (`DECISIONS.md#context-free-reader`).

  What does not depend on context is still decided here: a gensym (`x#`, one
  name per syntax-quote form), a `.method`, and a special form, all of which
  read as `(quote sym)` exactly as before."
  'flint.reader/syntax-quoted)

(defn syntax-quoted?
  "Is `f` a symbol syntax quote left for the analyzer to resolve?"
  [f]
  (and (seq? f) (= syntax-quoted (first f))))

(defn position-meta
  "The metadata `stamp` gives a form at `line`/`col` in `file` with `children`
  positions, over the `existing` metadata it already had.

  PUBLIC because `flint.compiler.forms` rebuilds read forms' metadata, and has to build
  it THE SAME WAY: `merge` goes through a transient, so the key order of the
  answer is the host's -- insertion order on one, hash order on another -- and
  only the same construction is sure to give the same map."
  [file line col children existing]
  (merge {:line line :column col :file file}
         (when (seq children) {:child-pos children})
         existing))

(defn- stamp
  "`with-pos` with the file named rather than read off the reader state, so
  `resolve-conditionals` stamps a chosen branch exactly as the reader would
  have when it chose as it read."
  [file line col v children]
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
     (with-meta v (position-meta file line col children (meta v)))
     v))

(def require-clauses
  "The `ns` clauses that NAME NAMESPACES.

  One set, read by everything that needs the answer: the analyzer binds their
  aliases and refers, `flint.compiler.core/ns-requires` builds the load-order graph
  from the same heads, and the kin reader gives `::alias/kw` its namespace by
  the same two heads (`kin/readform.kin`).
  Two spellings of \"which clause names a namespace\" is a graph that disagrees
  with the bindings."
  #{:require :use})

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
  image bytes, no constants, and no shaking.

  `:flint/nested` is ON by default and is what makes `flint.ception` nameable
  (`DECISIONS.md#flint-ception`). Unlike the others it selects no reader branch:
  the CLI reads it to decide whether to offer that virtual namespace at all, so
  a build compiled without it cannot `:require` the SDK rather than being
  refused later. It is a feature and not a grant because the SDK confers no
  access -- turning it off is a statement about what this artifact is allowed to
  BE, not about what it may reach."
  #{:flint :flint/check :flint/nested})

(declare resolve-form resolve-node)

(defn- resolve-err [file line col msg]
  (throw (ex-info (str "read error: " msg " (" file ":" line ":" col ")")
                  {:type :reader :line line :column col :file file})))

(defn- resolve-items
  "A delimited collection's elements, resolved -- a conditional replaced by its
  branch, spliced, or dropped, exactly as `read-delimited` does when it
  chooses as it reads -- with its flat `poss` rebuilt to match. Answers
  `[items poss changed?]`; `poss` is nil when the input's was not one pair per
  element (a `'x` carries a stale one), in which case it is left alone.

  `sink`, threaded through every resolve-* fn to the one place that calls
  `choose` (`resolve-node`'s `:else` branch): nil, or a volatile a matched-
  nothing conditional is `conj`ed onto. See `resolve-conditionals`."
  [items poss features file sink]
  (let [n (count items)
        paired? (= (count poss) (* 2 n))]
    (loop [i 0 acc [] ps [] changed? false]
      (if (= i n)
        [acc (when paired? ps) changed?]
        (let [x (nth items i)
              l (when paired? (nth poss (* 2 i)))
              c (when paired? (nth poss (inc (* 2 i))))]
          (if (conditional? x)
            (let [r (resolve-node x features file sink)]
              (cond
                (eof? r) (recur (inc i) acc ps true)
                (identical? r SPLICE-NONE) (recur (inc i) acc ps true)
                (spliced? r) (let [xs (nth r 1)]
                               (recur (inc i) (into acc xs)
                                      (into ps (mapcat (fn [_] [l c]) xs)) true))
                :else (recur (inc i) (conj acc r) (conj ps l c) true)))
            (let [y (resolve-form x features file sink)]
              (recur (inc i) (conj acc y) (conj ps l c)
                     (or changed? (not (identical? x y)))))))))))

(defn- resolve-one
  "A conditional in a position that holds exactly ONE form -- a map value, a
  metadata value -- resolved, or refused as the odd count it would have read
  as."
  [x features file sink]
  (if (conditional? x)
    (let [r (resolve-node x features file sink)
          [_ line col] (:form x)]
      (if (or (eof? r) (identical? r SPLICE-NONE) (spliced? r))
        (resolve-err file line col "map literal needs an even number of forms")
        r))
    (resolve-form x features file sink)))

(defn- resolve-meta
  "Metadata with any conditional in it resolved. Only an author's `^{..}` can
  hold one; the reader's own keys never do, and are skipped."
  [m features file sink]
  (reduce (fn [acc e]
            (let [k (key e) v (val e)]
              (if (contains? bookkeeping-meta k)
                acc
                (let [v2 (resolve-one v features file sink)]
                  (if (identical? v v2) acc (assoc acc k v2))))))
          m m))

(defn- resolve-node
  "One conditional node: `EOF` or `SPLICE-NONE` when it matched nothing, a
  `splice` when a `#?@` matched, and otherwise the form -- stamped as
  `read-form*` stamps what a `#?` reads as.

  Every conditional, top-level or nested, resolves through the `:else` branch
  below -- `:map`/`:set` only recurse into one by way of `resolve-items` -- so
  it is the one place `sink` is written to, mirroring `read-cond`'s `:elided`
  for the live reader (same shape: `{:file :line :offered}`)."
  [x features file sink]
  (let [[kind line col cpos items] (:form x)]
    (cond
      (= kind :map)
      (let [[kvs] (resolve-items items nil features file sink)]
        (when (odd? (count kvs))
          (resolve-err file line col "map literal needs an even number of forms"))
        (stamp file line col (flint.rt/array-map kvs) nil))

      (= kind :set)
      (let [[xs] (resolve-items items nil features file sink)]
        (stamp file line col (set xs) nil))

      :else
      (let [[clauses cps] (resolve-items items cpos features file sink)
            v (choose clauses features (= kind :splice))]
        (when (and sink (or (eof? v) (identical? v SPLICE-NONE)))
          (vswap! sink conj {:file file :line line :offered (vec (take-nth 2 clauses))}))
        (if (or (eof? v) (identical? v SPLICE-NONE) (spliced? v))
          v
          (stamp file line col v (when (seq? v) (or cps cpos))))))))

(defn- resolve-form
  "`f` with every conditional inside it resolved; `f` itself, metadata and
  all, when it holds none."
  [f features file sink]
  (let [m (meta f)
        m2 (if m (resolve-meta m features file sink) m)
        rebuilt
        (cond
          (or (seq? f) (vector? f))
          (let [[xs ps changed?] (resolve-items (vec f) (:child-pos m) features file sink)]
            (when changed?
              [(if (seq? f) (apply list xs) xs)
               ;; THE POSITIONS FOLLOW THE ELEMENTS, and an empty list has
               ;; none -- `with-pos` writes `:child-pos` only when there are
               ;; children.
               (if (and ps (contains? m2 :child-pos))
                 (if (seq ps) (assoc m2 :child-pos ps) (dissoc m2 :child-pos))
                 m2)]))

          (map? f)
          (let [kvs (vec (mapcat (fn [e] [(key e) (val e)]) f))
                n (count kvs)
                out (loop [i 0 acc [] changed? false]
                      (if (= i n)
                        (when changed? acc)
                        (let [x (nth kvs i)
                              y (if (even? i)
                                  (resolve-form x features file sink)
                                  (resolve-one x features file sink))]
                          (recur (inc i) (conj acc y)
                                 (or changed? (not (identical? x y)))))))]
            (when out [(flint.rt/array-map out) m2]))

          (set? f)
          (let [xs (vec f)
                ys (mapv (fn [x] (resolve-form x features file sink)) xs)]
            (when (some true? (map (fn [x y] (not (identical? x y))) xs ys))
              [(set ys) m2]))

          :else nil)]
    (cond
      rebuilt (let [[v vm] rebuilt] (if vm (with-meta v vm) v))
      (identical? m m2) f
      :else (with-meta f m2))))
(defn resolve-conditionals
  "The forms of a `read-deferred` answer under `features`: what `read-all`
  with those features would have read from the same text, metadata included.
  Only the top-level forms `:conds` names are walked. `file` is the read's
  `:file`, which positions are stamped with.

  `sink`, optional: a volatile every matched-nothing conditional is `conj`ed
  onto, as `{:file :line :offered}` -- the same shape `read-cond` builds for
  the live reader's `:elided`, so a caller can report elisions from a
  deferred read the same way `elided` reports them from a live one. Omitted
  (the 3-arg arity), nothing is recorded -- the behaviour every existing
  caller already gets."
  ([d features file] (resolve-conditionals d features file nil))
  ([{:keys [forms conds]} features file sink]
   (if (empty? conds)
     forms
     (let [at (set conds)
           n (count forms)]
       (loop [i 0 acc []]
         (if (= i n)
           acc
           (let [f (nth forms i)]
             (if (contains? at i)
               (if (conditional? f)
                 (let [r (resolve-node f features file sink)
                       [_ line col] (:form f)]
                   (cond
                     (or (eof? r) (identical? r SPLICE-NONE)) (recur (inc i) acc)
                     (spliced? r) (resolve-err file line col "#?@ outside a collection")
                     :else (recur (inc i) (conj acc r))))
                 (recur (inc i) (conj acc (resolve-form f features file sink))))
               (recur (inc i) (conj acc f))))))))))


;; ------------------------------------------------------------------ format
;;
;; magic \"FLF1\", then: the string table (count, then each as length + UTF-8);
;; the name table (count, then each as a kind byte and string indices); the
;; header value `{:opts .. :conds ..}`; the form count; the forms.

(def ^:private T-NIL 0) (def ^:private T-TRUE 1) (def ^:private T-FALSE 2)
(def ^:private T-INT 3) (def ^:private T-NEG 4) (def ^:private T-NUM 5)
(def ^:private T-STR 6) (def ^:private T-NAME 7) (def ^:private T-LIST 8)
(def ^:private T-VEC 9) (def ^:private T-MAP 10) (def ^:private T-SET 11)
(def ^:private T-TAGGED 12) (def ^:private T-META 13) (def ^:private T-POS 14)
;; A SYMBOL WITH ONLY ITS POSITION, which is most of a file: the position and
;; the name, and no flags.
(def ^:private T-POS-SYM 15)

;; `T-POS` flags. A collection's `:child-pos` is DERIVED when every child that
;; carries a position of its own sits where the parent says it does -- then
;; only the children that cannot carry one (numbers, strings, keywords) are
;; written. Otherwise it is written in full.
(def ^:private F-CP-DERIVED 1) (def ^:private F-CP-FULL 2) (def ^:private F-EXTRA 4)

;; A name-table entry's kind: symbol or keyword, with or without a namespace.
(def ^:private N-SYM 0) (def ^:private N-SYM-NS 1)
(def ^:private N-KW 2) (def ^:private N-KW-NS 3)

;; Integers at or past this go as text. Well inside a fixnum on every runtime,
;; so the varint arithmetic below never leaves it.
(def ^:private VARINT-LIMIT 140737488355328)                    ; 2^47

(def ^:private MAGIC [70 76 70 49])                                ; "FLF1"

;; ----------------------------------------------------------------- encoding

(defn- put-varint! [t n]
  (loop [n n]
    (if (< n 128)
      (flint.rt/b-conj! t n)
      (do (flint.rt/b-conj! t (+ 128 (rem n 128)))
          (recur (quot n 128))))))

(defn- zigzag [n] (if (neg? n) (dec (* -2 n)) (* 2 n)))

(defn- intern!
  "The index of `x` in the table `tbl` (a volatile `{:ix {x i} :xs [x ..]}`),
  adding it if it is new."
  [tbl x]
  (let [ix (get (:ix @tbl) x)]
    (or ix
        (let [i (count (:xs @tbl))]
          (vswap! tbl (fn [m] {:ix (assoc (:ix m) x i) :xs (conj (:xs m) x)}))
          i))))

(def ^:private cp-first?
  "Does the reader's `merge` leave `:child-pos` before `:file` on this host?
  Which order a merge answers is the host's business (`pos-map`), so it is
  asked rather than assumed -- and `pos-parts` still checks every map."
  (= [:line :column :child-pos :file]
     (vec (keys (position-meta "f" 1 1 [1 1] nil)))))

(defn- pos-map
  "The reader's position metadata, BUILT the way the decoder builds it: by
  `position-meta`, the reader's own construction, then any
  author's keys. ONE CONSTRUCTION for the decoder and for the encoder's check,
  because what a map's keys come back in is the host's business -- insertion
  order on one, hash order on another -- and the only portable test of \"this
  will decode to the same map\" is to build it and look."
  [line col file cp extra]
  (cond
    ;; THE COMMON SHAPES AS LITERALS, in the order the reader's `merge` leaves
    ;; them on this host (`cp-first?`) -- a literal is a fraction of a merge's
    ;; cost, and these are built once per decoded form. Anything else takes
    ;; the reader's own construction.
    (seq extra) (position-meta file line col cp (flint.rt/array-map extra))
    (nil? cp) {:line line :column col :file file}
    cp-first? {:line line :column col :child-pos cp :file file}
    :else {:line line :column col :file file :child-pos cp}))

(def ^:private pos-keys #{:line :column :file :child-pos})

(defn- pos-parts
  "`m` as `[line col cp extra]` when `pos-map` rebuilds it EXACTLY -- the same
  entries in the same order, in this file -- and nil otherwise, in which case
  it is written as an ordinary map."
  [m file]
  (let [cp (:child-pos m)]
    (when (and (int? (:line m)) (int? (:column m)) (not (neg? (:column m)))
               (= file (:file m))
               (or (nil? cp)
                   (and (vector? cp) (even? (count cp))
                        (every? (fn [x] (and (int? x) (not (neg? x)))) cp))))
      (let [extra (vec (mapcat (fn [e] [(key e) (val e)])
                               (remove (fn [e] (contains? pos-keys (key e))) m)))
            built (pos-map (:line m) (:column m) file cp extra)]
        (when (and (= built m) (= (vec (keys built)) (vec (keys m))))
          [(:line m) (:column m) cp extra])))))

(defn- number-of
  "The number text `s` names: what `num->str` wrote, or one of the three that
  have no digits."
  [s]
  (cond
    (= s "##NaN") ##NaN
    (= s "##Inf") ##Inf
    (= s "##-Inf") ##-Inf
    :else (flint.rt/str->num s)))

(declare put-value!)

(defn- put-coll! [st tag xs]
  (flint.rt/b-conj! (:t st) tag)
  (put-varint! (:t st) (count xs))
  (doseq [x xs] (put-value! st x)))

(defn- put-bare!
  "`v` without its metadata."
  [st v]
  (let [t (:t st)]
    (cond
      (nil? v) (flint.rt/b-conj! t T-NIL)
      (true? v) (flint.rt/b-conj! t T-TRUE)
      (false? v) (flint.rt/b-conj! t T-FALSE)
      (and (int? v) (< -1 v VARINT-LIMIT)) (do (flint.rt/b-conj! t T-INT) (put-varint! t v))
      (and (int? v) (< (- VARINT-LIMIT) v 0)) (do (flint.rt/b-conj! t T-NEG) (put-varint! t (- v)))
      ;; ANY OTHER NUMBER AS TEXT, and only if the text reads back as the same
      ;; number -- checked here, at the build, rather than discovered as a
      ;; wrong constant in somebody's program.
      (number? v) (let [s (cond
                            ;; NaN is the number no comparison holds for; `=`
                            ;; will not do, because a host may answer true for
                            ;; the same boxed object.
                            (not (or (< v 0) (>= v 0))) "##NaN"
                            (= v ##Inf) "##Inf"
                            (= v ##-Inf) "##-Inf"
                            :else (flint.rt/num->str v))]
                    (when-not (or (= "##NaN" s) (= v (number-of s)))
                      (throw (ex-info (str "flint.compiler.forms cannot write the number " s " exactly")
                                      {:value v})))
                    (flint.rt/b-conj! t T-NUM)
                    (put-varint! t (intern! (:strs st) s)))
      (string? v) (do (flint.rt/b-conj! t T-STR) (put-varint! t (intern! (:strs st) v)))
      (or (symbol? v) (keyword? v))
      (do (flint.rt/b-conj! t T-NAME)
          ;; Interned WITHOUT metadata: the table holds the name, and a
          ;; symbol's position is written with the occurrence.
          (put-varint! t (intern! (:names st) (if (symbol? v) (with-meta v nil) v))))
      (seq? v) (put-coll! st T-LIST v)
      (vector? v) (put-coll! st T-VEC v)
      (map? v) (do (flint.rt/b-conj! t T-MAP)
                   (put-varint! t (count v))
                   (doseq [e v] (put-value! st (key e)) (put-value! st (val e))))
      (set? v) (put-coll! st T-SET (vec v))
      (flint.rt/tagged-literal? v) (do (flint.rt/b-conj! t T-TAGGED)
                                       (put-value! st (:tag v))
                                       (put-value! st (:form v)))
      :else (throw (ex-info (str "flint.compiler.forms cannot write a " (pr-str (type v))) {:value v})))))

(defn- own-pos
  "A child's own `[line col]`, when its metadata has one."
  [x]
  (let [m (when (or (symbol? x) (coll? x)) (meta x))]
    (when (and (int? (:line m)) (int? (:column m)))
      [(:line m) (:column m)])))

(defn- derivable?
  "Can `cp`, the flat child positions of the elements `xs`, be rebuilt from the
  children's own positions plus the ones written for children without one?"
  [cp xs]
  (and (= (count cp) (* 2 (count xs)))
       (loop [i 0 xs (seq xs)]
         (if (nil? xs)
           true
           (let [p (own-pos (first xs))]
             (if (or (nil? p) (= p [(nth cp i) (nth cp (inc i))]))
               (recur (+ i 2) (next xs))
               false))))))

(defn- put-value! [st v]
  (let [m (when (or (symbol? v) (coll? v)) (meta v))
        t (:t st)]
    (cond
      (nil? m) (put-bare! st v)

      (pos-parts m (:file st))
      (let [[line _ cp extra] (pos-parts m (:file st))
            xs (when cp (if (map? v) nil (vec v)))
            derived? (and cp xs (derivable? cp xs))
            put-pos! (fn []
                       (put-varint! t (zigzag (- line @(:line st))))
                       (vreset! (:line st) line)
                       (put-varint! t (:column m)))]
        (if (and (symbol? v) (nil? cp) (empty? extra))
          (do (flint.rt/b-conj! t T-POS-SYM)
              (put-pos!)
              (put-varint! t (intern! (:names st) (with-meta v nil))))
          (do
            (flint.rt/b-conj! t T-POS)
            (put-pos!)
            (put-varint! t (+ (if cp (if derived? F-CP-DERIVED F-CP-FULL) 0)
                              (if (seq extra) F-EXTRA 0)))
            (when (seq extra)
              (put-varint! t (quot (count extra) 2))
              (doseq [x extra] (put-value! st x)))
            (put-bare! st v)
            ;; The child positions AFTER the children, so the derived ones can
            ;; be read off what was just decoded.
            (when cp
              (if derived?
                (doseq [i (range (count xs))]
                  (when-not (own-pos (nth xs i))
                    (put-varint! t (zigzag (- (nth cp (* 2 i)) line)))
                    (put-varint! t (nth cp (inc (* 2 i))))))
                (do (put-varint! t (quot (count cp) 2))
                    (loop [i 0]
                      (when (< i (count cp))
                        (put-varint! t (zigzag (- (nth cp i) line)))
                        (put-varint! t (nth cp (inc i)))
                        (recur (+ i 2))))))))))

      :else (do (flint.rt/b-conj! t T-META)
                (put-value! st m)
                (put-bare! st v)))))

(defn- put-string! [t s]
  (let [b (flint.rt/str->b s)]
    (put-varint! t (flint.rt/b-count b))
    (flint.rt/b-append! t b)))

(defn encode
  "`read`, a `read-deferred` answer with its `:opts`, as bytes:
  `{:opts {..} :forms [..] :conds [..]}`."
  [read]
  (let [file (:file (:opts read))
        body (flint.rt/b-transient (flint.rt/vec->b []))
        st {:t body :file file :line (volatile! 1)
            :strs (volatile! {:ix {} :xs []}) :names (volatile! {:ix {} :xs []})}]
    (put-value! st {:opts (:opts read) :conds (:conds read)})
    (put-varint! body (count (:forms read)))
    (doseq [f (:forms read)] (put-value! st f))
    ;; The NAME table refers into the string table, so it is resolved to
    ;; indices before the string table is written -- and may add to it.
    (let [names (mapv (fn [n]
                        (let [ns (namespace n) nm (name n)
                              kind (if (keyword? n)
                                     (if ns N-KW-NS N-KW)
                                     (if ns N-SYM-NS N-SYM))]
                          (if ns
                            [kind (intern! (:strs st) ns) (intern! (:strs st) nm)]
                            [kind (intern! (:strs st) nm)])))
                      (:xs @(:names st)))
          out (flint.rt/b-transient (flint.rt/vec->b MAGIC))]
      (put-varint! out (count (:xs @(:strs st))))
      (doseq [s (:xs @(:strs st))] (put-string! out s))
      (put-varint! out (count names))
      (doseq [n names] (doseq [x n] (put-varint! out x)))
      (flint.rt/b-append! out (flint.rt/b-persistent! body))
      (flint.rt/b-persistent! out))))

;; ----------------------------------------------------------------- decoding

(defn- take-varint!
  "A varint at `@at`, moving past it. ONE BYTE is the common case -- a delta,
  a column, an index into a table -- and is taken without the loop."
  [b at]
  (let [i @at
        x (flint.rt/b-at b i)]
    (vreset! at (inc i))
    (if (< x 128)
      x
      (loop [n (- x 128) scale 128]
        (let [j @at
              y (flint.rt/b-at b j)]
          (vreset! at (inc j))
          (if (< y 128)
            (+ n (* y scale))
            (recur (+ n (* (- y 128) scale)) (* scale 128))))))))

(defn- unzigzag [n] (if (odd? n) (- (quot (inc n) 2)) (quot n 2)))

(declare take-value!)

(defn- take-n! [st b at n]
  (loop [i 0 acc []]
    (if (< i n) (recur (inc i) (conj acc (take-value! st b at))) acc)))

(defn- take-bare! [st b at tag]
  (cond
    (= tag T-LIST) (apply list (take-n! st b at (take-varint! b at)))
    (= tag T-VEC) (take-n! st b at (take-varint! b at))
    (= tag T-NAME) (nth (:names st) (take-varint! b at))
    (= tag T-STR) (nth (:strs st) (take-varint! b at))
    (= tag T-INT) (take-varint! b at)
    (= tag T-NIL) nil
    (= tag T-TRUE) true
    (= tag T-FALSE) false
    (= tag T-NEG) (- (take-varint! b at))
    (= tag T-NUM) (number-of (nth (:strs st) (take-varint! b at)))
    ;; THE READER'S CONSTRUCTORS, so a map or set comes back built the way
    ;; the reader built it: `flint.rt/array-map` keeps source order.
    (= tag T-MAP) (flint.rt/array-map (take-n! st b at (* 2 (take-varint! b at))))
    (= tag T-SET) (set (take-n! st b at (take-varint! b at)))
    (= tag T-TAGGED) (let [tg (take-value! st b at)]
                       (flint.rt/tagged-literal tg (take-value! st b at)))
    :else (throw (ex-info (str "flint.compiler.forms: unknown tag " tag) {:tag tag}))))

(defn- take-line!
  "A position's line: a delta from the last one taken."
  [st b at]
  (let [line (+ @(:line st) (unzigzag (take-varint! b at)))]
    (vreset! (:line st) line)
    line))

(defn- derived-cp
  "The child positions of `v` rebuilt: each child's own, or the pair written
  for a child that cannot carry one."
  [v line b at]
  (loop [xs (seq v) acc []]
    (if (nil? xs)
      acc
      (let [x (first xs)
            m (when (or (symbol? x) (coll? x)) (meta x))
            l (:line m)]
        (if (and (int? l) (int? (:column m)))
          (recur (next xs) (conj (conj acc l) (:column m)))
          (let [l (+ line (unzigzag (take-varint! b at)))]
            (recur (next xs) (conj (conj acc l) (take-varint! b at)))))))))

(defn- take-value! [st b at]
  (let [i @at
        tag (flint.rt/b-at b i)]
    (vreset! at (inc i))
    (cond
      (= tag T-POS-SYM)
      (let [line (take-line! st b at)
            col (take-varint! b at)]
        (with-meta (nth (:names st) (take-varint! b at))
          {:line line :column col :file (:file st)}))

      (= tag T-POS)
      (let [line (take-line! st b at)
            col (take-varint! b at)
            flags (take-varint! b at)
            extra (when (>= flags F-EXTRA) (take-n! st b at (* 2 (take-varint! b at))))
            v (let [j @at t (flint.rt/b-at b j)] (vreset! at (inc j)) (take-bare! st b at t))
            cp (cond
                 (odd? flags) (derived-cp v line b at)                ; F-CP-DERIVED
                 (= F-CP-FULL (rem flags 4))
                 (let [n (take-varint! b at)]
                   (loop [k 0 acc []]
                     (if (< k n)
                       (let [l (+ line (unzigzag (take-varint! b at)))]
                         (recur (inc k) (conj (conj acc l) (take-varint! b at))))
                       acc)))
                 :else nil)]
        ;; BUILT AS THE ENCODER CHECKED IT WOULD BE (`pos-parts`).
        (with-meta v (pos-map line col (:file st) cp (or extra []))))

      (= tag T-META)
      (let [m (take-value! st b at)] (with-meta (take-value! st b at) m))

      :else (take-bare! st b at tag))))

(defn decode
  "The `{:opts .. :forms .. :conds ..}` that `encode` wrote into `b`."
  [b]
  (when-not (and (>= (flint.rt/b-count b) 4)
                 (= MAGIC (mapv (fn [i] (flint.rt/b-at b i)) [0 1 2 3])))
    (throw (ex-info "flint.compiler.forms: not an encoding of read forms" {})))
  (let [at (volatile! 4)
        nstr (take-varint! b at)
        strs (loop [i 0 acc []]
               (if (< i nstr)
                 (let [n (take-varint! b at)
                       from @at]
                   (vreset! at (+ from n))
                   (recur (inc i) (conj acc (flint.rt/b->str (flint.rt/b-slice b from (+ from n))))))
                 acc))
        nname (take-varint! b at)
        names (loop [i 0 acc []]
                (if (< i nname)
                  (let [kind (take-varint! b at)
                        a (nth strs (take-varint! b at))]
                    (recur (inc i)
                           (conj acc
                                 (cond
                                   (= kind N-SYM) (symbol a)
                                   (= kind N-KW) (keyword a)
                                   (= kind N-SYM-NS) (symbol a (nth strs (take-varint! b at)))
                                   :else (keyword a (nth strs (take-varint! b at)))))))
                  acc))
        st0 {:strs strs :names names :line (volatile! 1) :file nil}
        head (take-value! st0 b at)
        st (assoc st0 :file (:file (:opts head)))
        n (take-varint! b at)]
    (assoc head :forms (take-n! st b at n))))
