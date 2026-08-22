;; End-to-end: hand-assemble an image, link only the units and builtins it
;; reaches, splice it in, and run the result under node.
;;
;; This is the test that "only reachable code ships" has to survive.
(require '[flint.image :as img] '[flint.link :as link] '[flint.wasm :as w]
         '[clojure.java.io :as io] '[clojure.string :as str])

(def op {:const 0x01 :nil 0x02 :int 0x05 :local 0x06 :return 0x13
         :native 0x15 :call 0x11 :closure 0x14 :set-var 0x0B :var 0x0A})

(defn asm [& parts] (vec (flatten parts)))
(defn const [k] [(op :const) (img/u16 k)])
(defn native [i n] [(op :native) (img/u16 i) n])
(defn int16 [n] [(op :int) (img/u16 n)])

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
        (img/set-entry! b f))

      :arith
      ;; (fn [args] (num->str (+ 2 3)))
      (let [add (img/native-slot b "flint/add")
            n2s (img/native-slot b "flint/num->str")
            f (img/add-fn b {:name 'main
                             :arities [{:argc 1 :variadic? false :nlocals 1
                                        :code (asm (int16 2) (int16 3) (native add 2)
                                                   (native n2s 1) (op :return))}]})]
        (img/set-entry! b f))

      :echo
      ;; (fn [args] (first args))  -- proves arguments arrive
      (let [fst (img/native-slot b "first")
            f (img/add-fn b {:name 'main
                             :arities [{:argc 1 :variadic? false :nlocals 1
                                        :code (asm [(op :local) 0] (native fst 1) (op :return))}]})]
        (img/set-entry! b f)))
    b))

(defn build! [which out]
  (let [b (build-image which)
        needed (img/natives b)]
    (link/compose {:units-dir "units"
                   :sysroot "units/.sysroot"
                   :needed-builtins needed
                   :emit-image (fn [slots] (img/emit b slots))
                   :out out})))

(defn run-node [path & args]
  (let [p (.exec (Runtime/getRuntime)
                 (into-array String (concat ["node" "host/flint.mjs" path] args)))
        out (slurp (.getInputStream p))
        err (slurp (.getErrorStream p))]
    (.waitFor p)
    {:out (str/trim out) :err err :code (.exitValue p)}))

(defn check [label actual expected]
  (if (= actual expected)
    (println "  ok  " label "=>" (pr-str actual))
    (do (println "  FAIL" label "expected" (pr-str expected) "got" (pr-str actual))
        (System/exit 1))))

(println "e2e: hand-assembled images, linked and run under node")
(.mkdirs (io/file "out"))

(let [r (build! :hello "out/hello.wasm")]
  (println "  hello.wasm:" (:bytes r) "bytes," (:builtins r) "builtins," (:image-bytes r) "image bytes")
  (check "hello" (:out (run-node "out/hello.wasm")) "hello from flint"))

(let [r (build! :arith "out/arith.wasm")]
  (println "  arith.wasm:" (:bytes r) "bytes," (:builtins r) "builtins")
  (check "arith" (:out (run-node "out/arith.wasm")) "5"))

(let [r (build! :echo "out/echo.wasm")]
  (println "  echo.wasm:" (:bytes r) "bytes," (:builtins r) "builtins")
  (check "echo" (:out (run-node "out/echo.wasm" "first-arg" "second")) "first-arg"))

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
    (link/compose {:units-dir "units" :sysroot "units/.sysroot"
                   :needed-builtins (img/natives b)
                   :emit-image (fn [slots] (img/emit b slots))
                   :out out :keep-names true})))

(println "modularity: a builtin that is not called is not in the module")
(build-named! :hello "out/hello-named.wasm")
(build-named! :arith "out/arith-named.wasm")
(let [hello (or (fn-names "out/hello-named.wasm") "")
      arith (or (fn-names "out/arith-named.wasm") "")]
  (check "arith contains num->str" (boolean (str/includes? arith "number_to_string")) true)
  (check "hello omits num->str" (boolean (str/includes? hello "number_to_string")) false)
  (check "arith contains b_add" (boolean (str/includes? arith "flint_b_add")) true)
  (check "hello omits b_add" (boolean (str/includes? hello "flint_b_add")) false)
  (check "neither contains the map builtin" (boolean (str/includes? hello "flint_b_assoc")) false))
(println "modularity: ok")
