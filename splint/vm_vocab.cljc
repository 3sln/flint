(ns flint.impl.vm
  "The vocabulary a runtime's interpreter code is written in.

  This is the file that knows there are three languages. Every form is a
  FUNCTION per target, so the things that are not naming -- Rust's temporaries,
  its `mut`, its casts -- live inside the implementation that needs them and
  nowhere else."
  (:require [flint.splint :as sp]
            [clojure.string :as str]))

;; ------------------------------------------------------------------- tags
;;
;; A tag is a VALUE, not a name. It carries the type each target spells it as,
;; and a dispatch table saying what a method call on it becomes -- which is the
;; compile-time protocol: `(invoke stack push x)` asks the tag, not the
;; translator.

(def Usize
  {:name 'Usize
   :types {:rust "usize" :java "int" :csharp "int"}
   :methods {}})

(def Val
  {:name 'Val
   :types {:rust "Value" :java "long" :csharp "long"}
   :methods {'nil? {:rust "{0}.is_nil()"
                    :java "Val.isNil({0})"
                    :csharp "Val.IsNil({0})"}}})

;; --------------------------------------------------------------- helpers

(declare statement-if)

(defn- t [ctx] (:target ctx))

(defn- strip-parens
  "Drop the outer parentheses of a whole expression.

  Operators are parenthesised so precedence is never a question, and at the top
  of an assignment there is nothing to be ambiguous with -- so `x = (a + b);`
  becomes `x = a + b;`, which is what a person writes."
  [c]
  (if (and (str/starts-with? c "(") (str/ends-with? c ")")
           ;; Only when the opening paren matches the closing one, so
           ;; `(a + b) * (c + d)` is left alone.
           (loop [i 1 d 1]
             (cond (>= i (dec (count c))) (= d 1)
                   (= \( (nth c i)) (recur (inc i) (inc d))
                   (= \) (nth c i)) (if (= d 1) false (recur (inc i) (dec d)))
                   :else (recur (inc i) d))))
    (subs c 1 (dec (count c)))
    c))

(defn- fmt [tmpl args]
  (reduce (fn [s i] (str/replace s (str "{" i "}") (nth args i ""))) tmpl (range (count args))))

(defn- render-args [ctx forms] (mapv (fn [f] (sp/splint-render ctx f)) forms))

