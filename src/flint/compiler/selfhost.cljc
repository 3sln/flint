(ns flint.compiler.selfhost
  "The compiler, as a flint program.

  Compiled by babashka once; from then on flint compiles flint. Input and output
  are strings because that is the whole module ABI: a vector of strings in, a
  string out. The image is binary, so it comes back base64-encoded and the host
  decodes and links it.

  What stays on the host is only what a flint module has no business doing:
  reading files and running `rust-lld`. The compiler itself -- reader, analyzer,
  emitter, macro evaluation -- is all in here."
  (:require [flint.compiler.core :as compiler]
            [flint.compiler.image :as img]
            [flint.compiler.reader :as reader]
            [flint.compiler.resolve :as project]
            [flint.compiler.wasm :as w]
            [flint.compiler.bundle :as bundle]
            [flint.compiler.llvm :as llvm]
            [flint.compiler.clr :as clr]
            [flint.compiler.jvm :as jvm]
            [flint.compiler.modmeta :as modmeta]
            [flint.compiler.wasmshake :as wshake]

            ;; The resolver PORT is flint's alone: babashka drives
            ;; `compile-with` with a resolver of its own and never loads this.
            #?@(:flint [[flint.port :as port]])
            [clojure.string :as str]
            [flint.rt]))

(def ^:private b64-alphabet
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/")

(defn ^:internal base64
  "Bytes to base64 text. Encoded INTO a byte string and decoded once at the
  end: base64 is ASCII, so the output is bytes, and the obvious version --
  accumulating one-character strings and joining -- allocates one string per
  character. A wasm module is three-quarters of a million of them."
  [bytes]
  ;; `flint.compiler.image/emit` still answers with a VECTOR of byte values, so this
  ;; accepts either. Converting once here is cheap; what is not cheap is the
  ;; vector itself, and moving the image builder onto byte strings is worth
  ;; doing separately.
  (let [bytes (if (flint.rt/bytes? bytes) bytes (flint.rt/vec->b bytes))
        alpha (flint.rt/str->b b64-alphabet)
        n (flint.rt/b-count bytes)
        at (fn [i] (flint.rt/b-at alpha i))]
    (flint.rt/b->str
     (flint.rt/b-persistent!
      (loop [i 0 out (flint.rt/b-transient (flint.rt/str->b ""))]
        (if (>= i n)
          out
          (let [b0 (flint.rt/b-at bytes i)
                b1 (if (< (+ i 1) n) (flint.rt/b-at bytes (+ i 1)) 0)
                b2 (if (< (+ i 2) n) (flint.rt/b-at bytes (+ i 2)) 0)
                t (bit-or (bit-shift-left b0 16)
                          (bit-or (bit-shift-left b1 8) b2))
                out (flint.rt/b-conj! out (at (bit-and (bit-shift-right t 18) 63)))
                out (flint.rt/b-conj! out (at (bit-and (bit-shift-right t 12) 63)))
                out (flint.rt/b-conj! out (if (< (+ i 1) n)
                                            (at (bit-and (bit-shift-right t 6) 63))
                                            61))
                out (flint.rt/b-conj! out (if (< (+ i 2) n)
                                            (at (bit-and t 63))
                                            61))]
            (recur (+ i 3) out))))))))

(def ^:private pending-files
  "File bodies handed over ENCODED by a `split` call, merged into the next spec
  read. The native CLI sends them this way rather than inside the EDN: reading
  them as string literals was 57% of a trivial compile's instructions (10.9 M of
  19.1 M, measured 2026-10-01), and an encoded string is decoded natively."
  (atom nil))

(defn- read-spec
  "The spec, read from EDN, with any encoded file bodies merged into `:files`.
  EVERY mode reads its spec through here, so the split path is one mechanism
  and not one variant per target."
  [spec-edn]
  (let [spec (reader/read-one spec-edn)
        files @pending-files]
    (reset! pending-files nil)
    (if files (update spec :files merge files) spec)))

(defn ^:internal compile-to-base64
  "`spec` is EDN: {:sources {ns {:src .. :file ..}} :order [..] :entry ns/fn
  :builtins #{..}}. Returns the base64 image, with native slots left at zero for
  the host to patch (`flint.compiler.image/patch-native-slots`)."
  [spec-edn]
  (let [spec (read-spec spec-edn)
        result (compiler/compile-image spec)
        builder (:builder result)
        bytes (img/emit builder {})]
    {:image (base64 bytes)
     :natives (img/natives builder)
     :stats (:stats result)}))

(def ^:private b64-value
  "Character code to base64 value, for decoding. Indexed by BYTE: a module
  arrives as three-quarters of a megabyte of base64, and a substring per
  character would allocate one string per byte of it."
  (let [bs (flint.rt/str->b b64-alphabet)]
    (loop [i 0 m {}]
      (if (>= i 64) m (recur (inc i) (assoc m (flint.rt/b-at bs i) i))))))

(defn ^:internal base64-decode
  "Base64 text to a byte string. Through a transient, because the input is a
  whole wasm module and appending persistently would copy it on every byte."
  [s]
  (let [bs (flint.rt/str->b s)
        n (flint.rt/b-count bs)
        pad (flint.rt/str->b "=")
        eq (flint.rt/b-at pad 0)]
    (loop [i 0 out (flint.rt/b-transient (flint.rt/str->b ""))]
      (if (>= i n)
        (flint.rt/b-persistent! out)
        (let [c0 (get b64-value (flint.rt/b-at bs i) 0)
              c1 (get b64-value (flint.rt/b-at bs (+ i 1)) 0)
              p2 (= eq (flint.rt/b-at bs (+ i 2)))
              p3 (= eq (flint.rt/b-at bs (+ i 3)))
              c2 (if p2 0 (get b64-value (flint.rt/b-at bs (+ i 2)) 0))
              c3 (if p3 0 (get b64-value (flint.rt/b-at bs (+ i 3)) 0))
              t (bit-or (bit-shift-left c0 18)
                        (bit-or (bit-shift-left c1 12)
                                (bit-or (bit-shift-left c2 6) c3)))
              out (flint.rt/b-conj! out (bit-and (bit-shift-right t 16) 255))
              out (if p2 out (flint.rt/b-conj! out (bit-and (bit-shift-right t 8) 255)))
              out (if p3 out (flint.rt/b-conj! out (bit-and t 255)))]
          (recur (+ i 4) out))))))

