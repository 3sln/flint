(ns demo.sysdemo
  "The other half of a CLI: the namespaces a program calls back out to while it
  runs. A compile that resolves `flint.sys.fs` and a run that cannot reach it
  look identical from the outside, so this asks the host real questions.

  The three `escape=` lines are the containment check, written as a PROGRAM
  because an access check fails open (AGENTS.md §5): reading `under()` and
  finding it sensible is not evidence, since the sensible-sounding version is
  the one that gets written. Each is paired with a control one line away that
  differs in exactly one thing, so a refusal for an unrelated reason -- a typo
  in the path, a missing grant -- cannot be mistaken for the check working."
  (:require [flint.sys.fs :as fs]
            [flint.sys.env :as env]))

(defn main [args caps]
  (let [dir (first args)]
    (str "caps=" (pr-str (sort (map name (keys caps)))) "\n"
         "argc=" (count args) "\n"
         "cwd-is-string=" (pr-str (string? (env/cwd))) "\n"
         ;; The CONTROL for the three refusals below: an ordinary relative path
         ;; under the root, which must be read.
         "control-read=" (pr-str (boolean (re-find #"ns demo.util"
                                                   (fs/read-file (str dir "/demo/util.cljc")))))
         "\n"
         "exists=" (pr-str (fs/exists? (str dir "/demo/main.cljc"))) "\n"
         "missing=" (pr-str (fs/exists? (str dir "/demo/nope.cljc"))) "\n"
         "dir?=" (pr-str (fs/dir? (str dir "/demo"))) "\n"
         "listed=" (pr-str (mapv :name (fs/list-dir (str dir "/demo")))) "\n"
         ;; LEAVES the root.
         "escape-dotdot=" (try (fs/read-file "../../../../etc/passwd") "READ"
                               (catch Exception e "refused")) "\n"
         ;; NORMALISES back inside the root, and is still refused: popping `..`
         ;; would make `a/../../x` depend on how deep `a` was.
         "escape-inside=" (try (fs/read-file (str dir "/demo/../demo/util.cljc")) "READ"
                               (catch Exception e "refused")) "\n"
         ;; ABSOLUTE, which is not relative to the root whatever it resolves to.
         "escape-absolute=" (try (fs/read-file "/etc/passwd") "READ"
                                 (catch Exception e "refused")) "\n"
         ;; THE SAME ANSWER FOR A FILE THAT IS NOT THERE. A probe that behaved
         ;; differently for a missing file would leak whether it was there.
         "escape-absent=" (try (fs/read-file "../../../../etc/definitely-not-here") "READ"
                               (catch Exception e "refused")) "\n"
         ;; Writing is a SEPARATE grant, and `:with [fs]` is not it.
         "write=" (try (fs/write-file "flint-should-not-exist.txt" "x") "WROTE"
                       (catch Exception e "refused")) "\n")))
