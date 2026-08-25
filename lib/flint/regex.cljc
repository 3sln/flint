(ns flint.regex
  "Regular expressions: a shared NFA compiler and a Pike VM
  (`doc/decisions/0012`).

  The parser and the NFA compiler are cljc and shared, so every host executes
  the same compiled program and there is no per-host dialect to drift. The
  simulator is native, makes ONE left-to-right pass and never rewinds -- which is
  what lets the subject be a rope (`doc/decisions/0011`) and what makes
  `(a+)+b` linear rather than exponential.

  `flint.pike` is the same simulator in cljc: the conformance oracle, and what a
  new host runs before it has its own.

  It is here rather than in Rust for the reason in doc/decisions/0002: written
  in the language it tree-shakes per var, so a program with no regex literal
  carries no regex engine at all. It is also the thing a text-processing
  language should be able to express about itself.

  ## Supported

  literals, `.`, character classes `[...]` with ranges and negation, the escapes
  d D w W s S b B n r t f (each preceded by a backslash), anchors `^` and `$`,
  groups `(...)`,
  non-capturing groups `(?: )`, alternation `|`, and the quantifiers
  `* + ? {n} {n,} {n,m}` in both greedy and lazy (`*?`) forms.

  ## Not supported

  Lookahead and lookbehind, backreferences, named groups, inline flags,
  possessive quantifiers, unicode property classes. A pattern using any of them
  throws when compiled rather than matching something subtly different.
  `.` matches any character INCLUDING newline, where Java's default does not."
  (:require [flint.rt] [clojure.string] [flint.nfa :as nfa]))

;; ------------------------------------------------------------------- parsing

(defn- perr [msg src i] (throw (ex-info (str "regex: " msg) {:pattern src :at i :type :regex})))

(defn- ch [s i] (when (< i (count s)) (flint.rt/nth s i)))
(defn- cp [c] (flint.rt/code-point-at c 0))

