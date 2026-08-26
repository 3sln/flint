(ns flint.emitter
  "AST to bytecode.

  A post-order walk, which is the whole argument for a stack machine on the
  bootstrap critical path (see `doc/decisions/0001-dispatch.md`): there is no
  register allocator here, and there was never a point at which the compiler
  could not compile itself.

  Tail positions become `TAIL_CALL`, which drops the caller's frame before the
  callee's is pushed, so mutual recursion in tail position runs in constant
  space even though Clojure's `recur` only handles self-recursion."
  (:require [flint.image :as img]))

(def op
  {:nop 0x00 :const 0x01 :nil 0x02 :true 0x03 :false 0x04 :int 0x05
   :local 0x06 :local-w 0x07 :set-local 0x08 :upval 0x09
   :var 0x0A :set-var 0x0B :pop 0x0C :dup 0x0D
   :jump 0x0E :jump-if-false 0x0F :jump-if-true 0x10
   :call 0x11 :tail-call 0x12 :return 0x13
   :closure 0x14 :native 0x15 :throw 0x16
   :try 0x17 :pop-handler 0x18 :rethrow 0x19
   :vector 0x1A :map 0x1B :set 0x1C :list 0x1D :apply 0x1E
   :jump-if-false-keep 0x1F :jump-if-true-keep 0x20
   :pop-n 0x21 :set-local-keep 0x22 :self 0x23
   ;; --- specialised on type ------------------------------------------------
   ;;
   ;; Emitted where the analyzer PROVED both operands are integers -- from an
   ;; annotation, from a guard, or from arithmetic on something already known.
   ;; Each replaces a NATIVE call: no argc, no builtin table lookup, no cross
   ;; into a Rust function, and in compiled code no boundary at all.
   ;;
   ;; `^int` means integer, not fixnum: a value past the fixnum range is a
   ;; boxed bigint and still answers `int?`. So every one of these still tests
   ;; for fixnum and falls back. What is removed is the CALL, not the check.
   :add-int 0x24 :sub-int 0x25 :mul-int 0x26
   :lt-int 0x27 :le-int 0x28 :gt-int 0x29 :ge-int 0x2A :eq-int 0x2B})

(def int-specialised
  "Builtin name to the opcode that replaces it when both operands are known
  integers. Two operands exactly: the variadic arities go through the generic
  path, and a one-argument `-` is negation, not subtraction."
  {"flint/add" :add-int "flint/sub" :sub-int "flint/mul" :mul-int
   "flint/lt" :lt-int "flint/le" :le-int "flint/gt" :gt-int
   "flint/ge" :ge-int "flint/num-eq" :eq-int})

(defn new-buf [] (volatile! {:bytes [] :fixups [] :labels {}}))

(defn- put! [buf & bs]
  (vswap! buf update :bytes into (flatten bs)))

(defn- here [buf] (count (:bytes @buf)))

(defn label! [buf name] (vswap! buf assoc-in [:labels name] (here buf)) name)

(defn- jump! [buf o name]
  (put! buf (op o))
  (vswap! buf update :fixups conj [(here buf) name])
  (put! buf [0 0]))

(defn finish [buf]
  (let [{:keys [bytes fixups labels]} @buf]
    (reduce (fn [bs [at name]]
              (let [target (or (get labels name)
                               (throw (ex-info "unresolved label" {:label name})))
                    rel (- target (+ at 2))]
                (when (or (< rel -32768) (> rel 32767))
                  (throw (ex-info "jump out of range; the function is too large"
                                  {:offset rel})))
                (-> bs
                    (assoc at (bit-and rel 0xff))
                    (assoc (inc at) (bit-and (bit-shift-right rel 8) 0xff)))))
            (vec bytes)
            fixups)))

;; --------------------------------------------------------------------------

(declare emit emit-fn-object)

(defn- var-slot! [ctx sym]
  (or (get-in ctx [:var-slots sym])
      (throw (ex-info (str "no slot for var " sym
                           " -- it was reached but not emitted")
                      {:sym sym :type :compile}))))

(defn- emit-const [ctx buf v]
  (cond
    (nil? v) (put! buf (op :nil))
    (true? v) (put! buf (op :true))
    (false? v) (put! buf (op :false))
    (and (integer? v) (<= -32768 v 32767)) (put! buf (op :int) (img/u16 v))
    :else (put! buf (op :const) (img/u16 (img/const (:b ctx) v)))))

