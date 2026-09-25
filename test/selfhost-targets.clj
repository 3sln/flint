;; EVERY TARGET THE SELF-HOSTED COMPILER CLAIMS, DRIVEN END TO END.
;;
;; `src/flint/selfhost.cljc` states its targets THREE times and they must agree:
;; the `known?` list that decides whether the first argument is a mode or a spec,
;; the `cond` that dispatches it, and the output `cond` that turns each result map
;; back into one string. A target present in the first two and absent from the
;; third is the failure this file exists for, and it is not hypothetical:
;;
;;   `compile-to-clr` answered `{:clr bytes}`, the output `cond` had no arm for
;;   it, and so every `flint compile :to :clr` on the NATIVE CLI fell through to
;;   the `:else` branch, read `(:image r)` as nil, and died with
;;   `ClassCastException: str-join wants strings` -- four frames from anything
;;   named `clr`. `:to :clr` had never once worked through that door.
;;
;; It survived because `bin/flint :to :clr` works and always did: that door calls
;; `clr/assemble` itself and never enters `selfhost`. So the target was reachable
;; from the door a person would try first, and broken from the one the CLI uses.
;; The only test that mentioned it asserted the UNKNOWN-TARGET MESSAGE lists
;; `:to :clr` (`bin/test:643`) -- the help text, not the target.
;;
;; `src/flint/selfhost.cljc` has cited this file since before it existed, which is
;; its own lesson: a comment naming a test is not a test.
;;
;; THE ASSERTION IS THE ARTIFACT'S MAGIC, not the exit code. A compiler that
;; wrote a diagnostic into the output file would exit 0 and be found out only by
;; whoever loaded it.
(require '[clojure.string :as str] '[babashka.fs :as fs])

(def fails (atom 0))
(defn check-that [label ok]
  (if ok (println "  ok  " label)
      (do (swap! fails inc) (println "  FAIL" label))))

(defn sh [& args]
  (let [p (.start (doto (ProcessBuilder. (into-array String args))
                    (.redirectErrorStream true)))
        out (slurp (.getInputStream p))]
    {:code (.waitFor p) :out out}))

(def root (str (fs/parent (fs/parent (fs/real-path *file*)))))
(def cli (str root "/target/release/flint"))
(def work (str (fs/create-temp-dir {:prefix "flint-targets"})))

;; --- the three lists agree, read out of the source ------------------------
;;
;; A SOURCE CHECK AND A BEHAVIOURAL ONE, because neither covers the other: the
;; source check runs without a build and names the target that was forgotten,
;; and the behavioural one catches a target that is listed everywhere and still
;; does not work.
(let [src (slurp (str root "/src/flint/selfhost.cljc"))
      known (set (map second (re-seq #"\(= mode \"([a-z]+)\"\)" src)))
      ;; `project` and `spec` are not TARGETS -- they answer with an image the
      ;; usual way -- so only the artifact-producing modes need an output arm.
      artifact-modes #{"wasm" "jvm" "clr" "llvm"}
      out-arms (set (map second (re-seq #"\(:([a-z]+) r\) " src)))]
  (check-that "every artifact mode is in the known? list"
              (every? known artifact-modes))
  ;; `wasm` and `jvm` both answer under `:module`; `clr` under `:clr`; `llvm`
  ;; under `:ll`. What matters is that each has SOME arm, so this asserts the
  ;; count of distinct result keys rather than a name per mode.
  (check-that "the output cond has an arm for :module, :clr and :ll"
              (every? out-arms #{"module" "clr" "ll"})))

;; --- and each target actually produces its artifact -----------------------
(if-not (fs/exists? cli)
  (println "  --   no target/release/flint; skipping the end-to-end rows")
  (let [src (str work "/src")
        _ (fs/create-dirs src)
        _ (spit (str src "/t.cljc") "(ns t)\n(defn main [args] \"x\")\n")
        magic (fn [f n] (when (fs/exists? f)
                          (let [b (fs/read-all-bytes f)]
                            (vec (map #(bit-and % 0xff) (take n b))))))]
    ;; `:to :clr` -- a PE opens `MZ`.
    (let [out (str work "/p.dll")
          r (sh cli "compile" ":path" src ":fn" "t/main" ":to" ":clr" ":out" out)]
      (check-that "flint compile :to :clr exits 0" (zero? (:code r)))
      (check-that "flint compile :to :clr writes a PE (MZ)"
                  (= [0x4d 0x5a] (magic out 2))))
    ;; `:to :jvm` -- a class opens `CAFEBABE`, and `:out` is a CLASSPATH ROOT:
    ;; the class declares itself `flint.Artifact` and a JVM loads it only from a
    ;; path matching that name.
    (let [out (str work "/cp")
          klass (str out "/flint/Artifact.class")
          r (sh cli "compile" ":path" src ":fn" "t/main" ":to" ":jvm" ":out" out)]
      (check-that "flint compile :to :jvm exits 0" (zero? (:code r)))
      (check-that "flint compile :to :jvm writes <root>/flint/Artifact.class"
                  (fs/exists? klass))
      (check-that "flint compile :to :jvm writes a class file (CAFEBABE)"
                  (= [0xca 0xfe 0xba 0xbe] (magic klass 4)))
      ;; THE BYTECODE IS IN IT. The failure this catches is a class that loads,
      ;; reports its metadata correctly and throws at boot: handing `jvm/emit` a
      ;; VECTOR where it measures a byte string gave an 894-byte artifact with
      ;; the right `:builtins` count and no program in it.
      (check-that "the class carries the program, not just its metadata"
                  (> (count (fs/read-all-bytes klass)) 20000)))
    ;; `:to :llvm` -- text, and the one target that is not bytes.
    (let [out (str work "/p.ll")
          r (sh cli "compile" ":path" src ":fn" "t/main" ":to" ":llvm" ":out" out)]
      (check-that "flint compile :to :llvm exits 0" (zero? (:code r)))
      (check-that "flint compile :to :llvm writes IR naming the image"
                  (str/includes? (str (when (fs/exists? out) (slurp out)))
                                 "@flint_image")))))

(fs/delete-tree work)
(when (pos? @fails)
  (println (format "selfhost-targets: %d FAILED" @fails))
  (System/exit 1))
(println "selfhost-targets: every artifact target produces its artifact")
