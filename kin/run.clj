
;; The driver: read the source, honour its `ns` form, emit for every target.
(require '[clojure.string :as str])
(load-file "kin/kin.cljc")
(load-file "kin/core_vocab.cljc")
(load-file "kin/hash_vocab.cljc")
(load-file "kin/rt_vocab.cljc")

(def vocabs {'flint.impl.core {:forms (flint.impl.core/forms-for {})}
             'flint.impl.hash {:forms (flint.impl.hash/forms-for)
                               :tags flint.impl.hash/tags-for}
             'flint.impl.rt {:forms (flint.impl.rt/forms-for)
                             :tags flint.impl.rt/tags-for
                             :names flint.impl.rt/names-for}})

(def which (or (first *command-line-args*) "kin/hash.kin"))
(def src (slurp which))
(def all (read-string (str "[" src "]")))
(def ns-form (first (filter (fn [f] (and (seq? f) (= 'ns (first f)))) all)))
(def forms (vec (remove (fn [f] (and (seq? f) (= 'ns (first f)))) all)))
(def scope (if ns-form (flint.kin/require-scope ns-form vocabs) nil))

(doseq [target [:rust :java :csharp]]
  (let [ctx (assoc (flint.kin/context {} target)
                   :vocabs vocabs
                   :scope-syms scope
                   ;; What this FILE has declared, fresh per target -- a
                   ;; declaration carries the target's spelling of the name.
                   :locals (atom {})
                   :names (atom {})
                   :tmp (atom 0))]
    (println (str "==== " (name target) " ===================================="))
    (doseq [f forms] (flint.kin/kin-statement! ctx f))
    (print (flint.kin/kin-output ctx))
    (println)))
