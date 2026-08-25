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
  "Coordinate kinds this build can fetch.

  `git`, `npm` and `local`. 0021's order is git, npm, maven -- the cost order,
  and also the order of how likely the fetched code is to compile. Nothing about
  that order is arbitrary: a git coordinate is a clone and a path, and an npm
  one an exact tarball, whereas maven is POM parsing, a transitive graph and
  version conflict resolution, for source that mostly will not build here
  anyway."
  #{:git :npm :local})

(def default-npm-registry "https://registry.npmjs.org")

(defn exact-version?
  "Whether `v` is one version rather than a RANGE.

  npm's tarball path is derived from an exact version, so `^1.2.0` would build a
  URL for a package that does not exist. Refusing is also the same stance flint
  takes on a git branch: a range resolves to something different next week, and
  a build that changes underneath you is not a build."
  [v]
  (let [v (str v)]
    (and (not (str/blank? v))
         (not (some (fn [c] (str/includes? v c))
                    ["^" "~" ">" "<" "*" "|" " " "=" "X" "x"]))
         (some (fn [d] (str/starts-with? v d))
               ["0" "1" "2" "3" "4" "5" "6" "7" "8" "9"]))))

(defn npm-tarball
  "The tarball URL for an EXACT name and version.

  Exact, which is why no registry metadata is needed: npm's tarball path is
  derived from the coordinate. A range would need metadata AND would resolve to
  a different package next week, which is the same objection flint raises to a
  git branch."
  [registry nm version]
  (let [base (if (str/starts-with? nm "@") (last (str/split nm #"/")) nm)]
    (str registry "/" nm "/-/" base "-" version ".tgz")))

(defn dep-kind
  "Which sort of coordinate this is, by the key that identifies it."
  [coord]
  (cond
    (:git/url coord) :git
    (:local/root coord) :local
    (:npm/name coord) :npm
    (:npm/version coord) :npm
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

(defn- git-name
  "A cache directory name for a git coordinate. Derived from the URL and the
  sha, so two coordinates that differ in either do not share a checkout, and the
  same one twice does."
  [url sha]
  (let [clean (fn [x] (str/join (mapv (fn [c] (if (re-find #"[A-Za-z0-9._-]" c) c "-"))
                                      (str/split (str x) #""))))
        base (last (str/split (str/replace (str url) #"\.git$" "") #"/"))]
    (str (clean base) "-" (subs (clean sha) 0 (min 12 (count (str sha)))))))

(defn- coord-dir
  "Where a coordinate's source lives once fetched, or nil if flint cannot fetch
  it at all."
  [nm c cache]
  (let [k (dep-kind c)]
    (cond
      (= k :git) (let [sha (or (:git/sha c) (:sha c))]
                   (when-not (str/blank? (str sha))
                     (str cache "/git/" (git-name (:git/url c) sha))))
      (= k :npm) (let [v (or (:npm/version c) (:mvn/version c))]
                   (when (exact-version? v)
                     ;; `/package`, because that is what an npm tarball unpacks
                     ;; into, and a source root has to be the directory the
                     ;; namespaces are relative to.
                     (str cache "/npm/"
                          (str/replace (str/replace (str nm) "@" "") "/" "-")
                          "-" v "/package")))
      (= k :local) (when-not (str/blank? (str (:local/root c))) (:local/root c))
      :else nil)))

(defn fetch-plan
  "What has to be fetched before this project can build, as data -- transitively.

  Resolution here, fetching in the host: the guest decides WHAT to fetch and
  where it goes, which is pure and testable, and the host runs `git`. That is
  the same split the rest of the CLI draws, and it is why `deps.edn` support can
  be tested without a network.

  Transitive, and therefore iterative. A dependency's OWN `deps.edn` is what
  says where its source is and what it depends on in turn -- the coordinate does
  not, which is the part it is easy to get wrong -- and that file does not exist
  until the thing is fetched. So an entry that is not on disk yet comes back
  with `:fetched? false` and no `:paths`, the host fetches it, and the host asks
  again. It settles when nothing is left unfetched.

  `slurp*` is the project reader; `cache` is the root the host keeps checkouts
  under."
  [d cache slurp*]
  (let [registry (or (:flint/npm-registry d) default-npm-registry)]
   (loop [todo (vec (or (:deps d) {})) seen #{} out []]
    (if (empty? todo)
      out
      (let [e (first todo)
            nm (str (key e)) c (val e)
            dir (coord-dir (or (:npm/name c) nm) c cache)
            root (if (str/blank? (str (:deps/root c))) dir (str dir "/" (:deps/root c)))]
        (if (or (nil? dir) (contains? seen nm))
          (recur (vec (rest todo)) (conj seen nm) out)
          (let [inner (when root (slurp* (str root "/deps.edn")))
                ;; A STAMP, not the presence of `deps.edn`: a project without
                ;; one is legal and means `src`, and a half-finished clone has
                ;; files in it. The host writes the stamp only after the fetch
                ;; succeeds, so its presence means exactly what it says.
                fetched? (some? (slurp* (str dir "/.flint-fetched")))
                sub (when inner (edn/read-string inner))
                entry {:dep nm :kind (dep-kind c) :dir dir :root root
                       :url (if (= (dep-kind c) :npm)
                              (npm-tarball (or (:npm/registry c) registry)
                                           (or (:npm/name c) nm)
                                           (or (:npm/version c) (:mvn/version c)))
                              (:git/url c))
                       :sha (or (:git/sha c) (:sha c))
                       :fetched? (boolean fetched?)
                       :paths (when fetched?
                                (if (seq (:paths sub))
                                  (mapv (fn [p] (str root "/" p)) (:paths sub))
                                  ;; No `deps.edn` of its own, so a default per
                                  ;; kind. A git repo of Clojure is `src` by
                                  ;; convention; an npm tarball is not -- a
                                  ;; package that ships cljc puts it wherever
                                  ;; `package.json` points, and the root is the
                                  ;; only thing that is always right.
                                  (if (= (dep-kind c) :npm) [root] [(str root "/src")])))}]
            (recur (vec (concat (rest todo) (or (:deps sub) {})))
                   (conj seen nm)
                   (conj out entry)))))))))

(defn dep-paths
  "Every fetched dependency's source roots, in the order they were resolved."
  [plan]
  (vec (mapcat :paths (filter :fetched? plan))))

(defn incomplete
  "Coordinates flint would fetch but cannot as written, with the reason.

  A git dep without a sha is the one that matters: `deps.edn` allows it, and
  resolving it means asking the remote what a branch points at today, which is
  a different build tomorrow. flint refuses rather than doing that quietly."
  [d]
  (reduce (fn [acc e]
            (let [nm (str (key e)) c (val e) k (dep-kind c)]
              (cond
                (and (= k :git) (str/blank? (str (or (:git/sha c) (:sha c)))))
                (conj acc {:dep nm :why "no :git/sha -- flint will not resolve a branch to whatever it points at today"})
                (and (= k :npm) (str/blank? (str (or (:npm/version c) (:mvn/version c)))))
                (conj acc {:dep nm :why "no :npm/version"})
                (and (= k :npm) (not (exact-version? (or (:npm/version c) (:mvn/version c)))))
                (conj acc {:dep nm :why (str ":npm/version " (pr-str (or (:npm/version c) (:mvn/version c)))
                                             " is a range -- flint takes an exact version, for the same"
                                             " reason it takes a git sha and not a branch")})
                (and (= k :local) (str/blank? (str (:local/root c))))
                (conj acc {:dep nm :why "no :local/root"})
                :else acc)))
          [] (or (:deps d) {})))

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

(def stamp
  "The file a host writes into a checkout once the fetch has SUCCEEDED. Its
  presence is what `fetch-plan` reads as `:fetched?`."
  ".flint-fetched")

(defn describe
  "A one-line summary of what was found, for `flint deps`."
  [d]
  (let [u (unsupported d)
        bad (incomplete d)]
    (str/join "\n"
              (concat [(str "paths: " (str/join ", " (paths d)))
                       (str "tasks: " (str/join ", " (sort (keys (tasks d)))))]

                      (if (empty? bad)
                        []
                        (concat ["dependencies that are supported but unusable as written:"]
                                (mapv (fn [x] (str "  " (:dep x) "  -- " (:why x))) bad)))
                      (if (empty? u)
                        []
                        (concat ["dependencies this build cannot fetch:"]
                                (mapv (fn [x] (str "  " (:dep x) "  (" (name (:kind x)) ")")) u)
                                ["  flint fetches git, npm and :local/root. 0021's order is git,"
                                 "  npm, maven -- cost order, and also the order of how likely the"
                                 "  code is to compile: flint has no host interop, so a library has"
                                 "  to be portable cljc, which most of a registry is not."]))))))
