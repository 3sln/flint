;; End-to-end: hand-assemble an image, link only the units and builtins it
;; reaches, splice it in, and load the result under node.
;;
;; This is the test that "only reachable code ships" has to survive.
;;
;; LOADED, not run. See `loads-js` below: a hand-assembled image carries no
;; control plane, and calling is a message to one now. Nothing about the shake
;; needs the module to be called -- what it needs is for the module to be real,
;; which instantiating proves.
(require '[flint.image :as img] '[flint.link :as link] '[flint.wasm :as w]
         '[clojure.java.io :as io] '[clojure.string :as str])

(def op {:const 0x01 :nil 0x02 :int 0x05 :local 0x06 :return 0x13
         :native 0x15 :call 0x11 :closure 0x14 :set-var 0x0B :var 0x0A})

(defn asm [& parts] (vec (flatten parts)))
(defn const [k] [(op :const) (img/u16 k)])
(defn native [i n] [(op :native) (img/u16 i) n])
(defn int16 [n] [(op :int) (img/u16 n)])

(defn expose!
  "Bind `f` to the var `sym`.

  Nothing is called automatically (`DECISIONS.md#structured-ports` step 5): a module has no
  entry point, so a function nobody can name is a function nobody can run. These
  images are assembled by hand and had only `set-entry!`, which is why they were
  the last thing still relying on `main`. One initialiser per image binds the
  var, which is exactly what a compiled namespace does.

  The var is what makes the shake's job non-trivial -- it is the root the
  reachability walk starts from -- so it stays even though nothing here calls
  through it any more."
  [b sym f]
  (let [v (img/var-slot b sym)
        init (img/add-fn b {:name (symbol (str sym "-init"))
                            :arities [{:argc 0 :variadic? false :nlocals 0
                                       :code (asm [(op :closure)] (img/u16 f) [0]
                                                  [(op :set-var)] (img/u16 v)
                                                  (op :nil) (op :return))}]})]
    (img/add-init! b init)
    (img/set-entry! b f)))

