;; The CLI surface (`doc/decisions/0021`).
;;
;; The logic lives in `lib/flint/cli.cljc` and `lib/flint/deps.cljc` -- as flint
;; code, reaching the project through the `:fs` CAPABILITY -- because 0021's
;; strongest argument is that flint should be usable by someone with no Clojure
;; toolchain, and `bin/flint` is a babashka script. What has to survive that move
;; is this logic; the rest is a host wrapper.
;;
;; So the test runs the CLI as a compiled flint program, not as a script.
(require '[clojure.string :as str] '[babashka.fs :as fs] '[clojure.java.io :as io])

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
        out (slurp (.getInputStream p)) err (slurp (.getErrorStream p))]
    (.waitFor p) {:exit (.exitValue p) :out out :err err :all (str out err)}))

(def d (str (fs/create-temp-dir)))
(def proj (str (fs/create-temp-dir)))
(spit (str proj "/deps.edn")
      ;; Written as TEXT, not `pr-str`: babashka prints a map with qualified
      ;; keys as `#:git{...}`, and this file is a fixture rather than a test of
      ;; the printer. That flint reads the namespaced form is checked in the
      ;; conformance suite, where it belongs.
      (str "{:paths [\"src\" \"resources\"]\n"
           ;; Kinds flint CANNOT fetch. The kinds it can are exercised
           ;; against a real repository further down.
           " :deps {some/jar {:mvn/version \"1\"}\n"
           "        some/tarball {:npm/name \"x\"}}\n"
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
       "const {codec} = await import('./host/edn.mjs');"
       "const {module} = await m.load('out/cli.wasm');"
       "const i = m.instantiate(module);"
       "if (process.argv[1] === 'grant') i.capabilities({fs: fsCapability(process.argv[2], codec)});"
       "const r = i.main(...process.argv.slice(3));"
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
              (and (str/includes? o "some/jar  (maven)")
                   (str/includes? o "some/tarball  (npm)")
                   (str/includes? o "portable cljc"))))

;; The CLI has no ambient authority either. This is the property that makes the
;; capability model worth anything: it applies to the tool as much as to what
;; the tool runs.
(check-that "with no :fs grant, the CLI can read nothing"
            (str/includes? (cli false "paths") "refused the capability"))
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
       (str/trim (:out (sh "./bin/flint" "run" "out/cli-hi.wasm" "there"))) "hi there")
(let [env (into {} (System/getenv))
      p (doto (ProcessBuilder. (into-array String ["./bin/flint" "run" "out/cli-hi.image" "there"]))
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
                   (str/includes? o "doc/decisions/0010"))))
(check-that "an unbuilt target is refused by name, not silently"
            (let [o (cli true "build" ":target" "jvm")]
              (and (str/includes? o "cannot emit for jvm")
                   (str/includes? o "0010"))))
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
  (check "  ... and the artifact runs" (:out (run-in bp "run" "out/app.wasm" "ok")) "built ok")
  (check "  ... --image emits an image instead"
         (boolean (str/includes? (:out (run-in bp "build" "--image")) "out/app.image")) true)
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
    (check "  ... and the project compiles against it" (:out (flint-in gproj "run" "out/app.wasm" "you")) "HI YOU")
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

(println (if (zero? @fails) "cli: ok" (str "cli: " @fails " FAILURES")))
(System/exit (if (zero? @fails) 0 1))
