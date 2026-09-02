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

(defn splint-emit!
  "Append to the current sink."
  [ctx & parts]
  (swap! (:out ctx) conj (apply str parts))
  nil)

(defn splint-before!
  "Append a STATEMENT before the one currently being built.

  This is what makes hoisting a target's own business: Rust's `invoke` binds a
  temporary and emits the binding here, and no other target's implementation --
  or the translator -- has to know that happened."
  [ctx & parts]
  (swap! (:pre ctx) conj (apply str parts))
  nil)

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
    (str/join (deref (:out sub)))))

(defn splint-statement!
  "Run `form` as a statement, flushing anything it hoisted BEFORE it.

  The order is the point: a temporary must be bound before the statement that
  reads it, and a statement that hoists nothing pays nothing.

  ## Expressions and statements are different, and the vocabulary says which

  `(set-r si x)` is a call -- an EXPRESSION -- and its implementation emits just
  the call, because in `(if (nil? x) ...)` that is exactly what is wanted. Used
  as a statement it needs indentation and a terminator, and the first version
  emitted neither: `self.set_r(si, t2__)while true {`.

  Rather than guess -- a trailing newline would have been a workable heuristic
  and a bad rule -- the vocabulary DECLARES which heads emit complete
  statements. Everything else is an expression and gets wrapped."
  [ctx form]
  (let [sub (assoc ctx :out (new-sink) :pre (new-sink))
        head (when (seq? form) (first form))
        stmt? (contains? (or (:statements ctx) #{}) head)]
    (dispatch sub form)
    (doseq [p (deref (:pre sub))] (splint-emit! ctx p))
    (let [body (str/join (deref (:out sub)))]
      (if stmt?
        (splint-emit! ctx body)
        (splint-emit! ctx (indent-of ctx) body ";\n")))))

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
