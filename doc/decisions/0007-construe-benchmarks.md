# 0007 — Benchmark the DECISION, not the runtime

The final phase. Numbers only matter if they answer a question somebody is
actually deciding, so this names the question first.

## The question

flint exists to run pure logic anywhere. Its first real customer is **construe**
(`~/Projects/@3sln/construe`), which today compiles model-written `.cljc` to
JavaScript with cherry and runs it in a V8 isolate — `isolated-vm` on node,
Worker Loader on Cloudflare.

So the decision is not "is flint fast". It is: **for each place construe runs
code, is flint better, worse, or irrelevant — and what does that do to the
bill?**

Two facts from construe's own spec make this sharp:

- **CPU is 96% of what a session costs** (§10.0). Not tokens, not bandwidth. So
  anything that moves parse or suggest CPU moves the unit economics directly, and
  a flat subscription is priced on it.
- **Cherry cannot compile inside the deployed Worker.** `compileArtifact` fails
  with `$APP.sq is not a function` in the wrangler bundle though it works under
  vitest. That is a live blocker on promoting a search candidate in production.

## Fixtures — REAL ones, already in `bench/construe/`

- **`interpret.cljc`** — construe's actual seed interpreter, 258 lines, lifted
  from `packages/parser/src/seed.js`. This is the artifact a round evolves and a
  parse runs. Do not write a lookalike.
- **`contexts.json`** — four real annotated contexts dumped from construe's own
  annotator with its real lexicon: atoms, ancestry, spans, clause structure. The
  shape is `{text, clauses:[{nodes:[{start,end,options:[span]}]}], fields}`.

Take more if you need them; the dump script pattern is in this decision's commit.

## What to measure, and why each one is here

### 1. Parse latency — the number that touches the bill

One `interpret` call on a real context, warm. Construe budgets "a few
milliseconds of CPU" for this and prices a subscription on it.

**Compare against the incumbent**: the same `.cljc` compiled by cherry to JS and
run in node. That is the honest baseline, and it is JIT-compiled native — expect
flint to lose, possibly badly. **Report it anyway and plainly.** An interpreter
in wasm losing to JIT'd JS is not a failure of this project; pretending otherwise
would be.

What the number decides: whether flint can serve the read path at all, or whether
its place in construe is elsewhere.

### 2. Cold start and footprint — where flint may WIN big

A V8 isolate costs milliseconds to spin and megabytes to hold. A flint module
reported 0.11 ms cold and is a few hundred KB.

Measure: instantiate-to-first-answer, and resident memory per instance, against
spinning an `isolated-vm` isolate that has loaded the same compiled artifact.

What it decides: whether flint is a **cheaper sandbox** for running model-written
code. Construe runs untrusted evolved artifacts; a wasm module with its own GC
and no host access is a stronger and possibly far cheaper boundary than an
isolate. This may be the strongest economic case and it is not about throughput
at all.

### 3. The suite run — throughput, and the round's real cost

Construe's gates run a candidate over 500+ cases in one invocation, and its own
spec notes that an agent running the suite after every edit is "how one round
consumes a month of sandbox time".

Measure: 500 contexts through one warm module. Per-case cost, total, and **whether
GC pauses appear** — a major collection mid-suite is the risk.

### 4. Compilation — against a compiler that does not work

Time and module size to compile `interpret.cljc`. Compare with cherry's compile
of the same source.

flint's compiler is `.cljc` and self-hosts, so it can run **inside** a deployed
artifact where cherry demonstrably cannot. If flint compiles a candidate in
production, that is a blocker removed rather than a speed-up — and the number
that matters is "does it fit in a Worker's CPU budget", not "is it faster".

Report module size per compiled artifact too: construe stores one per workflow
per version, content-addressed, so size is storage that accumulates.

### 5. Heavy documents — memory, not throughput (see 0008)

Construe's extraction kind takes a document of potentially many MB, and
**memory is the bottleneck**. 0008 replaces the paging idea: structure is loaded
once into flint memory, content is fetched only when something asks for it.

So the number that matters is **peak resident memory as a function of document
size and of the fraction actually read** — not bytes per second.

Measure, across documents of very different sizes with the same access pattern:

- peak memory when a script reads the structure and touches 1% of the content —
  it should be roughly flat as documents grow, and if it is not, something is
  retaining;
- the same for 100%, which is the honest worst case and the upper bound on the
  claim;
- **port traffic during a pure structure walk, which must be zero**;
- a batched fetch of N nodes against N single fetches, since 0008 says locality
  is what makes batching possible and this is the number that proves it.

Also report per-message cost at batch 1 and batch 1000, which is 0006's claim
that batching amortises marshalling. **If that curve is flat, withdraw the
claim.**

### 6. Suggest / prefix scan — construe's own unmeasured number

§10.0 calls this "the most expensive unmeasured number" in construe: assumed at
1 ms, suspected nearer 0.2 ms, and 96% of session cost. Measuring it here is
cheap and tells construe something it does not currently know.

## Patterns to isolate

The construe workload is a particular mix, so measure the parts:

- deep nested map/vector construction (a constraint object is nested)
- keyword-keyed map access — the dominant operation in these scripts
- `reduce`/`into`/transients over span sequences
- large map merge (the ClojureDart work was taken for this)
- **string split and regex** — the annotator's shape, and regex is currently 84×
  slower than babashka, so this is where a realistic payload may hurt most

## How to report

A table per question, with the incumbent beside flint wherever one exists. Then a
short section — **"what this means for construe"** — that answers, in plain
sentences:

- can flint serve the read path, or not?
- is it a cheaper sandbox than an isolate?
- does it unblock compiling in production?
- and where would adopting it cost more than it saves?

**Say where flint loses.** A benchmark section that only contains wins is a
marketing page, and the person reading this has to make a decision with it.
