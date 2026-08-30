;; Capabilities as host-minted opaque values (`doc/decisions/0021`, `0022`).
;;
;; REWRITTEN 2026-08-30, and what it used to assert is worth recording.
;;
;; This test checked that the SDK refused a forged capability. The cutover
;; moved that decision OUT of the SDK deliberately: whether to allow an open is
;; the business of whoever lends the authority, not of the driver, and a host
;; could not define its own rule while the driver owned it. What was left
;; behind was a hook -- `cap.allow` in `sdks/esm/src/guest.js` -- with no users
;; anywhere, and this file still asserting the behaviour of deleted code. It
;; had been failing on three rows since.
;;
;; So this is the worked example the pattern did not have: a host that issues
;; ids and checks them, in the five lines it takes.
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

;; Against `dist/flint.js`, the file that ships, for the reason the SDK
;; selftest gives: a test against the source passes with the bundle broken.
(spit "out/capdriver.mjs"
      (str "import { readFile } from 'node:fs/promises';\n"
           "import { Compiler } from '../sdks/esm/dist/flint.js';\n"
           "const src = await readFile('test/capability.cljc', 'utf8');\n"
           "const image = (await Compiler.load()).compile({\n"
           "  files: { 'capability.cljc': src }, fn: 'capability/main' });\n"
           ;; THE HOST, implementing the pattern. One grant, id 5 -- arbitrary
           ;; and the host's own business; a guest can carry it and compare it
           ;; and do nothing else with it.
           "const ids = { fs: 5 };\n"
           ;; `allow` is the whole rule: look at what the guest presented, and
           ;; let it through only if the id is the one THIS host issued for
           ;; THIS name. Everything the SDK check used to do, where a host can
           ;; read it and change it.
           "const rule = (name) => ({\n"
           "  allow: (args) => {\n"
           "    const c = args[0] && args[0][':capability'];\n"
           "    return !!c && c.hostId === ids[name];\n"
           "  }, open() {} });\n"
           "const sb = await image.sandbox({ capabilities: { fs: rule('fs') } });\n"
           "process.stdout.write(String((await sb.main()).out));\n"))
(def raw (sh "node" "out/capdriver.mjs"))
(when-not (zero? (:exit raw))
  (println "driver failed:" (:out raw) (:err raw)) (System/exit 1))
(def r (edn/read-string (:out raw)))
(def refused "the host refused to open \"fs\"")

;; A host that HAS a rule applies it to every open, including the ones that
;; present nothing. A host with no rule allows everything, which is the honest
;; default -- capabilities are optional and most hosts want none.
(check "presenting nothing is refused by a host that has a rule"
       (:no-capability r) refused)

;; THE ONE THAT MATTERS. Minting an opaque value is free, so if that opened the
;; filesystem the whole model would be decoration.
;;
;; It DID, on the first run of the original version of this test. A guest-minted
;; value has a host id of 0, and so did "nothing was presented", so the host
;; read a forgery as an absence and fell back to allowing it. `codec.opaque`
;; now REFUSES to issue id 0 for that reason: a forgery must never be able to
;; collide with a grant.
(check "a guest-minted opaque value opens NOTHING" (:with-a-forged-one r) refused)
(check "and guessing the right id does not help, because the guest cannot set it"
       (:with-a-guessed-id r) refused)
(check "an ungranted name is refused before any rule is consulted"
       (:ungranted r) "the host refused to open \"net\"")

;; WHAT THIS CANNOT TEST YET, said out loud rather than left as a gap someone
;; discovers by trusting the row above for more than it says.
;;
;; The positive case -- a host-issued id actually opening -- needs the host to
;; hand the guest a capability, and on wasm there is no way to do that today:
;;
;;   * the entry receives `[argv caps]` with the map EMPTY, by design (`abi.rs`
;;     points at the encoded call path instead);
;;   * the encoded call path does not pump host-port events, so an `open` inside
;;     a `call` never reaches a handler and returns nil.
;;
;; `Program::run_with` on the native runtime DOES mint and project, so the model
;; is implementable there. Until one of those two wasm paths carries an opaque
;; in, `allow` can only ever refuse on this runtime, and every row above is a
;; refusal for exactly that reason.
;;
;; `sdks/esm/src/codec.js` gained `codec.opaque` for this: it could decode a
;; sentinel and not encode one, so a JS host could recognise a capability it had
;; no way to issue. That asymmetry is why the hook had no users.

(if (pos? @fails)
  (do (println "capability:" @fails "FAILURES") (System/exit 1))
  (println "capability: ok"))
