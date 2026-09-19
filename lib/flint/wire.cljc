(ns flint.wire
  "The wire codec, as flint (`DECISIONS.md#the-codec-is-guest-code`).

  The runtime still carries an encoder; this is the one that replaces it. What
  makes the replacement checkable is that the FORMAT does not change -- these
  emit exactly the bytes `runtime/src/codec.rs` emits, and
  `test/host_abi.mjs` sends each value both ways over one bridge and compares.

  ## Why this is here and not in the runtime

  Three runtimes carried the same encoder written three times, and every tag
  added had to be added three times. More to the point, the safety rule --
  `K_PORT` and `K_SENTINEL` carry their identity inline, so a decoder reachable
  from a guest would let it mint any host id -- was enforced by a tag switch in
  each of them. Here the rule is two primitives: `wire-port` and `wire-opaque`
  take a VALUE and read its identity themselves, and there is no way to hand
  them an integer.

  ## What the guest cannot do

  Reach the bytes. A writer is opaque, `port/send` takes it, and nothing
  answers its contents -- a guest that could ask would concatenate them with
  bytes of its own and forge a port tag."
  (:require [flint.protocols :as proto]))

(declare emit)

(defn- emit-value
  "One value, without its metadata -- `emit` deals with that."
  [w v]
  (let [k (flint.rt/kind v)]
    (cond
      (nil? v) (flint.rt/wire-nil w)
      (= k :boolean) (flint.rt/wire-bool w v)
      ;; ONE KIND, TWO TAGS. `:number` covers both, and the wire does not: an
      ;; integer and a double are different tags and different payloads.
      (= k :number) (if (int? v) (flint.rt/wire-int w v) (flint.rt/wire-double w v))
      (= k :string) (flint.rt/wire-str w v)
      (= k :bytes) (flint.rt/wire-bytes w v)
      ;; `namespace` ANSWERS NIL FOR AN UNQUALIFIED NAME, and the primitive
      ;; writes `NO_NS` for nil -- absent, which is not empty. That is what
      ;; separates `:kw` from `:/kw`, and it is a distinction a codec that used
      ;; the empty string would quietly lose.
      (= k :keyword) (flint.rt/wire-kw w (namespace v) (name v))
      (= k :symbol) (flint.rt/wire-sym w (namespace v) (name v))
      (= k :vector) (reduce emit (flint.rt/wire-vec w (count v)) v)
      (= k :set) (reduce emit (flint.rt/wire-set w (count v)) v)
      (= k :list) (reduce emit (flint.rt/wire-list w (count v)) v)
      (= k :map) (reduce (fn [w e] (emit (emit w (key e)) (val e)))
                         (flint.rt/wire-map w (count v))
                         v)
      ;; THE TWO THAT CARRY IDENTITY. Both take the value; neither can be
      ;; handed an id, which is the whole safety rule.
      (= k :port) (flint.rt/wire-port w v)
      (= k :opaque) (flint.rt/wire-opaque w v)
      ;; A TAGGED LITERAL is a wrapper, like metadata: the tag byte, then two
      ;; ordinary values. It READS like a two-key map on `:tag` and `:form`
      ;; (`DECISIONS.md#tagged-literals`), which is how this reaches its parts
      ;; without the type needing accessors of its own.
      (= k :tagged) (emit (emit (flint.rt/wire-tagged w) (:tag v)) (:form v))
      ;; A TABLE: the column count, a name and a type per column, then the ROW
      ;; count, then the cells COLUMN BY COLUMN -- so a receiver reading one
      ;; field reads one run rather than striding over the rest.
      ;;
      ;; The row count is the reason this arm could not be written until the
      ;; writer tracked structure. It sits after values, and a primitive that
      ;; wrote four raw bytes wherever it was called would let a guest put four
      ;; bytes where a VALUE is due -- `0000000f` there is `K_PORT` and an id,
      ;; which is a port minted out of an integer. `wire-table-rows` refuses
      ;; unless a count is what the writer is expecting, so the forgery has
      ;; nowhere to land (`DECISIONS.md#the-codec-is-guest-code`).
      (= k :table)
      (let [s (flint.rt/table-schema v)
            names (flint.rt/schema-columns s)
            types (flint.rt/schema-types s)
            n (count names)
            w (reduce (fn [w i] (emit (emit w (nth names i)) (nth types i)))
                      (flint.rt/wire-table w n)
                      (range n))
            w (flint.rt/wire-table-rows w (count v))]
        (reduce (fn [w nm] (reduce emit w (flint.rt/table-column v nm))) w names))
      ;; REFUSED BY NAME, and for the ones with a reason worth giving, WITH
      ;; the reason. These messages were the runtime encoder's before the codec
      ;; became guest code, and a message that got worse in the move would be a
      ;; regression a passing test could hide -- `test/sysns.clj` checks the
      ;; wording for exactly that.
      (= k :fn)
      (throw (ex-info (str "a function cannot cross a boundary: its meaning is "
                           "its environment, and that does not travel")
                      {:kind k}))
      (= k :atom) (throw (ex-info "an atom cannot cross a boundary" {:kind k}))
      (= k :var) (throw (ex-info "a var cannot cross a boundary" {:kind k}))
      (= k :thread) (throw (ex-info "a thread cannot cross a boundary" {:kind k}))
      :else (throw (ex-info (str "this cannot cross a boundary: " (str k))
                            {:kind k})))))

