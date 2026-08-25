# 0023 — What "ready for construe" means, concretely

> **A milestone definition, not a design.** It exists so the handoff is judged
> against a list rather than a feeling, and so the work between here and there is
> the work that actually matters to the first customer.

flint was built for construe. This records what construe needs before flint can
be plugged in, based on what construe actually is rather than on what would be
nice to have.

## What construe actually does, which changes the bar

construe is a deterministic natural-language → structured-constraint transform.
**No model runs in the request path.** The parser is ordinary deterministic code,
**evolved offline** by a model against a growing corpus, with **gates that diff
every candidate against the incumbent** before it can ship. Parsing then happens
per WebSocket frame, in a Cloudflare Worker.

Three consequences, and they are not the obvious ones:

- **The hot path is per-frame parse latency and cold start**, not throughput. A
  session is a socket; a parse is a frame. This is why flint's cold-start win
  (1.00 ms against a V8 isolate's 14.59 ms) is worth more to construe than any
  steady-state speedup, and why AOT's +12% cold start for +8% throughput was the
  wrong trade *for construe specifically*.
- **The gates need an exact cost metric.** They compare a candidate against the
  incumbent, and "is this candidate cheaper" has to be an exact question. `0009`'s
  deterministic instruction count is that metric, and it is why determinism is a
  product requirement here rather than a nicety.
- **The code being run is model-written**, which is what `0021`/`0022`'s
  capability work is ultimately for: a parser candidate should be able to reach
  nothing it was not handed.

## The bar

### 1. A real parser candidate runs, and agrees

Not a benchmark — an actual construe parser candidate, in `cljc`, producing
**byte-identical constraint objects** to the incumbent across construe's corpus.
`bench/construe` already checks answers before it times anything; this extends
that from the seed interpreter to a candidate the evolution loop would produce.

### 2. flint compiles inside the deployed Worker

**This is the blocker construe actually has**, and it is binary. cherry cannot
compile inside workerd, which is a live constraint on promoting a candidate.
flint self-hosts, so `flint.wasm` compiling `cljc` inside workerd is the thing
that removes it — and nobody has run flint in workerd yet. Until that is
demonstrated, the headline argument for flint at construe is untested.

`0018` (cross-runtime benchmarks) should start with workerd for this reason,
ahead of wasmtime and wasmer.

**Half of this is now demonstrated.** A flint module was run under workerd
(`wrangler dev --local`, compatibility date 2026-06-01) by importing the `.wasm`
as a `CompiledWasm` module and instantiating it with no imports:

```json
{"ok":true,"code":0,"out":"hello, construe","instantiate_ms":0,"run_ms":3,"exports":36}
```

That settles the easier half: **a flint program runs in the deployed runtime**,
with no polyfill, no WASI, and no host functions — which follows from the module
importing nothing, but is worth having measured rather than argued.

**Both halves are now demonstrated, and the answer to the module question is
no.**

`out/flintc-gen0.wasm` — the compiler as a flint program — runs in workerd,
compiles a candidate from an EDN spec, and a second resident module loads the
resulting image and runs it. No linker anywhere:

```json
{"ok":true,"out":"item0:0,item1:1,...","image_bytes":8243,
 "compiler_instantiate_ms":0,"compile_ms":1178,
 "runner_instantiate_ms":0,"load_ms":2,"run_ms":2}
```

**So a Worker does not have to produce a module**, and should not try. The
mechanism is that an image records each builtin it imports by NAME as well as by
slot: the slots belong to whichever module compiled it and are meaningless
anywhere else, the names are not. `bin/flint --loader` builds a module carrying
every builtin on the path and exporting `flint_load_image`, which re-resolves an
image's imports against its own table and refuses — naming the builtin — if it
carries one the image needs.

What that buys, and the shape it implies for construe:

- **Compile once, run many.** The loader is instantiated once and images are
  swapped into it; `test/loader.clj` asserts that a second image replaces the
  first cleanly, in either order, any number of times, because a warm isolate
  will do exactly that across requests.
- **Compilation is a promotion-time cost, not a request-time one.** 1 178 ms in
  workerd for a 22-namespace program, of which the library is nearly all. Fine
  for promoting a candidate; not something to do per frame.
- **Loading is not.** 2 ms to load an 8 KB image and 2 ms to run it, against
  1.1 ms to instantiate the loader module.
- **The trade is module size.** A loader carries every builtin rather than the
  ones its own program reached, so it is 555 KB against 214 KB. That is the
  price of running code it has not seen, and it is the same trade `0003`'s
  tree-shaking makes in the other direction.

One thing found on the way, worth recording because it will recur: the arena
started immediately after the image, so the third spliced data segment was
overwritten by the first allocation and read back as a malformed registry. The
comment on `ensure_arena` already warned about exactly this for the image; it now
accounts for every segment rather than the one that existed when it was written.

### 3. Gas is exact, and survives being useful

`0009` is shipped and asserted. What is not yet demonstrated is that two
*candidates* can be compared by instruction count in a way the gates can act on —
same corpus, same seed, reproducible across runs and across a redeploy.

### 4. The library surface covers what a parser candidate needs

The deficiency lists (`test/manifest.clj`) are true but not yet aimed at this.
The question is narrower than "how much of Clojure": what does a **parser**
need — string handling, regex, maps, sequences, sorting — and is any of it
missing or pathologically slow. `reduced` never having worked is the warning:
core features can be absent without any test noticing.

### 5. Strings and regex are not the bottleneck

Was the largest known gap and is now much smaller. `word frequency (regex
split)` sat at **56× babashka**; `0011` (ropes) and `0012` (the Pike VM) are
built, and it is **11.6×**. The same workload without the regex is 7.3 ms
against 54.5 ms before.

The decomposition that got there is worth keeping, because the first reading of
that 56× was wrong: the regex engine was only 27% of it, and the rest was two
quadratic scans inside `str_index_of` and a per-character `lower-case`. Fixing
those made the engine 88% of what remained, which is what justified the Pike VM
rather than more string work. `test/scaling.clj` now asserts that eighteen
operations stay linear, because all three quadratics produced correct answers
and were found by accident.

### 6. Memory per parse is bounded and known

Workers have hard memory limits and construe's own spec calls memory the primary
bottleneck for documents. Peak live per parse, and per session across many
parses, measured rather than assumed.

## What is explicitly NOT on the bar

So the handoff is not held up by things construe does not need yet:

- **AOT** (`0013`) — shelved, and the wrong trade for a cold-start-dominated path.
- **The CLI** (`0021`) — developer convenience, not a runtime requirement.
- **Shards and module metadata** (`0020`) — matters when several namespaces ship
  independently, which is later.
- **The thread pool** (`0019`) — construe parses one frame at a time.
- **Snapshots, debugger, profiler** (`0014`, `0015`, `0017`) — development tools.

## After the bar is met

The flint agent continues on its own: CLI, tooling, the AOT question if it is
ever worth reopening, and everything in the "not on the bar" list. The
integration work moves to construe's side.
