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
;; The codes live twice: `flint.types/code` and `type-p`. Drift between them
;; would not fail loudly -- it would check the WRONG type, quietly, at every
;; annotated binding.
;;
;; It used to scrape `builtins.rs`, then `vm.rs`, each of which carried its own
;; copy of the match. The copies drifted the moment a map entry became a
;; vector: the interpreter answered `vector?` one way and `check-tag` the
;; other, for the same value.
;;
;; It now reads `kin/typep.kin`, which is the ONE place the runtime's table is
;; written -- Rust, Java and C# are generated from it, and `bin/check-kin`
;; fails if any of the three has drifted from the source. So this compares the
;; compiler's table against the runtime's table, singular, rather than against
;; whichever copy it could find.
(let [cljc (slurp "src/flint/types.cljc")
      rs   (slurp "kin/typep.kin")
      from-cljc (into {} (for [[_ k v] (re-seq #":(\w+) (\d+)" (re-find #"\{:int 1[^}]+\}" cljc))]
                           [(keyword k) (parse-long v)]))
      ;; The `defn` line is load-bearing. Matching the bare name would find the
      ;; docstring above it too, so renaming the function would still "find" a
      ;; table and every check below would pass over the wrong text -- a
      ;; scraper that cannot fail to find its subject cannot fail at all.
      body (re-find #"(?s)\(defn \^:pub \^:method \^Bool type-p .*?\n\n" (str rs "\n\n"))
      ;; The predicate NAME is what gets compared. `is-vector-like` is matched
      ;; as `vector` -- the trailing `-like` is deliberate and is about a map
      ;; entry ALSO being a vector, not about a different type. `nil?` is the
      ;; one arm not spelled `is-`.
      ;; `(or body "")`, so a missing table fails as a CHECK rather than as a
      ;; NullPointerException from `re-seq`. A stack trace says the test broke;
      ;; a named failure says the runtime moved.
      from-rs (into {} (for [[_ n f] (re-seq #"\[(\d+)\] \((is-[a-z-]+|nil\?) rt v\)" (or body ""))]
                         [(parse-long n)
                          (-> f (str/replace #"^is-" "") (str/replace #"-like$" "")
                              (str/replace #"^nil\?$" "nil"))]))]
  (check-that "the cljc table was found and is not empty" (>= (count from-cljc) 14))
  ;; `type_p`'s last arm is `_ => is_sequential`, not `14 =>`, so 14 is not in
  ;; the scrape. It is asserted by name instead of being silently absent.
  (check-that "the rust table was found and is not empty"
              (and (some? body) (>= (count from-rs) 13)))
  (check-that "code 14 is sequential, from type-p's :else arm"
              (some? (re-find #":else \(is-sequential rt v\)" (or body ""))))
  (check "the two tables have the same number of codes"
         (inc (count from-rs)) (count from-cljc))
  (check "the code sets are identical"
         (sort (vals from-cljc)) (sort (conj (keys from-rs) 14)))
  ;; And they must be about the same types, not merely the same size.
  (let [names {:int "int" :float "double" :number "number" :string "string"
               :keyword "keyword" :symbol "symbol" :boolean "bool" :vector "vector"
               :map "map" :set "set" :seq "seq" :fn "fn" :nil "nil"
               :sequential "sequential"}
        bad (for [[k v] from-cljc
                  :when (not= v 14)
                  :let [rs-name (get from-rs v)]
                  :when (not= rs-name (get names k))]
              [k v rs-name])]
    (check "every code means the same type on both sides" (vec bad) [])))

;; --- the barrier, at each place a value can be written ---------------------

(let [b (sh "./bin/flint" ":src" "test" ":fn" "types/main" ":out" "out/types.wasm")]
  (when-not (zero? (:exit b))
    (println "build failed:" (:out b) (:err b)) (System/exit 1)))

(def r (let [x (sh "node" "host/flint.mjs" "out/types.wasm" "types/main")]
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

(println "types: what a malformed declaration gets told")

;; Every case here compiles fine if the compiler does not check, and then does
;; NOTHING -- the declaration is stored under a key nothing ever reads. That is
;; the worst outcome for an optimisation hint, because it is indistinguishable
;; from one that works: the program is still correct, only slower.
(defn refuses [fixture fragment]
  (let [x (sh "./bin/flint" ":src" "test/fixtures" ":fn" (str fixture "/main")
              ":out" "/tmp/flint-fixture.wasm")
        msg (str (:out x) (:err x))]
    (check-that (str fixture " is refused")
                (and (not (zero? (:exit x))) (str/includes? msg fragment)))))

(refuses "proj-key"     "only true and false are supported")
(refuses "proj-val"     "a projection is a METADATA MAP")
(refuses "proj-notag"   "the only key consumed today is :tag")
(refuses "proj-badtag"  "not a type flint knows")
(refuses "proj-any"     "no type stated")
(refuses "proj-noparam" "not a parameter of its 1-argument arity")
(refuses "proj-empty"   "is empty, so it declares nothing")
(refuses "inv-notsym"   "it names a PARAMETER, so it is a symbol")

(if (pos? @fails)
  (do (println "types:" @fails "failed") (System/exit 1))
  (println "types: ok"))
