(ns flint.check
  "Checks that cost nothing in a release build.

  Two things at once, and they are the same thing seen from two sides:

  * **Inline checks** against bad usage, so a library says what went wrong
    where it went wrong rather than failing three frames later with a message
    about a type nobody passed.
  * **Unit checks**, collected by `flint test`, written in the namespace they
    are about rather than in a parallel tree.

  Both live inside `#?(:flint/check ...)`, which is on by default and removed
  by `:optimize [perf]`. That is deliberate and it is the whole bargain: a
  check nobody turns on is a check nobody has, and a check that survives into
  production is a tax on every call. So the developer's build is the default
  and the release build is the exception -- and because the branch is REMOVED
  BY THE READER, what is inside it costs no image bytes, no constants and no
  work for the shaker. It is not compiled to nothing; it is never compiled.

  ## The predicate

  `expect` takes a predicate and the value to test. A predicate is either an
  ordinary function -- `string?`, `pos?`, anything -- or a value implementing
  `Predicate`, which is what lets a predicate be COMPUTED:

      (expect (http-status 200) response)
      (expect (min-length 3) name)

  Those return values that know both how to test and how to explain
  themselves. An ordinary function cannot: flint drops metadata on a closure,
  so a function has nowhere to carry an implementation. That constraint is the
  reason the protocol is a protocol over VALUES rather than a registry keyed on
  functions.

  ## Why two hooks

  `check` answers, `explain` describes -- and `explain` runs ONLY when `check`
  said no. The happy path allocates nothing, which is what makes it reasonable
  to put these inside the standard library's hot paths. The cost is that a
  failing predicate is evaluated twice; every flint value is immutable, so the
  second look sees exactly what the first did.

  ## No requires

  Deliberately. This namespace is compiled into every developer build, and it
  is ordered before everything but `clojure.core` -- so anything it required
  would have to be ordered before it too, and the pin that puts it early would
  put it ahead of its own dependency. It joins strings with `flint.rt/str-join`
  rather than `clojure.string` for that reason and no other."
  (:require))

;; --------------------------------------------------------------- protocol

