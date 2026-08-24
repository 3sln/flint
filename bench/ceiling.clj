;; The ceiling for `doc/decisions/0013`: how much of what the emitter leaves on
;; the table is the emitter's, and how much is wasm's.
;;
;; `tight`'s loop, hand-emitted three ways, all doing the SAME work in the same
;; engine. The deltas between them are the answer:
;;
;;   C1  operands in wasm LOCALS, a real `loop`, arithmetic inlined.
;;       The floor. Nothing indirect, nothing in memory.
;;   C2  operands in locals, a real `loop`, arithmetic through `call_indirect`
;;       -- the same shape a native call has.  C1 -> C2 is what the calls cost.
;;   C3  operands on a linear-memory stack, driven by `loop` + `br_table`, with
;;       the same indirect calls. This is what the emitter actually produces.
;;       C2 -> C3 is what OUR EMISSION SHAPE costs, and it is the number that
;;       says whether register-allocating between safepoints is worth building.
;;
;; Values are NaN-boxed fixnums throughout, untagged and retagged exactly as the
;; runtime does, so the arithmetic is not cheated.
(babashka.classpath/add-classpath "src")
(require '[flint.wasm :as w] '[clojure.java.io :as io])

(def TAG-FIX (bit-shift-left 0xFFFA 48))
(def TAG-SPEC (bit-shift-left 0xFFFB 48))
(def V-TRUE (bit-or TAG-SPEC 2))
(def V-FALSE (bit-or TAG-SPEC 1))
(defn fixnum [n] (bit-or TAG-FIX (bit-and n 0x0000FFFFFFFFFFFF)))

(def i64 0x7E) (def i32 0x7F)
(defn u [n] (w/uleb n)) (defn s [n] (w/sleb n))
(defn lget [i] [0x20 (u i)]) (defn lset [i] [0x21 (u i)]) (defn ltee [i] [0x22 (u i)])
(defn i64c [n] [0x42 (s n)]) (defn i32c [n] [0x41 (s n)])
(def i64-add 0x7C) (def i64-shl 0x86) (def i64-shr-s 0x87)
(def i64-and 0x83) (def i64-or 0x84) (def i64-lt-s 0x53) (def i64-eq 0x51)
(def i64-load 0x29) (def i64-store 0x37)
(def i32-eqz 0x45) (def i32-add 0x6A)
(def blk 0x02) (def lp 0x03) (def iff 0x04) (def els 0x05)
(def end 0x0B) (def br 0x0C) (def br-if 0x0D) (def br-table 0x0E) (def ret 0x0F)
(def void 0x40)
(defn ld [off] [i64-load (u 3) (u off)])
(defn st [off] [i64-store (u 3) (u off)])
;; `call_indirect` takes the table index on the stack, so the slot goes with it:
;; 0 = add, 1 = lt, 2 = inc.
(defn call-ind [t slot] [(i32c slot) 0x11 (u t) (u 0)])

;; A boxed fixnum on the stack -> its raw i64.
(def untag [(i64c 16) i64-shl (i64c 16) i64-shr-s])
;; A raw i64 -> a boxed fixnum.
(def retag [(i64c 0x0000FFFFFFFFFFFF) i64-and (i64c TAG-FIX) i64-or])

;; --- the three "natives" ---------------------------------------------------
(def f-add [(u 0) (lget 0) untag (lget 1) untag i64-add retag end])
(def f-lt  [(u 0) (lget 0) untag (lget 1) untag i64-lt-s
            iff i64 (i64c V-TRUE) els (i64c V-FALSE) end end])
(def f-inc [(u 0) (lget 0) untag (i64c 1) i64-add retag end])

;; --- C1: locals, a real loop, inlined arithmetic ---------------------------
;; param 0 = n (raw). locals 1 = i (boxed), 2 = acc (boxed).
(def f-c1
  [(u 1) (u 2) i64                                        ; 2 i64 locals
   (i64c (fixnum 0)) (lset 1)
   (i64c (fixnum 0)) (lset 2)
   blk void
   lp void
   (lget 1) untag (lget 0) i64-lt-s i32-eqz br-if (u 1)   ; while i < n
   (lget 2) untag (lget 1) untag i64-add retag (lset 2)    ; acc = acc + i
   (lget 1) untag (i64c 1) i64-add retag (lset 1)          ; i = i + 1
   br (u 0)
   end end
   (lget 2) untag end])

;; --- C2: locals, a real loop, the SAME work through call_indirect ----------
(def f-c2
  ;; param 0 = n (raw). locals 1 = i, 2 = acc, 3 = n boxed.
  [(u 1) (u 3) i64
   (i64c (fixnum 0)) (lset 1)
   (i64c (fixnum 0)) (lset 2)
   (lget 0) retag (lset 3)
   blk void
   lp void
   (lget 1) (lget 3) (call-ind 0 1)                        ; lt(i, n)
   (i64c V-FALSE) i64-eq br-if (u 1)
   (lget 2) (lget 1) (call-ind 0 0) (lset 2)               ; acc = add(acc, i)
   (lget 1) (call-ind 1 2) (lset 1)                        ; i = inc(i)
   br (u 0)
   end end
   (lget 2) untag end])

