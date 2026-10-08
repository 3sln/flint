;; The flint bootstrap driver -- JVM Clojure, invoked by `bin/flint`
;; (`DECISIONS.md#namespaces-over-the-system-port`, migration step 1.2).
;;
;; THIS REPLACES BABASHKA. The resolution this file used to do by hand --
;; `collect`, `topo-order`, `refuse-guarded-requires!`, `core-first` -- now
;; lives in `flint.compiler.resolve` (AGENTS.md sec. 1: two lists drift, so the second
;; one is deleted rather than kept in step). What stays here is the HOST half
;; only `bin/flint` can be: finding files on a search path, reading `deps.edn`
;; for a workspace's grants and tags, and implementing
;; `flint.compiler.resolve/Resolver` over the filesystem with the kin-generated Java
;; reader (`flint.driver.host-reader`) instead of `flint.compiler.reader` -- the guest's
;; own reader, which this driver no longer calls to resolve a project (see
;; `ns-of` and the compile below). `flint.compiler.reader` stays required for now:
;; `flint.compiler.resolve/read-entry` still calls `resolve-conditionals` on it to
;; resolve a deferred read's conditionals for this compile's features, which
;; is forms-to-forms work and not a second text reader.
;; flint :src <dir> :fn <ns/fn> [:out <file.wasm>]
(ns flint.driver.main
  (:require [clojure.string :as str] [clojure.java.io :as io]
            [flint.driver.fs :as fs] [clojure.edn :as edn]
            [flint.compiler.core :as compiler] [flint.compiler.image :as img] [flint.compiler.link :as link]
            [flint.compiler.reader :as reader] [flint.compiler.lint :as lint]
            [flint.compiler.forms :as forms]
            [flint.driver.host-reader :as hr]
            ;; For `source-extensions` and now for the whole resolver walk:
            ;; `flint.compiler.resolve/fn-resolver` and `flint.compiler.resolve/resolve-project-waves`
            ;; are the ONE wave walk every door goes through.
            [flint.compiler.resolve :as project]
            ;; The project surface -- `tasks`, `task`, `build`, `fetch`, ... -- is
            ;; flint code, in `lib/`, because 0021's argument is that it has to
            ;; survive losing babashka. What is in this file is the host half.
            [flint.cli :as cli] [flint.deps :as deps]
            [flint.compiler.wasm :as w] [flint.compiler.modmeta :as modmeta]
            ;; The CLR writer, for `:to :clr`. Portable cljc, so this driver and
            ;; the self-hosted compiler load the same emitter.
            [flint.compiler.clr :as clr]
            ;; The JVM writer, for `:to :jvm`, and portable cljc for the same reason.
            [flint.compiler.jvm :as jvm] [flint.rt]))

;; THE REPO ROOT, from `FLINT_ROOT` -- NOT the current directory. `bin/flint`
;; sets it (computed in a subshell, never `cd`ing the process itself) because
;; `flint task`/`flint build` read a "deps.edn" in the CALLER's own working
;; directory as that caller's project (`test/cli.clj` runs this with its cwd
;; set to a temp project precisely so that lookup finds it); a `cd` to the
;; repo root here would have this door running every task against ITS OWN
;; deps.edn instead, which is what "no such task: greet" turned out to mean.
;; Falls back to the current directory when run directly (`FLINT_ROOT` unset)
;; so a `clojure -M -m flint.driver.main` invocation from the repo root --
;; what the earlier `(fs/real-path *file*)` approach this replaced also could
;; not do -- still works for exploration.
(def root (or (System/getenv "FLINT_ROOT") (.getCanonicalPath (io/file "."))))

(def shipped-roots
  "The source roots of the guest code flint ships, in search order: the
  standard library's two layers, then `flint.deps` and `flint.cli`, each its own
  workspace (`DECISIONS.md#four-units`). The native CLI embeds the same four
  (`cli/build.rs`), and `bin/build-stdlib-forms` reads them for the JavaScript
  doors."
  (mapv (clojure.core/fn [d] (str root "/" d)) ["lib/stdcore" "lib/stdextra" "lib/deps" "cli/lib"]))

(defn clr-name
  "An assembly name from the output path -- READ from `flint.compiler.clr/assembly-name`
  rather than restated here.

  This door had its own copy of the rule, and the native CLI and `sdks/cli` each
  wanted one too. Three copies of a sanitiser agree on `app.dll` and part ways on
  `a.b.dll`, which is the drift `AGENTS.md` sec. 1 names; the shared one is in the
  emitter both this door and `flint.compiler.selfhost` already call.

  Deriving it from the path is what makes `:out app.dll` and `:out other.dll`
  distinct rather than both `Program`. It is also what makes the two doors'
  assemblies byte-identical, since the name sits in the `#Strings` heap and moves
  every offset after it."
  [out]
  (clr/assembly-name (str out)))

