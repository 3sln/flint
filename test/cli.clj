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
           " :deps {io.github.x/portable {:git/url \"u\" :git/sha \"s\"}\n"
           "        some/jar {:mvn/version \"1\"}}\n"
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
              (and (str/includes? o "io.github.x/portable  (git)")
                   (str/includes? o "some/jar  (maven)")
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

(println (if (zero? @fails) "cli: ok" (str "cli: " @fails " FAILURES")))
(System/exit (if (zero? @fails) 0 1))
