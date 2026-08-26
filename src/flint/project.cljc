(ns flint.project
  "Reading a program from its entry namespace outwards.

  This used to live in `bin/flint`, which is babashka, which meant the compiler
  compiled to wasm could not find its own sources -- a caller had to resolve
  every `:require` first and hand over a finished map. That is most of what a
  compiler does before it compiles anything, so it belongs here, where both the
  babashka front end and the wasm one can use it.

  Source access is a FUNCTION, not a directory list. The babashka front end
  passes one backed by the filesystem; a host driving `flintc.wasm` passes one
  backed by a map it already has. Neither of them is the compiler's business."
  (:require [flint.reader :as reader]
            [flint.compiler :as compiler]))

(def virtual-namespaces
  "Namespaces with no source: the compiler answers for them itself."
  '#{flint.rt})

(defn ns->path
  "The path a namespace's source would be at, without an extension."
  [n]
  (let [s (str n)
        out (loop [i 0 acc []]
              (if (= i (count s))
                acc
                (let [c (subs s i (inc i))]
                  (recur (inc i) (conj acc (cond (= c "-") "_" (= c ".") "/" :else c))))))]
    (apply str out)))

(defn- ns-form [forms]
  (first (filter (fn [f] (and (seq? f) (= 'ns (first f)))) forms)))

(defn collect
  "Read from `roots` outwards. `find-source` takes a namespace symbol and
  returns `{:src .. :file ..}` or nil.

  Returns `{:sources {ns {:src :file :forms}} :order [..] :missing [..]}`.
  A namespace with no source is REPORTED rather than thrown on, because the
  caller knows better than this does whether that is fatal -- a front end says
  so and stops, a tool listing dependencies keeps going."
  [find-source roots features]
  (loop [todo (vec roots) sources {} order [] missing []]
    (if (seq todo)
      (let [n (first todo)]
        (cond
          (or (contains? sources n) (contains? virtual-namespaces n))
          (recur (vec (rest todo)) sources order missing)

          :else
          (if-let [s (find-source n)]
            (let [forms (reader/read-all (:src s) {:file (:file s) :features features})
                  reqs (compiler/ns-requires (or (ns-form forms) '(ns x)))]
              (recur (into (vec (rest todo)) reqs)
                     (assoc sources n {:src (:src s) :file (:file s) :forms forms})
                     (conj order n)
                     missing))
            (recur (vec (rest todo)) sources order (conj missing n)))))
      {:sources sources :order order :missing missing})))

(defn topo-order
  "Dependencies before dependents. A cycle does not stop the build -- it picks
  one and carries on -- because Clojure allows mutual reference through vars
  and refusing here would refuse programs that work."
  [sources]
  (let [deps (into {} (for [[n {:keys [forms]}] sources]
                        [n (set (compiler/ns-requires (or (ns-form forms) '(ns x))))]))]
    (loop [done [] seen #{} pending (vec (keys deps))]
      (if (empty? pending)
        done
        (let [ready (filterv (fn [n] (every? (fn [d] (or (contains? seen d)
                                                         (not (contains? deps d))))
                                             (get deps n)))
                             pending)
              ready (if (seq ready) ready [(first pending)])
              rs (set ready)]
          (recur (into done ready) (into seen ready)
                 (vec (remove (fn [x] (contains? rs x)) pending))))))))

(defn core-first
  "`clojure.core` is referred by every namespace, so it is analysed first
  whatever the require graph says."
  [order]
  (cons 'clojure.core (remove (fn [n] (= 'clojure.core n)) order)))

(defn resolve-project
  "Everything a compile needs, from an entry and a way to find source.
  Returns `{:sources .. :order .. :missing ..}` with the order already
  topological and core-first."
  [find-source entry-ns features]
  ;; `clojure.core` is a root, not something the graph reaches: every namespace
  ;; refers it implicitly and almost none of them `:require` it, so starting
  ;; only from the entry collects a program whose `str` resolves to nothing.
  (let [{:keys [sources order missing]}
        (collect find-source ['clojure.core entry-ns] features)
        _ order]
    {:sources sources
     :order (vec (core-first (topo-order sources)))
     :missing missing}))
