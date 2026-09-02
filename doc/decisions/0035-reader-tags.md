# 0035 — A reader tag is a name; the var it names is the identity

> **PARTLY BUILT.** An unknown reader tag in source is an error, as in canonical
> Clojure. Tags are bound per project in `deps.edn` under `:flint/tag-readers`,
> mapping a tag NAME to the VAR that reads it, and apply only to that project's
> own source roots -- so two libraries can both want `#x`. `#x form` rewrites to
> `(the-var form)` carrying `:flint/read-form`, `:flint/read-tag`,
> `:flint/read-var` and its position. `#flint/table` is built in.
>
> The SDK carries the same map: `workspaces: [{prefix, name, tags}]` on
> `compile`, or a resolver answering `{source, workspace, tags}`. It rides the
> namespace resolver of `0036`, which is what step 3 was actually blocked on.
>
> NOT built: `reader-tag-of`, so a printer can ask what name this build bound to
> its reader.

## What is true today

`#foo/bar form` in source is an **error**. It used to build a tagged literal out
of any tag at all, which is not what Clojure does and is worse than it sounds: a
mistyped tag — `#inst` for `#instant`, a namespace misremembered — read as a
perfectly good value and failed somewhere else entirely, or quietly did the
wrong thing. A tag is a *request for a reader*, not permission to invent a value.

Two things still work and are the two things that should:

* **`(tagged-literal 'a/b form)`** makes one as a value.
* **`clojure.edn/read-string`** with `:readers` or `:default` reads one from
  data, and has always refused an unknown tag without `:default`.

So the type from `0034` is intact; what is gone is the reader inventing one.

## The problem with Clojure's answer

Clojure registers tags in `data_readers.clj` at the root of the classpath, and
the registry is **global**. Two libraries that both want `#inst` collide. A
project cannot rename a tag it finds too long. A dependency's tags are in scope
for your source whether you asked for them or not, and yours are in scope for
its. There is one namespace of tag names and everybody is in it.

## A tag is a NAME; the VAR is the identity

This is the whole design, and it is what the first draft of this file got wrong.

    ;; deps.edn
    {:paths ["src"]
     :flint/tag-readers {table  flint.table/read-table
                         x      lib.a/read-x
                         y      lib.b/read-x}}

A binding maps a **tag name** to a **var**. The name is short, unqualified,
convenient, and *not unique*. The var is fully qualified and therefore unique.
Everything follows from keeping those two apart.

The bindings apply **only when reading source under this project's own roots**.
A dependency is read with *its* `deps.edn`'s bindings, not with yours. Using a
library's tag is opt-in, renaming one is local, and two libraries that both want
`#x` no longer collide — the root project binds one of them to `#y` and both
readers stay reachable.

## Why the first draft was wrong about printing

The first draft said: printing always emits the canonical tag, because an alias
is a read-side convenience and identity is what prints — the way
`(:require [clojure.string :as s])` still prints `clojure.string/join`.

**That analogy only holds for qualified names.** `clojure.string` is globally
unique, so an alias for it is pure convenience. `#x` is a bare name that two
libraries can both claim, so "the canonical name" is not even well defined for
it. And the failure is not cosmetic: if library A uses `#x`, and the root project
rebinds A's reader to `#y` because library B also wanted `#x`, then a value of
A's printing itself as `#x` produces a form which, read back **in that same
project**, calls B's reader. Silently, and with a plausible-looking result. That
is worse than an unreadable form.

So printing must ask the same question reading answers: *in this program, what
name is bound to this reader?*

## What prints, then