(defprotocol Predicate
  "How a value tests and describes itself.

  `check` must be cheap and allocation-free on success -- it is called on every
  checked call. `explain` is called only after `check` has already said no, so
  it may do as much work as a good message needs.

  Both take the arguments as a VECTOR, because `expect` is variadic: `(expect =
  a b)` and `(expect between? x lo hi)` are ordinary uses, and a protocol whose
  hooks took a single value could not express them."
  (check [p args] "Truthy if `args` pass.")
  (explain [p args]
    "Why `args` failed, as data: `{:expected .. :actual .. :note ..}`. Any key
    may be absent; `expect` renders what it is given."))

;; AN ORDINARY FUNCTION IS A PREDICATE, and this is the whole of that case: it
;; is applied, and that is all a bare function can offer. It is also the case
;; that has to work, because a predicate is a VALUE and may arrive from
;; anywhere -- `(let [p =] (expect p 1 2))` has nothing for a macro to
;; recognise, so nothing may depend on recognising it.
;;
;; A function CAN do better than this default, because a closure carries
;; metadata: attach `:flint.check/explain` and dispatch finds it before it
;; falls back to kind. That is why `with-meta` on a function had to start
;; working before any of this was worth writing.
(extend-protocol Predicate
  :fn (check [f args] (apply f args)))

;; ------------------------------------------------------------- rendering

(defn- join-lines [xs]
  (flint.rt/str-join (vec (interpose "\n" xs))))

(defn- render-value
  "A value as it should appear in a failure. Long ones are cut: an error that
  scrolls off the screen is an error nobody reads."
  [v]
  (let [s (pr-str v)]
    (if (> (count s) 200) (str (subs s 0 200) " ...") s)))

(defn describe
  "The explanation for `p` failing on `args`, as a map.

  A predicate that does not implement `explain` gets one built from what
  `expect` saw in the SOURCE -- which is why the macro carries the predicate
  expression through. `string?` cannot describe itself, but `(expect string? x)`
  can still say `expected  string?`."
  [p args src]
  (let [f (find-protocol-method (:impls Predicate) :flint.check/explain p)]
    (or (when f (f p args)) {:expected src})))

(defn- caret
  "A pointer under the offending expression.

  The source line is not embedded -- the FORM is, and it prints back. So this
  renders what was written rather than quoting it verbatim: whitespace and
  comments are gone, and the column is the one the reader recorded for the
  sub-form, which is exact."
  [col n]
  (str (flint.rt/str-join (vec (repeat col " "))) (flint.rt/str-join (vec (repeat (max n 1) "^")))))

(defn failure-message
  "A check failure, rendered.

  Everything here came from `&form` at macro-expansion: the file, the line, the
  column of the PREDICATE and of the ARGUMENT separately, and both expressions
  as source. No source text is embedded in the image and none is read back at
  run time."
  [{:keys [file line column in pred-src arg-src pred-col arg-col]} explanation args]
  (let [{:keys [expected note]} explanation
        head (str "check failed" (when in (str " in " in)))
        where (str "  " file ":" line (when column (str ":" column)))
        form (str "  " pred-src " " arg-src)
        under (when (and pred-col arg-col)
                (caret (+ 2 (- arg-col pred-col)) (count arg-src)))]
    (join-lines
     (remove nil?
             [head
              where
              ""
              form
              under
              ""
              (str "  expected  " (or expected pred-src))
              (str "    actual  " (flint.rt/str-join (vec (interpose "  " (map render-value args)))))
              (when note (str "            " note))]))))

;; ------------------------------------------------------------------ expect

(defmacro expect
  "Check `args` against `pred`, or throw with a message that says where.

  Only ever written inside `#?(:flint/check ...)`, so in a release build this
  form does not exist -- the reader removed it before the analyzer saw it.

      #?(:flint/check (expect string? name))
      #?(:flint/check (expect = expected actual))
      #?(:flint/check (expect (min-length 3) name))

  VARIADIC, because a predicate of one argument is a special case and not the
  general one. The protocol takes a vector of arguments for the same reason.

  The message is built from `&form`: flint's reader records `:file`, `:line`
  and `:column` on every form AND on every sub-form, so the predicate and each
  argument know where they were written. Nothing is embedded but the
  expressions themselves -- there is no copy of the source line in the image,
  and none is read back at run time."
  [pred & args]
  (let [fm (meta &form)
        ;; `:child-pos` is `[line col line col ...]`, one pair per element of
        ;; the form -- so element 0 is `expect` itself, 1 is the predicate and
        ;; 2 is the first argument.
        ;;
        ;; It exists because a NUMBER cannot carry metadata, here or in
        ;; Clojure, so `(expect string? 42)` has nowhere on the 42 to record
        ;; where the 42 is. The collection records it instead, which is the
        ;; difference between pointing at the offending value and not.
        cp (:child-pos fm)
        col-of (fn [i v]
                 (or (:column (meta v))
                     (when (and cp (> (count cp) (inc (* 2 i)))) (nth cp (inc (* 2 i))))))
        site {:file (:file fm)
              :line (:line fm)
              :column (:column fm)
              :in (str (:ns &env))
              :pred-src (pr-str pred)
              :arg-src (flint.rt/str-join (vec (interpose " " (map pr-str args))))
              :pred-col (col-of 1 pred)
              :arg-col (col-of 2 (first args))}]
    (list 'clojure.core/let ['p# pred 'a# (vec args)]
          (list 'if (list 'flint.check/check 'p# 'a#)
                (list 'clojure.core/first 'a#)
                (list 'throw
                      (list 'clojure.core/ex-info
                            (list 'flint.check/failure-message
                                  site
                                  (list 'flint.check/describe 'p# 'a# (:pred-src site))
                                  'a#)
                            {:flint.check/site site}))))))

;; ------------------------------------------------------------------ tests

(defn test-var?
  "Is this var one of the grouped checks `flint test` runs?

  The compiler indexes EVERY var's metadata, not just this key -- see
  `flint.compiler`. This is one client of that index; a doc generator or a
  lint pass would be another asking the same map a different question."
  [m]
  (boolean (:flint.check/test m)))

(defn run-tests
  "Run `tests` (`[{:var .. :fn ..}]`) and report.

  Takes the registry as an ARGUMENT rather than naming it, because this
  namespace is analysed before any namespace that could have a test in it --
  the registry is generated last, and hands itself here.

  A test passes by returning; it fails by throwing, which is what `expect`
  does. There is no assertion count and no framework: a check that has to be
  registered with something is a check that can be forgotten to register."
  [tests]
  (loop [ts (seq tests) passed 0 failed 0 out []]
    (if (nil? ts)
      (let [total (+ passed failed)]
        ;; `remove nil?`, because `when` answers nil and `str-join` wants
        ;; strings -- which it says plainly, three frames from the `when`.
        (flint.rt/str-join
         (vec (remove nil?
                      (concat out
                              [(str "\n" passed "/" total " checks passed")
                               (when (pos? failed) (str ", " failed " FAILED"))
                               "\n"])))))
      (let [t (first ts)
            r (try ((:fn t)) nil (catch Throwable e (or (ex-message e) "threw")))]
        (if (nil? r)
          (recur (next ts) (inc passed) failed (conj out (str "  ok   " (:var t) "\n")))
          (recur (next ts) passed (inc failed)
                 (conj out (str "  FAIL " (:var t) "\n" r "\n"))))))))
