;; The deficiency lists are CHECKED, not written.
;;
;;  1. doc/manifest.edn is regenerated and compared, so it cannot drift from the
;;     source it describes.
;;  2. Every var it claims is present is referenced from a real program that is
;;     compiled and run, so "present" means the runtime really exposes it.
;;  3. Every macro it claims is present is expanded, for the same reason.
(require '[clojure.java.io :as io] '[clojure.string :as str] '[babashka.fs :as fs])

(def fails (atom 0))
(defn check [label ok & [detail]]
  (if ok
    (println "  ok  " label)
    (do (swap! fails inc) (println "  FAIL" label (when detail (str "\n     " detail))))))

(println "manifest: generated lists match the source")
(def before (slurp "doc/manifest.edn"))
(let [p (.start (ProcessBuilder. (into-array String ["./bin/manifest"])))]
  (slurp (.getInputStream p)) (slurp (.getErrorStream p)) (.waitFor p))
(def after (slurp "doc/manifest.edn"))
(check "doc/manifest.edn is up to date" (= before after)
       "run bin/manifest and commit the result")

(def manifest (read-string after))

;; --- every claimed var is really there --------------------------------------

(def tmpdir (fs/create-temp-dir))

(defn probe-ns [ns m]
  (let [vars (remove (:macros m) (:present m))
        alias-ns ns
        body (str "(ns probe (:require [" ns " :as target]))\n"
                  "(defn main [_]\n"
                  "  (str (count [\n"
                  (str/join "\n" (map (fn [v] (str "    target/" v)) vars))
                  "\n  ])))\n")]
    (spit (str tmpdir "/probe.cljc") body)
    (let [p (.start (ProcessBuilder.
                     (into-array String ["./bin/flint" ":src" (str tmpdir)
                                         ":fn" "probe/main" ":out" "out/probe.wasm"])))
          out (slurp (.getInputStream p)) err (slurp (.getErrorStream p))]
      (.waitFor p)
      (if (zero? (.exitValue p))
        (let [q (.start (ProcessBuilder.
                         (into-array String ["node" "host/flint.mjs" "out/probe.wasm"])))
              r (str/trim (slurp (.getInputStream q)))]
          (.waitFor q)
          (if (= r (str (count vars)))
            {:ok true :n (count vars)}
            {:ok false :why (str "module ran but reported " r " of " (count vars))}))
        {:ok false :why (str/trim (str out err))}))))

(println "manifest: every claimed var is reachable from a compiled module")
(doseq [[ns m] manifest]
  (let [r (probe-ns ns m)]
    (check (str ns " (" (count (remove (:macros m) (:present m))) " vars)")
           (:ok r) (:why r))))

;; --- every claimed macro really expands --------------------------------------
;;
;; Macros cannot be referenced as values, so they are checked by using them.
;; A macro that is listed but not registered fails to compile.

(def macro-uses
  '{when (when true 1)
    when-not (when-not false 1)
    if-not (if-not false 1 2)
    cond (cond :else 1)
    and (and 1 2)
    or (or nil 1)
    -> (-> 1 inc)
    ->> (->> [1] (map inc) first)
    as-> (as-> 1 x (inc x))
    some-> (some-> 1 inc)
    some->> (some->> [1] first)
    cond-> (cond-> 1 true inc)
    cond->> (cond->> [1] true (map inc))
    if-let (if-let [x 1] x 2)
    when-let (when-let [x 1] x)
    if-some (if-some [x 1] x 2)
    when-some (when-some [x 1] x)
    when-first (when-first [x [1]] x)
    doto (doto 1 identity)
    case (case 1 1 :a :b)
    cond-chain (cond-chain 1 [] nil)
    condp (condp = 1 1 :a :b)
    dotimes (dotimes [i 1] i)
    while (while false 1)
    assert (assert true)
    comment (comment 1)
    defonce (defonce probe-once 1)
    time (time 1)
    doseq (doseq [x [1]] x)
    for (for [x [1]] x)
    lazy-seq (lazy-seq [1])
    lazy-cat (lazy-cat [1] [2])
    letfn (letfn [(f [] 1)] (f))
    delay (delay 1)
    declare (declare probe-decl)
    fn (fn [] 1)
    let (let [x 1] x)
    loop (loop [] 1)
    defn (defn probe-defn [] 1)
    defn- (defn- probe-defn2 [] 1)
    defmacro (defmacro probe-macro [] 1)
    defmulti (defmulti probe-multi identity)
    defmethod (defmethod probe-multi :x [_] 1)})

(println "manifest: every claimed macro expands")
(let [claimed (:macros (get manifest 'clojure.core))
      untested (remove macro-uses claimed)]
  (check "every claimed macro has a use in this test" (empty? untested)
         (str "untested: " (pr-str (vec untested))))
  (let [body (str "(ns probe)\n"
                  "(defmulti probe-multi identity)\n"
                  (str/join "\n" (for [m claimed
                                       :when (and (macro-uses m)
                                                  (not (#{'defmulti 'defmethod} m)))]
                                   (str "(defn probe-" (str/replace (str m) #"[^a-zA-Z0-9-]" "_")
                                        " [] " (pr-str (macro-uses m)) ")")))
                  "\n(defmethod probe-multi :x [_] 1)\n"
                  "(defn main [_] \"macros ok\")\n")]
    (spit (str tmpdir "/probe.cljc") body)
    (let [p (.start (ProcessBuilder.
                     (into-array String ["./bin/flint" ":src" (str tmpdir)
                                         ":fn" "probe/main" ":out" "out/probe.wasm"])))
          out (slurp (.getInputStream p)) err (slurp (.getErrorStream p))]
      (.waitFor p)
      (spit "out/macro-probe.cljc" body)
      (check "all claimed macros compile" (zero? (.exitValue p))
             (apply str (take 900 (str/trim (str out err))))))))

(if (zero? @fails)
  (println "manifest: ok")
  (do (println "manifest:" @fails "FAILURES") (System/exit 1)))
