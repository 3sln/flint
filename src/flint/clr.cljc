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

  One type, one `FieldRva` array, and six static methods -- the container and a
  real CIL assembler, both proven. Still missing for a full `:to :clr`: N types
  (only methods are data-driven here), `TypeSpec` for generic instantiations,
  `CustomAttribute` (so no `TargetFrameworkAttribute`), an entry point for the
  exe variant, and the bytecode-to-CIL translation itself -- which is
  `flint.aot`'s job for wasm and has no CLR counterpart yet."
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

;; ----------------------------------------------------------------- CIL: the ISA
;;
;; `flint.wasm` has no counterpart to this section, and the reason is worth
;; stating because it is the one place CIL is genuinely harder than wasm rather
;; than merely different. Wasm needs NEITHER of the two things below:
;;
;;   * its branches name a LABEL DEPTH, not a byte displacement, so there is no
;;     short/long form to choose and no fixup pass;
;;   * its stack is VALIDATED by the engine from the types, so a function never
;;     declares how deep it gets.
;;
;; CIL needs both. A branch carries a signed displacement that is one byte or
;; four, and a method body declares `MaxStack`, which the runtime believes.
;;
;; What IS mirrored is `flint.aot/max-depth`, deliberately: `max-stack` below is
;; the same worklist over the same kind of graph, for the reason that function's
;; docstring gives -- two edges of one branch leave different depths, so a
;; running total quietly gets one of them wrong. Its refusal discipline is
;; mirrored too: an instruction whose effect is unknown returns nil and refuses
;; the method, because a depth this cannot bound is not one to guess.

