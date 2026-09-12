;; `flint.sys.*`: virtual namespaces served by the binary (`DECISIONS.md#system-namespaces-and-deps`).
;;
;; Run against `target/release/flint` -- the binary that SHIPS -- for the reason
;; the SDK selftest gives: a test against something else passes with the shipped
;; thing broken.
(require '[clojure.string :as str] '[babashka.fs :as fs])

(def root (str (fs/parent (fs/parent (fs/absolutize *file*)))))
(def flint (str root "/target/release/flint"))

(defn sh [proj & args]
  (let [pb (doto (ProcessBuilder. (into-array String args)) (.directory (java.io.File. proj)))
        p (.start pb)
        out (slurp (.getInputStream p)) err (slurp (.getErrorStream p))]
    (.waitFor p) {:exit (.exitValue p) :out (str out err)}))

(def fails (atom 0))
(defn check [label ok detail]
  (if ok (println "  ok  " label)
      (do (swap! fails inc) (println "  FAIL" label "\n        " detail))))

(println "sysns: flint.sys.* is served by the binary (0037)")

(when-not (fs/exists? flint)
  (println "  (skipped: no target/release/flint -- `cargo build --release -p flint-cli`)")
  (System/exit 0))

(def proj (str (fs/create-temp-dir)))
(fs/create-dirs (str proj "/app"))
(spit (str proj "/deps.edn") "{}")
(spit (str proj "/app/a.cljc")
      (str "(ns app.a (:require [flint.sys.fs :as fs]))\n"
           "(defn go [_] (str \"deps=\" (fs/exists? \"deps.edn\")\n"
           "                  \" nope=\" (fs/exists? \"nope.edn\")\n"
           "                  \" n=\" (count (fs/list-dir \"\"))))\n"
           "(defn escape [_] (fs/read-file \"../../etc/passwd\"))\n"))

;; GRANTED: the call reaches the server and comes back.
(let [r (sh proj flint "run" ":path" "." ":fn" "app.a/go" ":with" "[fs]")]
  (check "a granted flint.sys.fs call reaches the server"
         (str/includes? (:out r) "deps=true") (:out r))
  (check "and a missing file is false rather than an error"
         (str/includes? (:out r) "nope=false") (:out r)))

;; NOT GRANTED: there is no system port, so it cannot even ask.
(let [r (sh proj flint "run" ":path" "." ":fn" "app.a/go")]
  (check "without the grant it cannot ask at all"
         (and (not (zero? (:exit r))) (str/includes? (:out r) "no system port"))
         (:out r)))

;; THE ROOT IS THE AUTHORITY. An escape is refused, not clamped.
(let [r (sh proj flint "run" ":path" "." ":fn" "app.a/escape" ":with" "[fs]")]
  (check "a path that leaves the root is refused"
         (str/includes? (:out r) "leaves the root") (:out r)))

;; The CLI knows its own surface, so an unknown var is a COMPILE error.
(spit (str proj "/app/b.cljc")
      "(ns app.b (:require [flint.sys.fs :as fs])) (defn go [_] (fs/no-such-thing 1))")
(let [r (sh proj flint "run" ":path" "." ":fn" "app.b/go" ":with" "[fs]")]
  (check "an unknown var in a served namespace is a COMPILE error"
         (str/includes? (:out r) "does not hold no-such-thing") (:out r)))

;; Read-only unless asked for. `:with [fs]` lends reading; writing is `fs:write`.
(spit (str proj "/app/c.cljc")
      "(ns app.c (:require [flint.sys.fs :as fs])) (defn go [_] (fs/write-file \"x.txt\" \"hi\"))")
(let [r (sh proj flint "run" ":path" "." ":fn" "app.c/go" ":with" "[fs]")]
  (check "a :fs grant is read-only unless write was asked for"
         (str/includes? (:out r) "read-only") (:out r)))
(let [r (sh proj flint "run" ":path" "." ":fn" "app.c/go" ":with" "[fs:write]")]
  (check "and writable when it was"
         (and (zero? (:exit r)) (fs/exists? (str proj "/x.txt"))) (:out r)))

