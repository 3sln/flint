(ns flint.llvm
  "Bytecode to LLVM IR, one arity at a time (`DECISIONS.md#llvm-ir-target`).

  ## The same shape, aimed at a different target

  This is `flint.aot` with the bytes replaced by text. Everything ABOUT the
  bytecode -- decoding it, where a chunk begins, how deep the operand stack
  gets, where compiled code resumes after handing an instruction back -- is
  read from `flint.aot` rather than restated here, because two emitters that
  disagree about a chunk boundary agree on every program that never takes the
  disagreeing path (AGENTS.md §1). What lives here is only EMISSION.

  A compiled arity is one LLVM function

      define internal void @flint_aot_N(ptr %rt, i32 %fp, i32 %retto,
                                        i32 %blk, i64 %sync)

  whose body is a `switch` on `%blk` into one basic block per chunk, with the
  chunks laid out so each falls into the next.

  ## What LLVM makes easier, and the one thing it does not change

  wasm cannot branch INTO structured control flow, which is why `flint.aot`
  wraps its chunks in nested blocks and pays a `br_table` for every backward
  jump. LLVM has no such rule: a jump is `br label %chunkN` in either
  direction, and the `switch` runs once on the way in rather than once per
  back-edge.

  What does NOT change is why a Clojure call is not an LLVM call. A green
  thread parks by unwinding to the interpreter, and a frame that lived on the
  machine stack could not be suspended -- so `aot_call` pushes a frame and
  RETURNS, exactly as it does for wasm, and the interpreter enters the callee.
  `DECISIONS.md#emit-wasm-instead-of-dispatch` is the argument; none of it is
  about wasm specifically.

  ## Values live in memory, not in SSA registers

  Every piece of per-body state -- the stack top, the cached bases, the gas
  counter -- is an `alloca` written and read on every use, and `mem2reg`
  promotes what it can. That is not laziness about phi nodes: a chunk is a
  RE-ENTRY POINT, and every value that crosses one has to be somewhere the
  interpreter can also see. The operand stack is already the runtime's own
  `Vec`, for the reason `flint.aot` gives -- wasm locals are not scannable --
  and a native register is no more scannable than a wasm local.

  ## 64-bit only

  The sync block is seven pointer-width fields, and this emitter writes them as
  `i64`. LLVM IR is not target-independent about integer widths, and the only
  hosts flint links natively are 64-bit. A 32-bit target would need the struct
  type re-emitted, and nothing else."
  (:require [flint.aot :as aot]
            [flint.rt]
            [clojure.string :as str]))

;; --------------------------------------------------------------- the builder
;;
;; A volatile line buffer and one counter shared by value names (`%vN`) and
;; block labels (`LN`), so the two can never collide.

(defn- mk [] {:n (volatile! 0) :out (volatile! [])})
(defn- e! [b s] (vswap! (:out b) conj s) nil)
(defn- r! [b] (str "%v" (vswap! (:n b) inc)))
(defn- lb! [b] (str "L" (vswap! (:n b) inc)))

(defn- ld! [b ty p]
  (let [v (r! b)] (e! b (str "  " v " = load " ty ", ptr " p)) v))

(defn- st! [b ty val p]
  (e! b (str "  store " ty " " val ", ptr " p)))

(defn- gep! [b base off]
  (let [v (r! b)]
    (e! b (str "  " v " = getelementptr i8, ptr " base ", i64 " off))
    v))

(defn- op2! [b o ty x y]
  (let [v (r! b)] (e! b (str "  " v " = " o " " ty " " x ", " y)) v))

(defn- conv! [b o from x to]
  (let [v (r! b)] (e! b (str "  " v " = " o " " from " " x " to " to)) v))

(defn- call! [b ret f args]
  (if (= ret "void")
    (do (e! b (str "  call void " f "(" (str/join ", " args) ")")) nil)
    (let [v (r! b)]
      (e! b (str "  " v " = call " ret " " f "(" (str/join ", " args) ")"))
      v)))

(defn- term!
  "Emit a terminator and OPEN A FRESH BLOCK after it.

  Every terminator goes through here, so nothing is ever appended to a block
  that already ended. The alternative is tracking whether the current block is
  live, and getting that wrong produces IR that the verifier rejects with a
  message about the block rather than about the instruction that ended it --
  which for `:return` followed by dead bytecode is a long way from the cause."
  [b s]
  (e! b (str "  " s))
  (e! b (str (lb! b) ":")))

