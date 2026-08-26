// Where does construe's time actually go?
//
// Three instruments, none of them a guess:
//
//  * the opcode census, so the shape of the work is counted rather than
//    assumed;
//  * the per-builtin census, so "natives" is broken into WHICH natives;
//  * and the AOT build beside the plain one, because the difference between
//    them IS the dispatch share -- compiled code removes dispatch and changes
//    nothing else, so subtracting gives it directly.
//
// The unit that matters is what it costs to REACH a builtin, and the first
// version of this file got that badly wrong. It used 22.7 ns, the figure
// `bench/specialised.mjs` measures for replacing a NATIVE `add` with an inline
// ADD_INT -- but that number includes the arithmetic itself, which the inline
// form also stops doing. The protocol alone is what replacing a NATIVE
// predicate with a TYPE_P opcode saves, since the body is identical either
// way, and on these fixtures that is 4.4 to 8.5 ns. Using 22.7 attributed 49%
// of suggest to the call protocol; it is nearer 13%.
//
// So this file no longer multiplies by a constant. It builds the SAME module
// with the predicate opcode on and off and subtracts, which is a measurement
// on the workload rather than an estimate carried in from another one.
import { load, instantiate } from '../host/flint.mjs';

const NB = 20, NCOUNT = 28, C = NB * 4, OPS_AT = C + NCOUNT, NAT_AT = OPS_AT + 256;


const OPNAME = ['NOP','CONST','NIL','TRUE','FALSE','INT','LOCAL','LOCAL_W','SET_LOCAL',
  'UPVAL','VAR','SET_VAR','POP','DUP','JUMP','JUMP_IF_FALSE','JUMP_IF_TRUE','CALL',
  'TAIL_CALL','RETURN','CLOSURE','NATIVE','THROW','TRY','POP_HANDLER','RETHROW','VECTOR',
  'MAP','SET','LIST','APPLY','JUMP_IF_FALSE_KEEP','JUMP_IF_TRUE_KEEP','POP_N',
  'SET_LOCAL_KEEP','SELF','ADD_INT','SUB_INT','MUL_INT','LT_INT','LE_INT','GT_INT',
  'GE_INT','EQ_INT'];

// Builtins whose whole body is a tag test or one slot read. Reaching one costs
// more than running it, so every call is nearly pure protocol.
const TRIVIAL = new Set(['nil?', 'string?', 'keyword?', 'symbol?', 'number?', 'int?',
  'float?', 'boolean?', 'vector?', 'map?', 'set?', 'seq?', 'fn?', 'sequential?',
  'identical?', 'flint/map-entry?']);
// Cheap, but with a real body: a type dispatch and a load or two.
const SEQ = new Set(['first', 'next', 'rest', 'seq', 'count', 'nth', 'get', 'cons',
  'conj', 'conj!', 'flint/lazy-seq']);

const nativeName = (e, i) => {
  let s = '';
  for (let k = 0; k < 64; k++) {
    const c = Number(e.stat_native_name(i, k));
    if (!c) break;
    s += String.fromCharCode(c);
  }
  return s;
};

const time = async (file, args) => {
  const { module } = await load(file);
  let best = Infinity;
  for (let k = 0; k < 5; k++) {
    const inst = instantiate(module);
    inst.exports.set_step_limit(0, 1e12);
    const t0 = process.hrtime.bigint();
    const r = inst.main(...args);
    const t1 = process.hrtime.bigint();
    if (r.code !== 0) throw new Error(`${file}: ${r.out}`);
    best = Math.min(best, Number(t1 - t0) / 1e6);
  }
  return best;
};

