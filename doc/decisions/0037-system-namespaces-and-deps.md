# 0037 — System access and dependencies are virtual namespaces the CLI serves

> **PARTLY BUILT.** Virtual namespaces, `flint.sys.fs`, `flint.sys.env`,
> `flint.sys.slurp`, `flint.deps.npm`, `flint.deps.mvn`, `flint.deps.git`, the
> `.cljc` plan, `flint deps add`, capability delegation and PODS are in. The
> rest of `flint deps`, `flint.sys.net`/`proc`/`clock` and deleting the old
> babashka path are not.
> See "What is built" at the bottom.

> **What this banner used to say.** *"NOT BUILT — a proposal. Nothing in this
> file exists yet, and it is blocked on `0036` steps 4–6."* That blocker was
> built first, as this file's order said it had to be.

## What is wrong with what we have

Three separate things, and they have the same shape.

**`flint.fs` looks like a language namespace and is not one.** It ships in
`lib/`, so it is in flint's own workspace, beside `clojure.core` and
`flint.port`. Nothing in the tree says that the thing on the other end of it is
a decision the CLI made. A reader who finds `(fs/read-file h "x")` in a program
has no way to tell whether they are looking at flint or at one host's
convention, and the answer matters: a program written against it does not run on
a host that never implemented it.

**There is no networking at all.** Not a partial implementation — nothing. No
HTTP, no sockets, no `slurp` of a URL. A program that wants to read a
configuration file over HTTPS cannot.

**Dependency fetching is babashka shelling out.** `bin/flint` runs `git`,
`curl`, `tar` and `unzip` as subprocesses, and `lib/flint/deps.cljc` reports
what it cannot do rather than doing it: no transitive graph, no version
resolution, one jar at one exact version. The shipped binary — `cli/src/main.rs`,
2.6 MB, whose whole claim is "nothing to install" — has **no deps surface at
all**. `flint deps` exists only where babashka does.

So: a system surface that pretends to be part of the language, a missing one,
and a third that exists in the wrong binary.

## The shape: `flint.sys.*` and `flint.deps.*`, served over RPC

Everything here is a **virtual namespace** (`0036` step 4). The resolver flags
it, the compiler emits `flint.virtual/call` rather than a var reference, and a
library does the namespace-to-port resolution. The server is the CLI.

```clojure
(:require [flint.sys.fs :as fs])
(fs/list-dir "src")          ; compiles to a call over a port
```

**The name is the point.** `flint.sys.*` and `flint.deps.*` are not `flint.*`,
and a reader meeting one should be able to tell from the segment alone that it
is served rather than linked. Three properties follow from the namespace being
virtual, and all three are worth having:

* **It cannot be linked into a pure module.** There is no code to shake out,
  because there was never any code — a program that mentions `flint.sys.fs`
  and runs on a host that does not serve it fails when the port does not open,
  which is the honest failure. Today it would fail at a `p/open` buried three
  calls deep inside a library that looks like part of the language.
* **`flint inspect` can list what a program asks the world for**, because a
  virtual namespace is a require the artifact records rather than code it
  absorbed.
* **A different host can serve the same namespace differently**, and that is
  not a hole — it is what makes `flint.sys.fs` mean "the filesystem this host
  chose to lend" rather than "the filesystem".

`lib/flint/fs.cljc` is deleted and its callers move. It was the right idea in
the wrong place.

## The system namespaces, and the capability each one is

The split is **by authority, not by convenience**. Two operations belong in one
namespace when granting one would have been enough to do the other anyway.

| namespace | capability | what it is |
| --- | --- | --- |
| `flint.sys.slurp` | `:slurp` | bytes at a name: `file://`, `http://`, `https://`, `data:` |
| `flint.sys.fs` | `:fs` | hierarchy: list, stat, walk, mkdir, write, delete, rename |
| `flint.sys.net` | `:net` | requests with a method, headers and a body; later, sockets |
| `flint.sys.env` | `:env` | environment variables and process arguments |
| `flint.sys.proc` | `:proc` | subprocesses |
| `flint.sys.clock` | `:clock` | wall time; monotonic time is not authority and stays a builtin |

### Why `slurp` is separate from `fs`, and why one capability covers `http://`

