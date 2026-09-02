(ns splint
  "A spike: write a runtime's shared logic once, emit it for every host.

  ## The problem this is aimed at

  flint has four runtimes that are meant to be verbatim mirrors, and keeping
  them so is done BY HAND. Porting one interpreter opcode -- `APPLY`, while
  fixing a park bug -- meant writing the same thirty lines three times in Rust,
  Java and C#. The three differed in naming and punctuation and in nothing else.

  ## The bet

  Per-target knowledge is DATA, and the translator knows only control flow. If
  that split holds, the shared parts of three runtimes can be written once.

  `splint/rules/<target>.edn` says how a call, a field, a type and a statement
  render. This file knows `let`, `set`, `if`, `while`, `do`, `return`,
  `break`, `continue`, operators, and nothing about any language.

  ## What it deliberately does NOT try to do

  The divergent parts. A GC write barrier, a wasm ABI shim, the `unsafe` in the
  Rust heap -- those are different by nature and pretending otherwise would
  produce three things that are each wrong. The claim is only about the 1:1
  logic, which is most of the opcode bodies."
  (:require [clojure.string :as str]
            [clojure.edn :as edn]))

(defn- fmt
  "`\"self.{0}({1})\"` with arguments substituted."
  [tmpl args]
  (reduce (fn [s i]
            (str/replace s (str "{" i "}") (nth args i "")))
          tmpl
          (range (count args))))

(defn- named-fmt
  "`\"{type} {name} = {init};\"` with a map substituted."
  [tmpl m]
  (reduce (fn [s e] (str/replace s (str "{" (name (key e)) "}") (str (val e))))
          tmpl m))

(defn- pad [n] (apply str (repeat n " ")))

(declare expr)

;; Temporaries hoisted out of the expression currently being built.
;;
;; RUST NEEDS THIS AND THE OTHERS DO NOT, which is the one place the spike found
;; that is not a naming difference. `self.set_r(si, self.seq(self.r(si)))` is
;; two mutable borrows of `self` and the borrow checker refuses it:
;;
;;     error[E0499]: cannot borrow `*self` as mutable more than once at a time
;;
;; Java and C# accept the same shape happily. So a target may ask for call
;; arguments that are THEMSELVES calls to be lifted into locals first -- which
;; is exactly what the hand-written Rust does, and now nobody has to remember
;; to do it.
(def ^:private hoisted (atom []))
;; NEVER RESET WITHIN A SNIPPET. Per-statement numbering produced two locals
;; called `t0__` in one scope -- which Rust shadows silently and Java and C#
;; reject, so the bug would have shown up on two targets and not the third.
(def ^:private tmp-n (atom 0))

(defn- hoist!
  "Bind `code` to a fresh local and return the local's name."
  [rules code]
  (let [nm (str "t" (swap! tmp-n inc) "__")]
    (swap! hoisted conj (named-fmt (:tmp rules) {:name nm :init code}))
    nm))

(defn- call? [x] (and (seq? x) (symbol? (first x))))

(defn- args*
  [rules xs]
  (mapv (fn [x]
          (let [code (expr rules x)]
            (if (and (:hoist-call-args rules) (call? x))
              (hoist! rules code)
              code)))
        xs))

(defn- expr
  "One EXPRESSION, as target source."
  [rules form]
  (cond
    (symbol? form) (str form)
    (string? form) (pr-str form)
    (number? form) (str form)
    (true? form) (:true rules)
    (false? form) (:false rules)
    (nil? form) (:nil rules)

    (seq? form)
    (let [head (keyword (name (first form)))
          as (args* rules (rest form))]
      (cond
        ;; An operator: infix, and parenthesised so precedence is never a
        ;; question. Ugly output beats output that is wrong once.
        (get (:ops rules) head)
        (if (= 1 (count as))
          (str (get (:ops rules) head) (first as))
          (str "(" (str/join (str " " (get (:ops rules) head) " ") as) ")"))

        ;; A named call, from the rules.
        (get (:calls rules) head)
        (fmt (get (:calls rules) head) as)

        ;; A field.
        (= head :field)
        (get (:fields rules) (keyword (str/replace (str (first (rest form))) ":" "")))

        :else
        (throw (ex-info (str "splint: no rule for " head " on " (:name rules))
                        {:form form :target (:name rules)}))))
    :else (str form)))

(declare stmt)

