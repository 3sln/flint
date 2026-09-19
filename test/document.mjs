// The document resource (bench/construe/document-resource.md): structure once, content on
// demand, planned by the host, delivered in waves.
//
// Almost every claim here is about MEMORY or about TRAFFIC, so almost every
// assertion is a measurement rather than an inspection of the code.
import { load, instantiate } from '../host/flint.mjs';
import { DocStore, documentCapability, coalesce, breakEvenGap } from '../host/docstore.mjs';

let fails = 0;
const ok = (label, cond, extra) => {
  if (cond) console.log('  ok   ' + label);
  else { fails++; console.log('  FAIL ' + label + (extra ? '\n        ' + extra : '')); }
};
const eq = (label, a, b) => ok(label, a === b, `expected ${JSON.stringify(b)} got ${JSON.stringify(a)}`);

/// A document with `pages` pages of `blocks` blocks of `leaves` leaves, each
/// leaf holding `leafBytes` of text. Content is laid out in document order, so
/// a subtree is one range -- which is the locality 0008 asks the storage side
/// for, and without which batching cannot help.
function makeDoc({ pages = 2, blocks = 4, leaves = 8, leafBytes = 200 } = {}) {
  const nodes = [];
  let id = 0;
  let off = 0;
  const parts = [];
  const root = { id: id++, type: 'doc', page: 0, box: [0, 0, 0, 0], parent: null, children: [], len: 0, off: 0 };
  nodes.push(root);
  for (let p = 0; p < pages; p++) {
    const page = { id: id++, type: 'page', page: p, box: [0, 0, 0, 0], parent: root.id, children: [], len: 0, off: 0 };
    root.children.push(page.id); nodes.push(page);
    for (let b = 0; b < blocks; b++) {
      const block = { id: id++, type: 'block', page: p, box: [0, 0, 0, 0], parent: page.id, children: [], len: 0, off: 0 };
      page.children.push(block.id); nodes.push(block);
      for (let l = 0; l < leaves; l++) {
        const text = `p${p}b${b}l${l}:`.padEnd(leafBytes, '.');
        const leaf = { id: id++, type: 'leaf', page: p, box: [0, 0, 0, 0], parent: block.id, children: [], len: text.length, off };
        block.children.push(leaf.id); nodes.push(leaf); parts.push(text); off += text.length;
      }
    }
  }
  return { structure: { root: 0, nodes }, content: new TextEncoder().encode(parts.join('')) };
}

/// `stress` turns the collector on for every allocation.
///
/// `stat_peak_live` is a HIGH-WATER MARK SAMPLED AFTER EACH COLLECTION -- see
/// `note_peak`: mid-cycle the nursery is full of garbage and the number would
/// mean nothing. That is fine for a workload that collects often, and this one
/// stopped: when a port carried bytes the guest held an EDN text buffer and ran
/// a reader over it, and that garbage is what drove the collections the metric
/// was sampled at. A bridge decodes in the runtime now, so the same walk makes
/// almost none, and the mark was never taken while the structure was live --
/// every row reported the same 12,224 bytes.
///
/// Stressing the collector restores the sampling rather than the memory: the
/// numbers below are what was always resident, not a regression being hidden.
/// The module's namespace, from the file it was built into. Each is compiled
/// `:fn <ns>/main`, and the FUNCTION IS NAMED at the call now: a module has no
/// entry point (`DECISIONS.md#structured-ports` step 5).
const fnOf = (wasm) => `${/out\/doc-([a-z]+)\.wasm$/.exec(wasm)[1]}/main`;

