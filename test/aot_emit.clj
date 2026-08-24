;; The emitter, validated in isolation.
;;
;; A compiled arity is 250 KB into a linked module, so a validation failure
;; there says "function #261" and nothing else. Here each body goes into a
;; module of its own with four stub helpers, so a failure names the opcode that
;; caused it. This is the same reasoning as every instrument that earned its
;; place in the GC hunt: put the check where it can name the culprit.
(babashka.classpath/add-classpath "src")
(require '[flint.wasm :as w] '[flint.aot :as aot] '[clojure.string :as string])

(def fails (atom 0))
(defn check [label ok] (if ok (println "  ok  " label)
                           (do (println "  FAIL " label) (swap! fails inc))))

;; Four helpers, in the order `flint.aot` indexes them.
(def HELPERS {:native 0 :return 1 :bail 2 :tick 3 :call 4})
(def HELPER-TYPES
  ;; native (rt idx argc top ip block gas)->i32, return (rt top gas)->(),
  ;; bail (rt top ip resume-ip block gas)->(), tick (rt gas ip block)->i32,
  ;; call (rt argc top ip next-ip block gas)->i32
  [[[0x7F 0x7F 0x7F 0x7F 0x7F 0x7F 0x7F 0x7F 0x7F] [0x7F]]
   [[0x7F 0x7F 0x7F] []]
   [[0x7F 0x7F 0x7F 0x7F 0x7F 0x7F] []]
   [[0x7F 0x7F 0x7F 0x7F 0x7F] [0x7F]]
   [[0x7F 0x7F 0x7F 0x7F 0x7F 0x7F 0x7F 0x7F] [0x7F]]
   [[0x7F 0x7F 0x7F 0x7F 0x7F] []]])   ; the compiled arity itself

(defn module-for [body]
  (let [types (for [[ps rs] HELPER-TYPES]
                [0x60 (w/uleb (count ps)) ps (w/uleb (count rs)) rs])
        ;; The four helpers are imports, so the compiled body's calls resolve
        ;; without needing real implementations.
        imports (for [[i nm] (map-indexed vector ["n" "r" "b" "t" "c"])]
                  [(w/uleb 1) (int \e) (w/uleb (count nm)) (map int nm) 0x00 (w/uleb i)])
        code (w/->bytes body)]
    (w/->bytes
     [0x00 0x61 0x73 0x6d 0x01 0x00 0x00 0x00
      0x01 (let [p (w/->bytes [(w/uleb (count HELPER-TYPES)) types])]
             [(w/uleb (alength p)) p])
      0x02 (let [p (w/->bytes [(w/uleb 5) imports])] [(w/uleb (alength p)) p])
      0x03 (let [p (w/->bytes [(w/uleb 1) (w/uleb 5)])] [(w/uleb (alength p)) p])
      0x05 (let [p (w/->bytes [(w/uleb 1) 0x00 (w/uleb 1)])] [(w/uleb (alength p)) p])
      0x0a (let [p (w/->bytes [(w/uleb 1) (w/uleb (alength code)) code])]
             [(w/uleb (alength p)) p])])))

(defn try-compile [label code]
  (let [r (try (aot/compile-arity (vec code) 0 (count code) HELPERS)
               (catch Exception e
                 (println "  FAIL " label "—" (ex-message e) (pr-str (ex-data e)))
                 (swap! fails inc)
                 nil))]
    (if-not r
      (do (check (str label " — compiles") false) nil)
      (let [mod (module-for (:body r))]
        (spit (str "/tmp/aot-" (hash label) ".wasm") "")
        (with-open [o (java.io.FileOutputStream. (str "/tmp/aot-" (hash label) ".wasm"))]
          (.write o ^bytes mod))
        r))))

;; --- the cases ------------------------------------------------------------
(def CASES
  [["local; return"            [0x06 0x00 0x13]]
   ["const; return"            [0x01 0x00 0x00 0x13]]
   ["int; int; native; return" [0x05 0x01 0x00 0x05 0x02 0x00 0x15 0x00 0x00 0x02 0x13]]
   ;; 0 LOCAL 0 / 2 JUMP_IF_FALSE +6 -> 11 / 5 INT 1 / 8 JUMP +3 -> 14 /
   ;; 11 INT 2 / 14 RETURN
   ["if/else"                  [0x06 0x00 0x0F 0x06 0x00 0x05 0x01 0x00 0x0E 0x03 0x00
                                0x05 0x02 0x00 0x13]]
   ;; 0 INT 0 / 3 SET_LOCAL 0 / 5 LOCAL 0 <- head / 7 JUMP_IF_FALSE +3 -> 13 /
   ;; 10 JUMP -8 -> 5 / 13 LOCAL 0 / 15 RETURN
   ["loop (backward jump)"     [0x05 0x00 0x00 0x08 0x00 0x06 0x00 0x0F 0x03 0x00
                                0x0E 0xF8 0xFF 0x06 0x00 0x13]]
   ["call"                     [0x06 0x00 0x06 0x01 0x11 0x01 0x13]]
   ["vector (not inlined)"     [0x05 0x01 0x00 0x1A 0x01 0x00 0x13]]
   ["self; upval; return"      [0x23 0x09 0x00 0x13]]
   ["dup; pop; nil; return"    [0x02 0x0D 0x0C 0x13]]])

(println "aot: the emitter, one arity per module")
(doseq [[label code] CASES]
  (let [r (try-compile label code)]
    (when r
      (println (format "  %-28s %d chunks, %d wasm bytes, depth %d"
                       label (:chunks r) (count (:body r)) (:depth r))))))

(let [files (for [[label _] CASES] [label (str "/tmp/aot-" (hash label) ".wasm")])]
  (spit "/tmp/aot-validate.mjs"
        (str "import {readFileSync} from 'fs';\n"
             "const cases = [" (string/join ","
               (for [[l f] files] (str "[" (pr-str l) "," (pr-str f) "]"))) "];\n"
             "let bad = 0;\n"
             "for (const [label, f] of cases) {\n"
             "  try { new WebAssembly.Module(readFileSync(f)); console.log('  ok   ' + label); }\n"
             "  catch (e) { bad++; console.log('  FAIL ' + label + ': ' + e.message); }\n"
             "}\n"
             "process.exit(bad ? 1 : 0);\n"))
  (println "aot: each body validated by the engine")
  (let [p (.start (ProcessBuilder. (into-array String ["node" "/tmp/aot-validate.mjs"])))
        out (slurp (.getInputStream p)) err (slurp (.getErrorStream p))]
    (.waitFor p)
    (print out) (print err)
    (when-not (zero? (.exitValue p)) (swap! fails inc))))

(println (if (zero? @fails) "aot emitter: ok" (str "aot emitter: " @fails " FAILURES")))
(System/exit (if (zero? @fails) 0 1))
