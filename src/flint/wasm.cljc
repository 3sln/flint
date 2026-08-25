(ns flint.wasm
  "A minimal wasm binary reader/writer: enough to inspect a linked module and to
  splice in the pieces `flint` decides at link time.

  This is host-side tooling (it runs on the bootstrap host next to `rust-lld`),
  not part of the self-hosting requirement -- a flint module has no processes to
  spawn. It is written in portable Clojure anyway, because the parts that read
  and write LEB128 are exactly the parts worth having tested.

  A module is {:sections [{:id n :payload bytes} ...]}. Sections are kept as raw
  bytes and only decoded on demand, so we never have to understand a section we
  do not touch -- which is what keeps this small and robust against new wasm
  features appearing in the linker's output."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------- byte input

(defn- ub [^bytes b i] (bit-and (int (aget b (int i))) 0xff))

(defn ^:private rd-uleb
  "Read an unsigned LEB128 at i. Returns [value next-index]."
  [^bytes b i]
  (loop [i (int i) shift 0 acc 0]
    (let [x (ub b i)
          acc (bit-or acc (bit-shift-left (bit-and x 0x7f) shift))]
      (if (zero? (bit-and x 0x80))
        [acc (inc i)]
        (recur (inc i) (+ shift 7) acc)))))

(defn ^:private rd-sleb
  [^bytes b i]
  (loop [i (int i) shift 0 acc 0]
    (let [x (ub b i)
          acc (bit-or acc (bit-shift-left (bit-and x 0x7f) shift))
          shift (+ shift 7)]
      (if (zero? (bit-and x 0x80))
        [(if (and (< shift 64) (not (zero? (bit-and x 0x40))))
           (bit-or acc (bit-shift-left -1 shift))
           acc)
         (inc i)]
        (recur (inc i) shift acc)))))

;; --------------------------------------------------------------- byte output

(defn uleb
  "Encode an unsigned integer as LEB128, returning a seq of byte values."
  [n]
  (loop [n (long n) out []]
    (let [x (bit-and n 0x7f)
          n' (unsigned-bit-shift-right n 7)]
      (if (zero? n')
        (conj out x)
        (recur n' (conj out (bit-or x 0x80)))))))

(defn sleb [n]
  (loop [n (long n) out []]
    (let [x (bit-and n 0x7f)
          n' (bit-shift-right n 7)
          done? (or (and (zero? n') (zero? (bit-and x 0x40)))
                    (and (= n' -1) (not (zero? (bit-and x 0x40)))))]
      (if done?
        (conj out x)
        (recur n' (conj out (bit-or x 0x80)))))))

(defn- bytes->vec [^bytes b] (mapv #(bit-and (int %) 0xff) b))

(defn ->bytes
  "Flatten a nested structure of byte values / byte arrays into a byte-array."
  [x]
  (let [out (java.io.ByteArrayOutputStream.)]
    ((fn walk [v]
       (cond
         (nil? v) nil
         (integer? v) (.write out (int (bit-and (long v) 0xff)))
         (bytes? v) (.write out ^bytes v 0 (alength ^bytes v))
         (sequential? v) (run! walk v)
         :else (throw (ex-info "not byte-able" {:v v}))))
     x)
    (.toByteArray out)))

(defn utf8-bytes [^String s]
  ;; The `:flint` branch is not decoration. This file is read with
  ;; `#{:flint}`, so a conditional with only `:clj` and `:cljs` selects
  ;; NOTHING -- and a `defn` whose body vanishes is still a `defn`, so what you
  ;; get is `(defn utf8-bytes [s])`, returning nil, with no diagnostic
  ;; anywhere. It has been unreachable rather than harmless: the self-hosted
  ;; compiler does not link, so `flint.wasm` never shipped. It will the moment
  ;; the CLI links for itself. `test/reader_test.clj` now asserts the shape.
  #?(:clj (.getBytes s "UTF-8")
     :flint (flint.rt/str-bytes s)
     :cljs (throw (ex-info "no utf8" {}))))

(defn- vec-section [items]
  (concat (uleb (count items)) (apply concat items)))

;; -------------------------------------------------------------- module shape

(def ^:private section-order
  ;; canonical order; custom (0) may appear anywhere, datacount(12) before code(10)
  {1 1, 2 2, 3 3, 4 4, 5 5, 6 6, 7 7, 8 8, 9 9, 12 10, 10 11, 11 12})

(defn parse
  "Split a wasm module into its sections. Keeps payload bytes verbatim."
  [^bytes b]
  (assert (and (= 0x00 (ub b 0)) (= 0x61 (ub b 1)) (= 0x73 (ub b 2)) (= 0x6d (ub b 3)))
          "not a wasm module")
  (loop [i 8 secs []]
    (if (>= i (alength b))
      {:version (bytes->vec (java.util.Arrays/copyOfRange b 4 8))
       :sections secs}
      (let [id (ub b i)
            [size i2] (rd-uleb b (inc i))
            payload (java.util.Arrays/copyOfRange b (int i2) (int (+ i2 size)))]
        (recur (+ i2 size) (conj secs {:id id :payload payload}))))))

(defn emit ^bytes [{:keys [version sections]}]
  (->bytes [0x00 0x61 0x73 0x6d (or version [1 0 0 0])
            (for [{:keys [id ^bytes payload]} sections]
              [id (uleb (alength payload)) payload])]))

(defn section [m id] (first (filter #(= id (:id %)) (:sections m))))

(defn put-section
  "Replace the section with `id`, or insert it in canonical order."
  [m id ^bytes payload]
  (if (section m id)
    (update m :sections (fn [ss] (mapv #(if (= id (:id %)) {:id id :payload payload} %) ss)))
    (let [rank (fn [s] (get section-order (:id s) 0))
          my (get section-order id 0)
          [before after] (split-with #(<= (rank %) my) (:sections m))]
      (assoc m :sections (vec (concat before [{:id id :payload payload}] after))))))

;; ------------------------------------------------------------------- exports

(defn exports
  "{name {:kind :func|:table|:memory|:global :index n}}"
  [m]
  (if-let [{:keys [^bytes payload]} (section m 7)]
    (let [[n i] (rd-uleb payload 0)]
      (loop [i i k 0 acc {}]
        (if (= k n)
          acc
          (let [[len i] (rd-uleb payload i)
                nm (String. payload (int i) (int len) "UTF-8")
                i (+ i len)
                kind (ub payload i)
                [idx i] (rd-uleb payload (inc i))]
            (recur i (inc k)
                   (assoc acc nm {:kind ([:func :table :memory :global] kind) :index idx}))))))
    {}))

(defn global-i32-init
  "The `i32.const` initialiser of global `idx`. lld emits `__heap_base` and
  friends this way, which is how the patcher learns where it may put data."
  [m idx]
  (let [{:keys [^bytes payload]} (section m 6)
        [n i] (rd-uleb payload 0)]
    (loop [i i k 0]
      (when (< k n)
        (let [i (+ i 2)]                                  ; valtype, mutability
          (if (= k idx)
            (do (assert (= 0x41 (ub payload i)) "expected i32.const")
                (first (rd-sleb payload (inc i))))
            ;; skip the init expr: i32.const <sleb> end
            (let [[_ i] (rd-sleb payload (inc i))]
              (recur (inc i) (inc k)))))))))

;; -------------------------------------------------------- limits (table/mem)

(defn- read-limits [^bytes p i]
  (let [flags (ub p i)
        [mn i] (rd-uleb p (inc i))
        [mx i] (if (bit-test flags 0) (rd-uleb p i) [nil i])]
    {:flags flags :min mn :max mx :end i}))

(defn- write-limits [{:keys [flags min max]}]
  (concat [flags] (uleb min) (when max (uleb max))))

(defn table-min [m]
  (when-let [{:keys [^bytes payload]} (section m 4)]
    (let [[n i] (rd-uleb payload 0)]
      (when (pos? n) (:min (read-limits payload (inc i)))))))   ; skip reftype

(defn set-table-min [m v]
  (let [{:keys [^bytes payload]} (section m 4)
        [n i] (rd-uleb payload 0)
        reftype (ub payload i)
        lim (read-limits payload (inc i))
        rest-b (java.util.Arrays/copyOfRange payload (int (:end lim)) (alength payload))]
    (assert (= n 1) "expected exactly one table")
    (put-section m 4 (->bytes [(uleb n) reftype
                               (write-limits (assoc lim :min v
                                                    :max (when (:max lim) (clojure.core/max v (:max lim)))))
                               rest-b]))))

(defn memory-min [m]
  (when-let [{:keys [^bytes payload]} (section m 5)]
    (let [[n i] (rd-uleb payload 0)]
      (when (pos? n) (:min (read-limits payload i))))))

(defn set-memory-min [m pages]
  (let [{:keys [^bytes payload]} (section m 5)
        [n i] (rd-uleb payload 0)
        lim (read-limits payload i)
        rest-b (java.util.Arrays/copyOfRange payload (int (:end lim)) (alength payload))]
    (put-section m 5 (->bytes [(uleb n)
                               (write-limits (assoc lim :min pages
                                                    :max (when (:max lim) (clojure.core/max pages (:max lim)))))
                               rest-b]))))

;; -------------------------------------------------------- element/data appends

(defn- count-and-body [m id]
  (if-let [{:keys [^bytes payload]} (section m id)]
    (let [[n i] (rd-uleb payload 0)]
      [n (java.util.Arrays/copyOfRange payload (int i) (alength payload))])
    [0 (byte-array 0)]))

(defn append-elem
  "Append an active element segment placing `funcidxs` in table 0 at `base`.
  Grows the table's minimum to fit. Returns [module base]."
  [m base funcidxs]
  (let [[n body] (count-and-body m 9)
        seg (->bytes [0x00                                  ; flags: active, table 0
                      0x41 (sleb base) 0x0b                 ; offset: i32.const base; end
                      (uleb (count funcidxs))
                      (map uleb funcidxs)])
        m (put-section m 9 (->bytes [(uleb (inc n)) body seg]))
        need (+ base (count funcidxs))
        m (if (< (or (table-min m) 0) need) (set-table-min m need) m)]
    [m base]))

(defn append-data
  "Append an active data segment writing `data` at linear-memory address `addr`.
  Grows the memory minimum to cover it. `data` may be a byte array or a seq of
  byte values -- the image writer produces the latter, because it has to run on
  flint where there are no host arrays."
  [m addr data]
  (let [^bytes data (if (bytes? data) data (->bytes data))
        [n body] (count-and-body m 11)
        seg (->bytes [0x00 0x41 (sleb addr) 0x0b (uleb (alength data)) data])
        m (put-section m 11 (->bytes [(uleb (inc n)) body seg]))
        m (if-let [{:keys [^bytes payload]} (section m 12)]
            (do payload (put-section m 12 (->bytes (uleb (inc n)))))
            m)
        pages (quot (+ addr (alength data) 65535) 65536)
        m (if (< (or (memory-min m) 0) pages) (set-memory-min m pages) m)]
    m))

(defn strip-custom
  "Drop custom sections by name (`name`, `producers`, `target_features`, ...).
  Saves real bytes in the output module."
  [m names]
  (update m :sections
          (fn [ss]
            (vec (remove (fn [{:keys [id ^bytes payload]}]
                           (and (= 0 id)
                                (let [[len i] (rd-uleb payload 0)]
                                  (contains? names (String. payload (int i) (int len) "UTF-8")))))
                         ss)))))

(defn imports
  "Every import, as `{:module :name :kind}`. A flint program imports nothing;
  this exists because `0020`'s capability descriptor is precisely `how to spin
  up the glue`, and a descriptor derived from the module cannot drift from it."
  [m]
  (if-let [{:keys [^bytes payload]} (section m 2)]
    (let [[n i] (rd-uleb payload 0)]
      (loop [i i k 0 out []]
        (if (= k n)
          out
          (let [[mlen i2] (rd-uleb payload i)
                mod (String. payload (int i2) (int mlen) "UTF-8")
                i3 (+ i2 mlen)
                [nlen i4] (rd-uleb payload i3)
                nm (String. payload (int i4) (int nlen) "UTF-8")
                i5 (+ i4 nlen)
                kind (ub payload i5)
                ;; The descriptor after the kind byte varies; every form starts
                ;; with at least one uleb, and a memory or table adds a limits
                ;; block. Only the names are wanted here, so skip precisely.
                i6 (inc i5)
                i7 (case kind
                     0 (second (rd-uleb payload i6))
                     3 (+ i6 2)
                     (let [[flags j] (rd-uleb payload (inc i6))
                           [_ j2] (rd-uleb payload j)]
                       (if (zero? flags) j2 (second (rd-uleb payload j2)))))]
            (recur i7 (inc k) (conj out {:module mod :name nm :kind kind}))))))
    []))

(defn add-custom
  "Add a custom section named `nm` carrying `payload`, placed EARLY.

  Early matters (`doc/decisions/0020`): a runner deciding whether it can load a
  module at all, and how to build its glue, should not have to stream past a
  megabyte of code section first. It goes before the code section (id 10) and
  after the type section, which is the earliest point that keeps the canonical
  ordering readable.

  Any existing section with the same name is replaced, so re-running a link does
  not accumulate copies."
  [m nm payload]
  (let [b (utf8-bytes nm)
        body (->bytes [(uleb (alength b)) b payload])
        m (strip-custom m #{nm})
        ss (:sections m)
        ;; Before the first section that is not a type/import section, which in
        ;; practice puts it near the front and always before code.
        at (or (first (keep-indexed (fn [i {:keys [id]}] (when (>= id 3) i)) ss))
               (count ss))]
    (assoc m :sections (vec (concat (subvec ss 0 at)
                                    [{:id 0 :payload body}]
                                    (subvec ss at))))))

(defn custom-section
  "The payload of the custom section named `nm`, or nil. Parsing only -- this is
  what a runner does, and it must work without instantiating anything."
  [m nm]
  (some (fn [{:keys [id ^bytes payload]}]
          (when (= 0 id)
            (let [[len i] (rd-uleb payload 0)]
              (when (= nm (String. payload (int i) (int len) "UTF-8"))
                (java.util.Arrays/copyOfRange payload (int (+ i len)) (int (alength payload)))))))
        (:sections m)))

(defn rename-export
  "Rename an export. Needed because `wasm-ld` special-cases a symbol named
  `main` and wraps it; we export `flint_main` and rename afterwards."
  [m from to]
  (let [{:keys [^bytes payload]} (section m 7)
        [n i] (rd-uleb payload 0)]
    (loop [i i k 0 out []]
      (if (= k n)
        (put-section m 7 (->bytes [(uleb n) out]))
        (let [[len i2] (rd-uleb payload i)
              nm (String. payload (int i2) (int len) "UTF-8")
              i3 (+ i2 len)
              kind (ub payload i3)
              [idx i4] (rd-uleb payload (inc i3))
              nm' (if (= nm from) to nm)
              enc (utf8-bytes nm')]
          (recur i4 (inc k)
                 (conj out [(uleb (count enc)) enc kind (uleb idx)])))))))

(defn func-index [m sym]
  (get-in (exports m) [sym :index]))

;; ------------------------------------------------------- appending functions
;;
;; Everything above reads or patches what the linker produced. This adds code
;; that never went through the linker at all, which is what `doc/decisions/0013`
;; needs: compiled arities are emitted after the link, because only then are the
;; helper functions' indices known.

(defn imported-funcs
  "How many functions the import section declares. Function indices count
  imports first, so an appended body that got this wrong would call the wrong
  function and be very hard to see."
  [m]
  (if-let [{:keys [^bytes payload]} (section m 2)]
    (let [[n i] (rd-uleb payload 0)]
      (loop [i i k 0 acc 0]
        (if (= k n)
          acc
          (let [[l1 i] (rd-uleb payload i)
                i (+ i l1)
                [l2 i] (rd-uleb payload i)
                i (+ i l2)
                kind (ub payload i)
                ;; func: typeidx. table: reftype+limits. mem: limits.
                ;; global: valtype+mut.
                i (inc i)
                i (case kind
                    0 (second (rd-uleb payload i))
                    1 (let [i (inc i)] (:end (read-limits payload i)))
                    2 (:end (read-limits payload i))
                    3 (+ i 2)
                    (throw (ex-info "unknown import kind" {:kind kind})))]
            (recur i (inc k) (if (= kind 0) (inc acc) acc))))))
    0))

(defn- defined-funcs [m]
  (if-let [{:keys [^bytes payload]} (section m 3)]
    (first (rd-uleb payload 0))
    0))

(defn add-type
  "Append a function type, returning [module type-index]. Types are compared by
  bytes so a repeated signature does not add a second entry."
  [m params results]
  (let [enc (->bytes [0x60 (uleb (count params)) params
                      (uleb (count results)) results])
        [n body] (count-and-body m 1)
        existing (loop [i 0 k 0 acc []]
                   (if (= k n)
                     acc
                     ;; each type is 0x60 nparams params nresults results
                     (let [start i
                           [np i2] (rd-uleb body (inc i))
                           i3 (+ i2 np)
                           [nr i4] (rd-uleb body i3)
                           i5 (+ i4 nr)]
                       (recur i5 (inc k)
                              (conj acc (java.util.Arrays/copyOfRange body (int start) (int i5)))))))
        hit (first (keep-indexed (fn [k b] (when (java.util.Arrays/equals ^bytes b ^bytes enc) k))
                                 existing))]
    (if hit
      [m hit]
      [(put-section m 1 (->bytes [(uleb (inc n)) body enc])) n])))

(defn append-funcs
  "Append function bodies of type `type-idx`. `bodies` are already-encoded code
  entries (locals declaration followed by the instruction bytes, without the
  size prefix). Returns [module first-function-index]."
  [m type-idx bodies]
  (let [[nf fbody] (count-and-body m 3)
        [nc cbody] (count-and-body m 10)
        _ (assert (= nf nc) "function and code sections disagree")
        first-idx (+ (imported-funcs m) nf)
        m (put-section m 3 (->bytes [(uleb (+ nf (count bodies))) fbody
                                     (repeat (count bodies) (uleb type-idx))]))
        m (put-section m 10 (->bytes [(uleb (+ nc (count bodies))) cbody
                                      (for [^bytes b bodies]
                                        [(uleb (alength b)) b])]))]
    [m first-idx]))
