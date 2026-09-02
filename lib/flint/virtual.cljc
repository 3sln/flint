(ns flint.virtual
  "Namespaces with no source, spoken to over a port (`doc/decisions/0036` step
  4, `0037`).

  Nothing here is written by hand. A program says

      (:require [flint.sys.fs :as fs])
      (fs/list-dir \"src\")

  and the compiler emits `(flint.virtual/call 'flint.sys.fs/list-dir \"src\")`,
  because `flint.sys.fs` is a name the RESOLVER flagged as virtual rather than a
  namespace with source. The call site reads exactly like any other call, which
  is the point: whether a namespace is linked or served is the build's business
  and not the caller's.

  ## What is here, and what is deliberately not

  Three operations, matching `0036`'s protocol:

      (call 'ns/f a b)     invoke, and return the answer
      (fn-for 'ns/f)       the same thing as a value, for `(map f xs)`
      (value-of 'ns/x)     read a var that is not a function
      (list-vars 'ns)      what the far side says it holds

  **The port is opened lazily and once.** A program that requires a virtual
  namespace and never calls into it opens nothing -- so requiring one costs a
  memo lookup, not a round trip, and a host that would refuse the port is not
  asked. The memo is per namespace, because that is the grain the protocol is
  defined at: one port per virtual namespace, correlated by id.

  ## It carries DATA

  Arguments and results cross a bridge, so they go through the wire codec: a
  closure cannot cross one (`0006`, `0025`). That is not new, but this is the
  first place it becomes visible in the language surface -- `(fs/walk-with f)`
  looks like an ordinary higher-order call and cannot be one -- so the refusal
  says so rather than leaving it to be discovered.

  ## Blocking, and where a call may NOT go

  Every call parks the calling green thread and the rest of the sandbox keeps
  running. There is no timeout here for the reason `flint.rpc` gives: flint has
  no clock, and a deadline belongs in the request where the host can enforce it.

  Parking is refused inside native code, so **eager forms carry a virtual call
  and lazy ones do not**. Measured, not assumed:

      (s/greet "x")                     ok
      (let [f s/greet] (f "x"))         ok
      (mapv s/greet xs)                 ok
      (loop [...] ... (s/greet x) ...)  ok
      (for [x xs] (s/greet x))          REFUSED -- a lazy seq is native
      (map s/greet xs)                  REFUSED -- same

  The failure is loud and names the cause:

      cannot park here: this call is nested inside native code
      (map, sort, reduce, a lazy seq)

  **A park inside a lazy seq currently CRASHES both runtimes** rather than
  throwing that error, and it is not this namespace's doing in any way: a plain
  in-heap channel receive inside a `for` does it too, as does `thread/join`.
  `doc/decisions/0037` records the reproduction. Until it is fixed, the eager
  forms above are not merely the ones that work; they are the ones that fail
  safely.

  This is not new and is not this namespace's doing -- it is true of every port
  operation, and `flint.fs` had it too. It is written down HERE because this is
  where it stops looking like a port: `(map s/greet xs)` reads like an ordinary
  higher-order call over an ordinary function, and the whole design goal was
  that a virtual call should read like any other. Where the resemblance ends had
  better be stated rather than met."
  (:require [flint.port :as p]
            [flint.rpc :as rpc]))

;; One entry per virtual namespace: the RPC client over its port.
;;
;; An atom rather than anything cleverer because the memo is per SANDBOX and a
;; sandbox is one heap -- there is no second writer to race with, and a green
;; thread that parks inside `open` does so with the atom unwritten, so a second
;; caller simply opens too and the later `swap!` wins. Two ports where one would
;; do is a waste and not a bug; a lock to prevent it would be a deadlock waiting
;; for a host that never answers.
(def ^:private clients (atom {}))

(defn- client-for
  "The RPC client for virtual namespace `ns-sym`, opening its port on first use."
  [ns-sym]
  (or (get @clients ns-sym)
      (let [c (rpc/client (p/open (str ns-sym)))]
        (swap! clients assoc ns-sym c)
        c)))

(defn- split-var
  "`'flint.sys.fs/list-dir` -> `[flint.sys.fs \"list-dir\"]`."
  [q]
  [(symbol (namespace q)) (name q)])

(defn call
  "Invoke `q` -- a fully qualified symbol -- on the far side, and return what it
  answers. Throws what it throws.

  This is what a call into a virtual namespace compiles to. Writing it by hand
  is legitimate and is how a name computed at run time is invoked, which the
  compiled form cannot express."
  [q & args]
  (let [[ns-sym v] (split-var q)]
    (rpc/call (client-for ns-sym) {:op :invoke :var v :args (vec args)})))

(defn fn-for
  "`q` as a VALUE: a closure that invokes it.

  What a bare reference to a virtual var compiles to, so a virtual function can
  be stored in a local, put in a map, or passed on. The closure is ordinary --
  what crosses the port is the ARGUMENTS, and the closure itself never leaves
  this heap.

  **Arities are written out rather than `(fn [& args] (apply call q args))`,
  and that is not style.** `apply` is a builtin, so an `apply` on the way to a
  call that PARKS puts the park inside native code, and the runtime refuses it:

      cannot park here: this call is nested inside native code

  The variadic tail keeps `apply` for the case that has to have it, and a call
  with more than four arguments through a stored closure is the one shape this
  cannot serve. Said here rather than discovered."
  [q]
  (fn
    ([] (call q))
    ([a] (call q a))
    ([a b] (call q a b))
    ([a b c] (call q a b c))
    ([a b c d] (call q a b c d))
    ([a b c d & more] (apply call q a b c d more))))

(defn value-of
  "Read `q` as a value rather than calling it -- `0036`'s `:get`.

  Separate from a bare reference on purpose: nearly every var behind one of
  these namespaces is a function, so a bare reference means the function and
  this is how the other case is said."
  [q]
  (let [[ns-sym v] (split-var q)]
    (rpc/call (client-for ns-sym) {:op :get :var v})))

(defn list-vars
  "What `ns-sym` says it holds: `[{:name f :arities [..]} ..]`.

  The same request a build makes to get compile-time checking, available at run
  time so a program can ask too. An implementation that does not know a var's
  arities says so rather than omitting the field, because \"no arity
  information\" and \"takes no arguments\" must not look alike."
  [ns-sym]
  (rpc/call (client-for ns-sym) {:op :list}))
