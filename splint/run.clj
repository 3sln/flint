;; The spike's driver: read the DSL, read the rules, emit for every target.
;;
;; babashka for now. If this proves out it belongs in the flint CLI, and the
;; translator is already `.cljc` so it can move without being rewritten.
(require '[clojure.string :as str] '[clojure.edn :as edn])
(load-file "splint/splint.cljc")

(def src (slurp "splint/spike/spread.splint"))
(doseq [t ["rust" "jvm" "clr"]]
  (let [rules (edn/read-string (slurp (str "splint/rules/" t ".edn")))]
    (println (str "==== " t " ===================================="))
    (println (splint/translate src rules))
    (println)))