;; ------------------------------------------------------------------- memory
;;
;; The four cached bases and the stack top are `alloca`s; the sync block's
;; fields are reached through one `getelementptr` per field, named once in the
;; entry block so every reload is a plain load.

(defn- topb! [b] (ld! b "ptr" "%L.topb"))
(defn- set-topb! [b p] (st! b "ptr" p "%L.topb"))
(defn- gas! [b] (ld! b "i32" "%L.gas"))
(defn- gas-zero! [b] (st! b "i32" "0" "%L.gas"))

(defn- push!
  "Push an i64 that is already in `v`."
  [b v]
  (let [tb (topb! b)]
    (st! b "i64" v tb)
    (set-topb! b (gep! b tb "8"))))

(defn- pop!
  "Pop, and answer the value."
  [b]
  (let [tb (topb! b)
        nb (gep! b tb "-8")]
    (set-topb! b nb)
    (ld! b "i64" nb)))

(defn- top-index
  "The value stack top as an index, which is what the helpers take."
  [b]
  (let [tb (topb! b)
        sp (ld! b "ptr" "%L.sp")
        a (conv! b "ptrtoint" "ptr" tb "i64")
        c (conv! b "ptrtoint" "ptr" sp "i64")
        d (op2! b "sub" "i64" a c)
        s (op2! b "lshr" "i64" d "3")]
    (conv! b "trunc" "i64" s "i32")))

(defn- base!
  "Load one pointer-width sync field into an alloca."
  [b field alloca]
  (let [v (ld! b "i64" field)
        p (conv! b "inttoptr" "i64" v "ptr")]
    (st! b "ptr" p alloca)))

(defn- reload!
  "Everything a call back into Rust can invalidate. `need` is what this body
  actually reads -- see `flint.aot/needs`, which is where the measurement that
  made it conditional lives."
  [b need]
  (base! b "%sync.stack" "%L.sp")
  (let [sp (ld! b "ptr" "%L.sp")
        t (ld! b "i64" "%sync.top")
        off (op2! b "shl" "i64" t "3")
        tb (gep! b sp off)]
    (st! b "ptr" tb "%L.topb")
    (when (:fpb need)
      (let [z (conv! b "zext" "i32" "%fp" "i64")
            o (op2! b "shl" "i64" z "3")]
        (st! b "ptr" (gep! b sp o) "%L.fpb")))
    (when (:retb need)
      (let [z (conv! b "zext" "i32" "%retto" "i64")
            o (op2! b "shl" "i64" z "3")]
        (st! b "ptr" (gep! b sp o) "%L.retb")))))

;; ---------------------------------------------------------------- leaving
;;
;; Every helper that can ask compiled code to leave answers nonzero. The test
;; and the `ret` are the same three blocks each time.

(defn- ret-if! [b rv]
  (let [c (op2! b "icmp ne" "i32" rv "0")
        t (lb! b)
        f (lb! b)]
    (e! b (str "  br i1 " c ", label %" t ", label %" f))
    (e! b (str t ":"))
    (e! b "  ret void")
    (e! b (str f ":"))))

(defn- tick!
  "A back-edge: flush the gas accumulated since the last exit and ask whether
  the interpreter's own tick would now fire. The one preemption point in
  compiled code, in the one place that is cheap -- back-edges are 2.4% of
  executed instructions."
  [ctx ip]
  (let [b (:b ctx)
        g (gas! b)
        top (top-index b)
        rv (call! b "i32" "@aot_tick"
                  ["ptr %rt" (str "i32 " g) (str "i32 " top)
                   (str "i32 " ip) (str "i32 " (:i ctx))])]
    (ret-if! b rv)
    (gas-zero! b)))

(defn- br-to!
  "Reach chunk `j`. Forward or backward is the same branch; only a BACKWARD one
  pays the tick, because only a backward one can loop."
  [ctx j ip]
  (when (<= j (:i ctx)) (tick! ctx ip))
  (term! (:b ctx) (str "br label %chunk" j)))

(defn- br-to-if!
  "The same, under an i1 already computed."
  [ctx cnd j ip]
  (let [b (:b ctx)
        t (lb! b)
        f (lb! b)]
    (e! b (str "  br i1 " cnd ", label %" t ", label %" f))
    (e! b (str t ":"))
    (when (<= j (:i ctx)) (tick! ctx ip))
    (e! b (str "  br label %chunk" j))
    (e! b (str f ":"))))

