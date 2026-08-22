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
      (throw (ex-info (str "command failed: " (first args)) {:code code :err err :out out})))
    {:out out :err err}))

(defn read-unit
  "Load a unit manifest from `dir`."
  [dir]
  (let [f (io/file dir "unit.edn")]
    (when (.exists f)
      (assoc (read-string (slurp f)) :dir (str dir)))))

(defn discover-units
  "All units under `root`, by name."
  [root]
  (into {} (for [d (.listFiles (io/file root))
                 :when (.isDirectory d)
                 :let [u (read-unit d)]
                 :when u]
             [(:name u) u])))

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
  ["flint_main" "arg_alloc" "arg_push" "out_ptr" "out_len"
   "stat_bytes_allocated" "stat_collections" "image_desc_addr" "set_step_limit"])

(defn link-objects
  "Run the linker. Returns the module bytes."
  [{:keys [units exports]} sysroot out-path & [keep-names]]
  (let [objs (for [u units] (str (:dir u) "/" (:artifact u)))
        rlibs (filter #(str/ends-with? % ".rlib")
                      (map str (.listFiles (io/file sysroot))))
        args (concat [(lld-path) "-flavor" "wasm" "--no-entry" "--gc-sections"
                      "--export-table"
                      "--export=__heap_base" "--export=FLINT_IMAGE_DESC"]
                     (map #(str "--export=" %) abi-exports)
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
  [{:keys [units-dir sysroot needed-builtins emit-image out tmp keep-names]}]
  (let [units (discover-units units-dir)
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
     :units (mapv :name units)
     :builtins (count ordered)
     :table-base base}))
