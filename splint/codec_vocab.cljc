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

(def tags-for {'I32 I32 'I64 I64 'Text Text 'Bytes Bytes 'Sink Sink})

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
      :rust (sp/splint-emit!
             ctx (sp/indent-of ctx) "fn " (str/replace (str nm) "-" "_") "("
             (str/join ", " (mapv (fn [[p tag]] (str p ": " (ty-of ctx tag))) ps))
             ")" (if ret (str " -> " (ty-of ctx ret)) "") " {\n")
      :java (sp/splint-emit!
             ctx (sp/indent-of ctx) "static " (if ret (ty-of ctx ret) "void") " " camel "("
             (str/join ", " (mapv (fn [[p tag]] (str (ty-of ctx tag) " " p)) ps)) ") {\n")
      :csharp (sp/splint-emit!
               ctx (sp/indent-of ctx) "static " (if ret (ty-of ctx ret) "void") " " pascal "("
               (str/join ", " (mapv (fn [[p tag]] (str (ty-of ctx tag) " " p)) ps)) ") {\n"))
    (sp/splint-scoped ctx {:key :fn :value nm :indent 1}
                      (fn [inner] (doseq [f body] (sp/splint-statement! inner f))))
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

(defn forms-for []
  (merge
   {'defn defn-form 'let let-form
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
    'u32 (call {:rust "u32({0}, {1})" :java "u32({0}, {1})" :csharp "U32({0}, {1})"})}))