**`slurp` is a key-value read and nothing else.** `(slurp "file:///etc/hosts")`
and `(slurp "https://example.com/x.json")` are the same question — *give me the
bytes at this name* — and a program that reads one configuration file should not
be holding the thing that can enumerate a disk. Splitting them is the whole
reason `:fs` is worth guarding: `:fs` exposes STRUCTURE and MUTATION, and a URL
fetch never does.

That `file://` and `https://` share a capability is a real decision and the
uncomfortable one, because they are not the same authority: a URL fetch sends
the URL to somebody. The answer is the one `0036` already settled for
`flint.host/request` — **the compile-time guard is coarse and the host answers
the specific question.** `:slurp` says "this workspace may read by name at all";
the CLI's grant carries the scheme and host allowlist:

```
flint run :with [slurp:file,https://registry.npmjs.org]
```

A guard that varied by scheme could not work anyway: the guard is checked where
the var is REFERENCED, and the URL is a run-time value. Pretending otherwise
would be a check that looks stronger than it is, which `0036` spends a section
refusing to do.

**Streaming is not in `slurp`.** `slurp` answers with the whole thing. A
namespace that sometimes returns bytes and sometimes returns a handle is two
namespaces wearing one name, and the streaming one wants a port rather than a
call — so it can be `flint.sys.stream` when something needs it, and until then
it is honestly absent rather than half-present.

### `:fs` is still rooted

Every `:fs` grant carries a root and every path resolves under it; an escape is
REFUSED and never clamped, because silently rewriting `../../etc/passwd` answers
a question nobody asked. That rule already exists in `host/fs.mjs` and moves
across unchanged — it is the one part of the current implementation that was
never in the wrong place.

## The dependency namespaces

| namespace | capability | what it is |
| --- | --- | --- |
| `flint.deps.npm` | `:deps` | versions, manifest, tarball |
| `flint.deps.mvn` | `:deps` | versions, POM, jar |
| `flint.deps.git` | `:deps` | refs, tags, semver resolution, fetch by sha |
| `flint.deps.pod` | `:deps` | list, boot, describe, invoke |

One capability for all four, because a build that may fetch from npm may fetch
from Maven: they are one authority — *reach out and bring code in* — and
splitting them would be four grants that are always given together.

`lib/flint/deps.cljc` is rewritten **as `.cljc` on top of these**, so the
resolver, the version arithmetic, the transitive walk and the lockfile are
flint code that runs anywhere, and only the fetching is Rust. That is the same
division `0021` argues for and the same one `lib/flint/cli.cljc` already has:
the logic is a flint program, the host does I/O.

### The protocol per namespace

`0036`'s three operations, unchanged:

```text
->  {:op :invoke :var f :args [...]}   <-  {:body v} | {:error {...}}
->  {:op :get    :var x}               <-  {:body v}
->  {:op :list}                        <-  {:body [{:name f :arities [...]}]}
```

Every one of these namespaces answers `:list`, so `flint deps` and `--explain`
can say what a build can actually reach, and so an unknown var in
`flint.sys.fs` is a COMPILE error rather than a run-time one. The CLI knows its
own surface, so there is no reason to take it on trust.

## Git: canonical coordinates, and a tool for when they disagree

`:git/tag` and `:git/sha`, exactly as canonical `deps.edn` has them.

**`:git/version` was built and then removed**, and the removal is the decision
worth recording because the addition looked obviously right. A semver range over
tags is the wrong shape for the problem twice over:

1. **It makes every build a resolution.** A range asks the network what the
   newest matching tag is, so what a checkout means depends on when it ran --
   which is the objection this project already raises to a git branch.
2. **It answers the wrong question.** What actually hurts is not naming a
   version; it is two dependencies naming DIFFERENT tags of one repository. A
   range does not resolve that, it just gives each of them its own way to be
   right.

So resolution happens ONCE, at `flint deps add`, which picks the highest version
tag and writes it down:

```clojure
{org/lib {:git/url "https://github.com/org/lib"
          :git/tag "v1.2.0"
          :git/sha "a1b2c3d..."}}
```

`:git/sha` is INTEGRITY, not identity: the tag must resolve to that commit or
the plan is refused, and a prefix compares as a prefix so the 7-character form
works.

### `flint deps agree`

The tool the removal makes necessary, and the thing the range was reaching for:

