;; THE COMPILER RUNS UNDER ANY CLOJURE THAT CAN IMPLEMENT ITS RESOLVER PROTOCOL.
;;
;; `DECISIONS.md#namespaces-over-the-system-port`, "the resolver protocol". The
;; compiler asks `flint.project/Resolver` for namespaces in sorted waves and
;; takes FORMS back. Inside the sandbox an adapter over the host's port
;; implements it (`flint.selfhost/compile`, held by the native test
;; `cli/src/compile_call_test.rs`); here babashka implements it with `reify`,
;; reading files off the disk with `flint.reader`, and calls
;; `flint.selfhost/compile-with` -- the SAME compiler source, interpreted by sci
;; instead of compiled by flint.
;;
;; What is asserted:
;;
;;   * the IR this produces for a corpus program is byte-identical to what the
;;     native CLI writes for it (`flint compile :to :llvm`) -- two Clojures, two
;;     resolvers, one image;
;;   * the protocol's request discipline holds under a resolver that is not the
;;     port adapter: every wave sorted, every namespace asked once, exactly the
;;     reached ones asked;
;;   * a namespace the resolver does not answer is a `:missing` error positioned
;;     at its require, with a control that answers it.
;;
;; Needs `target/release/flint` built from the same tree (AGENTS.md section 3).
(require '[babashka.fs :as fs]
         '[babashka.classpath :as cp]
         '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.string :as str])

(def root (str (fs/parent (fs/parent (fs/absolutize *file*)))))
(cp/add-classpath (str root "/src:" root "/lib"))
(require '[flint.project :as project]
         '[flint.reader :as reader]
         '[flint.selfhost :as selfhost])

(def fails (atom 0))
(defn check-that [label ok]
  (if ok (println "  ok  " label)
      (do (swap! fails inc) (println "  FAIL" label))))

(def stdlib-dir (str root "/lib"))

(defn read-answer
  "Namespace `n` from the first of `dirs` that has it, READ -- the protocol
  speaks forms. The standard library answers with its own workspace and grants
  (`lib/deps.edn`), as every door's resolver does; anything else is anonymous."
  [dirs features n]
  (first
   (for [d dirs
         ext project/source-extensions
         :let [rel (str (project/ns->path n) ext)
               f (fs/file d rel)]
         :when (fs/exists? f)]
     (let [lib? (= d stdlib-dir)
           s (cond-> {:file rel}
               lib? (assoc :workspace 'flint/flint :grants [:host :vars]))]
       (assoc s :forms (reader/read-all (slurp f) (project/read-options s features)))))))

(defn dir-resolver
  "A `flint.project/Resolver` over directories, recording every wave asked."
  [dirs features asked]
  (reify project/Resolver
    (resolve-wave [_ names]
      (swap! asked conj names)
      (mapv (fn [n] (read-answer dirs features n)) names))))

(def slots (into {} (map (fn [[k v]] [k v])) (json/parse-string (slurp (str root "/dist/slots.json")))))

(defn waves-ok? [asked reached]
  (let [flat (vec (apply concat asked))]
    (and (every? (fn [w] (= w (vec (sort-by str w)))) asked)
         (= (count flat) (count (distinct flat)))
         (= flat reached))))

(println "resolver protocol: the compiler driven by babashka through reify")

(let [work (str (fs/create-temp-dir))
      prog "nbody"
      features reader/default-features
      asked (atom [])
      t0 (System/currentTimeMillis)
      r (selfhost/compile-with {:entry (symbol prog "main") :target :llvm :slots slots}
                               (dir-resolver [stdlib-dir (str root "/corpus")] features asked))
      ms (- (System/currentTimeMillis) t0)
      out (str work "/" prog ".ll")
      _ (p/shell {:dir root :out :string :err :string}
                 (str root "/target/release/flint") "compile" ":path" "corpus"
                 ":fn" (str prog "/main") ":to" ":llvm" ":out" out)
      native (slurp out)
      mine (some-> (:artifact r) (String. "UTF-8"))]
  (check-that (str prog " :to :llvm: babashka through the protocol and the native CLI agree byte for byte ("
                   ms " ms)")
              (and (empty? (:errors r)) (= native mine)))
  (when (seq (:errors r)) (println "      " (pr-str (:errors r))))
  (check-that (str "sorted waves, each namespace once, exactly the " (count (:reached r))
                   " reached, in " (count @asked) " waves")
              (waves-ok? @asked (mapv :ns (:reached r))))
  (fs/delete-tree work))

(let [work (str (fs/create-temp-dir))
      app "(ns app\n  (:require [clojure.string :as s]\n            [nope.gone :as g]))\n(defn main [_] (g/f))\n"
      _ (spit (str work "/app.cljc") app)
      run (fn [] (selfhost/compile-with {:entry 'app/main :target :image :builtins (set (keys slots))}
                                        (dir-resolver [stdlib-dir work] reader/default-features (atom []))))
      r (run)
      e (first (:errors r))]
  (check-that "a namespace nobody answers is :missing, positioned at its require (app.cljc:3:13)"
              (and (= 1 (count (:errors r)))
                   (= {:kind :missing :ns 'nope.gone :required-by ['app] :file "app.cljc" :line 3 :column 13}
                      e)))
  (when-not (= 1 (count (:errors r))) (println "      " (pr-str (:errors r))))
  (fs/create-dirs (str work "/nope"))
  (spit (str work "/nope/gone.cljc") "(ns nope.gone)\n(defn f [] \"ok\")\n")
  (let [r (run)]
    (check-that "the control: answered, it compiles"
                (and (empty? (:errors r)) (some? (:artifact r)))))
  (fs/delete-tree work))

;; THE SOURCE RESOLVED AS A NAMESPACE must declare exactly that namespace
;; (`DECISIONS.md#a-source-defines-only-its-own-namespace`), and `ns->path`
;; munges `-` to `_` -- so a namespace with a DASH and one with the SAME NAME
;; but a literal UNDERSCORE in that position resolve to the same file. This is
;; the shape behind the `bin/conform-hosts` fixture `aot_try.cljc`, which says
;; `(ns aot-try ..)`: the ordinary Clojure convention, `ns a-b.c` <-> path
;; `a_b/c`. The bug `bin/conform-hosts` had was asking the resolver for
;; namespace `aot_try` (the PATH, read as if it were the name) rather than
;; `aot-try` (the name the file declares) -- a caller deriving a namespace
;; from a path without un-munging it, exactly what `analyze-ns` now refuses.
(let [work (str (fs/create-temp-dir))
      ;; `ab_cd.cljc` on disk, `(ns ab-cd ..)` inside -- the convention, and
      ;; the shape `aot_try.cljc`/`(ns aot-try ..)` actually has.
      _ (spit (str work "/ab_cd.cljc") "(ns ab-cd)\n(defn main [_] \"ok\")\n")
      ;; A literal underscore IN THE NAMESPACE, not produced by munging a
      ;; dash: `(ns under_score ..)` at `under_score.cljc`. `ns->path` leaves
      ;; an underscore alone, so this resolves to its own path and is not the
      ;; munging of anything else.
      _ (spit (str work "/under_score.cljc") "(ns under_score)\n(defn main [_] \"ok\")\n")
      ;; A GENUINELY different name -- no munging makes these the same path
      ;; mean two things; `wrongname.cljc` simply declares the wrong one.
      _ (spit (str work "/wrongname.cljc") "(ns something-else)\n(defn main [_] \"ok\")\n")
      asked (atom [])
      compile1 (fn [entry]
                 (selfhost/compile-with {:entry entry :target :image :builtins (set (keys slots))}
                                        (dir-resolver [stdlib-dir work] reader/default-features asked)))
      ok (compile1 'ab-cd/main)
      bug (compile1 'ab_cd/main)
      lit (compile1 'under_score/main)
      wrong (compile1 'wrongname/main)]
  (check-that "resolved as ab-cd, declared ab-cd: compiles (the fixed convention)"
              (and (empty? (:errors ok)) (some? (:artifact ok))))
  ;; This walk (`flint.project/collect-waves`) catches a mismatch EARLY, as a
  ;; `:resolver` error naming both sides -- before the analyzer would ever see
  ;; the form. `bin/flint`'s own walk has no such pre-check (it duplicates
  ;; `flint.project/collect`, a function this one replaced), so the SAME
  ;; mismatch reaches `bin/flint` as the analyzer's `:compile`-kind "resolved
  ;; as namespace .. and its ns form names .." error instead -- which is what
  ;; `runtimes/conform/aot_try.cljc` actually showed in CI. Both doors still
  ;; refuse the program; they differ only in where.
  (check-that "resolved as ab_cd (the path, read as a name), declared ab-cd: refused"
              (and (= 1 (count (:errors bug)))
                   (= :resolver (:kind (first (:errors bug))))
                   (str/includes? (:message (first (:errors bug))) "asked for ab_cd")
                   (str/includes? (:message (first (:errors bug))) "ab-cd")))
  (check-that "resolved as under_score, declared under_score (a literal underscore, not a munged dash): compiles"
              (and (empty? (:errors lit)) (some? (:artifact lit))))
  (check-that "resolved as wrongname, declared something-else: still refused, no exception for a path-shaped name"
              (and (= 1 (count (:errors wrong)))
                   (= :resolver (:kind (first (:errors wrong))))))
  (fs/delete-tree work))

(when (pos? @fails)
  (println (format "resolver-protocol: %d FAILED" @fails))
  (System/exit 1))
(println "resolver-protocol: ok")
