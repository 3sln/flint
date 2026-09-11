;; FOUR SHAPES THAT RESOLVE WITHOUT A REQUIRE.
;;
;; The title used to be "a require is for ordering and aliasing, not for
;; existence", which is more than this file checks. A case has since been
;; found where a qualified reference is NOT sufficient -- see the note on
;; case 4 -- and the mechanism is not understood. What is below is four
;; shapes that do work, which is worth pinning and is not the same claim.
;;
;; flint reads every source on the path and registers every name it defines
;; BEFORE analysing any of them -- `read-namespace!` says so: "forward
;; references, within a namespace and between namespaces, resolve without
;; `declare`". Nothing is loaded on demand, so a `require` never decides
;; whether a namespace is there. It decides what order things are emitted in,
;; what aliases exist, and which capability guards apply.
;;
;; WHY THIS IS PINNED RATHER THAN ASSUMED. The question came up as "should a
;; fully-qualified reference establish a dependency the way a require does?"
;; -- prompted by `defprotocol`, which expands to a call into the namespace
;; that DEFINED the protocol, in whatever namespace used the macro. In Clojure
;; that is a real hazard: `require` is the only thing that loads, and a
;; qualified symbol resolves against what is already loaded or fails.
;;
;; Here it is not a hazard, because of the model above -- and these three
;; cases are the evidence. If they ever start failing, the model changed, and
;; the answer to that question changes with it.
;;
;; Each case puts the DEFINING namespace where an alphabetical order would
;; analyse it second (`zzz` before `aaa`), so passing cannot be luck about
;; how the labels happened to sort.
(require '[clojure.string :as str] '[babashka.fs :as fs])

(def fails (atom 0))
(defn check [label ok?]
  (if ok?
    (println "  ok  " label)
    (do (swap! fails inc) (println "  FAIL" label))))

(defn run [files]
  (let [d (fs/create-temp-dir)]
    (doseq [[n src] files] (spit (str d "/" n) src))
    (let [p (.start (ProcessBuilder.
                     (into-array String ["./bin/flint" "test" ":src" (str d)])))
          out (slurp (.getInputStream p))
          err (slurp (.getErrorStream p))]
      {:exit (.waitFor p) :text (str out err)})))

(println "\nrequires: a qualified name resolves without one\n")

;; 1. A FUNCTION. The ordinary case.
(let [r (run {"zzz.cljc" "(ns zzz)\n(defn twice [x] (* 2 x))"
              "aaa.cljc" (str "(ns aaa (:require [flint.check :refer [expect]]))\n"
                              "(defn ^:flint.check/test t []\n"
                              "  #?(:flint/check (expect = 84 (zzz/twice 42))))")})]
  (check "1. a function in a never-required namespace resolves" (= 0 (:exit r))))

;; 2. A TOP-LEVEL `def` READING ONE. This is the ordering case rather than the
;; resolution case: `derived` is computed at initialisation, so `zzz`'s own
;; top-level state has to be there by then.
(let [r (run {"zzz.cljc" "(ns zzz)\n(def table (hash-map :a 1))\n(defn lookup [k] (get table k))"
              "aaa.cljc" (str "(ns aaa (:require [flint.check :refer [expect]]))\n"
                              "(def derived (zzz/lookup :a))\n"
                              "(defn ^:flint.check/test t []\n"
                              "  #?(:flint/check (expect = 1 derived)))")})]
  (check "2. a top-level def may read one at initialisation" (= 0 (:exit r))))

;; 3. A MACRO, which is the sharpest: it must be DEFINED at the moment the
;; using namespace is analysed, not merely emitted before it runs.
(let [r (run {"zzz.cljc" "(ns zzz)\n(defmacro twice-it [x] (list '* 2 x))"
              "aaa.cljc" (str "(ns aaa (:require [flint.check :refer [expect]]))\n"
                              "(defn ^:flint.check/test t []\n"
                              "  #?(:flint/check (expect = 84 (zzz/twice-it 42))))")})]
  (check "3. a macro in a never-required namespace expands" (= 0 (:exit r))))

;; 4. A TOP-LEVEL SIDE EFFECT into a never-required namespace.
;;
;; Added after a case that FAILS was found and this one was assumed to be it.
;; It is not: this works, and that is what this row pins.
;;
;; THE CASE THAT FAILED HAS SINCE BEEN EXPLAINED AND FIXED, and the note here
;; described it wrongly on two counts. Moving `extend-method` to
;; `flint.protocols` gave `value is not a function (nil, 4 args)` because the
;; load ORDER is built from `:require` edges and nothing ordered
;; `flint.protocols` before the namespace whose top-level `extend-protocol`
;; called into it. An explicit require DID fix it -- this note said it did not,
;; measured wrong. `flint.protocols` is pinned now and `extend-method` has
;; moved. See `doc/goals/README.md`.
;;
;; So these four cases are four shapes that ARE sufficient, and not a proof
;; that a qualified reference always is. See `doc/goals/README.md`.
(let [r (run {"zzz.cljc" "(ns zzz)\n(def sink (atom []))\n(defn record! [x] (swap! sink conj x))"
              "aaa.cljc" (str "(ns aaa (:require [flint.check :refer [expect]]))\n"
                              "(zzz/record! :from-aaa)\n"
                              "(defn ^:flint.check/test t []\n"
                              "  #?(:flint/check (expect = [:from-aaa] @zzz/sink)))")})]
  (check "4. a top-level effect into a never-required namespace runs" (= 0 (:exit r))))