**The program has one answer, fixed at build time.** A compiled module is
produced by one root project, and that project's bindings are what its own
source reads with — so they are also what a printed form should be readable by.
A `Printable` implementation names its reader VAR, and the build resolves that
to the name bound to it:

    (extend-protocol clojure.core/Printable
      :table (print-data [t]
               (str "#" (reader-tag-of #'read-table) " " (pr-str (vec (rows t))))))

`reader-tag-of` is resolved **at compile time** to a literal string, because the
build knows the whole map and the answer cannot change while the program runs.
It costs no runtime lookup, no registry in the image, and no bytes — which
matters, since `0026` has already had one printing branch cost 15 832 bytes of
floor.

That also settles a question the first draft answered badly. "Which project is
current?" has no runtime answer — a program is assembled from many projects and
a value does not know who is looking at it. But it has a perfectly good *build
time* answer, and that is the only one needed.

**The library declares the default** in its own `deps.edn`, using the same key:
`{:flint/tag-readers {flint/table flint.table/read-table}}`. That is not a second
mechanism; a library's own declaration is simply the binding used when nobody
overrides it. Resolution is: the root project's map, else the defining library's.

**The wire is not source.** `0025`'s codec and `0033`'s formats identify a type
by its own tag numbering, not by a source-level name, so a project-local rename
cannot affect what crosses a port or lands in a file read by another program.
Renameable at the source layer, fixed at the wire layer, and the two do not have
to agree because they are answering different questions.

## The reader runs nothing

Worth stating because the first draft of this file talked itself into a
bootstrapping problem that does not exist.

`#x form` is **rewritten** to `(lib.a/read-x form)`. The reader does not call
anything, does not need the var to be loaded, and does not need a compiler in
scope. It emits a form, and the ordinary pipeline takes it from there:

* if the var is a **macro**, it expands at compile time and can fold the whole
  literal to a constant — which is what `#flint/table` will want (`0026` step 9);
* if it is a **function**, it is an ordinary call evaluated when that code runs.

The reader does not have to know or care which, and there is nothing to
bootstrap: the thing being named is resolved by the compiler, exactly as every
other symbol in the file is.

## The rewrite must remember what it was

A rewrite that forgets its origin reports errors against code nobody wrote. So
the emitted form carries the original:

    ^{:flint/read-form #x [1 2]        ; the tag AS WRITTEN, as a 0034 value
      :flint/read-var  lib.a/read-x}   ; what it resolved to
    (lib.a/read-x [1 2])

`:flint/read-form` is a **tagged literal value**, not a string, because `0034`
already has that type and it is precisely "the form as read": the tag stays a
symbol, the form stays data, and `pr-str` gives back `#x [1 2]` character for
character. It records the name **as written** — `#x`, the project's binding —
because that is what the person typed and what they will search for. The var is
recorded beside it, because the two errors that matter name different things:
"no reader bound to `#x`" names the tag, and "the reader `lib.a/read-x` threw"
names the var.

**This codebase has already had this exact bug one layer down.** Reader
conditionals used to relabel their result with the position of the `#?`, so
every check failure inside one pointed at the conditional and could not find its
arguments. The rule that fixed it is the rule here: *a form which already knows
where it came from does not get relabelled by whatever it came out of.* A tag
rewrite is a bigger version of the same rewrite, and gets the same treatment.

**The read form carries its own position**, and the derived form keeps one too.
Those are not in competition, because they answer different questions, and this
codebase renders errors in a way that makes the distinction sharp.

`flint.check`'s `caret` says it outright: *"The source line is not embedded --
the FORM is, and it prints back."* Nothing here slices text out of a file by
offset. A failure renders as a `file:line:column` header plus the FORM printed
back, with carets placed by arithmetic on the columns the reader recorded for
sub-forms. So a position is a POINTER, and the rendered shape comes from the
form itself. Two separate jobs, and a tag rewrite gets them right differently:

* **Position: keep it on the derived form.** `(lib.a/read-x [1 2])` came from
  the `#x` at line 12, and saying so is true in the only sense a pointer is ever
  true -- it is the same thing a macro expansion inheriting its call site's
  position means. Dropping it does not buy honesty, it buys an error with NO
  position, and every consumer that reads `:line` would need teaching the
  fallback separately. That is the `#?` bug's shape again: a position that is
  merely imprecise degrades far better than one that is absent.
* **Shape: take it from `:flint/read-form`, PREFERENTIALLY.** This is the half
  that is genuinely a lie otherwise. Rendering `(lib.a/read-x [1 2])` back to
  somebody who wrote `#x [1 2]` shows them code they did not write and cannot
  search for. So a renderer that finds `:flint/read-form` should print THAT --
  not only when position metadata is missing, but whenever it is there, because
  it is strictly the more faithful answer.
* **Synthetic sub-forms must not claim columns.** `lib.a/read-x` is not in the
  file and has no column; `[1 2]` is and does. `caret` computes its offset as
  `(- arg-col pred-col)`, so a head symbol carrying an invented column would put
  the carets under the wrong thing -- confidently. It must carry none, and the
  renderer must degrade to no carets, which it already does for the case of a
  number that cannot hold metadata.

And the weaker rule holds as a floor: a consumer with no `:line` at all should
look for `:flint/read-form` and use ITS position, which is why that value carries
`:line`, `:column` and `:file` of the `#x` itself rather than being bare data.

**It has to survive expansion.** If the reader var is a macro, the expansion
replaces the form, and an error inside the expansion would otherwise point at
generated code. So an expansion inherits `:flint/read-form` from the form it
came from unless it carries its own — the same inheritance rule, applied at the
next layer.

It costs nothing at run time: this is metadata on a FORM, and forms do not ship.
Worth saying only because metadata attached unconditionally has cost this
project 3 578 bytes of shipped module once already (`0032`), and the reason it
is free here is specifically that a form is not a value.

## The SDK

**Done.** `deps.edn` is the CLI's project file, and the SDK does not read one.

The answer was not a second option carrying `{tag-name -> var}`, and this file
proposing one was the mistake underneath the delay: it would have been a second
route to the same fact, which is how the two front doors got out of step in the
first place. What landed instead is the NAMESPACE RESOLVER of `0036` — one
function answering what a namespace is, `{:src :file :workspace :tags}`, which
the CLI builds from source roots and the SDK builds from a path map. Tags are
one of the things a workspace carries, not a thing threaded beside it.

    compile({ files, workspaces: [{prefix: 'foo/', name: 'foo/bar',
                                   tags: {x: 'foo.a/read-x'}}], fn })

The proof is the case that distinguishes bound-per-workspace from bound-globally
rather than either alone: two workspaces binding the SAME tag name to different
readers, compiled together, each source reading under its own. It is in the SDK
selftest, with the companion assertion that a tag no workspace binds is still
refused — so the pass is not tags appearing from nowhere.

One bug came out of it, and it is the one this file already names in step 2: the
selfhost path handed the compiler `:src` and `:file` only, so a tag known to
`collect` was unknown to the third reader, exactly as `default-features`
records for `:features`.

## What is undecided

* Whether a project may **shadow a built-in** — bind `#flint/table` to something
  else. Under this design that is now coherent rather than dangerous, since
  printing follows the binding. I still lean no, on the grounds that a build
  where the language's own literals mean something else is a debugging problem
  nobody needs, but it is no longer a correctness argument.
* Whether an unbound reader var should be a build error when a `Printable`
  implementation asks for its name, or should fall back to the qualified var
  name. I lean **error**: a type that can print but cannot be read back is
  exactly the situation this file exists to prevent.

## Order

1. The refusal, with a message that names the tag and says how to get a value
   and how to read data. **Done.**
2. `:flint/tag-readers` in `deps.edn`, threaded to the reader for that project's
   roots only, with a dependency read under its own. `#x form` rewrites to
   `(var form)`. **Done.** `test/tags.clj` is the case the mechanism exists for:
   two projects each bind `#pt` to their own reader, each source reads under its
   own, and a tag a dependency binds is NOT in scope for the project requiring
   it. The threading had to reach THREE readers -- `collect`, `topo-order` and
   the compiler -- which is the same defect `default-features` records for
   `:features`: a value only one reader knows about is a value the other two get
   wrong.
3. The SDK's equivalent of the `deps.edn` key. **Done**, as the namespace
   resolver of `0036` rather than as an option of its own — see "The SDK" above
   for why that is the better answer and not merely a different one.
4. `reader-tag-of`, resolved at build time.
   The rewrite carries `:flint/read-form` and `:flint/read-var`, and macro
   expansion inherits them; asserted by a test that a bad tagged literal reports
   against `#x` and not against the call it became. `flint.check`'s renderer
   prefers `:flint/read-form` for the shape it prints, and the derived call's
   head symbol carries no column so the carets do not point confidently at the
   wrong thing.
4. `#flint/table` as a default binding, with `0026` step 9's constant support,
   so a table's printed form reads back.
