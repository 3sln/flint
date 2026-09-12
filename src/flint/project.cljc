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

  That function is the NAMESPACE RESOLVER (`DECISIONS.md#workspace-capabilities`), and it
  answers more than source text. A namespace belongs to a WORKSPACE, and the
  workspace is what carries identity: which reader tags the source is read
  under, and -- once capabilities land -- what it was granted and what it may
  require. The alternative was for each front end to answer those separately,
  which is what `reader-tags` did with reader tags, and the result was that tags
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

(def source-extensions
  "Every extension a namespace's source may have, MOST SPECIFIC FIRST.

  One list, read by `bin/flint` too rather than copied there. Four places used
  to enumerate these -- here, `cli/src/main.rs`, and twice in `bin/flint`.

  `.fln` is a PLATFORM extension in the sense `.clj` and `.cljs` are: one
  namespace may have both a `.fln` and a `.cljc`, and each runtime loads the one
  it understands (`DECISIONS.md#dialects-and-preludes`). flint prefers its own,
  exactly as the JVM prefers `.clj` over `.cljc`.

  `.cljc` BEFORE `.clj` is flint's existing order and the reverse of the JVM's.
  It stays: a `.clj` here is an oddity rather than the native case, so the
  portable file is the better default when both are present."
  [".fln" ".cljc" ".clj"])

(defn dialect-of
  "Which dialect a source file is written in, by its extension.

  `:flint` may use everything the workspace offers -- its own reader tags, its
  prelude. `:portable` must mean the same thing under Clojure, so it may not.
  Nothing enforces that yet; this is the field the enforcement will read."
  [path]
  (if (str/ends-with? (str path) ".fln") :flint :portable))

(defn normalise-prelude
  "A workspace's `:flint/prelude` as entries the analyzer can read.

  A plain symbol is `{:ns sym}` -- include everything, exclude nothing. A map
  says what it needs to. Giving BOTH `:include` and `:exclude` is refused:
  every such list has a shorter unambiguous spelling as an `:include` alone, so
  accepting both would add a second way to write one thing and a question about
  which applies first (`DECISIONS.md#dialects-and-preludes`)."
  [entries]
  (mapv (fn [e]
          (if (symbol? e)
            {:ns e}
            (let [m (into {} e)]
              (when (and (seq (:include m)) (seq (:exclude m)))
                (throw (ex-info
                        (str "a prelude entry gives both :include and :exclude for "
                             (:ns m) " -- write the :include alone")
                        {:entry m})))
              {:ns (:ns m) :include (vec (:include m)) :exclude (vec (:exclude m))})))
        (or entries [])))

