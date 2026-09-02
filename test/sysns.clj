;; `flint.sys.*`: virtual namespaces served by the binary (`doc/decisions/0037`).
;;
;; Run against `target/release/flint` -- the binary that SHIPS -- for the reason
;; the SDK selftest gives: a test against something else passes with the shipped
;; thing broken.
(require '[clojure.string :as str] '[babashka.fs :as fs])

(def root (str (fs/parent (fs/parent (fs/absolutize *file*)))))
(def flint (str root "/target/release/flint"))

(defn sh [proj & args]
  (let [pb (doto (ProcessBuilder. (into-array String args)) (.directory (java.io.File. proj)))
        p (.start pb)
        out (slurp (.getInputStream p)) err (slurp (.getErrorStream p))]
    (.waitFor p) {:exit (.exitValue p) :out (str out err)}))

(def fails (atom 0))
(defn check [label ok detail]
  (if ok (println "  ok  " label)
      (do (swap! fails inc) (println "  FAIL" label "\n        " detail))))

(println "sysns: flint.sys.* is served by the binary (0037)")

(when-not (fs/exists? flint)
  (println "  (skipped: no target/release/flint -- `cargo build --release -p flint-cli`)")
  (System/exit 0))

(def proj (str (fs/create-temp-dir)))
(fs/create-dirs (str proj "/app"))
(spit (str proj "/deps.edn") "{}")
(spit (str proj "/app/a.cljc")
      (str "(ns app.a (:require [flint.sys.fs :as fs]))\n"
           "(defn go [_] (str \"deps=\" (fs/exists? \"deps.edn\")\n"
           "                  \" nope=\" (fs/exists? \"nope.edn\")\n"
           "                  \" n=\" (count (fs/list-dir \"\"))))\n"
           "(defn escape [_] (fs/read-file \"../../etc/passwd\"))\n"))

;; GRANTED: the call reaches the server and comes back.
(let [r (sh proj flint "run" ":path" "." ":fn" "app.a/go" ":with" "[fs]")]
  (check "a granted flint.sys.fs call reaches the server"
         (str/includes? (:out r) "deps=true") (:out r))
  (check "and a missing file is false rather than an error"
         (str/includes? (:out r) "nope=false") (:out r)))

;; NOT GRANTED: there is no system port, so it cannot even ask.
(let [r (sh proj flint "run" ":path" "." ":fn" "app.a/go")]
  (check "without the grant it cannot ask at all"
         (and (not (zero? (:exit r))) (str/includes? (:out r) "no system port"))
         (:out r)))

;; THE ROOT IS THE AUTHORITY. An escape is refused, not clamped.
(let [r (sh proj flint "run" ":path" "." ":fn" "app.a/escape" ":with" "[fs]")]
  (check "a path that leaves the root is refused"
         (str/includes? (:out r) "leaves the root") (:out r)))

;; The CLI knows its own surface, so an unknown var is a COMPILE error.
(spit (str proj "/app/b.cljc")
      "(ns app.b (:require [flint.sys.fs :as fs])) (defn go [_] (fs/no-such-thing 1))")
(let [r (sh proj flint "run" ":path" "." ":fn" "app.b/go" ":with" "[fs]")]
  (check "an unknown var in a served namespace is a COMPILE error"
         (str/includes? (:out r) "does not hold no-such-thing") (:out r)))

;; Read-only unless asked for. `:with [fs]` lends reading; writing is `fs:write`.
(spit (str proj "/app/c.cljc")
      "(ns app.c (:require [flint.sys.fs :as fs])) (defn go [_] (fs/write-file \"x.txt\" \"hi\"))")
(let [r (sh proj flint "run" ":path" "." ":fn" "app.c/go" ":with" "[fs]")]
  (check "a :fs grant is read-only unless write was asked for"
         (str/includes? (:out r) "read-only") (:out r)))
(let [r (sh proj flint "run" ":path" "." ":fn" "app.c/go" ":with" "[fs:write]")]
  (check "and writable when it was"
         (and (zero? (:exit r)) (fs/exists? (str proj "/x.txt"))) (:out r)))

(if (pos? @fails)
  (do (println "sysns:" @fails "FAILURES") (System/exit 1))
  (println "sysns: ok"))
