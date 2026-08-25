;; Compiling without linking (`doc/decisions/0023`).
;;
;; 0023 asks whether a Worker has to produce a MODULE. Emitting a `.wasm` needs
;; `rust-lld`, which will not run there -- but the compiler's output is a
;; bytecode IMAGE, and a module that can load one at run time means the whole
;; loop happens with no linking step anywhere.
;;
;; The mechanism is that an image records its builtins by NAME as well as by
;; slot. The slots belong to whichever module compiled it and are meaningless
;; anywhere else; the names are not.
(require '[clojure.string :as str] '[babashka.fs :as fs])

(def fails (atom 0))
(defn check [label actual expected]
  (if (= actual expected)
    (println "  ok  " label)
    (do (swap! fails inc)
        (println "  FAIL" label "\n        expected" (pr-str expected)
                 "\n        got     " (pr-str actual)))))
(defn check-that [label ok] (check label (boolean ok) true))

(defn sh [& args]
  (let [p (.start (ProcessBuilder. (into-array String args)))
        out (slurp (.getInputStream p)) err (slurp (.getErrorStream p))]
    (.waitFor p) {:exit (.exitValue p) :out out :err err :all (str out err)}))

(def d (str (fs/create-temp-dir)))
(spit (str d "/one.cljc")
      (str "(ns one (:require [clojure.string :as str]))\n"
           "(defn main [args]\n"
           "  (let [n (if (seq args) (parse-long (first args)) 3)]\n"
           "    (str/join \",\" (mapv (fn [i] (str \"x\" (* i i))) (range n)))))\n"))
(spit (str d "/two.cljc")
      (str "(ns two (:require [clojure.string :as str]))\n"
           "(defn main [args]\n"
           "  (let [n (if (seq args) (parse-long (first args)) 2)]\n"
           "    (str/upper-case (str/join \"-\" (repeat n \"flint\")))))\n"))

(println "loader: an image, loaded by a module that never linked it")

(defn build! [args]
  (let [r (apply sh (concat ["./bin/flint"] args))]
    (when-not (zero? (:exit r)) (println "build failed:" (:all r)) (System/exit 1))))

;; A loader carries every builtin, because it runs images it has never seen.
(build! [":src" d ":fn" "one/main" ":out" "out/loader.wasm" "--loader"])
(build! [":src" d ":fn" "one/main" ":out" "out/one.image" "--emit-image"])
(build! [":src" d ":fn" "two/main" ":out" "out/two.image" "--emit-image"])

;; And an ordinary module, which carries only what its own program reached.
(build! [":src" d ":fn" "one/main" ":out" "out/plain.wasm"])

(def driver
  "import('fs').then(async (fsm) => {
     const fs = fsm.default;
     const e = new WebAssembly.Instance(new WebAssembly.Module(fs.readFileSync(process.argv[1])), {}).exports;
     const mem = () => new Uint8Array(e.memory.buffer);
     const out = () => new TextDecoder().decode(mem().subarray(e.out_ptr(), e.out_ptr() + e.out_len()));
     const res = [];
     for (const spec of process.argv.slice(2)) {
       const [path, arg] = spec.split('=');
       const img = fs.readFileSync(path);
       const p = e.arg_alloc(img.length);
       mem().set(img, p);
       const rc = e.flint_load_image ? e.flint_load_image(p, img.length) : -1;
       if (rc !== 0) { res.push({rc, why: rc === -1 ? 'no flint_load_image export' : out()}); continue; }
       const b = new TextEncoder().encode(arg);
       const q = e.arg_alloc(b.length);
       mem().set(b, q);
       e.arg_push(q, b.length);
       res.push({rc: 0, code: e.main(), out: out()});
     }
     console.log(JSON.stringify(res));
   })")

(defn drive [wasm & specs]
  (let [r (apply sh (concat ["node" "-e" driver "--" wasm] specs))]
    (str/trim (str (:out r) (:err r)))))

;; The whole claim: a module built for `one` runs `two`, having never seen it,
;; and the two do not contaminate each other across a swap.
(let [got (drive "out/loader.wasm" "out/one.image=4" "out/two.image=3"
                 "out/one.image=2" "out/two.image=1")]
  (println (str "    " got))
  (check-that "an image runs in a module that never linked it"
              (str/includes? got "x0,x1,x4,x9"))
  (check-that "  ... and a second image replaces it cleanly"
              (str/includes? got "FLINT-FLINT-FLINT"))
  (check-that "  ... in either order, any number of times"
              (and (str/includes? got "\"x0,x1\"") (str/includes? got "\"FLINT\"")))
  (check-that "  ... with no failures along the way"
              (not (str/includes? got "\"why\""))))

;; And the refusal, which has to name the builtin: a plain module carries only
;; what its own program reached, so an image wanting more is a real failure and
;; "wrong builtin set" is otherwise indistinguishable from a corrupt image.
(let [got (drive "out/plain.wasm" "out/one.image=2")]
  (check-that "a module built WITHOUT --loader refuses, by name"
              (str/includes? got "no flint_load_image export")))

(println (if (zero? @fails) "loader: ok" (str "loader: " @fails " FAILURES")))
(System/exit (if (zero? @fails) 0 1))
