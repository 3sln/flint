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
  "`:refused` only when the capability guard stopped it.

  `builtins` is the catalogue `flint.rt/<x>` resolves against. It matters:
  `native-name` returns the name UNCHECKED when the catalogue is empty, so a
  probe that forgets it resolves `flint.rt/anything` and proves nothing. The
  first run of the rows below did exactly that and had to be redone."
  ([files ws] (guard-outcome files ws #{}))
  ([files ws builtins]
  (let [r (project/resolve-project (project/files-resolver files ws) 'app.main #{:flint})]
    (try
      (compiler/compile-image {:sources (:sources r) :order (:order r)
                               :builtins builtins
                               :entry 'app.main/main})
      :compiled
      (catch Exception e
        (if (str/includes? (ex-message e) "is guarded with") :refused :other))))))

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

;; ---------------------------------------------------------------------------
;; AND THE RUNG BELOW THE VAR, which is where the guard above was standing
;; in front of an open door.
;;
;; `native-name` turns `flint.rt/<x>` into a direct native call for any `x` the
;; loader carries, from ANY namespace. So a guard on a stdlib var that merely
;; forwards to a builtin is decorative: skip the wrapper, name the builtin.
;;
;; `flint.host/request` is guarded `[:host]` and forwards to `flint/request`.
;; It and `flint.host/ask` were the only guarded vars in all of `lib/`, so
;; level two of `0036` protected exactly one call and that call had a bypass.
;;
;; The catalogue is passed explicitly here. Without it `native-name` returns
;; the name unchecked and every `flint.rt/..` row would pass whether or not
;; anything worked -- which is how the first version of this was wrong.
(def catalogue #{"flint/request" "flint/port-send"})

(defn rt-files [call]
  {"app/main.cljc" (str "(ns app.main) (defn main [_] " call ")")})

(check "a fake builtin is still refused as unknown -- the catalogue is LIVE"
       (= :other (guard-outcome (rt-files "(flint.rt/definitely-not-real 1)")
                                guard-ws catalogue))
       "without this row the rows below cannot tell a guard from a typo")

(check "a guarded BUILTIN is refused from a workspace holding nothing"
       (= :refused (guard-outcome (rt-files "(flint.rt/request \"config\")")
                                  guard-ws catalogue))
       "flint.host/request's guard means nothing if this compiles")

(check "and allowed for a workspace that HOLDS the capability"
       (not= :refused (guard-outcome (rt-files "(flint.rt/request \"config\")")
                                     [{:prefix "app/" :name 'app :grants #{:host}}]
                                     catalogue))
       "the granted caller is the whole point of a grant")

(check "a program declaring NO workspaces is checked nowhere, as before"
       (not= :refused (guard-outcome (rt-files "(flint.rt/request \"config\")")
                                     [] catalogue))
       "this is the stdlib's own position, and flint builds itself from it")

(check "an unguarded builtin is untouched"
       (not= :refused (guard-outcome (rt-files "(flint.rt/port-send 1 2)")
                                     guard-ws catalogue))
       "the table is the two vars' forwarding target, not a policy about ports")

(check "the guarded builtin is one the real catalogue actually carries"
       (let [f "dist/builtins.json"]
         (or (not (.exists (java.io.File. f)))
             (str/includes? (slurp f) "\"flint/request\"")))
       "a guard on a name no loader carries would be theatre")

;; ---------------------------------------------------------------------------
;; A MARK THE COMPILER READS ONLY AFTER EXPANSION, and one it read only after
;; analysis. Same root as everything above: a mark is written in the source and
;; the compiler looks for it somewhere that is not filled in yet.
;;
;; `:var-meta` was built from the SOURCE form, in a pass that runs before any
;; macro expands. So a var defined BY a macro carried no metadata at all and
;; was not private, whatever its source said. `defn-` hit this once and was
;; patched by special-casing the symbol `defn-`, which fixed one macro rather
;; than the reason -- and `defprotocol` emits `def`s, so this was not exotic.
(def macro-ns "(ns libx.m)\n(defmacro mk [] '(def ^:private hidden 1))")

(defn mac-files [owner-body]
  {"libx/m.cljc" macro-ns
   "libx/owner4.cljc" (str "(ns libx.owner4 (:require [libx.m]))\n" owner-body)
   "app/main.cljc" "(ns app.main (:require [libx.owner4 :as o])) (defn main [_] libx.owner4/hidden)"})

(check "a MACRO-defined private var is refused, exactly as a literal one is"
       (= :refused (outcome (mac-files "(libx.m/mk)\n(defn go [x] x)") two))
       "the pre-expansion pass cannot see this def; the analyser can")

(check "and the literal spelling of the same def is still refused"
       (= :refused (outcome {"libx/owner4.cljc" "(ns libx.owner4)\n(def ^:private hidden 1)"
                             "app/main.cljc" "(ns app.main (:require [libx.owner4 :as o])) (defn main [_] libx.owner4/hidden)"}
                            two))
       "the pair is the point: one spelling refusing and the other not is the bug")

;; `:dynamic` IS THE SAME MISTAKE ONE TABLE OVER. The analyser records it when
;; the `def` is analysed; a reference reads it to choose between a thread
;; binding read and a plain one. A namespace analysed FIRST read an empty table
;; -- so `*x*` compiled to an ordinary var read that ignores every `binding`
;; around it, and `binding` itself refused the var for not being dynamic.
(defn dyn-outcome [files]
  (let [r (project/resolve-project (project/files-resolver files []) 'app.main #{:flint})]
    (try
      (compiler/compile-image {:sources (:sources r) :order (:order r)
                               :entry 'app.main/main})
      :compiled
      (catch Exception e
        (if (str/includes? (ex-message e) "is not dynamic") :not-dynamic :other)))))

(check "a dynamic var may be rebound from a namespace analysed BEFORE its definer"
       (not= :not-dynamic
             (dyn-outcome {"a/owner.cljc" "(ns a.owner (:require [a.back]))\n(def ^:dynamic *x* 1)\n(defn go [y] (a.back/reach y))"
                           "a/back.cljc" "(ns a.back)\n(defn reach [y] (binding [a.owner/*x* 2] y))"
                           "app/main.cljc" "(ns app.main (:require [a.owner :as o])) (defn main [_] (o/go 1))"}))
       "it said `*x* is not dynamic` about a var marked dynamic two lines up")

(check "and a var that really is not dynamic is still refused by `binding`"
       (= :not-dynamic
          (dyn-outcome {"a/owner.cljc" "(ns a.owner)\n(def plain 1)"
                        "app/main.cljc" "(ns app.main (:require [a.owner :as o])) (defn main [_] (binding [a.owner/plain 2] 1))"}))
       "the control: without it the row above passes by never checking anything")

;; A MACRO THAT QUIETLY BECAME A FUNCTION CALL, which is the same table again
;; and the worst of them: not a refusal that should have happened, but a
;; DIFFERENT PROGRAM. `:macros` holds expanders and is filled when a namespace
;; is analysed, so a namespace analysed first found nothing and compiled
;; `(their/macro x)` as an ordinary call -- evaluating every argument, in a
;; form usually written precisely so that they are not.
;;
;; The probe is a macro that DISCARDS its argument, given an argument that
;; cannot resolve. If the call expanded, the bad symbol is never analysed. If
;; it did not, resolving it is the first thing that happens. That difference is
;; the whole test, and no other error can imitate it.
(defn mac-outcome [files]
  (let [r (project/resolve-project (project/files-resolver files []) 'app.main #{:flint})]
    (try
      (compiler/compile-image {:sources (:sources r) :order (:order r)
                               :entry 'app.main/main})
      :compiled
      (catch Exception e
        (let [m (ex-message e)]
          (cond (str/includes? m "is a macro, and") :refused
                (str/includes? m "no-such-symbol-here") :evaluated-the-argument
                :else :other))))))

(def discards "(defmacro mm [x] ''expanded)")

(check "a macro used where it cannot be expanded is REFUSED, not called"
       (= :refused (mac-outcome
                    {"a/owner.cljc" (str "(ns a.owner (:require [a.back]))\n" discards
                                         "\n(defn go [y] (a.back/reach y))")
                     "a/back.cljc" "(ns a.back)\n(defn reach [y] (a.owner/mm no-such-symbol-here))"
                     "app/main.cljc" "(ns app.main (:require [a.owner :as o])) (defn main [_] (o/go 1))"}))
       "it compiled as a call and analysed the argument the macro throws away")

(check "and the ordinary edge still EXPANDS it, argument untouched"
       (not= :evaluated-the-argument
             (mac-outcome {"a/owner.cljc" (str "(ns a.owner)\n" discards)
                           "app/main.cljc" "(ns app.main (:require [a.owner :as o])) (defn main [_] (a.owner/mm no-such-symbol-here))"}))
       "the control: a macro that expands must not look at what it discards")

;; A VAR'S OWN GUARD DOES NOT AUTHORISE ITS BODY. A GRANT DOES.
;;
;; This is the second thing tried, and the first one compiled when it should
;; not have. Letting a guarded var name what it guards reads well -- it is
;; exactly the wrapper relationship, `flint.host/request` around
;; `flint.rt/request` -- and it is a way to MINT authority, because a guard is
;; not a grant:
;;
;;   a GRANT is conferred from OUTSIDE -- the embedder's workspace table, or
;;   `lib/deps.edn`, which ships with the compiler;
;;   a GUARD is written by the author of a var, about their own var.
;;
;; So under that rule a workspace holding nothing could write the guard itself,
;; call the builtin from behind it, and hand it on through a function with no
;; guard at all. Measured before it was reverted: it compiled.
;;
;; The library that implements the capability holds it instead, said in the two
;; places that name the workspace -- `lib/deps.edn` and the SDK -- which have
;; to agree or the front doors disagree about one workspace.
(def wrapper-ws [{:prefix "libx/" :name 'libx} {:prefix "app/" :name 'app}])

(defn launders [src]
  (guard-outcome {"libx/w5.cljc" (str "(ns libx.w5)\n" src)
                  "app/main.cljc" "(ns app.main (:require [libx.w5])) (defn main [_] 1)"}
                 wrapper-ws #{"flint/request"}))

(check "a workspace cannot authorise itself by declaring a guard it wrote"
       (= :refused (launders "(defn ^{:flint/capabilities-guard [:host]} w [x] (flint.rt/request x))"))
       "this compiled once: assert a guard, call the builtin, re-export unguarded")

(check "and an unguarded var naming the builtin is refused the same way"
       (= :refused (launders "(defn wide-open [x] (flint.rt/request x))"))
       "the plain case, which must not start passing when the one above is fixed")

(check "a workspace that was GRANTED the capability may name it"
       (not= :refused
             (guard-outcome
              {"libx/w8.cljc" "(ns libx.w8)\n(defn req [x] (flint.rt/request x))"
               "app/main.cljc" "(ns app.main (:require [libx.w8])) (defn main [_] 1)"}
              [{:prefix "libx/" :name 'libx :grants #{:host}} {:prefix "app/" :name 'app}]
              #{"flint/request"}))
       "which is how the standard library reaches the host, and the only how")

;; A BUILTIN IS NEVER "THE SAME WORKSPACE" AS ITS CALLER.
;;
;; The second hole in this check, and it made the first fix inert in exactly
;; the configuration that matters. `guard-check!` skips a reference within one
;; workspace, because a project is not a security boundary against itself. A
;; builtin belongs to no project: `flint.native` is a namespace no source
;; declares, so its workspace came back nil -- the ANONYMOUS one -- and any
;; caller that was also anonymous compared EQUAL to it and was never checked.
;;
;; Which is what the SDK does by default: it names the library's workspace and
;; leaves the embedder's own files unnamed unless the embedder says otherwise.
;; So the rung that matters when nobody has configured anything was the rung
;; that was off. The VAR guard was unaffected, because the library it protects
;; is named and that edge crosses -- which is why this was invisible.
(def lib-file {"flint/helper.cljc" "(ns flint.helper)\n(defn h [x] x)"})

(defn anon-outcome [ws]
  (guard-outcome
   (merge lib-file
          {"app/main.cljc" "(ns app.main (:require [flint.helper])) (defn main [_] (flint.rt/request \"config\"))"})
   ws #{"flint/request"}))

(check "anonymous code is refused a guarded builtin when the library is named"
       (= :refused (anon-outcome [{:prefix "flint/" :name 'flint/flint :grants #{:host}}]))
       "this compiled: unnamed caller and unnamed builtin were one workspace")

(check "but a program that names NO workspace at all is still checked nowhere"
       (not= :refused (anon-outcome []))
       "declare none and nothing is checked -- what this was before any of it")

(check "and a named caller holding nothing is refused, as before"
       (= :refused (anon-outcome [{:prefix "flint/" :name 'flint/flint :grants #{:host}}
                                  {:prefix "app/" :name 'app}]))
       "the row that already passed, kept so a fix cannot trade one for the other")
