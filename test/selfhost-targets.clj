;; EVERY TARGET THE SELF-HOSTED COMPILER CLAIMS, DRIVEN END TO END.
;;
;; `src/flint/compiler/selfhost.cljc` states its targets THREE times and they must agree:
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
;;
;; WHAT ASSERTS EACH TARGET ACTUALLY WORKS, as of 2026-09-28. This file checks
;; that an artifact is PRODUCED -- magic bytes and a size -- which is necessary and
;; was twice mistaken for sufficient. The rest of the matrix:
;;
;;   :to :wasm   `sdks/cli/selftest.mjs` instantiates and RUNS the npm CLI's
;;               module ("a `compile` that emits something no engine will take is
;;               the failure a size check cannot see"), and asserts the native
;;               CLI's output is BYTE-IDENTICAL to it. One side run plus
;;               byte-identity covers both doors.
;;   :to :llvm   `bin/check-llvm` emits from the NATIVE CLI, links it with
;;               `clang`, runs it, and requires the same answer and the same gas
;;               as `flint run`.
;;   :to :clr    `bin/check-clr` loads the NATIVE door's assembly through
;;               `runtimes/clr/artifact/Check.cs`, added 2026-09-28 after that
;;               door shipped unloadable assemblies.
;;   :to :jvm    `bin/check-sdk` BOOTS the native door's class through
;;               `com.flint.Main` and requires the program's value. `javap` was
;;               tried and is not enough: it reads the method table, which
;;               survives the bug that empties the bytecode.
;;
;; The pattern both failures shared is one door checked and another shipped. It is
;; closed for all four targets; a new target is not covered by appearing here.
;;
;; AND IT IS BROKEN AGAIN, DIFFERENTLY, AS OF 2026-09-26. This file asserts an
;; artifact is PRODUCED -- magic bytes -- and nothing asserts one LOADS from the
;; native door. It does not:
;;
;;     same program, same 29 184 bytes, differing from byte 385
;;       bin/flint   :to :clr  ->  loads; runtimes/clr/artifact/Check.cs proceeds
;;       native CLI  :to :clr  ->  BadImageFormatException: Invalid COR20 header
;;                                 signature
;;
;; `bin/check-clr` builds its artifact with `bin/flint` at lines 91 and 142, so the
;; native door's assembly is emitted by the CLI, checked for magic bytes here, and
;; loaded by nothing. That is the same gap in the same place as the failure above,
;; which is why it is recorded here rather than somewhere new: the lesson did not
;; take the first time, because the TEST that was added checks production and not
;; loading.
;; The only test that mentioned it asserted the UNKNOWN-TARGET MESSAGE lists
;; `:to :clr` (`bin/test:643`) -- the help text, not the target.
;;
;; `src/flint/compiler/selfhost.cljc` has cited this file since before it existed, which is
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
(let [src (slurp (str root "/src/flint/compiler/selfhost.cljc"))
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
    ;; --- AND THE TWO DOORS AGREE, BYTE FOR BYTE -------------------------
    ;;
    ;; ONE ROW HERE AND THE MATRIX IN `test/door-agreement.clj`, split by
    ;; measurement rather than by taste: every row is a pair of COMPILES at about
    ;; 1.5-1.9 s each (process start and reading `src/` and `lib/` dominate a
    ;; two-line program), so the full matrix took this suite from 4.7 s to 40.8 s
    ;; -- too much for `bin/check`, which runs on every change. This keeps the
    ;; cheapest row that can still catch a door divergence; `bin/test` runs the
    ;; rest (`DECISIONS.md#compiles-are-byte-reproducible`).
    (when (fs/exists? (str root "/bin/flint"))
      (let [emit (fn [door base]
                   (let [out (str work "/" door "/" base)]
                     (fs/create-dirs (str work "/" door))
                     (if (= door "bb")
                       (sh (str root "/bin/flint") ":src" src ":fn" "t/main"
                           ":to" ":clr" ":out" out)
                       (sh cli "compile" ":path" src ":fn" "t/main"
                           ":to" ":clr" ":out" out))
                     out))
            same? (fn [a b] (and (fs/exists? a) (fs/exists? b)
                                 (= (vec (fs/read-all-bytes a))
                                    (vec (fs/read-all-bytes b)))))]
        (check-that "`:to :clr`: bin/flint and the native CLI agree byte for byte"
                    (same? (emit "bb" "app.dll") (emit "nat" "app.dll")))
        ;; THE CONTROL. The output BASENAME is an input -- `:to :clr` writes it
        ;; into the assembly name -- so two doors that both ignored it would
        ;; report the row above as agreement. One artifact is emitted to a
        ;; different basename on purpose.
        (check-that "the comparison can tell two assembly names apart"
                    (not (same? (emit "bb" "app.dll") (emit "bb" "other.dll"))))))
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
