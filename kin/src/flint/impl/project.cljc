(ns flint.impl.project
  "This project, described to kin.

  kin is a LIBRARY. It has no command-line tool and no config file of its own,
  so the thing that says `these are my vocabularies and these are my targets`
  is ordinary code, and it lives here rather than in kin -- along with the
  scripts in `kin/` that call it.

  This replaced a `kin.edn`. A target carries a `:vfs` and a namespace-to-path
  function, and neither of those can be written in EDN."
  (:require [kin.project :as kp]
            [flint.impl.targets :as targets]))

(def sources-dir "kin")

(def project
  (delay
    (kp/load-project
     {:vocabularies '[flint.impl.rt flint.impl.hash]
      :targets targets/targets
      ;; The order every report lists them in. A map's keys have an accidental
      ;; order and a reader diffing two runs should not be reading a
      ;; reordering.
      :target-order [:rust :java :csharp]})))

(defn sources
  "`{path text}` for every kin source in the tree."
  []
  (into (sorted-map)
        (for [f (sort (.listFiles (java.io.File. sources-dir)))
              :when (.endsWith (.getName f) ".kin")]
          [(str sources-dir "/" (.getName f)) (slurp (str f))])))

(defn source-text
  "One source's text, by path."
  [path]
  (slurp path))
