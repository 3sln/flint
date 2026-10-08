(ns flint.compiler.callentry
  "THE CALL LOOP, which the compiler carries itself and puts in every image
  (`DECISIONS.md#the-control-plane-is-the-runtimes`).

  A sandbox is driven over its SYSTEM PORT, and every control message on it --
  `:bind`, `:unbind`, `:close`, `:snapshot` -- is the RUNTIME's: bookkeeping
  and runtime calls, none of which parks. What is left is serving CALLS on a
  bound port, which has to be flint because a call runs a guest function and
  may park. On `:bind` the runtime closes this entry over the bound port and
  spawns it as a green thread.

  ## Why it has no namespace, still

  It was `flint.system`, a namespace in `lib/` -- and which source backs a
  namespace is the RESOLVER's answer, which is the host's. That mattered for
  the CONTROL PLANE, because `:bind`/`:unbind`/`:close`/`:snapshot` ran
  trusted with the system port and nothing checked which source a resolver
  handed back for `flint.system/boot` -- a resolver-supplied `flint/system.cljc`
  could shadow it (`DECISIONS.md#the-control-plane-is-the-runtimes`). That
  risk is gone with the namespace: the control plane is runtime code now
  (`kin/control.kin`), and this loop keeps the same shape -- no namespace, no
  var, spawned by fn INDEX -- for a narrower and still-real reason: nothing
  can declare, define into, shadow or call it, so `flint.rt/var-named`
  dispatching `:fn` by a STRING a guest sent has exactly one caller to trust,
  and that caller cannot be impersonated by naming it.

  ## Why it is NOT self-contained any more

  A CALL THREAD IS NOT A TRUST BOUNDARY. It exists to run a guest function --
  `answer` below calls whatever `:fn` named, with whatever `:args` arrived --
  so resolver-supplied code already runs on it, inside the `try` that catches
  everything. Refusing this loop its own reference to `flint.port/send` or
  `flint.wire/read-from` protected nothing that `answer` was not already
  exposed to, and it cost a correctness bug: those two names are ordinary
  library code (`lib/flint/port.cljc`, `lib/flint/wire.cljc`) and this loop
  used to RE-IMPLEMENT their wire codec in ~30 `flint.rt/wire-*` builtins
  instead of calling them -- a second copy that had already drifted, because
  `flint.port/send` asks `flint.protocols/WireMeta` which of a reply's
  metadata should cross and the builtin re-implementation never did, so a
  reply crossed with NO metadata regardless of what the value opted in to
  (`DECISIONS.md#ports-speak-protocols`).

  So this loop now does exactly what any other guest code sending and
  receiving on a port does: `(flint.port/send p v)` to answer, and
  `(flint.wire/read-from r)` on `(flint.rt/port-receive-reader p)` to read a
  call -- the same two calls `lib/flint/port.cljc`'s own `send`/`receive` make.
  What is STILL refused, by `check-self-contained!` below, is everything else:
  special forms, this loop's own locals, `flint.rt` builtins, and exactly
  those two vars. No `clojure.core`, no macro, no `:inline` -- a program's own
  code reaches this loop only through `flint.port`/`flint.wire`'s own
  references, which `compile-image` seeds as extra GC roots so the shake
  cannot drop them out from under an image that never otherwise calls them
  (`flint.compiler.core/emit-call-entry!`).

  `flint.port`/`flint.wire` are ordinary REQUIRED namespaces for this loop,
  resolved exactly as any reference would be: `flint.compiler.resolve/resolve-project`
  and `bin/flint`'s own copy add them as ROOTS, alongside `clojure.core`, so
  every compile collects them from the resolver -- no implicit injection, and
  a resolver that cannot answer `flint.port` fails the compile with the same
  \"is flint.port required?\" a program would get for naming it directly."
  (:require [clojure.string :as str]))

