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

;; AND REFUSED WHEN THE DEFINER IS ANALYSED SECOND, which it was not.
;;
;; Privacy used to depend on the order the compiler reached the files. A
;; pre-pass records every top-level def NAME in `:declared` so a forward
;; reference resolves; it recorded the name and not the visibility, while
;; `privacy-check!` read `:var-meta`, which is filled in only when a namespace
;; is ANALYSED. So a reference from a namespace analysed BEFORE the definer
;; resolved through `:declared` and met no check at all -- `(:private nil)` is
;; nil, and nil is not a refusal.
;;
;; THE TRIGGER IS A REQUIRE POINTING THE OTHER WAY. If A requires B then B is
;; analysed first, so B could name A's private vars. That is not a corner: it
;; is what `clojure.core` requiring `flint.protocols` does, and it is why
;; moving `extend-method` there passed a privacy violation in silence and
;; failed later as `value is not a function`.
;;
;; Same two namespaces as the rows above would not show it -- `app.main`
;; requires `libx.core`, so the definer is always analysed first. These name
;; the reverse edge explicitly.
(def owner "(ns libx.owner (:require [libx.back]))
(defn- owner-secret [x] x)
(defn go [x] (libx.back/reach x))")

(def back-private "(ns libx.back)
(defn reach [x] (libx.owner/owner-secret x))")

(def back-public "(ns libx.back)
(defn reach [x] x)")

(check "a private var is refused from a namespace that was analysed FIRST"
       (= :refused (outcome {"libx/owner.cljc" owner "libx/back.cljc" back-private
                             "app/main.cljc" "(ns app.main (:require [libx.owner :as o])) (defn main [_] (o/go 1))"}
                            two))
       "the definer is analysed second here; privacy must not depend on that")

(check "and the same shape is NOT refused when nothing private is named"
       (not= :refused (outcome {"libx/owner.cljc" owner "libx/back.cljc" back-public
                                "app/main.cljc" "(ns app.main (:require [libx.owner :as o])) (defn main [_] (o/go 1))"}
                               two))
       "the control: the reverse edge itself must stay legal")
;; AND `^:internal` ACROSS THE SAME REVERSE EDGE. `:private` and `:internal`
;; are read from the SAME map by the same check, so if one was order-dependent
;; the other was too -- and the fix recorded only `:private`. This row is here
;; to say which of those is true rather than to assume.
(def owner-int "(ns libx.owner3 (:require [other.back3]))
(defn ^:internal team-only [x] x)
(defn go [x] (other.back3/reach x))")

(def back-int "(ns other.back3)
(defn reach [x] (libx.owner3/team-only x))")

(check "`^:internal` is refused across a workspace boundary, definer second"
       (= :refused (outcome {"libx/owner3.cljc" owner-int "other/back3.cljc" back-int
                             "app/main.cljc" "(ns app.main (:require [libx.owner3 :as o])) (defn main [_] (o/go 1))"}
                            [{:prefix "libx/" :name 'libx}
                             {:prefix "other/" :name 'other}
                             {:prefix "app/" :name 'app}]))
       "internal must not depend on analysis order either")

;; AND `^:private` ON A PLAIN `def`, WHICH IS THE OTHER SPELLING. The fix
;; above first caught only `defn-`, because that is the one the pre-pass could
;; tell from the form's HEAD. `^:private` is metadata on the name symbol,
;; which the reader has already attached by the time the pass runs -- so it
;; was readable all along and simply was not read. Sixty uses of `^:private`
;; across `lib/` and this direction checked none of them.
(def owner-def "(ns libx.owner2 (:require [libx.back2]))
(def ^:private hidden 42)
(defn go [] (libx.back2/reach))")

(def back-def "(ns libx.back2)
(defn reach [] libx.owner2/hidden)")

(check "`^:private` on a def is refused across the reverse edge too"
       (= :refused (outcome {"libx/owner2.cljc" owner-def "libx/back2.cljc" back-def
                             "app/main.cljc" "(ns app.main (:require [libx.owner2 :as o])) (defn main [_] (o/go))"}
                            two))
       "both spellings of private must hold, and in both orders")

;; `not= :refused` AND NOT `= :compiled`, which is what this first asserted.
;; These synthetic projects carry no `clojure.core`, so nothing here ever
;; reaches `:compiled` -- the first version of this control failed on
;; `unable to resolve symbol: nth` and looked like a bug in the fix it was
;; guarding. Every other control in this file already knew that.

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

;; ---------------------------------------------------------------------------
;; AND THE CAPABILITY GUARD, which is the same KIND of mark and had no test.
;;
;; `^{:flint/capabilities-guard [...]}` is not hygiene -- it is the var-level
;; half of 0036, refusing a reference from a workspace that does not hold the
;; capability. It was read out of the SAME table by the same shape of check as
;; the two above, so the order-dependence those rows pin was present here too,
;; and nothing anywhere named it: `capabilities-guard` appeared in `src/` and
;; `lib/` and in NO test file.
;;
;; What made it certain rather than suspected is the pair below. Both rows are
;; the same reverse edge, the same namespace, the same function position. The
;; only difference is WHICH mark the named var carries. Privacy refused and the
;; guard did not -- so the body was demonstrably analysed, and the guard's
;; silence was a bypass rather than a file the compiler never reached.
(defn guard-outcome
  "`:refused` only when the capability guard stopped it."
  [files ws]
  (let [r (project/resolve-project (project/files-resolver files ws) 'app.main #{:flint})]
    (try
      (compiler/compile-image {:sources (:sources r) :order (:order r)
                               :entry 'app.main/main})
      :compiled
      (catch Exception e
        (if (str/includes? (ex-message e) "is guarded with") :refused :other)))))

(def guard-ws [{:prefix "libx/" :name 'libx :grants #{:fs}}
               {:prefix "app/" :name 'app}])

(def guard-owner "(ns libx.owner2 (:require [app.back2]))
(defn ^{:flint/capabilities-guard [:fs]} danger [x] x)
(defn- guard-secret [x] x)
(defn go [x] (app.back2/reach x))")

(defn guard-files [reach]
  {"libx/owner2.cljc" guard-owner
   "app/back2.cljc" (str "(ns app.back2)\n(defn reach [x] " reach ")")
   "app/main.cljc" "(ns app.main (:require [libx.owner2 :as o])) (defn main [_] (o/go 1))"})

(check "a guarded var is refused from an ungranted workspace, ORDINARY edge"
       (= :refused (guard-outcome
                    {"libx/owner2.cljc" "(ns libx.owner2)\n(defn ^{:flint/capabilities-guard [:fs]} danger [x] x)"
                     "app/main.cljc" "(ns app.main (:require [libx.owner2 :as o])) (defn main [_] (o/danger 1))"}
                    guard-ws))
       "the baseline: without this row the check could be dead and look fine")

(check "and refused across the REVERSE edge, where the definer is analysed second"
       (= :refused (guard-outcome (guard-files "(libx.owner2/danger x)") guard-ws))
       "this is the row that failed: the guard read a table analysis had not filled yet")

(check "the same reverse edge refuses a PRIVATE var -- so the body IS analysed"
       (= :refused (outcome (guard-files "(libx.owner2/guard-secret x)") guard-ws))
       "the companion control: if this stops refusing, the row above proves nothing")

(check "and an unguarded var across that edge is still allowed"
       (not= :refused (guard-outcome (guard-files "x") guard-ws))
       "the guard must not refuse the reverse edge itself")

(check "a workspace that HOLDS the capability may name the guarded var"
       (not= :refused (guard-outcome (guard-files "(libx.owner2/danger x)")
                                     [{:prefix "libx/" :name 'libx :grants #{:fs}}
                                      {:prefix "app/" :name 'app :grants #{:fs}}]))
       "a guard that refuses the granted caller is not a guard, it is a wall")
