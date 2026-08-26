;; `:inline` function metadata -- Clojure's, applied at the call site.
;;
;; The trap this file is built around: an inline that never fires produces
;; exactly the right answer. So every check below is written so that inlining
;; and not inlining give DIFFERENT answers, and the size of the effect is
;; measured separately in `test/inline.mjs` on the deterministic instruction
;; count rather than on a timing.
(require '[clojure.string :as str] '[clojure.edn :as edn])

(defn sh [& args]
  (let [p (.start (ProcessBuilder. (into-array String args)))
        out (slurp (.getInputStream p)) err (slurp (.getErrorStream p))]
    (.waitFor p) {:exit (.exitValue p) :out out :err err}))

(def fails (atom 0))
(defn check [label actual expected]
  (if (= actual expected)
    (println "  ok  " label)
    (do (swap! fails inc)
        (println "  FAIL" label "\n        expected" (pr-str expected)
                 "\n        got     " (pr-str actual)))))
(defn check-that [label ok] (check label (boolean ok) true))

(println ":inline -- the expansion, and what it is allowed to do")

(let [b (sh "./bin/flint" ":src" "test" ":fn" "inline/main" ":out" "out/inline.wasm")]
  (when-not (zero? (:exit b))
    (println "build failed:" (:out b) (:err b)) (System/exit 1)))

(def r (let [x (sh "node" "host/flint.mjs" "out/inline.wasm")]
         (when-not (zero? (:exit x))
           (println "  FAIL the module trapped:" (str/trim (:err x))) (System/exit 1))
         (edn/read-string (str/trim (:out x)))))

;; The inline says something the function does not, so this distinguishes the
;; two paths. Every other check in this file would pass without the feature.
(check "a call site takes the :inline, not the function" (:tattle r) :inlined)
(check "the same var as a VALUE is the function" (:as-value r) :called)
(check "and passed to a higher-order fn it is the function" (:map-value r) [:called :called])

(check ":inline-arities gates it: 2 is inlined" (:gated-2 r) :inline-2)
(check "... and 3 is not, so no argument is silently dropped" (:gated-3 r) :fn-3)

(check "an inlined call still computes what it should" (:add2-2 r) 7)
(check "an un-inlined arity of the same var still works" (:add2-3 r) 12)
(check "syntax quote in an :inline body" (:square r) 49)
(check "an inlined call inside an inlined call" (:nested r) 10)
(check "a local shadowing the var wins over the :inline" (:shadowed r) 11)
(check "both loops in the measurement agree" (:loops-agree r) true)

;; Recorded rather than discovered: the expander receives argument FORMS, so an
;; expansion that mentions one twice evaluates it twice. Clojure has this too.
(check "an :inline that uses its argument twice evaluates it twice"
       (:twice-effect r) [10 2])

(println ":inline -- what the compiler refuses")

(let [x (sh "./bin/flint" ":src" "test/fixtures" ":fn" "inline-loop/main"
            ":out" "out/il.wasm")]
  (check-that "an :inline that re-emits its own name is named, not a stack overflow"
              (and (not (zero? (:exit x)))
                   (str/includes? (str (:out x) (:err x)) "does not terminate")))
  (check-that "... and the message says which var"
              (str/includes? (str (:out x) (:err x)) "inline-loop/spin")))

(let [x (sh "./bin/flint" ":src" "test/fixtures" ":fn" "inline-bad/main"
            ":out" "out/ib.wasm")]
  (check-that "an :inline that is not a function is refused at compile time"
              (and (not (zero? (:exit x)))
                   (str/includes? (str (:out x) (:err x)) "is not a function"))))

(if (pos? @fails)
  (do (println "inline:" @fails "failed") (System/exit 1))
  (println "inline: ok"))
