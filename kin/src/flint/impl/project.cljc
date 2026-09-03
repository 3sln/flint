(ns flint.impl.project
  "This project, described to kin.

  kin is a LIBRARY. It has no command-line tool and no config file of its own,
  so the thing that says `these are my vocabularies and these are my targets`
  is ordinary code, and it lives here rather than in kin -- along with the
  scripts in `kin/` that call it.

  This replaced a `kin.edn`. A target carries a `:vfs` and a namespace-to-path
  function, and neither of those can be written in EDN."
  (:require [clojure.string :as str]
            [kin.project :as kp]
            [kin.vfs :as vfs]
            [flint.impl.targets :as targets]))

(def project
  (delay
    (kp/resolve-exports
     (kp/load-project
     {:vocabularies '[flint.impl.rt flint.impl.hash flint.impl.host]
      :targets targets/targets
      ;; The order every report lists them in. A map's keys have an accidental
      ;; order and a reader diffing two runs should not be reading a
      ;; reordering.
      :target-order [:rust :java :csharp]
      ;; THE SOURCES GET A VFS OF THEIR OWN, and it is the one that lists.
      ;; Scanning for `kin/*.kin` used to be `for src in kin/*.kin` in four
      ;; separate shell scripts here, each with its own idea of the ordering
      ;; and of what to do when one failed.
      ;; `:label` and `:unlabel` bridge two naming conventions that both
      ;; predate the vfs: the vfs is rooted at `kin/` and answers
      ;; `champ.kin`, and the marker written into three runtimes says
      ;; `kin/champ.kin`. Changing the markers would rewrite every generated
      ;; region in the tree to save four characters here.
      :sources {:vfs (vfs/disk-vfs "kin")
                :match "*.kin"
                :label #(str "kin/" %)
                :unlabel #(str/replace % #"^kin/" "")}}))))
