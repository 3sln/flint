(ns flint.bundle
  "Splice a program image into a PREBUILT wasm module, producing a standalone
  one.

  This is the half of `flint.link/compose` that is not linking. Linking merges
  relocatable objects and is `wasm-ld`, a native tool; everything after it --
  appending the image as a data segment, pointing the descriptor at it,
  renaming the entry -- is byte manipulation on a finished module, and there is
  no reason it has to happen on a developer machine.

  So a compiler that ships as wasm carries a prebuilt runtime module and
  splices into it. The output is an ordinary flint module: instantiate it, call
  `main`. Nothing loads an image at run time and nothing needs a loader.

  The image's native slots have to name THIS module's table, which is why
  `slots` is a parameter: it is generated beside the runtime module it
  describes (`dist/slots.json`), because a slot is a property of the artifact
  and not of the compiler."
  (:require [flint.wasm :as w]
            [flint.image :as img]
            [flint.aot :as aot]
            [flint.modmeta :as modmeta]
            [clojure.string :as str]))

(def aot-helpers
  "The runtime functions compiled code calls. A module that does not export
  every one of them was not built with `bin/build-units --aot` and cannot run a
  compiled arity."
  {:native "aot_native" :return "aot_return" :bail "aot_bail" :tick "aot_tick"
   :call "aot_call" :int-binop "aot_int_binop" :type-p "aot_type_p"})

(defn compile-arities
  "Emit a wasm function for every arity the emitter can take, append them to
  `m`, and stamp each one's table slot into the image builder.

  Lives here rather than in `flint.link` because it needs NO linker. Compiled
  arities are appended to a module that is already linked -- wasm cannot add a
  function to a module that exists, which is why this happens at build time,
  and appending is byte manipulation, which is why it does not need `wasm-ld`.
  `flint.link` wraps this with the bisection knobs, which read the environment
  and so cannot compile to wasm.

  `select` decides which arities to compile, by ordinal; nil means all."
  [m b exp select]
  (let [helpers (reduce-kv (fn [acc k nm]
                             (assoc acc k
                                    (or (get-in exp [nm :index])
                                        (throw (ex-info
                                                (str "this runtime cannot run compiled "
                                                     "arities: build the units with "
                                                     "`bin/build-units --aot`")
                                                {:helper nm})))))
                           {} aot-helpers)
        code (vec (:code @b))
        ;; (fn-index, arity-index) in a fixed order, so the table this writes
        ;; and the indices it stores into the arities cannot drift apart.
        slots-of (for [[fi f] (map-indexed vector (:fns @b))
                       [ai a] (map-indexed vector (:arities f))]
                   [fi ai a])
        results (mapv (fn [k [fi ai a]]
                        [fi ai (when (or (nil? select) (select k))
                                 (aot/compile-arity code (:off a) (:len a) helpers))])
                      (range) slots-of)
        ok (filterv (fn [[_ _ r]] (some? r)) results)
        [m type-idx] (w/add-type m [0x7F 0x7F 0x7F 0x7F 0x7F] [])
        [m first-fn] (w/append-funcs m type-idx (mapv (fn [[_ _ r]] (:body r)) ok))
        tbase (or (w/table-min m) 1)
        [m _] (w/append-elem m tbase (vec (range first-fn (+ first-fn (count ok)))))
        table (mapv (fn [k [_ _ r]] {:slot (+ tbase k) :depth (:depth r) :points (:points r)})
                    (range) ok)
        index (into {} (map-indexed (fn [k [fi ai _]] [[fi ai] k]) ok))]
    ;; Stamp each compiled arity with its index into the table above.
    (vswap! b update :fns
            (fn [fns]
              (vec (map-indexed
                    (fn [fi f]
                      (update f :arities
                              (fn [as]
                                (vec (map-indexed
                                      (fn [ai a] (assoc a :aot (get index [fi ai] 0xFFFFFFFF)))
                                      as)))))
                    fns))))
    (vswap! b assoc :aot table)
    {:module m :compiled (count ok) :total (count slots-of)}))

(defn- global-addr [m exp name]
  (let [g (get exp name)]
    (or (and g (w/global-i32-init m (:index g)))
        (throw (ex-info (str "the base module does not export " name
                             ", so it is not a flint runtime module")
                        {:want name})))))

(defn into-module
  "`base` is a prebuilt flint runtime module (bytes), `image` the program image
  (bytes). Returns the bytes of a module that runs it.

  `opts` may carry `:entry`, `:version` and `:aot?`, which only affect the
  metadata section (`doc/decisions/0020`)."
  [base image opts]
  (let [m (w/parse base)
        exp (w/exports m)
        heap-base (global-addr m exp "__heap_base")
        ;; Aligned past the linker's own data, exactly as `compose` does. The
        ;; runtime's `heap_start` then walks every descriptor and starts the
        ;; arena past all of them, so a spliced segment is not eaten.
        img-addr (bit-and (+ heap-base 15) (bit-not 15))
        desc-addr (global-addr m exp "FLINT_IMAGE_DESC")
        m (w/append-data m img-addr image)
        ;; A later segment wins, so the descriptor is overwritten in place
        ;; rather than surgically edited inside the linker's data.
        m (w/append-data m desc-addr
                         (w/->bytes [(img/u32 img-addr) (img/u32 (count image))]))
        ;; The runtime module was built as a LOADER, so it carries a builtin
        ;; registry and `flint_load_image`. Neither is wrong in the output --
        ;; the module simply also happens to be able to load another image --
        ;; but the entry has to become `main`, which is what a host calls.
        m (if (get exp "main") m (w/rename-export m "flint_main" "main"))
        all-exported (vec (keys (w/exports m)))
        builtin? (fn [n] (str/starts-with? n "flint_b_"))
        exported (vec (remove builtin? all-exported))
        meta (modmeta/describe
              {:abi {:runtime 1 :value 1 :image 1}
               :memory :unshared
               :gas-in-aot (boolean (:aot? opts))
               :version (or (:version opts) "0.1.0")
               :entry (str (:entry opts))
               :exports exported
               :imports (vec (sort (map (fn [i] (str (:module i) "/" (:name i)))
                                        (w/imports m))))
               :units [{:name "flint.rt" :abi {:runtime 1 :value 1 :image 1}}]
               :builtins (count (filter builtin? all-exported))
               :features {:diagnostics (contains? (set exported) "collect_now")
                          :snapshots (contains? (set exported) "snapshot_export")
                          :capabilities (contains? (set exported) "flint_grant")
                          :loader (contains? (set exported) "flint_load_image")
                          :aot (boolean (:aot? opts))}})
        ;; Written last so it describes the module as it actually is, and
        ;; stripped first so a re-splice does not leave two.
        m (w/strip-custom m #{modmeta/section-name})
        m (w/add-custom m modmeta/section-name (w/utf8-bytes (pr-str meta)))]
    (w/emit m)))
