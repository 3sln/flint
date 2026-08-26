(ns flint.cli
  "The command surface, as a flint program (`doc/decisions/0021`).

  It lives here rather than in `bin/flint` because `bin/flint` is a babashka
  script, and 0021's strongest argument is that flint should be usable by
  someone with no Clojure toolchain -- which is currently everyone. The logic
  that has to survive that move is the logic in this file; what remains is a
  host wrapper, and a host wrapper is mechanical.

  Everything here reaches the filesystem through the `:fs` CAPABILITY, so the
  CLI has no ambient authority either: run it with no grant and it can read
  nothing.

  ## Why `run` returns a map

  Most commands answer with text. `task` cannot: running a task means compiling
  a form and instantiating a module for it, and `flint_load_image` clears the
  frames of the caller -- so a guest cannot run a task from inside itself. The
  guest decides WHAT to run and the host runs it, which is the same split 0021
  draws for everything else the host keeps."
  (:require [clojure.string :as str]
            [flint.deps :as deps]))

(def version "0.1.0")

(defn- usage []
  (str/join "\n"
            [(str "flint " version)
             ""
             "  flint tasks              list the tasks in deps.edn"
             "  flint task <name> [...]  run a task"
             "  flint build [:target t] [:fn ns/fn] [:out f] [--image]"
             "                           compile the project; :paths come from deps.edn"
             "  flint fetch              fetch the dependencies flint can fetch"
             "  flint deps               what deps.edn asks for, and what this build honours"
             "  flint paths              the source roots"
             "  flint targets            what this build can cross compile for"
             "  flint check [:src d]     what was written for flint and does nothing"
             "  flint inspect <file>     what a built module says about itself (0020)"
             "  flint version"
             ""
             "  and, from the host side, `flint run <file>` runs a MODULE or a"
             "  bytecode IMAGE -- the image path needs no linker anywhere"
             "  (doc/decisions/0023)."
             ""
             "Everything reads the project through the :fs capability, so a run"
             "granted nothing can read nothing."]))

;; ------------------------------------------------------------------- targets

(def targets
  "What `flint build :target ...` can emit, and what it cannot yet.

  Stated as data because 0021 makes a point about it that is easy to lose:
  emitting for a target has nothing to do with running on it. Codegen is pure,
  so a macOS binary emits JVM bytecode with no JVM present. The targets differ
  in their BACKENDS, which is `0010`'s work, not in the toolchain around them."
  [{:target "wasm" :ok true
    :note "a self-contained module, or a bytecode image (0023)"}
   {:target "jvm" :ok false
    :note "needs the JVM backend -- doc/decisions/0010"}
   {:target "clr" :ok false
    :note "needs the CLR backend -- doc/decisions/0010"}])

(defn target-named
  "The target entry for `nm`, or nil."
  [nm]
  (first (filter (fn [t] (= (:target t) nm)) targets)))

(defn- describe-targets []
  (str/join "\n"
            (concat ["targets:"]
                    (mapv (fn [t]
                            (str "  " (if (:ok t) " " "!") " " (:target t)
                                 "   " (:note t)))
                          targets)
                    [""
                     "A target flint cannot emit for is listed rather than hidden:"
                     "emission is codegen and codegen is pure, so what is missing is"
                     "a backend and not a machine to run it on."])))

;; --------------------------------------------------------------------- tasks