(defn- bail!
  "Hand control back at `ip`, and say where compiled code takes over again."
  [ctx ip]
  (let [b (:b ctx)
        ins (:ins ctx)
        [r-ip r-blk] (aot/resume-after (:op ins) ip (:len ins) (:chunk-of ctx))
        top (top-index b)
        g (gas! b)]
    (call! b "void" "@aot_bail"
           ["ptr %rt" (str "i32 " top) (str "i32 " ip)
            (str "i32 " r-ip) (str "i32 " r-blk) (str "i32 " g)])
    (term! b "ret void")))

;; ------------------------------------------- the specialised integer ops
;;
;; Emitted only where the compiler PROVED both operands are integers, so the
;; fast path below is the path taken. `^int` means integer and not fixnum, so
;; the tags are still tested and everything else goes to the helper --
;; `flint.aot` carries the argument for why this is a helper and not a bail.

(def ^:private TAG-SHIFTED (bit-shift-left aot/TAG-FIXNUM 48))

(def ^:private PAYLOAD-BITS
  "The 48 payload bits of a NaN box. `flint.aot` names the same constant
  `FIXNUM-BITS` because that is the only place it uses it; a heap address is
  masked with it too, and `Value::as_heap` is the Rust half of that sentence."
  aot/FIXNUM-BITS)

(defn- is-fixnum! [b v]
  (let [s (op2! b "lshr" "i64" v "48")]
    (op2! b "icmp eq" "i64" s (str aot/TAG-FIXNUM))))

(defn- unbox!
  "Sign-extend the low 48 bits."
  [b v]
  (let [a (op2! b "shl" "i64" v "16")]
    (op2! b "ashr" "i64" a "16")))

(defn- box! [b v]
  (let [a (op2! b "and" "i64" v (str aot/FIXNUM-BITS))]
    (op2! b "or" "i64" a (str TAG-SHIFTED))))

(defn- fits!
  "i1: does `v` survive a round trip through 48 bits? Written as the operation
  that would LOSE the information, so it cannot disagree with `box!` the way a
  pair of range constants could."
  [b v]
  (op2! b "icmp eq" "i64" (unbox! b v) v))

(defn- in-24!
  "i1: is `v` inside 24 bits? A 64-bit multiply of two 47-bit values can WRAP,
  and a wrapped product can look like it fits, so multiplication additionally
  demands both operands small enough that the product cannot reach 48."
  [b v]
  (let [a (op2! b "shl" "i64" v "40")
        c (op2! b "ashr" "i64" a "40")]
    (op2! b "icmp eq" "i64" c v)))

(def ^:private INT-CMP
  {:lt-int "slt" :le-int "sle" :gt-int "sgt" :ge-int "sge" :eq-int "eq"})

(def ^:private INT-ARITH
  {:add-int "add" :sub-int "sub" :mul-int "mul"})

(defn- int-slow!
  "Hand the whole operation to Rust, both operands still on the value stack."
  [ctx opcode ip nx j]
  (let [b (:b ctx)
        top (top-index b)
        g (gas! b)
        rv (call! b "i32" "@aot_int_binop"
                  ["ptr %rt" (str "i32 " opcode) (str "i32 " top) (str "i32 " ip)
                   (str "i32 " (:i ctx)) (str "i32 " (if j nx aot/AOT-NEVER))
                   (str "i32 " (or j 0)) (str "i32 " g)])]
    (ret-if! b rv)
    (gas-zero! b)
    (reload! b (:need ctx))))