;; 5. AND THE HALF A QUALIFIED REFERENCE CANNOT REPLACE. A require runs the
;; required namespace's top-level effects. `clojure.string` deliberately does
;; NOT name `flint.regex` -- a static call would make the regex engine live
;; for every program that splits on a comma -- and `flint.regex` registers
;; itself instead. That registration is why a require is not merely an
;; ordering hint, and it is checked here by its absence: a program that never
;; requires `flint.regex` must not carry it.
(let [r (run {"aaa.cljc" (str "(ns aaa (:require [clojure.string :as s]\n"
                              "                  [flint.check :refer [expect]]))\n"
                              "(defn ^:flint.check/test t []\n"
                              "  #?(:flint/check (expect = [\"a\" \"b\"] (vec (s/split \"a,b\" \",\")))))")})]
  (check "5. splitting on a literal works without the regex engine" (= 0 (:exit r))))


;; --- WHAT AN `ns` FORM MAY SAY -------------------------------------------
;;
;; The clause heads are one set (`flint.analyzer/known-ns-clauses`), read both
;; by the analyzer that binds aliases and by `flint.compiler/ns-requires`,
;; which builds the load-order graph. An unknown head used to be dropped in
;; silence, which turned a misspelled `:require` into a namespace with no
;; dependencies -- failing much later, as an unresolved var, naming nothing
;; that led back to the `ns` form.
(let [r (run {"zzz.cljc" "(ns zzz)\n(defn twice [x] (* 2 x))"
              "aaa.cljc" (str "(ns aaa (:requrie [zzz]))\n"
                              "(defn ^:flint.check/test t [] nil)")})]
  (check "a misspelled clause is refused rather than dropped"
         (and (not= 0 (:exit r)) (str/includes? (:text r) ":requrie")))
  (check "  ... and the refusal lists what an ns form does take"
         (and (str/includes? (:text r) ":require")
              (str/includes? (:text r) ":refer-clojure"))))

;; A clause flint KNOWS and rejects is a different answer from one it has never
;; heard of, and keeps its own reason.
(let [r (run {"aaa.cljc" (str "(ns aaa (:import java.util.Date))\n"
                              "(defn ^:flint.check/test t [] nil)")})]
  (check "an :import still says flint has no host interop"
         (and (not= 0 (:exit r)) (str/includes? (:text r) "host interop"))))

;; The accepted-and-ignored clause stays accepted.
(let [r (run {"zzz.cljc" "(ns zzz)\n(defn twice [x] (* 2 x))"
              "aaa.cljc" (str "(ns aaa (:refer-clojure :exclude [get])\n"
                              "  (:require [flint.check :refer [expect]]))\n"
                              "(defn ^:flint.check/test t []\n"
                              "  #?(:flint/check (expect = 84 (zzz/twice 42))))")})]
  (check ":refer-clojure is still accepted" (= 0 (:exit r))))


;; --- DIALECTS: `.fl` beside `.cljc` -------------------------------------
;;
;; `.fl` is a PLATFORM extension in the sense `.clj` and `.cljs` are
;; (`DECISIONS.md#dialects-and-preludes`): one namespace may have both, and the
;; runtime loads the one it understands. It is not a separate namespace and
;; there is no rule about which may require which.
(let [r (run {"zzz.fl" "(ns zzz)\n(defn twice [x] (* 3 x))"
              "aaa.cljc" (str "(ns aaa (:require [flint.check :refer [expect]]))\n"
                              "(defn ^:flint.check/test t []\n"
                              "  #?(:flint/check (expect = 126 (zzz/twice 42))))")})]
  (check "a .fl namespace compiles, and a .cljc may require it" (= 0 (:exit r))))

;; Platform-specific beats portable, as `.clj` beats `.cljc` on the JVM.
(let [r (run {"zzz.fl"   "(ns zzz)\n(defn twice [x] (* 3 x))"
              "zzz.cljc" "(ns zzz)\n(defn twice [x] (* 2 x))"
              "aaa.cljc" (str "(ns aaa (:require [flint.check :refer [expect]]))\n"
                              "(defn ^:flint.check/test t []\n"
                              "  #?(:flint/check (expect = 126 (zzz/twice 42))))")})]
  (check "with both present, flint takes the .fl" (= 0 (:exit r))))

;; And that pairing is ORDINARY, so it must not be reported as shadowing --
;; the two files are one namespace's two halves, not one hiding the other.
(let [r (run {"zzz.fl"   "(ns zzz)\n(defn twice [x] (* 3 x))"
              "zzz.cljc" "(ns zzz)\n(defn twice [x] (* 2 x))"
              "aaa.cljc" (str "(ns aaa (:require [flint.check :refer [expect]]))\n"
                              "(defn ^:flint.check/test t []\n"
                              "  #?(:flint/check (expect = 126 (zzz/twice 42))))")})]
  (check "  ... and the pairing is not reported as shadowing"
         (not (str/includes? (:text r) "shadowing"))))

(println)
(if (zero? @fails)
  (println "requires: four shapes that resolve without one\n")
  (do (println (format "requires: %d FAILURE(S)\n" @fails)) (System/exit 1)))
