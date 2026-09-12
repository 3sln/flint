(ns flint.deps.manifest
  "Manifest SCANNERS: what a package that is already on disk says it depends on
  (`DECISIONS.md#one-dependency-walk`).

  ## Two axes, and this file is one of them

  What FETCHES a package and what READS its dependency list are independent
  things, and coupling them is what produced three transitive mechanisms where
  there should be one. A `:local/root` is never downloaded and still has a
  manifest worth reading; a git checkout is fetched by git and may then turn out
  to carry a `deps.edn`, a `package.json` or a `pom.xml`. So a scanner takes a
  DIRECTORY THAT IS ALREADY THERE and answers in one shape:

      {:format :package-json
       :deps   {name coord}
       :paths  [\"src\"]}          ; only `deps.edn` has an opinion about roots

  `:deps` is keyed and valued exactly as `deps.edn` writes it, which is what
  lets one walk consume every format: the caller never learns which file the
  answer came out of.

  ## Why the parsing is here and not in Rust

  `ROADMAP.md` put manifest parsing in the native `deps.*` modules. It is here
  instead, and the reason is the reason this file exists at all: the walk that
  reads these manifests has to run under `bin/flint`, which is babashka with no
  flint runtime and no ports, AND inside the shipped binary. Parsing in Rust
  would mean the bootstrap host could not resolve a transitive dependency at
  all, or -- worse -- that it grew its own babashka copy of every format. That
  is the third-copy failure this whole change exists to undo, so the format
  knowledge lives in one portable namespace that both hosts run.

  For the same reason this does NOT use `flint.data.json` or `flint.data.xml`:
  both bottom out in `flint.rt/json-parse` and `flint.rt/xml-parse`, runtime
  builtins that do not exist under babashka. What is here is deliberately not a
  JSON or XML parser -- it is a scanner that can find one member of one object,
  and one element of one document, and nothing else. It is narrow on purpose:
  a manifest reader that cannot read arbitrary JSON cannot be wrong about
  arbitrary JSON."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            ;; REAL PARSERS, chosen per host by a reader conditional. This
            ;; namespace has to run under babashka (which drives the walk on
            ;; the bootstrap host) and under flint, and neither can load the
            ;; other's parser -- so each takes the one it has.
            #?(:clj [cheshire.core :as json]
               :flint [flint.data.json :as json])
            #?(:clj [clojure.data.xml :as xml]
               :flint [flint.data.xml :as xml])))

;; ------------------------------------------------------------- two hosts, one shape
;;
;; THIS USED TO BE A SCANNER, and its own docstring said so: "not a JSON or XML
;; parser -- it is a scanner that can find one member of one object". That was
;; not a judgement about robustness. `flint run` could not carry the JSON and
;; XML units at all, so flint's own CLI could not parse a `package.json` or a
;; `pom.xml` natively, and a scanner was what was reachable. The units are
;; linked now, so this reads the documents.
;;
;; The two hosts agree closely enough that one code path serves both: JSON
;; comes back identically, and XML comes back as `:tag`/`:attrs`/`:content`
;; either way -- `clojure.data.xml` wraps it in a record that answers the same
;; keywords. The only difference is that babashka's omits `:attrs` when empty,
;; which `attrs-of` below absorbs.

(defn- json-parse
  "A JSON document as data. String keys, both sides."
  [text]
  #?(:clj (json/parse-string (str text))
     :flint (json/read-str (str text))))

(defn- xml-parse
  "An XML document as ONE root element. `clojure.data.xml/parse-str` answers
  one; flint's `parse-str` answers a sequence and `parse-one` takes its head."
  [text]
  #?(:clj (xml/parse-str (str text))
     :flint (xml/parse-one (str text))))

(defn- attrs-of
  "An element's attributes, as a map either way."
  [e]
  (or (:attrs e) {}))

(defn- kids
  "The child ELEMENTS of `e` with tag `tag`, skipping text nodes."
  [e tag]
  (filterv (fn [c] (and (map? c) (= (name (:tag c)) (name tag)))) (:content e)))

(defn- text-of
  "An element's text content, trimmed. Empty when it has none."
  [e]
  (str/trim (apply str (filter string? (:content e)))))

(defn- child-text
  "The text of `e`'s first `tag` child, or nil when there is none."
  [e tag]
  (when-let [c (first (kids e tag))] (text-of c)))

;; ------------------------------------------------------------ JSON, narrowly

