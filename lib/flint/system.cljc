(ns flint.system
  "A sandbox's control plane, as flint code rather than runtime code.

  The whole of `DECISIONS.md#bridges-are-the-only-door` that is not a
  primitive. Bootstrap mints a closure over `serve` and spawns it as a green
  thread parked on the system port; from then on the only way to reach this
  sandbox is to put a message in a bridge.

  **This is flint and not Rust on purpose.** The alternative was the same
  protocol hand-written in `conc.rs`, `Conc.java` and `Conc.cs`, three copies
  kept in step by care. Here it is compiled into the image, so every runtime
  gets the identical protocol from the identical source, and what each runtime
  has to implement shrinks to spawning this closure.

  ## The protocol

  The system port carries CONTROL ONLY:

      {:op :bind   :port p}   run calls arriving on `p`
      {:op :unbind :port p}   stop
      {:op :close}            this sandbox is done

  Calls never come here. They go on a bound port, and answers go back on it:

      {:tx n :op :call :fn \"ns/f\" :args [..]}
      {:tx n :op :return :value v}
      {:tx n :op :throw  :kind \"..\" :message \"..\"}

  ## Why calls are not served on this thread

  Because a call may park -- open a port and wait -- and a parked call on this
  thread would park the control plane with it, leaving `close` stuck behind
  work that is itself waiting. And because `never becomes irrecoverable` is
  then a property held by catching everything, where one missed edge takes out
  the sandbox's only door.

  Guest code never runs here, so guest code cannot kill this. That is the
  difference between a guarantee and a discipline.

  ## One bound port is a queue

  A call thread serves its port serially, in arrival order. Several calls may
  be outstanding; they are processed one at a time. Concurrency is had by
  binding a second port, so its cost is visible rather than a thread appearing
  per call."
  (:require [flint.port :as port]
            [flint.thread :as thread]))

;; ---------------------------------------------------------------- calls

(defn- answer
  "What to send back for one call, as data.

  Separate from the loop that sends it so the loop has nothing in it that can
  throw: this is where guest code runs, and everything it can do is caught."
  [m]
  (let [tx (:tx m)]
    (try
      (let [nm (:fn m)
            f (flint.rt/var-named nm)]
        ;; CALLABLE, not merely present. `some?` is satisfied by anything the
        ;; var happens to hold, so a slot holding the wrong value reached
        ;; `apply` and failed there -- and `apply`'s message names the KIND it
        ;; was handed and the arity it was called with, three layers from the
        ;; name that resolved wrongly. Asking the real question here means the
        ;; answer says which NAME is broken.
        (if (fn? f)
          {:tx tx :op :return :value (apply f (or (:args m) []))}
          ;; NOT AN INTERNAL ERROR. A name that is absent is the ordinary case
          ;; for a function the shake removed -- only reachable code ships, and
          ;; a string does not keep a var alive (`DECISIONS.md#vars-is-its-own-grant`).
          ;; So it answers like any other failure and says what to do about it.
          {:tx tx :op :throw :kind "IllegalArgumentException"
           :message (str "this image has no callable `" nm "`"
                         (if (nil? f)
                           ""
                           (str " -- its var holds a " (str (flint.rt/kind f))))
                         "; if it should be callable, "
                         "name it in `:exports` so the shake keeps it")}))
      (catch Throwable e
        {:tx tx :op :throw
         ;; THE KIND A `catch` SELECTS ON, not the word "Error".
         ;;
         ;; `ex-data :kind` stays first because a guest may declare its own;
         ;; what was missing is the fallback, and the gap was not cosmetic. A
         ;; runtime error carries its kind in the object's `EX_KIND` slot
         ;; rather than in its data map, so EVERY one of them -- gas,
         ;; ClassCastException, the memory cap -- reached a host as the
         ;; generic "Error". Measured against a pre-control-plane run: the
         ;; gas error read `ResourceExhausted` before calls were served
         ;; through here and `Error` afterwards, because the throw now passes
         ;; through this catch on its way out.
         ;;
         ;; `DECISIONS.md#bridges-are-the-only-door` says failure is data. A
         ;; kind every failure shares is not data a host can act on.
         :kind (or (some-> e ex-data :kind) (flint.rt/ex-kind e) "Error")
         :message (or (ex-message e) "the call failed")}))))

(defn- serve-calls
  "Serve one bound port until it closes.

  `receive` answers nil once the port is closed and drained, which is how
  `unbind` stops this: nothing has to be signalled out of band -- but nil is
  ASKED ABOUT rather than believed; see the loop's else branch."
  [p]
  (loop []
    (let [m (port/receive p)]
      (if (some? m)
        (do
          ;; The SEND may itself fail -- a peer that went away mid-call -- and
          ;; that must not end the loop either: the next message is still
          ;; servable.
          ;;
          ;; A DROPPED ANSWER IS A HANG. The caller is pumping for THIS `:tx`
          ;; and nothing else will ever arrive for it, so swallowing the failure
          ;; turns a reportable error into a host that spins to its guard and
          ;; gives up -- which is how every reply that could not be encoded has
          ;; presented (`DECISIONS.md#the-codec-is-guest-code`).
          ;;
          ;; So the failure is ANSWERED: a small message carrying the same `:tx`,
          ;; which is the one thing certain to fit where the real answer did not.
          ;; If even that cannot be sent the loop still continues, because the
          ;; peer really may be gone -- and then there is no one to tell.
          ;;
          ;; HELD BACK ONCE, and the reason is worth keeping. `flint.system` ships
          ;; in every module, so this code grows every image, and when it was
          ;; first written a reply within a few kilobytes of the encoder's ceiling
          ;; crossed it and a passing compile started failing. That ceiling was
          ;; the stale-writer bug in `wire-str`, since fixed -- a 2 MB reply
          ;; crosses now -- so the objection is gone and the diagnostic is worth
          ;; more than the bytes.
          ;; BUILDING THE ANSWER AND SENDING IT ARE SEPARATE FAILURES, and one
          ;; `try` around both cannot say which happened. `answer` catches
          ;; everything guest code can do, so a throw escaping it is a fault in
          ;; the control plane itself rather than in the call -- and reporting
          ;; that as "could not be sent back" sends the reader to the encoder for
          ;; a bug that is nowhere near it.
          (let [reply (try (answer m)
                           (catch Throwable e
                             {:tx (:tx m) :op :throw :kind "AnswerFailed"
                              :message (str "building this call's answer failed: "
                                            (ex-message e))}))]
            (try (port/send p reply)
                 (catch Throwable e
                   (try (port/send p {:tx (:tx m) :op :throw
                                      :kind "SendFailed"
                                      :message (str "this call's answer could not be "
                                                    "sent back: " (ex-message e))})
                        (catch Throwable _ nil)))))
          (recur))
        ;; NIL IS NOT PROOF THE PORT ENDED, and treating it as proof cost this
        ;; sandbox a call. `flint.port/receive` on a bridge is
        ;;
        ;;     (let [r (flint.rt/port-receive-reader p)]
        ;;       (when (some? r) (wire/read-from r)))
        ;;
        ;; so it answers nil for THREE different things: the port really is
        ;; closed and drained, the peer sent a nil, or the decode produced
        ;; nothing. Only the first is an end of stream. Reading the other two
        ;; as one ended this thread mid-service: the port stayed bound with no
        ;; server on it, every later call on it was lost, and the host learned
        ;; nothing until its pump guard gave up a million iterations later.
        ;;
        ;; Measured, and it needs no exotic state: a host that delivers a bare
        ;; `nil` on a bound port kills that port's thread. Three lines against
        ;; any module (`doc/goals/kin-port.md`).
        ;;
        ;; So ASK. `closed?` covers closed, half-closed and orphaned -- every
        ;; way nothing further can arrive -- and anything else means this was a
        ;; message we could not serve, not a goodbye. Carrying on cannot spin:
        ;; the nil consumed a message, so the next `receive` parks like any
        ;; other, and a port that IS finished answers `closed?` true and ends
        ;; the loop exactly as before.
        (when-not (port/closed? p)
          (recur))))))

;; -------------------------------------------------------------- control

(defn- control
  "One control message, against the ports bound so far.

  Answers `[keep-going? bound]`. Control is not request/response -- nothing is
  sent back -- but it does carry state, because `close` has to be TOTAL: a
  sandbox that shut its front door while a call thread stayed parked on a bound
  port has not closed, it has leaked a thread nothing can ever wake. That is
  exactly what the first version did, and the deadlock detector named it."
  [m bound]
  (case (:op m)
    :bind (let [p (:port m)]
            (thread/spawn (fn [] (serve-calls p)))
            [true (conj bound p)])
    ;; Closing the bound port is the whole of unbind: the call thread's
    ;; `receive` answers nil and its loop ends. A thread told to stop by the
    ;; thing it is parked on needs no second channel to be told on.
    :unbind (let [p (:port m)]
              (port/close p)
              [true (filterv (fn [x] (not (identical? x p))) bound)])
    :close (do (doseq [p bound] (port/close p))
               [false []])
    ;; UNKNOWN OPS ARE IGNORED, not fatal. A newer host talking to an older
    ;; sandbox is the case this protects, and dying on an op we do not know
    ;; would make every addition a breaking change.
    [true bound]))

(defn serve
  "The system thread: control messages until told to close.

  **Total by construction.** Everything that can throw is inside `control` or
  `answer`, both of which catch; a message that is not a map, an op that does
  not exist, a bind of something that is not a port -- none of them end this
  loop. It ends when `:close` arrives or the system port does, and either way
  it closes what it bound on the way out."
  [sys]
  (loop [bound []]
    (let [m (port/receive sys)]
      (if (nil? m)
        ;; The system port went away. Still close what we bound: the sandbox is
        ;; over either way, and a parked call thread outlives it otherwise.
        (doseq [p bound] (port/close p))
        (let [[go bound*] (try (control m bound) (catch Throwable _ [true bound]))]
          (if go
            (recur bound*)
            nil))))))

(defn boot
  "The control plane, as a THUNK bootstrap can spawn directly.

  A green thread takes no arguments, so the port cannot be handed in -- it is
  fetched. That is why `flint/system-port` exists, and why this is a `defn`
  rather than a closure bootstrap builds: taking a var's value and spawning it
  runs NO guest code, where calling a flint function to build a closure
  re-enters the scheduler from inside itself. Measured
  (`DECISIONS.md#bridges-are-the-only-door`): the nested `drive` is what made
  the first version silently never start."
  []
  (serve (flint.rt/system-port)))
