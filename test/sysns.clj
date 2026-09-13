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

;; NOT GRANTED: the namespace is refused by name.
(let [r (sh proj flint "run" ":path" "." ":fn" "app.a/go")]
  ;; A NAMED REFUSAL, not "no system port". Since `flint.sdk` is served to
  ;; every program (`DECISIONS.md#flint-sdk`) a system port now always exists,
  ;; so an ungranted namespace is refused BY NAME instead of the transport
  ;; being absent. Strictly more informative, and a real change to the old
  ;; "granted nothing has no port" property -- recorded rather than absorbed.
  (check "without the grant it cannot ask at all"
         (and (not (zero? (:exit r)))
              (str/includes? (:out r) "refused to open")
              (str/includes? (:out r) "flint.sys.fs"))
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
  ;; REACHED AND REFUSED, which is what this row is about. The sentence is now
  ;; the load-time one rather than "no system port", because the call gets
  ;; further: a port exists, and what stops it is that a top-level form cannot
  ;; wait for an answer.
  (check "a virtual reference AT LOAD TIME reaches the machinery"
         (or (str/includes? (:out r) "still initialising")
             (str/includes? (:out r) "refused to open")) (:out r))
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
  ;; A `.fln`: a project tag is a flint-only tag, so the file using it is not
  ;; portable (`DECISIONS.md#dialects-and-preludes`). It was a `.cljc`, and the
  ;; assertion below was `includes? "3"` -- which the REFUSAL also satisfies,
  ;; because the read error ends in `(app.cljc:2:32)`. The check went green with
  ;; the feature it tests refused outright. Assert the whole answer.
  (spit (str p7 "/src/app.fln")
        "(ns app (:require [rdr]))\n(defn go [_] (str (:x #pt [3 4])))\n")
  (let [r (sh p7 flint "run" ":path" "src" ":fn" "app/go")]
    (check "a project's reader tag is bound in the shipped binary"
           (= "3" (str/trim (:out r))) (:out r))))


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

;; --- standalone scripts (`DECISIONS.md#standalone-scripts`) -----------------
;;
;; Driven through `target/release/flint`, because a shebang names a BINARY:
;; `#!/usr/bin/env flint` is answered by whatever `flint` is on the path, and a
;; script feature tested anywhere else is a feature the kernel never reaches.
(let [p10 (str (fs/create-temp-dir))
      script! (fn [nm text]
                (let [f (str p10 "/" nm)]
                  (spit f text)
                  (fs/set-posix-file-permissions f "rwxr-xr-x")
                  f))]
  ;; NO EXTENSION, on purpose: a script lives in `~/bin` under a bare name, and
  ;; keying a single-file source by its FILENAME -- which is what this did --
  ;; reported the namespace missing for every one of them.
  (script! "greet"
           (str "#!/usr/bin/env flint\n"
                "(ns ^:script greet\n"
                "  (:require [clojure.string :as str]))\n"
                "(defn main [args] (str \"hello \" (str/join \", \" args)))\n"))
  (let [r (sh p10 flint "./greet" "world" "friend")]
    (check "a #! script runs, and the shebang line is not read as source"
           (str/includes? (:out r) "hello world, friend") (:out r)))

  ;; `^{:script go}` names another entry. `^:script` alone is the convention.
  (script! "named" (str "#!/usr/bin/env flint\n(ns ^{:script go} named)\n"
                        "(defn go [_] \"went\")\n"))
  (let [r (sh p10 flint "./named")]
    (check "  ... and ^{:script go} runs go rather than main"
           (str/includes? (:out r) "went") (:out r)))

  ;; A module deliberately has NO entry (`DECISIONS.md#structured-ports`), so a
  ;; file that never claimed to be runnable is refused rather than guessed at.
  (script! "plain.fln" "(ns plain)\n(defn main [_] \"x\")\n")
  (let [r (sh p10 flint "./plain.fln")]
    (check "a file not marked ^:script is refused, not run anyway"
           (and (not (zero? (:exit r))) (str/includes? (:out r) "^:script")) (:out r)))

  ;; THE SOURCE PATH IS THE FILE. The control is the same script with the same
  ;; neighbour, differing only in whether it NAMES the directory -- so a failure
  ;; for any other reason would fail both arms.
  (fs/create-dirs (str p10 "/side"))
  (spit (str p10 "/side/helper.fln") "(ns helper)\n(defn shout [s] (str s \"!\"))\n")
  (spit (str p10 "/helper.fln") "(ns helper)\n(defn shout [s] (str s \"?\"))\n")
  (script! "uses"
           (str "#!/usr/bin/env flint\n(ns ^:script uses (:require [helper]))\n"
                "(defn main [_] (helper/shout \"hi\"))\n"))
  (let [r (sh p10 flint "./uses")]
    (check "a script does NOT scan its own directory: the neighbour is not found"
           (and (not (zero? (:exit r))) (str/includes? (:out r) "helper")) (:out r)))
  (script! "uses2"
           (str "#!/usr/bin/env flint\n"
                "(ns uses2 {:script {:paths [\"side\"]}} (:require [helper]))\n"
                "(defn main [_] (helper/shout \"hi\"))\n"))
  (let [r (sh p10 flint "./uses2")]
    (check "  ... and joins the path only by being NAMED in the ns form"
           (str/includes? (:out r) "hi!") (:out r)))

  ;; A deps.edn beside a script is NOT the script's workspace. This is the
  ;; hazard the feature exists to avoid, and it fails open: a script that
  ;; inherited a neighbour's grants would compile, run, and say nothing.
  (spit (str p10 "/deps.edn")
        "{:paths [\"src\"] :flint/tag-readers {pt greet/point}}\n")
  (script! "tagged" (str "#!/usr/bin/env flint\n(ns ^:script tagged)\n"
                         "(defn point [v] v)\n(defn main [_] (pr-str #pt [1 2]))\n"))
  (let [r (sh p10 flint "./tagged")]
    (check "a deps.edn beside a script does not become the script's workspace"
           (and (not (zero? (:exit r))) (str/includes? (:out r) "no reader for the tag"))
           (:out r)))

  ;; THE GRANT CHANNEL, probed as a program rather than reasoned about. This is
  ;; the one that fails OPEN: a script that inherited a neighbour's
  ;; `:flint/capabilities-grant` compiles, runs and says nothing, so reading the
  ;; code and finding it sensible proves nothing (`AGENTS.md` §5).
  ;;
  ;; The CONTROL is the same source in an ordinary project, which must still be
  ;; allowed -- otherwise a refusal here could mean the guard is broken in the
  ;; other direction and this test could not tell.
  (spit (str p10 "/deps.edn") "{:paths [\"src\"] :flint/capabilities-grant [:host]}\n")
  (script! "grabby" (str "#!/usr/bin/env flint\n"
                         "(ns ^:script grabby (:require [flint.host :as h]))\n"
                         "(defn main [_] (str h/request))\n"))
  (let [r (sh p10 flint "./grabby")]
    (check "a script does NOT inherit a neighbouring deps.edn's capability grant"
           (and (not (zero? (:exit r))) (str/includes? (:out r) "is guarded with"))
           (:out r)))
  (let [p10b (str (fs/create-temp-dir))]
    (fs/create-dirs (str p10b "/src"))
    (spit (str p10b "/deps.edn") "{:paths [\"src\"] :flint/capabilities-grant [:host]}\n")
    (spit (str p10b "/src/ok.fln")
          "(ns ok (:require [flint.host :as h]))\n(defn main [_] (str h/request))\n")
    (let [r (sh p10b flint "run" ":path" "src" ":fn" "ok/main")]
      (check "  ... and the control: a PROJECT with the same grant may name it"
             (zero? (:exit r)) (:out r))))

  ;; `:deps` is REAL SURFACE and this binary cannot honour it yet, so it says
  ;; so. Dropping it silently is the bug `analyze-ns`'s unknown-clause error
  ;; exists to stop, one level up.
  (script! "withdeps"
           (str "#!/usr/bin/env flint\n"
                "(ns ^:script withdeps (:deps {some/lib {:npm/version \"1.2.0\"}}))\n"
                "(defn main [_] \"x\")\n"))
  (let [r (sh p10 flint "./withdeps")]
    (check "a script's :deps is refused with a reason, not ignored"
           (and (not (zero? (:exit r)))
                (str/includes? (:out r) "does not fetch dependencies"))
           (:out r))))

;; A SCRIPT'S DECLARATIONS ARE NS METADATA, not ns clauses.
;;
;; They were clauses -- `(ns app (:deps {..}))` -- which meant a non-script had
;; to be REFUSED for using them, and the clause list had to carry two names no
;; ordinary namespace may write. Putting them in the `ns` form's metadata map
;; removes both problems: the shape is one every Clojure reader already parses,
;; and a namespace that is not a script simply has no `:script` key.
(let [p11 (str (fs/create-temp-dir))]
  (fs/create-dirs (str p11 "/src"))
  (spit (str p11 "/deps.edn") "{}")
  ;; `:deps` as a CLAUSE is now an unknown clause like any other, and the error
  ;; names the four an `ns` actually takes.
  (spit (str p11 "/src/app.cljc")
        "(ns app (:deps {some/lib {:npm/version \"1.0.0\"}}))\n(defn main [_] \"x\")\n")
  (let [r (sh p11 flint "run" ":path" "src" ":fn" "app/main")]
    (check ":deps as an ns CLAUSE is refused, and names the real list"
           (and (not (zero? (:exit r)))
                (str/includes? (:out r) "has no :deps clause")
                (str/includes? (:out r) ":refer-clojure"))
           (:out r)))
  (spit (str p11 "/src/app.cljc") "(ns app (:dpes {}))\n(defn main [_] \"x\")\n")
  (let [r (sh p11 flint "run" ":path" "src" ":fn" "app/main")]
    (check "  ... as is a misspelled one"
           (and (not (zero? (:exit r))) (str/includes? (:out r) ":dpes")) (:out r))))

;; --- the dialect split at the reader (`DECISIONS.md#dialects-and-preludes`) --
;;
;; `bin/flint` and the binary are meant to answer identically; `test/tags.clj`
;; drives the first, this drives the one that ships.
(let [p12 (str (fs/create-temp-dir))
      src (str "(ns app (:require [flint.table :as ft]))\n"
               "(defn main [_] (pr-str #flint/table"
               " {:schema [[:id :int]] :rows [{:id 1}]}))\n")]
  (fs/create-dirs (str p12 "/src"))
  (spit (str p12 "/deps.edn") "{}")
  (spit (str p12 "/src/app.cljc") src)
  (let [r (sh p12 flint "run" ":path" "src" ":fn" "app/main")]
    (check "a flint-only reader tag in a .cljc is refused by the shipped binary"
           (and (not (zero? (:exit r)))
                (str/includes? (:out r) "flint-only reader tag"))
           (:out r)))
  ;; The control: one character of the filename, nothing else.
  (fs/delete (str p12 "/src/app.cljc"))
  (spit (str p12 "/src/app.fln") src)
  (let [r (sh p12 flint "run" ":path" "src" ":fn" "app/main")]
    (check "  ... and the SAME source as a .fln runs"
           (str/includes? (:out r) "#flint/table") (:out r))))


;; THE STANDARD LAYOUT, which nothing covered. Every pod fixture above puts
;; `deps.edn` and the pod beside the sources; a real project puts sources under
;; `src/` and `deps.edn` at the root. Two things were wrong there and both were
;; silent: the pod declaration was looked for only BESIDE the source root, so a
;; project-root `deps.edn` was never read; and `:pod/path` resolved against the
;; source root rather than against the file declaring it, giving `src/./demopod`
;; for a pod sitting next to `deps.edn` one level up.
(let [pa (str (fs/create-temp-dir))]
  (fs/create-dirs (str pa "/src"))
  (fs/create-dirs (str pa "/demopod"))
  (fs/copy (str root "/test/fixtures/demopod") (str pa "/demopod/run"))
  (fs/set-posix-file-permissions (str pa "/demopod/run") "rwxr-xr-x")
  (spit (str pa "/demopod/manifest.edn")
        "{:pod/name pod.demo\n :pod/artifacts [{:artifact/executable \"run\"}]}\n")
  (spit (str pa "/deps.edn")
        "{:paths [\"src\"] :deps {pod.demo {:pod/path \"./demopod\"}}}\n")
  (spit (str pa "/src/app.cljc")
        "(ns app (:require [pod.demo :as d]))\n(defn go [_] (str \"pod says \" (d/add 1 2 3)))\n")
  (let [r (sh pa flint "run" ":path" "src" ":fn" "app/go")]
    (check "a pod is found when deps.edn is a directory ABOVE the source root"
           (str/includes? (:out r) "pod says 6") (:out r))))


;; --- `:checks`, independent of `:optimize` (`DECISIONS.md#checks`) ---------
;;
;; The two were inseparable: only `:optimize [perf]` removed checks, and the
;; shipped binary did not even do that -- it emitted no `:features` at all, so
;; a release module carried its own test code and a failing check threw from
;; it. Tying them together is wrong in both directions, and the second one is
;; what `bin/check-llvm` needs: it compares `[perf]` against `[size]` and
;; asserts identical gas, which is only meaningful if both arms read the SAME
;; source.
(let [pb (str (fs/create-temp-dir))]
  (spit (str pb "/deps.edn") "{:paths [\".\"]}\n")
  (spit (str pb "/app.cljc")
        (str "(ns app (:require [flint.check :refer [expect]]))\n"
             "(defn main [_] #?(:flint/check (expect = 1 2)) \"survived\")\n"))
  (let [run (fn [& opts]
              (apply sh pb flint "compile" ":path" "." ":fn" "app/main"
                     ":to" ":wasm" ":out" "m.wasm" opts)
              (:out (sh pb "node" (str root "/host/flint.mjs") "m.wasm" "app/main")))]
    (check "a failing check fires in a default build"
           (str/includes? (run) "check failed") (run))
    (check "  ... and `:checks false` removes it with no :optimize at all"
           (str/includes? (run ":checks" "false") "survived") (run ":checks" "false"))
    (check "  ... and `:checks true` KEEPS it under :optimize [perf]"
           (str/includes? (run ":optimize" "[perf]" ":checks" "true") "check failed")
           (run ":optimize" "[perf]" ":checks" "true"))))


;; --- a script declares itself in ns METADATA, four ways ------------------
(let [p12 (str (fs/create-temp-dir))]
  (let [script! (fn [name src]
                  (spit (str p12 "/" name) (str "#!/usr/bin/env " flint "\n" src))
                  (fs/set-posix-file-permissions (str p12 "/" name) "rwxr-xr-x"))]
    (script! "a" "(ns ^:script a)\n(defn main [_] \"flag\")\n")
    (script! "b" "(ns ^{:script go} b)\n(defn go [_] \"symbol\")\n")
    (script! "c" "(ns c {:script {:entry go}})\n(defn go [_] \"attr-map entry\")\n")
    ;; An EMPTY map still marks the file. `edn_block` answers "" both for an
    ;; absent key and an empty one, so this read as "not a script" until the
    ;; scanner tested for the key's PRESENCE instead of its contents.
    (script! "d" "(ns d {:script {}})\n(defn main [_] \"attr-map default\")\n")
    (doseq [[f want] [["a" "flag"] ["b" "symbol"] ["c" "attr-map entry"] ["d" "attr-map default"]]]
      (check (str "  ns metadata form `" f "` runs " want)
             (str/includes? (:out (sh p12 (str "./" f))) want)
             (:out (sh p12 (str "./" f)))))))

;; A SCRIPT IS STILL AN ORDINARY NAMESPACE. Nothing forbids requiring one --
;; the `:script` block is read by the launcher and ignored by the compiler, so
;; a file can be both a tool and a library without saying so twice.
(let [p13 (str (fs/create-temp-dir))]
  (fs/create-dirs (str p13 "/src"))
  (spit (str p13 "/deps.edn") "{:paths [\"src\"]}\n")
  (spit (str p13 "/src/tool.cljc")
        (str "(ns tool {:script {:entry go}})\n"
             "(defn helper [x] (* x 2))\n"
             "(defn go [_] (str \"as a script: \" (helper 21)))\n"))
  (spit (str p13 "/src/app.cljc")
        "(ns app (:require [tool]))\n(defn main [_] (str \"as a library: \" (tool/helper 21)))\n")
  (check "a script can be required as an ordinary namespace"
         (str/includes? (:out (sh p13 flint "run" ":path" "src" ":fn" "app/main")) "as a library: 42")
         (:out (sh p13 flint "run" ":path" "src" ":fn" "app/main")))
  (check "  ... and still runs as a script"
         (str/includes? (:out (sh p13 flint "run" ":path" "src" ":fn" "tool/go")) "as a script: 42")
         (:out (sh p13 flint "run" ":path" "src" ":fn" "tool/go"))))


;; --- a script asks for its own capabilities -------------------------------
;;
;; A script is self-contained, which is the point of it: having to remember the
;; right `:with` flags every time defeats that. So it DECLARES what it needs and
;; the launcher asks, once, keyed to the file's CONTENT.
(let [p14 (str (fs/create-temp-dir))]
  (spit (str p14 "/data.txt") "secret\n")
  (spit (str p14 "/s")
        (str "#!/usr/bin/env " flint "\n"
             "(ns s {:script {:capabilities [:fs]}} (:require [flint.sys.fs :as fs]))\n"
             "(defn main [_] (str \"read: \" (fs/read-file \"data.txt\")))\n"))
  (fs/set-posix-file-permissions (str p14 "/s") "rwxr-xr-x")
  ;; WITH NOBODY TO ASK, IT REFUSES. There is no consent to be had down a pipe,
  ;; and the safe direction is to run with nothing rather than to assume yes.
  (let [r (sh p14 "./s")]
    (check "a script asking for a capability REFUSES when there is no terminal"
           (and (not (zero? (:exit r))) (str/includes? (:out r) "no terminal to ask on"))
           (:out r))
    (check "  ... and names the invocation that lends it explicitly"
           (str/includes? (:out r) ":with [fs]") (:out r)))
  ;; `:with` BEFORE the path -- everything after it is the script's own argv --
  ;; and then nothing is asked, because nothing is unmet.
  (let [r (sh p14 flint ":with" "[fs]" "./s")]
    (check "  ... and `:with` before the path lends it with no prompt at all"
           (str/includes? (:out r) "read: secret") (:out r))))

;; --- flint.sys.wasm: the binary runs a module it compiled -----------------
;;
;; The ONE host operation that differs between the front ends in kind rather
;; than in spelling (`DECISIONS.md#wasm-engine`). This closes the loop the
;; namespace exists for: compile a module with the binary, then run it with the
;; binary, on whatever engine the machine turned out to have.
(let [p15 (str (fs/create-temp-dir))]
  (spit (str p15 "/deps.edn") "{}")
  (spit (str p15 "/m.cljc") "(ns m)\n(defn main [args] (str \"hello from \" (count args) \" args\"))\n")
  (spit (str p15 "/w.cljc")
        (str "(ns w (:require [flint.sys.wasm :as wasm]))\n"
             "(defn go [_] (let [r (wasm/run \"m.wasm\" \"m/main\")]\n"
             "               (str \"code=\" (:code r) \" out=\" (:out r))))\n"))
  (let [c (sh p15 flint "compile" ":src" "." ":fn" "m/main" ":out" "m.wasm")]
    (check "a module compiles, to be run by the namespace below"
           (and (zero? (:exit c)) (fs/exists? (str p15 "/m.wasm"))) (:out c)))
  ;; NOT GRANTED first: running a module is executing code, so it is a grant
  ;; like any other and the ungranted case must fail closed.
  (let [r (sh p15 flint "run" ":path" "." ":fn" "w/go")]
    (check "without the grant it cannot run a module at all"
           (and (not (zero? (:exit r)))
                (str/includes? (:out r) "refused to open")
                (str/includes? (:out r) "flint.sys.wasm"))
           (:out r)))
  (let [r (sh p15 flint "run" ":path" "." ":fn" "w/go" ":with" "[wasm]")]
    (check "a granted flint.sys.wasm/run executes the module"
           (str/includes? (:out r) "hello from 0 args") (:out r))
    (check "  ... and reports the module's exit code"
           (str/includes? (:out r) "code=0") (:out r))))

;; --- flint.sdk: the compiler, served to flint ------------------------------
;;
;; Self-hosting is what makes this possible: the compiler is already linked in,
;; so a flint program compiling another is a function call rather than a
;; subprocess (`DECISIONS.md#flint-sdk`). `run` needs no module and no wasm
;; engine on this binary -- the runtime is compiled in.
(let [p16 (str (fs/create-temp-dir))]
  (spit (str p16 "/deps.edn") "{}")
  (fs/create-dirs (str p16 "/inner"))
  (spit (str p16 "/inner/hi.cljc") "(ns inner.hi)\n(defn main [args] (str \"inner sees \" (count args) \" args\"))\n")
  ;; `:sources`, NOT `:paths`: the SDK reaches no filesystem, so the caller
  ;; supplies the source text it wants compiled (`DECISIONS.md#flint-sdk`).
  (spit (str p16 "/drv.cljc")
        (str "(ns drv (:require [flint.sdk :as sdk] [flint.bytes :as b]))\n"
             "(def src \"(ns inner.hi)\\n(defn main [args] (str \\\"inner sees \\\" (count args) \\\" args\\\"))\\n\")\n"
             "(defn go [_] (let [r (sdk/run {:sources {\"inner.hi\" src} :fn \"inner.hi/main\"})\n"
             "                   img (sdk/compile {:sources {\"inner.hi\" src} :fn \"inner.hi/main\"})]\n"
             "               (str \"code=\" (:code r) \" out=\" (:out r) \" bytes=\" (b/size img))))\n"))
  (let [r (sh p16 flint "run" ":path" "." ":fn" "drv/go" ":with" "[sdk]")]
    (check "a flint program compiles and runs another flint program"
           (str/includes? (:out r) "out=inner sees 0 args") (:out r))
    (check "  ... and compile hands back the image rather than writing one"
           (re-find #"bytes=[1-9][0-9]+" (:out r)) (:out r))
    ;; NOTHING IS WRITTEN. `compile` takes no `:out`, so there is no path for a
    ;; caller to name and nothing to announce.
    (check "  ... writing no file and announcing none"
           (not (str/includes? (:out r) "wrote")) (:out r)))
  ;; NO GRANT IS NEEDED, and that is the point: since `compile` takes source
  ;; text and hands back bytes, the SDK reaches nothing a program could not
  ;; already reach. Gating it bought no safety and made every nested compile ask
  ;; for a capability that conferred nothing.
  (let [r (sh p16 flint "run" ":path" "." ":fn" "drv/go")]
    (check "  ... and needs no capability at all"
           (str/includes? (:out r) "out=inner sees 0 args") (:out r)))
  ;; OFF UNDER A GAS LIMIT. A limit is a promise about the whole process, and a
  ;; nested sandbox runs on its own budget.
  (let [pb (doto (ProcessBuilder. (into-array String [flint "run" ":path" "." ":fn" "drv/go"]))
             (.directory (java.io.File. p16)))
        _ (.put (.environment pb) "FLINT_STEP_LIMIT" "50000000")
        proc (.start pb)
        out (str (slurp (.getInputStream proc)) (slurp (.getErrorStream proc)))]
    (.waitFor proc)
    (check "  ... but is off under a gas limit"
           (str/includes? out "off under a gas limit") out)
    (check "  ... saying why, rather than looking absent"
           (str/includes? out "own budget") out)))

;; --- flint.sdk: a sandbox constructor, holding nothing --------------------
;;
;; The shape `sdks/rust` and `sdks/c` have: compile to an artifact, construct a
;; sandbox from it, call a named function. The sandbox holds NOTHING -- no
;; ports, no capabilities, no IO -- so it reaches the world only through what it
;; is later handed (`DECISIONS.md#flint-sdk`).
(let [p18 (str (fs/create-temp-dir))]
  (spit (str p18 "/deps.edn") "{}")
  (spit (str p18 "/box.cljc")
        (str "(ns box (:require [flint.sdk :as sdk]))\n"
             "(def src (str \"(ns guest)\\n\"\n"
             "              \"(defn greet [a b] (str \\\"hi \\\" a \\\" and \\\" b))\\n\"\n"
             "              \"(defn main [args] \\\"entry\\\")\\n\"))\n"
             ;; `:exports` keeps `greet` callable: only reachable code ships, and
             ;; a function nobody calls from the entry is the one a host wants.
             "(defn go [_]\n"
             "  (let [img (sdk/compile {:sources {\"guest\" src} :fn \"guest/main\"\n"
             "                          :exports [\"guest/greet\"]})\n"
             "        b (sdk/sandbox img)\n"
             "        r (sdk/call b \"guest/greet\" [\"ada\" \"alan\"])\n"
             "        _ (sdk/close b)]\n"
             "    (str \"reply=\" (pr-str r))))\n"
             "(defn stale [_]\n"
             "  (let [img (sdk/compile {:sources {\"guest\" src} :fn \"guest/main\"})\n"
             "        b (sdk/sandbox img)]\n"
             "    (sdk/close b)\n"
             "    (sdk/call b \"guest/main\" [])))\n"))
  (let [r (sh p18 flint "run" ":path" "." ":fn" "box/go")]
    ;; ARGUMENTS ARE PASSED INDIVIDUALLY -- the `flint_call` ABI -- and not
    ;; wrapped into one vector the way an entry's `[args]` is. The two front
    ;; ends disagreed about this until they were made to agree.
    (check "a sandbox calls a named function with individual arguments"
           (str/includes? (:out r) "hi ada and alan") (:out r)))
  ;; A CLOSED HANDLE IS CLOSED, not silently some later sandbox that reused the
  ;; number: the slot is kept rather than compacted.
  (let [r (sh p18 flint "run" ":path" "." ":fn" "box/stale")]
    (check "  ... and a handle used after close says so"
           (str/includes? (:out r) "is closed") (:out r))))

;; --- flint.sdk does not mint authority -------------------------------------
;;
;; `sdk/run` takes `:with`, so without a test the capability it lends is
;; whatever the CALLER asks for -- and `sdk` becomes the only grant anyone
;; needs, because a program can write a child that does what it may not and
;; hand it the capability to do it. Found by probing, not by reading.
(let [p17 (str (fs/create-temp-dir))]
  (spit (str p17 "/deps.edn") "{}")
  (fs/create-dirs (str p17 "/child"))
  (spit (str p17 "/secret.txt") "SECRET\n")
  (spit (str p17 "/child/read.cljc")
        "(ns child.read (:require [flint.sys.fs :as fs]))\n(defn main [args] (str \"got \" (fs/read-file \"secret.txt\")))\n")
  (spit (str p17 "/attack.cljc")
        (str "(ns attack (:require [flint.sdk :as sdk]))\n"
             "(def src \"(ns child.read (:require [flint.sys.fs :as fs]))\\n(defn main [args] (str \\\"got \\\" (fs/read-file \\\"secret.txt\\\")))\\n\")\n"
             "(defn go [_] (:out (sdk/run {:sources {\"child.read\" src} :fn \"child.read/main\" :with [\"fs\"]})))\n"))
  (let [r (sh p17 flint "run" ":path" "." ":fn" "attack/go")]
    (check "a program granted nothing cannot lend :fs to a child"
           (str/includes? (:out r) "cannot lend it") (:out r))
    (check "  ... and the secret does not come back"
           (not (str/includes? (:out r) "SECRET")) (:out r)))
  ;; THE CONTROL, differing in exactly one thing: the caller now holds `fs`.
  ;; Without it a refusal for any unrelated reason would read as the check
  ;; working.
  (let [r (sh p17 flint "run" ":path" "." ":fn" "attack/go" ":with" "[fs]")]
    (check "  ... but a caller that HOLDS :fs may pass it on"
           (str/includes? (:out r) "got SECRET") (:out r))))

(if (pos? @fails)
  (do (println "sysns:" @fails "FAILURES") (System/exit 1))
  (println "sysns: ok"))
