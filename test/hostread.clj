;; THE READER, for a babashka test (`DECISIONS.md#one-reader-and-no-other`).
;;
;; The compiler reads no text, and there is no reader in `src/` any more: every
;; host reads source with the one kin-generated reader and hands the compiler
;; `flint.forms` bytes. Babashka can load the compiler's Clojure but cannot call
;; the generated reader, so a babashka test that compiles in-process asks
;; `host/read-forms.mjs` -- the same reader, as the wasm module the JavaScript
;; SDK uses -- one batch at a time.
;;
;;     (load-file "test/hostread.clj")
;;     (hostread/read-texts [{:file "app.cljc" :text "(ns app)" :dialect :portable}])
;;       => [{:preread <bytes> :dialect :portable}]  or  [{:read-error {..}}]
(ns hostread
  (:require [cheshire.core :as json] [clojure.string :as str]
            [babashka.process :as p]))

(def ^:private script
  (str (.getParentFile (.getParentFile (.getAbsoluteFile (java.io.File. ^String *file*))))
       "/host/read-forms.mjs"))

(defonce ^:private server (atom nil))

(defn- ask!
  "One batch, as a JSON line, to a `host/read-forms.mjs --serve` started on
  first use and kept for the life of this process; its one-line answer."
  [line]
  (locking server
    (when-not @server
      (let [proc (p/process ["node" script "--serve"] {:err :inherit})]
        (reset! server {:proc proc
                        :w (java.io.BufferedWriter. (java.io.OutputStreamWriter. (:in proc) "UTF-8"))
                        :r (java.io.BufferedReader. (java.io.InputStreamReader. (:out proc) "UTF-8"))})))
    (let [{:keys [w r]} @server]
      (.write ^java.io.Writer w ^String line)
      (.write ^java.io.Writer w "\n")
      (.flush ^java.io.Writer w)
      (or (.readLine ^java.io.BufferedReader r)
          (throw (ex-info "host/read-forms.mjs --serve ended without answering" {}))))))

(defn read-texts
  "Each `{:file :text :dialect :tags :features}` READ: `{:preread bytes
  :dialect d}` -- what a resolver answer carries, so it goes straight into a
  `files-resolver` map or a resolver's answer -- or `{:read-error {:message
  :line :column}}`. `:features` nil (the default) reads DEFERRED; `:tags` is a
  map of tag symbol to var symbol, in the workspace's order."
  [entries]
  (if (empty? entries)
    []
    (let [in (json/generate-string
              (mapv (fn [{:keys [file text dialect tags features]}]
                      {:file (str file) :text text
                       :dialect (name (or dialect :portable))
                       :tags (mapv (fn [[t v]] [(str t) (str v)]) tags)
                       :features (when features (mapv (fn [f] (str f)) (sort-by str features)))})
                    entries))
          out (ask! in)]
      (mapv (fn [e {:keys [dialect]}]
              (if-let [err (get e "error")]
                {:read-error {:message (get err "message") :line (get err "line") :column (get err "column")}}
                {:preread (.decode (java.util.Base64/getDecoder) ^String (get e "forms"))
                 :dialect (or dialect :portable)}))
            (json/parse-string out) entries))))

(defn dialect-of [path] (if (str/ends-with? (str path) ".fln") :flint :portable))

(defn read-files
  "A `flint.compiler.resolve/files-resolver` map of `path -> text`, READ:
  `path -> {:preread bytes :dialect d}`, each under the tags of the first
  workspace in `workspaces` whose `:prefix` it starts with -- the lookup
  `file-answer` makes -- and its extension's dialect."
  ([files] (read-files files nil))
  ([files workspaces]
   (let [paths (vec (keys files))
         ws-of (fn [path] (first (filter (fn [w] (let [pre (:prefix w)]
                                                   (or (nil? pre) (= "" pre)
                                                       (str/starts-with? (str path) (str pre)))))
                                         (or workspaces []))))
         read (read-texts (mapv (fn [path] {:file path :text (get files path)
                                            :dialect (dialect-of path)
                                            :tags (:tags (ws-of path))})
                                paths))]
     (zipmap paths read))))
