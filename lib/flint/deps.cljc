(ns flint.deps
  "`deps.edn`, the parts of it flint can honour (`DECISIONS.md#cli`).

  ## What is supported, and what is not

  `:paths`, `:flint/tasks`, `:flint/overrides` and `:deps` of every kind flint
  knows: git, npm, maven, `:local/root` and pods. All of them resolve
  TRANSITIVELY, through one walk (`DECISIONS.md#one-dependency-walk`) -- this
  file plans, the host fetches, and `flint.deps.manifest` is what knows which
  file a given ecosystem keeps its dependency list in.

  The caution that matters more than the cost still holds, and it is why this
  reports what it cannot do BY NAME rather than half-fetching: resolving a
  coordinate gets you SOURCE, not something that compiles, and most of Clojars
  reaches for host interop flint does not have. That is the manifest style the
  README already uses for library coverage -- a dependency source states what
  it does not support."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [flint.deps.manifest :as manifest]
            [flint.deps.registry :as registry]))

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

  `:manifests` is the formats a dependency of this kind may CARRY, in
  precedence order (`flint.deps.manifest`), and `:source` is where its code
  sits when it carries no manifest saying otherwise. Both live here rather than
  in the walk for the reason the kinds themselves do: adding a kind should have
  to answer \"what does one of these say it depends on, and where is its
  source\" in the same edit, not silently default to whatever the last `cond`
  clause happened to be.

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
    :also  [:sha]
    ;; A repository is fetched by git and may then turn out to carry ANY
    ;; format: `deps.edn` if it is flint or Clojure, `package.json` if somebody
    ;; published the same tree to npm, a `pom.xml` if it builds with maven.
    ;; That is the whole reason the downloader and the scanner are separate.
    :manifests [:deps-edn :package-json :pom]
    :source :src}
   {:kind :local
    :forms [[:local/root]]
    :also  []
    ;; The COORDINATE says, for a local reference: `:local/root` is flint's own
    ;; spelling and `deps.edn` is what it means.
    :manifests [:deps-edn]
    :source :src}
   {:kind :npm
    ;; The name defaults to the dependency's own symbol, so a version alone is
    ;; a complete npm coordinate.
    :forms [[:npm/name :npm/version]
            [:npm/version]]
    :also  [:npm/integrity :npm/registry]
    ;; `deps.edn` FIRST. A flint library published to npm carries a
    ;; `package.json` because npm demands one, and a `deps.edn` saying what it
    ;; actually depends on as flint code; the second is the better answer.
    :manifests [:deps-edn :package-json]
    ;; An npm tarball is not `src`: a package that ships cljc puts it wherever
    ;; `package.json` points, and the root is the only thing always right.
    :source :root}
   {:kind :mvn
    :forms [[:mvn/version]]
    :also  [:mvn/repos]
    :manifests [:deps-edn :pom]
    :source :root}
   {:kind :pod
    ;; The two genuine alternatives: on disk, or from a registry.
    :forms [[:pod/path]
            [:pod/version]]
    :also  [:pod/registry]
    :manifests [:pod]
    ;; A pod contributes NO SOURCE. It is a separate process with its own
    ;; authority, and its surface is discovered by booting it and asking.
    :source :none}])

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

  `git`, `npm`, `maven`, `local` and `pod`. 0021's order is git, npm, maven --
  the cost order, and also the order of how likely the fetched code is to
  compile.

  EVERY ONE OF THEM RESOLVES TRANSITIVELY, through one walk
  (`DECISIONS.md#one-dependency-walk`). Maven used to be the exception and is
  not any more: a jar carries its own POM, so the graph is read off what was
  already fetched. `flint.deps/maven-note` states what the POM reader still
  does not do."
  (set (map :kind coord-types)))

(def default-maven-repos
  "Where a jar is looked for, in order. Clojars first because that is where
  Clojure libraries live; Central because `org.clojure` itself does not."
  ["https://repo.clojars.org" "https://repo1.maven.org/maven2"])

