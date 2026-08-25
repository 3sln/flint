;; `:exclude` and `:wasm-path` (doc/decisions/0004).
;;
;; The point of `:exclude` is that it is an ASSERTION: it must FAIL, at compile
;; time, when the thing it names is reachable, and the failure must name the
;; namespace in the MIDDLE of the chain, because that is the one somebody can
;; change. Both are asserted here on the message text, not just the exit code.
(require '[clojure.string :as str] '[babashka.fs :as fs])

(def fails (atom 0))
(defn check [label actual expected]
  (if (= actual expected)
    (println "  ok  " label)
    (do (swap! fails inc) (println "  FAIL" label "expected" expected "got" actual))))

(defn check-that [label ok] (check label (boolean ok) true))

(defn flint [& args]
  (let [p (.start (ProcessBuilder. (into-array String (cons "./bin/flint" (map str args)))))
        out (slurp (.getInputStream p))
        err (slurp (.getErrorStream p))]
    (.waitFor p)
    {:exit (.exitValue p) :out out :err err :all (str out err)}))

(defn run [wasm]
  (let [p (.start (ProcessBuilder. (into-array String ["node" "host/flint.mjs" wasm])))
        out (slurp (.getInputStream p))]
    (slurp (.getErrorStream p)) (.waitFor p) (str/trim out)))

(def d (str (fs/create-temp-dir)))
(spit (str d "/splitlit.cljc")
      "(ns splitlit (:require [clojure.string :as str]))\n(defn main [_] (str/join \"|\" (str/split \"a,b,c\" \",\")))")
(spit (str d "/tokenize.cljc")
      "(ns tokenize (:require [clojure.string :as str]))\n(defn tokens [s] (str/split s #\"[0-9]+\"))")
(spit (str d "/splitpat.cljc")
      "(ns splitpat (:require [clojure.string :as str] [tokenize :as t]))\n(defn main [_] (str/join \"|\" (t/tokens \"a1b22c\")))")
(spit (str d "/plain.cljc") "(ns plain)\n(defn main [_] \"plain\")")
(spit (str d "/jsonly.cljc")
      "(ns jsonly (:require [flint.data.json :as json]))\n(defn main [_] (str (json/read-str \"[1,2]\")))")

(println "options: :exclude and :wasm-path")

;; ---------------------------------------------------------------- :exclude

(def pat (flint ":src" d ":fn" "splitpat/main" ":out" "out/opt-pat.wasm"))
(check "a program that splits on a pattern builds" (:exit pat) 0)

(def bad (flint ":src" d ":fn" "splitpat/main" ":out" "out/opt-bad.wasm" ":exclude" "[flint.regex]"))
(check "excluding something REACHABLE is a compile error" (:exit bad) 1)
(check "  ... and the message says so"
       (str/includes? (:all bad) "flint.regex is excluded, but it is reachable") true)
(check "  ... and names the intermediate namespace, not only the ends"
       (str/includes? (:all bad) "tokenize/tokens") true)
(check "  ... and names the entry that reached it"
       (str/includes? (:all bad) "splitpat/main") true)
(check "  ... and no module was written"
       (fs/exists? "out/opt-bad.wasm") false)

(def lit (flint ":src" d ":fn" "splitlit/main" ":out" "out/opt-lit.wasm" ":exclude" "[flint.regex]"))
(check "excluding something UNREACHABLE succeeds" (:exit lit) 0)
(check "  ... and the module still runs" (run "out/opt-lit.wasm") "a|b|c")

;; The size drop. `flint` shakes per VAR, so excluding code that is already
;; unreachable cannot shrink a module -- the module never held it. The drop the
;; flag buys you is the one on the other side of the assertion: the same program
;; written so that the exclusion HOLDS is smaller than the one where it does not.
(def pat-size (fs/size "out/opt-pat.wasm"))
(def lit-size (fs/size "out/opt-lit.wasm"))
(println (format "    with a regex engine %d bytes, without %d, saved %d"
                 pat-size lit-size (- pat-size lit-size)))
(check "a module that can honour :exclude [flint.regex] is materially smaller"
       (> (- pat-size lit-size) 8000) true)

(def unrelated (flint ":src" d ":fn" "plain/main" ":out" "out/opt-plain.wasm"
                      ":exclude" "[flint.data.xml flint.data.html clojure.math]"))
(check "excluding several unreached namespaces at once succeeds" (:exit unrelated) 0)

(def bi (flint ":src" d ":fn" "jsonly/main" ":out" "out/opt-json.wasm" ":exclude" "[flint.data.json]"))
(check "excluding a namespace whose BUILTIN is reached also fails" (:exit bi) 1)
(check "  ... and names the builtin"
       (str/includes? (:all bi) "the builtin `flint/json-parse`") true)

;; -------------------------------------------------------------- :wasm-path

