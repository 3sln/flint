(ns flint.pike
  "The reference Pike VM (`doc/decisions/0012`), in cljc.

  Not for speed. It is the conformance oracle the native simulator is checked
  against, it lets the shared compiler be tested before any native exists, and it
  is what a new host runs on day one before it has its own.

  ## Why this shape

  One left-to-right pass carrying a set of live threads, consuming each
  character exactly once and never rewinding. That is the property the rope
  wants: the simulator needs nothing from its input but `next-character`.

  It is also why `(a+)+b` is linear rather than exponential -- threads are
  deduplicated by program counter, so a position can hold at most one thread per
  instruction however many ways the pattern could have reached it.

  Leftmost-first (Perl) semantics, not leftmost-longest: `add-thread` walks
  SPLIT's preferred branch first and the dedup set keeps it, so an earlier
  alternative wins exactly as a backtracker's would."
  (:require [flint.nfa :as nfa]))

(defn- word-cp? [v]
  (or (and (>= v 48) (<= v 57)) (and (>= v 65) (<= v 90))
      (and (>= v 97) (<= v 122)) (= v 95)))
(defn- space-cp? [v]
  (or (= v 32) (= v 9) (= v 10) (= v 13) (= v 12) (= v 11)))

(defn- pred-hit? [code v]
  (cond
    (= code nfa/PRED-D) (and (>= v 48) (<= v 57))
    (= code nfa/PRED-D!) (not (and (>= v 48) (<= v 57)))
    (= code nfa/PRED-W) (word-cp? v)
    (= code nfa/PRED-W!) (not (word-cp? v))
    (= code nfa/PRED-S) (space-cp? v)
    :else (not (space-cp? v))))

(defn- class-hit? [classes off v]
  (let [n (nth classes off)]
    (loop [k 0]
      (if (< k n)
        (let [b (+ off 1 (* k 3))
              t (nth classes b)]
          (if (cond
                (= t nfa/CL-ONE) (= v (nth classes (+ b 1)))
                (= t nfa/CL-RANGE) (and (>= v (nth classes (+ b 1)))
                                        (<= v (nth classes (+ b 2))))
                :else (pred-hit? (nth classes (+ b 1)) v))
            true
            (recur (inc k))))
        false))))

;; A thread is [pc saved]; `saved` is a map of slot -> position, which is what a
;; reference implementation should use and what the native one replaces with a
;; slot vector.
(defn- add-thread
  "Follow every epsilon edge from `pc`, depth first and PREFERRED branch first,
  so the order of the resulting list is the priority order. `seen` dedups by
  program counter, which is what makes this linear."
  [prog s i lst seen pc saved]
  (if (contains? @seen pc)
    lst
    (do
      (vswap! seen conj pc)
      (let [code (:code prog)
            b (* pc 3)
            op (nth code b)
            a (nth code (+ b 1))
            c (nth code (+ b 2))]
        (cond
          (= op nfa/OP-JMP) (add-thread prog s i lst seen a saved)
          (= op nfa/OP-SPLIT)
          (let [l (add-thread prog s i lst seen a saved)]
            (add-thread prog s i l seen c saved))
          (= op nfa/OP-SAVE)
          (add-thread prog s i lst seen (inc pc) (assoc saved a i))
          (= op nfa/OP-BOL)
          (if (= i 0) (add-thread prog s i lst seen (inc pc) saved) lst)
          (= op nfa/OP-EOL)
          (if (= i (count s)) (add-thread prog s i lst seen (inc pc) saved) lst)
          (or (= op nfa/OP-WORDB) (= op nfa/OP-NWORDB))
          (let [before (and (> i 0) (word-cp? (flint.rt/code-point-at s (dec i))))
                after (and (< i (count s)) (word-cp? (flint.rt/code-point-at s i)))
                at-boundary (not= (boolean before) (boolean after))]
            (if (if (= op nfa/OP-WORDB) at-boundary (not at-boundary))
              (add-thread prog s i lst seen (inc pc) saved)
              lst))
          :else (conj lst [pc saved]))))))

(defn- consumes? [prog pc v]
  (let [code (:code prog)
        b (* pc 3)
        op (nth code b)
        a (nth code (+ b 1))
        c (nth code (+ b 2))]
    (cond
      (= op nfa/OP-CHAR) (= v a)
      ;; Not a newline. Java's `.` excludes it without DOTALL and the old
      ;; backtracker matched it, so this is a divergence being closed rather
      ;; than one being introduced.
      (= op nfa/OP-ANY) (not= v 10)
      (= op nfa/OP-CLASS) (let [hit (class-hit? (:classes prog) a v)]
                            (if (= c 1) (not hit) hit))
      :else false)))

(defn run
  "Match `prog` against `s` starting at `from`. `full?` accepts only a match that
  reaches the end, which is `re-matches` and cannot be had by checking the span
  afterwards. Returns the slot map of the best (leftmost-first) match, or nil."
  ([prog s from] (run prog s from 0 false))
  ([prog s from entry full?]
  (let [n (count s)
        code (:code prog)]
    (loop [i from
           clist (add-thread prog s from [] (volatile! #{}) entry {})
           best nil]
      (if (empty? clist)
        best
        (let [v (when (< i n) (flint.rt/code-point-at s i))
              seen (volatile! #{})
              step (loop [ts (seq clist) nxt [] b best]
                     (if (nil? ts)
                       [nxt b]
                       (let [t (first ts)
                             pc (nth t 0)
                             saved (nth t 1)
                             op (nth code (* pc 3))]
                         (cond
                           ;; A MATCH cuts every LOWER-priority thread: they can
                           ;; only produce a match this one already beat. The
                           ;; threads already carried forward have HIGHER
                           ;; priority, so they stay and may yet beat it.
                           (= op nfa/OP-MATCH)
                           (if (or (not full?) (= i n)) [nxt saved] (recur (next ts) nxt b))
                           (and (some? v) (consumes? prog pc v))
                           (recur (next ts)
                                  (add-thread prog s (inc i) nxt seen (inc pc) saved)
                                  b)
                           :else (recur (next ts) nxt b)))))]
          (if (< i n)
            (recur (inc i) (nth step 0) (nth step 1))
            (nth step 1))))))))
