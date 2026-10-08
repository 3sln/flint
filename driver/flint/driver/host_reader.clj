(ns flint.driver.host-reader
  "The JVM driver's own copy of 'a reader', over the kin-generated Java reader
  rather than `flint.compiler.reader` (`DECISIONS.md#namespaces-over-the-system-port`,
  migration step 1.2). `bin/flint` used to read every source with
  `src/flint/compiler/reader.cljc`, which is the GUEST'S reader compiled to run under
  plain Clojure; this calls the SAME generated Java the JVM runtime embeds
  (`runtimes/jvm/src/com/_3sln/flint/kgen/rt/Formsenc.java`, entry
  `readForms`), through reflection, so there is no second reader
  implementation here for AGENTS.md sec. 1 to drift from the generated one.

  Reflection, not `:import`, because the classes this calls do not exist until
  `ensure-classes!` compiles them -- on first use, into `target/kin-reader-classes`,
  which a `(ns ...)` form's compile-time `:import` cannot wait for.
  `clojure.lang.Reflector` is what Clojure's own compiler uses for a reflective
  call, so this is the same dispatch an `:import`ed call would get, aimed at a
  class discovered at run time instead of compile time.

  Every read answers `flint.compiler.forms` bytes under `:features :any` (a DEFERRED
  read, `DECISIONS.md#stdlib-preread`): this driver never decides a compile's
  feature set before it has read the `ns` form, so every file is read once,
  deferred, and `flint.compiler.resolve/read-entry` resolves the conditionals for
  whichever compile reaches it -- pure data work on the decoded forms, nothing
  here reads text twice."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [flint.compiler.forms :as forms]))

;; THE REPO ROOT, from `FLINT_ROOT` -- NOT the current directory, and NOT
;; `*file*` either. `*file*` is relative to whichever classpath entry this
;; namespace loaded from (`driver/`), not to the repo, so counting path
;; segments back up from it is exactly the kind of thing that silently
;; breaks when a file moves. The current directory is worse: `bin/flint`
;; deliberately does NOT `cd` to the repo root any more (`flint.driver.main`'s
;; own `root` says why -- `flint task`/`flint build` need the CALLER's
;; working directory left alone), so this process's cwd is the caller's
;; project, not this repo. `bin/flint` sets `FLINT_ROOT` from a subshell that
;; never touches its own cwd to get it.
(def root (or (System/getenv "FLINT_ROOT") (.getCanonicalPath (io/file "."))))

(def classes-dir (io/file root "target" "kin-reader-classes"))

(def ^:private java-globs
  ["runtimes/jvm/src/com/flint/*.java"
   "runtimes/jvm/src/com/flint/rt/*.java"
   "runtimes/jvm/src/com/_3sln/flint/kgen/rt/*.java"])

(defn- java-sources []
  (mapcat (fn [g]
            (let [[dir pat] (let [parts (str/split g #"/")]
                              [(str/join "/" (butlast parts)) (last parts)])
                  d (io/file root dir)]
              (when (.isDirectory d)
                (filter (fn [f] (str/ends-with? (.getName f) ".java")) (.listFiles d)))))
          java-globs))

(defn- newest-mtime [files]
  (reduce max 0 (map (fn [f] (.lastModified f)) files)))

(defn- oldest-class-mtime []
  (if (.isDirectory classes-dir)
    (let [cs (file-seq classes-dir)]
      (if (seq (filter (fn [f] (.isFile f)) cs))
        (reduce min Long/MAX_VALUE (map (fn [f] (.lastModified f)) (filter (fn [f] (.isFile f)) cs)))
        0))
    0))

(defn ensure-classes!
  "Compile the kin-generated Java reader (and the runtime classes it calls)
  into `target/kin-reader-classes`, if it is missing or any source is newer --
  the same staleness shape `ensure-self-compiler!` used to check for the
  self-hosted compiler's bytecode.

  NOT LOCKED against two `bin/flint` processes racing to compile this on a
  cold `target/`, unlike `bin/build-dist`'s `freshen` (AGENTS.md sec. 7, 'one
  gate at a time'). Two concurrent `javac` runs writing the SAME deterministic
  bytes to the same paths is low-risk in practice -- not torn, since `javac`
  writes each class file in one `write` -- but it is unexamined rather than
  proven safe. The window is only the FIRST build in a fresh worktree; every
  later call sees a fresh `classes-dir` and recompiles nothing."
  []
  (let [srcs (java-sources)
        newest-src (newest-mtime srcs)
        oldest-class (oldest-class-mtime)]
    (when (or (zero? oldest-class) (> newest-src oldest-class))
      (.mkdirs classes-dir)
      (let [args (into-array String (concat ["javac" "-d" (str classes-dir) "-nowarn"]
                                             (map str srcs)))
            p (.start (ProcessBuilder. ^"[Ljava.lang.String;" args))
            out (slurp (.getInputStream p))
            err (slurp (.getErrorStream p))]
        (.waitFor p)
        (when-not (zero? (.exitValue p))
          (binding [*out* *err*]
            (println "could not compile the kin Java reader:")
            (println out) (println err))
          (System/exit 1))))
    nil))

;; MAKE THE CLASSES REACHABLE: compiled after the JVM started, so they are not
;; on the classpath it was launched with. A `URLClassLoader` over
;; `target/kin-reader-classes`, parented on whatever loaded this namespace, so
;; every OTHER class (`com.flint.rt.Rt` referring to its own siblings, for
;; instance) still resolves normally and only the newly-compiled classes need
;; the new entry.
(defonce ^:private loader
  (delay (java.net.URLClassLoader. (into-array java.net.URL [(.toURL (.toURI classes-dir))])
                                   (.getContextClassLoader (Thread/currentThread)))))

(defn- cls ^Class [^String name] (Class/forName name false @loader))

(defn- static-invoke [^String classname ^String method-name & args]
  (clojure.lang.Reflector/invokeStaticMethod (cls classname) method-name (object-array args)))

(defn- instance-invoke [obj ^String method-name & args]
  (clojure.lang.Reflector/invokeInstanceMethod obj method-name (object-array args)))

(defn- new-instance [^String classname & args]
  (clojure.lang.Reflector/invokeConstructor (cls classname) (object-array args)))

(defn- static-field [^String classname ^String field-name]
  (clojure.lang.Reflector/getStaticField (cls classname) field-name))

;; ---------------------------------------------------------------- the runtime
;;
;; ONE `Rt`, reused across every read this process makes: creating it
;; allocates a nursery and a heap, and nothing about a read needs a fresh one.
;; Each read marks before and pops back to the mark after, so no read's
;; garbage outlives it.
(defonce ^:private rt
  (delay (new-instance "com.flint.rt.Rt" (* 4 1024 1024) (* 512 1024 1024))))

(defn- nil-val [] (static-field "com.flint.rt.Val" "NIL"))

(defn- build-symbols-vec
  "A flint vector of flint symbols, one per entry of `syms` (plain Clojure
  symbols). Built on the root stack, a push at a time
  (`kin/mapmake.kin`'s own comment: a value held anywhere else does not
  survive a `conj` that collects)."
  [r syms]
  (let [base (instance-invoke r "mark")
        vi (instance-invoke r "push" (static-invoke "com.flint.rt.Vec" "empty" r))]
    (doseq [s syms]
      (let [sv (static-invoke "com.flint.rt.Str" "symbol" r (namespace s) (name s))
            svi (instance-invoke r "push" sv)
            v2 (static-invoke "com.flint.rt.Vec" "conj" r (instance-invoke r "r" vi) (instance-invoke r "r" svi))]
        (instance-invoke r "setR" vi v2)))
    (let [out (instance-invoke r "r" vi)]
      (instance-invoke r "popTo" base)
      out)))

(defn- build-tags-map
  "The flint value `read-forms` wants for `tags`: NIL for no tags, or an
  array map built from `tag-sym var-sym` pairs flattened in order
  (`flint.rt/array-map`'s own order, which `kin/mapmake.kin`'s `orderedMap`
  matches -- `DECISIONS.md#reader-tags`)."
  [r tags]
  (if (empty? tags)
    (nil-val)
    (let [base (instance-invoke r "mark")
          flat (mapcat (fn [[k v]] [k v]) tags)
          kvs (build-symbols-vec r flat)
          kvsi (instance-invoke r "push" kvs)
          m (static-invoke "com._3sln.flint.kgen.rt.Mapmake" "orderedMap" r (instance-invoke r "r" kvsi))]
      (instance-invoke r "popTo" base)
      m)))

;; THE AUTO-GENSYM COUNTER, PROCESS-WIDE AND NEVER RESET
;; (`DECISIONS.md#namespaces-over-the-system-port`, the open question in
;; migration step 1.1): canonical Clojure's own reader shares one counter
;; (`clojure.lang.RT/nextID`) across every read in the process, so two files'
;; `x#` templates never collide even when one's expansion is spliced into the
;; other's. The kin reader takes its FIRST number as an argument
;; (`gensym0`) rather than owning a counter itself, so this driver is the thing
;; that has to behave like one: start at 1, and after every read, advance past
;; the highest `__auto__` suffix that read actually used -- scanning the
;; decoded forms rather than trusting `readForms` to report a count, because it
;; does not (its contract is bytes or an error, nothing about how many names it
;; spent).
(defonce ^:private gensym-counter (atom 1))

(def ^:private auto-gensym-re #"__(\d+)__auto__$")

(defn- bump-gensym-counter! [forms]
  (let [seen (volatile! 0)
        walk (fn walk [x]
               (cond
                 (symbol? x) (when-let [m (re-find auto-gensym-re (name x))]
                               (vswap! seen max (Long/parseLong (second m))))
                 (map? x) (doseq [[k v] x] (walk k) (walk v))
                 (coll? x) (doseq [e x] (walk e))
                 :else nil))]
    (doseq [f forms] (walk f))
    (when (pos? @seen) (swap! gensym-counter max (inc @seen)))))

(defn deferred-read
  "`text`, read deferred (every reader conditional kept as data, under
  `:features :any`) by the kin Java reader, as `flint.compiler.forms` bytes --
  `flint.compiler.resolve/preread`'s answer, produced by the generated reader instead of
  `flint.compiler.reader` + `flint.compiler.forms/encode`.

  `dialect` is `:flint` or `:portable`, carried EXPLICITLY (never derived from
  the extension down here -- `flint.compiler.resolve/dialect-of` already decided it, and
  repeating that decision is the duplication AGENTS.md sec. 1 warns against).

  Returns the bytes (a `byte[]`, what `flint.rt/b-persistent!` would have
  returned) or throws `ex-info` with `:file`, `:line`, `:column` on a read
  error -- the same shape `flint.compiler.resolve/resolve-wave` answers as `{:error
  ..}`, thrown here because `flint.compiler.resolve/fn-resolver`'s contract is that a
  failed read throws."
  [path text dialect tags]
  (ensure-classes!)
  (let [r @rt
        base (instance-invoke r "mark")
        si (instance-invoke r "push" (static-invoke "com.flint.rt.Str" "of" r text))
        fi (instance-invoke r "push" (static-invoke "com.flint.rt.Str" "of" r (str path)))
        tagsv (build-tags-map r tags)
        tagsi (instance-invoke r "push" tagsv)
        out (static-invoke "com._3sln.flint.kgen.rt.Formsenc" "readForms" r
                            (instance-invoke r "r" si)
                            (instance-invoke r "r" fi)
                            (nil-val)
                            (instance-invoke r "r" tagsi)
                            (boolean (not= dialect :flint))
                            (int @gensym-counter))
        oi (instance-invoke r "push" out)
        result
        (cond
          (static-invoke "com._3sln.flint.kgen.rt.Bytecore" "isBytes" r (instance-invoke r "r" oi))
          {:bytes (static-invoke "com.flint.rt.Bytes" "toArray" r (instance-invoke r "r" oi))}

          (boolean (static-invoke "com.flint.rt.Val" "isNil" out))
          {:error {:message "the reader answered nil"}}

          :else
          (let [msg (static-invoke "com.flint.rt.Str" "text" r
                                    (static-invoke "com._3sln.flint.kgen.rt.Vecread" "vecNth" r
                                                   (instance-invoke r "r" oi) (int 0) (nil-val)))
                line (try (static-invoke "com.flint.rt.Num" "i64Of" r
                                         (static-invoke "com._3sln.flint.kgen.rt.Vecread" "vecNth" r
                                                        (instance-invoke r "r" oi) (int 1) (nil-val)))
                          (catch Exception _ nil))
                col (try (static-invoke "com.flint.rt.Num" "i64Of" r
                                        (static-invoke "com._3sln.flint.kgen.rt.Vecread" "vecNth" r
                                                       (instance-invoke r "r" oi) (int 2) (nil-val)))
                         (catch Exception _ nil))]
            {:error {:message (str msg) :line line :column col}}))]
    (instance-invoke r "popTo" base)
    (if (:bytes result)
      (do (bump-gensym-counter! (:forms (forms/decode (:bytes result))))
          (:bytes result))
      (throw (ex-info (:message (:error result))
                      (merge {:file (str path) :type :reader} (:error result)))))))
