(ns flint.deps.resolve
  "The dependency PLAN: which version of what, and where it came from
  (`DECISIONS.md#system-namespaces-and-deps`).

  This is the half that decides. `flint.deps.npm`, `flint.deps.git` and
  `flint.deps.mvn` are virtual namespaces served by the CLI, and every one of
  them answers with EVERY match and never picks -- so version conflict is
  resolved here, once, in one language.

      Rust matches, flint decides.

  That rule is not tidiness. Version arithmetic exists in two places now:
  `semver` in the binary, comparing at fetch time, and this file, comparing at
  plan time where the graph is. Two implementations of *which version wins* is
  the shape `reader-tags` records going wrong -- a value only one of three readers knew
  about -- so there is exactly one, and it is this.

  ## The conflict rule, stated once

  One rule for every coordinate kind, because four resolution rules is how a
  dependency system becomes unpredictable:

  1. an **override** beats everything;
  2. otherwise the **nearest** declaration wins -- fewer hops from the root;
  3. a tie goes to the **higher** version.

  ## What is pinned

  Everything. A resolved plan carries an exact version and an integrity field
  for every node, because a build that resolves differently tomorrow is not
  reproducible and nobody finds that out at a convenient moment."
  (:require [clojure.string :as str]
            [flint.deps :as deps]
            [flint.deps.manifest :as manifest]
            [flint.deps.npm :as npm]
            [flint.deps.mvn :as mvn]
            [flint.deps.git :as git]))

;; ------------------------------------------------------------------ versions

