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
  (:require [flint.rt] [flint.protocols :as proto] [flint.wire :as wire]))

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
  ([name opts]
   ;; ENCODED HERE, IN FLINT (`DECISIONS.md#the-codec-is-guest-code`). The
   ;; payload is `[name opts]` -- the shape the host has always received -- and
   ;; the runtime's part is to check the writer is finished and take its bytes.
   ;;
   ;; The name goes over TWICE, once in the payload and once as an argument,
   ;; and that is deliberate: the runtime needs it for the refusal message, and
   ;; reading it back out of the encoding would mean decoding in the runtime,
   ;; which is the thing this moved away from.
   (flint.rt/open name (wire/encode [name (or opts {})]))))

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

(defn- wire-differs?
  "Does anything in `v` need CHANGING before it goes on the wire?

  The question is whether what a node currently carries is what should cross --
  NOT whether it has something to declare. Those differ in the case that
  matters: a value carrying `{:a 1}` whose `WireMeta` answers nil needs its
  metadata STRIPPED, and a predicate asking only `is there wire metadata`
  answers no and leaves it on. The encoder emits whatever metadata it is handed,
  so that is `{:a 1}` crossing a bridge for a program that never asked -- which
  is exactly the default this feature promises not to break. Found by the gate,
  after a first version that asked the wrong question.

  Asked FIRST, so that the common answer -- nothing differs -- costs one pass
  and changes nothing. That is not an optimisation, it is the other half of the
  correctness rule: `for-the-wire` rebuilds, and rebuilding a value that did not
  need it is not invisible. `(into {} ..)` gives back a map with the same
  entries in a different ORDER, and a host test that had always seen
  `{:a #{1 2}, :b [:x]}` started seeing `{:b [:x], :a #{1 2}}`. A value with
  nothing to change crosses as the object it already was."
  [v]
  (or
    ;; THE CHEAP QUESTION FIRST, and it is not only for speed. `WireMeta`
    ;; SELECTS FROM metadata, so a value with none has nothing to select and
    ;; nothing to strip -- the answer is no without asking.
    ;;
    ;; Asking anyway costs more than time. `-wire-meta` dispatches through
    ;; `find-protocol-method`, which looks a SYMBOL up in a map, and symbol
    ;; hashing is not in every image: a minimal program that sends one message
    ;; and never names a symbol should not have to carry it.
    (and (some? (meta v)) (not= (proto/-wire-meta v) (meta v)))
    (cond
      (vector? v) (boolean (some wire-differs? v))
      (set? v) (boolean (some wire-differs? v))
      (map? v) (boolean (some (fn [e] (or (wire-differs? (key e))
                                          (wire-differs? (val e)))) v))
      ;; `list?` IS `seq?` here, so this catches a lazy seq and walking one
      ;; forces it. Not a hazard the way it would be elsewhere: the encoder has
      ;; to force it anyway to write it down, so the forcing is brought forward
      ;; rather than added.
      (list? v) (boolean (some wire-differs? v))
      :else false)))

(defn for-the-wire
  "`v` rebuilt with only the metadata that should cross.

  One pass over the structure, asking `flint.protocols/-wire-meta` at each node
  and keeping what it answers. The default answer is nil for every built-in
  kind, so a program that has not opted in sends exactly what it sent before
  this existed -- the same object, unrebuilt, with its metadata dropped at the
  boundary as the encoder has always dropped it.

  DONE HERE, IN FLINT, and not in the encoder. The encoder is runtime code; a
  protocol is dispatched in the image. Reaching from one to the other would mean
  the runtime calling guest code in the middle of a `send`, which is re-entrancy
  into the interpreter at the worst possible moment -- a send can happen
  anywhere, including inside a forced lazy seq. A pass up here is ordinary flint
  calling ordinary flint, and what reaches the encoder is a plain value whose
  metadata is already the answer.

  ONLY ON A BRIDGE, because only a bridge encodes. A channel hands the object
  over as it stands, so nothing is selected and nothing is lost.

  It walks the whole structure, so a port nested inside a map is asked too."
  [v]
  (if-not (wire-differs? v)
    v
    (let [m (proto/-wire-meta v)
          walked (cond
                   (vector? v) (mapv for-the-wire v)
                   (set? v) (into #{} (map for-the-wire v))
                   (map? v) (into {} (map (fn [e] [(for-the-wire (key e))
                                                   (for-the-wire (val e))]) v))
                   (list? v) (apply list (map for-the-wire v))
                   :else v)]
      (if (nil? m) walked (with-meta walked m)))))

(defn send
  "Put `v` into the other end. Parks if that end's buffer is full.

  On a bridge the value is **encoded now**, by the runtime, not when the host
  gets round to reading it: that is what makes draining cheap and the byte
  budget mean something. A value the wire format cannot represent is an error
  here, naming the value — not a quiet coercion.

  A function is refused **by name**, on any port: a closure's meaning is its
  environment and an environment does not travel.

  ## Metadata

  On a bridge, what crosses is what `flint.protocols/WireMeta` selects, and the
  default for every built-in kind is NONE. See `for-the-wire` below.

  On a CHANNEL nothing is selected and nothing is dropped: the value moves by
  pointer, so it arrives as the same object with the metadata it always had.
  The two are not inconsistent -- a channel does not serialise, so there is no
  question of what to write down.

  ## Who writes the bytes

  `flint.wire`, which is flint (`DECISIONS.md#the-codec-is-guest-code`). The
  runtime still carries an encoder and `port-send` still accepts a value, but
  nothing in the library reaches it any more: what goes to a bridge is a
  writer, already finished, and the runtime's part is to check it is complete
  and take its bytes.

  The encoding is therefore GUEST WORK and billed as guest work. It is also
  where a value the format cannot carry is refused -- by name, naming the kind,
  before any of it reaches the port.

  A WRITER PASSES STRAIGHT THROUGH. A guest that encoded for itself -- a
  streaming encoder over a structure it never builds -- hands over the writer,
  and encoding that again would ask the encoder to encode its own output."
  [p v]
  (flint.rt/port-send p (if (and (flint.rt/port-bridge? p)
                                 (not (flint.rt/wire-writer? v)))
                          (wire/encode (for-the-wire v))
                          v)))

(defn receive
  "Take the next message. Parks if there is none; returns `nil` once the port is
  closed and drained.

  On a bridge the bytes are decoded HERE, by `flint.wire` -- guest code reading
  a guest encoding (`DECISIONS.md#the-codec-is-guest-code`). On a channel the
  value is handed over as it stands. Either way this answers a value."
  [p]
  (if (flint.rt/port-bridge? p)
    ;; A BRIDGE CARRIES BYTES AND THE GUEST READS THEM. The runtime answers a
    ;; live reader -- live meaning these bytes arrived over a bridge, which is
    ;; what licenses minting -- and `flint.wire` does the rest.
    ;;
    ;; What delivery still does is mint the PORTS in the message, at the
    ;; boundary, because a port has to exist before anything can be delivered
    ;; on it: a host binds a port and calls on it without pumping in between,
    ;; deliberately (`DECISIONS.md#the-codec-is-guest-code`).
    (let [r (flint.rt/port-receive-reader p)]
      ;; NIL IS END OF STREAM, not an empty message, and it must not be turned
      ;; into a decode of nothing.
      (when (some? r) (wire/read-from r)))
    (flint.rt/port-receive p)))

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
