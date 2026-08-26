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
            [flint.macros :as macros]
            [flint.types :as ty]))

(def specials
  '#{def if do let* loop* recur fn* quote var throw try catch finally binding
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

;; How many `:inline` expansions may nest before the analyzer calls it a loop.
;; An inline whose body calls the function it is inlining -- which is how a
;; fallback gets written, and easy to write by accident -- would otherwise
;; expand until the host stack goes, and a StackOverflowError names nothing a
;; reader can act on. Source nesting never approaches this; runaway expansion
;; reaches it immediately.
(def ^:private inline-depth-limit 200)

(defn- inline-fn
  "The `:inline` expander for `sym` at `argc` arguments, or nil.

  `:inline` is Clojure's, and it is a compile-time-only property: flint carries
  no var metadata at run time, so an inline that is never applied costs nothing
  and leaves no trace in the module. `:inline-arities` gates it -- a set or a
  predicate, tested on the argument count -- because the common shape is a
  two-argument inline beside a variadic definition, and inlining the variadic
  call with the two-argument body would be silently wrong."
  [env sym argc]
  (when-let [q (qualify env sym)]
    (when-let [{:keys [f arities]} (get-in @(:cc env) [:inlines q])]
      (when (or (nil? arities) (arities argc))
        f))))

;; ------------------------------------------------------------------ analysis

(declare analyze analyze-untagged analyze-body analyze-fn analyze-special analyze-ns)

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
          (if (get-in @(:cc env) [:dynamic q])
            ;; A dynamic var reads through the current thread's binding map,
            ;; falling back to the root value. `binding` is per GREEN thread
            ;; (doc/decisions/0005, section 4).
            (do (record-dep! env (symbol "flint.native" "flint/dyn-get"))
                {:op :native :name "flint/dyn-get"
                 :args [(const-node q) {:op :var :sym q}]})
            {:op :var :sym q}))
      (err (str "unable to resolve symbol: " sym)
           {:sym sym :ns (current-ns env) :line (:line (meta sym))})))))

(defn bootstrap-key [sym]
  (cond
    (nil? (namespace sym)) sym
    (= "clojure.core" (namespace sym)) (symbol (name sym))
    :else nil))

(defn- resolve-projections
  "Turn a by-index projection spec into `{truthiness {local-sym tag}}`.

  Only LOCALS are narrowed. A var's value can change between the test and the
  use, and a narrowing that assumed otherwise would be unsound in exactly the
  way this whole feature exists to avoid; a local cannot be rebound except by
  `recur`, which carries its own barrier."
  [env spec arg-forms]
  (when spec
    (let [args (vec arg-forms)]
      (reduce-kv
       (fn [out truth by-index]
         (let [narrowed (reduce-kv
                         (fn [m i meta-map]
                           (let [a (get args i)
                                 t (ty/projected-tag meta-map)]
                             (if (and t (symbol? a) (= :local (:kind (resolve-local env a))))
                               (assoc m a t)
                               m)))
                         {}
                         by-index)]
           (cond-> out (seq narrowed) (assoc truth narrowed))))
       {}
       spec))))

(defn- with-projections
  "`node`, carrying what its result would tell us about `arg-forms`."
  [env node spec arg-forms]
  (let [p (resolve-projections env spec arg-forms)]
    (cond-> node (seq p) (assoc :projects p))))