(defn collect
  "Read from `roots` outwards. `resolve-ns` takes a namespace symbol and returns
  nil, or what that namespace IS:

      {:src       the source text
       :file      what to name it in a diagnostic
       :workspace who owns it -- a symbol, nil for the anonymous one
       :tags      the reader tags its workspace binds (`DECISIONS.md#reader-tags`)
       :grants    what its workspace HOLDS -- a set of capability keywords
       :guard     what a workspace must hold to require it (`DECISIONS.md#workspace-capabilities`)
       :virtual   true when the namespace has NO SOURCE and is spoken to over a
                  port (`DECISIONS.md#workspace-capabilities` step 4, `system-namespaces-and-deps`)
       :vars      what a virtual namespace holds, OPTIONALLY:
                  [{:name f :arities [..]} ..]. Present, an unknown var is a
                  compile error; absent, it is a run-time one}

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
            ;; A VIRTUAL namespace has no source to read and no requires to
            ;; follow (`DECISIONS.md#workspace-capabilities` step 4). It is recorded rather than
            ;; skipped, because the compiler has to know a name is virtual --
            ;; that is what decides whether a reference to it compiles to a var
            ;; or to a call over a port -- and because it is not missing, which
            ;; is what an unrecorded require would look like.
            (if (:virtual s)
              (recur (vec (rest todo))
                     (assoc sources n {:virtual true :vars (:vars s)
                                       :file (or (:file s) (str n))
                                       :workspace (:workspace s)
                                       :grants (:grants s) :guard (:guard s)})
                     (conj order n)
                     missing)
            (let [forms (reader/read-all (:src s) {:file (:file s)
                                                   :features features
                                                   :tags (:tags s)})
                  reqs (compiler/ns-requires (or (ns-form forms) '(ns x)))
                  ;; A PRELUDE ENTRY IS AN IMPLICIT REQUIRE, and has to create
                  ;; the same edge. Its names resolve without a `:require`, so
                  ;; nothing else would ever pull the namespace in -- it would
                  ;; be absent from the program and every prelude name would
                  ;; fail to resolve, which is what `clojure.core` being a ROOT
                  ;; has always been working around.
                  ;;
                  ;; An edge rather than a pin, so `topo-order` also puts the
                  ;; prelude BEFORE the code using it. SELF IS EXCLUDED: a
                  ;; workspace's prelude covers its own namespaces too, and one
                  ;; of them would otherwise be asked to precede itself.
                  pre (remove (fn [x] (= x n)) (map :ns (:prelude s)))]
              (recur (into (into (vec (rest todo)) reqs) pre)
                     (assoc sources n {:src (:src s) :file (:file s) :forms forms
                                       ;; A resolver that answers only `{:src :file}` is
                                       ;; still valid (see this fn's docstring), so the
                                       ;; dialect is derived from the file it named rather
                                       ;; than demanded of it.
                                       :dialect (or (:dialect s) (dialect-of (:file s)))
                                       :workspace (:workspace s) :tags (:tags s)
                                       :prelude (:prelude s)
                                       :grants (:grants s) :guard (:guard s)})
                     (conj order n)
                     missing)))
            (recur (vec (rest todo)) sources order (conj missing n)))))
      {:sources sources :order order :missing missing})))

(defn files-resolver
  "A namespace resolver over a flat map of `path -> source`, which is what a
  host driving `flintc.wasm` has (`DECISIONS.md#construe-integration-bar`).

  `workspaces` says who owns what, as a vector searched in order:

      [{:prefix \"foo/\" :name foo/bar :tags {tag-sym var-sym}
        :grants #{:fs} :guard #{:trusted}}
       {:prefix \"flint/sys/\" :name flint/sys :virtual true
        :vars [{:name list-dir :arities [1]} ..]} ..]

  An entry with `:virtual true` declares that everything under its prefix has no
  source and is spoken to over a port (`DECISIONS.md#workspace-capabilities` step 4). `:vars` is
  optional and buys compile-time checking; without it any name resolves and an
  unknown one fails at run time.

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
     (let [base (ns->path n)
           ;; A workspace entry may declare a namespace VIRTUAL rather than
           ;; supply source for it (`DECISIONS.md#workspace-capabilities` step 4). Checked before
           ;; the files, because a virtual namespace has no file to find and
           ;; looking for one would report it missing.
           virt (first (filter (fn [w] (and (:virtual w)
                                            (let [pre (:prefix w)]
                                              (or (nil? pre) (= "" pre)
                                                  (str/starts-with? (str base "/") (str pre))
                                                  (str/starts-with? (str base) (str pre))))))
                               (or workspaces [])))]
       (if virt
         {:virtual true :vars (:vars virt) :file (str n)
          :workspace (:name virt)
          :grants (set (:grants virt)) :guard (set (:guard virt))}
       (when-let [path (first (filter (fn [p] (contains? files p))
                                      (mapv (fn [e] (str base e)) source-extensions)))]
         (let [w (first (filter (fn [w] (let [pre (:prefix w)]
                                          (or (nil? pre) (= "" pre)
                                              (str/starts-with? (str path) (str pre)))))
                                (or workspaces [])))]
           {:src (get files path) :file path :dialect (dialect-of path)
            :workspace (:name w) :tags (:tags w)
            :prelude (normalise-prelude (:prelude w))
            :grants (set (:grants w)) :guard (set (:guard w))})))))))

