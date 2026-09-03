(ns flint.impl.targets
  "Where each target puts a namespace's generated code.

  kin computes a destination rather than reading one from a table: each
  target carries a `:vfs`, a place, and `:path`, a function from namespace to
  file under it. That replaced sixteen `<source>.targets` sidecars, three
  columns each, which were sixteen restatements of a rule nobody had written
  down.

  This file is CODE rather than data for two reasons: a function cannot be
  written in EDN, and neither can a `:vfs`. kin performs no I/O of its own --
  every byte it reads or writes goes through the vfs a target carries -- so
  `disk-vfs` here is the project saying `write to my actual directories`, and
  a test can say something else.

  ## The table below is temporary, and it is the reason for the next commit

  Nine sources -- `champ`, `merge`, `copies`, `nodeassoc`, `collassoc`,
  `dissoc`, `find`, `collnode`, `nodeclass` -- all write into `map.rs`,
  `Maps.java` and `Maps.cs`. They exist because the port went function by
  function, not because the runtime wanted nine namespaces. A namespace to
  path function CAN be many to one, but only by carrying a lookup table --
  which is the sidecar moved rather than removed.

  So `unit` below is the sidecar, honestly labelled, and it lives only until
  the sources are consolidated one per destination file. When `flint.rt.champ`
  and its eight neighbours become one `flint.rt.maps`, `unit` collapses to
  the namespace's own last segment and this map goes away."
  (:require [clojure.string :as str]
            [kin]
            [kin.vfs :as vfs]
            [kin.target :as target]))

(def unit
  "Namespace -> the destination unit it belongs to.

  INTERIM. See the namespace docstring: this is the sidecar in one file
  instead of sixteen, and it is deleted by the consolidation commit."
  '{flint.rt.assoc     maps
   flint.rt.champ     maps
     flint.rt.collnode  maps
    flint.rt.copies    maps
    flint.rt.dissoc    maps
    flint.rt.find      maps
    flint.rt.merge     maps
     flint.rt.nodeclass maps
    flint.rt.eq        eq
    flint.rt.eqalloc   eq
    flint.rt.hash      hash
    flint.rt.interns   interns
    flint.rt.pike      pike
    flint.rt.seqs      seqs
    ;; NO DESTINATION, and that is not an omission. `unsigned.kin` exists to
    ;; be VERIFIED -- it pins what the unsigned forms MEAN in three languages
    ;; -- and ships nowhere. `:path` answering nil is how a source says so.
    flint.rt.unsigned  nil})

(defn- unit-of [ns-name]
  (or (get unit ns-name)
      (when-not (contains? unit ns-name)
        (throw (ex-info
                (str "kin: flint.impl.targets has no destination rule for "
                     ns-name ". Add it to `unit`, or -- better -- name the"
                     " source after the file it writes into.")
                {:namespace ns-name :known (vec (sort (keys unit)))})))))

(def rust-file
  "Rust's file per unit, and the irregularity is worth reading.

  `maps` is `map.rs` and `interns` is `gc.rs`: neither is derivable from the
  unit name by any rule, because the Rust tree was laid out by hand years
  before kin existed. The ports are regular -- `Maps.java`, `Interns.cs` --
  so only this one target needs a map at all."
  '{maps "map.rs" eq "eq.rs" hash "hash.rs" interns "gc.rs"
    pike "pike.rs" seqs "seqs.rs"})

