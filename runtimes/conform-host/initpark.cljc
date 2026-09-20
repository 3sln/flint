(ns initpark
  "A TOP-LEVEL FORM THAT ASKS THE HOST, which is the one thing a sandbox may
  not do.

  `flint.host/request` raises an event and PARKS the calling thread until the
  host answers. At top level there is nobody to come back to: the initialiser
  loop is not re-entrant, so there is no saved position to resume, and the park
  can never be satisfied. `DECISIONS.md#ports-are-the-hosts` says a sandbox that
  cannot ask is TOLD so rather than parked, and this is that sentence one phase
  earlier -- at the form that asked, rather than at a downstream symptom.

  Native says it. The two ports did not, which is what this fixture measures:
  the same image on all three, and a transcript that shows what each does with
  a park nothing can resume.

  The sibling `hostreq.cljc` makes the SAME call from inside `main`, where it
  is entirely legal and gets answered. The pair is the point: what separates
  them is not the call but WHERE it is, and only a fixture that keeps
  everything else equal can say so."
  (:require [flint.host :as host]))

;; TOP LEVEL, and READ by `main`, or the shake drops it and this fixture passes
;; by never running the thing it is about -- the trap `test/sysns.clj` names
;; against the same shape.
(def at-load (host/request "clock" ["utc"]))

(defn main [_] (str "at-load=" (pr-str at-load)))
