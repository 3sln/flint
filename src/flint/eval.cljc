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
  (:require [flint.hostfns :as hostfns]))

(def ^:private RECUR ::recur)

(declare ev)

(defn- ev-body [ctx locals upvals body]
  (loop [[x & more] body r nil]
    (if (nil? x) r (recur more (ev ctx locals upvals x)))))

(defn- make-fn [ctx upvals-vals node]
  (let [arities (:arities node)]
    (fn [& args]
      (let [n (count args)
            arity (or (first (filter #(and (not (:variadic? %)) (= n (:argc %))) arities))
                      (first (filter #(and (:variadic? %) (>= n (:argc %))) arities))
                      (throw (ex-info "wrong number of arguments to a macro function"
                                      {:given n :type :compile})))
            slots (:slots arity)
            locals (object-array (max 8 (inc (apply max 0 slots)) (:max-locals arity 0)))
            fixed (:argc arity)]
        (dotimes [i fixed] (aset locals (nth slots i) (nth (vec args) i)))
        (when (:variadic? arity)
          (aset locals (nth slots fixed) (seq (drop fixed args))))
        (loop []
          (let [r (ev ctx locals upvals-vals (:body arity))]
            (if (and (map? r) (= RECUR (:flint.eval/tag r)))
              (do (doseq [[s v] (map vector (:slots r) (:vals r))] (aset locals s v))
                  (recur))
              r)))))))

(defn ev
  "Evaluate `node`. `locals` is a mutable object array indexed by slot."
  [ctx locals upvals node]
  (case (:op node)
    :const (:val node)
    :local (aget ^objects locals (:idx node))
    :upval (nth upvals (:idx node))
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
    :let (do (doseq [{:keys [idx init]} (:bindings node)]
               (aset ^objects locals idx (ev ctx locals upvals init)))
             (ev ctx locals upvals (:body node)))
    :loop (do (doseq [{:keys [idx init]} (:bindings node)]
                (aset ^objects locals idx (ev ctx locals upvals init)))
              (loop []
                (let [r (ev ctx locals upvals (:body node))]
                  (if (and (map? r) (= RECUR (:flint.eval/tag r)) (= (:id r) (:id node)))
                    (do (doseq [[s v] (map vector (:slots r) (:vals r))]
                          (aset ^objects locals s v))
                        (recur))
                    r))))
    :recur {:flint.eval/tag RECUR :id (:id node) :slots (:slots node)
            :vals (mapv #(ev ctx locals upvals %) (:args node))}
    :fn (let [captured (mapv (fn [u] (if (= :local (:kind u))
                                       (aget ^objects locals (:idx u))
                                       (nth upvals (:idx u))))
                             (:upvals node))]
          (make-fn ctx captured node))
    :invoke (let [f (ev ctx locals upvals (:fn node))
                  args (mapv #(ev ctx locals upvals %) (:args node))]
              (apply f args))
    :native-value (hostfns/lookup (:name node))
    :native (let [f (hostfns/lookup (:name node))
                  args (mapv #(ev ctx locals upvals %) (:args node))]
              (apply f args))
    :throw (throw (let [v (ev ctx locals upvals (:expr node))]
                    (if (instance? Throwable v)
                      v
                      (ex-info "thrown" {:value v}))))
    :try (try
           (ev ctx locals upvals (:body node))
           (catch Exception e
             (if-let [c (first (:catches node))]
               (do (aset ^objects locals (:idx c) e)
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
  (ev ctx (object-array 64) [] node))
