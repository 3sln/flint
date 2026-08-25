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

Currently the largest known gap. A parser is text processing, and
`word frequency (regex split)` sits at **56× babashka**, of which the regex
engine is only 27% — the rest is `lower-case`, `split`, `frequencies` and
`sort-by` on flat strings. `0011` and `0012` are the work; this is the number
that says whether they finished.

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
