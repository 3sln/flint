
;; The driver: read the source, honour its `ns` form, emit for every target.
(require '[clojure.string :as str])
(load-file "splint/splint.cljc")
(load-file "splint/vm_vocab.cljc")
(load-file "splint/codec_vocab.cljc")

(def vocabs {'flint.impl.vm {:forms (flint.impl.vm/forms-for)
                             :tags flint.impl.vm/tags-for}
             'flint.impl.codec {:forms (flint.impl.codec/forms-for)
                                :tags flint.impl.codec/tags-for}})

(def which (or (first *command-line-args*) "splint/spread.splint"))
(def src (slurp which))
(def all (read-string (str "[" src "]")))
(def ns-form (first (filter (fn [f] (and (seq? f) (= 'ns (first f)))) all)))
(def forms (vec (remove (fn [f] (and (seq? f) (= 'ns (first f)))) all)))
(def scope (if ns-form (flint.splint/require-scope ns-form vocabs) nil))

(doseq [target [:rust :java :csharp]]
  (let [ctx (assoc (flint.splint/context {} target)
                   :vocabs vocabs
                   :scope-syms scope
                   :vocab (flint.impl.vm/forms-for)
                   :tmp (atom 0))]
    (println (str "==== " (name target) " ===================================="))
    (doseq [f forms] (flint.splint/splint-statement! ctx f))
    (print (flint.splint/splint-output ctx))
    (println)))