(defn- with-hoists
  "Render one statement, emitting any temporaries it needed FIRST.

  The order is the whole point: a temporary has to be bound before the
  statement that reads it, and a statement that hoists nothing pays nothing."
  [rules f indent]
  ;; SAVE AND RESTORE, not reset. A nested block -- an `if`'s then-branch, a
  ;; `do` -- renders through here too, and resetting threw away the temporaries
  ;; the ENCLOSING statement had already collected. The symptom was a generated
  ;; `if !t6__` with no `let t6__` above it, which is the kind of thing that
  ;; compiles on no target and is invisible until you run the compiler.
  (let [outer @hoisted]
    (reset! hoisted [])
    (let [body (stmt rules f indent)
          pre @hoisted]
      (reset! hoisted outer)
      (if (seq pre)
        (str (str/join "\n" (mapv (fn [h] (str (pad indent) h)) pre)) "\n" body)
        body))))

(defn- block [rules forms indent]
  (str/join "\n" (mapv (fn [f] (with-hoists rules f indent)) forms)))


(defn- stmt
  "One STATEMENT, as target source, indented by `indent` spaces."
  [rules form indent]
  (let [i (pad indent)]
    (if-not (seq? form)
      (str i (expr rules form) (:end rules))
      (let [head (keyword (name (first form)))]
        (case head
          :let
          (let [[_ bindings & body] form
                pairs (partition 2 bindings)
                ;; MUTABILITY IS INFERRED, not declared. Rust needs `mut` and
                ;; refuses a second assignment without it; Java and C# need
                ;; nothing. Asking the author to write it would be asking them
                ;; to know which target they are writing for, which is the whole
                ;; thing this is trying to avoid.
                assigned (let [found (atom #{})]
                           (letfn [(walk [f]
                                     (when (seq? f)
                                       (when (and (= 'set (first f)) (symbol? (second f)))
                                         (swap! found conj (second f)))
                                       (doseq [x f] (walk x))))]
                             (doseq [f body] (walk f)))
                           @found)]
            (str (str/join "\n"
                           (mapv (fn [[nm init]]
                                   (let [tag (:tag (meta nm))
                                         ty (get (:types rules)
                                                 (keyword (str (or tag "val"))))
                                         f (if (contains? assigned nm)
                                             (get-in rules [:let :mut])
                                             (get-in rules [:let :fmt]))]
                                     (str i (named-fmt f
                                                       {:type ty :name (str nm)
                                                        :init (expr rules init)}))))
                                 pairs))
                 "\n" (block rules body indent)))

          :set
          (let [[_ place value] form]
            (str i (named-fmt (get-in rules [:set :fmt])
                              {:place (expr rules place) :value (expr rules value)})))

          :if
          (let [[_ test then else] form]
            (str i (named-fmt (get-in rules [:if :open]) {:test (expr rules test)}) "\n"
                 (stmt rules then (+ indent 4)) "\n"
                 (if else
                   (str i (get-in rules [:if :else]) "\n"
                        (stmt rules else (+ indent 4)) "\n" i (get-in rules [:if :close]))
                   (str i (get-in rules [:if :close])))))

          ;; A LOOP TEST IS EVALUATED EVERY ITERATION, so it cannot be
          ;; hoisted out the way an `if` test can.
          ;;
          ;; The first version lifted it and produced `while !t1__ {` with the
          ;; temporary bound once, before the loop -- an infinite loop, or a
          ;; loop that never runs. So the test moves INSIDE:
          ;;
          ;;     while (true) { <hoists> if (!test) break; <body> }
          ;;
          ;; which is correct on every target and needs no target-specific rule.
          ;; The cost is one `break` and a `true` that any compiler folds.
          :while
          (let [[_ test & body] form
                inner (+ indent 4)]
            (str i (named-fmt (get-in rules [:while :open]) {:test (:true rules)}) "\n"
                 (with-hoists rules
                   (list 'if (if (and (seq? test) (= 'not (first test)))
                               (second test)
                               (list 'not test))
                         '(break))
                   inner) "\n"
                 (block rules body inner) "\n"
                 i (get-in rules [:while :close])))

          :do (block rules (rest form) indent)
          :return (str i (named-fmt (get-in rules [:return :fmt])
                                    {:value (expr rules (second form))}))
          :break (str i (:break rules))
          :continue (str i (:continue rules))
          ;; Anything else is an expression used for effect.
          (str i (expr rules form) (:end rules)))))))

(defn render
  "A `defsnippet` form, as target source."
  [rules form]
  (reset! tmp-n 0)
  (let [[_ nm doc params & body] form]
    (str "// " (str/replace (str doc) "\n" "\n// ") "\n"
         "// GENERATED by splint from the shared source. Do not edit here.\n"
         (block rules body 0))))

(defn translate
  "`source` and a rules map -> target source."
  [source rules]
  (let [forms (read-string (str "[" source "]"))]
    (str/join "\n\n" (mapv (fn [f] (render rules f)) forms))))