(defn script-ns
  "What a file's `ns` form says, for the two things a script CLI needs: the
  namespace, and the entry its `^:script` mark names.

  Read with a reader rather than by matching text, because `^:script` is
  METADATA and metadata is the reader's business -- a regex over the source
  would get `^{:script go}` wrong, and that is the form naming an entry other
  than `main`. The reader also knows about `#!`, which is why the shebang line
  needs no handling here.

  The kin Java reader, deferred, via `flint.driver.host-reader` -- this driver
  resolves a project's namespaces through it now, and a second text reader
  for just this one case would be the duplication AGENTS.md sec. 1 warns
  about."
  [f]
  (try
    (let [text (slurp (str f))
          dialect (project/dialect-of (str f))
          bytes (hr/deferred-read (str f) text dialect {})
          form (first (:forms (forms/decode bytes)))]
      (when (and (seq? form) (= 'ns (first form)) (symbol? (second form)))
        (let [nm (second form)
              mark (:script (meta nm))]
          {:ns (str nm)
           :entry (cond (true? mark) (str nm "/main")
                        (symbol? mark) (str nm "/" mark)
                        :else nil)})))
    (catch Exception _ nil)))

(defn- option? [s] (or (str/starts-with? s ":") (str/starts-with? s "-")))

(defn- take-list
  "Values for a list-valued option. Accepts `[a b c]` as one shell word, as
  several words, or a bare single value -- because which of those the shell
  hands over depends on quoting, and failing on the wrong one is a bad welcome."
  [a]
  (let [xs (take-while (complement option?) a)]
    (if (empty? xs)
      [[] a]
      (let [joined (str/join " " xs)
            bracketed? (str/starts-with? joined "[")]
        (if bracketed?
          (let [inner (str/replace (str/replace joined #"^\[" "") #"\]$" "")]
            [(vec (remove str/blank? (str/split inner #"[,\s]+"))) (drop (count xs) a)])
          [(vec xs) (drop (count xs) a)])))))

(defn parse-args [args]
  (loop [a args m {}]
    (if-let [k (first a)]
      (cond
        (= k ":src") (recur (drop 2 a) (update m :src (fnil conj []) (second a)))
        (= k ":fn") (recur (drop 2 a) (assoc m :fn (symbol (second a))))
        (= k ":exclude") (let [[vs rest-a] (take-list (rest a))]
                           (recur rest-a (update m :exclude (fnil into #{}) (map symbol vs))))
        ;; MORE ROOTS, so a function nobody calls from inside the module is
        ;; still reachable from OUTSIDE it (`DECISIONS.md#structured-ports` step 5).
        ;; Nothing runs automatically any more: a host asks for a function by
        ;; name over the system port, and a name the linker dropped is a name
        ;; that cannot be asked for.
        (= k ":exports") (let [[vs rest-a] (take-list (rest a))]
                           (recur rest-a (update m :exports (fnil into []) (map symbol vs))))
        ;; `:wasm-ld` shipped once under that name and reads like "flags for the
        ;; linker"; `:wasm-path` says what it is. The old spelling stays as a
        ;; deprecated alias for one release (DECISIONS.md#threads-and-ports, section 7).
        (= k ":features") (let [[vs rest-a] (take-list (rest a))]
                            (recur rest-a (assoc m :features (into #{} (map keyword vs)))))
        (= k ":wasm-path") (let [[vs rest-a] (take-list (rest a))]
                             (recur rest-a (update m :wasm-path (fnil into []) vs)))
        (= k ":wasm-ld") (let [[vs rest-a] (take-list (rest a))]
                           (println "note: :wasm-ld is deprecated; it is now :wasm-path")
                           (recur rest-a (update m :wasm-path (fnil into []) vs)))
        (= k ":out") (recur (drop 2 a) (assoc m :out (second a)))
        (= k ":o") (recur (drop 2 a) (assoc m :out (second a)))
        (= k "--stats") (recur (rest a) (assoc m :stats true))
        (= k "--keep-names") (recur (rest a) (assoc m :keep-names true))
        (= k "--explain") (recur (drop 2 a) (assoc m :explain (symbol (second a))))
        (= k "--disasm") (recur (drop 2 a) (assoc m :disasm (second a)))
        (= k "--self") (recur (rest a) (assoc m :self true))
        ;; DECISIONS.md#emit-wasm-instead-of-dispatch. A flag rather than a default until the
        ;; benchmarks say which way round the numbers fall. `FLINT_AOT=1` turns
        ;; it on for every build, which is how the whole existing suite gets run
        ;; against compiled code without a single test knowing about it.
        ;; `:optimize` is the config `DECISIONS.md#structured-ports` describes: an ORDERED
        ;; PREFERENCE, not a switch. The first token this build understands
        ;; decides and the rest are ignored, which is what lets a script written
        ;; against a newer flint still get an older one's best effort.
        (= k ":optimize") (let [[xs rest*] (take-list (rest a))]
                            (recur rest* (assoc m :optimize (mapv str xs))))
        ;; The older spelling, kept because it is in scripts and in the AOT
        ;; bisection handles. `:optimize [perf]` is the one to write.
        (= k "--aot") (recur (rest a) (assoc m :aot true))
        ;; DECISIONS.md#construe-integration-bar: a module that can be handed an IMAGE at run time,
        ;; so a Worker can compile a candidate and run it without linking.
        (= k "--loader") (recur (rest a) (assoc m :loader true))
        ;; Emit the bytecode IMAGE and stop -- no linker, no module. This is the
        ;; half of the toolchain that can run somewhere `rust-lld` cannot.
        (= k "--emit-image") (recur (rest a) (assoc m :emit-image true))
        ;; `:to` LIVES HERE TOO NOW. Without this clause the `:else` at the
        ;; bottom of this `cond` says "unknown argument: :to" -- so
        ;; `bin/flint ... :to :clr` died naming the flag rather than the target,
        ;; which reads like a typo rather than a missing feature.
        (= k ":to") (recur (drop 2 a) (assoc m :to (str/replace (str (second a)) #"^:" "")))
        ;; The compiler's INPUT, written out. `out/flintc-gen0.wasm` takes this
        ;; and nothing else, so it is what a Worker has to be handed
        ;; (DECISIONS.md#construe-integration-bar).
        (= k "--emit-spec") (recur (rest a) (assoc m :emit-spec true))
        (= k "--const") (recur (drop 2 a) (assoc m :const (parse-long (second a))))
        ;; A STANDALONE SCRIPT, named directly. `flint <file>` already runs one
        ;; and `flint compile <file>` now builds one; this CLI could do
        ;; neither, because its `:src` wanted a directory
        ;; (`DECISIONS.md#standalone-scripts`).
        ;;
        ;; It fills in the two things the file already says -- itself as the
        ;; source, and the entry its `^:script` mark names -- and then stops:
        ;; everything after the file is left for the options that follow, so
        ;; `:out` and the rest keep working exactly as they do for a project.
        ;;
        ;; NO ENTRY, NO RUN. A module deliberately has no entry point, and
        ;; inventing one is what this codebase refuses everywhere else.
        (and (fs/regular-file? k) (nil? (:fn m)))
        (let [nsform (script-ns k)]
          (when-not nsform
            (println (str k " has no `ns` form, so it is not a flint script."))
            (System/exit 2))
          (when-not (:entry nsform)
            (println (str "(ns " (:ns nsform) ") in " k " is not marked `^:script`,"
                          " so nothing names its entry point."))
            (println (str "  write (ns ^:script " (:ns nsform) " ...) to use "
                          (:ns nsform) "/main, or name it with :fn"))
            (System/exit 2))
          (recur (rest a) (-> m
                              (update :src (fnil conj []) k)
                              (assoc :fn (symbol (:entry nsform))))))
        :else (do (println "unknown argument:" k) (System/exit 2)))
      m)))

;; `ns->path` USED TO BE DEFINED HERE, byte-for-byte what `flint.compiler.resolve/ns->path`
;; already does (AGENTS.md sec. 1: a fourth copy was a fourth chance to
;; disagree about what a source file is). Read from there instead.

(defn source-candidates
  "Every file on the search path that could be namespace `n`, in path order.
  More than one is not an error -- it is shadowing, and shadowing is reported.

  A `:src` MAY BE A FILE and not only a directory. A standalone script is one
  file and has no directory to be found under, so `:src <dir>` alone could not
  reach it -- which is why this CLI could compile a project and not a script
  (`DECISIONS.md#standalone-scripts`). A file entry answers for the namespace
  it is NAMED after, the same rule a directory entry follows, so nothing else
  in the search path changes meaning."
  [dirs n]
  (let [want (project/ns->path n)
        leaf (last (str/split (str want) #"/"))]
    (distinct
      (concat
        (for [d dirs
              :let [f (io/file (str d))]
              :when (and (.isFile f)
                         (some (clojure.core/fn [ext] (= (.getName f) (str leaf ext)))
                               project/source-extensions))]
          (str f))
        (for [d dirs
              ext project/source-extensions
              :let [f (io/file (str d) (str want ext))]
              :when (.exists f)]
          (str f))))))

(defn- root-of
  "Which search-path dir a file came from. A file belongs to the project that
  owns its root, and reader tags are bound per PROJECT (`DECISIONS.md#reader-tags`),
  so this is what a tag lookup keys on.

  THE LONGEST MATCHING DIR, not the first: roots may nest -- `lib/deps/` sits
  inside `lib/` -- and a file belongs to the innermost root that holds it.
  `flint.deps` used to need a carve-out here, a path check that pushed its four
  files one directory deeper than `lib/` so `project-of` read their own
  `deps.edn` (`DECISIONS.md#flint-deps-is-its-own-workspace`); it is a root of
  its own now, `lib/deps/` (`DECISIONS.md#four-units`), and the carve-out is
  gone."
  [dirs f]
  (let [under? (clojure.core/fn [d]
                 ;; A `:src` may be the FILE itself (a script), and a dir may be
                 ;; spelled with a trailing slash.
                 (let [d (str/replace (str d) #"/+$" "")]
                   (or (= (str f) d) (str/starts-with? (str f) (str d "/")))))]
    (last (sort-by (comp count str) (filter under? dirs)))))

(defn find-source
  "First hit wins. The path is `:src` dirs, then `:wasm-path` dirs, then flint's
  own `lib/`: your source beats source a unit vendored beside itself, which
  beats flint's."
  [dirs n]
  (when-let [f (first (source-candidates dirs n))]
    {:src (slurp (io/file f)) :file f :root (root-of dirs f)}))

(defn project-of
  "What a source root's OWN project file says about it (`DECISIONS.md#workspace-capabilities`).

      {:name       who this workspace is
       :tags       the reader tags its sources are read under (`reader-tags`)
       :grants     the capabilities it holds
       :guard      what another workspace must hold to require it}

  Read from `deps.edn` beside the root or one directory up, and never from the
  project doing the compiling. That is the whole point of a workspace: two
  libraries may both want `#x`, and a capability one was granted is not one the
  other holds.

  This used to be `tag-readers-for` and read the same file for one key. Reading
  it once for all four is not only cheaper -- it is what makes the workspace a
  single concept rather than a set of unrelated lookups that happen to share a
  file.

  `:name` defaults to the root's own path. A workspace needs an identity, not a
  pretty one, and two roots without declared names must not collide into one --
  which is exactly what a `nil` name shared between them would do."
  [d]
  (let [try-read (clojure.core/fn [p]
                   (try (let [m (edn/read-string (slurp p))]
                          (when (map? m) m))
                        (catch Exception _ nil)))
        m (or (try-read (io/file d "deps.edn"))
              (try-read (io/file (.getParentFile (.getAbsoluteFile (io/file d))) "deps.edn"))
              {})]
    {:name (or (:flint/workspace m) (symbol (str d)))
     :tags (or (:flint/tag-readers m) {})
     ;; The names that resolve without a `:require`. Read here with the rest of
     ;; the workspace, for the reason this fn already gives: four lookups in one
     ;; file are one concept, not four.
     :prelude (vec (:flint/prelude m))
     :grants (set (:flint/capabilities-grant m))
     :guard (set (:flint/capabilities-guard m))}))

(def virtual-namespaces
  ;; flint.rt is not a namespace you can load: the analyzer turns flint.rt/x into
  ;; a direct call into the wasm table. src/flint/rt.cljc exists only so the same
  ;; source runs on a host.
  '#{flint.rt})

(def elisions
  "Reader conditionals that matched no feature, gathered across every source.

  Was DEAD from the move off babashka until this fix: `flint.compiler.reader/elided`
  was a property of the LIVE reader state `collect` held, and the kin
  reader's deferred forms carried no such log back through
  `flint.compiler.resolve/read-entry` -- nothing pushed into this volatile, so the
  note it fed (\"N reader conditional(s) ... matched none of ... -- the form
  each stood in was DELETED\") never printed, and no suite caught it: a
  `grep` for the message over `test/cli.clj`, `test/door-agreement.clj` and
  `test/selfhost*.clj`, done when this regression was first recorded, missed
  `test/options.clj`'s own check for it (AGENTS.md sec. 1 -- the grep that
  justified leaving this dead did not cover every list).

  Revived by threading an optional `sink` through
  `flint.compiler.reader/resolve-conditionals` (and `flint.compiler.resolve/read-entry` and
  `fn-resolver` above it) down to the one place that calls `choose` on a
  deferred conditional -- so a DEFERRED read now records an elision the same
  way the live reader's `read-cond` always did, as `{:file :line :offered}`,
  and `resolve-sources!` passes this volatile as that sink."
  (volatile! []))

(defn ns-of
  "The namespace a source file at `path` declares, or nil.

  Reads only the first form, which is all `(ns ...)` can be -- so this does not
  need the feature set and cannot be confused by a reader conditional further
  down. Through the kin Java reader, like `script-ns` -- see its docstring for
  why this driver does not keep a second text reader for just this case."
  [path]
  (try
    (let [text (slurp (str path))
          dialect (project/dialect-of (str path))
          bytes (hr/deferred-read (str path) text dialect {})
          f (first (:forms (forms/decode bytes)))]
      (when (and (seq? f) (= 'ns (first f)) (symbol? (second f)))
        (second f)))
    (catch Exception _ nil)))

;; ---------------------------------------------------------------- resolution
;;
;; `collect`, `topo-order`, `refuse-guarded-requires!`, `core-first` and
;; `implied-requires` USED TO BE HERE, hand-duplicating
;; `flint.compiler.resolve` (AGENTS.md sec. 1). They are gone: `resolve-ns` below only
;; finds a file and reads it -- the one thing the compiler cannot do for
;; itself -- and the wave walk, the topological order, the guard check and
;; `core-first` all come from `flint.compiler.resolve/resolve-project-waves`, the SAME
;; walk the EDN doors go through (`DECISIONS.md#namespaces-over-the-system-port`).

(defn resolve-ns
  "A `flint.compiler.resolve/fn-resolver` function over the filesystem, through the kin
  Java reader (`flint.driver.host-reader`) instead of `flint.compiler.reader`.

  Answers `nil` (not found) or `{:preread :src :file :dialect :workspace
  :tags :prelude :grants :guard}` -- `fn-resolver` decodes `:preread` under
  this compile's features via `flint.compiler.resolve/read-entry`, so a file is read
  ONCE here, deferred, however many compiles in this process reach it.

  `:src` RIDES ALONG, UNUSED BY THE NORMAL PATH: `read-entry` takes the
  `:preread` branch whenever its `:opts` match (always, for a file this
  driver just read itself), so `:src` is dead weight on every ordinary
  compile. It matters for `--emit-spec` and `--self`: the spec they build
  keeps every field but `:forms` (`(dissoc s :forms)`, below), and without
  `:src` a spec handed to `flint.compiler.selfhost`'s `build-image` -- which skips
  resolution for an already-resolved spec and falls through to
  `flint.compiler.core/read-source`'s `:src`-or-nothing fallback -- found NO text
  for `clojure.core` at all: `bin/check-sdk`'s reference artifact failed
  with \"no source for namespace clojure.core\" before this was added.

  A read the kin reader refuses THROWS, as `flint.compiler.resolve/fn-resolver`'s own
  docstring says every resolver function here must: `flint.driver.host-reader/deferred-read`
  raises `ex-info` with `:file`, `:line`, `:column`, and this driver's `catch`
  around the whole compile reports it exactly as a compile error would be."
  [dirs n]
  (when-let [s (find-source dirs n)]
    (let [proj (project-of (:root s))
          dialect (project/dialect-of (:file s))
          bytes (hr/deferred-read (:file s) (:src s) dialect (:tags proj))]
      {:preread bytes :src (:src s) :file (:file s) :dialect dialect
       :workspace (:name proj) :tags (:tags proj)
       :prelude (:prelude proj) :grants (:grants proj) :guard (:guard proj)})))

(defn- format-resolve-error
  "One `flint.compiler.resolve/resolve-project-waves` error, as a line for a person --
  the same three messages `collect`, `refuse-guarded-requires!` and the cycle
  check in `topo-order` used to print by hand, now read off the shared walk's
  own error shapes instead of a second copy of the rule that produces them."
  [e]
  (case (:kind e)
    :missing (str "cannot find source for namespace " (:ns e)
                  (when (seq (:required-by e))
                    (str ", required by " (str/join ", " (:required-by e)))))
    :refused (str (:from e) " requires " (:to e) ", which " (:to-workspace e)
                  " guards with " (pr-str (:needs e)) "; " (:from-workspace e)
                  " does not hold it")
    :read (str (:file e) ":" (:line e) ":" (:column e) ": " (:message e))
    :resolver (str (:ns e) ": " (:message e))
    (str (:ns e) " " (name (or (:kind e) :error)) ": " (:message e))))

(defn resolve-sources!
  "Everything a compile needs, from `entry-ns` outwards, over the filesystem
  resolver above -- `dirs`, `entry-ns`, `roots*` and `features` exactly as
  `collect`'s arguments used to be, but through
  `flint.compiler.resolve/resolve-project-waves`. `:sources` and `:order` come back
  already topologically sorted and core-first
  (`flint.compiler.resolve/finish-project`); any resolution error -- missing, refused, a
  bad answer shape -- is reported all at once and exits 1, the way `collect`
  reported a missing namespace and `refuse-guarded-requires!` reported a guard
  in one shot each, now unified into one report.

  `elisions` rides along as `fn-resolver`'s sink, so a matched-nothing
  conditional in ANY file this walk reads lands in the one list `compile`
  below reports from -- see `elisions`'s docstring."
  [dirs entry-ns features roots*]
  (let [resolver (project/fn-resolver (clojure.core/fn [n] (resolve-ns dirs n)) features elisions)
        r (project/resolve-project-waves resolver entry-ns features roots*)]
    (when (seq (:errors r))
      (binding [*out* *err*]
        (doseq [e (:errors r)] (println (format-resolve-error e))))
      (System/exit 1))
    {:sources (:sources r) :order (:order r)}))

(defn b64-decode [s]
  (vec (map #(bit-and (int %) 0xff)
            (.decode (java.util.Base64/getDecoder) ^String (str/trim s)))))

(def selfc (str root "/out/flintc.wasm"))

(defn ensure-self-compiler!
  "The self-hosted compiler, built by babashka once. After this, flint compiles
  flint -- which is the whole point of the bootstrap, so it is worth being able
  to use it and not only to test it."
  []
  (when-not (.exists (io/file selfc))
    (println "building the self-hosted compiler (once) ...")
    (let [p (.start (ProcessBuilder.
                     (into-array String [(str root "/bin/flint") ":src" (str root "/src")
                                         ":fn" "flint.compiler.selfhost/main" ":out" selfc])))]
      (slurp (.getInputStream p)) (slurp (.getErrorStream p)) (.waitFor p)
      (when-not (zero? (.exitValue p))
        (println "could not build the self-hosted compiler") (System/exit 1))))
  selfc)

(defn compile-with-flint
  "Run the compiler ON flint. Returns {:image bytes :natives [names]}."
  [spec]
  (let [module (ensure-self-compiler!)
        tmp (fs/create-temp-file {:suffix ".edn"})
        _ (spit (str tmp) (pr-str spec))
        p (.start (ProcessBuilder.
                   (into-array String ["node" (str root "/host/flint-file.mjs") module (str tmp)])))
        out (slurp (.getInputStream p))
        err (slurp (.getErrorStream p))]
    (.waitFor p)
    (when-not (zero? (.exitValue p))
      (println "self-hosted compile failed:" (str/trim out) err) (System/exit 1))
    (let [lines (str/split-lines out)]
      {:image (b64-decode (first lines))
       :natives (vec (remove str/blank? (rest lines)))})))

(defn builtin-names
  "Every builtin any unit on the path provides. The compiler needs the union so
  it can tell a typo from a builtin in a namespace this program has not reached
  yet."
  [unit-path]
  (into #{} (mapcat (fn [e] (keys (:provides (val e))))
                    (link/discover-units unit-path))))

(defn builtins-by-unit
  "unit name -> the set of builtins it provides, so `:exclude` can report a
  builtin as belonging to the namespace that ships it."
  [unit-path]
  (into {} (map (fn [e] [(key e) (set (keys (:provides (val e))))])
                (link/discover-units unit-path))))

(defn die!
  "Compile and link errors are for people, not for a stack trace. A failed
  `:exclude` in particular is a message somebody has to act on."
  [e]
  (binding [*out* *err*] (println (ex-message e)))
  (System/exit 1))

;; `flint run <file> <ns/fn> [args]` -- a module or a bytecode IMAGE. THE
;; FUNCTION IS NAMED: a module has no entry point, nothing is called
;; automatically, and no name is recorded for a runner to find
;; (`DECISIONS.md#structured-ports` step 5). The image path is
;; what construe's sandbox binding settled on and needs no linker anywhere
;; (DECISIONS.md#cli, 0023); it is here rather than in `cli/lib/flint/cli.cljc`
;; because running an image means instantiating a SECOND module, which is the
;; host's job and not the guest's.
(when (= "run" (first *command-line-args*))
  (let [[_ file fname & rest] *command-line-args*
        loader (or (System/getenv "FLINT_LOADER") "out/flint-loader.wasm")
        ;; An image is not a module: it has to be instantiated INTO a loader.
        ;; Saying so beats `ENOENT: out/flint-loader.wasm`, which names a path
        ;; the reader never chose and does not say what would produce it.
        _ (when (and (str/ends-with? (str file) ".image") (not (fs/exists? loader)))
            (binding [*out* *err*]
              (println (str "no loader at " loader))
              (println "an image is instantiated into a loader module rather than run on its own")
              (println "  flint build --image      builds both")
              (println "  FLINT_LOADER=<path>      points at one you already have"))
            (System/exit 1))
        script (str "import('" root "/host/run.mjs').then(async (m) => {"
                    "  const r = await m.run(process.argv[1], process.argv.slice(4),"
                    "                        {loaderPath: process.argv[2], fn: process.argv[3]});"
                    "  process.stdout.write(r.out); process.exitCode = r.code;"
                    "}).catch((e) => { console.error(String(e.message ?? e)); process.exit(2); })")
        p (.start (ProcessBuilder.
                   (into-array String (concat ["node" "-e" script "--" (str file) loader
                                               (str fname)]
                                              (map str rest)))))]
    (.start (Thread. (clojure.core/fn [] (io/copy (.getInputStream p) System/out))))
    (.start (Thread. (clojure.core/fn [] (io/copy (.getErrorStream p) System/err))))
    (System/exit (.waitFor p))))

;; `flint inspect <file.wasm>` -- what a module says about itself
;; (DECISIONS.md#module-metadata-and-shards). Read from the BYTES, with no instantiation: a runner
;; has to decide whether to instantiate at all, and on what glue, and that
;; decision cannot depend on having already done it.
(when (= "inspect" (first *command-line-args*))
  (let [file (second *command-line-args*)
        _ (when-not file
            (binding [*out* *err*] (println "usage: flint inspect <file.wasm>"))
            (System/exit 2))
        bytes (java.nio.file.Files/readAllBytes (.toPath (io/file file)))
        m (try (reader/read-one (String. ^bytes (w/custom-section (w/parse bytes) modmeta/section-name)
                                         "UTF-8"))
               (catch Exception _ nil))]
    (if (nil? m)
      (do (binding [*out* *err*]
            (println (str file " carries no flint metadata section."))
            (println "Either it is not a flint module, or it was built before 0020."))
          (System/exit 1))
      (do
        ;; No entry. There is not one to print any more -- nothing is called
        ;; automatically and a caller names the function it wants
        ;; (`DECISIONS.md#structured-ports`) -- and this went on printing the label with
        ;; nothing after it, which reads as an artifact that lost its entry
        ;; rather than one that never had the concept. `exports` below is the
        ;; honest answer to "what can I call".
        (println (str "flint " (:version m)))
        (println (str "compatibility key " (get-in m [:compat :key])
                      "   abi " (pr-str (get-in m [:compat :abi]))
                      "   " (name (get-in m [:compat :memory]))
                      (when (get-in m [:compat :gas-in-aot]) "   gas-in-aot")))
        (println (str "present: "
                      (str/join ", " (map (clojure.core/fn [e] (name (key e)))
                                          (filter val (:features m))))))
        (println (str "absent:  "
                      (str/join ", " (map (clojure.core/fn [e] (name (key e)))
                                          (remove val (:features m))))))
        (println (str "units:   " (str/join ", " (map :name (:units m)))))
        (println (str "exports: " (str/join " " (:exports m))
                      "   (+" (:builtins m) " builtins)"))
        (println (str "imports: " (if (seq (:imports m)) (str/join " " (:imports m)) "none")))
        ;; Last, and only when there is any. flint carries this and never reads
        ;; it (`DECISIONS.md#structured-ports`), which is exactly why it has to be VISIBLE:
        ;; the declared capabilities of a program live here by convention, and a
        ;; convention nobody can read from the artifact is not one.
        (when (seq (:meta m))
          (println "metadata (carried, not interpreted):")
          (doseq [e (sort-by (clojure.core/fn [e] (str (key e))) (:meta m))]
            (println (str "  " (key e) " " (pr-str (val e))))))
        (System/exit 0)))))

;; `flint check` -- what was written for flint and does nothing.
;;
;; Separate from `build` on purpose. Metadata is an open map, so the compiler
;; reads the keys it owns and is silent about the rest -- which is right, and
;; leaves a declaration aimed at flint and spelled slightly wrong carried,
;; never read, and never complained about. This is the opt-in half. It is
;; allowed to be wrong about intent, which a compiler is not.
(when (= "check" (first *command-line-args*))
  (let [opts (parse-args (rest *command-line-args*))
        dirs (or (seq (:src opts))
                 (let [d (try (edn/read-string (slurp "deps.edn")) (catch Exception _ nil))]
                   (seq (:paths d)))
                 ["src"])
        files (->> dirs
                   (mapcat (clojure.core/fn [d]
                             (when (.isDirectory (io/file d)) (file-seq (io/file d)))))
                   (filter (clojure.core/fn [f]
                             (and (.isFile ^java.io.File f)
                                  (some (clojure.core/fn [e]
                                          (str/ends-with? (.getName ^java.io.File f) e))
                                        project/source-extensions))))
                   (map (clojure.core/fn [f] (.getPath ^java.io.File f)))
                   sort)
        findings (mapcat (clojure.core/fn [f]
                           (try (lint/check (reader/read-all (slurp f)) f)
                                (catch Exception e
                                  [{:kind :unreadable :what (ex-message e)
                                    :why "this file could not be read"
                                    :at {:file f}}])))
                         files)]
    (println (str "checked " (count files) " file(s) under "
                  (str/join " " dirs)))
    (doseq [g (filter lint/finding? findings)]
      (println)
      (println (str "  " (:file (:at g))
                    (when-let [l (:line (:at g))] (str ":" l))
                    "   " (name (:kind g)) "   " (:what g)))
      (println (str "    " (:why g))))
    ;; Notes are summarised, never listed. Twenty-nine lines of `^bytes` is a
    ;; wall that hides the two lines that matter.
    (let [notes (remove lint/finding? findings)
          real (filter lint/finding? findings)]
      (when (seq notes)
        (println)
        (println (str "  " (count notes) " annotation(s) flint ignores. That is "
                      "correct if they are hints for another host:"))
        (doseq [[what n] (sort-by val > (frequencies (map :what notes)))]
          (let [a (:at (first (filter (clojure.core/fn [x] (= what (:what x))) notes)))]
            (println (str "    " what "  x" n "   first at " (:file a)
                          (when (:line a) (str ":" (:line a))))))))
      (println)
      (if (seq real)
        (do (println (str (count real) " finding(s). None of this is an error: "
                          "metadata is an open map and these keys may belong to "
                          "something else."))
            (System/exit 1))
        (do (println "nothing that names flint is being ignored.")
            (System/exit 0))))))

;; The project commands -- `tasks`, `task`, `deps`, `paths`, `targets`,
;; `version`, `help`. The LOGIC for all of them is `cli/lib/flint/cli.cljc`, a flint
;; program, because 0021's argument is that this surface has to survive losing
;; babashka. What is here is the host half: read a file, and run what the guest
;; says to run.
;;
;; Under bb the project reader is `slurp`; inside a compiled module it is the
;; `:fs` capability. `flint.cli` never learns which, which is the point of it
;; taking a function.
(when (contains? #{"tasks" "task" "build" "fetch" "deps" "paths" "targets" "version" "help"}
                 (first *command-line-args*))
  (let [slurp* (clojure.core/fn [p]
                 ;; `.isFile`, not `.exists`: a probe for a directory must
                 ;; answer nil rather than throw, and `fetch-plan` probes for a
                 ;; stamp inside one that may not be there.
                 (let [f (io/file p)] (when (.isFile f) (slurp f))))
        ;; The OTHER half of that probe. `:local/root` and `:pod/path` name
        ;; directories, and a walk with only a file reader cannot tell a
        ;; missing one from a present one -- which is why every `:local/root`
        ;; used to come back pending for ever.
        exists?* (clojure.core/fn [p] (.exists (io/file p)))
        ;; `std::env::consts` names, because those are what the shipped binary
        ;; compares a pod manifest against. The JVM's own spellings are
        ;; different and mapping them here, once, is what keeps a pod artifact
        ;; meaning the same thing to both hosts.
        os-name (let [o (str/lower-case (str (System/getProperty "os.name")))]
                  (cond (str/includes? o "mac") "macos"
                        (str/includes? o "win") "windows"
                        (str/includes? o "linux") "linux"
                        :else o))
        os-arch (let [a (str/lower-case (str (System/getProperty "os.arch")))]
                  (cond (contains? #{"aarch64" "arm64"} a) "aarch64"
                        (contains? #{"amd64" "x86_64"} a) "x86_64"
                        :else a))
        opts {:exists? exists?* :os/name os-name :os/arch os-arch}
        git! (clojure.core/fn [x]
               ;; A shallow fetch of ONE sha rather than a clone: it is the
               ;; cheap shape, and it cannot silently give you a different
               ;; commit later, which a branch clone can.
               (let [dir (:dir x)]
                 (println (str "fetching " (:dep x) "  " (:url x) " @ " (subs (str (:sha x)) 0 (min 8 (count (str (:sha x)))))))
                 (fs/create-dirs dir)
                 (doseq [args [["git" "init" "-q"]
                               ["git" "remote" "add" "origin" (:url x)]
                               ["git" "fetch" "-q" "--depth" "1" "origin" (:sha x)]
                               ["git" "checkout" "-q" "FETCH_HEAD"]]]
                   (let [pb (doto (ProcessBuilder. (into-array String args)) (.directory (io/file dir)))
                         p (.start pb)
                         o (slurp (.getInputStream p)) e (slurp (.getErrorStream p))]
                     (.waitFor p)
                     (when (and (not (zero? (.exitValue p)))
                                ;; `remote add` on a re-fetch is not a failure.
                                (not (str/includes? (str e) "already exists")))
                       (binding [*out* *err*]
                         (println (str "could not fetch " (:dep x) ":"))
                         (println (str "  " (str/join " " args)))
                         (println (str "  " (str/trim (str o e)))))
                       (System/exit 1))))
                 ;; The stamp goes on LAST, so a fetch that died halfway is not
                 ;; mistaken for one that finished.
                 (spit (io/file dir deps/stamp) (str (:url x) " " (:sha x) "\n"))))
        sh! (clojure.core/fn [args]
              (let [p (.start (ProcessBuilder. (into-array String args)))
                    o (slurp (.getInputStream p)) e (slurp (.getErrorStream p))]
                (.waitFor p) {:exit (.exitValue p) :out (str/trim (str o e))}))
        die! (clojure.core/fn [& lines]
               (binding [*out* *err*] (doseq [l lines] (println l)))
               (System/exit 1))
        ;; ONE download, however the archive is shaped. `:url` may be a LIST --
        ;; a maven coordinate does not say whether the jar is on Clojars or on
        ;; Central -- so every downloader tries them in order and reports all of
        ;; them if none works.
        get! (clojure.core/fn [x to]
               (let [urls (if (vector? (:url x)) (:url x) [(:url x)])
                     hit (some (clojure.core/fn [u]
                                 (println (str "fetching " (:dep x) "  " u))
                                 (when (zero? (:exit (sh! ["curl" "-sSfL" u "-o" to]))) u))
                               urls)]
                 (when-not hit
                   (apply die! (cons (str "could not fetch " (:dep x) ":")
                                     (mapv (clojure.core/fn [u] (str "  " u)) urls))))
                 hit))
        ;; A tarball. npm's unpacks into `package/`, which is why `:unpack` and
        ;; `:dir` are different fields: the archive goes one place and the
        ;; source root is another.
        tgz! (clojure.core/fn [x]
               (let [into-dir (or (:unpack x) (:dir x))
                     tmp (str into-dir "/archive.tgz")]
                 (fs/create-dirs into-dir)
                 (let [u (get! x tmp)
                       r (sh! ["tar" "-xzf" tmp "-C" into-dir])]
                   (when-not (zero? (:exit r))
                     (die! (str "could not unpack " (:dep x) ": " (:out r))))
                   (fs/delete-if-exists tmp)
                   ;; The archive is expected to CONTAIN the content directory
                   ;; -- `package/` for npm. Saying so here beats stamping an
                   ;; empty directory as fetched and failing later as a missing
                   ;; namespace, which names the wrong thing.
                   (when-not (.exists (io/file (:dir x)))
                     (die! (str (:dep x) ": " u " unpacked without " (:dir x))))
                   (spit (io/file (:dir x) deps/stamp) (str u "\n")))))
        ;; A zip. A jar is one, and Clojure source sits at its root -- so the
        ;; extracted directory IS the source root.
        zip! (clojure.core/fn [x]
               (let [into-dir (or (:unpack x) (:dir x))
                     tmp (str into-dir "/archive.zip")]
                 (fs/create-dirs into-dir)
                 (let [u (get! x tmp)
                       r (sh! ["unzip" "-qo" tmp "-d" into-dir])]
                   (when-not (zero? (:exit r))
                     (die! (str "could not unpack " (:dep x) ": " (:out r))))
                   (fs/delete-if-exists tmp)
                   (spit (io/file (:dir x) deps/stamp) (str u "\n")))))
        ;; A bare file: a registry document, or a pod artifact that is one
        ;; executable rather than an archive.
        file! (clojure.core/fn [x]
                (let [dir (:dir x)
                      nm (or (:file x) (:exec x) "artifact")
                      to (str dir "/" nm)]
                  (fs/create-dirs dir)
                  (let [u (get! x to)]
                    (when (:exec x)
                      (fs/set-posix-file-permissions to "rwxr-xr-x"))
                    (spit (io/file dir deps/stamp) (str u "\n")))))
        ;; A FETCHED POD BECOMES A LOCAL ONE. The artifact was chosen where the
        ;; plan was made -- once, with the platform in hand -- so what is
        ;; written here is the same `manifest.edn` a `:pod/path` pod has, and
        ;; the boot side never learns the difference.
        pod-manifest! (clojure.core/fn [x]
                        (when (and (= (:kind x) :pod) (:exec x))
                          (let [exe (io/file (:dir x) (:exec x))]
                            (when (.exists exe)
                              (fs/set-posix-file-permissions (str exe) "rwxr-xr-x"))
                            (spit (io/file (:dir x) "manifest.edn")
                                  (str "{:pod/name " (:dep x) "\n"
                                       " :pod/artifacts [{:artifact/executable "
                                       (pr-str (:exec x)) "}]}\n")))))
        fetch1! (clojure.core/fn [x]
                  ;; DISPATCH ON `:via`, NOT ON `:kind`. How a thing is
                  ;; downloaded and which ecosystem it belongs to are different
                  ;; questions, and the copy of the KINDS that used to live here
                  ;; is what broke the last time they moved.
                  (let [v (:via x)]
                    (cond
                      (= v :none)
                      (die! (str "no such " (if (= (:kind x) :pod) ":pod/path" ":local/root")
                                 " for " (:dep x) ": " (:dir x)))
                      (= v :git) (git! x)
                      (= v :tgz) (tgz! x)
                      (= v :zip) (zip! x)
                      (= v :file) (file! x)
                      :else (die! (str "no downloader for " (:dep x)
                                       " (:via " (pr-str v) ")")))
                    (pod-manifest! x)))
        r (loop [argv (vec *command-line-args*) guard 0]
            (let [r (cli/run argv slurp* opts)]
              (if-let [f (:fetch r)]
                (do
                  (when (> guard 32)
                    (die! "dependency resolution did not settle"))
                  ;; `fetch-plan` is transitive and a fetched dep can name more,
                  ;; so this is a fixpoint the host drives: fetch what is
                  ;; pending, then ask again. The ROUND runs concurrently --
                  ;; independent downloads have no reason to be serial, and the
                  ;; batch is a batch precisely because nothing in it waits on
                  ;; anything else in it.
                  (doseq [t (mapv (clojure.core/fn [x] (future (fetch1! x))) f)] @t)
                  (recur (vec (:then r)) (inc guard)))
                r)))]
    (when-let [b (:build r)]
      ;; `flint build` is `flint :src ... :fn ...` with the project filled in --
      ;; the roots come from `deps.edn`, so a build inside a project needs no
      ;; `:src`. Re-entering this same script rather than reaching into the
      ;; compiler keeps one code path for compiling.
      (let [srcs (mapcat (clojure.core/fn [p] [":src" (str (fs/absolutize p))]) (:paths b))
            run! (clojure.core/fn [args]
                   (let [p (.start (ProcessBuilder. (into-array String args)))]
                     (.start (Thread. (clojure.core/fn [] (io/copy (.getInputStream p) System/out))))
                     (.start (Thread. (clojure.core/fn [] (io/copy (.getErrorStream p) System/err))))
                     (.waitFor p)))
            ;; THE TARGET HAS TO TRAVEL. `cli/lib/flint/cli.cljc` put `:target` in
            ;; the build request and this re-entry dropped it, so
            ;; `flint build :target clr` emitted a wasm module while the CLI that
            ;; asked believed otherwise -- invisible for as long as `clr` was
            ;; refused upstream, and a silent wrong artifact the moment it was
            ;; not. "wasm" is the default and needs no flag.
            code (run! (concat [(str root "/bin/flint") ":fn" (:entry b) ":out" (:out b)]
                               srcs
                               (when (and (:target b) (not= "wasm" (:target b)))
                                 [":to" (:target b)])
                               (when (:image? b) ["--emit-image"])))]
        ;; An image needs a loader to be instantiated into, so building one
        ;; builds the other. One loader serves every image (0023), so it is
        ;; built once and left alone after that.
        (when (and (zero? code) (:loader b) (not (fs/exists? (:loader b))))
          (run! (concat [(str root "/bin/flint") ":fn" (:entry b)
                         ":out" (:loader b) "--loader"]
                        srcs)))
        (System/exit code)))
    (if-let [x (:exec r)]
      ;; A task is a program: write it, compile it, run it. Two processes rather
      ;; than one because `flint_load_image` clears the caller's frames -- a
      ;; guest cannot run a task from inside itself (see `cli/lib/flint/cli.cljc`).
      (let [dir (str (fs/create-temp-dir))
            wasm (str dir "/task.wasm")]
        (fs/create-dirs (str dir "/flint"))
        (spit (str dir "/flint/task.cljc") (:src x))
        (let [;; The project's own `:paths` come along, so a task can require a
              ;; namespace from the project it is a task of. Absolute, because
              ;; the compile runs with its own working directory.
              roots (cons dir (mapv (clojure.core/fn [p] (str (fs/absolutize p))) (:paths x)))
              args (concat [(str root "/bin/flint") ":fn" (:entry x) ":out" wasm]
                           (mapcat (clojure.core/fn [p] [":src" p]) roots))
              b (.start (ProcessBuilder. (into-array String args)))
              ;; Drained BEFORE `waitFor`: a compile that says a lot would fill
              ;; the pipe and deadlock a wait that has not read it.
              bout (slurp (.getInputStream b))
              berr (slurp (.getErrorStream b))]
          (.waitFor b)
          (when-not (zero? (.exitValue b))
            (binding [*out* *err*]
              (println "the task did not compile:")
              (println bout) (println berr)
              (println "---- the program flint built for it ----")
              (println (:src x)))
            (System/exit 1))
          (let [p (.start (ProcessBuilder.
                           (into-array String (concat [(str root "/bin/flint") "run" wasm
                                                       (str (:entry x))]
                                                      (map str (:args x))))))]
            (.start (Thread. (clojure.core/fn [] (io/copy (.getInputStream p) System/out))))
            (.start (Thread. (clojure.core/fn [] (io/copy (.getErrorStream p) System/err))))
            (System/exit (.waitFor p)))))
      (do (when (seq (:out r)) (println (:out r)))
          (System/exit (or (:code r) 0))))))

;; `flint test` -- run every `^:flint.check/test` var on the source path.
;;
;; It is a MODE rather than a command with its own pipeline: the same compile,
;; with two differences. Every namespace under `:src` is a root, because a test
;; nobody requires is still a test; and the entry is the generated registry,
;; which is why the entry namespace cannot be resolved the ordinary way -- it
;; does not exist until the compile has read everything else.
(def test-mode? (= "test" (first *command-line-args*)))

(defn run-tests-module!
  "Run a compiled test module and exit with its verdict.

  `flint test` compiles and RUNS: a test command that leaves you an artifact to
  run yourself is a build command wearing the wrong name."
  [wasm]
  (let [p (.start (ProcessBuilder.
                   (into-array String ["node" (str root "/host/flint.mjs") wasm
                                       "flint.check.registry/run"])))
        out (slurp (.getInputStream p))
        err (slurp (.getErrorStream p))]
    (.waitFor p)
    (print out)
    (when (seq err) (binding [*out* *err*] (print err)))
    (flush)
    (System/exit (if (or (re-find #"FAILED" (str out)) (not (zero? (.exitValue p)))) 1 0))))

(let [{:keys [src fn out stats keep-names explain disasm const self exclude exports wasm-path aot optimize loader emit-image emit-spec features to]}
      (parse-args (if test-mode? (rest *command-line-args*) *command-line-args*))
      fn (if test-mode? 'flint.check.registry/run fn)]
  ;; AN UNRECOGNISED `:to` IS REFUSED, and this file used to fall through to the
  ;; wasm path with it. `bin/flint ... :to :llvm` therefore wrote a 494 KB WASM
  ;; MODULE into `p.ll` and said "wrote p.ll" -- no error, the wrong artifact,
  ;; and the only tell was `\0asm` where `; flint program, as LLVM IR` should
  ;; have been. A typo did the same: `:to :wsam` compiled and shipped wasm.
  ;;
  ;; `:llvm` IS IN THIS LIST NOW. It was not, and the comment here said "this
  ;; door does not emit IR: the native CLI does" -- which described the DISPATCH
  ;; and read as a property of the door. `src/flint/compiler/llvm.cljc` is portable cljc
  ;; and this file runs the compiler's own source, so the emitter was always in
  ;; reach; the npm CLI had the same refusal for the same non-reason and was
  ;; wired up on 2026-09-26. Three doors, one emitter, byte-identical output
  ;; (`DECISIONS.md#compiles-are-byte-reproducible`).
  (when (and to (not (contains? #{"wasm" "clr" "jvm" "llvm"} to)))
    (binding [*out* *err*]
      (println (str "no such target `:to :" to "` on this door. `bin/flint` emits"
                    " :wasm (the default), :clr, :jvm and :llvm.")))
    (System/exit 1))
  (when-not fn
    ;; `:to` BELONGS IN THE USAGE. This door emits four targets and the help
    ;; named none of them, while an unrecognised `:to` above prints the set --
    ;; so the only way to learn the option existed was to guess it wrong.
    (println "usage: flint :src <dir> :fn <ns/fn> [:out <file>]")
    (println "                 [:to wasm|clr|jvm|llvm]   default wasm")
    (println "       flint test :src <dir>        run every ^:flint.check/test var")
    (println "                 [:exclude [ns ...]]   assert these are unreachable")
    (println "                 [:wasm-path <dir> ...] search path for precompiled units")
    (println "                 [--self] [--stats] [--keep-names]")
    (System/exit 2))
  (try
   (let [;; `units/` is the LAST entry on the unit search path, not a special
        ;; case: a :wasm-path directory shadows a built-in of the same name, and
        ;; every compile exercises the same mechanism a user unit uses.
        unit-path (concat (or wasm-path []) [(str root "/units")])
        ;; Source resolution: your :src first, then source shipped beside a unit
        ;; (a unit is a namespace's native half; the .cljc beside it is its
        ;; Clojure half), then the guest source flint ships -- the standard
        ;; library's two roots, `flint.deps`'s and `flint.cli`'s, the four
        ;; units the two CLIs embed (`DECISIONS.md#four-units`). Stated in the
        ;; README.
        dirs (concat (or src []) unit-path shipped-roots)
        entry-ns (if test-mode? 'clojure.core (symbol (namespace fn)))
        ;; `:optimize [perf]` REMOVES `:flint/check`.
        ;;
        ;; Not "compiles the checks away" -- removes the reader branch, so what
        ;; is inside it is never read, never analysed and never in the image.
        ;; That is the bargain the whole system rests on: checks are on by
        ;; default because a check nobody turns on is a check nobody has, and
        ;; they cost exactly nothing in the build that ships.
        ;;
        ;; It has to happen HERE, before the sources are collected: the
        ;; collection reads every file, and a feature decided afterwards would
        ;; leave the branches already read in.
        features (cond-> (or features reader/default-features)
                   (some #{"perf" ":perf"} (or optimize [])) (disj :flint/check))
        ;; In test mode every namespace under `:src` is a root: a test that
        ;; nothing requires is still a test, and collecting from one entry
        ;; outwards would silently run a subset. The entry itself is generated
        ;; and so cannot be a root -- it does not exist yet.
        ;; `clojure.core/fn`, spelled out: this `let` destructures `:keys [.. fn ..]`,
        ;; so a bare `fn` here is the ENTRY NAME, a string, and calling it
        ;; reports `Could not resolve symbol: p` from inside a lambda that was
        ;; never a lambda. Same shape as the note in `aot.cljc` about not
        ;; destructuring `:op` as `op`.
        src-nses (when test-mode?
                   ;; EVERY dialect, not just the portable one. A test that
                   ;; lives in a `.fln` is still a test, and a namespace that is
                   ;; never collected is one a qualified reference cannot
                   ;; resolve -- which surfaces as "is zzz required?" and sends
                   ;; the reader to the `ns` form rather than to the file list.
                   (->> (mapcat (clojure.core/fn [d]
                                  (fs/glob d (str "**.{"
                                                  (str/join "," (map (clojure.core/fn [e] (subs e 1))
                                                                     project/source-extensions))
                                                  "}")))
                                (or src []))
                        (map (clojure.core/fn [pth] (ns-of pth)))
                        (remove nil?)
                        vec))
        ;; `roots*`, OVERRIDING `flint.compiler.resolve/project-roots`'s default only in
        ;; test mode: ordinary compiles pass `nil` and get `[clojure.core
        ;; entry-ns]`, which is the same set `(cons entry-ns ['clojure.core])`
        ;; named here before. `flint.port`, `flint.wire` and `flint.check` are
        ;; added by `project-roots` itself now, UNCONDITIONALLY -- ONE list,
        ;; read rather than restated (AGENTS.md sec. 1). `flint.check` always
        ;; exists (`DECISIONS.md#checks`); `features` only selects which of
        ;; its two internal variants its own `#?(:flint/check ...)` resolves
        ;; to, not whether it is a root at all.
        roots* (when test-mode? (vec (cons 'clojure.core src-nses)))
        ;; `resolve-sources!` is `collect` + `refuse-guarded-requires!` +
        ;; `core-first (topo-order ..)`, all three now
        ;; `flint.compiler.resolve/resolve-project-waves`'s job, over the filesystem
        ;; resolver above. Any resolution error exits 1 from inside it.
        {:keys [sources order]} (resolve-sources! dirs entry-ns features roots*)
        out (or out "out.wasm")
        t0 (System/nanoTime)
        units (link/discover-units unit-path)
        ;; Before the compile, not after it: a stale unit should cost you 10ms.
        _ (link/check-abi! units)
        ;; A unit is a namespace's NATIVE half and the .cljc its Clojure half:
        ;; they compose rather than compete, so "source or unit?" is not the
        ;; question. What can genuinely conflict is two of the same kind, and
        ;; both resolve the same way -- earlier on the path wins.
        ;; SAME REASONING ONE STEP FURTHER. A `.fln` and a `.cljc` for one
        ;; namespace are its flint half and its portable half -- different
        ;; DIALECTS, chosen by the runtime doing the loading, exactly as
        ;; `foo.clj` beside `foo.cljc` is ordinary in Clojure and unremarked.
        ;; They compose. Only two files of the SAME extension, on different
        ;; path entries, are one hiding the other.
        _ (doseq [n (sort (keys sources))]
            (let [cs (source-candidates dirs n)
                  ext-of (clojure.core/fn [f]
                           (first (filter (clojure.core/fn [e] (str/ends-with? f e))
                                          project/source-extensions)))]
              (doseq [[e group] (sort-by key (group-by ext-of cs))]
                (when (next group)
                  (println (str "note: " n " has " (count group) " " e " sources; using "
                                (first group) " (shadowing "
                                (str/join ", " (rest group)) ")"))))))
        _ (doseq [u (link/shadowed-units unit-path)]
            (println (str "note: unit " (:name u) " at " (:manifest u)
                          " is shadowed by " (:by u))))
        ;; Grouped by file, and capped, because a library can have dozens and
        ;; the point is to be told, not to be buried.
        ;; Flint's OWN sources are excluded, and that is what keeps the note
        ;; worth reading. `clojure.core` carries `#?(:flint/check ...)` in
        ;; every hardened function, so any build with a non-default feature set
        ;; elides several of them BY DESIGN -- and a warning that fires on
        ;; correct, intended behaviour is one a reader learns to skip past,
        ;; which costs them the one time it is about their own code.
        ;;
        ;; The note exists to say "a form you wrote silently vanished". A form
        ;; flint wrote, vanishing exactly as flint intended, is not that.
        _ (let [mine? (clojure.core/fn [f]
                        (not (or (some (clojure.core/fn [d] (str/starts-with? (str f) (str d "/")))
                                       shipped-roots)
                                 (str/starts-with? (str f) (str root "/units")))))
                by-file (group-by :file (filter (comp mine? :file) @elisions))]
            (when (seq by-file)
              (println (str "note: " (reduce + (map (comp count val) by-file))
                            " reader conditional(s) in "
                            (count by-file) " file(s) matched none of "
                            (str/join ", " (sort (map str features)))
                            " -- the form each stood in was DELETED"))
              (doseq [[f xs] (take 6 (sort-by key by-file))]
                (println (str "  " f " (" (count xs) ") lines "
                              (str/join ", " (take 8 (map :line xs)))
                              "  offering " (str/join "/" (sort (distinct (map str (mapcat :offered xs))))))))
              (when (> (count by-file) 6)
                (println (str "  ... and " (- (count by-file) 6) " more file(s)")))
              (println "  a :flint or :default branch, or :features, decides which one is taken")))
        ;; The output directory is CREATED, not assumed. `flint build` puts its
        ;; artifact under `out/` by default and a fresh project has no `out/`;
        ;; what came back was `rust-lld: cannot open output file`, which names
        ;; the linker for something that is not the linker's doing.
        _ (when-let [d (fs/parent (fs/absolutize out))] (fs/create-dirs d))
        ;; THE SPEC CARRIES NO FORMS. It is printed for `--emit-spec` and sent
        ;; to the self-hosted compiler as EDN, which are comparisons between
        ;; doors that send source text; only this process's own compile below
        ;; is handed the forms `resolve-sources!` read.
        ;;
        ;; `sources` DOES STILL CARRY `:src`: `resolve-ns` keeps the text
        ;; it already read alongside `:preread`, unused on the ordinary
        ;; compile path but load-bearing here -- `bin/check-sdk`'s reference
        ;; artifact, built by handing THIS spec to `flintc.wasm`
        ;; (`host/flint-argv.mjs`), failed with "no source for namespace
        ;; clojure.core" the one time `:src` was missing:
        ;; `flint.compiler.selfhost/build-image` skips resolution for an
        ;; already-resolved spec like this one, and `flint.compiler.core/read-source`'s
        ;; fallback for a namespace with no `:forms` is `:src`, not nothing.
        spec {:sources (into {} (map (clojure.core/fn [[n s]] [n (dissoc s :forms)]) sources))
              :order order :entry fn
              :exports (vec (distinct exports))
              :features features
              :exclude exclude
              :excluded-builtins (select-keys (builtins-by-unit unit-path) (vec (or exclude [])))
              :builtins (builtin-names unit-path)}
        _ (when emit-spec
            (spit out (pr-str spec))
            (println "wrote" out (str "(" (count (pr-str spec)) " bytes, spec only)"))
            (System/exit 0))
        selfres (when self (compile-with-flint spec))
        compiled (when-not self (compiler/compile-image (assoc spec :sources sources)))
        _ (when (and explain (not self))
            (println "explain" explain)
            (println "  in roots:" (contains? (:roots compiled) explain))
            (println "  kept:" (boolean (some #{explain} (:kept-syms compiled))))
            (println "  items with that sym:"
                     (count (filter #(= explain (:sym %)) (:items compiled))))
            (doseq [it (filter #(= explain (:sym %)) (:items compiled))]
              (println "    ns" (:ns it) "kind" (:kind it) "macro?" (:macro? it))))
        _ (when (and disasm (not self))
            (require '[flint.compiler.disasm :as dis])
            (let [b (:builder compiled)
                  st @b]
              (doseq [[i f] (map-indexed vector (:fns st))
                      :let [nm (get-in st [:consts (:name f) 1])]
                      :when (or (= nm disasm) (= (str i) disasm))]
                (println "fn" i nm)
                (doseq [a (:arities f)]
                  (println " arity argc" (:argc a) "variadic" (:variadic? a) "nlocals" (:nlocals a))
                  (println ((resolve 'flint.compiler.disasm/disasm) (:code st) (:off a) (:len a)))))))
        _ (when const
            (let [b @(:builder compiled)]
              (doseq [i (range (max 0 (- const 3)) (min (count (:consts b)) (+ const 4)))]
                (println "const" i (pr-str (nth (:consts b) i))))))
        ;; The ordered preference, resolved to the one boolean an artifact can
        ;; carry. The FIRST token this build understands decides; unrecognised
        ;; ones are stepped over rather than refused, which is what lets a list
        ;; written against a newer flint still get this one's best effort.
        ;; With no recognised token at all, `--aot` and FLINT_AOT still answer.
        ;; `(some known ...)` would be wrong here: `some` returns the first
        ;; TRUTHY result, so a `size` token -- whose answer is `false` -- would
        ;; be skipped and the default taken instead. Find the first RECOGNISED
        ;; token, then read its answer.
        perf? (let [known {"perf" true ":perf" true "size" false ":size" false}
                    tok (first (filter known (or optimize [])))]
                (if tok
                  (known tok)
                  (boolean (or aot (= "1" (System/getenv "FLINT_AOT"))))))
        builder (when compiled (:builder compiled))
        ;; Recorded in the IMAGE, not only acted on here. On wasm this also
        ;; selects a different runtime binary and emits compiled arities; on the
        ;; JVM and the CLR the arities are emitted at load time, so the image
        ;; has to carry the request for those ports to honour it.
        _ (when builder (img/set-perf! builder perf?))
        stats* (when compiled (:stats compiled))
        t1 (System/nanoTime)
        needed (if self (:natives selfres) (img/natives builder))
        ;; A loader module runs images it has never seen, so it has to carry
        ;; every builtin one of them might import. That is the whole trade: a
        ;; bigger module, and no linker at run time (DECISIONS.md#construe-integration-bar).
        needed (if loader (vec (sort (builtin-names unit-path))) needed)
        _ (when emit-image
            ;; Slots are left at zero on purpose: they belong to whichever module
            ;; links the builtins, and a loader re-resolves every one of them by
            ;; NAME. Writing a slot here would be writing a number that is only
            ;; meaningful somewhere this file has never been.
            (let [bytes (if self (:image selfres) (img/emit builder {}))]
              (with-open [o (java.io.FileOutputStream. (str out))]
                (.write o (byte-array (map unchecked-byte bytes))))
              (println "wrote" out (str "(" (count bytes) " bytes, image only)"))
              (System/exit 0)))
        ;; `:to :jvm` -- HERE FOR `:to :clr`'s REASONS, and the artifact is ONE
        ;; CLASS FILE. Not a jar: the class carries the program and references the
        ;; runtime, which the host supplies through `link`, exactly as the CLR
        ;; assembly references `Flint.dll`. A jar that also carried the
        ;; interpreter would be the one thing `one-image-per-sandbox` says an
        ;; artifact is not.
        ;;
        ;; `bin/build-jvm-artifact` is the spike this replaces and says so in its
        ;; own header. It stays: it can write the convenience jar and report the
        ;; expansion factor, neither of which belongs on this path.
        _ (when (= to "jvm")
            (let [image (if self (:image selfres) (img/emit builder {}))
                  ;; NO `modmeta/describe` HERE, and that is NOT what the `:to :clr`
                  ;; branch below does. The two emitters own their metadata at
                  ;; OPPOSITE ends: `clr/assemble` takes the describe map verbatim
                  ;; from its caller ("ONE PRODUCER", its comment says), while
                  ;; `jvm/emit` calls its own `jvm/describe` -- which exists
                  ;; because the JVM's `:abi` is a version map and not wasm's
                  ;; linear-memory key, and the two must not collide.
                  ;;
                  ;; Mirroring the clr branch literally therefore DOUBLE-DESCRIBED
                  ;; it: the class came out with a `:compat {:abi {:image 3 ..}}`
                  ;; wrapping a whole second describe map as a STRING under
                  ;; `:meta`, each with a different `:features` key meaning
                  ;; something different. `:meta` here is the user payload, which
                  ;; is what `bin/build-jvm-artifact` already passes.
                  ;; `:out` MAY BE A FILE NOW, and that is the ordinary case:
                  ;; `:out build/Prog.class` emits a class that CALLS ITSELF
                  ;; `Prog`, so the path and the name agree by construction and a
                  ;; JVM will load it. It used to refuse this, because the name was
                  ;; the constant `flint.Artifact` and a JVM loads a class only
                  ;; from a path matching its own name -- so `:out` had to be the
                  ;; classpath ROOT that `flint/Artifact.class` hangs off. The name
                  ;; is a parameter now (`jvm/artifact-class-name`).
                  ;;
                  ;; A DIRECTORY STILL WORKS and still writes
                  ;; `<root>/flint/Artifact.class`, because that is what every
                  ;; existing consumer expects -- `Main.java` and `FourOps.java`
                  ;; look up `flint.Artifact` unless told another name.
                  dotclass? (str/ends-with? (str out) ".class")
                  ;; The internal name is the basename without `.class`, in the
                  ;; DEFAULT package: one file, one name, no directory implied.
                  ;; A FILENAME IS NOT A CLASS NAME. `:out my-prog.class` would
                  ;; emit a class called `my-prog`, which a JVM accepts -- class
                  ;; file naming is laxer than the Java language's -- and which NO
                  ;; JAVA SOURCE CAN REFERENCE. Refused rather than written,
                  ;; because the artifact looks fine until somebody writes code
                  ;; against it. The native door refuses the same spelling.
                  cname (if dotclass?
                          (let [b (.getName (java.io.File. ^String (str out)))
                                nm (subs b 0 (- (count b) 6))]
                            (when-not (and (seq nm)
                                           (or (Character/isLetter (first nm))
                                               (#{\_ \$} (first nm)))
                                           (every? #(or (Character/isLetterOrDigit %)
                                                        (#{\_ \$} %))
                                                   (rest nm)))
                              (binding [*out* *err*]
                                (println (str "`" out "` is not a usable class name: `" nm
                                              "` would emit a class a JVM loads but no Java"
                                              " source can name. A class name starts with a"
                                              " letter, `_` or `$` and continues with those or"
                                              " digits. Rename the file, or pass `:out` as a"
                                              " CLASSPATH ROOT (a directory) for the default"
                                              " `flint.Artifact`.")))
                              (System/exit 1))
                            nm)
                          jvm/ARTIFACT-CLASS)
                  klass (jvm/emit (byte-array (map unchecked-byte image))
                                  {:version (or (System/getenv "FLINT_VERSION") "0.1.0")
                                   :meta {}
                                   :builtins (count needed)
                                   :class cname})
                  target (if dotclass? (str out) (str out "/" jvm/ARTIFACT-CLASS ".class"))
                  _ (.mkdirs (.getParentFile (java.io.File. ^String target)))]
              (with-open [o (java.io.FileOutputStream. ^String target)]
                (.write o ^bytes klass))
              (println "wrote" target (str "(" (alength ^bytes klass) " bytes, "
                                           (count image) " of it bytecode)"))
              (System/exit 0)))
        ;; `:to :llvm` -- BESIDE `:to :jvm` and `:to :clr` for their reason: IR is
        ;; not a link either. `llvm/emit-module` takes the image, the compiled
        ;; arities' IR and their names, and answers TEXT -- the one target here
        ;; that is not bytes.
        ;;
        ;; ARITIES BEFORE THE IMAGE, which is not an ordering this door gets to
        ;; choose: `llvm/compile-arities` writes each arity's slot INTO the
        ;; builder, so an image emitted first carries none of them.
        ;; `flint.compiler.selfhost/compile-to-llvm` says the same thing at the same place,
        ;; and the two are meant to stay identical (AGENTS.md sec. 1) -- verified
        ;; byte-for-byte against the native CLI and the npm CLI by
        ;; `bb test/selfhost-targets.clj`.
        _ (when (= to "llvm")
            (require '[flint.compiler.llvm :as llvm])
            (let [res (when perf? ((resolve 'flint.compiler.llvm/compile-arities) builder))
                  image (if self (:image selfres) (img/emit builder {}))
                  ir ((resolve 'flint.compiler.llvm/emit-module)
                      image (or (:ir res) "") (or (:names res) []))]
              (spit (str out) ir)
              (println "wrote" out (str "(" (count ir) " bytes"
                                        (when res (str ", " (:compiled res) " of "
                                                       (:total res) " arities compiled"))
                                        ")"))
              (System/exit 0)))
        ;; `:to :clr` -- BESIDE `--emit-image` and before `link/compose`, because
        ;; a CLR assembly is not a link. The bytecode goes in as a `FieldRva`
        ;; static array and flint's runtime is an assembly REFERENCE, so there is
        ;; nothing for `rust-lld` to do and calling it would produce a wasm module
        ;; nobody asked for.
        _ (when (= to "clr")
            (let [image (if self (:image selfres) (img/emit builder {}))
                  ;; NO `modmeta/describe` HERE ANY MORE. `clr/assemble` builds it
                  ;; from these inputs, because `:abi :clr` and the three exports
                  ;; are properties of the TARGET and this door was one of two
                  ;; places restating them (`DECISIONS.md#four-operations`, "the
                  ;; emitter owns describe"). What stays here is what only this
                  ;; door knows: the version, the builtin count and the features.
                  dll (clr/assemble {:name (clr-name out)
                                     :image (flint.rt/vec->b (vec image))
                                     :describe true
                                     :version (or (System/getenv "FLINT_VERSION") "0.1.0")
                                     :builtins (count needed)
                                     ;; NO `:features`. `describe`'s `:features` is
                                     ;; the five BUILD flags, derived from what the
                                     ;; artifact exports -- `{:aot .. :snapshots ..}`
                                     ;; -- and `features` here is the READER feature
                                     ;; SET from `:features` on the command line,
                                     ;; `#{:flint :flint/check :flint/nested}`. One
                                     ;; slot, two meanings: this door put a set of
                                     ;; reader keywords where a map of export probes
                                     ;; belongs, and `wasm.cljc` says why that is the
                                     ;; wrong direction -- "a descriptor that reports
                                     ;; the build flags rather than the module is the
                                     ;; kind that goes quietly wrong".
                                     ;;
                                     ;; The native CLI had the same bug through
                                     ;; `selfhost`, fixed 2026-09-26; this is the
                                     ;; other door, and it is the one whose artifacts
                                     ;; load, which is why nobody read the field.
                                     })
                  ;; `:bytes`, NOT `dll`. `assemble` answers a map -- the bytes
                  ;; and the per-method assembly facts -- and both new callers
                  ;; of it assumed the byte string it used to return. The symptom
                  ;; was `MapEntry cannot be cast to Character` from inside
                  ;; `flint.rt/b->vec`, four frames from anything named.
                  bytes (flint.rt/b->vec (:bytes dll))]
              (with-open [o (java.io.FileOutputStream. (str out))]
                (.write o (byte-array (map unchecked-byte bytes))))
              (println "wrote" out (str "(" (count bytes) " bytes, "
                                        (count image) " of it bytecode)"))
              (System/exit 0)))
        result (link/compose {:unit-path unit-path
                              :sysroot (str root "/units/.sysroot")
                              :needed-builtins needed
                              :emit-image (clojure.core/fn [slots]
                                            (if self
                                              (img/patch-native-slots (:image selfres) needed slots)
                                              (img/emit builder slots)))
                              :out out
                              :builder builder
                              :aot? (and perf? (not self))
                              :loader? loader
                              :entry-sym fn
                              :flint-version (or (System/getenv "FLINT_VERSION") "0.1.0")
                              :keep-names keep-names})
        t2 (System/nanoTime)]
    (when stats
      (println (format "units linked %s"
                       (str/join ", " (map (clojure.core/fn [u] (str (:name u) " <- " (:manifest u)))
                                           (sort-by (comp str :name) (:units result))))))
      (when (seq exclude)
        (println (format "excluded (asserted unreachable) %s" (str/join ", " (sort exclude)))))
      (println (format "%s %.0fms  link %.0fms  module %d bytes  image %d bytes  %sbuiltins %d"
                       (if self "compile (on flint)" "compile (on bb)")
                       (/ (- t1 t0) 1e6) (/ (- t2 t1) 1e6)
                       (:bytes result) (:image-bytes result)
                       (if stats* (format "vars %d/%d  " (:items-kept stats*) (:items-total stats*)) "")
                       (:builtins result))))
    (if test-mode?
      (run-tests-module! out)
      (println "wrote" out (str "(" (:bytes result) " bytes)"))))
   (catch clojure.lang.ExceptionInfo e (die! e))))

;; Every branch above calls `System/exit` -- the `run`/`inspect`/`check`/project
;; and compile commands all do -- so this is reached only if NONE of them
;; matched (an empty or unrecognised invocation reaching neither the usage
;; message nor a command). `clojure -M -m flint.driver.main` calls `-main`
;; after loading this namespace's top-level forms, and with none defined that
;; is a `NullPointerException` even though the real work above already ran and
;; already exited -- this exists only to make that unreachable-in-practice tail
;; harmless rather than a scary stack trace after a successful compile.
(defn -main [& _] (System/exit 0))
