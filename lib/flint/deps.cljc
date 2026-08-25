(ns flint.deps
  "`deps.edn`, the parts of it flint can honour (`doc/decisions/0021`).

  ## What is supported, and what is not

  `:paths` and `:flint/tasks` are honoured. `:deps` are **read and reported but
  not fetched**: 0021 puts them in the order git, npm, maven, which is both the
  cost order and the order of how likely the fetched code is to build — and
  states the caution that matters more than the cost, that resolving a
  coordinate gets you SOURCE and not something that compiles. Most of Clojars
  reaches for host interop flint does not have.

  So this reports what it cannot do, by name, rather than half-fetching. That is
  the manifest style the README already uses for library coverage: a dependency
  source states what it does not support."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def supported-dep-kinds
  "Coordinate kinds this build can fetch. Nothing yet -- `:paths` and tasks come
  first because they need no network and no resolver (`0021`)."
  #{})

(defn dep-kind
  "Which sort of coordinate this is, by the key that identifies it."
  [coord]
  (cond
    (:git/url coord) :git
    (:local/root coord) :local
    (:npm/name coord) :npm
    (:mvn/version coord) :maven
    :else :unknown))

(defn read-deps
  "Read `deps.edn` through `slurp*`, a function from a project-relative path to
  its text or nil. An absent `deps.edn` is an empty project.

  A function rather than an fs handle: what this namespace needs is *can I read
  a project file*, not the shape of the `:fs` capability. That seam is what lets
  the same code run under the bootstrap host and inside a compiled module,
  which is the whole reason the logic is here and not in `bin/flint`."
  [slurp*]
  (let [t (slurp* "deps.edn")]
    (if (nil? t) {} (edn/read-string t))))

(defn paths
  "Source roots, defaulting to `src` as `deps.edn` does."
  [d]
  (let [p (:paths d)]
    (if (seq p) (vec p) ["src"])))

(defn tasks
  "`:flint/tasks`, in babashka's shape: a map of name to `{:doc :task}` or to a
  bare form. A bare form is the task."
  [d]
  (let [t (:flint/tasks d)]
    (reduce (fn [m e]
              (let [k (key e) v (val e)]
                (assoc m (str (if (keyword? k) (name k) k))
                       (if (map? v) v {:task v}))))
            {} (or t {}))))

(defn unsupported
  "Every dependency this build cannot fetch, with the reason, so a project that
  will not work says so at the start rather than at the first missing var."
  [d]
  (reduce (fn [acc e]
            (let [k (dep-kind (val e))]
              (if (contains? supported-dep-kinds k)
                acc
                (conj acc {:dep (str (key e)) :kind k}))))
          [] (or (:deps d) {})))

(defn describe
  "A one-line summary of what was found, for `flint deps`."
  [d]
  (let [u (unsupported d)]
    (str/join "\n"
              (concat [(str "paths: " (str/join ", " (paths d)))
                       (str "tasks: " (str/join ", " (sort (keys (tasks d)))))]
                      (if (empty? u)
                        []
                        (concat ["dependencies this build cannot fetch:"]
                                (mapv (fn [x] (str "  " (:dep x) "  (" (name (:kind x)) ")")) u)
                                ["  flint fetches none yet. 0021's order is git, npm, maven --"
                                 "  cost order, and also the order of how likely the code is to"
                                 "  compile: flint has no host interop, so a library has to be"
                                 "  portable cljc."]))))))
