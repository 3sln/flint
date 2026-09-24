(ns flint.clr
  "An ECMA-335 assembly writer: enough to emit a loadable .NET assembly from
  scratch, with the bytecode image as a static byte array in `.text`.

  THE IMAGE IS A `FieldRva` FIELD, not a managed resource and not an appended
  trailer. A static field whose `Flags` carry `HasFieldRVA` has no value in the
  metadata at all -- its row in the `FieldRva` table names an RVA, and the bytes
  live in `.text` like code does. `ldsflda` on such a field yields a pointer to
  them. This is what a C# `ReadOnlySpan<byte>` literal compiles to, and what
  `System.Reflection.Emit`'s `DefineInitializedData` builds underneath, so it is
  the well-trodden path rather than a trick.

  Two consequences worth stating, because both were measured rather than
  assumed:

  **It keeps the heaps small.** An image in the `#Blob` heap would push that
  heap past 64 KB on any real program, which flips every blob index in every
  table from two bytes to four -- the `HeapSizes` bit. In `.text` the image
  contributes nothing to any heap, so the widths stay narrow no matter how big
  the program is.

  **It is the only part with an alignment rule of its own.** See `text-layout`.

  ## Why written by hand rather than through a host API

  `System.Reflection.Emit.PersistedAssemblyBuilder` exists on .NET 9+ and does
  all of this. It is also unreachable: flint's compiler runs on babashka and on
  flint, neither of which is .NET. Same reason `flint.wasm` writes wasm by hand
  next to a linker that could have.

  ## Portability

  Byte strings throughout, via `flint.rt/b-*`, for the reason `flint.wasm` gives
  in its own header: a namespace that reaches for host byte arrays is a
  namespace the self-hosted compiler cannot run. Nothing here touches a host
  type. The caller writes the returned byte string to a file, which is the only
  host-specific step and is deliberately not in here.

  ## What this does NOT do

  This emits one type, one `FieldRva` array, and three static methods. It is
  the container, proven: a real `:to :clr` needs `MemberRef`/`TypeSpec` for
  calls into the runtime, a general IL assembler, and the AOT codegen. See the
  report for what each costs."
  (:require [flint.rt]))

;; ---------------------------------------------------------------- byte output
;;
;; Fixed-width little-endian, not LEB128: ECMA-335 uses LEB-like compression
;; ONLY inside blobs (`cint` below). Everything in a header or a table row is
;; fixed width, and mixing the two up is the first way this goes wrong.

(defn- u16 [n] [(bit-and n 0xff) (bit-and (bit-shift-right n 8) 0xff)])

(defn- u32 [n] [(bit-and n 0xff)
                (bit-and (bit-shift-right n 8) 0xff)
                (bit-and (bit-shift-right n 16) 0xff)
                (bit-and (bit-shift-right n 24) 0xff)])

(defn- u64 [n] (concat (u32 n) (u32 (unsigned-bit-shift-right n 32))))

(defn ->bytes
  "Flatten a nested structure of byte values and byte strings into one byte
  string. Through a transient, for `flint.wasm/->bytes`'s reason: this is the
  output path for a whole assembly."
  [x]
  (let [out (flint.rt/b-transient (flint.rt/str->b ""))]
    ((fn walk [v]
       (cond
         (nil? v) nil
         (integer? v) (flint.rt/b-conj! out (bit-and v 0xff))
         (flint.rt/bytes? v) (flint.rt/b-append! out v)
         (sequential? v) (run! walk v)
         :else (throw (ex-info "not byte-able" {:v v}))))
     x)
    (flint.rt/b-persistent! out)))

(defn- align
  "`n` rounded up to a multiple of `a`."
  [n a]
  (let [r (mod n a)] (if (zero? r) n (+ n (- a r)))))

(defn- zeros [n] (repeat n 0))