(def ^:private CIL
  "Opcode table. `:pop`/`:push` are the stack effect; nil means the instruction
  must carry its own, which is how `call` works -- the effect is in the callee's
  signature and this table cannot see it.

  `:short`/`:long` mark a branch, whose opcode depends on the displacement the
  fixup pass settles on. `:wide` marks one whose encoding depends on its
  operand, which is the same idea one level down."
  {:nop       {:code [0x00] :operand :none :pop 0 :push 0}
   :dup       {:code [0x25] :operand :none :pop 1 :push 2}
   :pop       {:code [0x26] :operand :none :pop 1 :push 0}
   :ldnull    {:code [0x14] :operand :none :pop 0 :push 1}
   :ldc.i4    {:operand :i4  :pop 0 :push 1 :wide :ldc-i4}
   :ldc.i8    {:code [0x21] :operand :i8 :pop 0 :push 1}
   :ldc.r8    {:code [0x23] :operand :r8 :pop 0 :push 1}
   :ldstr     {:code [0x72] :operand :tok :pop 0 :push 1}
   :ldarg     {:operand :index :pop 0 :push 1 :wide :ldarg}
   :ldloc     {:operand :index :pop 0 :push 1 :wide :ldloc}
   :stloc     {:operand :index :pop 1 :push 0 :wide :stloc}
   :ldloca    {:operand :index :pop 0 :push 1 :wide :ldloca}
   :ldsfld    {:code [0x7e] :operand :tok :pop 0 :push 1}
   :ldsflda   {:code [0x7f] :operand :tok :pop 0 :push 1}
   :stsfld    {:code [0x80] :operand :tok :pop 1 :push 0}
   :ldfld     {:code [0x7b] :operand :tok :pop 1 :push 1}
   :ldflda    {:code [0x7c] :operand :tok :pop 1 :push 1}
   :stfld     {:code [0x7d] :operand :tok :pop 2 :push 0}
   :ldind.i1  {:code [0x46] :operand :none :pop 1 :push 1}
   :ldind.u1  {:code [0x47] :operand :none :pop 1 :push 1}
   :ldind.i4  {:code [0x4a] :operand :none :pop 1 :push 1}
   :ldind.i8  {:code [0x4c] :operand :none :pop 1 :push 1}
   :add       {:code [0x58] :operand :none :pop 2 :push 1}
   :sub       {:code [0x59] :operand :none :pop 2 :push 1}
   :mul       {:code [0x5a] :operand :none :pop 2 :push 1}
   :div       {:code [0x5b] :operand :none :pop 2 :push 1}
   :rem       {:code [0x5d] :operand :none :pop 2 :push 1}
   :and       {:code [0x5f] :operand :none :pop 2 :push 1}
   :or        {:code [0x60] :operand :none :pop 2 :push 1}
   :xor       {:code [0x61] :operand :none :pop 2 :push 1}
   :shl       {:code [0x62] :operand :none :pop 2 :push 1}
   :shr       {:code [0x63] :operand :none :pop 2 :push 1}
   :shr.un    {:code [0x64] :operand :none :pop 2 :push 1}
   :neg       {:code [0x65] :operand :none :pop 1 :push 1}
   :not       {:code [0x66] :operand :none :pop 1 :push 1}
   :conv.i4   {:code [0x69] :operand :none :pop 1 :push 1}
   :conv.i8   {:code [0x6a] :operand :none :pop 1 :push 1}
   :conv.r8   {:code [0x6c] :operand :none :pop 1 :push 1}
   :conv.u8   {:code [0x6e] :operand :none :pop 1 :push 1}
   :conv.u2   {:code [0xd1] :operand :none :pop 1 :push 1}
   :conv.u1   {:code [0xd2] :operand :none :pop 1 :push 1}
   :ceq       {:code [0xfe 0x01] :operand :none :pop 2 :push 1}
   :cgt       {:code [0xfe 0x02] :operand :none :pop 2 :push 1}
   :cgt.un    {:code [0xfe 0x03] :operand :none :pop 2 :push 1}
   :clt       {:code [0xfe 0x04] :operand :none :pop 2 :push 1}
   :clt.un    {:code [0xfe 0x05] :operand :none :pop 2 :push 1}
   :newarr    {:code [0x8d] :operand :tok :pop 1 :push 1}
   :ldlen     {:code [0x8e] :operand :none :pop 1 :push 1}
   :box       {:code [0x8c] :operand :tok :pop 1 :push 1}
   :throw     {:code [0x7a] :operand :none :pop 1 :push 0 :terminal true}
   ;; `call` and friends CANNOT have a static effect here: it is the callee's
   ;; signature, which this table cannot see. So they demand their own, and a
   ;; call written without one refuses the method rather than guessing.
   :call      {:code [0x28] :operand :tok :pop nil :push nil}
   :callvirt  {:code [0x6f] :operand :tok :pop nil :push nil}
   :newobj    {:code [0x73] :operand :tok :pop nil :push nil}
   ;; `ret` is TERMINAL, so its pop never raises the maximum -- the depth at the
   ;; return was already counted by whatever pushed it. Recording 0 here is
   ;; conservative in the safe direction.
   :ret       {:code [0x2a] :operand :none :pop 0 :push 0 :terminal true}
   :br        {:operand :target :pop 0 :push 0 :short 0x2b :long 0x38 :terminal true}
   :brfalse   {:operand :target :pop 1 :push 0 :short 0x2c :long 0x39}
   :brtrue    {:operand :target :pop 1 :push 0 :short 0x2d :long 0x3a}
   :beq       {:operand :target :pop 2 :push 0 :short 0x2e :long 0x3b}
   :bge       {:operand :target :pop 2 :push 0 :short 0x2f :long 0x3c}
   :bgt       {:operand :target :pop 2 :push 0 :short 0x30 :long 0x3d}
   :ble       {:operand :target :pop 2 :push 0 :short 0x31 :long 0x3e}
   :blt       {:operand :target :pop 2 :push 0 :short 0x32 :long 0x3f}
   :bne.un    {:operand :target :pop 2 :push 0 :short 0x33 :long 0x40}
   :bge.un    {:operand :target :pop 2 :push 0 :short 0x34 :long 0x41}
   :bgt.un    {:operand :target :pop 2 :push 0 :short 0x35 :long 0x42}
   :ble.un    {:operand :target :pop 2 :push 0 :short 0x36 :long 0x43}
   :blt.un    {:operand :target :pop 2 :push 0 :short 0x37 :long 0x44}})

