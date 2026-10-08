(ns ^:internal flint.compiler.resolve
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
  (:require [flint.compiler.forms :as forms]
            [clojure.string :as str]
            [flint.compiler.core :as compiler]
            [flint.compiler.analyzer :as ana]))

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

(defn read-options
  "Every option the read of a resolver answer `s` takes, as one map.

  THE ONLY READ: the forms are what the compiler analyses, because the reader
  depends on nothing it learns (`DECISIONS.md#context-free-reader`). Every
  option a read takes is here, and a caller that compiles from `:src` instead
  must pass the same ones (`DECISIONS.md#reader-tags`).

  It is a VALUE because it is also the KEY a pre-read file is checked against
  (`DECISIONS.md#stdlib-preread`): forms read ahead of time stand in for this
  read only when they were read under exactly these options. `:tags` is
  normalised to nil when empty because the reader merges it over the built-in
  tags, so `{}` and nil read the same text identically and must not miss."
  [s features]
  {:file (:file s)
   :features features
   :tags (not-empty (:tags s))
   :dialect (or (:dialect s) (dialect-of (:file s)))})

(defn preread-options
  "The options a DEFERRED read of resolver answer `s` is made under:
  `read-options` with `:features :any`, because a deferred read keeps reader
  conditionals as data and one read serves every feature set
  (`DECISIONS.md#stdlib-preread`). The rest -- file, tags, dialect -- still
  decides what the text reads as, so it is still the key."
  [s]
  (read-options s :any))

(defn text-refused
  "The error for an answer that carries source TEXT: the compiler reads none
  (`DECISIONS.md#one-reader-and-no-other`). Every host reads with the one
  kin-generated reader -- `flint_rt::hostread` natively, `dist/flint-reader.wasm`
  in JavaScript, `Formsenc.readForms` on the JVM and CLR -- and hands over its
  `flint.forms` bytes."
  [s]
  (ex-info (str (or (:file s) "a namespace") " arrived as source text, and the compiler reads no"
                " text: the host reads it with the kin reader and hands over flint.forms bytes"
                " (DECISIONS.md#one-reader-and-no-other)")
           {:file (:file s) :type :reader}))