(defn- substitute
  "Replace `$1`, `$2`... with the invocation's arguments, walking the FORM
  rather than its printed text.

  Printed text was the first version and it is wrong in a way that would have
  been found late: `str/replace` on `pr-str` output rewrites `$1` inside a
  string that merely contains it, and rewrites the `$1` of `$10` before `$10`
  is ever considered."
  [form args]
  (let [sub (fn sub [s]
              (reduce (fn [acc i]
                        ;; Descending, so `$10` is taken before `$1` can eat it.
                        (let [i (- (count args) i)]
                          (str/replace acc (str "$" i) (str (nth args (dec i))))))
                      s (range (count args))))
        walk (fn walk [x]
               (cond
                 (string? x) (sub x)
                 (symbol? x) (let [n (sub (str x))]
                               (if (= n (str x)) x (symbol n)))
                 (map? x) (reduce (fn [m e] (assoc m (walk (key e)) (walk (val e)))) {} x)
                 (vector? x) (mapv walk x)
                 (set? x) (into #{} (map walk x))
                 (seq? x) (apply list (mapv walk x))
                 :else x))]
    (walk form)))

(def ^:private task-preamble
  "`println`, `print` and `prn`, defined INSIDE the task's own namespace.

  flint has no ambient stdout -- that is the capability model, not an omission:
  a program granted nothing runs pure, so `println` cannot exist in core. But
  `(println ...)` is the shape every babashka task is written in, and a task
  that will not compile is not a task.

  So the task gets them, and they append to a vector the task's `main` returns.
  The output is a VALUE, which is what flint's model actually says it is; the
  host is what turns a returned string into bytes on a terminal."
  (str "(def ^:private -out (volatile! []))\n"
       "(defn println [& xs] (vswap! -out conj (apply println-str xs)) nil)\n"
       "(defn print [& xs] (vswap! -out conj (apply print-str xs)) nil)\n"
       "(defn prn [& xs] (vswap! -out conj (str (apply pr-str xs) \"\\n\")) nil)\n"))

(defn task-source
  "The task, as a complete flint program.

  A task RUNS, so it has to be compiled, and the smallest thing that compiles is
  a namespace. `:requires` is honoured in babashka's shape. The form's value, if
  it is not nil, is printed after whatever it printed -- so `(+ 1 2)` is a
  usable task and not only `(println ...)`."
  [t args]
  (let [reqs (:requires t)
        form (substitute (:task t) args)]
    (str "(ns flint.task"
         (when (seq reqs) (str "\n  (:require " (str/join "\n            " (mapv pr-str reqs)) ")"))
         ")\n\n"
         task-preamble
         "\n(defn main [args]\n"
         "  (let [v " (pr-str form) "]\n"
         "    (str (flint.rt/str-join @-out)\n"
                  ;; A string comes back RAW and everything else readable. A task that
         ;; answers with a path should print the path, not the path in quotes.
         "         (cond (nil? v) \"\"\n"
         "               (string? v) (str v \"\\n\")\n"
         "               :else (str (pr-str v) \"\\n\")))))\n")))

;; ----------------------------------------------------------------------- run

(defn- namespace*
  "The namespace half of an `ns/fn` string."
  [entry]
  (subs entry 0 (str/index-of entry "/")))

(defn- parse-opts
  "`:key value` and `--flag`, in the shape `bin/flint` already accepts. Returns
  a map of string to string; a flag's value is the flag."
  [argv]
  (loop [a (seq argv) m {}]
    (if-let [k (first a)]
      (cond
        (str/starts-with? k "--") (recur (next a) (assoc m (subs k 2) (subs k 2)))
        (str/starts-with? k ":") (recur (next (next a)) (assoc m (subs k 1) (second a)))
        :else (recur (next a) m))
      m)))

(defn- text [s] {:out s})
(defn- oops [s] {:out s :code 1})

(defn run
  "The whole CLI, as a function of `argv` and a THUNK that opens the filesystem.

  `slurp*` maps a project-relative path to its text, or nil. A function rather
  than an `:fs` handle for two reasons. It is opened LAZILY, so `version` and
  `help` need no grant -- opening eagerly made `flint version` fail with `the
  host refused the capability \"fs\"`, which is the capability model working
  correctly and the CLI asking for the wrong thing. And it is the seam that lets
  this same code run under the bootstrap host, where there is no port.

  Returns `{:out text}`, optionally with `:code`, or `{:exec {...}}` for a task
  the host should compile and run."
  [argv slurp*]
  (let [cmd (first argv)
        rest-args (vec (rest argv))
        cache ".flint"
        loader-path "out/flint-loader.wasm"]
    (cond
      (or (nil? cmd) (= cmd "help") (= cmd "--help")) (text (usage))
      (= cmd "version") (text version)
      (= cmd "targets") (text (describe-targets))
      :else
      (let [d (deps/read-deps slurp*)
            plan (deps/fetch-plan d cache slurp*)
            pending (filterv (fn [x] (not (:fetched? x))) plan)
            ;; The project's own roots FIRST. A dependency must not be able to
            ;; shadow the namespace of the project that depends on it.
            all-paths (vec (concat (deps/paths d) (deps/dep-paths plan)))]
        (cond
          ;; A dependency flint would fetch but cannot as written stops the
          ;; build HERE, naming itself. Letting it through produced `cannot find
          ;; source for namespace greeter.core`, which is true and names the
          ;; wrong thing: the namespace is missing because a coordinate was
          ;; unusable, and that is what the reader needs told.
          (and (seq (deps/incomplete d))
               (contains? #{"build" "task"} cmd))
          (oops (str/join "\n"
                          (concat ["this project depends on something flint cannot fetch as written:"]
                                  (mapv (fn [x] (str "  " (:dep x) "  -- " (:why x)))
                                        (deps/incomplete d)))))

          ;; Anything that needs a source root needs the dependencies on disk
          ;; first, and the host is what fetches. `fetch-plan` is transitive, so
          ;; this is a fixpoint the host drives rather than one pass.
          (and (seq pending)
               (contains? #{"build" "task" "paths"} cmd))
          {:fetch pending :then argv}

          (= cmd "fetch")
          (if (seq pending)
            {:fetch pending :then ["deps"]}
            (text (if (empty? plan)
                    "nothing to fetch"
                    (str "all " (count plan) " dependencies are present"))))

          (= cmd "paths") (text (str/join "\n" all-paths))
          (= cmd "deps") (text (deps/describe d))
          (= cmd "tasks")
          (let [t (deps/tasks d)]
            (if (empty? t)
              (text "no :flint/tasks in deps.edn")
              (text (str/join "\n"
                              (mapv (fn [k]
                                      (let [v (get t k)]
                                        (str "  " k (when (:doc v) (str "  -- " (:doc v))))))
                                    (sort (keys t)))))))
          ;; `flint build` -- the front end 0021 asks for. What it adds over
          ;; `flint :src ... :fn ...` is the project: `:paths` come from
          ;; `deps.edn`, so a build in a project needs no `:src` at all.
          (= cmd "build")
          (let [opts (parse-opts rest-args)
                tname (get opts "target" "wasm")
                t (target-named tname)
                entry (or (get opts "fn") (some-> (:flint/main d) str))]
            (cond
              (nil? t) (oops (str "no such target: " tname "\n\n" (describe-targets)))
              (not (:ok t)) (oops (str "flint cannot emit for " tname " yet: " (:note t)
                                       "\n\nEmission is codegen and codegen is pure, so what is"
                                       "\nmissing is a backend and not a machine to run it on."))
              (nil? entry) (oops (str "which entry point? `flint build :fn my.ns/main`,"
                                      "\nor put `:flint/main my.ns/main` in deps.edn"))
              (not (str/includes? entry "/")) (oops (str "an entry point is `ns/fn`, not " entry))
              :else
              (let [image? (contains? opts "image")
                    out (or (get opts "out")
                            (str "out/" (str/replace (namespace* entry) "." "-")
                                 (if image? ".image" ".wasm")))]
                {:build {:target tname
                         :entry entry
                         :paths all-paths
                         :out out
                         :image? image?
                         ;; An image is not runnable on its own -- it needs a
                         ;; loader module to be instantiated into. So building
                         ;; one builds the other, rather than leaving the user
                         ;; with an artifact and `ENOENT: out/flint-loader.wasm`
                         ;; naming a path they never chose.
                         ;;
                         ;; One loader serves every image: it carries all the
                         ;; builtins and an image re-resolves them by NAME
                         ;; (0023), so this is not per-program.
                         :loader (when image? loader-path)}})))

          (= cmd "task")
          (let [nm (first rest-args)
                t (get (deps/tasks d) nm)]
            (cond
              (nil? nm) (oops "which task? `flint tasks` lists them")
              (nil? t) (oops (str "no such task: " nm
                                  "\navailable: " (str/join ", " (sort (keys (deps/tasks d))))))
              :else {:exec {:src (task-source t (vec (rest rest-args)))
                            :entry "flint.task/main"
                            :paths all-paths
                            :args (vec (rest rest-args))}}))
          :else (oops (str "no such command: " cmd "\n\n" (usage))))))))

(defn run-text
  "`run`, flattened to the text a host would print. The `:exec` case has no text
  answer, so it says so rather than inventing one."
  [argv slurp*]
  (let [r (run argv slurp*)]
    (or (:out r)
        (str "this command runs a program; the host must execute it: "
             (or (-> r :exec :entry) (-> r :build :entry))))))