(defn- next-of
  "The first index at or after `from` of any of `cs`, or nil."
  [s from cs]
  (reduce (fn [best c]
            (let [i (str/index-of s c from)]
              (if (and i (or (nil? best) (< i best))) i best)))
          nil cs))

(defn- string-end
  "Index just past the closing quote of the JSON string whose opening quote is
  at `i`. A backslash escapes the next character, which is the one thing a
  naive scan gets wrong on real manifests -- a Windows path in a `\"bin\"`
  entry ends in `\\\\` often enough to matter."
  [s i]
  (loop [j (inc i)]
    (let [q (str/index-of s "\"" j)
          b (str/index-of s "\\" j)]
      (cond
        (nil? q) (count s)
        (and b (< b q)) (recur (+ b 2))
        :else (inc q)))))

(defn- bracket-end
  "Index just past the `}` or `]` that closes the bracket opening at `i`.
  Strings are skipped whole, so a brace inside a value cannot unbalance it."
  [s i]
  (loop [j (inc i) depth 1]
    (if (zero? depth)
      j
      (let [nx (next-of s j ["{" "}" "[" "]" "\""])]
        (if (nil? nx)
          (count s)
          (let [c (subs s nx (inc nx))]
            (cond
              (= c "\"") (recur (string-end s nx) depth)
              (or (= c "{") (= c "[")) (recur (inc nx) (inc depth))
              :else (if (= 1 depth) (inc nx) (recur (inc nx) (dec depth))))))))))

(defn- value-end
  "Index just past the JSON value beginning at `i`."
  [s i]
  (let [c (subs s i (inc i))]
    (cond
      (= c "\"") (string-end s i)
      (or (= c "{") (= c "[")) (bracket-end s i)
      :else (or (next-of s i [","  "}"]) (count s)))))

