;; The self-hosting fixpoint test.
;;
;;   gen0  bb compiles the compiler        -> flintc.wasm      (image A)
;;   gen1  flintc.wasm compiles the compiler -> image B
;;   gen2  a module built from image B compiles the compiler -> image C
;;
;; The brief asks for two things: bb-compiled and flint-compiled must AGREE
;; (A == B), and the second generation must reproduce itself byte for byte
;; (B == C).
(babashka.classpath/add-classpath "src")
(require '[flint.compiler.core :as compiler] '[flint.compiler.image :as img] '[flint.compiler.link :as link]
         '[flint.compiler.forms :as forms] '[flint.compiler.imgread :as imgread]
         '[flint.compiler.resolve :as project]
         '[clojure.java.io :as io] '[clojure.string :as str]
         '[babashka.fs :as fs])

(def root ".")
(def virtual '#{flint.rt})

(defn ns->path [n] (-> (str n) (str/replace "-" "_") (str/replace "." "/")))

;; READ BY THE KIN READER, every file once, in one batch (`test/hostread.clj`):
;; the compiler reads no text (`DECISIONS.md#one-reader-and-no-other`). This used
;; to walk the requires itself with `flint.compiler.reader` (`collect`, `topo`);
;; it goes through the ONE wave walk every door uses now, over a resolver that
;; answers from the batch.
(load-file "test/hostread.clj")

(defn source-files [dirs]
  (for [d dirs
        f (file-seq (io/file d))
        :when (.isFile f)
        :let [p (str f)]
        :when (some #(str/ends-with? p %) [".fln" ".cljc" ".clj"])]
    p))

(defn read-all-files
  "Every source file under `dirs`, READ deferred: `{path answer}`."
  [dirs]
  (let [paths (vec (source-files dirs))]
    (zipmap paths (hostread/read-texts (mapv (fn [p] {:file p :text (slurp p) :dialect (hostread/dialect-of p)})
                                             paths)))))

(defn find-source [read dirs n]
  (some (fn [d] (some (fn [ext]
                        (let [p (str (io/file d (str (ns->path n) ext)))]
                          (when-let [a (get read p)] (assoc a :file p))))
                      ;; Most specific first (`DECISIONS.md#dialects-and-preludes`):
                      ;; a namespace may have both a `.fln` and a `.cljc`.
                      [".fln" ".cljc" ".clj"]))
        dirs))

(def features #{:flint})

(defn resolve-program
  "`{:sources :order}` for a program from `roots`, through
  `flint.compiler.resolve/resolve-project`, answering from `read` and `extra`
  (`{ns answer}`)."
  [read dirs roots extra]
  (let [r (project/resolve-project (fn [n] (or (get extra n) (find-source read dirs n)))
                                   (first roots) features (vec roots))]
    (when (seq (:missing r)) (throw (ex-info (str "no source: " (:missing r)) {})))
    {:sources (:sources r) :order (:order r)}))

(defn builtin-names []
  (into #{} (for [[_ u] (link/discover-units ["units"])
                  k (keys (:provides u))]
              k)))

;; The four shipped roots (`DECISIONS.md#four-units`), plus `src` for the
;; compiler's own namespaces -- `lib` used to be the one stdlib root.
(def dirs ["src" "lib/stdcore" "lib/stdextra" "lib/deps" "cli/lib"])
(def entry 'flint.compiler.selfhost/main)
;; THE ROOTS ARE `flint.compiler.resolve/project-roots`'s, because the spec
;; goes through the real walk now: `flint.port`/`flint.wire` (the call loop's)
;; and `flint.check` are added there, and `clojure.core` is reached through each
;; referrer's prelude (`DECISIONS.md#four-units`). This file used to keep its
;; own root list, and twice it fell behind the real one.
(def read-files (read-all-files dirs))
(def program (resolve-program read-files dirs [(symbol (namespace entry))] {}))
(def sources (:sources program))
(def order (:order program))
(def spec {:sources sources :order order :entry entry :builtins (builtin-names) :features features})

(defn spec-edn
  "`spec` as the EDN a flint-hosted compiler takes: each source READ, its
  bytes as base64 under `:preread`, never its forms or its text."
  [spec]
  (pr-str (update spec :sources
                  (fn [ss] (into {} (map (fn [[n a]]
                                           [n (cond-> (dissoc a :forms :src)
                                                (:preread a)
                                                (assoc :preread (.encodeToString (java.util.Base64/getEncoder)
                                                                                 ^bytes (:preread a))))])
                                         ss))))))

(when (System/getenv "FLINT_DUMP_SPEC")
  (spit (System/getenv "FLINT_DUMP_SPEC") (spec-edn spec)))

(defn compile-on-bb []
  (let [r (compiler/compile-image spec)]
    {:image (vec (img/emit (:builder r) {}))
     :natives (img/natives (:builder r))
     :stats (:stats r)}))

(defn build-module! [image natives out]
  ;; Link with the natives this image needs, then patch the slots in.
  (link/compose {:unit-path ["units"] :sysroot "units/.sysroot"
                 :needed-builtins natives
                 :emit-image (fn [slots] (img/patch-native-slots image natives slots))
                 :out out}))

(defn run-node [path input]
  (let [tmp (fs/create-temp-file {:suffix ".txt"})]
    (spit (str tmp) input)
    (let [pb (ProcessBuilder. ["node" "host/flint-file.mjs" path (str tmp)])
          p (.start pb)
          out (slurp (.getInputStream p))
          err (slurp (.getErrorStream p))]
      (.waitFor p)
      (when-not (zero? (.exitValue p))
        (println "node failed:" err (subs out 0 (min 400 (count out))))
        (System/exit 1))
      out)))

(defn b64-decode [s]
  (vec (map #(bit-and (int %) 0xff)
            (.decode (java.util.Base64/getDecoder) ^String (str/trim s)))))

(defn compile-on-flint [module]
  (let [spec-edn (spec-edn spec)
        out (run-node module spec-edn)
        lines (str/split-lines out)]
    {:image (b64-decode (first lines))
     :natives (vec (remove str/blank? (rest lines)))}))

(defn code-start
  "Byte offset of the code section in an emitted image. Found by looking for the
  codelen field whose value makes the rest of the image add up."
  [bytes b]
  (let [codelen (count (:code b))
        n (count bytes)
        u32at (fn [i] (+ (nth bytes i) (* 256 (nth bytes (+ i 1)))
                         (* 65536 (nth bytes (+ i 2))) (* 16777216 (nth bytes (+ i 3)))))]
    (loop [i 0]
      (cond
        (> i (- n 4)) nil
        (and (= (u32at i) codelen)
             (= (+ i 4 codelen 4 4 (* 4 (count (:init b)))) n)) (+ i 4)
        :else (recur (inc i))))))

(defn report-diff [a bimg]
  (let [n (min (count a) (count bimg))
        d (first (filter #(not= (nth a %) (nth bimg %)) (range n)))
        ndiff (count (filter #(not= (nth a %) (nth bimg %)) (range n)))]
    (println "  FAIL  bb and flint disagree:" (count a) "vs" (count bimg) "bytes")
    (println "        first difference at byte" d "of" ndiff "differing")
    (let [dif (imgread/diff a bimg)
          f (get-in dif [:fns :first])]
      (println "        consts differ:" (some? (:first-differing dif))
               (pr-str (:first-differing dif)))
      (println "        fns:" (get-in dif [:fns :count-a]) "vs" (get-in dif [:fns :count-b]))
      (when f
        (println "        first differing fn:" (:name-a f))
        (println "          bb   " (pr-str (:a f)))
        (println "          flint" (pr-str (:b f)))
        (require '[flint.compiler.disasm :as dis])
        (let [pa (imgread/parse a)
              pb (imgread/parse bimg)
              fa (imgread/parse-fns a (:consts-end pa))
              fb (imgread/parse-fns bimg (:consts-end pb))
              code-a (imgread/code-bytes a (:fns-end fa))
              code-b (imgread/code-bytes bimg (:fns-end fb))
              ar (first (:arities (:a f)))
              br (first (:arities (:b f)))]
          (println "        --- bb ---")
          (println ((resolve 'flint.compiler.disasm/disasm) code-a (:off ar) (:len ar)))
          (println "        --- flint ---")
          (println ((resolve 'flint.compiler.disasm/disasm) code-b (:off br) (:len br))))))))

(println "self-hosting fixpoint")
(let [t0 (System/nanoTime)
      gen0 (compile-on-bb)
      t1 (System/nanoTime)]
  (println (format "  gen0  bb compiled the compiler: %d image bytes, %d natives, %.1fs"
                   (count (:image gen0)) (count (:natives gen0)) (/ (- t1 t0) 1e9)))
  (build-module! (:image gen0) (:natives gen0) "out/flintc-gen0.wasm")
  (println "  gen0  linked out/flintc-gen0.wasm" (fs/size "out/flintc-gen0.wasm") "bytes")

  ;; A LITERAL BIGGER THAN THE SHADOW STACK'S OLD DEFAULT.
  ;;
  ;; gen0 is a WASM-HOSTED compiler, and analysing a collection literal recurses
  ;; at 320 bytes an entry. `wasm-ld`'s default 64 KiB stack ran out at 178 of
  ;; them -- not by saying so, but by growing down through its floor and
  ;; rewriting the runtime's own globals, which is what made THIS test hang
  ;; (`DECISIONS.md#the-shadow-stack-is-not-a-default`).
  ;;
  ;; The fixpoint above is a WEAK guard for that: the compiler's own sources
  ;; only just crossed the old default, so half the stack still passes it. This
  ;; asks for a literal well past it -- the margin rather than the floor. 2 000
  ;; entries needs about 220 KB of the megabyte the link line asks for -- past
  ;; a 128 KiB stack, which the fixpoint alone still passes -- and the spec is
  ;; `clojure.core` plus one file, so it costs a second rather than a compile of
  ;; the whole compiler.
  ;;
  ;; WHY NOT MORE. There is a SECOND stack, and it is not ours: the recursion
  ;; runs on the embedder's native stack as well as the guest's, so past about
  ;; a thousand entries node throws `RangeError: Maximum call stack size
  ;; exceeded` with a backtrace full of wasm frames. `node --stack-size` moves
  ;; that one. It is an honest error rather than corruption, but it is the
  ;; HOST's limit, so a number that trips it would be measuring node.
  (let [n 500
        ;; `flint.port`/`flint.wire`, same as the `sources` root list above: the
        ;; call loop every image carries calls them unconditionally, so a spec
        ;; built by hand (rather than through `flint.compiler.resolve/project-roots`,
        ;; which adds both for exactly this reason) must add them itself or
        ;; gen0 refuses the compile with "is flint.port required?".
        ;; V IS DEAD ON PURPOSE, and the threshold does not care: measured,
        ;; a literal reachable from `main` and one the shake drops break at the
        ;; same size, because the cost is in ANALYSING it and analysis happens
        ;; before the shake. Keeping it dead keeps it out of the ANSWER, and a
        ;; 2 000-element vector in the answer overruns node's own JS stack in
        ;; the host codec -- a harness limit that would be measuring the wrong
        ;; runtime entirely.
        app-src (str "(ns app)\n(def V [" (str/join " " (repeat n "1")) "])\n"
                     "(defn main [_] \"hi\")")
        app (merge {:file "app.cljc"}
                   (first (hostread/read-texts [{:file "app.cljc" :text app-src :dialect :portable}])))
        prog (resolve-program read-files dirs ['app] {'app app})
        wide (assoc spec :sources (:sources prog) :order (:order prog) :entry 'app/main)]
    ;; `run-node` exits non-zero itself if the module hangs or throws, which is
    ;; exactly the failure being guarded against; a blank answer would mean it
    ;; returned without an image.
    (if (str/blank? (run-node "out/flintc-gen0.wasm" (spec-edn wide)))
      (do (println "  FAIL  a" n "entry literal produced no image") (System/exit 1))
      (println "  ok   " n "entry literal compiles -- the shadow stack has margin")))


  (let [t2 (System/nanoTime)
        gen1 (compile-on-flint "out/flintc-gen0.wasm")
        t3 (System/nanoTime)]
    (println (format "  gen1  flint compiled the compiler: %d image bytes, %.1fs"
                     (count (:image gen1)) (/ (- t3 t2) 1e9)))
    (when-not (= (:image gen0) (:image gen1))
      (report-diff (:image gen0) (:image gen1))
      (System/exit 1))
    (println "  ok    bb-compiled and flint-compiled images are IDENTICAL")

    (build-module! (:image gen1) (:natives gen1) "out/flintc-gen1.wasm")
    (let [gen2 (compile-on-flint "out/flintc-gen1.wasm")]
      (if (= (:image gen1) (:image gen2))
        (println "  ok    generation 2 reproduces itself byte for byte")
        (do (println "  FAIL  gen1 and gen2 differ") (System/exit 1)))))
  (println "self-hosting: ok"))
