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

  ## There is no table any more

  There used to be one. Nine sources all wrote into `map.rs`, `Maps.java`
  and `Maps.cs`, because the port went function by function rather than
  because the runtime wanted nine namespaces -- and a namespace-to-path
  function CAN be many to one, but only by carrying a lookup, which is the
  sidecar moved rather than removed.

  Each source is its own module now, so `:path` is `module-path` and nothing
  else: `flint.rt.champ` names `kgen/rt/champ.rs` and there is
  nothing left to look up. `unit`, `rust-file` and `deep-rust` went with the
  regions."
  (:require [clojure.string :as str]
            [kin.lang :as lang]
            [kin]
            [kin.vfs :as vfs]
            [kin.lang]
            [kin.project :as kp]
            [kin.target :as target]))

(defn- pascal [s] (str/join (mapv str/capitalize (str/split (str s) #"-"))))

;; ---------------------------------------------------------------------------
;; WHOLE MODULES, under each target's own generated root.
;;
;; Each target owns a whole file, which is what decision G says an `:emit`
;; means. There is no splicing left anywhere: kin writes files it creates and
;; never touches one somebody else wrote.

(def generated-root
  "The generated subtree, PER TARGET, in that language's own convention.

  It was one string -- `kingen` -- for all three, on the reasoning that Rust
  mirrors module paths onto directories exactly as Java mirrors a package and
  C# a namespace, so the shape could be shared. The shape is shared; the ROOT
  is not, because what a root looks like is exactly where the three languages
  differ:

      rust     kgen/rt/seqs.rs                     crate::kgen::rt::seqs
      java     com/_3sln/flint/kgen/rt/Seqs.java   com._3sln.flint.kgen.rt
      csharp   _3sln/Flint/Kgen/Rt/Seqs.cs         _3sln.Flint.Kgen.Rt

  Java is reverse-DNS and lower case; C# is PascalCase and does not use a
  reverse-DNS prefix at all; Rust has neither, because the CRATE is already
  the root and a second one inside it would say `flint` twice.

  `_3sln` rather than `3sln`: an identifier cannot begin with a digit in
  either Java or C#, and both compilers refuse it outright -- `javac` reports
  `';' expected` and `csc` reports CS1514. The leading underscore is the
  ordinary escape.

  The leading `flint` of a namespace is DROPPED, because every root already
  says it. `flint.rt.seqs` is `...kgen.rt.Seqs` and not `...kgen.flint.rt.Seqs`."
  {:rust ["kgen"]
   :java ["com" "_3sln" "flint" "kgen"]
   :csharp ["_3sln" "Flint" "Kgen"]})

(def ^:private ns-tail
  "A namespace's segments with the leading `flint` dropped and the last
  segment split off: `flint.rt.seqs` -> [[\"rt\"] \"seqs\"]."
  (memoize
   (fn [ns-name]
     (let [segs (str/split (str ns-name) #"\.")
           segs (if (= "flint" (first segs)) (rest segs) segs)]
       [(vec (butlast segs)) (last segs)]))))

(defn- generated-path
  "Where a namespace's file goes for `target`, as path segments.

  Not `kin.target/module-path`, which takes ONE root and one casing for all
  three. That was true while the root was one string; it stopped being true
  when each language got its own convention, and C# capitalises the
  DIRECTORIES as well as the file."
  [target ns-name {:keys [ext capitalise?]}]
  (let [[dirs tail] (ns-tail ns-name)
        dirs (if (= :csharp target) (mapv pascal dirs) dirs)
        file (str (if capitalise? (pascal tail) tail) "." ext)]
    (str/join "/" (concat (generated-root target) dirs [file]))))

(defn- generated-ns
  "The package or namespace a generated module declares, which MIRRORS the
  directory it is written to -- that is the whole rule, in every target."
  [target ns-name]
  (let [[dirs _] (ns-tail ns-name)
        dirs (if (= :csharp target) (mapv pascal dirs) dirs)]
    (str/join "." (concat (generated-root target) dirs))))

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

(def ships-nowhere
  "Sources that generate and are written NOWHERE.

  `unsigned.kin` exists to pin what the unsigned forms mean in three
  languages; `kin/scripts/verify` is the whole of its job and no runtime
  wants a file of it. `:path` answering nil is how a source says so.

  Said ONCE, because it is two answers to the same question: nothing writes
  it, and nothing can import it either. The first version of this had the
  name in the three `:path` functions and not in the sibling list, so every
  generated Java file imported a class that does not exist."
  '#{flint.rt.unsigned})

(defn- read-forms
  "Every top-level form of a kin source, as data."
  [text]
  (let [r (java.io.PushbackReader. (java.io.StringReader. text))]
    (loop [out []]
      (let [f (read {:eof ::done :read-cond :allow} r)]
        (if (= ::done f) out (recur (conj out f)))))))

(def vocabularies
  "The vocabularies this project speaks.

  HERE rather than in `flint.impl.project`, which is the natural home, because
  `defines-symbol` below has to ask kin what a source declares and that needs
  a project value -- and `project` requires THIS file. Stating the list twice
  to break the cycle would be two statements of one fact, which is the defect
  `defines-symbol` had in the first place."
  ;; TWO, not three. `flint.impl.host` was a hand-written table standing in
  ;; for "the runtime kin did not write", and its own docstring said it was
  ;; expected to shrink to nothing. It had: both forms it still declared --
  ;; `bn-new` and `cn-copy-set-val` -- are `^:pub` in `kin/collnode.kin` and
  ;; generated, both callers already require them from there, and no source
  ;; named the namespace at all. It was describing functions that had moved
  ;; out from under it.
  '[flint.impl.rt flint.impl.hash])

(declare targets)

(def ^:private asking-project
  "A project value good enough to ASK what a source declares, and nothing
  more. `declared-names` resolves no requires -- that is what makes it
  answerable from inside the emit of the tree being asked about -- so this
  needs the vocabularies and a target to pick spellings from, and no sources."
  (delay (kp/load-project {:vocabularies vocabularies
                           :targets targets
                           :target-order [:rust :java :csharp]})))

(def ^:private defines-symbol
  "Which module defines each name, read from the SOURCES.

  A generated module calls its siblings by their bare names -- `bnDatamap`
  comes from `champ.kin` and is called from `assoc.kin` -- so Java and C#
  need a static import naming the sibling. Importing them ALL is what this
  did first, and it does not work: `mask` is a CHAMP bit helper in
  `com.flint.rt.Maps` and the intern table's slot mask in
  `flint.rt.Interns`, and two on-demand static imports offering one name
  make it ambiguous rather than making it resolve.

  So the imports are DERIVED. Each source is asked for the names it defines,
  and a module imports exactly the siblings whose names it mentions. That is
  a dependency, computed from the sources, rather than a list anyone
  maintains.

  ASKED OF KIN, not matched by head. This used to keep its own set --
  `#{'defn 'defconst}` -- and read `(second form)` out of anything matching.
  That is a copy of the vocabulary kept where the vocabulary cannot see it,
  and `defdata` broke it twice over: the head was not in the set, and the
  names it defines are its `:accessors` keys rather than `(second form)`. The
  symptom was a module that referred ONLY an accessor getting no import and
  failing to compile, while a module that also referred a `defconst` compiled
  by accident -- the constant pulled the import in.

  `kp/declared-names` runs the declare pass and reports what registered, so a
  declaration form added tomorrow is covered the day it is written. It
  resolves no requires, which is what makes it askable from inside the emit
  of the very tree being asked about."
  (delay
    (into {}
          (for [f (vfs/listing (vfs/disk-vfs "kin") "*.kin")
                :let [module (str/replace f #"\.kin$" "")
                      text (vfs/-read (vfs/disk-vfs "kin") f)]
                :when (not (contains? ships-nowhere
                                      (symbol (str "flint.rt." module))))
                :let [d (kp/declared-names @asking-project text f)]
                sym (concat (:forms d) (:names d))]
            [sym module]))))

(defn- mentioned-symbols
  "Every symbol appearing anywhere in `forms`."
  [forms]
  (let [out (volatile! #{})]
    ((fn walk [x]
       (cond (symbol? x) (vswap! out conj x)
             (coll? x) (run! walk x)))
     forms)
    @out))

(defn- siblings-used
  "The sibling modules whose names this source actually mentions."
  [forms self]
  (let [owns @defines-symbol]
    (->> (mentioned-symbols forms)
         (keep owns)
         (remove #{self})
         distinct
         sort)))

(defn resolve-path
  "A vfs-relative path, as a path from the repository root.

  Named ONCE and used twice: to build the disk vfs each target writes
  through, and to turn a vfs-relative path back into something `cmp` can
  read. A path kin answers is relative to a vfs, because that is what a path
  means once I/O goes through a protocol -- so resolving one against a real
  directory is OUR job, and doing it from the same map that built the vfs is
  what keeps the two from drifting."
  [target path]
  (str (get module-roots target) "/" path))

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
        ty (when recv (get-in (kin/tag ctx recv) [:types :rust]))]
    (module-file
     ctx ns-name "//"
     ;; ONE PRELUDE FOR EVERY MODULE, rather than a per-source set derived
     ;; from what each one happens to touch. The alternative is `need!` --
     ;; every vocabulary entry declaring its imports as data, which is what
     ;; `examples/go` does -- and it is the better answer for a vocabulary
     ;; being written now. Retrofitting it means annotating several hundred
     ;; existing entries. `allow(unused_imports)` is what that costs, and it
     ;; is stated here rather than discovered.
     ;;
     ;; Java and C# do NOT do this: their sibling imports are derived, because
     ;; there they had to be -- see `defines-symbol`. Rust needs no equivalent
     ;; for methods, which is most of what is generated, and the two
     ;; free-function modules are named below.
     (str "#![allow(unused_imports)]\n"
          "use crate::gc::InternTable;\n"
          "use crate::hash;\n"
          "use crate::eq::*;\n"
          "use crate::map::*;\n"
          "use crate::mem::Addr;\n"
          "use crate::obj::*;\n"
          "use crate::rt::Rt;\n"
          "use crate::seqs::*;\n"
          "use crate::strs::INTERN_MAX;\n"
          ;; The VECTOR's constants -- `BITS`, `WIDTH`, `MASK` and the slot
          ;; names -- which are module-level in Rust and class-level on the
          ;; ports. `vecread` is the first generated source to name one.
          "use crate::vector::*;\n"
          "use crate::value::{Value, FALSE, NIL, NOT_FOUND, TRUE};\n"
          ;; The modules whose exports a sibling names UNQUALIFIED. Three are
          ;; free-function modules; the rest of the generated tree is methods,
          ;; and a method needs no import in Rust at all.
          ;;
          ;; THIS LIST IS HAND-KEPT AND JAVA'S IS NOT, which is a trap worth
          ;; naming: a new free-function source compiles on both ports and
          ;; fails only on Rust, with "cannot find function" at the CALLER.
          ;; `hamt` was the third and cost exactly that.
          ;;
          ;; `casetable` is the third and a different kind: a `defdata` emits
          ;; module-level `static`s and `const`s, which a sibling reaches the
          ;; same way it reaches a free function. `defn`'s cross-unit rule
          ;; qualifies for Java and C# and does nothing for Rust -- right for
          ;; an inherent `impl` method, which is what it was written for, and
          ;; not a rule about data. An import is what Rust wants instead.
          (str/join (for [s ["hash" "hashtext" "pike" "casetable" "hamt"] :when (not= s self)]
                      (str "use crate::" (str/join "::" (:rust generated-root))
                           "::" (str/join "::" (first (ns-tail ns-name)))
                           "::" s "::*;\n"))))
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
        pkg (generated-ns :java ns-name)
        cls (pascal self)
        siblings (siblings-used forms self)
        ;; CS0542: A C# MEMBER CANNOT SHARE ITS ENCLOSING TYPE'S NAME. This
        ;; convention makes the collision reachable -- the class is the
        ;; namespace's last segment pascalised, so `flint.rt.describe` holding
        ;; a `describe` emits `static class Describe { string Describe(...) }`.
        ;; Rust and Java accept it; only the CLR refuses, and it refuses in a
        ;; GENERATED file, pointing at a line nobody wrote, during a build that
        ;; runs long after the source was edited. Both names are known right
        ;; here, so this says it in terms of the source instead.
        _ (doseq [f (body-of forms)
                  :when (and (seq? f) (= 'defn (first f)))
                  :let [nm (pascal (second f))]
                  :when (= nm cls)]
            (throw (ex-info
                    (str "kin/flint: C# would emit `" nm "` into a class also"
                         " called `" cls "`, and a member cannot share its"
                         " enclosing type's name (CS0542). The class name is the"
                         " namespace's last segment, so rename the NAMESPACE"
                         " `" ns-name "` or the function `" (second f) "`."
                         " Rust and Java accept this, so nothing else will"
                         " tell you.")
                    {:function (second f) :class cls :ns ns-name :target :csharp})))]
    (module-file
     ctx ns-name "//"
     (str "package " pkg ";\n\n"
          "import com.flint.rt.*;\n"
          "import static com.flint.rt.Obj.*;\n"
          ;; The hand-written classes whose constants the generated code
          ;; reads bare: the node layout from Maps, `CAT_*` from Eq,
          ;; `LS_*` from Seqs, the trie shape and slot names from Vec.
          "import static com.flint.rt.Maps.*;\n"
          "import static com.flint.rt.Eq.*;\n"
          "import static com.flint.rt.Seqs.*;\n"
          "import static com.flint.rt.Vec.*;\n"
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
        ns-part (generated-ns :csharp ns-name)
        cls (pascal self)
        siblings (siblings-used forms self)]
    (module-file
     ctx ns-name "//"
     (str "namespace " ns-part ";\n\n"
          "using System.Numerics;\n"
          ;; `global::`, and it is not decoration. The generated namespace is
          ;; `_3sln.Flint.Kgen.Rt`, so a bare `Flint.Rt.Obj` resolves against
          ;; the ENCLOSING namespaces first and finds `_3sln.Flint`, then looks
          ;; for an `Rt` inside it and fails: CS0234, "the type or namespace
          ;; name 'Rt' does not exist in the namespace '_3sln.Flint'".
          ;;
          ;; `global::` is what C# provides for exactly this, and it is why the
          ;; alias below is needed too.
          "using global::Flint.Rt;\n"
          "using static global::Flint.Rt.Obj;\n"
          "using static global::Flint.Rt.Maps;\n"
          "using static global::Flint.Rt.Eq;\n"
          "using static global::Flint.Rt.Seqs;\n"
          "using static global::Flint.Rt.Vec;\n"
          ;; THE RECEIVER'S TYPE, aliased. The last namespace segment is `Rt`
          ;; -- it mirrors the `rt` directory -- and the runtime's main class
          ;; is also `Rt`, so the simple name binds to the enclosing NAMESPACE
          ;; and every signature reads "'Rt' is a namespace but is used like a
          ;; type" (CS0118). The alias wins over the enclosing namespace;
          ;; verified, because C# refuses an alias that collides with a type in
          ;; the same namespace (CS0576) and it was not obvious this differs.
          "using Rt = global::Flint.Rt.Rt;\n"
          (str/join (for [sib siblings]
                      (str "using static global::" ns-part "." (pascal sib) ";\n"))))
     (fn [c]
       (kin/emit! c "public static class " cls " {\n")
       (kin/scoped c {:key :class :value cls :indent 1}
                   (fn [in] (emit-body in (body-of forms))))
       (kin/emit! c "}\n")))))

(def targets
  "The three again, each owning a whole file.

  `:path` is `kin.target/module-path` and nothing else -- the namespace
  mirrored onto directories, which is one shape for all three and the whole
  of decision I. `unit`, `rust-file` and `deep-rust` all disappear with the
  regions: a namespace names its own file, so there is nothing left to look
  up."
  {:rust (merge
          target/rust
          {:vfs (vfs/disk-vfs (:rust module-roots))
           ;; WHAT A HOST ANNOTATION MEANS HERE. kin reads the marker and the
           ;; name and hands the payload back untouched -- the payload's shape
           ;; is this project's invention, and this is where it is invented.
           ;;
           ;; It is the smallest thing that can say a call: the template this
           ;; target fills, which is exactly what a `core/call` entry held when
           ;; the same fact lived in `flint.impl.rt`. Moving it here moves it
           ;; NEXT TO THE FUNCTION IT NAMES, which is the whole point: a
           ;; `runtime/src/vector.rs` that renames `vec_count` now has the
           ;; declaration in front of it rather than in another repository's
           ;; table.
           :link (fn [data _ _]
                   {:link-fn (lang/call {:rust (:template data)})})
           ;; WHICH EMITTER RENDERS A `defdata`, looked up by the qualified
           ;; symbol its declaration names. An ordinary target key, read back
           ;; with `(get-in ctx [:targets (:target ctx) :data-emitters])` --
           ;; the same mechanism as `:indent-unit` and `:local-name`, and the
           ;; reason `defdata` needed nothing new in kin core.
           ;;
           ;; There is deliberately NO default: a fallback cannot know what an
           ;; accessor MEANS, so a table names the representation it chose.
           :data-emitters {'kin.lang/flat-array kin.lang/flat-array}
           :emit rust-emit
           ;; `unsigned.kin` SHIPS NOWHERE, and that is not an omission: it
           ;; exists to pin what the unsigned forms mean in three languages,
           ;; and `verify` is the whole of its job. Named here rather than
           ;; in a table, because it is the only one and a table of one is a
           ;; sidecar waiting to grow.
           :path (fn [ns-name]
                   (when-not (contains? ships-nowhere ns-name)
                     (generated-path :rust ns-name {:ext "rs"})))})
   :java (merge
          target/java
          {:vfs (vfs/disk-vfs (:java module-roots))
           ;; WHAT A HOST ANNOTATION MEANS HERE. kin reads the marker and the
           ;; name and hands the payload back untouched -- the payload's shape
           ;; is this project's invention, and this is where it is invented.
           ;;
           ;; It is the smallest thing that can say a call: the template this
           ;; target fills, which is exactly what a `core/call` entry held when
           ;; the same fact lived in `flint.impl.rt`. Moving it here moves it
           ;; NEXT TO THE FUNCTION IT NAMES, which is the whole point: a
           ;; `runtime/src/vector.rs` that renames `vec_count` now has the
           ;; declaration in front of it rather than in another repository's
           ;; table.
           :link (fn [data _ _]
                   {:link-fn (lang/call {:java (:template data)})})
           ;; WHICH EMITTER RENDERS A `defdata`, looked up by the qualified
           ;; symbol its declaration names. An ordinary target key, read back
           ;; with `(get-in ctx [:targets (:target ctx) :data-emitters])` --
           ;; the same mechanism as `:indent-unit` and `:local-name`, and the
           ;; reason `defdata` needed nothing new in kin core.
           ;;
           ;; There is deliberately NO default: a fallback cannot know what an
           ;; accessor MEANS, so a table names the representation it chose.
           :data-emitters {'kin.lang/flat-array kin.lang/flat-array}
           :emit java-emit
           :path (fn [ns-name]
                   (when-not (contains? ships-nowhere ns-name)
                     (generated-path :java ns-name
                                     {:ext "java" :capitalise? true})))})
   :csharp (merge
            target/csharp
            {:vfs (vfs/disk-vfs (:csharp module-roots))
           ;; WHAT A HOST ANNOTATION MEANS HERE. kin reads the marker and the
           ;; name and hands the payload back untouched -- the payload's shape
           ;; is this project's invention, and this is where it is invented.
           ;;
           ;; It is the smallest thing that can say a call: the template this
           ;; target fills, which is exactly what a `core/call` entry held when
           ;; the same fact lived in `flint.impl.rt`. Moving it here moves it
           ;; NEXT TO THE FUNCTION IT NAMES, which is the whole point: a
           ;; `runtime/src/vector.rs` that renames `vec_count` now has the
           ;; declaration in front of it rather than in another repository's
           ;; table.
           :link (fn [data _ _]
                   {:link-fn (lang/call {:csharp (:template data)})})
           ;; WHICH EMITTER RENDERS A `defdata`, looked up by the qualified
           ;; symbol its declaration names. An ordinary target key, read back
           ;; with `(get-in ctx [:targets (:target ctx) :data-emitters])` --
           ;; the same mechanism as `:indent-unit` and `:local-name`, and the
           ;; reason `defdata` needed nothing new in kin core.
           ;;
           ;; There is deliberately NO default: a fallback cannot know what an
           ;; accessor MEANS, so a table names the representation it chose.
           :data-emitters {'kin.lang/flat-array kin.lang/flat-array}
             :emit csharp-emit
             :path (fn [ns-name]
                     (when-not (contains? ships-nowhere ns-name)
                       (generated-path :csharp ns-name
                                       {:ext "cs" :capitalise? true})))})})