(defn read-entry
  "The forms of resolver answer `s`, under `features`.

  The answer arrives READ (`:preread` bytes, the `flint.compiler.forms`
  encoding), and is decoded HERE, when the namespace is reached and not
  before, and its conditionals resolved for `features` -- once its `:opts` are
  the ones this compile would read it under apart from features. Forms read
  under other options -- another workspace's tags -- are refused: forms read
  under the wrong options are a different program, never a faster one.

  `sink`, optional: passed straight through to `resolve-conditionals`, so a
  caller can collect every matched-nothing conditional across a whole
  project read the same way one file's deferred read already can."
  [s features & [sink]]
  ;; A FILE THE HOST COULD NOT READ arrives as its read error, and is reported
  ;; only if the compile REACHES it -- the way it was when the compiler did the
  ;; reading -- worded and positioned as the reader words it.
  (when-let [e (:read-error s)]
    ;; The kin reader's message is WHOLE -- "read error: .. (file:line:col)"
    ;; for the reader's own -- exactly as the compiler's reader worded it.
    (throw (ex-info (:message e)
                    {:type :reader :file (:file s) :line (:line e) :column (:column e)})))
  (if-let [pre (:preread s)]
    (let [d (forms/decode pre)]
      (if
        ;; Read DEFERRED (the embedded standard library), or read EAGERLY
        ;; under exactly this compile's features -- which is what a host reads
        ;; user text as before answering a namespace request
        ;; (`DECISIONS.md#namespaces-over-the-system-port`, "what the compiler
        ;; checks on arrival"). An eager read keeps no conditionals, so its
        ;; `:conds` is empty and `resolve-conditionals` hands the forms back.
        (or (= (:opts d) (preread-options s))
            (= (:opts d) (read-options s features)))
        (forms/resolve-conditionals d features (:file s) sink)
        (throw (ex-info (str (:file s) " arrived pre-read under " (pr-str (:opts d))
                             ", and this compile reads it under " (pr-str (preread-options s))
                             " (DECISIONS.md#stdlib-preread)")
                        {:file (:file s)}))))
    (throw (text-refused s))))

(defn file-answer
  "What `files-resolver` answers for the SOURCE FILE at `path`, which must be
  in `files`."
  [files workspaces path]
  (let [w (first (filter (fn [w] (let [pre (:prefix w)]
                                   (or (nil? pre) (= "" pre)
                                       (str/starts-with? (str path) (str pre)))))
                         (or workspaces [])))
        ;; A BODY IS TEXT, OR A FILE ALREADY READ: `{:preread bytes}`, the
        ;; `flint.compiler.forms` encoding, which is how the native CLI hands over the
        ;; standard library (`DECISIONS.md#stdlib-preread`) -- with no text
        ;; beside it. `read-entry` decodes it if the namespace is reached;
        ;; this only passes it on.
        body (get files path)
        pre? (map? body)]
    {:src (if pre? (:src body) body) :preread (when pre? (:preread body))
     :read-error (when pre? (:read-error body))
     ;; THE DIALECT A HOST READ IT UNDER, when it says; the extension otherwise.
     :file path :dialect (or (when pre? (:dialect body)) (dialect-of path))
     :workspace (:name w) :tags (:tags w)
     :prelude (normalise-prelude (:prelude w))
     :grants (set (:grants w)) :guard (set (:guard w))}))

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
         (file-answer files workspaces path)))))))

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
        ;; A PRELUDE ENTRY IS AN EDGE HERE TOO, and it was only an edge in
        ;; `collect-waves`. That pulled the namespace INTO the program and said nothing
        ;; about where it landed, so a prelude's position was whatever this
        ;; function's worklist happened to visit first -- which was a hash map's
        ;; key order, and came out right often enough to look deliberate.
        ;; Sorting that seed (`DECISIONS.md#compiles-are-byte-reproducible`) moved
        ;; one prelude AFTER the namespace using its names, and four rows of
        ;; `bb test/sysns.clj` failed with "unable to resolve symbol: shout".
        ;; `collect-waves`'s own comment already claimed this: "An edge rather than a
        ;; pin, so `topo-order` also puts the prelude BEFORE the code using it."
        ;; It was an edge in one of the two functions that sentence spans.
        ;; EVERY PRELUDE PROVIDER IS EXCLUDED, not just self. `collect-waves` removes
        ;; only `n`, which is enough to pull sources in; here it is not. A
        ;; workspace offering TWO prelude entries attaches the same list to both
        ;; of those namespaces, so each would be given an edge to the other and
        ;; the graph would carry a cycle that is not in anybody's `:require`:
        ;; `other.prelude -> mylib.prelude -> other.prelude`, which is what two
        ;; rows of `bb test/sysns.clj` reported after the first version of this
        ;; edge. A prelude namespace OFFERS names rather than consuming them, so
        ;; it takes no prelude edges; everything else in the workspace takes them
        ;; all.
        pre-of (fn [n prelude]
                 (let [ps (set (map :ns prelude))]
                   (if (contains? ps n) #{} ps)))
        deps (into {} (for [[n {:keys [forms prelude]}] sources]
                        (let [reqs (into (set (compiler/ns-requires (or (ns-form forms) '(ns x))))
                                         (pre-of n prelude))]
                          [n (cond-> (into reqs (implied-requires n forms))
                               (and (not= n 'flint.virtual)
                                    (some virtuals reqs))
                               (conj 'flint.virtual))])))]
    ;; SORTED, BECAUSE A MAP'S ITERATION ORDER IS NOT PART OF ITS VALUE
    ;; (`DECISIONS.md#compiles-are-byte-reproducible`).
    ;;
    ;; `pending` used to be `(vec (keys deps))`, and `deps` is a hash map -- so
    ;; the order of namespaces that are ready in the same wave was whatever the
    ;; HOST's hashing produced. Clojure on babashka and flint on itself hash
    ;; differently, so the same program compiled to a different image depending
    ;; on which compiler ran, and whether it differed depended on the namespace
    ;; NAMES: `(ns prog)` agreed and `(ns t)` did not.
    ;;
    ;; MEASURED 2026-09-28, `(ns t) (defn main [_] "ok")` to `:to :clr`, both
    ;; doors writing the same basename so the assembly name could not be the
    ;; cause: 51 bytes of the embedded image differed. One spec through both
    ;; compilers isolated it -- the babashka compiler and `dist/flintc.wasm`
    ;; answered the same bytes for a RESOLVED spec, which skips this function,
    ;; and different bytes for an unresolved one, which does not.
    ;;
    ;; Sorting the seed is enough: `ready` is a `filterv` of `pending` and the
    ;; remainder keeps its order, so every wave stays sorted. Ordering is by the
    ;; PRINTED name rather than by `compare` on symbols, because that is one
    ;; rule rather than two hosts' idea of how symbols compare.
    (loop [done [] seen #{} pending (vec (sort-by str (keys deps)))]
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
  whatever the require graph says. `flint.check` follows it for the same
  reason -- it is now an UNCONDITIONAL root (`DECISIONS.md#checks`) -- and
  with one addition: `expect` is a MACRO in both of its variants, and a macro
  has to be compiled before the namespace that expands it is analysed.
  Nothing `:require`s `flint.check`, so the graph has no edge to order by and
  this supplies one."
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
  ;;
  ;; `flint.core.impl` LEADS, and `clojure.core` FOLLOWS `flint.check` now
  ;; (`DECISIONS.md#four-units`): the stdcore namespaces refer nothing of
  ;; `clojure.core` and are initialised from `flint.core.impl`, which requires
  ;; nothing; `clojure.core` aliases and requires `flint.core.impl` and
  ;; `flint.protocols`; and nothing pinned ahead of `flint.check` uses
  ;; `expect`, which is still what makes putting it after the three safe.
  (let [pinned '[flint.core.impl flint.core flint.protocols flint.check clojure.core]
        pin? (set pinned)]
    (concat (filter (set order) pinned)
            (remove pin? order))))

(defn project-roots
  "Where a compile starts reading: `roots*` or the entry, plus `flint.port`,
  `flint.wire` and `flint.check`, always. One function for every walk, so no
  two can start from different places."
  [entry-ns features roots*]
  ;; `clojure.core` IS NOT A ROOT ANY MORE (`DECISIONS.md#four-units`). It was
  ;; one because every namespace refers it implicitly and almost none of them
  ;; `:require` it, so starting only from the entry collected a program whose
  ;; `str` resolved to nothing. The implicit refer is an EDGE now, contributed
  ;; by each referring namespace's own prelude (`take-answer`), so the graph
  ;; reaches `clojure.core` exactly when something refers it.
  ;;
  ;; `flint.system` IS NOT A ROOT ANY MORE. It was the control plane, added
  ;; here and in every other door because nothing referenced it; the control
  ;; plane is the RUNTIME's now, and the call loop is compiled from
  ;; `flint.compiler.callentry` into every image by the compiler itself
  ;; (`DECISIONS.md#the-control-plane-is-the-runtimes`).
  ;;
  ;; `flint.port` AND `flint.wire` ARE ROOTS, unconditionally: the call loop is
  ;; in every image and references `flint.port/send`/`flint.wire/read-from` by
  ;; var (`src/flint/compiler/callentry.cljc`), which no `:require` in the program names.
  ;; Call serving is always on (the maintainer's decision), so this is the
  ;; ordinary require graph, not an injection -- a resolver that cannot answer
  ;; `flint.port` reports it missing like any other unresolved require.
  ;;
  ;; `flint.check` IS A ROOT UNCONDITIONALLY TOO, as of the maintainer's
  ;; revision (`DECISIONS.md#checks`): the namespace itself always exists, in
  ;; one of two compiler-injected variants chosen by whether `:flint/check` is
  ;; in `features` -- the real implementation when it is, and an ON-shaped but
  ;; inert stand-in (no-op macros, throwing functions) when it is not. That is
  ;; what lets a program name `flint.check/expect` or `flint.check/run-tests`
  ;; with no `#?(:flint/check ...)` wrapper and no `:require`, in EITHER build:
  ;; the old design made the namespace vanish entirely under `:optimize
  ;; [perf]`, which meant code could only reach it from inside a reader
  ;; conditional, and this root addition was itself conditional on the same
  ;; feature. Both of those are gone -- `flint.check` is a root the same way
  ;; `flint.port`/`flint.wire` are, and `features` only selects WHICH source
  ;; the single `flint.check.cljc` resolves to via its own internal
  ;; `#?(:flint/check A :default B)` branches (`DECISIONS.md#namespaces-over-the-system-port`
  ;; §4), the same deferred-`#?` mechanism every other stdlib file already
  ;; uses -- not whether the namespace is requested at all.
  (let [given (vec (or roots* [entry-ns]))]
    (into given '[flint.port flint.wire flint.check])))

(defn- finish-project
  "What both walks answer once every namespace is collected: the order already
  topological and core-first, each namespace's workspace, and the refusals."
  [sources missing]
  {:sources sources
   :order (vec (core-first (topo-order sources)))
   :workspaces (into {} (map (fn [e] [(key e) (:workspace (val e))]) sources))
   :refused (refused-requires sources)
   :missing missing})

;; ------------------------------------------------- namespaces asked for in waves
;;
;; `DECISIONS.md#namespaces-over-the-system-port`. The compiler is handed a
;; RESOLVER it can only ASK, and asks it for a whole LEVEL of the require graph
;; at once: the roots, then every name the answers mention that has not been
;; asked yet, sorted by printed name. The number of round trips is the depth of
;; the graph rather than its size, an asynchronous host gets a batch it can
;; fetch in parallel, and the sequence of requests is a function of the answers
;; alone.

(defprotocol Resolver
  "Where a compile's namespaces come from: ANY value implementing this can run
  the compiler (`DECISIONS.md#namespaces-over-the-system-port`, \"the resolver
  protocol\").

  It speaks FORMS, not text and not bytes. Whoever implements it has already
  read each file -- `bin/flint` with the kin Java reader over a directory, the sandboxed
  compiler's adapter by decoding the `flint.compiler.forms` bytes its host sent
  (`flint.compiler.selfhost/port-resolver`) -- so nothing below this line reads source.

  An ordinary BLOCKING call: a file read in babashka, a park on a port in the
  sandbox. Implemented by METADATA in flint, which has no `reify`; the
  protocol is declared `:extend-via-metadata` on the JVM side so the same
  implementation works under Clojure and babashka. flint's `defprotocol`
  always dispatches on metadata first and takes no options, hence the splice."
  #?@(:flint [] :default [:extend-via-metadata true])
  (resolve-wave [r names]
    "Answer `names` -- a vector of namespace symbols, sorted by printed name,
    none asked before in this compile -- with a vector parallel to it. Each
    element is one of:

        nil                                     not found
        {:forms [form ..] :file \"app/main.cljc\" :dialect :flint | :portable
         :workspace w :grants #{..} :guard #{..} :prelude [..] :tags {..}}
        {:virtual true :vars [..]? :workspace w :grants #{..} :guard #{..}}
        {:error {:message .. :file .. :line .. :column ..}}

    `:forms` are READ under this compile's features. Every identity field is
    optional; absent, the namespace belongs to the anonymous workspace."))

(defn- answer-entry
  "One resolver answer, normalised exactly as `file-answer` normalises a
  workspace entry -- a set for each capability list, a read prelude -- because
  the two walks must hand the compiler EQUAL values or their images differ."
  [n a]
  (if (:virtual a)
    {:virtual true :vars (:vars a) :file (or (:file a) (str n))
     :workspace (:workspace a)
     :grants (set (:grants a)) :guard (set (:guard a))}
    {:forms (:forms a)
     ;; THE BYTES too, when the resolver had them: a door that writes the
     ;; resolved program out as a spec (`bin/flint --emit-spec`) hands the
     ;; source on READ, since the compiler that takes the spec reads no text
     ;; (`DECISIONS.md#one-reader-and-no-other`).
     :preread (:preread a)
     :file (:file a) :dialect (or (:dialect a) (dialect-of (:file a)))
     :workspace (:workspace a) :tags (:tags a)
     :prelude (normalise-prelude (:prelude a))
     :grants (set (:grants a)) :guard (set (:guard a))}))

(defn- take-answer
  "Fold one answer `a` for namespace `n` into the walk's state `st`."
  [st n a]
  (cond
    (nil? a) (update st :missing conj n)

    ;; The host's own failure for this namespace: an unreadable file carries a
    ;; position and is a `:read` error, a hook that threw is a `:resolver` one.
    (:error a)
    (let [e (:error a)]
      (update st :errors conj
              (cond-> {:kind (if (:line e) :read :resolver) :ns n
                       :message (or (:message e) "the resolver failed")}
                (:file e) (assoc :file (:file e))
                (:line e) (assoc :line (:line e))
                (:column e) (assoc :column (:column e)))))

    (:virtual a)
    (-> st
        (update :sources assoc n (answer-entry n a))
        (update :order conj n))

    :else
    (let [s (answer-entry n a)
          forms (:forms s)
          nsf (ns-form forms)
          declared (when nsf (second nsf))]
      (if (and declared (not= declared n))
        ;; ANSWERED X WITH A FILE DECLARING Y. Compiling it under the name
        ;; asked for would put Y's definitions where X's requirers look.
        (update st :errors conj
                {:kind :resolver :ns n :file (:file s)
                 :message (str "asked for " n ", answered with a file declaring " declared)})
        (let [reqs (compiler/ns-require-positions (or nsf '(ns x)))
              ;; A PRELUDE ENTRY IS AN IMPLICIT REQUIRE, and has to create
              ;; the same edge. Its names resolve without a `:require`, so
              ;; nothing else would ever pull the namespace in -- it would be
              ;; absent from the program and every prelude name would fail to
              ;; resolve. An edge rather than a pin, so `topo-order` also puts
              ;; the prelude BEFORE the code using it. SELF IS EXCLUDED: a
              ;; workspace's prelude covers its own namespaces too, and one of
              ;; them would otherwise be asked to precede itself.
              ;;
              ;; AND THE IMPLICIT `clojure.core` REFER IS ONE TOO
              ;; (`DECISIONS.md#namespaces-over-the-system-port` §4, the
              ;; 2026-10-07 addendum): `ana/ns-prelude` is the prelude this
              ;; namespace's names resolve through, `:refer-clojure` and
              ;; `:refer :all` applied, so `clojure.core` is asked for on
              ;; behalf of each namespace that refers it -- and of none that
              ;; does not -- instead of being a root of every compile. Its
              ;; position is the `ns` form's, so a resolver that cannot answer
              ;; it reports `:missing` AT THE NAMESPACE THAT NEEDED IT.
              pre (remove (fn [x] (= x n)) (map :ns (ana/ns-prelude (:dialect s) (:prelude s) nsf)))
              at (select-keys (meta nsf) [:line :column])
              by (reduce (fn [m [r pos]]
                           (update m r (fnil conj [])
                                   (assoc pos :ns n :file (:file s))))
                         (:by st)
                         (concat reqs (map (fn [x] [x at]) pre)))]
          (-> st
              (update :sources assoc n s)
              (update :order conj n)
              (assoc :by by)
              (update :named into (concat (map first reqs) pre))))))))

(defn collect-waves
  "Read from `roots` outwards, asking `resolver` (a `Resolver`) for a WHOLE
  LEVEL of the graph per call -- the one walk every door goes through. Each name is asked AT MOST ONCE per walk, missing ones included,
  and every request is sorted by printed name, so the requests are a function
  of the answers alone.

  The `:sources` map is what the depth-first walk this replaced built for the
  same answers -- the same entries, built from the same fields -- which is
  what kept every image byte-identical across the change. The ORDER the
  namespaces arrive in differs, and nothing downstream reads it: `topo-order`
  sorts.

  Answers `{:sources .. :order .. :missing .. :errors .. :waves .. :by ..}`:
  `:order` is the namespaces answered, in request order; `:waves` every request
  made; `:by` each required name's requirers, with where each named it."
  [resolver roots]
  (loop [want (vec (sort-by str (distinct (remove virtual-namespaces roots))))
         st {:asked #{} :sources {} :order [] :missing [] :errors [] :waves []
             :by {} :named []}]
    (if (empty? want)
      (dissoc st :named :asked)
      ;; BOUND, NOT AN ARGUMENT: `resolve-wave` may park on a port, and a
      ;; parking call in an argument position is re-entered with a partly built
      ;; frame under it (`flint.host/request` says so at more length).
      (let [answers (resolve-wave resolver want)
            st (reduce (fn [st i] (take-answer st (nth want i) (nth answers i)))
                       (-> st (update :asked into want) (update :waves conj want) (assoc :named []))
                       (range (count want)))
            asked (:asked st)
            fresh (vec (sort-by str (distinct (remove (fn [x] (or (contains? asked x)
                                                                  (contains? virtual-namespaces x)))
                                                      (:named st)))))
            ;; `flint.virtual`, when a virtual namespace arrived and nothing
            ;; asked for it -- `resolve-project` says why.
            fresh (if (and (empty? fresh)
                           (some (fn [e] (:virtual (val e))) (:sources st))
                           (not (contains? asked 'flint.virtual)))
                    ['flint.virtual]
                    fresh)]
        (recur fresh st)))))

(defn resolve-project-waves
  "`resolve-project` over a `Resolver` (`collect-waves`), answering its map
  plus `:errors`, `:reached` and `:waves`.

  `:errors` is every failure to RESOLVE, as data
  (`DECISIONS.md#namespaces-over-the-system-port`): `{:kind :missing :ns n
  :required-by [m ..] :file :line :column}` positioned at the first requirer's
  naming of it, `:read` and `:resolver` from the answers, and `:refused` for a
  guarded require. All of them at once: reporting the first and stopping makes
  fixing a dependency list an n-round conversation."
  [resolver entry-ns features roots*]
  (let [roots (project-roots entry-ns features roots*)
        w (collect-waves resolver roots)
        by (:by w)
        missing (vec (distinct (:missing w)))
        r (finish-project (:sources w) missing)
        missing-errors (mapv (fn [n]
                               (let [rs (get by n)
                                     at (first rs)]
                                 (cond-> {:kind :missing :ns n
                                          :required-by (vec (distinct (map :ns rs)))}
                                   (:file at) (assoc :file (:file at))
                                   (:line at) (assoc :line (:line at))
                                   (:column at) (assoc :column (:column at)))))
                             missing)
        answer-errors (mapv (fn [e] (assoc e :required-by (vec (distinct (map :ns (get by (:ns e)))))))
                            (:errors w))
        refused (mapv (fn [x] (assoc x :kind :refused)) (:refused r))]
    (assoc r
           :errors (vec (concat missing-errors answer-errors refused))
           :reached (mapv (fn [n] {:ns n :workspace (:workspace (get (:sources w) n))})
                          (:order w))
           :waves (:waves w))))

(defn fn-resolver
  "A `Resolver` over a FUNCTION resolver `resolve-ns` (symbol -> nil, a virtual
  answer, or `{:src | :preread :file :workspace :tags ..}` as `files-resolver`
  answers), reading each file with `read-entry` under `features`. How every
  caller that still holds a function -- the EDN-spec doors, the tests -- walks
  through the ONE wave walk rather than a second copy of it. A read that fails
  THROWS here, as it always has for these callers.

  `sink`, optional: forwarded to every `read-entry` call, so a caller driving
  a whole project through this resolver can collect elided conditionals
  across every file it reads, not just one."
  [resolve-ns features & [sink]]
  (with-meta {}
    {'flint.compiler.resolve/resolve-wave
     (fn [_ names]
       (mapv (fn [n]
               (let [s (resolve-ns n)]
                 (if (or (nil? s) (:virtual s))
                   s
                   (assoc s :forms (read-entry s features sink)))))
             names))}))

(defn resolve-project
  "Everything a compile needs, from an entry and a namespace resolver
  FUNCTION (`fn-resolver`). Returns `{:sources .. :order .. :workspaces ..
  :refused .. :missing ..}` with the order already topological and core-first.
  `:refused` is REPORTED for the same reason `:missing` is: whether a refused
  require stops the build is the front end's call, and a tool listing
  dependencies wants to see them all.

  `roots` overrides the entry as the starting point (`project-roots`).

  THROUGH THE WAVE WALK since `DECISIONS.md#namespaces-over-the-system-port`
  step 2. There used to be a second walk here, depth-first and one namespace at
  a time; it built the same `:sources` map, and `:missing` now comes out in
  request order (sorted per wave) rather than in the order it was met."
  ([resolve-ns entry-ns features] (resolve-project resolve-ns entry-ns features nil))
  ([resolve-ns entry-ns features roots*]
   (select-keys (resolve-project-waves (fn-resolver resolve-ns features) entry-ns features roots*)
                [:sources :order :workspaces :refused :missing])))
