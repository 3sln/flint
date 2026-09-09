(ns green
  "Green threads and local channels, on every runtime.

  `threads.cljc` next door tests SHARED STATE -- an atom under real host
  threads -- which is a different problem. This one is about flint's own
  concurrency: spawn, join, yield, and a channel that parks both ends.

  Every answer here has to be the same on all three runtimes, and one of them
  is about ORDER rather than values: the scheduler is deterministic
  (`doc/decisions/0005`), so `:order` is a fact and not a race. If two ports
  ever disagree on it, they disagree about scheduling and the answers to
  everything else are luck."
  (:require [flint.thread :as t] [flint.port :as p]))

(defn- squares [n] (reduce + 0 (map (fn [i] (* i i)) (range n))))

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

  `doc/decisions/0006` refused this outright -- \"an endpoint cannot be
  delegated at run time\" -- and `0025` reverses it, which is what makes a
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

(defn main [_]
  (pr-str {:joined  (joined)
           :identity (identity-of)
           :channel (through-a-channel)
           :order   (interleaving)
           :nested  (nested)
           :delegated (delegated)}))