(defn cint
  "An ECMA-335 compressed unsigned integer (II.23.2). One, two or four bytes,
  BIG-endian inside the compressed form -- the opposite of everything else here,
  which is exactly why it has its own function."
  [n]
  (cond
    (< n 0x80) [n]
    (< n 0x4000) [(bit-or 0x80 (bit-shift-right n 8)) (bit-and n 0xff)]
    :else [(bit-or 0xc0 (bit-shift-right n 24))
           (bit-and (bit-shift-right n 16) 0xff)
           (bit-and (bit-shift-right n 8) 0xff)
           (bit-and n 0xff)]))

;; ---------------------------------------------------------------------- heaps
;;
;; HEAPS ARE BUILT BEFORE TABLES, and that ordering is forced rather than
;; chosen: a table row's heap indices are two or four bytes wide depending on
;; the FINAL size of each heap, so no row can be encoded until every heap is
;; closed. Nothing in a heap depends on a table index in return -- the
;; `TypeDefOrRef` that appears inside a signature blob is a COMPRESSED index,
;; whose width does not vary -- so there is no cycle, only an order.

(defn- intern-str
  [h s]
  (if (or (= s "") (contains? (:index h) s))
    h
    (let [b (flint.rt/str->b s)]
      {:index (assoc (:index h) s (:size h))
       :parts (conj (:parts h) [b 0])
       :size (+ (:size h) (flint.rt/b-count b) 1)})))

(defn- strings-heap
  "`#Strings`: null-terminated UTF-8, with the empty string at offset 0."
  [ss]
  (reduce intern-str {:index {"" 0} :parts [[0]] :size 1} ss))

(defn- intern-blob
  [h v]
  (let [k (vec v)]
    (if (contains? (:index h) k)
      h
      (let [pre (cint (count k))]
        {:index (assoc (:index h) k (:size h))
         :parts (conj (:parts h) [pre k])
         :size (+ (:size h) (count pre) (count k))}))))

(defn- blob-heap
  "`#Blob`: each entry a compressed length then the bytes, with the empty blob
  at offset 0. Deduplicated -- two methods with the same signature share a
  blob, which is not an optimisation but what every real assembly does."
  [blobs]
  (reduce intern-blob {:index {[] 0} :parts [[0]] :size 1} blobs))

;; ------------------------------------------------------------- coded indices
;;
;; A coded index packs a TAG naming which table into the low bits and the row id
;; above it. Its WIDTH is two bytes unless some table in its tag set has more
;; rows than the tag bits leave room for, which for anything this file emits it
;; never does. The general rule is in `coded-width`; it is here so that the
;; place it will have to be used from later already exists.

(def ^:private coded-tags
  "Tag values per coded-index kind, by table index. ECMA-335 II.24.2.6."
  {:type-def-or-ref   {:bits 2 :tags {0x02 0, 0x01 1, 0x1b 2}}
   :resolution-scope  {:bits 2 :tags {0x00 0, 0x1a 1, 0x23 2, 0x01 3}}
   :member-ref-parent {:bits 3 :tags {0x02 0, 0x01 1, 0x1a 2, 0x06 3, 0x1b 4}}})

(defn- coded
  "Encode row `rid` of table `table` as a coded index of `kind`."
  [kind table rid]
  (let [{:keys [bits tags]} (get coded-tags kind)
        tag (get tags table)]
    (when (nil? tag)
      (throw (ex-info "table not in this coded-index kind" {:kind kind :table table})))
    (bit-or (bit-shift-left rid bits) tag)))

(defn- coded-width
  "Two bytes, or four when any table in the tag set is too long to fit its rid
  beside the tag. `rows` maps table index to row count."
  [kind rows]
  (let [{:keys [bits tags]} (get coded-tags kind)
        limit (bit-shift-left 1 (- 16 bits))]
    (if (some (fn [t] (>= (get rows t 0) limit)) (keys tags)) 4 2)))

(defn- ix
  "A table or heap index, `w` bytes wide."
  [w n]
  (if (= w 2) (u16 n) (u32 n)))

;; ------------------------------------------------------------------ IL bodies