;; --- `flint deps add` ------------------------------------------------------
;;
;; NETWORK. Skipped rather than failed when there is none, because a test that
;; cannot run and a test that failed must not look alike -- and a suite that
;; goes red on a train is a suite people stop running.
(def online?
  (zero? (:exit (sh proj "curl" "-sSf" "-o" "/dev/null" "--max-time" "10"
                    "https://registry.npmjs.org/left-pad"))))

(if-not online?
  (println "  (skipped: `flint deps add` needs the network)")
  (let [p2 (str (fs/create-temp-dir))]
    (spit (str p2 "/deps.edn") ";; keep me\n{:paths [\"src\"]}\n")
    (let [r (sh p2 flint "deps" "add" "npm:left-pad@^1.2.0")
          text (slurp (str p2 "/deps.edn"))]
      (check "deps add resolves and PINS an exact version"
             (re-find #":npm/version \"1\.\d+\.\d+\"" text) text)
      (check "and records the integrity"
             (str/includes? text ":npm/integrity \"sha512-") text)
      (check "and leaves the rest of the file alone"
             (and (str/includes? text ";; keep me") (str/includes? text ":paths"))
             text)
      (check "and says what it did" (str/includes? (:out r) "added left-pad") (:out r)))
    ;; An exact version must resolve to ITSELF. A bare `1.2.0` is a CARET RANGE
    ;; in semver, so this was 1.3.0 until `exact-range` existed -- a pin that
    ;; silently did nothing, which is worse than no pin at all.
    (let [p3 (str (fs/create-temp-dir))]
      (spit (str p3 "/deps.edn") "{}\n")
      (sh p3 flint "deps" "add" "npm:left-pad@1.2.0")
      (check "an exact version pins to itself, not to a caret range"
             (str/includes? (slurp (str p3 "/deps.edn")) ":npm/version \"1.2.0\"")
             (slurp (str p3 "/deps.edn"))))))

;; --- pods (`DECISIONS.md#system-namespaces-and-deps` step 9) ------------------------------------
;;
;; A pod is one implementation of the SAME interface `flint.sys.fs` implements,
;; which is the whole reason `workspace-capabilities` made the var list optional and sourced from
;; `:list`. `test/fixtures/demopod` is a real babashka pod: bencode over stdio,
;; JSON payloads, `describe` and `invoke`.
(let [p4 (str (fs/create-temp-dir))]
  (fs/create-dirs (str p4 "/app"))
  ;; A LOCAL POD IS A DIRECTORY holding a manifest, the way `:local/root` is a
  ;; directory holding a `deps.edn`. The coordinate says which manifest to
  ;; read, so a pod under development needs no registry entry.
  (fs/create-dirs (str p4 "/demopod"))
  (fs/copy (str root "/test/fixtures/demopod") (str p4 "/demopod/run"))
  (fs/set-posix-file-permissions (str p4 "/demopod/run") "rwxr-xr-x")
  (spit (str p4 "/demopod/manifest.edn")
        "{:pod/name pod.demo\n :pod/artifacts [{:artifact/executable \"run\"}]}\n")
  (spit (str p4 "/deps.edn")
        "{:paths [\".\"]\n :deps {pod.demo {:pod/path \"./demopod\"}}}\n")
  (spit (str p4 "/app/a.cljc")
        "(ns app.a (:require [pod.demo :as d]))\n(defn go [_] (str \"add=\" (d/add 1 2 3) \" greet=\" (d/greet \"flint\")))\n")
  (let [r (sh p4 flint "run" ":path" "." ":fn" "app.a/go")]
    (check "a pod is booted, described and invoked"
           (str/includes? (:out r) "add=6 greet=hello flint") (:out r)))
  ;; Booting the pod is what buys the checking: `describe` gave the var list, so
  ;; an unknown name is a COMPILE error rather than a run-time one.
  (spit (str p4 "/app/b.cljc")
        "(ns app.b (:require [pod.demo :as d])) (defn go [_] (d/subtract 1 2))")
  (let [r (sh p4 flint "run" ":path" "." ":fn" "app.b/go")]
    (check "and its var list makes an unknown var a COMPILE error"
           (str/includes? (:out r) "does not hold subtract") (:out r))))

;; --- a pod FETCHED from a registry (`DECISIONS.md#pods-are-a-resolvable-dependency`)
;;
;; `:pod/version` used to be recognised and refused with a sentence naming the
;; missing registry. The registry exists now, and the property that matters is
;; that a FETCHED pod is indistinguishable from a local one by the time
;; anything boots it: the artifact is chosen once, where the plan is made, and
;; what lands on disk is an ordinary pod directory with an ordinary manifest.
(let [p (str (fs/create-temp-dir))
      reg (str (fs/create-temp-dir))]
  (fs/create-dirs (str p "/app"))
  (fs/copy (str root "/test/fixtures/demopod") (str reg "/demopod"))
  (spit (str reg "/registry.edn")
        (str "{:registry/name \"test\"\n"
             " :pods {pod.demo {\"1.0.0\" {:pod/artifacts"
             " [{:artifact/url \"file://" reg "/demopod\""
             "   :artifact/executable \"run\"}]}}}}\n"))
  (spit (str p "/deps.edn")
        (str "{:paths [\".\"]\n"
             " :flint/pod-registries [\"file://" reg "/registry.edn\"]\n"
             " :deps {pod.demo {:pod/version \"1.0.0\"}}}\n"))
  (spit (str p "/app/a.cljc")
        "(ns app.a (:require [pod.demo :as d]))\n(defn go [_] (str \"add=\" (d/add 2 3)))\n")
  ;; UNFETCHED FIRST, because the message is the part that is easy to get
  ;; wrong: what is missing is the fetch, not a manifest the user was supposed
  ;; to write.
  (let [r (sh p flint "run" ":path" "." ":fn" "app.a/go")]
    (check "an unfetched :pod/version says the fetch is what is missing"
           (and (str/includes? (:out r) "has not been fetched")
                (str/includes? (:out r) "flint fetch"))
           (:out r)))
  (let [f (sh p (str root "/bin/flint") "fetch")]
    (check "`flint fetch` resolves a :pod/version against the registry"
           (zero? (:exit f)) (:out f)))
  (let [r (sh p flint "run" ":path" "." ":fn" "app.a/go")]
    (check "  ... and the shipped binary boots what it fetched"
           (str/includes? (:out r) "add=5") (:out r))))

;; --- git tags that disagree (`DECISIONS.md#system-namespaces-and-deps`) ---------------------------
;;
;; `:git/version` was built and then REMOVED: a semver range re-resolves on
;; every build, and it answered the wrong question anyway. The question is two
;; dependencies naming different tags of one repository, and this is it.
(if-not online?
  (println "  (skipped: `flint deps agree` needs the network)")
  (let [p5 (str (fs/create-temp-dir))]
    (spit (str p5 "/deps.edn")
          (str "{:deps {lib-a {:git/url \"https://github.com/clojure/data.json\" :git/tag \"v2.4.0\"}\n"
               "        lib-b {:git/url \"https://github.com/clojure/data.json.git\" :git/tag \"v2.5.2\"}}}\n"))
    (let [r (sh p5 flint "deps" "agree")]
      ;; THE THREE SPELLINGS ARE ONE REPOSITORY. A `.git` suffix and a trailing
      ;; slash are the common case, and a detector that missed them would miss
      ;; the conflicts that actually occur.
      (check "disagreeing git tags are found across url spellings"
             (and (str/includes? (:out r) "v2.4.0") (str/includes? (:out r) "v2.5.2"))
             (:out r))
      (check "and it proposes the highest tag ALREADY ASKED FOR"
             (str/includes? (:out r) "=> v2.5.2") (:out r)))
    (sh p5 flint "deps" "agree" "--apply")
    (let [text (slurp (str p5 "/deps.edn"))]
      (check "applying writes overrides carrying the tag AND its sha"
             (and (str/includes? text ":flint/overrides")
                  (str/includes? text ":git/tag \"v2.5.2\"")
                  (str/includes? text ":git/sha \""))
             text))
    ;; It CONVERGES: a second run has nothing to say.
    (let [r (sh p5 flint "deps" "agree")]
      (check "and then everybody agrees"
             (str/includes? (:out r) "every git dependency agrees") (:out r)))))

;; A missing `git` says what to install, for this platform.
(let [r (sh proj "/bin/sh" "-c"
             (str "PATH=/nonexistent " flint " deps add git:github.com/clojure/data.json"))]
  (check "a missing git says why and how to get it"
         (and (str/includes? (:out r) "not on your PATH")
              (or (str/includes? (:out r) "brew install git")
                  (str/includes? (:out r) "apt install git")
                  (str/includes? (:out r) "winget")
                  (str/includes? (:out r) "git-scm.com")))
         (:out r)))

(let [r (sh proj flint "deps" "add" "cargo:serde")]
  (check "an unknown dependency kind is refused by name"
         (and (not (zero? (:exit r))) (str/includes? (:out r) "cargo")) (:out r)))

;; A VIRTUAL REFERENCE AT LOAD TIME, which is a different phase from every row
;; above and used to give a different ERROR. A virtual namespace compiles to
;; calls on `flint.virtual`, and the using namespace requires `flint.sys.fs` --
;; the namespace it NAMES -- never the machinery behind it. `flint.virtual` was
;; added to the program as a ROOT, which puts it in and gives it no edge, so it
;; could be initialised after the code calling into it:
;;
;;   before   "value is not a function (nil, 2 args)"
;;   after    "no system port, so it cannot ask for \"flint.sys.fs\""
;;
;; and requiring `flint.sys.fs` did not help, because that is not what the call
;; is on. `topo-order` derives the edge now: a source requiring a VIRTUAL
;; namespace depends on `flint.virtual`.
;;
;; UNGRANTED ON PURPOSE. What this row pins is that the call is REACHED and
;; refused for the right reason. A granted call at load time is a separate
;; question and does not work -- see the row below.
(spit (str proj "/app/c.cljc")
      (str "(ns app.c (:require [flint.sys.fs :as fs]))\n"
           ;; TOP LEVEL, and READ by `go`, or the linker drops it and the row
           ;; passes by never running the thing it is about.
           "(def at-load (try (fs/exists? \"deps.edn\") (catch Throwable e (ex-message e))))\n"
           "(defn go [_] (str at-load))\n"))

(let [r (sh proj flint "run" ":path" "." ":fn" "app.c/go")]
  (check "a virtual reference AT LOAD TIME reaches the machinery"
         (str/includes? (:out r) "no system port") (:out r))
  (check "and does not fall back to the nil-callee message"
         (not (str/includes? (:out r) "is not a function")) (:out r)))

;; AND A GRANTED ONE AT LOAD TIME IS REFUSED, WITH THE REASON. This used to be
;; a silent half-built program: a top-level form that asks the HOST comes back
;; parked, `run_program`'s initialiser loop discarded the park -- it is not
;; re-entrant, so there is no position to resume to -- and the entry was never
;; reached. What the user saw was "the entry function did not return a string",
;; about an entry that returned a constant.
;;
;; The comment above that loop already said this happens for a YIELD, and
;; disarms preemption so none can occur. A park is the other thing and was
;; still discarded. `ports-are-the-hosts` says a sandbox that cannot ask is TOLD so rather
;; than parked; this is that sentence one phase earlier.
;;
;; IN-SANDBOX PARKING AT LOAD TIME IS UNAFFECTED, which is the line between the
;; two: a channel round-trip in a top-level `def` parks and RESUMES inside the
;; same call, so `park_on` is nil by the time the initialiser returns. Only a
;; park nothing can resume is refused.
(let [r (sh proj flint "run" ":path" "." ":fn" "app.c/go" ":with" "[fs]")]
  (check "a GRANTED virtual call at load time is refused, not silently broken"
         (str/includes? (:out r) "still initialising") (:out r))
  (check "and the refusal says what to do instead"
         (str/includes? (:out r) "Move the call into a function") (:out r))
  (check "and it is not the old message about the entry's return type"
         (not (str/includes? (:out r) "did not return a string")) (:out r)))


;; --- the SOURCE workspace (`DECISIONS.md#workspace-capabilities`) ------------
;;
;; This binary used to emit VIRTUAL workspaces only -- the `flint.sys.*`
;; catalogue and any pods -- and no workspace for the files it was compiling.
;; Everything therefore belonged to the anonymous workspace, and because the
;; capability guard skips references WITHIN one workspace, it never fired here.
;; `bin/flint` read `deps.edn` and refused the same program, so the feature
;; looked built and was inert in the thing that ships.
;;
;; Which is this file's own opening argument, arrived at the hard way: a test
;; against something else passes with the shipped thing broken. Every test for
;; the guard and for `:flint/tag-readers` drove `bin/flint`.
(let [p6 (str (fs/create-temp-dir))]
  (fs/create-dirs (str p6 "/src"))
  (spit (str p6 "/src/evil.cljc")
        (str "(ns evil (:require [flint.host :as h]))\n"
             "(defn go [_] (str \"reached: \" (some? h/request)))\n"))
  (spit (str p6 "/deps.edn") "{:paths [\"src\"]}\n")
  (let [r (sh p6 flint "run" ":path" "src" ":fn" "evil/go")]
    (check "an UNGRANTED workspace cannot name a guarded var"
           (str/includes? (:out r) "is guarded with") (:out r))
    (check "  ... and the refusal names the workspace that guards it"
           (str/includes? (:out r) "flint/flint") (:out r)))
  ;; The control. Without it, a check that the guard refuses would also pass
  ;; against a binary that refuses everything.
  (spit (str p6 "/deps.edn") "{:paths [\"src\"] :flint/capabilities-grant [:host]}\n")
  (let [r (sh p6 flint "run" ":path" "src" ":fn" "evil/go")]
    (check "  ... and a GRANTED one may"
           (str/includes? (:out r) "reached: true") (:out r))))

;; `:flint/tag-readers` had the same gap and the same cause: a tag is bound per
;; PROJECT, and this binary knew of no project.
(let [p7 (str (fs/create-temp-dir))]
  (fs/create-dirs (str p7 "/src"))
  (spit (str p7 "/deps.edn") "{:paths [\"src\"] :flint/tag-readers {pt rdr/point}}\n")
  (spit (str p7 "/src/rdr.cljc")
        "(ns rdr)\n(defn point [v] {:x (first v) :y (second v)})\n")
  (spit (str p7 "/src/app.cljc")
        "(ns app (:require [rdr]))\n(defn go [_] (str (:x #pt [3 4])))\n")
  (let [r (sh p7 flint "run" ":path" "src" ":fn" "app/go")]
    (check "a project's reader tag is bound in the shipped binary"
           (str/includes? (:out r) "3") (:out r))))


;; A guard BETWEEN two project workspaces, which is the half the first fix
;; missed. One catch-all prefix collapsed every source root into one workspace,
;; and a guard cannot fire inside one workspace -- so this passed while
;; `bin/flint` refused it. Files are now attributed per root.
(let [p8 (str (fs/create-temp-dir))]
  (fs/create-dirs (str p8 "/lib/src/acme"))
  (fs/create-dirs (str p8 "/app/src"))
  (spit (str p8 "/lib/deps.edn")
        "{:paths [\"src\"] :flint/workspace acme/lib :flint/capabilities-guard [:secret]}\n")
  (spit (str p8 "/lib/src/acme/thing.cljc") "(ns acme.thing)\n(defn peek [] \"sensitive\")\n")
  (spit (str p8 "/app/src/app.cljc")
        "(ns app (:require [acme.thing :as t]))\n(defn go [_] (t/peek))\n")
  (spit (str p8 "/app/deps.edn") "{:paths [\"src\"]}\n")
  (let [r (sh p8 flint "run" ":path" "[app/src lib/src]" ":fn" "app/go")]
    (check "a guard BETWEEN two project workspaces refuses"
           (str/includes? (:out r) "guards with") (:out r))
    ;; The refusal marker had no handler on this side, because with everything
    ;; anonymous nothing was ever refused -- so it reached the image loader and
    ;; came back as "this is not a flint image".
    (check "  ... as a sentence, not as a corrupt image"
           (not (str/includes? (:out r) "not a flint image")) (:out r)))
  (spit (str p8 "/app/deps.edn")
        "{:paths [\"src\"] :flint/capabilities-grant [:secret]}\n")
  (let [r (sh p8 flint "run" ":path" "[app/src lib/src]" ":fn" "app/go")]
    (check "  ... and a GRANTED workspace may require it"
           (str/includes? (:out r) "sensitive") (:out r))))


;; --- the prelude (`DECISIONS.md#dialects-and-preludes`) ---------------------
;;
;; What `clojure.core` has always had, generalised: a workspace's ordered list
;; of namespaces whose names resolve without a `:require`.
(let [p9 (str (fs/create-temp-dir))]
  (fs/create-dirs (str p9 "/src/mylib"))
  (fs/create-dirs (str p9 "/src/other"))
  (spit (str p9 "/src/mylib/prelude.fln") "(ns mylib.prelude)\n(defn shout [s] (str s \"!\"))\n")
  (spit (str p9 "/src/other/prelude.fln") "(ns other.prelude)\n(defn shout [s] (str s \"?\"))\n")
  (spit (str p9 "/src/app.fln") "(ns app)\n(defn go [_] (shout \"hello\"))\n")
  ;; A `.cljc` must NOT see it: other platforms' readers know nothing of a
  ;; flint workspace's prelude, so a name resolving only through one is not
  ;; portable however the file is spelled.
  (spit (str p9 "/src/app2.cljc") "(ns app2)\n(defn go [_] (shout \"portable\"))\n")
  (let [pre (fn [body] (spit (str p9 "/deps.edn")
                             (str "{:paths [\"src\"] :flint/prelude " body "}\n")))]
    (pre "[clojure.core mylib.prelude]")
    (check "a prelude name resolves with no :require"
           (str/includes? (:out (sh p9 flint "run" ":path" "src" ":fn" "app/go")) "hello!")
           (:out (sh p9 flint "run" ":path" "src" ":fn" "app/go")))
    ;; Which also proves the namespace was COLLECTED. Nothing requires it, so a
    ;; prelude entry has to create the edge itself or the program never holds it.
    (check "  ... and a .cljc does NOT get it"
           (str/includes? (:out (sh p9 flint "run" ":path" "src" ":fn" "app2/go"))
                          "unable to resolve")
           (:out (sh p9 flint "run" ":path" "src" ":fn" "app2/go")))
    (pre "[clojure.core {:ns mylib.prelude :exclude [shout]}]")
    (check "  ... :exclude removes a name from the entry it names"
           (str/includes? (:out (sh p9 flint "run" ":path" "src" ":fn" "app/go"))
                          "unable to resolve")
           (:out (sh p9 flint "run" ":path" "src" ":fn" "app/go")))
    (pre "[clojure.core {:ns mylib.prelude :include [shout]}]")
    (check "  ... :include admits only what it lists"
           (str/includes? (:out (sh p9 flint "run" ":path" "src" ":fn" "app/go")) "hello!")
           (:out (sh p9 flint "run" ":path" "src" ":fn" "app/go")))
    (pre "[clojure.core {:ns mylib.prelude :include [shout] :exclude [x]}]")
    (check "  ... and giving both is refused"
           (str/includes? (:out (sh p9 flint "run" ":path" "src" ":fn" "app/go"))
                          "both :include and :exclude")
           (:out (sh p9 flint "run" ":path" "src" ":fn" "app/go")))
    ;; Ambiguity is refused rather than settled by order: the author can say
    ;; exactly which they meant, so guessing buys nothing.
    (pre "[clojure.core mylib.prelude other.prelude]")
    (let [r (sh p9 flint "run" ":path" "src" ":fn" "app/go")]
      (check "two entries offering one name is refused"
             (str/includes? (:out r) "lists both") (:out r))
      (check "  ... and the refusal names the exclusion that settles it"
             (str/includes? (:out r) ":exclude [shout]") (:out r)))))

(if (pos? @fails)
  (do (println "sysns:" @fails "FAILURES") (System/exit 1))
  (println "sysns: ok"))
