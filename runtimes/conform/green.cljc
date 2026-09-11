(ns green
  "Green threads and local channels, on every runtime.

  `threads.cljc` next door tests SHARED STATE -- an atom under real host
  threads -- which is a different problem. This one is about flint's own
  concurrency: spawn, join, yield, and a channel that parks both ends.

  Every answer here has to be the same on all three runtimes, and one of them
  is about ORDER rather than values: the scheduler is deterministic
  (`DECISIONS.md#threads-and-ports`), so `:order` is a fact and not a race. If two ports
  ever disagree on it, they disagree about scheduling and the answers to
  everything else are luck."
  (:require [flint.thread :as t] [flint.port :as p]))

(defn- squares [n] (reduce + 0 (map (fn [i] (* i i)) (range n))))

(def ^:dynamic *where* :root)

(defn- inherited
  "A SPAWNED THREAD INHERITS A SNAPSHOT of the spawner's dynamic bindings.

  `flint/bindings` and `flint/set-bindings` are how that snapshot is taken and
  installed, and they were the last two builtins on the unwatched list that a
  conformance program could reach. Nothing exercised them because nothing here
  ever spawned a thread from inside a `binding`.

  A SNAPSHOT and not a reference, which is the part with an edge: the child
  sees what was bound when it was SPAWNED, a rebinding in the spawner
  afterwards does not reach it, and the child's own `binding` does not escape
  back. `flint/thread.cljc` promises exactly that, and until now the promise
  was only in the docstring.

  The rebinding case needs the child to still be running when the spawner
  rebinds, so it yields first: the scheduler is deterministic (`threads-and-ports`), so
  `yield` puts it back in the queue and the spawner reaches the rebinding
  before the child reads. Reading `:first` there is the snapshot; reading
  `:second` would mean it had a reference to a binding stack that moved."
  []
  (let [outside (t/spawn (fn [] *where*))
        [inside child]
        (binding [*where* :spawner]
          (let [a (t/spawn (fn [] *where*))
                b (t/spawn (fn [] (binding [*where* :child] *where*)))]
            [(t/join a) (t/join b)]))
        parked (binding [*where* :first] (t/spawn (fn [] (t/yield) *where*)))
        after-rebinding (binding [*where* :second] (t/join parked))]
    {:outside-sees-root (t/join outside)
     :child-sees-the-binding inside
     :childs-own-binding-is-its-own child
     :rebinding-does-not-reach-it after-rebinding
     ;; AND THE SPAWNER IS BACK where it started, so nothing leaked outward.
     :spawner-restored *where*}))

(defn- joined
  "Spawn, then join. The simplest thing that has to work before anything else
  can: a thread runs, finishes, and hands its answer back."
  []
  [(t/join (t/spawn (fn [] (squares 10))))
   (t/join (t/spawn (fn [] :done)))])

(defn- through-a-channel
  "A one-slot buffer forces BOTH parks: the sender blocks on a full buffer and
  the receiver on an empty one. Five messages through one slot is four of each."
  []
  (let [[a b] (p/channel 1 "one-slot")
        w (t/spawn (fn [] (dotimes [i 5] (p/send a i)) :sent))
        got (loop [acc []]
              (if (= 5 (count acc)) acc (recur (conj acc (p/receive b)))))]
    {:got got :worker (t/join w) :state (t/state w)}))

(defn- interleaving
  "The order three threads and the main thread actually run in.

  A VALUE, not a comment: it is the same on every runtime or the schedulers
  differ. `yield` is what makes the interleaving observable at all -- without
  it each thread would run to completion and the order would say nothing."
  []
  (let [[in out] (p/channel 16 "log")]
    (doseq [tag [:a :b :c]]
      (t/spawn (fn [] (p/send in tag) (t/yield) (p/send in tag))))
    (loop [acc []]
      (if (= 6 (count acc)) acc (recur (conj acc (p/receive out)))))))

(defn- nested
  "A thread that spawns a thread. The parent's continuation has to survive its
  child parking, which is the case a host-stack continuation cannot do at all."
  []
  (t/join (t/spawn (fn []
                     (let [inner (t/spawn (fn [] (squares 5)))]
                       (+ 1 (t/join inner)))))))

