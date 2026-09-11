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
       :tags      the reader tags its workspace binds (`doc/decisions/0035`)
       :grants    what its workspace HOLDS -- a set of capability keywords
       :guard     what a workspace must hold to require it (`doc/decisions/0036`)
       :virtual   true when the namespace has NO SOURCE and is spoken to over a
                  port (`doc/decisions/0036` step 4, `0037`)
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
            ;; follow (`doc/decisions/0036` step 4). It is recorded rather than
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
                  reqs (compiler/ns-requires (or (ns-form forms) '(ns x)))]
              (recur (into (vec (rest todo)) reqs)
                     (assoc sources n {:src (:src s) :file (:file s) :forms forms
                                       :workspace (:workspace s) :tags (:tags s)
                                       :grants (:grants s) :guard (:guard s)})
                     (conj order n)
                     missing)))
            (recur (vec (rest todo)) sources order (conj missing n)))))
      {:sources sources :order order :missing missing})))

(defn files-resolver
  "A namespace resolver over a flat map of `path -> source`, which is what a
  host driving `flintc.wasm` has (`doc/decisions/0023`).

  `workspaces` says who owns what, as a vector searched in order:

      [{:prefix \"foo/\" :name foo/bar :tags {tag-sym var-sym}
        :grants #{:fs} :guard #{:trusted}}
       {:prefix \"flint/sys/\" :name flint/sys :virtual true
        :vars [{:name list-dir :arities [1]} ..]} ..]

  An entry with `:virtual true` declares that everything under its prefix has no
  source and is spoken to over a port (`doc/decisions/0036` step 4). `:vars` is
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
           ;; supply source for it (`doc/decisions/0036` step 4). Checked before
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
                                      [(str base ".cljc") (str base ".clj")]))]
         (let [w (first (filter (fn [w] (let [pre (:prefix w)]
                                          (or (nil? pre) (= "" pre)
                                              (str/starts-with? (str path) (str pre)))))
                                (or workspaces [])))]
           {:src (get files path) :file path
            :workspace (:name w) :tags (:tags w)
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
  (let [deps (into {} (for [[n {:keys [forms]}] sources]
                        [n (into (set (compiler/ns-requires (or (ns-form forms) '(ns x))))
                                 (implied-requires n forms))]))]
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
  "Every `:require` a workspace guard refuses (`doc/decisions/0036`).

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
  (let [pinned '[clojure.core flint.check flint.core flint.protocols]
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
    ;; (`doc/decisions/0032`). A module writes `#?(:flint/check (expect ...))`
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
          ;; rewrite happens later, in the analyzer (`doc/decisions/0036` step
          ;; 4). So the graph has no edge to it and this supplies one, the same
          ;; way `core-first` supplies one for `flint.check`.
          ;;
          ;; Only when one was actually found. Adding it unconditionally would
          ;; put an RPC client and a port in every program that has no virtual
          ;; namespace at all, which is the cost `0003` exists to avoid.
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
