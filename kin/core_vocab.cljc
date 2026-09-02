(ns flint.impl.core
  "The shape of a program, in three languages: `defn`, `let`, `if`, `return`.

  Split out of the codec's vocabulary once a SECOND source needed it. Nothing
  here knows what is being compiled -- a function signature, a local, a branch
  and a return are the same three-way disagreement whatever the body says, and
  a vocabulary that had to restate them per subject would make every new source
  pay for the language before it paid for its own subject.

  A vocabulary MERGES these in rather than inheriting them, so a subject that
  needs a different `let` can still have one. What it must not do is get them
  by accident, which is why this is a namespace a source has to name."
  (:require [flint.kin :as sp]
            [clojure.string :as str]))

(defn t [ctx] (:target ctx))

(defn fmt [tmpl args]
  (reduce (fn [s i] (str/replace s (str "{" i "}") (nth args i ""))) tmpl (range (count args))))

(defn strip-parens
  "Drop the outer parentheses of a whole expression.

  **NOT for call arguments**, and that restriction was learned the hard way.
  Stripping them there produced

      u32(o, (int) n >>> 32)        // casts, THEN shifts -- wrong
      u32(o, (int) (n >>> 32))      // what it has to be

  because the argument is substituted into another template that does not
  re-parenthesise it, so the parens were carrying the precedence. An aesthetic
  rule silently changed the semantics, which is the one way the not-worse rule
  can do harm: correct always outranks tidy.

  Safe only where the expression is the WHOLE right-hand side of an assignment,
  which is where it is used."
  [c]
  (if (and (str/starts-with? c "(") (str/ends-with? c ")")
           (loop [i 1 d 1]
             (cond (>= i (dec (count c))) (= d 1)
                   (= \( (nth c i)) (recur (inc i) (inc d))
                   (= \) (nth c i)) (if (= d 1) false (recur (inc i) (dec d)))
                   :else (recur (inc i) d))))
    (subs c 1 (dec (count c)))
    c))