(defn- pascal [s] (str/join (mapv str/capitalize (str/split (str s) #"-"))))

(def deep-rust
  "Units whose Rust region sits inside `impl Rt { }` and so is indented.

  `hash.rs` and `pike.rs` are free functions at the top of the file; the rest
  are methods. PROVISIONAL -- this is the indent half of `:wrap`, whose
  semantics are not yet settled, and it stands where the sidecar's third
  column used to."
  '#{maps eq interns seqs})

(def roots
  "Where each target's tree lives, relative to the repository root.

  Named ONCE and used twice: to build the disk vfs each target writes
  through, and to turn a vfs-relative path back into something `cmp` can
  read. A path kin answers is relative to a vfs, because that is what a path
  means once I/O goes through a protocol -- so resolving one against a real
  directory is OUR job, and doing it from the same map that built the vfs is
  what keeps the two from drifting."
  {:rust "runtime/src"
   :java "runtimes/jvm/src/com/flint/rt"
   :csharp "runtimes/clr/src/rt"})

(defn resolve-path
  "A vfs-relative path, as a path from the repository root."
  [target path]
  (str (get roots target) "/" path))

(def targets
  "The three, each one kin's shipped description MERGED WITH ours.

  The merge is written out rather than done for us. `bin/kin` used to fold
  `kin.target/defaults` into any target whose key happened to be `:rust`,
  which is convenient and is also kin knowing something about a language --
  the one thing this design says it must not. A project that names a target
  `:rust` and means something else entirely should not inherit Rust's
  identifier rules by accident, so inheriting them is a thing we SAY."
  {:rust (merge
          target/rust
          {:vfs (vfs/disk-vfs (:rust roots))
           :path (fn [ns-name] (some-> (unit-of ns-name) rust-file))
           :indent (fn [ns-name]
                     (if (contains? deep-rust (unit-of ns-name)) 4 0))})
   :java (merge
          target/java
          {:vfs (vfs/disk-vfs (:java roots))
           :path (fn [ns-name] (some-> (unit-of ns-name) pascal (str ".java")))
           ;; Every port region nests inside a class, so every one is indented.
           :indent 4})
   :csharp (merge
            target/csharp
            {:vfs (vfs/disk-vfs (:csharp roots))
             :path (fn [ns-name] (some-> (unit-of ns-name) pascal (str ".cs")))
             :indent 4})})


;; ---------------------------------------------------------------------------
;; WHOLE MODULES, under `kingen/`.
;;
;; The three targets above splice REGIONS into hand-written files. The three
;; below own a whole file each, which is what decision G says an `:emit`
;; means. They exist side by side for one commit so the two can be diffed
;; against each other: same code, different wrapper, and nothing else.

(def generated-root
  "The subtree every generated module lives under, PARALLEL to the human
  source rather than mixed into it. `kingen/flint/rt/champ.rs` sits beside
  `map.rs`, never contends with a hand-written name, and is obvious in a
  diff."
  "kingen")

(def module-roots
  "Where each target's generated subtree is rooted.

  Rust's has to sit INSIDE the crate -- `runtime/src` -- because a Rust
  module that is not under the crate root is not compiled at all. Java's and
  C#'s could sit anywhere their build is told about; they go in the same
  place for the same reason, which is that a reader looking for generated
  code should find it next to the code that consumes it."
  {:rust "runtime/src"
   :java "runtimes/jvm/src"
   :csharp "runtimes/clr/src"})

(defn- source-modules
  "Every kin source's last namespace segment, READ FROM THE SOURCE DIRECTORY.

  A generated module calls its siblings by their bare names -- `bnDatamap`
  comes from `champ.kin` and is called from `assoc.kin` -- so Java and C#
  need a static import of every sibling. The LIST of siblings is not written
  down here: a list of sixteen names in this file is the sidecar again, and
  it would go stale the first time a source was added. The vfs lists, so ask
  it.

  A source's file name is its namespace's last segment, which
  `flint.impl.project` already assumes when it labels `champ.kin` as
  `kin/champ.kin` for `flint.rt.champ`."
  []
  (sort (map #(str/replace % #"\.kin$" "")
             (vfs/listing (vfs/disk-vfs "kin") "*.kin"))))

(defn- ns-form-of [forms]
  (first (filter #(and (seq? %) (= 'ns (first %))) forms)))

(defn- body-of [forms]
  (remove #(and (seq? %) (= 'ns (first %))) forms))

(defn- emit-body
  "Every form, in source order, with no blank line between them -- which is
  the convention the regions already had and `dissoc.kin` already showed."
  [ctx forms]
  (doseq [f forms] (kin/statement! ctx f)))

(defn- receiver-tag
  "The TAG of the first parameter of the first method, or nil.

  A source is uniform: `champ.kin` is eight `^:method`s on `^Rt`,
  `interns.kin` is four `^:instance`s on `^Interns`, and `hash.kin` is nine
  free functions. So one form answers for the file, and Rust learns whether
  to open an `impl` and over WHAT from the source rather than from a table --
  which is what `deep-rust` was."
  [forms]
  (some (fn [f]
          (when (and (seq? f) (= 'defn (first f)))
            (let [m (meta (second f))]
              (when (or (:method m) (:instance m))
                (:tag (meta (first (nth f 2))))))))
        forms))

(def ^:private banner
  "Generated by kin from the source named below. Do not edit.")

(def ^:private rust-prelude
  "What a generated Rust module needs in scope.

  ONE PRELUDE FOR EVERY MODULE, rather than a per-source set derived from
  what each one happens to touch. The alternative is `need!` -- every
  vocabulary entry declaring its imports as data, which is what
  `examples/go` does -- and it is the better answer for a vocabulary being
  written now. Retrofitting it means annotating several hundred existing
  entries, and getting one wrong produces a module that does not compile
  rather than one that is subtly wrong, so the cost is real and the risk is
  not. `allow(unused_imports)` is the price, and it is stated here rather
  than discovered.")

(defn- module-file
  "The banner, the prelude, then the module's own content."
  [ctx ns-name comment-prefix prelude f]
  (kin/emit! ctx comment-prefix " " banner "\n")
  (kin/emit! ctx comment-prefix " source: " (str ns-name) "\n\n")
  (when (seq prelude) (kin/emit! ctx prelude "\n"))
  (f ctx))

(defn rust-emit
  "A Rust MODULE: prelude, then either an `impl` block or free functions.

  `impl Rt { }` in another module of the same crate is ordinary Rust -- an
  inherent impl may live anywhere in the defining crate -- so the methods
  keep being methods and every call site in the runtime is untouched. What
  does change is VISIBILITY: a method that is module-private is invisible
  from `map.rs` once it no longer lives there, so the vocabulary spells the
  default as `pub(crate)`."
  [ctx forms]
  (let [ns-name (second (ns-form-of forms))
        self (last (str/split (str ns-name) #"\."))
        recv (receiver-tag forms)
        ty (when recv (get-in (kin/tag ctx recv) [:types :rust]))
        siblings (remove #{self} (source-modules))]
    (module-file
     ctx ns-name "//"
     (str "#![allow(unused_imports)]\n"
          "use crate::gc::InternTable;\n"
          "use crate::hash;\n"
          "use crate::map::*;\n"
          "use crate::mem::Addr;\n"
          "use crate::obj::*;\n"
          "use crate::rt::Rt;\n"
          "use crate::strs::INTERN_MAX;\n"
          "use crate::value::{Value, FALSE, NIL, NOT_FOUND, TRUE};\n"
          ;; Only the two free-function modules export anything a sibling
          ;; calls unqualified; the rest are methods, and a method needs no
          ;; import in Rust at all.
          (str/join (for [s ["hash" "pike"] :when (not= s self)]
                      (str "use crate::" generated-root "::flint::rt::" s "::*;\n"))))
     (fn [c]
       (if ty
         (do (kin/emit! c "impl " ty " {\n")
             (kin/scoped c {:key :impl :value ty :indent 1}
                         (fn [in] (emit-body in (body-of forms))))
             (kin/emit! c "}\n"))
         (emit-body c (body-of forms)))))))

(defn java-emit
  "A Java CLASS in package `flint.rt`, mirroring the namespace.

  THE PACKAGE HAS TO MOVE, and it is the collision that forces it. Five of
  the sources are named after the file they used to be spliced into --
  `eq`, `hash`, `interns`, `pike`, `seqs` -- so a generated `Eq` in
  `com.flint.rt` would be a second `com.flint.rt.Eq`, which is not a
  compile error to be worked around but the same name meaning two things.
  Decision I calls the generated subtree PARALLEL to the human source for
  exactly this reason, and a parallel subtree in the same package is not
  parallel.

  The cost is stated rather than discovered: cross-package means every
  generated method is `public`, and the seven package-private constants
  `Maps.java` keeps for its own node layout have to widen to match."
  [ctx forms]
  (let [ns-name (second (ns-form-of forms))
        self (last (str/split (str ns-name) #"\."))
        pkg (str/join "." (butlast (str/split (str ns-name) #"\.")))
        cls (pascal self)
        siblings (remove #{self} (source-modules))]
    (module-file
     ctx ns-name "//"
     (str "package " pkg ";\n\n"
          "import com.flint.rt.*;\n"
          "import static com.flint.rt.Obj.*;\n"
          ;; The hand-written classes whose constants the generated code
          ;; reads bare: the node layout from Maps, `CAT_*` from Eq,
          ;; `LS_*` from Seqs.
          "import static com.flint.rt.Maps.*;\n"
          "import static com.flint.rt.Eq.*;\n"
          "import static com.flint.rt.Seqs.*;\n"
          (str/join (for [sib siblings]
                      (str "import static " pkg "." (pascal sib) ".*;\n"))))
     (fn [c]
       (kin/emit! c "public final class " cls " {\n")
       (kin/scoped c {:key :class :value cls :indent 1}
                   (fn [in] (emit-body in (body-of forms))))
       (kin/emit! c "}\n")))))

(defn csharp-emit
  "A C# static class in namespace `flint.rt`, mirroring the namespace.

  Same collision and the same answer as Java's: a generated `Eq` in
  `Flint.Rt` would be a second `Flint.Rt.Eq`. The namespace is spelled in
  the source's own case rather than PascalCased, which is not C#'s house
  style and is the point -- `flint.rt` and `Flint.Rt` are two namespaces, and
  a reader who sees the lower-case one knows immediately which tree the code
  came from.

  C# is the cheaper of the two ports: `internal` is assembly-wide, so
  nothing has to become `public` merely to cross a namespace."
  [ctx forms]
  (let [ns-name (second (ns-form-of forms))
        self (last (str/split (str ns-name) #"\."))
        ns-part (str/join "." (butlast (str/split (str ns-name) #"\.")))
        cls (pascal self)
        siblings (remove #{self} (source-modules))]
    (module-file
     ctx ns-name "//"
     (str "namespace " ns-part ";\n\n"
          "using System.Numerics;\n"
          "using Flint.Rt;\n"
          "using static Flint.Rt.Obj;\n"
          "using static Flint.Rt.Maps;\n"
          "using static Flint.Rt.Eq;\n"
          "using static Flint.Rt.Seqs;\n"
          (str/join (for [sib siblings]
                      (str "using static " ns-part "." (pascal sib) ";\n"))))
     (fn [c]
       (kin/emit! c "public static class " cls " {\n")
       (kin/scoped c {:key :class :value cls :indent 1}
                   (fn [in] (emit-body in (body-of forms))))
       (kin/emit! c "}\n")))))

(def module-targets
  "The three again, each owning a whole file.

  `:path` is `kin.target/module-path` and nothing else -- the namespace
  mirrored onto directories, which is one shape for all three and the whole
  of decision I. `unit`, `rust-file` and `deep-rust` all disappear with the
  regions: a namespace names its own file, so there is nothing left to look
  up."
  {:rust (merge
          target/rust
          {:vfs (vfs/disk-vfs (:rust module-roots))
           :emit rust-emit
           ;; `unsigned.kin` SHIPS NOWHERE, and that is not an omission: it
           ;; exists to pin what the unsigned forms mean in three languages,
           ;; and `verify` is the whole of its job. Named here rather than
           ;; in a table, because it is the only one and a table of one is a
           ;; sidecar waiting to grow.
           :path (fn [ns-name]
                   (when-not (= 'flint.rt.unsigned ns-name)
                     (target/module-path generated-root ns-name {:ext "rs"})))})
   :java (merge
          target/java
          {:vfs (vfs/disk-vfs (:java module-roots))
           :emit java-emit
           :path (fn [ns-name]
                   (when-not (= 'flint.rt.unsigned ns-name)
                     (target/module-path generated-root ns-name
                                         {:ext "java" :capitalise? true})))})
   :csharp (merge
            target/csharp
            {:vfs (vfs/disk-vfs (:csharp module-roots))
             :emit csharp-emit
             :path (fn [ns-name]
                     (when-not (= 'flint.rt.unsigned ns-name)
                       (target/module-path generated-root ns-name
                                           {:ext "cs" :capitalise? true})))})})
