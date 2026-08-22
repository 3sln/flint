(ns flint.analyzer
  "Forms to AST.

  Two phases, not one: analysis produces an AST, and only then does emission
  produce bytecode. The extra phase pays for itself three times over --
  `flint.eval` walks the AST so that `defmacro` works on any host, tree shaking
  walks the reference graph the AST records, and the emitter stays a
  straightforward post-order walk.

  ## Locals, upvalues, and why closures capture by value
  Each `fn*` gets its own frame in the VM's value stack. A symbol resolved in an
  enclosing `fn*` becomes an **upvalue**: the enclosing frame pushes its current
  value at closure-creation time. Clojure closures capture by value, so nothing
  needs boxing -- and a captured value that is never re-read cannot keep an
  object alive, which matters given the collector."
  (:require [clojure.string :as str]
            [flint.canon :as canon]
            [flint.macros :as macros]))

(def specials
  '#{def if do let* loop* recur fn* quote var throw try catch finally
     new set! . monitor-enter monitor-exit deftype* reify* case* letfn* ns})

(defn- err [msg data]
  (throw (ex-info (str "compile error: " msg) (assoc data :type :compile))))

;; ------------------------------------------------------------- fn scopes

(defn new-fn-scope [parent]
  (volatile! {:parent parent :upvals [] :upval-index {} :nlocals 0 :max-locals 0}))

(defn- alloc-local! [scope]
  (let [i (:nlocals @scope)]
    (vswap! scope #(-> % (assoc :nlocals (inc i))
                       (update :max-locals max (inc i))))
    i))

(defn- release-locals! [scope n]
  (vswap! scope assoc :nlocals n))

(defn- add-upval! [scope name kind idx]
  (let [k [kind idx]]
    (if-let [i (get (:upval-index @scope) k)]
      i
      (let [i (count (:upvals @scope))]
        (vswap! scope #(-> % (update :upvals conj {:name name :kind kind :idx idx})
                           (assoc-in [:upval-index k] i)))
        i))))

;; --------------------------------------------------------------- environment
;;
;; `:locals` is the current fn's lexical map. `:outer` is the environment of the
;; enclosing fn, which is how an upvalue is found and threaded through every
;; intermediate closure.

(defn- resolve-local [env sym]
  (if-let [b (get (:locals env) sym)]
    b
    (when-let [outer (:outer env)]
      (when-let [r (resolve-local outer sym)]
        (let [i (add-upval! (:scope env) sym (:kind r) (:idx r))]
          {:kind :upval :idx i})))))

;; ---------------------------------------------------------------- namespaces

(defn- current-ns [env] (:ns env))

(defn qualify
  "The fully qualified symbol a name refers to in `env`, or nil."
  [env sym]
  (let [cc @(:cc env)
        nsname (current-ns env)
        nsdef (get-in cc [:namespaces nsname])]
    (if-let [ns-part (namespace sym)]
      (let [alias (symbol ns-part)
            target (get (:aliases nsdef) alias alias)
            q (symbol (str target) (name sym))]
        ;; Refuse to invent a var. Without this a missing :require produces a
        ;; dangling reference that only fails much later, at emission.
        (when-not (or (get-in cc [:vars q]) (get-in cc [:declared q]))
          (err (str "unable to resolve " sym
                    (if (= (str target) ns-part)
                      (str " -- is " target " required?")
                      (str " -- alias " alias " means " target)))
               {:sym sym :ns nsname}))
        q)
      (or (get (:refers nsdef) sym)
          (when (get-in cc [:vars (symbol (str nsname) (name sym))])
            (symbol (str nsname) (name sym)))
          ;; clojure.core is referred everywhere, as in Clojure
          (when (get-in cc [:vars (symbol "clojure.core" (name sym))])
            (symbol "clojure.core" (name sym)))
          (when (get-in cc [:declared (symbol (str nsname) (name sym))])
            (symbol (str nsname) (name sym)))))))

