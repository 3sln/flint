(ns flint.deps.resolve
  "The dependency PLAN: which version of what, and where it came from
  (`doc/decisions/0037`).

  This is the half that decides. `flint.deps.npm`, `flint.deps.git` and
  `flint.deps.mvn` are virtual namespaces served by the CLI, and every one of
  them answers with EVERY match and never picks -- so version conflict is
  resolved here, once, in one language.

      Rust matches, flint decides.

  That rule is not tidiness. Version arithmetic exists in two places now:
  `semver` in the binary, comparing at fetch time, and this file, comparing at
  plan time where the graph is. Two implementations of *which version wins* is
  the shape `0035` records going wrong -- a value only one of three readers knew
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
            [flint.deps.npm :as npm]
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

(defn coord-kind
  "Which sort of coordinate this is, by the key that identifies it.

  `:git/version` and `:git/tag` both mean git, which is the whole point of
  `0037`'s git support: a version is a first-class way to name a git dependency
  rather than a second-class alias for a sha."
  [c]
  (cond
    (or (:git/url c) (:git/version c) (:git/tag c) (:git/sha c)) :git
    (:local/root c) :local
    (or (:npm/version c) (:npm/name c)) :npm
    (:mvn/version c) :mvn
    :else :unknown))

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
  "A git coordinate, by whichever of the three ways it was named.

  The three are not alternatives to each other -- they compose, and the
  composition is what `0037` is for:

  * `:git/version` picks a tag by SEMVER;
  * `:git/tag` names one exactly, which is canonical `deps.edn`;
  * `:git/sha` on top of either is INTEGRITY -- the resolved commit must start
    with it, or the plan is refused.

  A bare `:git/sha` with neither is canonical too, and is taken as given."
  [nm c]
  (let [url (str (:git/url c))
        want-sha (some-> (:git/sha c) str)
        picked
        (cond
          (:git/version c)
          (let [hits (git/resolve url (exact-range (:git/version c)))]
            (when (seq hits) (last hits)))

          (:git/tag c)
          {:tag (str (:git/tag c))
           :sha (git/resolve-tag url (str (:git/tag c)))
           :version nil}

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
    ;; Maven resolves to the version it was given: flint does not walk a POM
    ;; graph, and `0021` prices that work and states the reason -- resolving a
    ;; coordinate gets you SOURCE, not something that compiles. Said here rather
    ;; than pretended.
    :mvn {:kind :mvn :name (str nm) :version (str (:mvn/version c))}
    nil))

;; ------------------------------------------------------------------- the walk

(defn- deps-of
  "What a resolved node depends on, as `{name coord}`.

  npm answers from its manifest. A git dependency's own `deps.edn` needs the
  checkout, so it is read by the CALLER after fetching -- this returns nothing
  for it rather than fetching from inside a resolver, because a function that
  quietly downloads is one nobody can reason about."
  [node]
  (case (:kind node)
    :npm (let [m (npm/manifest (:name node) (:version node))]
           (reduce (fn [acc e] (assoc acc (key e) {:npm/version (val e)}))
                   {} (or (:deps m) {})))
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
   (loop [queue (mapv (fn [e] [(key e) (val e) 0]) deps)
          nodes {}
          order []
          refused []]
     (if (empty? queue)
       {:nodes nodes :order order :refused refused}
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
                      (conj refused {:dep nm :reason "flint cannot resolve this coordinate"}))

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
                        refused))))))))))

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
                       {}))))
          {} (:order p)))

;; ------------------------------------------------------------- capabilities
;;
;; `0036` gives a WORKSPACE two keys: `:flint/capabilities-grant`, what it
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

(defn- names-of
  "A grant in either form as a set of capability NAMES.

  `0037` lets a grant be a map of name to policy as well as a set of names,
  because a name says what KIND of authority and the policy says which routes.
  Only the names matter here: the policy is the host's, checked when a call
  happens, and this is the compile-time half (`0036`)."
  [g]
  (cond
    (map? g) (set (keys g))
    (or (vector? g) (set? g) (seq? g)) (set g)
    (nil? g) #{}
    :else #{g}))

(defn lending-errors
  "Every rule a `deps.edn`'s capability delegation breaks.

  Two of the three rules `0037` states; the third is `flint deps add` writing
  the grant it found, which is the tool's job and not this one's.

  1. **You cannot lend what you do not hold.** A grant on a dependency entry
     that the project itself was never granted is refused, naming both. Without
     this, `deps.edn` would be a way to MINT authority: a project could hand a
     dependency `:fs` it never had, and the whole chain stops being auditable
     from the top.

  2. **A dependency declaring a guard must be granted it.** This is `0036` level
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

(defn tree-lines
  "The plan as indented lines: what is reached, and how deep.

  Depth rather than parentage, because `plan` records the depth a node was
  REACHED at -- which is what the conflict rule uses -- and inventing a parent
  edge to draw would be drawing something the resolver did not decide."
  [p]
  (mapv (fn [nm]
          (let [n (get (:nodes p) nm)]
            (str (apply str (repeat (* 2 (:depth n 0)) " "))
                 nm " " (or (:version n) (:sha n) "?")
                 (when (= :git (:kind n)) (str "  " (:url n))))))
        (:order p)))

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
   ;; `doc/decisions/0037` with a one-line reproduction -- but code that has to
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