(defn- wide-form
  "The compact encodings. CIL spells the first four locals and arguments, and
  any index under 256, in fewer bytes -- and `ldc.i4` has eleven spellings for
  small constants. Purely a size win, but a loop body is tens of bytes of IL
  against twelve of header, so the win is most of the body."
  [kind n]
  (case kind
    :ldc-i4 (cond (and (>= n -1) (<= n 8)) [(+ 0x16 n)]      ; ldc.i4.m1 .. ldc.i4.8
                  (and (>= n -128) (<= n 127)) [0x1f (bit-and n 0xff)]
                  :else (cons 0x20 (u32 n)))
    :ldarg  (cond (<= n 3) [(+ 0x02 n)]
                  (<= n 255) [0x0e n]
                  :else (concat [0xfe 0x09] (u16 n)))
    :ldloc  (cond (<= n 3) [(+ 0x06 n)]
                  (<= n 255) [0x11 n]
                  :else (concat [0xfe 0x0c] (u16 n)))
    :stloc  (cond (<= n 3) [(+ 0x0a n)]
                  (<= n 255) [0x13 n]
                  :else (concat [0xfe 0x0e] (u16 n)))
    :ldloca (if (<= n 255) [0x12 n] (concat [0xfe 0x0d] (u16 n)))))

(defn- effect
  "`[pops pushes]` for one instruction, or nil if unknown -- an opcode not in the
  table, or a call that did not declare its own arity."
  [[o _ p q]]
  (let [e (get CIL o)]
    (cond
      (nil? e) nil
      (nil? (:pop e)) (when (and p q) [p q])
      :else [(:pop e) (:push e)])))

(defn- instr-size
  "Encoded size of one instruction, given whether a branch went long."
  [[o a] long?]
  (let [e (get CIL o)]
    (case (:operand e)
      :none (count (:code e))
      :tok (+ (count (:code e)) 4)
      :i8 (+ (count (:code e)) 8)
      :r8 (+ (count (:code e)) 8)
      (:i4 :index) (count (wide-form (:wide e) a))
      :target (if long? 5 2))))

(defn max-stack
  "The deepest the CIL evaluation stack gets. A WORKLIST, mirroring
  `flint.aot/max-depth` and for its stated reason: the two edges of a branch
  leave different depths, and a running total would quietly get one of them
  wrong. Returns nil if any instruction's effect is unknown.

  `code` is the instruction vector with labels already removed; `label-at` maps
  a label to its index in it."
  [code label-at]
  (loop [work [[0 0]] seen {} best 0 guard 0]
    (cond
      (> guard 200000) nil
      (empty? work) best
      :else
      (let [[i d] (peek work) work (pop work)]
        (if (or (>= i (count code)) (<= d (get seen i -1)))
          (recur work seen best (inc guard))
          (let [ins (nth code i)
                e (effect ins)]
            (if (nil? e)
              nil
              (let [[pops pushes] e
                    after (+ (- d pops) pushes)
                    spec (get CIL (first ins))
                    tgt (when (= :target (:operand spec)) (get label-at (second ins)))
                    more (cond-> []
                           (not (:terminal spec)) (conj [(inc i) after])
                           tgt (conj [tgt after]))]
                (when (neg? after)
                  (throw (ex-info "CIL stack underflow" {:at i :instr ins :depth d})))
                (recur (into work more) (assoc seen i d)
                       (max best d after) (inc guard))))))))))

