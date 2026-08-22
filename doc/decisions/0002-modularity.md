# 0002 — Only what is needed goes into the build

A module compiled from a program that never mentions XML must not carry an XML
parser. This arrived after the plan was written and it cuts against part of it,
so resolve it deliberately.

## The tension

The plan builds the runtime `.wasm` ONCE and splices the program image in as a
data segment. That is a good design — the patcher needs no symbol table, and
compiles are fast because there is no `cargo build` on the path.

But it also means **everything on the Rust side ships in every module**, used or
not. A prebuilt binary cannot tree-shake against a program it has never seen.

## The resolution, by tier

There are two tiers, and only one of them has the problem.

**Tier 2 — cljc namespaces compiled into the program image.** Tree-shakes for
free: start from `:fn`, take the reachable set, compile that. Nothing else
appears. No mechanism needed beyond the compiler already knowing what it reached.

**Tier 1 — Rust builtins in the prebuilt runtime.** In every module regardless.
Making this conditional means either rebuilding the runtime per compile (slow,
and gives up the splice design) or maintaining feature-gated prebuilt variants
(a combinatorial set that goes stale).

## So: prefer cljc, and mean it

The default for a library namespace is **cljc, not Rust**. Reserve Rust for what
genuinely cannot be expressed in the language — allocation, hashing, the number
tower, UTF-8, collection internals, the GC.

`flint.data.json`, `flint.data.html`, `flint.data.xml` and most of
`clojure.string` are pure text-to-data work. That is precisely what this language
exists to do. Writing them in cljc makes modularity automatic AND dogfoods the
compiler on real programs, which is worth having anyway.

The pull toward Rust will be SPEED. Resist it until a benchmark says so. A JSON
parser in cljc that tree-shakes away when unused may well beat a Rust one that
every module carries — and if it does not, that is a measurement worth having
before the decision, not after.

## What must be true at the end

- A trivial program produces a small module. **Report the number.**
- A realistic program reports its number too, so the floor and the slope are both
  visible.
- Whatever every module carries unavoidably — the GC, the collections, the core
  primitives — is named in the README as the floor, honestly.
- If any namespace ends up Rust-side and optional, the mechanism for excluding it
  is described rather than implied.
