;; THE THREE DOORS PRODUCE THE SAME BYTES, ACROSS EVERY TARGET AND BOTH
;; `:optimize` MODES.
;;
;; `DECISIONS.md#compiles-are-byte-reproducible` is the decision; this is the
;; matrix behind it. `test/selfhost-targets.clj` keeps ONE cheap row of this in
;; `bin/check`, split by measurement: a row is a pair of COMPILES at about
;; 1.5-1.9 s each, so the matrix took that suite from 4.7 s to 40.8 s and does not
;; belong on a per-change gate.
;;
;; WHY THE MATRIX RATHER THAN ONE ROW. Two defects were found by asking two doors
;; for the same bytes, and neither would have failed a smaller version of this
;; file:
;;
;;   * `flint.project/topo-order` seeded its worklist from a hash map's keys, so
;;     namespaces ready in the same wave came out in the HOST's hash order.
;;     Whether two doors agreed depended on the namespace NAME -- `t`, `abc` and
;;     `abcde` diverged, `ab`, `abcd`, `prog` and `progx` agreed -- which is why
;;     the names already in the suite never caught it.
;;   * `flint.selfhost` never wrote the image's `FLAG-PERF`, so every `:to :clr`
;;     and `:to :jvm` artifact the native and npm doors emitted under
;;     `:optimize [perf]` asked its port to compile nothing: 1987 arities through
;;     `bin/flint`, 0 through the native CLI. The PLAIN arms alone could not see
;;     it, which is why both modes are here.
;;
;; THE OUTPUT BASENAME IS AN INPUT. `:to :clr` writes it into the assembly name
;; and `:to :jvm` into the class name, and both sit in a string table that moves
;; every offset after them -- so each pair of arms writes to the SAME basename,
;; and a sweep that wrote `X.bb.dll` and `X.nat.dll` reported six divergences
;; that were six filenames.
;;
;; EVERY BLOCK CARRIES A CONTROL, because agreement between two arms that both
;; ignore an input reads exactly like agreement.
(require '[babashka.fs :as fs])

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
(def npm (str root "/sdks/cli/bin/flint.mjs"))
(def work (str (fs/create-temp-dir {:prefix "flint-doors"})))
(def src (str work "/src"))
(fs/create-dirs src)
(spit (str src "/t.cljc") "(ns t)\n(defn main [args] \"ok\")\n")

(defn same? [a b]
  (and (fs/exists? a) (fs/exists? b)
       (= (vec (fs/read-all-bytes a)) (vec (fs/read-all-bytes b)))))

(defn emit
  "Compile the fixture through `door` to `base`, and answer the path written.

  `bin/flint` spells the source root `:src` and takes no `compile` verb; the
  other two spell it `:path`. That is the only difference the doors are allowed
  to have here."
  [door base flags]
  (let [out (str work "/" door "/" base)]
    (fs/create-dirs (str work "/" door))
    (apply sh (case door
                "bb"  (concat [(str root "/bin/flint") ":src" src ":fn" "t/main"] flags [":out" out])
                "nat" (concat [cli "compile" ":path" src ":fn" "t/main"] flags [":out" out])
                "npm" (concat ["node" npm "compile" ":path" src ":fn" "t/main"] flags [":out" out])))
    out))

(if-not (fs/exists? cli)
  (println "  --   no target/release/flint; every row below needs it, so NONE ran")
  (do
    ;; --- `:to :clr`, and the basenames are the interesting input ------------
    ;;
    ;; `a.b.dll` is the case three separately written sanitisers part ways on:
    ;; strip one extension and the assembly is `a_b`, strip greedily and it is
    ;; `a`. `flint.clr/assembly-name` is the only copy of that rule now.
    (doseq [base ["app.dll" "a.b.dll" "9odd-name.v2.dll"]]
      (check-that (str "`:to :clr :out " base "`: all three doors agree byte for byte")
                  (let [a (emit "bb" base [":to" ":clr"])
                        b (emit "nat" base [":to" ":clr"])
                        c (emit "npm" base [":to" ":clr"])]
                    (and (same? a b) (same? a c)))))
    (check-that "the comparison can tell two assembly names apart"
                (not (same? (emit "bb" "app.dll" [":to" ":clr"])
                            (emit "bb" "other.dll" [":to" ":clr"]))))

    ;; --- every target, both modes -------------------------------------------
    ;;
    ;; `:to :wasm` IS NOT HERE ON PURPOSE, and it is the one exception:
    ;; `bin/flint` composes a module where the other two shake a prebuilt one, so
    ;; its wasm is 494 595 bytes against their 662 237. The maintainer's
    ;; instruction is that the artefacts produced by the clj implementation and
    ;; the built-in platform emitters need not match, and `sdks/cli/selftest.mjs`
    ;; holds the native and npm doors to each other for wasm in five rows.
    (doseq [[target base doors] [["clr" "Agree.dll" ["bb" "nat" "npm"]]
                                 ["jvm" "Agree.class" ["bb" "nat" "npm"]]
                                 ["llvm" "agree.ll" ["bb" "nat" "npm"]]]
            [label extra] [["plain" []] [":optimize [perf]" [":optimize" "[perf]"]]]]
      (let [flags (concat [":to" (str ":" target)] extra)
            paths (mapv (fn [d] [d (emit d base flags)]) doors)
            [_ first-path] (first paths)
            bad (remove (fn [[_ pth]] (same? first-path pth)) (rest paths))]
        (check-that (str "`:to :" target "` " label ": "
                         (clojure.string/join ", " doors) " agree byte for byte"
                         (when (seq bad)
                           (str " -- disagreeing: " (clojure.string/join ", " (map first bad)))))
                    (and (fs/exists? first-path) (empty? bad)))))
    ;; THE HARNESS CONTROL. If `emit` dropped its extra flags, every `perf` row
    ;; above would compile exactly like its `plain` twin and all six rows would
    ;; agree for a reason that has nothing to do with the doors.
    (check-that "the comparison can tell :optimize [perf] from plain"
                (not (same? (emit "bb" "ctl-a.ll" [":to" ":llvm"])
                            (emit "bb" "ctl-b.ll" [":to" ":llvm" ":optimize" "[perf]"]))))

    ;; --- and the largest program in the repo, which is the compiler ---------
    ;;
    ;; Everything above compiles two lines. `src/` is about a hundred namespaces,
    ;; and the two doors HASH DIFFERENTLY -- that is what gives this row its
    ;; power, since a hash-ordered collection still reaching an artifact anywhere
    ;; in the pipeline gets a hundred namespaces' worth of chances to show up.
    ;;
    ;; WHAT IT DOES NOT PROVE: that no such collection exists. Agreement between
    ;; two hosts is evidence, not a proof of invariance; the static sweep of the
    ;; 64 map-iteration sites in the pipeline is recorded as open in `ROADMAP.md`.
    ;;
    ;; COST: 10.3 s measured 2026-09-28 (5.18 s + 5.10 s by `time -p`), which is
    ;; why it is one target and not six.
    (let [src-root (str root "/src")
          bb-out (str work "/bb/Compiler.dll")
          nat-out (str work "/nat/Compiler.dll")]
      (sh (str root "/bin/flint") ":src" src-root ":fn" "flint.selfhost/main"
          ":to" ":clr" ":out" bb-out)
      (sh cli "compile" ":path" src-root ":fn" "flint.selfhost/main"
          ":to" ":clr" ":out" nat-out)
      (check-that "the WHOLE COMPILER: bin/flint and the native CLI agree byte for byte"
                  (same? bb-out nat-out)))))

(fs/delete-tree work)
(when (pos? @fails)
  (println (format "door-agreement: %d FAILED" @fails))
  (System/exit 1))
(println "door-agreement: every door agrees, on every target it emits")