(def ^:private op
  {:ldarg.0 0x02 :ldloc.0 0x06 :ldloc.1 0x07 :stloc.0 0x0a :stloc.1 0x0b
   :ldc.i4.0 0x16 :ldc.i4.1 0x17 :ldc.i4 0x20 :ret 0x2a :br.s 0x2b
   :blt.un.s 0x37 :ldind.u1 0x47 :add 0x58 :ldsflda 0x7f})

(defn- tiny-body
  "A method body in the tiny format: one header byte. Only legal with no locals,
  code under 64 bytes and a stack no deeper than 8 -- and `fat-body` is not an
  optimisation away from it, it is the only legal form once any of those fails."
  [code]
  (let [n (count code)]
    (when (>= n 64) (throw (ex-info "tiny body too long" {:len n})))
    [(bit-or (bit-shift-left n 2) 0x02) code]))

(defn- fat-body
  "The 12-byte fat header, which must be 4-BYTE ALIGNED -- the caller pads. A
  body with locals has no tiny form, whatever its size.

  `max-stack` IS NOT OPTIONAL and is not derivable from the header: the runtime
  reads it to size the frame and rejects a body whose declared depth is too
  small. Getting it wrong yields an `InvalidProgramException` at first call,
  with no hint that the number is what was wrong."
  [code max-stack local-sig-tok]
  (concat (u16 (bit-or (bit-shift-left 3 12) 0x13))   ; 3 dwords, fat + InitLocals
          (u16 max-stack)
          (u32 (count code))
          (u32 local-sig-tok)
          code))

;; ------------------------------------------------------------- .text layout

(def ^:private text-rva 0x2000)
(def ^:private text-file-off 0x200)
(def ^:private cli-header-size 72)
(def ^:private dos-header-size 128)

(def ^:private headers-size
  "DOS stub, PE signature, COFF header, optional header, one section header.
  Written as the sum rather than as a literal because getting it wrong shifts
  every file offset in the image and the loader's complaint names none of it --
  the first version of this forgot the 40-byte section header."
  (+ dos-header-size 4 20 224 40))

(defn text-layout
  "Where everything inside `.text` goes, and the rule for each boundary.

      +0x00  CLI header, 72 bytes      (the COR20 data directory points here)
             method bodies             4-byte aligned, because a fat header is
             the image bytes           8-byte aligned, see below
             metadata root             4-byte aligned

  THE IMAGE'S ALIGNMENT IS THE ONE REAL CONSTRAINT and it is mild. ECMA-335
  II.16.3 says `FieldRva` data must be aligned for its type; for a byte array
  the type's own requirement is 1, so any offset is legal. 8 is used anyway,
  because a `FieldRva` blob is the natural place to put something a host may
  later want to read as wider words, and the cost is at most seven bytes.

  There is NO size limit that bites. The field's declaring type carries its
  length in a `ClassLayout.ClassSize`, a u32, so the ceiling is 4 GB; `.text`
  itself is bounded by `SizeOfImage`, also a u32. A 26 KB image is nothing, and
  a 26 MB one would still only widen the section. What DOES grow with the image
  is the file, byte for byte -- there is no compression anywhere in a PE.

  Returns the offsets relative to the start of `.text`."
  [body-bytes image-len]
  (let [bodies-at (align cli-header-size 4)
        image-at (align (+ bodies-at body-bytes) 8)
        md-at (align (+ image-at image-len) 4)]
    {:bodies bodies-at :image image-at :metadata md-at}))

;; ------------------------------------------------------------------ metadata

(def ^:private md-version "v4.0.30319")

(defn- stream-header
  [offset size name]
  (let [b (flint.rt/str->b name)
        n (flint.rt/b-count b)]
    [(u32 offset) (u32 size) b (zeros (- (align (inc n) 4) n))]))