(when-not (fs/exists? "test/fixtures/wasm-path/demo/shout.unit.edn")
  (println "  building the fixture unit")
  (let [p (.start (ProcessBuilder. (into-array String ["./bin/build-test-unit"])))]
    (slurp (.getInputStream p)) (slurp (.getErrorStream p)) (.waitFor p)))

(spit (str d "/app.cljc") "(ns app (:require [demo.shout :as s]))\n(defn main [_] (s/shout \"hello\"))")

(def missing (flint ":src" d ":fn" "app/main" ":out" "out/opt-wl.wasm"))
(check "without :wasm-path the namespace cannot be found" (:exit missing) 1)

(def wl (flint ":src" d ":fn" "app/main" ":wasm-path" "test/fixtures/wasm-path"
               ":out" "out/opt-wl.wasm" "--stats"))
(check "a unit found on the :wasm-path path links" (:exit wl) 0)
(check "  ... and --stats says which manifest was used"
       (str/includes? (:all wl) "demo.shout <- test/fixtures/wasm-path/demo/shout.unit.edn") true)
(check "  ... and the module RUNS, calling into the unit" (run "out/opt-wl.wasm") "HELLO!")

(def refused (flint ":src" d ":fn" "app/main" ":wasm-path" "test/fixtures/wasm-path-bad"
                    ":out" "out/opt-wlbad.wasm"))
(check "an incompatible unit is refused" (:exit refused) 1)
(check "  ... by name and version, with a reason"
       (boolean (re-find #"refusing unit demo\.shout .*runtime 2 \(need 1\)" (:all refused))) true)

(def old-name (flint ":src" d ":fn" "app/main" ":wasm-ld" "test/fixtures/wasm-path"
                    ":out" "out/opt-wl3.wasm"))
(check "the deprecated :wasm-ld spelling still works" (:exit old-name) 0)
(check "  ... and says it is deprecated"
       (str/includes? (:all old-name) ":wasm-ld is deprecated") true)

(def shadow (flint ":src" d ":fn" "app/main"
                   ":wasm-path" "test/fixtures/wasm-path" "test/fixtures/wasm-path-bad"
                   ":out" "out/opt-wl2.wasm"))
(check "an earlier directory wins, and the loser is reported" (:exit shadow) 0)
(check "  ... naming both manifests"
       (str/includes? (:all shadow) "is shadowed by test/fixtures/wasm-path/demo/shout.unit.edn") true)

;; ------------------------------------------------- :features, and elisions
;;
;; A reader conditional matching nothing DELETES the form it stood in. That is
;; how a library compiles with functions missing, and how a `:require` becomes
;; invisible -- so the compile says which ones, and `:features` is what changes
;; the answer.
(def fd (str (fs/create-temp-dir)))
(spit (str fd "/f.cljc")
      (str "(ns f)\n"
           "(def platform #?(:clj \"jvm\" :cljs \"js\"))\n"
           "(def known #?(:cljs 1 :default 9))\n"
           "(defn main [_] (str platform \" \" known))\n"))

(def elided (flint ":src" fd ":fn" "f/main" ":out" "out/opt-elide.wasm"))
(check "an elided conditional is reported, with its file and line" (:exit elided) 0)
(check-that "  ... naming the count, the features tried, and what it offered"
            (and (str/includes? (:all elided) "matched none of :flint")
                 (str/includes? (:all elided) "was DELETED")
                 (str/includes? (:all elided) "offering :clj/:cljs")))
(check-that "  ... but a :default branch is not reported"
            (not (str/includes? (:all elided) "lines 2, 3")))

(def picked (flint ":src" fd ":fn" "f/main" ":features" "[flint cljs]"
                   ":out" "out/opt-feat.wasm"))
(check ":features selects a branch instead" (:exit picked) 0)
(check-that "  ... and then there is nothing to report"
            (not (str/includes? (:all picked) "was DELETED")))
(check "  ... and the branch it selected is the one that runs"
       (str/trim (run "out/opt-feat.wasm")) "js 1")

;; The crash this replaced said `Don't know how to create ISeq from:
;; clojure.lang.Symbol`, which names nothing a reader can act on. It is reached
;; by elision rather than by typo: rewrite-clj writes the argument vector itself
;; inside a conditional.
(spit (str fd "/g.cljc")
      (str "(ns g)\n(defn- broken #?(:clj [x]) (inc x))\n(defn main [_] \"ok\")\n"))
(def novec (flint ":src" fd ":fn" "g/main" ":out" "out/opt-novec.wasm"))
(check "a fn with no argument vector is diagnosed, not crashed on" (:exit novec) 1)
(check-that "  ... naming the function, the line, and what it found"
            (and (str/includes? (:all novec) "a fn arity needs an argument vector")
                 (str/includes? (:all novec) "g/broken")
                 (not (str/includes? (:all novec) "ISeq"))))

(if (zero? @fails)
  (println "options: ok")
  (do (println "options:" @fails "FAILURES") (System/exit 1)))
