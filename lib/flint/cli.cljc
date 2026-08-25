(ns flint.cli
  "The command surface, as a flint program (`doc/decisions/0021`).

  It lives here rather than in `bin/flint` because `bin/flint` is a babashka
  script, and 0021's strongest argument is that flint should be usable by
  someone with no Clojure toolchain -- which is currently everyone. The logic
  that has to survive that move is the logic in this file; what remains is a
  host wrapper, and a host wrapper is mechanical.

  Everything here reaches the filesystem through the `:fs` CAPABILITY, so the
  CLI has no ambient authority either: run it with no grant and it can read
  nothing."
  (:require [clojure.string :as str]
            [flint.deps :as deps]
            [flint.fs :as fs]))

(def version "0.1.0")

(defn- usage []
  (str/join "\n"
            [(str "flint " version)
             ""
             "  flint tasks              list the tasks in deps.edn"
             "  flint task <name> [...]  print a task's form, with its arguments bound"
             "  flint deps               what deps.edn asks for, and what this build honours"
             "  flint paths              the source roots"
             "  flint version"
             ""
             "  and, from the host side, `flint run <file>` runs a MODULE or a"
             "  bytecode IMAGE -- the image path needs no linker anywhere"
             "  (doc/decisions/0023)."
             ""
             "Everything reads the project through the :fs capability, so a run"
             "granted nothing can read nothing."]))

(defn- task-form
  "A task's form with `$1`, `$2`... replaced by the invocation's arguments, which
  is babashka's shape and the one a `deps.edn` reader expects."
  [form args]
  (reduce (fn [s i]
            (str/replace s (str "$" (inc i)) (str (nth args i))))
          (pr-str form)
          (range (count args))))

(defn run
  "The whole CLI, as a function of `argv` and a THUNK that opens the filesystem.

  A thunk rather than a handle, because `version` and `help` need no project and
  should not need a grant to answer. Opening eagerly made `flint version` fail
  with `the host refused the capability \"fs\"`, which is the capability model
  working and the CLI asking for the wrong thing."
  [argv open-fs]
  (let [cmd (first argv)
        rest-args (vec (rest argv))]
    (cond
      (or (nil? cmd) (= cmd "help") (= cmd "--help")) (usage)
      (= cmd "version") version
      :else
      (let [d (deps/read-deps (open-fs))]
        (cond
          (= cmd "paths") (str/join "\n" (deps/paths d))
          (= cmd "deps") (deps/describe d)
          (= cmd "tasks")
          (let [t (deps/tasks d)]
            (if (empty? t)
              "no :flint/tasks in deps.edn"
              (str/join "\n"
                        (mapv (fn [k]
                                (let [v (get t k)]
                                  (str "  " k (when (:doc v) (str "  -- " (:doc v))))))
                              (sort (keys t))))))
          (= cmd "task")
          (let [name (first rest-args)
                t (get (deps/tasks d) name)]
            (cond
              (nil? name) "which task? `flint tasks` lists them"
              (nil? t) (str "no such task: " name
                            "\navailable: " (str/join ", " (sort (keys (deps/tasks d)))))
              :else (task-form (:task t) (vec (rest rest-args)))))
          :else (str "no such command: " cmd "\n\n" (usage)))))))
