(ns flint.nfa
  "Regex AST to a Thompson NFA program (`doc/decisions/0012`).

  This is the SHARED half, and being shared is the point: every host executes
  the same compiled program, so there is no per-host pattern parser to disagree
  about `\\w`. It runs once per pattern and the result is cached, so it does not
  need to be fast.

  ## The encoding, which is part of the contract

  A program is a flat vector of integers, three per instruction, so an
  instruction index is a program counter and `pc * 3` is where it starts. Flat
  and fixed-width because the native simulator reads it as raw words and
  `0012` requires the same pattern to compile to a byte-identical program on
  every host -- a representation with nesting or optional fields would have
  several encodings of one program.

  Character classes do not fit a fixed width, so they live in a second flat
  vector and an instruction refers to one by offset.")

;; --- opcodes ---------------------------------------------------------------
(def OP-CHAR 0)   ; a = code point
(def OP-ANY 1)    ; any character EXCEPT a newline, as Java's `.` is
(def OP-SPLIT 2)  ; a, b = two program counters; `a` is PREFERRED
(def OP-JMP 3)    ; a = program counter
(def OP-SAVE 4)   ; a = capture slot
(def OP-MATCH 5)
(def OP-BOL 6)
(def OP-EOL 7)
(def OP-WORDB 8)
(def OP-NWORDB 9)
(def OP-CLASS 10) ; a = offset into the class table, b = 1 when negated

;; --- class table items -----------------------------------------------------
(def CL-ONE 0)    ; x = code point
(def CL-RANGE 1)  ; x = low, y = high
(def CL-PRED 2)   ; x = predicate code
(def PRED-D 0) (def PRED-D! 1) (def PRED-W 2) (def PRED-W! 3)
(def PRED-S 4) (def PRED-S! 5)

(def ^:private pred-code
  {"d" PRED-D "D" PRED-D! "w" PRED-W "W" PRED-W! "s" PRED-S "S" PRED-S!})

;; Counted repetition expands, so `(a{100}){100}` is a small pattern and an
;; enormous NFA. Bounding it is the same hazard as ReDoS arriving through the
;; compiler instead of the matcher, and a named refusal beats an out-of-memory.
(def MAX-INSTRS 20000)

;; Where the pattern proper begins, past the `.*?` search prefix.
(def ENTRY-UNANCHORED 0)
(def ENTRY-ANCHORED 3)

(defn- cp [c] (flint.rt/code-point-at c 0))

(defn- emit! [st op a b]
  (let [pc (count (:code @st))]
    (vswap! st update :code conj [op a b])
    pc))

(defn- patch! [st pc a b]
  (vswap! st update :code
          (fn [c] (assoc c pc [(nth (nth c pc) 0) a b]))))

(defn- here [st] (count (:code @st)))

(defn- add-class! [st items]
  (let [off (count (:classes @st))
        ;; Written out rather than with a transducer: this file is compiled by
        ;; flint as well as by the host, and the one-argument transducer arities
        ;; are not part of the subset.
        flat (reduce (fn [acc it]
                       (let [t (nth it 0)]
                         (cond
                           (= t :one) (conj acc CL-ONE (cp (nth it 1)) 0)
                           (= t :range) (conj acc CL-RANGE (cp (nth it 1)) (cp (nth it 2)))
                           :else (conj acc CL-PRED (get pred-code (nth it 1) PRED-D) 0))))
                     [(count items)] items)]
    (vswap! st update :classes into flat)
    off))

(declare c-node)

(defn- c-opt-chain
  "`a{2,4}` is `a a (a (a)?)?` -- nested, not sequential, so that a shorter match
  is only reachable by giving up the optional tail in order."
  [st inner n greedy?]
  (when (pos? n)
    (let [s (emit! st OP-SPLIT 0 0)]
      (c-node st inner)
      (c-opt-chain st inner (dec n) greedy?)
      (let [e (here st)]
        (patch! st s (if greedy? (inc s) e) (if greedy? e (inc s)))))))

(defn- c-star [st inner greedy?]
  (let [l1 (emit! st OP-SPLIT 0 0)]
    (c-node st inner)
    (emit! st OP-JMP l1 0)
    (let [l3 (here st)]
      (patch! st l1 (if greedy? (inc l1) l3) (if greedy? l3 (inc l1))))))

(defn- c-rep [st node]
  (let [inner (nth node 1) lo (nth node 2) hi (nth node 3) greedy? (nth node 4)]
    (dotimes [_ lo] (c-node st inner))
    (cond
      (nil? hi) (c-star st inner greedy?)
      (> hi lo) (c-opt-chain st inner (- hi lo) greedy?)
      :else nil)))

(defn- c-alt [st branches]
  (let [n (count branches)]
    (loop [k 0 jumps []]
      (if (= k (dec n))
        (do (c-node st (nth branches k))
            (let [e (here st)]
              (doseq [j jumps] (patch! st j e 0))))
        (let [s (emit! st OP-SPLIT 0 0)]
          (c-node st (nth branches k))
          (let [j (emit! st OP-JMP 0 0)
                nxt (here st)]
            (patch! st s (inc s) nxt)
            (recur (inc k) (conj jumps j))))))))

(defn- c-node [st node]
  (when (> (here st) MAX-INSTRS)
    (throw (ex-info (str "regex: compiled program exceeds " MAX-INSTRS
                         " instructions; counted repetition expands, so a small "
                         "pattern can name an enormous machine")
                    {:type :regex :limit MAX-INSTRS})))
  (let [tag (nth node 0)]
    (cond
      (= tag :char) (emit! st OP-CHAR (cp (nth node 1)) 0)
      (= tag :any) (emit! st OP-ANY 0 0)
      (= tag :bol) (emit! st OP-BOL 0 0)
      (= tag :eol) (emit! st OP-EOL 0 0)
      (= tag :wordb) (emit! st OP-WORDB 0 0)
      (= tag :nwordb) (emit! st OP-NWORDB 0 0)
      (= tag :class) (let [off (add-class! st (nth node 2))]
                       (emit! st OP-CLASS off (if (nth node 1) 1 0)))
      (= tag :seq) (doseq [x (nth node 1)] (c-node st x))
      (= tag :alt) (c-alt st (nth node 1))
      (= tag :group) (let [g (nth node 1)]
                       (emit! st OP-SAVE (* 2 g) 0)
                       (c-node st (nth node 2))
                       (emit! st OP-SAVE (inc (* 2 g)) 0))
      (= tag :rep) (c-rep st node)
      :else nil)))

(defn compile-ast
  "AST to `{:code [ints] :classes [ints] :ninstrs n}`. Slot 0 and 1 are the whole
  match, so group `g` uses slots `2g` and `2g+1`."
  [ast]
  (let [st (volatile! {:code [] :classes []})]
    ;; A `.*?` prefix, so an UNANCHORED search is one left-to-right pass rather
    ;; than a fresh run per starting position. The SPLIT prefers entering the
    ;; pattern, which is what makes the match leftmost.
    ;;
    ;; Both programs are one program: enter at 0 to search, at `ENTRY-ANCHORED`
    ;; to match exactly here. Emitting two would be two things to keep in step.
    (emit! st OP-SPLIT ENTRY-ANCHORED 1)
    (emit! st OP-ANY 0 0)
    (emit! st OP-JMP 0 0)
    (emit! st OP-SAVE 0 0)
    (c-node st ast)
    (emit! st OP-SAVE 1 0)
    (emit! st OP-MATCH 0 0)
    (let [code (:code @st)]
      (when (> (count code) MAX-INSTRS)
        (throw (ex-info (str "regex: compiled program exceeds " MAX-INSTRS " instructions")
                        {:type :regex :limit MAX-INSTRS})))
      {:ninstrs (count code)
       :code (reduce (fn [acc i] (conj acc (nth i 0) (nth i 1) (nth i 2))) [] code)
       :classes (:classes @st)})))
