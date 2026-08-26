// doc/decisions/0013's region histogram, measured rather than assumed.
//
// 0013 gates the whole AOT decision on one distribution: how long are the
// regions in real code, and what does entering one cost? Two models are counted
// side by side, because 0013 argues they differ by an order of magnitude and an
// argument is not a number:
//
//   Model A — a region ends at EVERY call. What "contiguous non-parking chunk"
//             meant before the guard.
//   Model B — a call is an inline guard, not a boundary, so the unit is one
//             frame invocation. The design being built.
//
// The saving per region is (length - 1) x dispatch_cost - boundary_cost, so the
// boundary cost is measured here too rather than guessed: a wasm call taking a
// pointer, on this host, through the same call_indirect the natives use.
import { load, instantiate } from '../host/flint.mjs';
import { DocStore, documentCapability } from '../host/docstore.mjs';
import { codec } from '../host/edn.mjs';

const NB = 20;
// COUNTS.len() in aotstat.rs. It is 28, and this file read 24 for its first
// eight months, so every opcode in the mix was reported as the one four slots
// below it -- NATIVE as RETHROW, CALL as NATIVE. The census caught it by
// disagreeing with itself: NATIVE_CALLS totalled more than the NATIVE opcode.
const NCOUNT = 28;
const C = {
  INSTRS: 0, FRAMES: 1, CALLS: 2, TAILCALLS: 3, NATIVES: 4, APPLIES: 5,
  GUARDS: 6, GUARD_HITS: 7, RESUMED_INSTRS: 8, RESUMED_FRAMES: 9,
  BACKEDGES: 10, RUN_SUM: 11, RUN_N: 12, FRAME_SUM: 13, FRAME_N: 14,
  RESUMED_SUM: 15, RESTORES: 16, SAVES_PARK: 17, SAVES_YIELD: 18,
  SEG_SUM: 19, SEG_N: 20,
};

export function readRegions(e) {
  const g = (i) => Number(e.stat_region(i));
  const frame = [], run = [], resumed = [], seg = [], counts = {};
  for (let i = 0; i < NB; i++) frame.push(g(i));
  for (let i = 0; i < NB; i++) run.push(g(NB + i));
  for (let i = 0; i < NB; i++) resumed.push(g(NB * 2 + i));
  for (let i = 0; i < NB; i++) seg.push(g(NB * 3 + i));
  for (const [k, v] of Object.entries(C)) counts[k] = g(NB * 4 + v);
  return { frame, run, resumed, seg, counts };
}

const pct = (a, b) => (b ? (100 * a / b).toFixed(1) : '0.0') + '%';
const num = (n) => n.toLocaleString('en-US');

/// A histogram weighted by INSTRUCTIONS, not by occurrence. A region that
/// appears once and runs a million times is the only kind that matters, and a
/// count-weighted histogram hides exactly that.
function show(label, hist, total) {
  console.log(`  ${label}`);
  let instrTotal = 0;
  for (let k = 0; k < NB; k++) instrTotal += hist[k] * (1.5 * 2 ** k);
  for (let k = 0; k < NB; k++) {
    if (!hist[k]) continue;
    const lo = 2 ** k, hi = 2 ** (k + 1) - 1;
    const share = hist[k] * (1.5 * 2 ** k) / instrTotal;
    const bar = '#'.repeat(Math.max(1, Math.round(share * 40)));
    console.log(`    ${String(lo).padStart(7)}..${String(hi).padEnd(8)} ` +
                `${String(num(hist[k])).padStart(12)}  ${pct(hist[k], total).padStart(7)}  ` +
                `${(100 * share).toFixed(1).padStart(5)}% of work  ${bar}`);
  }
}