(defn- assigns?
  "Does `body` assign to `nm`? Rust needs `mut` and the others do not, so this
  is asked HERE rather than written by the author -- who would otherwise have
  to know which target they were writing for."
  [body nm]
  (let [found (atom false)]
    (letfn [(walk [f]
              (when (seq? f)
                (when (and (= 'set (first f)) (= nm (second f))) (reset! found true))
                (doseq [x f] (walk x))))]
      (doseq [f body] (walk f)))
    @found))

(defn- nested-call?
  "Does `form` contain a call INSIDE a call?

  The hoisting rule, and it is narrower than the first version's. Rust's
  two-phase borrows accept `self.seq(self.r(si))` -- one level -- and the
  runtime is full of it. What `rustc` refuses is two:

      self.set_r(si, self.seq(self.r(si)))     E0499

  Hoisting every call argument satisfied the compiler and produced three
  temporaries where a person would write none, which is worse code than the
  hand-written original. So an argument is hoisted only when it is itself a
  call WITH a call inside it."
  [form]
  (and (seq? form) (some seq? (rest form)) true))

(defn- hoist!
  "Bind `code` to a temporary named after the call, and return the name.

  Named rather than numbered because `let seq_1 = ...` reads and `let t2__ =
  ...` does not, and generated code that is harder to read than what it
  replaces is not worth generating."
  [ctx head code]
  (let [n (swap! (:tmp ctx) inc)
        nm (str (str/replace (str head) #"[^A-Za-z0-9]" "_") "_" n)]
    (sp/splint-before! ctx (sp/indent-of ctx) "let " nm " = " code ";\n")
    nm))

(defn- call
  "A runtime call, per target. `hoist?` says Rust must bind the arguments
  first."
  [tmpls]
  (fn [ctx form]
    (let [as (render-args ctx (rest form))
          as (if (= :rust (t ctx))
               (mapv (fn [a f] (if (nested-call? f) (hoist! ctx (first f) a) a))
                     as (rest form))
               as)]
      ;; A CALL READS ITS POSITION. `(set-r si x)` is the same call in
      ;; `(if (nil? x) ...)` and at the top of a body; what differs is that one
      ;; of them needs indentation and a terminator. The form asks rather than
      ;; something downstream guessing from the shape of the string.
      (if (= :statement (sp/splint-position ctx))
        (sp/splint-emit! ctx (sp/indent-of ctx) (fmt (get tmpls (t ctx)) as) ";\n")
        (sp/splint-emit! ctx (fmt (get tmpls (t ctx)) as))))))

;; ------------------------------------------------------------------- forms

(def ^:private ops
  {'+ "+" '- "-" '* "*" '< "<" '> ">" '== "==" 'not "!"})

(defn- op-form [sym]
  (fn [ctx form]
    (let [as (render-args ctx (rest form))]
      (sp/splint-emit! ctx
                       (if (= 1 (count as))
                         (str (get ops sym) (first as))
                         (str "(" (str/join (str " " (get ops sym) " ") as) ")"))))))

(defn- let-form [ctx form]
  (let [[_ bindings & body] form
        pairs (partition 2 bindings)]
    (doseq [[nm init] pairs]
      (let [tag (:tag (meta nm))
            ty (get-in (if (= 'Usize tag) Usize Val) [:types (t ctx)])
            code (strip-parens (sp/splint-render ctx init))]
        (sp/splint-emit!
         ctx (sp/indent-of ctx)
         (case (t ctx)
           ;; `mut` and the type, because Rust needs both: without the type,
           ;; `spread` was inferred `u64` from its first use.
           :rust (str "let " (when (assigns? body nm) "mut ") nm ": " ty " = " code ";\n")
           (str ty " " nm " = " code ";\n")))))
    (doseq [f body] (sp/splint-statement! ctx f))))

(defn- set-form [ctx form]
  (let [[_ place value] form
        p (sp/splint-render ctx place)]
    ;; `x += n` where a person would write it. `spread = (spread + 1)` is
    ;; correct, and it is not what the hand-written code says, and the rule is
    ;; that generated code may not be worse.
    (if (and (seq? value) (= '+ (first value)) (= place (second value))
             (= 3 (count value)))
      (sp/splint-emit! ctx (sp/indent-of ctx) p " += "
                       (sp/splint-render ctx (nth value 2)) ";\n")
      (sp/splint-emit! ctx (sp/indent-of ctx) p " = "
                       (strip-parens (sp/splint-render ctx value)) ";\n"))))

(defn- if-form [ctx form]
  ;; THE SAME `if` IN BOTH POSITIONS, and the three targets differ.
  ;;
  ;; In expression position Rust writes `if c { a } else { b }` -- `if` is an
  ;; expression there and that is what a person writes. Java and C# have no such
  ;; thing and want the conditional operator. In statement position all three
  ;; want braces.
  ;;
  ;; The implementation ASKS. Nothing declares that `if` is a statement, because
  ;; it is not one -- it is whatever the thing enclosing it needed.
  (if (= :expression (sp/splint-position ctx))
    (let [[_ test then else] form
          c (sp/splint-render ctx test)
          a (sp/splint-render ctx then)
          b (sp/splint-render ctx else)]
      (sp/splint-emit! ctx
                       (if (= :rust (t ctx))
                         (str "if " c " { " a " } else { " b " }")
                         (str "(" c " ? " a " : " b ")"))))
    (statement-if ctx form)))

(defn- statement-if [ctx form]
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

(defn- hoists?
  "Would rendering `form` need a temporary on this target?

  Asked so the loop can keep its natural shape when nothing hoists -- see
  `while-form`."
  [ctx form]
  (let [probe (assoc ctx :out (atom []) :scope (assoc (:scope ctx) :splint/stmt-anchor nil)
                     :tmp (atom 0))]
    (sp/splint-render probe form)
    (pos? (deref (:tmp probe)))))

(defn- while-form [ctx form]
  ;; THE TEST GOES INSIDE. A loop test is evaluated every iteration, so it
  ;; cannot be hoisted out -- lifting it produced a temporary bound once,
  ;; before the loop. `while (true) { if (!test) break; ... }` is correct on
  ;; every target and costs a `break` and a constant any compiler folds.
  (let [[_ test & body] form
        neg (if (and (seq? test) (= 'not (first test))) (second test) (list 'not test))]
    ;; THE NATURAL SHAPE WHEN IT FITS. A loop test is evaluated every iteration,
    ;; so a test that needs a temporary cannot keep it in the condition -- but
    ;; one that needs no temporary can, and rewriting it anyway would emit
    ;;
    ;;     while true { if !c { break; } ... }
    ;;
    ;; where a person would have written `while !c { ... }`. That is WORSE than
    ;; the hand-written code, and generated code that is worse than what it
    ;; replaces is not worth generating.
    (if (hoists? ctx test)
      (do (sp/splint-emit! ctx (sp/indent-of ctx)
                           (if (= :rust (t ctx)) "while true {\n" "while (true) {\n"))
          (sp/splint-scoped ctx {:key :in-loop :value true :indent 1}
                            (fn [inner]
                              (sp/splint-statement! inner (list 'if neg '(break)))
                              (doseq [f body] (sp/splint-statement! inner f)))))
      (let [c (sp/splint-render ctx test)]
        (sp/splint-emit! ctx (sp/indent-of ctx)
                         (if (= :rust (t ctx)) (str "while " c " {\n")
                             (str "while (" c ") {\n")))
        (sp/splint-scoped ctx {:key :in-loop :value true :indent 1}
                          (fn [inner] (doseq [f body] (sp/splint-statement! inner f))))))
    (sp/splint-emit! ctx (sp/indent-of ctx) "}\n")))

(defn- invoke-form
  "`(invoke x method args...)` -- the method comes from x's TAG.

  This is the compile-time protocol: the tag carries what the call becomes on
  each target, so a new target is a new entry in the tag rather than a new case
  in the translator."
  [ctx form]
  (let [[_ target-form m & args] form
        tag (or (:tag (meta target-form)) 'Val)
        tag-val (if (= 'Usize tag) Usize Val)
        tmpl (get-in tag-val [:methods m (t ctx)])]
    (when-not tmpl
      (throw (ex-info (str "splint: the tag " (:name tag-val) " has no " m
                           " for " (name (t ctx)))
                      {:tag (:name tag-val) :method m :target (t ctx)})))
    (sp/splint-emit! ctx (fmt tmpl (render-args ctx (cons target-form args))))))

(defn forms-for
  "Every form, for every target. One map because a form's three implementations
  belong beside each other -- that is what makes a divergence visible."
  []
  (merge
   {'let let-form 'set set-form 'if if-form 'while while-form 'invoke invoke-form
    'do (fn [ctx form] (doseq [f (rest form)] (sp/splint-statement! ctx f)))
    'break (fn [ctx _] (sp/splint-emit! ctx (sp/indent-of ctx) "break;\n"))}
   (reduce (fn [m s] (assoc m s (op-form s))) {} (keys ops))
   ;; The runtime's own calls. Three spellings, side by side.
   {'r       (call {:rust "self.r({0})" :java "r({0})" :csharp "R({0})"})
    'set-r   (call {:rust "self.set_r({0}, {1})" :java "setR({0}, {1})" :csharp "SetR({0}, {1})"})
    'vpush   (call {:rust "self.vpush({0})" :java "vpush({0})" :csharp "VPush({0})"})
    'pop-to  (call {:rust "self.pop_to({0})" :java "popTo({0})" :csharp "PopTo({0})"})
    'seq     (call {:rust "self.seq({0})" :java "Seqs.seq(this, {0})" :csharp "Seqs.Seq(this, {0})"})
    'first   (call {:rust "self.first({0})" :java "Seqs.first(this, {0})" :csharp "Seqs.First(this, {0})"})
    'next    (call {:rust "self.next({0})" :java "Seqs.next(this, {0})" :csharp "Seqs.Next(this, {0})"})
    ;; THE CAST LIVES HERE. `charge_tick` takes u64, the caller counts in
    ;; usize, and both other targets use `long` for both.
    'charge  (call {:rust "self.charge_tick({0} as u64, {1}, {2})"
                    :java "chargeTick({0}, {1}, {2})"
                    :csharp "ChargeTick({0}, {1}, {2})"})}))
