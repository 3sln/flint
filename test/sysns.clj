;; `flint.sys.*`: virtual namespaces served by the binary (`doc/decisions/0037`).
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

;; --- pods (`doc/decisions/0037` step 9) ------------------------------------
;;
;; A pod is one implementation of the SAME interface `flint.sys.fs` implements,
;; which is the whole reason `0036` made the var list optional and sourced from
;; `:list`. `test/fixtures/demopod` is a real babashka pod: bencode over stdio,
;; JSON payloads, `describe` and `invoke`.
(let [p4 (str (fs/create-temp-dir))]
  (fs/create-dirs (str p4 "/app"))
  (fs/copy (str root "/test/fixtures/demopod") (str p4 "/demopod"))
  (fs/set-posix-file-permissions (str p4 "/demopod") "rwxr-xr-x")
  (spit (str p4 "/deps.edn")
        "{:paths [\".\"]\n :flint/pods {pod.demo {:pod/program \"./demopod\"}}}\n")
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

;; --- git tags that disagree (`doc/decisions/0037`) ---------------------------
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

(if (pos? @fails)
  (do (println "sysns:" @fails "FAILURES") (System/exit 1))
  (println "sysns: ok"))
