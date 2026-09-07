(ns clojure.core
  "flint's clojure.core.

  Written in cljc on top of the Rust primitives, for the reason in
  doc/decisions/0002: a cljc function tree-shakes per var, so a program that
  never calls `partition-by` does not carry it. A var whose whole body is one
  `flint.rt/x` call is detected by the compiler and called directly, so the
  wrapper layer costs nothing at the call site."
  (:require [flint.regex]))

;; ---------------------------------------------------------------- primitives

(defn identity [x] x)
;; `:flint/result-inverts` tells the analyzer that everything known from `x` being
;; truthy applies to the OTHER branch of a test on `(not x)`, so occurrence
;; narrowing crosses a negation intact.
(defn ^{:flint/result-inverts x} not [x] (if x false true))

(defn nil? [x] (flint.rt/nil? x))
(defn some? [x] (if (flint.rt/nil? x) false true))
(defn true? [x] (flint.rt/identical? x true))
(defn false? [x] (flint.rt/identical? x false))
(defn boolean? [x] (flint.rt/boolean? x))
(defn boolean [x] (if x true false))

(defn =
  ([a] true)
  ([a b] (flint.rt/= a b))
  ([a b & more] (if (flint.rt/= a b)
                  (loop [prev b s (flint.rt/seq more)]
                    (if s
                      (if (flint.rt/= prev (flint.rt/first s))
                        (recur (flint.rt/first s) (flint.rt/next s))
                        false)
                      true))
                  false)))
(defn not=
  ([a] false)
  ([a b] (not (flint.rt/= a b)))
  ([a b & more] (not (apply2 = (cons a (cons b more))))))
(defn distinct? ([a] true) ([a b] (not (flint.rt/= a b))))
(defn identical? [a b] (flint.rt/identical? a b))
(defn hash [x] (flint.rt/hash x))
(defn compare [a b] (flint.rt/compare a b))

(defn ifn? [x] (if (flint.rt/fn? x) true (if (flint.rt/keyword? x) true
                                            (if (flint.rt/map? x) true
                                                (if (flint.rt/set? x) true
                                                    (flint.rt/vector? x))))))
(defn coll? [x] (if (flint.rt/sequential? x) true
                    (if (flint.rt/map? x) true (flint.rt/set? x))))
(defn associative? [x] (if (flint.rt/map? x) true (flint.rt/vector? x)))
(defn char? [x] (if (flint.rt/string? x) (flint.rt/= 1 (flint.rt/count x)) false))
(defn ident? [x] (if (flint.rt/keyword? x) true (flint.rt/symbol? x)))
(defn simple-keyword? [x] (if (flint.rt/keyword? x) (flint.rt/nil? (flint.rt/namespace x)) false))
(defn qualified-keyword? [x] (if (flint.rt/keyword? x) (some? (flint.rt/namespace x)) false))
(defn simple-symbol? [x] (if (flint.rt/symbol? x) (flint.rt/nil? (flint.rt/namespace x)) false))
(defn qualified-symbol? [x] (if (flint.rt/symbol? x) (some? (flint.rt/namespace x)) false))

(defn first [coll] (flint.rt/first coll))
(defn rest [coll] (flint.rt/rest coll))
(defn next [coll] (flint.rt/next coll))
(defn seq [coll] (flint.rt/seq coll))
(defn cons [x coll] (flint.rt/cons x coll))
(defn count [coll] (flint.rt/count coll))
(defn empty [coll] (flint.rt/empty coll))
(defn peek [coll] (flint.rt/peek coll))
(defn pop [coll] (flint.rt/pop coll))
(defn contains? [coll k] (flint.rt/contains? coll k))
(defn name [x] (flint.rt/name x))
(defn namespace [x] (flint.rt/namespace x))
(defn meta [x] (flint.rt/meta x))
(defn with-meta [x m] (flint.rt/with-meta x m))
(defn vary-meta
  ([x f] (flint.rt/with-meta x (f (flint.rt/meta x))))
  ([x f a] (flint.rt/with-meta x (f (flint.rt/meta x) a)))
  ([x f a b] (flint.rt/with-meta x (f (flint.rt/meta x) a b)))
  ([x f a b c] (flint.rt/with-meta x (f (flint.rt/meta x) a b c))))
(defn atom [x] (flint.rt/atom x))
(defn deref [x] (flint.rt/deref x))
(defn reset! [a v] (flint.rt/reset! a v))
(defn compare-and-set!
  "Set the atom to `new` only if it still holds `old`, by identity. True when
  it did. The primitive `swap!` retries on."
  [a old nv] (flint.rt/compare-and-set! a old nv))
(defn ex-message [e] (flint.rt/ex-message e))
(defn ex-data [e] (flint.rt/ex-data e))

;; A byte string answers `bytes?`, as it does in Clojure. Everything else about
;; them lives in `flint.bytes`: `byte-cat` and `str->bytes` are flint's, not
;; Clojure's, and `clojure.core` is a namespace people expect to match.
(defn bytes? [x] (flint.rt/bytes? x))

(defn transient [c] (flint.rt/transient c))
(defn persistent! [c] (flint.rt/persistent! c))
(defn conj! [c x] (flint.rt/conj! c x))
(defn assoc! [c k v] (flint.rt/assoc! c k v))
(defn dissoc! [c k] (flint.rt/dissoc! c k))

(defn list [& xs] (if (nil? xs) '() xs))

(defn- kind-explain
  "An explanation for a type predicate: what was wanted, and what arrived.

  `flint.check`'s `Predicate` protocol dispatches on `(meta value)`, and a
  `defn`'s metadata lands on the VAR -- so `:flint/value-meta` is what puts it
  on the function itself. There is no registry of known predicates anywhere,
  which is what lets a predicate held in a local explain itself as well as a
  literal one does.

  Written as a function rather than a macro because a macro that expands to a
  `def` defines a var this compiler never records: names are collected before
  any macro is evaluated. See `def-form-names`.

  Every call site is inside `#?(:flint/check ...)`, and that is MEASURED rather
  than tidy: attaching these fifteen explanations unconditionally cost 3 578
  bytes in a module that calls none of them (264 941 against 261 363, a pure
  `(defn main [_] \"nothing\")`). `doc/decisions/0032` says a check costs
  nothing in the build that ships, and metadata that ships is a cost -- so the
  reader removes the map and `m-defn` emits no `with-meta` at all."
  [expected]
  (fn [_ args]
    {:expected expected
     :note (flint.rt/str-join
            ["got " (flint.rt/name (flint.rt/kind (flint.rt/nth args 0)))])}))

(defn ^{:flint/value-meta #?(:flint/check {:flint.check/explain (kind-explain "a number")}
                             :default nil)}
  number? [x] (flint.rt/number? x))
(defn ^{:flint/value-meta #?(:flint/check {:flint.check/explain (kind-explain "an integer")}
                             :default nil)}
  int? [x] (flint.rt/int? x))
(defn ^{:flint/value-meta #?(:flint/check {:flint.check/explain (kind-explain "an integer")}
                             :default nil)}
  integer? [x] (flint.rt/int? x))
(defn ^{:flint/value-meta #?(:flint/check {:flint.check/explain (kind-explain "a float")}
                             :default nil)}
  float? [x] (flint.rt/float? x))
(defn ^{:flint/value-meta #?(:flint/check {:flint.check/explain (kind-explain "a float")}
                             :default nil)}
  double? [x] (flint.rt/float? x))
(defn ^{:flint/value-meta #?(:flint/check {:flint.check/explain (kind-explain "a string")}
                             :default nil)}
  string? [x] (flint.rt/string? x))
(defn ^{:flint/value-meta #?(:flint/check {:flint.check/explain (kind-explain "a keyword")}
                             :default nil)}
  keyword? [x] (flint.rt/keyword? x))
(defn ^{:flint/value-meta #?(:flint/check {:flint.check/explain (kind-explain "a symbol")}
                             :default nil)}
  symbol? [x] (flint.rt/symbol? x))
(defn ^{:flint/value-meta #?(:flint/check {:flint.check/explain (kind-explain "a vector")}
                             :default nil)}
  vector? [x] (flint.rt/vector? x))
(defn ^{:flint/value-meta #?(:flint/check {:flint.check/explain (kind-explain "a map")}
                             :default nil)}
  map? [x] (flint.rt/map? x))
(defn ^{:flint/value-meta #?(:flint/check {:flint.check/explain (kind-explain "a set")}
                             :default nil)}
  set? [x] (flint.rt/set? x))
(defn ^{:flint/value-meta #?(:flint/check {:flint.check/explain (kind-explain "a seq")}
                             :default nil)}
  seq? [x] (flint.rt/seq? x))
(defn ^{:flint/value-meta #?(:flint/check {:flint.check/explain (kind-explain "a list")}
                             :default nil)}
  list? [x] (flint.rt/seq? x))
(defn ^{:flint/value-meta #?(:flint/check {:flint.check/explain (kind-explain "a function")}
                             :default nil)}
  fn? [x] (flint.rt/fn? x))
(defn ^{:flint/value-meta #?(:flint/check {:flint.check/explain (kind-explain "something sequential")}
                             :default nil)}
  sequential? [x] (flint.rt/sequential? x))

(defn apply2 [f args] (flint.rt/apply f args))

(defn second [coll] (flint.rt/first (flint.rt/next coll)))
(defn ffirst [coll] (flint.rt/first (flint.rt/first coll)))
(defn nnext [coll] (flint.rt/next (flint.rt/next coll)))
(defn nfirst [coll] (flint.rt/next (flint.rt/first coll)))
(defn fnext [coll] (flint.rt/first (flint.rt/next coll)))
(defn empty? [coll] (nil? (flint.rt/seq coll)))

