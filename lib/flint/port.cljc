(ns flint.port
  "Ports: an endpoint you send to and receive from.

  A port is the unit of impurity. flint is a pure logic executor; a port is how
  a host *lends* it a capability, and how two green threads talk. Either end may
  be inside the runtime or held by the host.

      (let [[a b] (channel)]        ; a coupled pair
        (send a :hello)
        (receive b))                ; => :hello

      (with-open [r (open \"clock\" {:codec edn/codec})]
        (send r :now)
        (receive r))

  ## What may cross

  **Data only.** A function is refused *by name* at the send, because a
  closure's meaning is its environment and an environment does not travel. And
  **a port cannot be sent through a port**: ports are not transferable. That
  costs the ability to delegate a capability at run time, and buys no ownership
  transfer to reason about, no capability leaking through a message, and a wire
  format that never has to represent a port. Transfer can be added later; it
  could not be removed.

  Transfer is **by value**. Inside one runtime the value is passed by reference
  as an optimisation, and that is sound *precisely because flint values are
  immutable* — there is no way for the sender to observe a later change, because
  there are no later changes. A mutable-object language could not take this
  shortcut.

  ## Back-pressure

  Every port has a bounded buffer, and a send to a full one parks the sending
  thread until there is room — the same parking mechanism as `open`, not a
  second one. A channel is bounded in **messages**; a host port is bounded in
  **bytes**, because the point of back-pressure is to bound memory and one 4 MB
  message is not one message's worth of it.

  ## Lifetime: close is the good path, collection is the net

  `with-open` closes on the way out, including on a throw, and it is the shape
  to reach for. If a script simply drops its last reference to a port, the
  collector finds it unreachable and the runtime closes it on the script's
  behalf — but that is a *safety net*: it is deterministic, and it is not
  prompt, and a host holding a socket open until then is a real cost.

  ## Formats

  A host port carries **bytes**, so a value has to be encoded. Hand the codec
  in, as a value:

      (:require [flint.port :as p] [flint.port.edn :as edn])
      (p/open \"thing\" {:codec edn/codec})

  Passing it rather than naming a format is deliberate. A `cond` here over every
  format would make all of them reachable from any program that opens any port,
  so a JSON program would carry an EDN reader it never uses; and a registry
  filled by requiring a namespace for its side effect is a load-order trap. A
  codec is a value, so you link the one you use.

  With no codec the port is **raw**: `send` takes a string and `receive` gives
  one back. Driving a resource raw has to work, and this is what that looks
  like."
  (:refer-clojure :exclude [send])
  (:require [flint.rt]))

(defn- codec-of [p]
  (:flint/codec (flint.rt/port-opts p)))

(defn channel
  "A coupled pair `[a b]`: what goes into one comes out of the other, both ways.
  `cap` is the buffer size in messages (default 16); `label` is for diagnostics
  and shows up in a deadlock report."
  ([] (flint.rt/channel 16 nil))
  ([label] (if (string? label) (flint.rt/channel 16 label) (flint.rt/channel label nil)))
  ([cap label] (flint.rt/channel cap label)))

(defn open
  "Ask the host for the capability `name`. Blocking from the program's point of
  view: the green thread stops being runnable until the host answers. Nothing
  suspends a wasm frame and the host is never blocked.

  A refusal is a normal outcome and arrives as a catchable `SecurityException`.

  `opts` may carry `:codec` (none means raw bytes) and whatever options that
  codec understands — `:key-fn` for JSON, for instance. The codec's format name
  is what the host is told the bytes are."
  ([name] (open name nil))
  ([name opts]
   (let [codec (:codec opts)
         p (flint.rt/open name (or (:format opts) (:format codec) :bytes))]
     (flint.rt/set-port-opts p (assoc (dissoc (or opts {}) :codec) :flint/codec codec))
     (flint.rt/set-port-binary p (boolean (:binary codec)))
     p)))

(defn port? [x] (flint.rt/port? x))

(defn host?
  "True when the other end is the host's, which is also when messages are bytes
  and a codec is involved."
  [p]
  (flint.rt/port-host? p))

(defn state
  "What this end is doing:

  | | |
  |---|---|
  | `:pending` | an `open` the host has not answered |
  | `:open` | both ends live |
  | `:half-closed` | the peer closed cleanly; drain what is buffered, then end of stream |
  | `:closed` | this end is closed |
  | `:orphaned` | the peer went away *without* closing; receiving errors |
  | `:refused` | the host would not lend this capability |

  A channel is only finished when **both** ends are, which is why half-closed is
  a state you can see rather than a race you cannot."
  [p]
  (flint.rt/port-state p))

(defn closed?
  "Can nothing new ever arrive here? True once this end is closed, the peer has
  closed (`:half-closed` — anything already buffered is still readable), or the
  peer is gone.

  Asking is always available; waiting to be told is not always enough."
  [p]
  (let [s (state p)]
    (or (= s :closed) (= s :half-closed) (= s :orphaned) (= s :refused))))

(defn label [p] (flint.rt/port-label p))
(defn format-of [p] (flint.rt/port-format p))

(defn port-id
  "The number the host knows this port by."
  [p]
  (flint.rt/port-id p))

(defn send
  "Put `v` into the other end. Parks if that end's buffer is full.

  On a host port the value is **encoded now**, not when the host gets round to
  reading it: that is what makes draining cheap and the byte budget mean
  something. A value the format cannot represent is an error here, naming the
  value — not a quiet coercion."
  [p v]
  (if (host? p)
    (let [c (codec-of p)]
      (flint.rt/port-send p (if c ((:encode c) v (flint.rt/port-opts p)) v)))
    (flint.rt/port-send p v)))

(defn receive
  "Take the next message. Parks if there is none; returns `nil` once the port is
  closed and drained."
  [p]
  (let [v (flint.rt/port-receive p)
        c (and (host? p) (some? v) (codec-of p))]
    (if c ((:decode c) v (flint.rt/port-opts p)) v)))

(defn close
  "Close a port — any port, not only one you opened. Anybody parked on it wakes
  and reads end-of-stream, and the host is told."
  [p]
  (flint.rt/port-close p))

(defmacro with-open
  "Bind ports, run the body, and close them on the way out — including when the
  body throws. This is the good path: the collector will close a dropped port
  eventually, but eventually is not promptly, and a host holding a resource open
  until a collection happens is a real cost."
  [bindings & body]
  (if (empty? bindings)
    `(do ~@body)
    `(let [~(first bindings) ~(second bindings)]
       (try
         (with-open ~(vec (drop 2 bindings)) ~@body)
         (finally (close ~(first bindings)))))))
