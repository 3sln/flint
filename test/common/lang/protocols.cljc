(ns lang.protocols
  "Protocol dispatch, and the order it resolves in.

  `find-protocol-method` asks a value's METADATA first and its `flint.rt/kind`
  second. That order is the whole design -- it is what lets an ordinary vector
  be given an implementation without a new type, and what makes
  `flint.check`'s `Predicate` work on a function held in a local. A runtime
  that checked kind first would still pass every single-implementation test."
  (:require [flint.check :refer [expect]]))

(defprotocol Greet
  (greet [x] "A greeting, however this value gives one."))

(extend-protocol Greet
  :string (greet [s] (str "hello " s))
  :number (greet [n] (str "hello #" n))
  :vector (greet [v] (str "hello " (count v) " things")))

(defn ^:flint.check/test dispatch-on-kind []
  (expect = "hello world" (greet "world"))
  (expect = "hello #7" (greet 7))
  (expect = "hello 2 things" (greet [:a :b])))

(defn ^:flint.check/test kind-is-a-closed-set []
  ;; `kind` is what dispatch runs on (`doc/decisions/0005`), so it has to be a
  ;; SMALL CLOSED SET rather than a type name -- three string tiers and eight
  ;; seq representations all have to answer with one keyword each, or
  ;; `extend-protocol :string` would work for some strings and not others
  ;; depending on how they were built.
  (expect = :nil (flint.rt/kind nil))
  (expect = :boolean (flint.rt/kind true))
  (expect = :number (flint.rt/kind 1))
  (expect = :number (flint.rt/kind 1.5))
  (expect = :string (flint.rt/kind "a"))
  ;; A short string is inline in the value, a long one is on the heap, and a
  ;; concatenated one is a rope. One keyword for all three.
  (expect = :string (flint.rt/kind "a string long enough to be on the heap"))
  (expect = :string (flint.rt/kind (str "a" "b")))
  (expect = :keyword (flint.rt/kind :k))
  (expect = :symbol (flint.rt/kind 'sym))
  (expect = :vector (flint.rt/kind [1]))
  (expect = :map (flint.rt/kind {:a 1}))
  (expect = :set (flint.rt/kind #{1}))
  (expect = :list (flint.rt/kind '(1)))
  ;; A vector's seq, a lazy seq and a range are three representations and one
  ;; kind.
  (expect = :list (flint.rt/kind (seq [1])))
  (expect = :list (flint.rt/kind (map inc [1])))
  (expect = :list (flint.rt/kind (range 3)))
  (expect = :fn (flint.rt/kind (fn [] nil)))
  (expect = :atom (flint.rt/kind (atom 1)))
  ;; `:tagged`, not `:map` (`doc/decisions/0034`). A tagged literal READS like
  ;; a two-key map, and if it answered `:map` here every `extend-protocol :map`
  ;; in every program would silently start catching them.
  (expect = :tagged (flint.rt/kind #a/b [1])))

(defn ^:flint.check/test metadata-beats-kind []
  ;; The same vector, one of them carrying its own implementation. Kind
  ;; dispatch would answer "hello 2 things" for both.
  ;;
  ;; The key is the method's FULLY-QUALIFIED keyword, not its bare name --
  ;; otherwise two protocols with a `greet` would collide on any value that
  ;; carried one. `protocol-miss` says which keyword it wanted, so a wrong
  ;; guess here is a message rather than a puzzle.
  (let [plain [:a :b]
        special (with-meta [:a :b] {::greet (fn [_] "hello, specifically")})]
    (expect = "hello 2 things" (greet plain))
    (expect = "hello, specifically" (greet special))
    ;; And the metadata does not leak into equality: two values that are `=`
    ;; may still dispatch differently, which is the point of carrying it.
    (expect = plain special)))

(defn ^:flint.check/test metadata-on-a-closure []
  ;; A closure carries metadata in the slot AFTER its upvalues, which is what
  ;; makes a predicate held in a local able to explain itself. `with-meta` on a
  ;; function silently returned it unchanged before that slot existed.
  (let [f (fn [x] (* x 2))
        g (with-meta f {:note "doubles"})]
    (expect = 6 (g 3))
    (expect = {:note "doubles"} (meta g))
    (expect nil? (meta f))))

(defn ^:flint.check/test a-miss-names-what-is-missing []
  ;; Adding an implementation for one kind must not make an unrelated kind
  ;; start answering -- and the refusal has to say what would fix it, which is
  ;; the difference between this and a null pointer three frames later.
  (let [msg (try (greet :a-keyword) nil (catch Throwable e (ex-message e)))]
    (expect some? msg)
    (expect true? (clojure.string/includes? msg "lang.protocols/greet"))
    (expect true? (clojure.string/includes? msg ":keyword"))))

(defn ^:flint.check/test satisfies-agrees-with-dispatch []
  (expect true? (satisfies? Greet "s"))
  (expect true? (satisfies? Greet [1]))
  (expect false? (satisfies? Greet :k))
  (expect true? (satisfies? Greet (with-meta [1] {::greet (fn [_] "x")})))
  ;; And on a kind with no implementation, metadata is what makes it satisfy --
  ;; the same rule dispatch uses, asked ahead of time.
  (expect true? (satisfies? Greet (with-meta {} {::greet (fn [_] "x")}))))
