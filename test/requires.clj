;; A REQUIRE IS FOR ORDERING AND ALIASING, NOT FOR EXISTENCE.
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

;; 4. AND THE HALF A QUALIFIED REFERENCE CANNOT REPLACE. A require runs the
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
  (check "4. splitting on a literal works without the regex engine" (= 0 (:exit r))))

(println)
(if (zero? @fails)
  (println "requires: existence is not what a require establishes\n")
  (do (println (format "requires: %d FAILURE(S)\n" @fails)) (System/exit 1)))