(defn native-name
  "If `sym` names a Rust builtin via the `flint.rt` namespace, the catalogue
  name for it. `flint.rt/x` tries the builtin `x` and then `flint/x`, which is
  how names like `flint/add` stay distinct from `clojure.core/+` without the
  source having to spell the prefix."
  [env sym]
  (when-let [nspart (namespace sym)]
    (let [cc @(:cc env)
          target (get (get-in cc [:namespaces (:ns env) :aliases]) (symbol nspart) (symbol nspart))]
      (when (= 'flint.rt target)
        (let [n (name sym)
              bs (:builtins cc)]
          (cond
            (contains? bs n) n
            (contains? bs (str "flint/" n)) (str "flint/" n)
            (empty? bs) n
            :else (err (str "no such builtin: flint.rt/" n)
                       {:sym sym :known (count bs)})))))))

(defn- macro-fn [env sym]
  (when-let [q (qualify env sym)]
    (get-in @(:cc env) [:macros q])))

;; ------------------------------------------------------------------ analysis

(declare analyze analyze-body analyze-fn analyze-special analyze-ns)

(defn- const-node [v] {:op :const :val v})

(defn- record-dep! [env q]
  (when-let [cur (:current-var env)]
    (vswap! (:cc env) update-in [:deps cur] (fnil conj #{}) q)))

(defn- analyze-symbol [env sym]
  (if-let [b (resolve-local env sym)]
    (assoc b :op (case (:kind b) :local :local :upval :upval :self :self) :name sym)
    (if-let [nn (and (namespace sym) (native-name env sym))]
      (do (record-dep! env (symbol "flint.native" nn))
          {:op :native-value :name nn})
      (if-let [q (qualify env sym)]
      (do (record-dep! env q)
          {:op :var :sym q})
      (err (str "unable to resolve symbol: " sym)
           {:sym sym :ns (current-ns env) :line (:line (meta sym))})))))

(defn bootstrap-key [sym]
  (cond
    (nil? (namespace sym)) sym
    (= "clojure.core" (namespace sym)) (symbol (name sym))
    :else nil))

(defn- analyze-seq [env form]
  (let [head (first form)]
    (cond
      (and (symbol? head) (contains? specials head))
      (analyze-special env head form)

      ;; A direct builtin call. This is the ONLY way native code is reached, and
      ;; it is why an unused builtin can be dropped by the linker.
      (and (symbol? head) (namespace head) (not (resolve-local env head))
           (native-name env head))
      (let [n (native-name env head)]
        ;; Record the builtin as a dependency too, under a namespace no source
        ;; can define. `:exclude` needs a reference chain for builtins exactly
        ;; as it does for vars, and this makes one fall out of the same edges.
        (record-dep! env (symbol "flint.native" n))
        {:op :native :name n :args (mapv #(analyze env %) (rest form))})

      ;; Bootstrap macros answer to both `fn` and `clojure.core/fn`: syntax
      ;; quote qualifies them, and they have to keep working after it does.
      (and (symbol? head) (not (resolve-local env head))
           (get macros/bootstrap (bootstrap-key head)))
      (analyze env ((get macros/bootstrap (bootstrap-key head)) form env))

      (and (symbol? head) (not (resolve-local env head)) (macro-fn env head))
      (let [f (macro-fn env head)
            expanded (apply f form nil (rest form))]
        (analyze env expanded))

      :else
      (let [f (analyze env head)
            args (mapv #(analyze env %) (rest form))]
        (if (and (= :var (:op f)) (get-in @(:cc env) [:native-alias (:sym f)])
                 (= (count args) (get-in @(:cc env) [:native-arity (:sym f)])))
          ;; A core var whose whole body is one native call: go straight to the
          ;; builtin. This is what keeps the cljc wrapper layer free.
          {:op :native :name (get-in @(:cc env) [:native-alias (:sym f)]) :args args}
          {:op :invoke :fn f :args args})))))

(defn analyze [env form]
  (cond
    (symbol? form) (analyze-symbol env form)
    (seq? form) (if (empty? form)
                  (const-node ())
                  (analyze-seq env form))
    ;; A #"..." literal becomes a call to the memoised compiler, so the engine
    ;; is reachable only from programs that actually use one.
    ;;
    ;; The `string?` test is not cosmetic. Without it this rewrites the READER's
    ;; own construction of the marker -- `{:flint/regex (str-join acc)}` in
    ;; read-regex -- into a call to the regex compiler, so a flint-hosted reader
    ;; returns compiled patterns where a host-hosted one returns markers. That
    ;; showed up only as a self-hosting divergence, which is exactly what the
    ;; fixpoint test is for.
    (and (map? form) (string? (:flint/regex form)))
    (analyze env (list 'flint.regex/pattern (:flint/regex form)))

    (vector? form) {:op :vector :items (mapv #(analyze env %) form)}
    ;; Source order: the reader preserves it (see `flint.rt/array-map`), and
    ;; Clojure evaluates map literal values in source order too.
    (map? form) {:op :map :pairs (mapv (fn [e] [(analyze env (key e)) (analyze env (val e))]) form)}
    (set? form) {:op :set :items (mapv #(analyze env %) (canon/sorted-elements form))}
    :else (const-node form)))

(defn analyze-body [env forms]
  (case (count forms)
    0 (const-node nil)
    1 (analyze env (first forms))
    {:op :do :body (mapv #(analyze env %) forms)}))

;; --------------------------------------------------------------- specials

(defn- bind-locals
  "Bind `pairs` ([sym init-form] ...) sequentially, returning [env bindings]."
  [env pairs]
  (reduce (fn [[e acc] [sym init]]
            (when-not (symbol? sym) (err "binding name must be a symbol" {:sym sym}))
            (let [init-ast (analyze e init)
                  idx (alloc-local! (:scope e))]
              [(assoc-in e [:locals sym] {:kind :local :idx idx})
               (conj acc {:idx idx :init init-ast :name sym})]))
          [env []]
          pairs))

(defn analyze-special [env head form]
  (case head
    quote (const-node (second form))

    if (let [[_ test then else] form]
         (when (< (count form) 3) (err "if needs at least a test and a then" {:form form}))
         {:op :if :test (analyze env test)
          :then (analyze env then)
          :else (if (> (count form) 3) (analyze env else) (const-node nil))})

    do (analyze-body env (rest form))

    let* (let [[_ bindings & body] form
               _ (when (odd? (count bindings)) (err "let needs an even binding vector" {:form form}))
               n0 (:nlocals @(:scope env))
               [env' bs] (bind-locals env (partition 2 bindings))
               body-ast (analyze-body env' body)]
           (release-locals! (:scope env) n0)
           {:op :let :bindings bs :body body-ast})

    loop* (let [[_ bindings & body] form
                n0 (:nlocals @(:scope env))
                [env' bs] (bind-locals env (partition 2 bindings))
                loop-id (gensym "loop")
                env' (assoc env' :loop {:id loop-id :slots (mapv :idx bs) :n (count bs)})
                body-ast (analyze-body env' body)]
            (release-locals! (:scope env) n0)
            {:op :loop :id loop-id :bindings bs :body body-ast})

    recur (let [l (:loop env)]
            (when-not l (err "recur outside of loop or fn" {:form form}))
            (let [args (mapv #(analyze env %) (rest form))]
              (when (not= (count args) (:n l))
                (err (str "recur expects " (:n l) " arguments, got " (count args)) {:form form}))
              {:op :recur :id (:id l) :slots (:slots l) :args args}))

    fn* (analyze-fn env form)

    ;; (def n), (def n init) and (def n "doc" init) are all legal. Taking the
    ;; third element as the init silently binds the DOCSTRING as the value,
    ;; which is a bug that shows up a long way from its cause.
    def (let [nm (second form)
              _ (when-not (symbol? nm) (err "def needs a symbol" {:form form}))
              n (count form)
              _ (when (> n 4) (err "too many arguments to def" {:form form}))
              q (symbol (str (current-ns env)) (clojure.core/name nm))
              init-form (when (>= n 3) (nth (vec form) (dec n)))
              doc (when (= n 4) (nth (vec form) 2))]
          (when (and (= n 4) (not (string? doc)))
            (err "the third argument to a 3-argument def must be a docstring" {:form form}))
          (vswap! (:cc env) assoc-in [:declared q] true)
          {:op :def :sym q :meta (merge (meta nm) (when doc {:doc doc}))
           :init (when (>= n 3)
                   (analyze (assoc env :current-var q) init-form))})

    var (let [q (qualify env (second form))]
          (when-not q (err (str "unable to resolve var: " (second form)) {:form form}))
          (record-dep! env q)
          {:op :the-var :sym q})

    throw {:op :throw :expr (analyze env (second form))}

    try (let [[_ & body] form
              catches (filter #(and (seq? %) (= 'catch (first %))) body)
              finallys (filter #(and (seq? %) (= 'finally (first %))) body)
              main (remove #(and (seq? %) (#{'catch 'finally} (first %))) body)
              n0 (:nlocals @(:scope env))
              catch-asts (mapv (fn [[_ kind bsym & cbody]]
                                 (let [idx (alloc-local! (:scope env))
                                       e' (assoc-in env [:locals bsym] {:kind :local :idx idx})
                                       a {:kind (if (= 'Throwable kind) :any (str kind))
                                          :idx idx
                                          :body (analyze-body e' cbody)}]
                                   (release-locals! (:scope env) (inc n0))
                                   a))
                               catches)]
          (release-locals! (:scope env) n0)
          (when (> (count finallys) 1) (err "at most one finally" {:form form}))
          {:op :try
           :body (analyze-body env main)
           :catches catch-asts
           :finally (when (seq finallys) (analyze-body env (rest (first finallys))))})

    ns (analyze-ns env form)

    letfn* (err "letfn* is not implemented; use let with fns that do not refer to each other, or top-level defs" {:form form})

    (new set! . monitor-enter monitor-exit deftype* reify* case*)
    (err (str head " is host interop or unsupported in flint") {:form form})

    (err (str "unhandled special form: " head) {:form form})))

;; ------------------------------------------------------------------ fn*

(defn- arity-info [params]
  (let [i (count (take-while #(not= '& %) params))
        variadic? (< i (count params))
        fixed (vec (take i params))
        restp (when variadic? (nth (vec params) (inc i)))]
    {:fixed fixed :variadic? variadic? :rest restp
     :all (if variadic? (conj fixed restp) fixed)}))

(defn analyze-fn [env form]
  (let [[_ & more] form
        [fname more] (if (symbol? (first more)) [(first more) (next more)] [nil more])
        arities (if (vector? (first more)) (list more) more)
        scope (new-fn-scope (:scope env))
        base-env (-> env
                     (assoc :scope scope :outer env :locals {} :loop nil))
        ;; A named fn can refer to itself; bind the name as a self-reference.
        self-idx (when fname nil)
        analyzed
        (mapv (fn [[params & body]]
                (let [{:keys [fixed variadic? rest all]} (arity-info params)
                      _ (release-locals! scope 0)
                      env' (reduce (fn [e p]
                                     (let [idx (alloc-local! scope)]
                                       (assoc-in e [:locals p] {:kind :local :idx idx})))
                                   base-env
                                   all)
                      ;; A named fn can call itself. It cannot capture itself --
                      ;; the closure does not exist while its body is compiled --
                      ;; so the name resolves to the frame's own closure.
                      env' (if fname (assoc-in env' [:locals fname] {:kind :self}) env')
                      slots (mapv #(get-in env' [:locals % :idx]) all)
                      env' (assoc env' :loop {:id (gensym "fnloop") :slots slots :n (count all)})
                      body-ast (analyze-body env' body)]
                  {:argc (count fixed) :variadic? variadic?
                   :loop-id (get-in env' [:loop :id])
                   :slots slots
                   :body body-ast}))
              arities)]
    (let [_ self-idx]
      {:op :fn
       :name fname
       :upvals (:upvals @scope)
       :max-locals (:max-locals @scope)
       :arities (mapv #(assoc % :max-locals (:max-locals @scope)) analyzed)})))

;; ------------------------------------------------------------------- ns

(defn analyze-ns [env form]
  (let [[_ nsname & clauses] form
        cc (:cc env)]
    (doseq [c clauses]
      (when (seq? c)
        (case (first c)
          (:require :use)
          (doseq [spec (rest c)]
            (let [spec (if (symbol? spec) [spec] spec)
                  [target & opts] spec
                  opts (apply hash-map opts)]
              (vswap! cc update-in [:namespaces nsname :requires] (fnil conj #{}) target)
              (when-let [a (:as opts)]
                (vswap! cc assoc-in [:namespaces nsname :aliases a] target))
              (doseq [r (:refer opts)]
                (vswap! cc assoc-in [:namespaces nsname :refers r] (symbol (str target) (name r))))))
          :refer-clojure nil
          :import (throw (ex-info "flint has no host interop, so :import is not supported" {:form c}))
          nil)))
    {:op :const :val nil}))