```text
$ flint deps agree
https://github.com/clojure/data.json
  v2.4.0  <- lib-a
  v2.5.2  <- lib-b
  => v2.5.2

$ flint deps agree --apply
wrote the agreed tags into :flint/overrides
```

Three properties, each deliberate:

* **URL spellings are folded.** `…/x`, `…/x.git` and `…/x/` are one repository.
  A detector that missed that would miss the conflicts that actually happen.
* **It proposes only a tag somebody ALREADY ASKED FOR.** It does not go looking
  for a newer one. Choosing a version no dependency requested is a decision
  nobody made, and the job here is agreement rather than upgrade.
* **It writes the sha with the tag**, so the result is pinned in the sense
  `0037` means -- which is the other half of the request: agree on a tag, then
  update the truncated sha to match.

Tags with no version in them -- a branch-like name -- are reported and NOT
resolved, because there is nothing to be right about and a guess is worse than
saying so.

## npm without a `package.json`

`deps.edn` is the source of truth. flint writes no `package.json` and reads none
of its own, because two files that both say what a project depends on is two
files that disagree.

```clojure
{:deps {npm/left-pad {:npm/version "^1.3.0" :npm/integrity "sha512-..."}}}
```

A fetched package's own `package.json` is READ — that is where its dependencies
and its entry point are — but it is an input, exactly like a POM, never
something flint maintains.

## Pins, and how they are kept

**Everything is pinned by default.** `flint deps add` writes the resolved exact
version AND the integrity field; nothing is left floating, because a build that
resolves differently tomorrow is not reproducible and nobody discovers that at a
convenient moment.

**Transitives are pinned into `:flint/overrides`**, in `deps.edn`, rather than
into a second lockfile:

```clojure
{:deps      {org/lib {:git/version "1.2.0" :git/sha "..."}}
 :flint/overrides
 {org/dep   {:git/version "3.1.4" :git/sha "..."}
  npm/other {:npm/version "2.0.1" :npm/integrity "sha512-..."}}}
```

One file, and the pins are in the same language as the declarations — so a
person can read a pin, edit it, and understand what it did. A separate lockfile
in a different format is a second thing to keep true, and `0035` is this
repository's record of what that costs.

Overrides are also the conflict escape hatch, which is why the pin lives there
rather than in a parallel structure: pinning a transitive and forcing a version
are the same operation, and they should not have two spellings.

## The `flint deps` surface

```text
flint deps add npm:left-pad              # resolve latest, pin, write
flint deps add mvn:org.clojure/data.json
flint deps add git:github.com/org/lib
flint deps add pod:org.babashka/postgresql

flint deps add npm:left-pad@^1.3.0       # a constraint, resolved and pinned

flint deps bump                          # every dep, within its constraint
flint deps bump :patch                   # …and no further
flint deps bump :minor
flint deps bump :major                   # crosses a major, so it asks

flint deps pin                           # write every transitive into overrides
flint deps unpin org/dep
flint deps tree                          # what is reached, and from where
flint deps why org/dep                   # which path pulled it in
```

`add` is the interesting one and the rest follow from it. `bump` without an
argument stays inside each declared constraint; with `:major` it crosses one and
therefore ASKS, because a major bump is a decision and a tool that makes it
silently is a tool people stop trusting.

## Capabilities on dependencies

`0036` gives a workspace `:flint/capabilities-grant` (what it holds) and
`:flint/capabilities-guard` (what a requirer must hold). Dependencies need one
more thing, and it is a different relation.

**A dependency entry may carry a grant, and that grant is a DELEGATION:**

```clojure
{:flint/capabilities-grant [:fs :slurp]        ; what THIS project holds
 :deps
 {org/lib {:git/version "1.2.0" :git/sha "..."
           :flint/capabilities-grant [:fs]}}}  ; what I lend to org/lib
```

Read plainly: *this project holds `:fs` and `:slurp`, and lends `:fs` — not
`:slurp` — to `org/lib`.*

Three rules, and each exists because its absence is a hole:

1. **You cannot lend what you do not hold.** A grant on a dependency entry that
   the project itself was never granted is refused at read time, naming both.
   Without this, `deps.edn` would be a way to mint authority.
