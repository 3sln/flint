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
        (if (some? f)
          {:tx tx :op :return :value (apply f (or (:args m) []))}
          ;; NOT AN INTERNAL ERROR. A name that is absent is the ordinary case
          ;; for a function the shake removed -- only reachable code ships, and
          ;; a string does not keep a var alive (`DECISIONS.md#vars-is-its-own-grant`).
          ;; So it answers like any other failure and says what to do about it.
          {:tx tx :op :throw :kind "IllegalArgumentException"
           :message (str "this image has no `" nm "`; if it should be callable, "
                         "name it in `:exports` so the shake keeps it")}))
      (catch Throwable e
        {:tx tx :op :throw
         :kind (or (some-> e ex-data :kind) "Error")
         :message (or (ex-message e) "the call failed")}))))

(defn- serve-calls
  "Serve one bound port until it closes.

  `receive` answers nil once the port is closed and drained, which is how
  `unbind` stops this: nothing has to be signalled out of band."
  [p]
  (loop []
    (let [m (port/receive p)]
      (when (some? m)
        ;; The SEND may itself fail -- a peer that went away mid-call -- and
        ;; that must not end the loop either: the next message is still
        ;; servable.
        (try (port/send p (answer m)) (catch Throwable _ nil))
        (recur)))))

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
