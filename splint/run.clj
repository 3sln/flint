;; The v2 driver.
(require '[clojure.string :as str])
(load-file "splint/splint.cljc")
(load-file "splint/vm_vocab.cljc")

(def which (or (first *command-line-args*) "splint/spread.splint"))
(def src (slurp which))
;; Skip the `ns` form: what it requires is the vocabulary, which this spike
;; wires directly. Resolving it for real is one of the open questions.
(def forms (let [all (read-string (str "[" src "]"))]
             (vec (remove (fn [f] (and (seq? f) (= 'ns (first f)))) all))))

(doseq [target [:rust :java :csharp]]
  (let [ctx (assoc (flint.splint/context {} target)
                   :vocab (flint.impl.vm/forms-for)
                   :tmp (atom 0))]
    (println (str "==== " (name target) " ===================================="))
    (doseq [f forms] (flint.splint/splint-statement! ctx f))
    (print (flint.splint/splint-output ctx))
    (println)))