(defn- tables-stream
  "The `#~` stream: a header naming which tables are present, then their row
  counts, then the rows themselves."
  [rows row-bytes heap-sizes]
  (let [present (sort (keys rows))]
    [(u32 0) 2 0 heap-sizes 1
     (u64 (reduce (fn [m t] (bit-or m (bit-shift-left 1 t))) 0 present))
     ;; The canonical `Sorted` mask. Bits for tables that are REQUIRED to be
     ;; sorted by their key; setting a bit for a table this assembly does not
     ;; emit is harmless, and the constant is what every compiler writes.
     (u64 0x000016003301fa00)
     (for [t present] (u32 (get rows t)))
     (for [t present] (get row-bytes t))]))

(defn- metadata
  [{:keys [rows row-bytes heap-sizes strings blobs guid]}]
  (let [ver (flint.rt/str->b md-version)
        ver-len (align (inc (flint.rt/b-count ver)) 4)
        tables (->bytes (tables-stream rows row-bytes heap-sizes))
        tables-b (->bytes [tables (zeros (- (align (flint.rt/b-count tables) 4)
                                           (flint.rt/b-count tables)))])
        pad (fn [b] (->bytes [b (zeros (- (align (flint.rt/b-count b) 4)
                                          (flint.rt/b-count b)))]))
        sb (pad (->bytes (:parts strings)))
        bb (pad (->bytes (:parts blobs)))
        gb (->bytes guid)
        us (->bytes [0 0 0 0])
        ;; The root: signature, version, then one header per stream. Headers are
        ;; fixed size once the names are known, so the stream OFFSETS can be
        ;; computed before the streams are placed.
        root-size (+ 4 2 2 4 4 ver-len 2 2
                     (reduce + (for [n ["#~" "#Strings" "#US" "#GUID" "#Blob"]]
                                 (+ 8 (align (inc (count n)) 4)))))
        o1 root-size
        o2 (+ o1 (flint.rt/b-count tables-b))
        o3 (+ o2 (flint.rt/b-count sb))
        o4 (+ o3 (flint.rt/b-count us))
        o5 (+ o4 (flint.rt/b-count gb))]
    (->bytes
      [(u32 0x424a5342)                                   ; "BSJB"
       (u16 1) (u16 1) (u32 0)                            ; major, minor, reserved
       (u32 ver-len) ver (zeros (- ver-len (flint.rt/b-count ver)))
       (u16 0) (u16 5)                                    ; flags, stream count
       (stream-header o1 (flint.rt/b-count tables-b) "#~")
       (stream-header o2 (flint.rt/b-count sb) "#Strings")
       (stream-header o3 (flint.rt/b-count us) "#US")
       (stream-header o4 (flint.rt/b-count gb) "#GUID")
       (stream-header o5 (flint.rt/b-count bb) "#Blob")
       tables-b sb us gb bb])))

;; ------------------------------------------------------------------ the shape
;;
;; ONE TYPE, ONE ARRAY, THREE METHODS. The methods are chosen to exercise the
;; parts that go wrong rather than to be useful:
;;
;;   Length()   tiny body, a 4-byte inline operand
;;   At(int)    tiny body, reads THROUGH the `FieldRva` pointer
;;   Sum()      fat body: locals, a `StandAloneSig`, a forward and a backward
;;              short branch, and a `MaxStack` that is not 8
;;
;; `Sum` is the one that matters. If the image bytes were not really in `.text`
;; at the RVA the `FieldRva` row claims, it would return a wrong total rather
;; than failing, so the caller compares it against a sum computed outside.

