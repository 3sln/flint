;; The CLI surface (`DECISIONS.md#cli`).
;;
;; The logic lives in `lib/flint/cli.cljc` and `lib/flint/deps.cljc` -- as flint
;; code, reaching the project through the `:fs` CAPABILITY -- because 0021's
;; strongest argument is that flint should be usable by someone with no Clojure
;; toolchain, and `bin/flint` is a babashka script. What has to survive that move
;; is this logic; the rest is a host wrapper.
;;
;; So the test runs the CLI as a compiled flint program, not as a script.
(require '[clojure.string :as str] '[babashka.fs :as fs] '[clojure.java.io :as io])
;; The URL derivation is pure, so it is checked directly rather than through a
;; build -- and against the same code the compiled CLI runs, not a copy.
(babashka.classpath/add-classpath "lib")
(require '[flint.deps :as fdeps])
(def deps-ns-npm-tarball fdeps/npm-tarball)

(def fails (atom 0))
(defn check [label actual expected]
  (if (= actual expected)
    (println "  ok  " label)
    (do (swap! fails inc)
        (println "  FAIL" label "\n        expected" (pr-str expected)
                 "\n        got     " (pr-str actual)))))
(defn check-that [label ok] (check label (boolean ok) true))

(defn sh [& args]
  (let [p (.start (ProcessBuilder. (into-array String args)))
        ;; STDERR IS DRAINED ON ITS OWN THREAD (`DECISIONS.md#the-codec-is-guest-code`,
        ;; "the test helper deadlocked"). Reading stdout to completion and
        ;; stderr after DEADLOCKS the moment a child writes more than a pipe
        ;; buffer to stderr: the child blocks writing, this blocks reading, and
        ;; neither moves again.
        err (future (slurp (.getErrorStream p)))
        out (slurp (.getInputStream p))
        err @err]
    (.waitFor p) {:exit (.exitValue p) :out out :err err :all (str out err)}))

(def d (str (fs/create-temp-dir)))
(def proj (str (fs/create-temp-dir)))
(spit (str proj "/deps.edn")
      ;; Written as TEXT, not `pr-str`: babashka prints a map with qualified
      ;; keys as `#:git{...}`, and this file is a fixture rather than a test of
      ;; the printer. That flint reads the namespaced form is checked in the
      ;; conformance suite, where it belongs.
      (str "{:paths [\"src\" \"resources\"]\n"
           ;; A coordinate flint does not recognise at all. The kinds it DOES
           ;; fetch are exercised below, against a real repository, a real
           ;; tarball and a real jar.
           " :deps {some/thing {:weird/coord \"x\"}}\n"
           " :flint/tasks\n"
           " {greet {:doc \"say hello to $1\" :task (println \"hello\" \"$1\")}\n"
           "  bare (println \"no doc\")\n"
           "  ten (str \"$10\" \"$1\")\n"
           "  sum {:doc \"add\" :task (+ (parse-long \"$1\") (parse-long \"$2\"))}\n"
           "  joined {:requires [[clojure.string :as str]]\n"
           "          :task (str/join \"-\" [\"$1\" \"$2\"])}}}\n"))
(spit (str d "/entry.cljc")
      ;; The entry namespace is the only place the `:fs` capability appears:
      ;; it turns "read a project file" into an fs read. `cli/run` never sees a
      ;; handle, which is what lets `bin/flint` run the same code under bb.
      (str "(ns entry (:require [flint.cli :as cli] [flint.fs :as fs]))\n"
           "(defn- slurp* [p]\n"
           "  (let [h (fs/open)] (when (fs/exists? h p) (fs/read-file h p))))\n"
           "(defn main [args]\n"
           "  (let [r (cli/run (vec args) slurp*)]\n"
           "    (if (:exec r) (pr-str (:exec r)) (:out r))))\n"))

(println "cli: the command surface, as a flint program")
(let [r (sh "./bin/flint" ":src" d ":fn" "entry/main" ":out" "out/cli.wasm")]
  (when-not (zero? (:exit r)) (println "build failed:" (:all r)) (System/exit 1)))

(def driver
  (str "import('./host/flint.mjs').then(async (m) => {"
       "const {fsCapability} = await import('./host/fs.mjs');"
       "const {module} = await m.load('out/cli.wasm');"
       "const i = m.instantiate(module);"
       "if (process.argv[1] === 'grant') i.capabilities({fs: fsCapability(process.argv[2])});"
       "const r = i.run('entry/main', process.argv.slice(3));"
       "process.stdout.write(r.out);})"))

(defn cli [grant & argv]
  (str/trim (:all (apply sh (concat ["node" "-e" driver "--" (if grant "grant" "none") proj] argv)))))

(check "version" (cli true "version") "0.1.0")
(check "paths come from deps.edn" (cli true "paths") "src\nresources")
(check-that "tasks are listed with their docs"
            (and (str/includes? (cli true "tasks") "greet  -- say hello to $1")
                 (str/includes? (cli true "tasks") "bare")))
;; A task RUNS, so what comes back from the guest is a PROGRAM to compile, not a
;; printed form. The guest decides what to run; the host runs it, because
;; `flint_load_image` clears the caller's frames and a guest cannot run a task
;; from inside itself.
(check-that "a task becomes a compilable program, with its arguments bound"
            (let [x (read-string (cli true "task" "greet" "world"))]
              (and (str/includes? (:src x) "(println \"hello\" \"world\")")
                   (= (:entry x) "flint.task/main")
                   (= (:args x) ["world"]))))
(check "  ... and $10 is not eaten by $1"
       (-> (cli true "task" "ten" "a" "b" "c" "d" "e" "f" "g" "h" "i" "J")
           read-string :src (str/includes? "(str \"J\" \"a\")") boolean)
       true)
(check-that "an unknown task lists the real ones"
            (str/includes? (cli true "task" "nope") "available: bare, greet"))
;; A dependency source states what it does NOT support, in the manifest style
;; the README uses for library coverage -- rather than half-fetching and failing
;; at the first missing var.
(check-that "deps it cannot fetch are named, with the reason"
            (let [o (cli true "deps")]
              (and (str/includes? o "some/thing  (unknown)")
                   (str/includes? o "portable cljc"))))

;; The CLI has no ambient authority either. This is the property that makes the
;; capability model worth anything: it applies to the tool as much as to what
;; the tool runs.
(check-that "with no :fs grant, the CLI can read nothing"
            ;; TWO refusals now, and which one you get says something real
            ;; (`DECISIONS.md#ports-are-the-hosts`). A host that lent SOMETHING installs a
            ;; system port, so an `open` it does not recognise comes back
            ;; "refused to open". A host that lent NOTHING installs none, and
            ;; the sandbox has no way to ask anybody anything -- which is the
            ;; stronger statement and the one this row gets, because the driver
            ;; calls `capabilities` only when granting.
            ;;
            ;; Neither says "refused the capability": the cutover took that word
            ;; out of the sandbox, which does not have the concept. The PROPERTY
            ;; is what this row is for and is unchanged -- the CLI has no ambient
            ;; authority, so it applies to the tool as much as to what the tool
            ;; runs.
            (let [o (cli false "paths")]
              (or (str/includes? o "refused to open \"fs\"")
                  (str/includes? o "no system port, so it cannot ask for \"fs\""))))
(check "  ... but the commands that need no project still work"
       (cli false "version") "0.1.0")

;; `flint run` takes a module or a bytecode image. The image path needs no
;; linker anywhere, which is the shape construe's sandbox binding settled on.
(spit (str d "/hi.cljc")
      "(ns hi)\n(defn main [args] (str \"hi \" (first args)))\n")
(doseq [[a o] [[["--emit-image"] "out/cli-hi.image"] [[] "out/cli-hi.wasm"]
               [["--loader"] "out/cli-loader.wasm"]]]
  (let [r (apply sh (concat ["./bin/flint" ":src" d ":fn" "hi/main" ":out" o] a))]
    (when-not (zero? (:exit r)) (println "build failed:" (:all r)) (System/exit 1))))

(check "flint run executes a module"
       (str/trim (:out (sh "./bin/flint" "run" "out/cli-hi.wasm" "hi/main" "there"))) "hi there")
(let [env (into {} (System/getenv))
      p (doto (ProcessBuilder. (into-array String ["./bin/flint" "run" "out/cli-hi.image" "hi/main" "there"]))
          (-> .environment (.put "FLINT_LOADER" "out/cli-loader.wasm")))
      pr (.start p)
      out (slurp (.getInputStream pr))]
  (.waitFor pr)
  (check "flint run executes a bytecode IMAGE, with no linker" (str/trim out) "hi there"))

;; And end to end, through `bin/flint`: a task that PRINTS, a task that returns
;; a value, and a task with a `:requires`. flint has no ambient stdout -- a
;; program granted nothing runs pure -- so `println` cannot exist in core. The
;; task's namespace defines its own, appending to a value its `main` returns,
;; which is what flint's model says output is.
(defn task [& args]
  (str/trim (:all (apply sh (concat ["./bin/flint" "task"] args)))))
(let [cwd (System/getProperty "user.dir")]
  (try
    (System/setProperty "user.dir" proj)
    (let [run-in (fn [& args]
                   (let [pb (ProcessBuilder. (into-array String (concat [(str cwd "/bin/flint") "task"] args)))]
                     (.directory pb (io/file proj))
                     (let [p (.start pb) o (slurp (.getInputStream p)) e (slurp (.getErrorStream p))]
                       (.waitFor p) (str/trim (str o e)))))]
      (check "a task that prints, prints -- with no ambient stdout anywhere"
             (run-in "greet" "world") "hello world")
      (check "a task's VALUE is its output when it returns one"
             (run-in "sum" "2" "40") "42")
      (check "a task's :requires is honoured, in babashka's shape"
             (run-in "joined" "a" "b") "a-b"))
    (finally (System/setProperty "user.dir" cwd))))

;; Cross compilation. A target flint cannot emit for is listed and refused BY
;; NAME rather than hidden -- 0021's point being that emission is codegen and
;; codegen is pure, so what is missing is a backend and not a machine to run on.
(check-that "targets names what it can and cannot emit"
            (let [o (cli true "targets")]
              (and (str/includes? o "wasm") (str/includes? o "jvm")
                   (str/includes? o "DECISIONS.md#other-hosts"))))
(check-that "an unbuilt target is refused by name, not silently"
            (let [o (cli true "build" ":target" "jvm")]
              (and (str/includes? o "cannot emit for jvm")
                   (str/includes? o "other-hosts"))))
(check-that "an unknown target lists the real ones"
            (str/includes? (cli true "build" ":target" "wat") "no such target: wat"))
(check-that "build with no entry point says how to give one"
            (str/includes? (cli true "build") ":flint/main"))

;; `flint build` end to end: `:paths` come from deps.edn, so a build inside a
;; project needs no `:src`.
(let [cwd (System/getProperty "user.dir")
      bp (str (fs/create-temp-dir))
      run-in (fn [dir & args]
               (let [pb (ProcessBuilder. (into-array String (cons (str cwd "/bin/flint") args)))]
                 (.directory pb (io/file dir))
                 (let [p (.start pb) o (slurp (.getInputStream p)) e (slurp (.getErrorStream p))]
                   {:exit (do (.waitFor p) (.exitValue p)) :out (str/trim (str o e))})))]
  (fs/create-dirs (str bp "/code"))
  (spit (str bp "/deps.edn") "{:paths [\"code\"] :flint/main app/main}\n")
  (spit (str bp "/code/app.cljc") "(ns app)\n(defn main [args] (str \"built \" (first args)))\n")
  (check "build takes its source roots and entry point from deps.edn"
         (:exit (run-in bp "build")) 0)
  (check "  ... and the artifact runs" (:out (run-in bp "run" "out/app.wasm" "app/main" "ok")) "built ok")
  ;; The image path is the shape flint's first real consumer uses: a resident
  ;; loader instantiated once, an image loaded per call. So `--image` has to
  ;; produce something RUNNABLE, which means the loader too -- an image on its
  ;; own gave `ENOENT: out/flint-loader.wasm`, naming a path nobody chose.
  (check "  ... --image emits an image instead"
         (boolean (str/includes? (:out (run-in bp "build" "--image")) "out/app.image")) true)
  (check "  ... and the loader beside it, because an image cannot run alone"
         (boolean (fs/exists? (str bp "/out/flint-loader.wasm"))) true)
  (check "  ... so the image runs" (:out (run-in bp "run" "out/app.image" "app/main" "ok")) "built ok")
  (check "  ... and a build leaves no scratch file next to the artifact"
         (vec (sort (map fs/file-name (fs/list-dir (str bp "/out")))))
         ["app.image" "app.wasm" "flint-loader.wasm"])
  (fs/delete (str bp "/out/flint-loader.wasm"))
  ;; 0020: the metadata is READ, not merely carried. A module used where a
  ;; loader belongs is named from its bytes, before compiling half a megabyte of
  ;; wasm to find out.
  (check-that "  ... and a MODULE used as a loader is refused by name"
              (let [o (:out (run-in bp "run" "out/app.image" "app/main" "ok"))]
                (or (str/includes? o "not built with --loader")
                    (str/includes? o "no loader at"))))
  (check-that "  ... while a missing loader says what would produce one"
              (let [o (:out (run-in bp "run" "out/app.image" "app/main" "ok"))]
                (and (str/includes? o "flint build --image")
                     (not (str/includes? o "ENOENT")))))
  (check "  ... and an unbuilt target exits nonzero"
         (:exit (run-in bp "build" ":target" "jvm")) 1))

;; ---------------------------------------------------------------- git deps
;;
;; 0021's first dependency source. Resolution is in the guest and the FETCH is
;; in the host, which is the same split the rest of the CLI draws -- and the
;; reason this can be tested without a network: the "remote" here is a local
;; repository, and `git fetch` does not care.
(let [cwd (System/getProperty "user.dir")
      git (fn [dir & args]
            (let [pb (ProcessBuilder. (into-array String (concat ["git"] args)))]
              (.directory pb (io/file dir))
              (let [p (.start pb)] (slurp (.getInputStream p)) (slurp (.getErrorStream p))
                   (.waitFor p) (.exitValue p))))
      commit! (fn [dir msg]
                (git dir "add" "-A")
                (git dir "-c" "user.email=t@t" "-c" "user.name=t" "commit" "-q" "-m" msg))
      sha (fn [dir]
            (let [pb (ProcessBuilder. (into-array String ["git" "rev-parse" "HEAD"]))]
              (.directory pb (io/file dir))
              (let [p (.start pb) o (slurp (.getInputStream p))] (.waitFor p) (str/trim o))))
      flint-in (fn [dir & args]
                 (let [pb (ProcessBuilder. (into-array String (cons (str cwd "/bin/flint") args)))]
                   (.directory pb (io/file dir))
                   (let [p (.start pb) o (slurp (.getInputStream p)) e (slurp (.getErrorStream p))]
                     {:exit (do (.waitFor p) (.exitValue p)) :out (str/trim (str o e))})))
      ;; deep <- greeter <- the project, so the middle one is only discoverable
      ;; by reading a dependency's OWN deps.edn, which does not exist until it
      ;; is fetched.
      deep (str (fs/create-temp-dir))
      greeter (str (fs/create-temp-dir))
      gproj (str (fs/create-temp-dir))]
  (fs/create-dirs (str deep "/src"))
  (spit (str deep "/deps.edn") "{:paths [\"src\"]}\n")
  (spit (str deep "/src/deep.cljc")
        "(ns deep)\n(defn shout [s] (clojure.string/upper-case s))\n")
  (git deep "init" "-q" ".") (commit! deep "init")

  (fs/create-dirs (str greeter "/src/greeter"))
  (spit (str greeter "/deps.edn")
        (str "{:paths [\"src\"] :deps {my/deep {:git/url \"" deep "\" :git/sha \"" (sha deep) "\"}}}\n"))
  (spit (str greeter "/src/greeter/core.cljc")
        "(ns greeter.core (:require [deep]))\n(defn hello [who] (deep/shout (str \"hi \" who)))\n")
  (git greeter "init" "-q" ".") (commit! greeter "init")

  (fs/create-dirs (str gproj "/src"))
  (spit (str gproj "/src/app.cljc")
        "(ns app (:require [greeter.core :as g]))\n(defn main [args] (g/hello (first args)))\n")
  (let [good (str "{:paths [\"src\"] :flint/main app/main\n"
                  " :deps {my/greeter {:git/url \"" greeter "\" :git/sha \"" (sha greeter) "\"}}}\n")]
    (spit (str gproj "/deps.edn") good)

    ;; The dependency's paths come from ITS deps.edn, not from the coordinate --
    ;; which is the part that is easy to get wrong, and unobservable until the
    ;; dep has a `:paths` that is not the default.
    (check "a git dep is fetched, transitively, on the way to a build"
           (:exit (flint-in gproj "build")) 0)
    (check "  ... and the project compiles against it" (:out (flint-in gproj "run" "out/app.wasm" "app/main" "you")) "HI YOU")
    (check-that "  ... with the project's own roots first, then the deps'"
                (let [ls (str/split-lines (:out (flint-in gproj "paths")))]
                  (and (= (first ls) "src") (= (count ls) 3)
                       (every? (fn [l] (str/includes? l ".flint/git/")) (rest ls)))))
    (check "  ... a second build does not refetch"
           (boolean (str/includes? (:out (flint-in gproj "build")) "fetching")) false)
    (check "  ... and fetch says so when everything is present"
           (:out (flint-in gproj "fetch")) "all 2 dependencies are present")

    ;; A branch is not a version. Resolving one means asking the remote what it
    ;; points at TODAY, which is a different build tomorrow.
    (spit (str gproj "/deps.edn")
          (str "{:paths [\"src\"] :flint/main app/main\n"
               " :deps {my/greeter {:git/url \"" greeter "\"}}}\n"))
    (let [r (flint-in gproj "build")]
      (check-that "a git dep with no :git/sha is refused, by name and with the reason"
                  (and (str/includes? (:out r) "my/greeter")
                       (str/includes? (:out r) "no :git/sha")))
      ;; Letting it through gave `cannot find source for namespace greeter.core`,
      ;; which is true and names the wrong thing.
      (check-that "  ... rather than failing later as a missing namespace"
                  (not (str/includes? (:out r) "cannot find source"))))
    (spit (str gproj "/deps.edn") good)))

;; ---------------------------------------------------------------- npm deps
;;
;; 0021's second source, and the reason it needs no registry metadata: an EXACT
;; version determines the tarball path. A range would need metadata and would
;; also resolve to something different next week, which is the objection flint
;; already raises to a git branch.
;;
;; Tested against a `file://` registry, so the suite does not need the network.
;; The URL shape is checked against the real one separately, below.
(check "an npm tarball URL is derived from the coordinate"
       (deps-ns-npm-tarball "https://registry.npmjs.org" "squint-cljs" "0.14.208")
       "https://registry.npmjs.org/squint-cljs/-/squint-cljs-0.14.208.tgz")
(check "  ... and a scoped package drops the scope from the filename"
       (deps-ns-npm-tarball "https://registry.npmjs.org" "@scope/pkg" "1.2.3")
       "https://registry.npmjs.org/@scope/pkg/-/pkg-1.2.3.tgz")

(let [cwd (System/getProperty "user.dir")
      reg (str (fs/create-temp-dir))
      stage (str (fs/create-temp-dir))
      np (str (fs/create-temp-dir))
      flint-in (fn [dir & args]
                 (let [pb (ProcessBuilder. (into-array String (cons (str cwd "/bin/flint") args)))]
                   (.directory pb (io/file dir))
                   (let [p (.start pb) o (slurp (.getInputStream p)) e (slurp (.getErrorStream p))]
                     {:exit (do (.waitFor p) (.exitValue p)) :out (str/trim (str o e))})))]
  ;; An npm tarball unpacks into `package/`, and a package that ships cljc puts
  ;; it wherever `package.json` points -- so the package ROOT is the source
  ;; root, not `src`. A git repo of Clojure is `src` by convention; this is not.
  (fs/create-dirs (str stage "/package/util"))
  (spit (str stage "/package/package.json") "{\"name\":\"portable-cljc\",\"version\":\"2.0.1\"}")
  (spit (str stage "/package/util/text.cljc")
        "(ns util.text)\n(defn shout [s] (str (clojure.string/upper-case s) \"!\"))\n")
  (fs/create-dirs (str reg "/portable-cljc/-"))
  (let [pb (ProcessBuilder. (into-array String
                                        ["tar" "-czf" (str reg "/portable-cljc/-/portable-cljc-2.0.1.tgz") "package"]))]
    (.directory pb (io/file stage))
    (.waitFor (.start pb)))

  (fs/create-dirs (str np "/src"))
  (spit (str np "/src/app.cljc")
        "(ns app (:require [util.text :as t]))\n(defn main [args] (t/shout (first args)))\n")
  (let [ok (str "{:paths [\"src\"] :flint/main app/main\n"
                " :flint/npm-registry \"file://" reg "\"\n"
                " :deps {portable-cljc {:npm/version \"2.0.1\"}}}\n")]
    (spit (str np "/deps.edn") ok)
    (check "an npm dep is fetched and unpacked on the way to a build"
           (:exit (flint-in np "build")) 0)
    (check "  ... and the project compiles against the cljc in it"
           (:out (flint-in np "run" "out/app.wasm" "app/main" "hello")) "HELLO!")
    (check-that "  ... with the package ROOT as the source root, not src"
                (str/includes? (:out (flint-in np "paths")) "/package"))

    (spit (str np "/deps.edn") (str/replace ok "\"2.0.1\"" "\"^2.0.0\""))
    (let [r (flint-in np "build")]
      (check-that "an npm version RANGE is refused, with the reason"
                  (and (str/includes? (:out r) "portable-cljc")
                       (str/includes? (:out r) "is a range")))
      (check "  ... and exits nonzero" (:exit r) 1))))

;; -------------------------------------------------------------- maven deps
;;
;; 0021's third source, in its CHEAP half only. An exact coordinate is a derived
;; URL exactly like npm, and that part costs nothing. What 0021 prices as
;; expensive -- POM parsing, the transitive graph, version conflict resolution
;; -- is deliberately not built, and `flint deps` says so where a reader will
;; meet it rather than in a document they have not opened.
(check "a maven jar URL is derived from the coordinate"
       (fdeps/maven-jar "https://repo.clojars.org" 'metosin/malli "0.16.4")
       "https://repo.clojars.org/metosin/malli/0.16.4/malli-0.16.4.jar")

;; The GROUP's dots become path separators and the ARTIFACT's do not. Checked
;; against a real fetch from Central, not against what looks symmetrical.
(check "  ... with the group's dots as separators, and the artifact's left alone"
       (fdeps/maven-jar "https://repo1.maven.org/maven2" 'org.clojure/core.match "1.1.0")
       "https://repo1.maven.org/maven2/org/clojure/core.match/1.1.0/core.match-1.1.0.jar")

(let [cwd (System/getProperty "user.dir")
      repo (str (fs/create-temp-dir))
      stage (str (fs/create-temp-dir))
      mp (str (fs/create-temp-dir))
      flint-in (fn [dir & args]
                 (let [pb (ProcessBuilder. (into-array String (cons (str cwd "/bin/flint") args)))]
                   (.directory pb (io/file dir))
                   (let [p (.start pb) o (slurp (.getInputStream p)) e (slurp (.getErrorStream p))]
                     {:exit (do (.waitFor p) (.exitValue p)) :out (str/trim (str o e))})))]
  ;; A jar is a zip with Clojure source at its ROOT, so the extracted directory
  ;; is the source root -- as with npm, and unlike a git checkout.
  (fs/create-dirs (str stage "/portable"))
  (spit (str stage "/portable/maths.cljc")
        "(ns portable.maths)\n(defn triple [n] (* 3 n))\n")
  (fs/create-dirs (str repo "/com/example/portable/3.1.0"))
  (let [pb (ProcessBuilder. (into-array String
                                        ["zip" "-qr" (str repo "/com/example/portable/3.1.0/portable-3.1.0.jar") "portable"]))]
    (.directory pb (io/file stage))
    (.waitFor (.start pb)))

  (fs/create-dirs (str mp "/src"))
  (spit (str mp "/src/app.cljc")
        "(ns app (:require [portable.maths :as m]))\n(defn main [args] (str (m/triple 14)))\n")
  (spit (str mp "/deps.edn")
        (str "{:paths [\"src\"] :flint/main app/main\n"
             " :flint/maven-repos [\"file://" repo "\"]\n"
             " :deps {com.example/portable {:mvn/version \"3.1.0\"}}}\n"))
  (check "a maven jar is fetched and unpacked on the way to a build"
         (:exit (flint-in mp "build")) 0)
  (check "  ... and the project compiles against the source in it"
         (:out (flint-in mp "run" "out/app.wasm" "app/main")) "42")
  (check-that "  ... with the jar ROOT as the source root"
              (str/includes? (:out (flint-in mp "paths")) ".flint/mvn/"))
  ;; The limitation is stated next to the dependency, not buried -- and what
  ;; the limitation IS has changed: the transitive graph is read now, off the
  ;; POM inside the jar. What is still not read is the parts of maven that are
  ;; maven, and that is what the note has to say.
  (check-that "  ... and deps says what the POM reader does not read"
              (let [o (:out (flint-in mp "deps"))]
                (and (str/includes? o "reads the jar's own POM")
                     (str/includes? o "a parent POM, a profile, or a version range")
                     (str/includes? o "no host interop")))))

;; --- one walk, every kind (`DECISIONS.md#one-dependency-walk`) -------------
;;
;; There used to be THREE transitive mechanisms: `fetch-plan` read a fetched
;; dependency's `deps.edn` and recursed, `flint.deps.resolve/plan` read an npm
;; MANIFEST and recursed separately, and maven had neither. What made them
;; three was that the walk knew which file to read; now `flint.deps.manifest`
;; knows, one walk consumes every format, and the kinds table says which
;; formats a kind may carry.
(println "cli: manifest scanners")
(babashka.classpath/add-classpath "lib")
(require '[flint.deps.manifest :as fman] '[flint.deps.registry :as freg])

(check "package.json gives npm coordinates, dependencies only"
       (fman/package-json
        (str "{\"name\":\"x\",\"devDependencies\":{\"jest\":\"1.0.0\"},"
             "\"dependencies\":{\"left-pad\":\"^1.3.0\",\"@s/y\":\"2.0.0\"}}"))
       {:format :package-json
        :deps {'left-pad {:npm/name "left-pad" :npm/version "^1.3.0"}
               (symbol "@s/y") {:npm/name "@s/y" :npm/version "2.0.0"}}
        :paths []})
;; The case a naive `index-of "dependencies"` gets wrong, and it occurs: tools
;; put their own configuration in `package.json`, and a nested key of the same
;; name is not this package's dependency list.
(check "  ... and a NESTED \"dependencies\" is not this package's"
       (:deps (fman/package-json
               (str "{\"scripts\":{\"dependencies\":\"lie\"},"
                    "\"dependencies\":{\"real\":\"1.0.0\"}}")))
       {'real {:npm/name "real" :npm/version "1.0.0"}})
;; A backslash escapes the next character. Without that a Windows path in a
;; `"bin"` entry ends the string early and everything after it is misread.
(check "  ... and an escaped quote does not end the string early"
       (:deps (fman/package-json
               (str "{\"bin\":\"a\\\\b\\\"c\",\"dependencies\":{\"real\":\"1.0.0\"}}")))
       {'real {:npm/name "real" :npm/version "1.0.0"}})

(def pom-fixture
  (str "<project><groupId>com.example</groupId><artifactId>t</artifactId>"
       "<version>1.2.3</version>"
       "<properties><json.version>2.4.0</json.version></properties>"
       "<!-- <dependency><groupId>ghost</groupId><artifactId>g</artifactId>"
       "<version>1</version></dependency> -->"
       "<dependencyManagement><dependencies><dependency><groupId>managed</groupId>"
       "<artifactId>m</artifactId><version>9</version></dependency></dependencies>"
       "</dependencyManagement>"
       "<dependencies>"
       "<dependency><groupId>org.clojure</groupId><artifactId>data.json</artifactId>"
       "<version>${json.version}</version></dependency>"
       "<dependency><groupId>com.example</groupId><artifactId>sib</artifactId>"
       "<version>${project.version}</version></dependency>"
       "<dependency><groupId>junit</groupId><artifactId>junit</artifactId>"
       "<version>4.13</version><scope>test</scope></dependency>"
       "<dependency><groupId>opt</groupId><artifactId>o</artifactId>"
       "<version>1</version><optional>true</optional></dependency>"
       "</dependencies></project>"))

;; `dependencyManagement` is version POLICY for dependencies declared
;; elsewhere, not an edge; a `test` scope belongs to the POM's own build; an
;; optional one is the consumer's choice. Taking any of the three would put
;; things on the path that maven itself would not.
(check "a POM gives maven coordinates, with the graph's edges only"
       (:deps (fman/pom-xml pom-fixture))
       {'org.clojure/data.json {:mvn/version "2.4.0"}
        'com.example/sib {:mvn/version "1.2.3"}})
;; A jar carries its own POM, which is why maven needs no second request to
;; answer what it depends on.
(check "  ... and it is looked for inside the jar, at the coordinate's path"
       (let [t (first (filter (fn [f] (= :pom (:format f))) fman/formats))]
         ((:files t) 'com.example/thing))
       ["pom.xml" "META-INF/maven/com.example/thing/pom.xml"])

;; The precedence is the kinds table's answer, not the scanner's: a LOCAL
;; reference names its ecosystem in how it is written, and a FETCHED package
;; may carry more than its own registry understands, so `deps.edn` wins there.
(check "deps.edn beats the ecosystem's own manifest on a fetched package"
       [(fdeps/manifests-of :npm) (fdeps/manifests-of :mvn)
        (fdeps/manifests-of :local) (fdeps/manifests-of :pod)]
       [[:deps-edn :package-json] [:deps-edn :pom] [:deps-edn] [:pod]])
(check "every kind says what it carries"
       (every? (fn [t] (and (seq (:manifests t)) (:source t))) fdeps/coord-types)
       true)

;; --- transitive, for the two kinds that did not have it --------------------

(def cwd* (System/getProperty "user.dir"))
(defn flint-in* [dir & args]
  (let [pb (ProcessBuilder. (into-array String (cons (str cwd* "/bin/flint") args)))]
    (.directory pb (io/file dir))
    (let [p (.start pb) o (slurp (.getInputStream p)) e (slurp (.getErrorStream p))]
      {:exit (do (.waitFor p) (.exitValue p)) :out (str/trim (str o e))})))
(defn run-in* [dir args]
  (let [pb (ProcessBuilder. (into-array String (concat [(str cwd* "/bin/flint")] args)))]
    (.directory pb (io/file dir))
    (let [p (.start pb) o (slurp (.getInputStream p)) e (slurp (.getErrorStream p))]
      {:exit (do (.waitFor p) (.exitValue p)) :out (str/trim (str o e))})))

(println "cli: transitive resolution, every kind")

;; MAVEN. A jar's dependencies are in its POM and nothing read one, so a maven
;; dependency of a maven dependency simply was not there.
(let [repo (str (fs/create-temp-dir))
      stage (str (fs/create-temp-dir))
      proj (str (fs/create-temp-dir))
      jar! (fn [dir out]
             (let [pb (ProcessBuilder. (into-array String ["zip" "-qr" out "."]))]
               (.directory pb (io/file dir))
               (.waitFor (.start pb))))]
  ;; leaf: no dependencies of its own.
  (fs/create-dirs (str stage "/leaf/leafns"))
  (spit (str stage "/leaf/leafns/core.cljc")
        "(ns leafns.core)\n(defn shout [s] (clojure.string/upper-case s))\n")
  (fs/create-dirs (str repo "/com/example/leaf/1.0.0"))
  (jar! (str stage "/leaf") (str repo "/com/example/leaf/1.0.0/leaf-1.0.0.jar"))
  ;; trunk: depends on leaf, and says so in the POM inside its own jar.
  (fs/create-dirs (str stage "/trunk/trunkns"))
  (fs/create-dirs (str stage "/trunk/META-INF/maven/com.example/trunk"))
  (spit (str stage "/trunk/trunkns/core.cljc")
        "(ns trunkns.core (:require [leafns.core :as l]))\n(defn hi [w] (l/shout (str \"hi \" w)))\n")
  (spit (str stage "/trunk/META-INF/maven/com.example/trunk/pom.xml")
        (str "<project><groupId>com.example</groupId><artifactId>trunk</artifactId>"
             "<version>1.0.0</version><dependencies>"
             "<dependency><groupId>com.example</groupId><artifactId>leaf</artifactId>"
             "<version>1.0.0</version></dependency></dependencies></project>"))
  (fs/create-dirs (str repo "/com/example/trunk/1.0.0"))
  (jar! (str stage "/trunk") (str repo "/com/example/trunk/1.0.0/trunk-1.0.0.jar"))

  (fs/create-dirs (str proj "/src"))
  (spit (str proj "/src/app.cljc")
        "(ns app (:require [trunkns.core :as t]))\n(defn main [args] (t/hi (first args)))\n")
  (spit (str proj "/deps.edn")
        (str "{:paths [\"src\"] :flint/main app/main\n"
             " :flint/maven-repos [\"file://" repo "\"]\n"
             " :deps {com.example/trunk {:mvn/version \"1.0.0\"}}}\n"))
  (check "a maven dependency's own POM is read, and its deps fetched"
         (:exit (flint-in* proj "build")) 0)
  (check "  ... so the project compiles against the transitive one"
         (:out (run-in* proj ["run" "out/app.wasm" "app/main" "you"])) "HI YOU")
  (check-that "  ... and both jars are on the path"
              (let [o (:out (flint-in* proj "paths"))]
                (and (str/includes? o "trunk-1.0.0") (str/includes? o "leaf-1.0.0")))))

;; NPM. `package.json` was read by `flint.deps.resolve` in a second walk that
;; the thing doing the fetching never called, so a build got the package and
;; not what it depends on.
(let [reg (str (fs/create-temp-dir))
      stage (str (fs/create-temp-dir))
      proj (str (fs/create-temp-dir))
      tgz! (fn [dir out]
             (let [pb (ProcessBuilder. (into-array String ["tar" "-czf" out "package"]))]
               (.directory pb (io/file dir))
               (.waitFor (.start pb))))]
  (fs/create-dirs (str stage "/b/package/bns"))
  (spit (str stage "/b/package/package.json") "{\"name\":\"pkg-b\",\"version\":\"1.0.0\"}")
  (spit (str stage "/b/package/bns/core.cljc")
        "(ns bns.core)\n(defn shout [s] (str (clojure.string/upper-case s) \"!\"))\n")
  (fs/create-dirs (str reg "/pkg-b/-"))
  (tgz! (str stage "/b") (str reg "/pkg-b/-/pkg-b-1.0.0.tgz"))

  (fs/create-dirs (str stage "/a/package/ans"))
  (spit (str stage "/a/package/package.json")
        (str "{\"name\":\"pkg-a\",\"version\":\"1.0.0\","
             "\"devDependencies\":{\"nope\":\"9.9.9\"},"
             "\"dependencies\":{\"pkg-b\":\"1.0.0\"}}"))
  (spit (str stage "/a/package/ans/core.cljc")
        "(ns ans.core (:require [bns.core :as b]))\n(defn hi [w] (b/shout (str \"hi \" w)))\n")
  (fs/create-dirs (str reg "/pkg-a/-"))
  (tgz! (str stage "/a") (str reg "/pkg-a/-/pkg-a-1.0.0.tgz"))

  (fs/create-dirs (str proj "/src"))
  (spit (str proj "/src/app.cljc")
        "(ns app (:require [ans.core :as a]))\n(defn main [args] (a/hi (first args)))\n")
  (let [base (str "{:paths [\"src\"] :flint/main app/main\n"
                  " :flint/npm-registry \"file://" reg "\"\n")]
    (spit (str proj "/deps.edn") (str base " :deps {pkg-a {:npm/version \"1.0.0\"}}}\n"))
    (check "an npm package's package.json is read, and its deps fetched"
           (:exit (flint-in* proj "build")) 0)
    (check "  ... so the project compiles against the transitive one"
           (:out (run-in* proj ["run" "out/app.wasm" "app/main" "you"])) "HI YOU!")
    (check-that "  ... and devDependencies are NOT fetched"
                (not (str/includes? (:out (flint-in* proj "paths")) "nope")))

    ;; A RANGE arriving from somebody else's manifest. flint takes exact
    ;; versions everywhere, and the thing that makes this workable rather than
    ;; merely strict is that the refusal names the tool that fixes it.
    (fs/delete-tree (str proj "/.flint"))
    (spit (str stage "/a/package/package.json")
          "{\"name\":\"pkg-a\",\"version\":\"1.0.0\",\"dependencies\":{\"pkg-b\":\"^1.0.0\"}}")
    (tgz! (str stage "/a") (str reg "/pkg-a/-/pkg-a-1.0.0.tgz"))
    (let [r (flint-in* proj "build")]
      (check-that "a TRANSITIVE range is refused, naming the dependency nobody typed"
                  (and (str/includes? (:out r) "not declared in this deps.edn")
                       (str/includes? (:out r) "pkg-b")
                       (str/includes? (:out r) "flint deps pin")))
      (check "  ... and exits nonzero" (:exit r) 1))
    ;; And `:flint/overrides` is what makes it buildable again -- the same key
    ;; `flint deps pin` writes, now read by the walk that fetches.
    (fs/delete-tree (str proj "/.flint"))
    (spit (str proj "/deps.edn")
          (str base " :flint/overrides {pkg-b {:npm/version \"1.0.0\"}}\n"
               " :deps {pkg-a {:npm/version \"1.0.0\"}}}\n"))
    (check "an override pins a transitive, and the build proceeds"
           (:exit (flint-in* proj "build")) 0)))

;; :local/root. It was BROKEN, and invisibly: the walk asked for a
;; `.flint-fetched` stamp, which nothing writes into somebody's own source
;; tree, so every local dependency came back pending for ever and the host
;; reported `no such :local/root` for a directory that was right there.
(let [lib (str (fs/create-temp-dir))
      proj (str (fs/create-temp-dir))]
  (fs/create-dirs (str lib "/src"))
  (spit (str lib "/deps.edn") "{:paths [\"src\"]}\n")
  (spit (str lib "/src/locallib.cljc") "(ns locallib)\n(defn hi [w] (str \"hi \" w))\n")
  (fs/create-dirs (str proj "/src"))
  (spit (str proj "/src/app.cljc")
        "(ns app (:require [locallib]))\n(defn main [args] (locallib/hi (first args)))\n")
  (spit (str proj "/deps.edn")
        (str "{:paths [\"src\"] :flint/main app/main\n"
             " :deps {my/lib {:local/root \"" lib "\"}}}\n"))
  (check "a :local/root builds" (:exit (flint-in* proj "build")) 0)
  (check "  ... and the project compiles against it"
         (:out (run-in* proj ["run" "out/app.wasm" "app/main" "you"])) "hi you")
  ;; The control: the same coordinate pointing nowhere must still say so, and
  ;; say it about the directory rather than about a namespace three steps later.
  (spit (str proj "/deps.edn")
        "{:paths [\"src\"] :flint/main app/main :deps {my/lib {:local/root \"/nope/nowhere\"}}}\n")
  (let [r (flint-in* proj "build")]
    (check-that "a :local/root that is not there is named"
                (and (str/includes? (:out r) "no such :local/root")
                     (str/includes? (:out r) "my/lib")))))

;; --- pods resolve from a registry (`DECISIONS.md#pods-are-a-resolvable-dependency`)
(println "cli: pods from a registry")

(check "an artifact with no platform matches anything"
       (freg/artifact-for {:pod/artifacts [{:artifact/executable "run"}]} "macos" "aarch64")
       {:artifact/executable "run"})
(check "the NATIVE match wins, and wasm is only the fallback"
       [(:artifact/executable
         (freg/artifact-for {:pod/artifacts [{:artifact/wasm true :artifact/executable "w"}
                                             {:os/name "linux" :artifact/executable "l"}]}
                            "linux" "x86_64"))
        (:artifact/executable
         (freg/artifact-for {:pod/artifacts [{:artifact/wasm true :artifact/executable "w"}
                                             {:os/name "linux" :artifact/executable "l"}]}
                            "macos" "aarch64"))]
       ["l" "w"])
(check "no artifact and no wasm build is nil, not a guess"
       (freg/artifact-for {:pod/artifacts [{:os/name "linux" :artifact/executable "l"}]}
                          "macos" "aarch64")
       nil)
;; The default is the community registry, and flint's own is NOT in it: a
;; first-party registry that also answered for somebody else's coordinate would
;; quietly change what that coordinate means.
(check "a project resolves pods against the community registry by default"
       (freg/registries {}) freg/default-registries)
(check "  ... and flint's own tooling registry is not in that list"
       (boolean (some (fn [r] (= r freg/tooling-registry)) freg/default-registries)) false)
(check "  ... but a project may name registries, flint's included"
       (freg/registries {:flint/pod-registries ["file:///r" freg/tooling-registry]})
       ["file:///r" freg/tooling-registry])

(let [reg (str (fs/create-temp-dir))
      proj (str (fs/create-temp-dir))]
  ;; The artifact is the demo pod the sysns suite boots, published as a bare
  ;; executable -- which is one of the three shapes a registry may name.
  (fs/copy (str cwd* "/test/fixtures/demopod") (str reg "/demopod"))
  (spit (str reg "/registry.edn")
        (str "{:registry/name \"test\"\n"
             " :pods {pod.demo {\"1.0.0\" {:pod/artifacts"
             " [{:artifact/url \"file://" reg "/demopod\""
             "   :artifact/executable \"run\"}]}}}}\n"))
  (fs/create-dirs (str proj "/src"))
  (spit (str proj "/src/app.cljc")
        "(ns app (:require [pod.demo :as d]))\n(defn main [args] (str (d/add 1 2)))\n")
  (spit (str proj "/deps.edn")
        (str "{:paths [\"src\"] :flint/main app/main\n"
             " :flint/pod-registries [\"file://" reg "/registry.edn\"]\n"
             " :deps {pod.demo {:pod/version \"1.0.0\"}}}\n"))
  (let [r (flint-in* proj "fetch")]
    (check "a :pod/version is fetched through the registry" (:exit r) 0))
  ;; A FETCHED POD IS A LOCAL POD. The artifact was chosen once, where the plan
  ;; was made, so what lands on disk is the same manifest a `:pod/path` names
  ;; and the boot side never learns the difference.
  (check-that "  ... and what lands is an ordinary pod directory with a manifest"
              (let [d (str proj "/.flint/pod/pod.demo-1.0.0")]
                (and (fs/exists? (str d "/manifest.edn"))
                     (str/includes? (slurp (str d "/manifest.edn")) ":artifact/executable")
                     (fs/executable? (str d "/run")))))
  ;; A pod contributes no SOURCE: it is a separate process with its own
  ;; authority, so it must never become a source root.
  (check-that "  ... and a pod is not a source root"
              (not (str/includes? (:out (flint-in* proj "paths")) "/pod/")))
  ;; The registry document is cached like anything else, so a second round
  ;; fetches nothing.
  (check "  ... and a second fetch has nothing to do"
         (str/includes? (:out (flint-in* proj "fetch")) "are present") true)
  ;; A FETCHED POD NEEDS NO REGISTRY. Its cache directory is derived from the
  ;; coordinate alone, so the question "is it already here" can be asked before
  ;; anything is resolved -- and without that, a build with every dependency on
  ;; disk would still fail the moment the registry was unreachable.
  (fs/delete-tree (str proj "/.flint/registry"))
  (check "  ... and a pod already on disk is not resolved again"
         (str/includes? (:out (flint-in* proj "fetch")) "are present") true)

  ;; A version the registry does not have says so, naming what was searched.
  (spit (str proj "/deps.edn")
        (str "{:paths [\"src\"] :flint/main app/main\n"
             " :flint/pod-registries [\"file://" reg "/registry.edn\"]\n"
             " :deps {pod.demo {:pod/version \"9.9.9\"}}}\n"))
  (let [r (flint-in* proj "build")]
    (check-that "a pod version the registry does not hold is refused by name"
                (and (str/includes? (:out r) "pod.demo") (str/includes? (:out r) "9.9.9")))))

;; --- the coordinate table (`DECISIONS.md#system-namespaces-and-deps`) --------
;;
;; ONE table says what kinds exist and what keys each understands. It used to be
;; two -- `flint.deps/dep-kind` and `flint.deps.resolve/coord-kind` -- and they
;; had drifted: maven carried a different kind keyword in each, and a git
;; coordinate written as `:git/tag` with no `:git/url` was git to one and
;; unknown to the other. A THIRD copy lived in `bin/flint`'s fetch dispatch,
;; which is what actually broke when the keyword was unified, so these check the
;; table itself rather than any one reader of it.
(println "cli: the coordinate table")

(check "a version alone is a complete npm coordinate"
       (fdeps/dep-kind {:npm/version "1.0.0"}) :npm)
(check "maven has ONE kind keyword, matching its coordinate namespace"
       (fdeps/dep-kind {:mvn/version "1.0.0"}) :mvn)
(check "both pod forms are pods"
       [(fdeps/dep-kind {:pod/path "./p"}) (fdeps/dep-kind {:pod/version "1.0"})]
       [:pod :pod])
(check "every known kind is a supported kind"
       (= fdeps/supported-dep-kinds (set (map :kind fdeps/coord-types))) true)

;; A key set is satisfied or it is not; a coordinate that satisfies none is not
;; of that kind, however much it looks like one.
(check "a git tag with no url satisfies no git key set"
       (fdeps/dep-kind {:git/tag "v1"}) :unknown)
(check "  ... and the complaint still names git, and what is missing"
       (fdeps/coord-complaint {:git/tag "v1"}) "git coordinates need :git/url")

;; The case the `:known` lists exist for. A typo is in NO kind's key list, so
;; attribution falls to the key's namespace -- without that, the one mistake
;; this machinery is for reports as a coordinate of no kind.
(check "a typo is attributed to its kind and named"
       (fdeps/coord-complaint {:npm/verison "1.0"})
       ":npm/verison is not a key npm understands; npm coordinates need :npm/version")

;; Reported, not refused: `deps.edn` is a format flint shares with tools.deps.
(check "an unknown key on a USABLE coordinate does not refuse it"
       (fdeps/coord-complaint {:npm/version "1.0" :npm/bogus 1}) nil)
(check "  ... it is a note instead"
       (fdeps/coord-notes {:npm/version "1.0" :npm/bogus 1})
       ":npm/bogus is not a key npm understands, and is ignored")
(check "a universal key is understood by every kind"
       (fdeps/coord-notes {:local/root "." :deps/root "sub"}) nil)

;; `incomplete` and `unsupported` answer different questions, and a coordinate
;; of no known kind belongs to the second. Both firing stopped `flint task` on a
;; project that used to run.
(check "a coordinate of no known kind is not `incomplete`'s to report"
       (fdeps/coord-complaint {:weird/coord "x"}) nil)
(check "  ... it is `unsupported`'s"
       (mapv :kind (fdeps/unsupported {:deps {'some/thing {:weird/coord "x"}}}))
       [:unknown])


;; --- capability delegation on dependency entries -------------------------
;;
;; `system-namespaces-and-deps` rule 1: a project cannot lend what it does not
;; hold, or `deps.edn` becomes a way to MINT authority and the chain stops
;; being auditable from the top.
;;
;; The rule was implemented in `flint.deps.resolve` and had NO CALLER, and the
;; reason was structural: `resolve` requires virtual namespaces the CLI serves,
;; which babashka cannot load, so `flint.cli` could never require the namespace
;; the rule lived in. A rule nothing can call is a rule that does not exist.
(let [mint (str (fs/create-temp-dir))]
  (spit (str mint "/app.cljc") "(ns app)\n(defn go [_] \"built\")\n")
  (let [with (fn [deps-edn]
               (spit (str mint "/deps.edn") deps-edn)
               (:out (flint-in* mint "paths")))]
    (check-that "lending a capability the project does not hold is REFUSED"
                (str/includes?
                 (with "{:paths [\".\"] :deps {some/dep {:local/root \".\" :flint/capabilities-grant [:host]}}}")
                 "lends a capability it does not hold"))
    (check-that "  ... and it names both the dependency and what was lent"
                (let [o (with "{:paths [\".\"] :deps {some/dep {:local/root \".\" :flint/capabilities-grant [:host]}}}")]
                  (and (str/includes? o "some/dep") (str/includes? o ":host"))))
    ;; The control, which is what makes the check mean something: a project
    ;; that HOLDS the capability may lend it.
    (check-that "  ... but a project that HOLDS it may lend it"
                (not (str/includes?
                      (with (str "{:paths [\".\"] :flint/capabilities-grant [:host] "
                                 ":deps {some/dep {:local/root \".\" :flint/capabilities-grant [:host]}}}"))
                      "lends a capability it does not hold")))))


;; --- a transitive's relative path is relative to what DECLARED it ---------
;;
;; `:local/root "../lib2"` written in `lib/deps.edn` means `lib/../lib2`. It
;; was resolved against the project at the top of the walk instead, so the
;; CORRECT spelling failed and a path written as if the file sat at the root
;; worked -- the shape of bug that trains people to write the wrong thing.
(let [r (str (fs/create-temp-dir))]
  (fs/create-dirs (str r "/lib"))
  (fs/create-dirs (str r "/lib2"))
  (spit (str r "/app.cljc") "(ns app)\n(defn go [_] \"ok\")\n")
  (spit (str r "/deps.edn") "{:paths [\".\"] :deps {dep/x {:local/root \"lib\"}}}\n")
  (spit (str r "/lib2/deps.edn") "{:paths [\".\"]}\n")
  (let [with (fn [inner]
               (spit (str r "/lib/deps.edn") inner)
               (:out (flint-in* r "paths")))]
    (check-that "a transitive :local/root resolves against the deps.edn that declared it"
                (str/includes? (with "{:paths [\".\"] :deps {other/y {:local/root \"../lib2\"}}}")
                               "lib2"))
    ;; The control, and the half that proves the base moved rather than the
    ;; check being loosened: the spelling that used to work must now fail.
    (check-that "  ... so a path written as if it sat at the ROOT now fails"
                (str/includes? (with "{:paths [\".\"] :deps {other/y {:local/root \"lib2\"}}}")
                               "no such :local/root"))
    (check-that "  ... and an absolute path is left alone"
                (str/includes? (with (str "{:paths [\".\"] :deps {other/y {:local/root \"" r "/lib2\"}}}"))
                               "lib2"))))

(println (if (zero? @fails) "cli: ok" (str "cli: " @fails " FAILURES")))
(System/exit (if (zero? @fails) 0 1))