(defn emit
  "Emit `v` into writer `w`, and answer the writer.

  METADATA FIRST, as a wrapper around the value, which is the order
  `K_WITH_META` is written and read in. What metadata is still ON the value
  here is what `flint.port/for-the-wire` already decided should cross -- this
  emits what it is given and makes no selection of its own."
  [w v]
  (let [m (meta v)]
    (if (nil? m)
      (emit-value w v)
      ;; The value WITHOUT its metadata, or this recurses on itself.
      (emit-value (emit (flint.rt/wire-meta w) m) (with-meta v nil)))))

(defn encode
  "`v` as a writer, ready to hand to `port/send`."
  [v]
  (emit (flint.rt/wire-writer) v))

;; --- reading ---------------------------------------------------------------
;;
;; NOT THE WRITER'S MIRROR IMAGE. The writer is opaque because a guest that can
;; write raw bytes can forge a `K_PORT` tag; a reader hands out integers and
;; strings freely, because reading bytes a guest already holds tells it nothing
;; new. Only the two MINTING reads are guarded, and by a flag on the reader --
;; true only for bytes that arrived on a bridge -- rather than by refusing tags.

(def ^:private K-NIL 0) (def ^:private K-TRUE 1) (def ^:private K-FALSE 2)
(def ^:private K-INT 3) (def ^:private K-DOUBLE 4) (def ^:private K-STRING 5)
(def ^:private K-KEYWORD 6) (def ^:private K-SYMBOL 7) (def ^:private K-VECTOR 8)
(def ^:private K-LIST 9) (def ^:private K-MAP 10) (def ^:private K-SET 11)
(def ^:private K-BYTES 14) (def ^:private K-PORT 15) (def ^:private K-SENTINEL 16)
(def ^:private K-TAGGED 17) (def ^:private K-TABLE 18)
(def ^:private K-WITH-META 19)

(declare take-value)

(defn- take-n
  "`n` values, as a vector. The count came off the wire, so it is not trusted
  for allocation -- each value is read and conj'd, and a count larger than the
  message runs out of bytes and throws rather than reserving for it."
  [r n]
  (loop [i 0 acc []]
    (if (< i n) (recur (inc i) (conj acc (take-value r))) acc)))

