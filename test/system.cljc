(ns system
  "The control plane (`DECISIONS.md#bridges-are-the-only-door`), driven over a
  LOCAL channel.

  A local channel rather than a bridge on purpose: what is under test is the
  protocol -- bind, call, unbind, close, and the thread staying alive through
  whatever a call does -- and none of that is about which kind of port carries
  it. A bridge adds a host and an executor to the picture and would make a
  failure here ambiguous between the two."
  (:require [flint.system :as system]
            [flint.port :as port]
            [flint.thread :as thread]
            [clojure.string :as str]))

(defn greet [a b] (str "hi " a " and " b))
(defn boom [] (throw (ex-info "inner blew up" {})))
(defn tally [] 7)

(defn- call! [p tx nm args]
  (port/send p {:tx tx :op :call :fn nm :args args})
  (port/receive p))

(defn main [args]
  ;; Kept past the shake: a string does not hold a var alive
  ;; (`DECISIONS.md#vars-is-its-own-grant`), which is the whole reason
  ;; `:exports` exists.
  (let [_ [greet boom tally]
        [sys-a sys-b] (port/channel "system")
        [call-a call-b] (port/channel "calls")
        _ (thread/spawn (fn [] (system/serve sys-b)))
        _ (port/send sys-a {:op :bind :port call-b})

        ok (call! call-a 1 "system/greet" ["ada" "alan"])
        threw (call! call-a 2 "system/boom" [])
        ;; AFTER the throw, on the SAME port: the call thread has to have
        ;; survived it. This is the row that would catch a loop that dies on
        ;; the first exception, which is what "never irrecoverable" means here.
        after (call! call-a 3 "system/tally" [])
        absent (call! call-a 4 "system/nope" [])

        ;; An op nobody knows must be IGNORED, not fatal -- otherwise every
        ;; addition to the protocol is a breaking change for older sandboxes.
        _ (port/send sys-a {:op :no-such-op})
        still (call! call-a 5 "system/tally" [])

        ;; A NIL MESSAGE IS NOT A GOODBYE. `receive` answers nil for a port
        ;; that is closed and drained AND for a peer that simply sent nil, and
        ;; reading the second as the first ended the serving thread in the
        ;; middle of its work: the port stayed bound with nobody on it, every
        ;; later call on it was lost, and a host learned only when its pump
        ;; guard gave up. This row is that case. It DEADLOCKS rather than
        ;; fails if the loop goes back to believing nil, because the call
        ;; after it is never served -- which the deadlock detector reports.
        _ (port/send call-a nil)
        after-nil (call! call-a 6 "system/tally" [])

        ;; `unbind` closes the bound port, so the call thread's `receive`
        ;; answers nil AND the port says it is closed, which is what ends the
        ;; loop. Nothing is signalled out of band.
        _ (port/send sys-a {:op :unbind :port call-b})
        _ (port/send sys-a {:op :close})]
    (str/join "\n"
      [(str "call " (if (= "hi ada and alan" (:value ok)) "ok" (str "FAIL " ok)))
       (str "throw-is-data " (if (and (= :throw (:op threw))
                                      (str/includes? (str (:message threw)) "inner blew up"))
                               "ok" (str "FAIL " threw)))
       (str "thread-survives-throw " (if (= 7 (:value after)) "ok" (str "FAIL " after)))
       (str "absent-name-says-exports "
            (if (and (= :throw (:op absent))
                     (str/includes? (str (:message absent)) ":exports"))
              "ok" (str "FAIL " absent)))
       (str "unknown-op-ignored " (if (= 7 (:value still)) "ok" (str "FAIL " still)))
       (str "nil-message-is-not-a-close "
            (if (= 7 (:value after-nil)) "ok" (str "FAIL " after-nil)))
       (str "tx-preserved " (if (= [1 2 3 4 5 6] [(:tx ok) (:tx threw) (:tx after)
                                                  (:tx absent) (:tx still) (:tx after-nil)])
                              "ok" "FAIL"))])))
