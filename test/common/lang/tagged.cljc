(ns lang.tagged
  "Tagged literals, which are a VALUE rather than a two-key map.

  `doc/decisions/0034`. The map representation was ambiguous with an ordinary
  map in every format that has tags -- a codec meeting one could not tell a
  tagged literal from a map that happened to have those keys -- and it lost the
  namespace wherever the key had to become a string. Both of those are
  serialisation problems, so most of what is asserted here is about being
  DISTINGUISHABLE, not about being convenient."
  (:require [flint.check :refer [expect]]))

(defn ^:flint.check/test reads-as-a-value []
  (expect tagged-literal? #my.ns/thing [1 2])
  (expect = :tagged (flint.rt/kind #my.ns/thing [1 2]))
  ;; Constructed and read produce the same thing, so a literal is not special.
  (expect = #my.ns/thing [1 2] (tagged-literal 'my.ns/thing [1 2])))

(defn ^:flint.check/test it-reads-like-a-two-key-map []
  ;; The point of the type is to be distinguishable, NOT to be awkward: code
  ;; that treats one as a map keeps working.
  (let [t #my.ns/thing [1 2]]
    (expect = 'my.ns/thing (tag t))
    (expect = [1 2] (form t))
    (expect = 'my.ns/thing (get t :tag))
    (expect = [1 2] (get t :form))
    ;; `(:tag t)` and `(get t :tag)` are the same lookup by two spellings, and
    ;; they disagreed at first: the keyword-as-function path fell through to
    ;; the default for anything that was not a map or a set.
    (expect = 'my.ns/thing (:tag t))
    (expect = [1 2] (:form t))
    (expect = 2 (count t))
    (expect nil? (:nope t))))

(defn ^:flint.check/test it-is-not-a-map []
  ;; Which is the whole reason it exists. A codec has to be able to tell a
  ;; tagged literal from a map, in both directions.
  (let [t #my.ns/thing [1 2]]
    (expect false? (map? t))
    (expect false? (= t {:tag 'my.ns/thing :form [1 2]}))
    (expect false? (= {:tag 'my.ns/thing :form [1 2]} t))))

(defn ^:flint.check/test equality-is-structural []
  (expect = #a/b [1] #a/b [1])
  (expect false? (= #a/b [1] #a/b [2]))
  (expect false? (= #a/b [1] #a/c [1]))
  ;; Equal values must hash together or a map keyed by one loses it.
  (expect = (hash #a/b [1]) (hash #a/b [1]))
  (expect = 1 (count {#a/b [1] :x, #a/b [1] :y})))

(defn ^:flint.check/test the-namespace-survives []
  ;; The other half of why a map was not good enough: through a map whose key
  ;; must become a string, `my.ns` is flattened or dropped and the tag stops
  ;; identifying what it identified.
  (expect = "my.ns" (namespace (tag #my.ns/thing 1)))
  (expect = "thing" (name (tag #my.ns/thing 1))))

(defn ^:flint.check/test it-prints-readably []
  (expect = "#my.ns/thing [1 2]" (pr-str #my.ns/thing [1 2]))
  (expect = "#a/b 1" (pr-str #a/b 1))
  ;; Nested, because the form is printed by the same walk.
  (expect = "#a/b {:k [1 2]}" (pr-str #a/b {:k [1 2]})))

(defn ^:flint.check/test assoc-keeps-the-type-or-refuses []
  (let [t #a/b [1]]
    (expect = #a/c [1] (assoc t :tag 'a/c))
    (expect = #a/b [9] (assoc t :form [9]))
    (expect tagged-literal? (assoc t :form [9]))
    ;; Two slots, nowhere for a third. Refusing is the alternative to silently
    ;; promoting to a map and losing the taggedness, and the message NAMES the
    ;; key rather than saying "wrong key".
    (let [msg (try (assoc t :other 1) nil (catch Throwable e (ex-message e)))]
      (expect some? msg)
      (expect true? (clojure.string/includes? msg ":other"))
      (expect true? (clojure.string/includes? msg ":tag")))
    ;; A tag that is not a symbol is not a tag.
    (expect some? (try (assoc t :tag "a/c") nil (catch Throwable e (ex-message e))))))

(defn ^:flint.check/test it-carries-any-form []
  (expect = 1 (form #a/b 1))
  (expect nil? (form #a/b nil))
  (expect = {:x 1} (form #a/b {:x 1}))
  (expect = [#a/b 1] (form #a/c [#a/b 1]))
  (expect tagged-literal? (form #a/c #a/b 1)))
