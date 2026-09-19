;; Capabilities as host-minted opaque values (`DECISIONS.md#cli`, `opaque-values`).
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
        ;; STDERR IS DRAINED ON ITS OWN THREAD (`DECISIONS.md#the-codec-is-guest-code`,
        ;; "the test helper deadlocked"). Reading stdout to completion and
        ;; stderr after DEADLOCKS the moment a child writes more than a pipe
        ;; buffer to stderr: the child blocks writing, this blocks reading, and
        ;; neither moves again.
        err (future (slurp (.getErrorStream p)))
        out (slurp (.getInputStream p))
        err @err]
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
           "  files: { 'capability.cljc': src }, fn: 'capability/main',\n"
           "  exports: ['capability/main', 'capability/granted'] });\n"
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
           "process.stdout.write(String(await sb.call('capability/main', [[]])));\n"
           ;; A SECOND sandbox, with a host that allows, for the positive case.
           ;; Separate rather than another name on the first, because a rule
           ;; that both refuses forgeries and allows this would have to be two
           ;; rules anyway, and one of them would not be the rule under test.
           "let seen = null;\n"
           "const sb2 = await image.sandbox({ capabilities: { fs: {\n"
           "  allow: () => true, open() {},\n"
           "  message: (p, v) => { seen = v; } } } });\n"
           "const got = await sb2.call('capability/granted', [[]]);\n"
           "process.stdout.write('\\n{:granted ' + JSON.stringify(got) +\n"
           "  ' :granted-message ' + JSON.stringify(seen) + '}');\n"
           ))
(def raw (sh "node" "out/capdriver.mjs"))
(when-not (zero? (:exit raw))
  (println "driver failed:" (:out raw) (:err raw)) (System/exit 1))
;; TWO forms on stdout, one per sandbox, merged here. One driver run rather
;; than two, because compiling the fixture twice is the slow part.
(def r (let [[a b] (str/split-lines (str/trim (:out raw)))]
         (merge (edn/read-string a) (edn/read-string (or b "{}")))))
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

;; THE POSITIVE CASE, which this file used to say could not be tested here.
;;
;; What blocked it was that the encoded call path did not pump host-port events,
;; so an `open` inside a `call` never reached a handler and answered nil. It does
;; now: a host that names a capability gets a SYSTEM PORT, and a call over the
;; system port is a message the pump serves, so the whole open-grant-send round
;; trip happens inside one `call` (`DECISIONS.md#ports-are-the-hosts`).
;;
;; Asserted as the round trip rather than as "open returned something", because
;; a port that cannot carry a message is not a granted capability.
(check "a granted open returns a real port, inside an ordinary call"
       (:granted r) "opened true")
(check "and the host receives what is sent on it" (:granted-message r) "ping")

;; WHAT THIS STILL CANNOT TEST, said out loud rather than left as a gap someone
;; discovers by trusting a row above for more than it says.
;;
;; A host-issued capability reaching the guest IN THE FIRST PLACE. The rows above
;; cover a host that refuses, and the two new ones cover a host that allows --
;; but `allow` there returns true unconditionally, because the guest has nothing
;; host-issued to present. The entry receives `[argv caps]` with the map empty,
;; by design (`abi.rs` points at the encoded call path instead), so a guest
;; cannot hold an opaque the host minted and hand it back.
;;
;; The way in exists now and is not wired: `flint.host/request` can answer with
;; any value, an opaque among them (`DECISIONS.md#workspace-capabilities` step 7). A guest could
;; ask for its capability and present what it was given. That is worth doing,
;; and it is what the row below would then assert instead of `allow: () => true`.
;;
;; What used to be written here -- "the encoded call path does not pump host-port
;; events, so an `open` inside a `call` never reaches a handler and returns nil"
;; -- is no longer true, and the two rows above are the measurement that says so.
;;
;; `sdks/esm/src/codec.js` gained `codec.opaque` for this: it could decode a
;; sentinel and not encode one, so a JS host could recognise a capability it had
;; no way to issue. That asymmetry is why the hook had no users.

(if (pos? @fails)
  (do (println "capability:" @fails "FAILURES") (System/exit 1))
  (println "capability: ok"))