(def ^:private class-escapes #{"d" "D" "w" "W" "s" "S"})

(defn- lit-escape [c]
  (cond (= c "n") "\n" (= c "t") "\t" (= c "r") "\r" (= c "f") "\f" :else c))

(declare parse-alt)

(defn- parse-class [s i src]
  (let [neg? (= "^" (ch s i))
        i (if neg? (inc i) i)]
    (loop [i i items [] first? true]
      (let [c (ch s i)]
        (cond
          (nil? c) (perr "unterminated character class" src i)
          (and (= c "]") (not first?)) [[:class neg? items] (inc i)]
          (= c "\\")
          (let [e (ch s (inc i))]
            (if (class-escapes e)
              (recur (+ i 2) (conj items [:pred e]) false)
              (recur (+ i 2) (conj items [:one (lit-escape e)]) false)))
          (and (= "-" (ch s (inc i))) (some? (ch s (+ i 2))) (not= "]" (ch s (+ i 2))))
          (recur (+ i 3) (conj items [:range c (ch s (+ i 2))]) false)
          :else (recur (inc i) (conj items [:one c]) false))))))

(defn- parse-int-at [s i]
  (loop [i i acc nil]
    (let [c (ch s i)]
      (if (and c (<= 48 (cp c) 57))
        (recur (inc i) (+ (* (if acc acc 0) 10) (- (cp c) 48)))
        [acc i]))))

(defn- parse-quantifier [node s i src]
  (let [c (ch s i)]
    (cond
      (= c "*") [[:rep node 0 nil true] (inc i)]
      (= c "+") [[:rep node 1 nil true] (inc i)]
      (= c "?") [[:rep node 0 1 true] (inc i)]
      (= c "{")
      (let [pi (parse-int-at s (inc i))
            lo (first pi) j (second pi)]
        (cond
          (nil? lo) [node i]
          (= "}" (ch s j)) [[:rep node lo lo true] (inc j)]
          (= "," (ch s j))
          (let [pk (parse-int-at s (inc j))
                hi (first pk) k (second pk)]
            (if (= "}" (ch s k))
              [[:rep node lo hi true] (inc k)]
              (perr "unterminated {n,m}" src k)))
          :else (perr "unterminated {n,m}" src j)))
      :else [node i])))

(defn- apply-lazy [node s i]
  (if (and (= :rep (first node)) (= "?" (ch s i)))
    [(assoc node 4 false) (inc i)]
    [node i]))

(defn- parse-atom [s i src ngroups]
  (let [c (ch s i)]
    (cond
      (= c "(")
      (if (= "?" (ch s (inc i)))
        (if (= ":" (ch s (+ i 2)))
          (let [r (parse-alt s (+ i 3) src ngroups)]
            (if (= ")" (ch s (second r)))
              [(first r) (inc (second r))]
              (perr "unterminated group" src (second r))))
          (perr "only (?: ) groups are supported: no lookaround, flags or named groups" src i))
        (let [gn (inc @ngroups)]
          (vreset! ngroups gn)
          (let [r (parse-alt s (inc i) src ngroups)]
            (if (= ")" (ch s (second r)))
              [[:group gn (first r)] (inc (second r))]
              (perr "unterminated group" src (second r))))))
      (= c "[") (parse-class s (inc i) src)
      (= c ".") [[:any] (inc i)]
      (= c "^") [[:bol] (inc i)]
      (= c "$") [[:eol] (inc i)]
      (= c "\\")
      (let [e (ch s (inc i))]
        (cond
          (nil? e) (perr "trailing backslash" src i)
          (class-escapes e) [[:class false [[:pred e]]] (+ i 2)]
          (= e "b") [[:wordb] (+ i 2)]
          (= e "B") [[:nwordb] (+ i 2)]
          (and (>= (cp e) 49) (<= (cp e) 57)) (perr "backreferences are not supported" src i)
          :else [[:char (lit-escape e)] (+ i 2)]))
      :else [[:char c] (inc i)])))

(defn- parse-seq [s i src ngroups]
  (loop [i i acc []]
    (let [c (ch s i)]
      (if (or (nil? c) (= c "|") (= c ")"))
        [(if (= 1 (count acc)) (first acc) [:seq acc]) i]
        (let [a (parse-atom s i src ngroups)
              q (parse-quantifier (first a) s (second a) src)
              l (apply-lazy (first q) s (second q))]
          (recur (second l) (conj acc (first l))))))))

(defn- parse-alt [s i src ngroups]
  (loop [i i branches []]
    (let [r (parse-seq s i src ngroups)]
      (if (= "|" (ch s (second r)))
        (recur (inc (second r)) (conj branches (first r)))
        [(if (empty? branches) (first r) [:alt (conj branches (first r))]) (second r)]))))

;; ------------------------------------------------------------------ matching

(defn- word-cp? [v]
  (or (and (>= v 48) (<= v 57)) (and (>= v 65) (<= v 90))
      (and (>= v 97) (<= v 122)) (= v 95)))

(defn- space-cp? [v]
  (or (= v 32) (= v 9) (= v 10) (= v 13) (= v 12) (= v 11)))

(defn- word-char? [c] (word-cp? (cp c)))
(defn- space-char? [c] (space-cp? (cp c)))

;; Every predicate here is a test on a CODE POINT, and every caller used to hand
;; it a one-character string that it immediately converted back. The scan in
;; `find-from` does that once per position of the subject, which on a 32 799
;; character corpus was 36.65 ms -- 64% of the whole regex cost of
;; `bench/progs/words.cljc`, and more than the rest of the engine put together.
(defn- pred-match-cp? [kind v]
  (cond
    (= kind "d") (and (>= v 48) (<= v 57))
    (= kind "D") (not (and (>= v 48) (<= v 57)))
    (= kind "w") (word-cp? v)
    (= kind "W") (not (word-cp? v))
    (= kind "s") (space-cp? v)
    (= kind "S") (not (space-cp? v))
    :else false))

(defn- pred-match? [kind c] (pred-match-cp? kind (cp c)))

(defn- class-match-cp?
  "By INDEX, not by `seq`. `items` is a vector, and `seq`/`next` over a vector
  allocates a sequence cell per step -- once per item per POSITION of the
  subject, which for a one-item class was 7.5 ms of a 15.7 ms scan."
  [items v]
  (let [n (count items)]
    (loop [k 0]
      (if (< k n)
        (let [it (nth items k)
              t (nth it 0)]
          (if (cond
                (= :one t) (= v (cp (nth it 1)))
                (= :range t) (and (>= v (cp (nth it 1))) (<= v (cp (nth it 2))))
                :else (pred-match-cp? (nth it 1) v))
            true
            (recur (inc k))))
        false))))

(defn- class-match? [items c] (class-match-cp? items (cp c)))

(declare m match-seq)

(defn- m-rep [node s i groups k]
  (let [inner (nth node 1) lo (nth node 2) hi (nth node 3) greedy? (nth node 4)]
    (letfn [(go [n at gs]
              ;; The (> i2 at) guard is what stops (a*)* looping forever: a body
              ;; that matched nothing must not count as progress.
              (let [can-more (or (nil? hi) (< n hi))
                    more (fn [] (when can-more
                                  (m inner s at gs
                                     (fn [i2 g2] (when (> i2 at) (go (inc n) i2 g2))))))
                    stop (fn [] (when (>= n lo) (k at gs)))]
                (if greedy?
                  (let [r (more)] (if (nil? r) (stop) r))
                  (let [r (stop)] (if (nil? r) (more) r)))))]
      (go 0 i groups))))

(defn- m [node s i groups k]
  (let [tag (first node)]
    (cond
      (= tag :char) (when (and (< i (count s)) (= (flint.rt/nth s i) (second node)))
                      (k (inc i) groups))
      (= tag :any) (when (< i (count s)) (k (inc i) groups))
      (= tag :class)
      (when (< i (count s))
        (let [c (flint.rt/nth s i)
              hit (class-match? (nth node 2) c)]
          (when (if (second node) (not hit) hit) (k (inc i) groups))))
      (= tag :bol) (when (= i 0) (k i groups))
      (= tag :eol) (when (= i (count s)) (k i groups))
      (= tag :wordb)
      (let [before (and (> i 0) (word-char? (flint.rt/nth s (dec i))))
            after (and (< i (count s)) (word-char? (flint.rt/nth s i)))]
        (when (not= (boolean before) (boolean after)) (k i groups)))
      (= tag :nwordb)
      (let [before (and (> i 0) (word-char? (flint.rt/nth s (dec i))))
            after (and (< i (count s)) (word-char? (flint.rt/nth s i)))]
        (when (= (boolean before) (boolean after)) (k i groups)))
      (= tag :seq) (match-seq (second node) s i groups k)
      (= tag :alt)
      (loop [bs (seq (second node))]
        (when bs
          (let [r (m (first bs) s i groups k)]
            (if (nil? r) (recur (next bs)) r))))
      (= tag :group)
      (m (nth node 2) s i groups
         (fn [i2 g2] (k i2 (assoc g2 (second node) [i i2]))))
      (= tag :rep) (m-rep node s i groups k)
      :else nil)))

(defn- match-seq [nodes s i groups k]
  (if (empty? nodes)
    (k i groups)
    (m (first nodes) s i groups
       (fn [i2 g2] (match-seq (rest nodes) s i2 g2 k)))))

;; -------------------------------------------------------------------- the API

(defn- first-node
  "The node that must match at the start, if the pattern has one. Used to skip
  positions cheaply: without it, `find-from` builds a continuation closure at
  every index of the subject, which is where all the time went."
  [ast]
  (let [tag (first ast)]
    (cond
      (= tag :seq) (let [ns (second ast)] (when (seq ns) (first-node (first ns))))
      (= tag :group) (first-node (nth ast 2))
      (= tag :rep) (when (>= (nth ast 2) 1) (first-node (nth ast 1)))
      (= tag :char) ast
      (= tag :class) ast
      (= tag :bol) :bol
      :else nil)))

(def ^:private cache (atom {}))

(defn pattern
  "Compile `src`. Memoised, so a #\"...\" literal costs one map lookup per use.

  The AST is parsed here and handed to `flint.nfa`, which is SHARED across
  hosts -- the point of `doc/decisions/0012` being that every host executes the
  same compiled program, so there is no per-host pattern parser to disagree
  about `\\w`. The program then goes to the native simulator, which reads a rope
  through a cursor and never materialises it."
  [src]
  (let [hit (get @cache src)]
    (if hit
      hit
      (let [ngroups (volatile! 0)
            r (parse-alt src 0 src ngroups)]
        (when (< (second r) (count src)) (perr "unexpected )" src (second r)))
        (let [ast (first r)
              prog (nfa/compile-ast ast)
              words (reduce conj
                            (reduce conj
                                    [(:ninstrs prog) (count (:classes prog)) @ngroups]
                                    (:code prog))
                            (:classes prog))
              p {:flint/pattern src :ast ast :ngroups @ngroups :first (first-node ast)
                 :re (flint.rt/re-compile src words)}]
          (reset! cache (assoc @cache src p))
          p)))))

(defn pattern? [x] (and (map? x) (contains? x :flint/pattern)))

(defn- groups->result [s start end groups ngroups]
  (if (zero? ngroups)
    (subs s start end)
    (into [(subs s start end)]
          (map (fn [n] (let [g (get groups n)] (when g (subs s (first g) (second g)))))
               (range 1 (inc ngroups))))))

(defn- slots->groups
  "The native simulator returns a flat slot vector; the rest of this file speaks
  in `{group [start end]}`."
  [r ngroups]
  (loop [g 1 acc {}]
    (if (> g ngroups)
      acc
      (let [a (nth r (* 2 g)) b (nth r (inc (* 2 g)))]
        (recur (inc g) (if (neg? a) acc (assoc acc g [a b])))))))

(defn match-at
  "Match anchored at `i`. One left-to-right pass, no rewinding -- which is what
  lets the subject be a rope."
  [p s i]
  (let [r (flint.rt/re-run (:re p) s i nfa/ENTRY-ANCHORED)]
    (when r [(nth r 1) (slots->groups r (:ngroups p))])))

(defn find-from
  "First match at or after `from`.

  ONE pass: the compiled program carries a lazy `.*?` prefix, so the simulator
  finds the leftmost match itself rather than being restarted at every position.
  The prefilter this used to need -- a character test per position, in cljc --
  was measured at 33.6 ms of a 61 ms benchmark, and it is gone."
  [p s from]
  (let [r (flint.rt/re-run (:re p) s from nfa/ENTRY-UNANCHORED)]
    (when r [(nth r 0) (nth r 1) (slots->groups r (:ngroups p))])))

(defn find-from-old
  "The backtracker's scan, kept only as the thing the new one is checked against."
  [p s from]
  (let [fnode (:first p)
        n (count s)]
    (cond
      ;; Anchored at the start: there is exactly one position to try.
      (= fnode :bol)
      (when (<= from 0) (let [r (match-at p s 0)] (when r [0 (first r) (second r)])))

      (and (vector? fnode) (= :char (first fnode)))
      (let [c (second fnode)]
        (loop [i from]
          (let [j (flint.rt/str-index-of s c i)]
            (when j
              (let [r (match-at p s j)]
                (if r [j (first r) (second r)] (recur (inc j))))))))

      (and (vector? fnode) (= :class (first fnode)))
      (let [neg? (second fnode) items (nth fnode 2)]
        (loop [i from]
          (when (< i n)
            ;; The code point, not a one-character string: this is the scan, and
            ;; it runs once per position of the subject.
            (let [c (flint.rt/code-point-at s i)
                  hit (class-match-cp? items c)]
              (if (if neg? (not hit) hit)
                (let [r (match-at p s i)]
                  (if r [i (first r) (second r)] (recur (inc i))))
                (recur (inc i)))))))

      :else
      (loop [i from]
        (if (> i n)
          nil
          (let [r (match-at p s i)]
            (if r [i (first r) (second r)] (recur (inc i)))))))))

(defn re-find
  ([p s] (re-find p s 0))
  ([p s from]
   (let [r (find-from p s from)]
     (when r (groups->result s (first r) (second r) (nth r 2) (:ngroups p))))))

(defn re-matches
  "The whole string, or nil.

  NOT `match-at` plus an end check: leftmost-first would return the first match
  anchored at zero and give up, so the pattern a-or-ab against the subject ab
  would be nil where Java finds ab. The simulator is told to accept only a match
  that
  reaches the end, which lets the lower-priority alternative win -- the same
  answer a backtracker gets by backtracking against the anchor, without
  backtracking."
  [p s]
  (let [r (flint.rt/re-run (:re p) s 0 nfa/ENTRY-ANCHORED 1)]
    (when r (groups->result s (nth r 0) (nth r 1) (slots->groups r (:ngroups p))
                            (:ngroups p)))))

(defn- all-matches
  "Every match, from ONE pass of the simulator.

  `re-seq`, `split` and `replace` used to call `find-from` in a loop, and the
  simulator decodes the subject when it starts -- so splitting a 32 799
  character corpus into 6 601 pieces decoded it 6 601 times and the Pike VM came
  out four times SLOWER than the backtracker it replaced. A matcher that never
  rewinds can find every match in one traversal, and this is where that is spent."
  [p s limit]
  (let [w (flint.rt/re-find-all (:re p) s limit)
        ng (:ngroups p)
        k (* 2 (inc ng))
        n (quot (count w) k)]
    (mapv (fn [mi]
            (let [b (* mi k)]
              [(nth w b) (nth w (inc b))
               (loop [g 1 acc {}]
                 (if (> g ng)
                   acc
                   (let [a (nth w (+ b (* 2 g)))
                         e (nth w (+ b (inc (* 2 g))))]
                     (recur (inc g) (if (neg? a) acc (assoc acc g [a e]))))))]))
          (range n))))

(defn re-seq [p s]
  (let [ng (:ngroups p)]
    (seq (mapv (fn [m] (groups->result s (nth m 0) (nth m 1) (nth m 2) ng))
               (all-matches p s 0)))))

(defn replace-all [p s f]
  (let [ng (:ngroups p)]
    (loop [i 0 ms (seq (all-matches p s 0)) out []]
      (if (nil? ms)
        (flint.rt/str-join (conj out (subs s i)))
        (let [m (first ms) start (nth m 0) end (nth m 1)
              mres (groups->result s start end (nth m 2) ng)
              rep (if (string? f) f (f mres))]
          (if (= end start)
            (if (>= start (count s))
              (flint.rt/str-join (conj out (subs s i) rep))
              (recur (inc start) (next ms)
                     (conj out (subs s i start) rep (subs s start (inc start)))))
            (recur end (next ms) (conj out (subs s i start) rep))))))))

(defn replace-first [p s f]
  (let [r (find-from p s 0)]
    (if (nil? r)
      s
      (let [start (first r) end (second r)
            mres (groups->result s start end (nth r 2) (:ngroups p))
            rep (if (string? f) f (f mres))]
        (flint.rt/str-join [(subs s 0 start) rep (subs s end)])))))

(defn split
  ([p s] (split p s 0))
  ([p s limit]
   ;; Java's rules, which this never implemented and which the new battery
   ;; exposed: a zero-width match AT THE START produces no leading empty piece,
   ;; a zero-width match elsewhere still splits, and with no limit the trailing
   ;; empty pieces are dropped. Splitting abc on the empty pattern gives three
   ;; one-character pieces, not one three-character piece.
   (let [pieces (loop [i 0 ms (seq (all-matches p s (if (> limit 0) (dec limit) 0)))
                       acc []]
                  (if (nil? ms)
                    (conj acc (subs s i))
                    (let [m (first ms) start (nth m 0) end (nth m 1)]
                      (if (and (= end start) (= start 0))
                        (recur i (next ms) acc)
                        (recur end (next ms) (conj acc (subs s i start)))))))]
     (if (> limit 0)
       pieces
       ;; ALL of them, down to an empty vector: splitting abc on a-or-ab-then-c
       ;; is the empty vector in Clojure, not a vector holding one empty string.
       (loop [v pieces]
         (if (and (pos? (count v)) (= "" (peek v))) (recur (pop v)) v))))))

;; Registration, not a call: `clojure.string/split` must not name this namespace
;; statically or every program that splits on a comma would carry the engine.
;; This is a bare top-level form, so it ships exactly when this namespace is part
;; of the build -- which happens when something reaches `pattern`, which is the
;; only way to get a pattern in the first place.
(clojure.string/register-regex-ops!
 {:split split :replace-first replace-first :replace-all replace-all})
