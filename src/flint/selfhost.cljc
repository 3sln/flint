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

(defn compile-project
  "Compile from an ENTRY and a map of source files, resolving `:require`s here.

  `spec` is EDN:

      {:files {\"clojure/core.cljc\" \"(ns clojure.core) ..\" ..}
       :entry my.app/main
       :builtins #{..}
       :features #{:flint}}

  The difference from `compile-to-base64` is that the caller does not have to
  know what the program requires. That resolution is most of what a compiler
  does before it compiles anything, and a host driving this module through
  WebAssembly has no way to do it -- it would have to parse `ns` forms, which
  means it would need a Clojure reader, which is what it is calling.

  A namespace with no source is named, all of them at once. Reporting the first
  and stopping makes fixing a dependency list an n-round conversation."
  [spec-edn]
  (let [spec (reader/read-one spec-edn)
        files (:files spec)
        features (or (:features spec) #{:flint})
        entry (:entry spec)
        entry-ns (symbol (namespace entry))
        find-source (fn [n]
                      (let [base (project/ns->path n)]
                        (or (when-let [src (get files (str base ".cljc"))]
                              {:src src :file (str base ".cljc")})
                            (when-let [src (get files (str base ".clj"))]
                              {:src src :file (str base ".clj")}))))
        {:keys [sources order missing]} (project/resolve-project find-source entry-ns features)]
    (if (seq missing)
      {:missing (vec missing)}
      (let [result (compiler/compile-image
                    {:sources (into {} (map (fn [e] [(key e) {:src (:src (val e))
                                                              :file (:file (val e))}])
                                            sources))
                     :order (vec (filter (fn [n] (contains? sources n)) order))
                     :entry entry
                     :builtins (or (:builtins spec) #{})
                     :features features})
            builder (:builder result)]
        {:image (base64 (img/emit builder {}))
         :natives (img/natives builder)}))))

(defn compile-to-wasm
  "Compile a program and splice it into a PREBUILT runtime module, producing a
  standalone `.wasm`.

  This is what a compiler is expected to emit; the bytecode image is internal
  machinery. `spec` adds three keys to `compile-project`'s:

      :slots  {builtin-name table-slot} for that module
      :aot    true to append compiled arities as well
      :shake  true to cut the runtime down to what this program reaches

  The runtime module arrives as a SEPARATE argument rather than inside the
  spec, and that is not tidiness. Three-quarters of a megabyte of base64 inside
  an EDN string is three-quarters of a megabyte the reader has to scan a
  character at a time: it took 198 seconds, against 450 milliseconds for all
  the byte handling put together.

  No linking happens and none is needed (`doc/decisions/0024`). Linking merges
  relocatable objects and is `wasm-ld`; the runtime module was linked once,
  when flint was built. Everything after that -- appending the image as a data
  segment, pointing the descriptor at it, appending compiled arities -- is byte
  manipulation on a finished module."
  [spec-edn base-b64]
  (let [spec (reader/read-one spec-edn)
        files (:files spec)
        features (or (:features spec) #{:flint})
        entry (:entry spec)
        entry-ns (symbol (namespace entry))
        slots (:slots spec)
        find-source (fn [n]
                      (let [base (project/ns->path n)]
                        (or (when-let [src (get files (str base ".cljc"))]
                              {:src src :file (str base ".cljc")})
                            (when-let [src (get files (str base ".clj"))]
                              {:src src :file (str base ".clj")}))))
        {:keys [sources order missing]} (project/resolve-project find-source entry-ns features)]
    (if (seq missing)
      {:missing (vec missing)}
      (let [result (compiler/compile-image
                    {:sources (into {} (map (fn [e] [(key e) {:src (:src (val e))
                                                              :file (:file (val e))}])
                                            sources))
                     :order (vec (filter (fn [n] (contains? sources n)) order))
                     :entry entry
                     :builtins (set (keys slots))
                     :features features})
            builder (:builder result)
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
            used (set (img/natives builder))
            shaken (when (:shake spec)
                     (let [exp (w/exports m)
                           table (wshake/table-entries m)
                           abi (remove (fn [n] (str/starts-with? n "flint_b_")) (keys exp))
                           roots (into (into (into #{} (keep (fn [n] (:index (get exp n))) abi))
                                             (keep (fn [n] (get table n))
                                                   (keep (fn [n] (get slots n)) used)))
                                       ;; The compiled arities, which nothing
                                       ;; else can reach: they live only in the
                                       ;; element segment `compile-arities`
                                       ;; appended.
                                       (or (:funcs res) []))]
                       (wshake/stub-dead m roots)))
            m (if shaken (first shaken) m)
            image (img/emit builder slots)]
        {:module (base64 (bundle/into-module (w/emit m) image
                                             {:entry entry :aot? aot?}))
         :compiled (when res (:compiled res))
         :arities (when res (:total res))
         :shaken (when shaken (second shaken))}))))

(defn main [args]
  ;; Two entries, chosen by the first argument. `spec` is the original: the
  ;; caller resolved every namespace and handed over a finished map, which is
  ;; what the bootstrap does because babashka is already reading files.
  ;; `project` is the one a host with no Clojure reader can use.
  (let [mode (first args)
        known? (if (= mode "project") true (= mode "wasm"))
        [mode spec-edn] (if known? [mode (second args)] ["spec" mode])
        r (cond
            (= mode "wasm") (compile-to-wasm spec-edn (nth args 2 ""))
            (= mode "project") (compile-project spec-edn)
            :else (compile-to-base64 spec-edn))]
    (cond
      (:missing r)
      (flint.rt/str-join (concat ["!missing\n"] (interpose "\n" (map str (:missing r)))))
      ;; A module comes back alone: its native slots are already in it, so
      ;; there is no import order for the host to apply.
      (:module r) (:module r)
      :else
      ;; One string out: base64 image, newline, then the native import order,
      ;; one per line, which is what the host needs to assign slots.
      (flint.rt/str-join
       (concat [(:image r) "\n"]
               (interpose "\n" (:natives r)))))))