(def forms
  "The entry, as data.

  NO MAP LITERAL WITH MORE THAN ONE ENTRY. These forms are a CONSTANT in the
  compiler's own image, and a map constant there is not promised to keep its
  order -- so the self-hosted compiler analysed `{:tx tx :op :throw ..}` in a
  different order from `bin/flint`, and the two doors' images differed
  (measured: a `:to :clr` assembly, one constant swapped). A reply's key ORDER
  is wire behaviour too. `flint.rt/array-map` over a vector says the order.

  `(fn* [p] (fn* [] ..))`: the OUTER function exists only
  so the inner one closes over `p` -- the compiler emits the INNER one, whose
  single upvalue is the port, and the runtime builds the closure with the
  bound port in that slot. That is how a thread is started with an argument
  without a thread slot to carry one: a slot on every thread costs gas on
  every spawn (`runtime/src/conc.rs`, the note where `TH_ARGS` was)."
  '(fn* [p]
     (fn* flint-call-loop []
       (let* [;; ---------------------------------------------------- the wire
              ;; Reading a call and sending a reply go through the SAME two
              ;; calls any other guest code on a port makes: `flint.wire/
              ;; read-from` on a live reader, and `flint.port/send`, which asks
              ;; `WireMeta` and encodes through `flint.wire`. See the namespace
              ;; docstring for why this loop may name them.
              send (fn* send [v] (flint.port/send p v))

              ;; ---------------------------------------------------- one call
              ;; What to send back for call `m`, as data. Separate from the
              ;; loop so the loop has nothing in it that can throw: this is
              ;; where guest code runs, and everything it can do is caught.
              answer
              (fn* answer [m]
                (let* [tx (flint.rt/get m :tx)]
                  (try
                    (let* [nm (flint.rt/get m :fn)
                           f (flint.rt/var-named nm)]
                      ;; CALLABLE, not merely present: the answer then says
                      ;; which NAME is broken.
                      (if (flint.rt/fn? f)
                        (flint.rt/array-map [:tx tx :op :return
                         :value (flint.rt/apply f (let* [a (flint.rt/get m :args)]
                                                    (if (flint.rt/nil? a) [] a)))])
                        ;; NOT AN INTERNAL ERROR: a name the shake removed is
                        ;; the ordinary case (`DECISIONS.md#vars-is-its-own-grant`).
                        (flint.rt/array-map [:tx tx :op :throw :kind "IllegalArgumentException"
                         :message (flint.rt/str2
                                   (flint.rt/str2
                                    (flint.rt/str2 (flint.rt/str2 "this image has no callable `" nm) "`")
                                    (if (flint.rt/nil? f)
                                      ""
                                      (flint.rt/str2 " -- its var holds a :" (flint.rt/name (flint.rt/kind f)))))
                                   "; if it should be callable, name it in `:exports` so the shake keeps it")])))
                    (catch Throwable e
                      (flint.rt/array-map [:tx tx :op :throw
                       ;; THE KIND A `catch` SELECTS ON, not the word "Error":
                       ;; `ex-data :kind` first, because a guest may declare its
                       ;; own, then the runtime's kind.
                       :kind (let* [d (flint.rt/ex-data e)
                                    dk (if (flint.rt/nil? d) nil (flint.rt/get d :kind))]
                               (if (flint.rt/nil? dk)
                                 (let* [ek (flint.rt/ex-kind e)]
                                   (if (flint.rt/nil? ek) "Error" ek))
                                 dk))
                       ;; NAME THE THROWN VALUE when there is no message: flint
                       ;; lets a program throw any value.
                       :message (let* [msg (flint.rt/ex-message e)]
                                  (if (flint.rt/nil? msg)
                                    (if (flint.rt/nil? (flint.rt/ex-kind e))
                                      (flint.rt/str2
                                       (flint.rt/str2 "a " (flint.rt/name (flint.rt/kind e)))
                                       " was thrown, with no message")
                                      nil)
                                    msg))])))))

              ;; Build the answer, then send it -- two failures, reported apart.
              ;; A DROPPED ANSWER IS A HANG, so a send that fails is answered
              ;; with a small message carrying the same `:tx`.
              serve-one
              (fn* serve-one [m]
                (let* [reply (try (answer m)
                                  (catch Throwable e
                                    (flint.rt/array-map [:tx (flint.rt/get m :tx) :op :throw :kind "AnswerFailed"
                                     :message (flint.rt/str2 "building this call's answer failed: "
                                                             (flint.rt/ex-message e))])))]
                  (try (send reply)
                       (catch Throwable e
                         (try (send (flint.rt/array-map [:tx (flint.rt/get m :tx) :op :throw :kind "SendFailed"
                                     :message (flint.rt/str2 "this call's answer could not be sent back: "
                                                             (flint.rt/ex-message e))]))
                              (catch Throwable _ nil))))))

              ;; Can nothing new ever arrive on `p`? `flint.port/closed?`.
              closed? (fn* closed? [p]
                        (let* [s (flint.rt/port-state p)]
                          (if (flint.rt/= s :closed) true
                          (if (flint.rt/= s :half-closed) true
                          (if (flint.rt/= s :orphaned) true
                            (flint.rt/= s :refused))))))]
         ;; ------------------------------------------------------ the loop
         ;; `port-receive-reader` answers nil once the port is closed and
         ;; drained. NIL IS NOT PROOF THE PORT ENDED, though -- a message that
         ;; decoded to nil is a nil too -- so `closed?` is ASKED rather than
         ;; believed (`DECISIONS.md#the-control-plane-is-the-runtimes`).
         (loop* []
           (let* [r (flint.rt/port-receive-reader p)
                  m (if (flint.rt/nil? r) nil (flint.wire/read-from r))]
             (if (flint.rt/nil? m)
               (if (closed? p) nil (recur))
               (do (serve-one m)
                   (recur)))))))))

