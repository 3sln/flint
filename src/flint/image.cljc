(ns flint.image
  "Writes the flint program image: the compiler's output, which `flint` splices
  into the linked module as a data segment.

  The format is described in `runtime/src/image.rs`; this namespace and that one
  are the two halves of it, and `test/image_roundtrip.clj` pins them together."
  (:require [flint.wasm :as w]))

(def MAGIC (mapv int "FLINTIMG"))
(def VERSION 1)

(def K-NIL 0) (def K-TRUE 1) (def K-FALSE 2) (def K-INT 3) (def K-DOUBLE 4)
(def K-STRING 5) (def K-KEYWORD 6) (def K-SYMBOL 7) (def K-VECTOR 8)
(def K-LIST 9) (def K-MAP 10) (def K-SET 11) (def K-FN 12) (def K-NATIVE 13)

(def NO-CONST 0xFFFFFFFF)

(defn u32 [n]
  (let [n (bit-and (long n) 0xFFFFFFFF)]
    [(bit-and n 0xff) (bit-and (bit-shift-right n 8) 0xff)
     (bit-and (bit-shift-right n 16) 0xff) (bit-and (bit-shift-right n 24) 0xff)]))

(defn u16 [n]
  (let [n (bit-and (long n) 0xFFFF)]
    [(bit-and n 0xff) (bit-and (bit-shift-right n 8) 0xff)]))

