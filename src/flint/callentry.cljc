(ns flint.callentry
  "THE CALL LOOP, which the compiler carries itself and puts in every image
  (`DECISIONS.md#the-control-plane-is-the-runtimes`).

  A sandbox is driven over its SYSTEM PORT, and every control message on it --
  `:bind`, `:unbind`, `:close`, `:snapshot` -- is the RUNTIME's: bookkeeping
  and runtime calls, none of which parks. What is left is serving CALLS on a
  bound port, which has to be flint because a call runs a guest function and
  may park. On `:bind` the runtime closes this entry over the bound port and
  spawns it as a green thread.

  ## Why it has no namespace

  It was `flint.system`, a namespace in `lib/` -- and which source backs a
  namespace is the RESOLVER's answer, which is the host's, and the standard
  library is becoming one more thing a resolver supplies. Code a resolver
  supplies must never run on a trusted path. So this has no namespace and no
  var: nothing can declare it, define into it, shadow it or name it. The
  compiler analyses these forms itself and records the function by INDEX in
  the image (`flint.image`'s `:serve`); the runtime spawns it by that index,
  never by a name.

  ## Why it is written the way it is

  SELF-CONTAINED: special forms, its own locals, and `flint.rt` builtins --
  nothing else. No `clojure.core`, no `flint.port`, no `flint.wire`: each of
  those is resolver-supplied source, and a macro or `:inline` from one would
  put that source's code into this loop as surely as a call would.
  `check-self-contained!` refuses any other symbol before the forms are
  analysed, and the compiler refuses an analysed form that references a var.

  So the wire codec a call needs is here, re-expressed in builtins: the
  decoder `flint.wire/take-value` was and the encoder `flint.wire/emit` was,
  with the same tags, the same order and the same error messages
  (`test/sysns.clj` checks the wording of the refusals).

  ONE THING `flint.port/send` DID IS NOT HERE: asking `WireMeta` which of a
  reply's metadata should cross. That is a protocol, dispatched through
  `flint.protocols` -- resolver-supplied code -- so a call's reply crosses with
  NO metadata, which is what `WireMeta` answers for every built-in kind and
  every value that has not opted in."
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
       (let* [;; ---------------------------------------------------- reading
              ;; `n` values off `r`, as a vector. `tk` is `take`, passed in:
              ;; a function's own name is its FRAME's closure, which a nested
              ;; function cannot capture.
              take-n (fn* take-n [tk r n]
                       (loop* [i 0 acc []]
                         (if (flint.rt/lt i n)
                           (recur (flint.rt/add i 1) (flint.rt/conj acc (tk r)))
                           acc)))
              ;; One value off reader `r`: `flint.wire/take-value`.
              take
              (fn* take [r]
                (let* [t (flint.rt/wire-tag r)]
                  (if (flint.rt/nil? t)
                    (throw (flint.rt/ex-info "the encoding ends where a value was expected" {}))
                    (if (flint.rt/= t 0) nil
                    (if (flint.rt/= t 1) true
                    (if (flint.rt/= t 2) false
                    (if (flint.rt/= t 3) (flint.rt/wire-i64 r)
                    (if (flint.rt/= t 4) (flint.rt/wire-f64 r)
                    (if (flint.rt/= t 5) (flint.rt/wire-text r)
                    (if (flint.rt/= t 14) (flint.rt/wire-blob r)
                    (if (flint.rt/= t 6)
                      (let* [ns (flint.rt/wire-ns r) nm (flint.rt/wire-text r)]
                        (if (flint.rt/nil? ns) (flint.rt/keyword2 nm) (flint.rt/keyword2 ns nm)))
                    (if (flint.rt/= t 7)
                      (let* [ns (flint.rt/wire-ns r) nm (flint.rt/wire-text r)]
                        (if (flint.rt/nil? ns) (flint.rt/symbol2 nm) (flint.rt/symbol2 ns nm)))
                    (if (flint.rt/= t 8) (take-n take r (flint.rt/wire-u32 r))
                    (if (flint.rt/= t 9)
                      (let* [v (take-n take r (flint.rt/wire-u32 r))]
                        (loop* [i (flint.rt/sub (flint.rt/count v) 1) acc (quote ())]
                          (if (flint.rt/lt i 0)
                            acc
                            (recur (flint.rt/sub i 1) (flint.rt/cons (flint.rt/nth v i) acc)))))
                    (if (flint.rt/= t 11)
                      (let* [v (take-n take r (flint.rt/wire-u32 r)) n (flint.rt/count v)]
                        (loop* [i 0 acc #{}]
                          (if (flint.rt/lt i n)
                            (recur (flint.rt/add i 1) (flint.rt/conj acc (flint.rt/nth v i)))
                            acc)))
                    (if (flint.rt/= t 10)
                      (let* [n (flint.rt/wire-u32 r)]
                        (loop* [i 0 m {}]
                          (if (flint.rt/lt i n)
                            (let* [k (take r) v (take r)]
                              (recur (flint.rt/add i 1) (flint.rt/assoc m k v)))
                            m)))
                    (if (flint.rt/= t 17)
                      (let* [tag (take r)] (flint.rt/tagged-literal tag (take r)))
                    ;; A TABLE IS REBUILT THROUGH `schema` AND `table`, the
                    ;; same two calls a program makes.
                    (if (flint.rt/= t 18)
                      (let* [ncols (flint.rt/wire-u32 r)
                             pairs (loop* [i 0 acc []]
                                     (if (flint.rt/lt i ncols)
                                       (let* [nm (take r) ty (take r)]
                                         (recur (flint.rt/add i 1) (flint.rt/conj acc [nm ty])))
                                       acc))
                             nrows (flint.rt/wire-u32 r)
                             cols (loop* [c 0 acc []]
                                    (if (flint.rt/lt c ncols)
                                      (recur (flint.rt/add c 1) (flint.rt/conj acc (take-n take r nrows)))
                                      acc))
                             rows (loop* [i 0 acc []]
                                    (if (flint.rt/lt i nrows)
                                      (recur (flint.rt/add i 1)
                                             (flint.rt/conj
                                              acc
                                              (loop* [c 0 m {}]
                                                (if (flint.rt/lt c ncols)
                                                  (recur (flint.rt/add c 1)
                                                         (flint.rt/assoc
                                                          m
                                                          (flint.rt/nth (flint.rt/nth pairs c) 0)
                                                          (flint.rt/nth (flint.rt/nth cols c) i)))
                                                  m))))
                                      acc))]
                        (flint.rt/table (flint.rt/schema pairs) rows))
                    (if (flint.rt/= t 19)
                      (let* [m (take r)] (flint.rt/with-meta (take r) m))
                    (if (flint.rt/= t 15) (flint.rt/wire-port-in r)
                    (if (flint.rt/= t 16) (flint.rt/wire-opaque-in r)
                      (throw (flint.rt/ex-info
                              (flint.rt/str2 "unknown tag in the encoding: " (flint.rt/num->str t))
                              {:tag t})))))))))))))))))))))))

              ;; ---------------------------------------------------- writing
              ;; `v` into writer `w`, answering the writer: `flint.wire/emit`,
              ;; with no metadata (see the namespace docstring).
              ;; Every element of `s` into `w`, by `em` -- which is `emit`,
              ;; passed in for the reason `take-n` takes `take`.
              each (fn* each [em w s]
                     (loop* [w w s (flint.rt/seq s)]
                       (if (flint.rt/nil? s)
                         w
                         (recur (em w (flint.rt/first s)) (flint.rt/next s)))))
              emit
              (fn* emit [w v]
                (let* [k (flint.rt/kind v)]
                  (if (flint.rt/nil? v) (flint.rt/wire-nil w)
                  (if (flint.rt/= k :boolean) (flint.rt/wire-bool w v)
                  (if (flint.rt/= k :number)
                    (if (flint.rt/int? v) (flint.rt/wire-int w v) (flint.rt/wire-double w v))
                  (if (flint.rt/= k :string) (flint.rt/wire-str w v)
                  (if (flint.rt/= k :bytes) (flint.rt/wire-bytes w v)
                  (if (flint.rt/= k :keyword) (flint.rt/wire-kw w (flint.rt/namespace v) (flint.rt/name v))
                  (if (flint.rt/= k :symbol) (flint.rt/wire-sym w (flint.rt/namespace v) (flint.rt/name v))
                  (if (flint.rt/= k :vector) (each emit (flint.rt/wire-vec w (flint.rt/count v)) v)
                  (if (flint.rt/= k :set) (each emit (flint.rt/wire-set w (flint.rt/count v)) v)
                  (if (flint.rt/= k :list) (each emit (flint.rt/wire-list w (flint.rt/count v)) v)
                  (if (flint.rt/= k :map)
                    (loop* [w (flint.rt/wire-map w (flint.rt/count v)) s (flint.rt/seq v)]
                      (if (flint.rt/nil? s)
                        w
                        (let* [e (flint.rt/first s)]
                          (recur (emit (emit w (flint.rt/nth e 0)) (flint.rt/nth e 1))
                                 (flint.rt/next s)))))
                  (if (flint.rt/= k :port) (flint.rt/wire-port w v)
                  (if (flint.rt/= k :opaque) (flint.rt/wire-opaque w v)
                  (if (flint.rt/= k :tagged)
                    (emit (emit (flint.rt/wire-tagged w) (flint.rt/get v :tag)) (flint.rt/get v :form))
                  (if (flint.rt/= k :table)
                    (let* [sc (flint.rt/table-schema v)
                           names (flint.rt/schema-columns sc)
                           types (flint.rt/schema-types sc)
                           n (flint.rt/count names)
                           w (loop* [w (flint.rt/wire-table w n) i 0]
                               (if (flint.rt/lt i n)
                                 (recur (emit (emit w (flint.rt/nth names i)) (flint.rt/nth types i))
                                        (flint.rt/add i 1))
                                 w))
                           w (flint.rt/wire-table-rows w (flint.rt/count v))]
                      (loop* [w w i 0]
                        (if (flint.rt/lt i n)
                          (recur (each emit w (flint.rt/table-column v (flint.rt/nth names i)))
                                 (flint.rt/add i 1))
                          w)))
                  (if (flint.rt/= k :fn)
                    (throw (flint.rt/ex-info
                            "a function cannot cross a boundary: its meaning is its environment, and that does not travel"
                            {:kind k}))
                  (if (flint.rt/= k :atom)
                    (throw (flint.rt/ex-info "an atom cannot cross a boundary" {:kind k}))
                  (if (flint.rt/= k :var)
                    (throw (flint.rt/ex-info "a var cannot cross a boundary" {:kind k}))
                  (if (flint.rt/= k :thread)
                    (throw (flint.rt/ex-info "a thread cannot cross a boundary" {:kind k}))
                    (throw (flint.rt/ex-info
                            (flint.rt/str2 "this cannot cross a boundary: :" (flint.rt/name k))
                            {:kind k})))))))))))))))))))))))

              send (fn* send [v] (flint.rt/port-send p (emit (flint.rt/wire-writer) v)))

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
                  m (if (flint.rt/nil? r)
                      nil
                      (let* [v (take r)]
                        (if (flint.rt/gt (flint.rt/wire-left r) 0)
                          (throw (flint.rt/ex-info "the encoding has bytes left over"
                                                   {:left (flint.rt/wire-left r)}))
                          v)))]
             (if (flint.rt/nil? m)
               (if (closed? p) nil (recur))
               (do (serve-one m)
                   (recur)))))))))

