(ns flint.impl.targets
  "Where each target puts a namespace's generated code.

  kin computes a destination rather than reading one from a table: each
  target carries `:dest`, a root, and `:path`, a function from namespace to
  file under it. That replaced sixteen `<source>.targets` sidecars, three
  columns each, which were sixteen restatements of a rule nobody had written
  down.

  This file is CODE rather than `kin.edn` data for exactly one reason: a
  function cannot be written in EDN. `kin.edn` names this namespace and kin
  reads `targets` out of it.

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
            [kin.target :as target]))

(def unit
  "Namespace -> the destination unit it belongs to.

  INTERIM. See the namespace docstring: this is the sidecar in one file
  instead of sixteen, and it is deleted by the consolidation commit."
  '{runtime.champ     maps
    runtime.collassoc maps
    runtime.collnode  maps
    runtime.copies    maps
    runtime.dissoc    maps
    runtime.find      maps
    runtime.merge     maps
    runtime.nodeassoc maps
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

(def targets
  {:rust {:dest "runtime/src"
          :path (fn [ns-name] (some-> (unit-of ns-name) rust-file))
          :indent (fn [ns-name]
                    (if (contains? deep-rust (unit-of ns-name)) 4 0))}
   :java {:dest "runtimes/jvm/src/com/flint/rt"
          :path (fn [ns-name] (some-> (unit-of ns-name) pascal (str ".java")))
          ;; Every port region nests inside a class, so every one is indented.
          :indent 4}
   :csharp {:dest "runtimes/clr/src/rt"
            :path (fn [ns-name] (some-> (unit-of ns-name) pascal (str ".cs")))
            :indent 4}})