/// The boundary constant: what one entry into compiled code costs. Measured
/// through `call_indirect` on this host, because that is the shape an AOT region
/// would be entered through -- the same table the natives already use.
/// The boundary constant: what one entry into compiled code costs. Measured
/// through `call_indirect`, because that is the shape an AOT region would be
/// entered through -- which region to enter is data, so it goes through a table,
/// the same way the natives already do. A direct `call` would be inlined by the
/// engine and would measure nothing.
///
/// Hand-assembled rather than pulled in as a dependency: the module is
///
///     (type $t (func (param i32) (result i32)))
///     (func $id (param i32) (result i32) local.get 0)
///     (table 1 1 funcref) (elem (i32.const 0) $id)
///     (func (export "loop") (param $p i32) (param $n i32) (result i32)
///       (block (loop
///         (br_if 1 (i32.eqz (local.get $n)))
///         (local.set $p (call_indirect (type $t) (local.get $p) (i32.const 0)))
///         (local.set $n (i32.sub (local.get $n) (i32.const 1)))
///         (br 0)))
///       (local.get $p))
function boundaryModule() {
  const body0 = [0x00, 0x20, 0x00, 0x0b];
  const body1 = [
    0x00,                    // no extra locals
    0x02, 0x40,              // block
    0x03, 0x40,              //   loop
    0x20, 0x01, 0x45,        //     local.get $n; i32.eqz
    0x0d, 0x01,              //     br_if 1
    0x20, 0x00, 0x41, 0x00,  //     local.get $p; i32.const 0
    0x11, 0x00, 0x00,        //     call_indirect (type 0) (table 0)
    0x21, 0x00,              //     local.set $p
    0x20, 0x01, 0x41, 0x01, 0x6b, 0x21, 0x01, // $n -= 1
    0x0c, 0x00,              //     br 0
    0x0b, 0x0b,              //   end loop; end block
    0x20, 0x00, 0x0b,        // local.get $p; end
  ];
  const code = [0x02, body0.length, ...body0, body1.length, ...body1];
  const types = [0x02, 0x60, 0x01, 0x7f, 0x01, 0x7f, 0x60, 0x02, 0x7f, 0x7f, 0x01, 0x7f];
  const bytes = [
    0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00,
    0x01, types.length, ...types,
    0x03, 0x03, 0x02, 0x00, 0x01,
    0x04, 0x05, 0x01, 0x70, 0x01, 0x01, 0x01,
    0x07, 0x08, 0x01, 0x04, 0x6c, 0x6f, 0x6f, 0x70, 0x00, 0x01,
    0x09, 0x07, 0x01, 0x00, 0x41, 0x00, 0x0b, 0x01, 0x00,
    0x0a, code.length, ...code,
  ];
  const m = new WebAssembly.Module(new Uint8Array(bytes));
  return new WebAssembly.Instance(m).exports.loop;
}

async function measureBoundary() {
  const loop = boundaryModule();
  const N = 50_000_000;
  let best = Infinity;
  for (let i = 0; i < 5; i++) {
    const t0 = process.hrtime.bigint();
    loop(7, N);
    const t1 = process.hrtime.bigint();
    best = Math.min(best, Number(t1 - t0) / N);
  }
  return best; // ns per call_indirect
}

const WORKLOADS = [
  ['parse', ['parse', '1'], 'construe: one interpret over 4 real contexts'],
  ['parse x20', ['parse', '20'], 'construe: the same, twenty times'],
  ['suggest', ['suggest', '1'], 'construe: suggestion ranking over 4000 candidates'],
];

const wasm = process.argv[2] ?? 'out/cb-diag.wasm';
console.log('region histogram (doc/decisions/0013)');
console.log();

const boundaryNs = await measureBoundary();
console.log(`  one call_indirect taking a pointer, on this host: ${boundaryNs.toFixed(2)} ns`);
console.log();

const S = { FNS: 0, ARITIES: 1, BYTES: 2, INSTRS: 3, CALLSITES: 4,
            BACKTARGETS: 5, MAXINSTRS: 6, TINY: 7, UNKNOWN: 8 };

/// The static half. Re-entry points cost module bytes rather than time -- every
/// value is already in the linear-memory stack, so entering compiled code
/// mid-body is a `br_table` and nothing to reconstruct. So what sizes the chunks
/// is this: how many re-entry points does real code want, against how many
/// instructions there are to amortise them over.
{
  const { module } = await load(wasm);
  const inst = instantiate(module);
  inst.main('parse', '1');
  const g = (k) => Number(inst.exports.stat_static(S[k]));
  const fns = g('FNS'), ar = g('ARITIES'), instrs = g('INSTRS');
  const sites = g('CALLSITES'), back = g('BACKTARGETS');
  console.log('=== the static side — what the metadata would cost');
  if (g('UNKNOWN')) {
    console.log(`  WALK INCOMPLETE: ${g('UNKNOWN')} unknown opcodes -- the numbers below are short`);
  }
  console.log(`  ${num(fns)} functions, ${num(ar)} arities, ${num(g('BYTES'))} bytes of ` +
              `bytecode, ${num(instrs)} instructions`);
  console.log(`  mean ${(instrs / Math.max(1, ar)).toFixed(1)} instructions per arity, ` +
              `largest ${num(g('MAXINSTRS'))}; ${num(g('TINY'))} arities of one instruction ` +
              `(${pct(g('TINY'), ar)}) -- those want inlining, not a call`);
  console.log(`  re-entry points if EVERY call site gets one: ${num(sites + back)} ` +
              `(${num(sites)} call sites + ${num(back)} backward-jump targets), ` +
              `one per ${(instrs / Math.max(1, sites + back)).toFixed(1)} instructions`);
  console.log(`  re-entry points at BACK-EDGES only: ${num(back)}, ` +
              `one per ${(instrs / Math.max(1, back)).toFixed(1)} instructions`);
  console.log();
}

