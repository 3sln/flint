(ns lang.control
  "Control flow, and the exception model.

  `doc/decisions` records that a flint builtin sets `thrown` rather than
  raising a host exception, so the same `try`/`catch` has to mean the same
  thing whether the throw came from a builtin, from user code, or from the
  runtime itself. That is one mechanism with three entrances and they are
  checked separately."
  (:require [flint.check :refer [expect]]))

(defn ^:flint.check/test truthiness-is-nil-and-false-only []
  ;; Zero is truthy, the empty string is truthy, the empty vector is truthy.
  ;; A runtime that borrowed its host's notion of falsy would fail here and
  ;; nowhere else, because every other check happens to use real booleans.
  (expect true? (if 0 true false))
  (expect true? (if "" true false))
  (expect true? (if [] true false))
  (expect false? (if nil true false))
  (expect false? (if false true false)))

(defn ^:flint.check/test loop-and-recur []
  (expect = 55 (loop [i 1 acc 0] (if (<= i 10) (recur (inc i) (+ acc i)) acc)))
  ;; Deep enough that a `recur` compiled as a CALL would exhaust the stack.
  ;; The answer is incidental; not overflowing is the check.
  (expect = 100000 (loop [i 0] (if (< i 100000) (recur (inc i)) i))))

(defn ^:flint.check/test when-and-cond []
  (expect nil? (when false :x))
  (expect = :x (when true :x))
  (expect = :b (cond false :a true :b :else :c))
  (expect = :c (cond false :a false :b :else :c))
  (expect nil? (cond false :a)))

(defn ^:flint.check/test catch-catches-user-throws []
  (expect = "boom"
          (try (throw (ex-info "boom" {})) (catch Throwable e (ex-message e))))
  (expect = {:k 1}
          (try (throw (ex-info "boom" {:k 1})) (catch Throwable e (ex-data e)))))

(defn ^:flint.check/test catch-catches-runtime-faults []
  ;; A fault raised by the RUNTIME rather than by user code -- the second of
  ;; the three entrances. It has to be an ordinary catchable value, or a
  ;; program cannot defend itself against its own bugs.
  (expect some?
          (try (nth [1 2] 99) nil (catch Throwable e (ex-message e)))))

(defn ^:flint.check/test calling-a-non-function-unwinds-where-it-happened []
  ;; A CALL POSITION fault is the third entrance, and both ports let it walk
  ;; past its own `try`. `callValue` sets `thrown` and answers nil exactly as
  ;; a builtin does, and the interpreter checked only whether it had PARKED --
  ;; so execution continued with the nil, this `try` saw nothing, and the
  ;; pending throw aborted an unrelated expression two operations later.
  ;;
  ;; Measured before the fix: `caught` was `:no-throw` and a later
  ;; `(count [1 2 3])` raised instead.
  (let [not-a-fn (first {:a 1})
        caught (try (not-a-fn 0) :no-throw (catch Throwable e (ex-message e)))
        after  (try (+ 1 2) (catch Throwable e :LEAKED))
        after2 (try (count [1 2 3]) (catch Throwable e :LEAKED))]
    (expect some? caught)
    (expect not= :no-throw caught)
    (expect = 3 after)
    (expect = 3 after2)))

(defn- caught? [f]
  ;; The value is irrelevant; what matters is that the `try` HERE saw it.
  (try (f) :ESCAPED (catch Throwable e (if (ex-message e) :caught :caught-blank))))

(defn- leaked? []
  ;; Anything at all. If a previous failure did not unwind, its pending throw
  ;; lands on whatever runs next -- which is how the call-position fault was
  ;; found, two operations downstream of the `try` that should have had it.
  (try (count [1 2 3]) (catch Throwable e :LEAKED)))

(defn ^:flint.check/test every-throwing-path-unwinds-where-it-happens []
  ;; ONE PROPERTY, over every entrance a fault has: the `try` around it
  ;; catches it, and nothing downstream is disturbed.
  ;;
  ;; `callValue` failed both halves on both ports -- it set `thrown`, the
  ;; interpreter checked only for a PARK, and the throw surfaced two
  ;; expressions later inside an unrelated `try`. The NATIVE opcode path had
  ;; the check the whole time. This asks the question of every path rather
  ;; than the one that happened to be looked at.
  (let [not-a-fn (first {:a 1})
        tail-call (fn [f] (f 0))]
    (doseq [f [;; CALL of a value that is not a function
               (fn [] (not-a-fn 0))
               ;; APPLY of one
               (fn [] (apply not-a-fn [0]))
               ;; TAIL position
               (fn [] (tail-call not-a-fn))
               ;; a builtin that refuses
               (fn [] (/ 1 0))
               (fn [] (quot 1 0))
               (fn [] (nth [1] 5))
               (fn [] (assoc 5 :k 1))
               (fn [] (count 5))
               (fn [] (compare :k [1]))
               ;; the arithmetic slow path
               (fn [] (+ 1 "a"))
               ;; and a user throw, which is the entrance that always worked
               (fn [] (throw (ex-info "boom" {})))]]
      (expect not= :ESCAPED (caught? f))
      (expect = 3 (leaked?)))))

(defn ^:flint.check/test finally-runs-on-both-paths []
  (let [normal (let [a (atom [])]
                 (try (swap! a conj :body) (finally (swap! a conj :finally)))
                 @a)
        thrown (let [a (atom [])]
                 (try (try (throw (ex-info "x" {}))
                           (finally (swap! a conj :finally)))
                      (catch Throwable _ nil))
                 @a)]
    (expect = [:body :finally] normal)
    (expect = [:finally] thrown)))

(defn ^:flint.check/test a-catch-returns-a-value []
  ;; `try` is an EXPRESSION, so both arms have to produce one -- an early
  ;; implementation returned nil from the catch arm regardless of its body.
  (expect = 1 (try 1 (catch Throwable _ 2)))
  (expect = 2 (try (throw (ex-info "x" {})) (catch Throwable _ 2))))
