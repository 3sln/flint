(ns flint.rt
  "Host implementations of the runtime builtins.

  `flint.rt/x` is not an ordinary namespace when flint compiles it: the analyzer
  intercepts those symbols and emits a direct call into the wasm table, so this
  file is never compiled into a module. It exists so that the SAME source runs on
  a host -- babashka running the compiler, and `flint.eval` running a macro body.

  Two implementations of one contract is a drift risk, so keep them together:
  every function here corresponds to an entry in `builtins::CATALOGUE`, and
  `test/builtin_parity.clj` asserts the two agree."
  (:refer-clojure :exclude [= conj get assoc dissoc disj count first rest next seq cons nth pop peek apply lazy-seq subs
                            empty contains? name namespace meta with-meta atom deref reset!
                            transient persistent! conj! assoc! dissoc! hash compare quot rem
                            nil? number? int? float? string? keyword? symbol? vector? map? set?
                            seq? fn? boolean? sequential? identical? bit-and bit-or bit-xor bit-not
                            bit-shift-left bit-shift-right unsigned-bit-shift-right bit-test])
  (:require [clojure.string :as cstr]))

(def = clojure.core/=)
(def identical? clojure.core/identical?)
(def hash clojure.core/hash)
(def compare clojure.core/compare)

(def add clojure.core/+)
(def sub clojure.core/-)
(def mul clojure.core/*)
(defn div [& xs] (let [r (clojure.core/apply clojure.core// xs)] (if (ratio? r) (double r) r)))
(def quot clojure.core/quot)
(def rem clojure.core/rem)
(def lt clojure.core/<)
(def le clojure.core/<=)
(def gt clojure.core/>)
(def ge clojure.core/>=)
(def num-eq clojure.core/==)

(def bit-and clojure.core/bit-and)
(def bit-or clojure.core/bit-or)
(def bit-xor clojure.core/bit-xor)
(def bit-not clojure.core/bit-not)
(def bit-shift-left clojure.core/bit-shift-left)
(def bit-shift-right clojure.core/bit-shift-right)
(def unsigned-bit-shift-right clojure.core/unsigned-bit-shift-right)
(def bit-test clojure.core/bit-test)

(def nil? clojure.core/nil?)
(def number? clojure.core/number?)
(def int? clojure.core/int?)
(def float? clojure.core/float?)
(def string? clojure.core/string?)
(def keyword? clojure.core/keyword?)
(def symbol? clojure.core/symbol?)
(def vector? clojure.core/vector?)
(def map? clojure.core/map?)
(def set? clojure.core/set?)
(def seq? clojure.core/seq?)
(def fn? clojure.core/fn?)
(def boolean? clojure.core/boolean?)
(def sequential? clojure.core/sequential?)

;; flint has no char type. On a host, indexing or seq-ing a string yields
;; Characters, so every one of those has to be normalised to a one-character
;; string here -- otherwise compiler code that compares against "(" silently
;; sees \( and takes the wrong branch, which is a miserable bug to find.
(defn- c->s [v] (if (clojure.core/char? v) (clojure.core/str v) v))

(def count clojure.core/count)
(defn first [c] (c->s (clojure.core/first c)))
(defn rest [c] (if (clojure.core/string? c) (clojure.core/map c->s (clojure.core/rest c)) (clojure.core/rest c)))
(defn next [c] (if (clojure.core/string? c)
                 (clojure.core/seq (clojure.core/map c->s (clojure.core/rest c)))
                 (clojure.core/next c)))
(defn seq [c] (if (clojure.core/string? c)
                (clojure.core/seq (clojure.core/map c->s c))
                (clojure.core/seq c)))
(def cons clojure.core/cons)
(def conj clojure.core/conj)
(defn get ([c k] (c->s (clojure.core/get c k))) ([c k d] (c->s (clojure.core/get c k d))))
(def assoc clojure.core/assoc)
(def dissoc clojure.core/dissoc)
(def disj clojure.core/disj)
(def contains? clojure.core/contains?)
(defn nth ([c i] (c->s (clojure.core/nth c i))) ([c i d] (c->s (clojure.core/nth c i d))))
(def pop clojure.core/pop)
(defn peek [c] (c->s (clojure.core/peek c)))
(def empty clojure.core/empty)

(def transient clojure.core/transient)
(def persistent! clojure.core/persistent!)
(def conj! clojure.core/conj!)
(def assoc! clojure.core/assoc!)
(def dissoc! clojure.core/dissoc!)

(defn str2 [a b] (clojure.core/str a b))
(def name clojure.core/name)
(def namespace clojure.core/namespace)
(defn keyword2 ([n] (clojure.core/keyword n)) ([ns n] (clojure.core/keyword ns n)))
(defn symbol2 ([n] (clojure.core/symbol n)) ([ns n] (clojure.core/symbol ns n)))
(defn subs ([s a] (clojure.core/subs s a)) ([s a b] (clojure.core/subs s a b)))
(defn num->str [n] (clojure.core/str n))
(defn str->num [s]
  (let [t (cstr/trim s)]
    (cond
      (clojure.core/= t "##Inf") ##Inf
      (clojure.core/= t "##-Inf") ##-Inf
      (clojure.core/= t "##NaN") ##NaN
      :else (or (clojure.core/parse-long t) (clojure.core/parse-double t)))))
(defn code-point-at [s i] (clojure.core/int (clojure.core/nth (clojure.core/str s) i)))
(defn from-code-point [c] (clojure.core/str (clojure.core/char c)))
(defn str-join [xs] (clojure.core/apply clojure.core/str xs))
(defn str-index-of
  ([s v] (cstr/index-of s v))
  ([s v from] (cstr/index-of s v from)))
(defn str-bytes [s] (mapv #(clojure.core/bit-and (clojure.core/int %) 0xff) (.getBytes ^String s "UTF-8")))
(defn double-bits [d] (Double/doubleToRawLongBits (double d)))

(defn ex-info
  ([m] (clojure.core/ex-info m {}))
  ([m d] (clojure.core/ex-info m (or d {})))
  ([m d c] (clojure.core/ex-info m (or d {}) c)))
(def ex-message clojure.core/ex-message)
(def ex-data clojure.core/ex-data)
(defn ex-kind [e] (if (instance? clojure.lang.ExceptionInfo e) "ExceptionInfo" "Throwable"))

(def atom clojure.core/atom)
(def deref clojure.core/deref)
(def reset! clojure.core/reset!)
(defn volatile [x] (clojure.core/volatile! x))
(defn volatile? [x] (instance? clojure.lang.Volatile x))

(def meta clojure.core/meta)
(def with-meta clojure.core/with-meta)

(defn lazy-seq [f] (clojure.core/lazy-seq (f)))
(defn apply [f args] (clojure.core/apply f args))
(defn array-map [kvs] (clojure.core/apply clojure.core/array-map kvs))
(defn range3 [s e st] (if e (clojure.core/range s e st) (clojure.core/iterate #(clojure.core/+ % st) s)))
(defn gc-stats [] {})