(defn assemble-il
  "Instructions to bytes, with branch displacements resolved and the short or
  long form chosen per branch.

  ITERATE TO A FIXED POINT, assuming short first. Widening a branch pushes
  everything after it further away, which can force another branch to widen; the
  loop terminates because widening is MONOTONE -- a branch never goes back to
  short -- so it can happen at most once per branch.

  An instruction is `[op]`, `[op operand]`, or `[op operand pops pushes]` for a
  call. `[:label :name]` is a marker and emits nothing.

  Returns `{:bytes <seq of byte seqs> :max-stack n}`."
  [instrs]
  (let [code (vec (remove #(= :label (first %)) instrs))
        ;; Label -> index of the next real instruction, by walking the original
        ;; sequence and counting the real ones seen so far.
        label-at (:labels
                   (reduce (fn [acc [o a]]
                             (if (= :label o)
                               (update acc :labels assoc a (:n acc))
                               (update acc :n inc)))
                           {:labels {} :n 0} instrs))
        n (count code)]
    (loop [long? #{} guard 0]
      (when (> guard (+ n 4))
        (throw (ex-info "branch widening did not settle" {:instrs n})))
      (let [offs (reduce (fn [v i]
                           (conj v (+ (peek v) (instr-size (nth code i) (contains? long? i)))))
                         [0] (range n))
            ;; The displacement is measured from the END of the branch, which is
            ;; why this reads `offs` at `(inc i)` and not at `i`.
            disp (fn [i]
                   (let [t (get label-at (second (nth code i)))]
                     (when (nil? t)
                       (throw (ex-info "no such label" {:label (second (nth code i))})))
                     (- (nth offs t) (nth offs (inc i)))))
            need (reduce (fn [s i]
                           (if (not= :target (:operand (get CIL (first (nth code i)))))
                             s
                             (let [d (disp i)]
                               (if (and (>= d -128) (<= d 127)) s (conj s i)))))
                         #{} (range n))]
        (if (= need long?)
          (let [ms (max-stack code label-at)]
            (when (nil? ms)
              (throw (ex-info "cannot bound the CIL stack for this method"
                              {:instrs (mapv first code)})))
            {:max-stack ms
             :bytes (mapv (fn [i]
                            (let [[o a] (nth code i)
                                  spec (get CIL o)]
                              (case (:operand spec)
                                :none (:code spec)
                                :tok (concat (:code spec) (u32 a))
                                :i8 (concat (:code spec) (u64 a))
                                :r8 (concat (:code spec) (u64 a))
                                (:i4 :index) (wide-form (:wide spec) a)
                                :target (if (contains? long? i)
                                          (cons (:long spec) (u32 (disp i)))
                                          [(:short spec) (bit-and (disp i) 0xff)]))))
                          (range n))})
          (recur (into long? need) (inc guard)))))))

;; ------------------------------------------------------------------ IL bodies

(defn- tiny-body
  "A method body in the tiny format: one header byte. Only legal with no locals,
  code under 64 bytes and a stack no deeper than 8 -- and `fat-body` is not an
  optimisation away from it, it is the only legal form once any of those fails."
  [code len]
  (when (>= len 64) (throw (ex-info "tiny body too long" {:len len})))
  [(bit-or (bit-shift-left len 2) 0x02) code])

(defn- fat-body
  "The 12-byte fat header, which must be 4-BYTE ALIGNED -- the caller pads. A
  body with locals has no tiny form, whatever its size.

  `max-stack` IS NOT OPTIONAL and is not derivable from the header: the runtime
  reads it to size the frame and rejects a body whose declared depth is too
  small. Getting it wrong yields an `InvalidProgramException` at first call,
  with no hint that the number is what was wrong."
  [code max-stack local-sig-tok len]
  (concat (u16 (bit-or (bit-shift-left 3 12) 0x13))   ; 3 dwords, fat + InitLocals
          (u16 max-stack)
          (u32 len)
          (u32 local-sig-tok)
          code))

(defn body
  "Assemble one method body, choosing its own format.

  The choice is NOT an optimisation: the tiny form is simply illegal once there
  are locals, or the code reaches 64 bytes, or the stack goes deeper than 8. All
  three are known only after assembly, which is why this wraps `assemble-il`
  rather than sitting beside it."
  [instrs nlocals local-sig-tok]
  (let [{:keys [bytes max-stack]} (assemble-il instrs)
        len (reduce + (map count bytes))
        tiny? (and (zero? nlocals) (< len 64) (<= max-stack 8))]
    {:bytes (->bytes (if tiny?
                       (tiny-body bytes len)
                       (fat-body bytes max-stack local-sig-tok len)))
     :max-stack max-stack
     :code-len len
     :tiny? tiny?}))

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
;; ONE TYPE, ONE ARRAY, SIX METHODS. The methods exist to exercise the parts
;; that go wrong rather than to be useful:
;;
;;   Length()        tiny body, a 4-byte inline operand
;;   At(int)         tiny body, reads THROUGH the `FieldRva` pointer
;;   Sum()           fat: locals, a `StandAloneSig`, short branches both ways,
;;                   and a `MaxStack` that is not 8
;;   Fnv1a()         fat, 64-bit: the SAME hash `Img.Load` computes over the
;;                   same bytes, so the emitted IL can be checked against the
;;                   runtime's own `rt.fingerprint` and not merely against
;;                   itself
;;   LongBranch()    a forward branch over more than 127 bytes, which is the
;;                   only way to prove the fixup pass ever chooses the long form
;;   MaxOf(long,long) a `call` through a `MemberRef` into the framework
;;
;; `Sum` and `Fnv1a` are the ones that matter. If the image bytes were not
;; really at the RVA the `FieldRva` row claims, both would return a plausible
;; wrong number rather than failing, so the caller compares them against values
;; computed outside the assembly.

(def ^:private E-I4 0x08)
(def ^:private E-I8 0x0a)

(defn- method-sig
  "A `MethodDefSig`/`MemberRefSig`: default calling convention, parameter count,
  return type, parameters."
  [ret params]
  (concat [0x00 (count params) ret] params))

(defn- locals-sig
  "A `LocalVarSig`. `0x07` is the one thing that distinguishes it from a method
  signature, and a body whose `LocalVarSigTok` points at a method signature
  fails at first call rather than at load."
  [types]
  (concat [0x07 (count types)] types))

(defn- fnv-il
  "FNV-1a over the whole array, as CIL. The constants are `Img.cs`'s, and the
  basis is written in decimal because `0xcbf29ce484222325` has its high bit set
  and is not a readable long literal."
  [fld n]
  [[:ldc.i8 -3750763034362895579]  ; 0xcbf29ce484222325
   [:stloc 0]
   [:ldc.i4 0] [:stloc 1]
   [:br :test]
   [:label :body]
   [:ldloc 0]
   [:ldsflda fld] [:ldloc 1] [:add] [:ldind.u1] [:conv.i8]
   [:xor]
   [:ldc.i8 1099511628211]          ; 0x100000001b3
   [:mul]
   [:stloc 0]
   [:ldloc 1] [:ldc.i4 1] [:add] [:stloc 1]
   [:label :test]
   [:ldloc 1] [:ldc.i4 n] [:blt.un :body]
   [:ldloc 0] [:ret]])

(defn- sum-il
  [fld n]
  [[:ldc.i4 0] [:stloc 0]
   [:ldc.i4 0] [:stloc 1]
   [:br :test]
   [:label :body]
   [:ldloc 0] [:ldsflda fld] [:ldloc 1] [:add] [:ldind.u1] [:add] [:stloc 0]
   [:ldloc 1] [:ldc.i4 1] [:add] [:stloc 1]
   [:label :test]
   [:ldloc 1] [:ldc.i4 n] [:blt.un :body]
   [:ldloc 0] [:ret]])

(defn- long-branch-il
  "A forward branch over `pad` bytes of `nop`. The ONLY way to show the fixup
  pass ever widens: with 200 bytes in the way the displacement does not fit in
  a signed byte, so `br.s` (0x2b) must become `br` (0x38)."
  [pad]
  (concat [[:br :over]]
          (repeat pad [:nop])
          [[:label :over] [:ldc.i4 1] [:ret]]))

(defn assemble
  "Emit a loadable .NET assembly carrying `image` as a static byte array.

  `{:name \"T\" :image <byte-string>}` -> byte string."
  [{:keys [name image]}]
  (let [img-len (flint.rt/b-count image)
        fld-tok 0x04000001                                ; Field table, row 1

        ;; ---- type and member references. TypeRef rows, in order, then the
        ;; MemberRefs that hang off them.
        typerefs [["System" "Object"] ["System" "ValueType"] ["System" "Math"]]
        sig-max (method-sig E-I8 [E-I8 E-I8])
        memberrefs [{:parent-table 0x01 :parent-rid 3 :name "Max" :sig sig-max}]
        ;; MemberRef tokens are table 0x0a.
        max-tok 0x0a000001

        ;; ---- local variable signatures, one StandAloneSig row each.
        locals [[E-I4 E-I4]                               ; Sum:   sum, i
                [E-I8 E-I4]]                              ; Fnv1a: h, i
        sig-tok (fn [n] (+ 0x11000000 n))                 ; StandAloneSig tokens

        ;; ---- the methods, as data. Each is assembled independently and then
        ;; laid out in order; a method's RVA is not knowable until the ones
        ;; before it have been assembled, which is why this is a reduction and
        ;; not a map.
        specs [{:name "Length" :sig (method-sig E-I4 [])
                :locals 0
                :il [[:ldc.i4 img-len] [:ret]]}
               {:name "At" :sig (method-sig E-I4 [E-I4])
                :locals 0
                :il [[:ldsflda fld-tok] [:ldarg 0] [:add] [:ldind.u1] [:ret]]}
               {:name "Sum" :sig (method-sig E-I4 [])
                :locals 2 :local-sig (sig-tok 1)
                :il (sum-il fld-tok img-len)}
               {:name "Fnv1a" :sig (method-sig E-I8 [])
                :locals 2 :local-sig (sig-tok 2)
                :il (fnv-il fld-tok img-len)}
               {:name "LongBranch" :sig (method-sig E-I4 [])
                :locals 0
                :il (long-branch-il 200)}
               {:name "MaxOf" :sig (method-sig E-I8 [E-I8 E-I8])
                :locals 0
                ;; THE CALL DECLARES ITS OWN ARITY. `CIL` cannot know it: the
                ;; effect is in the callee's signature, which lives in a blob.
                :il [[:ldarg 0] [:ldarg 1] [:call max-tok 2 1] [:ret]]}]

        ;; Assemble bodies and place them. A fat header must be 4-byte aligned,
        ;; so each body is padded to a 4-byte boundary before the next starts --
        ;; simpler than aligning only the fat ones and correct either way.
        placed (reduce
                 (fn [{:keys [at out]} s]
                   (let [b (body (:il s) (:locals s) (or (:local-sig s) 0))
                         len (flint.rt/b-count (:bytes b))
                         pad (- (align len 4) len)]
                     {:at (+ at len pad)
                      :out (conj out (assoc b :spec s :off at :pad pad))}))
                 {:at 0 :out []} specs)
        bodies (->bytes (for [m (:out placed)] [(:bytes m) (zeros (:pad m))]))
        body-len (flint.rt/b-count bodies)
        lay (text-layout body-len img-len)
        rva-image (+ text-rva (:image lay))
        method-rva (fn [i] (+ text-rva (:bodies lay) (:off (nth (:out placed) i))))

        ;; ---- heaps
        arr-type (str "$ArrayType$" img-len)
        strs (concat [(str name ".dll") "<Module>" "Program" arr-type "IMAGE"
                      name "System.Runtime"]
                     (map first typerefs) (map second typerefs)
                     (map :name memberrefs)
                     (map :name specs))
        sh (strings-heap strs)

        sig-field [0x06 0x11 (first (cint (coded :type-def-or-ref 0x02 3)))]
        ecma-token [0xb0 0x3f 0x5f 0x7f 0x11 0xd5 0x0a 0x3a]
        bh (blob-heap (concat [sig-field ecma-token]
                             (map :sig specs)
                             (map :sig memberrefs)
                             (map locals-sig locals)))

        S (fn [s] (get (:index sh) s))
        B (fn [b] (get (:index bh) (vec b)))

        ;; ---- row counts, hence index widths. The size-first pass.
        rows {0x00 1 0x01 (count typerefs) 0x02 3 0x04 1
              0x06 (count specs) 0x0a (count memberrefs)
              0x0f 1 0x11 (count locals) 0x1d 1 0x20 1 0x23 1}
        heap-sizes (bit-or (if (>= (:size sh) 0x10000) 1 0)
                           (if (>= 16 0x10000) 2 0)
                           (if (>= (:size bh) 0x10000) 4 0))
        ws (if (zero? (bit-and heap-sizes 1)) 2 4)
        wg (if (zero? (bit-and heap-sizes 2)) 2 4)
        wb (if (zero? (bit-and heap-sizes 4)) 2 4)
        wt (fn [t] (if (>= (get rows t 0) 0x10000) 4 2))
        wtdr (coded-width :type-def-or-ref rows)
        wrs (coded-width :resolution-scope rows)
        wmrp (coded-width :member-ref-parent rows)

        row-bytes
        {0x00 [(u16 0) (ix ws (S (str name ".dll"))) (ix wg 1) (ix wg 0) (ix wg 0)]
         0x01 (for [[ns' n'] typerefs]
                [(ix wrs (coded :resolution-scope 0x23 1))
                 (ix ws (S n')) (ix ws (S ns'))])
         ;; TypeDef. FieldList/MethodList are RANGE STARTS: a row owns from its
         ;; own start up to the next row's, which is why `<Module>` and `Program`
         ;; both say 1 and `<Module>` therefore owns nothing. The third row must
         ;; start PAST the end of both lists, or it would claim `Program`'s
         ;; members as its own.
         0x02 [[(u32 0) (ix ws (S "<Module>")) (ix ws 0) (ix wtdr 0)
                (ix (wt 0x04) 1) (ix (wt 0x06) 1)]
               [(u32 0x00100001)                          ; Public | BeforeFieldInit
                (ix ws (S "Program")) (ix ws 0)
                (ix wtdr (coded :type-def-or-ref 0x01 1)) ; extends System.Object
                (ix (wt 0x04) 1) (ix (wt 0x06) 1)]
               [(u32 0x00000111)                          ; Private|ExplicitLayout|Sealed
                (ix ws (S arr-type)) (ix ws 0)
                (ix wtdr (coded :type-def-or-ref 0x01 2)) ; extends System.ValueType
                (ix (wt 0x04) 2) (ix (wt 0x06) (inc (count specs)))]]
         0x04 [(u16 0x0111) (ix ws (S "IMAGE")) (ix wb (B sig-field))]
         0x06 (map-indexed
                (fn [i s]
                  [(u32 (method-rva i)) (u16 0) (u16 0x0016)
                   (ix ws (S (:name s))) (ix wb (B (:sig s))) (ix (wt 0x08) 1)])
                specs)
         0x0a (for [m memberrefs]
                [(ix wmrp (coded :member-ref-parent (:parent-table m) (:parent-rid m)))
                 (ix ws (S (:name m))) (ix wb (B (:sig m)))])
         0x0f [(u16 1) (u32 img-len) (ix (wt 0x02) 3)]
         0x11 (for [l locals] [(ix wb (B (locals-sig l)))])
         0x1d [(u32 rva-image) (ix (wt 0x04) 1)]
         0x20 [(u32 0) (u16 0) (u16 0) (u16 0) (u16 0) (u32 0)
               (ix wb 0) (ix ws (S name)) (ix ws 0)]
         0x23 [(u16 10) (u16 0) (u16 0) (u16 0) (u32 0)
               (ix wb (B ecma-token)) (ix ws (S "System.Runtime")) (ix ws 0) (ix wb 0)]}

        md (metadata {:rows rows :row-bytes row-bytes :heap-sizes heap-sizes
                      :strings sh :blobs bh
                      :guid (concat (u32 0x5f544e4c) (u32 0x00544e49)
                                    (u32 0x00000001) (u32 0x00000001))})
        md-len (flint.rt/b-count md)

        text (->bytes
               [(u32 cli-header-size) (u16 2) (u16 5)
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
       ;; loader's `mscoree!_CorDllMain` stub, and a library with no entry point
       ;; on CoreCLR needs none of them.
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

(defn describe
  "What `assemble` would emit, without emitting it: the per-method assembly
  facts. For the gate and for a human checking that the fixup pass did what it
  claims -- a `MaxStack` nobody looks at is a number nobody has checked."
  [{:keys [image]}]
  (let [n (flint.rt/b-count image)
        fld 0x04000001]
    (for [[nm il nlocals]
          [["Length" [[:ldc.i4 n] [:ret]] 0]
           ["At" [[:ldsflda fld] [:ldarg 0] [:add] [:ldind.u1] [:ret]] 0]
           ["Sum" (sum-il fld n) 2]
           ["Fnv1a" (fnv-il fld n) 2]
           ["LongBranch" (long-branch-il 200) 0]
           ["MaxOf" [[:ldarg 0] [:ldarg 1] [:call 0x0a000001 2 1] [:ret]] 0]]]
      (let [b (body il nlocals (if (pos? nlocals) 0x11000001 0))]
        {:name nm :max-stack (:max-stack b) :code-len (:code-len b)
         :form (if (:tiny? b) :tiny :fat)}))))
