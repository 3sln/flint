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
  the sources are consolidated one per destination file. When `runtime.champ`
  and its eight neighbours become one `flint.rt.maps`, `unit` collapses to
  the namespace's own last segment and this map goes away."
  (:require [clojure.string :as str]
            [kin.vfs :as vfs]
            [kin.target :as target]))

(def unit
  "Namespace -> the destination unit it belongs to.

  INTERIM. See the namespace docstring: this is the sidecar in one file
  instead of sixteen, and it is deleted by the consolidation commit."
  '{runtime.assoc     maps
   runtime.champ     maps
     runtime.collnode  maps
    runtime.copies    maps
    runtime.dissoc    maps
    runtime.find      maps
    runtime.merge     maps
     runtime.nodeclass maps
    runtime.eq        eq
    runtime.eqalloc   eq
    runtime.hash      hash
    runtime.interns   interns
    runtime.pike      pike
    runtime.seqs      seqs
    ;; NO DESTINATION, and that is not an omission. `unsigned.kin` exists to
    ;; be VERIFIED -- it pins what the unsigned forms MEAN in three languages
    ;; -- and ships nowhere. `:path` answering nil is how a source says so.
    runtime.unsigned  nil})

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
