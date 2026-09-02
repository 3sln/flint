(ns flint.splint
  "splint: write a runtime's shared logic once, emit it for every host.

  ## Why this is code and not data

  The first version made per-target knowledge a table of format strings. It got
  most of the way and then leaked: FOUR things turned out not to be expressible
  as data, and each ended up special-cased in the translator instead --

  * Rust needs call arguments hoisted into temporaries and the others do not;
  * a loop test cannot be hoisted and has to be rewritten into the loop;
  * `mut` has to be inferred from whether the body assigns;
  * numeric width is a per-call cast.

  A translator that knows about all four is not a translator with a config
  file; it is a compiler for three languages wearing one. So the rules are
  FUNCTIONS. Each target implements each form, and the four become ordinary
  code inside the implementations that need them -- the Rust `invoke` hoists
  because Rust's `invoke` says so, and nothing else in the system knows.

  ## The pieces

  * `splint-ns` -- a VOCABULARY: tags, and per-target implementations of forms.
  * `splint` -- a DRIVER: targets, each with a path and a file preamble.
  * A source file is an ordinary `ns` with `:require`, so what a file may say is
    what it asked for. Two sources can use different vocabularies.

  ## Emission

  Two sinks, and the difference is the whole reason hoisting works:

      (splint-emit!  ctx \"...\")   append here
      (splint-before! ctx \"...\")   append BEFORE the current statement

  and `splint-render` runs a form into a string instead of the current sink, so
  an expression can be composed while a statement is emitted."
  (:require [clojure.string :as str]))

;; --------------------------------------------------------------- the context

(defn- new-sink [] (atom []))

;; --------------------------------------------------------------- anchors
;;
;; An ANCHOR is a named place in the output that has already gone past.
;;
;; `splint-before!` came first and could only reach ONE level up -- before the
;; statement being built. That is enough for hoisting a temporary and enough for
;; nothing else: a loop-invariant binding wants to go before the LOOP, a scratch
;; declaration wants the top of the FUNCTION, and neither is one level up.
;;
;; An anchor is dropped where the output should later appear, carried in a scope
;; frame, and emitted against from arbitrarily deep. Resolution happens when the
;; buffer is joined, so an anchor placed early can be written to late.

(defn anchor
  "A fresh anchor -- a place to emit into, resolved when the output is joined."
  []
  {:splint/anchor (new-sink)})

(defn anchor? [x] (and (map? x) (contains? x :splint/anchor)))

(defn splint-emit-anchor!
  "Drop an anchor HERE and return it. Whatever is emitted against it later
  appears at this point in the output."
  [ctx]
  (let [a (anchor)]
    (swap! (:out ctx) conj a)
    a))

(defn resolve-sink
  "A sink's items as one string, anchors resolved in place and recursively --
  an anchor may itself contain anchors, which is what makes them nest."
  [items]
  (str/join (mapv (fn [i]
                    (if (anchor? i)
                      (resolve-sink (deref (:splint/anchor i)))
                      i))
                  items)))

(defn context
  "A fresh emission context for `target`."
  [driver target]
  {:driver driver
   :target target
   :out (new-sink)
   ;; Statements to place BEFORE the one being built. A form that needs a
   ;; temporary writes here and the statement layer flushes it.
   :pre (new-sink)
   :scope {}
   :indent 0})

(defn splint-scoped
  "Call `f` with `ctx` extended by one scoped entry.

  `{:key :class :value {...}}`, and `:indent` if the scope indents. Scoped
  rather than global because a form's implementation asks what encloses it --
  which class, which package, how deep -- and that is a stack, not a variable."
  [ctx entry f]
  (f (-> ctx
         (assoc-in [:scope (:key entry)] (:value entry))
         (update :indent + (or (:indent entry) 0)))))

(defn splint-get
  "Read a scoped entry."
  [ctx k]
  (get (:scope ctx) k))

(defn indent-of [ctx] (apply str (repeat (* 4 (:indent ctx)) " ")))

(defn splint-emit!
  "Append to the current sink, or to an ANCHOR.

  `(splint-emit! ctx \"...\")` writes here; `(splint-emit! a \"...\")` writes
  where `a` was dropped. One function for both because a form implementation
  should not have to care which it was handed -- it emits at a place, and a
  place is either \"here\" or an anchor."
  [target & parts]
  (swap! (if (anchor? target) (:splint/anchor target) (:out target))
         conj (apply str parts))
  nil)

(defn splint-before!
  "Emit before the statement being built -- the anchor the statement layer
  dropped, looked up by name.

  Kept as a convenience because hoisting a temporary is the common case, and
  now it is one anchor among others rather than a mechanism of its own."
  [ctx & parts]
  (apply splint-emit! (or (splint-get ctx :splint/stmt-anchor) ctx) parts))


;; ------------------------------------------------------------------ dispatch

(declare dispatch literal)

(defn require-scope
  "The symbol table a source's `ns` form asks for.

  `{written-symbol -> [vocabulary-name simple-symbol]}`, honouring both halves
  of a require:

      (:require [flint.impl.vm :as vm :refer [let set if]])

  gives `let` (referred, unqualified) and `vm/let` (through the alias) and
  `flint.impl.vm/let` (fully qualified), and gives NOTHING else -- a form that
  was not referred is not in scope unqualified, exactly as in Clojure.

  This matters for the same reason it matters in Clojure: two vocabularies can
  both define `let`, and which one a file means has to be a fact about the file
  rather than about the order somebody merged two maps."
  [ns-form vocabs]
  (let [reqs (->> (rest ns-form)
                  (filter (fn [f] (and (seq? f) (= :require (first f)))))
                  (mapcat rest))]
    (reduce
     (fn [scope spec]
       (let [spec (if (vector? spec) spec [spec])
             vname (first spec)
             opts (apply hash-map (rest spec))
             alias (:as opts)
             referred (:refer opts)
             vocab (get vocabs vname)]
         (when-not vocab
           (throw (ex-info (str "splint: no vocabulary " vname)
                           {:required vname :known (vec (keys vocabs))})))
         ;; FORMS AND TAGS ALIKE. A tag is referred and aliased exactly as a
         ;; form is -- `^Usize` has to mean whichever vocabulary's `Usize` this
         ;; file asked for, for the same reason `let` does.
         (let [names (concat (keys (:forms vocab)) (keys (:tags vocab)))]
           (as-> scope sc
             ;; Fully qualified always works.
             (reduce (fn [m k] (assoc m (symbol (str vname) (str k)) [vname k])) sc names)
             ;; The alias, when one was asked for.
             (if alias
               (reduce (fn [m k] (assoc m (symbol (str alias) (str k)) [vname k])) sc names)
               sc)
             ;; And only what was REFERRED, unqualified.
             (reduce (fn [m k]
                       (when-not (or (contains? (:forms vocab) k)
                                     (contains? (:tags vocab) k))
                         (throw (ex-info (str "splint: " vname " has no " k " to refer")
                                         {:vocabulary vname :symbol k})))
                       (assoc m k [vname k]))
                     sc (or referred []))))))
     {} reqs)))

(defn splint-tag
  "The TAG value a symbol names, resolved through the file's require scope.

  Tags are namespaced like everything else, so `^Usize` means the `Usize` this
  file asked for and not whichever one happened to be merged last."
  [ctx sym]
  (when sym
    (if-let [scope (:scope-syms ctx)]
      (when-let [[vname k] (get scope sym)]
        (get-in ctx [:vocabs vname :tags k]))
      (get-in ctx [:tags sym]))))

(defn splint-declare!
  "Register `sym` as callable by later forms in THIS source file.

  A source has to be able to define a helper and then call it. Without this,
  every helper would have to live in a vocabulary -- and a helper in a
  vocabulary is a helper written once per target, which is the cost this whole
  exercise exists to remove.

  Declarations are FILE-LOCAL and come second: a vocabulary name still wins, so
  a file cannot quietly redefine `let` out from under the reader. They are also
  ordered -- a call before the `defn` that declares it is not in scope, the
  same rule C and Rust modules differ on and the stricter of the two."
  [ctx sym f]
  (when-let [a (:locals ctx)] (swap! a assoc sym f))
  nil)

(defn- form-fn
  "The implementation of `head` for this context's target, or nil.

  Resolved through the source's require scope when there is one, so a name
  means what the file said it means, then through what the file itself has
  declared."
  [ctx head]
  (if-let [scope (:scope-syms ctx)]
    (or (when-let [[vname k] (get scope head)]
          (get-in ctx [:vocabs vname :forms k]))
        (get (some-> (:locals ctx) deref) head))
    (or (get-in ctx [:vocab head])
        (get (some-> (:locals ctx) deref) head))))

(defn splint-render
  "Run `form` into a STRING rather than into the current sink.

  Expressions compose; statements emit. A form implementation that needs a
  sub-expression calls this, and one that emits a statement calls
  `splint-emit!` -- which is how one vocabulary serves both positions without
  the translator deciding which is which."
  [ctx form]
  (let [sub (-> ctx
                (assoc :out (new-sink))
                (assoc-in [:scope :position] :expression))]
    (dispatch sub form)
    (resolve-sink (deref (:out sub)))))

(defn splint-position
  "What this form is being compiled AS: `:statement` or `:expression`.

  Pushed DOWN by whatever encloses it, because that is who knows. A top-level
  form in a method body is a statement whether or not the construct could also
  be an expression, and the method body is the thing that knows it is a body.

  This replaced metadata on the implementation saying what it EMITS, plus a
  per-target wrapper that combined the two. That was answering the question from
  the wrong end: a form does not have a kind, it has a POSITION, and the same
  `if` is a statement here and an expression there --

      (if c (do-a) (do-b))            statement
      (let [x (if c a b)] ...)        expression, and legal Rust

  -- so the enclosing form says which, and the implementation reads it and emits
  what that target wants in that position. One lookup, no wrapper, and nothing
  post-processes a string it did not produce."
  [ctx]
  (or (splint-get ctx :position) :expression))

(defn splint-in
  "Render `form` at `position`, with an anchor for anything it hoists."
  [ctx position form]
  ;; The anchor goes down FIRST, so anything hoisted lands above whatever this
  ;; turns out to be, however deep the form that hoisted it.
  (let [a (splint-emit-anchor! ctx)
        sub (-> ctx
                (assoc :out (new-sink))
                (assoc-in [:scope :splint/stmt-anchor] a)
                (assoc-in [:scope :position] position))]
    (dispatch sub form)
    (splint-emit! ctx (resolve-sink (deref (:out sub))))))

(defn splint-statement!
  "Render `form` as a statement -- the common call, kept short."
  [ctx form]
  (splint-in ctx :statement form))

(defn dispatch
  "One form. A seq whose head is in scope goes to its implementation.

  A seq whose head is a symbol NOT in scope is an error rather than a literal.
  Falling through would emit the symbol's name and produce something that looks
  like a call and is not one -- which is the failure mode a require scope exists
  to prevent."
  [ctx form]
  (cond
    (and (seq? form) (symbol? (first form)))
    (if-let [f (form-fn ctx (first form))]
      (f ctx form)
      (throw (ex-info (str "splint: " (first form) " is not in scope"
                           (when (:scope-syms ctx)
                             (str " -- this file requires "
                                  (pr-str (vec (sort (map str (keys (:scope-syms ctx))))))))) 
                      {:symbol (first form)})))
    :else (splint-emit! ctx (literal ctx form))))

(defn local-name
  "A dashed name, spelled the way the target spells a LOCAL.

  Only dashes are touched, so `SEED` and `C1` pass through untouched -- a
  constant is written the same in all three and must not be camelised into
  something a reader cannot grep for. Locals are camel in BOTH Java and C#,
  which is where they differ from a function name: C# pascalises the function
  and not the variable, and one rule for both would have produced `ItemHash`."
  [target sym]
  (let [s (str sym)]
    (if-not (clojure.string/includes? s "-")
      s
      (case target
        :rust (clojure.string/replace s "-" "_")
        (let [[h & r] (clojure.string/split s #"-")]
          (str h (clojure.string/join (mapv clojure.string/capitalize r))))))))

(defn literal
  "A non-form: a symbol, a number, a string, a boolean."
  [ctx v]
  (cond
    (string? v) (pr-str v)
    (symbol? v) (local-name (:target ctx) v)
    (nil? v) (or (splint-get ctx :nil) "null")
    :else (str v)))

;; ------------------------------------------------------------- declarations

(defn splint-output
  "Everything emitted into `ctx`, with anchors resolved. What a driver writes."
  [ctx]
  (resolve-sink (deref (:out ctx))))

(defn splint-ns
  "A vocabulary: `:name`, `:tags`, and `:forms` keyed by target.

  A TAG carries data rather than being a name. `^Stack` can hold the type each
  target spells it as AND a dispatch table saying what `(push it x)` becomes --
  which is the compile-time protocol, and the reason tags are values."
  [& {:keys [name tags forms]}]
  {:name name :tags (or tags {}) :forms (or forms {})})

(defn splint
  "A driver: `:targets` and the `:namespaces` in scope.

  Each target has `:path` (where a namespace's file goes), `:write` (the file's
  preamble and epilogue) and `:vfs` (where it is written)."
  [& {:keys [targets namespaces]}]
  {:targets (or targets {}) :namespaces (or namespaces [])})
