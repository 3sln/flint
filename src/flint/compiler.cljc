(ns flint.compiler
  "The driver: sources in, program image out.

  Three passes, and the middle one is the reason there are three:

  1. **read and declare** every namespace, so forward references inside a
     namespace resolve without `declare` gymnastics;
  2. **analyze** every top-level form to an AST, running `defmacro` bodies
     through `flint.eval` as they are met, and recording which vars each var
     refers to;
  3. **reach and emit** -- start from `:fn`, take the transitive closure over
     that reference graph, and emit only those. Tree shaking is per VAR, not per
     namespace, which is what makes `clojure.core` affordable to ship."
  (:require [clojure.string :as str]
            [flint.reader :as reader]
            [flint.analyzer :as ana]
            [flint.emitter :as emit]
            [flint.eval :as ev]
            [flint.image :as img]
            [flint.macros :as macros]))

(defn- err [msg data] (throw (ex-info msg (assoc data :type :compile))))

;; --------------------------------------------------------------- namespaces

(defn ns-form? [f] (and (seq? f) (= 'ns (first f))))

(defn ns-requires [form]
  (let [[_ _ & clauses] form]
    (vec (for [c clauses
               :when (and (seq? c) (#{:require :use} (first c)))
               spec (rest c)
               :let [t (if (symbol? spec) spec (first spec))]]
           t))))

(defn ns-aliases [form]
  (into {} (for [c (drop 2 form)
                 :when (and (seq? c) (#{:require :use} (first c)))
                 spec (rest c)
                 :when (vector? spec)
                 :let [opts (apply hash-map (rest spec))]
                 :when (:as opts)]
             [(:as opts) (first spec)])))

(defn- def-form-names
  "Top-level names a form defines, following the bootstrap macros far enough to
  see through `defn`/`defmacro`/`declare`."
  [form]
  (when (seq? form)
    (case (first form)
      (def) [(second form)]
      (defn defn- defmacro) [(second form)]
      (declare) (vec (rest form))
      (do) (vec (mapcat def-form-names (rest form)))
      nil)))

;; ------------------------------------------------------------------ context

(defn new-context [opts]
  (volatile! {:namespaces {}
              :vars {}
              :declared {}
              :macros {}
              :deps {}
              :items []
              :builtins (:builtins opts #{})
              :native-alias {}
              :native-arity {}
              :eval-vars (atom {})}))

(defn- register-native-aliases!
  "A core var whose body is exactly one `flint.rt/x` call with the same
  arguments is recorded, so call sites go straight to the builtin. This is what
  makes writing `clojure.core` in cljc cost nothing at the call site."
  [cc sym ast]
  (when (and (= :fn (:op ast)) (= 1 (count (:arities ast))))
    (let [{:keys [argc variadic? body slots]} (first (:arities ast))]
      (when (and (not variadic?) (= :native (:op body))
                 (= argc (count (:args body)))
                 (every? true? (map (fn [a s] (and (= :local (:op a)) (= (:idx a) s)))
                                    (:args body) slots)))
        (vswap! cc assoc-in [:native-alias sym] (:name body))
        (vswap! cc assoc-in [:native-arity sym] argc)))))

;; ------------------------------------------------------------------ pass 1/2

(defn- base-env [cc nsname]
  {:cc cc :ns nsname :locals {} :outer nil :scope (ana/new-fn-scope nil) :loop nil})

(defn flatten-top-level
  "Top-level `(do ...)` and `(declare ...)` become separate items, as they do in
  Clojure. Otherwise one item defines several vars, and tree shaking -- which is
  per var -- has nothing to hang them on."
  [forms]
  (vec (mapcat (fn [f]
                 (cond
                   (and (seq? f) (= 'do (first f))) (flatten-top-level (rest f))
                   (and (seq? f) (= 'declare (first f)))
                   (map (fn [n] (list 'def n)) (rest f))
                   :else [f]))
               forms)))

(defn ast-defs
  "Every var an analyzed item defines. Walks the AST rather than the surface
  form, because a macro may expand to several defs -- `defmulti` produces both
  the function and its method table -- and the surface form does not say so."
  [node]
  (cond
    (map? node)
    (concat (when (= :def (:op node)) [(:sym node)])
            (mapcat ast-defs (vals node)))
    (sequential? node) (mapcat ast-defs node)
    :else nil))

(defn read-namespace!
  "Read one namespace and register every name it defines, so that forward
  references -- within a namespace and between namespaces -- resolve without
  `declare`. Clojure needs `declare` for the intra-namespace case; reading
  everything before analysing anything makes it unnecessary."
  [cc nsname src file]
  (let [resolve-hook
        (fn [sym]
          (let [nsdef (get-in @cc [:namespaces nsname])
                n (name sym)]
            (or (get (:refers nsdef) sym)
                (when (get-in @cc [:declared (symbol (str nsname) n)])
                  (symbol (str nsname) n))
                (when (get-in @cc [:declared (symbol "clojure.core" n)])
                  (symbol "clojure.core" n))
                (when (contains? macros/bootstrap (symbol n))
                  (symbol "clojure.core" n)))))
        st (reader/reader src {:file file                                ;; Not #{:clj}: flint is not the JVM, and a
                               ;; :clj branch here would be host interop we
                               ;; cannot compile. Ported code needs a :flint or
                               ;; :default branch -- said plainly in the README.
                               :features #{:flint} :resolve resolve-hook})
        _ (vswap! cc assoc-in [:namespaces nsname] (get-in @cc [:namespaces nsname] {}))
        forms (loop [acc []]
                (let [f (reader/read-form st)]
                  (if (reader/eof? f)
                    acc
                    (do (when (ns-form? f)
                          (reader/set-ns! st (second f) (ns-aliases f)))
                        (recur (conj acc f))))))]
    (let [forms (flatten-top-level forms)]
      (doseq [f forms, n (def-form-names f)]
        (vswap! cc assoc-in [:declared (symbol (str nsname) (name n))] true))
      forms)))

(defn analyze-namespace!
  "Analyze one namespace's already-read forms, appending to `:items` in order."
  [cc nsname forms]
  (do
    (doseq [f forms]
      (cond
        (ns-form? f)
        (let [env (base-env cc nsname)]
          (ana/analyze-ns env f))

        (nil? f) nil

        :else
        (try
        (let [names (def-form-names f)
              sym (when (= 1 (count names)) (symbol (str nsname) (name (first names))))
              ;; Every item gets an id, including bare top-level expressions:
              ;; without one their references go unrecorded, and a var reached
              ;; only from an expression looks unreachable.
              id (or sym (symbol (str nsname) (str "__top-" (count (:items @cc)))))
              env (assoc (base-env cc nsname) :current-var id)
              wrapped (list 'fn* [] f)
              ast (ana/analyze env wrapped)
              macro? (boolean (some-> (first names) meta :macro))
              defform (when (seq? f) (first f))]
          (vswap! cc update :items conj
                  {:ns nsname :sym sym :id id :ast ast
                   :defines (vec (distinct (ast-defs ast)))
                   :kind (if sym :def :expr)
                   :macro? (or macro? (= 'defmacro defform))})
          ;; A macro must exist before the next form that uses it, so evaluate
          ;; it now, on this host, through our own AST interpreter.
          (when (or macro? (= 'defmacro defform))
            (let [ctx {:vars (:eval-vars @cc)}
                  f0 (ev/eval-top ctx ast)]
              (f0)
              (let [mv (get @(:eval-vars @cc) sym)]
                (vswap! cc assoc-in [:macros sym] mv))))
          ;; A macro body can call any function defined before it, so every
          ;; top-level def is also evaluated on the host. Failures are recorded
          ;; rather than swallowed: a function that will not evaluate here simply
          ;; cannot be used from a macro, and the diagnostic says which.
          (when (and sym (not macro?) (not (:macro? (last (:items @cc)))))
            (let [defnode (-> ast :arities first :body)]
              (when (= :def (:op defnode))
                (try
                  (let [ctx {:vars (:eval-vars @cc)}
                        f0 (ev/eval-top ctx ast)]
                    (f0))
                  (catch Throwable e
                    (vswap! cc update :uncompiled-at-compile-time
                            (fnil conj []) [sym (ex-message e)]))))))
          (when sym
            (let [defnode (-> ast :arities first :body)]
              (when (= :def (:op defnode))
                (register-native-aliases! cc sym (:init defnode))))
            (vswap! cc assoc-in [:vars sym] true)))
        (catch Throwable e
          ;; Say WHERE. A bare "unable to resolve symbol" halfway through
          ;; clojure.core is close to useless without the enclosing form.
          (throw (ex-info (str (ex-message e)
                               "\n  in " nsname
                               (when-let [n (first (def-form-names f))] (str "/" n))
                               (when-let [l (:line (meta f))] (str " (line " l ")")))
                          (merge (ex-data e) {:ns nsname :form-name (first (def-form-names f))})))))))))

;; ------------------------------------------------------------------ pass 3

(defn reachable*
  "Transitive closure of `roots` over the reference graph, keeping the edge that
  first reached each node.

  The predecessor map is the whole point: `:exclude` has to be able to say
  `my.app/handler -> clojure.string/split -> flint.regex/pattern?` and not just
  \"flint.regex is reachable\". Recording it costs one map entry per var and is
  the difference between a message somebody can act on and one that sends them
  grepping.

  `via` may carry predecessors from an earlier pass; the reachability fixpoint
  runs several, and a node keeps the edge that reached it first."
  [cc roots via]
  (let [deps (:deps @cc)]
    (loop [seen #{} via via todo (vec roots)]
      (if-let [s (peek todo)]
        (if (seen s)
          (recur seen via (pop todo))
          (recur (conj seen s)
                 (reduce (fn [m d] (if (contains? m d) m (assoc m d s)))
                         via (get deps s))
                 (into (pop todo) (get deps s))))
        {:seen seen :via via}))))

(defn reachable
  "Transitive closure of `roots` over the reference graph."
  [cc roots]
  (:seen (reachable* cc roots {})))

(defn chain-to
  "The reference chain from a seed to `target`, as a vector of vars, following
  the predecessor edges back and then reversing."
  [via target]
  (loop [at target acc (list target) guard 0]
    (let [prev (get via at)]
      (if (or (nil? prev) (> guard 256))
        (vec acc)
        (recur prev (conj acc prev) (inc guard))))))

(defn describe-ref
  "How to name one step of a reference chain. Synthetic ids -- the ones bare
  top-level forms get -- read as what they are rather than as a var nobody
  wrote."
  [sym]
  (let [n (name sym)]
    (cond
      (= "flint.native" (namespace sym)) (str "the builtin `" n "`")
      (str/starts-with? n "__top-") (str "a top-level form in " (namespace sym))
      :else (str sym))))

(defn- synthetic? [sym] (str/starts-with? (name sym) "__top-"))

(defn best-chain
  "Of the excluded things that are reachable, the one whose chain explains the
  most. A chain that runs from the entry point is worth more than one that
  starts mid-graph, and a named var is worth more than the synthetic id a bare
  top-level form gets -- those are true but unactionable on their own."
  [via entry hits]
  (let [scored (for [h hits
                     :let [c (chain-to via h)]]
                 {:sym h :chain c
                  :score [(if (= entry (first c)) 2 (if (next c) 1 0))
                          (if (synthetic? h) 0 1)
                          (- (count c))]})]
    (first (sort-by :score (fn [a b] (compare b a)) scored))))

(defn exclusion-error
  "Message for an exclusion that turned out to be false. Prints the chain,
  because the whole value of the flag is telling somebody what to change."
  [ns-sym best others]
  (str "namespace " ns-sym " is excluded, but it is reachable.\n\n"
       "  " (describe-ref (:sym best)) " is reached by:\n"
       (str/join "\n" (map-indexed (fn [i s]
                                     (str "    " (if (zero? i) "   " "-> ") (describe-ref s)))
                                   (:chain best)))
       (when (seq others)
         (str "\n\n  also reachable in " ns-sym ": "
              (str/join ", " (map describe-ref (take 6 (sort others))))
              (when (> (count others) 6) (str " and " (- (count others) 6) " more"))))
       "\n\nEither stop reaching it, or drop it from :exclude."))

(defn check-exclusions!
  "`:exclude` is an ASSERTION, not a pruning: if excluded code is genuinely
  reachable that is a compile error here, rather than a module that links,
  ships and dies at runtime on a path nobody tested."
  [excluded roots via entry excluded-builtins]
  (doseq [ns-sym (sort excluded)]
    (let [prefix (str ns-sym)
          in-ns (filter (fn [s] (and (namespace s) (= prefix (namespace s)))) roots)
          ;; A builtin belongs to the unit that provides it, not to a namespace,
          ;; so those are matched separately and reported the same way.
          bnames (get excluded-builtins ns-sym #{})
          in-builtins (filter (fn [s] (and (= "flint.native" (namespace s))
                                           (contains? bnames (name s))))
                              roots)
          hits (concat in-builtins in-ns)]
      (when (seq hits)
        (let [best (best-chain via entry hits)
              others (remove #(or (= % (:sym best)) (synthetic? %)) hits)]
          (err (exclusion-error ns-sym best others) {:excluded ns-sym}))))))

(defn compile-image
  "Compile `sources` ({ns-symbol {:src s :file f}}) with entry var `entry-sym`.
  Returns {:builder b :stats {...}}."
  [{:keys [sources order entry builtins exclude excluded-builtins]}]
  (let [cc (new-context {:builtins builtins})]
    (let [read-forms (into {} (for [nsname order]
                                (let [{:keys [src file]} (get sources nsname)]
                                  (when-not src (err (str "no source for namespace " nsname) {:ns nsname}))
                                  [nsname (read-namespace! cc nsname src file)])))]
      (doseq [nsname order]
        (analyze-namespace! cc nsname (get read-forms nsname))))

    ;; The entry shim: convert the result to text here, in cljc, so the printer
    ;; is reachable only because this shim uses it -- not because the runtime
    ;; carries one.
    (let [shim-ns 'flint.main
          shim-src (str "(ns flint.main (:require [" (namespace entry) "]))\n"
                        "(defn -main [args]\n"
                        "  (let [r (" entry " args)]\n"
                        "    (if (string? r) r (pr-str r))))\n")]
      (analyze-namespace! cc shim-ns (read-namespace! cc shim-ns shim-src "<entry-shim>")))

    (let [entry-var 'flint.main/-main
          items (:items @cc)
          ;; Reachability is a fixpoint, not one pass. Two things make it so:
          ;; including a namespace brings in its bare top-level expressions, and
          ;; an item that defines several vars (`defmulti` defines the function
          ;; AND its method table) records its own references under a synthetic
          ;; id that has to be seeded once the item is known to be kept.
          state (loop [r0 (reachable* cc [entry-var] {})]
                  (let [rs (conj (:seen r0) entry-var)
                        reached (fn [it] (some (fn [d] (contains? rs d)) (:defines it)))
                        inc-ns (conj (into #{} (map :ns (filter reached items))) 'flint.main)
                        keeping (filter (fn [it]
                                          (and (not (:macro? it))
                                               (if (seq (:defines it))
                                                 (reached it)
                                                 (inc-ns (:ns it)))))
                                        items)
                        ;; Seed the item's id AND every var it defines: `def`
                        ;; records its init's references under its own name, so
                        ;; keeping an item because ONE of its vars was reached
                        ;; makes the others' references live too.
                        r1 (reachable* cc
                                       (into (vec rs)
                                             (mapcat (fn [it] (cons (:id it) (:defines it)))
                                                     keeping))
                                       (:via r0))]
                    (if (= (:seen r1) (:seen r0))
                      {:roots rs :included inc-ns :via (:via r1)}
                      (recur r1))))
          roots (:roots state)
          included-ns (:included state)
          via (:via state)
          _ (check-exclusions! (or exclude #{}) roots via entry-var (or excluded-builtins {}))
          reached? (fn [it] (some (fn [d] (contains? roots d)) (:defines it)))
          keep? (fn [it]
                  (cond
                    (:macro? it) false
                    (seq (:defines it)) (boolean (reached? it))
                    ;; A bare top-level expression rides on its namespace: it can
                    ;; have side effects nothing references, so there is nothing
                    ;; to reach it by. Documented in the README.
                    :else (contains? included-ns (:ns it))))
          kept (filterv keep? items)
          b (img/new-builder)
          ;; Slots for every var any kept item defines, not just its headline
          ;; name: one item can define several.
          var-slots (into {} (for [s (sort (distinct (mapcat :defines kept)))]
                               [s (img/var-slot b s)]))
          ;; Vars that are referenced but never defined would be a silent nil.
          missing (remove var-slots (filter #(get-in @cc [:vars %]) roots))
          ctx {:b b :var-slots var-slots :cc cc}]
      (doseq [it kept]
        (let [{:keys [fn-index]} (emit/emit-fn-object ctx (:ast it))]
          (img/add-init! b fn-index)))
      ;; The entry itself: a 1-arg closure over the shim var.
      (let [main-idx (get var-slots entry-var)]
        (when-not main-idx
          (err (str "entry shim was not emitted"
                    "\n  entry-var " entry-var
                    "\n  kept " (count kept) " of " (count items)
                    "\n  roots contains it: " (contains? roots entry-var)
                    "\n  var-slots keys: " (pr-str (vec (take 8 (keys var-slots))))
                    "\n  kept syms tail: " (pr-str (vec (take-last 6 (filter some? (map :sym kept)))))
                    "\n  sources: " (count sources) " order: " (count order)
                    "\n  order is: " (pr-str (vec order))
                    "\n  first source key: " (pr-str (first (keys sources)))
                    "\n  items in cc: " (count (:items @cc)))
               {:entry entry}))
        (let [buf (emit/new-buf)]
          (emit/emit ctx buf {:op :var :sym entry-var} false)
          (emit/emit ctx buf {:op :local :idx 0} false)
          (img/set-entry!
           b (img/add-fn b {:name 'main
                            :nupvals 0
                            :arities [{:argc 1 :variadic? false :nlocals 1
                                       :code (conj (vec (emit/finish buf))
                                                   (get emit/op :tail-call) 1)}]}))))
      {:builder b
       :via via
       :roots-set roots
       :kept-syms (vec (filter some? (map :sym kept)))
       :roots roots
       :items items
       :stats {:vars (count var-slots)
               :items-total (count items)
               :items-kept (count kept)
               :namespaces (count order)
               :missing (vec missing)}})))
