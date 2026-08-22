(ns clojure.core
  "flint's clojure.core.

  Written in cljc on top of the Rust primitives, for the reason in
  doc/decisions/0002: a cljc function tree-shakes per var, so a program that
  never calls `partition-by` does not carry it. A var whose whole body is one
  `flint.rt/x` call is detected by the compiler and called directly, so the
  wrapper layer costs nothing at the call site.")

;; ---------------------------------------------------------------- primitives

(defn identity [x] x)
(defn not [x] (if x false true))

(defn nil? [x] (flint.rt/nil? x))
(defn some? [x] (if (flint.rt/nil? x) false true))
(defn true? [x] (flint.rt/identical? x true))
(defn false? [x] (flint.rt/identical? x false))
(defn boolean? [x] (flint.rt/boolean? x))
(defn boolean [x] (if x true false))

(defn = [a b] (flint.rt/= a b))
(defn not= [a b] (not (flint.rt/= a b)))
(defn identical? [a b] (flint.rt/identical? a b))
(defn hash [x] (flint.rt/hash x))
(defn compare [a b] (flint.rt/compare a b))

(defn number? [x] (flint.rt/number? x))
(defn int? [x] (flint.rt/int? x))
(defn integer? [x] (flint.rt/int? x))
(defn float? [x] (flint.rt/float? x))
(defn double? [x] (flint.rt/float? x))
(defn string? [x] (flint.rt/string? x))
(defn keyword? [x] (flint.rt/keyword? x))
(defn symbol? [x] (flint.rt/symbol? x))
(defn vector? [x] (flint.rt/vector? x))
(defn map? [x] (flint.rt/map? x))
(defn set? [x] (flint.rt/set? x))
(defn seq? [x] (flint.rt/seq? x))
(defn fn? [x] (flint.rt/fn? x))
(defn ifn? [x] (if (flint.rt/fn? x) true (if (flint.rt/keyword? x) true
                                            (if (flint.rt/map? x) true
                                                (if (flint.rt/set? x) true
                                                    (flint.rt/vector? x))))))
(defn sequential? [x] (flint.rt/sequential? x))
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
(defn atom [x] (flint.rt/atom x))
(defn deref [x] (flint.rt/deref x))
(defn reset! [a v] (flint.rt/reset! a v))
(defn ex-message [e] (flint.rt/ex-message e))
(defn ex-data [e] (flint.rt/ex-data e))

(defn transient [c] (flint.rt/transient c))
(defn persistent! [c] (flint.rt/persistent! c))
(defn conj! [c x] (flint.rt/conj! c x))
(defn assoc! [c k v] (flint.rt/assoc! c k v))
(defn dissoc! [c k] (flint.rt/dissoc! c k))

(defn list [& xs] (if (nil? xs) '() xs))
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
  ([x y & more] (concat x (concat y (apply2 concat more)))))

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
   (loop [acc init s (seq coll)]
     (if s
       (let [acc' (f acc (first s))]
         (if (reduced? acc') (deref acc') (recur acc' (next s))))
       acc))))

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

(defn < ([a b] (flint.rt/lt a b)) ([a b & more] (cmp-chain flint.rt/lt a b more)))
(defn <= ([a b] (flint.rt/le a b)) ([a b & more] (cmp-chain flint.rt/le a b more)))
(defn > ([a b] (flint.rt/gt a b)) ([a b & more] (cmp-chain flint.rt/gt a b more)))
(defn >= ([a b] (flint.rt/ge a b)) ([a b & more] (cmp-chain flint.rt/ge a b more)))
(defn == ([a b] (flint.rt/num-eq a b)) ([a b & more] (cmp-chain flint.rt/num-eq a b more)))

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

(defn subs
  ([s start] (flint.rt/subs s start))
  ([s start end] (flint.rt/subs s start end)))

(defn keyword
  ([n] (if (keyword? n) n (flint.rt/keyword2 n)))
  ([ns n] (flint.rt/keyword2 ns n)))
(defn symbol
  ([n] (if (symbol? n) n (flint.rt/symbol2 n)))
  ([ns n] (flint.rt/symbol2 ns n)))

(defn parse-long [s] (let [v (flint.rt/str->num s)] (if (int? v) v nil)))
(defn parse-double [s] (let [v (flint.rt/str->num s)] (if (number? v) (flint.rt/add v 0.0) nil)))

(defn int [x] (if (int? x) x (flint.rt/quot x 1)))
(defn double [x] (flint.rt/add x 0.0))
(defn long [x] (int x))

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

(defmacro some-> [expr & forms]
  (let [g 'some__g]
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
   (let [b (first bindings) v (second bindings) g 'iflet__g]
     (list 'let* [g v]
           (list 'if g (list 'let* [b g] then) else)))))

(defmacro when-let [bindings & body]
  (list 'clojure.core/if-let bindings (cons 'do body) nil))

(defmacro if-some
  ([bindings then] (list 'clojure.core/if-some bindings then nil))
  ([bindings then else]
   (let [b (first bindings) v (second bindings) g 'ifsome__g]
     (list 'let* [g v]
           (list 'if (list 'clojure.core/nil? g) else (list 'let* [b g] then))))))

(defmacro when-some [bindings & body]
  (list 'clojure.core/if-some bindings (cons 'do body) nil))

(defmacro when-first [bindings & body]
  (let [b (first bindings) v (second bindings)]
    (list 'clojure.core/when-let ['when__s (list 'clojure.core/seq v)]
          (list 'let* [b (list 'clojure.core/first 'when__s)] (cons 'do body)))))

(defmacro doto [x & forms]
  (list 'let* ['doto__g x]
        (cons 'do (map2 (fn [f]
                          (if (seq? f)
                            (cons (first f) (cons 'doto__g (rest f)))
                            (list f 'doto__g)))
                        forms))
        'doto__g))

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
  (let [i (first bindings) n (second bindings)]
    (list 'let* ['dotimes__n n]
          (list 'loop* [i 0]
                (list 'if (list 'clojure.core/< i 'dotimes__n)
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

(defmacro doseq [bindings & body]
  (let [b (first bindings) v (second bindings)]
    (list 'loop* ['doseq__s (list 'clojure.core/seq v)]
          (list 'if 'doseq__s
                (cons 'do
                      (concat (list (list 'let* [b (list 'clojure.core/first 'doseq__s)]
                                          (cons 'do body)))
                              (list (list 'recur (list 'clojure.core/next 'doseq__s)))))
                nil))))

(defmacro for [bindings body]
  ;; A single binding pair with optional :when/:while, which is what most `for`
  ;; uses look like. The general nested form is listed as missing in the README.
  (let [b (first bindings) v (second bindings) opts (apply hash-map (nnext bindings))]
    (list 'clojure.core/keep2
          (list 'fn* [b] (if (:when opts) (list 'if (:when opts) body nil) body))
          v)))

;; ---------------------------------------------------------- higher order fns

(defn map
  ([f coll] (map2 f coll))
  ([f c1 c2] (lazy-seq (let [s1 (seq c1) s2 (seq c2)]
                         (when (and s1 s2)
                           (cons (f (first s1) (first s2))
                                 (map f (rest s1) (rest s2))))))))

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

(defn mapcat [f coll] (apply2 concat (map2 f coll)))

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
  ([f g h] (fn [& args] (f (g (apply2 h args))))))

(defn partial
  ([f a] (fn [& args] (apply2 f (cons a args))))
  ([f a b] (fn [& args] (apply2 f (cons a (cons b args))))))

(defn constantly [x] (fn [& _] x))
(defn complement [f] (fn [& args] (not (apply2 f args))))

(defn juxt
  ([f g] (fn [& args] [(apply2 f args) (apply2 g args)]))
  ([f g h] (fn [& args] [(apply2 f args) (apply2 g args) (apply2 h args)])))

(defn swap!
  ([a f] (reset! a (f (deref a))))
  ([a f x] (reset! a (f (deref a) x)))
  ([a f x y] (reset! a (f (deref a) x y)))
  ([a f x y & more] (reset! a (apply2 f (cons (deref a) (cons x (cons y more)))))))

(defn ex-info
  ([msg] (flint.rt/ex-info msg nil))
  ([msg data] (flint.rt/ex-info msg data))
  ([msg data cause] (flint.rt/ex-info msg data cause)))

(defn keys [m] (map2 first m))
(defn vals [m] (map2 second m))
(defn key [e] (flint.rt/nth e 0))
(defn val [e] (flint.rt/nth e 1))

(defn merge [& maps]
  (reduce (fn [a b] (if (nil? b) a (reduce (fn [m e] (assoc m (key e) (val e))) a b)))
          (first maps) (rest maps)))

(defn select-keys [m ks]
  (reduce (fn [acc k] (if (contains? m k) (assoc acc k (get m k)) acc)) {} ks))

(defn update
  ([m k f] (assoc m k (f (get m k))))
  ([m k f a] (assoc m k (f (get m k) a)))
  ([m k f a b] (assoc m k (f (get m k) a b))))

(defn get-in [m ks] (reduce (fn [acc k] (if (nil? acc) nil (get acc k))) m ks))
(defn assoc-in [m ks v]
  (let [k (first ks)]
    (if (next ks)
      (assoc m k (assoc-in (get m k) (next ks) v))
      (assoc m k v))))
(defn update-in [m ks f] (assoc-in m ks (f (get-in m ks))))

(defn frequencies [coll]
  (persistent! (reduce (fn [m x] (assoc! m x (inc (get m x 0)))) (transient {}) coll)))

(defn group-by [f coll]
  (reduce (fn [m x] (let [k (f x)] (assoc m k (conj (get m k []) x)))) {} coll))

(defn distinct [coll]
  (loop [acc [] seen #{} s (seq coll)]
    (if s
      (let [x (first s)]
        (if (contains? seen x)
          (recur acc seen (next s))
          (recur (conj acc x) (conj seen x) (next s))))
      (seq acc))))

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

(defn- merge-sort [cmp v]
  (let [n (count v)]
    (if (< n 2)
      (seq v)
      (let [mid (quot n 2)
            a (merge-sort cmp (subvec2 v 0 mid))
            b (merge-sort cmp (subvec2 v mid n))]
        (loop [acc [] a a b b]
          (cond
            (nil? (seq a)) (seq (into acc b))
            (nil? (seq b)) (seq (into acc a))
            (<= (cmp (first a) (first b)) 0) (recur (conj acc (first a)) (next a) b)
            :else (recur (conj acc (first b)) a (next b))))))))

(defn subvec2 [v start end]
  (loop [acc [] i start] (if (< i end) (recur (conj acc (nth v i)) (inc i)) acc)))
(defn subvec
  ([v start] (subvec2 v start (count v)))
  ([v start end] (subvec2 v start end)))

(defn sort-by
  ([kf coll] (merge-sort (fn [a b] (compare (kf a) (kf b))) (vec coll)))
  ([kf cmp coll] (let [c (as-comparator cmp)]
                   (merge-sort (fn [a b] (c (kf a) (kf b))) (vec coll)))))

(defn partition
  ([n coll] (partition n n coll))
  ([n step coll]
   (lazy-seq (let [s (seq coll)]
               (when s
                 (let [p (vec (take n s))]
                   (when (= n (count p))
                     (cons (seq p) (partition n step (drop step s))))))))))

(defn partition-all
  ([n coll] (partition-all n n coll))
  ([n step coll]
   (lazy-seq (let [s (seq coll)]
               (when s (cons (seq (vec (take n s))) (partition-all n step (drop step s))))))))

(defn iterate [f x] (lazy-seq (cons x (iterate f (f x)))))

(defn zipmap [ks vs]
  (loop [m {} ks (seq ks) vs (seq vs)]
    (if (and ks vs) (recur (assoc m (first ks) (first vs)) (next ks) (next vs)) m)))

(defn count-matching [pred coll] (reduce (fn [n x] (if (pred x) (inc n) n)) 0 coll))

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

(defn- join-with [sep xs]
  (loop [acc "" s (seq xs) first? true]
    (if s
      (recur (flint.rt/str2 (if first? acc (flint.rt/str2 acc sep)) (pr-str (first s)))
             (next s) false)
      acc)))

(defn- join-entries [m]
  (loop [acc "" s (seq m) first? true]
    (if s
      (let [e (first s)]
        (recur (flint.rt/str2 (if first? acc (flint.rt/str2 acc ", "))
                              (flint.rt/str2 (pr-str (key e))
                                             (flint.rt/str2 " " (pr-str (val e)))))
               (next s) false))
      acc)))

(defn pr-str [x]
  (cond
    (nil? x) "nil"
    (true? x) "true"
    (false? x) "false"
    (string? x) (escape-string x)
    (number? x) (flint.rt/num->str x)
    (keyword? x) (flint.rt/str2 ":" (kw-or-sym-str x))
    (symbol? x) (kw-or-sym-str x)
    (vector? x) (flint.rt/str2 "[" (flint.rt/str2 (join-with " " x) "]"))
    (set? x) (flint.rt/str2 "#{" (flint.rt/str2 (join-with " " x) "}"))
    (map? x) (flint.rt/str2 "{" (flint.rt/str2 (join-entries x) "}"))
    (seq? x) (flint.rt/str2 "(" (flint.rt/str2 (join-with " " x) ")"))
    (sequential? x) (flint.rt/str2 "(" (flint.rt/str2 (join-with " " x) ")"))
    :else "#<unprintable>"))

(defn prn-str [x] (flint.rt/str2 (pr-str x) "\n"))

;; ---------------------------------------------------------------- volatiles
;;
;; A volatile is an atom without the ceremony; flint is single threaded, so the
;; distinction is only about intent. The compiler leans on them heavily.

(defn volatile! [x] (flint.rt/volatile x))
(defn vreset! [v x] (flint.rt/reset! v x))
(defn volatile? [x] (flint.rt/volatile? x))
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

;; ---------------------------------------------------------------- interop-free
;;
;; `read-string` and `eval` do not exist: a flint module carries no compiler.
;; `clojure.edn/read-string` is the way to turn text into data.

(defn ->str-builder [] (volatile! []))
(defn sb-append! [sb s] (vswap! sb conj s) sb)
(defn sb-str [sb] (flint.rt/str-join @sb))
(defn println-str [& xs] (flint.rt/str2 (join-with " " xs) "\n"))