async function runWith(wasm, store, args = [], { stress = false } = {}) {
  const { module } = await load(wasm);
  const inst = instantiate(module);
  if (stress && inst.exports.set_gc_stress) inst.exports.set_gc_stress(1);
  let messages = 0;
  const cap = documentCapability(store);
  inst.capabilities({
    doc: {
      message(port, data, api) { messages += 1; cap.message(port, data, api); },
      poll(port, api) { cap.poll(port, api); },
    },
  });
  const r = inst.run(fnOf(wasm), args);
  if (r.code !== 0) throw new Error(`module failed: ${r.out}`);
  const e = inst.exports;
  // SAY WHICH BUILD IS MISSING, rather than crashing on a missing export.
  // `collect_now` and `stat_peak_live` exist only in a diagnostics build;
  // `bin/test` runs `./bin/build-units --diagnostics` before this file for
  // exactly that reason, so the gate never sees it. Running this test by hand
  // against production units used to fail with "e.collect_now is not a
  // function", which names the symbol and not the cause.
  // `bench/construe.mjs` already guards the same two exports and says so.
  if (typeof e.collect_now !== "function" || typeof e.stat_peak_live !== "function") {
    throw new Error(
      "this test measures memory and needs a DIAGNOSTICS build of the units -- " +
      "run `./bin/build-units --diagnostics` first (which is what `bin/test` does)");
  }
  e.collect_now();
  // TWO PEAKS, because they mean different things.
  //
  // `stat_peak_live` is an UPPER BOUND: after a minor, `old_live` still counts
  // every old object allocated since the last major, dead ones included.
  // `stat_peak_live_major` is sampled only after a major, where `sweep_old`
  // has just made `old_live` true (`DECISIONS.md#two-builds`).
  //
  // A claim about RESIDENCY wants the second. A comparison of two runs against
  // each other can use either, since the bias is the same on both sides.
  return { out: r.out, messages,
           peakLive: Number(e.stat_peak_live()),
           peakLiveMajor: Number(e.stat_peak_live_major()),
           exports: e };
}

console.log('documents');

// --- the coalescer, and the two constants it depends on ---------------------
{
  ok('coalescing merges across a gap smaller than the threshold',
     JSON.stringify(coalesce([[0, 10], [1000, 1010]], 2000)) === '[[0,1010]]');
  ok('  ... and leaves a larger one alone',
     JSON.stringify(coalesce([[0, 10], [1000, 1010]], 100)) === '[[0,10],[1000,1010]]');

  // Measure this machine's in-memory store rather than trusting a number.
  const { content } = makeDoc({ pages: 4, blocks: 8, leaves: 16, leafBytes: 512 });
  const store = new DocStore({ root: 0, nodes: [] }, content, {});
  const reps = 2000;
  let t0 = process.hrtime.bigint();
  for (let i = 0; i < reps; i++) store.fetchRange(0, 1);
  let t1 = process.hrtime.bigint();
  const latencyMs = Number(t1 - t0) / 1e6 / reps;
  const big = Math.min(content.length, 1 << 20);
  t0 = process.hrtime.bigint();
  for (let i = 0; i < 200; i++) { const b = store.fetchRange(0, big); if (b.length !== big) throw new Error('short'); }
  t1 = process.hrtime.bigint();
  const bytesPerMs = big / (Number(t1 - t0) / 1e6 / 200);
  const measured = breakEvenGap({ latencyMs, bytesPerMs });
  console.log(`    measured for THIS store: ${latencyMs.toFixed(6)} ms/request, ` +
              `${(bytesPerMs / 1000).toFixed(0)} MB/s  ->  break-even gap ${measured} bytes`);
  console.log(`    for object storage at 20 ms and 100 MB/s: break-even gap ` +
              `${breakEvenGap({ latencyMs: 20, bytesPerMs: 100_000 })} bytes (~2 MB)`);
  ok('the break-even gap follows from the two constants, not from instinct',
     measured === Math.round(latencyMs * bytesPerMs));
}

// --- a structure walk must generate ZERO port traffic ------------------------
{
  const d = makeDoc({ pages: 3, blocks: 4, leaves: 10, leafBytes: 100 });
  const store = new DocStore(d.structure, d.content, {});
  const r = await runWith('out/doc-walk.wasm', store);
  eq('a structure walk asks the host exactly once -- for the structure', r.messages, 1);
  eq('  ... and fetches no content at all', store.stats.requests, 0);
  ok('  ... having really walked the whole tree', r.out.includes(':walked 136'), r.out);
}

