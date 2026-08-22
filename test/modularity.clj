;; "A module compiled from a program that never mentions XML must not carry an
;; XML parser." Asserted by SYMBOL, not by size, so it cannot pass for the wrong
;; reason -- and by size as well, so the saving is a number.
(require '[clojure.java.io :as io] '[clojure.string :as str] '[babashka.fs :as fs])

(def fails (atom 0))
(defn check [label actual expected]
  (if (= actual expected)
    (println "  ok  " label)
    (do (swap! fails inc) (println "  FAIL" label "expected" expected "got" actual))))

(def srcdir (fs/create-temp-dir))
(spit (str srcdir "/jonly.cljc")
      "(ns jonly (:require [flint.data.json :as json]))\n(defn main [_] (json/write-str (json/read-str \"{\\\"a\\\":[1,2]}\")))")
(spit (str srcdir "/xonly.cljc")
      "(ns xonly (:require [flint.data.xml :as xml]))\n(defn main [_] (xml/emit-str (xml/parse-one \"<a><b/></a>\")))")
(spit (str srcdir "/honly.cljc")
      "(ns honly (:require [flint.data.html :as html]))\n(defn main [_] (str (count (html/parse \"<p>a<p>b\"))))")
(spit (str srcdir "/none.cljc") "(ns none)\n(defn main [_] \"nothing\")")

(defn build! [n & [keep-names]]
  (let [args (concat ["./bin/flint" ":src" (str srcdir) ":fn" (str n "/main")
                      ":out" (str "out/mod-" n ".wasm")]
                     (when keep-names ["--keep-names"]))
        p (.start (ProcessBuilder. (into-array String args)))]
    (slurp (.getInputStream p)) (slurp (.getErrorStream p)) (.waitFor p)
    (when-not (zero? (.exitValue p)) (println "build failed for" n) (System/exit 1))
    (fs/size (str "out/mod-" n ".wasm"))))

(defn syms [n]
  (let [b (fs/read-all-bytes (str "out/mod-" n ".wasm"))]
    (String. b "ISO-8859-1")))

(defn has? [n s] (str/includes? (syms n) s))

(println "modularity: only reachable code ships")
(def sizes (into {} (for [n ["jonly" "xonly" "honly" "none"]] [n (build! n true)])))

(check "json module has the json parser"   (has? "jonly" "json_parse") true)
(check "json module has no xml parser"     (has? "jonly" "xml_parse") false)
(check "json module has no html parser"    (has? "jonly" "html_parse") false)
(check "json module has no xmlparser crate" (has? "jonly" "xmlparser") false)
(check "xml module has the xml parser"     (has? "xonly" "xml_parse") true)
(check "xml module has no json parser"     (has? "xonly" "json_parse") false)
(check "xml module has no serde"           (has? "xonly" "serde") false)
(check "html module has the html parser"   (has? "honly" "html_parse") true)
(check "html module has no xml parser"     (has? "honly" "xml_parse") false)
(check "a program with no parsers has none of them"
       (or (has? "none" "json_parse") (has? "none" "xml_parse") (has? "none" "html_parse")) false)
(check "a builtin the program never calls is absent"
       (has? "none" "flint_b_m_sqrt") false)

;; And the same thing as a number, stripped, for the benchmark table.
(def stripped (into {} (for [n ["jonly" "xonly" "honly" "none"]] [n (build! n)])))
(println "  stripped module sizes:")
(doseq [n ["none" "jonly" "xonly" "honly"]]
  (println (format "    %-6s %7d bytes  (+%d over the floor)"
                   n (stripped n) (- (stripped n) (stripped "none")))))
(check "a parser costs real bytes, so its absence is worth something"
       (> (- (stripped "jonly") (stripped "none")) 10000) true)

(if (zero? @fails)
  (println "modularity: ok")
  (do (println "modularity:" @fails "FAILURES") (System/exit 1)))
