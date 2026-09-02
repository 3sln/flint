(ns flint.project
  "Reading a program from its entry namespace outwards.

  This used to live in `bin/flint`, which is babashka, which meant the compiler
  compiled to wasm could not find its own sources -- a caller had to resolve
  every `:require` first and hand over a finished map. That is most of what a
  compiler does before it compiles anything, so it belongs here, where both the
  babashka front end and the wasm one can use it.

  Source access is a FUNCTION, not a directory list. The babashka front end
  passes one backed by the filesystem; a host driving `flintc.wasm` passes one
  backed by a map it already has. Neither of them is the compiler's business.

  That function is the NAMESPACE RESOLVER (`doc/decisions/0036`), and it
  answers more than source text. A namespace belongs to a WORKSPACE, and the
  workspace is what carries identity: which reader tags the source is read
  under, and -- once capabilities land -- what it was granted and what it may
  require. The alternative was for each front end to answer those separately,
  which is what `0035` did with reader tags, and the result was that tags
  worked from the CLI and silently did not through the SDK, because only one
  of the two front doors had been taught the concept.

  One function, two producers, and nothing here knows which it got."
  (:require [flint.reader :as reader]
            [clojure.string :as str]
            [flint.compiler :as compiler]))

(def virtual-namespaces
  "Namespaces with no source: the compiler answers for them itself."
  '#{flint.rt})

(defn ns->path
  "The path a namespace's source would be at, without an extension."
  [n]
  (let [s (str n)
        out (loop [i 0 acc []]
              (if (= i (count s))
                acc
                (let [c (subs s i (inc i))]
                  (recur (inc i) (conj acc (cond (= c "-") "_" (= c ".") "/" :else c))))))]
    (apply str out)))

(defn- ns-form [forms]
  (first (filter (fn [f] (and (seq? f) (= 'ns (first f)))) forms)))

(defn collect
  "Read from `roots` outwards. `resolve-ns` takes a namespace symbol and returns
  nil, or what that namespace IS:

      {:src       the source text
       :file      what to name it in a diagnostic
       :workspace who owns it -- a symbol, nil for the anonymous one
       :tags      the reader tags its workspace binds (`doc/decisions/0035`)}

  Only `:src` is required. A resolver that answers just `{:src :file}` is the
  old `find-source` and still works; it simply reports every namespace as
  belonging to the anonymous workspace, which is the right answer for a caller
  that has no notion of projects.

  Returns `{:sources {ns {:src :file :forms :workspace :tags}} :order [..]
  :missing [..]}`. A namespace with no source is REPORTED rather than thrown
  on, because the caller knows better than this does whether that is fatal --
  a front end says so and stops, a tool listing dependencies keeps going.

  The tags travel WITH the source, and that is not bookkeeping: whoever reads
  this file again -- `topo-order` here, the compiler later -- must read it
  under the same tags, and a tag map only this loop knew about is a tag map
  they would get wrong."
  [resolve-ns roots features]
  (loop [todo (vec roots) sources {} order [] missing []]
    (if (seq todo)
      (let [n (first todo)]
        (cond
          (or (contains? sources n) (contains? virtual-namespaces n))
          (recur (vec (rest todo)) sources order missing)

          :else
          (if-let [s (resolve-ns n)]
            (let [forms (reader/read-all (:src s) {:file (:file s)
                                                   :features features
                                                   :tags (:tags s)})
                  reqs (compiler/ns-requires (or (ns-form forms) '(ns x)))]
              (recur (into (vec (rest todo)) reqs)
                     (assoc sources n {:src (:src s) :file (:file s) :forms forms
                                       :workspace (:workspace s) :tags (:tags s)})
                     (conj order n)
                     missing))
            (recur (vec (rest todo)) sources order (conj missing n)))))
      {:sources sources :order order :missing missing})))

(defn files-resolver
  "A namespace resolver over a flat map of `path -> source`, which is what a
  host driving `flintc.wasm` has (`doc/decisions/0023`).

  `workspaces` says who owns what, as a vector searched in order:

      [{:prefix \"foo/\" :name foo/bar :tags {tag-sym var-sym}} ..]

  First matching prefix wins, and a file matching none belongs to the anonymous
  workspace with only the built-in tags -- so a caller that passes no
  workspaces gets exactly the behaviour this had before there were any.

  The prefix is over the path a NAMESPACE maps to, not over wherever the caller
  keeps its files: `files` is keyed by `ns->path`, because a namespace has to be
  findable without a filesystem. So a workspace owns a NAMESPACE PREFIX --
  `foo/` for everything under `foo.*` -- which is the convention a Clojure
  library already follows. A layout whose directories do not match its
  namespaces is the caller's to flatten first, and stripping a source root is
  exactly what the CLI does."
  ([files] (files-resolver files nil))
  ([files workspaces]
   (fn [n]
     (let [base (ns->path n)]
       (when-let [path (first (filter (fn [p] (contains? files p))
                                      [(str base ".cljc") (str base ".clj")]))]
         (let [w (first (filter (fn [w] (let [pre (:prefix w)]
                                          (or (nil? pre) (= "" pre)
                                              (str/starts-with? (str path) (str pre)))))
                                (or workspaces [])))]
           {:src (get files path) :file path
            :workspace (:name w) :tags (:tags w)}))))))

(defn topo-order
  "Dependencies before dependents. A cycle does not stop the build -- it picks
  one and carries on -- because Clojure allows mutual reference through vars
  and refusing here would refuse programs that work."
  [sources]
  (let [deps (into {} (for [[n {:keys [forms]}] sources]
                        [n (set (compiler/ns-requires (or (ns-form forms) '(ns x))))]))]
    (loop [done [] seen #{} pending (vec (keys deps))]
      (if (empty? pending)
        done
        (let [ready (filterv (fn [n] (every? (fn [d] (or (contains? seen d)
                                                         (not (contains? deps d))))
                                             (get deps n)))
                             pending)
              ready (if (seq ready) ready [(first pending)])
              rs (set ready)]
          (recur (into done ready) (into seen ready)
                 (vec (remove (fn [x] (contains? rs x)) pending))))))))

(defn core-first
  "`clojure.core` is referred by every namespace, so it is analysed first
  whatever the require graph says. `flint.check` follows it for the same reason
  and with one addition: `expect` is a MACRO, and a macro has to be compiled
  before the namespace that expands it is analysed. Nothing `:require`s
  `flint.check`, so the graph has no edge to order by and this supplies one."
  [order]
  (let [pinned '[clojure.core flint.check]
        pin? (set pinned)]
    (concat (filter (set order) pinned)
            (remove pin? order))))

(defn resolve-project
  "Everything a compile needs, from an entry and a namespace resolver.
  Returns `{:sources .. :order .. :workspaces .. :missing ..}` with the order
  already topological and core-first.

  `roots` overrides the entry as the starting point, and `flint test` is why:
  its entry is `flint.check.registry`, which the COMPILER generates and no
  source path contains, so resolving from it reports the entry itself missing.
  Every namespace under the source path becomes a root instead -- which is also
  the right answer for a test run, because a test that nothing requires is
  still a test and collecting from one entry outwards would silently run a
  subset."
  ([resolve-ns entry-ns features] (resolve-project resolve-ns entry-ns features nil))
  ([resolve-ns entry-ns features roots*]
    ;; `clojure.core` is a root, not something the graph reaches: every namespace
    ;; refers it implicitly and almost none of them `:require` it, so starting
    ;; only from the entry collects a program whose `str` resolves to nothing.
    ;;
    ;; `flint.check` is a root for the same reason and only when checks are on
    ;; (`doc/decisions/0032`). A module writes `#?(:flint/check (expect ...))`
    ;; without requiring anything, because the branch does not exist in a build
    ;; where the namespace does not either -- so there is nothing to require and
    ;; nothing left behind. Under `:optimize [perf]` this root is simply not
    ;; added, and `flint.check` is not in the program at all.
    (let [roots (cond-> (vec (or roots* ['clojure.core entry-ns]))
                  (contains? features :flint/check) (conj 'flint.check))
          {:keys [sources order missing]}
          (collect resolve-ns roots features)
          _ order]
      {:sources sources
       :order (vec (core-first (topo-order sources)))
       :workspaces (into {} (map (fn [e] [(key e) (:workspace (val e))]) sources))
       :missing missing})))