(defn inc [n] (flint.rt/add n 1))
(defn dec [n] (flint.rt/sub n 1))
(defn zero? [n] (flint.rt/num-eq n 0))
(defn pos? [n] (flint.rt/gt n 0))
(defn neg? [n] (flint.rt/lt n 0))
(defn quot [a b] (flint.rt/quot a b))
(defn rem [a b] (flint.rt/rem a b))
(defn mod [a b] (let [m (flint.rt/rem a b)]
                  (if (if (flint.rt/num-eq m 0) true
                          (flint.rt/= (flint.rt/gt a 0) (flint.rt/gt b 0)))
                    m
                    (flint.rt/add m b))))
(defn even? [n] (flint.rt/num-eq 0 (flint.rt/rem n 2)))
(defn odd? [n] (not (flint.rt/num-eq 0 (flint.rt/rem n 2))))

;; ------------------------------------------------------------ the first macros
;;
;; `lazy-seq` cannot use syntax quote, because syntax quote expands into calls
;; to `concat`/`list`/`seq` and `concat` is itself built on `lazy-seq`. It is
;; written with plain `list`/`cons` to break that knot; everything after it can
;; use syntax quote freely.

(defmacro lazy-seq [& body]
  (list 'flint.rt/lazy-seq (cons 'fn* (cons [] body))))

(defmacro when [test & body]
  (list 'if test (cons 'do body)))

(defmacro when-not [test & body]
  (list 'if test nil (cons 'do body)))