2. **A dependency declaring `:flint/capabilities-guard` must be granted it**, or
   the build is refused, naming the dependency and the missing capability. This
   is `0036` level one moved to where the coordinate is: the guard already
   refuses the `:require`, and refusing at the dependency entry says so at the
   place a person can fix it.
3. **`flint deps add` writes the grant it found.** The tool reads the
   dependency's own `deps.edn` (or a pod's manifest), sees its guard, and writes
   the matching grant into the new entry — after asking, because granting a
   capability is a decision and this is the moment to make it visible:

   ```text
   $ flint deps add git:github.com/org/lib
     org/lib 1.2.0 (a1b2c3d) requires: :fs
     grant :fs to org/lib? [y/N]
   ```

   A dependency hand-added to `deps.edn` without the grant is refused by rule 2,
   so the prompt is a convenience and never the enforcement.

**A pod manifest carries the same two keys**, so a pod that needs `:proc` says
so in the artifact rather than in a README, and `flint deps add pod:` can show
it before anything is booted.

### What this does not do

The same limit `0036` records, restated because a dependency system is exactly
where somebody will expect more: **this constrains linking, not leaking.** A
dependency granted `:fs` can wrap it in a function of its own and hand the
result to a dependency that was granted nothing. What the grant buys is that the
set of dependencies holding an authority DIRECTLY is small, declared, and
diffable in a pull request.

## Where the policy lives: grants narrow as they descend

A capability name says *what kind* of authority; it does not say which HTTP
routes, which directories, which hosts. That has to be written down somewhere,
and the somewhere decides whether the system is worth anything.

**The granting side is the authority, always.** A dependency cannot be trusted
to say what it may reach — that is the request, not the answer — so policy is
never read from the code it constrains. Three levels, each narrowing the one
above and never widening it:

```text
flint run :with [...]          the invoker, above everything
  └── top-level deps.edn       the project, authority for what it holds
        └── a dependency entry what it lends onward, narrowed
```

That is the same rule the `:fs` root already follows: a grant carries a root, and
a derived grant can only be a subtree. Generalised, it is the only rule here —
**a grant may be narrowed at every hop and widened at none** — and it makes the
whole chain auditable from the top, because nothing below the root can add to it.

So `:flint/capabilities-grant` takes a MAP as well as the set of names it takes
today:

```clojure
{:flint/capabilities-grant
 {:slurp {:allow ["file://./config/**" "https://registry.npmjs.org/**"]}
  :fs    {:root "." :write ["target/**"]}
  :net   {:allow ["https://api.example.com/**"]}}

 :deps
 {org/lib {:git/version "1.2.0" :git/sha "..."
           ;; NARROWED: org/lib may reach the registry and nothing else.
           :flint/capabilities-grant {:slurp {:allow ["https://registry.npmjs.org/**"]}}}}}
```

The set form stays and means "these names, with whatever policy I hold, unchanged"
— which is the common case and should not have to be spelled out. Both forms read
the same way: *what I lend*.

### How a workspace's policy reaches the thing that enforces it

Stated as a gap first, because it is one: **per-workspace policy is not enforced
today.** `:with [slurp:https://a.com/**]` is ONE policy for the whole program.
If workspace A is allowed only its own domain and workspace B the whole web,
nothing at run time can tell which of them is calling.

Two ways to close it, and the difference matters.

**Wrapping the reference — rejected, and measured.** The obvious shape is to
wrap a guarded var where it is referenced, carrying the referencing workspace's
options on the closure:

```clojure
(if (fn? x) (fn {:flint/capabilities opts} [& args] (apply x args)) x)
```

It cannot work, and not for a reason of taste. `apply` is a builtin, and a park
reached through `apply` is refused — so a wrapper of exactly this shape crashes
on the first capability that does I/O, which is all of them:

```clojure
(let [w (fn [p] (fs/exists? p))]      (w ""))   ; => true
(let [w (fn [& args] (apply fs/exists? args))] (w ""))   ; => crash
```

Beyond that it needs the CALLEE to read the WRAPPER's slot, which is a dynamic
binding by another name, and it puts the options inside the guest where they can
be read.

**Binding the policy to the PORT — the answer.** A virtual namespace is reached
over a port, and a port is the one thing a guest cannot fabricate. So:

