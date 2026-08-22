(ns flint.eval
  "An interpreter for the analyzer's AST.

  This is what makes user `defmacro` work. A macro has to RUN at compile time,
  and the compiler has to produce the same expansion whether it is hosted on
  babashka or on flint itself. Evaluating our own AST -- rather than handing the
  form to the host's `eval` -- is what makes that true: the same analyzer, the
  same special forms, the same resolution rules, on every host.

  It also means the bootstrap needs no second compiler. `doc/decisions` warned
  that a seed pass in Rust would be the implementation that drifts; this is how
  we avoid needing one."
  (:require [flint.hostfns :as hostfns]
            [flint.rt]))

(def ^:private RECUR ::recur)

(declare ev)

(defn- ev-body [ctx locals upvals body]
  (loop [[x & more] body r nil]
    (if (nil? x) r (recur more (ev ctx locals upvals x)))))

(defn- make-fn [ctx upvals-vals node]
  (let [arities (:arities node)
        selfref (volatile! nil)
        ctx (assoc ctx :self-ref selfref)
        f (fn [& args]
      (let [n (count args)
            arity (or (first (filter #(and (not (:variadic? %)) (= n (:argc %))) arities))
                      (first (filter #(and (:variadic? %) (>= n (:argc %))) arities))
                      (throw (ex-info "wrong number of arguments to a macro function"
                                      {:given n :type :compile})))
            slots (:slots arity)
            size (max 8 (inc (reduce max 0 slots)) (or (:max-locals arity) 0))
            ;; A volatile holding a vector, not a host array: the evaluator has
            ;; to run on flint too, where there are no Java arrays.
            locals (volatile! (vec (repeat size nil)))
            fixed (:argc arity)
            argv (vec args)]
        (dotimes [i fixed] (vswap! locals assoc (nth slots i) (nth argv i)))
        (when (:variadic? arity)
          (vswap! locals assoc (nth slots fixed) (seq (drop fixed args))))
        (loop []
          (let [r (ev ctx locals upvals-vals (:body arity))]
            (if (and (map? r) (= RECUR (:flint.eval/tag r)))
              (do (doseq [pair (map vector (:slots r) (:vals r))]
                    (vswap! locals assoc (first pair) (second pair)))
                  (recur))
              r)))))]
    (vreset! selfref f)
    f))

(defn ev
  "Evaluate `node`. `locals` is a mutable object array indexed by slot."
  [ctx locals upvals node]
  (case (:op node)
    :const (:val node)
    :local (nth @locals (:idx node))
    :upval (nth upvals (:idx node))
    :self @(:self-ref ctx)
    :var (let [v (get @(:vars ctx) (:sym node) ::missing)]
           (if (= ::missing v)
             (throw (ex-info (str "var not defined at compile time: " (:sym node))
                             {:sym (:sym node) :type :compile}))
             v))
    :the-var (get @(:vars ctx) (:sym node))
    :def (let [v (when (:init node) (ev ctx locals upvals (:init node)))]
           (swap! (:vars ctx) assoc (:sym node) v)
           nil)
    :if (if (let [t (ev ctx locals upvals (:test node))] (and (some? t) (not= false t)))
          (ev ctx locals upvals (:then node))
          (ev ctx locals upvals (:else node)))
    :do (ev-body ctx locals upvals (:body node))
    :let (do (doseq [b (:bindings node)]
               (vswap! locals assoc (:idx b) (ev ctx locals upvals (:init b))))
             (ev ctx locals upvals (:body node)))
    :loop (do (doseq [b (:bindings node)]
                (vswap! locals assoc (:idx b) (ev ctx locals upvals (:init b))))
              (loop []
                (let [r (ev ctx locals upvals (:body node))]
                  (if (and (map? r) (= RECUR (:flint.eval/tag r)) (= (:id r) (:id node)))
                    (do (doseq [pair (map vector (:slots r) (:vals r))]
                          (vswap! locals assoc (first pair) (second pair)))
                        (recur))
                    r))))
    :recur {:flint.eval/tag RECUR :id (:id node) :slots (:slots node)
            :vals (mapv #(ev ctx locals upvals %) (:args node))}
    :fn (let [captured (mapv (fn [u] (case (:kind u)
                                       :local (nth @locals (:idx u))
                                       :upval (nth upvals (:idx u))
                                       :self @(:self-ref ctx)))
                             (:upvals node))]
          (make-fn ctx captured node))
    :invoke (let [f (ev ctx locals upvals (:fn node))
                  args (mapv #(ev ctx locals upvals %) (:args node))]
              (apply f args))
    :native-value (hostfns/lookup (:name node))
    :native (let [f (hostfns/lookup (:name node))
                  args (mapv #(ev ctx locals upvals %) (:args node))]
              (apply f args))
    :throw (throw (ev ctx locals upvals (:expr node)))
    :try (try
           (ev ctx locals upvals (:body node))
           (catch Throwable e
             (if-let [c (first (:catches node))]
               (do (vswap! locals assoc (:idx c) e)
                   (ev ctx locals upvals (:body c)))
               (throw e)))
           (finally (when (:finally node) (ev ctx locals upvals (:finally node)))))
    :vector (mapv #(ev ctx locals upvals %) (:items node))
    :set (into #{} (map #(ev ctx locals upvals %) (:items node)))
    :map (into {} (map (fn [[k v]] [(ev ctx locals upvals k) (ev ctx locals upvals v)])
                       (:pairs node)))
    (throw (ex-info "cannot evaluate node" {:node node :type :compile}))))

(defn eval-top
  "Evaluate a top-level AST node in `ctx` ({:vars atom})."
  [ctx node]
  (ev ctx (volatile! (vec (repeat 64 nil))) [] node))
