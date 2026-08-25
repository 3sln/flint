;; Capabilities as host-minted opaque values (`doc/decisions/0021`, `0022`).
(require '[clojure.string :as str] '[clojure.edn :as edn])

(defn sh [& args]
  (let [p (.start (ProcessBuilder. (into-array String args)))
        out (slurp (.getInputStream p)) err (slurp (.getErrorStream p))]
    (.waitFor p) {:exit (.exitValue p) :out out :err err}))

(def fails (atom 0))
(defn check [label actual expected]
  (if (= actual expected)
    (println "  ok  " label)
    (do (swap! fails inc)
        (println "  FAIL" label "\n        expected" (pr-str expected)
                 "\n        got     " (pr-str actual)))))

(println "capability: possession is not the check, the grant table is (0022)")
(let [b (sh "./bin/flint" ":src" "test" ":fn" "capability/main" ":out" "out/capability.wasm")]
  (when-not (zero? (:exit b)) (println "build failed:" (:out b) (:err b)) (System/exit 1)))

(def driver
  (str "import('./host/flint.mjs').then(async (m) => {"
       "const {module} = await m.load('out/capability.wasm');"
       "const i = m.instantiate(module);"
       "i.capabilities({fs: {open(){}}, http: {open(){}}});"
       "process.stdout.write(i.main().out);})"))
(def r (edn/read-string (:out (sh "node" "-e" driver))))
(def refused "the host refused the capability \"fs\"")

;; Opening by name alone still works: every program written before capabilities
;; existed does exactly that, and the model has to not break them.
(check "presenting nothing still opens a granted capability" (:no-capability r) "opened")
(check "the capability the host issued opens it" (:with-the-real-one r) "opened")

;; THE ONE THAT MATTERS. Minting an opaque value is free -- `(opaque "fs")` is
;; something any program can write -- so if that opened the filesystem, the whole
;; model would be decoration.
;;
;; It DID, on the first run. A guest-minted value has a host id of 0, and so did
;; "nothing was presented", so the host read a forgery as an absence and fell
;; back to allowing it. Presenting garbage is now distinct from presenting
;; nothing, and only the second is allowed.
(check "a guest-minted opaque value opens NOTHING" (:with-a-forged-one r) refused)
(check "a real capability for a different name opens nothing"
       (:with-the-wrong-one r) refused)
(check "an ungranted name is refused whatever is presented"
       (:ungranted r) "the host refused the capability \"net\"")

(println (if (zero? @fails) "capability: ok" (str "capability: " @fails " FAILURES")))
(System/exit (if (zero? @fails) 0 1))
