(ns flint.link
  "Composes a module from units.

  Reachability decides what is linked (`DECISIONS.md#namespace-units`):
  only the units the entry point reaches are handed to `rust-lld`, and within a
  linked unit only the builtins the program actually calls are `--export`ed, so
  `--gc-sections` deletes the rest. The registry is then assembled *here*, after
  the link, by reading the module's export section and appending an element
  segment -- which is why nothing in the runtime holds a table of builtins."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [flint.wasm :as w]
            ;; Required rather than assumed: `flint.image/u32` is used below and
            ;; this namespace only ever loaded because `bin/flint` happened to
            ;; require it first. Anything else that reaches for the linker got a
            ;; resolution error from a file it did not touch.
            [flint.image]
            [flint.modmeta :as modmeta]
            [flint.aot :as aot]))

(def ^:private toolchain
  (str (System/getProperty "user.home") "/.rustup/toolchains/nightly-aarch64-apple-darwin"))

(defn lld-path []
  (str toolchain "/lib/rustlib/aarch64-apple-darwin/bin/rust-lld"))

(defn- sh! [args]
  (let [p (.exec (Runtime/getRuntime) (into-array String (map str args)))
        out (slurp (.getInputStream p))
        err (slurp (.getErrorStream p))
        code (.waitFor p)]
    (when-not (zero? code)
      ;; With the message, not just the name. A linker failure that prints only
      ;; the path of the linker is a bad welcome, and this file says so about a
      ;; smaller thing three lines up.
      (throw (ex-info (str "command failed: " (first args) "\n" err out)
                      {:code code :err err :out out})))
    {:out out :err err}))

(def current-abi
  "What this build of flint can link. A unit declaring anything else is refused
  by name and version rather than linked and left to crash at run time.

  :runtime  the builtin calling convention (extern C, (rt, base, argc) -> u64)
  :value    the NaN-boxing layout
  :image    the program image format"
  {:runtime 1 :value 1 :image 1})

(defn abi-problem
  "Why `u` cannot be linked, or nil."
  [u]
  (cond
    (not= 1 (:flint/unit u))
    (str "unit format version " (pr-str (:flint/unit u)) ", expected 1")
    :else
    (let [bad (for [[k want] current-abi
                    :let [got (get (:abi u) k)]
                    :when (not= got want)]
                (str (name k) " " (pr-str got) " (need " want ")"))]
      (when (seq bad) (str/join ", " bad)))))

(defn read-unit-file
  "Load one `<ns-path>.unit.edn`. Artifact and libs are relative to it."
  [f]
  (when (.exists f)
    (let [u (read-string (slurp f))]
      (assoc u :dir (str (.getParent f)) :manifest (str f)))))

(defn ns->path
  "flint.data.json -> flint/data/json, the same shape :src uses for source."
  [n]
  (-> (str n) (str/replace "-" "_") (str/replace "." "/")))

(defn resolve-unit
  "Find the unit providing namespace `n` on `path`. Earlier directories win, so
  a user-supplied unit shadows a built-in of the same name."
  [path n]
  (some (fn [d] (read-unit-file (io/file d (str (ns->path n) ".unit.edn")))) path))