const OPNAME = ['NOP','CONST','NIL','TRUE','FALSE','INT','LOCAL','LOCAL_W','SET_LOCAL',
  'UPVAL','VAR','SET_VAR','POP','DUP','JUMP','JUMP_IF_FALSE','JUMP_IF_TRUE','CALL',
  'TAIL_CALL','RETURN','CLOSURE','NATIVE','THROW','TRY','POP_HANDLER','RETHROW','VECTOR',
  'MAP','SET','LIST','APPLY','JUMP_IF_FALSE_KEEP','JUMP_IF_TRUE_KEEP','POP_N',
  'SET_LOCAL_KEEP','SELF'];

/// Which opcodes an emitter must handle inline is a distribution too, and
/// guessing it is the same mistake as guessing the region length.
function showOps(e, total) {
  const ops = [];
  for (let i = 0; i < 256; i++) {
    const n = Number(e.stat_region(NB * 4 + NCOUNT + i));
    if (n) ops.push([OPNAME[i] ?? `0x${i.toString(16)}`, n]);
  }
  ops.sort((a, b) => b[1] - a[1]);
  let acc = 0;
  console.log('  opcode mix (cumulative share of executed instructions)');
  for (const [name, n] of ops) {
    acc += n;
    console.log(`    ${name.padEnd(20)} ${String(num(n)).padStart(13)}  ` +
                `${pct(n, total).padStart(7)}  cum ${pct(acc, total)}`);
  }
}

const rows = [];
for (const [name, args, desc] of WORKLOADS) {
  const { module } = await load(wasm);
  const inst = instantiate(module);
  const r = inst.main(...args);
  if (r.code !== 0) throw new Error(`${name} failed: ${r.out}`);
  const { frame, run, resumed, counts } = readRegions(inst.exports);
  console.log(`=== ${name} — ${desc}`);
  console.log(`  ${num(counts.INSTRS)} instructions, ${num(counts.FRAME_N)} frame ` +
              `invocations, ${num(counts.RUN_N)} runs`);
  const meanRun = counts.RUN_SUM / Math.max(1, counts.RUN_N);
  const meanFrame = counts.FRAME_SUM / Math.max(1, counts.FRAME_N);
  console.log(`  Model A (region ends at every call):  mean ${meanRun.toFixed(1)} instructions`);
  console.log(`  Model B (guard-only, unit is a frame): mean ${meanFrame.toFixed(1)} instructions`);
  show('Model A — run lengths between calls', run, counts.RUN_N);
  show('Model B — instructions per frame invocation', frame, counts.FRAME_N);
  console.log(`  calls ${num(counts.CALLS)}, tail calls ${num(counts.TAILCALLS)}, ` +
              `natives ${num(counts.NATIVES)}, applies ${num(counts.APPLIES)}`);
  console.log(`  guards executed ${num(counts.GUARDS)} ` +
              `(${pct(counts.GUARDS, counts.INSTRS)} of instructions), ` +
              `fired ${num(counts.GUARD_HITS)} (${pct(counts.GUARD_HITS, counts.GUARDS)})`);
  console.log(`  back-edges taken ${num(counts.BACKEDGES)} ` +
              `(${pct(counts.BACKEDGES, counts.INSTRS)})`);
  console.log(`  resumed frames ${num(counts.RESUMED_FRAMES)}, instructions in them ` +
              `${num(counts.RESUMED_INSTRS)} (${pct(counts.RESUMED_INSTRS, counts.INSTRS)}); ` +
              `state saves ${num(counts.SAVES_PARK)} park / ${num(counts.SAVES_YIELD)} yield`);
  if (name === 'suggest') showOps(inst.exports, counts.INSTRS);
  rows.push({ name, counts, meanRun, meanFrame });
  console.log();
}

// The estimate 0013 asks for: (length - 1) x dispatch - boundary, summed over
// the weighted histogram. Dispatch is the measured 6.2 ns/instruction from the
// README; both models are priced with the SAME boundary constant so the only
// thing that differs is the distribution.
const DISPATCH_NS = 6.2;
console.log('=== the estimate 0013 asks for');
console.log(`  dispatch ${DISPATCH_NS} ns/instruction (README), boundary ` +
            `${boundaryNs.toFixed(2)} ns/entry (measured above)`);
