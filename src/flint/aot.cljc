(ns flint.aot
  "Bytecode to wasm, one arity at a time (`doc/decisions/0013`).

  ## The shape, and why it is this shape

  A compiled arity is one wasm function whose body is a chain of blocks that
  **fall through in order**, wrapped in a `loop` with a `br_table` at the top:

      loop $D
        block $EXIT
          block $B2
            block $B1
              block $B0
                br_table $B0 $B1 $B2 $EXIT (local.get $pc)
              end                ;; -> chunk 0
              ;; chunk 0
            end                  ;; -> chunk 1
            ;; chunk 1
          end                    ;; -> chunk 2
          ;; chunk 2
        end
      end

  Three properties fall out of that layout, and all three were asked for:

  * **The engine optimises across the join.** Adjacent chunks are straight-line
    fallthrough; nothing is a call and nothing is a branch.
  * **A forward jump is one `br`.** Chunk `i` jumping to chunk `j > i` branches
    out to `$B_j`, which is in scope precisely because later chunks enclose
    earlier ones. Only a BACKWARD jump pays the `br_table`, and back-edges are
    2.4% of executed instructions.
  * **Re-entry is possible at every chunk.** This is the part wasm forces: you
    cannot branch INTO structured control flow, so a design that reconstructed
    `if`/`else` nesting could only ever be entered at the top. The measurement in
    0013 says a quarter of the work in a program that parks happens in a frame
    that has already been resumed, so entering only at the top was never an
    option.

  ## What a chunk boundary is

  Every jump target, every call-ish instruction and the instruction after it,
  and every opcode this emitter does not inline and the instruction after it.
  0013's static measurement sizes that at one boundary per 5.9 instructions, and
  the runtime cost of a boundary in this layout is zero.

  ## Gas

  Charged inline, per chunk, by the chunk's own static instruction count -- which
  is exact because a chunk has no internal branch, so either all of it runs or
  none of it does. A chunk that ends by handing an instruction back to the
  interpreter does not charge for that instruction, because the interpreter is
  about to. `doc/decisions/0016` makes gas a production feature and construe's
  gates depend on the count, so `test/aot.clj` asserts the count is IDENTICAL
  with and without compilation rather than merely close."
  (:require [flint.wasm :as w]))

;; ------------------------------------------------------------------ opcodes

(def OPS
  "opcode -> [name operand-bytes]. The operand width is what a walk strides by,
  and a walk that mis-strides produces plausible nonsense rather than an error,
  so an unknown opcode refuses the whole arity instead."
  {0x00 [:nop 0]      0x01 [:const 2]   0x02 [:nil 0]      0x03 [:true 0]
   0x04 [:false 0]    0x05 [:int 2]     0x06 [:local 1]    0x07 [:local-w 2]
   0x08 [:set-local 1] 0x09 [:upval 1]  0x0A [:var 2]      0x0B [:set-var 2]
   0x0C [:pop 0]      0x0D [:dup 0]     0x0E [:jump 2]     0x0F [:jump-if-false 2]
   0x10 [:jump-if-true 2] 0x11 [:call 1] 0x12 [:tail-call 1] 0x13 [:return 0]
   0x14 [:closure 3]  0x15 [:native 3]  0x16 [:throw 0]    0x17 [:try 2]
   0x18 [:pop-handler 0] 0x19 [:rethrow 0] 0x1A [:vector 2] 0x1B [:map 2]
   0x1C [:set 2]      0x1D [:list 2]    0x1E [:apply 1]    0x1F [:jump-if-false-keep 2]
   0x20 [:jump-if-true-keep 2] 0x21 [:pop-n 1] 0x22 [:set-local-keep 1] 0x23 [:self 0]})