(defmacro if-not
  ([test then] (list 'if test nil then))
  ([test then else] (list 'if test else then)))

(defmacro cond [& clauses]
  (when clauses
    (list 'if (first clauses)
          (if (next clauses)
            (second clauses)
            (throw (ex-info "cond requires an even number of forms" {})))
          (cons 'clojure.core/cond (nnext clauses)))))

(defmacro and
  ([] true)
  ([x] x)
  ([x & more]
   (list 'let* ['and__x x]
         (list 'if 'and__x (cons 'clojure.core/and more) 'and__x))))

(defmacro or
  ([] nil)
  ([x] x)
  ([x & more]
   (list 'let* ['or__x x]
         (list 'if 'or__x 'or__x (cons 'clojure.core/or more)))))

(defmacro declare [& names]
  (cons 'do (map2 (fn [n] (list 'def n)) names)))

;; ------------------------------------------------------------------ sequences

(defn concat
  ([] nil)
  ([x] (lazy-seq x))
  ([x y] (lazy-seq
          (let [s (seq x)]
            (if s (cons (first s) (concat (rest s) y)) (seq y)))))
  ;; FOLDED, not nested. `(concat y (apply concat more))` recurses once per
  ;; argument before any of it is realised, which is a frame per argument.
  ([x y & more] (reduce (fn [a b] (concat a b)) (concat x y) more)))

(defn apply
  ([f args] (flint.rt/apply f args))
  ([f a args] (flint.rt/apply f (cons a args)))
  ([f a b args] (flint.rt/apply f (cons a (cons b args))))
  ([f a b c args] (flint.rt/apply f (cons a (cons b (cons c args)))))
  ([f a b c d & more]
   (flint.rt/apply f (cons a (cons b (cons c (cons d (spread more))))))))

(defn spread [args]
  (cond (nil? args) nil
        (nil? (next args)) (seq (first args))
        :else (cons (first args) (spread (next args)))))

(defn map2
  "Two-argument map over one collection, defined before `map` so that macros
  above can use it."
  [f coll]
  (lazy-seq (let [s (seq coll)]
              (when s (cons (f (first s)) (map2 f (rest s)))))))

(defn reduce
  ([f coll] (let [s (seq coll)]
              (if s (reduce f (first s) (rest s)) (f))))
  ([f init coll]
   ;; A vector is walked BY INDEX. The general road is `seq`/`first`/`next`,
   ;; and it allocates a seq step PER ELEMENT -- measured at 1.0 allocations
   ;; per element on `bench/progs/colls.cljc`, for a walk that produces no
   ;; value anyone keeps. Wall-clock barely sees it in a run that collects
   ;; twice; a nursery does, and so does a 128 MiB isolate.
   ;;
   ;; This is not one function getting faster: `mapv`, `filterv`, `into`,
   ;; `run!`, `some`, `every?`, `frequencies` and `group-by` all go through
   ;; here. The annotations are free -- `count` declares itself an integer and
   ;; the counter starts at a literal, so both are PROVEN and no check is
   ;; emitted, while the comparison and the increment become inline opcodes.
   (if (vector? coll)
     (let [^int n (count coll)]
       (loop [acc init ^int i 0]
         (if (flint.rt/lt i n)
           (let [acc' (f acc (flint.rt/nth coll i))]
             (if (reduced? acc') (nth acc' 0) (recur acc' (flint.rt/add i 1))))
           acc)))
     (reduce-seq f init coll))))

(defn- reduce-seq
  "`reduce` over anything that is not indexed."
  [f init coll]
  (loop [acc init s (seq coll)]
     (if s
       (let [acc' (f acc (first s))]
         ;; `nth`, not `deref`. A `reduced` is a one-element vector carrying a
         ;; marker in its metadata, and `deref` knows about atoms, volatiles and
         ;; delays -- so every short-circuiting `reduce` raised
         ;; `cannot deref this value`, and nothing in the suite had ever taken
         ;; that branch.
        (if (reduced? acc') (nth acc' 0) (recur acc' (next s))))
      acc)))

(defn reduced [x] (flint.rt/with-meta [x] {:flint/reduced true}))
(defn reduced? [x] (if (vector? x) (boolean (:flint/reduced (meta x))) false))

(defn into
  ([to from]
   (if (nil? from)
     to
     (if (or (vector? to) (map? to) (set? to))
       (persistent! (reduce conj! (transient to) from))
       (reduce conj to from)))))

(defn vec [coll] (if (vector? coll) coll (into [] coll)))
(defn set [coll] (if (set? coll) coll (into #{} coll)))

(defn hash-map [& kvs]
  (loop [m {} s (seq kvs)]
    (if s (recur (assoc m (first s) (second s)) (nnext s)) m)))

(defn hash-set [& xs] (set xs))
(defn vector [& xs] (vec xs))

(defn conj
  ([coll] coll)
  ([coll x] (flint.rt/conj coll x))
  ([coll x & more] (reduce flint.rt/conj (flint.rt/conj coll x) more)))

(defn assoc
  ([m k v] (flint.rt/assoc m k v))
  ([m k v & more]
   (loop [m (flint.rt/assoc m k v) s (seq more)]
     (if s (recur (flint.rt/assoc m (first s) (second s)) (nnext s)) m))))

(defn dissoc
  ([m] m)
  ([m k] (flint.rt/dissoc m k))
  ([m k & more] (reduce flint.rt/dissoc (flint.rt/dissoc m k) more)))

(defn disj
  ([s] s)
  ([s k] (flint.rt/disj s k))
  ([s k & more] (reduce flint.rt/disj (flint.rt/disj s k) more)))

(defn get
  ([m k] (flint.rt/get m k))
  ([m k not-found] (flint.rt/get m k not-found)))

(defn nth
  ([coll i] (flint.rt/nth coll i))
  ([coll i not-found] (flint.rt/nth coll i not-found)))

(defn last [coll]
  (loop [s (seq coll)]
    (if s (if (next s) (recur (next s)) (first s)) nil)))

(defn butlast [coll]
  (loop [acc [] s (seq coll)]
    (if (and s (next s)) (recur (conj acc (first s)) (next s)) (seq acc))))

(defn nthnext [coll n]
  (loop [n n s (seq coll)] (if (and s (pos? n)) (recur (dec n) (next s)) s)))

(defn nthrest [coll n]
  (loop [n n s coll] (if (pos? n) (recur (dec n) (rest s)) s)))

;; ----------------------------------------------------------------- arithmetic

(defn +
  ([] 0) ([a] a) ([a b] (flint.rt/add a b))
  ([a b & more] (reduce flint.rt/add (flint.rt/add a b) more)))
(defn -
  ([a] (flint.rt/sub 0 a)) ([a b] (flint.rt/sub a b))
  ([a b & more] (reduce flint.rt/sub (flint.rt/sub a b) more)))
(defn *
  ([] 1) ([a] a) ([a b] (flint.rt/mul a b))
  ([a b & more] (reduce flint.rt/mul (flint.rt/mul a b) more)))
(defn /
  ([a] (flint.rt/div 1 a)) ([a b] (flint.rt/div a b))
  ([a b & more] (reduce flint.rt/div (flint.rt/div a b) more)))

(defn- cmp-chain [f a b more]
  (if (f a b)
    (loop [prev b s (seq more)]
      (if s (if (f prev (first s)) (recur (first s) (next s)) false) true))
    false))

(defn < ([a] true) ([a b] (flint.rt/lt a b)) ([a b & more] (cmp-chain flint.rt/lt a b more)))
(defn <= ([a] true) ([a b] (flint.rt/le a b)) ([a b & more] (cmp-chain flint.rt/le a b more)))
(defn > ([a] true) ([a b] (flint.rt/gt a b)) ([a b & more] (cmp-chain flint.rt/gt a b more)))
(defn >= ([a] true) ([a b] (flint.rt/ge a b)) ([a b & more] (cmp-chain flint.rt/ge a b more)))
(defn == ([a] true) ([a b] (flint.rt/num-eq a b)) ([a b & more] (cmp-chain flint.rt/num-eq a b more)))

(defn min ([a] a) ([a b] (if (flint.rt/lt a b) a b))
  ([a b & more] (reduce min (min a b) more)))
(defn max ([a] a) ([a b] (if (flint.rt/gt a b) a b))
  ([a b & more] (reduce max (max a b) more)))
(defn abs [n] (if (flint.rt/lt n 0) (flint.rt/sub 0 n) n))

;; ---------------------------------------------------------------- bit fiddling

(defn bit-and ([a b] (flint.rt/bit-and a b)) ([a b & more] (reduce flint.rt/bit-and (flint.rt/bit-and a b) more)))
(defn bit-or ([a b] (flint.rt/bit-or a b)) ([a b & more] (reduce flint.rt/bit-or (flint.rt/bit-or a b) more)))
(defn bit-xor ([a b] (flint.rt/bit-xor a b)) ([a b & more] (reduce flint.rt/bit-xor (flint.rt/bit-xor a b) more)))
(defn bit-not [a] (flint.rt/bit-not a))
(defn bit-shift-left [a n] (flint.rt/bit-shift-left a n))
(defn bit-shift-right [a n] (flint.rt/bit-shift-right a n))
(defn unsigned-bit-shift-right [a n] (flint.rt/unsigned-bit-shift-right a n))
(defn bit-test [a n] (flint.rt/bit-test a n))
(defn bit-set [a n] (flint.rt/bit-or a (flint.rt/bit-shift-left 1 n)))
(defn bit-clear [a n] (flint.rt/bit-and a (flint.rt/bit-not (flint.rt/bit-shift-left 1 n))))
(defn bit-flip [a n] (flint.rt/bit-xor a (flint.rt/bit-shift-left 1 n)))

;; -------------------------------------------------------------------- strings

(defn- kw-or-sym-str [x]
  (let [n (flint.rt/name x) ns (flint.rt/namespace x)]
    (if ns (flint.rt/str2 ns (flint.rt/str2 "/" n)) n)))

(defn str
  ([] "")
  ([x] (cond
         (nil? x) ""
         (string? x) x
         (number? x) (flint.rt/num->str x)
         (keyword? x) (flint.rt/str2 ":" (kw-or-sym-str x))
         (symbol? x) (kw-or-sym-str x)
         (true? x) "true"
         (false? x) "false"
         :else (pr-str x)))
  ([x & more] (loop [acc (str x) s (seq more)]
                (if s (recur (flint.rt/str2 acc (str (first s))) (next s)) acc))))

(defn- check-subs
  "Throw a substring range error that says which end was wrong, and by how much."
  [who s start end]
  (let [n (count s)]
    (cond
      (not (string? s))
      (throw (ex-info (str who " wants a string, got " (name (flint.rt/kind s)))
                      {:actual s}))
      (not (int? start))
      (throw (ex-info (str who " start must be an integer, got "
                           (name (flint.rt/kind start)))
                      {:start start}))
      (not (int? end))
      (throw (ex-info (str who " end must be an integer, got "
                           (name (flint.rt/kind end)))
                      {:end end}))
      (neg? start)
      (throw (ex-info (str who " start is negative: " start)
                      {:start start :end end :length n}))
      (> end n)
      (throw (ex-info (str who " end is " end ", past the end of a string of "
                           n " character" (if (= n 1) "" "s"))
                      {:start start :end end :length n}))
      (> start end)
      (throw (ex-info (str who " start " start " is past its end " end)
                      {:start start :end end :length n}))
      :else nil)))

(defn subs
  ;; The check names the NUMBERS. `bad substring range` is true and useless:
  ;; the two things a reader needs are which end was out and what the length
  ;; actually was, and both are right here and gone by the time it throws.
  ;;
  ;; Written as a `throw` rather than as `flint.check/expect`, and that is a
  ;; hard constraint rather than a style: `clojure.core` is compiled BEFORE
  ;; `flint.check` -- it has to be, since `flint.check` is written in this
  ;; language -- so nothing in this file can name it. What the check branch
  ;; buys here is the same thing it buys anywhere: under `:optimize [perf]`
  ;; the reader deletes it and the call is the raw builtin again.
  ([s start]
   #?(:flint/check (check-subs "subs" s start (count s)))
   (flint.rt/subs s start))
  ([s start end]
   #?(:flint/check (check-subs "subs" s start end))
   (flint.rt/subs s start end)))

(defn keyword
  ([n] (if (keyword? n) n (flint.rt/keyword2 n)))
  ([ns n] (flint.rt/keyword2 ns n)))
(defn symbol
  ([n] (if (symbol? n) n (flint.rt/symbol2 n)))
  ([ns n] (flint.rt/symbol2 ns n)))

(defn tagged-literal
  "`#my.ns/thing form`, as a value.

  Its own type rather than a map (`doc/decisions/0034`): a map is ambiguous
  with a map in every format that has tags, and loses the namespace wherever
  the key has to become a string. It still READS like a two-key map on `:tag`
  and `:form`, so nothing treating one as a map needs a different way in."
  [tag form]
  (flint.rt/tagged-literal tag form))

(defn tagged-literal? [x] (flint.rt/tagged-literal? x))



;; `:tag` and `:form`, the names Clojure's own `tagged-literal` answers to.
(defn tag [x] (flint.rt/get x :tag))
(defn form [x] (flint.rt/get x :form))

(defn parse-long [s] (let [v (flint.rt/str->num s)] (if (int? v) v nil)))
(defn parse-double [s] (let [v (flint.rt/str->num s)] (if (number? v) (flint.rt/add v 0.0) nil)))

(defn long
  "Truncate toward zero to a 64-bit integer, as Clojure's `long` does."
  [x] (flint.rt/to-long x))
(defn int [x] (flint.rt/to-long x))
(defn double [x] (flint.rt/add x 0.0))
(defn num [x] x)

(defn char [n] (flint.rt/from-code-point n))
(defn int-of-char [c] (flint.rt/code-point-at c 0))

;; -------------------------------------------------------------- more macros

(defmacro ->
  [x & forms]
  (loop [x x forms forms]
    (if forms
      (let [form (first forms)
            threaded (if (seq? form)
                       (cons (first form) (cons x (rest form)))
                       (list form x))]
        (recur threaded (next forms)))
      x)))

(defmacro ->>
  [x & forms]
  (loop [x x forms forms]
    (if forms
      (let [form (first forms)
            threaded (if (seq? form)
                       (concat form (list x))
                       (list form x))]
        (recur threaded (next forms)))
      x)))

(defmacro as-> [expr nm & forms]
  (list 'let* (vec (concat [nm expr]
                           (interleave2 (repeat2 (count forms) nm) forms)))
        nm))

(defmacro cond-> [expr & clauses]
  (let [g (gensym "ct")
        pairs (partition 2 clauses)]
    (list 'clojure.core/let
          (vec (concat [g expr]
                       (mapcat (fn [p]
                                 (let [test (first p) step (second p)]
                                   [g (list 'if test
                                            (if (seq? step)
                                              (cons (first step) (cons g (rest step)))
                                              (list step g))
                                            g)]))
                               pairs)))
          g)))

(defmacro cond->> [expr & clauses]
  (let [g (gensym "ct")
        pairs (partition 2 clauses)]
    (list 'clojure.core/let
          (vec (concat [g expr]
                       (mapcat (fn [p]
                                 (let [test (first p) step (second p)]
                                   [g (list 'if test
                                            (if (seq? step) (concat step (list g)) (list step g))
                                            g)]))
                               pairs)))
          g)))

(defmacro some->> [expr & forms]
  (let [g (gensym "st")]
    (list 'clojure.core/let [g expr]
          (loop [acc g fs forms]
            (if fs
              (let [form (first fs)
                    step (if (seq? form) (concat form (list acc)) (list form acc))]
                (recur (list 'if (list 'clojure.core/nil? acc) nil step) (next fs)))
              acc)))))

(defmacro letfn
  "Mutually recursive local functions. flint closures capture by value, so each
  name is bound to a stub that dispatches through a volatile; the real functions
  are installed afterwards. That indirection is the price of by-value capture,
  and by-value capture is what keeps dead closure slots from retaining objects."
  [fnspecs & body]
  (let [names (map first fnspecs)
        boxes (map (fn [n] (gensym (str (name n) "-box"))) names)
        argsym (gensym "letfn-args")
        box-binds (mapcat (fn [b] [b (list 'clojure.core/volatile! nil)]) boxes)
        stub-binds (mapcat (fn [n b]
                             [n (list 'clojure.core/fn ['& argsym]
                                      (list 'clojure.core/apply (list 'clojure.core/deref b) argsym))])
                           names boxes)
        sets (map (fn [spec b]
                    (list 'clojure.core/vreset! b (cons 'clojure.core/fn (rest spec))))
                  fnspecs boxes)]
    (list 'clojure.core/let (vec (concat box-binds stub-binds))
          (cons 'do (concat sets body)))))

(defmacro some-> [expr & forms]
  (let [g (gensym "some")]
    (list 'let* [g expr]
          (loop [acc g fs forms]
            (if fs
              (let [form (first fs)
                    step (if (seq? form) (cons (first form) (cons acc (rest form))) (list form acc))]
                (recur (list 'if (list 'clojure.core/nil? acc) nil step) (next fs)))
              acc)))))

(defmacro if-let
  ([bindings then] (list 'clojure.core/if-let bindings then nil))
  ([bindings then else]
   (let [b (first bindings) v (second bindings) g (gensym "iflet")]
     ;; `clojure.core/let`, not `let*`: the binding form may be a destructuring
     ;; pattern, and only the macro layer knows how to take one apart.
     (list 'let* [g v]
           (list 'if g (list 'clojure.core/let [b g] then) else)))))

(defmacro when-let [bindings & body]
  (list 'clojure.core/if-let bindings (cons 'do body) nil))

(defmacro if-some
  ([bindings then] (list 'clojure.core/if-some bindings then nil))
  ([bindings then else]
   (let [b (first bindings) v (second bindings) g (gensym "ifsome")]
     (list 'let* [g v]
           (list 'if (list 'clojure.core/nil? g) else (list 'clojure.core/let [b g] then))))))

(defmacro when-some [bindings & body]
  (list 'clojure.core/if-some bindings (cons 'do body) nil))

(defmacro when-first [bindings & body]
  (let [b (first bindings) v (second bindings) g (gensym "wf")]
    (list 'clojure.core/when-let [g (list 'clojure.core/seq v)]
          (list 'clojure.core/let [b (list 'clojure.core/first g)] (cons 'do body)))))

(defmacro doto [x & forms]
  (let [g (gensym "doto")]
    (list 'let* [g x]
          (cons 'do (map2 (fn [f]
                            (if (seq? f)
                              (cons (first f) (cons g (rest f)))
                              (list f g)))
                          forms))
          g)))

(defmacro condp [pred expr & clauses]
  (let [g (gensym "condp")]
    (list 'clojure.core/let [g expr]
          (loop [cs (reverse (partition-all 2 clauses)) acc nil]
            (if (nil? (seq cs))
              acc
              (let [c (first cs)]
                (if (= 1 (count c))
                  (recur (next cs) (first c))
                  (recur (next cs)
                         (list 'if (list pred (first c) g) (second c) acc)))))))))

(defmacro case [e & clauses]
  ;; flint has no jump-table opcode yet; `case` is a chain of `=` tests. Keys
  ;; are keywords and small strings most of the time, and those compare as a
  ;; single 64-bit word, so the chain is cheaper than it looks. Noted in the
  ;; README as a difference: flint's `case` is O(n), Clojure's is O(1).
  (list 'let* ['case__g e]
        (loop [cs clauses acc nil seen []]
          (if (nil? cs)
            (list 'clojure.core/cond-chain 'case__g seen acc)
            (if (nil? (next cs))
              (recur nil (first cs) seen)
              (recur (nnext cs) acc (conj seen [(first cs) (second cs)])))))))

(defmacro cond-chain [g pairs default]
  (loop [ps (reverse pairs) acc default]
    (if (nil? (seq ps))
      acc
      (let [[test result] (first ps)
            t (if (and (seq? test) (= 'quote (first test))) (second test) test)
            cnd (if (or (vector? t) (seq? t) (set? t))
                  (cons 'clojure.core/or (map2 (fn [k] (list 'clojure.core/= g (list 'quote k))) t))
                  (list 'clojure.core/= g (list 'quote t)))]
        (recur (next ps) (list 'if cnd result acc))))))

(defmacro dotimes [bindings & body]
  (let [i (first bindings) n (second bindings) g (gensym "n")]
    (list 'let* [g n]
          (list 'loop* [i 0]
                (list 'if (list 'clojure.core/< i g)
                      (cons 'do (concat body (list (list 'recur (list 'clojure.core/inc i)))))
                      nil)))))

(defmacro while [test & body]
  (list 'loop* []
        (list 'if test (cons 'do (concat body (list (list 'recur)))) nil)))

(defmacro assert
  ([x] (list 'if x nil (list 'throw (list 'clojure.core/ex-info "assert failed" {}))))
  ([x msg] (list 'if x nil (list 'throw (list 'clojure.core/ex-info msg {})))))

(defmacro comment [& _] nil)

(defmacro defonce [nm expr]
  (list 'def nm expr))

(defmacro time [expr] expr)

(defn- seq-binding-form [pairs inner leaf]
  ;; Shared shape for `doseq` and `for`: fold the binding pairs right to left,
  ;; so later bindings nest inside earlier ones and :let/:when/:while apply from
  ;; the point they appear, as in Clojure.
  (reduce (fn [acc pair]
            (let [b (first pair) v (second pair)]
              (cond
                (= b :let) (list 'clojure.core/let v acc)
                (= b :when) (list 'if v acc nil)
                (= b :while) (list 'if v acc nil)
                :else (inner b v acc))))
          leaf
          (reverse pairs)))

(defmacro doseq [bindings & body]
  (seq-binding-form
   (partition 2 bindings)
   (fn [b v acc]
     (let [g (gensym "doseq")]
       (list 'loop* [g (list 'clojure.core/seq v)]
             (list 'if g
                   (list 'do
                         (list 'clojure.core/let [b (list 'clojure.core/first g)] acc)
                         (list 'recur (list 'clojure.core/next g)))
                   nil))))
   (cons 'do body)))

(defmacro for [bindings body]
  (seq-binding-form
   (partition 2 bindings)
   (fn [b v acc] (list 'clojure.core/mapcat (list 'clojure.core/fn [b] acc) v))
   (list 'clojure.core/list body)))

;; ---------------------------------------------------------- higher order fns

(defn map
  ([f coll] (map2 f coll))
  ([f c1 c2] (lazy-seq (let [s1 (seq c1) s2 (seq c2)]
                         (when (and s1 s2)
                           (cons (f (first s1) (first s2))
                                 (map f (rest s1) (rest s2))))))))

(defn mapcat2
  "Lazily, one element at a time.

  Not `(apply concat (map f coll))`, which is what this was. That applies
  `concat` to as many arguments as there are elements, and `concat`'s variadic
  arity recurses once per argument -- so `for` over six hundred items built six
  hundred nested frames and came apart inside `apply`, with a bounds check in
  `enter` on a function index that had been walked over. Three items worked."
  [f coll]
  (lazy-seq
   (let [s (seq coll)]
     (when s
       (concat (f (first s)) (mapcat2 f (rest s)))))))

(defn keep2 [f coll]
  (lazy-seq (let [s (seq coll)]
              (when s
                (let [v (f (first s))]
                  (if (nil? v) (keep2 f (rest s)) (cons v (keep2 f (rest s)))))))))
(defn keep [f coll] (keep2 f coll))

(defn filter [pred coll]
  (lazy-seq (let [s (seq coll)]
              (when s
                (if (pred (first s))
                  (cons (first s) (filter pred (rest s)))
                  (filter pred (rest s)))))))

(defn remove [pred coll] (filter (fn [x] (not (pred x))) coll))

(defn take [n coll]
  (lazy-seq (when (pos? n)
              (let [s (seq coll)]
                (when s (cons (first s) (take (dec n) (rest s))))))))

(defn drop [n coll]
  (loop [n n s (seq coll)] (if (and (pos? n) s) (recur (dec n) (next s)) s)))

(defn take-while [pred coll]
  (lazy-seq (let [s (seq coll)]
              (when s (when (pred (first s))
                        (cons (first s) (take-while pred (rest s))))))))

(defn drop-while [pred coll]
  (loop [s (seq coll)] (if (and s (pred (first s))) (recur (next s)) s)))

(defn range
  ([] (flint.rt/range3 0 nil 1))
  ([end] (flint.rt/range3 0 end 1))
  ([start end] (flint.rt/range3 start end 1))
  ([start end step] (flint.rt/range3 start end step)))

(defn repeat2 [n x] (take n (repeat-forever x)))
(defn repeat-forever [x] (lazy-seq (cons x (repeat-forever x))))
(defn repeat
  ([x] (repeat-forever x))
  ([n x] (repeat2 n x)))

(defn interleave2 [a b]
  (lazy-seq (let [sa (seq a) sb (seq b)]
              (when (and sa sb)
                (cons (first sa) (cons (first sb) (interleave2 (rest sa) (rest sb))))))))
(defn interleave
  ([a b] (interleave2 a b))
  ([a b & more] (apply2 interleave-all (cons a (cons b more)))))
(defn interleave-all [colls]
  (lazy-seq (let [ss (map2 seq colls)]
              (when (every? some? ss)
                (concat (map2 first ss) (interleave-all (map2 rest ss)))))))

(defn interpose [sep coll]
  (drop 1 (mapcat (fn [x] (list sep x)) coll)))

(defn mapcat
  ([f coll] (mapcat2 f coll))
  ([f c1 c2] (lazy-seq
              (let [s (seq (map f c1 c2))]
                (when s (concat (first s) (mapcat (fn [x] x) (rest s)))))))) 

(defn every? [pred coll]
  (loop [s (seq coll)] (if s (if (pred (first s)) (recur (next s)) false) true)))
(defn some [pred coll]
  (loop [s (seq coll)] (when s (let [v (pred (first s))] (if v v (recur (next s)))))))
(defn not-any? [pred coll] (not (some pred coll)))
(defn not-every? [pred coll] (not (every? pred coll)))

(defn reverse [coll] (reduce (fn [acc x] (cons x acc)) '() coll))

(defn comp
  ([] identity)
  ([f] f)
  ([f g] (fn [& args] (f (apply2 g args))))
  ([f g h] (fn [& args] (f (g (apply2 h args)))))
  ([f g h & more] (reduce (fn [a b] (comp a b)) (comp f g h) more)))

(defn partial
  ([f] f)
  ([f a] (fn [& args] (apply2 f (cons a args))))
  ([f a b] (fn [& args] (apply2 f (cons a (cons b args)))))
  ([f a b c] (fn [& args] (apply2 f (cons a (cons b (cons c args))))))
  ([f a b c & more] (fn [& args] (apply2 f (concat (cons a (cons b (cons c more))) args)))))

(defn constantly [x] (fn [& _] x))
(defn complement [f] (fn [& args] (not (apply2 f args))))

(defn juxt
  ([f] (fn [& args] [(apply2 f args)]))
  ([f g] (fn [& args] [(apply2 f args) (apply2 g args)]))
  ([f g h] (fn [& args] [(apply2 f args) (apply2 g args) (apply2 h args)]))
  ([f g h & more] (let [fs (cons f (cons g (cons h more)))]
                    (fn [& args] (mapv (fn [x] (apply2 x args)) fs)))))

(defn swap!
  "Apply `f` to the atom's value and store the result, retrying if someone else
  got there first.

  A RETRY LOOP over `compare-and-set!`, not `(reset! a (f (deref a)))`. A
  read-modify-write loses updates the moment two threads are inside it at once,
  and loses them silently -- the counter is simply smaller than it should be.
  That was unreachable while one thread ran a sandbox and became reachable the
  day two could (`doc/decisions/0028`).

  `f` may therefore run more than once, which is the same contract Clojure
  gives and the reason it wants a pure function."
  ([a f]
   (loop [] (let [old (deref a) nv (f old)]
              (if (compare-and-set! a old nv) nv (recur)))))
  ([a f x]
   (loop [] (let [old (deref a) nv (f old x)]
              (if (compare-and-set! a old nv) nv (recur)))))
  ([a f x y]
   (loop [] (let [old (deref a) nv (f old x y)]
              (if (compare-and-set! a old nv) nv (recur)))))
  ([a f x y & more]
   (loop [] (let [old (deref a)
                  nv (apply2 f (cons old (cons x (cons y more))))]
              (if (compare-and-set! a old nv) nv (recur))))))

(defn ex-info
  ([msg] (flint.rt/ex-info msg nil))
  ([msg data] (flint.rt/ex-info msg data))
  ([msg data cause] (flint.rt/ex-info msg data cause)))

(defn keys [m] (map2 first m))
(defn vals [m] (map2 second m))
(defn key [e] (flint.rt/nth e 0))
(defn val [e] (flint.rt/nth e 1))

(defn merge [& maps]
  ;; ONE transient across every source, not one per source. A transient made
  ;; from an existing map inherits that map's nodes, so the first write to each
  ;; path still copies it -- which is why merging two maps measured exactly the
  ;; same either way. The saving is on nodes the session itself created, so it
  ;; appears from the second source map onward, and only if the transient
  ;; survives that long.
  (let [ms (remove nil? maps)]
    (if (nil? (seq ms))
      nil
      (persistent!
       (reduce (fn [m b] (reduce (fn [m e] (assoc! m (key e) (val e))) m b))
               (transient (first ms)) (rest ms))))))

(defn select-keys [m ks]
  (persistent!
   (reduce (fn [acc k] (if (contains? m k) (assoc! acc k (get m k)) acc))
           (transient {}) ks)))

(defn update
  ([m k f] (assoc m k (f (get m k))))
  ([m k f a] (assoc m k (f (get m k) a)))
  ([m k f a b] (assoc m k (f (get m k) a b)))
  ([m k f a b & more] (assoc m k (apply2 f (cons (get m k) (cons a (cons b more)))))))

(defn get-in
  ([m ks] (reduce (fn [acc k] (if (nil? acc) nil (get acc k))) m ks))
  ([m ks not-found]
   (loop [acc m s (seq ks)]
     (if s
       (if (and (associative? acc) (contains? acc (first s)))
         (recur (get acc (first s)) (next s))
         (if (and (set? acc) (contains? acc (first s)))
           (recur (get acc (first s)) (next s))
           not-found))
       acc))))
(defn assoc-in [m ks v]
  (let [k (first ks)]
    (if (next ks)
      (assoc m k (assoc-in (get m k) (next ks) v))
      (assoc m k v))))
(defn update-in
  ([m ks f] (assoc-in m ks (f (get-in m ks))))
  ([m ks f a] (assoc-in m ks (f (get-in m ks) a)))
  ([m ks f a b] (assoc-in m ks (f (get-in m ks) a b)))
  ([m ks f a b & more] (assoc-in m ks (apply2 f (cons (get-in m ks) (cons a (cons b more)))))))

(defn frequencies [coll]
  (persistent! (reduce (fn [m x] (assoc! m x (inc (get m x 0)))) (transient {}) coll)))

(defn group-by [f coll]
  ;; The outer map is transient; the per-key vectors stay persistent because
  ;; they are read back on the next element that lands in the same group.
  (persistent!
   (reduce (fn [m x] (let [k (f x)] (assoc! m k (conj (get m k []) x))))
           (transient {}) coll)))

(defn distinct [coll]
  (loop [acc (transient []) seen (transient #{}) s (seq coll)]
    (if s
      (let [x (first s)]
        (if (contains? seen x)
          (recur acc seen (next s))
          (recur (conj! acc x) (conj! seen x) (next s))))
      (seq (persistent! acc)))))

(defn- as-comparator
  "Clojure lets a predicate stand in for a comparator: (sort > xs) works because
  a boolean-returning fn is read as `less-than`."
  [f]
  (fn [a b]
    (let [r (f a b)]
      (if (number? r) r (if r -1 (if (f b a) 1 0))))))

(defn sort
  ([coll] (merge-sort compare (vec coll)))
  ([cmp coll] (merge-sort (as-comparator cmp) (vec coll))))

;; ONE RUN MERGED INTO `acc`: `src[lo..mid)` against `src[mid..hi)`, both
;; already sorted, appended in order.
;;
;; A TIE TAKES FROM THE LEFT, which is what makes the sort STABLE -- `<= 0`
;; and not `< 0`. Clojure's sort is stable and `sort-by` is worth nothing
;; without it.
(defn- merge-run [cmp src acc lo mid hi]
  (loop [acc acc ^int i lo ^int j mid]
    (if (flint.rt/lt i mid)
      (if (flint.rt/lt j hi)
        (if (<= (cmp (flint.rt/nth src i) (flint.rt/nth src j)) 0)
          (recur (conj! acc (flint.rt/nth src i)) (flint.rt/add i 1) j)
          (recur (conj! acc (flint.rt/nth src j)) i (flint.rt/add j 1)))
        (recur (conj! acc (flint.rt/nth src i)) (flint.rt/add i 1) j))
      (if (flint.rt/lt j hi)
        (recur (conj! acc (flint.rt/nth src j)) i (flint.rt/add j 1))
        acc))))

;; BOTTOM-UP, so nothing is sliced and nothing is walked as a seq.
;;
;; The recursive top-down version split with `subvec`, which COPIES here --
;; O(n) per level, so O(n log n) element copies before any comparing -- and
;; then merged through `first`/`next` with a persistent `conj` per element.
;; `sort` is the only caller and it paid for all three.
;;
;; This makes runs of 1, 2, 4, ... and merges pairs of them into a transient,
;; one fresh vector per pass. Same complexity, and none of the copying.
(defn- merge-sort [cmp v0]
  (let [^int n (count v0)]
    (if (< n 2)
      (seq v0)
      (loop [src v0 ^int width 1]
        (if (flint.rt/lt (flint.rt/sub n 1) width)
          (seq src)
          (recur
            (persistent!
              (loop [acc (transient []) ^int lo 0]
                (if (flint.rt/lt lo n)
                  (let [^int mid (if (flint.rt/lt n (flint.rt/add lo width))
                                   n (flint.rt/add lo width))
                        ^int hi (if (flint.rt/lt n (flint.rt/add mid width))
                                  n (flint.rt/add mid width))]
                    (recur (merge-run cmp src acc lo mid hi) hi))
                  acc)))
            (flint.rt/add width width)))))))

(defn subvec2 [v start end]
  (loop [acc [] i start] (if (< i end) (recur (conj acc (nth v i)) (inc i)) acc)))
(defn subvec
  ([v start] (subvec2 v start (count v)))
  ([v start end] (subvec2 v start end)))

(defn sort-by
  ([kf coll] (merge-sort (fn [a b] (compare (kf a) (kf b))) (vec coll)))
  ([kf cmp coll] (let [c (as-comparator cmp)]
                   (merge-sort (fn [a b] (c (kf a) (kf b))) (vec coll)))))

(defn- check-partition [who n step]
  (cond
    (not (int? n))
    (throw (ex-info (str who " size must be an integer, got " (name (flint.rt/kind n)))
                    {:n n}))
    (not (pos? n))
    ;; `(partition 0 coll)` took nothing, found nothing missing, dropped
    ;; nothing and recurred -- an infinite sequence of nils, produced silently
    ;; and lazily, so the symptom appeared wherever it was finally realised
    ;; rather than here.
    (throw (ex-info (str who " size must be positive, got " n
                         " -- a partition of " n " would never consume the "
                         "collection and the sequence would not end")
                    {:n n}))
    (not (pos? step))
    (throw (ex-info (str who " step must be positive, got " step
                         " -- a step of " step " would not advance")
                    {:step step}))
    :else nil))

(defn partition
  ([n coll] (partition n n coll))
  ([n step coll]
   #?(:flint/check (check-partition "partition" n step))
   (lazy-seq (let [s (seq coll)]
               (when s
                 (let [p (vec (take n s))]
                   (when (= n (count p))
                     (cons (seq p) (partition n step (drop step s))))))))))

(defn partition-all
  ([n coll] (partition-all n n coll))
  ([n step coll]
   #?(:flint/check (check-partition "partition-all" n step))
   (lazy-seq (let [s (seq coll)]
               (when s (cons (seq (vec (take n s))) (partition-all n step (drop step s))))))))

(defn iterate [f x] (lazy-seq (cons x (iterate f (f x)))))

(defn zipmap [ks vs]
  (loop [m (transient {}) ks (seq ks) vs (seq vs)]
    (if (and ks vs)
      (recur (assoc! m (first ks) (first vs)) (next ks) (next vs))
      (persistent! m))))

(defn mapv
  ([f coll] (persistent! (reduce (fn [acc x] (conj! acc (f x))) (transient []) coll)))
  ([f c1 c2] (vec (map f c1 c2))))
(defn filterv [pred coll]
  (persistent! (reduce (fn [acc x] (if (pred x) (conj! acc x) acc)) (transient []) coll)))

;; LAZY, like `map` and `keep` next door and like Clojure. These two built the
;; whole answer into a vector and handed back its seq, so
;; `(take 3 (map-indexed f (iterate inc 0)))` did not return -- while the same
;; expression with plain `map` did. Nothing recorded that as a divergence
;; because nothing asked it.
(defn- map-indexed2 [f i coll]
  (lazy-seq (let [s (seq coll)]
              (when s
                (cons (f i (first s)) (map-indexed2 f (inc i) (rest s)))))))

(defn map-indexed [f coll] (map-indexed2 f 0 coll))

(defn- keep-indexed2 [f i coll]
  (lazy-seq (let [s (seq coll)]
              (when s
                (let [v (f i (first s))]
                  (if (nil? v)
                    (keep-indexed2 f (inc i) (rest s))
                    (cons v (keep-indexed2 f (inc i) (rest s)))))))))

(defn keep-indexed [f coll] (keep-indexed2 f 0 coll))

(defn reduce-kv [f init m]
  (reduce (fn [acc e] (f acc (key e) (val e))) init m))

(defn fnil
  ([f d] (fn [x & args] (apply2 f (cons (if (nil? x) d x) args))))
  ([f d1 d2] (fn [x y & args] (apply2 f (cons (if (nil? x) d1 x)
                                              (cons (if (nil? y) d2 y) args))))))

(defn run! [f coll] (reduce (fn [_ x] (f x) nil) nil coll) nil)
(defn doall [coll] (do (count coll) coll))
(defn dorun [coll] (do (count coll) nil))
(defn sequence [coll] (seq coll))

(defn list*
  ([args] (seq args))
  ([a args] (cons a (seq args)))
  ([a b args] (cons a (cons b (seq args))))
  ([a b c args] (cons a (cons b (cons c (seq args))))))

(defn not-empty [coll] (if (seq coll) coll nil))
(defn find [m k] (if (contains? m k) [k (get m k)] nil))

(defn split-at [n coll] [(vec (take n coll)) (drop n coll)])
(defn split-with [pred coll] [(vec (take-while pred coll)) (drop-while pred coll)])

(defn take-last [n coll] (let [c (count coll)] (drop (max 0 (- c n)) coll)))
(defn drop-last
  ([coll] (drop-last 1 coll))
  ([n coll] (take (max 0 (- (count coll) n)) coll)))

(defn take-nth [n coll]
  (keep-indexed (fn [i x] (if (zero? (rem i n)) x nil)) coll))

(defn dedupe [coll]
  (loop [acc (transient []) prev ::none s (seq coll)]
    (if s
      (let [x (first s)]
        (recur (if (= x prev) acc (conj! acc x)) x (next s)))
      (seq (persistent! acc)))))

;; LAZY, and one run at a time. The eager version walked the whole input
;; before returning anything, so an infinite seq never came back.
;;
;; The run is taken with `take-while` and dropped with `drop`, which is how
;; Clojure writes it -- and it means only the run being asked for is forced.
(defn partition-by [f coll]
  (lazy-seq
    (let [s (seq coll)]
      (when s
        (let [fst (first s)
              fv (f fst)
              run (cons fst (take-while (fn [x] (= fv (f x))) (next s)))]
          (cons run (partition-by f (drop (count run) s))))))))

(defn flatten [x]
  (filter (fn [e] (not (sequential? e)))
          (rest (tree-seq sequential? seq x))))

(defn tree-seq [branch? children root]
  (let [walk (fn walk [node]
               (lazy-seq
                (cons node (when (branch? node) (mapcat walk (children node))))))]
    (walk root)))

(defn max-key
  ([k x] x)
  ([k x y] (if (> (k x) (k y)) x y))
  ([k x y & more] (reduce (fn [a b] (if (> (k a) (k b)) a b)) (max-key k x y) more)))
(defn min-key
  ([k x] x)
  ([k x y] (if (< (k x) (k y)) x y))
  ([k x y & more] (reduce (fn [a b] (if (< (k a) (k b)) a b)) (min-key k x y) more)))

(defn merge-with [f & maps]
  (let [ms (remove nil? maps)]
    (if (nil? (seq ms))
      nil
      (persistent!
       (reduce (fn [m b]
                 (reduce (fn [m e]
                           (let [k (key e) v (val e)]
                             (if (contains? m k)
                               (assoc! m k (f (get m k) v))
                               (assoc! m k v))))
                         m b))
               (transient (first ms)) (rest ms))))))

(defn update-vals [m f] (reduce-kv (fn [acc k v] (assoc acc k (f v))) {} m))
(defn update-keys [m f] (reduce-kv (fn [acc k v] (assoc acc (f k) v)) {} m))

(defn array-map [& kvs] (apply2 hash-map kvs))

(defn memoize [f]
  (let [cache (atom {})]
    (fn [& args]
      (let [k (vec args)]
        (if (contains? @cache k)
          (get @cache k)
          (let [v (apply2 f args)]
            (reset! cache (assoc @cache k v))
            v))))))

(defn trampoline
  ([f] (loop [r (f)] (if (fn? r) (recur (r)) r)))
  ([f & args] (trampoline (fn [] (apply2 f args)))))

(defn cycle [coll] (lazy-seq (concat coll (cycle coll))))

(defn every-pred
  ([p] (fn [& args] (every? (fn [x] (p x)) args)))
  ([p q] (fn [& args] (and (every? (fn [x] (p x)) args) (every? (fn [x] (q x)) args)))))
(defn some-fn
  ([p] (fn [& args] (some (fn [x] (p x)) args)))
  ([p q] (fn [& args] (or (some (fn [x] (p x)) args) (some (fn [x] (q x)) args)))))

(defn count-matching [pred coll] (reduce (fn [n x] (if (pred x) (inc n) n)) 0 coll))

(defn ratio? [x] false)
(defn bigdec? [x] false)
(defn decimal? [x] false)
(defn rational? [x] (int? x))
(defn nat-int? [x] (and (int? x) (>= x 0)))
(defn pos-int? [x] (and (int? x) (> x 0)))
(defn neg-int? [x] (and (int? x) (< x 0)))
(defn indexed? [x] (vector? x))
(defn counted? [x] (or (vector? x) (map? x) (set? x) (string? x)))
(defn seqable? [x] (or (nil? x) (coll? x) (string? x)))

(defn type [x]
  (cond (nil? x) nil (string? x) :string (keyword? x) :keyword (symbol? x) :symbol
        (int? x) :int (float? x) :double (boolean? x) :boolean
        (vector? x) :vector (map? x) :map (set? x) :set (seq? x) :seq (fn? x) :fn
        :else :unknown))

(defn nil-or [x d] (if (nil? x) d x))

;; ------------------------------------------------------------------ printing

(defn- escape-string [s]
  (loop [acc "\"" i 0]
    (if (< i (count s))
      (let [c (nth s i)]
        (recur (flint.rt/str2 acc
                              (cond (= c "\"") "\\\""
                                    (= c "\\") "\\\\"
                                    (= c "\n") "\\n"
                                    (= c "\t") "\\t"
                                    (= c "\r") "\\r"
                                    :else c))
               (inc i)))
      (flint.rt/str2 acc "\""))))

;; Two printers, one traversal. `pr` is READABLE -- a string comes back with its
;; quotes -- and `print` is not. They differ in exactly one leaf case and are
;; otherwise the same walk, so `readable?` rides along rather than the cond
;; being written twice.
;;
;; It used to be one printer, with `print-str` and `println-str` calling the
;; readable one. That is wrong at every level, not just the top: Clojure's
;; `(print-str ["x" 1])` is `[x 1]`, and flint's was `["x" 1]`.

(defn- join-with* [sep xs readable?]
  (loop [acc "" s (seq xs) first? true]
    (if s
      (recur (flint.rt/str2 (if first? acc (flint.rt/str2 acc sep))
                            (pr-str* (first s) readable?))
             (next s) false)
      acc)))

(defn- join-entries* [m readable?]
  (loop [acc "" s (seq m) first? true]
    (if s
      (let [e (first s)]
        (recur (flint.rt/str2 (if first? acc (flint.rt/str2 acc ", "))
                              (flint.rt/str2 (pr-str* (key e) readable?)
                                             (flint.rt/str2 " " (pr-str* (val e) readable?))))
               (next s) false))
      acc)))

(def Printable__impls (atom {}))

;; `Printable`: how a value writes ITSELF.
;;
;; `pr-str` and `str` fall through to this for anything `clojure.core` does not
;; define itself, so a library type specialises its own printing without the
;; printer learning what it is.
;;
;; TWO METHODS. `print-data` is the form that READS BACK -- what `pr-str` wants.
;; `print-human` is the form for a person, with no quoting and nothing there for
;; a reader -- what `print-str` wants. They are separate rather than one method
;; taking a `readable?` flag, because they are separate jobs and a flag argument
;; is two functions sharing a name. An implementation may give only
;; `print-data`; `print-human` falls back to it, never the other way round.
;;
;; A method recurses into its children through `pr-str` or `print-str`, which
;; are the same two things it is choosing between, so no flag has to travel.
;;
;; Written LONGHAND rather than with `defprotocol`, for the reason recorded on
;; `def-form-names`: top-level `def` names are collected before macros are
;; expanded, so a name a macro would have produced cannot be referenced earlier
;; in the file -- and the printer is four hundred lines above `defprotocol`.
;; The alternative was moving the printer below the protocol section, which
;; would put the printer nowhere in particular to save four lines. This is the
;; second time that limit has cost something (the first was `defpred`), which
;; is worth knowing when it is next weighed.
;;
;; The shape is exactly what `defprotocol` emits, so `extend`, `extend-method`,
;; `extend-protocol` and `satisfies?` all work on it unchanged.
(def Printable
  (hash-map :flint/protocol 'clojure.core/Printable
            :impls Printable__impls
            :method-keys [:clojure.core/print-data :clojure.core/print-human]))

(defn print-data
  "`x` as DATA: a form meant to be read back. This is what `pr-str` reaches."
  [x]
  (let [f (find-protocol-method Printable__impls :clojure.core/print-data x)]
    (if f (f x) (protocol-miss 'clojure.core/Printable 'clojure.core/print-data x))))

(defn print-human
  "`x` FOR A PERSON: no quoting, no escaping, nothing there to satisfy a reader.
  This is what `print-str` reaches.

  A kind that gives only `print-data` gets it used here too, because a readable
  form is a serviceable human one. The reverse is NOT true and is not done: a
  human form promoted into `pr-str` would produce something that does not read
  back, which is the one thing `pr-str` promises."
  [x]
  (let [f (or (find-protocol-method Printable__impls :clojure.core/print-human x)
              (find-protocol-method Printable__impls :clojure.core/print-data x))]
    (if f (f x) (protocol-miss 'clojure.core/Printable 'clojure.core/print-human x))))

(defn- pr-str* [x readable?]
  (cond
    (nil? x) "nil"
    (true? x) "true"
    (false? x) "false"
    (string? x) (if readable? (escape-string x) x)
    (number? x) (flint.rt/num->str x)
    (keyword? x) (flint.rt/str2 ":" (kw-or-sym-str x))
    (symbol? x) (kw-or-sym-str x)
    (vector? x) (flint.rt/str2 "[" (flint.rt/str2 (join-with* " " x readable?) "]"))
    (set? x) (flint.rt/str2 "#{" (flint.rt/str2 (join-with* " " x readable?) "}"))
    (map? x) (flint.rt/str2 "{" (flint.rt/str2 (join-entries* x readable?) "}"))
    ;; No read syntax, deliberately: a value whose printed form can be read
    ;; back is forgeable by construction (0022).
    ;; Before the map branch, because a tagged literal READS like a map and
    ;; would otherwise print as one.
    (tagged-literal? x) (flint.rt/str2 "#" (flint.rt/str2 (kw-or-sym-str (tag x))
                                                          (flint.rt/str2 " " (pr-str* (form x) readable?))))
    (opaque? x) (let [l (opaque-label x)]
                  (if (nil? l) "#<opaque>" (flint.rt/str2 "#<opaque " (flint.rt/str2 (pr-str* l false) ">"))))
    (seq? x) (flint.rt/str2 "(" (flint.rt/str2 (join-with* " " x readable?) ")"))
    (sequential? x) (flint.rt/str2 "(" (flint.rt/str2 (join-with* " " x readable?) ")"))
    ;; Everything else asks the VALUE how it writes itself.
    ;;
    ;; The branches above are core's own types, and core knowing its own
    ;; internals is not a coupling. Anything a LIBRARY adds is a different
    ;; matter: a `(flint.rt/table? x)` here would mean the printer -- which
    ;; every program links -- had to know about a type most programs never use,
    ;; which is what a protocol exists to stop. `#flint/table` therefore lives
    ;; in `flint.table` and arrives here as an implementation, so a program that
    ;; never requires that namespace never carries the branch or the builtin
    ;; behind it.
    ;;
    ;; TWO HOOKS, not one with a flag. Printing as DATA and printing for a
    ;; PERSON are different jobs -- one has to read back, the other has to be
    ;; legible -- and an implementation that branches on a boolean is two
    ;; functions wearing one name. The split also means an implementation can
    ;; supply only the data half, which is the common case, and `print-human`
    ;; falls back to it.
    ;;
    ;; `find-protocol-method` rather than the generated `print-data`, because a
    ;; miss here is a FALLBACK and not an error: an unprintable value should
    ;; print as one rather than throw out of `str`. Metadata is consulted first,
    ;; which is `0005`'s primary mechanism -- so one value can carry its own
    ;; printer without its kind having one.
    :else (let [f (if readable?
                    (find-protocol-method Printable__impls :clojure.core/print-data x)
                    (or (find-protocol-method Printable__impls :clojure.core/print-human x)
                        (find-protocol-method Printable__impls :clojure.core/print-data x)))]
            (if f (f x) "#<unprintable>"))))

(defn pr-str [x] (pr-str* x true))

(defn prn-str [x] (flint.rt/str2 (pr-str x) "\n"))

;; ------------------------------------------------------------------- regexes
;;
;; These forward to `flint.regex`, which is cljc and therefore only linked into
;; a program that actually reaches one of them.

(defn re-pattern [s] (if (flint.regex/pattern? s) s (flint.regex/pattern s)))
(defn re-find
  ([re s] (flint.regex/re-find (re-pattern re) s))
  ([re s from] (flint.regex/re-find (re-pattern re) s from)))
(defn re-matches [re s] (flint.regex/re-matches (re-pattern re) s))
(defn re-seq [re s] (flint.regex/re-seq (re-pattern re) s))
(defn re-quote-replacement [s] s)

;; ------------------------------------------------------------------- delays

(defmacro delay [& body] (list 'flint.rt/delay (cons 'fn* (cons [] body))))
(defn force [x] (if (flint.rt/delay? x) (flint.rt/deref x) x))
(defn delay? [x] (flint.rt/delay? x))
(defn realized? [x] (flint.rt/realized? x))

;; ------------------------------------------------------------- multimethods
;;
;; No hierarchies: dispatch is `=` on the dispatch value, with `:default` as the
;; fallback. `derive`/`isa?`/`prefer-method` are absent and listed as such.
;;
;; The method table is a separate var rather than metadata on the function,
;; because a flint closure has nowhere to put metadata.

(defmacro defmulti [name dispatch-fn & _opts]
  (let [tbl (if (namespace name)
              (symbol (namespace name) (str (clojure.core/name name) "__methods"))
              (symbol (str (clojure.core/name name) "__methods")))]
    (list 'do
          (list 'def tbl (list 'clojure.core/atom {}))
          (list 'def name
                (list 'clojure.core/let ['dfn dispatch-fn]
                      (list 'clojure.core/fn ['& 'args]
                            (list 'clojure.core/let
                                  ['dv (list 'clojure.core/apply 'dfn 'args)]
                                  (list 'clojure.core/let
                                        ['f (list 'clojure.core/or
                                                  (list 'clojure.core/get
                                                        (list 'clojure.core/deref tbl) 'dv)
                                                  (list 'clojure.core/get
                                                        (list 'clojure.core/deref tbl) :default))]
                                        (list 'if 'f
                                              (list 'clojure.core/apply 'f 'args)
                                              (list 'throw
                                                    (list 'clojure.core/ex-info
                                                          "no method for dispatch value"
                                                          (list 'clojure.core/hash-map
                                                                :multi (list 'quote name)
                                                                :dispatch-value 'dv))))))))))))

(defmacro defmethod [name dispatch-val & fn-tail]
  (let [tbl (if (namespace name)
              (symbol (namespace name) (str (clojure.core/name name) "__methods"))
              (symbol (str (clojure.core/name name) "__methods")))]
    (list 'clojure.core/reset! tbl
          (list 'clojure.core/assoc (list 'clojure.core/deref tbl) dispatch-val
                (cons 'clojure.core/fn fn-tail)))))

(defn methods-of [tbl] @tbl)

;; ---------------------------------------------------------- unchecked & misc

(defn unchecked-add [a b] (flint.rt/unchecked-add a b))
(defn unchecked-subtract [a b] (flint.rt/unchecked-sub a b))
(defn unchecked-multiply [a b] (flint.rt/unchecked-mul a b))
(defn unchecked-inc [a] (flint.rt/unchecked-add a 1))
(defn unchecked-dec [a] (flint.rt/unchecked-sub a 1))
(defn unchecked-negate [a] (flint.rt/unchecked-sub 0 a))
(defn bit-and-not [a b] (flint.rt/bit-and a (flint.rt/bit-not b)))

(defn any? [_] true)
(defn NaN? [x] (and (float? x) (not (flint.rt/num-eq x x))))
(defn infinite? [x] (and (float? x) (not (NaN? x)) (or (> x 1.7976931348623157E308)
                                                       (< x -1.7976931348623157E308))))
(defn parse-boolean [s] (cond (= s "true") true (= s "false") false :else nil))
(defn map-entry? [x] (flint.rt/map-entry? x))
(defn simple-ident? [x] (and (ident? x) (nil? (namespace x))))
(defn qualified-ident? [x] (and (ident? x) (some? (namespace x))))
(defn bounded-count [n coll] (if (counted? coll) (count coll) (count (take n coll))))
(defn unreduced [x] (if (reduced? x) (nth x 0) x))
(defn ensure-reduced [x] (if (reduced? x) x (reduced x)))
(defn chunked-seq? [_] false)
(defn record? [_] false)
(defn reversible? [x] (vector? x))
(defn rseq [v] (when (seq v) (map (fn [i] (nth v i)) (range (dec (count v)) -1 -1))))

(defn repeatedly
  ([f] (lazy-seq (cons (f) (repeatedly f))))
  ([n f] (take n (repeatedly f))))

(defn reductions
  ([f coll] (let [s (seq coll)] (if s (reductions f (first s) (rest s)) (list (f)))))
  ([f init coll]
   (cons init (lazy-seq (let [s (seq coll)]
                          (when s (reductions f (f init (first s)) (rest s))))))))

(defn replace [smap coll]
  (if (vector? coll)
    (mapv (fn [x] (if (contains? smap x) (get smap x) x)) coll)
    (map (fn [x] (if (contains? smap x) (get smap x) x)) coll)))

(defmacro lazy-cat [& colls]
  (cons 'clojure.core/concat (map2 (fn [c] (list 'clojure.core/lazy-seq c)) colls)))

(defn swap-vals!
  ([a f] (let [old @a] [old (reset! a (f old))]))
  ([a f x] (let [old @a] [old (reset! a (f old x))])))
(defn reset-vals! [a v] (let [old @a] [old (reset! a v)]))

(defn pop! [t] (flint.rt/pop! t))
(defn disj! [t x] (flint.rt/dissoc! t x))

(defn hash-ordered-coll [coll] (hash (vec coll)))
(defn hash-unordered-coll [coll] (hash (set coll)))

(defn print-str [& xs] (join-with* " " xs false))

(defn- fixed6
  "A double with exactly six decimal places, which is what %f means."
  [x]
  (let [neg? (< x 0)
        v (if neg? (- x) x)
        scaled (flint.rt/to-long (flint.rt/floor (flint.rt/add (flint.rt/mul v 1000000.0) 0.5)))
        whole (quot scaled 1000000)
        frac (rem scaled 1000000)
        fs (flint.rt/num->str frac)
        pad (subs "000000" 0 (- 6 (count fs)))]
    (str (if neg? "-" "") (flint.rt/num->str whole) "." pad fs)))

(defn format
  "A small subset of `format`: %s, %d, %f and %% only. Anything else is left
  alone rather than guessed at. %f prints six decimal places, as Java's does;
  very large magnitudes lose precision, which Java's does not."
  [fmt & args]
  (loop [acc [] i 0 as (seq args)]
    (if (>= i (count fmt))
      (flint.rt/str-join acc)
      (let [c (nth fmt i)]
        (if (and (= c "%") (< (inc i) (count fmt)))
          (let [d (nth fmt (inc i))]
            (cond
              (= d "%") (recur (conj acc "%") (+ i 2) as)
              (or (= d "s") (= d "d")) (recur (conj acc (str (first as))) (+ i 2) (next as))
              (= d "f") (recur (conj acc (fixed6 (double (first as)))) (+ i 2) (next as))
              :else (recur (conj acc c) (inc i) as)))
          (recur (conj acc c) (inc i) as))))))

;; ---------------------------------------------------------------- volatiles
;;
;; A volatile is an atom without the ceremony; flint is single threaded, so the
;; distinction is only about intent. The compiler leans on them heavily.

(defn volatile! [x] (flint.rt/volatile x))
(defn vreset! [v x] (flint.rt/reset! v x))
(defn volatile? [x] (flint.rt/volatile? x))

;; --------------------------------------------------------- opaque values
;;
;; `doc/decisions/0022`. Clojure's unique-sentinel idiom is `(Object.)` --
;; how you tell ABSENT from present-and-nil, how a library gets a key nobody
;; can collide with, how a protocol keeps a private marker. flint has no host
;; classes, so it had no way to say it.

(defn opaque
  "A value equal only to itself. `(opaque)` twice gives two different values.

  The optional label is for PRINTING and plays no part in identity: two opaque
  values with the same label are still distinct, which is the point.

  Minting one grants nothing -- see 0022. Anyone can, so possession of *an*
  opaque value is never authority; only the host recognising a specific one is."
  ([] (flint.rt/opaque nil))
  ([label] (flint.rt/opaque label)))

(defn opaque? [x] (flint.rt/opaque? x))

(defn opaque-label
  "The label an opaque value was given, or nil. Printing only."
  [x]
  (flint.rt/opaque-label x))
(defn vswap!
  ([v f] (flint.rt/reset! v (f (flint.rt/deref v))))
  ([v f a] (flint.rt/reset! v (f (flint.rt/deref v) a)))
  ([v f a b] (flint.rt/reset! v (f (flint.rt/deref v) a b)))
  ([v f a b & more] (flint.rt/reset! v (apply2 f (cons (flint.rt/deref v) (cons a (cons b more)))))))

;; -------------------------------------------------------------------- gensym
;;
;; Deterministic: the counter starts at 1 in every process. That is not just
;; tidiness -- the self-hosting fixpoint test compares compiler output byte for
;; byte, and a gensym that leaked into a constant would make two identical
;; compilations differ.

(def ^:private gensym-counter (atom 0))

(defn gensym
  ([] (gensym "G__"))
  ([prefix] (reset! gensym-counter (inc @gensym-counter))
            (symbol (flint.rt/str2 (str prefix) (flint.rt/num->str @gensym-counter)))))

;; ------------------------------------------------------------------- strings

(defn str-join [xs] (flint.rt/str-join xs))
(defn str-bytes [s] (flint.rt/str-bytes s))
(defn bytes->str [bs] (flint.rt/bytes->str bs))

;; ---------------------------------------------------------------- interop-free
;;
;; `read-string` and `eval` do not exist: a flint module carries no compiler.
;; `clojure.edn/read-string` is the way to turn text into data.

(defn ->str-builder [] (volatile! []))
(defn sb-append! [sb s] (vswap! sb conj s) sb)
(defn sb-str [sb] (flint.rt/str-join @sb))
(defn println-str [& xs] (flint.rt/str2 (join-with* " " xs false) "\n"))

;; --------------------------------------------------------------- protocols
;;
;; Protocols are the basis for polymorphism here, and they work differently from
;; Clojure's for a reason that is not a shortcut: **flint has no types.** There
;; is no deftype, no defrecord, no class. So "which type is this?" has no general
;; answer, and dispatch has two roads:
;;
;; * **built-in kinds** -- a small closed set (`kind` below) covering nil,
;;   booleans, numbers, strings, keywords, symbols, vectors, maps, sets, lists,
;;   functions and ports;
;; * **metadata** -- for everything a user defines, because there is nothing else
;;   a user-defined abstraction can *be*. Clojure has this as
;;   `extend-via-metadata`, opt-in and slightly out of the way. **Here it is the
;;   main road**, and it is the one to reach for.
;;
;; A method attached by metadata is keyed by the method's fully-qualified
;; keyword, exactly as Clojure's `extend-via-metadata` keys it:
;;
;;     (with-meta {:w 3 :h 4} {:shapes/area (fn [s] (* (:w s) (:h s)))})
;;
;; Not everything can carry metadata: see `meta`'s note. Inline values -- short
;; strings, keywords and chars, which live in the value word itself -- have
;; nowhere to hang a map, and neither do numbers or functions. Those dispatch by
;; kind, which is what kinds are for.

(defn kind
  "The dispatch kind of `x`: one of `:nil :boolean :number :string :keyword
  :symbol :vector :map :set :list :fn :port :thread :atom :var :regex
  :exception :other`. A closed set, because flint has no types."
  [x]
  (flint.rt/kind x))

;; PUBLIC, and it has to be: `defprotocol` EMITS a call to this into its
;; expansion, so the reference lands in whatever namespace used the macro. A
;; var that appears in an expansion cannot be private, because the caller is
;; the one who names it.
;;
;; It was `defn-` until privacy was actually enforced, at which point every
;; namespace using `defprotocol` stopped compiling. That is the mark being
;; wrong rather than the check: it had been documentation, and documentation
;; can be wrong without anything noticing.
(defn protocol-miss [pname mname x]
  (throw (ex-info (flint.rt/str-join
                   ["no implementation of " (str mname) " (protocol " (str pname)
                    ") for a value of kind " (str (kind x))
                    ". Extend the protocol to that kind, or attach "
                    (str (keyword (namespace mname) (name mname)))
                    " as metadata on the value."])
                  {:protocol pname :method mname :kind (kind x) :value x})))

(defn find-protocol-method
  "The implementation of `mkey` (a fully-qualified keyword) for `x`: metadata
  first, then the protocol's table for `x`'s kind. `nil` when there is none."
  [impls mkey x]
  (or (get (meta x) mkey)
      (get (get (deref impls) (flint.rt/kind x)) mkey)))

(defn extend
  "Give `kind` (a keyword from `kind`) implementations of a protocol's methods.
  `mmap` maps a method's fully-qualified keyword to a function."
  [protocol kind mmap]
  (swap! (:impls protocol) update kind merge mmap)
  nil)

(defn- method-key
  "The protocol's OWN key for the method named `n`.

  A method key is qualified by the namespace that DEFINED the protocol, which
  is not the namespace doing the extending. Computing it lexically at the
  extend site -- which is what `extend-protocol` did -- writes an
  implementation under a key nobody ever reads, so extending a protocol from
  another namespace was a silent no-op that surfaced later as `protocol-miss`.
  Silent is the part that made it worth a named function and this comment."
  [protocol n]
  (loop [ks (seq (:method-keys protocol))]
    (cond
      (nil? ks)
      (throw (ex-info (str "the protocol " (:flint/protocol protocol)
                           " has no method named " n "; its methods are "
                           (pr-str (mapv name (:method-keys protocol))))
                      {:protocol (:flint/protocol protocol) :method n}))
      (= n (name (first ks))) (first ks)
      :else (recur (next ks)))))

(defn extend-method
  "One method of `protocol` for one `kind`. `mname` is the method's bare name as
  a string, resolved against the protocol rather than against the caller."
  [protocol kind mname f]
  (extend protocol kind (hash-map (method-key protocol mname) f)))

(defn satisfies?
  "Does `x` have an implementation of every method of `protocol`, by metadata or
  by kind?"
  [protocol x]
  (let [m (meta x)
        bykind (get (deref (:impls protocol)) (flint.rt/kind x))]
    (every? (fn [k] (or (contains? m k) (contains? bykind k))) (:method-keys protocol))))

(defmacro defprotocol
  "Define a protocol and its methods.

      (defprotocol Shape
        \"docs\"
        (area [s] \"docs\")
        (scale [s k]))

  Each method becomes a function that dispatches on `s`: metadata first, then
  the value's built-in kind. A value with no implementation fails with a message
  naming the protocol and the kind."
  [pname & sigs]
  (let [nsname (str (:ns &env))
        sigs (remove string? sigs)
        methods (map (fn [sig]
                       (let [mname (first sig)
                             arglists (take-while vector? (rest sig))]
                         {:name mname
                          :key (keyword nsname (name mname))
                          :arglists (if (seq arglists) arglists (list (second sig)))}))
                     sigs)
        impls-sym (symbol (str (name pname) "__impls"))
        qual (symbol nsname (name pname))]
    (list* 'do
           (list 'def impls-sym (list 'clojure.core/atom {}))
           (list 'def pname
                 (list 'clojure.core/hash-map
                       :flint/protocol (list 'quote qual)
                       :impls impls-sym
                       :method-keys (vec (map :key methods))))
           (map (fn [m]
                  (list* 'defn (:name m)
                         (map (fn [args]
                                (list args
                                      (list 'clojure.core/let
                                            ['f (list 'clojure.core/find-protocol-method
                                                      impls-sym (:key m) (first args))]
                                            (list 'if 'f
                                                  (list* 'f args)
                                                  (list 'clojure.core/protocol-miss
                                                        (list 'quote qual)
                                                        (list 'quote (symbol nsname (name (:name m))))
                                                        (first args))))))
                              (:arglists m))))
                methods))))

(defmacro extend-protocol
  "Extend a protocol to one or more built-in kinds.

      (extend-protocol Shape
        :vector (area [s] (* (nth s 0) (nth s 1)))
        :map    (area [s] (* (:w s) (:h s))))"
  [pname & body]
  (let [groups (loop [xs body k nil acc []]
                 (if (empty? xs)
                   acc
                   (if (keyword? (first xs))
                     (recur (rest xs) (first xs) acc)
                     (recur (rest xs) k (conj acc [k (first xs)])))))]
    (list* 'do
           (map (fn [[k mform]]
                  (list 'clojure.core/extend-method pname k
                        (name (first mform))
                        (list* 'clojure.core/fn (rest mform))))
                groups))))
