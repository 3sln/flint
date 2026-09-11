(ns flint.deps
  "`deps.edn`, the parts of it flint can honour (`DECISIONS.md#cli`).

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

(def universal-coord-keys
  "Keys meaningful on a coordinate of ANY kind, so no kind has to list them."
  #{:deps/root})

(def coord-types
  "EVERY coordinate kind flint knows, the key sets that define one, and every
  other key it understands.

  ONE LIST, deliberately. This used to be two -- `dep-kind` here and
  `coord-kind` in `flint.deps.resolve` -- written separately and drifted: maven
  had a different kind keyword in each, and a git coordinate written as
  `:git/tag` with no `:git/url` was git to one and unknown to the other.
  Neither disagreement was reachable through a supported path, which is exactly
  why they survived: two tables that agree on the common cases look like one
  table until something uncommon arrives.

  `:forms` is a list of ALTERNATIVE KEY SETS, and a coordinate is of this kind
  when it carries every key in ANY ONE of them. This is one idea rather than
  two: \"what marks this kind\" and \"what does it require\" are the same
  question asked of a complete coordinate, and splitting them into a one-of and
  an all-of made `{:pod/path}` and `{:git/url :git/sha}` look like different
  species of rule when they are the same rule.

  An ARRAY, walked in order, first satisfied set winning -- so order is
  PRECEDENCE, and a coordinate carrying keys from two ecosystems resolves the
  same way everywhere rather than depending on which reader saw it. Within a
  kind the MORE SPECIFIC set comes first, so the form that gets reported is the
  one the author most nearly wrote.

  `:also` is every other key the kind understands: optional settings, and
  spellings read as fallbacks. It is what makes a TYPO REPORTABLE -- without it
  `{:npm/verison \"1.0\"}` is not an npm coordinate with a misspelling, it is a
  coordinate of no kind at all, and the message says so and helps nobody.

  The functions that act on a kind stay separate -- resolving, fetching and
  pinning are genuinely different jobs. What must not be separate is the answer
  to `what kinds are there, and what may they say`."
  [{:kind :git
    ;; PINNED FORMS FIRST. All three are git; which one was written decides
    ;; whether `incomplete` has anything to say about resolving a branch.
    :forms [[:git/url :git/sha]
            [:git/url :git/tag]
            [:git/url :git/version]
            [:git/url]]
    ;; `:sha` bare is read as a fallback for `:git/sha`, so it is known rather
    ;; than reported as a typo for the thing it is a spelling of.
    :also  [:sha]}
   {:kind :local
    :forms [[:local/root]]
    :also  []}
   {:kind :npm
    ;; The name defaults to the dependency's own symbol, so a version alone is
    ;; a complete npm coordinate.
    :forms [[:npm/name :npm/version]
            [:npm/version]]
    :also  [:npm/integrity :npm/registry]}
   {:kind :mvn
    :forms [[:mvn/version]]
    :also  [:mvn/repos]}
   {:kind :pod
    ;; The two genuine alternatives: on disk, or from a registry.
    :forms [[:pod/path]
            [:pod/version]]
    :also  []}])

(defn- has-all? [coord form] (every? (fn [k] (get coord k)) form))

(defn coord-type
  "The first entry with a satisfied key set, or nil."
  [coord]
  (some (fn [t] (when (some (fn [form] (has-all? coord form)) (:forms t)) t))
        coord-types))

(defn- kind-keys
  "Every key one kind understands, across all its forms."
  [t]
  (into (set (:also t)) (mapcat identity (:forms t))))

(defn coord-candidate
  "The kind a coordinate was REACHING FOR when no set was satisfied.

  Without this an incomplete coordinate is simply of no kind, and the only
  honest message is \"flint cannot resolve this\" -- which is true and useless
  next to \"git needs :git/url, and this has only :git/tag\". The candidate is
  the kind sharing the most keys with what was written."
  [coord]
  (let [scored (keep (fn [t]
                       (let [ks (kind-keys t)
                             ;; THE KEY'S NAMESPACE IS THE STRONGER SIGNAL, and
                             ;; the only one that survives a typo: `:npm/verison`
                             ;; is in no kind's key list, so matching by key
                             ;; alone cannot attribute it and the misspelling --
                             ;; the single case this exists for -- reports as a
                             ;; coordinate of no kind. The namespaces ARE the
                             ;; kind names, which is the convention every
                             ;; coordinate already follows.
                             nsname (name (:kind t))
                             n (count (filter (fn [k]
                                                (or (contains? ks k)
                                                    (= nsname (namespace k))))
                                              (keys coord)))]
                         (when (pos? n) [n t])))
                     coord-types)]
    (second (last (sort-by first scored)))))

(defn coord-problems
  "What is wrong with one coordinate, as data.

  `{:kind k :missing [..] :unknown [..]}`. `:missing` is the absent keys of the
  NEAREST key set -- the one the author most nearly wrote -- and `:unknown` is
  keys the kind does not understand, which is how a typo gets named instead of
  silently doing nothing."
  [coord]
  (let [matched (coord-type coord)
        t (or matched (coord-candidate coord))]
    (if-not t
      {:kind :unknown :missing [] :unknown []}
      (let [known (into universal-coord-keys (kind-keys t))
            nearest (when-not matched
                      (first (sort-by (fn [form]
                                        (count (remove (fn [k] (get coord k)) form)))
                                      (:forms t))))]
        {:kind (:kind t)
         :matched? (some? matched)
         :missing (vec (remove (fn [k] (get coord k)) nearest))
         :unknown (vec (remove (fn [k] (contains? known k)) (keys coord)))}))))

(defn coord-complaint
  "One sentence about why a coordinate CANNOT BE USED, or nil if it can.

  Fatal only. A coordinate that satisfies a key set is usable, even carrying a
  key this kind does not know -- `deps.edn` is a format flint shares with
  tools.deps, real files carry keys flint has no opinion about (`:exclusions`),
  and refusing a build over one would be flint asserting ownership of a format
  it borrows. Those are `coord-notes`, which is reporting rather than refusing.

  ONE WORDING, used by every funnel that refuses a coordinate. `incomplete` and
  `flint.deps.resolve/plan` both do it and both have to say why; two spellings
  of the same complaint is the same drift this file's one table exists to end,
  one level up."
  [coord]
  (let [{:keys [kind matched? missing unknown]} (coord-problems coord)]
    (cond
      matched? nil
      ;; A COORDINATE OF NO KNOWN KIND IS `unsupported`'s TO REPORT, not this
      ;; function's. They are different questions -- "flint has never heard of
      ;; this" against "this is an npm coordinate with a hole in it" -- and
      ;; answering both here made `incomplete` fire on a project that
      ;; `unsupported` already described, which stops a build twice for one
      ;; fault and stopped `flint task` on a project that used to run.
      (= :unknown kind) nil
      ;; THE UNKNOWN KEY FIRST, because it explains the missing one:
      ;; `{:npm/verison "1.0"}` is also "no :npm/version", and reporting only
      ;; that sends a reader looking at a key they can see is right there.
      (seq unknown)
      (str (str/join ", " (map str unknown))
           (if (= 1 (count unknown)) " is not a key " " are not keys ")
           (name kind) " understands"
           (when (seq missing)
             (str "; " (name kind) " coordinates need "
                  (str/join " and " (map str missing)))))
      (seq missing)
      (str (name kind) " coordinates need " (str/join " and " (map str missing)))
      :else nil)))

(defn coord-notes
  "Worth saying about a USABLE coordinate, or nil.

  A key its kind does not understand: harmless to this build, and almost always
  either a typo that silently did nothing or a setting meant for another tool.
  Reported rather than refused -- see `coord-complaint`."
  [coord]
  (let [{:keys [kind matched? unknown]} (coord-problems coord)]
    (when (and matched? (seq unknown))
      (str (str/join ", " (map str unknown))
           (if (= 1 (count unknown)) " is not a key " " are not keys ")
           (name kind) " understands, and is ignored"))))

(def supported-dep-kinds
  "Coordinate kinds this build can fetch.

  `git`, `npm`, `maven` and `local`. 0021's order is git, npm, maven -- the cost
  order, and also the order of how likely the fetched code is to compile.

  Maven is here in its CHEAP half only. An exact coordinate is a derived URL,
  exactly like npm, and that part costs nothing. What 0021 prices as expensive
  is the rest -- POM parsing, the transitive graph, version conflict resolution
  -- and its caution is that resolving a coordinate gets you SOURCE, not
  something that compiles. So flint fetches one jar at one version and does not
  pretend to resolve a graph; see `flint.deps/maven-note`."
  (set (map :kind coord-types)))

(def default-maven-repos
  "Where a jar is looked for, in order. Clojars first because that is where
  Clojure libraries live; Central because `org.clojure` itself does not."
  ["https://repo.clojars.org" "https://repo1.maven.org/maven2"])

(def maven-note
  "What flint's maven support does NOT do, stated where a reader will meet it."
  (str "flint fetches a maven jar at an exact version and takes the source in it.\n"
       "It does NOT resolve the transitive graph: a jar's own dependencies are not\n"
       "fetched, so name them yourself. 0021 prices that work and states the reason\n"
       "it is not obviously worth it -- resolving a coordinate gets you source, not\n"
       "something that compiles, and flint has no host interop."))

(defn maven-jar
  "The jar URL for an exact `group/artifact` and version."
  [repo dep version]
  (let [i (str/index-of (str dep) "/")
        group (if i (subs (str dep) 0 i) (str dep))
        artifact (if i (subs (str dep) (inc i)) (str dep))]
    (str repo "/" (str/replace group "." "/") "/" artifact "/" version
         "/" artifact "-" version ".jar")))

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
  "Which sort of coordinate this is, by the key set it satisfies.

  Reads `coord-types` rather than repeating it. A key present but nil does NOT
  count -- the old `cond` tested the VALUE, and a coordinate written
  `{:git/url nil}` has said nothing about being git."
  [coord]
  (:kind (coord-type coord) :unknown))

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
      (= k :mvn) (let [v (:mvn/version c)]
                     (when (exact-version? v)
                       (str cache "/mvn/"
                            (str/replace (str/replace (str nm) "/" "-") ":" "-") "-" v)))
      (= k :local) (when-not (str/blank? (str (:local/root c))) (:local/root c))
      ;; NIL ON PURPOSE, and it is the whole reason a pod fits here without a
      ;; special case downstream. A pod contributes no SOURCE: it is a separate
      ;; process with its own authority, and its surface is discovered by
      ;; booting it and asking. `fetch-plan` skips a coordinate whose dir is
      ;; nil, so a pod never becomes a source root and never gets compiled --
      ;; which is what it means for the host, not the resolver, to own it.
      (= k :pod) nil
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
  (let [registry (or (:flint/npm-registry d) default-npm-registry)
        repos (if (seq (:flint/maven-repos d)) (:flint/maven-repos d) default-maven-repos)]
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
                       :url (cond
                              (= (dep-kind c) :npm)
                              (npm-tarball (or (:npm/registry c) registry)
                                           (or (:npm/name c) nm)
                                           (or (:npm/version c) (:mvn/version c)))
                              ;; Several, tried in order: a jar is on Clojars or
                              ;; on Central and the coordinate does not say
                              ;; which.
                              (= (dep-kind c) :mvn)
                              (mapv (fn [r] (maven-jar r nm (:mvn/version c)))
                                    (or (:mvn/repos c) repos))
                              :else (:git/url c))
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
                                  (if (contains? #{:npm :mvn} (dep-kind c))
                                    [root]
                                    [(str root "/src")])))}]
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
            (let [nm (str (key e)) c (val e) k (dep-kind c)
                  probs (coord-problems c)]
              (cond
                (coord-complaint c)
                (conj acc {:dep nm :why (coord-complaint c)})
                (and (= k :git) (str/blank? (str (or (:git/sha c) (:sha c)))))
                (conj acc {:dep nm :why "no :git/sha -- flint will not resolve a branch to whatever it points at today"})
                (and (= k :mvn) (not (exact-version? (:mvn/version c))))
                (conj acc {:dep nm :why (str ":mvn/version " (pr-str (:mvn/version c))
                                             " is not one exact version -- flint does not resolve"
                                             " a range or a graph")})
                (and (= k :npm) (str/blank? (str (or (:npm/version c) (:mvn/version c)))))
                (conj acc {:dep nm :why "no :npm/version"})
                (and (= k :npm) (not (exact-version? (or (:npm/version c) (:mvn/version c)))))
                (conj acc {:dep nm :why (str ":npm/version " (pr-str (or (:npm/version c) (:mvn/version c)))
                                             " is a range -- flint takes an exact version, for the same"
                                             " reason it takes a git sha and not a branch")})
                (and (= k :local) (str/blank? (str (:local/root c))))
                (conj acc {:dep nm :why "no :local/root"})
                ;; A pod from a REGISTRY is the half that is not built. Said
                ;; here rather than left to fail at boot, because the failure
                ;; would otherwise be "could not start the pod \"\"" -- a
                ;; message about an empty path that says nothing about why.
                (and (= k :pod) (str/blank? (str (:pod/path c))))
                (conj acc {:dep nm :why (str ":pod/version needs a pod registry, which is not built yet"
                                             " -- a pod is resolvable today only as :pod/path, naming a"
                                             " directory that holds its manifest")})
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
                      ;; The maven caveat goes where a reader will meet it --
                      ;; next to their own maven dependency -- rather than in a
                      ;; document they have not opened.
                      (if (some (fn [e] (= :mvn (dep-kind (val e)))) (or (:deps d) {}))
                        (concat [""] (str/split-lines maven-note))
                        [])
                      (if (empty? u)
                        []
                        (concat ["dependencies this build cannot fetch:"]
                                (mapv (fn [x] (str "  " (:dep x) "  (" (name (:kind x)) ")")) u)
                                ["  flint fetches git, npm and :local/root. 0021's order is git,"
                                 "  npm, maven -- cost order, and also the order of how likely the"
                                 "  code is to compile: flint has no host interop, so a library has"
                                 "  to be portable cljc, which most of a registry is not."]))))))