(defn- skip-space [s i]
  (loop [j i]
    (if (and (< j (count s)) (contains? #{" " "\t" "\n" "\r"} (subs s j (inc j))))
      (recur (inc j))
      j)))

(defn deps-edn
  "`deps.edn`: flint's own format, and the one that wins wherever two are
  present. A package published to npm carries a `package.json` because npm
  demands one; if it also carries a `deps.edn` then that is what its author
  wrote about flint, and it is the better answer."
  [text]
  (let [d (edn/read-string text)]
    {:format :deps-edn
     :deps (or (:deps d) {})
     :paths (vec (:paths d))}))

(defn package-json
  "`package.json`. `dependencies` only -- `devDependencies` are the package's
  own build and nobody consuming it needs them, which is the rule npm itself
  applies when installing a dependency rather than a project.

  The VERSIONS ARE RANGES, and flint takes exact versions. They are carried
  through as written; deciding what to do about a range is the walk's business
  (`flint.deps/fetch-plan` applies `:flint/overrides` first, so a pinned one is
  already exact by the time it gets here)."
  [text]
  {:format :package-json
   :deps (reduce (fn [m e]
                   (assoc m (symbol (key e))
                          {:npm/name (key e) :npm/version (str (val e))}))
                 {} (get (json-parse text) "dependencies"))
   :paths []})

(def ^:private skipped-scopes
  "A dependency of the POM's own build, not of anything consuming it.
  `provided` and `system` are supplied by the container, `test` by the test
  run, and `import` is a `dependencyManagement` device rather than an edge."
  #{"test" "provided" "system" "import"})

(defn pom-xml
  "`pom.xml`, read as far as is honest.

  Handled: the top-level `<dependencies>`, `<properties>` substitution for
  `${name}`, and `${project.version}`. Skipped: `test`/`provided`/`system`
  scopes, `<optional>true</optional>`, and everything in
  `<dependencyManagement>` -- which is version POLICY for dependencies declared
  elsewhere and not itself an edge in the graph.

  NOT handled, and stated rather than discovered later: parent POM inheritance,
  profiles, and version RANGES. A dependency whose version is still `${...}`
  after substitution, or absent because a parent declared it, comes back with
  the unresolved text in `:mvn/version`, where it is refused by name -- which
  is what it should do, because inventing a version would be worse."
  [text]
  (let [root (xml-parse text)
        props (into {} (for [p (kids root "properties")
                             c (:content p)
                             :when (map? c)]
                         [(name (:tag c)) (text-of c)]))
        ;; The POM'S OWN version, not a dependency's. Read off the root's own
        ;; children rather than from anywhere in the document, which is what a
        ;; tree gets for free and a scan had to arrange by deleting blocks.
        own-version (child-text root "version")
        expand (fn [v]
                 (let [v (str/trim (str v))]
                   (if-not (str/starts-with? v "${")
                     v
                     (let [k (subs v 2 (max 2 (dec (count v))))]
                       (cond
                         (contains? props k) (get props k)
                         (contains? #{"project.version" "version" "pom.version"} k)
                         (str own-version)
                         :else v)))))
        ;; `<dependencyManagement>` is version POLICY for dependencies declared
        ;; elsewhere, not an edge in the graph. Excluded by taking only the
        ;; ROOT's own `<dependencies>` -- the nesting says it, so nothing has to
        ;; find and delete the block.
        entries (mapcat (fn [b] (kids b "dependency")) (kids root "dependencies"))]
    {:format :pom
     :deps (reduce (fn [m d]
                     (let [g (child-text d "groupId")
                           a (child-text d "artifactId")
                           v (child-text d "version")
                           scope (child-text d "scope")
                           optional (child-text d "optional")]
                       (if (or (str/blank? (str g)) (str/blank? (str a))
                               (contains? skipped-scopes (str scope))
                               (= "true" (str optional)))
                         m
                         (assoc m
                                ;; `group/artifact`, exactly as `deps.edn` keys
                                ;; a maven dependency.
                                (symbol (str g "/" a))
                                {:mvn/version (expand (or v ""))}))))
                   {} entries)
     :paths []}))

(defn pod-manifest
  "A pod's `manifest.edn`.

  A pod contributes NO SOURCE -- it is a separate process with its own
  authority -- so `:paths` is empty whatever the manifest says. What it may
  declare is `:deps`, and those must be PODS: see
  `DECISIONS.md#pods-are-a-resolvable-dependency`. A coordinate of any other
  kind is dropped here and reported by the walk, rather than silently pulling
  a compiler's worth of source in behind a subprocess."
  [text]
  (let [m (edn/read-string text)]
    {:format :pod
     :deps (or (:deps m) {})
     :artifacts (or (:pod/artifacts m) [])
     :paths []}))

(def formats
  "EVERY manifest format flint reads, and how to find one.

  ONE LIST. `:files` is a function of the dependency's NAME because Maven is
  the case that needs it: a jar carries its own POM at
  `META-INF/maven/<group>/<artifact>/pom.xml`, which is the coordinate spelled
  as a path. Every other format is a fixed file name.

  Which formats a KIND may carry lives in `flint.deps/coord-types` beside the
  kinds themselves, so adding a kind forces an answer to what it carries."
  [{:format :deps-edn
    :files (fn [_] ["deps.edn"])
    :read deps-edn}
   {:format :package-json
    :files (fn [_] ["package.json"])
    :read package-json}
   {:format :pom
    :files (fn [nm]
             (let [s (str nm)
                   i (str/index-of s "/")
                   group (if i (subs s 0 i) s)
                   artifact (if i (subs s (inc i)) s)]
               ["pom.xml"
                (str "META-INF/maven/" group "/" artifact "/pom.xml")]))
    :read pom-xml}
   {:format :pod
    :files (fn [_] ["manifest.edn"])
    :read pod-manifest}])

(defn- format-named [f]
  (some (fn [t] (when (= f (:format t)) t)) formats))

(defn scan
  "The first manifest present under `dir`, read.

  `order` is the format precedence for this dependency's kind -- see
  `flint.deps/coord-types`' `:manifests`. Two rules produce that order and they
  do not conflict: a LOCAL reference names its ecosystem in how it is written,
  so there is nothing to guess; a FETCHED package had to satisfy some
  registry's format and may say more than that registry understands, so
  `deps.edn` comes first and the ecosystem's own file after it.

  `slurp*` is the same project reader the rest of the walk uses: a path to its
  text, or nil. Answers nil when nothing is there, which is legal -- a git
  repository of plain cljc has no manifest at all."
  [slurp* dir nm order]
  (some (fn [f]
          (let [t (format-named f)]
            (when t
              (some (fn [rel]
                      (let [text (slurp* (str dir "/" rel))]
                        (when text
                          (try ((:read t) text)
                               ;; A MANIFEST THAT DOES NOT PARSE IS NOT A
                               ;; MANIFEST. Refusing the build over somebody
                               ;; else's malformed `package.json` would make
                               ;; flint's build depend on a file flint does not
                               ;; use for anything else.
                               (catch Throwable _
                                 {:format (:format t) :deps {} :paths []
                                  :unreadable (str dir "/" rel)})))))
                    ((:files t) nm)))))
        order))
