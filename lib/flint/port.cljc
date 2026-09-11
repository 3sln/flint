(ns flint.port
  "Ports: an endpoint you send to and receive from.

  A port is the unit of impurity. flint is a pure logic executor; a port is how
  a host *lends* it a capability, and how two green threads talk. Either end may
  be inside the runtime or held by the host.

      (let [[a b] (channel)]        ; a coupled pair
        (send a :hello)
        (receive b))                ; => :hello

      (with-open [r (open \"clock\")]
        (send r :now)
        (receive r))

  ## What may cross

  **Data, and capabilities.** A function is refused *by name* at the send,
  because a closure's meaning is its environment and an environment does not
  travel.

  A **bridge may be sent through a bridge**, and that is how a capability is
  delegated: its id is the host's own and means the same thing on the far side,
  so the receiver ends up holding *the same port*. `host-abi` called the absence of
  this the right default and `structured-ports` reversed it.

  A **channel end may not** cross a bridge. Both its ends live in this heap and
  the host has never been told it exists, so its id would name one of our
  objects from outside — a refusal with a reason, not a silent promotion.

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

  ## What a bridge carries

  **Values, not bytes.** A bridge encodes on the way out and decodes on the way
  in, and the runtime is what does it — there is no codec to choose, to attach,
  or to get wrong, and `send` takes the same value a channel would take.

  That is a safety rule, not a convenience (`DECISIONS.md#structured-ports`). The wire
  format writes an opaque value's host id inline, and bytes are integers a guest
  can write; a codec running in here would therefore be an integer-to-capability
  conversion, and an opaque value's whole meaning is that no such conversion
  exists. Keeping the encoder on the runtime's side of the line is what makes
  `(send p {:cap c})` safe to allow at all.

  There used to be a `:codec` option taking `:encode`/`:decode` functions. It is
  gone, and so is the raw byte mode it sat on: a port that carried strings the
  program had already serialised was the same hole seen from the other side."
  (:refer-clojure :exclude [send])
  (:require [flint.rt]))

(defn channel
  "A coupled pair `[a b]`: what goes into one comes out of the other, both ways.
  `cap` is the buffer size in messages (default 16); `label` is for diagnostics
  and shows up in a deadlock report.

  Both ends are in this heap, so a message is a pointer move: nothing is
  encoded, nothing is copied, and an identity crossing one still means what it
  meant. This is the port that costs nothing."
  ([] (flint.rt/channel 16 nil))
  ([label] (if (string? label) (flint.rt/channel 16 label) (flint.rt/channel label nil)))
  ([cap label] (flint.rt/channel cap label)))

(defn open
  "Ask the host for a port called `name`, forwarding `opts` to it verbatim.

  **A request, not a construction** (`DECISIONS.md#ports-are-the-hosts`). The sandbox cannot
  make a bridge; it asks on the system port it was given at construction, and
  the host answers with a handle on a port the host already owns — or refuses,
  which is a normal outcome and arrives as a catchable `SecurityException`. A
  sandbox given no system port cannot ask at all, and is told so.

  **The runtime takes no view of what `opts` contains.** It crosses as data, and
  anything in it that is an opaque value (`DECISIONS.md#opaque-values`) crosses carrying
  the host id it was ISSUED with. So a host that lent a capability recognises
  its own and nothing else, and one that requires none simply ignores what it
  was sent.

      (p/open \"fs\")                   ; ask, present nothing
      (p/open \"fs\" {:capability c})   ; present what you hold"
  ([name] (open name nil))
  ([name opts] (flint.rt/open name (or opts {}))))

(defn port? [x] (flint.rt/port? x))

(defn bridge?
  "True when this port crosses a heap — the host owns the other end, messages
  are encoded, and only what means something on the far side may cross.

  False for a channel, whose ends are both in here."
  [p]
  (flint.rt/port-bridge? p))

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

(defn port-id
  "The number the host knows this port by."
  [p]
  (flint.rt/port-id p))

(defn send
  "Put `v` into the other end. Parks if that end's buffer is full.

  On a bridge the value is **encoded now**, by the runtime, not when the host
  gets round to reading it: that is what makes draining cheap and the byte
  budget mean something. A value the wire format cannot represent is an error
  here, naming the value — not a quiet coercion.

  A function is refused **by name**, on any port: a closure's meaning is its
  environment and an environment does not travel."
  [p v]
  (flint.rt/port-send p v))

(defn receive
  "Take the next message. Parks if there is none; returns `nil` once the port is
  closed and drained.

  On a bridge the bytes are decoded by the runtime before you see them, so this
  answers a value on every kind of port."
  [p]
  (flint.rt/port-receive p))

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
