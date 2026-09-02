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
  (:require [flint.splint :as sp]
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

(defn call
  "A form that is a call: render the arguments, fill the target's template, and
  emit as a statement or an expression depending on where it sits."
  [tmpls]
  (fn [ctx form]
    (let [as (mapv (fn [f] (sp/splint-render ctx f)) (rest form))
          code (fmt (get tmpls (t ctx)) as)]
      (if (= :statement (sp/splint-position ctx))
        (sp/splint-emit! ctx (sp/indent-of ctx) code ";\n")
        (sp/splint-emit! ctx code)))))

(def ops
  {'+ "+" '- "-" '* "*" '< "<" '> ">" '== "==" 'not "!"
   'bit-shift-right ">>" 'bit-and "&" 'bit-or "|" 'bit-xor "^"})

(defn op-form [sym]
  (fn [ctx form]
    (let [as (mapv (fn [f] (sp/splint-render ctx f)) (rest form))]
      (sp/splint-emit! ctx (if (= 1 (count as))
                             (str (get ops sym) (first as))
                             (str "(" (str/join (str " " (get ops sym) " ") as) ")"))))))

;; ------------------------------------------------------------------- naming
;;
;; One name in the source, three conventions in the output. This is the whole
;; of what a reader has to know to find the generated function by hand.

(defn target-name [ctx nm]
  (case (t ctx)
    :rust (str/replace (str nm) "-" "_")
    :java (let [[h & r] (str/split (str nm) #"-")] (str h (str/join (mapv str/capitalize r))))
    :csharp (str/join (mapv str/capitalize (str/split (str nm) #"-")))))

(defn- ty-of [ctx default tag] (get-in (or (sp/splint-tag ctx tag) default) [:types (t ctx)]))

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
          ps (partition 2 (interleave params (map (fn [p] (:tag (meta p))) params)))
          ty (partial ty-of ctx default)]
      (sp/splint-declare!
       ctx nm
       (fn [c f]
         (let [as (mapv (fn [x] (sp/splint-render c x)) (rest f))
               code (str (target-name c nm) "(" (str/join ", " as) ")"
                         (if (and throws? (= :rust (t c))) "?" ""))]
           (if (= :statement (sp/splint-position c))
             (sp/splint-emit! c (sp/indent-of c) code ";\n")
             (sp/splint-emit! c code)))))
      (case (t ctx)
        ;; RUST RETURNS A RESULT WHERE THE OTHERS THROW, and that is the first
        ;; divergence found in this port that is not naming: it changes the
        ;; signature and every call site. `^:throws` on the name says a function
        ;; can fail; Rust turns the return type into `Result<T, String>` and a
        ;; call to it gets `?`, and Java and C# ignore the mark entirely because
        ;; an exception needs nothing in either place.
        :rust (sp/splint-emit!
               ctx (sp/indent-of ctx) "fn " (target-name ctx nm) "("
               ;; `^:mut` on a PARAMETER. Rust is the only one of the three
               ;; that has to say a parameter is reassigned; Java and C# read
               ;; the mark and emit nothing, which is the ordinary shape of a
               ;; divergence here -- one target needs a word, so the source
               ;; says the thing and each target spends what it must.
               (str/join ", " (mapv (fn [[p tag]] (str (when (:mut (meta p)) "mut ")
                                                       (sp/local-name :rust p) ": " (ty tag))) ps))
               ")"
               (cond
                 (and ret throws?) (str " -> Result<" (ty ret) ", String>")
                 ret (str " -> " (ty ret))
                 throws? " -> Result<(), String>"
                 :else "")
               " {\n")
        :java (sp/splint-emit!
               ctx (sp/indent-of ctx) "static " (if ret (ty ret) "void") " " (target-name ctx nm) "("
               (str/join ", " (mapv (fn [[p tag]] (str (ty tag) " " (sp/local-name :java p))) ps)) ") {\n")
        :csharp (sp/splint-emit!
                 ctx (sp/indent-of ctx) "static " (if ret (ty ret) "void") " " (target-name ctx nm) "("
                 (str/join ", " (mapv (fn [[p tag]] (str (ty tag) " " (sp/local-name :csharp p))) ps)) ") {\n"))
      (sp/splint-scoped ctx {:key :fn :value nm :indent 1}
                        (fn [inner]
                          (sp/splint-scoped inner {:key :throws :value throws?}
                                            (fn [in2] (doseq [f body]
                                                        (sp/splint-statement! in2 f))))))
      (sp/splint-emit! ctx (sp/indent-of ctx) "}\n"))))

(defn- let-form [default]
  (fn [ctx form]
    (let [[_ bindings & body] form]
      (doseq [[nm init] (partition 2 bindings)]
        (let [ty (ty-of ctx default (:tag (meta nm)))
              code (strip-parens (sp/splint-render ctx init))]
          (sp/splint-emit! ctx (sp/indent-of ctx)
                           (let [n (sp/local-name (t ctx) nm)]
                             (case (t ctx)
                               :rust (str "let " n ": " ty " = " code ";\n")
                               (str ty " " n " = " code ";\n"))))))
      (doseq [f body] (sp/splint-statement! ctx f)))))

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
        :rust (do (sp/splint-emit! ctx (sp/indent-of ctx) "struct " pascal " {\n")
                  (doseq [[f tag] fs]
                    (sp/splint-emit! ctx (sp/indent-of ctx) "    " f ": "
                                     (ty-of ctx default tag) ",\n"))
                  (sp/splint-emit! ctx (sp/indent-of ctx) "}\n"))
        :java (do (sp/splint-emit! ctx (sp/indent-of ctx) "static final class " pascal " {\n")
                  (doseq [[f tag] fs]
                    (sp/splint-emit! ctx (sp/indent-of ctx) "    " (ty-of ctx default tag) " " f ";\n"))
                  (sp/splint-emit! ctx (sp/indent-of ctx) "}\n"))
        :csharp (do (sp/splint-emit! ctx (sp/indent-of ctx) "sealed class " pascal " {\n")
                    (doseq [[f tag] fs]
                      (sp/splint-emit! ctx (sp/indent-of ctx) "    internal "
                                       (ty-of ctx default tag) " " f ";\n"))
                    (sp/splint-emit! ctx (sp/indent-of ctx) "}\n"))))))

(defn- field-form
  "`(. r i)` -- a field, readable and assignable. One spelling everywhere, which
  is why it is one form."
  [ctx form]
  (let [[_ obj f] form]
    (sp/splint-emit! ctx (sp/splint-render ctx obj) "." (str f))))

(defn- set-form [ctx form]
  (let [[_ place value] form]
    (sp/splint-emit! ctx (sp/indent-of ctx)
                     (sp/splint-render ctx place) " = "
                     (strip-parens (sp/splint-render ctx value)) ";\n")))

(defn- if-form [ctx form]
  (let [[_ test then else] form
        c (sp/splint-render ctx test)]
    (sp/splint-emit! ctx (sp/indent-of ctx)
                     ;; Rust warns on the parens the other two require.
                     (if (= :rust (t ctx))
                       (str "if " (strip-parens c) " {\n")
                       (str "if (" c ") {\n")))
    (sp/splint-scoped ctx {:key :in-if :value true :indent 1}
                      (fn [inner] (sp/splint-statement! inner then)))
    (when else
      (sp/splint-emit! ctx (sp/indent-of ctx) "} else {\n")
      (sp/splint-scoped ctx {:key :in-if :value true :indent 1}
                        (fn [inner] (sp/splint-statement! inner else))))
    (sp/splint-emit! ctx (sp/indent-of ctx) "}\n")))

(defn- return-form [ctx form]
  (let [v (strip-parens (sp/splint-render ctx (second form)))
        throws? (sp/splint-get ctx :throws)]
    (sp/splint-emit! ctx (sp/indent-of ctx) "return "
                     (if (and (= :rust (t ctx)) throws?) (str "Ok(" v ")") v) ";\n")))

(defn forms-for
  "The shape forms. `:default-tag` is the tag an untagged name is given, which
  is a per-subject choice and so is asked for rather than assumed."
  [{:keys [default-tag]}]
  (merge
   {'defn (defn-form default-tag)
    'let (let-form default-tag)
    'defstruct (defstruct-form default-tag)
    '. field-form 'set set-form 'if if-form 'return return-form
    'do (fn [ctx form] (doseq [f (rest form)] (sp/splint-statement! ctx f)))}
   (reduce (fn [m s] (assoc m s (op-form s))) {} (keys ops))))
