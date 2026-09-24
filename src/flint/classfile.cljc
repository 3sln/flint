(ns flint.classfile
  "A JVM class-file writer in flint, for the `:to :jvm` target's entry class.

  ## What it does and where it stops

  It writes a constant pool, static fields, and methods whose bodies are
  STRAIGHT LINES -- no branches. That is not a placeholder: the four operations
  an artifact exposes are delegations, and a delegation is a straight line.

  It stops exactly where `StackMapTable` begins. From class-file version 50 the
  verifier requires a stack map frame at every BRANCH TARGET, and computing those
  frames means a dataflow pass over the method: the type of every operand-stack
  slot and every local at every merge point, with the JVM's own subtyping rules
  for reference types (`java.lang.classfile` has `ClassHierarchyResolver` for
  exactly this and it is not a small class). Branch-free code has no merge
  points, so it needs no frames, and this writer needs no dataflow.

  `runtimes/jvm/src/com/flint/rt/AotEmit.java` is the other half of that
  sentence: it compiles a flint arity to a JVM method, and a flint arity is a
  `tableswitch` into one label per chunk -- every one of which is a branch target
  needing a frame. It uses the JDK's `java.lang.classfile`, which computes them.
  So: this namespace can write the artifact's FACE anywhere flint runs, and AOT
  bodies need a JVM present. See the report for what closing that would cost.

  ## The format

  `class_file { u4 magic; u2 minor; u2 major; u2 cp_count; cp_info[cp_count-1];
  u2 access; u2 this; u2 super; u2 n_ifaces; iface[]; u2 n_fields; field[];
  u2 n_methods; method[]; u2 n_attrs; attr[] }`

  The constant pool is ONE-INDEXED and `cp_count` is one more than the number of
  entries -- index 0 is not an entry and means \"absent\". Long and Double take two
  slots, which is the other trap; nothing here emits either."
  (:require [flint.rt]
            [flint.jar :as jar]))

;; Class-file version 52 is Java 8. Chosen as the FLOOR, not to match anything:
;; the interpreter classes beside this one need 24 for `java.lang.classfile`, so
;; the jar's real requirement is theirs, and a lower number here just means this
;; one file is not the thing that raises it.
(def MAJOR 52)
(def MINOR 0)

;; BIG-ENDIAN, and this is the one thing to get wrong: a ZIP is little-endian and
;; a class file is big-endian, so reusing `flint.jar/u16` here writes
;; `0xBEBAFECA` and `javap` says "Bad magic number" -- which is a true report of
;; a file whose every other field is also byte-swapped. They are separate
;; functions for that reason and not because two formats wanted two spellings.

(defn u16 [n]
  (let [n (bit-and (long n) 0xFFFF)]
    [(bit-and (bit-shift-right n 8) 0xff) (bit-and n 0xff)]))

(defn u32 [n]
  (let [n (bit-and (long n) 0xFFFFFFFF)]
    [(bit-and (bit-shift-right n 24) 0xff) (bit-and (bit-shift-right n 16) 0xff)
     (bit-and (bit-shift-right n 8) 0xff) (bit-and n 0xff)]))

(def ACC-PUBLIC 0x0001)
(def ACC-PRIVATE 0x0002)
(def ACC-STATIC 0x0008)
(def ACC-FINAL 0x0010)
(def ACC-SUPER 0x0020)

;; --------------------------------------------------------- the constant pool
;;
;; Built by INTERNING as the method bodies are written, so nothing keeps a
;; parallel list of what a body will need. The pool is a map from a descriptor
;; vector to an index plus the entries in order.

(defn pool [] {:index {} :order [] :next 1})

(defn- intern1 [p k entry]
  (if-let [i (get (:index p) k)]
    [p i]
    (let [i (:next p)]
      [{:index (assoc (:index p) k i)
        :order (conj (:order p) entry)
        :next (inc i)}
       i])))

(defn utf8 [p s]
  (intern1 p [:utf8 s] [:utf8 s]))

