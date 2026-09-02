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

(defn- form-fn
  "The implementation of `head` for this context's target, or nil."
  [ctx head]
  (get-in ctx [:vocab head]))

(defn splint-render
  "Run `form` into a STRING rather than into the current sink.

  Expressions compose; statements emit. A form implementation that needs a
  sub-expression calls this, and one that emits a statement calls
  `splint-emit!` -- which is how one vocabulary serves both positions without
  the translator deciding which is which."
  [ctx form]
  (let [sub (assoc ctx :out (new-sink))]
    (dispatch sub form)
    (resolve-sink (deref (:out sub)))))

(defn emits
  "What kind of thing a form's implementation produces.

  Metadata ON THE IMPLEMENTATION rather than a list kept beside it, so the two
  cannot drift: a form that starts emitting a complete statement says so where
  it is written."
  [f]
  (or (:splint/emits (meta f)) :expression))

(defn splint-place!
  "Render `form` at `position`, and let the TARGET decide how a thing of that
  kind sits there.

  ## Why the target decides

  \"Is this a statement\" is not a property of a form and not a property of the
  language-neutral source. It is a question about the TARGET, and the targets
  disagree -- including two of ours:

  * in Java and C#, `if` is a statement and cannot produce a value;
  * **in Rust `if` is an expression**, and `let x = if c { a } else { b };` is
    what a person writes;
  * a language with no statements at all -- a Lisp backend, which is the whole
    point of splint being language-agnostic -- has nothing to wrap.

  So a form's implementation says what it EMITS, in metadata, and the target
  supplies `:place`, which is handed the position and the kind and decides. A
  target with no statements supplies a `:place` that returns its argument, and
  the concept costs it nothing."
  [ctx position form]
  ;; The anchor goes down FIRST, so anything hoisted lands above whatever this
  ;; turns out to be, however deep the form that hoisted it.
  (let [a (splint-emit-anchor! ctx)
        sub (-> ctx
                (assoc :out (new-sink))
                (assoc-in [:scope :splint/stmt-anchor] a)
                (assoc :position position))
        head (when (seq? form) (first form))
        impl (when head (get-in ctx [:vocab head]))
        kind (if impl (emits impl) :expression)]
    (dispatch sub form)
    (let [body (resolve-sink (deref (:out sub)))
          place (or (:place ctx) (fn [_ _ _ b] b))]
      (splint-emit! ctx (place ctx position kind body)))))

(defn splint-statement!
  "`splint-place!` at statement position -- the common call, kept short."
  [ctx form]
  (splint-place! ctx :statement form))

(defn dispatch
  "One form. A seq whose head the vocabulary knows goes to its implementation;
  anything else is a literal."
  [ctx form]
  (if (and (seq? form) (symbol? (first form)) (form-fn ctx (first form)))
    ((form-fn ctx (first form)) ctx form)
    (splint-emit! ctx (literal ctx form))))

(defn literal
  "A non-form: a symbol, a number, a string, a boolean."
  [ctx v]
  (cond
    (string? v) (pr-str v)
    (symbol? v) (str v)
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