(defn build-image
  "Returns [builder needed-builtin-names]."
  [which]
  (let [b (img/new-builder)]
    (case which
      :hello
      (let [k (img/const b "hello from flint")
            f (img/add-fn b {:name 'main
                             :arities [{:argc 1 :variadic? false :nlocals 1
                                        :code (asm (const k) (op :return))}]})]
        (expose! b 'e2e/hello f))

      :arith
      ;; (fn [args] (num->str (+ 2 3)))
      (let [add (img/native-slot b "flint/add")
            n2s (img/native-slot b "flint/num->str")
            f (img/add-fn b {:name 'main
                             :arities [{:argc 1 :variadic? false :nlocals 1
                                        :code (asm (int16 2) (int16 3) (native add 2)
                                                   (native n2s 1) (op :return))}]})]
        (expose! b 'e2e/arith f))

      :echo
      ;; (fn [argv] (first argv))  -- proves arguments arrive.
      ;;
      ;; ONE `first`, and it went back to one when `main` did. The entry used to
      ;; be handed `[argv caps]`, a pair that existed because the runtime was
      ;; the thing invoking it and had to say something about the host. Nothing
      ;; invokes anything now (`DECISIONS.md#structured-ports` step 5): a caller names a
      ;; function and passes its arguments, so this function takes what it was
      ;; given and the pair is gone with the entry point that needed it.
      (let [fst (img/native-slot b "first")
            f (img/add-fn b {:name 'main
                             :arities [{:argc 1 :variadic? false :nlocals 1
                                        :code (asm [(op :local) 0]
                                                   (native fst 1) (op :return))}]})]
        (expose! b 'e2e/echo f)))
    b))

(defn build! [which out]
  (let [b (build-image which)
        needed (img/natives b)]
    (link/compose {:unit-path ["units"]
                   :sysroot "units/.sysroot"
                   :needed-builtins needed
                   :emit-image (fn [slots] (img/emit b slots))
                   :out out})))

(def loads-js
  "Load a linked module under node and instantiate it, calling NOTHING.

  WHY NOT `run`. These images are assembled by hand, so they contain no
  `flint.system` -- and a call is a message to a control plane now
  (`DECISIONS.md#bridges-are-the-only-door`), not a function the host invokes.
  The synchronous `flint_call` that used to serve one is gone on purpose
  (`DECISIONS.md#calls-are-ports`), so an image with no control plane is an
  image nothing can call, which is a coherent thing for a linker fixture to be.

  That a COMPILED module runs by name under node is covered where it belongs,
  by every test that compiles source -- `shake.clj`, `bytes.clj`, `inline.clj`.
  What is left here is what only this file has: a module linked out of
  hand-assembled bytecode instantiates, exports the ABI, and carries the units
  the shake decided it needed."
  "import { load } from './host/flint.mjs';
   import { instantiate } from './sdks/esm/src/guest.js';
   const { module } = await load(process.argv[1]);
   const i = instantiate(module, {});
   const want = ['memory', 'flint_resume', 'flint_install_port', 'flint_in_alloc'];
   const missing = want.filter((n) => !i.exports[n]);
   console.log(missing.length ? ('missing ' + missing.join(' ')) : 'loads');")

(defn loads-node [path]
  (let [p (.exec (Runtime/getRuntime)
                 (into-array String ["node" "--input-type=module" "-e" loads-js path]))
        out (slurp (.getInputStream p))
        err (slurp (.getErrorStream p))]
    (.waitFor p)
    (if (zero? (.exitValue p)) (str/trim out) (str "threw: " (str/trim err)))))

(defn check [label actual expected]
  (if (= actual expected)
    (println "  ok  " label "=>" (pr-str actual))
    (do (println "  FAIL" label "expected" (pr-str expected) "got" (pr-str actual))
        (System/exit 1))))

(println "e2e: hand-assembled images, linked and loaded under node")
(.mkdirs (io/file "out"))

(let [r (build! :hello "out/hello.wasm")]
  (println "  hello.wasm:" (:bytes r) "bytes," (:builtins r) "builtins," (:image-bytes r) "image bytes")
  (check "hello" (loads-node "out/hello.wasm") "loads"))

(let [r (build! :arith "out/arith.wasm")]
  (println "  arith.wasm:" (:bytes r) "bytes," (:builtins r) "builtins")
  (check "arith" (loads-node "out/arith.wasm") "loads"))

(let [r (build! :echo "out/echo.wasm")]
  (println "  echo.wasm:" (:bytes r) "bytes," (:builtins r) "builtins")
  (check "echo" (loads-node "out/echo.wasm") "loads"))

(println "e2e: ok")

;; --- only reachable code ships ---------------------------------------------
;;
;; A builtin the program never calls must not be in the module. Checked by name,
;; not by size, so it cannot pass for the wrong reason.

(defn fn-names [path]
  (let [m (w/parse (with-open [in (io/input-stream path)] (.readAllBytes in)))]
    (->> (:sections m)
         (filter #(zero? (:id %)))
         (map :payload)
         (mapcat (fn [^bytes p]
                   (let [s (String. p "ISO-8859-1")]
                     (when (str/starts-with? s (str (char 4) "name"))
                       [s]))))
         first)))

(defn build-named! [which out]
  (let [b (build-image which)]
    (link/compose {:unit-path ["units"] :sysroot "units/.sysroot"
                   :needed-builtins (img/natives b)
                   :emit-image (fn [slots] (img/emit b slots))
                   :out out :keep-names true})))

(println "modularity: a builtin that is not called is not in the module")
(build-named! :hello "out/hello-named.wasm")
(build-named! :arith "out/arith-named.wasm")
(let [hello (or (fn-names "out/hello-named.wasm") "")
      arith (or (fn-names "out/arith-named.wasm") "")]
  ;; `num_to_str` and not `number_to_string`: the formatter is GENERATED from
  ;; `kin/dblstr.kin` now, so the symbol the shake has to reach is the
  ;; generated one. Still the implementation and not the `b_num2str` wrapper,
  ;; because what this checks is that the whole call tree comes with it.
  (check "arith contains num->str" (boolean (str/includes? arith "num_to_str")) true)
  (check "hello omits num->str" (boolean (str/includes? hello "num_to_str")) false)
  (check "arith contains b_add" (boolean (str/includes? arith "flint_b_add")) true)
  (check "hello omits b_add" (boolean (str/includes? hello "flint_b_add")) false)
  (check "neither contains the map builtin" (boolean (str/includes? hello "flint_b_assoc")) false))
(println "modularity: ok")
