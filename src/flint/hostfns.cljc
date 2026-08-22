(ns flint.hostfns
  "Host implementations of the Rust builtins, for compile-time evaluation.

  When `flint.eval` runs a macro function it hits `:native` nodes -- the macro
  called `conj`, or `str`, or `=`. Those cannot dispatch into wasm at compile
  time, so they dispatch here instead, onto whatever Clojure is hosting the
  compiler. Because macros manipulate *forms*, and forms are ordinary Clojure
  data on every host, this is exactly the right semantics rather than a
  shortcut: `conj` on babashka and `conj` on flint agree on what conj means.

  Anything missing here simply cannot be called from a macro body, and says so."
  (:require [clojure.string :as str]))

(defn- nyi [name]
  (fn [& _] (throw (ex-info (str "builtin `" name "` is not available at compile time")
                            {:builtin name :type :compile}))))

(def table
  {"=" =
   "identical?" identical?
   "hash" hash
   "compare" compare
   "flint/add" +
   "flint/sub" -
   "flint/mul" *
   "flint/div" (fn [& xs] (let [r (apply / xs)] (if (ratio? r) (double r) r)))
   "quot" quot
   "rem" rem
   "flint/lt" <
   "flint/le" <=
   "flint/gt" >
   "flint/ge" >=
   "flint/num-eq" ==
   "nil?" nil?
   "number?" number?
   "int?" int?
   "float?" float?
   "string?" string?
   "keyword?" keyword?
   "symbol?" symbol?
   "vector?" vector?
   "map?" map?
   "set?" set?
   "seq?" seq?
   "fn?" fn?
   "boolean?" boolean?
   "sequential?" sequential?
   "count" count
   "first" first
   "rest" rest
   "next" next
   "seq" seq
   "cons" cons
   "conj" conj
   "get" get
   "assoc" assoc
   "dissoc" dissoc
   "disj" disj
   "contains?" contains?
   "nth" nth
   "pop" pop
   "peek" peek
   "empty" empty
   "transient" transient
   "persistent!" persistent!
   "conj!" conj!
   "assoc!" assoc!
   "dissoc!" dissoc!
   "flint/str2" (fn [a b] (str a b))
   "name" name
   "namespace" namespace
   "flint/keyword2" (fn ([n] (keyword n)) ([ns n] (keyword ns n)))
   "flint/symbol2" (fn ([n] (symbol n)) ([ns n] (symbol ns n)))
   "flint/subs" (fn ([s a] (subs s a)) ([s a b] (subs s a b)))
   "flint/num->str" (fn [n] (str n))
   "flint/str->num" (fn [s] (try (or (parse-long (str/trim s)) (parse-double (str/trim s)))
                                 (catch Exception _ nil)))
   "flint/code-point-at" (fn [s i] (int (nth s i)))
   "flint/from-code-point" (fn [c] (str (char c)))
   "ex-info" (fn ([m] (ex-info m {})) ([m d] (ex-info m (or d {}))) ([m d c] (ex-info m (or d {}) c)))
   "ex-message" ex-message
   "ex-data" ex-data
   "flint/ex-kind" (fn [e] (if (instance? clojure.lang.ExceptionInfo e) "ExceptionInfo" "Throwable"))
   "atom" atom
   "deref" deref
   "reset!" reset!
   "meta" meta
   "with-meta" with-meta
   "flint/apply" (fn [f args] (apply f args))
   "flint/lazy-seq" (fn [f] (lazy-seq (f)))
   "flint/range3" (fn [s e st] (if e (range s e st) (iterate #(+ % st) s)))
   "flint/gc-stats" (nyi "flint/gc-stats")})

(defn lookup [name]
  (or (get table name) (nyi name)))
