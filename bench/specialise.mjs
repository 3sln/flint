// Where would a specialising compiler actually pay? (doc/decisions/0013's
// "what would make it win", turned into a distribution.)
//
// 0013 ends by naming three things the emitter does not do — type
// specialisation, unboxed locals, inlining — and says specialisation is "the
// largest single item on this list". That is an argument. This is the census
// that sizes it, and it is deliberately capable of saying no:
//
//   * A native call is only worth specialising if its operands are actually
//     the shape the specialisation assumes. So NATIVE_FIX counts, per builtin,
//     the calls whose every argument was a fixnum — read off the real operands
//     at the real call, not inferred from the source.
//   * A builtin that is 100% fixnum and runs 40 times is not evidence. The
//     ordering below is by SHARE OF EXECUTED INSTRUCTIONS, so a rare-but-pure
//     builtin sorts where it belongs, which is nowhere.
//   * The ceiling is reported against total instructions, so "specialise
//     everything perfectly" has a number and it is less than 100%.
import { load, instantiate } from '../host/flint.mjs';

const NB = 20, NCOUNT = 28;
const OPS_AT = NB * 4 + NCOUNT;
const NAT_AT = OPS_AT + 256;
const FIX_AT = NAT_AT + 512;
const C = { INSTRS: 0, FRAMES: 1, CALLS: 2, TAILCALLS: 3, NATIVES: 4, APPLIES: 5 };

const num = (n) => n.toLocaleString('en-US');
const pct = (a, b) => (b ? (100 * a / b).toFixed(1) : '0.0') + '%';

const OPNAME = ['NOP','CONST','NIL','TRUE','FALSE','INT','LOCAL','LOCAL_W','SET_LOCAL',
  'UPVAL','VAR','SET_VAR','POP','DUP','JUMP','JUMP_IF_FALSE','JUMP_IF_TRUE','CALL',
  'TAIL_CALL','RETURN','CLOSURE','NATIVE','THROW','TRY','POP_HANDLER','RETHROW','VECTOR',
  'MAP','SET','LIST','APPLY','JUMP_IF_FALSE_KEEP','JUMP_IF_TRUE_KEEP','POP_N',
  'SET_LOCAL_KEEP','SELF'];

function nativeName(e, idx) {
  let s = '';
  for (let i = 0; i < 64; i++) {
    const c = Number(e.stat_native_name(idx, i));
    if (!c) break;
    s += String.fromCharCode(c);
  }
  return s || `#${idx}`;
}

const WORKLOADS = [
  ['parse',   ['parse', '3'],   'construe: interpret 4 real contexts, 3x'],
  ['suggest', ['suggest', '1'], 'construe: rank 4000 candidates'],
];

const wasm = process.argv[2] ?? 'out/cb-diag.wasm';
console.log('what a specialising compiler would find (0013, "what would make it win")');
console.log();

const totals = [];
for (const [name, args, desc] of WORKLOADS) {
  const { module } = await load(wasm);
  const inst = instantiate(module);
  const r = inst.main(...args);
  if (r.code !== 0) throw new Error(`${name} failed: ${r.out}`);
  const e = inst.exports;
  const g = (i) => Number(e.stat_region(i));
  const instrs = g(NB * 4 + C.INSTRS);

  console.log(`=== ${name} — ${desc}`);
  console.log(`  ${num(instrs)} instructions executed`);

  const ops = [];
  for (let i = 0; i < 256; i++) {
    const n = g(OPS_AT + i);
    if (n) ops.push([OPNAME[i] ?? `0x${i.toString(16)}`, n]);
  }
  ops.sort((a, b) => b[1] - a[1]);
  console.log('  opcode mix — the top eight');
  let acc = 0;
  for (const [nm, n] of ops.slice(0, 8)) {
    acc += n;
    console.log(`    ${nm.padEnd(20)} ${String(num(n)).padStart(13)}  ${pct(n, instrs).padStart(7)}`);
  }
  console.log(`    ${'(the rest)'.padEnd(20)} ${String(num(instrs - acc)).padStart(13)}  ` +
              `${pct(instrs - acc, instrs).padStart(7)}`);

  const nats = [];
  let natTotal = 0, fixTotal = 0;
  for (let i = 0; i < 512; i++) {
    const n = g(NAT_AT + i), f = g(FIX_AT + i);
    if (!n) continue;
    natTotal += n; fixTotal += f;
    nats.push({ name: nativeName(e, i), n, f });
  }
  nats.sort((a, b) => b.n - a.n);

  // Two independent counters of the same event: `call_native` increments the
  // per-builtin census, the dispatch loop increments the opcode census. If they
  // disagree, one of the two readouts is misaligned -- which is exactly how the
  // four-slot shift in this file's offset was found, so it is now asserted
  // rather than eyeballed.
  // A builtin is reached two ways: the NATIVE opcode, which names it
  // statically, and a generic CALL on a native reached as a VALUE -- `(map inc
  // xs)` passes `inc` itself. The per-builtin census counts both, the opcode
  // census counts only the first, so the census must be the larger. If it is
  // smaller, one of the two readouts is misaligned, which is exactly how the
  // four-slot shift in this file's offset was found.
  const natOp = g(OPS_AT + 0x15);
  if (natTotal < natOp) {
    throw new Error(`census misaligned: NATIVE opcode ${num(natOp)} but only ` +
                    `${num(natTotal)} native calls -- the census cannot be the smaller`);
  }
  console.log(`  ${num(natTotal)} native calls (${pct(natTotal, instrs)} of instructions); ` +
              `${num(fixTotal)} had every argument a fixnum (${pct(fixTotal, natTotal)})`);
  console.log(`  of those, ${num(natTotal - natOp)} were reached as a VALUE rather than by the ` +
              `NATIVE\n  opcode (${pct(natTotal - natOp, natTotal)}) -- a static specialiser cannot see those`);
  console.log('  the builtins that carry it, and how often the operands are what a');
  console.log('  fixnum specialisation would assume:');
  console.log(`    ${'builtin'.padEnd(24)} ${'calls'.padStart(12)} ${'of instrs'.padStart(10)} ` +
              `${'all-fixnum'.padStart(11)}`);
  let shown = 0;
  for (const x of nats) {
    if (x.n / natTotal < 0.01) break;
    shown += x.n;
    console.log(`    ${x.name.padEnd(24)} ${String(num(x.n)).padStart(12)} ` +
                `${pct(x.n, instrs).padStart(10)} ${pct(x.f, x.n).padStart(11)}`);
  }
  console.log(`    ${`(${nats.length} builtins, ${num(natTotal - shown)} calls below 1%)`.padEnd(24)}`);
  totals.push({ name, instrs, natTotal, fixTotal, nats });
  console.log();
}

console.log('=== the ceiling, stated so it can be wrong');
console.log('  Specialising every all-fixnum native call perfectly removes the CALL and');
console.log('  nothing else. Against total executed instructions that is:');
for (const t of totals) {
  console.log(`    ${t.name.padEnd(10)} ${pct(t.fixTotal, t.instrs)} of instructions ` +
              `(${num(t.fixTotal)} of ${num(t.instrs)})`);
}