* `flint.virtual` memoises its client per **(namespace, workspace)** rather than
  per namespace, so workspace A and workspace B talking to `flint.sys.slurp`
  hold two different ports;
* the open request carries the referencing workspace, which the compiler knows
  and the guest does not choose;
* the host binds that workspace's EFFECTIVE policy to the port when it grants
  it, and every message arriving on that port is from that workspace by
  construction.

What this buys, and why it is better than the wrapper on every axis: nothing is
allocated per call, the options never enter the guest at all, `apply` is not
involved, and a transitive dependency gets its own port and therefore its own
policy without anything being threaded through the program.

It also answers the "do we hand the guest a vector of
`[root-options … leaf-options]`" question with **no**. The host has the whole
`deps.edn` chain already, so it computes the effective policy for each workspace
ONCE, at startup, by narrowing down the delegation chain. The guest says who it
is; it never carries what it is allowed to do.

**The hole this depends on closing.** `flint.port/open` is not guarded, so guest
code can open a system namespace's port by hand and get whatever policy the host
gives an unattributed open. That is exactly what `0036` step 7 means by "`open`
stops carrying its weight", and it stops being a style preference here: retiring
`open` onto the guarded request primitive is a PRECONDITION for per-workspace
policy, not a tidy-up.

**On ignoring the declarations.** The `:flint/capabilities-guard` a dependency
declares is the REQUEST and the grant in the depending project's map is the
ANSWER; both are kept and they are not the same thing. The declaration is what
lets `flint deps add` offer the right grant, and what lets the build refuse a
dependency whose need was never met. Nothing about it is authority.

### Why this does not weaken the compile-time guard

**The guard never sees the policy.** It compares NAMES, at the reference, exactly
as `0036` built it: does this workspace hold `:slurp` at all. The policy is
host-side and run-time, checked when `slurp` is actually called with a URL.

That is not a compromise, it is the same division stated once more. A guard that
tried to check the route would be checking a run-time value at compile time, and
`0036` spends a section on why a check that looks stronger than it is, is worse
than no check. What the policy adds is that the specific question — *this URL,
now* — gets a declarative answer in `deps.edn` instead of one buried in host
code, and one a reviewer can read in a diff.

### Per workspace, and that falls out

Each dependency entry is a workspace's policy for the workspace below it, so
policy is per-workspace by construction rather than by a separate mechanism. The
top level is the authority not because it is special but because it is the only
one nobody delegated to.

The open question of `0036` — whether a dependency may RE-LEND what it was lent
— is answered by the narrowing rule and needs no separate answer: it may, and
only narrower. A grant that stopped at the first edge would make a library unable
to use its own dependencies to do the job it was granted the authority for.

## Pods

A pod is one implementation of the virtual-namespace interface, behind `:deps`
and whatever the pod's own manifest guards. `flint.deps.pod` boots it, speaks
babashka's pod protocol, and answers `:list` from `describe` — which is why
`0036` made the var list optional and sourced from `:list`: a booted pod can be
asked what it holds, and a build that boots one gets the same compile-time
checking as any other namespace.

**A build that boots a pod has a live process in it.** That is a real cost, so
it is opt-in and the answer is cached under `.flint/` like a fetched dependency
— paid once, not per build. A build that does not boot compiles against the
`deps.edn` declaration and takes the surface on trust, and `flint build` says
which namespaces were CHECKED and which were TRUSTED, because the same mistake
being a compile error or a run-time error depending on build configuration is
only defensible if a person can tell which they got.

**Macros from a pod: no.** Invoking a pod at compile time and letting its output
into the program is a larger step than reading a description, babashka's pods do
not do it, and it is refused here rather than left unmentioned.

## The Rust side, and what it costs

The servers live in `cli/src/main.rs` and its new modules. This is the first
time the binary takes real external dependencies, and the number that has been
quoted in the README since it was written is going to move:

| | today |
| --- | --- |
| `flint` binary | 2.6 MB |
| dependencies | `flint-rt`, `flint-conc`, `anyhow` |

Adding an HTTP client, a git implementation, zip/tar and semver will grow that,
and this file first guessed "plausibly 15–25 MB". **The first measurement says
otherwise**, which is why the guess is left visible rather than quietly edited:

