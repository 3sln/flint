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
            [clojure.string :as str]))

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

(defn json-member
  "The TEXT of the top-level member named `k`, or nil.

  Top level only, and that is the point: `\"dependencies\"` nested inside some
  tool's own configuration block is not this package's dependency list, and a
  scan that took the first match anywhere would pick it up."
  [s k]
  (let [open (str/index-of s "{")]
    (when open
      (loop [j (skip-space s (inc open))]
        (if (or (>= j (count s)) (= "}" (subs s j (inc j))))
          nil
          (if-not (= "\"" (subs s j (inc j)))
            nil                                     ; not an object member: give up
            (let [ke (string-end s j)
                  nm (subs s (inc j) (dec ke))
                  colon (skip-space s ke)
                  vstart (skip-space s (inc colon))
                  vend (value-end s vstart)]
              (if (= nm k)
                (subs s vstart vend)
                (let [after (skip-space s vend)]
                  (if (and (< after (count s)) (= "," (subs s after (inc after))))
                    (recur (skip-space s (inc after)))
                    nil))))))))))

(defn json-string-map
  "A JSON object of string to string, as a Clojure map. Anything whose value is
  not a string is dropped rather than guessed at."
  [s]
  (if (or (nil? s) (not (str/starts-with? (str/trim (str s)) "{")))
    {}
    (let [s (str/trim (str s))]
      (loop [j (skip-space s 1) out {}]
        (if (or (>= j (count s)) (not= "\"" (subs s j (inc j))))
          out
          (let [ke (string-end s j)
                k (subs s (inc j) (dec ke))
                colon (skip-space s ke)
                vstart (skip-space s (inc colon))
                vend (value-end s vstart)
                v (subs s vstart vend)
                out (if (str/starts-with? v "\"")
                      (assoc out k (subs v 1 (dec (count v))))
                      out)
                after (skip-space s vend)]
            (if (and (< after (count s)) (= "," (subs s after (inc after))))
              (recur (skip-space s (inc after)) out)
              out)))))))

;; ------------------------------------------------------------- XML, narrowly

(defn- strip-comments [s]
  (loop [t (str s)]
    (let [i (str/index-of t "<!--")]
      (if (nil? i)
        t
        (let [j (str/index-of t "-->" i)]
          (recur (str (subs t 0 i) (if j (subs t (+ j 3)) ""))))))))

(defn elements
  "The inner text of every `<tag>...</tag>` in `s`, at any depth, in order.

  Same-name nesting is not handled and does not occur in the elements this
  reads (`dependency`, `groupId`, `version`); `dependencies` DOES nest inside
  `dependencyManagement`, which is why the caller strips that block first
  rather than asking this to understand it."
  [s tag]
  (let [open (str "<" tag ">") close (str "</" tag ">")]
    (loop [from 0 out []]
      (let [i (str/index-of s open from)]
        (if (nil? i)
          out
          (let [start (+ i (count open))
                j (str/index-of s close start)]
            (if (nil? j)
              out
              (recur (+ j (count close)) (conj out (subs s start j))))))))))

(defn element
  "The inner text of the FIRST `<tag>` in `s`, trimmed, or nil."
  [s tag]
  (let [xs (elements s tag)]
    (when (seq xs) (str/trim (first xs)))))

(defn- drop-blocks
  "`s` with every `<tag>...</tag>` removed."
  [s tag]
  (let [open (str "<" tag ">") close (str "</" tag ">")]
    (loop [t s]
      (let [i (str/index-of t open)]
        (if (nil? i)
          t
          (let [j (str/index-of t close i)]
            (if (nil? j)
              (subs t 0 i)
              (recur (str (subs t 0 i) (subs t (+ j (count close))))))))))))

;; --------------------------------------------------------------- the formats

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
                   (assoc m (symbol (key e)) {:npm/name (key e) :npm/version (val e)}))
                 {} (json-string-map (json-member text "dependencies")))
   :paths []})

(def ^:private skipped-scopes
  "A dependency of the POM's own build, not of anything consuming it.
  `provided` and `system` are supplied by the container, `test` by the test
  run, and `import` is a `dependencyManagement` device rather than an edge."
  #{"test" "provided" "system" "import"})

(defn- child-elements
  "Every immediate `<name>text</name>` pair in `s`, as `{name text}`.

  `<properties>` is the reason this exists: the element names are the property
  names, so they cannot be looked for and have to be read off the open tags."
  [s]
  (loop [from 0 out {}]
    (let [i (str/index-of (str s) "<" from)]
      (cond
        (nil? i) out
        (= "</" (subs s i (min (count s) (+ i 2)))) (recur (inc i) out)
        :else
        (let [gt (str/index-of s ">" i)]
          (if (nil? gt)
            out
            (let [raw (subs s (inc i) gt)
                  sp (str/index-of raw " ")
                  nm (if sp (subs raw 0 sp) raw)
                  close (str "</" nm ">")
                  j (str/index-of s close gt)]
              (if (nil? j)
                (recur (inc gt) out)
                (recur (+ j (count close))
                       (assoc out nm (str/trim (subs s (inc gt) j))))))))))))

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
  (let [t (strip-comments (str text))
        props (child-elements (or (first (elements t "properties")) ""))
        ;; The POM's OWN version, read with the dependency blocks taken out so
        ;; a dependency's `<version>` cannot be mistaken for the project's.
        own-version (element (drop-blocks t "dependencies") "version")
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
        body (drop-blocks t "dependencyManagement")
        blocks (elements body "dependencies")
        entries (mapcat (fn [b] (elements b "dependency")) blocks)]
    {:format :pom
     :deps (reduce (fn [m d]
                     (let [g (element d "groupId")
                           a (element d "artifactId")
                           v (element d "version")
                           scope (element d "scope")
                           optional (element d "optional")]
                       (if (or (nil? g) (nil? a)
                               (contains? skipped-scopes (str scope))
                               (= "true" (str optional)))
                         m
                         (assoc m
                                ;; `group/artifact`, exactly as `deps.edn`
                                ;; keys a maven dependency -- and `group/group`
                                ;; when they are the same, which is what
                                ;; tools.deps writes too.
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
