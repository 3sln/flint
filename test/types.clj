;; Checked type annotations, and the barrier that makes them sound.
;;
;; A tag here is not a hint. The check goes at the WRITE -- every binding form,
;; and every `recur` that writes the slot again -- so that reads are free and
;; the claim can be relied on by whatever compiles the code that follows. The
;; failure this file is built around is an annotation that is merely RECORDED:
;; it gives the right answer on the first iteration and a wrong one afterwards.
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

(println "types: annotations are checked claims, not hints")

;; --- the two tables must agree ---------------------------------------------
;;
;; The codes live twice: `flint.types/code` and the match in
;; `runtime/src/builtins.rs`. Drift between them would not fail loudly -- it
;; would check the WRONG type, quietly, at every annotated binding.
(let [cljc (slurp "src/flint/types.cljc")
      rs   (slurp "runtime/src/builtins.rs")
      from-cljc (into {} (for [[_ k v] (re-seq #":(\w+) (\d+)" (re-find #"\{:int 1[^}]+\}" cljc))]
                           [(keyword k) (parse-long v)]))
      body (re-find #"(?s)let ok = match code \{.*?\n        \};" rs)
      ;; `rt.is_int(v)` and `v.is_nil()` both appear, so the receiver is skipped
      ;; and the predicate NAME is what gets compared.
      from-rs (into {} (for [[_ n f] (re-seq #"(\d+) => \w+\.is_(\w+)\(" body)]
                         [(parse-long n) f]))]
  (check-that "the cljc table was found and is not empty" (>= (count from-cljc) 14))
  (check-that "the rust table was found and is not empty" (>= (count from-rs) 14))
  (check "the two tables have the same number of codes" (count from-rs) (count from-cljc))
  (check "the code sets are identical"
         (sort (vals from-cljc)) (sort (keys from-rs)))
  ;; And they must be about the same types, not merely the same size.
  (let [names {:int "int" :float "float" :number "number" :string "string"
               :keyword "keyword" :symbol "symbol" :boolean "bool" :vector "vector"
               :map "map" :set "set" :seq "seq" :fn "fn" :nil "nil"
               :sequential "sequential"}
        bad (for [[k v] from-cljc
                  :let [rs-name (get from-rs v)]
                  :when (not= rs-name (get names k))]
              [k v rs-name])]
    (check "every code means the same type on both sides" (vec bad) [])))

;; --- the barrier, at each place a value can be written ---------------------

(let [b (sh "./bin/flint" ":src" "test" ":fn" "types/main" ":out" "out/types.wasm")]
  (when-not (zero? (:exit b))
    (println "build failed:" (:out b) (:err b)) (System/exit 1)))

(def r (let [x (sh "node" "host/flint.mjs" "out/types.wasm")]
         (when-not (zero? (:exit x))
           (println "  FAIL the module trapped:" (str/trim (:err x))) (System/exit 1))
         (edn/read-string (str/trim (:out x)))))

(check "a let binding passes a value of the declared type" (:let-ok r) 42)
(check "a fn parameter passes"                             (:param-ok r) 42)
(check "a declared return passes"                          (:ret-ok r) 7)
(check "a loop initialiser passes"                         (:loop-init r) 3)
(check "a use-site annotation passes"                      (:use-site r) 3)
(check "int satisfies ^number"                             (:widen-int r) 3)
(check "float satisfies ^number"                           (:widen-float r) 1.5)
(check "the two measurement loops agree"                   (:loops-agree r) true)

(defn refused [label got want]
  (check-that label (and (string? got) (str/starts-with? got "!")
                         (str/includes? got (str "declared ^" want)))))

(refused "a let binding refuses the wrong type"  (:let-bad r) "int")
(refused "a fn parameter refuses it"             (:param-bad r) "int")
(refused "a declared return refuses it"          (:ret-bad r) "int")
(refused "a loop INITIALISER refuses it"         (:loop-init-bad r) "int")
;; The one that matters most: the first iteration is fine and the second is not.
(refused "and so does a RECUR, on the second iteration" (:loop-recur-bad r) "int")
(refused "a use-site annotation refuses it"      (:use-site-bad r) "int")
(refused "^number refuses a string"              (:widen-bad r) "number")

(check-that "the error names the binding, not just the type"
            (str/starts-with? (:let-bad r) "!n is declared"))
(check-that "a return error names the function"
            (str/includes? (:ret-bad r) "ret-ok's return"))
(check-that "a use-site error names the argument that was wrong"
            (str/starts-with? (:use-site-bad r) "!y is declared"))

(if (pos? @fails)
  (do (println "types:" @fails "failed") (System/exit 1))
  (println "types: ok"))