(def maven-note
  "What flint's maven support does and does not do, stated where a reader will
  meet it.

  The transitive half is BUILT now: a jar carries its own POM at
  `META-INF/maven/<group>/<artifact>/pom.xml`, so the graph is read off the
  thing that was already downloaded rather than from a second request. What is
  still not done is the parts of Maven that are Maven rather than dependency
  resolution -- parent POMs, profiles, version ranges -- and a version those
  leave unresolved is refused by name instead of guessed at."
  (str "flint fetches a maven jar at an exact version and takes the source in it,\n"
       "and reads the jar's own POM for what IT depends on. What it does NOT read:\n"
       "a parent POM, a profile, or a version range -- so a dependency whose version\n"
       "only a parent knows is reported rather than guessed. The caution that matters\n"
       "more than the cost still holds: resolving a coordinate gets you SOURCE, not\n"
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

(def stamp
  "The file a host writes into a checkout once the fetch has SUCCEEDED. Its
  presence is what `fetch-plan` reads as `:fetched?`."
  ".flint-fetched")

(defn coord-reason
  "Why flint cannot fetch this coordinate as written, or nil.

  ONE WORDING, for the same reason `coord-complaint` is one wording: this is
  asked both of a project's own `:deps` (by `incomplete`, at the top of a
  build) and of a coordinate the transitive walk reached (by `fetch-plan`,
  which has nowhere else to say it). Two spellings of `is a range` is the drift
  the coordinate table exists to end, one level up.

  A git dep without a sha is the one that matters: `deps.edn` allows it, and
  resolving it means asking the remote what a branch points at today, which is
  a different build tomorrow. flint refuses rather than doing that quietly."
  [c]
  (let [k (dep-kind c)]
    (cond
      (coord-complaint c) (coord-complaint c)
      (and (= k :git) (str/blank? (str (or (:git/sha c) (:sha c)))))
      "no :git/sha -- flint will not resolve a branch to whatever it points at today"
      (and (= k :mvn) (not (exact-version? (:mvn/version c))))
      (str ":mvn/version " (pr-str (:mvn/version c))
           " is not one exact version -- flint takes an exact version, and"
           " `flint deps pin` writes one")
      (and (= k :npm) (str/blank? (str (or (:npm/version c) (:mvn/version c)))))
      "no :npm/version"
      (and (= k :npm) (not (exact-version? (or (:npm/version c) (:mvn/version c)))))
      (str ":npm/version " (pr-str (or (:npm/version c) (:mvn/version c)))
           " is a range -- flint takes an exact version, for the same"
           " reason it takes a git sha and not a branch."
           " `flint deps pin` resolves it and writes it into :flint/overrides")
      (and (= k :local) (str/blank? (str (:local/root c))))
      "no :local/root"
      ;; A pod from a REGISTRY resolves now; what it still needs is an exact
      ;; version, for the reason every other kind does.
      (and (= k :pod) (str/blank? (str (:pod/path c)))
           (not (exact-version? (:pod/version c))))
      (str ":pod/version " (pr-str (:pod/version c))
           " is not one exact version -- a pod is pinned like everything else")
      :else nil)))

(defn- npm-base
  "The directory an npm tarball is unpacked INTO. The tarball itself contains a
  `package/`, so this is one level above the source root."
  [nm c cache]
  (let [v (or (:npm/version c) (:mvn/version c))]
    (when (exact-version? v)
      (str cache "/npm/"
           (str/replace (str/replace (str nm) "@" "") "/" "-") "-" v))))

(defn pod-dir
  "Where a pod fetched from a registry lives. `:pod/path` names its own."
  [cache nm version]
  (str cache "/pod/" (str/replace (str/replace (str nm) "@" "") "/" "-") "-" version))

(defn- coord-dir
  "Where a coordinate's content lives once fetched, or nil if flint cannot
  fetch it at all."
  [nm c cache]
  (let [k (dep-kind c)]
    (cond
      (= k :git) (let [sha (or (:git/sha c) (:sha c))]
                   (when-not (str/blank? (str sha))
                     (str cache "/git/" (git-name (:git/url c) sha))))
      ;; `/package`, because that is what an npm tarball unpacks into, and a
      ;; source root has to be the directory the namespaces are relative to.
      ;; The tarball is unpacked into the directory ABOVE -- see `:unpack`.
      (= k :npm) (when-let [b (npm-base nm c cache)] (str b "/package"))
      (= k :mvn) (let [v (:mvn/version c)]
                     (when (exact-version? v)
                       (str cache "/mvn/"
                            (str/replace (str/replace (str nm) "/" "-") ":" "-") "-" v)))
      (= k :local) (when-not (str/blank? (str (:local/root c))) (:local/root c))
      ;; A POD IS IN THE WALK NOW, and the directory is what puts it there. It
      ;; used to answer nil, which took a pod out of the plan entirely -- so a
      ;; `:pod/path` that did not exist was never reported, and a
      ;; `:pod/version` had nowhere to be fetched to.
      (= k :pod) (if (str/blank? (str (:pod/path c)))
                   (when (exact-version? (:pod/version c))
                     (pod-dir cache nm (:pod/version c)))
                   (str (:pod/path c)))
      :else nil)))

(defn- kind-entry [k] (some (fn [t] (when (= k (:kind t)) t)) coord-types))

(defn manifests-of
  "The manifest formats a kind may carry, in precedence order. Reads
  `coord-types` rather than restating it."
  [k]
  (vec (:manifests (kind-entry k))))

(defn- default-source
  "Where a dependency's code is when its manifest does not say."
  [k root]
  (case (:source (kind-entry k))
    :root [root]
    :src [(str root "/src")]
    []))

(defn- never-fetched?
  "Kinds that are ALREADY THERE by construction. `:local/root` and `:pod/path`
  name a directory rather than something to go and get, and treating them as
  unfetched is the bug this names: the walk probed for a `.flint-fetched`
  stamp, which nothing ever writes into somebody's own source tree, so every
  `:local/root` came back pending for ever and `bin/flint` reported it as `no
  such :local/root` -- for a directory that was right there."
  [k c]
  (or (= k :local) (and (= k :pod) (not (str/blank? (str (:pod/path c)))))))

(defn- rebase-relative
  "A transitive coordinate's relative path, made relative to the manifest that
  DECLARED it rather than to the project at the top of the walk.

  `:local/root \"../lib2\"` written in `lib/deps.edn` means `lib/../lib2`. It
  was being resolved against the top project instead, so the correct spelling
  failed and the wrong one -- a path written as if the file sat at the root --
  worked. That is the shape of bug that trains people to write the wrong thing.

  Absolute paths are left alone, and so is every other kind: only a path
  coordinate has a base to be wrong about."
  [kids base]
  (if (str/blank? (str base))
    kids
    (mapv (fn [e]
            (let [c (second e)
                  fix (fn [c k]
                        (let [r (str (get c k))]
                          (if (or (str/blank? r) (str/starts-with? r "/"))
                            c
                            (assoc c k (str base "/" r)))))]
              [(first e) (-> c (fix :local/root) (fix :pod/path))]))
          kids)))

(defn- kids-of
  "What a scanned manifest contributes to the walk.

  A POD MAY ONLY DEPEND ON PODS. Whatever a pod links natively is its own
  affair and invisible from here; a pod depending on ANOTHER pod is meaningful,
  and resolving it is the one piece that cannot be delegated to a driver pod,
  because the pod manager is the fixed point everything else is fetched by. A
  coordinate of any other kind in a pod's manifest is DROPPED rather than
  walked -- a subprocess must not be able to pull a compiler's worth of source
  onto the path behind itself."
  [k m]
  (let [ds (or (:deps m) {})]
    (if-not (= k :pod)
      ds
      (reduce (fn [acc e] (if (= :pod (dep-kind (val e))) (conj acc e) acc)) [] ds))))

(defn fetch-plan
  "What has to be fetched before this project can build, as data -- transitively,
  for EVERY kind (`DECISIONS.md#one-dependency-walk`).

  Resolution here, fetching in the host: the guest decides WHAT to fetch and
  where it goes, which is pure and testable, and the host runs `git`. That is
  the same split the rest of the CLI draws, and it is why `deps.edn` support can
  be tested without a network.

  Transitive, and therefore iterative. A dependency's OWN manifest is what says
  where its source is and what it depends on in turn -- the coordinate does
  not, which is the part it is easy to get wrong -- and that file does not
  exist until the thing is fetched. So an entry that is not on disk yet comes
  back with `:fetched? false` and no `:paths`, the host fetches it, and the
  host asks again. It settles when nothing is left unfetched.

  WHICH manifest is `flint.deps.manifest`'s business and not this one's. That
  is the change that made one walk possible: this used to read `deps.edn` and
  only `deps.edn`, so npm's transitives were resolved by a second walk in
  `flint.deps.resolve` and maven's were not resolved at all.

  Each entry carries `:via`, which is the DOWNLOADER it needs -- `:git`,
  `:tgz`, `:zip`, `:file`, or `:none` for something already on disk. A host
  dispatches on that and not on `:kind`: how a thing is fetched and what
  ecosystem it belongs to are different questions, and a host that switched on
  the kind is the third copy of this table that broke last time the kinds
  moved.

  `slurp*` is the project reader; `cache` is the root the host keeps checkouts
  under. `opts` may carry `:exists?`, a directory probe, and `:os/name` and
  `:os/arch`, which pod artifact selection needs."
  ([d cache slurp*] (fetch-plan d cache slurp* {}))
  ([d cache slurp* opts]
   (let [registry (or (:flint/npm-registry d) default-npm-registry)
         repos (if (seq (:flint/maven-repos d)) (:flint/maven-repos d) default-maven-repos)
         ;; AN OVERRIDE WINS AT ANY DEPTH, applied before anything is derived
         ;; from the coordinate. `flint.deps.resolve/plan` already worked this
         ;; way and this walk did not, which meant `flint deps pin` wrote pins
         ;; that the thing doing the fetching never read -- and a transitive
         ;; npm range, which arrives from a `package.json` and is not the
         ;; project's to edit, had no way of ever becoming exact.
         overrides (or (:flint/overrides d) {})
         exists? (:exists? opts)
         os (get opts :os/name "") arch (get opts :os/arch "")
         direct (set (mapv (fn [e] (str (key e))) (or (:deps d) {})))]
     (loop [todo (vec (or (:deps d) {})) seen #{} out []]
       (if (empty? todo)
         out
         (let [e (first todo)
               ;; `first`/`second` rather than `key`/`val`: the queue starts as
               ;; map entries and gains plain pairs when a transitive's relative
               ;; path is rebased, and both answer these.
               k0 (first e)
               nm (str k0)
               c (or (get overrides k0) (get overrides (symbol nm)) (second e))
               kind (dep-kind c)
               ;; A pod from a registry has to be looked up before it has a URL
               ;; at all, and the lookup needs the registry document, which is
               ;; itself a fetch. So it goes in the same batch and the fixpoint
               ;; picks the answer up on the next round.
               ;;
               ;; NOT ONCE IT IS ON DISK, though. A fetched pod needs no URL,
               ;; so asking the registry again would make an offline build fail
               ;; on a dependency it already has -- and the cache directory is
               ;; derived from the coordinate alone, which is what lets the
               ;; question be asked before resolving anything.
               pod (when (and (= kind :pod) (str/blank? (str (:pod/path c)))
                              (exact-version? (:pod/version c))
                              (nil? (slurp* (str (pod-dir cache nm (:pod/version c))
                                                 "/" stamp))))
                     (registry/resolve-pod slurp* cache d nm (:pod/version c) os arch))
               dir (coord-dir (or (:npm/name c) nm) c cache)
               root (if (str/blank? (str (:deps/root c))) dir (str dir "/" (:deps/root c)))]
           (cond
             (contains? seen nm) (recur (vec (rest todo)) seen out)

             ;; The registry document is missing, so THAT is what this round
             ;; asks for. The pod itself comes back next time round.
             (:need-registry pod)
             (let [u (:need-registry pod)
                   rd (registry/doc-dir cache u)]
               (recur (vec (rest todo)) seen
                      (if (some (fn [x] (= (:dir x) rd)) out)
                        out
                        (conj out {:dep u :kind :registry :via :file
                                   :dir rd :root rd :url u
                                   :file registry/doc-file
                                   :fetched? false :paths []}))))

             ;; UNRESOLVABLE, and SAID SO IN THE PLAN. It used to be dropped
             ;; silently, which was survivable only while the walk saw nothing
             ;; but a project's own `:deps` -- `incomplete` reported those. A
             ;; transitive coordinate has no such second reader, and dropping
             ;; one quietly means a build that fails later as `cannot find
             ;; source for namespace ...`, naming the wrong thing.
             (or (nil? dir) (:why pod))
             (recur (vec (rest todo)) (conj seen nm)
                    ;; WHAT ANOTHER READER ALREADY SAYS IS NOT SAID AGAIN. An
                    ;; unknown kind is `unsupported`'s to report, and a direct
                    ;; coordinate `coord-reason` can judge on its own is
                    ;; `incomplete`'s; saying either here as well stops a build
                    ;; twice for one fault, which is the exact mistake
                    ;; `coord-complaint` records having made before. What is
                    ;; left is what no other reader can see: a coordinate
                    ;; reached transitively, and a registry lookup that failed
                    ;; -- `incomplete` gets no cache and no reader, so it
                    ;; cannot know a pod version is absent.
                    (if (or (= :unknown kind)
                            (and (contains? direct nm) (some? (coord-reason c))))
                      out
                      (conj out {:dep nm :kind kind :via :none
                                 :direct? (contains? direct nm)
                                 :why (or (:why pod) (coord-reason c)
                                          "flint cannot resolve this coordinate")
                                 :fetched? true :paths []})))

             :else
             (let [a (:artifact pod)
                   on-disk? (never-fetched? kind c)
                   fetched? (if on-disk?
                              ;; NOTHING TO FETCH, so the question is whether
                              ;; it is there at all. A host that cannot answer
                              ;; that says so by passing no `:exists?`, and the
                              ;; benefit of the doubt goes to the directory.
                              (if exists? (boolean (exists? dir)) true)
                              (some? (slurp* (str dir "/" stamp))))
                   m (when fetched? (manifest/scan slurp* root nm (manifests-of kind)))
                   entry {:dep nm :kind kind
                          :via (cond
                                 on-disk? :none
                                 (= kind :git) :git
                                 (= kind :npm) :tgz
                                 (= kind :mvn) :zip
                                 (= kind :pod) (registry/via (:artifact/url a))
                                 :else :file)
                          :dir dir :root root
                          ;; WHERE THE ARCHIVE IS UNPACKED, which is not always
                          ;; where the content ends up: an npm tarball carries
                          ;; its own `package/`, so the source root is one
                          ;; level down from what tar is pointed at.
                          :unpack (if (= kind :npm)
                                    (npm-base (or (:npm/name c) nm) c cache)
                                    dir)
                          :url (cond
                                 (= kind :npm)
                                 (npm-tarball (or (:npm/registry c) registry)
                                              (or (:npm/name c) nm)
                                              (or (:npm/version c) (:mvn/version c)))
                                 ;; Several, tried in order: a jar is on
                                 ;; Clojars or on Central and the coordinate
                                 ;; does not say which.
                                 (= kind :mvn)
                                 (mapv (fn [r] (maven-jar r nm (:mvn/version c)))
                                       (or (:mvn/repos c) repos))
                                 (= kind :pod) (:artifact/url a)
                                 :else (:git/url c))
                          :sha (or (:git/sha c) (:sha c))
                          :sha256 (:artifact/sha256 a)
                          ;; What the host has to write down so that a fetched
                          ;; pod is indistinguishable from a `:pod/path` one:
                          ;; the artifact was chosen HERE, once, and the boot
                          ;; side only ever runs what the manifest names.
                          :exec (:artifact/executable a)
                          :fetched? (boolean fetched?)
                          :paths (when fetched?
                                   (if (seq (:paths m))
                                     (mapv (fn [p] (str root "/" p)) (:paths m))
                                     (default-source kind root)))}]
               (recur (vec (concat (rest todo) (rebase-relative (kids-of kind m) root)))
                      (conj seen nm)
                      (conj out entry))))))))))

(defn dep-paths
  "Every fetched dependency's source roots, in the order they were resolved."
  [plan]
  (vec (mapcat :paths (filter :fetched? plan))))

;; ---------------------------------------------------- capability delegation
;;
;; THESE LIVED IN `flint.deps.resolve` AND HAD NO CALLER, and the reason is
;; structural rather than an oversight: `resolve` requires `flint.deps.npm` and
;; `flint.deps.git`, which are VIRTUAL namespaces served by the CLI. Babashka
;; cannot load them, so `flint.cli` -- the command surface, which is where a
;; refusal has to happen -- could never require the namespace these were in.
;;
;; A rule nothing can call is a rule that does not exist. Moved here, where
;; `flint.cli` already reaches, and wired into the command surface.

(defn- names-of
  "A grant in either form as a set of capability NAMES.

  `system-namespaces-and-deps` lets a grant be a map of name to policy as well as a set of names,
  because a name says what KIND of authority and the policy says which routes.
  Only the names matter here: the policy is the host's, checked when a call
  happens, and this is the compile-time half (`workspace-capabilities`)."
  [g]
  (cond
    (map? g) (set (keys g))
    (or (vector? g) (set? g) (seq? g)) (set g)
    (nil? g) #{}
    :else #{g}))

(defn lending-errors
  "Every rule a `deps.edn`'s capability delegation breaks.

  Two of the three rules `system-namespaces-and-deps` states; the third is `flint deps add` writing
  the grant it found, which is the tool's job and not this one's.

  1. **You cannot lend what you do not hold.** A grant on a dependency entry
     that the project itself was never granted is refused, naming both. Without
     this, `deps.edn` would be a way to MINT authority: a project could hand a
     dependency `:fs` it never had, and the whole chain stops being auditable
     from the top.

  2. **A dependency declaring a guard must be granted it.** This is `workspace-capabilities` level
     one moved to where the coordinate is. The guard already refuses the
     `:require`; refusing here as well says so at the place a person can fix it,
     with the dependency's name in front of them rather than a namespace three
     levels down.

  `guards` is `{dep-name #{capability ..}}` -- what each dependency's own
  project file demands -- because that is read from the fetched dependency and
  is not in this file's input.

  Returns `[{:dep :missing :reason}]`, empty when all is well. REPORTED rather
  than thrown for the same reason `plan` reports: whether it is fatal is the
  front end's call, and a tool listing a graph wants to see every problem at
  once rather than the first."
  ([project deps] (lending-errors project deps {}))
  ([project deps guards]
   (let [held (names-of (:flint/capabilities-grant project))]
     (vec (concat
           ;; 1. lending what you do not hold
           (for [[nm coord] deps
                 :let [lent (names-of (:flint/capabilities-grant coord))
                       over (into #{} (remove held lent))]
                 :when (seq over)]
             {:dep nm :missing over
              :reason (str "this project lends " (pr-str over) " to " nm
                           " and was never granted it"
                           (if (seq held)
                             (str "; it holds " (pr-str held))
                             " -- it holds nothing"))})
           ;; 2. a guard that was not granted
           (for [[nm coord] deps
                 :let [needs (names-of (get guards nm))
                       lent (names-of (:flint/capabilities-grant coord))
                       short (into #{} (remove lent needs))]
                 :when (seq short)]
             {:dep nm :missing short
              :reason (str nm " requires " (pr-str short)
                           " and this deps.edn does not grant it -- add "
                           ":flint/capabilities-grant " (pr-str (vec (sort short)))
                           " to its entry")}))))))

;; ------------------------------------------------------------------ reporting
;;
;; `tree`, `why` and `pin` are all views of one plan. They live here rather than
;; in the CLI for the reason the rest of this file does: the CLI would have to
;; re-derive the graph to render it, and a second derivation is a second answer.

(defn incomplete
  "Coordinates flint would fetch but cannot as written, with the reason."
  [d]
  (reduce (fn [acc e]
            (if-let [why (coord-reason (val e))]
              (conj acc {:dep (str (key e)) :why why})
              acc))
          [] (or (:deps d) {})))

(defn refused
  "Every entry the WALK had to drop, with the reason.

  Separate from `incomplete` because it answers about the whole graph and not
  about what a person typed: a transitive npm range arrives from somebody
  else's `package.json` and is not in this project's `deps.edn` at all, so
  nothing that reads only `:deps` can report it."
  [plan]
  (vec (filter :why plan)))

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
                                ["  flint fetches git, npm, maven, :local/root and pods. 0021's"
                                 "  order is git, npm, maven -- cost order, and also the order of"
                                 "  how likely the code is to compile: flint has no host interop,"
                                 "  so a library has to be portable cljc, which most of a registry"
                                 "  is not."]))))))
