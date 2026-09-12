(ns flint.deps.registry
  "Where a pod comes from (`DECISIONS.md#pods-are-a-resolvable-dependency`).

  A pod is an ordinary `:deps` entry. `:pod/path` names a directory that
  already holds a manifest; `:pod/version` names a version to go and get, and
  this is the half that says where from.

  ## Two registries, two jobs

  **The in-repo registry serves flint's own TOOLING pods** -- the dependency
  drivers and whatever else sheds out of the binary -- and nothing else. It
  exists so flint's own build does not depend on third-party infrastructure
  being up.

  **A user's dependencies resolve against the COMMUNITY registry**, which is
  where the pods people publish live. The two are kept apart deliberately: a
  first-party registry that also answered for `pod.org/postgres` would quietly
  change what somebody else's coordinate means, and that is worth more than the
  convenience of one lookup path.

  A project may name its own registries with `:flint/pod-registries`, exactly
  as it may name `:flint/npm-registry` and `:flint/maven-repos` -- including
  flint's own, for a project that wants the tooling pods as ordinary
  dependencies. The default just does not assume it.

  ## The document

  One EDN map per registry, fetched like anything else and cached like
  anything else:

      {:registry/name \"...\"
       :pods {pod.demo {\"1.2.0\" {:pod/artifacts
                                  [{:os/name \"macos\" :os/arch \"aarch64\"
                                    :artifact/url \"https://.../demo-macos.tgz\"
                                    :artifact/sha256 \"...\"
                                    :artifact/executable \"run\"}
                                   {:artifact/wasm true
                                    :artifact/url \"https://.../demo.wasm\"
                                    :artifact/executable \"demo.wasm\"}]}}}}

  This is what a pod registry already is -- name a pod, name its versions, say
  which artifact matches which host -- because prebuilt per platform is what a
  pod IS rather than one distribution option among several."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def community-registry
  "Where a user's pod dependencies resolve from when a project says nothing.

  **It is not serving yet**, and that is stated here rather than discovered as
  a confusing fetch failure: until it is, a `:pod/version` needs
  `:flint/pod-registries` naming a registry that is -- which is the same key a
  project uses to point at a private one, so nothing about the shape changes
  when the community one comes up."
  "https://flint.3sln.com/pods/registry.edn")

(def tooling-registry
  "flint's own, in this repository, for the pods flint's toolchain needs.

  A path rather than a URL: it ships in the tree, so the CLI reads it without
  reaching anywhere. It is NOT in `default-registries` -- a project that wants
  these pods says so."
  "registry/pods.edn")

(def default-registries [community-registry])

(defn registries
  "The registries a project resolves pod coordinates against, in order."
  [d]
  (let [rs (:flint/pod-registries d)]
    (if (seq rs) (vec rs) default-registries)))

(defn cache-key
  "A directory name for a registry document, derived from its URL so two
  registries never share a cached copy and the same one twice does."
  [url]
  (let [clean (fn [x] (str/join (mapv (fn [c] (if (re-find #"[A-Za-z0-9._-]" c) c "-"))
                                      (str/split (str x) #""))))]
    (clean url)))

(defn doc-dir
  "Where a registry document is cached."
  [cache url]
  (str cache "/registry/" (cache-key url)))

(def doc-file
  "The name the cached document is written under, inside `doc-dir`."
  "registry.edn")

(defn read-doc
  "A cached registry document, or nil if it has not been fetched."
  [slurp* cache url]
  (let [t (slurp* (str (doc-dir cache url) "/" doc-file))]
    (when t
      (try (edn/read-string t)
           ;; A registry that does not read is not a registry. Treated as
           ;; absent so the next one in the list gets its turn, rather than
           ;; failing the build over somebody else's bad document.
           (catch Throwable _ nil)))))

(defn matches-platform?
  "Whether an artifact entry is for this host.

  An artifact naming neither an OS nor an architecture matches ANYTHING, which
  is what makes a one-artifact manifest -- the shape a pod under development
  has -- work without saying the same thing three times. The names are flint's
  own (`std::env::consts`: `macos`, `linux`, `windows`; `aarch64`, `x86_64`),
  because that is what the CLI compares against when it boots one."
  [a os arch]
  (let [ok (fn [k actual]
             (let [v (get a k)]
               (or (nil? v) (= (str v) (str actual)) (= (str v) "*"))))]
    (and (not (:artifact/wasm a)) (ok :os/name os) (ok :os/arch arch))))

(defn artifact-for
  "The artifact to fetch for this host, or nil.

  A NATIVE match first, and a wasm build only as the fallback, so the matrix
  does not have to be complete: publish natives for the platforms worth
  publishing for, publish one wasm build beside them, and the tail is covered
  by a host that can run it."
  [entry os arch]
  (let [as (vec (:pod/artifacts entry))]
    (or (some (fn [a] (when (matches-platform? a os arch) a)) as)
        (some (fn [a] (when (:artifact/wasm a) a)) as))))

(defn find-version
  "The registry entry for `nm` at `version`, or nil. Exact versions only -- a
  range would make what a build means depend on when it ran, which is the same
  objection flint raises to a git branch and to an npm range."
  [doc nm version]
  (get-in doc [:pods (symbol (str nm)) (str version)]))

(defn via
  "Which DOWNLOADER an artifact URL needs, from what it ends in.

  This is the other axis: how a thing is fetched is independent of what
  manifest it turns out to carry. `:file` is a bare executable, and the host
  makes it executable because a downloaded binary is not."
  [url]
  (let [u (str url)]
    (cond
      (or (str/ends-with? u ".tgz") (str/ends-with? u ".tar.gz")) :tgz
      (str/ends-with? u ".zip") :zip
      :else :file)))

(defn resolve-pod
  "What has to happen for one `:pod/version` coordinate, as data.

  Three answers, and the caller acts on which one it got:

  * `{:need-registry url}` -- a registry document is not cached yet. Fetching
    it is an ordinary fetch, so it goes in the same batch as everything else
    and the fixpoint picks the resolution up on the next round.
  * `{:artifact a :registry url}` -- resolved.
  * `{:why \"...\"}` -- it cannot be resolved, with the sentence to print."
  [slurp* cache d nm version os arch]
  (let [rs (registries d)
        docs (mapv (fn [u] [u (read-doc slurp* cache u)]) rs)
        missing (some (fn [[u doc]] (when (nil? doc) u)) docs)]
    (cond
      missing {:need-registry missing}
      :else
      (let [hit (some (fn [[u doc]]
                        (when-let [e (find-version doc nm version)] [u e]))
                      docs)]
        (cond
          (nil? hit)
          {:why (str "no pod " nm " " version " in "
                     (str/join ", " rs)
                     (when (= rs default-registries)
                       (str " -- the community registry is not serving yet;"
                            " name one with :flint/pod-registries")))}
          :else
          (let [[u e] hit
                a (artifact-for e os arch)]
            (if (nil? a)
              {:why (str nm " " version " has no artifact for " os " " arch
                         " and no wasm build to fall back to")}
              {:registry u :artifact a})))))))