(defn- emit-int-op!
  "One specialised integer operation, fast path inline.

  The value stack is untouched until the result is stored, so every route to
  the slow path finds both operands exactly where the interpreter would."
  [ctx ins opcode]
  (let [b (:b ctx)
        k (:op ins)
        ip (:ip ins)
        nx (+ ip (:len ins))
        j ((:chunk-of ctx) nx)
        tb (topb! b)
        xp (gep! b tb "-16")
        yp (gep! b tb "-8")
        x (ld! b "i64" xp)
        y (ld! b "i64" yp)
        both (op2! b "and" "i1" (is-fixnum! b x) (is-fixnum! b y))
        lfast (lb! b)
        lslow (lb! b)
        ldone (lb! b)]
    (e! b (str "  br i1 " both ", label %" lfast ", label %" lslow))
    (e! b (str lfast ":"))
    (let [ix (unbox! b x)
          iy (unbox! b y)]
      (if (INT-CMP k)
        ;; Branchless: both answers are constants, so `select` beats a branch.
        (let [c (op2! b (str "icmp " (INT-CMP k)) "i64" ix iy)
              s (r! b)]
          (e! b (str "  " s " = select i1 " c ", i64 " aot/V-TRUE ", i64 " aot/V-FALSE))
          (st! b "i64" s xp)
          (set-topb! b yp)
          (e! b (str "  br label %" ldone)))
        (do
          (when (= :mul-int k)
            (let [g (op2! b "and" "i1" (in-24! b ix) (in-24! b iy))
                  lmul (lb! b)]
              (e! b (str "  br i1 " g ", label %" lmul ", label %" lslow))
              (e! b (str lmul ":"))))
          (let [res (op2! b (INT-ARITH k) "i64" ix iy)
                lok (lb! b)]
            (e! b (str "  br i1 " (fits! b res) ", label %" lok ", label %" lslow))
            (e! b (str lok ":"))
            (st! b "i64" (box! b res) xp)
            (set-topb! b yp)
            (e! b (str "  br label %" ldone))))))
    (e! b (str lslow ":"))
    (int-slow! ctx opcode ip nx j)
    (e! b (str "  br label %" ldone))
    (e! b (str ldone ":"))))

;; ---------------------------------------------------------------- one opcode

(defn- falsy!
  "i1: the value is nil or false -- what a JUMP_IF_FALSE branches on."
  [b v]
  (let [a (op2! b "icmp eq" "i64" v (str aot/V-NIL))
        c (op2! b "icmp eq" "i64" v (str aot/V-FALSE))]
    (op2! b "or" "i1" a c)))

(defn- opcode-byte
  "The byte an opcode keyword was decoded from. Read back out of `flint.aot/OPS`
  rather than restated, so there is one table (AGENTS.md §1)."
  [k]
  (or (first (keep (fn [e] (when (= (first (val e)) k) (key e))) aot/OPS))
      (throw (ex-info (str "no opcode byte for " k) {:op k}))))