(def JUMPS #{:jump :jump-if-false :jump-if-true :jump-if-false-keep :jump-if-true-keep})

(def CALLS
  "Instructions that can leave compiled code. `:call` often does NOT -- a callee
  that is not a closure finishes in place -- but it is still a chunk boundary,
  because it might."
  #{:call :tail-call :apply})

(def INLINED
  "Emitted as wasm. 0013's opcode histogram says these are 98.7% of executed
  instructions, and the rest go back to the interpreter one at a time."
  #{:nop :const :nil :true :false :int :local :local-w :set-local :set-local-keep
    :pop :pop-n :dup :var :set-var :self :upval :jump :jump-if-false :jump-if-true
    :jump-if-false-keep :jump-if-true-keep :return :native})

;; ------------------------------------------------------------------- values

(def TAG-SPECIAL 0xFFFB)
(def TAG-FIXNUM 0xFFFA)
(def V-NIL (bit-or (bit-shift-left TAG-SPECIAL 48) 0))
(def V-FALSE (bit-or (bit-shift-left TAG-SPECIAL 48) 1))
(def V-TRUE (bit-or (bit-shift-left TAG-SPECIAL 48) 2))
(defn fixnum [n]
  (bit-or (bit-shift-left TAG-FIXNUM 48) (bit-and n 0x0000FFFFFFFFFFFF)))

;; ------------------------------------------------------------------ decoding

(defn decode
  "The instructions of one arity, as `[{:ip :op :n :len}]`. `nil` if any opcode
  is unknown or the walk does not land exactly on the end -- both mean the
  stride is wrong, and a wrong stride is worse than no compilation."
  [code start len]
  (let [end (+ start len)]
    (loop [ip start out []]
      (cond
        (= ip end) out
        (> ip end) nil
        :else
        (let [b (nth code ip)
              e (OPS b)]
          (if-not e
            nil
            (let [[op nb] e]
              (recur (+ ip 1 nb)
                     (conj out {:ip ip :op op :nb nb
                                :len (+ 1 nb)
                                :b (subvec (vec code) (inc ip) (+ ip 1 nb))})))))))))

(defn- u16 [bs] (bit-or (nth bs 0) (bit-shift-left (nth bs 1) 8)))
(defn- i16 [bs] (let [v (u16 bs)] (if (>= v 0x8000) (- v 0x10000) v)))

(defn jump-target [{:keys [op ip len b]}]
  (when (JUMPS op) (+ ip len (i16 b))))

;; ------------------------------------------------------------------- chunking

(defn boundaries
  "Byte offsets that begin a chunk. Every one is a re-entry point."
  [instrs]
  (let [targets (into #{} (keep jump-target) instrs)
        ;; A NATIVE needs a re-entry point on BOTH sides. Before it, because a
        ;; park resumes by re-executing it. After it, because a COURTESY YIELD
        ;; is a park whose call already finished, so it must not run again.
        ;;
        ;; And after every JUMP, conditional or not. Gas is charged per chunk by
        ;; the chunk's static instruction count, and that is only exact if a
        ;; chunk has no INTERNAL branch -- a conditional jump in the middle of
        ;; one leaves without running the rest, and the count charged for them
        ;; anyway. `test/aot.clj` caught it: the answers all matched and the
        ;; instruction counts did not.
        after (into #{} (comp (filter (fn [i] (or (CALLS (:op i))
                                                  (= :native (:op i))
                                                  (JUMPS (:op i))
                                                  (not (INLINED (:op i))))))
                              (map (fn [i] (+ (:ip i) (:len i)))))
                    instrs)
        ;; A NATIVE starts a chunk even though it runs INSIDE compiled code,
        ;; because it can park -- and a parked thread resumes at the chunk
        ;; containing it, so anything earlier in that chunk would run a second
        ;; time. Which natives park is not knowable here, and a chunk boundary
        ;; is free at run time, so every one of them gets a boundary.
        ;; A BACKWARD jump starts a chunk as well as ending one. It is the one
        ;; preemption point in compiled code, and a thread preempted there
        ;; resumes at the chunk the emitter named -- so if the jump is not that
        ;; chunk's first instruction, everything before it in the chunk runs a
        ;; SECOND time. The EDN reader accumulated a token twice and produced a
        ;; map with an odd number of forms.
        back (into #{} (comp (filter (fn [i] (when-let [t (jump-target i)] (< t (:ip i)))))
                             (map :ip))
                   instrs)
        at (into #{} (comp (filter (fn [i] (or (CALLS (:op i))
                                               (= :native (:op i))
                                               (not (INLINED (:op i))))))
                           (map :ip))
                 instrs)
        valid (into #{} (map :ip) instrs)]
    (->> (concat [(:ip (first instrs))] targets after at back)
         (filter valid)
         (into (sorted-set)))))

(defn chunks
  "Split into chunks at the boundaries. Each is `{:idx :ip :instrs :charge}`."
  [instrs bounds]
  (let [idx (zipmap bounds (range))]
    (->> (reduce (fn [acc i]
                   (if (and (seq acc) (not (bounds (:ip i))))
                     (update acc (dec (count acc)) conj i)
                     (conj acc [i])))
                 [] instrs)
         (mapv (fn [is]
                 (let [last-op (:op (last is))
                       ;; A chunk that hands its final instruction back does not
                       ;; charge for it: the interpreter is about to.
                       handed-back? (or (CALLS last-op) (not (INLINED last-op)))]
                   {:idx (idx (:ip (first is))) :ip (:ip (first is)) :instrs (vec is)
                    :charge (cond-> (count is) handed-back? dec)}))))))

