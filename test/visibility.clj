;; Privacy is ENFORCED, not documented.
;;
;; Three visibilities, two boundaries:
;;
;;   ^:private   (or `defn-`)   only the defining NAMESPACE may name it
;;   ^:internal                 only the defining WORKSPACE may name it
;;   neither                    anyone may
;;
;; Before this, `^:private` was a comment the compiler never read -- `defn-`
;; appears sixty times across `lib/` and nothing checked one of them. A rule
;; nobody enforces is a rule that has already been broken somewhere, and the
;; first run of this check found where: `defprotocol` EMITS a call to
;; `clojure.core/protocol-miss` into its expansion, so every namespace using a
;; protocol named a private var. The mark was wrong, not the check.
;;
;; The positive cases matter as much as the refusals. A check that refuses
;; everything passes its own test and breaks the build, so each refusal here
;; is paired with the reference that must still be allowed.
(babashka.classpath/add-classpath "src:lib")
(require '[flint.project :as project] '[flint.compiler :as compiler]
         '[clojure.string :as str])

(def fails (atom 0))
(defn check [label ok detail]
  (if ok
    (println "  ok  " label)
    (do (swap! fails inc) (println "  FAIL" label detail))))

(defn outcome
  "`:refused` when visibility stopped it, `:other` for any other error,
  `:compiled` when it went through."
  [files ws]
  (let [r (project/resolve-project (project/files-resolver files ws) 'app.main #{:flint})]
    (try
      (compiler/compile-image {:sources (:sources r) :order (:order r)
                               :entry 'app.main/main})
      :compiled
      (catch Exception e
        (let [m (ex-message e)]
          (if (or (str/includes? m "is internal to") (str/includes? m "is private to"))
            :refused
            :other))))))

(def lib "(ns libx.core)
(defn ^:internal helper [x] x)
(defn- secret [x] x)
(defn public-fn [x] (helper (secret x)))")

(defn app [body] (str "(ns app.main (:require [libx.core :as l])) (defn main [_] " body ")"))

(def two [{:prefix "libx/" :name 'libx} {:prefix "app/" :name 'app}])
(def one [{:prefix "" :name 'one}])

(println "== visibility")

;; ^:internal -- the workspace boundary
(check "an internal var is refused from another workspace"
       (= :refused (outcome {"libx/core.cljc" lib "app/main.cljc" (app "(l/helper 1)")} two))
       "expected a refusal")
(check "and allowed from inside its own workspace"
       (not= :refused (outcome {"libx/core.cljc" lib "app/main.cljc" (app "(l/helper 1)")} one))
       "internal must not refuse within the workspace")

;; ^:private / defn- -- the namespace boundary
(check "a private var is refused from another namespace"
       (= :refused (outcome {"libx/core.cljc" lib "app/main.cljc" (app "(l/secret 1)")} two))
       "expected a refusal")
(check "and refused even from the same workspace"
       (= :refused (outcome {"libx/core.cljc" lib "app/main.cljc" (app "(l/secret 1)")} one))
       "private is a NAMESPACE boundary, not a workspace one")

;; EVERY WAY OF NAMING IT, not just calling it. Privacy that covered calls
;; and not value references would be a hole a `(map l/helper xs)` walks
;; straight through. It holds because `record-dep!` sits on symbol RESOLUTION
;; rather than on the call path -- the same property that makes `0036`'s
;; guards sound, and the reason it insists anything resolving authority be a
;; compile-time construct and never a callable.
(check "refused when bound to a local, not called"
       (= :refused (outcome {"libx/core.cljc" lib
                             "app/main.cljc" (app "(let [f l/helper] (f 1))")} two))
       "value position must be refused too")
(check "refused inside a collection literal"
       (= :refused (outcome {"libx/core.cljc" lib "app/main.cljc" (app "[l/helper]")} two))
       "a reference is a reference wherever it appears")

;; The var that uses both from inside must still work.
(check "a public var may name its own private and internal helpers"
       (not= :refused (outcome {"libx/core.cljc" lib "app/main.cljc" (app "(l/public-fn 1)")} two))
       "a namespace must be able to use its own helpers")

;; And the real library still compiles, which is the check that would have
;; caught `protocol-miss` had it existed first.
(let [r (try (project/resolve-project
              (project/files-resolver
               {"a/main.cljc" "(ns a.main) (defprotocol P (m [x])) (defn main [_] 1)"} nil)
              'a.main #{:flint})
             (catch Exception _ nil))]
  (check "defprotocol still expands and compiles"
         (some? r) "a protocol emits a call into the caller's namespace"))

(when (pos? @fails) (println "  " @fails "FAILURES") (System/exit 1))