(defn- emit-instr! [ctx ins]
  (let [b (:b ctx)
        k (:op ins)
        ip (:ip ins)
        ob (:b ins)
        ln (:len ins)
        chunk-of (:chunk-of ctx)
        need (:need ctx)
        tgt (aot/jump-target ins)
        ctx (assoc ctx :ins ins)
        ;; Where compiled code takes over again after handing this instruction
        ;; back, as `[next-ip next-block]`. The emitter knows it statically, so
        ;; nothing is ever searched at run time; `AOT-NEVER` means the next
        ;; instruction does not begin a chunk, NOT "never come back".
        after (fn [] (let [nx (+ ip ln) j (chunk-of nx)]
                       [(if j nx aot/AOT-NEVER) (or j 0)]))]
    (case k
      :nil (push! b (str aot/V-NIL))
      :true (push! b (str aot/V-TRUE))
      :false (push! b (str aot/V-FALSE))
      :int (push! b (str (aot/fixnum (aot/i16 ob))))
      :const (push! b (ld! b "i64" (gep! b (ld! b "ptr" "%L.consts")
                                         (str (* 8 (aot/u16 ob))))))
      :var (push! b (ld! b "i64" (gep! b (ld! b "ptr" "%L.globals")
                                       (str (* 8 (aot/u16 ob))))))
      :local (push! b (ld! b "i64" (gep! b (ld! b "ptr" "%L.fpb")
                                         (str (* 8 (nth ob 0))))))
      :local-w (push! b (ld! b "i64" (gep! b (ld! b "ptr" "%L.fpb")
                                           (str (* 8 (aot/u16 ob))))))
      :self (push! b (ld! b "i64" (ld! b "ptr" "%L.retb")))
      ;; The closure is `stack[ret_to]` and an upvalue is one of its slots. The
      ;; frame deliberately does not cache the closure -- that copy was once a
      ;; root the collector could not see -- so this reads it exactly as the
      ;; interpreter does.
      ;;
      ;; MASKED to 48 bits, not truncated to 32. `flint.aot` writes
      ;; `i32.wrap_i64` here and that IS the mask on wasm, where an address is
      ;; 32 bits by the platform's definition. `mem::Addr` is a `u64` and
      ;; `Value::as_heap` takes 48 payload bits, so a 32-bit wrap is a heap cap
      ;; this emitter has no business imposing.
      :upval (let [h (ld! b "ptr" "%L.heap")
                   cv (ld! b "i64" (ld! b "ptr" "%L.retb"))
                   a (op2! b "and" "i64" cv (str PAYLOAD-BITS))]
               (push! b (ld! b "i64" (gep! b (gep! b h a)
                                           (str (+ 8 (* 8 (inc (nth ob 0)))))))))
      :set-local (let [f (ld! b "ptr" "%L.fpb")
                       v (pop! b)]
                   (st! b "i64" v (gep! b f (str (* 8 (nth ob 0))))))
      :set-local-w (let [f (ld! b "ptr" "%L.fpb")
                         v (pop! b)]
                     (st! b "i64" v (gep! b f (str (* 8 (aot/u16 ob))))))
      :set-var (let [g (ld! b "ptr" "%L.globals")
                     v (pop! b)]
                 (st! b "i64" v (gep! b g (str (* 8 (aot/u16 ob))))))
      :pop (set-topb! b (gep! b (topb! b) "-8"))
      :dup (push! b (ld! b "i64" (gep! b (topb! b) "-8")))
      :jump (br-to! ctx (chunk-of tgt) ip)
      :jump-if-false (br-to-if! ctx (falsy! b (pop! b)) (chunk-of tgt) ip)
      :return (let [top (top-index b)
                    g (gas! b)]
                (call! b "void" "@aot_return"
                       ["ptr %rt" (str "i32 " top) (str "i32 " g)])
                (term! b "ret void"))
      :call (let [[nip nblk] (after)
                  top (top-index b)
                  g (gas! b)
                  rv (call! b "i32" "@aot_call"
                            ["ptr %rt" (str "i32 " (nth ob 0)) (str "i32 " top)
                             (str "i32 " ip) (str "i32 " (:i ctx))
                             (str "i32 " nip) (str "i32 " nblk) (str "i32 " g)])]
              (ret-if! b rv)
              (gas-zero! b)
              (reload! b need))
      :native (let [[nip nblk] (after)
                    top (top-index b)
                    g (gas! b)
                    rv (call! b "i32" "@aot_native"
                              ["ptr %rt" (str "i32 " (aot/u16 ob)) (str "i32 " (nth ob 2))
                               (str "i32 " top) (str "i32 " ip) (str "i32 " (:i ctx))
                               (str "i32 " nip) (str "i32 " nblk) (str "i32 " g)])]
                (ret-if! b rv)
                (gas-zero! b)
                (reload! b need))
      ;; A type predicate. No bail protocol and no reload: the helper cannot
      ;; allocate, cannot fail and cannot re-enter, so nothing cached here can
      ;; move underneath it. The answer overwrites the argument, so the stack
      ;; top does not change either.
      :type-p (let [dst (gep! b (topb! b) "-8")
                    top (top-index b)
                    v (call! b "i64" "@aot_type_p"
                             ["ptr %rt" (str "i32 " (nth ob 0)) (str "i32 " top)])]
                (st! b "i64" v dst))

      (:add-int :sub-int :mul-int :lt-int :le-int :gt-int :ge-int :eq-int)
      (emit-int-op! ctx ins (opcode-byte k))

      ;; Everything else goes back to the interpreter for exactly one
      ;; instruction. That is what lets this emitter be COMPLETE from the first
      ;; version rather than refusing a whole function over one rare opcode.
      (bail! ctx ip))))

;; ------------------------------------------------------------------ assembly

(defn- prologue!
  [b need n]
  (e! b "start:")
  (e! b "  %L.sp = alloca ptr")
  (e! b "  %L.topb = alloca ptr")
  (e! b "  %L.fpb = alloca ptr")
  (e! b "  %L.retb = alloca ptr")
  (e! b "  %L.consts = alloca ptr")
  (e! b "  %L.globals = alloca ptr")
  (e! b "  %L.heap = alloca ptr")
  (e! b "  %L.gas = alloca i32")
  (e! b "  store i32 0, ptr %L.gas")
  (e! b "  %sync.p = inttoptr i64 %sync to ptr")
  (e! b "  %sync.stack = getelementptr %flint.sync, ptr %sync.p, i32 0, i32 0")
  (e! b "  %sync.top = getelementptr %flint.sync, ptr %sync.p, i32 0, i32 1")
  (e! b "  %sync.consts = getelementptr %flint.sync, ptr %sync.p, i32 0, i32 2")
  (e! b "  %sync.globals = getelementptr %flint.sync, ptr %sync.p, i32 0, i32 3")
  (e! b "  %sync.heap = getelementptr %flint.sync, ptr %sync.p, i32 0, i32 4")
  ;; The write-once bases, read once on the way in. `stack` and `top` are the
  ;; only two a crossing can change, which is what `reload!` is for.
  (when (:consts need) (base! b "%sync.consts" "%L.consts"))
  (when (:globals need) (base! b "%sync.globals" "%L.globals"))
  (when (:heap need) (base! b "%sync.heap" "%L.heap"))
  (reload! b need)
  (e! b (str "  switch i32 %blk, label %exit [ "
             (str/join " " (map (fn [k] (str "i32 " k ", label %chunk" k)) (range n)))
             " ]")))

