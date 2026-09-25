(ns flint.selfhost
  "The compiler, as a flint program.

  Compiled by babashka once; from then on flint compiles flint. Input and output
  are strings because that is the whole module ABI: a vector of strings in, a
  string out. The image is binary, so it comes back base64-encoded and the host
  decodes and links it.

  What stays on the host is only what a flint module has no business doing:
  reading files and running `rust-lld`. The compiler itself -- reader, analyzer,
  emitter, macro evaluation -- is all in here."
  (:require [flint.compiler :as compiler]
            [flint.image :as img]
            [flint.reader :as reader]
            [flint.project :as project]
            [flint.wasm :as w]
            [flint.bundle :as bundle]
            [flint.llvm :as llvm]
            [flint.clr :as clr]
            [flint.jvm :as jvm]
            [flint.modmeta :as modmeta]
            [flint.wasmshake :as wshake]

            [clojure.string :as str]
            [flint.rt]))

(def ^:private b64-alphabet
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/")

(defn base64
  "Bytes to base64 text. Encoded INTO a byte string and decoded once at the
  end: base64 is ASCII, so the output is bytes, and the obvious version --
  accumulating one-character strings and joining -- allocates one string per
  character. A wasm module is three-quarters of a million of them."
  [bytes]
  ;; `flint.image/emit` still answers with a VECTOR of byte values, so this
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

(defn compile-to-base64
  "`spec` is EDN: {:sources {ns {:src .. :file ..}} :order [..] :entry ns/fn
  :builtins #{..}}. Returns the base64 image, with native slots left at zero for
  the host to patch (`flint.image/patch-native-slots`)."
  [spec-edn]
  (let [spec (reader/read-one spec-edn)
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

(defn base64-decode
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
        features (or (:features spec) flint.reader/default-features)
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
        {:keys [sources order missing refused]}
        (project/resolve-project resolve-ns entry-ns features (:roots spec))]
    (if (seq missing)
      {:missing (vec missing)}
      (if (seq refused)
        {:refused (vec refused)}
        (let [result (compiler/compile-image
                      ;; `:tags` and `:workspace` travel WITH the source. This
                      ;; used to hand on `:src` and `:file` only, and the compiler
                      ;; reads each file again -- so a tag the resolver bound was
                      ;; known to `collect` and unknown here, and `#x` read as an
                      ;; unbound tag however carefully the workspace declared it.
                      ;; `compiler.cljc` says a file is read three times and a
                      ;; value only one reader knows is one the others get wrong;
                      ;; this was that, and the SDK having no tags at all is why
                      ;; nothing caught it (`DECISIONS.md#reader-tags`, `workspace-capabilities`).
                      {:sources (into {} (map (fn [e] [(key e) {:src (:src (val e))
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
                       ;; `flint.system/serve` IS ALWAYS AN EXPORT. It is the
                       ;; sandbox's control plane
                       ;; (`DECISIONS.md#bridges-are-the-only-door`), spawned by
                       ;; bootstrap rather than called from the program -- so
                       ;; nothing in the program references it and the shake
                       ;; would drop the only door into the module. Added here
                       ;; rather than asked for, because a caller cannot be
                       ;; expected to know the runtime needs it.
                       ;; A SYMBOL, not a string: `extra-roots` feeds
                       ;; reachability beside `entry-var`, which is a symbol,
                       ;; and a string silently matches nothing -- the module
                       ;; compiled, the namespace was in the program, and
                       ;; `serve` was shaken anyway.
                       :exports (vec (distinct (conj (or (:exports spec) [])
                                                     'flint.system/boot)))
                       :builtins builtins
                       :features features})]
          {:builder (:builder result) :stats (:stats result)})))))

(defn compile-project
  "Compile from an ENTRY and a map of source files, resolving `:require`s here.

  `spec` is EDN:

      {:files {\"clojure/core.cljc\" \"(ns clojure.core) ..\" ..}
       :entry my.app/main
       :builtins #{..}
       :workspaces [{:prefix \"vendor/foo/\" :name foo/bar :tags {t f}} ..]
       :features flint.reader/default-features}

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
  (let [spec (reader/read-one spec-edn)
        built (build-image spec (or (:builtins spec) #{}))]
    (if (:missing built)
      {:missing (:missing built)}
      (if (:refused built)
        {:refused (:refused built)}
        (let [builder (:builder built)]
          {:image (base64 (img/emit builder {}))
           :natives (img/natives builder)})))))

(defn compile-to-wasm
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
  (let [spec (reader/read-one spec-edn)
        entry (:entry spec)
        slots (:slots spec)
        built (build-image spec (set (keys slots)))]
    (if (:missing built)
      {:missing (:missing built)}
      (if (:refused built)
        {:refused (:refused built)}
      (let [builder (:builder built)
            m (w/parse (base64-decode base-b64))
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
        {:module (base64 (bundle/into-module (w/emit m) image
                                             {:entry entry :aot? aot? :slots slots
                                              :meta (:meta spec)}))
         :compiled (when res (:compiled res))
         :arities (when res (:total res))
         :shaken (when shaken (second shaken))})))))

(defn compile-to-llvm
  "Compile a program to ONE LLVM IR module (`DECISIONS.md#llvm-ir-target`).

  `spec` is `compile-to-wasm`'s, minus everything about a wasm module: there is
  no base module, no table to splice into, and no shaking, because there is no
  finished artifact here to cut down. What comes out is text.

  `:aot` compiles every arity it can to an LLVM function
  (`flint.llvm/compile-arities`); without it the module is the program image
  and the two calls that start it, and every arity is interpreted.

  NOTHING IS LINKED HERE and nothing needs to be. That was the error in the
  refusal this replaces: it gave `:to :native`'s reason -- a linker -- for
  `:to :llvm`'s absence, and the actual reason was that no emitter existed."
  [spec-edn]
  (let [spec (reader/read-one spec-edn)
        built (build-image spec (set (keys (:slots spec))))]
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

(defn compile-to-clr
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

  Bytes out, so the caller base64s them -- the opposite of `:ll`, which is text."
  [spec-edn]
  (let [spec (reader/read-one spec-edn)
        built (build-image spec (set (keys (:slots spec))))]
    (if (:missing built)
      {:missing (:missing built)}
      (if (:refused built)
        {:refused (:refused built)}
        ;; NO `modmeta/describe` HERE ANY MORE: `clr/assemble` builds it from the
        ;; inputs below. `:abi :clr` and the three exports are properties of the
        ;; TARGET, and this was one of the two places restating them
        ;; (`DECISIONS.md#four-operations`, "the emitter owns describe"). The
        ;; compatibility key is still computed by `flint.modmeta` exactly once --
        ;; that has not changed, only WHO calls it.
        (let [image (img/emit (:builder built) {})]
          ;; `:bytes` out of the map `assemble` answers -- it also carries the
          ;; per-method assembly facts, which nothing here wants.
          {:clr (:bytes (clr/assemble {:name (or (:name spec) "Program")
                                       :image image
                                       :describe true
                                       :version (:version spec)
                                       :builtins (count (:builtins spec))
                                       :features (:features spec)
                                       :meta-map (:meta spec)}))})))))

(defn compile-to-jvm
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
  (`DECISIONS.md#no-runtime-linking`)."
  [spec-edn base-b64]
  (let [spec (reader/read-one spec-edn)
        built (build-image spec (set (keys (:slots spec))))]
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
              opts {:version (:version spec)
                    :meta (:meta spec)
                    :builtins (count (img/natives builder))}]
          ;; ONE DOOR FOR THE CLASS. `jvm/pack` calls `jvm/emit` itself, so both
          ;; arms go through the same emitter and the class cannot pick up a
          ;; property on one path and lose it on the other -- which is the exact
          ;; defect `bin/build-jvm-artifact` records having had when it called
          ;; `artifact-class` directly.
          {:module (base64 (if (or (nil? base-b64) (= "" base-b64))
                             (jvm/emit image opts)
                             (jvm/pack (base64-decode base-b64) image opts)))})))))

(defn main [args]
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
                   (= mode "clr") (= mode "jvm"))
        [mode spec-edn] (if known? [mode (second args)] ["spec" mode])
        r (cond
            (= mode "wasm") (compile-to-wasm spec-edn (nth args 2 ""))
            ;; The CLASS, or a jar when a third argument carries one -- base64
            ;; under `:module` either way, the way a wasm module comes back,
            ;; because it is the same thing: a finished artifact with the program
            ;; in it and nothing for the host to link.
            (= mode "jvm") (compile-to-jvm spec-edn (nth args 2 ""))
            (= mode "clr") (compile-to-clr spec-edn)
            (= mode "llvm") (compile-to-llvm spec-edn)
            (= mode "project") (compile-project spec-edn)
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
