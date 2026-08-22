# 0002 — Only reachable code ships, builtins included

**Supersedes the first version of this note**, which concluded "write the library
namespaces in cljc so they tree-shake for free". The owner's requirement is
stronger than that resolution assumed:

> only reachable code (including core built-ins) should be included in the
> output. We don't need to implement the parsers ourselves, we can adapt existing
> rust libraries to our data structures.

So Rust-side code must be eliminated by reachability too, and the parsers are
adapted crates rather than cljc. The cljc-tree-shaking argument still holds for
tier 2 — it is just no longer the whole answer.

## The problem it creates

The plan builds the runtime `.wasm` ONCE and splices the program image in as a
data segment. A prebuilt binary cannot tree-shake against a program it has never
seen, and worse: because the program is INTERPRETED, every builtin is reached
through a dispatch table, so a linker or `wasm-opt` cannot prove any of them dead.
They are all live by construction.

## Two ways out. The second keeps the splice design.

**(a) Build the runtime per compile, with cargo features.** The compiler works
out the reachable set, enables the matching features, and `cargo build --release`
with LTO drops the rest. Simple and certainly correct. The cost is a Rust build
on the compile path — tens of seconds — which can be cached per feature-set.

**(b) Null the dispatch table, then run wasm DCE.** Keep the single prebuilt
runtime. Make the **builtin registry the only thing that references an optional
Rust function** — no direct calls from anywhere else. The patcher then rewrites
the table's element segment to drop the entries the program never reaches, which
makes those function bodies genuinely unreachable, and a DCE pass (binaryen
`wasm-opt --dce`, not currently installed) removes them.

(b) is better if it holds: compiles stay fast and the splice design survives.
It depends on a discipline the code must keep from the start — **one table, one
reference, no back doors** — which is cheap now and impossible to retrofit once
something calls a parser directly.

Choose deliberately, measure both if it is close, and write down which and why.
If (b) turns out not to hold, (a) is the honest fallback and not a failure.

## The parsers: adapt, do not write

`flint.data.json`, `flint.data.html`, `flint.data.xml` come from existing Rust
crates rather than from scratch. Two constraints shape the choice:

- **We are `no_std`-ish with our own allocator.** Prefer crates that work with
  `alloc` and no `std`. Many popular ones will not.
- **Use the streaming or event API and build flint values DIRECTLY.** Do not
  materialise the crate's own document tree and convert it — that is two
  allocations of everything and drags in the parts of the crate we least want.
  A SAX-style pass emitting our vectors and maps is smaller, faster, and cuts the
  dependency surface.

Each parser is its own cargo feature, which is what makes the reachability story
above work.

HTML is the one to be careful about: spec-complete HTML5 parsing is weeks of
error recovery and implied-tag rules. Take a crate that already did that work, or
document the subset honestly. Do not half-write one.

## What must be true at the end

- A trivial program produces a small module. **Report the number.**
- A realistic program reports its number too, so the floor and the slope are both
  visible.
- A program that never mentions XML contains no XML parser, and there is a
  **test that asserts this** rather than a claim in prose — module size, or a
  symbol/section check on the output.
- The unavoidable floor — GC, collections, number tower, UTF-8 — is named in the
  README honestly.
- Every adapted crate is justified: what it gave us, and what it cost in bytes.