(defn compile-arity
  "One arity to an LLVM function. Returns `{:ir :name :points :depth :chunks}`,
  or nil if it cannot be compiled -- an unknown opcode or an unbounded operand
  stack, both of which mean this emitter does not understand the code well
  enough to be trusted with it.

  `k` names the function; it is an ordinal over every arity in the image, so a
  name is stable whether or not its neighbours compiled.

  `chunk-all?` makes EVERY instruction a boundary, and it is a bisection handle
  rather than a mode, for the reason `flint.aot/boundaries` gives: if a failure
  survives maximal chunking then no boundary was missing and the fault is in
  how an opcode is EMITTED. Nothing passes it yet -- the wasm side reaches its
  copy through `flint.link`, which reads the environment, and this namespace is
  compiled BY flint and has no host interop -- so today it is reached by calling
  this arity directly."
  ([code start len k] (compile-arity code start len k false))
  ([code start len k chunk-all?]
   (when-let [instrs (seq (aot/decode code start len))]
     (when-let [depth (aot/max-depth instrs)]
       (let [starts (into #{} (map (fn [i] (:ip i)) instrs))
             ;; A jump into the middle of an instruction. Nothing the compiler
             ;; emits does this, which is exactly why it is checked rather than
             ;; assumed: the failure would be a branch to a block that does not
             ;; exist, and LLVM would report it as a parse error in generated
             ;; text with no line the reader can act on.
             _ (when-not (every? starts (keep aot/jump-target instrs))
                 (throw (ex-info "jump into the middle of an instruction"
                                 {:offsets (remove starts (keep aot/jump-target instrs))})))
             bounds (aot/boundaries instrs chunk-all?)
             cks (aot/chunks instrs bounds)
             n (count cks)
             chunk-of (zipmap bounds (range))
             need (aot/needs instrs)
             nm (str "@flint_aot_" k)
             b (mk)
             ctx {:b b :n n :chunk-of chunk-of :need need}]
         (e! b (str "define internal void " nm
                    "(ptr %rt, i32 %fp, i32 %retto, i32 %blk, i64 %sync) {"))
         (prologue! b need n)
         (doseq [c cks]
           (let [i (:idx c)]
             (e! b (str "chunk" i ":"))
             ;; Gas, charged by the chunk's own static instruction count --
             ;; exact, because a chunk has no internal branch, so either all of
             ;; it runs or none of it does.
             (when (pos? (:charge c))
               (let [g (gas! b)]
                 (st! b "i32" (op2! b "add" "i32" g (str (:charge c))) "%L.gas")))
             (doseq [ins (:instrs c)]
               (emit-instr! (assoc ctx :i i) ins))
             (e! b (str "  br label %"
                        (if (< (inc i) n) (str "chunk" (inc i)) "exit")))))
         (e! b "exit:")
         (e! b "  ret void")
         (e! b "}")
         {:ir (str/join "\n" @(:out b))
          :name nm
          :points (mapv (fn [c] [(:ip c) (:idx c)]) cks)
          :depth depth
          :chunks n})))))

(defn compile-arities
  "Emit an LLVM function for every arity the emitter can take, and stamp each
  one's slot into the image builder.

  The mirror of `flint.bundle/compile-arities`, and the same order: `(fn-index,
  arity-index)` ascending, so the table this produces and the indices it stores
  into the arities cannot drift apart.

  A slot is 1-BASED. Natively a slot is an index into a table the artifact
  registers rather than a wasm table index, and `AotFn` already reserves 0 for
  \"not compiled\"."
  [b]
  (let [code (vec (:code @b))
        slots-of (for [[fi f] (map-indexed vector (:fns @b))
                       [ai a] (map-indexed vector (:arities f))]
                   [fi ai a])
        results (mapv (fn [k [fi ai a]]
                        [fi ai (compile-arity code (:off a) (:len a) k)])
                      (range) slots-of)
        ok (filterv (fn [[_ _ r]] (some? r)) results)
        table (mapv (fn [k [_ _ r]]
                      {:slot (inc k) :depth (:depth r) :points (:points r)})
                    (range) ok)
        index (into {} (map-indexed (fn [k [fi ai _]] [[fi ai] k]) ok))]
    (vswap! b update :fns
            (fn [fns]
              (vec (map-indexed
                    (fn [fi f]
                      (update f :arities
                              (fn [as]
                                (vec (map-indexed
                                      (fn [ai a] (assoc a :aot (get index [fi ai] 0xFFFFFFFF)))
                                      as)))))
                    fns))))
    (vswap! b assoc :aot table)
    {:ir (str/join "\n\n" (map (fn [[_ _ r]] (:ir r)) ok))
     :names (mapv (fn [[_ _ r]] (:name r)) ok)
     :compiled (count ok)
     :total (count slots-of)}))

;; ------------------------------------------------------------------- module

(def ^:private HEX
  "Byte values of `0123456789abcdef`. A vector rather than a string because
  this indexes it per nibble of a whole program image."
  [48 49 50 51 52 53 54 55 56 57 97 98 99 100 101 102])

(defn- escape
  "The image as the body of an LLVM `c\"...\"` constant.

  Built into a BYTE STRING through a transient, for the reason
  `flint.selfhost/base64` gives: the obvious version allocates one string per
  byte, and an image is hundreds of thousands of them."
  [bytes]
  (let [bs (if (flint.rt/bytes? bytes) bytes (flint.rt/vec->b bytes))
        n (flint.rt/b-count bs)]
    (flint.rt/b->str
     (flint.rt/b-persistent!
      (loop [i 0 out (flint.rt/b-transient (flint.rt/str->b ""))]
        (if (>= i n)
          out
          (let [c (flint.rt/b-at bs i)]
            (recur
             (inc i)
             ;; Printable ASCII rides through; everything else, and the two
             ;; characters that would end or escape the literal, goes as `\HH`.
             (if (and (>= c 0x20) (< c 0x7F) (not= c 0x22) (not= c 0x5C))
               (flint.rt/b-conj! out c)
               (let [out (flint.rt/b-conj! out 0x5C)
                     out (flint.rt/b-conj! out (nth HEX (bit-shift-right c 4)))]
                 (flint.rt/b-conj! out (nth HEX (bit-and c 15)))))))))))))

;; THE STATUS NUMBERS ARE THE ABI, and they are named here rather than written
;; into the emitted text so that the one place they appear is a definition. Every
;; target agrees on 0, 1, 2 and each spells the names its own way
;; (`DECISIONS.md#four-operations`).
;; `SHELVED` and `WEDGED` added 2026-09-25: 3 was already returned by the runtime
;; and declared by no face, and 4 is new because the scheduler's deadlock branch
;; answered 0, the same number a clean finish answers.
(def ^:private DONE 0)
(def ^:private THREW 1)
(def ^:private NEEDS-HOST 2)
(def ^:private SHELVED 3)
(def ^:private WEDGED 4)

(def ^:private DECLS
  ["; The runtime this links against. Every one of them is `#[no_mangle]` in"
   "; `runtime/src/aot.rs`, and `flint_native_main` is `nativeabi/src/lib.rs`."
   "declare i32 @aot_call(ptr, i32, i32, i32, i32, i32, i32, i32)"
   "declare i32 @aot_native(ptr, i32, i32, i32, i32, i32, i32, i32, i32)"
   "declare i32 @aot_int_binop(ptr, i32, i32, i32, i32, i32, i32, i32)"
   "declare i64 @aot_type_p(ptr, i32, i32)"
   "declare void @aot_return(ptr, i32, i32)"
   "declare void @aot_bail(ptr, i32, i32, i32, i32, i32)"
   "declare i32 @aot_tick(ptr, i32, i32, i32, i32)"
   "declare void @flint_aot_register(ptr, i64)"
   "declare i32 @flint_native_main(ptr, i64, i32, ptr)"
   "; The three operations' runtime halves, also `nativeabi/src/lib.rs`."
   "declare i64 @flint_native_boot(ptr, i64, ptr)"
   "declare i32 @flint_native_loop(i64)"
   "declare i32 @flint_native_link(ptr, ptr, ptr)"])

(defn emit-module
  "The finished `.ll`: the program image, its compiled arities, the table that
  names them, and a `main` that hands both to the runtime.

  Nothing here is linked and nothing needs to be. That was the whole error in
  the refusal this replaces -- it gave `:to :native`'s reason for `:to :llvm`'s
  absence (`DECISIONS.md#llvm-ir-target`)."
  [image ir names]
  (let [n (count names)
        bs (if (flint.rt/bytes? image) image (flint.rt/vec->b image))
        len (flint.rt/b-count bs)]
    (str/join
     "\n"
     (concat
      ["; flint program, as LLVM IR. Generated by `flint compile :to :llvm`."
       ";"
       "; Link it:  clang this.ll libflintnative.a -o prog"
       ";"
       "; The sync block, which is `AotSync` in `runtime/src/aot.rs`: seven"
       "; pointer-width fields. This module is 64-bit."
       "%flint.sync = type { i64, i64, i64, i64, i64, i64, i64 }"
       ""
       (str "@flint_image = private constant [" len " x i8] c\"" (escape bs) "\"")
       (if (zero? n)
         "@flint_aot_table = constant [0 x ptr] zeroinitializer"
         (str "@flint_aot_table = constant [" n " x ptr] ["
              (str/join ", " (map (fn [x] (str "ptr " x)) names)) "]"))
       ""]
      DECLS
      [""
       ir
       ""
       ;; `weak`, AND THAT IS WHAT MAKES THE ARTIFACT DRIVABLE. This module is
       ;; the only one of the four targets that defines a `main` -- a wasm module,
       ;; a JVM class and a CLR assembly all have none -- and a strong one made an
       ;; embedder's own `main` a DUPLICATE SYMBOL at link time. So the artifact
       ;; could be run and could not be driven, which is the gap `boot`/`loop`
       ;; below exist to close, reintroduced by the convenience entry.
       ;;
       ;; Weak keeps both: `clang prog.ll libflintnative.a -o prog` still links
       ;; and runs, and a host that brings its own `main` silently overrides this
       ;; one and drives the three operations instead. Verified both ways.
       "define weak i32 @main(i32 %argc, ptr %argv) {"
       "start:"
       (str "  call void @flint_aot_register(ptr @flint_aot_table, i64 " n ")")
       (str "  %r = call i32 @flint_native_main(ptr @flint_image, i64 " len
            ", i32 %argc, ptr %argv)")
       "  ret i32 %r"
       "}"
       ""
       ;; --- THE THREE OPERATIONS (`DECISIONS.md#four-operations`) -----------
       ;;
       ;; `boot`, `loop`, `link`, so a host can DRIVE this artifact instead of
       ;; only running it. Before these, `:to :llvm` emitted a module whose one
       ;; entry was `main`: it ran to completion and there was no way in, which
       ;; made the fourth target the only one with no face.
       ;;
       ;; THREE-LINE WRAPPERS, exactly as `main` above wraps
       ;; `flint_native_main`. The runtime halves are in the archive and know
       ;; nothing about where an image lives; these pass `@flint_image` and its
       ;; length, so the image stays a detail of the artifact.
       "; --- the three operations. `boot` registers the AOT table first, for"
       "; `main`'s reason: a host that drives this artifact never calls `main`,"
       "; so the table would otherwise be registered by nothing and every"
       "; compiled arity would be missing at the moment it was first wanted."
       (str "@FLINT_DONE = constant i32 " DONE)
       (str "@FLINT_THREW = constant i32 " THREW)
       (str "@FLINT_NEEDS_HOST = constant i32 " NEEDS-HOST)
       (str "@FLINT_SHELVED = constant i32 " SHELVED)
       (str "@FLINT_WEDGED = constant i32 " WEDGED)
       ""
       "define i64 @flint_boot(ptr %bridge) {"
       "start:"
       (str "  call void @flint_aot_register(ptr @flint_aot_table, i64 " n ")")
       (str "  %h = call i64 @flint_native_boot(ptr @flint_image, i64 " len
            ", ptr %bridge)")
       "  ret i64 %h"
       "}"
       ""
       "define i32 @flint_loop(i64 %sandbox) {"
       "start:"
       "  %r = call i32 @flint_native_loop(i64 %sandbox)"
       "  ret i32 %r"
       "}"
       ""
       "define i32 @flint_link(ptr %bridge, ptr %name, ptr %fn) {"
       "start:"
       "  %r = call i32 @flint_native_link(ptr %bridge, ptr %name, ptr %fn)"
       "  ret i32 %r"
       "}"
       ""]))))