;; ---------------------------------------------------------------- wasm bytes

(def ^:private B
  {:block 0x02 :loop 0x03 :if 0x04 :else 0x05 :end 0x0B :br 0x0C :br-if 0x0D
   :br-table 0x0E :return 0x0F :call 0x10 :drop 0x1A
   :local-get 0x20 :local-set 0x21 :local-tee 0x22
   :i32-load 0x28 :i64-load 0x29 :i32-store 0x36 :i64-store 0x37
   :i32-const 0x41 :i64-const 0x42
   :i32-eqz 0x45 :i32-eq 0x46 :i32-ne 0x47
   :i64-eq 0x51 :i64-ne 0x52 :i64-ge-u 0x5A
   :i32-add 0x6A :i32-sub 0x6B :i32-shl 0x74 :i32-or 0x72 :i32-shr-u 0x76
   :i64-add 0x7C
   :i32-wrap-i64 0xA7 :void 0x40})

(defn- op [k] (B k))
(defn- i32c [n] [(op :i32-const) (w/sleb n)])
(defn- i64c [n] [(op :i64-const) (w/sleb n)])
(defn- lget [i] [(op :local-get) (w/uleb i)])
(defn- lset [i] [(op :local-set) (w/uleb i)])
(defn- ltee [i] [(op :local-tee) (w/uleb i)])
(defn- i64ld [off] [(op :i64-load) (w/uleb 3) (w/uleb off)])
(defn- i64st [off] [(op :i64-store) (w/uleb 3) (w/uleb off)])
(defn- i32ld [off] [(op :i32-load) (w/uleb 2) (w/uleb off)])

;; Locals. The four parameters come first and are fixed by the ABI in
;; `runtime/src/aot.rs`; everything after is this emitter's own.
(def RT 0) (def FP 1) (def RET-TO 2) (def ENTRY 3) (def SYNC 4)
(def SP 5) (def TOPB 6) (def CONSTS 7) (def GLOBALS 8) (def HEAP 9)
(def PC 10) (def GAS 11) (def T 12) (def FPB 13) (def RETB 14)

;; Sync-block field offsets, matching `AotSync` in `runtime/src/aot.rs`.
(def S-STACK 0) (def S-TOP 4) (def S-CONSTS 8) (def S-GLOBALS 12)
(def S-HEAP 16) (def S-STEPS 20) (def S-CHK 24)

(defn- push-from
  "Push whatever `produce` leaves on the wasm stack as an i64."
  [produce]
  [(lget TOPB) produce (i64st 0)
   (lget TOPB) (i32c 8) (op :i32-add) (lset TOPB)])

(defn- pop-to-t []
  [(lget TOPB) (i32c 8) (op :i32-sub) (ltee TOPB) (i64ld 0) (lset T)])

(defn- peek-to-t []
  [(lget TOPB) (i32c 8) (op :i32-sub) (i64ld 0) (lset T)])

(defn- reload
  "Everything compiled code caches that a call back into Rust can invalidate.
  The value stack is a `Vec` and a push can reallocate it, so the base cannot be
  held across a call -- which is why the sync block exists at all.

  `need` is what the BODY actually reads. A callee like `+` is four instructions
  long and touches none of the closure, so reloading its address on every entry
  and after every native was pure overhead measured against a body that small."
  [need]
  [(lget SYNC) (i32ld S-STACK) (lset SP)
   (lget SYNC) (i32ld S-TOP) (i32c 3) (op :i32-shl) (lget SP) (op :i32-add) (lset TOPB)
   (when (:fpb need)
     [(lget SP) (lget FP) (i32c 3) (op :i32-shl) (op :i32-add) (lset FPB)])
   (when (:retb need)
     [(lget SP) (lget RET-TO) (i32c 3) (op :i32-shl) (op :i32-add) (lset RETB)])])

(defn- top-index
  "The value stack top as an index, which is what the helpers take."
  []
  [(lget TOPB) (lget SP) (op :i32-sub) (i32c 3) (op :i32-shr-u)])

;; ------------------------------------------------------------- stack depth

