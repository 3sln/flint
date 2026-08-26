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

(println "types: occurrence narrowing -- the test IS the annotation")

(check "a builtin predicate narrows its argument"     (:user-narrowed r) 42)
(check "... and refuses when the guard fails"         (:user-refuses r) :nope)
(check "`and` narrows through the let it expands to"  (:and-narrowed r) 5)
(check "... and the second conjunct is really tested" (:and-refuses r) :nope)
(check "a user :result-projected-meta works the same" (:user-narrowed r) 42)

;; Soundness. Narrowing that leaked into the else branch would add 1 to a
;; string, silently, which is the only way this feature can be dangerous.
(check "narrowing reaches the then branch"      (:narrow-else-yes r) :yes)
(check-that "and NOT the else branch, which still checks"
            (str/includes? (str (:narrow-else-throws r)) "declared ^int"))
;; A projection recorded on a loop binding would be a claim about a value that
;; `recur` has already replaced.
(check "a projection does not survive a recur"  (:narrow-rebound-1 r) :not-int)
(check "and the un-recurred case still narrows" (:narrow-rebound-0 r) 2)

(check "`or` narrows when both arms agree"      (:or-agrees r) 42)
(check "`or` over an int still works"           (:or-disagrees-int r) 2)
(check-that "`or` over DISAGREEING arms proves nothing, so the check stays"
            (str/includes? (str (:or-disagrees-str r)) "declared ^int"))

(if (pos? @fails)
  (do (println "types:" @fails "failed") (System/exit 1))
  (println "types: ok"))