;; --- C3: a linear-memory operand stack, driven by loop + br_table ----------
;; Locals at mem 0 (i), 8 (acc), 16 (n). Operand stack from 64.
;; local 1 = $sp (byte address), local 2 = $pc.
(def f-c3
  [(u 2) (u 1) i64 (u 2) i32                               ; 1 i64 local, 2 i32
   ;; n into memory, i and acc initialised
   (i32c 16) (lget 0) retag (st 0)
   (i32c 0) (i64c (fixnum 0)) (st 0)
   (i32c 8) (i64c (fixnum 0)) (st 0)
   (i32c 64) (lset 2)                                      ; $sp
   (i32c 0) (lset 3)                                       ; $pc
   lp void
   blk void                                                ; $EXIT   (depth 3 from a chunk)
   blk void                                                ; $B2
   blk void                                                ; $B1
   blk void                                                ; $B0
   (lget 3) br-table (u 3) (u 0) (u 1) (u 2) (u 3)
   end
   ;; --- chunk 0: push i, push n, lt, branch ---
   (lget 2) (i32c 0) (ld 0) (st 0) (lget 2) (i32c 8) i32-add (lset 2)
   (lget 2) (i32c 16) (ld 0) (st 0) (lget 2) (i32c 8) i32-add (lset 2)
   (lget 2) (i32c 16) [0x6B] (ltee 2) (ld 0)
   (lget 2) (i32c 8) i32-add (ld 0)
   (call-ind 0 1)
   (i64c V-FALSE) i64-eq
   br-if (u 1)                                             ; false -> chunk 2
   end
   ;; --- chunk 1: acc = add(acc,i); i = inc(i); back-edge ---
   (lget 2) (i32c 8) (ld 0) (st 0) (lget 2) (i32c 8) i32-add (lset 2)
   (lget 2) (i32c 0) (ld 0) (st 0) (lget 2) (i32c 8) i32-add (lset 2)
   (lget 2) (i32c 16) [0x6B] (ltee 2) (ld 0)
   (lget 2) (i32c 8) i32-add (ld 0)
   (call-ind 0 0)
   (lset 1) (i32c 8) (lget 1) (st 0)
   (lget 2) (i32c 0) (ld 0) (st 0) (lget 2) (i32c 8) i32-add (lset 2)
   (lget 2) (i32c 8) [0x6B] (ltee 2) (ld 0)
   (call-ind 1 2)
   (lset 1) (i32c 0) (lget 1) (st 0)
   (i32c 0) (lset 3) br (u 2)                              ; $pc = 0; br $dispatch
   end
   ;; --- chunk 2: the answer ---
   (i32c 8) (ld 0) (lset 1)
   end
   end
   (lget 1) untag end])

(defn sect [id payload]
  (let [p (w/->bytes payload)] [id (u (alength p)) p]))

(def types
  ;; 0: (i64,i64)->i64   1: (i64)->i64
  [[0x60 (u 2) [i64 i64] (u 1) [i64]]
   [0x60 (u 1) [i64] (u 1) [i64]]])

(def module
  (w/->bytes
   [0x00 0x61 0x73 0x6d 0x01 0x00 0x00 0x00
    (sect 1 [(u (count types)) types])
    ;; add, lt : type 0 ; inc, c1, c3 : type 1
    (sect 3 [(u 6) (u 0) (u 0) (u 1) (u 1) (u 1) (u 1)])
    (sect 4 [(u 1) 0x70 0x01 (u 3) (u 3)])
    (sect 5 [(u 1) 0x00 (u 1)])
    (sect 7 [(u 3)
             (u 2) (map int "c1") 0x00 (u 3)
             (u 2) (map int "c2") 0x00 (u 4)
             (u 2) (map int "c3") 0x00 (u 5)])
    (sect 9 [(u 1) 0x00 0x41 (s 0) 0x0B (u 3) (u 0) (u 1) (u 2)])
    (sect 10 [(u 6)
              (let [b (w/->bytes f-add)] [(u (alength b)) b])
              (let [b (w/->bytes f-lt)] [(u (alength b)) b])
              (let [b (w/->bytes f-inc)] [(u (alength b)) b])
              (let [b (w/->bytes f-c1)] [(u (alength b)) b])
              (let [b (w/->bytes f-c2)] [(u (alength b)) b])
              (let [b (w/->bytes f-c3)] [(u (alength b)) b])])]))

(io/copy module (io/file "out/ceiling.wasm"))
(println "wrote out/ceiling.wasm" (alength module) "bytes")
