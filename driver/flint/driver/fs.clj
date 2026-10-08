(ns flint.driver.fs
  "The handful of `babashka.fs` calls `bin/flint` used, over `java.nio.file`
  and `java.io.File` instead: this driver is JVM Clojure, not babashka
  (`DECISIONS.md#namespaces-over-the-system-port`, migration step 1.2), and
  `babashka.fs` is a new external dependency this file would otherwise need
  only to keep ten call sites spelled the way they already were. Narrow by
  design -- it answers exactly the calls `bin/flint` makes, not `babashka.fs`'s
  whole surface."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn parent [p]
  (when p (.getParentFile (io/file (str p)))))

(defn absolutize [p]
  (.getAbsoluteFile (io/file (str p))))

(defn exists? [p]
  (.exists (io/file (str p))))

(defn regular-file? [p]
  (.isFile (io/file (str p))))

(defn create-dirs [d]
  (when d (.mkdirs (io/file (str d)))))

(defn create-temp-file [{:keys [suffix]}]
  (doto (java.io.File/createTempFile "flint" suffix)
    (.deleteOnExit)))

(defn create-temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "flint" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn delete-if-exists [p]
  (.delete (io/file (str p))))

(defn set-posix-file-permissions [p perms]
  (try
    (java.nio.file.Files/setPosixFilePermissions
     (.toPath (io/file (str p)))
     (java.nio.file.attribute.PosixFilePermissions/fromString (str perms)))
    (catch UnsupportedOperationException _
      ;; No POSIX permission bits on this filesystem (Windows). The file
      ;; still exists; it just cannot be marked executable here.
      nil)))

(defn glob
  "Every file under `dir` whose name ends with one of the extensions in
  `pattern`, which is always `\"**.{ext1,ext2,..}\"` here -- the one shape
  `bin/flint` asks for (every source extension, recursively). Not a general
  glob: narrower than `babashka.fs/glob` on purpose, matching its one call
  site rather than its whole syntax."
  [dir pattern]
  (let [exts (when-let [m (re-find #"\{([^}]*)\}" (str pattern))]
               (str/split (second m) #","))
        d (io/file (str dir))]
    (if (and (.isDirectory d) (seq exts))
      (->> (file-seq d)
           (filter (fn [f] (and (.isFile f)
                                (some (fn [e] (str/ends-with? (.getName f) (str "." e))) exts))))
           (map (fn [f] (.toPath f))))
      [])))