(defn- intersect-narrowings
  "Only what BOTH ways of reaching this outcome agree on. `(or (int? x)
  (string? x))` proves nothing about `x`, and this is the rule that says so."
  [a b]
  (reduce-kv (fn [m sym t] (if (= t (get b sym)) (assoc m sym t) m)) {} (or a {})))

(defn- same-local? [a b]
  (and (= :local (:op a)) (= :local (:op b)) (= (:idx a) (:idx b))))

(declare projects)

(defn- can-be?
  "Can `node` come out with truthiness `truth`, given that `test` was taken the
  way that leads here? Two things make the answer no, and between them they are
  what `and` and `or` are built out of: a literal of the wrong truthiness, and
  the branch BEING the test -- `(if t rest t)` cannot yield truthy through its
  else, because `t` is false there."
  [node truth test test-truth]
  (cond
    (= :const (:op node)) (= truth (boolean (:val node)))
    (same-local? test node) (= truth test-truth)
    :else true))

(defn- projects
  "What `node` coming out with truthiness `truth` implies about locals.

  Written as one function over both polarities because `and`, `or`, `when-not`
  and `if-not` all reduce to `if`, and each of them needs a different corner of
  the same rule. An `if` can reach an outcome two ways; what is proven is what
  BOTH ways prove, unless one of them cannot happen."
  [node truth]
  (case (:op node)
    :let (let [bound (set (map :name (:bindings node)))]
           ;; A symbol this let binds is a different binding outside it, so a
           ;; projection naming one must not escape.
           (apply dissoc (projects (:body node) truth) bound))
    :do (projects (last (:body node)) truth)
    :if (let [{:keys [test then else]} node
              via-then (when (can-be? then truth test true)
                         (merge (projects test true) (projects then truth)))
              via-else (when (can-be? else truth test false)
                         (merge (projects test false) (projects else truth)))]
          (cond
            (and via-then via-else) (intersect-narrowings via-then via-else)
            via-then via-then
            via-else via-else
            :else {}))
    (get (:projects node) truth)))

(defn- branch-projects
  "`{true {...} false {...}}` for a test node, empty entries dropped."
  [node]
  (cond-> {}
    (seq (projects node true)) (assoc true (projects node true))
    (seq (projects node false)) (assoc false (projects node false))))

(defn- narrow
  "`env` with each local in `narrowed` known to have the tag it maps to. A
  narrowing never widens: if the local is already known to be something the
  projection proves, the existing tag is kept."
  [env narrowed]
  (reduce-kv (fn [e sym t]
               (if (ty/proves? (get-in e [:locals sym :tag]) t)
                 e
                 (assoc-in e [:locals sym :tag] t)))
             env
             narrowed))

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
        (with-projections env
          {:op :native :name n :args (mapv #(analyze env %) (rest form))}
          (get ty/native-projections n) (rest form)))

      ;; Bootstrap macros answer to both `fn` and `clojure.core/fn`: syntax
      ;; quote qualifies them, and they have to keep working after it does.
      (and (symbol? head) (not (resolve-local env head))
           (get macros/bootstrap (bootstrap-key head)))
      (analyze env ((get macros/bootstrap (bootstrap-key head)) form env))

      ;; `:inline`, and it goes BEFORE the generic call because the expander
      ;; takes the argument FORMS, not their analysis -- that is the whole
      ;; point of it. Depth is counted rather than the var being blocked: the
      ;; nested call in `(f (f x) y)` is a different call and must still
      ;; inline, while an inline that re-emits its own name must stop.
      (and (symbol? head) (not (resolve-local env head))
           (not (macro-fn env head))
           (inline-fn env head (count (rest form))))
      (let [q (qualify env head)
            d (inc (:inline-depth env 0))
            _ (when (> d inline-depth-limit)
                (err (str "the :inline for " q " does not terminate: "
                          inline-depth-limit " nested expansions. An :inline "
                          "body that calls the function it inlines expands "
                          "forever -- call the underlying operation instead.")
                     {:form form :sym q}))
            f (inline-fn env head (count (rest form)))
            expanded (try (apply f (rest form))
                          (catch Throwable e
                            (err (str "the :inline for " q " threw: " (ex-message e))
                                 {:form form :sym q})))]
        (analyze (assoc env :inline-depth d) expanded))

      (and (symbol? head) (not (resolve-local env head)) (macro-fn env head))
      (let [f (macro-fn env head)
            ;; `&env` is deliberately tiny: the namespace being compiled, and
            ;; nothing else. `defprotocol` needs it to build the fully-qualified
            ;; method keyword that metadata dispatch looks for; handing macros
            ;; the whole analyzer environment would make it API.
            expanded (apply f form {:ns (current-ns env)} (rest form))]
        (analyze env expanded))

      :else
      (let [f (analyze env head)
            args (mapv #(analyze env %) (rest form))]
        (if-let [nat (and (= :var (:op f))
                          (get-in @(:cc env) [:native-alias (:sym f) (count args)]))]
          ;; A core var ARITY whose whole body is one native call: go straight to
          ;; the builtin. Keyed on the argument count, because `+` and friends
          ;; are written as a two-argument arity beside a variadic one.
          ;; The template puts this call's argument expressions where the
          ;; wrapper's parameters were, keeping any constants the wrapper
          ;; supplied -- which is how `(inc i)` becomes `add(i, 1)`.
          ;; The builtin's argument positions are not the call's: the template
          ;; may drop or add constants. `(inc i)` becomes `add(i, 1)`, and a
          ;; projection about the builtin's argument 0 is about the call's `i`.
          ;; So the forms are mapped through the same template the values are.
          (let [thru (mapv (fn [t] (if (= :arg (first t))
                                     (nth (vec (rest form)) (second t))
                                     ::literal))
                           (:tmpl nat))]
            (with-projections env
              {:op :native :name (:name nat)
               :args (mapv (fn [t] (if (= :arg (first t)) (nth args (second t)) (second t)))
                           (:tmpl nat))}
              (get ty/native-projections (:name nat)) thru))
          ;; A user function's own declaration. `:flint/result-projected-meta` is
          ;; recorded by argument INDEX at definition, so it reads the same way
          ;; the builtin table does.
          (let [node (with-projections env {:op :invoke :fn f :args args}
                       (when (= :var (:op f))
                         (get-in @(:cc env) [:projections (:sym f) (count args)]))
                       (rest form))
                inv (when (= :var (:op f))
                      (get-in @(:cc env) [:inversions (:sym f) (count args)]))]
            ;; `(not e)` proves on the false side whatever `e` proves on the
            ;; true side, and the other way round. Everything narrowing knows
            ;; crosses a negation intact.
            (if-let [a (and inv (nth args inv nil))]
              (let [flipped (cond-> {}
                              (seq (projects a false)) (assoc true (projects a false))
                              (seq (projects a true)) (assoc false (projects a true)))]
                (cond-> node (seq flipped) (assoc :projects flipped)))
              node)))))))

;; ------------------------------------------------------- type annotations
;;
;; A tag on a BINDING is a checked claim, and the check goes at the write. That
;; is the whole design: `(let [^int x e] ...)` tests once, and every read of `x`
;; afterwards is known without testing, so the code that follows can be
;; specialised on it. A tag on a use site -- `(+ ^int x ^int y)` -- is the same
;; barrier at a different place, and it errors at the annotation rather than
;; wherever the wrong value eventually causes trouble.
;;
;; The check is ELIDED when the value is already known to satisfy it, which is
;; what keeps annotating cheap: annotating a value that came from an annotated
;; binding costs nothing at all.

(defn- node-tag
  "What `node` is known to evaluate to, or nil. Never a guess: an unsound
  answer here elides a check that was load-bearing, and the annotation silently
  becomes the hint it is supposed not to be."
  [node]
  (or (:tag node)
      (case (:op node)
        :const (ty/const-tag (:val node))
        :native (ty/native-return (:name node) (mapv node-tag (:args node)))
        :vector :vector
        :map :map
        :set :set
        :fn :fn
        ;; Both arms, or nothing. `(if p 1 :k)` is not an int.
        :if (let [a (node-tag (:then node)) b (node-tag (:else node))]
              (when (and a (= a b)) a))
        :do (node-tag (last (:body node)))
        :let (node-tag (:body node))
        nil)))

(defn- check-node
  "The barrier itself, always emitted."
  [env node want where]
  (record-dep! env (symbol "flint.native" "flint/check-tag"))
  {:op :native :name "flint/check-tag" :tag want
   :args [node (const-node (get ty/code want)) (const-node (str where))]})

(defn- checked
  "`node`, guaranteed to satisfy `want`. Emits nothing when it already does --
  which is what makes annotating cheap, and what makes annotating a value that
  came from an annotated binding entirely free."
  [env node want where]
  (cond
    (nil? want) node
    (ty/proves? (node-tag node) want) (assoc node :tag want)
    :else (check-node env node want where)))

(defn- form-tag
  "The tag written on a form, where the form can carry metadata at all."
  [form]
  (when (or (symbol? form) (seq? form) (vector? form) (map? form) (set? form))
    (ty/known form)))

(defn analyze [env form]
  ;; A tag at a USE site is the same barrier as one at a binding, placed where
  ;; the author wants the error: `(+ ^int x ^int y)` fails at the annotation
  ;; naming `x`, not four frames deeper inside the number tower. The tag is
  ;; stripped before the form is analyzed, or this would not terminate.
  (let [want (form-tag form)
        where (if (symbol? form) form "a value")
        form (if want (vary-meta form dissoc :tag) form)
        node (analyze-untagged env form)
        ;; STAMP what is known onto the node. `node-tag` computes it from the
        ;; node's shape, and stamping makes that computation O(1) for every
        ;; node above -- but the reason to do it is that the emitter and every
        ;; backend after it read the AST, not the analyzer. A type that only
        ;; exists inside `node-tag` cannot specialise anything.
        node (if-let [t (node-tag node)] (assoc node :tag t) node)]
    (if want (checked env node want where) node)))

(defn analyze-untagged [env form]
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
  "Bind `pairs` ([sym init-form] ...) sequentially, returning [env bindings].

  `rebound?` says whether `recur` can write these slots again. It changes what
  may be believed about them: a `let` binding is written once, so a tag INFERRED
  from the initialiser holds for the whole scope, while a `loop` binding is
  written again by every `recur` and only a DECLARED tag survives -- because a
  declared one is checked at each recur, and an inferred one would be a claim
  about the first iteration presented as a claim about all of them."
  [env pairs rebound?]
  (reduce (fn [[e acc] [sym init]]
            (when-not (symbol? sym) (err "binding name must be a symbol" {:sym sym}))
            (let [want (ty/known sym)
                  init-ast (checked e (analyze e init) want sym)
                  idx (alloc-local! (:scope e))
                  ;; What every later READ of this local reports. Sound because
                  ;; the barrier above is the only way into the slot.
                  tag (if rebound? want (or want (node-tag init-ast)))]
              [(assoc-in e [:locals sym]
                         (cond-> {:kind :local :idx idx}
                           tag (assoc :tag tag)
                           ;; What testing THIS local would tell us, inherited
                           ;; from whatever it was bound to. `and` and `or`
                           ;; expand to a let of the test followed by an `if` on
                           ;; the local, so the projection has to survive one
                           ;; binding or it is lost exactly where it is wanted.
                           ;; Never for a rebound slot: `recur` can put a
                           ;; different value in it.
                           ;; Through whatever the initialiser is made of. A
                           ;; nested `and` is a `:let` node with no `:projects`
                           ;; of its own, so reading the key rather than
                           ;; computing it lost every nested case.
                           (and (not rebound?) (seq (branch-projects init-ast)))
                           (assoc :projects (branch-projects init-ast))))
               (conj acc {:idx idx :init init-ast :name sym :tag tag})]))
          [env []]
          pairs))

(defn analyze-special [env head form]
  (case head
    quote (const-node (second form))

    ;; Occurrence narrowing. `(if (int? x) A B)` compiles A knowing `x` is an
    ;; int, because that is what the test having succeeded MEANS. Nobody has to
    ;; write an annotation for it, and it applies to code written years before
    ;; flint existed -- which is most of what the census says real programs
    ;; spend their time in.
    if (let [[_ test then else] form]
         (when (< (count form) 3) (err "if needs at least a test and a then" {:form form}))
         (let [test-ast (analyze env test)
               ;; Seen through whatever the test is made of: a predicate call, a
               ;; local bound to one, or the `let`+`if` that `and` and `or`
               ;; expand to. A local node carries the projections of whatever it
               ;; was bound to, which is what makes the first two the same case.
               p (branch-projects test-ast)]
           {:op :if :test test-ast
            :then (analyze (narrow env (get p true)) then)
            :else (if (> (count form) 3)
                    (analyze (narrow env (get p false)) else)
                    (const-node nil))}))

    do (analyze-body env (rest form))

    let* (let [[_ bindings & body] form
               _ (when (odd? (count bindings)) (err "let needs an even binding vector" {:form form}))
               n0 (:nlocals @(:scope env))
               [env' bs] (bind-locals env (partition 2 bindings) false)
               body-ast (analyze-body env' body)]
           (release-locals! (:scope env) n0)
           {:op :let :bindings bs :body body-ast})

    loop* (let [[_ bindings & body] form
                n0 (:nlocals @(:scope env))
                [env' bs] (bind-locals env (partition 2 bindings) true)
                loop-id (gensym "loop")
                env' (assoc env' :loop {:id loop-id :slots (mapv :idx bs) :n (count bs)
                                        :tags (mapv :tag bs) :names (mapv :name bs)})
                body-ast (analyze-body env' body)]
            (release-locals! (:scope env) n0)
            {:op :loop :id loop-id :bindings bs :body body-ast})

    recur (let [l (:loop env)]
            (when-not l (err "recur outside of loop or fn" {:form form}))
            (let [args (mapv #(analyze env %) (rest form))]
              (when (not= (count args) (:n l))
                (err (str "recur expects " (:n l) " arguments, got " (count args)) {:form form}))
              ;; The barrier again, at the OTHER way into these slots. Without
              ;; this a declared loop tag would hold on the first iteration and
              ;; be a lie on the second, and every specialisation downstream of
              ;; it would be unsound -- which is the worst shape of bug this
              ;; feature can have, because the first iteration passes.
              {:op :recur :id (:id l) :slots (:slots l)
               :args (vec (map-indexed
                           (fn [i a]
                             (checked env a (nth (:tags l) i nil)
                                      (or (nth (:names l) i nil) "a recur argument")))
                           args))}))

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
          (when (:dynamic (meta nm))
            (vswap! (:cc env) assoc-in [:dynamic q] true))
          {:op :def :sym q :meta (merge (meta nm) (when doc {:doc doc}))
           :init (when (>= n 3)
                   (analyze (assoc env :current-var q) init-form))})

    ;; `binding` is a special form rather than a macro because it has to resolve
    ;; each name to the var it means, which is analysis, not expansion. It
    ;; rewrites to ordinary forms, so the emitter learns nothing new.
    binding
    (let [bs (vec (second form))
          _ (when (odd? (count bs)) (err "binding needs an even binding vector" {:form form}))
          body (drop 2 form)
          pairs (partition 2 bs)
          outer 'flint-binding-outer
          qs (mapv (fn [p]
                     (let [q (qualify env (first p))]
                       (when-not q (err (str "unable to resolve " (first p)) {:form form}))
                       (when-not (get-in @(:cc env) [:dynamic q])
                         (err (str q " is not dynamic, so it cannot be rebound."
                                   " Define it with (def ^:dynamic " (clojure.core/name q) " ...)")
                              {:sym q}))
                       q))
                   pairs)
          assoc-form (concat (list 'clojure.core/assoc outer)
                             (mapcat (fn [q p] [(list 'quote q) (second p)]) qs pairs))
          rewritten (list 'let* (vector outer (list 'flint.rt/dyn-bindings))
                          (list 'try
                                (cons 'do (cons (list 'flint.rt/dyn-set-bindings assoc-form) body))
                                (list 'finally (list 'flint.rt/dyn-set-bindings outer))))]
      (analyze env rewritten))

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
                                     (let [idx (alloc-local! scope)
                                           t (ty/known p)]
                                       (assoc-in e [:locals p]
                                                 (cond-> {:kind :local :idx idx}
                                                   t (assoc :tag t)))))
                                   base-env
                                   all)
                      ;; A named fn can call itself. It cannot capture itself --
                      ;; the closure does not exist while its body is compiled --
                      ;; so the name resolves to the frame's own closure.
                      env' (if fname (assoc-in env' [:locals fname] {:kind :self}) env')
                      slots (mapv #(get-in env' [:locals % :idx]) all)
                      env' (assoc env' :loop {:id (gensym "fnloop") :slots slots
                                              :n (count all)
                                              :tags (mapv ty/known all) :names (vec all)})
                      ;; A parameter's tag is checked ON ENTRY, once, and every
                      ;; read of it in the body is then known without a test.
                      ;; The checks are statements: `check-tag` returns the
                      ;; value, and the slot already holds it.
                      pchecks (vec (keep (fn [p]
                                           (when-let [t (ty/known p)]
                                             (check-node env'
                                                         {:op :local :name p
                                                          :idx (get-in env' [:locals p :idx])}
                                                         t p)))
                                         all))
                      body-ast (analyze-body env' body)
                      ;; The declared return type, checked at the return site.
                      ret (ty/known params)
                      body-ast (checked env' body-ast ret
                                        (str (or fname "this fn") "'s return"))
                      body-ast (if (seq pchecks)
                                 {:op :do :body (conj pchecks body-ast)}
                                 body-ast)]
                  {:argc (count fixed) :variadic? variadic?
                   ;; The parameter NAMES, kept so a declaration written about
                   ;; `x` can be resolved to an argument position.
                   :params (vec all)
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