(defn- take-value
  "One value off `r`."
  [r]
  (let [t (flint.rt/wire-tag r)]
    (cond
      (nil? t) (throw (ex-info "the encoding ends where a value was expected" {}))
      (= t K-NIL) nil
      (= t K-TRUE) true
      (= t K-FALSE) false
      (= t K-INT) (flint.rt/wire-i64 r)
      (= t K-DOUBLE) (flint.rt/wire-f64 r)
      (= t K-STRING) (flint.rt/wire-text r)
      (= t K-BYTES) (flint.rt/wire-blob r)
      ;; `wire-ns` ANSWERS NIL FOR ABSENT, and `keyword` of a nil namespace is
      ;; the unqualified one -- the distinction the wire keeps as `ffffffff`.
      (= t K-KEYWORD) (let [ns (flint.rt/wire-ns r) nm (flint.rt/wire-text r)]
                        (if (nil? ns) (keyword nm) (keyword ns nm)))
      (= t K-SYMBOL) (let [ns (flint.rt/wire-ns r) nm (flint.rt/wire-text r)]
                       (if (nil? ns) (symbol nm) (symbol ns nm)))
      (= t K-VECTOR) (take-n r (flint.rt/wire-u32 r))
      (= t K-LIST) (apply list (take-n r (flint.rt/wire-u32 r)))
      (= t K-SET) (into #{} (take-n r (flint.rt/wire-u32 r)))
      (= t K-MAP) (let [n (flint.rt/wire-u32 r)]
                    (loop [i 0 m {}]
                      (if (< i n)
                        (let [k (take-value r) v (take-value r)]
                          (recur (inc i) (assoc m k v)))
                        m)))
      (= t K-TAGGED) (let [tag (take-value r)] (tagged-literal tag (take-value r)))
      ;; A TABLE IS REBUILT THROUGH `schema` AND `table`, the same two calls a
      ;; program makes. Nothing here can produce a table its own schema would
      ;; refuse, which a decoder that filled the columns in directly could.
      (= t K-TABLE)
      (let [ncols (flint.rt/wire-u32 r)
            pairs (loop [i 0 acc []]
                    (if (< i ncols)
                      (recur (inc i) (conj acc [(take-value r) (take-value r)]))
                      acc))
            nrows (flint.rt/wire-u32 r)
            cols (loop [c 0 acc []]
                   (if (< c ncols) (recur (inc c) (conj acc (take-n r nrows))) acc))
            names (mapv (fn [p] (nth p 0)) pairs)]
        (flint.rt/table
          (flint.rt/schema pairs)
          (mapv (fn [i]
                  (loop [c 0 m {}]
                    (if (< c ncols)
                      (recur (inc c) (assoc m (nth names c) (nth (nth cols c) i)))
                      m)))
                (range nrows))))
      ;; METADATA FIRST, then the value, then the two put together -- the order
      ;; the encoder writes them in.
      (= t K-WITH-META) (let [m (take-value r)] (with-meta (take-value r) m))
      (= t K-PORT) (flint.rt/wire-port-in r)
      (= t K-SENTINEL) (flint.rt/wire-opaque-in r)
      :else (throw (ex-info (str "unknown tag in the encoding: " (str t)) {:tag t})))))

(defn read-from
  "The value on `r`, which is already a reader.

  What `flint.port/receive` uses: the runtime hands it a LIVE reader -- the only
  kind that may mint a port -- and this reads one value off it. Trailing bytes
  are an error here too: a message with extra on the end is one the sender and
  this disagree about."
  [r]
  (let [v (take-value r)]
    (if (pos? (flint.rt/wire-left r))
      (throw (ex-info "the encoding has bytes left over" {:left (flint.rt/wire-left r)}))
      v)))

(defn decode
  "The value in `bytes`.

  The reader this makes may NOT mint: bytes a program holds are bytes a program
  holds, and a port cannot be made from them. `port/receive` is what produces a
  reader that may, because it is what knows the bytes came from a bridge."
  [bytes]
  (let [r (flint.rt/wire-reader bytes)
        v (take-value r)]
    ;; TRAILING BYTES ARE AN ERROR, not something to ignore. A message with
    ;; extra on the end is one the sender and this disagree about.
    (if (pos? (flint.rt/wire-left r))
      (throw (ex-info "the encoding has bytes left over" {:left (flint.rt/wire-left r)}))
      v)))