| | bytes | |
| --- | --- | --- |
| before any crate | 2 835 088 | |
| with `ureq` + `rustls`, used by `slurp` | 3 979 520 | **+1.1 MB** |
| with `flate2`/`tar`/`zip`/`semver`/`sha2`/`serde_json`, used by npm and Maven | 4 393 808 | **+0.4 MB** |

`ureq` with `default-features = false, features = ["rustls"]` is the whole TLS
stack for 1.1 MB, and adding the dependency changed nothing at all until
something called it — LTO and `opt-level = "z"` removed a crate nobody used.
So the estimate above was wrong by an order of magnitude and the honest position
is that each crate is measured as it lands, not predicted in a table.

Chosen for being pure Rust, so that cross-compilation and a static binary stay
possible:

* **`ureq`** + **`rustls`** for HTTP. Blocking, which is what a CLI wants, and
  no async runtime pulled in behind it.
* **`git`, the program** -- not `gix` and not `git2`. This is a deliberate
  exception to "pull in the crates" and the reason is measured rather than
  aesthetic: what git resolution needs is TWO operations, `ls-remote --tags` and
  a depth-1 fetch of one sha, and `gix` is a very large dependency tree for two
  operations. `git` is present wherever somebody fetches source from git at all,
  which is exactly the case this serves. Recorded here as an exception rather
  than left to read as an inconsistency; if `git` turns out to be absent in a
  real environment then the crate is the answer, and the surface does not
  change.
* **`zip`** and **`tar`** + **`flate2`** for jars and npm tarballs.
* **`semver`** for version arithmetic, in Rust — with the caveat below.
* **`sha2`** for integrity.

**The version arithmetic is duplicated on purpose**, and that has to be said out
loud because this repository has a record of what unplanned duplication costs
(`0035`: a value only one of three readers knew about). The Rust `semver` crate
resolves at FETCH time; `flint.deps` in `.cljc` compares versions at PLAN time,
where the graph lives. Two implementations of "which version wins" is exactly
the shape that goes wrong, so: **the plan is authoritative and Rust never
chooses.** `flint.deps.git/resolve` is given a constraint and returns EVERY
matching tag with its sha; picking one is the `.cljc` side's job. Rust matches,
flint decides.

## Order

1. **Virtual namespaces** (`0036` steps 4–6). The blocker. Nothing here exists
   without them, and they should land alone with one trivial `flint.sys.*`
   namespace as the proof.
2. **`flint.sys.slurp` and `flint.sys.fs`**, served by the Rust CLI. `:fs`
   ported from `host/fs.mjs` including the rooting rule; `lib/flint/fs.cljc`
   deleted and its callers moved.
3. **`flint.sys.env`, `flint.sys.proc`, `flint.sys.net`, `flint.sys.clock`.**
4. **The crates**, with the size measurement, behind `flint.deps.npm` and
   `flint.deps.mvn` — the two whose semantics are simplest.
5. **`flint.deps.git`**, with semver over tags, prefix sha integrity, and
   canonical `:git/tag` unchanged.
6. **`flint.deps` rewritten in `.cljc`**: the graph, the conflict rule, the
   transitive walk, overrides.
7. **`flint deps add` / `bump` / `pin` / `tree` / `why`.**
8. **Capability delegation on dependency entries**, with all three rules.
9. **`flint.deps.pod`** and the babashka pod protocol.
10. **The old path deleted**: `bin/flint`'s `git!`/`npm!`/`mvn!` shell-outs and
    the parts of `lib/flint/deps.cljc` they served.

## What is built

Measured, and in the gate (`test/sysns.clj`, thirteen assertions against the
shipped binary).

* **Virtual namespaces** (`0036` step 4) — a source rewrite in the analyzer, no
  new AST node. The optional var list decides compile error versus run-time
  error, and the CLI always supplies one because it knows its own surface.
* **`flint.sys.fs`** and **`flint.sys.env`**, with the rooting rule ported from
  `host/fs.mjs` unchanged. `:fs` is read-only unless `fs:write` was asked for.
* **`flint.sys.slurp`**, `file://` and `https://` behind one capability with a
  host-side allowlist.
* **`flint.deps.npm`**, **`flint.deps.mvn`**, **`flint.deps.git`** — real
  registries, real fetches, escape-refusing unpackers.
* **`flint.deps.resolve`** in `.cljc`: the conflict rule, the transitive walk,
  the pins.
