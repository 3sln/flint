;; A HOST-REQUESTED, STREAMED SNAPSHOT, on native and on wasm (`DECISIONS.md#snapshots`).
;;
;; One fixture, `test/snapstream/snap.cljc`, compiled ONCE into a production
;; module. Native runs the image that module carries, so the two share a
;; fingerprint and a stream one writes is a stream the other must take:
;;
;;   1. `cargo test -p flint-cli snapstream` -- native: the stream is the one-shot
;;      export, the copy carries on and does not stream again, and every guest
;;      route to the builtin is refused while the host's request is served;
;;   2. `node test/snapstream.mjs` -- the same on wasm, through the production
;;      ABI (`flint_live_import`), and native's stream imported there.
;;
;; The JVM and the CLR run the same fixture in `bin/conform-hosts`, which is
;; where their builds are.
(require '[clojure.string :as str] '[babashka.fs :as fs] '[babashka.process :as p])

;; A PRODUCTION build of the units, because "shelving works in production" is
;; the claim: a diagnostics module also links the snapshot unit, and passing
;; there would say nothing about a module that does not.
(let [mode (when (fs/exists? "units/.build-mode") (str/trim (slurp "units/.build-mode")))]
  (when (and mode (not= mode "production"))
    (println "this test needs a PRODUCTION build of the units; units/.build-mode says" mode)
    (println "  run ./bin/build-units")
    (System/exit 1)))

(def out "out/snapstream")
(fs/create-dirs out)

(defn run! [label env & cmd]
  (let [r (apply p/shell {:out :string :err :string :continue true
                          :extra-env env} cmd)]
    (when-not (zero? (:exit r))
      (println (:out r) (:err r))
      (println "  FAIL" label)
      (System/exit 1))
    r))

(println "snapshots, host-requested and streamed")

(run! "the fixture compiles to a production module" {}
      "./bin/flint" ":src" "test/snapstream" ":fn" "snap/main" ":out" (str out "/snap.wasm"))

;; ABSOLUTE: `cargo test` runs in the crate's own directory.
(let [r (run! "native" {"FLINT_SNAPSTREAM_IMG" (str (fs/absolutize (str out "/snap.wasm")))
                        "FLINT_SNAPSTREAM_OUT" (str (fs/absolutize (str out "/native.stream")))}
              "cargo" "test" "--release" "-q" "-p" "flint-cli" "snapstream")
      line (some #(when (str/starts-with? % "test result") %) (str/split-lines (:out r)))]
  ;; "2 passed" and not merely "ok": a filter that matched nothing passes too.
  (if (and line (str/includes? line "2 passed"))
    (println "  ok   native:" line)
    (do (println (:out r)) (println "  FAIL native ran" (pr-str line)) (System/exit 1))))

(let [r (run! "wasm" {} "node" "test/snapstream.mjs" (str out "/snap.wasm")
              (str out "/native.stream"))]
  (print (:out r)))
