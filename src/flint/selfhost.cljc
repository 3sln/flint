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

            [flint.rt]))

(def ^:private b64-alphabet
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/")

(defn base64 [bytes]
  (let [n (count bytes)]
    (flint.rt/str-join
     (loop [acc [] i 0]
       (if (>= i n)
         acc
         (let [b0 (nth bytes i)
               b1 (if (< (+ i 1) n) (nth bytes (+ i 1)) 0)
               b2 (if (< (+ i 2) n) (nth bytes (+ i 2)) 0)
               trip (bit-or (bit-shift-left b0 16) (bit-or (bit-shift-left b1 8) b2))
               c0 (nth b64-alphabet (bit-and (bit-shift-right trip 18) 63))
               c1 (nth b64-alphabet (bit-and (bit-shift-right trip 12) 63))
               c2 (if (< (+ i 1) n) (nth b64-alphabet (bit-and (bit-shift-right trip 6) 63)) "=")
               c3 (if (< (+ i 2) n) (nth b64-alphabet (bit-and trip 63)) "=")]
           (recur (conj acc c0 c1 c2 c3) (+ i 3))))))))

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

(defn main [args]
  ;; Two entries, chosen by the first argument. `spec` is the original: the
  ;; caller resolved every namespace and handed over a finished map, which is
  ;; what the bootstrap does because babashka is already reading files.
  ;; `project` is the one a host with no Clojure reader can use.
  (let [mode (first args)
        [mode spec-edn] (if (= mode "project") [mode (second args)] ["spec" mode])
        r (if (= mode "project")
            (compile-project spec-edn)
            (compile-to-base64 spec-edn))]
    (cond
      (:missing r)
      (flint.rt/str-join (concat ["!missing\n"] (interpose "\n" (map str (:missing r)))))
      :else
      ;; One string out: base64 image, newline, then the native import order,
      ;; one per line, which is what the host needs to assign slots.
      (flint.rt/str-join
       (concat [(:image r) "\n"]
               (interpose "\n" (:natives r)))))))
