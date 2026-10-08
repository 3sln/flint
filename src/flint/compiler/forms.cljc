(ns flint.compiler.forms
  "A compact encoding for READ FORMS -- what `flint.compiler.reader/read-deferred`
  answers -- so a file can travel already read (`DECISIONS.md#stdlib-preread`).

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

  The encoder runs where `preread` runs, at the native CLI's build; the
  decoder runs in every compile that is handed a file this way, and only for
  the files it reaches. Both are ordinary guest code, so no runtime, port or
  door has anything new to carry.

  EXACTNESS IS THE CONTRACT: `decode` of `encode` is the same forms with the
  same metadata, held by `test/reader_test.clj` over every file in `lib/` and
  by the native CLI's images being byte-identical to a text read. Anything the
  compact meta form cannot say exactly -- keys in another order, another file
  -- falls back to writing the map as a value."
  (:require [flint.compiler.reader :as reader]
            [flint.rt]))

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
     (vec (keys (reader/position-meta "f" 1 1 [1 1] nil)))))

(defn- pos-map
  "The reader's position metadata, BUILT the way the decoder builds it: by
  `flint.compiler.reader/position-meta`, the reader's own construction, then any
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
    (seq extra) (reader/position-meta file line col cp (flint.rt/array-map extra))
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
