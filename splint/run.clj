
;; The driver: read the source, honour its `ns` form, emit for every target.
(require '[clojure.string :as str])
(load-file "splint/splint.cljc")
(load-file "splint/core_vocab.cljc")
(load-file "splint/vm_vocab.cljc")
(load-file "splint/codec_vocab.cljc")
(load-file "splint/hash_vocab.cljc")
(load-file "splint/rt_vocab.cljc")

(def vocabs {'flint.impl.vm {:forms (flint.impl.vm/forms-for)
                             :tags flint.impl.vm/tags-for}
             'flint.impl.codec {:forms (flint.impl.codec/forms-for)
                                :tags flint.impl.codec/tags-for}
             'flint.impl.hash {:forms (flint.impl.hash/forms-for)
                               :tags flint.impl.hash/tags-for}
             'flint.impl.rt {:forms (flint.impl.rt/forms-for)
                             :tags flint.impl.rt/tags-for
                             :names flint.impl.rt/names-for}})

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
                   ;; What this FILE has declared, fresh per target -- a
                   ;; declaration carries the target's spelling of the name.
                   :locals (atom {})
                   :names (atom {})
                   :tmp (atom 0))]
    (println (str "==== " (name target) " ===================================="))
    (doseq [f forms] (flint.splint/splint-statement! ctx f))
    (print (flint.splint/splint-output ctx))
    (println)))
