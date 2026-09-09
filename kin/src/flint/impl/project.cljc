(ns flint.impl.project
  "This project, described to kin.

  kin is a LIBRARY. It has no command-line tool and no config file of its own,
  so the thing that says `these are my vocabularies and these are my targets`
  is ordinary code, and it lives here rather than in kin -- along with the
  scripts in `kin/` that call it.

  This replaced a `kin.edn`. A target carries a `:vfs` and a namespace-to-path
  function, and neither of those can be written in EDN."
  (:require [clojure.string :as str]
            [kin.host :as host]
            [kin.project :as kp]
            [kin.vfs :as vfs]
            [flint.impl.targets :as targets]))

(def project
  (delay
    (kp/resolve-exports
     (kp/load-project
     {:vocabularies targets/vocabularies
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
      ;; WHAT THE HAND-WRITTEN RUNTIMES DECLARE ABOUT THEMSELVES. One scan per
      ;; target, because a tree speaks one language and its comment prefix is
      ;; that language's -- `kin.host` refuses a scan with no `:target` for
      ;; exactly this reason.
      ;;
      ;; A host file names its OWN namespace, so a module hand-written in
      ;; three runtimes is a module like any other: `flint.rt.vector` is
      ;; required the same way `flint.rt.vecread` is, and a caller cannot tell
      ;; which of the two kin generated. That is the whole point -- the
      ;; alternative was one catch-all vocabulary standing in for "the host",
      ;; which is a list that has to be maintained by hand and drifts from the
      ;; code it describes.
      :host (host/interpret
             targets/targets
             [(host/scan {:vfs (vfs/disk-vfs "runtime/src") :match "*.rs"
                          :target :rust :comment "//"})
              (host/scan {:vfs (vfs/disk-vfs "runtimes/jvm/src/com/flint/rt")
                          :match "*.java" :target :java :comment "//"})
              (host/scan {:vfs (vfs/disk-vfs "runtimes/clr/src/rt")
                          :match "*.cs" :target :csharp :comment "//"})])
      :sources {:vfs (vfs/disk-vfs "kin")
                :match "*.kin"
                :label #(str "kin/" %)
                :unlabel #(str/replace % #"^kin/" "")}}))))
