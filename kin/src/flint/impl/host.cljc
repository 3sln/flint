(ns flint.impl.host
  "The HAND-WRITTEN runtime, exposed to kin as a namespace.

  This is the manual external-reference mechanism, and it is the whole of it:
  a kin namespace representing a unit kin did not write, whose forms generate
  the code to reach it. Nothing here is inferred and nothing is automatic --
  kin knows its own definitions and cannot know anyone else's, so where it
  does not know, a declaration is the only way it can learn.

  ## Why these three are separate from `flint.impl.rt`

  `flint.impl.rt` is a vocabulary: tags, primitives, heap access, the shape of
  a runtime call. What sat in it by accident was thirty `own` declarations for
  functions KIN ITSELF GENERATES -- one definition kept in two places, which
  is the drift exports exist to remove.

  These three are the genuine remainder: `bn_new`, `cn_set` and
  `cn_copy_set_val` are hand-written in all three runtimes and called from
  generated code. They are the entire external surface, and separating them
  makes that surface countable rather than mixed in with the rest.

  They are also the three that would break SILENTLY on extraction:
  `self.bn_new(...)` still resolves in Rust from another file in the same
  `impl`, while an unqualified `bnNew(...)` does not resolve in Java at all.

  ## This file is expected to shrink to nothing

  Each of the three is portable -- `cn_copy_set_val` is twenty-three lines and
  needs nothing kin lacks. Once they are ported the external surface is zero,
  and what remains here is the mechanism rather than any user of it."
  (:require [kin]
            [flint.impl.rt :as rt]))

(def vocabulary
  (kin/vocabulary
   :namespace 'flint.impl.host
   :targets #{:rust :java :csharp}
   :forms
   {;; A bitmap node, allocated and stamped with its two maps.
    'bn-new (rt/own "bn_new" "bnNew" 3)
    ;; A collision node copied with one value replaced.
    'cn-copy-set-val (rt/own "cn_copy_set_val" "cnCopySetVal" 4)}))