(defn- unit-files [d]
  (when (.isDirectory (io/file d))
    (filter #(str/ends-with? (str %) ".unit.edn") (file-seq (io/file d)))))

(defn discover-units
  "Every unit on the search path, by name. Earlier directories win, which is
  what makes `units/` merely the default entry rather than a special case."
  [path]
  (reduce (fn [acc d]
            (reduce (fn [m f]
                      (let [u (read-unit-file f)]
                        (if (or (nil? u) (contains? m (:name u))) m (assoc m (:name u) u))))
                    acc (unit-files d)))
          {} path))

(defn check-abi!
  "Every unit on the path must be linkable by this flint. A directory named with
  `:wasm-ld` is an assertion that its units are for this runtime, so a stale one
  is refused by name and version here rather than linked and left to crash."
  [units]
  (doseq [[n u] (sort-by key units)]
    (when-let [why (abi-problem u)]
      (throw (ex-info (str "refusing unit " n " at " (:manifest u) ": " why)
                      {:unit n :manifest (:manifest u) :reason why})))))

(defn shadowed-units
  "Units on the path that lost to an earlier one of the same name. Reported
  rather than silently dropped, because a stale copy that never links is the
  kind of thing somebody spends an afternoon on."
  [path]
  (let [winners (volatile! {})]
    (vec (for [d path
               f (unit-files d)
               :let [u (read-unit-file f)]
               :when u
               :let [w (get @winners (:name u))]
               :when (do (when-not w (vswap! winners assoc (:name u) (:manifest u))) w)]
           (assoc u :by w)))))

(defn plan
  "Which units to link, and which of their symbols to export, given the builtin
  names the program actually imports."
  [units needed-builtins]
  (let [provider (into {} (for [[nm u] units
                                [b _] (:provides u)]
                            [b nm]))
        missing (remove provider needed-builtins)]
    (when (seq missing)
      (throw (ex-info "no unit provides these builtins" {:missing (vec missing)})))
    (let [used (distinct (map provider needed-builtins))
          ;; close over :requires
          closure (loop [seen #{} todo (vec used)]
                    (if-let [n (first todo)]
                      (if (seen n)
                        (recur seen (subvec (vec todo) 1))
                        (recur (conj seen n) (into (subvec (vec todo) 1) (:requires (units n)))))
                      seen))
          ;; flint.rt carries the interpreter itself, so it is always required.
          ;;
          ;; AND flint.conc, WHICH IS NEW. A sandbox is reached only through its
          ;; system port now (`DECISIONS.md#calls-are-ports`) -- a call in, a
          ;; request out, driving -- and the port machinery lives in this unit.
          ;; Linking it by reachability meant a program that opened no port had
          ;; no port, which used to be a saving and is now a module nothing can
          ;; call.
          ;;
          ;; The saving was real and is given up on purpose: `sdks/esm`
          ;; measured a trivial module at 300 801 bytes without the scheduler
          ;; and 335 320 with it.
          closure (conj closure 'flint.rt 'flint.conc)
          exports (into {} (for [b needed-builtins
                                 :let [u (units (provider b))]]
                             [b (get-in u [:provides b :symbol])]))]
      {:units (mapv units (sort-by str closure))
       :exports exports})))

(def abi-exports
  ;; The module's outside edge, and the roots --gc-sections keeps.
  ;;
  ;; PRODUCTION ONLY (DECISIONS.md#two-builds). Gas and the memory cap are here
  ;; because they are resource control rather than instrumentation, and
  ;; construe's gates depend on a deterministic instruction count -- `stat_steps`
  ;; is how a host reads it, so it survives stripping with them. Everything
  ;; diagnostic (`collect_now`, `set_gc_stress`, the heap statistics) is carried
  ;; by the runtime unit's own `:exports`, and only the diagnostics build of the
  ;; runtime declares them.
  ;; `flint_opaque_host_id` is what is left of the capability edge, and it is
  ;; the only part that was ever the runtime's business: given a value, say what
  ;; id it was ISSUED with. `flint_grant` and `flint_presented_capability` are
  ;; gone with the grant table -- the host projects an opaque value in by any
  ;; means it likes, the guest presents it with a request, and it crosses as a
  ;; sentinel carrying that id. Nothing here decides what any of it MEANS
  ;; (`DECISIONS.md#opaque-values`).
  ;; `flint_call` is GONE (`DECISIONS.md#calls-are-ports`). It was the second
  ;; way into a sandbox -- a named call, synchronous, no scheduler -- and one
  ;; way in is worth more than the saving it bought a module that could not
  ;; park. `out_ptr` and `out_len` stay because the PORT path reads outbound
  ;; bytes through them (`sdks/esm/src/guest.js:76`, `:466`).
  ;;
  ;; `arg_push` went with it, 2026-09-23. This comment used to say it "stays --
  ;; how a host writes an encoded value in", and that was measured and found
  ;; false: inbound bytes go through `flint_in_alloc`, `arg_push` had ZERO
  ;; callers in the tree, and the `static mut ARGS` behind it was written by
  ;; one export and read by nobody.
  ;;
  ;; `arg_alloc` is still here and its only remaining callers load an IMAGE
  ;; (`host/run.mjs:71`, `test/loader.clj`), which is `loader-exports` work --
  ;; a production module that cannot be handed an image has no user for it.
  ;; Moving it is a decision, not a cleanup, so it is written down rather than
  ;; done.
  ["arg_alloc" "out_ptr" "out_len"
   "image_desc_addr" "set_step_limit" "stat_steps" "set_memory_limit"
   "flint_opaque_host_id"])

(def loader-exports
  "A module built with `--loader` can be handed an image at run time. That needs
  the registry's address (so the linker can write it) and the entry point that
  reads it (`DECISIONS.md#construe-integration-bar`)."
  ["FLINT_BUILTIN_REGISTRY" "builtin_registry_addr" "flint_load_image"])

(defn unit-exports
  "Extra wasm exports a linked unit asks for. This is how a unit can widen the
  module's outside edge -- the concurrency unit's host-callback surface is the
  only user so far -- without every module paying for the wider edge."
  [units]
  (vec (mapcat :exports units)))

(defn link-objects
  "Run the linker. Returns the module bytes."
  [{:keys [units exports] :as p} sysroot out-path & [keep-names]]
  (let [objs (for [u units] (str (:dir u) "/" (:artifact u)))
        ;; Each unit carries its own dependency rlibs. A program that never
        ;; mentions XML never puts xmlparser on the link line at all, so
        ;; "not linked" is stronger than "linked and then gc-sectioned".
        unit-libs (for [u units
                        :let [d (io/file (:dir u) (or (:libs u) "no-libs"))]
                        :when (.isDirectory d)
                        f (.listFiles d)
                        :when (str/ends-with? (str f) ".rlib")]
                    (str f))
        rlibs (concat unit-libs
                      (filter #(str/ends-with? % ".rlib")
                              (map str (.listFiles (io/file sysroot)))))
        args (concat [(lld-path) "-flavor" "wasm" "--no-entry" "--gc-sections"
                      "--export-table"
                      ;; THE SHADOW STACK, sized and placed on purpose.
                      ;;
                      ;; wasm-ld's default is 64 KiB placed ABOVE the static
                      ;; data, and both halves of that default were wrong here.
                      ;;
                      ;; Too small: the interpreter recurses on the Rust side
                      ;; in proportion to the guest structure it is walking --
                      ;; about 305 bytes a literal element, measured -- so 64
                      ;; KiB ran out at a 178-element vector literal. That is
                      ;; not an exotic size; it is a lookup table.
                      ;;
                      ;; Placed above the data: an overflow then grows DOWN
                      ;; into the statics and scribbles them, and the program
                      ;; carries on with its own globals corrupted. That is how
                      ;; this presented for weeks -- a compile that stopped
                      ;; mid-analysis, a thread finished while 25 frames deep,
                      ;; a second control-plane thread booting because the
                      ;; `system_booted` flag had been overwritten. Nothing
                      ;; said "stack".
                      ;;
                      ;; `--stack-first` puts it at the bottom instead, so an
                      ;; overflow runs off address zero and TRAPS. Silent
                      ;; corruption becomes `memory access out of bounds` at
                      ;; the instruction that did it.
                      ;;
                      ;; 1 MiB carries a literal of about 3 400 elements. The
                      ;; cost is initial memory, not file size: 5 pages to 20,
                      ;; +960 KiB a module, the file unchanged. This number is
                      ;; a dial -- 256 KiB buys ~800 elements for +192 KiB --
                      ;; and the trade is written down in
                      ;; `DECISIONS.md#the-shadow-stack-is-not-a-default`, with
                      ;; the measurements in `doc/goals/kin-port.md`.
                      "-z" "stack-size=1048576" "--stack-first"
                      "--export=__heap_base" "--export=FLINT_IMAGE_DESC"]
                     (map #(str "--export=" %) abi-exports)
                     (map #(str "--export=" %) (if (:loader? p) loader-exports []))
                     (map #(str "--export=" %) (unit-exports units))
                     (map #(str "--export=" %) (vals exports))
                     (when keep-names ["--strip-debug"])
                     (when-not keep-names ["--strip-all"])
                     ["-o" out-path]
                     objs
                     rlibs)]
    (sh! args)
    (with-open [in (io/input-stream out-path)]
      (.readAllBytes in))))

(def ^:private AOT-HELPERS
  {:native "aot_native" :return "aot_return" :bail "aot_bail" :tick "aot_tick"
   :call "aot_call" :int-binop "aot_int_binop" :type-p "aot_type_p"})

(defn compile-aot
  "Emit a wasm function for every arity the emitter can take, put them in the
  table, and record the mapping in the image builder.

  What refuses compilation is reported rather than swallowed: an emitter that
  quietly skips half a program looks exactly like one that is not helping."
  [m b exp]
  (let [helpers (reduce-kv (fn [acc k nm]
                             (assoc acc k
                                    (or (get-in exp [nm :index])
                                        (throw (ex-info
                                                (str "this runtime cannot run compiled "
                                                     "arities: build the units with "
                                                     "`bin/build-units --aot`")
                                                {:helper nm})))))
                           {} AOT-HELPERS)
        code (vec (:code @b))
        ;; (fn-index, arity-index) in a fixed order, so the table this writes and
        ;; the indices it stores into the arities cannot drift apart.
        slots-of (for [[fi f] (map-indexed vector (:fns @b))
                       [ai a] (map-indexed vector (:arities f))]
                   [fi ai a])
        ;; A bisection handle. Compiling a PREFIX of the arities is what turns
        ;; "the module is wrong" into "arity N is wrong" -- the same
        ;; discriminator that closed the GC hunt, and for the same reason: a
        ;; whole-module symptom names nothing.
        limit (some-> (System/getenv "FLINT_AOT_LIMIT") parse-long)
        only (some-> (System/getenv "FLINT_AOT_ONLY") parse-long)
        from (or (some-> (System/getenv "FLINT_AOT_FROM") parse-long) 0)
        ;; An explicit SET, because a prefix bisection lies here: adding one more
        ;; arity shifts what else runs compiled, so "the prefix breaks at N" does
        ;; not mean "N is at fault" -- and it did not. Same trap the GC hunt hit
        ;; with collection #300.
        ;; Excluding a range is the more robust half of the bisection: the rest
        ;; of the program stays compiled, so what is being tested is "is this
        ;; range necessary" rather than "is this range sufficient".
        skip-from (some-> (System/getenv "FLINT_AOT_SKIP_FROM") parse-long)
        skip-to (some-> (System/getenv "FLINT_AOT_SKIP_TO") parse-long)
        pick (some->> (System/getenv "FLINT_AOT_PICK")
                      (#(clojure.string/split % #","))
                      (map parse-long) set)
        ;; Name the arity a bisection is pointing at. `FLINT_AOT_ONLY=106` says
        ;; WHICH one is at fault only if something prints what 106 is, and the
        ;; index is a position in this flattened list -- it is not derivable
        ;; from anything the module carries afterwards.
        name-k (some-> (System/getenv "FLINT_AOT_NAME") parse-long)
        ;; `FLINT_AOT_NAMES=reduce` lists every index whose function name
        ;; contains that text, which is how a bisection gets STARTED -- the
        ;; index of the arity you already suspect is otherwise unfindable.
        _ (when-let [pat (System/getenv "FLINT_AOT_NAMES")]
            (doseq [[k [fi ai a]] (map-indexed vector slots-of)
                    :let [nm (str (nth (:consts @b) (:name (nth (:fns @b) fi)) nil))]
                    :when (clojure.string/includes? nm pat)]
              (println (format "  aot arity %d = fn %d arity %d %s (argc %d)"
                               k fi ai nm (:argc a)))))
        _ (when name-k
            (doseq [[k [fi ai a]] (map-indexed vector slots-of)
                    :when (= k name-k)]
              (println (format "  aot arity %d = fn %d arity %d %s (off %d len %d argc %d)"
                               k fi ai
                               (pr-str (nth (:consts @b) (:name (nth (:fns @b) fi)) nil))
                               (:off a) (:len a) (:argc a)))))
        results (mapv (fn [k [fi ai a]]
                        [fi ai (when (and (or (nil? limit) (< k limit))
                                          (>= k from)
                                          (or (nil? pick) (pick k))
                                          (not (and skip-from skip-to
                                                    (>= k skip-from) (< k skip-to)))
                                          (or (nil? only) (= k only)))
                                 (aot/compile-arity code (:off a) (:len a) helpers
                                                    (some? (System/getenv "FLINT_AOT_CHUNK_ALL"))))])
                      (range) slots-of)
        ok (filterv (fn [[_ _ r]] (some? r)) results)
        [m type-idx] (w/add-type m [0x7F 0x7F 0x7F 0x7F 0x7F] [])
        [m first-fn] (w/append-funcs m type-idx (mapv (fn [[_ _ r]] (:body r)) ok))
        tbase (or (w/table-min m) 1)
        [m _] (w/append-elem m tbase (vec (range first-fn (+ first-fn (count ok)))))
        table (mapv (fn [k [_ _ r]] {:slot (+ tbase k) :depth (:depth r) :points (:points r)})
                    (range) ok)
        index (into {} (map-indexed (fn [k [fi ai _]] [[fi ai] k])) ok)]
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
    (when-let [want (some-> (System/getenv "FLINT_AOT_FN") parse-long)]
      (doseq [[fi f] (map-indexed vector (:fns @b))
              :when (= fi want)
              [ai a] (map-indexed vector (:arities f))]
        (println (format "  fn %d arity %d %s off %d len %d argc %d nlocals %d"
                         fi ai (pr-str (nth (:consts @b) (:name f) nil))
                         (:off a) (:len a) (:argc a) (:nlocals a)))
        (println (str "    " (pr-str (mapv (fn [x] (format "%02X" x))
                                           (subvec (vec code) (:off a) (+ (:off a) (:len a)))))))))
    (when (System/getenv "FLINT_AOT_DUMP")
      (doseq [[k [fi ai r]] (map-indexed vector ok)]
        (let [a (nth (:arities (nth (:fns @b) fi)) ai)]
          (println (format "  aot[%d] fn %d arity %d  off %d len %d  chunks %d depth %d  argc %d nlocals %d%s"
                           k fi ai (:off a) (:len a) (:chunks r) (:depth r)
                           (:argc a) (:nlocals a) (if (:variadic? a) " VARIADIC" "")))
          (println (str "        fn name const " (:name (nth (:fns @b) fi))
                        " = " (pr-str (nth (:consts @b) (:name (nth (:fns @b) fi)) nil))
                        "  natives " (pr-str (mapv :name (take 3 (:natives @b))))))
          (println (str "        " (pr-str (mapv (fn [x] (format "%02X" x))
                                                 (subvec (vec code) (:off a) (+ (:off a) (:len a))))))))))
    {:module m
     :compiled (count ok)
     :arities (count results)
     :refused (- (count results) (count ok))
     :chunks (reduce + 0 (map (fn [[_ _ r]] (:chunks r)) ok))
     :bytes (reduce + 0 (map (fn [[_ _ r]] (count (:body r))) ok))}))

(defn compose
  "Link, bind the reached builtins into the wasm table, splice the image in.
  `emit-image` is called with the resolved {builtin-name -> table-slot} map and
  must return the image bytes."
  [{:keys [unit-path sysroot needed-builtins emit-image out tmp keep-names builder aot?
           loader? entry-sym flint-version]}]
  (let [units (discover-units unit-path)
        _ (check-abi! units)
        p (plan units needed-builtins)
        raw (link-objects (assoc p :loader? loader?) sysroot (or tmp (str out ".tmp"))
                          keep-names)
        m (w/parse raw)
        exp (w/exports m)
        base (or (w/table-min m) 1)
        ;; Slot 0 of the table is reserved (a null funcref), so start above the
        ;; existing minimum, whatever the linker already put there.
        ordered (vec needed-builtins)
        slots (into {} (map-indexed (fn [i b] [b (+ base i)]) ordered))
        fidx (mapv (fn [b]
                     (let [sym (get-in p [:exports b])]
                       (or (get-in exp [sym :index])
                           (throw (ex-info "builtin was not exported by the link"
                                           {:builtin b :symbol sym})))))
                   ordered)
        [m _] (w/append-elem m base fidx)
        ;; The slot map, for anyone who has to point an image at THIS module's
        ;; table without linking. `bin/build-dist` writes it beside the module
        ;; it ships: a distributable compiler splices an image into a prebuilt
        ;; module rather than linking one, and the image's native slots have to
        ;; come from somewhere.
        _ (when-let [out (System/getenv "FLINT_SLOTS_OUT")]
            (spit out (str "{\n"
                           (str/join ",\n"
                                     (map (fn [e] (str "  \"" (key e) "\": " (val e)))
                                          (sort-by key slots)))
                           "\n}\n")))
        ;; Compiled arities go in AFTER the link, because only now are the
        ;; helper functions' indices known (DECISIONS.md#emit-wasm-instead-of-dispatch). The builder is
        ;; mutated before `emit-image` runs, so the table it writes is the one
        ;; these slots came from.
        aot-res (when (and aot? builder) (compile-aot m builder exp))
        m (or (:module aot-res) m)
        image (emit-image slots)
        heap-base (let [g (get exp "__heap_base")]
                    (or (w/global-i32-init m (:index g))
                        (throw (ex-info "no __heap_base" {}))))
        img-addr (bit-and (+ heap-base 15) (bit-not 15))
        desc-addr (let [g (get exp "FLINT_IMAGE_DESC")]
                    (or (w/global-i32-init m (:index g))
                        (throw (ex-info "no FLINT_IMAGE_DESC" {}))))
        m (w/append-data m img-addr image)
        ;; A second data segment overwrites the descriptor in place; later
        ;; segments win, so no byte surgery inside the linker's own data.
        ;; The builtin registry: `(slot, name-length, name)` for every builtin
        ;; this module carries, so an image compiled elsewhere can be re-pointed
        ;; at THIS module's table by name.
        m (if-not loader?
            m
            (let [g (get exp "FLINT_BUILTIN_REGISTRY")
                  addr (or (w/global-i32-init m (:index g))
                           (throw (ex-info "no FLINT_BUILTIN_REGISTRY" {})))
                  blob (w/->bytes
                        (for [[nm slot] (sort-by key slots)]
                          (let [b (w/utf8-bytes nm)]
                            [(flint.image/u32 slot) (flint.image/u32 (alength b)) b])))
                  at (bit-and (+ img-addr (count image) 15) (bit-not 15))]
              (-> m
                  (w/append-data at blob)
                  (w/append-data addr (w/->bytes [(flint.image/u32 at)
                                                  (flint.image/u32 (alength blob))])))))
        m (w/append-data m desc-addr
                         (w/->bytes [(flint.image/u32 img-addr)
                                     (flint.image/u32 (count image))]))
        m (w/strip-custom m (if keep-names #{"producers" "target_features"}
                                #{"producers" "target_features" "name"}))
        ;; What the module says about itself (`DECISIONS.md#module-metadata-and-shards`). Written
        ;; LAST, so it describes the module as it actually is -- exports and all
        ;; -- rather than what the link intended, and placed early in the byte
        ;; stream so a runner can read it without downloading the code section.
        ;; The ABI surface a runner drives, not every symbol the linker kept.
        ;; `flint_b_*` are builtin implementations reached through the table --
        ;; internal linkage detail, hundreds of them, and nothing a runner can
        ;; do with the names. They are counted rather than listed.
        all-exported (vec (keys (w/exports m)))
        builtin? (fn [n] (str/starts-with? n "flint_b_"))
        exported (vec (remove builtin? all-exported))
        meta (modmeta/describe
              {:abi (:abi (first (vals units)) {:runtime 1 :value 1 :image 1})
               :memory :unshared
               :gas-in-aot (boolean aot?)
               :version flint-version
               :exports exported
               :imports (vec (sort (map (fn [i] (str (:module i) "/" (:name i)))
                                        (w/imports m))))
               :units (mapv (fn [u] {:name (str (:name u)) :abi (:abi u)}) (:units p))
               ;; Derived from the ARTIFACT, not from the build flags: a
               ;; descriptor that says what was asked for rather than what
               ;; arrived is the kind that goes quietly wrong.
               :builtins (count (filter builtin? all-exported))
               :features {:diagnostics (contains? (set exported) "collect_now")
                          :snapshots (contains? (set exported) "flint_snapshot_capture")
                          :loader (contains? (set exported) "flint_load_image")
                          :capabilities (contains? (set exported) "flint_opaque_host_id")
                          :aot (boolean aot?)}})
        m (w/add-custom m modmeta/section-name (w/utf8-bytes (pr-str meta)))
        bytes (w/emit m)]
    (io/copy bytes (io/file out))
    ;; The linker's raw output is scratch -- it is parsed, rewritten and
    ;; re-emitted above. Leaving it behind put a stray half-megabyte next to
    ;; every artifact, which was tolerable when `out/` was flint's own build
    ;; directory and is not now that `flint build` creates one for a user.
    ;; Only the default name is removed: a caller who NAMED a `tmp` asked for it.
    (when-not tmp (io/delete-file (io/file (str out ".tmp")) true))
    {:bytes (count bytes)
     :image-bytes (count image)
     :aot (dissoc aot-res :module)
     :units (mapv (fn [u] {:name (:name u) :manifest (:manifest u)}) (:units p))
     :builtins (count ordered)
     :table-base base}))