(defn- delimited?
  "Does `tmpl` place `{i}` IMMEDIATELY inside a delimiter pair?

  This is the safe test for stripping an argument's outer parentheses, and it
  is a question about the TEMPLATE rather than about the expression -- which is
  what the earlier bug got wrong. `to-i32` is `((int) {0})`: the `{0}` sits
  after a cast, so its parens are load-bearing and stripping them produced
  `(int) n >>> 32`, which casts and then shifts. `wrapping_add({1})` puts the
  argument between `(` and `)` with nothing to bind to on either side, so its
  outer parens can only be noise.

  So the rule is not `strip when the expression looks fully parenthesised` --
  it is `strip only where the template guarantees there is nothing to bind
  with`. Same conclusion the hard way round: correct outranks tidy, and the way
  to be tidy safely is to prove there is nothing to break."
  [tmpl i]
  (let [k (str "{" i "}")
        at (.indexOf (str tmpl) k)
        ;; Skip one space, so `, {2})` counts as delimited the way `,{2})`
        ;; does. Templates are written for a human to read, and every one of
        ;; them puts a space after the comma.
        before (if (and (pos? at) (= \space (nth tmpl (dec at) \space)))
                 (nth tmpl (- at 2) \space)
                 (nth tmpl (dec at) \space))]
    (and (pos? at)
         (contains? #{\( \, \[} before)
         (< (+ at (count k)) (inc (count tmpl)))
         (contains? #{\) \, \]} (nth tmpl (+ at (count k)) \space)))))

(defn call
  "A form that is a call: render the arguments, fill the target's template, and
  emit as a statement or an expression depending on where it sits."
  [tmpls]
  (fn [ctx form]
    (let [tmpl (get tmpls (t ctx))
          as (vec (map-indexed
                   (fn [i f]
                     (let [c (sp/kin-render ctx f)]
                       (if (delimited? tmpl i) (strip-parens c) c)))
                   (rest form)))
          code (fmt tmpl as)]
      (if (= :statement (sp/kin-position ctx))
        (sp/kin-emit! ctx (sp/indent-of ctx) code ";\n")
        (sp/kin-emit! ctx code)))))

(def ops
  {'+ "+" '- "-" '* "*" '< "<" '> ">" '== "==" 'not "!"
   'bit-shift-right ">>" 'bit-and "&" 'bit-or "|" 'bit-xor "^"
   ;; Integer division. `/` on two ints truncates in all three, so `quot` is
   ;; the honest name for what it does -- and `/` is left unbound rather than
   ;; meaning something different from Clojure's.
   'quot "/" 'rem "%"})

(defn op-form [sym]
  (fn [ctx form]
    (let [as (mapv (fn [f] (sp/kin-render ctx f)) (rest form))]
      (sp/kin-emit! ctx (if (= 1 (count as))
                             (str (get ops sym) (first as))
                             (str "(" (str/join (str " " (get ops sym) " ") as) ")"))))))

;; ------------------------------------------------------------------- naming
;;
;; One name in the source, three conventions in the output. This is the whole
;; of what a reader has to know to find the generated function by hand.

(defn- cons* [x xs] (if x (cons x xs) xs))

(defn target-name [ctx nm]
  (case (t ctx)
    :rust (str/replace (str nm) "-" "_")
    :java (let [[h & r] (str/split (str nm) #"-")] (str h (str/join (mapv str/capitalize r))))
    :csharp (str/join (mapv str/capitalize (str/split (str nm) #"-")))))

(defn- ty-of [ctx default tag] (get-in (or (sp/kin-tag ctx tag) default) [:types (t ctx)]))

(defn- defn-form
  "A function, framed the way each target frames one.

  The signature is where three languages disagree most and it is entirely
  mechanical: a return type before or after, `static` or `fn`, `self` or not.

  It also DECLARES the name, so a later form in the same file can call it. A
  source that could define a function and not call it would push every helper
  into the vocabulary, and a helper in the vocabulary is a helper written three
  times -- which is the thing this whole exercise exists to stop."
  [default]
  (fn [ctx form]
    (let [[_ nm params & body] form
          ret (:tag (meta nm))
          throws? (:throws (meta nm))
          pub? (:pub (meta nm))
          ;; `^:unchecked`: C# wraps the BODY, which is what the hand-written
          ;; runtime does and is why murmur's wrapping arithmetic can then be
          ;; written as plain `*` and `+`. Per-expression `unchecked(...)`
          ;; nests into `unchecked(unchecked(a * b) + c)` -- the same IL and a
          ;; good deal harder to read, which the not-worse rule covers.
          unchecked? (:unchecked (meta nm))
          ;; `^:method`: the FIRST parameter is the receiver.
          ;;
          ;; Rust puts these on `impl Rt` and the JVM and CLR make them
          ;; statics that take the runtime as an argument. That is the same
          ;; function three ways, and it is the difference that has kept `Eq`,
          ;; `Seqs` and most of the bulk out of reach -- not the bodies, which
          ;; already agree, but where the receiver goes.
          method? (:method (meta nm))
          recv (when method? (first params))
          params (if method? (rest params) params)
          ps (partition 2 (interleave params (map (fn [p] (:tag (meta p))) params)))
          ty (partial ty-of ctx default)]
      ;; The receiver is spelled `self` in Rust and by its own name in the
      ;; other two, so a body that says `(. rt gc)` comes out as `self.gc`
      ;; there and `rt.gc` here. Registering the NAME is all that takes.
      (when recv
        (sp/kin-declare-name! ctx recv {:rust "self" :java (str recv) :csharp (str recv)}))
      (sp/kin-declare!
       ctx nm
       (fn [c f]
         (let [as (mapv (fn [x] (sp/kin-render c x)) (rest f))
               code (str (if (and method? (= :rust (t c)))
                           (str (first as) "." (target-name c nm)
                                "(" (str/join ", " (rest as)) ")")
                           (str (target-name c nm) "(" (str/join ", " as) ")"))
                         (if (and throws? (= :rust (t c))) "?" ""))]
           (if (= :statement (sp/kin-position c))
             (sp/kin-emit! c (sp/indent-of c) code ";\n")
             (sp/kin-emit! c code)))))
      ;; `^:inline`. Rust is the only one that says so in the source; the JVM
      ;; and the CLR decide at run time from profile data, which is strictly
      ;; more information than a source can have. So the mark is emitted for
      ;; one target and dropped by two -- and dropping it is not a loss.
      (when (and (:inline (meta nm)) (= :rust (t ctx)))
        (sp/kin-emit! ctx (sp/indent-of ctx) "#[inline]\n"))
      (case (t ctx)
        ;; RUST RETURNS A RESULT WHERE THE OTHERS THROW, and that is the first
        ;; divergence found in this port that is not naming: it changes the
        ;; signature and every call site. `^:throws` on the name says a function
        ;; can fail; Rust turns the return type into `Result<T, String>` and a
        ;; call to it gets `?`, and Java and C# ignore the mark entirely because
        ;; an exception needs nothing in either place.
        :rust (sp/kin-emit!
               ctx (sp/indent-of ctx) (if pub? "pub fn " "fn ") (target-name ctx nm) "("
               ;; `^:mut` on a PARAMETER. Rust is the only one of the three
               ;; that has to say a parameter is reassigned; Java and C# read
               ;; the mark and emit nothing, which is the ordinary shape of a
               ;; divergence here -- one target needs a word, so the source
               ;; says the thing and each target spends what it must.
               (str/join ", " (cons* (when recv (if (:mut (meta recv)) "&mut self" "&self"))
                                     (mapv (fn [[p tag]] (str (when (:mut (meta p)) "mut ")
                                                              (sp/local-name :rust p) ": " (ty tag))) ps)))
               ")"
               (cond
                 (and ret throws?) (str " -> Result<" (ty ret) ", String>")
                 ret (str " -> " (ty ret))
                 throws? " -> Result<(), String>"
                 :else "")
               " {\n")
        :java (sp/kin-emit!
               ctx (sp/indent-of ctx) (if pub? "public static " "static ")
               (if ret (ty ret) "void") " " (target-name ctx nm) "("
               (str/join ", " (cons* (when recv (str (ty (:tag (meta recv))) " " recv))
                                     (mapv (fn [[p tag]] (str (ty tag) " " (sp/local-name :java p))) ps))) ") {\n")
        :csharp (sp/kin-emit!
                 ctx (sp/indent-of ctx) (if pub? "public static " "static ")
               (if ret (ty ret) "void") " " (target-name ctx nm) "("
                 (str/join ", " (cons* (when recv (str (ty (:tag (meta recv))) " " recv))
                                       (mapv (fn [[p tag]] (str (ty tag) " " (sp/local-name :csharp p))) ps))) ") {\n"))
      (let [wrap? (and unchecked? (= :csharp (t ctx)))]
        (sp/kin-scoped
         ctx {:key :fn :value nm :indent 1}
         (fn [inner]
           (when wrap? (sp/kin-emit! inner (sp/indent-of inner) "unchecked {\n"))
           (sp/kin-scoped
            inner {:key :throws :value throws? :indent (if wrap? 1 0)}
            (fn [in2] (doseq [f body] (sp/kin-statement! in2 f))))
           (when wrap? (sp/kin-emit! inner (sp/indent-of inner) "}\n")))))
      (sp/kin-emit! ctx (sp/indent-of ctx) "}\n"))))

(defn- let-form [default]
  (fn [ctx form]
    (let [[_ bindings & body] form]
      (doseq [[nm init] (partition 2 bindings)]
        (let [ty (ty-of ctx default (:tag (meta nm)))
              code (strip-parens (sp/kin-render ctx init))]
          (sp/kin-emit! ctx (sp/indent-of ctx)
                           ;; `^:mut` on a LOCAL, for the same reason it is on
                           ;; a parameter: Rust alone has to say that a binding
                           ;; is reassigned. Found the moment a loop existed to
                           ;; accumulate into one -- `let acc: u32 = 0;`
                           ;; followed by `acc = ...` does not compile.
                           (let [n (sp/local-name (t ctx) nm)]
                             (case (t ctx)
                               :rust (str "let " (when (:mut (meta nm)) "mut ")
                                          n ": " ty " = " code ";\n")
                               (str ty " " n " = " code ";\n"))))))
      (doseq [f body] (sp/kin-statement! ctx f)))))

(defn- defstruct-form
  "A small mutable record, declared the way each target declares one.

  The three differ in mechanism rather than meaning: Rust wants a `struct`,
  Java and C# want a class with fields. What a source says is the FIELDS."
  [default]
  (fn [ctx form]
    (let [[_ nm fields] form
          fs (mapv (fn [f] [f (:tag (meta f))]) fields)
          pascal (str/join (mapv str/capitalize (str/split (str nm) #"-")))]
      (case (t ctx)
        :rust (do (sp/kin-emit! ctx (sp/indent-of ctx) "struct " pascal " {\n")
                  (doseq [[f tag] fs]
                    (sp/kin-emit! ctx (sp/indent-of ctx) "    " f ": "
                                     (ty-of ctx default tag) ",\n"))
                  (sp/kin-emit! ctx (sp/indent-of ctx) "}\n"))
        :java (do (sp/kin-emit! ctx (sp/indent-of ctx) "static final class " pascal " {\n")
                  (doseq [[f tag] fs]
                    (sp/kin-emit! ctx (sp/indent-of ctx) "    " (ty-of ctx default tag) " " f ";\n"))
                  (sp/kin-emit! ctx (sp/indent-of ctx) "}\n"))
        :csharp (do (sp/kin-emit! ctx (sp/indent-of ctx) "sealed class " pascal " {\n")
                    (doseq [[f tag] fs]
                      (sp/kin-emit! ctx (sp/indent-of ctx) "    internal "
                                       (ty-of ctx default tag) " " f ";\n"))
                    (sp/kin-emit! ctx (sp/indent-of ctx) "}\n"))))))

(defn comment-form
  "`(comment \"line\" \"line\")` -- a comment, in the output.

  Added the moment the first port LOST one. `category` carries an explanation
  of why a row ref is in the map category, with a pointer to the decision that
  settled it, and generating the function silently dropped it from two
  runtimes. A generator that discards the reasoning keeps the code and throws
  away the part that was expensive to work out.

  `;;` comments in a kin source are for the SOURCE and never reach the
  output -- the reader discards them before any of this runs. So a comment
  meant for a reader of the generated file has to be said as a form, and the
  difference between the two is exactly the difference between explaining the
  rule and explaining the code it produces."
  [ctx form]
  (doseq [line (rest form)]
    (sp/kin-emit! ctx (sp/indent-of ctx) "// " line "\n")))

(defn doc-form
  "`(doc \"line\" ...)` -- a DOC comment, `///` on all three.

  Distinct from `comment` because the distinction is real in the output: `///`
  is picked up by rustdoc, javadoc and the C# XML doc pipeline, and `//` is
  not. The first emit of the CHAMP accessors turned a `///` into a `//` and
  quietly demoted a documented function to an undocumented one in three
  runtimes at once."
  [ctx form]
  (doseq [line (rest form)]
    (sp/kin-emit! ctx (sp/indent-of ctx) "/// " line "\n")))

(defn- field-form
  "`(. r i)` -- a field, readable and assignable. One spelling everywhere, which
  is why it is one form."
  [ctx form]
  (let [[_ obj f] form]
    (sp/kin-emit! ctx (sp/kin-render ctx obj) "." (str f))))

(def base-compound
  "Forms with a compound-assignment spelling, PER TARGET.

  Per target because a form can have one in two languages and not the third:
  `mul32` is `*=` on the JVM and the CLR and `wrapping_mul` in Rust, which has
  no compound spelling at all. A target with no entry simply gets the long
  form, which is what it would have been written by hand.

  Shifts are deliberately absent everywhere: `>>>` against `>>` is the
  difference the vocabulary exists to hide, and `>>>=` would put it back in
  the source."
  (let [everywhere (fn [op] {:rust op :java op :csharp op})]
    {'+ (everywhere "+=") '- (everywhere "-=") '* (everywhere "*=")
     'bit-and (everywhere "&=") 'bit-or (everywhere "|=")
     'bit-xor (everywhere "^=")}))

(defn- head-op
  "The compound spelling of a head symbol for this target, through the file's
  require scope."
  [table ctx head]
  (when (symbol? head)
    (let [k (if (:scope-syms ctx) (second (get (:scope-syms ctx) head)) head)]
      (get-in table [k (t ctx)]))))

(defn- set-form
  "`(set x v)`.

  `x = x ^ y` is written `x ^= y` where the operator has a compound form, which
  is not an optimisation -- the two compile identically -- but is what the
  hand-written code says, and the rule is that generated code may not be worse
  than what it replaces. A diff full of `h1 = h1 ^ len` against `h1 ^= len` is
  a diff nobody reads, and an unread diff is the acceptance check not running."
  [table]
  (fn [ctx form]
    (let [[_ place value] form
          p (sp/kin-render ctx place)
          cmp (when (seq? value) (head-op table ctx (first value)))]
      (if (and cmp (= 3 (count value)) (= p (sp/kin-render ctx (second value))))
        (sp/kin-emit! ctx (sp/indent-of ctx) p " " cmp " "
                         (strip-parens (sp/kin-render ctx (nth value 2))) ";\n")
        (sp/kin-emit! ctx (sp/indent-of ctx) p " = "
                         (strip-parens (sp/kin-render ctx value)) ";\n")))))

(defn- head-is-if?
  "Is this seq's head the `if` THIS FILE means? Resolved through the require
  scope, so a source that aliased something else to `if` is not chained by
  accident."
  [ctx form]
  (let [h (first form)]
    (if-let [scope (:scope-syms ctx)]
      (= 'if (second (get scope h)))
      (= 'if h))))

(declare if-body)

(defn- if-form [ctx form]
  (sp/kin-emit! ctx (sp/indent-of ctx))
  (if-body ctx form))

(defn- if-body [ctx form]
  (let [[_ test then else] form
        c (sp/kin-render ctx test)]
    (sp/kin-emit! ctx
                     ;; The test is a WHOLE expression with nothing to bind
                     ;; with, so its outer parens are the safe case to strip --
                     ;; Rust warns on them and the other two would otherwise
                     ;; get `if ((x == 0))`.
                     (let [c (strip-parens c)]
                       (if (= :rust (t ctx)) (str "if " c " {\n") (str "if (" c ") {\n"))))
    (sp/kin-scoped ctx {:key :in-if :value true :indent 1}
                      (fn [inner] (sp/kin-statement! inner then)))
    ;; An `else` whose body is itself an `if` becomes `} else if (...) {`
    ;; rather than a nested block. Without this every extra arm cost a brace
    ;; level, and a five-arm dispatch came out indented five deep -- which no
    ;; hand-written file here does, so it fails the not-worse rule on reading
    ;; even though it compiles.
    (cond
      (and else (seq? else) (= 'if (first else)) (head-is-if? ctx else))
      (do (sp/kin-emit! ctx (sp/indent-of ctx) "} else ")
          (sp/kin-scoped ctx {:key :else-if :value true}
                            (fn [inner] (if-body inner else))))

      else
      (do (sp/kin-emit! ctx (sp/indent-of ctx) "} else {\n")
          (sp/kin-scoped ctx {:key :in-if :value true :indent 1}
                            (fn [inner] (sp/kin-statement! inner else)))
          (sp/kin-emit! ctx (sp/indent-of ctx) "}\n"))

      :else (sp/kin-emit! ctx (sp/indent-of ctx) "}\n"))))

(defn- return-form
  "`(return v)`, and `(return)` from a function with no value.

  The bare form emitted `return null;` on all three and compiled nowhere,
  which is a hole `Codec.java` alone would hit fourteen times. Rust's is
  `Ok(())` when the function can fail, because `^:throws` turns its return
  type into `Result<(), String>`."
  [ctx form]
  (let [throws? (sp/kin-get ctx :throws)]
    (if (= 1 (count form))
      (sp/kin-emit! ctx (sp/indent-of ctx)
                       (if (and (= :rust (t ctx)) throws?) "return Ok(());\n" "return;\n"))
      (let [v (strip-parens (sp/kin-render ctx (second form)))]
        (sp/kin-emit! ctx (sp/indent-of ctx) "return "
                         (if (and (= :rust (t ctx)) throws?) (str "Ok(" v ")") v) ";\n")))))

(defn- for-form
  "`(for [^I32 c start end] body...)` -- a COUNTED loop, half-open.

  Blocker number one for everything after `Hash`. `for (int c = 0; c < n; c++)`
  appears twelve times in `Codec.java` and seventeen in `codec.rs`, and `Maps`,
  `Table`, `Vec`, `Str`, `Bytes`, `Snap` and `Pike` are all loops over arrays.
  Nothing in phase 3 can be written without it.

  Rust says the range and infers the index type from it; the other two spell
  out all three clauses and need the type. The bound is evaluated ONCE in
  Rust's `start..end` and once per iteration in a C-style `for`, so a bound
  with a side effect would differ -- but a bound with a side effect in a loop
  header is not something any of the three hand-written runtimes does, and a
  source that wants one can hoist it into a `let`."
  [default]
  (fn [ctx form]
    (let [[_ binding & body] form
          [nm start end] binding
          ty (get-in (or (sp/kin-tag ctx (:tag (meta nm))) default) [:types (t ctx)])
          n (sp/local-name (t ctx) nm)
          a (strip-parens (sp/kin-render ctx start))
          b (strip-parens (sp/kin-render ctx end))]
      (sp/kin-emit! ctx (sp/indent-of ctx)
                       (case (t ctx)
                         :rust (str "for " n " in " a ".." b " {\n")
                         (str "for (" ty " " n " = " a "; " n " < " b "; " n "++) {\n")))
      (sp/kin-scoped ctx {:key :in-loop :value true :indent 1}
                        (fn [inner] (doseq [f body] (sp/kin-statement! inner f))))
      (sp/kin-emit! ctx (sp/indent-of ctx) "}\n"))))

(defn- while-form
  "`(while test body...)`.

  Moved here from `flint.impl.vm`, which was the only vocabulary that had one
  -- so a codec or a collections source could not loop at all, even where a
  `while` was exactly right. It carried its own `let`, `set` and `if` alongside
  it, which predate this file.

  THE TEST GOES INSIDE only when it has to. A loop test is evaluated every
  iteration, so a test needing a temporary cannot stay in the condition; but
  one that does not need a temporary can, and rewriting it anyway emits

      while true { if !c { break; } ... }

  where a person would write `while !c { ... }`. That is worse than the
  hand-written code, and generated code that is worse is not worth generating."
  [ctx form]
  (let [[_ test & body] form
        c (strip-parens (sp/kin-render ctx test))]
    (sp/kin-emit! ctx (sp/indent-of ctx)
                     (if (= :rust (t ctx)) (str "while " c " {\n") (str "while (" c ") {\n")))
    (sp/kin-scoped ctx {:key :in-loop :value true :indent 1}
                      (fn [inner] (doseq [f body] (sp/kin-statement! inner f))))
    (sp/kin-emit! ctx (sp/indent-of ctx) "}\n")))

(defn forms-for
  "The shape forms. `:default-tag` is the tag an untagged name is given, which
  is a per-subject choice and so is asked for rather than assumed."
  [{:keys [default-tag compound]}]
  (merge
   {'defn (defn-form default-tag)
    'let (let-form default-tag)
    'defstruct (defstruct-form default-tag)
    '. field-form 'set (set-form (merge base-compound compound)) 'if if-form 'return return-form
    'comment comment-form 'doc doc-form
    'for (for-form default-tag) 'while while-form
    'break (fn [ctx _] (sp/kin-emit! ctx (sp/indent-of ctx) "break;\n"))
    'continue (fn [ctx _] (sp/kin-emit! ctx (sp/indent-of ctx) "continue;\n"))
    'do (fn [ctx form] (doseq [f (rest form)] (sp/kin-statement! ctx f)))}
   (reduce (fn [m s] (assoc m s (op-form s))) {} (keys ops))))