* **`flint deps add`**, which runs the `.cljc` plan rather than reimplementing
  it.
* **Capability delegation** on a dependency entry, with both rules.
* **Pods**, speaking babashka's protocol -- bencode over stdio, JSON payloads,
  `describe` and `invoke`. A booted pod's `describe` supplies the var list, so a
  pod gets the SAME compile-time checking as any other namespace:

      unable to resolve d/subtract -- pod.demo is a virtual namespace
      and does not hold subtract

  Arities are omitted rather than claimed as `[]`, because a pod's `describe`
  does not always carry them and `[]` would read as "takes no arguments".

Two things the build found that the design had not:

* **A pin that was not a pin.** A bare `1.2.0` written as a semver RANGE means
  `^1.2.0`, so pinning to `1.2.0` resolved to `1.3.0`. "Everything is pinned by
  default" has to be true or it is worse than not claiming it.
* **An annotated tag resolves twice.** `ls-remote` lists the tag object and the
  commit it dereferences to; taking the first pins an object no checkout wants,
  and the sha integrity check then fails against a correct repository.

And one deliberate exception to "pull in the crates", recorded above: git is
`git` the program, because the two operations needed do not justify `gix`'s
dependency tree.

## A crash found on the way, and not yet fixed

**Corrected.** This section first said the crash was about virtual-namespace
calls, and that it was a divergence between the runtimes. Both claims were
wrong, and the correction is the useful part: what was measured the first time
was one instance of a much more general bug, and attributing it to the feature
it was found in is how a language bug gets filed as a library bug.

**A park inside a LAZY SEQ crashes, on both runtimes, with no ports and no
virtual namespaces involved.**

```clojure
;; no park: fine
(let [[tx rx] (p/channel 4 "c")]
  (p/send tx 1) (p/send tx 2)
  (vec (for [_ [0 1]] (p/receive rx))))          ; => [1 2]

;; a park inside `loop`: fine
(loop [n 2 acc []]
  (if (zero? n) acc (recur (dec n) (conj acc (p/receive rx)))))  ; => [1 2]

;; a park inside a lazy seq: CRASH
(t/spawn (fn [] (p/send tx 1)))
(vec (for [_ [0]] (p/receive rx)))               ; panic / unreachable

;; and with no ports at all
(vec (for [th [(t/spawn (fn [] 7))]] (t/join th)))   ; panic / unreachable
```

| | |
| --- | --- |
| native, `flint run` | `index out of bounds: the len is 1024 but the index is 18446744073709551615` |
| wasm, through the SDK | `unreachable` — a trap |

So it is not a divergence: both crash. What differs is only that ONE park path
— a bridge receive on wasm — reaches the clean `cannot park here` refusal, which
is why the first measurement looked like a divergence.

### What is known

* It needs an ACTUAL park. The same code with the value already available works,
  so `for` is not the problem and neither is the lazy seq by itself.
* `loop` is fine. Only lazy realisation crashes.
* `Rt::parked` has a guard for exactly this — `base_depth != 0`, "Rust frames
  are live underneath: a lazy-seq force" — and it refuses cleanly when it fires.
  It is not firing here, and finding out why is where the next attempt starts.
* The panic surfaces in `enter`, in the outermost interpreter loop, which says
  the damage is done by the time it shows.

**Not fixed here.** The obvious repair — `parked` restoring `stack_top` and
returning rather than unwinding — was written, measured, did not fix it, and was
reverted rather than left in the tree as an unverified change to the
interpreter. This belongs to the language rather than to `0037`, and it is
recorded here only because this is where it was found.

`flint.deps.resolve/bump-plan` is written with `reduce` rather than `for` for
this reason, and says so where it is written.

## What is undecided

* Whether `flint.sys.*` should be servable by the SDKs too, or stay the CLI's.
  Leaning: the namespace is a contract, so any host may serve it, and the SDK
  should get a helper — but the CLI is the only implementation this file
  proposes building.
* Whether `:slurp` of `file://` should be rooted the way `:fs` is. Leaning yes,
  by the same argument, which means a `:slurp` grant carries a root as well as
  an allowlist.
* `flint.sys.clock` at all. Wall time is authority (it fingerprints), monotonic
  time is not, and separating them may be more pedantry than it is worth.