(defn bare
  "`form` with every piece of metadata removed.

  THE FORMS ARE READ BY WHICHEVER HOST RUNS THE COMPILER, not by
  `flint.reader`: babashka's reader puts `:line`/`:column` on lists and flint's
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

(defn check-self-contained!
  "Refuse `form` unless every symbol in it is a special form, a name it binds,
  `Throwable`, or a `flint.rt` builtin -- and say which one is not. Run before
  analysis, so a macro or a prelude name never gets the chance to expand."
  [form]
  (let [bound (bound-names form)
        bad (volatile! [])
        walk (fn walk [x]
               (cond
                 (and (seq? x) (= 'quote (first x))) nil
                 (symbol? x) (when-not (or (contains? specials x)
                                           (contains? bound x)
                                           (= 'Throwable x)
                                           (= "flint.rt" (namespace x)))
                               (vswap! bad conj x))
                 (map? x) (doseq [[k v] x] (walk k) (walk v))
                 (coll? x) (doseq [e x] (walk e))
                 :else nil))]
    (walk form)
    (when (seq @bad)
      (throw (ex-info (str "compile error: the call loop must be self-contained -- "
                           "special forms, its own locals and flint.rt builtins only -- and names "
                           (str/join ", " (map str (distinct @bad)))
                           " (DECISIONS.md#the-control-plane-is-the-runtimes)")
                      {:type :compile :names (vec (distinct @bad))})))
    form))
