(ns flint.regex
  "A backtracking regular-expression engine, in cljc.

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
  (:require [flint.rt]))

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

(defn- word-char? [c]
  (let [v (cp c)]
    (or (and (>= v 48) (<= v 57)) (and (>= v 65) (<= v 90))
        (and (>= v 97) (<= v 122)) (= v 95))))

(defn- space-char? [c]
  (let [v (cp c)] (or (= v 32) (= v 9) (= v 10) (= v 13) (= v 12) (= v 11))))

(defn- pred-match? [kind c]
  (cond
    (= kind "d") (let [v (cp c)] (and (>= v 48) (<= v 57)))
    (= kind "D") (let [v (cp c)] (not (and (>= v 48) (<= v 57))))
    (= kind "w") (word-char? c)
    (= kind "W") (not (word-char? c))
    (= kind "s") (space-char? c)
    (= kind "S") (not (space-char? c))
    :else false))

(defn- class-match? [items c]
  (loop [xs (seq items)]
    (if xs
      (let [it (first xs)]
        (cond
          (= :one (first it)) (if (= c (second it)) true (recur (next xs)))
          (= :range (first it)) (if (and (>= (cp c) (cp (second it)))
                                         (<= (cp c) (cp (nth it 2))))
                                  true (recur (next xs)))
          :else (if (pred-match? (second it) c) true (recur (next xs)))))
      false)))

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
  "Compile `src`. Memoised, so a #\"...\" literal costs one map lookup per use."
  [src]
  (let [hit (get @cache src)]
    (if hit
      hit
      (let [ngroups (volatile! 0)
            r (parse-alt src 0 src ngroups)]
        (when (< (second r) (count src)) (perr "unexpected )" src (second r)))
        (let [ast (first r)
              p {:flint/pattern src :ast ast :ngroups @ngroups :first (first-node ast)}]
          (reset! cache (assoc @cache src p))
          p)))))

(defn pattern? [x] (and (map? x) (contains? x :flint/pattern)))

(defn- groups->result [s start end groups ngroups]
  (if (zero? ngroups)
    (subs s start end)
    (into [(subs s start end)]
          (map (fn [n] (let [g (get groups n)] (when g (subs s (first g) (second g)))))
               (range 1 (inc ngroups))))))

(defn match-at [p s i]
  (m (:ast p) s i {} (fn [e gs] [e gs])))

(defn find-from
  "First match at or after `from`. Skips positions the pattern's first node
  cannot possibly match, which turns a scan over a large subject from one
  closure allocation per position into a character test per position -- and,
  for a literal first character, into a single native substring search."
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
            (let [c (flint.rt/nth s i)
                  hit (class-match? items c)]
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

(defn re-matches [p s]
  (let [r (m (:ast p) s 0 {} (fn [e gs] (when (= e (count s)) [e gs])))]
    (when r (groups->result s 0 (first r) (second r) (:ngroups p)))))

(defn re-seq [p s]
  (loop [i 0 acc []]
    (if (> i (count s))
      (seq acc)
      (let [r (find-from p s i)]
        (if (nil? r)
          (seq acc)
          (let [start (first r) end (second r)]
            (recur (if (= end start) (inc end) end)
                   (conj acc (groups->result s start end (nth r 2) (:ngroups p))))))))))

(defn replace-all [p s f]
  (loop [i 0 out []]
    (if (> i (count s))
      (flint.rt/str-join out)
      (let [r (find-from p s i)]
        (if (nil? r)
          (flint.rt/str-join (conj out (subs s i)))
          (let [start (first r) end (second r)
                mres (groups->result s start end (nth r 2) (:ngroups p))
                rep (if (string? f) f (f mres))]
            (if (= end start)
              (if (>= start (count s))
                (flint.rt/str-join (conj out (subs s i) rep))
                (recur (inc start) (conj out (subs s i start) rep (subs s start (inc start)))))
              (recur end (conj out (subs s i start) rep)))))))))

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
   (loop [i 0 n 1 acc []]
     (if (and (> limit 0) (>= n limit))
       (conj acc (subs s i))
       (let [r (find-from p s i)]
         (if (nil? r)
           (conj acc (subs s i))
           (let [start (first r) end (second r)]
             (if (= end start)
               (conj acc (subs s i))
               (recur end (inc n) (conj acc (subs s i start)))))))))))