(defn- a-cycle
  "One concrete loop among `pending`, as `[a b .. a]`.

  NAMING THE LOOP is the whole point. \"There is a cycle\" sends a reader to
  find it by hand across a hundred namespaces; `a -> b -> a` is the answer.
  Depth-first from any node still pending: every one of them is in or behind a
  cycle, so the first repeat closes a real one."
  [deps pending]
  (let [in? (set pending)]
    (loop [node (first pending) path [] seen #{}]
      (if (contains? seen node)
        (conj (vec (drop-while #(not= % node) path)) node)
        (let [nexts (filter in? (get deps node))]
          (if (empty? nexts)
            (conj (vec path) node)
            (recur (first nexts) (conj path node) (conj seen node))))))))

(defn implied-requires
  "The namespaces a source depends on because the COMPILER will emit calls into
  them, which no `:require` in that source names.

  The load order is built from `:require` edges. A regex literal becomes
  `(flint.regex/pattern ..)` and a `defprotocol` becomes calls on
  `flint.protocols/extend-method` and `protocol-miss` -- references the user
  never wrote, into namespaces they never named. At TOP LEVEL that ran before
  the callee was initialised, and `(def re #\"a+b\")` in a namespace with no
  requires died as \"value is not a function (nil, 1 args)\".

  Derived rather than pinned. `core-first` pins four names and that is already
  a hand-written prefix of the load order; `flint.regex` alone would add three
  more, and the next emitted reference would reopen it.

  SELF IS EXCLUDED, because `flint.protocols` defines a protocol and
  `flint.regex` would otherwise be asked to precede itself.

  `extend-type` IS LISTED AND DOES NOT EXIST YET -- `doc/manifest.edn` has it
  among the names flint has not ported. It is here because the next person to
  implement it would have no reason to think about load order, and the edge
  costing nothing until then is cheaper than the bug costing an afternoon."
  [me forms]
  (let [found (volatile! #{})
        walk (fn walk [x]
               (cond
                 (and (map? x) (string? (:flint/regex x))) (vswap! found conj 'flint.regex)
                 (map? x) (doseq [[k v] x] (walk k) (walk v))
                 (seq? x) (do (when (contains? '#{defprotocol extend-protocol extend-type}
                                                (first x))
                                (vswap! found conj 'flint.protocols))
                              (doseq [e x] (walk e)))
                 (coll? x) (doseq [e x] (walk e))
                 :else nil))]
    (doseq [f forms] (walk f))
    (disj @found me)))

(defn topo-order
  "Dependencies before dependents. A namespace cycle is REFUSED, naming the
  loop.

  It used to pick one and carry on, justified by \"Clojure allows mutual
  reference through vars\". That is true WITHIN a namespace -- which is what
  `declare` is for -- and false ACROSS them: Clojure refuses a namespace-level
  require cycle outright, and names it:

      Cyclic load dependency: [ /aa ]->/bb->[ /aa ]

  So carrying on was not matching Clojure, it was diverging from it, and it
  cost a real bug. `clojure.core` requiring `flint.protocols` while
  `flint.protocols` required `clojure.core` produced no error and no loop --
  it produced an EMPTY protocol map, which surfaced four hundred lines away
  as \"the protocol  has no method named print-data; its methods are []\".
  A silent cycle does not stay silent; it re-emerges somewhere with no
  information attached."
  [sources]
  (let [;; A VIRTUAL NAMESPACE COMPILES TO CALLS ON `flint.virtual`, and
        ;; requiring it names the virtual namespace rather than the machinery.
        ;; `resolve-project` adds `flint.virtual` to the program as a ROOT when
        ;; one is present -- which puts it in, and gives it no edge, so it
        ;; could still be initialised after the namespace calling into it.
        ;; Measured: a top-level `(flint.sys.fs/list-dir "src")` died as "value
        ;; is not a function (nil, 2 args)" even with `flint.sys.fs` required,
        ;; because the call is on `flint.virtual` and nothing named that.
        ;;
        ;; `bin/flint` HAS NO COUNTERPART, and that is not a divergence: it
        ;; cannot compile such a program at all -- it answers "cannot find
        ;; source for namespace flint.sys.fs", because its workspace model has
        ;; no `:virtual`. Its `virtual-namespaces` is `#{flint.rt}`, which is a
        ;; different thing: builtins, compiled to native calls, with no
        ;; `flint.virtual` behind them. Using that set for this trigger would
        ;; add an edge for every `(:require [flint.rt])` and be wrong.
        virtuals (set (for [[n e] sources :when (:virtual e)] n))
        deps (into {} (for [[n {:keys [forms]}] sources]
                        (let [reqs (set (compiler/ns-requires (or (ns-form forms) '(ns x))))]
                          [n (cond-> (into reqs (implied-requires n forms))
                               (and (not= n 'flint.virtual)
                                    (some virtuals reqs))
                               (conj 'flint.virtual))])))]
    (loop [done [] seen #{} pending (vec (keys deps))]
      (if (empty? pending)
        done
        (let [ready (filterv (fn [n] (every? (fn [d] (or (contains? seen d)
                                                         (not (contains? deps d))))
                                             (get deps n)))
                             pending)
              _ (when (empty? ready)
                  (let [loop- (a-cycle deps pending)]
                    (throw (ex-info
                            (str "cyclic namespace dependency: "
                                 (str/join " -> " (map str loop-))
                                 ". Namespace requires must form a DAG, as they"
                                 " do in Clojure. Mutual reference WITHIN a"
                                 " namespace is what `declare` is for; across"
                                 " namespaces, move what both halves need into"
                                 " a third namespace they can each require.")
                            {:cycle (vec loop-)
                             :pending (vec (sort pending))}))))
              rs (set ready)]
          (recur (into done ready) (into seen ready)
                 (vec (remove (fn [x] (contains? rs x)) pending))))))))

(defn refused-requires
  "Every `:require` a workspace guard refuses (`DECISIONS.md#workspace-capabilities`).

  Level one of two. A workspace may declare `:flint/capabilities-guard`, and
  then only a workspace holding those capabilities may require it AT ALL. The
  var-level guard is the other level and is the analyzer's; this one is checked
  from the dependency graph alone, before a line of the guarded workspace has
  been analysed, which is the point of having it separately: `flint deps` can
  answer \"this project needs :fs because it depends on X\" without compiling.

  Checked at EVERY EDGE and never transitively. A requires B requires C: if B
  holds what C guards and A does not, A still reaches C's behaviour through B,
  and that is correct -- it is B choosing to re-export, which is the same
  delegation allowed everywhere else. What a guard buys is that the set of
  workspaces holding a capability DIRECTLY is small and declared; it does not
  and cannot stop authority spreading through the functions a holder exports.

  Within one workspace, nothing is checked. A project is not a security boundary
  against itself, and making it one would mean every file in a library declaring
  what every other file may use.

  Returns `[{:from :to :from-workspace :to-workspace :needs}]`, `:needs` being
  what was missing rather than the whole guard -- naming the three capabilities
  a caller already holds helps nobody find the one it does not."
  [sources]
  (vec (for [[n info] sources
             r (compiler/ns-requires (or (ns-form (:forms info)) '(ns x)))
             :let [to (get sources r)
                   guard (:guard to)]
             :when (and to
                        (seq guard)
                        (not= (:workspace info) (:workspace to)))
             :let [missing (into #{} (remove (or (:grants info) #{}) guard))]
             :when (seq missing)]
         {:from n :to r
          :from-workspace (:workspace info) :to-workspace (:workspace to)
          :needs missing})))

(defn core-first
  "`clojure.core` is referred by every namespace, so it is analysed first
  whatever the require graph says. `flint.check` follows it for the same reason
  and with one addition: `expect` is a MACRO, and a macro has to be compiled
  before the namespace that expands it is analysed. Nothing `:require`s
  `flint.check`, so the graph has no edge to order by and this supplies one."
  [order]
  ;;
  ;; `flint.protocols` FOLLOWS, and for a measured reason rather than a
  ;; symmetry. `defprotocol` expands to calls on `flint.protocols/extend-method`
  ;; and `flint.protocols/protocol-miss` INTO the using namespace, which never
  ;; required it -- a qualified reference resolves through the read pre-pass,
  ;; and the load order is built from `:require` EDGES. So a namespace with no
  ;; requires sorts before it, and its top-level code calls a var that is still
  ;; nil.
  ;;
  ;; That is not hypothetical. The SAME protocol miss, in the same program:
  ;;
  ;;   during load  -> "value is not a function (nil, 3 args)"
  ;;   after load   -> "no implementation of app.main/greet (protocol ..) for
  ;;                    a value of kind :number. Extend the protocol to that
  ;;                    kind, or attach .. as metadata on the value."
  ;;
  ;; `protocol-miss` exists to say the second thing, and it was unreachable in
  ;; exactly the phase where the first one is useless.
  ;;
  ;; `flint.core` IS PINNED WITH IT, and that was measured too. Pinning
  ;; `flint.protocols` alone moved the failure one level down rather than
  ;; fixing it -- `protocol-miss` ran, and died on `(kind x)`, because `kind`
  ;; lives in `flint.core` and that was still nil. The claim that its
  ;; `flint.core` reference was call-time-only was wrong: `protocol-miss` IS
  ;; the load-time caller. `flint.core` requires nothing, so the chain stops
  ;; there.
  ;; ORDER WITHIN THE PIN MATTERS, and it is not the order they were added in.
  ;; `flint.check` uses `extend-protocol` at top level, so it needs
  ;; `flint.protocols` INITIALISED before it -- and pinning overrides the
  ;; require graph, so a derived edge cannot fix this one. With `extend-method`
  ;; in `clojure.core` that was invisible, because `clojure.core` is first
  ;; whatever else happens. Moving `extend-method` to `flint.protocols` made
  ;; `flint.check` call a nil: "value is not a function (nil, 4 args)", from
  ;; `bin/flint test` on a two-file project.
  ;;
  ;; Nothing pinned ahead of `flint.check` uses `expect`, which is what makes
  ;; putting it last safe -- checked in `clojure.core`, `flint.core` and
  ;; `flint.protocols`.
  (let [pinned '[clojure.core flint.core flint.protocols flint.check]
        pin? (set pinned)]
    (concat (filter (set order) pinned)
            (remove pin? order))))

(defn resolve-project
  "Everything a compile needs, from an entry and a namespace resolver.
  Returns `{:sources .. :order .. :workspaces .. :refused .. :missing ..}` with
  the order already topological and core-first. `:refused` is REPORTED for the
  same reason `:missing` is: whether a refused require stops the build is the
  front end's call, and a tool listing dependencies wants to see them all.

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
    ;; (`DECISIONS.md#checks`). A module writes `#?(:flint/check (expect ...))`
    ;; without requiring anything, because the branch does not exist in a build
    ;; where the namespace does not either -- so there is nothing to require and
    ;; nothing left behind. Under `:optimize [perf]` this root is simply not
    ;; added, and `flint.check` is not in the program at all.
    (let [roots (cond-> (vec (or roots* ['clojure.core entry-ns]))
                  (contains? features :flint/check) (conj 'flint.check))
          {:keys [sources order missing] :as r0}
          (collect resolve-ns roots features)
          ;; A virtual namespace compiles to CALLS on `flint.virtual`, so that
          ;; namespace has to be in the program -- and nothing `:require`s it,
          ;; because the requires were written against `flint.sys.fs` and the
          ;; rewrite happens later, in the analyzer (`DECISIONS.md#workspace-capabilities` step
          ;; 4). So the graph has no edge to it and this supplies one, the same
          ;; way `core-first` supplies one for `flint.check`.
          ;;
          ;; Only when one was actually found. Adding it unconditionally would
          ;; put an RPC client and a port in every program that has no virtual
          ;; namespace at all, which is the cost `namespace-units` exists to avoid.
          virtual? (some (fn [e] (:virtual (val e))) sources)
          {:keys [sources order missing]}
          (if (and virtual? (not (contains? sources 'flint.virtual)))
            (collect resolve-ns (conj (vec roots) 'flint.virtual) features)
            r0)
          _ order]
      {:sources sources
       :order (vec (core-first (topo-order sources)))
       :workspaces (into {} (map (fn [e] [(key e) (:workspace (val e))]) sources))
       :refused (refused-requires sources)
       :missing missing})))