(defn- spec-builtins
  "What the compiler may call, for a target with no slot map of its own.

  SLOTS WHEN THERE ARE SLOTS, which is every existing caller and is unchanged.
  `:builtins` only when there are none -- an already-resolved spec, the shape
  `bin/flint --emit-spec` writes, carries `:builtins` and no `:slots`, and three
  artifact targets derived their set from `(keys (:slots spec))` and so got the
  empty set. The symptom was not `!missing` but a builtin reported as not
  available, one step further in.

  `compile-project` set this precedent already: its docstring says it takes
  `:builtins` because its caller has no slot map to take it from. This is the
  same case, and the order matters -- `build-image`'s own docstring warns against
  deriving either from the other, because the two being equal in practice is a
  coincidence and not a rule."
  [spec]
  (if (:slots spec) (set (keys (:slots spec))) (or (:builtins spec) #{})))

(defn- build-image
  "Resolve every namespace a program requires, and compile them to an image
  builder.

  The half of `compile-to-wasm` that has nothing to do with wasm, which is why
  it is here rather than there: `compile-to-llvm` needs exactly this and
  nothing else of it, and a second copy of the resolver wiring is a second
  place for a reader tag or a workspace grant to go missing (AGENTS.md §1).

  `builtins` is a PARAMETER rather than derived here, and that is not
  indecision: the wasm and LLVM targets take it from the keys of `:slots`,
  because a target with a slot map has already been told what the runtime
  carries, and `compile-project` takes `:builtins` because its caller has no
  slot map to take it from. Both are always the same set in practice. Deriving
  one from the other here would make that coincidence load-bearing.

  Answers `{:missing ..}`, `{:refused ..}` or `{:builder ..}`."
  [spec builtins]
  (let [files (:files spec)
        features (or (:features spec) flint.compiler.reader/default-features)
        entry (:entry spec)
        entry-ns (symbol (namespace entry))
        ;; The namespace RESOLVER (`DECISIONS.md#workspace-capabilities`). This used to be a
        ;; lambda here that answered source text and nothing else, which is why
        ;; reader tags worked from the CLI and silently did not through the
        ;; SDK: the CLI knew which project a file belonged to and this did not.
        ;; Now both front doors produce the same thing.
        resolve-ns (project/files-resolver files (:workspaces spec))
        ;; `:roots` is how `flint test` compiles: its entry is generated and
        ;; is on no source path, so resolving from it would report the entry
        ;; itself missing. Absent, the entry is the root as always.
        ;; A SPEC MAY ARRIVE ALREADY RESOLVED, and then resolution is skipped
        ;; rather than redone. `bin/flint --emit-spec` writes `{:sources .. :order ..}`
        ;; -- the shape `compile-to-base64` takes -- and every ARTIFACT target went
        ;; through the resolver below, which wants `{:files ..}`. So an emitted spec
        ;; compiled in mode `spec` and answered `!missing` in mode `jvm`, `clr` or
        ;; `llvm`, naming the program's own namespaces: the mode decided, not the
        ;; spec. Each CLI builds the unresolved shape internally and exposes it to
        ;; nobody, so a HOST could obtain one shape and no artifact target accepted
        ;; it.
        ;;
        ;; Everything AFTER this line is unchanged and still applies -- the
        ;; field-by-field rebuild, the builtins parameter -- so a resolved spec gets exactly the treatment a resolved one
        ;; always got. This skips a step; it does not take a different path.
        {:keys [sources order missing refused]}
        (if (and (:sources spec) (:order spec))
          {:sources (:sources spec) :order (:order spec)}
          (project/resolve-project resolve-ns entry-ns features (:roots spec)))]
    (if (seq missing)
      {:missing (vec missing)}
      (if (seq refused)
        {:refused (vec refused)}
        (let [result (compiler/compile-image
                      ;; `:tags` and `:workspace` travel WITH the source. This
                      ;; used to hand on `:src` and `:file` only, and the compiler
                      ;; read each file again -- so a tag the resolver bound was
                      ;; known to `collect` and unknown here, and `#x` read as an
                      ;; unbound tag however carefully the workspace declared it.
                      ;; A value only one reader knows is one the others get
                      ;; wrong; this was that, and the SDK having no tags at all is why
                      ;; nothing caught it (`DECISIONS.md#reader-tags`, `workspace-capabilities`).
                      {:sources (into {} (map (fn [e] [(key e) {:src (:src (val e))
                                                                ;; THE FORMS the resolver
                                                                ;; already read, so the
                                                                ;; compiler does not read
                                                                ;; the file a second time
                                                                ;; (`flint.compiler.core/read-source`).
                                                                :forms (:forms (val e))
                                                                :file (:file (val e))
                                                                :tags (:tags (val e))
                                                                :workspace (:workspace (val e))
                                                                ;; The DIALECT and the PRELUDE travel
                                                                ;; with them, and for the same reason:
                                                                ;; this map is rebuilt field by field,
                                                                ;; so anything not named here is
                                                                ;; silently dropped between the
                                                                ;; resolver and the compiler
                                                                ;; (`DECISIONS.md#dialects-and-preludes`).
                                                                :dialect (:dialect (val e))
                                                                :prelude (:prelude (val e))
                                                                :grants (:grants (val e))
                                                                ;; A VIRTUAL namespace has no
                                                                ;; `:src` and must not be read
                                                                ;; (`DECISIONS.md#workspace-capabilities` step 4).
                                                                :virtual (:virtual (val e))
                                                                :vars (:vars (val e))}])
                                              sources))
                       :order (vec (filter (fn [n] (contains? sources n)) order))
                       :entry entry
                       :exports (vec (distinct (or (:exports spec) [])))
                       :builtins builtins
                       :features features})]
          ;; THE PERF DECISION GOES IN THE IMAGE, and this path never wrote it.
          ;;
          ;; `FLAG-PERF` is how an artifact ASKS ITS PORT to compile arities at
          ;; load: `image.cljc` says "an image for a PORT has the bit and an empty
          ;; table, which is exactly the case that could not be expressed before".
          ;; `bin/flint` sets it for every target before dispatching; nothing here
          ;; did, so `:optimize [perf]` was accepted, resolved, used to pick the AOT
          ;; runtime for wasm -- and silently dropped for the port targets.
          ;;
          ;; MEASURED 2026-09-28, `(ns t) (defn main [_] "ok")` to
          ;; `:to :clr :optimize [perf]`, read off `runtimes/clr/artifact/Check.cs`:
          ;; 1987 arities compiled through `bin/flint` and 0 through the native CLI,
          ;; from one source. Both artifacts "passed every check", because the check
          ;; prints the count as information and asserts nothing about it -- the
          ;; same shape as `:to :clr` shipping unloadable assemblies while a suite
          ;; confirmed the magic bytes (`test/selfhost-targets.clj`).
          ;;
          ;; `(:aot spec)` and not `:optimize`: the CLIs resolve the preference list
          ;; to a decision before building the spec, which is the same split
          ;; `set-perf!`'s docstring names -- "the image carries the DECISION, not
          ;; the preference list".
          (img/set-perf! (:builder result) (boolean (:aot spec)))
          {:builder (:builder result) :stats (:stats result)})))))

(declare compile-project-spec)

(defn ^:internal compile-project
  "Compile from an ENTRY and a map of source files, resolving `:require`s here.

  `spec` is EDN:

      {:files {\"clojure/core.cljc\" \"(ns clojure.core) ..\" ..}
       :entry my.app/main
       :builtins #{..}
       :workspaces [{:prefix \"vendor/foo/\" :name foo/bar :tags {t f}} ..]
       :features flint.compiler.reader/default-features}

  `:workspaces` says who OWNS which files, first matching prefix winning. It is
  how a caller with no filesystem says what the CLI reads off a source root:
  which reader tags a file is read under, and -- once capabilities land -- what
  its workspace was granted (`DECISIONS.md#workspace-capabilities`). Absent, every file belongs
  to the anonymous workspace and only the built-in tags are bound, which is
  what this did before workspaces existed.

  The difference from `compile-to-base64` is that the caller does not have to
  know what the program requires. That resolution is most of what a compiler
  does before it compiles anything, and a host driving this module through
  WebAssembly has no way to do it -- it would have to parse `ns` forms, which
  means it would need a Clojure reader, which is what it is calling.

  A namespace with no source is named, all of them at once. Reporting the first
  and stopping makes fixing a dependency list an n-round conversation."
  [spec-edn]
  (compile-project-spec (read-spec spec-edn)))

(defn ^:internal compile-project-spec
  "`compile-project` on a spec that is already a value.

  The native CLI hands the FILE BODIES over this way: an envelope read as EDN,
  whose `:files` is empty, and the bodies as an encoded map the runtime decoded
  natively. Reading the spec had been 57% of a trivial compile's instructions
  (10.9 M of 19.1 M, measured 2026-10-01), almost all of it the reader walking
  the bodies of string literals it was only going to hand back."
  [spec]
  (let [built (build-image spec (or (:builtins spec) #{}))]
    (if (:missing built)
      {:missing (:missing built)}
      (if (:refused built)
        {:refused (:refused built)}
        (let [builder (:builder built)]
          {:image (base64 (img/emit builder {}))
           :natives (img/natives builder)})))))

;; FORWARD-DECLARED for sci's sake (see `compile-to-clr*` below): each EDN
;; door is followed by the value-taking function `compile` shares with it.
(declare wasm-artifact llvm-artifact jvm-artifact)

(defn ^:internal compile-to-wasm
  "Compile a program and splice it into a PREBUILT runtime module, producing a
  standalone `.wasm`.

  This is what a compiler is expected to emit; the bytecode image is internal
  machinery. `spec` adds three keys to `compile-project`'s:

      :slots  {builtin-name table-slot} for that module
      :aot    true to append compiled arities as well
      :shake  true to cut the runtime down to what this program reaches
      :meta   arbitrary metadata to record in the artifact, carried and never
              read -- the declared capabilities of a program live here by the
              CLI's convention, and the convention is the reader's, not ours

  The runtime module arrives as a SEPARATE argument rather than inside the
  spec, and that is not tidiness. Three-quarters of a megabyte of base64 inside
  an EDN string is three-quarters of a megabyte the reader has to scan a
  character at a time: it took 198 seconds, against 450 milliseconds for all
  the byte handling put together.

  No linking happens and none is needed (`DECISIONS.md#no-runtime-linking`). Linking merges
  relocatable objects and is `wasm-ld`; the runtime module was linked once,
  when flint was built. Everything after that -- appending the image as a data
  segment, pointing the descriptor at it, appending compiled arities -- is byte
  manipulation on a finished module."
  [spec-edn base-b64]
  (let [r (wasm-artifact (read-spec spec-edn) (base64-decode base-b64))]
    (if (:bytes r)
      (-> r (dissoc :bytes) (assoc :module (base64 (:bytes r))))
      r)))

(defn- wasm-artifact
  "`compile-to-wasm` on a spec that is already a VALUE and a runtime module that
  is already BYTES, answering the module as bytes under `:bytes`. The EDN door
  above and `compile` below both come through here, so the two cannot build
  different modules."
  [spec base]
  (let [entry (:entry spec)
        slots (:slots spec)
        built (build-image spec (set (keys slots)))]
    (if (:missing built)
      {:missing (:missing built)}
      (if (:refused built)
        {:refused (:refused built)}
      (let [builder (:builder built)
            m (w/parse base)
            aot? (boolean (:aot spec))
            ;; BEFORE the image is emitted: `compile-arities` writes each
            ;; compiled arity's table slot into the builder, so an image
            ;; emitted first would carry none of them.
            res (when aot? (bundle/compile-arities m builder (w/exports m) nil))
            m (if res (:module res) m)
            ;; Shaking LAST, and after the compiled arities exist, so they are
            ;; roots like anything else. The roots are every entry point a host
            ;; uses plus exactly the table slots this image imports -- which is
            ;; precision the linker could not have had, because it was handed an
            ;; export list before the program existed.
            shaken (when (:shake spec)
                     (let [exp (w/exports m)
                           table (wshake/table-entries m)
                           abi (remove (fn [n] (str/starts-with? n "flint_b_")) (keys exp))
                           ;; The table holds two different things, and they
                           ;; need different treatment.
                           ;;
                           ;; Below `slots` are the LINKER's own function
                           ;; pointers -- Rust compiles a closure or a trait
                           ;; object to `call_indirect` -- and nothing here can
                           ;; tell which of them is reachable, so all of them
                           ;; are roots. Rooting only the builtins the image
                           ;; imports looked like precision the linker could
                           ;; not have; it stubbed the scheduler's own
                           ;; callbacks, and every program using ports trapped
                           ;; inside `conc::scheduler` while small ones worked.
                           ;;
                           ;; At and above them are flint's BUILTINS, which are
                           ;; reached only through the NATIVE opcode with an
                           ;; index the image carries (`DECISIONS.md#namespace-units`).
                           ;; Those the image does not name, nothing can call.
                           builtin-slots (set (vals slots))
                           used (set (img/natives builder))
                           linker-fns (keep (fn [e] (when-not (contains? builtin-slots (key e))
                                                      (val e)))
                                            table)
                           mine (keep (fn [n] (get table (get slots n))) used)
                           roots (into (into (into #{} (keep (fn [n] (:index (get exp n))) abi))
                                             linker-fns)
                                       (concat mine
                                               ;; The compiled arities, which
                                               ;; nothing else can reach: they
                                               ;; live only in the element
                                               ;; segment `compile-arities`
                                               ;; appended.
                                               (or (:funcs res) [])))]
                       (wshake/stub-dead m roots)))
            m (if shaken (first shaken) m)
            image (img/emit builder slots)]
        {:bytes (bundle/into-module (w/emit m) image
                                    {:entry entry :aot? aot? :slots slots
                                     :meta (:meta spec)})
         :compiled (when res (:compiled res))
         :arities (when res (:total res))
         :shaken (when shaken (second shaken))})))))

(defn ^:internal compile-to-llvm
  "Compile a program to ONE LLVM IR module (`DECISIONS.md#llvm-ir-target`).

  `spec` is `compile-to-wasm`'s, minus everything about a wasm module: there is
  no base module, no table to splice into, and no shaking, because there is no
  finished artifact here to cut down. What comes out is text.

  `:aot` compiles every arity it can to an LLVM function
  (`flint.compiler.llvm/compile-arities`); without it the module is the program image
  and the two calls that start it, and every arity is interpreted.

  NOTHING IS LINKED HERE and nothing needs to be. That was the error in the
  refusal this replaces: it gave `:to :native`'s reason -- a linker -- for
  `:to :llvm`'s absence, and the actual reason was that no emitter existed."
  [spec-edn]
  (llvm-artifact (read-spec spec-edn)))

(defn- llvm-artifact
  "`compile-to-llvm` on a spec that is already a value: the door `compile`
  shares with the EDN one."
  [spec]
  (let [built (build-image spec (spec-builtins spec))]
    (if (:missing built)
      {:missing (:missing built)}
      (if (:refused built)
        {:refused (:refused built)}
        (let [builder (:builder built)
              aot? (boolean (:aot spec))
              ;; BEFORE the image is emitted, for the same reason the wasm path
              ;; compiles arities first: this writes each one's slot into the
              ;; builder, and an image emitted first would carry none of them.
              res (when aot? (llvm/compile-arities builder))
              ;; EMPTY slots, unlike the wasm path. A natively linked program
              ;; resolves its natives BY NAME against the host registry, the
              ;; way `flint run` does -- a table index would name a table this
              ;; artifact does not have (`DECISIONS.md#construe-integration-bar`).
              image (img/emit builder {})]
          {:ll (llvm/emit-module image (or (:ir res) "") (or (:names res) []))
           :compiled (when res (:compiled res))
           :arities (when res (:total res))})))))

;; FORWARD-DECLARED, because `compile-to-clr` reads the docstring's worth of
;; explanation and its helper follows it. flint's own analyzer accepts the forward
;; reference and sci does not, so loading this namespace with `bb --classpath src`
;; -- which is how one spec gets driven through BOTH compilers to tell a door bug
;; from a self-hosting divergence -- failed at analysis with "Could not resolve
;; symbol: compile-to-clr*".
(declare compile-to-clr*)

(defn ^:internal compile-to-clr
  "Compile a program to ONE .NET assembly (`DECISIONS.md#four-operations`).

  `spec` is `compile-to-llvm`'s. Nothing is linked and nothing needs to be: the
  assembly carries the program -- the bytecode as a `FieldRva` static array, plus
  the three operations -- and NAMES flint's runtime as an assembly reference. The
  interpreter, the collector and the builtins come from the host.

  EMPTY SLOTS, like the LLVM path and unlike wasm's. The natives resolve BY NAME
  against whatever table the host carries, so a slot index would name a table
  this artifact does not have.

  The metadata goes in as a `CustomAttribute` row rather than being answered by a
  call, which is why there is no `prop` here or anywhere: a reader decides
  WHETHER to load an artifact, and that cannot depend on having loaded it.

  Bytes out, so the caller base64s them -- the opposite of `:ll`, which is text.

  A RESOLVED SPEC WORKS HERE, and a restriction that said otherwise was removed
  along with the bug that motivated it. It required a spec to carry `:slots`,
  because an already-resolved spec produced an assembly `Assembly.Load` refused
  with `BadImageFormatException: Invalid COR20 header signature`.

  THE CAUSE WAS NOT THE SPEC. `compile-to-clr*` handed `clr/assemble` the VECTOR
  `img/emit` answers, and `clr/assemble` measures its image with
  `flint.rt/b-count`, which does not measure a vector -- so the length came out
  wrong, the COR20 header's metadata RVA was computed WITHOUT the bytecode's size,
  and it pointed 26 719 bytes early, into the embedded image itself. .NET read
  `FLIN` where `BSJB` belongs. Every spec hit it, including the CLI's own, which is
  why refusing resolved ones fixed nothing.

  With `vec->b` in place a resolved spec compiles to an assembly that loads and
  passes all 39 rows of `runtimes/clr/artifact/Check.cs`, with or without `:slots`.

  `name` is the output file's BASENAME, or empty when there is no output file --
  `flint.compiler.clr/assembly-name` turns it into the assembly name and is the only place
  that rule lives. `bin/flint` derived the name from `:out` and this door could
  not, so the two produced artifacts differing by 56 bytes of string-heap offsets
  -- the only thing left between them once the describe was canonical. Byte
  agreement between the doors is the standard `sdks/cli/selftest.mjs` already holds
  `:to :wasm` to, and `test/selfhost-targets.clj` now holds `:to :clr` to it.

  A RAW BASENAME AND NOT A SANITISED NAME, so that each door passes something it
  cannot get wrong. Three doors sanitising separately agree on `app.dll` and part
  ways on `a.b.dll`, where one strips an extension the others already stripped."
  [spec-edn name]
  (compile-to-clr* (read-spec spec-edn) name))

(defn- compile-to-clr* [spec name]
  (let [built (build-image spec (spec-builtins spec))]
    (if (:missing built)
      {:missing (:missing built)}
      (if (:refused built)
        {:refused (:refused built)}
        ;; NO `modmeta/describe` HERE ANY MORE: `clr/assemble` builds it from the
        ;; inputs below. `:abi :clr` and the three exports are properties of the
        ;; TARGET, and this was one of the two places restating them
        ;; (`DECISIONS.md#four-operations`, "the emitter owns describe"). The
        ;; compatibility key is still computed by `flint.compiler.modmeta` exactly once --
        ;; that has not changed, only WHO calls it.
        ;; `vec->b`, AND `compile-to-jvm`'S COMMENT ALREADY CLAIMED THIS PATH DID IT.
        ;; `img/emit` answers a VECTOR of byte values; `clr/assemble` measures its
        ;; image with `flint.rt/b-count`, which does not measure a vector. So the
        ;; length came out wrong, the COR20 header's metadata RVA was computed
        ;; WITHOUT the bytecode's size, and the header pointed 26 719 bytes early --
        ;; into the embedded image itself. `.NET` then read `FLIN` where `BSJB`
        ;; belongs and refused the assembly with `BadImageFormatException: Invalid
        ;; COR20 header signature`.
        ;;
        ;; It is the same defect `compile-to-jvm` records having had -- "handing the
        ;; vector over produced a class with correct METADATA and no bytecode in it"
        ;; -- and its comment says "`vec->b`, WHICH `compile-to-clr` DOES TOO". That
        ;; sentence was false, and a comment asserting a sibling is correct is how
        ;; the sibling stays wrong.
        (let [image (flint.rt/vec->b (vec (img/emit (:builder built) {})))]
          ;; `:bytes` out of the map `assemble` answers -- it also carries the
          ;; per-method assembly facts, which nothing here wants.
          {:clr (:bytes (clr/assemble {:name (if (or (nil? name) (= "" name))
                                               (clr/assembly-name (:name spec))
                                               (clr/assembly-name name))
                                       :image image
                                       :describe true
                                       :version (:version spec)
                                       ;; WHAT ARRIVED, not what was offered.
                                       ;; `(:builtins spec)` is every builtin the
                                       ;; runtime provides -- 226 -- and this field
                                       ;; described the artifact as needing all of
                                       ;; them, where `bin/flint` and
                                       ;; `compile-to-jvm` both report the ones it
                                       ;; actually uses (88). `describe`'s
                                       ;; neighbouring `:features` states the rule:
                                       ;; "From what ARRIVED, not from what was
                                       ;; asked for: a descriptor that reports the
                                       ;; build flags rather than the module is the
                                       ;; kind that goes quietly wrong."
                                       :builtins (count (img/natives (:builder built)))
                                       ;; NO `:features` HERE, AND THAT IS THE FIX FOR A
                                       ;; NAME COLLISION. `describe`'s `:features` is the
                                       ;; BUILD feature map a wasm module carries --
                                       ;; `{:aot true :capabilities true ..}`. `(:features
                                       ;; spec)` is the READER feature SET,
                                       ;; `#{:flint :flint/nested :flint/check}`. Passing
                                       ;; one as the other put a set in a map's slot.
                                       ;;
                                       ;; It was INERT for as long as no spec carried
                                       ;; `:features`: a CLI spec has none, `(:features
                                       ;; spec)` was nil, and describe recorded `{}`. An
                                       ;; already-resolved spec DOES carry them -- that is
                                       ;; what `bin/flint --emit-spec` writes -- and then
                                       ;; the artifact's metadata read
                                       ;; `:features #{:flint :flint/nested :flint/check}`
                                       ;; where every other artifact reads a map. One slot,
                                       ;; two meanings; the reader set has no business in
                                       ;; an artifact's build description.
                                       :meta-map (:meta spec)}))})))))

(defn ^:internal compile-to-jvm
  "Compile a program to ONE CLASS FILE, or to a jar carrying a runtime beside it.

  THE ARTIFACT IS THE CLASS, and `base-b64` being EMPTY is how a caller asks for
  it. That is what makes this reachable from a host that has no jar to hand: the
  class references the runtime and the host supplies it through `link`, exactly as
  `:to :clr`'s assembly references `Flint.dll`
  (`DECISIONS.md#one-image-per-sandbox`). `flint compile :to :jvm` takes this
  path, which is why the native CLI does not have to embed `dist/flint-rt.jar` --
  a cost that was named as the blocker for wiring this target and is not one.

  `base-b64` NON-EMPTY is `dist/flint-rt.jar`, and then the answer is that jar
  with the class appended: a convenience for a consumer with an empty classpath,
  the way a distribution ships a JRE beside an application. It arrives as its own
  ARGUMENT rather than inside the spec for the reason `compile-to-wasm`'s module
  does: half a megabyte of base64 inside an EDN string is half a megabyte for
  flint's reader to scan a character at a time.

  EMPTY SLOTS, like the LLVM target and unlike wasm. This port resolves every
  native BY NAME when it loads the image (`Img.java`), so a table index would
  name a table this artifact has not got.

  Nothing is linked and no JDK is involved. `javac` ran once, when flint was
  built; appending one class to a finished jar is byte manipulation
  (`DECISIONS.md#no-runtime-linking`).

  `class-name` NAMES THE CLASS, or is empty for the default `flint/Artifact`. A
  JVM loads a class only from a path matching its own name, so a caller writing
  one FILE rather than a classpath root has to be able to say what that file is
  called -- which is what made `:to :jvm`'s `:out` a directory while every other
  target took a file. It is a fourth ARGUMENT and not a new mode, so the three
  lists `main` warns about are untouched."
  [spec-edn base-b64 class-name]
  (let [r (jvm-artifact (read-spec spec-edn)
                        (when-not (or (nil? base-b64) (= "" base-b64)) (base64-decode base-b64))
                        class-name)]
    (if (:bytes r) {:module (base64 (:bytes r))} r)))

(defn- jvm-artifact
  "`compile-to-jvm` on a spec that is already a value and a base jar that is
  already bytes (nil for the class alone), answering `:bytes`."
  [spec base class-name]
  (let [built (build-image spec (spec-builtins spec))]
    (if (:missing built)
      {:missing (:missing built)}
      (if (:refused built)
        {:refused (:refused built)}
        (let [builder (:builder built)
              ;; `vec->b`, WHICH `compile-to-clr` DOES TOO. `img/emit` answers a
              ;; VECTOR of byte values and `jvm/emit` measures its argument with
              ;; `flint.rt/b-count`, so handing the vector over produced a class
              ;; with correct METADATA and no bytecode in it -- 894 bytes that
              ;; loaded, reported `:builtins 88`, and threw at boot. The
              ;; babashka door passes `byte-array` for the same reason.
              image (flint.rt/vec->b (vec (img/emit builder {})))
              opts (let [base {:version (:version spec)
                               :meta (:meta spec)
                               :builtins (count (img/natives builder))}]
                     (if (or (nil? class-name) (= "" class-name))
                       base
                       (assoc base :class class-name)))]
          ;; ONE DOOR FOR THE CLASS. `jvm/pack` calls `jvm/emit` itself, so both
          ;; arms go through the same emitter and the class cannot pick up a
          ;; property on one path and lose it on the other -- which is the exact
          ;; defect `bin/build-jvm-artifact` records having had when it called
          ;; `artifact-class` directly.
          {:bytes (if (nil? base)
                    (jvm/emit image opts)
                    (jvm/pack base image opts))})))))

(defn ^:internal preread
  "Every file in the spec READ AHEAD OF ITS FEATURES, as `{path bytes}` -- what
  the native CLI embeds for the standard library so that a compile does not
  read it again (`DECISIONS.md#stdlib-preread`).

  Each file is asked exactly the question a compile asks of it,
  `flint.compiler.resolve/file-answer` over the spec's `:files` and `:workspaces`, and
  read by `flint.compiler.reader/read-deferred`, which keeps reader conditionals as
  data: one read serves the default build, `:optimize [perf]` and any explicit
  `:features`. The bytes are `flint.compiler.forms`'s, which carry every form's
  position metadata and the options the read was made under, which a compile
  checks before it uses them.

  A spec with `:features` reads EAGERLY under that set instead
  (`flint.compiler.resolve/read-eager`) -- the bytes a host-side reader must reproduce,
  which is what the kin reader's conformity guard compares against
  (`cli/src/kin_reader_test.rs`). With `:guard` a file that does not read
  answers `{:error message}` in place of its bytes, so one bad fixture does
  not hide the rest; the build's own pre-read keeps throwing."
  [spec-edn]
  (let [spec (read-spec spec-edn)
        files (:files spec)
        features (:features spec)
        guard? (contains? spec :guard)]
    {:preread
     (into {} (map (fn [path]
                     (let [a (project/file-answer files (:workspaces spec) path)
                           read (fn [] (if features
                                         (project/read-eager a (set features))
                                         (project/preread a)))]
                       [path (if guard?
                               (try (read) (catch Throwable e {:error (ex-message e)}))
                               (read))]))
                   (keys files)))}))

(defn- image-artifact
  "The bare bytecode image -- `:target :image`, what `flint run` loads -- with
  the native import order beside it, as `compile-project-spec` answers it."
  [spec]
  (let [built (build-image spec (spec-builtins spec))]
    (if (or (:missing built) (:refused built))
      built
      (let [builder (:builder built)]
        {:bytes (flint.rt/vec->b (vec (img/emit builder {})))
         :natives (img/natives builder)
         :stats (:stats built)}))))

(defn- answer-forms
  "One element of the host's answer with its file READ: `:forms` bytes (the
  `flint.compiler.forms` encoding, checked against the options this compile reads
  under, `flint.compiler.resolve/read-entry`) or, until hosts read user text
  themselves, `:source` text -- either way `:forms` comes out as forms, which
  is all `flint.compiler.resolve/Resolver` speaks. A read that fails is the element's
  `{:error ..}`, positioned, rather than a throw that would lose the others."
  [a features]
  (if (or (nil? a) (:virtual a) (:error a))
    a
    (try
      (assoc (dissoc a :source)
             :forms (project/read-entry (assoc a :src (:source a) :preread (:forms a)) features))
      (catch Throwable e
        (let [d (ex-data e)]
          {:error (cond-> {:message (ex-message e)}
                    (:file d) (assoc :file (:file d))
                    (:line d) (assoc :line (:line d))
                    (:column d) (assoc :column (:column d)))})))))

(defn- error-of
  "A thrown value as one `:errors` entry."
  [e]
  (let [d (ex-data e)]
    (cond-> {:kind (cond (:flint/resolver d) :resolver
                         (= :reader (:type d)) :read
                         :else :compile)
             :message (or (ex-message e)
                          (str "a " #?(:flint (name (flint.rt/kind e)) :default (str (type e)))
                               " was thrown, with no message"))}
      (:file d) (assoc :file (:file d))
      (:line d) (assoc :line (:line d))
      (:column d) (assoc :column (:column d)))))

(defn- artifact
  "The artifact for `spec`'s `:target`, as `{:artifact bytes ..}`, through the
  same per-target functions the EDN doors use."
  [spec]
  (let [target (or (:target spec) :image)
        r (case target
            :image (image-artifact spec)
            :wasm (wasm-artifact spec (:base spec))
            :llvm (let [r (llvm-artifact spec)]
                    (if (:ll r) (-> r (dissoc :ll) (assoc :bytes (flint.rt/str->b (:ll r)))) r))
            :clr (let [r (compile-to-clr* spec (:name spec))]
                   (if (:clr r) {:bytes (:clr r)} r))
            :jvm (jvm-artifact spec (:base spec) (:class spec))
            {:unknown target})]
    (cond
      (:unknown r) {:errors [{:kind :request
                              :message (str "unknown :target " (pr-str (:unknown r))
                                            "; one of :image :wasm :llvm :clr :jvm")}]}
      ;; Resolution already succeeded, so these cannot happen through `compile`;
      ;; kept so a change upstream reports rather than answers nil.
      (:missing r) {:errors (mapv (fn [n] {:kind :missing :ns n}) (:missing r))}
      (:refused r) {:errors (mapv (fn [x] (assoc x :kind :refused)) (:refused r))}
      :else (-> r (dissoc :bytes) (assoc :artifact (:bytes r))))))

(defn ^:internal compile-with
  "Compile a program, asking `resolver` -- any `flint.compiler.resolve/Resolver` -- for
  each namespace it reaches (`DECISIONS.md#namespaces-over-the-system-port`,
  migration step 2). The entry for anything that can implement the protocol:
  babashka over a directory, or `compile` below over a host's port.

  `request` is a DATA map, not EDN text:

      {:id       any value, echoed in every namespace request
       :entry    my.app/main
       :roots    [ns ..]          optional; replaces the entry as the start
       :exports  [sym ..]
       :features #{:flint ..}     the read features; default flint.compiler.reader/default-features
       :target   :image | :wasm | :llvm | :clr | :jvm     (default :image)
       :aot bool :shake bool :meta {..}
       :slots    {\"name\" n}     or :builtins #{..}
       :base     #bytes           the runtime module (:wasm) or jar (:jvm)
       :class    \"Name\"         :jvm only
       :name     \"out.dll\"      :clr only: the output's basename}

  `resolver` is asked in sorted waves, each namespace once
  (`flint.compiler.resolve/resolve-wave` says what it answers).

  Answers `{:artifact bytes :reached [{:ns :workspace} ..] ..}` -- `:natives`
  and `:stats` for `:image`, `:compiled`/`:arities` where the target compiles
  arities -- or `{:errors [{:kind .. :ns .. :message .. :file .. :line ..
  :column ..} ..]}`, every resolution error at once. A failure is DATA; this
  never throws to the host.

  The EDN modes of `main` stay beside this until every door has moved
  (migration steps 3 to 7); both reach the same per-target functions, so they
  cannot build different artifacts from the same answers."
  [request resolver]
  (try
    (let [features (or (:features request) reader/default-features)
          entry (:entry request)
          r (project/resolve-project-waves resolver (symbol (namespace entry)) features
                                           (:roots request))]
      (if (seq (:errors r))
        {:errors (:errors r) :reached (:reached r)}
        (let [out (artifact (assoc request :features features
                                   :sources (:sources r) :order (:order r)))]
          (assoc out :reached (:reached r)))))
    (catch Throwable e
      {:errors [(error-of e)]})))

#?(:flint
   (do
     (defn port-resolver
       "A `flint.compiler.resolve/Resolver` over the resolver PORT a host passed into
       `compile`: one request per wave, `{:id id :want [ns ..]}`, parked until the
       host answers a vector parallel to `:want`
       (`DECISIONS.md#namespaces-over-the-system-port`).

       HOLDING THE PORT IS THE CAPABILITY. The compiler asks on what it was handed,
       so it holds no `:host` grant and calls no `flint.host/request`; a host that
       passes no port has given it nothing to ask.

       The bootstrap ADAPTER between the wire and the protocol: the wire carries
       bytes, the protocol speaks forms, and this is where one becomes the other
       (`answer-forms`). A port that ends unanswered, or an answer of the wrong
       shape, throws a `:flint/resolver` error that `compile` reports as
       `:resolver` -- never a hang or a misread program.

       Implemented by METADATA, which is how a flint value implements a protocol
       (flint has no `reify`)."
       [id p features]
       (with-meta {}
         {'flint.compiler.resolve/resolve-wave
          (fn [_ want]
            (when-not (port/port? p)
              (throw (ex-info "this host does not serve namespaces: the compile was given no resolver port"
                              {:flint/resolver true})))
            (port/send p {:id id :want want})
            ;; BOUND, then read: `receive` parks (see `flint.host/request`).
            (let [a (port/receive p)]
              (cond
                (and (vector? a) (= (count a) (count want)))
                (mapv (fn [x] (answer-forms x features)) a)

                (and (nil? a) (port/closed? p))
                (throw (ex-info "this host does not serve namespaces: the resolver port closed unanswered"
                                {:flint/resolver true}))

                :else
                (throw (ex-info (str "the resolver answered a request for " (count want)
                                     " namespaces with " (if (vector? a)
                                                           (str "a vector of " (count a))
                                                           (name (flint.rt/kind a))))
                                {:flint/resolver true})))))}))

     (defn compile
       "`compile-with`, asking for namespaces on `resolver`: a PORT the host
       opened and passed in (`DECISIONS.md#namespaces-over-the-system-port`).
       An ORDINARY CALL on a bound port, `{:op :call :fn
       \"flint.compiler.selfhost/compile\" :args [request resolver]}`.

       On the port the compiler sends `{:id (:id request) :want [ns ..]}` and
       the host answers a vector parallel to `:want` whose elements are nil (not
       found), `{:forms bytes | :source text :file .. :dialect .. :workspace ..
       :grants .. :guard .. :prelude .. :tags ..}`, `{:virtual true :vars ..
       :workspace ..}`, or `{:error {:message .. :file .. :line .. :column
       ..}}` -- the protocol's answer with the forms still ENCODED, which
       `port-resolver` decodes."
       [request resolver]
       (compile-with request
                     (port-resolver (:id request) resolver
                                    (or (:features request) reader/default-features))))))

(declare main*)

(defn main
  "The compiler's entry. `[\"split\" files mode & args]` is `[mode & args]` with
  the spec's file bodies handed over ENCODED -- only a caller using `call` can
  pass a map, and the native CLI does (`build_spec_split`). Every mode reads its
  spec through `read-spec`, which merges them."
  [args]
  (if (= (first args) "split")
    (do (reset! pending-files (second args))
        (try (main* (vec (drop 2 args)))
             (finally (reset! pending-files nil))))
    (main* args)))

(defn- main* [args]
  ;; Two entries, chosen by the first argument. `spec` is the original: the
  ;; caller resolved every namespace and handed over a finished map, which is
  ;; what the bootstrap does because babashka is already reading files.
  ;; `project` is the one a host with no Clojure reader can use.
  (let [mode (first args)
        ;; A MODE MISSING FROM `known?` IS NOT A REFUSAL, it is silently read as
        ;; a SPEC. The `[mode spec-edn]` line below reinterprets the first
        ;; argument as the spec when it does not recognise it, so a target added
        ;; to the `cond` and forgotten here compiles the word "clr" as a program
        ;; and reports something about the reader. The two lists must move
        ;; together, and `test/selfhost-targets.clj` asserts they do -- a file
        ;; this comment cited for some time before it existed, which is its own
        ;; lesson. It checks THREE lists and not two: this one, the dispatch
        ;; `cond` below, and the OUTPUT `cond` that turns each result map back
        ;; into a string. The third is the one that was wrong -- `:clr` had no arm
        ;; there, so the target was listed in both lists above and still could not
        ;; work from the native CLI.
        known? (or (= mode "project") (= mode "wasm") (= mode "llvm")
                   (= mode "clr") (= mode "jvm") (= mode "preread"))
        [mode spec-edn] (if known? [mode (second args)] ["spec" mode])
        r (cond
            (= mode "wasm") (compile-to-wasm spec-edn (nth args 2 ""))
            ;; The CLASS, or a jar when a third argument carries one -- base64
            ;; under `:module` either way, the way a wasm module comes back,
            ;; because it is the same thing: a finished artifact with the program
            ;; in it and nothing for the host to link.
            (= mode "jvm") (compile-to-jvm spec-edn (nth args 2 "") (nth args 3 ""))
            (= mode "clr") (compile-to-clr spec-edn (nth args 2 ""))
            (= mode "llvm") (compile-to-llvm spec-edn)
            (= mode "project") (compile-project spec-edn)
            ;; Not a target: the standard library read, for the native CLI's
            ;; build to embed (`DECISIONS.md#stdlib-preread`).
            (= mode "preread") (preread spec-edn)

            :else (compile-to-base64 spec-edn))]
    (cond
      (:missing r)
      (flint.rt/str-join (concat ["!missing\n"] (interpose "\n" (map str (:missing r)))))
      ;; A REFUSED require is not a missing one and must not be reported as
      ;; one: the source is there and readable, and the answer is that this
      ;; workspace may not have it (`DECISIONS.md#workspace-capabilities`). Each line names both
      ;; ends and what was missing, because "refused" without the capability is
      ;; a message nobody can act on.
      (:refused r)
      (flint.rt/str-join
       (concat ["!refused\n"]
               (interpose "\n"
                          (map (fn [x]
                                 (str (:from x) " requires " (:to x)
                                      ", which " (:to-workspace x) " guards with "
                                      (pr-str (:needs x))
                                      "; " (or (:from-workspace x) "this program")
                                      " does not hold it"))
                               (:refused r)))))
      ;; A module comes back alone: its native slots are already in it, so
      ;; there is no import order for the host to apply.
      (:module r) (:module r)
      ;; THE ONE ANSWER THAT IS NOT A STRING: `{path bytes}`, each file's
      ;; forms in `flint.compiler.forms`'s encoding (`preread`).
      (:preread r) (:preread r)
      ;; `:clr` -- AND THIS ARM WAS MISSING, so `:to :clr` through the
      ;; SELF-HOSTED compiler had never once worked. `compile-to-clr` answers
      ;; `{:clr bytes}` and its own docstring says "Bytes out, so the caller
      ;; base64s them"; with no arm for it the `cond` fell through to `:else`,
      ;; which reads `(:image r)` -- nil -- and every `flint compile :to :clr`
      ;; on the native CLI died with `ClassCastException: str-join wants
      ;; strings`, four frames from anything named `clr`.
      ;;
      ;; `bin/flint :to :clr` works and always did, which is exactly why this
      ;; survived: it calls `clr/assemble` itself and never comes through here,
      ;; so the target looked wired from the door most likely to be tried.
      (:clr r) (base64 (:clr r))
      ;; LLVM IR is TEXT and leaves as text -- no base64 on the way out, which
      ;; is the one visible difference from every other target here.
      (:ll r) (:ll r)
      :else
      ;; One string out: base64 image, newline, then the native import order,
      ;; one per line, which is what the host needs to assign slots.
      (flint.rt/str-join
       (concat [(:image r) "\n"]
               (interpose "\n" (:natives r)))))))