(defn- emit-seq [ctx buf asts tail?]
  (doseq [a asts] (emit ctx buf a false))
  (when tail? nil))

(defn- emit-invoke [ctx buf {:keys [fn args]} tail?]
  (emit ctx buf fn false)
  (doseq [a args] (emit ctx buf a false))
  (if (and tail? (not (:in-try? ctx)))
    (put! buf (op :tail-call) (count args))
    (put! buf (op :call) (count args))))

(defn- emit-fn [ctx buf {:keys [name upvals arities] :as node}]
  (let [fidx (:fn-index (emit-fn-object ctx node))]
    (doseq [u upvals]
      (case (:kind u)
        :local (put! buf (op :local) (:idx u))
        :upval (put! buf (op :upval) (:idx u))
        :self (put! buf (op :self))))
    (put! buf (op :closure) (img/u16 fidx) (count upvals))))

(defn emit-fn-object
  "Emit every arity of a `:fn` node into the image, returning {:fn-index n}."
  [ctx {:keys [name upvals arities]}]
  (let [arity-defs
        (mapv (fn [{:keys [argc variadic? loop-id slots body max-locals]}]
                (let [buf (new-buf)
                      ctx' (assoc ctx :loops {loop-id {:label (gensym "L") :slots slots}}
                                  :in-try? false)]
                  ;; loop target for `recur` to the function's own arguments
                  (label! buf (get-in ctx' [:loops loop-id :label]))
                  (emit ctx' buf body true)
                  (put! buf (op :return))
                  {:argc argc :variadic? variadic? :nlocals (max max-locals (+ argc (if variadic? 1 0)))
                   :code (finish buf)}))
              arities)]
    {:fn-index (img/add-fn (:b ctx) {:name (or name 'fn)
                                     :nupvals (count upvals)
                                     :arities arity-defs})}))

(defn- emit-let [ctx buf {:keys [bindings body]} tail?]
  (doseq [{:keys [idx init]} bindings]
    (emit ctx buf init false)
    (put! buf (op :set-local) idx))
  (emit ctx buf body tail?))

(defn- emit-loop [ctx buf {:keys [id bindings body]} tail?]
  (doseq [{:keys [idx init]} bindings]
    (emit ctx buf init false)
    (put! buf (op :set-local) idx))
  (let [lbl (gensym "loop")
        ctx' (assoc-in ctx [:loops id] {:label lbl :slots (mapv :idx bindings)})]
    (label! buf lbl)
    (emit ctx' buf body tail?)))

(defn- emit-recur [ctx buf {:keys [id slots args]}]
  (when (:in-try? ctx)
    (throw (ex-info "cannot recur across a try boundary" {:type :compile})))
  (doseq [a args] (emit ctx buf a false))
  ;; Stores happen after every argument is evaluated, which is what makes
  ;; (recur b a) a simultaneous rebinding rather than a sequential one.
  (doseq [s (reverse slots)] (put! buf (op :set-local) s))
  (let [lbl (get-in ctx [:loops id :label])]
    (when-not lbl (throw (ex-info "recur target not found" {:id id})))
    (jump! buf :jump lbl)))

(defn- emit-if [ctx buf {:keys [test then else]} tail?]
  (let [l-else (gensym "else") l-end (gensym "end")]
    (emit ctx buf test false)
    (jump! buf :jump-if-false l-else)
    (emit ctx buf then tail?)
    (jump! buf :jump l-end)
    (label! buf l-else)
    (emit ctx buf else tail?)
    (label! buf l-end)))

(defn- emit-try [ctx buf {:keys [body catches finally]} _tail?]
  (let [ctx (assoc ctx :in-try? true)
        l-end (gensym "tryend")
        l-fin-exc (gensym "finexc")
        emit-finally (fn [] (when finally
                              (emit ctx buf finally false)
                              (put! buf (op :pop))))]
    (when finally (jump! buf :try l-fin-exc))
    (let [l-catch (gensym "catch")]
      (if (seq catches)
        (do
          (jump! buf :try l-catch)
          (emit ctx buf body false)
          (put! buf (op :pop-handler))
          (jump! buf :jump l-end)
          (label! buf l-catch)
          ;; The thrown value is on the stack. Catch clauses are tried in
          ;; order. flint has no class hierarchy, so a clause matches on the
          ;; exception's KIND STRING -- but `Exception` and `Error` still have
          ;; to mean what they mean in Clojure, and that rule lives in one
          ;; place: `flint/ex-matches?`. It used to be a string equality here,
          ;; which made `(catch Exception e ...)` match nothing at all.
          (loop [cs catches]
            (if-let [{:keys [kind idx body]} (first cs)]
              (let [l-next (gensym "cnext")]
                (if (= :any kind)
                  (do (put! buf (op :set-local) idx)
                      (emit ctx buf body false))
                  (do (put! buf (op :dup))
                      (emit-const ctx buf (str kind))
                      (put! buf (op :native)
                            (img/u16 (img/native-slot (:b ctx) "flint/ex-matches?")) 2)
                      (jump! buf :jump-if-false l-next)
                      (put! buf (op :set-local) idx)
                      (emit ctx buf body false)
                      (jump! buf :jump l-end)
                      (label! buf l-next)
                      (recur (next cs)))))
              ;; nothing matched: re-throw
              (put! buf (op :rethrow)))))
        (do (emit ctx buf body false))))
    (label! buf l-end)
    (when finally
      (put! buf (op :pop-handler))
      (emit-finally)
      (let [l-done (gensym "findone")]
        (jump! buf :jump l-done)
        (label! buf l-fin-exc)
        (emit-finally)
        (put! buf (op :rethrow))
        (label! buf l-done)))))

(defn emit
  "Emit `node`. `tail?` says the value is the function's result."
  [ctx buf node tail?]
  (case (:op node)
    :const (emit-const ctx buf (:val node))
    :local (if (< (:idx node) 256)
             (put! buf (op :local) (:idx node))
             (put! buf (op :local-w) (img/u16 (:idx node))))
    :upval (put! buf (op :upval) (:idx node))
    :self (put! buf (op :self))
    :var (put! buf (op :var) (img/u16 (var-slot! ctx (:sym node))))
    :the-var (put! buf (op :var) (img/u16 (var-slot! ctx (:sym node))))
    :def (do (if (:init node)
               (emit ctx buf (:init node) false)
               (put! buf (op :nil)))
             (put! buf (op :set-var) (img/u16 (var-slot! ctx (:sym node))))
             (put! buf (op :nil)))
    :if (emit-if ctx buf node tail?)
    :do (let [b (:body node)]
          (doseq [x (butlast b)]
            (emit ctx buf x false)
            (put! buf (op :pop)))
          (emit ctx buf (last b) tail?))
    :let (emit-let ctx buf node tail?)
    :loop (emit-loop ctx buf node tail?)
    :recur (emit-recur ctx buf node)
    :fn (emit-fn ctx buf node)
    :invoke (emit-invoke ctx buf node tail?)
    :native-value (put! buf (op :const) (img/u16 (img/native-const (:b ctx) (:name node))))
    :native (let [args (:args node)
                  spec (when (= 2 (count args))
                         (let [o (get int-specialised (:name node))]
                           (when (and o (every? (fn [a] (= :int (:tag a))) args))
                             o)))]
              (doseq [a args] (emit ctx buf a false))
              (if spec
                (put! buf (op spec))
                (put! buf (op :native)
                      (img/u16 (img/native-slot (:b ctx) (:name node)))
                      (count args))))
    :throw (do (emit ctx buf (:expr node) false) (put! buf (op :throw)))
    :try (emit-try ctx buf node tail?)
    :vector (do (doseq [x (:items node)] (emit ctx buf x false))
                (put! buf (op :vector) (img/u16 (count (:items node)))))
    :set (do (doseq [x (:items node)] (emit ctx buf x false))
             (put! buf (op :set) (img/u16 (count (:items node)))))
    :map (do (doseq [[k v] (:pairs node)]
               (emit ctx buf k false)
               (emit ctx buf v false))
             (put! buf (op :map) (img/u16 (count (:pairs node)))))
    (throw (ex-info "cannot emit node" {:node node}))))