// --- batching is the difference between one request and N -------------------
{
  const mk = () => { const d = makeDoc({ pages: 2, blocks: 4, leaves: 8, leafBytes: 200 }); return new DocStore(d.structure, d.content, {}); };
  const batched = mk();
  const rb = await runWith('out/doc-batch.wasm', batched, ['batch']);
  const single = mk();
  const rs = await runWith('out/doc-batch.wasm', single, ['single']);
  console.log(`    64 leaves: batched ${batched.stats.requests} storage request(s), ` +
              `${rb.messages} port message(s); one at a time ${single.stats.requests} and ${rs.messages}`);
  eq('a batched fetch of 64 nodes is ONE storage request', batched.stats.requests, 1);
  ok('  ... against one per node when asked one at a time', single.stats.requests === 64,
     `${single.stats.requests}`);
  ok('  ... which is the whole reason the API is plural',
     single.stats.requests / batched.stats.requests >= 64);
  ok('  ... and both got the same bytes', rb.out === rs.out, `${rb.out} vs ${rs.out}`);
}

// --- discarded bytes must never enter the guest heap ------------------------
//
// A controlled comparison, because an absolute number would be dominated by the
// structure the script is deliberately holding. The SAME script, the same
// wanted nodes; only the planner's gap threshold differs, so only the number of
// bytes fetched-and-thrown-away differs. If the discarded bytes crossed the
// boundary, the coalesced run's peak would be half a megabyte higher.
{
  const d = makeDoc({ pages: 4, blocks: 8, leaves: 16, leafBytes: 1024 });
  const leaves = d.structure.nodes.filter((n) => n.type === 'leaf');
  const first = leaves[0].id;
  const last = leaves[leaves.length - 1].id;
  const run = async (gapThreshold) => {
    const store = new DocStore(d.structure, d.content, { gapThreshold });
    const r = await runWith('out/doc-ends.wasm', store, [String(first), String(last)]);
    return { store, r };
  };
  const wide = await run(1 << 30);   // coalesce the whole span
  const tight = await run(1);        // two separate requests
  console.log(`    coalesced: ${wide.store.stats.requests} request(s), ` +
              `${wide.store.stats.bytesFetched} bytes fetched to deliver ` +
              `${wide.store.stats.bytesDelivered}, peak live ${wide.r.peakLive}`);
  console.log(`    separate:  ${tight.store.stats.requests} request(s), ` +
              `${tight.store.stats.bytesFetched} bytes fetched to deliver ` +
              `${tight.store.stats.bytesDelivered}, peak live ${tight.r.peakLive}`);
  eq('a wide gap threshold coalesces into one request', wide.store.stats.requests, 1);
  eq('  ... and a tight one does not', tight.store.stats.requests, 2);
  const wasted = wide.store.stats.bytesFetched - tight.store.stats.bytesFetched;
  ok('  ... so the coalesced run fetched far more than it delivered',
     wasted > 400_000, `${wasted} extra bytes`);
  ok('  ... and yet the guest heap is the same size either way',
     Math.abs(wide.r.peakLive - tight.r.peakLive) < wasted / 10,
     `coalesced peak ${wide.r.peakLive}, separate peak ${tight.r.peakLive}, ` +
     `${wasted} bytes discarded`);
  eq('  ... and both delivered the same answer', wide.r.out, tight.r.out);
}

