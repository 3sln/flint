(ns flint.hostfns
  "Builtin implementations for compile-time evaluation.

  When `flint.eval` runs a macro body it hits `:native` nodes -- the macro called
  `conj`, or `str`, or `=`. Those cannot dispatch into the wasm table at compile
  time, so they dispatch here.

  Every entry goes through `flint.rt`, which is the one place a builtin has both
  a host implementation and a wasm one. That keeps this table from becoming a
  third implementation that drifts from the other two: on babashka these are the
  host shims, and on flint the analyzer turns each into a native function value."
  (:require [flint.rt]))

(def table
  {"=" flint.rt/=
   "identical?" flint.rt/identical?
   "hash" flint.rt/hash
   "compare" flint.rt/compare
   "flint/add" flint.rt/add
   "flint/sub" flint.rt/sub
   "flint/mul" flint.rt/mul
   "flint/div" flint.rt/div
   "quot" flint.rt/quot
   "rem" flint.rt/rem
   "flint/lt" flint.rt/lt
   "flint/le" flint.rt/le
   "flint/gt" flint.rt/gt
   "flint/ge" flint.rt/ge
   "flint/num-eq" flint.rt/num-eq
   "bit-and" flint.rt/bit-and
   "bit-or" flint.rt/bit-or
   "bit-xor" flint.rt/bit-xor
   "bit-not" flint.rt/bit-not
   "bit-shift-left" flint.rt/bit-shift-left
   "bit-shift-right" flint.rt/bit-shift-right
   "unsigned-bit-shift-right" flint.rt/unsigned-bit-shift-right
   "bit-test" flint.rt/bit-test
   "nil?" flint.rt/nil?
   "number?" flint.rt/number?
   "int?" flint.rt/int?
   "float?" flint.rt/float?
   "string?" flint.rt/string?
   "keyword?" flint.rt/keyword?
   "symbol?" flint.rt/symbol?
   "vector?" flint.rt/vector?
   "map?" flint.rt/map?
   "set?" flint.rt/set?
   "seq?" flint.rt/seq?
   "fn?" flint.rt/fn?
   "boolean?" flint.rt/boolean?
   "sequential?" flint.rt/sequential?
   "count" flint.rt/count
   "first" flint.rt/first
   "rest" flint.rt/rest
   "next" flint.rt/next
   "seq" flint.rt/seq
   "cons" flint.rt/cons
   "conj" flint.rt/conj
   "get" flint.rt/get
   "assoc" flint.rt/assoc
   "dissoc" flint.rt/dissoc
   "disj" flint.rt/disj
   "contains?" flint.rt/contains?
   "nth" flint.rt/nth
   "pop" flint.rt/pop
   "peek" flint.rt/peek
   "empty" flint.rt/empty
   "transient" flint.rt/transient
   "persistent!" flint.rt/persistent!
   "conj!" flint.rt/conj!
   "assoc!" flint.rt/assoc!
   "dissoc!" flint.rt/dissoc!
   "flint/str2" flint.rt/str2
   "name" flint.rt/name
   "namespace" flint.rt/namespace
   "flint/keyword2" flint.rt/keyword2
   "flint/symbol2" flint.rt/symbol2
   "flint/subs" flint.rt/subs
   "flint/num->str" flint.rt/num->str
   "flint/str->num" flint.rt/str->num
   "flint/code-point-at" flint.rt/code-point-at
   "flint/from-code-point" flint.rt/from-code-point
   "flint/str-join" flint.rt/str-join
   "flint/str-index-of" flint.rt/str-index-of
   "flint/str-bytes" flint.rt/str-bytes
   "flint/double-bits" flint.rt/double-bits
   "ex-info" flint.rt/ex-info
   "ex-message" flint.rt/ex-message
   "ex-data" flint.rt/ex-data
   "flint/ex-kind" flint.rt/ex-kind
   "atom" flint.rt/atom
   "deref" flint.rt/deref
   "reset!" flint.rt/reset!
   "flint/volatile" flint.rt/volatile
   "flint/capabilities" flint.rt/capabilities
   "flint/opaque" flint.rt/opaque
   "flint/opaque?" flint.rt/opaque?
   "flint/opaque-label" flint.rt/opaque-label
   "flint/volatile?" flint.rt/volatile?
   "meta" flint.rt/meta
   "with-meta" flint.rt/with-meta
   "flint/lazy-seq" flint.rt/lazy-seq
   "flint/apply" flint.rt/apply
   "flint/range3" flint.rt/range3
   "flint/array-map" flint.rt/array-map
   "flint/sqrt" flint.rt/sqrt
   "flint/cbrt" flint.rt/cbrt
   "flint/exp" flint.rt/exp
   "flint/expm1" flint.rt/expm1
   "flint/log" flint.rt/log
   "flint/log10" flint.rt/log10
   "flint/log1p" flint.rt/log1p
   "flint/sin" flint.rt/sin
   "flint/cos" flint.rt/cos
   "flint/tan" flint.rt/tan
   "flint/asin" flint.rt/asin
   "flint/acos" flint.rt/acos
   "flint/atan" flint.rt/atan
   "flint/sinh" flint.rt/sinh
   "flint/cosh" flint.rt/cosh
   "flint/tanh" flint.rt/tanh
   "flint/floor" flint.rt/floor
   "flint/ceil" flint.rt/ceil
   "flint/rint" flint.rt/rint
   "flint/trunc" flint.rt/trunc
   "flint/pow" flint.rt/pow
   "flint/atan2" flint.rt/atan2
   "flint/hypot" flint.rt/hypot
   "flint/signum" flint.rt/signum
   "flint/to-long" flint.rt/to-long
   "flint/map-entry?" flint.rt/map-entry?
   "flint/delay" flint.rt/delay
   "flint/realized?" flint.rt/realized?
   "flint/delay?" flint.rt/delay?
   "bytes?" flint.rt/bytes?
   "flint/b-count" flint.rt/b-count
   "flint/b-at" flint.rt/b-at
   "flint/b-concat" flint.rt/b-concat
   "flint/b-slice" flint.rt/b-slice
   "flint/str->b" flint.rt/str->b
   "flint/b->str" flint.rt/b->str
   "flint/vec->b" flint.rt/vec->b
   "flint/b->vec" flint.rt/b->vec
   "flint/b-eq?" flint.rt/b-eq?
   "flint/b-depth" flint.rt/b-depth
   "flint/b-transient" flint.rt/b-transient
   "flint/b-conj!" flint.rt/b-conj!
   "flint/b-append!" flint.rt/b-append!
   "flint/b-tcount" flint.rt/b-tcount
   "flint/b-persistent!" flint.rt/b-persistent!
   "flint/unchecked-add" flint.rt/unchecked-add
   "flint/unchecked-sub" flint.rt/unchecked-sub
   "flint/unchecked-mul" flint.rt/unchecked-mul
   "flint/fabs" flint.rt/fabs
   "flint/copy-sign" flint.rt/copy-sign})

(defn lookup [name]
  (or (get table name)
      (throw (ex-info (str "builtin `" name "` is not available at compile time")
                      {:builtin name :type :compile}))))
