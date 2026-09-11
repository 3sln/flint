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
            q (symbol (str target) (name sym))
            v (get (:virtual cc) target)]
        (cond
          ;; A VIRTUAL namespace has no vars to look up (`DECISIONS.md#workspace-capabilities`
          ;; step 4). Whether an unknown name here is caught depends on what the
          ;; resolver could say, and both answers are legitimate:
          ;;
          ;;   * a var list -- from a booted pod's `describe`, or from the CLI
          ;;     which knows its own surface -- makes this a COMPILE error, with
          ;;     the same message every other unknown var gets;
          ;;   * no var list makes it a run-time error, which is worse, and is
          ;;     the price of not booting a process at build time.
          ;;
          ;; The build says which it got, because the same mistake being caught
          ;; at two different times is only defensible if a person can tell.
          (and v (:checked? v))
          (do (when-not (get (:vars v) (symbol (name sym)))
                (err (str "unable to resolve " sym " -- " target
                          " is a virtual namespace and does not hold "
                          (name sym))
                     {:sym sym :ns nsname :virtual target}))
              q)

          v q

          ;; Refuse to invent a var. Without this a missing :require produces a
          ;; dangling reference that only fails much later, at emission.
          (not (or (get-in cc [:vars q]) (get-in cc [:declared q])))
          (err (str "unable to resolve " sym
                    (if (= (str target) ns-part)
                      (str " -- is " target " required?")
                      (str " -- alias " alias " means " target)))
               {:sym sym :ns nsname})

          :else q))
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

(defn- virtual-var
  "The qualified symbol `sym` names, when its namespace is VIRTUAL, else nil.

  A virtual namespace has no source and no vars (`DECISIONS.md#workspace-capabilities` step 4);
  a reference to one compiles to a call over a port. Resolving it is the same
  `qualify` every other reference goes through -- aliases included, so
  `(:require [flint.sys.fs :as fs])` and `fs/list-dir` work exactly as they
  read."
  [env sym]
  (when (and (symbol? sym) (namespace sym) (not (resolve-local env sym)))
    (let [cc @(:cc env)
          nsdef (get-in cc [:namespaces (current-ns env)])
          alias (symbol (namespace sym))
          target (get (:aliases nsdef) alias alias)
          v (get (:virtual cc) target)]
      (when v
        ;; CHECKED HERE, and not only in `qualify`. The head-position rewrite
        ;; below resolves through this function and never reaches `qualify`, so
        ;; a check that lived only there would fire for a virtual var used as a
        ;; VALUE and not for one that was called -- which is every ordinary use.
        (when (and (:checked? v) (not (get (:vars v) (symbol (name sym)))))
          (err (str "unable to resolve " sym " -- " target
                    " is a virtual namespace and does not hold " (name sym))
               {:sym sym :ns (current-ns env) :virtual target
                :line (:line (meta sym)) :column (:column (meta sym))}))
        (symbol (str target) (name sym))))))

(defn- macro-fn [env sym]
  (when-let [q (qualify env sym)]
    (let [c @(:cc env)]
      (or (get-in c [:macros q])
          ;; A MACRO WE KNOW ABOUT AND CANNOT EXPAND YET IS AN ERROR, not a
          ;; function call. `:macros` holds expanders, and an expander exists
          ;; only once its namespace has been ANALYSED. A namespace analysed
          ;; BEFORE the definer therefore found nothing here and compiled the
          ;; form as an ordinary call -- silently, and with different
          ;; evaluation: every argument evaluated, in a form written precisely
          ;; so that they would not be.
          ;;
          ;; Measured with a macro that DISCARDS its argument, given an
          ;; argument that cannot resolve. Ordinary edge: expanded, the symbol
          ;; never analysed. Reverse edge: "unable to resolve symbol". The
          ;; argument was evaluated in a macro call.
          ;;
          ;; The read pre-pass marks the NAME, which is all that is needed to
          ;; say so. Saying so is the whole fix: there is no ordering that
          ;; makes this work, because the user's namespace does not require
          ;; the definer -- it names it -- so there is no edge to order by.
          (when (:macro (get (:declared c) q))
            (err (str q " is a macro, and " (current-ns env)
                      " is compiled before the namespace that defines it, so"
                      " there is no expansion to use here."
                      " Move the macro to a namespace this one requires.")
                 {:sym sym :macro q :ns (current-ns env)}))))))