(defn bare
  "`form` with every piece of metadata removed.

  THE FORMS ARE READ BY WHICHEVER HOST RUNS THE COMPILER, not by
  `flint.compiler.reader`: babashka's reader puts `:line`/`:column` on lists and flint's
  puts them on symbols too. Positions travel into the image, so the same
  compiler source made different bytes depending on which compiler read it --
  measured: `bin/flint` and the native CLI disagreed on a `:to :clr` assembly
  at one byte in 22 528. The loop has no source file for a position to point
  into anyway."
  [form]
  (let [m (cond
            (seq? form) (apply list (map bare form))
            (vector? form) (mapv bare form)
            (map? form) (into {} (map (fn [[k v]] [(bare k) (bare v)]) form))
            (set? form) (into #{} (map bare form))
            :else form)]
    (if (meta m) (with-meta m nil) m)))

(def ^:private specials
  '#{fn* let* loop* recur if do quote try catch throw})

(defn- bound-names
  "Every name the forms BIND -- parameters, `let*`/`loop*` locals, a `fn*`'s own
  name, a `catch`'s binding. Over-approximate on purpose: a name bound anywhere
  counts as bound everywhere, which can only admit a symbol the analyser will
  then refuse as unresolved, never one it would resolve to a var."
  [form]
  (let [out (volatile! #{})
        walk (fn walk [x]
               (cond
                 (seq? x)
                 (let [h (first x)]
                   (cond
                     (= 'quote h) nil
                     (= 'fn* h) (let [more (rest x)
                                      [nm more] (if (symbol? (first more))
                                                  [(first more) (rest more)]
                                                  [nil more])]
                                  (when nm (vswap! out conj nm))
                                  (doseq [p (first more)] (vswap! out conj p))
                                  (doseq [b (rest more)] (walk b)))
                     (or (= 'let* h) (= 'loop* h))
                     (do (doseq [[n v] (partition 2 (second x))]
                           (vswap! out conj n)
                           (walk v))
                         (doseq [b (drop 2 x)] (walk b)))
                     (= 'catch h) (do (vswap! out conj (nth x 2))
                                      (doseq [b (drop 3 x)] (walk b)))
                     :else (doseq [e x] (walk e))))
                 (map? x) (doseq [[k v] x] (walk k) (walk v))
                 (coll? x) (doseq [e x] (walk e))
                 :else nil))]
    (walk form)
    @out))

(def allowed-vars
  "The only vars this loop may name, beyond specials, its own locals and
  `flint.rt` builtins: the normal wire codec a call's reply and a call's
  reading go through, `lib/flint/port.cljc` and `lib/flint/wire.cljc`'s own
  entry points. A call thread is not a trust boundary (see the namespace
  docstring), so this is not a safety list -- it is what keeps the loop from
  quietly growing a reference to `clojure.core` or a macro's expansion, which
  would put a program's own code on a path nothing analyses as this loop's."
  '#{flint.port/send flint.wire/read-from})

(defn check-self-contained!
  "Refuse `form` unless every symbol in it is a special form, a name it binds,
  `Throwable`, a `flint.rt` builtin, or one of `allowed-vars` -- and say which
  one is not. Run before analysis, so a macro or a prelude name never gets the
  chance to expand."
  [form]
  (let [bound (bound-names form)
        bad (volatile! [])
        walk (fn walk [x]
               (cond
                 (and (seq? x) (= 'quote (first x))) nil
                 (symbol? x) (when-not (or (contains? specials x)
                                           (contains? bound x)
                                           (= 'Throwable x)
                                           (= "flint.rt" (namespace x))
                                           (contains? allowed-vars x))
                               (vswap! bad conj x))
                 (map? x) (doseq [[k v] x] (walk k) (walk v))
                 (coll? x) (doseq [e x] (walk e))
                 :else nil))]
    (walk form)
    (when (seq @bad)
      (throw (ex-info (str "compile error: the call loop may use only special forms, its own "
                           "locals, flint.rt builtins and " (str/join ", " (sort allowed-vars))
                           " -- and names "
                           (str/join ", " (map str (distinct @bad)))
                           " (DECISIONS.md#the-control-plane-is-the-runtimes)")
                      {:type :compile :names (vec (distinct @bad))})))
    form))