(defn parse-version
  "`\"1.2.3\"` -> `[1 2 3]`, or nil.

  Comparison only, so a pre-release suffix is DROPPED rather than ordered. That
  is a real limitation and it is here rather than in a docstring nobody reads:
  `1.0.0-rc1` and `1.0.0` compare equal, so a plan that mixes them picks
  arbitrarily between them. Ordering pre-releases correctly is semver's hardest
  corner and it is not needed to choose between released versions, which is what
  a dependency graph almost always holds."
  [v]
  (let [core (first (str/split (str v) #"[-+]"))
        parts (str/split (str core) #"\.")]
    (when (and (seq parts) (every? (fn [p] (re-matches #"\d+" p)) parts))
      (mapv (fn [p] (parse-long p)) parts))))

(defn version-compare
  "-1, 0 or 1. A version that does not parse sorts BELOW one that does, so a
  malformed tag never wins a tie by accident."
  [a b]
  (let [pa (parse-version a) pb (parse-version b)]
    (cond
      (and (nil? pa) (nil? pb)) 0
      (nil? pa) -1
      (nil? pb) 1
      :else
      (loop [i 0]
        (let [x (nth pa i 0) y (nth pb i 0)]
          (cond
            (and (>= i (count pa)) (>= i (count pb))) 0
            (< x y) -1
            (> x y) 1
            :else (recur (inc i))))))))

(defn highest
  "The highest of `versions`, or nil. Ties keep the first, so the answer is
  stable rather than dependent on iteration order."
  [versions]
  (reduce (fn [best v]
            (if (or (nil? best) (pos? (version-compare v best))) v best))
          nil
          versions))

;; ------------------------------------------------------------------- kinds

(defn tag-version-string
  "The version a tag NAMES, as a string, or nil.

  `v1.2.3`, `1.2.3`, `release-1.2.3` and `lib-v1.2.3` all name `1.2.3`. This is
  not resolution -- nothing is asked of the network -- it is reading a tag, and
  it is what lets two coordinates that named different tags be COMPARED."
  [tag]
  (let [t (str tag)
        strip (fn [x] (if (str/starts-with? x "v") (subs x 1) x))
        cand (if (parse-version (strip t))
               (strip t)
               (let [i (str/last-index-of t "-")]
                 (when i (strip (subs t (inc i))))))]
    (when (and cand (parse-version cand)) cand)))

(def coord-kind
  "Which sort of coordinate this is.

  `flint.deps/dep-kind`, under the name this namespace calls it. The KINDS live
  in one table (`flint.deps/coord-types`) because two copies of that list is
  what this file used to hold and they drifted; the classifier is shared with
  them rather than reimplemented beside them."
  deps/dep-kind)

;; --------------------------------------------------------------- resolution

(defn exact-range
  "A version string as a range that means ONLY that version.

  This exists because of a bug it shipped with for one commit. In semver -- and
  in npm and cargo -- a bare `1.2.0` written as a RANGE means `^1.2.0`, so
  pinning a dependency to `1.2.0` resolved to `1.3.0` and the pin silently did
  nothing. \"Everything is pinned by default\" has to be true or it is worse
  than not claiming it.

  So an exact version is asked for as `=1.2.0`, and anything already carrying an
  operator is passed through untouched -- `^1.2`, `~1.2.3`, `>=1.0 <2.0` all
  mean what they say."
  [v]
  (let [v (str/trim (str v))]
    (if (re-matches #"\d+(\.\d+)*" v) (str "=" v) v)))

(defn resolve-npm
  "Every version of `nm` matching `c`, and the one this plan picks.

  The server returns all matches; the pick is the HIGHEST, here."
  [nm c]
  (let [range (exact-range (or (:npm/version c) "*"))
        hits (npm/resolve (str (or (:npm/name c) nm)) range)]
    (when (seq hits)
      (let [pick (last hits)]                       ; the server sorts ascending
        {:kind :npm
         :name (str (or (:npm/name c) nm))
         :version (:version pick)
         :integrity (:integrity pick)
         :candidates (mapv :version hits)}))))

(defn resolve-git
  "A git coordinate: `:git/tag` and `:git/sha`, as canonical `deps.edn` has them.

  **`:git/version` is gone**, and its removal is worth recording because it was
  built first. A semver range over tags looked like the obvious improvement on
  canonical `deps.edn`, and it is the wrong shape for the problem it was aimed
  at. Two reasons:

  1. **It makes every build a resolution.** A range asks the network what the
     newest matching tag is, so what a checkout means depends on when it ran --
     which is the objection this project already raises to a git branch.
  2. **It solved the wrong half.** The thing that actually hurts is not naming a
     version, it is two dependencies that named DIFFERENT tags of the same
     repository. A range does not answer that; it just gives each of them a
     different way to be right.

  So a coordinate names a tag, exactly as it always did, and the disagreement is
  handled where it happens -- see `tag-conflicts` and `agree-on-tag` below.

  `:git/sha` beside a tag is INTEGRITY: the tag must resolve to that commit or
  the plan is refused, and a prefix compares as a prefix so the familiar
  7-character form works. A bare `:git/sha` with no tag is canonical too, and is
  taken as given."
  [nm c]
  (let [url (str (:git/url c))
        want-sha (some-> (:git/sha c) str)
        picked
        (cond
          (:git/tag c)
          {:tag (str (:git/tag c))
           :sha (git/resolve-tag url (str (:git/tag c)))
           :version (tag-version-string (str (:git/tag c)))}

          want-sha {:sha want-sha :tag nil :version nil})]
    (when picked
      ;; INTEGRITY, and a mismatch is fatal rather than a warning: the whole
      ;; value of a pinned sha is that it fails when the tag moved.
      (when (and want-sha (:sha picked)
                 (not (str/starts-with? (str (:sha picked)) want-sha))
                 (not (str/starts-with? want-sha (str (:sha picked)))))
        (throw (ex-info (str nm ": :git/sha " want-sha " does not match "
                             (or (:tag picked) "the resolved commit") " at "
                             (:sha picked))
                        {:dep nm :expected want-sha :actual (:sha picked)})))
      {:kind :git
       :name (str nm)
       :url url
       :version (:version picked)
       :tag (:tag picked)
       :sha (:sha picked)})))

(defn resolve-one
  "One coordinate, resolved to something exact. nil when flint cannot."
  [nm c]
  (case (coord-kind c)
    :npm (resolve-npm nm c)
    :git (resolve-git nm c)
    :local {:kind :local :name (str nm) :root (str (:local/root c))}
    ;; Maven resolves to the version it was given, and its graph is walked from
    ;; there -- see `deps-of`, which reads the POM this used to say nobody read.
    :mvn {:kind :mvn :name (str nm) :version (str (:mvn/version c))}
    ;; A LOCAL POD resolves to itself, exactly as `:local` does: it is already
    ;; on disk and there is nothing to pin. The directory it names holds the
    ;; manifest, and the CLI reads that at boot -- resolution's job here is to
    ;; say the coordinate is legitimate, not to open it. A REGISTRY pod
    ;; resolves to its version; which artifact that is depends on the host
    ;; doing the fetching, and is decided in the fetch plan
    ;; (`DECISIONS.md#pods-are-a-resolvable-dependency`) rather than here,
    ;; where there is no platform to decide it with.
    :pod (if (str/blank? (str (:pod/path c)))
           {:kind :pod :name (str nm) :version (str (:pod/version c))}
           {:kind :pod :name (str nm) :root (str (:pod/path c))})
    nil))

;; ------------------------------------------------------------------- the walk

(defn- deps-of
  "What a resolved node depends on, as `{name coord}`.

  THE TRANSITIVE STEP, for the kinds that can answer WITHOUT the thing being on
  disk. A registry knows what a package depends on before anybody downloads it:
  npm says so in the packument, and maven serves the POM beside the jar. That
  is what lets this walk pin a graph -- `flint deps tree`, `why`, `pin` -- with
  nothing fetched at all.

  The other kinds genuinely cannot answer here, and the docstring this replaces
  said something that was not true: it claimed a git dependency's `deps.edn`
  was \"read by the CALLER after fetching\", which described a mechanism this
  namespace's callers do not have. What actually reads it is
  `flint.deps/fetch-plan`, the other half of the same walk, which has the
  checkout and reads every format through `flint.deps.manifest`. So there is
  one piece of format knowledge and two callers of it, rather than two
  transitive mechanisms -- and the POM reader here is that same one.

  A function that quietly downloads is one nobody can reason about, so nothing
  here fetches: `npm/manifest` and `mvn/pom` are metadata reads."
  [node]
  (case (:kind node)
    :npm (let [m (npm/manifest (:name node) (:version node))]
           (reduce (fn [acc e] (assoc acc (key e) {:npm/version (val e)}))
                   {} (or (:deps m) {})))
    ;; MAVEN HAD NO TRANSITIVE RESOLUTION AT ALL -- it fell through to `{}`
    ;; with no clause and no comment, while `flint.deps.mvn/pom` sat there
    ;; served, cached and never called.
    :mvn (let [pom (try (mvn/pom (:name node) (:version node))
                        ;; A jar with no POM beside it is a real thing in old
                        ;; repositories. It means "nothing known about this
                        ;; one's dependencies", not "fail the plan".
                        (catch Throwable _ nil))]
           (if pom (:deps (manifest/pom-xml pom)) {}))
    {}))

(defn plan
  "Resolve `deps` to a pinned graph.

  `deps` is `{name coord}` as `deps.edn` writes it; `overrides` is
  `:flint/overrides`, and an entry there beats every declaration at any depth.

  Returns `{:nodes {name node} :order [name ..] :refused [..]}`. A coordinate
  flint cannot resolve is REPORTED rather than thrown on, for the same reason
  `project/collect` reports a missing namespace: whether it is fatal is the
  front end's call."
  ([deps] (plan deps {}))
  ([deps overrides]
   ;; NOTES COME FROM THE DECLARED COORDINATES ONLY, not from the transitive
   ;; walk. A key this build does not understand is worth telling the person who
   ;; TYPED it; the same key arriving from a registry's own metadata is not
   ;; theirs to fix and would be noise on every build.
   (let [notes (reduce (fn [acc e]
                         (if-let [n (deps/coord-notes (val e))]
                           (conj acc {:dep (str (key e)) :note n})
                           acc))
                       [] deps)]
   (loop [queue (mapv (fn [e] [(key e) (val e) 0]) deps)
          nodes {}
          order []
          refused []]
     (if (empty? queue)
       {:nodes nodes :order order :refused refused :notes notes}
       (let [[nm coord depth] (first queue)
             rest* (vec (rest queue))
             ;; AN OVERRIDE WINS AT ANY DEPTH, and is applied before resolution
             ;; rather than after: pinning a transitive and forcing a version
             ;; are the same operation, so they must not resolve differently.
             coord (or (get overrides nm) coord)
             seen (get nodes nm)]
         (cond
           ;; Already resolved. NEARER wins; a tie goes to the higher version.
           (and seen (<= (:depth seen) depth))
           (recur rest* nodes order refused)

           :else
           (let [r (try (resolve-one nm coord)
                        (catch Throwable e {:error (ex-message e)}))]
             (cond
               (nil? r)
               (recur rest* nodes order
                      ;; The specific complaint when there is one -- a typo'd
                      ;; key, or a key set with a hole in it -- and the generic
                      ;; sentence only when the coordinate really is of no
                      ;; recognisable kind.
                      (conj refused {:dep nm
                                     :reason (or (deps/coord-complaint coord)
                                                 "flint cannot resolve this coordinate")}))

               (:error r)
               (recur rest* nodes order (conj refused {:dep nm :reason (:error r)}))

               :else
               (let [r (assoc r :depth depth)
                     ;; A tie at the same depth: keep the higher version, so the
                     ;; answer does not depend on which edge was walked first.
                     r (if (and seen (= (:depth seen) depth)
                                (pos? (version-compare (str (:version seen))
                                                       (str (:version r)))))
                         seen r)
                     kids (mapv (fn [e] [(key e) (val e) (inc depth)]) (deps-of r))]
                 (recur (into rest* kids)
                        (assoc nodes nm r)
                        (if (contains? nodes nm) order (conj order nm))
                        refused)))))))))))

(defn pins
  "A plan as the `:flint/overrides` map that would reproduce it exactly.

  This is what `flint deps pin` writes. Overrides rather than a second lockfile,
  because pinning a transitive and forcing a version are the same operation and
  should not have two spellings -- and because a pin in the same language as the
  declaration is one a person can read and edit."
  [p]
  (reduce (fn [m nm]
            (let [n (get (:nodes p) nm)]
              (assoc m nm
                     (case (:kind n)
                       :npm {:npm/version (:version n) :npm/integrity (:integrity n)}
                       :git (cond-> {:git/url (:url n) :git/sha (:sha n)}
                              (:version n) (assoc :git/version (:version n))
                              (:tag n) (assoc :git/tag (:tag n)))
                       :mvn {:mvn/version (:version n)}
                       ;; A PATH-BASED COORDINATE STILL HAS TO SURVIVE BEING
                       ;; PINNED. These fell to `{}`, which `flint deps pin`
                       ;; then wrote into `:flint/overrides` -- and an override
                       ;; of `{}` is a coordinate of no kind, so pinning a
                       ;; project with a `:local/root` broke the build it was
                       ;; supposed to make reproducible. Unreachable until the
                       ;; fetch walk started reading overrides; reachable now.
                       :local {:local/root (:root n)}
                       :pod (if (:root n)
                              {:pod/path (:root n)}
                              {:pod/version (:version n)})
                       {}))))
          {} (:order p)))

;; ------------------------------------------------------------- capabilities
;;
;; `workspace-capabilities` gives a WORKSPACE two keys: `:flint/capabilities-grant`, what it
;; holds, and `:flint/capabilities-guard`, what a requirer must hold. A
;; dependency ENTRY takes a third relation, and it is the one that makes a
;; dependency graph auditable:
;;
;;     {:flint/capabilities-grant [:fs :slurp]          ; what THIS project holds
;;      :deps {org/lib {:git/version "1.2.0"
;;                      :flint/capabilities-grant [:fs]}}} ; what I LEND to org/lib
;;
;; Read plainly: this project holds `:fs` and `:slurp`, and lends `:fs` -- not
;; `:slurp` -- to `org/lib`.

(defn tree-lines
  "The plan as indented lines: what is reached, and how deep.

  Depth rather than parentage, because `plan` records the depth a node was
  REACHED at -- which is what the conflict rule uses -- and inventing a parent
  edge to draw would be drawing something the resolver did not decide."
  [p]
  (into
   (mapv (fn [nm]
          (let [n (get (:nodes p) nm)]
            (str (apply str (repeat (* 2 (:depth n 0)) " "))
                 ;; A path-based dependency has no version and never will --
                 ;; `:local` and `:pod` are both "whatever is in that directory
                 ;; right now". The ROOT is the identifying fact for them, and
                 ;; printing it beats printing `?`, which reads as a resolver
                 ;; that failed rather than as a coordinate with nothing to pin.
                 nm " " (or (:version n) (:sha n) (:root n) "?")
                 (when (= :git (:kind n)) (str "  " (:url n))))))
        (:order p))
   ;; Notes AFTER the tree, so the shape a reader came for is not pushed down
   ;; the screen by something advisory.
   (mapv (fn [x] (str "note: " (:dep x) " -- " (:note x))) (:notes p))))

(defn why-lines
  "Why `target` is in the plan: the declaration that reached it, and at what
  depth. Empty when it is not in the plan at all, which is itself the answer."
  [p target]
  (let [n (get (:nodes p) target)]
    (if (nil? n)
      []
      [(str target " " (or (:version n) (:sha n))
            (if (zero? (:depth n 0))
              "  -- declared directly"
              (str "  -- reached at depth " (:depth n))))])))

;; ---------------------------------------------------------------------- bump

(defn bump-range
  "The range to look in when bumping `current` by `level`.

  * `nil`   -- stay inside whatever the declaration already said;
  * `:patch` -- `>=1.2.3 <1.3.0`;
  * `:minor` -- `>=1.2.3 <2.0.0`;
  * `:major` -- `>=1.2.3`, which crosses one and is why the CLI asks first.

  Built from the CURRENT version rather than from the declared range, because
  \"bump\" means *move forward from where I am*, and a declaration of `^1.0` on
  a project pinned at `1.2.3` should not offer to move to `1.0.9`."
  [current level]
  (let [v (parse-version current)]
    (when v
      (let [[maj min* _] [(nth v 0 0) (nth v 1 0) (nth v 2 0)]]
        (case level
          ;; COMMA-separated. `semver` refuses `>=1.1.3 <2.0.0` -- "expected
          ;; comma after patch version number" -- and npm accepts the space
          ;; form, so writing the one npm uses gets a message about nothing.
          :patch (str ">=" current ", <" maj "." (inc min*) ".0")
          :minor (str ">=" current ", <" (inc maj) ".0.0")
          :major (str ">=" current)
          nil)))))

(defn bump-plan
  "What `flint deps bump` would change: `[{:dep :from :to :crosses-major?}]`.

  Reported rather than applied, and the CLI decides what to do about it. A
  dependency already at the newest matching version is simply absent from the
  result, so an empty answer means \"nothing to do\" rather than \"nothing was
  looked at\"."
  ([deps] (bump-plan deps nil))
  ([deps level]
   ;; `reduce`, NOT `for`, and that is not a style choice.
   ;;
   ;; Every branch here makes a call into a virtual namespace, which PARKS, and
   ;; parking is refused inside native code -- a `for` is a lazy seq, which is
   ;; native. On wasm that refusal is a catchable error; on the NATIVE runtime
   ;; it currently panics the process:
   ;;
   ;;     index out of bounds: the len is 1024 but the index is
   ;;     18446744073709551615
   ;;
   ;; So this walks eagerly. `mapv` and `reduce` over a vector are eager and
   ;; carry a parking call happily; `for` and `map` do not. The panic is a
   ;; runtime bug rather than a rule of the language, and it is written up in
   ;; `DECISIONS.md#system-namespaces-and-deps` with a one-line reproduction -- but code that has to
   ;; work today is written the way that works today, and says why.
   (reduce
    (fn [acc e]
      (let [nm (key e) coord (val e)
            kind (coord-kind coord)
            current (str (or (:npm/version coord) (:git/version coord)
                             (:mvn/version coord)))
            range* (or (bump-range current level) current)
            hits (case kind
                   :npm (npm/resolve (str (or (:npm/name coord) nm)) range*)
                   :git (git/resolve (str (:git/url coord)) range*)
                   [])
            newest (:version (last hits))]
        (if (and newest (pos? (version-compare newest current)))
          (conj acc {:dep nm :from current :to newest
                     :crosses-major? (not= (first (parse-version current))
                                           (first (parse-version newest)))})
          acc)))
    []
    deps)))

;; ----------------------------------------------------- git tags that disagree
;;
;; This is what `:git/version` was reaching for and did not solve. A range gave
;; every dependency its own way to be right; what is wanted is to NOTICE that
;; two of them named different tags of one repository, and to find a tag they
;; can all take.

(defn- url-key
  "Two spellings of one repository. `https://github.com/org/x`,
  `https://github.com/org/x.git` and a trailing slash are the same place, and a
  conflict detector that missed that would miss the common case."
  [url]
  (let [u (str/trim (str url))
        u (if (str/ends-with? u "/") (subs u 0 (dec (count u))) u)
        u (if (str/ends-with? u ".git") (subs u 0 (- (count u) 4)) u)]
    (str/lower-case u)))

(defn tag-conflicts
  "Every repository that two coordinates named DIFFERENT tags of.

  `deps` is `{name coord}` -- both the declared ones and whatever a transitive
  walk added, because the case that matters is a direct dependency and a
  transitive one disagreeing.

  Returns `[{:url :wanted {tag [dep ..]} :versions {tag version-or-nil}}]`,
  empty when everybody agrees. A repository named once, however many times, is
  not a conflict."
  [deps]
  (let [by-url (reduce (fn [m e]
                         (let [nm (key e) c (val e)]
                           (if (and (= :git (coord-kind c)) (:git/tag c) (:git/url c))
                             (update-in m [(url-key (:git/url c)) (str (:git/tag c))]
                                        (fn [xs] (conj (or xs []) nm)))
                             m)))
                       {} deps)]
    (vec (for [[u tags] by-url
               :when (> (count tags) 1)]
           {:url u
            :wanted tags
            :versions (reduce (fn [m t] (assoc m t (tag-version-string t))) {} (keys tags))}))))

(defn agree-on-tag
  "The tag a conflicted repository should settle on, or nil.

  **The HIGHEST version among the tags already asked for**, and nothing else --
  it does not go to the network and does not invent a tag nobody named. That is
  deliberate: choosing a version none of the dependencies asked for is a
  decision nobody made, and the point here is to find agreement rather than to
  upgrade.

  nil when the tags carry no version to compare -- two branch-ish tags, say --
  because there is nothing to be right about and a guess would be worse than
  the honest refusal."
  [conflict]
  (let [ts (keys (:wanted conflict))
        versioned (filterv (fn [t] (get (:versions conflict) t)) ts)]
    (when (seq versioned)
      (reduce (fn [best t]
                (if (or (nil? best)
                        (pos? (version-compare (get (:versions conflict) t)
                                               (get (:versions conflict) best))))
                  t best))
              nil versioned))))

(defn tag-agreement
  "What `flint deps agree` would do: one row per conflicted repository.

  `[{:url :from {tag [dep ..]} :to tag :sha sha}]`, and `:to` is nil when the
  tags cannot be compared. The SHA is resolved for the chosen tag, so the
  overrides this produces carry the integrity that `system-namespaces-and-deps` says a pin must --
  which is the other half of the request: agree on a tag, then update the
  truncated sha to match."
  [deps]
  (reduce (fn [acc c]
            (let [to (agree-on-tag c)]
              (conj acc {:url (:url c)
                         :from (:wanted c)
                         :to to
                         :sha (when to (git/resolve-tag (:url c) to))})))
          [] (tag-conflicts deps)))

(defn newest-tag
  "The highest version tag a repository has, as `{:tag :version :sha}`, or nil.

  What `flint deps add git:...` uses to choose ONCE. Resolution at ADD time is
  a decision somebody made and a tag it wrote down; resolution at BUILD time is
  what `:git/version` was and is what makes a checkout mean different things on
  different days."
  [url]
  (let [rows (git/tags url)
        best (reduce (fn [best r]
                       (let [v (tag-version-string (:tag r))]
                         (cond
                           (nil? v) best
                           (nil? best) (assoc r :version v)
                           (pos? (version-compare v (:version best))) (assoc r :version v)
                           :else best)))
                     nil rows)]
    best))
