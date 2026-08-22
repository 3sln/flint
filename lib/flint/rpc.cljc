(ns flint.rpc
  "Request/response over a port.

  A port is a **one-way** message stream, and almost every real capability is
  request/response: a document store, a key-value store, an HTTP client. Without
  this every driver reinvents correlation ids, and reinvents them differently —
  so it is here once (`doc/decisions/0008`).

      (let [c (rpc/client port)]
        (rpc/call c {:op :get :key \"a\"})        ; parks until the reply arrives
        (rpc/call-seq c {:op :scan :prefix \"a\"}) ; several replies, in order
        (rpc/close c))

  ## How it works, and why a reader thread

  Each call is tagged with an id. A dedicated **reader green thread** takes
  messages off the port and hands each one to the call that is waiting for it,
  through a private one-slot channel. That is what makes concurrent calls safe:
  without a demultiplexer, two threads receiving from the same port would eat
  each other's replies.

  The waiting thread **parks**; it does not spin. A spinning waiter is always
  runnable, so the scheduler would never hand control back to the host and the
  reply would never arrive.

  ## Several replies to one request

  `call-seq` exists because an answer may not fit in memory at once. The server
  marks every message but the last `{:final false}`, and the caller processes and
  releases each wave before the next is asked for — which is how 0008's document
  fetch stays inside a memory budget. Waves arrive in the order the server sent
  them.

  ## Cancellation, and the timeout that is not here

  `cancel` tells the server to stop and releases the caller's slot. **There is no
  timeout in flint, because flint has no clock** — nothing in a pure logic
  executor can tell the time. Put a deadline in the request and let the host
  enforce it; a timeout is a property of the world, not of the language."
  (:require [flint.port :as p]
            [flint.thread :as t]))

(defn- next-id! [c]
  (let [n (inc @(:seq c))]
    (reset! (:seq c) n)
    n))

(defn client
  "Wrap a port in a request/response client. Spawns one reader thread."
  [port]
  (let [c {:port port
           :seq (atom 0)
           :waiting (atom {})
           :reader (atom nil)}
        reader (t/spawn
                (fn []
                  (loop []
                    (let [msg (p/receive port)]
                      (when (some? msg)
                        (when-let [box (get @(:waiting c) (:id msg))]
                          ;; A one-slot channel per call: the reply lands there
                          ;; and the parked caller wakes with it.
                          (p/send box msg))
                        (recur))))
                  ;; The port is finished; wake everyone still waiting so nobody
                  ;; is left parked on a stream that has ended.
                  (doseq [e @(:waiting c)]
                    (p/send (val e) {:id (key e) :final true :error "the port closed"}))
                  :reader-done))]
    (reset! (:reader c) reader)
    c))

(defn- open-call! [c req]
  (let [id (next-id! c)
        [tx rx] (p/channel 1 "rpc")]
    (swap! (:waiting c) assoc id tx)
    (p/send (:port c) (assoc req :id id))
    [id rx]))

(defn- close-call! [c id]
  (swap! (:waiting c) dissoc id))

(defn- check [msg]
  (if (:error msg)
    (throw (ex-info (str "rpc: " (:error msg)) {:reply msg}))
    msg))

(defn call
  "Send `req` and park until its reply arrives. Returns the reply's `:body`.
  A reply carrying `:error` is thrown, so a caller that ignores errors cannot
  quietly treat one as data."
  [c req]
  (let [[id rx] (open-call! c req)]
    (try
      (:body (check (p/receive rx)))
      (finally (close-call! c id)))))

;; The drain loops live outside the `try` because `recur` cannot cross one.
(defn- drain-all [rx]
  (loop [acc []]
    (let [msg (check (p/receive rx))
          acc (if (contains? msg :body) (conj acc (:body msg)) acc)]
      (if (:final msg) acc (recur acc)))))

(defn- drain-each [rx f]
  (loop [n 0]
    (let [msg (check (p/receive rx))
          n (if (contains? msg :body) (do (f (:body msg)) (inc n)) n)]
      (if (:final msg) n (recur n)))))

(defn call-seq
  "Send `req` and collect every reply until one says `:final`. Returns a vector
  of bodies, **in the order the server sent them**.

  Use `call-each` instead when the point is to stay inside a memory budget: this
  one holds all the waves at once, which is exactly what waves exist to avoid."
  [c req]
  (let [[id rx] (open-call! c req)]
    (try (drain-all rx) (finally (close-call! c id)))))

(defn call-each
  "Send `req` and call `f` on each reply body as it arrives, releasing it before
  asking for the next. This is the shape that keeps peak memory to one wave
  rather than to the whole answer. Returns the number of waves."
  [c req f]
  (let [[id rx] (open-call! c req)]
    (try (drain-each rx f) (finally (close-call! c id)))))

(defn cancel
  "Ask the server to stop working on `id` and release the caller's slot."
  [c id]
  (close-call! c id)
  (p/send (:port c) {:id id :op :cancel}))

(defn close
  "Close the underlying port. The reader thread ends and anything still waiting
  is woken with an error rather than left parked."
  [c]
  (p/close (:port c))
  c)
