(ns flint.types
  "The tag vocabulary, shared by the analyzer and the runtime.

  A tag written on a binding is a CHECKED claim, not a hint. `(let [^int x e]
  ...)` binds `check-tag(e, 1, \"x\")`, so every read of `x` afterwards is known
  to be an int without testing anything: the cost is one test at the write
  instead of one at each read, and the claim is sound because the write barrier
  is the only way in.

  The integer codes are duplicated in `runtime/src/builtins.rs` under
  `flint/check-tag`, because that is on the write path and a keyword lookup
  there would cost more than the test. `test/types.clj` asserts the two tables
  agree; drift between them would make every annotation vacuous, which is the
  worst possible failure for a feature whose whole value is that it is sound.")

(def code
  "Tag keyword to the integer `flint/check-tag` dispatches on."
  {:int 1 :float 2 :number 3 :string 4 :keyword 5 :symbol 6 :boolean 7
   :vector 8 :map 9 :set 10 :seq 11 :fn 12 :nil 13 :sequential 14})

(def ^:private aliases
  "What may be written, and what it means. `long` and `int` are the same thing
  here -- flint has one integer type -- and saying so beats refusing a hint
  somebody carried over from Clojure."
  {'int :int 'long :int 'integer :int 'Integer :int 'Long :int
   'double :float 'float :float 'Double :float
   'number :number 'Number :number
   'string :string 'String :string
   'keyword :keyword 'Keyword :keyword
   'symbol :symbol 'Symbol :symbol
   'boolean :boolean 'Boolean :boolean
   'vector :vector 'map :map 'set :set 'seq :seq
   'fn :fn 'ifn :fn 'nil :nil 'sequential :sequential
   ;; Written by anyone hinting for Clojure's benefit, and meaningless here.
   ;; Accepted as "no claim" rather than refused, so a portable file compiles.
   'Object :any 'any :any})

(defn tag
  "The tag `x` names, or nil if it names none. Keywords and symbols both work,
  so `^:int` and `^int` mean the same thing."
  [x]
  (cond
    (nil? x) nil
    (keyword? x) (let [k (get aliases (symbol (name x)) (keyword (name x)))]
                   (when (or (= :any k) (contains? code k)) k))
    (symbol? x) (get aliases x (let [k (keyword (name x))]
                                 (when (contains? code k) k)))
    :else nil))

(defn known
  "The tag written on `form`'s metadata, normalised. `:any` reads as no claim."
  [form]
  (when-let [t (tag (:tag (meta form)))]
    (when-not (= :any t) t)))

(def ^:private wider
  "What each tag also proves. `int` is a `number`; a `vector` is `sequential`."
  {:int #{:number} :float #{:number} :vector #{:sequential} :seq #{:sequential}})

(defn proves?
  "Does a value known to be `have` satisfy a `want` annotation? This is what
  elides a check, so it must never say yes wrongly -- an unsound elision turns
  the annotation into the hint it is supposed not to be."
  [have want]
  (boolean (and have want (or (= have want) (contains? (wider have) want)))))

(defn const-tag
  "The tag of a literal, which is the one case that needs no analysis at all."
  [v]
  (cond
    (nil? v) :nil
    (boolean? v) :boolean
    (integer? v) :int
    (float? v) :float
    (string? v) :string
    (keyword? v) :keyword
    (symbol? v) :symbol
    (vector? v) :vector
    (map? v) :map
    (set? v) :set
    :else nil))

(def ^:private arithmetic
  "Builtins whose return type follows their arguments. `int + int` is an int
  or an ArithmeticException and never a double -- `num_add` calls `checked_add`
  and throws on overflow, exactly as Clojure's `+` does -- so the claim is
  sound. It is the single most load-bearing entry in this file: without it a
  loop counter's tag dies at the first `inc` and every annotation downstream
  becomes a real test.

  `^int` means integer, not fixnum: a value past the fixnum range is a boxed
  TY_BIGINT and still answers `int?`. Anything that later wants to UNBOX on the
  strength of this tag needs a narrower one, and must not read this as it."
  #{"flint/add" "flint/sub" "flint/mul" "flint/unchecked-add"
    "flint/unchecked-sub" "flint/unchecked-mul" "quot" "rem" "mod"
    "flint/abs" "min" "max" "flint/neg"})

(def ^:private fixed-returns
  "What a builtin returns regardless of what it is given. Deliberately partial
  and deliberately conservative: a wrong entry elides a check that was
  load-bearing, so anything uncertain is absent rather than guessed."
  {"flint/lt" :boolean "flint/gt" :boolean "flint/le" :boolean
   "flint/ge" :boolean "flint/num-eq" :boolean "=" :boolean
   "nil?" :boolean "number?" :boolean "int?" :boolean "float?" :boolean
   "string?" :boolean "keyword?" :boolean "symbol?" :boolean
   "vector?" :boolean "map?" :boolean "set?" :boolean "seq?" :boolean
   "fn?" :boolean "boolean?" :boolean "sequential?" :boolean
   "contains?" :boolean "identical?" :boolean
   "count" :int "compare" :int "hash" :int
   "flint/str2" :string "flint/num->str" :string "flint/subs" :string
   "name" :string "flint/str-index-of" :int})

(defn native-return
  "What builtin `name` returns, given what is known about its arguments.
  `arg-tags` may contain nils; a nil is 'unknown', and unknown never widens
  into a claim."
  [name arg-tags]
  (or (get fixed-returns name)
      (when (and (contains? arithmetic name) (seq arg-tags))
        (cond
          (every? #(= :int %) arg-tags) :int
          (every? #(contains? #{:int :float :number} %) arg-tags) :number
          :else nil))))