;; --------------------------------------------------- arbitrary bytes as a constant
;;
;; A class file HAS NO DATA SECTION. ECMA-335 has `FieldRVA`, which lets a CLR
;; field point straight at raw bytes in a section; the JVM has no analogue, so
;; constant data has to be a constant-pool entry, and the only entry that holds a
;; run of bytes is `CONSTANT_Utf8_info`.
;;
;; Its payload is MODIFIED UTF-8, not raw bytes, and not real UTF-8 either:
;;
;;   * `0x00` is TWO bytes, `0xC0 0x80`. Real UTF-8 encodes it as one, and a
;;     single `0x00` in the payload is illegal here -- so `flint.rt/str->b` is
;;     the wrong encoder for this and would produce a class file that loads until
;;     the first NUL in the data.
;;   * `0x01`..`0x7F` is one byte.
;;   * `0x80`..`0xFF` is a code point in `U+0080..U+00FF`, so two bytes.
;;
;; Measured on real images rather than assumed: 1.417x for a 26 715-byte program
;; (32.7% zero bytes, 9.1% high) and 1.328x for the 206 383-byte compiler. The
;; uniform-random figure is 1.5x and a flint image is not uniform.
;;
;; `length` is a `u2`, so 65 535 encoded bytes is the hard cap per entry and
;; anything bigger is CHUNKED by the caller. `chunk-bound` says where to cut.

(defn- mutf8-len [b] (if (or (= 0 b) (>= b 0x80)) 2 1))

(defn chunk-bound
  "How many bytes of `bs` from `at` fit in one constant-pool entry.

  Counted in ENCODED bytes, which is the whole point: cutting on a fixed count of
  source bytes overflows the `u2` on data that happens to be zero-heavy, and the
  class file is then rejected for a length field that wrapped."
  [bs at limit]
  (let [n (flint.rt/b-count bs)]
    (loop [i at enc 0]
      (if (>= i n)
        (- i at)
        (let [w (mutf8-len (flint.rt/b-at bs i))]
          (if (> (+ enc w) limit)
            (- i at)
            (recur (inc i) (+ enc w))))))))

(def UTF8-MAX 65535)

(defn- mutf8 [bs from to]
  (let [out (volatile! (flint.rt/b-transient (flint.rt/str->b "")))]
    (loop [i from]
      (when (< i to)
        (let [b (flint.rt/b-at bs i)]
          (cond
            (= 0 b) (do (vswap! out flint.rt/b-conj! 0xC0) (vswap! out flint.rt/b-conj! 0x80))
            (< b 0x80) (vswap! out flint.rt/b-conj! b)
            :else (do (vswap! out flint.rt/b-conj! (bit-or 0xC0 (bit-shift-right b 6)))
                      (vswap! out flint.rt/b-conj! (bit-or 0x80 (bit-and b 0x3f))))))
        (recur (inc i))))
    (flint.rt/b-persistent! @out)))

(defn blob
  "A `CONSTANT_String` whose characters are the bytes `bs[from..to)`, one
  character per byte.

  ONE CHARACTER PER BYTE, not a bit-packing. Seven-bit packing would encode the
  same 26 715 bytes in 30 532 rather than 37 867 -- measured, 24% less -- and it is
  still the wrong trade here. The decoder for this is `(byte) s.charAt(i)`, three
  lines with no bit-stream state, and the CLR's is the same three lines over a
  UTF-16 literal; a packed stream is a second encoder/decoder pair that has to
  agree across two runtimes forever, which is the kind of duplication this project
  pays for repeatedly (AGENTS.md §1). Twenty-four percent of the image is 1.2% of
  the artifact, because the interpreter's classes dominate it."
  [p bs from to]
  (let [payload (mutf8 bs from to)
        [p u] (intern1 p [:blob from to] [:rawutf8 payload])]
    (intern1 p [:blobstr from to] [:string u])))

(defn class-ref
  "A CONSTANT_Class, whose name is an INTERNAL name -- `com/flint/rt/Sandbox`,
  slashes not dots, and a nested class is `Outer$Inner`."
  [p internal-name]
  (let [[p n] (utf8 p internal-name)]
    (intern1 p [:class internal-name] [:class n])))

(defn name-and-type [p nm desc]
  (let [[p n] (utf8 p nm)
        [p d] (utf8 p desc)]
    (intern1 p [:nat nm desc] [:nat n d])))

(defn- ref* [p kind owner nm desc]
  (let [[p c] (class-ref p owner)
        [p nt] (name-and-type p nm desc)]
    (intern1 p [kind owner nm desc] [kind c nt])))

(defn method-ref [p owner nm desc] (ref* p :methodref owner nm desc))
(defn field-ref [p owner nm desc] (ref* p :fieldref owner nm desc))