const census = async (args) => {
  const { module } = await load('out/cb-diag.wasm');
  const inst = instantiate(module);
  inst.exports.set_step_limit(0, 1e12);
  inst.main(...args);
  const e = inst.exports;
  const g = (k) => Number(e.stat_region(k));
  const ops = [];
  for (let i = 0; i < 256; i++) if (g(OPS_AT + i)) ops.push([OPNAME[i] ?? `0x${i.toString(16)}`, g(OPS_AT + i)]);
  ops.sort((a, b) => b[1] - a[1]);
  const nats = [];
  for (let i = 0; i < 512; i++) if (g(NAT_AT + i)) nats.push({ name: nativeName(e, i), n: g(NAT_AT + i) });
  nats.sort((a, b) => b.n - a.n);
  return { total: g(C), calls: g(C + 2) + g(C + 3), natives: g(C + 4),
           // Counted as an OPCODE: once a predicate is specialised it is no
           // longer a native call and vanishes from the builtin census. The
           // first version of this looked for it there and found a fifth of
           // the real number, which made the per-call cost look 6x too big.
           typeP: g(OPS_AT + 0x2C), ops, nats };
};

const num = (n) => n.toLocaleString('en-US');
const pct = (a, b) => (100 * a / b).toFixed(1) + '%';

console.log('where construe spends its time');
console.log();

for (const [name, hot, cold] of [['parse', ['parse', '20'], ['parse', '3']],
                                 ['suggest', ['suggest', '1'], ['suggest', '1']]]) {
  const plain = await time('out/cb.wasm', hot);
  const aot = await time('out/cb-aot.wasm', hot);
  const c = await census(cold);
  const scale = name === 'parse' ? 20 / 3 : 1;   // census runs fewer reps
  const natives = c.natives * scale;
  const calls = c.calls * scale;
  const total = c.total * scale;

  console.log(`=== ${name}   ${plain.toFixed(1)} ms interpreted, ${aot.toFixed(1)} ms compiled`);
  console.log(`  ${num(Math.round(total))} instructions, ${num(Math.round(natives))} native calls, ` +
              `${num(Math.round(calls))} Clojure calls`);
  console.log(`  dispatch          ${(plain - aot).toFixed(1).padStart(6)} ms  ` +
              `${pct(plain - aot, plain).padStart(6)}   (what compiling removes, and nothing else)`);
  // Measured: the same module with the predicate opcode emitted and not. The
  // body is identical either way, so the difference is purely what it costs to
  // REACH a builtin -- and that per-call figure is then scaled to all natives.
  const noTp = await time('out/cb-notp.wasm', hot);
  const preds = c.typeP * scale;
  const perCall = (noTp - plain) * 1e6 / preds;
  const protocolMs = natives * perCall / 1e6;
  console.log(`  native protocol   ${protocolMs.toFixed(1).padStart(6)} ms  ` +
              `${pct(protocolMs, plain).padStart(6)}   ` +
              `(${num(Math.round(natives))} x ${perCall.toFixed(1)} ns, MEASURED on ` +
              `${num(Math.round(preds))} predicates)`);
  console.log(`  the rest          ${(plain - (plain - aot) - protocolMs).toFixed(1).padStart(6)} ms  ` +
              `${pct(plain - (plain - aot) - protocolMs, plain).padStart(6)}   ` +
              `(builtin BODIES, the Clojure call protocol, allocation)`);

  let seq = 0;
  for (const x of c.nats) if (SEQ.has(x.name)) seq += x.n;
  console.log(`  of those native calls:`);
  console.log(`    ${num(Math.round(preds))} are a tag test, already an opcode ` +
              `rather than a call`);
  console.log(`    ${pct(seq, c.natives).padStart(6)} are sequence access -- ` +
              `${num(Math.round(seq * scale))} calls`);
  console.log(`  the eight that carry it:`);
  for (const x of c.nats.slice(0, 8)) {
    const kind = TRIVIAL.has(x.name) ? 'tag test' : SEQ.has(x.name) ? 'sequence' : '';
    console.log(`    ${x.name.padEnd(18)} ${num(Math.round(x.n * scale)).padStart(9)}  ` +
                `${pct(x.n, c.natives).padStart(6)}  ${kind}`);
  }
  console.log();
}