for (const r of rows) {
  const a = (r.meanRun - 1) * DISPATCH_NS - boundaryNs;
  const b = (r.meanFrame - 1) * DISPATCH_NS - boundaryNs;
  const totalA = a * r.counts.RUN_N / 1e6;
  const totalB = b * r.counts.FRAME_N / 1e6;
  const wall = r.counts.INSTRS * DISPATCH_NS / 1e6;
  console.log(`  ${r.name.padEnd(12)} A: ${a.toFixed(1).padStart(7)} ns/region ` +
              `-> ${totalA.toFixed(2).padStart(7)} ms of ${wall.toFixed(2)} ms dispatch ` +
              `(${pct(totalA, wall)})`);
  console.log(`  ${''.padEnd(12)} B: ${b.toFixed(1).padStart(7)} ns/region ` +
              `-> ${totalB.toFixed(2).padStart(7)} ms of ${wall.toFixed(2)} ms dispatch ` +
              `(${pct(totalB, wall)})`);
}

// ---------------------------------------------------------------------------
// The case the three workloads above cannot see.
//
// Every one of them reports `guards fired: 0` and `resumed frames: 0`. That is
// a COVERAGE zero, not a result: construe's fixtures never open a port, so they
// never park, and a design whose whole risk is what happens after a park has not
// been tested by them at all. 0013 names the shape that matters -- "a loop that
// parks per iteration bails on the first iteration and then interprets every
// remaining iteration, for ever" -- and the wave run is exactly that shape: 64
// waves, one park each.
// ---------------------------------------------------------------------------
function makeDoc({ pages, blocks, leaves, leafBytes }) {
  const nodes = []; let id = 0, off = 0; const parts = [];
  const root = { id: id++, type: 'doc', page: 0, box: [0,0,0,0], parent: null, children: [], len: 0, off: 0 };
  nodes.push(root);
  for (let p = 0; p < pages; p++) {
    const pg = { id: id++, type: 'page', page: p, box: [0,0,0,0], parent: root.id, children: [], len: 0, off: 0 };
    root.children.push(pg.id); nodes.push(pg);
    for (let b = 0; b < blocks; b++) {
      const bl = { id: id++, type: 'block', page: p, box: [0,0,0,0], parent: pg.id, children: [], len: 0, off: 0 };
      pg.children.push(bl.id); nodes.push(bl);
      for (let l = 0; l < leaves; l++) {
        const t = `p${p}b${b}l${l}:`.padEnd(leafBytes, '.');
        const lf = { id: id++, type: 'leaf', page: p, box: [0,0,0,0], parent: bl.id, children: [], len: t.length, off };
        bl.children.push(lf.id); nodes.push(lf); parts.push(t); off += t.length;
      }
    }
  }
  return { structure: { root: 0, nodes }, content: new TextEncoder().encode(parts.join('')) };
}

try {
  const doc = makeDoc({ pages: 8, blocks: 8, leaves: 16, leafBytes: 4096 });
  const store = new DocStore(doc.structure, doc.content, { budgetBytes: 65536 });
  const { module } = await load('out/doc-waves.wasm');
  const inst = instantiate(module);
  inst.capabilities({ doc: documentCapability(store, codec) });
  const r = inst.main();
  if (r.code !== 0) throw new Error(r.out);
  const { seg, resumed, counts } = readRegions(inst.exports);
  console.log();
  console.log('=== waves — a loop that parks per iteration (0013\'s pathological case)');
  console.log(`  ${r.out.trim()}`);
  console.log(`  ${num(counts.INSTRS)} instructions, ${num(counts.FRAME_N)} frame invocations`);
  console.log(`  guards executed ${num(counts.GUARDS)}, fired ${num(counts.GUARD_HITS)} ` +
              `(${pct(counts.GUARD_HITS, counts.GUARDS)})`);
  console.log(`  state saves: ${num(counts.SAVES_PARK)} on a port park, ` +
              `${num(counts.SAVES_YIELD)} on a courtesy yield; ` +
              `${num(counts.RESTORES)} restores`);
  console.log(`  resumed frames ${num(counts.RESUMED_FRAMES)}, instructions in them ` +
              `${num(counts.RESUMED_INSTRS)} (${pct(counts.RESUMED_INSTRS, counts.INSTRS)} ` +
              `of all work)`);
  console.log('  Without re-entry points every one of those instructions is');
  console.log('  interpreted: the frame came back mid-body and compiled code can');
  console.log('  only be entered at the top.');
  show('resumed frames — instructions per invocation', resumed, counts.RESUMED_FRAMES);
  const meanSeg = counts.SEG_SUM / Math.max(1, counts.SEG_N);
  console.log(`  mean SEGMENT (entry-or-resume to return-or-park): ${meanSeg.toFixed(1)} ` +
              `instructions over ${num(counts.SEG_N)} segments`);
  show('segments — one contiguous stretch of compiled execution', seg, counts.SEG_N);
  const meanF = counts.FRAME_SUM / Math.max(1, counts.FRAME_N);
  console.log(`  mean instructions per frame invocation (Model B): ${meanF.toFixed(1)}`);
} catch (e) {
  console.log();
  console.log(`  (the parking workload needs out/doc-waves.wasm: ${e.message})`);
}