(defn assemble
  "Emit a loadable .NET assembly carrying `image` as a static byte array.

  `{:name \"T\" :image <byte-string>}` -> byte string."
  [{:keys [name image]}]
  (let [img-len (flint.rt/b-count image)
        fld-tok 0x04000001                                ; Field table, row 1
        sig-tok 0x11000001                                ; StandAloneSig, row 1

        ;; ---- IL, with the branch displacements worked out by hand. A real
        ;; assembler needs a fixup pass; three methods do not, and pretending
        ;; otherwise would hide that the pass is missing.
        il-length (->bytes [(:ldc.i4 op) (u32 img-len) (:ret op)])
        il-at (->bytes [(:ldsflda op) (u32 fld-tok) (:ldarg.0 op)
                        (:add op) (:ldind.u1 op) (:ret op)])
        il-sum (->bytes
                 [(:ldc.i4.0 op) (:stloc.0 op)            ; 0  sum = 0
                  (:ldc.i4.0 op) (:stloc.1 op)            ; 2  i = 0
                  (:br.s op) 0x0f                         ; 4  -> test at 21
                  (:ldloc.0 op)                           ; 6
                  (:ldsflda op) (u32 fld-tok)             ; 7
                  (:ldloc.1 op) (:add op) (:ldind.u1 op)  ; 12
                  (:add op) (:stloc.0 op)                 ; 15
                  (:ldloc.1 op) (:ldc.i4.1 op)            ; 17
                  (:add op) (:stloc.1 op)                 ; 19
                  (:ldloc.1 op)                           ; 21 test
                  (:ldc.i4 op) (u32 img-len)              ; 22
                  (:blt.un.s op) 0xe9                     ; 27 -> body at 6
                  (:ldloc.0 op) (:ret op)])               ; 29

        bodies (->bytes [(tiny-body il-length)
                         (tiny-body il-at)
                         ;; 4-BYTE ALIGNED. Two tiny bodies of 6 and 9 bytes
                         ;; plus their header bytes land at 17, so this pad is
                         ;; load-bearing rather than cosmetic.
                         (zeros (- (align 17 4) 17))
                         (fat-body il-sum 3 sig-tok)])
        body-len (flint.rt/b-count bodies)
        lay (text-layout body-len img-len)

        rva-length (+ text-rva (:bodies lay))
        rva-at (+ rva-length 7)                           ; 1 header + 6 code
        rva-sum (+ text-rva (align (+ (:bodies lay) 17) 4))
        rva-image (+ text-rva (:image lay))

        ;; ---- heaps. Every string and blob this assembly names, interned
        ;; before a single row is encoded.
        arr-type (str "$ArrayType$" img-len)
        strs [(str name ".dll") "<Module>" "Program" arr-type
              "Object" "System" "ValueType" "IMAGE" "Length" "At" "Sum"
              name "System.Runtime"]
        sh (strings-heap strs)

        sig-field [0x06 0x11 (first (cint (coded :type-def-or-ref 0x02 3)))]
        sig-length [0x00 0x00 0x08]                       ; () -> int32
        sig-at [0x00 0x01 0x08 0x08]                      ; (int32) -> int32
        sig-locals [0x07 0x02 0x08 0x08]                  ; 2 locals, int32
        ;; The ECMA public key TOKEN for the framework assemblies. A token, not
        ;; a key: `AssemblyRef.Flags` leaves bit 0 clear to say which it is.
        ecma-token [0xb0 0x3f 0x5f 0x7f 0x11 0xd5 0x0a 0x3a]
        bh (blob-heap [sig-field sig-length sig-at sig-locals ecma-token])

        S (fn [s] (get (:index sh) s))
        B (fn [b] (get (:index bh) (vec b)))

        ;; ---- row counts, hence index widths. This is the size-first pass.
        rows {0x00 1 0x01 2 0x02 3 0x04 1 0x06 3
              0x0f 1 0x11 1 0x1d 1 0x20 1 0x23 1}
        ;; Heap index widths: bit 0 #Strings, bit 1 #GUID, bit 2 #Blob.
        heap-sizes (bit-or (if (>= (:size sh) 0x10000) 1 0)
                           (if (>= 16 0x10000) 2 0)
                           (if (>= (:size bh) 0x10000) 4 0))
        ws (if (zero? (bit-and heap-sizes 1)) 2 4)
        wg (if (zero? (bit-and heap-sizes 2)) 2 4)
        wb (if (zero? (bit-and heap-sizes 4)) 2 4)
        wt (fn [t] (if (>= (get rows t 0) 0x10000) 4 2))
        wtdr (coded-width :type-def-or-ref rows)
        wrs (coded-width :resolution-scope rows)

        row-bytes
        {;; Module
         0x00 [(u16 0) (ix ws (S (str name ".dll"))) (ix wg 1) (ix wg 0) (ix wg 0)]
         ;; TypeRef: System.Object, System.ValueType -- both in AssemblyRef 1
         0x01 [[(ix wrs (coded :resolution-scope 0x23 1)) (ix ws (S "Object")) (ix ws (S "System"))]
               [(ix wrs (coded :resolution-scope 0x23 1)) (ix ws (S "ValueType")) (ix ws (S "System"))]]
         ;; TypeDef. FieldList/MethodList are RANGE STARTS: a row owns from its
         ;; own start up to the next row's, which is why `<Module>` and
         ;; `Program` both say 1 and `<Module>` therefore owns nothing.
         0x02 [[(u32 0) (ix ws (S "<Module>")) (ix ws 0) (ix wtdr 0) (ix (wt 0x04) 1) (ix (wt 0x06) 1)]
               [(u32 0x00100001)                          ; Public | BeforeFieldInit
                (ix ws (S "Program")) (ix ws 0)
                (ix wtdr (coded :type-def-or-ref 0x01 1)) ; extends System.Object
                (ix (wt 0x04) 1) (ix (wt 0x06) 1)]
               [(u32 0x00000111)                          ; Private | ExplicitLayout | Sealed
                (ix ws (S arr-type)) (ix ws 0)
                (ix wtdr (coded :type-def-or-ref 0x01 2)) ; extends System.ValueType
                (ix (wt 0x04) 2) (ix (wt 0x06) 4)]]
         ;; Field: Static | Private | HasFieldRVA
         0x04 [(u16 0x0111) (ix ws (S "IMAGE")) (ix wb (B sig-field))]
         ;; MethodDef: Public | Static | HideBySig
         0x06 [[(u32 rva-length) (u16 0) (u16 0x0016) (ix ws (S "Length")) (ix wb (B sig-length)) (ix (wt 0x08) 1)]
               [(u32 rva-at) (u16 0) (u16 0x0016) (ix ws (S "At")) (ix wb (B sig-at)) (ix (wt 0x08) 1)]
               [(u32 rva-sum) (u16 0) (u16 0x0016) (ix ws (S "Sum")) (ix wb (B sig-length)) (ix (wt 0x08) 1)]]
         ;; ClassLayout: this is where the array's LENGTH is declared.
         0x0f [(u16 1) (u32 img-len) (ix (wt 0x02) 3)]
         0x11 [(ix wb (B sig-locals))]
         0x1d [(u32 rva-image) (ix (wt 0x04) 1)]
         ;; Assembly. HashAlgId 0 -- nothing hashes an assembly that is not
         ;; strong-named, and this one is not.
         0x20 [(u32 0) (u16 0) (u16 0) (u16 0) (u16 0) (u32 0)
               (ix wb 0) (ix ws (S name)) (ix ws 0)]
         ;; AssemblyRef: System.Runtime 10.0.0.0. The FACADE, not
         ;; System.Private.CoreLib -- it is what a compiler references and it
         ;; type-forwards to whatever the running framework actually uses.
         0x23 [(u16 10) (u16 0) (u16 0) (u16 0) (u32 0)
               (ix wb (B ecma-token)) (ix ws (S "System.Runtime")) (ix ws 0) (ix wb 0)]}

        md (metadata {:rows rows :row-bytes row-bytes :heap-sizes heap-sizes
                      :strings sh :blobs bh
                      ;; The MVID. Fixed, so the same input gives the same
                      ;; bytes: a compiler whose output differs run to run
                      ;; cannot be diffed, and a fresh GUID would buy nothing.
                      :guid (concat (u32 0x5f544e4c) (u32 0x00544e49)
                                    (u32 0x00000001) (u32 0x00000001))})
        md-len (flint.rt/b-count md)

        text (->bytes
               [;; CLI header (COR20)
                (u32 cli-header-size) (u16 2) (u16 5)
                (u32 (+ text-rva (:metadata lay))) (u32 md-len)
                (u32 1)                                   ; ILONLY
                (u32 0)                                   ; no entry point: a library
                (zeros 48)                                ; the six empty directories
                (zeros (- (:bodies lay) cli-header-size))
                bodies
                (zeros (- (:image lay) (:bodies lay) body-len))
                image
                (zeros (- (:metadata lay) (:image lay) img-len))
                md])
        text-len (flint.rt/b-count text)
        text-raw (align text-len 0x200)
        size-of-image (align (+ text-rva text-len) 0x2000)]
    (->bytes
      [;; ---- DOS header. 128 bytes, and the only part that matters is the
       ;; `e_lfanew` at 0x3c saying where the PE header starts.
       [0x4d 0x5a 0x90 0x00 0x03 0x00 0x00 0x00 0x04 0x00 0x00 0x00 0xff 0xff 0x00 0x00
        0xb8 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x40 0x00 0x00 0x00 0x00 0x00 0x00 0x00]
       ;; 32 bytes above, then zeros up to 0x3c. COUNTED, not eyeballed: the
       ;; first version of this had 24 here and put `e_lfanew` at 0x38, and the
       ;; only symptom was `BadImageFormatException: Image is either too small
       ;; or contains an invalid byte offset` from inside `SkipDosHeader`.
       (zeros (- 0x3c 32))
       [0x80 0x00 0x00 0x00]                              ; e_lfanew at 0x3c
       [0x0e 0x1f 0xba 0x0e 0x00 0xb4 0x09 0xcd 0x21 0xb8 0x01 0x4c 0xcd 0x21]
       (flint.rt/str->b "This program cannot be run in DOS mode.")
       [0x0d 0x0d 0x0a 0x24 0x00]
       (zeros (- dos-header-size 0x40 14 39 5))

       ;; ---- PE signature and COFF header
       (flint.rt/str->b "PE") 0 0
       (u16 0x014c)                                       ; I386, which is what AnyCPU is
       (u16 1)                                            ; one section
       (u32 0) (u32 0) (u32 0)                            ; timestamp, symbols
       (u16 224) (u16 0x2002)                             ; opt header size; ExecutableImage | Dll

       ;; ---- optional header, PE32
       (u16 0x010b) 11 0
       (u32 text-raw) (u32 0) (u32 0)
       (u32 0)                                            ; AddressOfEntryPoint: none
       (u32 text-rva) (u32 0)
       (u32 0x400000)                                     ; ImageBase
       (u32 0x2000) (u32 0x200)                           ; section, file alignment
       (u16 4) (u16 0) (u16 0) (u16 0) (u16 4) (u16 0)
       (u32 0)
       (u32 size-of-image) (u32 0x200)                    ; SizeOfImage, SizeOfHeaders
       (u32 0)                                            ; CheckSum: unused for managed
       (u16 3) (u16 0x8540)                               ; WindowsCui; NX | DynamicBase | ...
       (u32 0x100000) (u32 0x1000) (u32 0x100000) (u32 0x1000)
       (u32 0) (u32 16)
       ;; Sixteen data directories, of which ONLY number 14 is set. No import
       ;; table, no IAT, no relocations: those exist for the legacy Windows
       ;; loader's `mscoree!_CorDllMain` stub, and a library with no entry
       ;; point on CoreCLR needs none of them.
       (for [i (range 16)]
         (if (= i 14)
           [(u32 text-rva) (u32 cli-header-size)]
           [(u32 0) (u32 0)]))

       ;; ---- section table
       (flint.rt/str->b ".text") (zeros 3)
       (u32 text-len) (u32 text-rva) (u32 text-raw) (u32 text-file-off)
       (u32 0) (u32 0) (u16 0) (u16 0)
       (u32 0x60000020)                                   ; CODE | EXECUTE | READ
       (zeros (- text-file-off headers-size))

       ;; ---- the section itself
       text (zeros (- text-raw text-len))])))