;; How many `:inline` expansions may nest before the analyzer calls it a loop.
;; An inline whose body calls the function it is inlining -- which is how a
;; fallback gets written, and easy to write by accident -- would otherwise
;; expand until the host stack goes, and a StackOverflowError names nothing a
;; reader can act on. Source nesting never approaches this; runaway expansion
;; reaches it immediately.
(def ^:private inline-depth-limit 200)

;; The same, for ordinary macros. Higher because legitimate nesting is deeper:
;; a macro that expands into a macro that expands into a macro is ordinary,
;; while an `:inline` that reaches 200 is already pathological.
(def ^:private macro-depth-limit 500)

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

(declare analyze analyze* analyze-untagged analyze-body analyze-fn analyze-special analyze-ns)

(defn- const-node [v] {:op :const :val v})

(def builtin-guards
  "Capabilities a BUILTIN demands of the workspace naming it.

  `workspace-capabilities` level two guards a VAR. Builtins are not vars, and the catalogue is
  reachable from anywhere: `native-name` turns `flint.rt/<x>` into a direct
  native call for any `x` the loader carries. So a guard on a stdlib var that
  merely FORWARDS to a builtin is decorative -- the caller can skip the wrapper
  and name the builtin.

  That was not hypothetical. `flint.host/request` is guarded `[:host]`, and it
  and `flint.host/ask` were the ONLY guarded vars in the whole of `lib/`. Both
  forward to `flint/request`. Measured with the real 192-name catalogue, a
  workspace holding nothing:

    names the guarded var   -> refused, \"does not hold #{:host}\"
    names flint.rt/request  -> compiled

  So level two protected nothing that mattered, and the docstring below already
  named the mechanism -- it cited `flint.rt/<name>` as the reason a guard can
  never be a callable, without noticing the same reach applied to the builtin
  the guard was standing in front of.

  THE RULE HERE IS DERIVED, NOT CHOSEN. An entry says: this builtin is what a
  guarded stdlib var wraps, so it carries that var's guard. It is deliberately
  not a general policy about which builtins are authority-bearing -- whether
  the port surface should demand something is a real design question and is
  not answered here."
  {"flint/request" #{:host}})

(defn- guard-check!
  "Refuse a reference to a var its workspace guards (`DECISIONS.md#workspace-capabilities`).

  Level two of two. Level one is the `:require` edge and is checked from the
  dependency graph; this is the reference itself, so a workspace may be depended
  on freely while three of its vars are not.

  Checked HERE, at the reference, and emitted nowhere. A guard is a compile-time
  construct: it costs a run nothing, and there is no callable behind it. That is
  not a convenience -- `native-name` lets any source write `flint.rt/<name>` for
  anything in the builtin catalogue, so a callable `resolve` taking a quoted
  symbol would be a general reflective var lookup and would defeat this entirely
  in one line. Anything that resolves authority has to be a compile-time
  construct, never a callable.

  Same workspace, no check, for the reason level one has none: a project is not
  a security boundary against itself. Two namespaces with NO workspace are both
  the anonymous one and so are the same -- a program that declares no workspaces
  is checked nowhere, which is what it was before any of this existed.

  THE LIMIT THIS ONCE CLAIMED WAS WRONG, and the correction is worth keeping.
  It said the guard is always recorded before the reference because namespaces
  are analysed dependencies-first, so that only a cycle ACROSS workspaces could
  fail open. Dependencies-first is what CREATES the hole: if A requires B then
  B is analysed FIRST, and B may name A's vars without requiring it, because
  `qualify` resolves through `:declared`. No cycle, no reverse require, just an
  ordinary edge pointing the other way.

  Measured before this was rewritten: the same function, in the same position,
  refused for naming a private var and ACCEPTED for naming a guarded one.

  It reads `:declared` now, which the read pre-pass fills before any analysis,
  so the answer no longer depends on which file the compiler reached first."
  [env q]
  (when (namespace q)
    (let [c @(:cc env)
          ;; THE SAME FALLBACK `privacy-check!` USES, and for the same reason
          ;; one function up. `:var-meta` is filled in when a namespace is
          ;; ANALYSED; `:declared` is filled in by the read pre-pass, before
          ;; any analysis. Reading only the first made this ORDER-DEPENDENT,
          ;; and the docstring above says why that was thought safe --
          ;; "namespaces are analysed dependencies-first". That is exactly what
          ;; breaks it: dependencies-first means that if A requires B then B is
          ;; analysed FIRST, so B's references to A's guarded vars are checked
          ;; against an empty table. No cycle is needed, and no `:require` from
          ;; B to A is needed either, because `qualify` resolves through
          ;; `:declared`.
          ;; BUILTINS COME THROUGH HERE TOO. Both places that resolve a
          ;; `flint.rt/<x>` reference record it as `flint.native/<catalogue
          ;; name>`, so checking that namespace here covers the value position
          ;; and the call position at once, with the workspace comparison
          ;; below unchanged.
          builtin? (= "flint.native" (namespace q))
          guard (if builtin?
                  (get builtin-guards (name q))
                  (:flint/capabilities-guard
                   (or (get (:var-meta c) q)
                       (let [d (get (:declared c) q)]
                         (when (map? d) d)))))]
      (when (seq guard)
        (let [here (current-ns env)
              there (symbol (namespace q))
              ws (:workspaces c)
              w-here (get-in ws [here :workspace])
              w-there (get-in ws [there :workspace])]
          ;; A BUILTIN IS NEVER "THE SAME WORKSPACE" AS ITS CALLER, and this is
          ;; the second hole found in this check rather than the first.
          ;;
          ;; The same-workspace exemption exists because a project is not a
          ;; security boundary against itself. A builtin belongs to no project:
          ;; `there` is `flint.native`, which no source declares, so `w-there`
          ;; came back nil -- the ANONYMOUS workspace -- and any caller that
          ;; was also anonymous compared equal to it and was never checked.
          ;;
          ;; That is not a corner. It is what the SDK does by default: it names
          ;; the LIBRARY's workspace and leaves the embedder's own files
          ;; unnamed unless the embedder says otherwise. Measured:
          ;;
          ;;   app anonymous, only the lib named -> compiled
          ;;   app named, holding nothing        -> refused
          ;;
          ;; The var guard was unaffected, because the library it protects IS
          ;; named, so that edge crosses. Only the builtin -- the rung under
          ;; it, and the one that matters when nobody has configured anything
          ;; -- was inert exactly where the configuration is thinnest.
          ;;
          ;; THE ESCAPE FOR A PROGRAM WITH NO WORKSPACES AT ALL STAYS. "Declare
          ;; none and nothing is checked" is what this was before any of it
          ;; existed, and a CLI build of one's own program should not need a
          ;; capability table. The test is whether the program names any
          ;; workspace ANYWHERE: once it is using the mechanism, code that was
          ;; left unnamed is a workspace holding nothing, not a peer of the
          ;; runtime.
          (when (if builtin?
                  (some :workspace (vals ws))
                  (not= w-here w-there))
            ;; What is MISSING, not the whole guard: naming three capabilities
            ;; the caller already holds helps nobody find the one it does not.
            ;; A VAR'S OWN GUARD DOES NOT AUTHORISE ITS BODY, and this is
            ;; the second thing tried here rather than the first. Letting a
            ;; guarded var name what it guards reads well -- it is how
            ;; `flint.host/request` wraps `flint.rt/request` -- and it is a
            ;; LAUNDERING HOLE, because a guard is not a grant. A grant comes
            ;; from the embedder, in the workspace table the host supplies. A
            ;; guard is written by the author of the var, about their own var.
            ;;
            ;; Measured, with a workspace holding nothing:
            ;;
            ;;   (defn ^{:flint/capabilities-guard [:host]} w [x]
            ;;     (flint.rt/request x))
            ;;   (defn wide-open [x] (w x))     ; same workspace, no check
            ;;
            ;; compiled. Any code could mint the authority by asserting it, and
            ;; then hand it on unguarded. The check must read only what was
            ;; GRANTED.
            (let [missing (into #{} (remove (or (get-in ws [here :grants]) #{}) guard))]
              (when (seq missing)
                ;; Name it the way a person WROTE it. `flint.native/flint/add`
                ;; is the internal coordinate and appears in no source.
                (err (str (if builtin?
                            (str "flint.rt/" (str/replace (name q) #"^flint/" ""))
                            q)
                          " is guarded with " (pr-str (into #{} guard))
                          " by " (if builtin? "the runtime" (or w-there "its workspace"))
                          "; " (or w-here "this program") " does not hold "
                          (pr-str missing))
                     {:var q :needs missing
                      :from-workspace w-here :to-workspace w-there})))))))))

(defn- privacy-check!
  "Refuse a reference to a var that is not visible from here.

  Two marks, two boundaries, and both are checked at the REFERENCE for the
  same reason `guard-check!` is: visibility is a compile-time construct that
  costs a run nothing, and there is no callable behind it to route around.

      ^:private    (or `defn-`)   only the defining NAMESPACE may name it
      ^:internal                  only the defining WORKSPACE may name it
      neither                     anyone may

  `^:internal` exists because a namespace is too small a unit to build a
  library out of and a workspace is the unit that ships. A library with six
  namespaces has helpers that all six need and nobody outside should touch,
  and before this the only way to say that was to make them public and hope.

  A workspace is the same boundary `workspace-capabilities` guards capabilities across, and the
  two questions compose without interfering: `^:internal` asks WHO MAY NAME
  THIS, a guard asks WHAT MAY THIS CODE DO. A var can be both.

  Namespaces with no workspace are all the anonymous one, so a program that
  declares none is checked only for `:private` -- which is what it should be,
  since a workspace boundary nobody drew cannot be crossed.

  On macros: a macro body calling a private helper is safe, because that call
  happens while the macro RUNS, in its own namespace, at expansion time. What
  would be refused is a macro that EMITS a private var into its expansion,
  where the reference really does land in the caller. Clojure refuses that
  too, and the fix is the same: make the helper visible, or expand to
  something public."
  [env q]
  (when (namespace q)
    (let [c @(:cc env)
          ;; `:var-meta` IS ONLY FILLED IN WHEN A NAMESPACE IS ANALYSED, and
          ;; `:declared` is filled in by a pre-pass over every source. Reading
          ;; only the first made privacy ORDER-DEPENDENT: a reference from a
          ;; namespace analysed before the definer found no metadata and met
          ;; no check. If A required B, B could name A's private vars -- same
          ;; two files, refused when A was analysed first and allowed when B
          ;; was.
          ;;
          ;; The pre-pass entry is a map now and carries `:private`, so the
          ;; fallback answers before the definer has been analysed. A `declare`
          ;; writes `true` rather than a map, which is why this checks; a
          ;; declared name has no visibility to report anyway.
          m (or (get (:var-meta c) q)
                (let [d (get (:declared c) q)]
                  (when (map? d) d)))
          here (current-ns env)
          there (symbol (namespace q))]
      (when (not= here there)
        (cond
          (:private m)
          (err (str q " is private to " there
                    "; " here " may not name it")
               {:var q :from here :defined-in there :visibility :private})

          (:internal m)
          (let [ws (:workspaces c)
                w-here (get-in ws [here :workspace])
                w-there (get-in ws [there :workspace])]
            (when (not= w-here w-there)
              (err (str q " is internal to " (or w-there "its workspace")
                        "; " (or w-here "this program") " is outside it")
                   {:var q :from here :defined-in there
                    :from-workspace w-here :to-workspace w-there
                    :visibility :internal}))))))))

(defn- record-dep! [env q]
  (privacy-check! env q)
  (guard-check! env q)
  (when-let [cur (:current-var env)]
    (vswap! (:cc env) update-in [:deps cur] (fnil conj #{}) q)))

(defn- analyze-symbol [env sym]
  (if-let [vq (virtual-var env sym)]
    ;; IN VALUE POSITION, a virtual var becomes a closure that invokes it
    ;; remotely -- not a `:get` on the far side.
    ;;
    ;; `workspace-capabilities` wrote the two operations as `call` and `get`, and taking a bare
    ;; reference to mean `get` would break `(mapv fs/list-dir xs)`, and storing
    ;; one in a local, and putting one in a map. Nearly every var behind one of
    ;; these namespaces is a
    ;; function, so the common reading wins and fetching a non-function var is
    ;; spelled out as `flint.virtual/value-of`. That keeps both operations
    ;; reachable and neither of them surprising.
    (do (record-dep! env 'flint.virtual/fn-for)
        (analyze env (list 'flint.virtual/fn-for (list 'quote vq))))
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
            ;; (DECISIONS.md#threads-and-ports, section 4).
            (do (record-dep! env (symbol "flint.native" "flint/dyn-get"))
                {:op :native :name "flint/dyn-get"
                 :args [(const-node q) {:op :var :sym q}]})
            {:op :var :sym q}))
      (err (str "unable to resolve symbol: " sym)
           ;; The SYMBOL's own position, which it has carried since the reader
           ;; started giving one to every meta-able form rather than only to
           ;; sequences. Before that this key was written and always nil, so the
           ;; error fell back to the enclosing `defn`'s line -- where the
           ;; function starts, not where the mistake is.
           {:sym sym :ns (current-ns env)
            :line (:line (meta sym)) :column (:column (meta sym))}))))))

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
  (let [head (first form)
        ;; The expansion counters measure a CHAIN: macro expands to macro
        ;; expands to macro. A chain ENDS as soon as a form is analyzed that is
        ;; not itself an expansion, so the counters are cleared here and put
        ;; back only on the branches that expand.
        ;;
        ;; Carrying them into subforms instead adds up sibling expansions
        ;; across a whole function body and reports a limit nobody reached --
        ;; which is what the first version of this did, blaming `(cond)` for a
        ;; count its ancestors had run up.
        mdepth (:macro-depth env 0)
        idepth (:inline-depth env 0)
        env (dissoc env :macro-depth :inline-depth)]
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
      ;; A VIRTUAL var IN HEAD POSITION becomes one call, not a closure and
      ;; then a call (`DECISIONS.md#workspace-capabilities` step 4).
      ;;
      ;; `analyze-symbol` would already turn the head into `fn-for`, and
      ;; `((fn-for 'ns/f) a b)` is correct -- and allocates a closure per call
      ;; for nothing. This is the ordinary case and it gets the direct shape.
      ;;
      ;; Checked BEFORE inlines and macros because a virtual namespace has
      ;; neither: there is no source to have defined one in.
      (virtual-var env head)
      (analyze env (list* 'flint.virtual/call
                          (list 'quote (virtual-var env head))
                          (rest form)))

      ;; inline, while an inline that re-emits its own name must stop.
      (and (symbol? head) (not (resolve-local env head))
           (not (macro-fn env head))
           (inline-fn env head (count (rest form))))
      (let [q (qualify env head)
            d (inc idepth)
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
            ;; The same guard the `:inline` branch has, and for the same
            ;; reason: expansion re-analyzes, so a macro that returns its own
            ;; input never terminates. Without it the compiler simply runs out
            ;; of stack, which names nothing -- 11 million identical frames
            ;; with no form and no macro in them.
            d (inc mdepth)
            _ (when (> d macro-depth-limit)
                (err (str "macro expansion of " (qualify env head) " does not "
                          "terminate: " macro-depth-limit " nested expansions. "
                          "A macro whose expansion still calls itself in head "
                          "position expands forever.")
                     {:form form :sym (qualify env head)}))
            ;; `&env` is deliberately tiny: the namespace being compiled, and
            ;; nothing else. `defprotocol` needs it to build the fully-qualified
            ;; method keyword that metadata dispatch looks for; handing macros
            ;; the whole analyzer environment would make it API.
            expanded (apply f form {:ns (current-ns env)} (rest form))]
        (analyze (assoc env :macro-depth d) expanded))

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

;; Analysis is a recursive descent, so a form that keeps producing itself
;; descends without bound and takes the HOST's stack with it. On a port that is
;; a `StackOverflowError` naming nothing -- a million identical interpreter
;; frames with no flint form anywhere in them.
;;
;; A backstop, not a budget: 2 000 is far past any real nesting, and low enough
;; to be reached before a host stack gives out. A limit above what the host
;; survives never fires, which is the same as not having one.
;;
;; A volatile rather than a value threaded through `env` because every one of
;; the fifty-odd recursive calls would have to pass it, and one that forgot
;; would silently reset the count. A compile is single-threaded.
(def ^:private analyze-depth-limit 2000)
(def ^:private analyze-depth (volatile! 0))

;; The last `trace-depth` forms by DEPTH, indexed by depth itself, so reading it
;; back gives the innermost nesting rather than the most recent calls. That
;; distinction matters: a log of recent calls includes ones that already
;; returned, and reads as a cycle whether or not there is one.
(def ^:private trace-depth 12)
(def ^:private analyze-trace (volatile! (vec (repeat trace-depth nil))))

(defn- brief [form]
  (let [s (pr-str form)]
    (if (> (count s) 140) (str (subs s 0 140) " ...") s)))

(defn analyze [env form]
  (let [d (inc (long @analyze-depth))]
    (vswap! analyze-trace assoc (rem d trace-depth) form)
    (when (> d analyze-depth-limit)
      (vreset! analyze-depth 0)
      (err (str "analysis descended past " analyze-depth-limit " nested forms, "
                "which is not a depth real code reaches. Something is "
                "producing itself. The innermost " trace-depth
                " forms, outermost first:\n    "
                (apply str
                       (interpose "\n    "
                                  (map (fn [i]
                                         (brief (nth @analyze-trace
                                                     (rem (+ d 1 i) trace-depth))))
                                       (range trace-depth)))))
           {:form form}))
    (vreset! analyze-depth d))
  (try
    (analyze* env form)
    (finally (vswap! analyze-depth dec))))

(defn- analyze* [env form]
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

(defn- recur-proofs
  "The `:proved` vector of every `recur` targeting `id`, anywhere below `node`.

  Filtered by id because a nested `loop` or `fn` carries recurs of its own, and
  believing one of those about THIS loop's slots would be exactly the unsound
  step this verification exists to prevent."
  [node id]
  (let [out (volatile! [])]
    (letfn [(walk [x]
              (cond
                (map? x) (do (when (and (= :recur (:op x)) (= id (:id x)))
                               (vswap! out conj (:proved x)))
                             (run! walk (vals x)))
                (or (vector? x) (seq? x)) (run! walk x)
                :else nil))]
      (walk node))
    @out))

(defn- bind-locals
  "Bind `pairs` ([sym init-form] ...) sequentially, returning [env bindings].

  `rebound?` says whether `recur` can write these slots again. It changes what
  may be believed about them: a `let` binding is written once, so a tag INFERRED
  from the initialiser holds for the whole scope, while a `loop` binding is
  written again by every `recur`.

  `infer?` lets a `loop` binding take its initialiser's tag ANYWAY, as a
  hypothesis. That is only sound if it is then verified -- see `loop*`, which
  assumes, analyzes, and throws the hypothesis away unless every `recur` proved
  it. Without that verification an inferred loop tag is a claim about the first
  iteration presented as a claim about all of them."
  [env pairs rebound? infer?]
  (reduce (fn [[e acc] [sym init]]
            (when-not (symbol? sym) (err "binding name must be a symbol" {:sym sym}))
            (let [want (ty/known sym)
                  init-ast (checked e (analyze e init) want sym)
                  idx (alloc-local! (:scope e))
                  ;; What every later READ of this local reports. Sound because
                  ;; the barrier above is the only way into the slot.
                  tag (if (and rebound? (not infer?)) want (or want (node-tag init-ast)))]
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
               [env' bs] (bind-locals env (partition 2 bindings) false false)
               body-ast (analyze-body env' body)]
           (release-locals! (:scope env) n0)
           {:op :let :bindings bs :body body-ast})

    ;; A loop binding with no written annotation is HYPOTHESISED to keep its
    ;; initialiser's type, the body is analyzed under that hypothesis, and the
    ;; hypothesis is kept only if every `recur` proved it. That is induction:
    ;; the initialiser is the base case, each recur the inductive step. It adds
    ;; no runtime check and rejects no program -- a loop that recurs with a
    ;; float into a slot that started as an int simply falls back, exactly as
    ;; before.
    ;;
    ;; It has to be a hypothesis rather than a bottom-up inference because the
    ;; two depend on each other: `(recur (+ i 1))` is an int only if `i` is one,
    ;; which is the very thing being decided. Assuming it is the only way to ask
    ;; the question.
    ;;
    ;; This is what turns the specialised integer opcodes on. Before it, an
    ;; ordinary counting loop emitted not one of them: the census says loop
    ;; counters are where the arithmetic is, and nobody annotates them.
    loop* (let [[_ bindings & body] form
                pairs (partition 2 bindings)
                n0 (:nlocals @(:scope env))
                analyze-with
                (fn [infer?]
                  (let [[env' bs] (bind-locals env pairs true infer?)
                        loop-id (gensym "loop")
                        env' (assoc env' :loop
                                    {:id loop-id :slots (mapv :idx bs) :n (count bs)
                                     :tags (mapv :tag bs) :names (mapv :name bs)})]
                    [bs loop-id (analyze-body env' body)]))
                [bs loop-id body-ast] (analyze-with true)
                ;; Which slots were hypothesised -- a WRITTEN tag is checked at
                ;; each recur on purpose and is not up for revision.
                guessed (set (keep-indexed (fn [i [sym _]]
                                             (when (and (:tag (nth bs i))
                                                        (not (ty/known sym)))
                                               i))
                                           pairs))
                held? (or (empty? guessed)
                          (every? (fn [pv] (every? #(nth pv % true) guessed))
                                  (recur-proofs body-ast loop-id)))
                [bs loop-id body-ast] (if held?
                                        [bs loop-id body-ast]
                                        (do (release-locals! (:scope env) n0)
                                            (analyze-with false)))]
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
               ;; Per argument: did it PROVE the slot's tag, or did it need a
               ;; barrier? A hypothesised loop tag survives only if every recur
               ;; proved it, and reading that back off the emitted node would
               ;; confuse a barrier the AUTHOR asked for with one the hypothesis
               ;; forced. So record it here, where the difference is still known.
               :proved (vec (map-indexed
                             (fn [i a]
                               (let [want (nth (:tags l) i nil)]
                                 (or (nil? want) (ty/proves? (node-tag a) want))))
                             args))
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
          ;; KEEP THE PRE-PASS MAP. `:declared` holds `{:private .. :internal
          ;; ..}` from the read pass, and assoc'ing `true` over it threw that
          ;; away the moment a namespace was analysed. Nothing needed it gone:
          ;; every reader of `:declared` wants either existence or those marks.
          (vswap! (:cc env) update-in [:declared q] (fn [d] (if (map? d) d true)))
          ;; AND RECORD THE METADATA FROM THE FORM IN FRONT OF US, which is the
          ;; EXPANDED one. The other place that fills `:var-meta`
          ;; (`compiler.cljc`) reads the source form in a pass that runs before
          ;; any macro expands, so it sees `(my-macro ..)` and not the `def` it
          ;; becomes -- a var defined BY a macro got no metadata at all, and so
          ;; no privacy. Measured: the same `(def ^:private hidden 1)` was
          ;; refused across a namespace boundary when written literally and
          ;; ACCEPTED when a macro produced it. That is not an ordering
          ;; problem; it is every order.
          ;;
          ;; `defn-` is the case that made this visible once already. It was
          ;; patched there by special-casing the symbol `defn-`, which fixed
          ;; one macro. This fixes the reason.
          ;;
          ;; A MARK IS NEVER DROPPED. Expansion wins for ordinary keys, but if
          ;; either the source form or the expansion says private, internal, or
          ;; guarded, that stands -- a macro that forgets to carry a mark
          ;; forward must not be able to publish someone's private var.
          (let [prev (get-in @(:cc env) [:var-meta q])
                now (meta nm)]
            (when (or (seq prev) (seq now))
              (vswap! (:cc env) assoc-in [:var-meta q]
                      (cond-> (merge prev now)
                        (or (:private prev) (:private now))
                        (assoc :private true)

                        (or (:internal prev) (:internal now))
                        (assoc :internal true)

                        (or (:flint/capabilities-guard prev)
                            (:flint/capabilities-guard now))
                        (assoc :flint/capabilities-guard
                               (or (:flint/capabilities-guard prev)
                                   (:flint/capabilities-guard now)))))))
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

(def require-clauses
  "The `ns` clauses that NAME NAMESPACES.

  One set, read by everything that needs the answer: this file binds their
  aliases and refers, and `flint.compiler/ns-requires` and `ns-aliases` build
  the load-order graph from the same heads. Two spellings of \"which clause
  names a namespace\" is a graph that disagrees with the bindings."
  #{:require :use})

(def known-ns-clauses
  "Every clause head an `ns` form may carry.

  `:import` is included and still REFUSED -- it is a clause flint knows about
  and rejects with a reason, which is a different answer from one it has never
  heard of."
  (into #{:refer-clojure :import} require-clauses))

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
          ;; AN UNKNOWN CLAUSE IS AN ERROR, not a no-op. Dropping it silently
          ;; makes a misspelled `:require` into a namespace with no
          ;; dependencies, which fails much later as an unresolved var and
          ;; names nothing that would lead a reader back to the `ns` form.
          (throw (ex-info (str "(ns " nsname ") has no " (pr-str (first c))
                               " clause -- flint takes "
                               (str/join ", " (sort (map str known-ns-clauses))))
                          {:form c :ns nsname :known known-ns-clauses})))))
    {:op :const :val nil}))
