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
(require '[flint.compiler :as compiler] '[flint.image :as img] '[flint.link :as link]
         '[flint.reader :as reader] '[flint.imgread :as imgread]
         '[clojure.java.io :as io] '[clojure.string :as str]
         '[babashka.fs :as fs])

(def root ".")
(def virtual '#{flint.rt})

(defn ns->path [n] (-> (str n) (str/replace "-" "_") (str/replace "." "/")))

(defn find-source [dirs n]
  (some (fn [d] (some (fn [ext]
                        (let [f (io/file d (str (ns->path n) ext))]
                          (when (.exists f) {:src (slurp f) :file (str f)})))
                      [".cljc" ".clj"]))
        dirs))

(defn collect [dirs roots]
  (loop [todo (vec roots) sources {}]
    (if-let [n (first todo)]
      (if (or (contains? sources n) (contains? virtual n))
        (recur (rest todo) sources)
        (let [s (or (find-source dirs n) (throw (ex-info (str "no source: " n) {})))
              forms (reader/read-all (:src s) {:file (:file s) :features #{:flint}})
              nsform (first (filter #(and (seq? %) (= 'ns (first %))) forms))]
          (recur (into (vec (rest todo)) (if nsform (compiler/ns-requires nsform) []))
                 (assoc sources n s))))
      sources)))

(defn topo [sources]
  (let [deps (into {} (for [[n {:keys [src file]}] sources]
                        [n (let [forms (reader/read-all src {:file file :features #{:flint}})
                                 nsform (first (filter #(and (seq? %) (= 'ns (first %))) forms))]
                             (set (remove virtual (when nsform (compiler/ns-requires nsform)))))]))]
    (cons 'clojure.core
          (remove #{'clojure.core}
                  (loop [done [] seen #{} pending (vec (keys deps))]
                    (if (empty? pending)
                      done
                      (let [ready (filter #(every? (fn [d] (or (seen d) (not (contains? deps d)))) (deps %)) pending)
                            ready (if (seq ready) ready [(first pending)])]
                        (recur (into done ready) (into seen ready)
                               (vec (remove (set ready) pending))))))))))

(defn builtin-names []
  (into #{} (for [d (.listFiles (io/file "units"))
                  :when (.isDirectory d)
                  :let [f (io/file d "unit.edn")]
                  :when (.exists f)
                  k (keys (:provides (read-string (slurp f))))]
              k)))

(def dirs ["src" "lib"])
(def entry 'flint.selfhost/main)
(def sources (collect dirs [(symbol (namespace entry)) 'clojure.core]))
(def order (topo sources))
(def spec {:sources sources :order order :entry entry :builtins (builtin-names)})

(defn compile-on-bb []
  (let [r (compiler/compile-image spec)]
    {:image (vec (img/emit (:builder r) {}))
     :natives (img/natives (:builder r))
     :stats (:stats r)}))

(defn build-module! [image natives out]
  ;; Link with the natives this image needs, then patch the slots in.
  (link/compose {:units-dir "units" :sysroot "units/.sysroot"
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
  (let [spec-edn (pr-str spec)
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
        (require '[flint.disasm :as dis])
        (let [pa (imgread/parse a)
              pb (imgread/parse bimg)
              fa (imgread/parse-fns a (:consts-end pa))
              fb (imgread/parse-fns bimg (:consts-end pb))
              code-a (imgread/code-bytes a (:fns-end fa))
              code-b (imgread/code-bytes bimg (:fns-end fb))
              ar (first (:arities (:a f)))
              br (first (:arities (:b f)))]
          (println "        --- bb ---")
          (println ((resolve 'flint.disasm/disasm) code-a (:off ar) (:len ar)))
          (println "        --- flint ---")
          (println ((resolve 'flint.disasm/disasm) code-b (:off br) (:len br))))))))

(println "self-hosting fixpoint")
(let [t0 (System/nanoTime)
      gen0 (compile-on-bb)
      t1 (System/nanoTime)]
  (println (format "  gen0  bb compiled the compiler: %d image bytes, %d natives, %.1fs"
                   (count (:image gen0)) (count (:natives gen0)) (/ (- t1 t0) 1e9)))
  (build-module! (:image gen0) (:natives gen0) "out/flintc-gen0.wasm")
  (println "  gen0  linked out/flintc-gen0.wasm" (fs/size "out/flintc-gen0.wasm") "bytes")

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