(defn i64->bytes [n]
  (let [n (long n)]
    (mapv #(bit-and (unsigned-bit-shift-right n (* 8 %)) 0xff) (range 8))))

(defn f64->bytes [d]
  (i64->bytes #?(:clj (Double/doubleToRawLongBits (double d))
                 :cljs (throw (ex-info "no f64 bits" {})))))

(defn utf8 [^String s]
  #?(:clj (vec (map #(bit-and (int %) 0xff) (.getBytes s "UTF-8")))
     :cljs (throw (ex-info "no utf8" {}))))

;; ---------------------------------------------------------------- constants
;;
;; The constant pool is emitted in dependency order: a compound constant refers
;; to its parts by index, and the loader builds them in one pass.

(defn- intern-const [b entry]
  (if-let [i (get (:index @b) entry)]
    i
    (let [i (count (:consts @b))]
      (vswap! b #(-> % (update :consts conj entry) (assoc-in [:index entry] i)))
      i)))

(defn new-builder []
  (volatile! {:consts [] :index {} :fns [] :vars [] :var-index {}
              :natives [] :native-index {} :code [] :entry 0 :init []}))

(declare const)

(defn const
  "Add `v` to the constant pool, returning its index. Idempotent."
  [b v]
  (cond
    (nil? v) (intern-const b [:nil])
    (true? v) (intern-const b [:true])
    (false? v) (intern-const b [:false])
    (integer? v) (intern-const b [:int (long v)])
    (double? v) (intern-const b [:double v])
    (string? v) (intern-const b [:string v])
    (keyword? v) (let [nsc (if (namespace v) (const b (namespace v)) NO-CONST)
                       nmc (const b (name v))]
                   (intern-const b [:keyword nsc nmc]))
    (symbol? v) (let [nsc (if (namespace v) (const b (namespace v)) NO-CONST)
                      nmc (const b (name v))]
                  (intern-const b [:symbol nsc nmc]))
    (vector? v) (intern-const b (into [:vector] (mapv #(const b %) v)))
    (set? v) (intern-const b (into [:set] (mapv #(const b %) v)))
    (map? v) (intern-const b (into [:map] (mapcat (fn [[k x]] [(const b k) (const b x)]) v)))
    (seq? v) (intern-const b (into [:list] (mapv #(const b %) v)))
    :else (throw (ex-info "not a constant" {:v v :type (type v)}))))

(defn- emit-const [entry]
  (let [[tag & args] entry]
    (case tag
      :nil [K-NIL]
      :true [K-TRUE]
      :false [K-FALSE]
      :int [K-INT (i64->bytes (first args))]
      :double [K-DOUBLE (f64->bytes (first args))]
      :string (let [bs (utf8 (first args))] [K-STRING (u32 (count bs)) bs])
      :keyword [K-KEYWORD (u32 (first args)) (u32 (second args))]
      :symbol [K-SYMBOL (u32 (first args)) (u32 (second args))]
      :vector [K-VECTOR (u32 (count args)) (map u32 args)]
      :list [K-LIST (u32 (count args)) (map u32 args)]
      :set [K-SET (u32 (count args)) (map u32 args)]
      :map [K-MAP (u32 (quot (count args) 2)) (map u32 args)]
      :fn [K-FN (u32 (first args))]
      :native [K-NATIVE (u32 (first args)) (u32 (second args))])))

;; ------------------------------------------------------------ vars, natives

(defn var-slot
  "Index of the global slot for `sym`, creating it if new."
  [b sym]
  (if-let [i (get (:var-index @b) sym)]
    i
    (let [i (count (:vars @b))
          c (const b (str sym))]
      (vswap! b #(-> % (update :vars conj c) (assoc-in [:var-index sym] i)))
      i)))

(defn native-slot
  "Index of the native-import entry for builtin `name`, creating it if new.
  These indices are what `NATIVE` opcodes refer to; the linker later resolves
  each to a wasm table slot."
  [b name]
  (if-let [i (get (:native-index @b) name)]
    i
    (let [i (count (:natives @b))
          c (const b name)]
      (vswap! b #(-> % (update :natives conj {:name name :name-const c})
                     (assoc-in [:native-index name] i)))
      i)))

;; ------------------------------------------------------------------- fns

(defn native-const
  "A builtin as a first-class value, for `(map flint.rt/inc xs)` and friends."
  [b name]
  (let [idx (native-slot b name)
        namec (const b name)]
    (intern-const b [:native idx namec])))

(defn add-fn
  "Append a function. `arities` is a seq of
  {:argc n :variadic? bool :nlocals n :code <byte-seq>}."
  [b {:keys [name arities nupvals]}]
  (let [namec (const b (str name))
        placed (mapv (fn [a]
                       (let [off (count (:code @b))
                             body (vec (:code a))]
                         (vswap! b update :code into body)
                         (assoc a :off off :len (count body))))
                     arities)
        entry {:name namec :nupvals (or nupvals 0) :arities placed}
        i (count (:fns @b))]
    (vswap! b update :fns conj entry)
    i))

(defn- emit-fn [{:keys [name nupvals arities]}]
  [(u32 name) nupvals (count arities)
   (for [a arities]
     [(:argc a) (if (:variadic? a) 1 0) (u16 (:nlocals a)) (u32 (:off a)) (u32 (:len a))])])

;; ------------------------------------------------------------------ output

(defn emit
  "Serialise the image. `native-slots` maps builtin name -> wasm table slot;
  unresolved names get slot 0, which traps if ever called."
  [b native-slots]
  (let [{:keys [consts fns vars natives code entry init]} @b]
    (w/->bytes
     [MAGIC (u32 VERSION)
      (u32 (count consts)) (map emit-const consts)
      (u32 (count fns)) (map emit-fn fns)
      (u32 (count vars)) (map u32 vars)
      (u32 (count natives))
      (for [n natives]
        ;; the name is kept for diagnostics; the runtime reads only the slot
        [(u32 (:name-const n)) (u32 (get native-slots (:name n) 0))])
      (u32 (count code)) code
      (u32 entry)
      (u32 (count init)) (map u32 init)])))

(defn set-entry! [b i] (vswap! b assoc :entry i))
(defn add-init! [b i] (vswap! b update :init conj i))
(defn natives
  "The builtin names this image imports, in native-index order."
  [b] (mapv :name (:natives @b)))
