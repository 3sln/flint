(ns hostport
  "HOST PORTS, on every runtime.

  In its OWN directory rather than beside the other conformance programs,
  because it is not one: `runtimes/conform` holds programs a runtime can run on
  its own and be compared on, and this one cannot run at all without a host
  answering it. Left next door, the gate's `for src in runtimes/conform/*.cljc`
  loop ran it with nobody on the other end and reported three failures for a
  program that was working exactly as designed.

  `green.cljc` next door covers what a program can do on its own -- spawn,
  join, a channel between two green threads. This covers what it cannot: a
  capability it has to ASK the host for, bytes the host pushes in, and the
  event queue the two talk over.

  The program is only half the test. The other half is the DRIVER -- the host
  side -- and each runtime has its own (`hostports.rs`, `RtHostPorts.java`,
  `--rt-hostports`). All three drive this same image through the same script
  and must produce the same transcript, which is the only way to find out
  whether three separately written implementations of a protocol are one
  protocol."
  (:require [flint.port :as p]))

(defn main [_]
  (let [;; A capability the host has to grant. This parks until it answers.
        fs (p/open "fs")
        ;; What the host pushed in while we were away.
        a (p/receive fs)
        ;; Something back the other way, which leaves as one event.
        _ (p/send fs "ack")
        ;; And what the host said next.
        b (p/receive fs)
        ;; The host hangs up: drained and closed reads as end of stream, which
        ;; is `nil` and not an error.
        c (p/receive fs)]
    (str "[" a " " b " " (pr-str c) " " (name (p/state fs)) "]")))