(defn- effect
  "Net operand-stack effect, and the effect along a `keep` jump's taken edge --
  those two differ, which is the whole reason this is a dataflow rather than a
  running total."
  [{:keys [op b]}]
  (case op
    (:const :nil :true :false :int :local :local-w :upval :var :self :dup) [1 1]
    (:set-local :set-var :pop :throw :jump-if-false :jump-if-true) [-1 -1]
    (:jump-if-false-keep :jump-if-true-keep) [-1 0]
    (:nop :jump :set-local-keep :try :pop-handler :return :tail-call) [0 0]
    :rethrow [1 1]
    :pop-n [(- (nth b 0)) (- (nth b 0))]
    :call [(- (nth b 0)) (- (nth b 0))]
    :apply [(- (nth b 0)) (- (nth b 0))]
    :native [(- 1 (nth b 2)) (- 1 (nth b 2))]
    :closure [(- 1 (nth b 2)) (- 1 (nth b 2))]
    (:vector :set :list) [(- 1 (u16 b)) (- 1 (u16 b))]
    :map [(- 1 (* 2 (u16 b))) (- 1 (* 2 (u16 b)))]
    nil))

(defn max-depth
  "The deepest the operand stack gets, so the prologue can reserve once and
  every push the emitter produces is an unchecked store. A worklist rather than
  a running total: the two edges of a `keep` jump leave different depths, and a
  running total would quietly get one of them wrong.

  Returns nil if any instruction has no known effect -- refusing to compile is
  the only safe answer to a depth this cannot bound."
  [instrs]
  (let [by-ip (into {} (map (juxt :ip identity)) instrs)
        nxt (into {} (map (fn [i] [(:ip i) (+ (:ip i) (:len i))])) instrs)]
    (loop [work [[(:ip (first instrs)) 0]] seen {} best 0 guard 0]
      (cond
        (> guard 200000) nil
        (empty? work) best
        :else
        (let [[ip d] (peek work) work (pop work)]
          (if (or (nil? (by-ip ip)) (<= d (get seen ip -1)))
            (recur work seen best (inc guard))
            (let [i (by-ip ip)
                  e (effect i)]
              (if (nil? e)
                nil
                (let [[fall taken] e
                      t (jump-target i)
                      more (cond-> []
                             (not (#{:return :tail-call :jump} (:op i)))
                             (conj [(nxt ip) (max 0 (+ d fall))])
                             t (conj [t (max 0 (+ d taken))]))]
                  (recur (into work more) (assoc seen ip d)
                         (max best (+ d 1)) (inc guard)))))))))))

;; ------------------------------------------------------------------ emission

(def AOT-NEVER -1)   ;; u32::MAX as an sleb i32

(defn- call-fn [helpers k] [(op :call) (w/uleb (get helpers k))])

(defn- tick
  "A back-edge: flush the gas accumulated since the last exit, and ask whether
  the interpreter's own tick would now fire. This is the ONE place a long
  compiled loop can be preempted, which the deterministic scheduler needs -- and
  it is the cheapest place for it, because back-edges are 2.4% of executed
  instructions and every other chunk boundary is free."
  [{:keys [helpers i]} ip]
  [(lget RT) (lget GAS) (top-index) (i32c ip) (i32c i) (call-fn helpers :tick)
   (op :if) (op :void) (op :return) (op :end)
   (i32c 0) (lset GAS)])

(defn- br-to
  "Reach chunk `j` from inside chunk `i`. A forward jump is one `br`, because
  later chunks enclose earlier ones; a backward jump is the only thing that pays
  the dispatcher."
  [{:keys [i n extra] :as ctx} j ip]
  (if (> j i)
    [(op :br) (w/uleb (+ (- j i 1) extra))]
    [(tick ctx ip)
     (i32c j) (lset PC) (op :br) (w/uleb (+ (- n i) extra))]))

(defn- br-to-if
  "The same, under a condition already on the wasm stack."
  [{:keys [i n extra] :as ctx} j ip]
  (if (> j i)
    [(op :br-if) (w/uleb (+ (- j i 1) extra))]
    [(op :if) (op :void)
     (tick (assoc ctx :extra (inc extra)) ip)
     (i32c j) (lset PC) (op :br) (w/uleb (+ (- n i) extra 1))
     (op :end)]))

(defn- bail
  "Hand control back at `ip`, and say where compiled code takes over again.
  One helper covers every exit -- a call, an opcode this does not inline, a gas
  trip -- because every way back in is the same comparison on `ip`.

  The accumulated gas rides along. Between exits it lives in a wasm local, so a
  straight run of chunks charges gas without touching memory at all; it was a
  load, an add, a store and a compare PER CHUNK before that, which is most of
  what made the first version of this slower than the interpreter."
  [helpers ip resume-ip resume-block]
  [(lget RT) (top-index) (i32c ip) (i32c resume-ip) (i32c resume-block) (lget GAS)
   (call-fn helpers :bail)
   (op :return)])

(defn- falsy
  "`t` is nil or false -- Clojure's `not truthy`, which is what a
  `JUMP_IF_FALSE` branches on."
  []
  [(lget T) (i64c V-NIL) (op :i64-eq)
   (lget T) (i64c V-FALSE) (op :i64-eq)
   (op :i32-or)])

(defn- truthy []
  [(falsy) (op :i32-eqz)])

(defn emit-instr
  ;; `:op` is deliberately NOT destructured as `op`: that name is the byte
  ;; emitter one line up, and shadowing it made every inline `(op :i32-add)`
  ;; return nil, which `->bytes` then skipped in silence. The bytes were simply
  ;; absent and the only symptom was a stack-height error 300 bytes later.
  [{:keys [helpers chunk-of] :as ctx} {k :op :keys [ip b len] :as ins}]
  (let [to (fn [t] (br-to ctx (chunk-of t) ip))
        to-if (fn [t] (br-to-if ctx (chunk-of t) ip))
        tgt (jump-target ins)]
    (case k
      :nop []
      :nil (push-from (i64c V-NIL))
      :true (push-from (i64c V-TRUE))
      :false (push-from (i64c V-FALSE))
      :int (push-from (i64c (fixnum (i16 b))))
      :const (push-from [(lget CONSTS) (i64ld (* 8 (u16 b)))])
      :var (push-from [(lget GLOBALS) (i64ld (* 8 (u16 b)))])
      :local (push-from [(lget FPB) (i64ld (* 8 (nth b 0)))])
      :local-w (push-from [(lget FPB) (i64ld (* 8 (u16 b)))])
      :self (push-from [(lget RETB) (i64ld 0)])
      ;; The closure is `stack[ret_to]`, and an upvalue is one of its slots. The
      ;; frame deliberately does not cache the closure -- that copy was once a
      ;; root the collector could not see -- so this reads it the same way the
      ;; interpreter does.
      :upval (push-from [(lget HEAP) (lget RETB) (i64ld 0) (op :i32-wrap-i64)
                         (op :i32-add) (i64ld (+ 8 (* 8 (inc (nth b 0)))))])
      :set-local [(lget FPB) (pop-to-t) (lget T) (i64st (* 8 (nth b 0)))]
      :set-local-keep [(lget FPB) (peek-to-t) (lget T) (i64st (* 8 (nth b 0)))]
      :set-var [(lget GLOBALS) (pop-to-t) (lget T) (i64st (* 8 (u16 b)))]
      :pop [(lget TOPB) (i32c 8) (op :i32-sub) (lset TOPB)]
      :pop-n [(lget TOPB) (i32c (* 8 (nth b 0))) (op :i32-sub) (lset TOPB)]
      :dup (push-from [(lget TOPB) (i32c 8) (op :i32-sub) (i64ld 0)])
      :jump (to tgt)
      :jump-if-false [(pop-to-t) (falsy) (to-if tgt)]
      :jump-if-true [(pop-to-t) (truthy) (to-if tgt)]
      ;; The `keep` forms do not pop when they jump, so the pop belongs on the
      ;; fallthrough only -- which is also why `max-depth` is a dataflow.
      :jump-if-false-keep [(peek-to-t) (falsy) (to-if tgt)
                           (lget TOPB) (i32c 8) (op :i32-sub) (lset TOPB)]
      :jump-if-true-keep [(peek-to-t) (truthy) (to-if tgt)
                          (lget TOPB) (i32c 8) (op :i32-sub) (lset TOPB)]
      :return [(lget RT) (top-index) (lget GAS) (call-fn helpers :return) (op :return)]
      :call (let [nx (+ ip len) j (chunk-of nx)]
              [(lget RT) (i32c (nth b 0)) (top-index) (i32c ip) (i32c (:i ctx))
               (i32c (if j nx AOT-NEVER)) (i32c (or j 0)) (lget GAS)
               (call-fn helpers :call)
               (op :if) (op :void) (op :return) (op :end)
               (i32c 0) (lset GAS)
               (reload (:need ctx))])
      :native (let [nx (+ ip len) j (chunk-of nx)]
                [(lget RT) (i32c (u16 b)) (i32c (nth b 2)) (top-index)
                 (i32c ip) (i32c (:i ctx))
                 (i32c (if j nx AOT-NEVER)) (i32c (or j 0)) (lget GAS)
                 (call-fn helpers :native)
                 (op :if) (op :void) (op :return) (op :end)
                 (i32c 0) (lset GAS)
                 (reload (:need ctx))])
      ;; Everything else goes back to the interpreter for exactly one
      ;; instruction. That is what lets this emitter be COMPLETE from the first
      ;; version instead of refusing a whole function over one rare opcode, and
      ;; it is cheap for the same reason re-entry is.
      (let [nx (+ ip len) j (chunk-of nx)]
        (if j (bail helpers ip nx j) (bail helpers ip AOT-NEVER 0))))))

(defn- emit-chunk
  [{:keys [helpers] :as ctx} {:keys [instrs charge ip]}]
  (into
   ;; Gas, charged by the chunk's own static instruction count -- exact, because
   ;; a chunk has no internal branch, so either all of it runs or none of it
   ;; does. Into a wasm LOCAL: it reaches `Rt::steps` on the way out, and the
   ;; only places that can observe it are the same places compiled code leaves
   ;; from.
   (if (pos? charge)
     [(lget GAS) (i32c charge) (op :i32-add) (lset GAS)]
     [])
   (map #(emit-instr ctx %) instrs)))

;; ------------------------------------------------------------------ assembly

(def ^:private LOCAL-DECLS
  "Groups must follow the index order the `def`s above fix: locals 5..11 are
  i32, 12 is the i64 scratch, 13 and 14 are i32."
  [[7 0x7F] [1 0x7E] [2 0x7F]])

(defn locals-decl []
  (into [(w/uleb (count LOCAL-DECLS))]
        (mapcat (fn [[n t]] [(w/uleb n) t]) LOCAL-DECLS)))

(defn needs
  "Which of the cached bases this body actually reads. A prologue that loads all
  of them regardless is measured overhead on a small callee, and most callees in
  idiomatic Clojure are small."
  [instrs]
  (let [ops (into #{} (map :op) instrs)]
    {:fpb (some ops [:local :local-w :set-local :set-local-keep])
     :retb (some ops [:self :upval])
     :consts (some ops [:const])
     :globals (some ops [:var :set-var])
     :heap (some ops [:upval])}))

(defn compile-arity
  "One arity to a wasm function body. Returns `{:body :points :depth :chunks}`,
  or nil if it cannot be compiled -- an unknown opcode or an unbounded operand
  stack, both of which mean this emitter does not understand the code well
  enough to be trusted with it."
  [code start len helpers]
  (when-let [instrs (seq (decode code start len))]
    (when-let [depth (max-depth instrs)]
      (let [starts (into #{} (map :ip) instrs)
            ;; A jump into the middle of an instruction. Nothing the compiler
            ;; emits does this, which is exactly why it must be checked here
            ;; rather than assumed: the failure would be a branch to a chunk
            ;; that does not exist, and the emitter would have no way to say so.
            _ (when-not (every? starts (keep jump-target instrs))
                (throw (ex-info "jump into the middle of an instruction"
                                {:offsets (remove starts (keep jump-target instrs))})))
            bounds (boundaries instrs)
            cks (chunks instrs bounds)
            n (count cks)
            chunk-of (zipmap bounds (range))
            need (needs instrs)
            ctx {:n n :chunk-of chunk-of :helpers helpers :extra 0 :need need}]
        {:points (mapv (fn [c] [(:ip c) (:idx c)]) cks)
         :depth depth
         :chunks n
         :body
         (w/->bytes
          [(locals-decl)
           (when (:consts need) [(lget SYNC) (i32ld S-CONSTS) (lset CONSTS)])
           (when (:globals need) [(lget SYNC) (i32ld S-GLOBALS) (lset GLOBALS)])
           (when (:heap need) [(lget SYNC) (i32ld S-HEAP) (lset HEAP)])
           (reload need)
           (lget ENTRY) (lset PC)
           (op :loop) (op :void)
           (repeat (inc n) [(op :block) (op :void)])
           (lget PC)
           (op :br-table) (w/uleb n) (map w/uleb (range n)) (w/uleb n)
           (map-indexed (fn [i c] [(op :end) (emit-chunk (assoc ctx :i i) c)]) cks)
           (op :end)                                        ; $EXIT
           (op :end)                                        ; the dispatcher loop
           (op :end)])}))))                                 ; the function