// --- an ask larger than the budget is answered in waves ---------------------
//
// The ask has to be several times the *nursery* as well as several times the
// budget. Below that, nothing forces a collection and peak memory is simply
// "everything allocated so far" -- which says nothing about whether waves work.
{
  const d = makeDoc({ pages: 8, blocks: 8, leaves: 16, leafBytes: 4096 });
  const budget = 64 * 1024;
  const store = new DocStore(d.structure, d.content, { budgetBytes: budget });
  const r = await runWith('out/doc-waves.wasm', store);
  const total = store.stats.bytesDelivered;
  console.log(`    ${total} bytes of content, ${budget} byte budget -> ` +
              `${store.stats.waves} waves; peak live ${r.peakLiveMajor} bytes ` +
              `(ceiling incl. old-space garbage: ${r.peakLive})`);
  ok('an ask several times the budget comes back in waves',
     store.stats.waves >= total / budget, `${store.stats.waves} waves for ${total}/${budget}`);
  ok('  ... and the script saw every one of them',
     r.out.includes(`:waves ${store.stats.waves}`), r.out);
  ok('  ... and read every byte', r.out.includes(`:bytes ${total}`), r.out);
  // AGAINST THE TRUTHFUL PEAK, and the difference is not cosmetic.
  //
  // This read `stat_peak_live` and failed at 4 039 744 against a 4 194 304 ask
  // -- for a program whose genuinely live set never exceeds about 530 KB. The
  // number was right about what it measures and wrong about what this row
  // claims: `LARGE_OBJECT` is 16 KB, so each 64 KB wave is born in OLD space,
  // and sixty-four of them accumulate between majors while `old_live` counts
  // every one of them alive or dead.
  //
  // The claim here is about RESIDENCY -- "peak memory is proportional to the
  // content a script actually kept" -- so it is made against the counter that
  // means that (`DECISIONS.md#two-builds`, which names this fix as the one
  // that breaks nothing and stops the docstring being false).
  ok('  ... while peak memory stayed a fraction of the ask',
     r.peakLiveMajor < total / 3,
     `peak live ${r.peakLiveMajor} against ${total} total ` +
     `(ceiling ${r.peakLive})`);
  // This run is the reproducer for the stale-pointer bug: `port_send` used to
  // hand its unrooted Rust argument to `check_sendable`, which allocates, and
  // pushed the result of that stale local as a root. One wave in sixty-four
  // went missing and everything else read as success -- so the guard lives
  // here, where it failed, and it asserts its own coverage before its zero.
  const g = r.exports;
  ok('  ... and not one stale pointer was written in the whole run',
     g.stat_stale_set(0) === 0 && g.stat_stale_root(0) === 0 && g.stat_stale_push(0) === 0 &&
     g.stat_stale_root(5) > 0 && g.stat_stale_push(3) > 0,
     `stale writes ${g.stat_stale_set(0)}, stale roots ${g.stat_stale_root(0)}, ` +
     `stale pushes ${g.stat_stale_push(0)}, after ${g.stat_stale_root(5)} collections ` +
     `walked and ${g.stat_stale_push(3)} pushes checked`);
}

// --- peak memory follows what is KEPT, not the document size ----------------
{
  const rows = [];
  for (const leafBytes of [256, 1024, 4096]) {
    const d = makeDoc({ pages: 2, blocks: 4, leaves: 8, leafBytes });
    const store = new DocStore(d.structure, d.content, {});
    const r = await runWith('out/doc-onepct.wasm', store, [], { stress: true });
    rows.push({ docBytes: d.content.length, peak: r.peakLive, fetched: store.stats.bytesDelivered });
  }
  console.log('    document bytes | content read | module peak live');
  for (const x of rows) console.log(`    ${String(x.docBytes).padStart(14)} | ${String(x.fetched).padStart(12)} | ${x.peak}`);
  const grew = rows[2].docBytes / rows[0].docBytes;
  const peakGrew = rows[2].peak / rows[0].peak;
  ok('a document 16x larger, with the same access pattern, does not cost 16x the memory',
     peakGrew < grew / 4, `document grew ${grew}x, peak grew ${peakGrew.toFixed(2)}x`);
}

// --- and the same pattern with MORE NODES, which is the honest caveat -------
{
  const rows = [];
  for (const leaves of [8, 32]) {
    const d = makeDoc({ pages: 2, blocks: 4, leaves, leafBytes: 256 });
    const store = new DocStore(d.structure, d.content, {});
    const r = await runWith('out/doc-onepct.wasm', store, [], { stress: true });
    rows.push({ nodes: d.structure.nodes.length, peak: r.peakLive });
  }
  console.log(`    structure is resident: ${rows[0].nodes} nodes -> ${rows[0].peak} bytes, ` +
              `${rows[1].nodes} nodes -> ${rows[1].peak} bytes`);
  ok('memory grows with the NODE COUNT, because structure is deliberately resident',
     rows[1].peak > rows[0].peak, `${rows[0].peak} vs ${rows[1].peak}`);
}

console.log(fails === 0 ? 'documents: ok' : `documents: ${fails} FAILURES`);
process.exitCode = fails === 0 ? 0 : 1;