(defn- delegated
  "An ENDPOINT sent through a channel, and then used by whoever received it.

  `DECISIONS.md#host-abi` refused this outright -- \"an endpoint cannot be
  delegated at run time\" -- and `structured-ports` reverses it, which is what makes a
  capability something a program can hand on rather than only hold. Between two
  green threads there is no encoding involved at all: both ends live in one
  heap, so the port that arrives IS the port that was sent.

  Checked by USING it rather than by inspecting it: the receiving thread is
  given an endpoint it never opened, sends through it, and the answer comes
  back out the other end. A port that arrived as a copy, or as a handle to
  nothing, would fail here rather than look right."
  []
  (let [[out-a out-b] (p/channel 4 "outer")
        [in-a in-b] (p/channel 4 "inner")
        w (t/spawn (fn []
                     (let [got (p/receive out-b)]
                       (p/send got :sent-through-a-delegated-port)
                       (p/port? got))))]
    (p/send out-a in-a)
    {:received (p/receive in-b)
     :is-a-port (t/join w)
     ;; The label travels with it, so what arrived is recognisably the same
     ;; endpoint and not merely something port-shaped.
     :label (p/label in-a)}))

(defn- identity-of
  "WHO A THREAD IS, which nothing asked before.

  `self`, `thread?`, `thread-id` and `result` were reached by no conformance
  program -- `green.cljc` used `spawn`, `join`, `state` and `yield` and
  stopped there. They are the four accessors a scheduler has to agree about
  for a thread to be a value rather than a side effect.

  The ids are compared RELATIVELY, never printed: an absolute id is an
  allocation counter and would pin this file to how many threads the runtime
  happened to make before it got here. That two threads differ, and that a
  thread is not its spawner, is the part that has to hold everywhere."
  []
  (let [me (t/self)
        a (t/spawn (fn [] (t/thread-id (t/self))))
        b (t/spawn (fn [] (throw (ex-info "deliberate" {}))))
        ida (t/join a)]
    ;; `b` FAILED, so `join` rethrows and `result` is the value it threw.
    ;; Both have to be true at once, and a runtime that lost the failure
    ;; would look fine on either one alone.
    {:self-is-a-thread [(t/thread? me) (t/thread? a) (t/thread? 1) (t/thread? nil)]
     :ids-differ [(= ida (t/thread-id a)) (= ida (t/thread-id me))]
     :done (t/state a)
     :result-of-done (= ida (t/result a))
     :failed [(t/state b)
              (try (t/join b) :no-throw (catch Exception e (ex-message e)))
              (let [r (t/result b)] (if (nil? r) :nil (ex-message r)))]}))

(defn- closing
  "WHAT A CLOSED PORT DOES, and what a port says about itself.

  `bridge?`, `port-id` and `close` were three more of the builtins nothing
  reached. Closing is the interesting one: `close` wakes anybody parked and
  they read end-of-stream, so the answers here are about a decision each
  runtime had to make separately -- what a receive after close gives, what a
  send after close does, and what the state becomes.

  A channel is NOT a bridge, which is the distinction `bridge?` exists to
  draw: both ends of a channel live in this heap and nothing is encoded.

  Ids are compared to each OTHER and never printed, for the reason the thread
  ids are: an absolute one is a counter, and asserting it would pin this file
  to how many ports happened to exist first."
  []
  (let [[a b] (p/channel 2 "closing")
        [c d] (p/channel 1 "other")]
    (p/send a :before)
    (p/close a)
    {:not-a-bridge [(p/bridge? a) (p/bridge? b)]
     ;; The two ENDS of one channel are distinct ports, and two different
     ;; channels are distinct again.
     :ids [(= (p/port-id a) (p/port-id a)) (= (p/port-id a) (p/port-id c))]
     :closed [(p/closed? a) (p/state a)]
     ;; A MESSAGE ALREADY SENT SURVIVES THE CLOSE, and the reader sees
     ;; end-of-stream only once the buffer is empty.
     :drains [(str (p/receive b)) (str (p/receive b))]
     :send-after (try (p/send a :after) :sent
                      (catch Exception e (flint.rt/ex-kind e)))
     ;; Closing the OTHER end of an untouched channel, then reading.
     :other (do (p/close d) [(p/closed? d) (str (p/receive c))])}))

(defn main [_]
  (pr-str {:joined  (joined)
           :inherited (inherited)
           :closing (closing)
           :identity (identity-of)
           :channel (through-a-channel)
           :order   (interleaving)
           :nested  (nested)
           :delegated (delegated)}))