(defn- cp-bytes [p]
  [(u16 (:next p))
   (mapv (fn [e]
           (let [t (first e)]
             (cond
               ;; A NAME or a DESCRIPTOR, which are ASCII here, so real UTF-8 and
               ;; modified UTF-8 agree on every byte. `:rawutf8` is the one that
               ;; carries data and does its own encoding.
               (= t :utf8) (let [b (flint.rt/str->b (nth e 1))]
                             [1 (u16 (flint.rt/b-count b)) b])
               (= t :rawutf8) (let [b (nth e 1)]
                                [1 (u16 (flint.rt/b-count b)) b])
               (= t :string) [8 (u16 (nth e 1))]
               (= t :class) [7 (u16 (nth e 1))]
               (= t :nat) [12 (u16 (nth e 1)) (u16 (nth e 2))]
               (= t :fieldref) [9 (u16 (nth e 1)) (u16 (nth e 2))]
               (= t :methodref) [10 (u16 (nth e 1)) (u16 (nth e 2))]
               :else (throw (ex-info "unknown constant-pool entry" {:entry e})))))
         (:order p))])

;; ----------------------------------------------------------------- opcodes
;;
;; Only what a delegation needs. `aload_0`..`aload_3` are the one-byte forms; a
;; fifth argument would need `aload <u8>` (0x19) and nothing here has one.

(def ALOAD [0x2a 0x2b 0x2c 0x2d])
(def DUP 0x59)
(def SIPUSH 0x11)
;; `ldc_w`, NEVER `ldc`. `ldc` takes a ONE-byte pool index, and a program whose
;; image needs several chunks pushes the pool past 255 entries -- at which point
;; `ldc` silently addresses the wrong constant instead of failing. The wide form
;; costs one byte per push and cannot do that.
(def LDC-W 0x13)
(def ANEWARRAY 0xbd)
(def AASTORE 0x53)
(def GETSTATIC 0xb2)
(def PUTSTATIC 0xb3)
(def INVOKEVIRTUAL 0xb6)
(def INVOKESTATIC 0xb8)
(def IRETURN 0xac)
(def ARETURN 0xb0)
(def RETURN 0xb1)

(defn- code-attr
  "A Code attribute with no exception table and no attributes of its own.

  NO StackMapTable, and that is legal rather than lucky: JVMS 4.7.4 requires one
  only where the verifier has to merge two paths, and a method with no branch has
  none. `javap -v` on the result shows `Code:` with `stack=`, `locals=` and no
  frames, exactly as `javac` emits for a one-line delegation."
  [p code max-stack max-locals]
  (let [[p n] (utf8 p "Code")
        body (jar/bytes-of code)
        len (flint.rt/b-count body)]
    [p [(u16 n)
        (u32 (+ 2 2 4 len 2 2))
        (u16 max-stack) (u16 max-locals)
        (u32 len) body
        (u16 0)                                  ; exception_table_length
        (u16 0)]]))                              ; attributes_count

(defn method
  "One method. `body` is a byte tree of opcodes, already resolved against `p`."
  [p access nm desc body max-stack max-locals]
  (let [[p n] (utf8 p nm)
        [p d] (utf8 p desc)
        [p c] (code-attr p body max-stack max-locals)]
    [p [(u16 access) (u16 n) (u16 d) (u16 1) c]]))

(defn field [p access nm desc]
  (let [[p n] (utf8 p nm)
        [p d] (utf8 p desc)]
    [p [(u16 access) (u16 n) (u16 d) (u16 0)]]))

(defn class-file
  "Assemble it. `fields` and `methods` are the byte trees `field`/`method` built
  against the SAME pool `p`, which must therefore be the pool they left behind."
  [p access this-internal super-internal fields methods]
  (let [[p this] (class-ref p this-internal)
        [p super] (class-ref p super-internal)]
    ;; The pool goes in AFTER `this` and `super` are interned, which is why it is
    ;; read here and not by the caller: a pool serialised before the last
    ;; `class-ref` is a pool missing two entries, and the class loads until
    ;; something reads index `this`.
    (jar/bytes-of
     [(u32 0xCAFEBABE) (u16 MINOR) (u16 MAJOR)
      (cp-bytes p)
      (u16 access) (u16 this) (u16 super)
      (u16 0)
      (u16 (count fields)) fields
      (u16 (count methods)) methods
      (u16 0)])))
