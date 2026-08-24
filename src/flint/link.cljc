(ns flint.link
  "Composes a module from units.

  Reachability decides what is linked (`doc/decisions/0003-namespace-units.md`):
  only the units the entry point reaches are handed to `rust-lld`, and within a
  linked unit only the builtins the program actually calls are `--export`ed, so
  `--gc-sections` deletes the rest. The registry is then assembled *here*, after
  the link, by reading the module's export section and appending an element
  segment -- which is why nothing in the runtime holds a table of builtins."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [flint.wasm :as w]))

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
          ;; flint.rt carries the interpreter itself, so it is always required
          closure (conj closure 'flint.rt)
          exports (into {} (for [b needed-builtins
                                 :let [u (units (provider b))]]
                             [b (get-in u [:provides b :symbol])]))]
      {:units (mapv units (sort-by str closure))
       :exports exports})))

(def abi-exports
  ;; The module's outside edge, and the roots --gc-sections keeps.
  ;;
  ;; PRODUCTION ONLY (doc/decisions/0016). Gas and the memory cap are here
  ;; because they are resource control rather than instrumentation, and
  ;; construe's gates depend on a deterministic instruction count -- `stat_steps`
  ;; is how a host reads it, so it survives stripping with them. Everything
  ;; diagnostic (`collect_now`, `set_gc_stress`, the heap statistics) is carried
  ;; by the runtime unit's own `:exports`, and only the diagnostics build of the
  ;; runtime declares them.
  ["flint_main" "arg_alloc" "arg_push" "out_ptr" "out_len"
   "image_desc_addr" "set_step_limit" "stat_steps" "set_memory_limit"])

(defn unit-exports
  "Extra wasm exports a linked unit asks for. This is how a unit can widen the
  module's outside edge -- the concurrency unit's host-callback surface is the
  only user so far -- without every module paying for the wider edge."
  [units]
  (vec (mapcat :exports units)))

(defn link-objects
  "Run the linker. Returns the module bytes."
  [{:keys [units exports]} sysroot out-path & [keep-names]]
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
                      "--export=__heap_base" "--export=FLINT_IMAGE_DESC"]
                     (map #(str "--export=" %) abi-exports)
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

(defn compose
  "Link, bind the reached builtins into the wasm table, splice the image in.
  `emit-image` is called with the resolved {builtin-name -> table-slot} map and
  must return the image bytes."
  [{:keys [unit-path sysroot needed-builtins emit-image out tmp keep-names]}]
  (let [units (discover-units unit-path)
        _ (check-abi! units)
        p (plan units needed-builtins)
        raw (link-objects p sysroot (or tmp (str out ".tmp")) keep-names)
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
        m (w/append-data m desc-addr
                         (w/->bytes [(flint.image/u32 img-addr)
                                     (flint.image/u32 (count image))]))
        m (w/rename-export m "flint_main" "main")
        m (w/strip-custom m (if keep-names #{"producers" "target_features"}
                                #{"producers" "target_features" "name"}))
        bytes (w/emit m)]
    (io/copy bytes (io/file out))
    {:bytes (count bytes)
     :image-bytes (count image)
     :units (mapv (fn [u] {:name (:name u) :manifest (:manifest u)}) (:units p))
     :builtins (count ordered)
     :table-base base}))
