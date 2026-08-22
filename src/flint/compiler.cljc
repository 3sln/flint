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

(defn read-namespace!
  "Read one namespace and register every name it defines, so that forward
  references -- within a namespace and between namespaces -- resolve without
  `declare`. Clojure needs `declare` for the intra-namespace case; reading
  everything before analysing anything makes it unnecessary."
  [cc nsname src file]
  (let [st (reader/reader src {:file file :features #{:clj :flint}})
        _ (vswap! cc assoc-in [:namespaces nsname] (get-in @cc [:namespaces nsname] {}))
        forms (loop [acc []]
                (let [f (reader/read-form st)]
                  (if (= f :flint.reader/eof)
                    acc
                    (do (when (ns-form? f)
                          (reader/set-ns! st (second f) (ns-aliases f)))
                        (recur (conj acc f))))))]
    (doseq [f forms, n (def-form-names f)]
      (vswap! cc assoc-in [:declared (symbol (str nsname) (name n))] true))
    forms))

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
        (let [names (def-form-names f)
              sym (when (= 1 (count names)) (symbol (str nsname) (name (first names))))
              env (assoc (base-env cc nsname) :current-var sym)
              wrapped (list 'fn* [] f)
              ast (ana/analyze env wrapped)
              macro? (boolean (some-> (first names) meta :macro))
              defform (when (seq? f) (first f))]
          (vswap! cc update :items conj
                  {:ns nsname :sym sym :ast ast
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
                  (catch #?(:clj Exception :cljs :default) e
                    (vswap! cc update :uncompiled-at-compile-time
                            (fnil conj []) [sym (ex-message e)]))))))
          (when sym
            (let [defnode (-> ast :arities first :body)]
              (when (= :def (:op defnode))
                (register-native-aliases! cc sym (:init defnode))))
            (vswap! cc assoc-in [:vars sym] true)))))))

;; ------------------------------------------------------------------ pass 3

(defn reachable
  "Transitive closure of `roots` over the reference graph."
  [cc roots]
  (let [deps (:deps @cc)]
    (loop [seen #{} todo (vec roots)]
      (if-let [s (peek todo)]
        (if (seen s)
          (recur seen (pop todo))
          (recur (conj seen s) (into (pop todo) (get deps s))))
        seen))))

(defn compile-image
  "Compile `sources` ({ns-symbol {:src s :file f}}) with entry var `entry-sym`.
  Returns {:builder b :stats {...}}."
  [{:keys [sources order entry builtins]}]
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
          roots (conj (reachable cc [entry-var]) entry-var)
          items (:items @cc)
          included-ns (into #{} (map :ns (filter #(and (:sym %) (roots (:sym %))) items)))
          included-ns (conj included-ns 'flint.main)
          keep? (fn [it]
                  (cond
                    (:macro? it) false
                    (:sym it) (contains? roots (:sym it))
                    ;; A bare top-level expression rides on its namespace: it can
                    ;; have side effects nothing references, so there is nothing
                    ;; to reach it by. Documented in the README.
                    :else (contains? included-ns (:ns it))))
          kept (filterv keep? items)
          b (img/new-builder)
          var-slots (into {} (for [s (sort (filter symbol? (map :sym kept)))]
                               [s (img/var-slot b s)]))
          ;; Vars that are referenced but never defined would be a silent nil.
          missing (remove var-slots (filter #(get-in @cc [:vars %]) roots))
          ctx {:b b :var-slots var-slots :cc cc}]
      (doseq [it kept]
        (let [{:keys [fn-index]} (emit/emit-fn-object ctx (:ast it))]
          (img/add-init! b fn-index)))
      ;; The entry itself: a 1-arg closure over the shim var.
      (let [main-idx (get var-slots entry-var)]
        (when-not main-idx (err "entry shim was not emitted" {:entry entry}))
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
       :stats {:vars (count var-slots)
               :items-total (count items)
               :items-kept (count kept)
               :namespaces (count order)
               :missing (vec missing)}})))
