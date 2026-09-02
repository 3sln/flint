(ns flint.impl.codec
  "The vocabulary the wire codec is written in (`doc/goals/splint-port.md`).

  Separate from `flint.impl.vm` because a source should ask for what it needs
  and no more: the codec has no value stack and no frames, and a file that
  cannot name `vpush` cannot reach for it by accident.

  Everything here is naming. The three codecs already agree on structure -- what
  differs is `out.write` against `out.WriteByte((byte) x)`, `>>>` against
  `((ulong) n >> 32)`, `getBytes(UTF_8)` against `Encoding.UTF8.GetBytes`. That
  is a vocabulary, which is the whole bet."
  (:require [flint.splint :as sp]
            [clojure.string :as str]))

(def I32 {:name 'I32 :types {:rust "u32" :java "int" :csharp "int"} :methods {}})
(def I64 {:name 'I64 :types {:rust "u64" :java "long" :csharp "long"} :methods {}})
(def Text {:name 'Text :types {:rust "&str" :java "String" :csharp "string"} :methods {}})
(def Bytes {:name 'Bytes :types {:rust "Vec<u8>" :java "byte[]" :csharp "byte[]"} :methods {}})

(def Sink
  "The byte sink. Three types for one idea, which is why it is a tag."
  {:name 'Sink
   :types {:rust "&mut Vec<u8>" :java "ByteArrayOutputStream" :csharp "MemoryStream"}
   :methods {}})

(def MaybeText
  "A string that may be ABSENT, which is not the same as empty -- that is what
  distinguishes `:kw` from `:/kw`.

  Rust says so in the type and the other two use null, which is the second
  divergence in this port that is not naming. It rides the TAG rather than a
  mark on the function, because absence is a property of the value."
  {:name 'MaybeText
   :types {:rust "Option<String>" :java "String" :csharp "string"}
   :methods {}})

(def Reader
  {:name 'Reader
   :types {:rust "&mut Reader" :java "Reader" :csharp "Reader"}
   :methods {}})

(def tags-for {'I32 I32 'I64 I64 'Text Text 'Bytes Bytes 'Sink Sink 'Reader Reader 'MaybeText MaybeText})

(defn- t [ctx] (:target ctx))

(defn- fmt [tmpl args]
  (reduce (fn [s i] (str/replace s (str "{" i "}") (nth args i ""))) tmpl (range (count args))))

(defn- strip-parens
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

(defn- call [tmpls]
  (fn [ctx form]
    (let [as (mapv (fn [f] (sp/splint-render ctx f)) (rest form))
          code (fmt (get tmpls (t ctx)) as)]
      (if (= :statement (sp/splint-position ctx))
        (sp/splint-emit! ctx (sp/indent-of ctx) code ";\n")
        (sp/splint-emit! ctx code)))))

(def ^:private ops {'+ "+" '- "-" '* "*" '< "<" '> ">" '== "==" 'not "!" 'bit-shift-right ">>"})

(defn- op-form [sym]
  (fn [ctx form]
    (let [as (mapv (fn [f] (sp/splint-render ctx f)) (rest form))]
      (sp/splint-emit! ctx (if (= 1 (count as))
                             (str (get ops sym) (first as))
                             (str "(" (str/join (str " " (get ops sym) " ") as) ")"))))))

(defn- ty-of [ctx tag] (get-in (or (sp/splint-tag ctx tag) I64) [:types (t ctx)]))

(defn- defn-form
  "A function, framed the way each target frames one.

  The signature is where three languages disagree most and it is entirely
  mechanical: a return type before or after, `static` or `fn`, `self` or not."
  [ctx form]
  (let [[_ nm params & body] form
        ret (:tag (meta nm))
        ps (partition 2 (interleave params (map (fn [p] (:tag (meta p))) params)))
        camel (let [[h & r] (str/split (str nm) #"-")]
                (str h (str/join (mapv str/capitalize r))))
        pascal (str/join (mapv str/capitalize (str/split (str nm) #"-")))]
    (case (t ctx)
      ;; RUST RETURNS A RESULT WHERE THE OTHERS THROW, and that is the first
      ;; divergence found in this port that is not naming: it changes the
      ;; signature and every call site. `^:throws` on the name says a function
      ;; can fail; Rust turns the return type into `Result<T, String>` and a
      ;; call to it gets `?`, and Java and C# ignore the mark entirely because
      ;; an exception needs nothing in either place.
      :rust (sp/splint-emit!
             ctx (sp/indent-of ctx) "fn " (str/replace (str nm) "-" "_") "("
             (str/join ", " (mapv (fn [[p tag]] (str p ": " (ty-of ctx tag))) ps))
             ")"
             (cond
               (and ret (:throws (meta nm))) (str " -> Result<" (ty-of ctx ret) ", String>")
               ret (str " -> " (ty-of ctx ret))
               (:throws (meta nm)) " -> Result<(), String>"
               :else "")
             " {\n")
      :java (sp/splint-emit!
             ctx (sp/indent-of ctx) "static " (if ret (ty-of ctx ret) "void") " " camel "("
             (str/join ", " (mapv (fn [[p tag]] (str (ty-of ctx tag) " " p)) ps)) ") {\n")
      :csharp (sp/splint-emit!
               ctx (sp/indent-of ctx) "static " (if ret (ty-of ctx ret) "void") " " pascal "("
               (str/join ", " (mapv (fn [[p tag]] (str (ty-of ctx tag) " " p)) ps)) ") {\n"))
    (sp/splint-scoped ctx {:key :fn :value nm :indent 1}
                      (fn [inner]
                        (sp/splint-scoped inner {:key :throws :value (:throws (meta nm))}
                                          (fn [in2] (doseq [f body]
                                                      (sp/splint-statement! in2 f))))))
    (sp/splint-emit! ctx (sp/indent-of ctx) "}\n")))

(defn- let-form [ctx form]
  (let [[_ bindings & body] form]
    (doseq [[nm init] (partition 2 bindings)]
      (let [ty (ty-of ctx (:tag (meta nm)))
            code (strip-parens (sp/splint-render ctx init))]
        (sp/splint-emit! ctx (sp/indent-of ctx)
                         (case (t ctx)
                           :rust (str "let " nm ": " ty " = " code ";\n")
                           (str ty " " nm " = " code ";\n")))))
    (doseq [f body] (sp/splint-statement! ctx f))))

(defn- defstruct-form
  "A small mutable record, declared the way each target declares one.

  The first form here that is not a function or a statement, and the three
  differ in mechanism rather than meaning: Rust wants a `struct` with a
  lifetime for the borrowed slice, Java and C# want a class with fields. What
  a source says is the FIELDS."
  [ctx form]
  (let [[_ nm fields] form
        fs (mapv (fn [f] [f (:tag (meta f))]) fields)
        pascal (str/join (mapv str/capitalize (str/split (str nm) #"-")))]
    (case (t ctx)
      :rust (do (sp/splint-emit! ctx (sp/indent-of ctx) "struct " pascal " {\n")
                (doseq [[f tag] fs]
                  (sp/splint-emit! ctx (sp/indent-of ctx) "    " f ": "
                                   (ty-of ctx tag) ",\n"))
                (sp/splint-emit! ctx (sp/indent-of ctx) "}\n"))
      :java (do (sp/splint-emit! ctx (sp/indent-of ctx) "static final class " pascal " {\n")
                (doseq [[f tag] fs]
                  (sp/splint-emit! ctx (sp/indent-of ctx) "    " (ty-of ctx tag) " " f ";\n"))
                (sp/splint-emit! ctx (sp/indent-of ctx) "}\n"))
      :csharp (do (sp/splint-emit! ctx (sp/indent-of ctx) "sealed class " pascal " {\n")
                  (doseq [[f tag] fs]
                    (sp/splint-emit! ctx (sp/indent-of ctx) "    internal "
                                     (ty-of ctx tag) " " f ";\n"))
                  (sp/splint-emit! ctx (sp/indent-of ctx) "}\n")))))

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
                     (if (= :rust (t ctx)) (str "if " c " {\n") (str "if (" c ") {\n")))
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

(defn forms-for []
  (merge
   {'defn defn-form 'let let-form 'defstruct defstruct-form
    '. field-form 'set set-form 'if if-form 'return return-form
    'do (fn [ctx form] (doseq [f (rest form)] (sp/splint-statement! ctx f)))}
   (reduce (fn [m s] (assoc m s (op-form s))) {} (keys ops))
   {;; THE BYTE SINK. One byte, and the cast the CLR needs lives here rather
    ;; than in every call.
    'write-byte (call {:rust "{0}.push({1} as u8)"
                       :java "{0}.write({1})"
                       :csharp "{0}.WriteByte((byte) {1})"})
    'write-bytes (call {:rust "{0}.extend_from_slice(&{1})"
                        :java "{0}.write({1}, 0, {1}.length)"
                        :csharp "{0}.Write({1}, 0, {1}.Length)"})
    'byte-count (call {:rust "{0}.len() as u32" :java "{0}.length" :csharp "{0}.Length"})
    ;; An UNSIGNED shift. `>>>` in Java, a cast in C#, and plain `>>` in Rust
    ;; where the type already says unsigned -- three spellings of one idea.
    'ushr (call {:rust "({0} >> {1})" :java "({0} >>> {1})" :csharp "((ulong) {0} >> {1})"})
    'to-i32 (call {:rust "({0} as u32)" :java "((int) {0})" :csharp "((int) {0})"})
    'utf8 (call {:rust "{0}.as_bytes().to_vec()"
                 :java "{0}.getBytes(StandardCharsets.UTF_8)"
                 :csharp "Encoding.UTF8.GetBytes({0})"})
    'u32 (call {:rust "u32({0}, {1})" :java "u32({0}, {1})" :csharp "U32({0}, {1})"})
    ;; THE READER SIDE.
    ;;
    ;; A byte out of a slice, unsigned. Java and C# have signed bytes and need
    ;; the mask; Rust's `u8` does not, which is one idea and three spellings --
    ;; exactly what a vocabulary is for.
    'byte-at (call {:rust "({0}[{1} as usize] as u32)"
                    :java "({0}[{1}] & 0xff)"
                    :csharp "({0}[{1}] & 0xff)"})
    'len (call {:rust "({0}.len() as u32)" :java "{0}.length" :csharp "{0}.Length"})
    'shl (call {:rust "({0} << {1})" :java "({0} << {1})" :csharp "({0} << {1})"})
    'bit-or (call {:rust "({0} | {1})" :java "({0} | {1})" :csharp "({0} | {1})"})
    'to-i64 (call {:rust "({0} as u64)" :java "((long) {0} & 0xffffffffL)"
                   :csharp "((long) {0} & 0xffffffffL)"})
    'refuse (call {:rust "return Err(String::from({0}))"
                   :java "throw new Refused({0})"
                   :csharp "throw new Refused({0})"})
    ;; Calling a function that CAN FAIL. Rust propagates with `?`; the other
    ;; two do nothing, because an exception needs nothing at the call site.
    ;; Same shape as `^:throws` on the declaration -- the mark is in the source
    ;; and only the target that cares reads it.
    'try-u32 (call {:rust "u32({0})?" :java "u32({0})" :csharp "U32({0})"})
    'mask32 (call {:rust "({0} as u64)"
                   :java "((long) {0} & 0xffffffffL)"
                   :csharp "((long) {0} & 0xffffffffL)"})
    'shl64 (call {:rust "({0} << {1})" :java "({0} << {1})" :csharp "({0} << {1})"})
    ;; A CONSTANT CAN DIFFER PER TARGET when the types do. The absent-namespace
    ;; marker is the same 32 bits everywhere and is spelled `u32::MAX` in Rust
    ;; and `-1` in Java and C#, whose ints are signed -- writing `4294967295` in
    ;; the source produced `integer number too large` on two of the three.
    'no-ns (call {:rust "u32::MAX" :java "NO_NS" :csharp "NO_NS"})

    ;; ABSENCE. Rust has a type for it and the other two have null.
    'absent (call {:rust "None" :java "null" :csharp "null"})
    'present (call {:rust "Some({0})" :java "{0}" :csharp "{0}"})
    'utf8-str (call {:rust "String::from_utf8_lossy(&{0}[{1} as usize..({1} + {2}) as usize]).into_owned()"
                     :java "new String({0}, {1}, {2}, StandardCharsets.UTF_8)"
                     :csharp "Encoding.UTF8.GetString({0}, {1}, {2})"})}))
