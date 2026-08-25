// Hard limits (doc/decisions/0009).
//
// The interpreter costs speed against JIT'd native code; deterministic resource
// limits are a large part of what it buys back. A wall-clock timeout bounds
// *time* and varies with machine load, so a gate built on one is flaky by
// construction. An instruction count bounds *work* and is the same on every
// machine — which is what turns "did this candidate hang?" into a reproducible
// fact. These assertions are therefore about determinism and about proportion,
// not about speed.
import { load, instantiate } from '../host/flint.mjs';

let fails = 0;
const ok = (label, cond, extra) => {
  if (cond) console.log('  ok   ' + label);
  else { fails++; console.log('  FAIL ' + label + (extra ? '\n        ' + extra : '')); }
};
const eq = (label, a, b) => ok(label, a === b, `expected ${JSON.stringify(b)} got ${JSON.stringify(a)}`);

const { module } = await load('out/limits.wasm');
const fresh = () => instantiate(module);
const gas = (inst, n) => inst.exports.set_step_limit(Math.floor(n / 2 ** 32), n >>> 0);

console.log('limits');

// --- the same work costs the same everywhere, every time --------------------
{
  const counts = [];
  for (let i = 0; i < 5; i++) {
    const inst = fresh();
    gas(inst, 1e9);                       // high enough not to fire
    inst.main('work', '200');
    counts.push(Number(inst.exports.stat_steps()));
  }
  eq('the same program reports the same instruction count every run',
     new Set(counts).size, 1);
  console.log(`    ${counts[0]} instructions, five times over`);
  ok('  ... and it is a real number, not zero', counts[0] > 10000, String(counts[0]));
}

// --- a runaway loop stops AT the limit, not near it -------------------------
{
  for (const limit of [500_000, 2_000_000]) {
    const inst = fresh();
    gas(inst, limit);
    const r = inst.main('spin');
    ok(`a runaway loop stops at exactly ${limit}`,
       r.code === 1 && r.out.includes(`spent ${limit} of ${limit}`), r.out.slice(0, 120));
  }
}

// --- the error is catchable, and says what was spent against what ----------
{
  const inst = fresh();
  gas(inst, 1_000_000);
  const r = inst.main('caught');
  eq('the gas error is catchable', r.code, 0);
  ok('  ... and carries spent, limit and thread as data',
     r.out.includes(':spent 1000000') && r.out.includes(':limit 1000000') &&
     r.out.includes(':thread 0'), r.out.slice(0, 200));
}

// --- catching it and carrying on does not defeat the gate -------------------
{
  const inst = fresh();
  gas(inst, 1_000_000);
  const r = inst.main('caught-then-spin');
  eq('a program that catches the error and loops again is still stopped', r.code, 1);
  ok('  ... by an error that escapes every handler',
     r.out.includes('gas limit exceeded'), r.out.slice(0, 160));
}

// --- a native call is one instruction and arbitrary work -------------------
//
// This is the hole the decision is mostly about. Instruction counting bounds
// bytecode; a builtin is one instruction whatever it does. Each of these is ONE
// call, so if the cost were not charged the numbers would be flat.
{
  const measure = (what, n) => {
    const inst = fresh();
    gas(inst, 1e9);
    inst.main(what, String(n));
    return Number(inst.exports.stat_steps());
  };
  for (const [label, what] of [['= over two big vectors', 'eq'],
                               ['hash of a big vector', 'hashing'],
                               ['seq over a big map', 'mapseq'],
                               ['str-join over many pieces', 'joining']]) {
    const small = measure(what, 10_000);
    const big = measure(what, 20_000);
    const ratio = (big - 0) / (small || 1);
    console.log(`    ${label.padEnd(28)} ${small} -> ${big} (${ratio.toFixed(2)}x for 2x the work)`);
    ok(`${label} charges in proportion, not 1`, ratio > 1.5 && ratio < 2.6,
       `${small} then ${big}`);
  }
}

// --- and the one that matters most: catastrophic backtracking --------------
//
// This assertion USED to be that the gas limit stops `(a+)+$`, and that was the
// right answer for a backtracking engine: the bound was exact rather than
// heuristic because the backtracking was itself bytecode.
//
// It is the wrong answer now. `doc/decisions/0012` replaced the backtracker with
// a Pike VM, which never rewinds and deduplicates threads by program counter --
// so the pattern is LINEAR and there is nothing for the limit to stop. The
// hazard is gone rather than mitigated, and the test should say which.
{
  const inst = fresh();
  gas(inst, 3_000_000);
  const r = inst.main('redos');
  eq('a known catastrophic regex now COMPLETES, in a budget that used to stop it',
     r.code, 0);
  ok('  ... with the right answer', r.out.trim() === '0', r.out.slice(0, 160));
  const spent = Number(inst.exports.stat_steps());
  console.log(`    (a+)+$ over 32 a's: ${spent.toLocaleString()} instructions, ` +
              `linear by construction`);
  ok('  ... and well inside a budget that a backtracker exhausted',
     spent < 3_000_000, String(spent));
}

// --- memory: collect first, then a catchable error --------------------------
{
  const inst = fresh();
  inst.exports.set_memory_limit(6 * 1024 * 1024);
  const r = inst.main('eat');
  eq('exceeding the memory limit is a catchable error, not a trap', r.code, 0);
  ok('  ... naming what was held against what was allowed',
     r.out.includes('memory limit exceeded') && r.out.includes(':limit 6291456'),
     r.out.slice(0, 200));
  ok('  ... raised after a collection, with the heap genuinely full',
     inst.exports.stat_heap_used() > 4 * 1024 * 1024,
     String(inst.exports.stat_heap_used()));
}

// --- what the counted loop costs -------------------------------------------
{
  const best = (n, f) => {
    let b = Infinity;
    for (let i = 0; i < n; i++) {
      const t0 = process.hrtime.bigint();
      f();
      const t1 = process.hrtime.bigint();
      b = Math.min(b, Number(t1 - t0) / 1e6);
    }
    return b;
  };
  const free = fresh();
  const counted = fresh();
  gas(counted, 1e12);
  const N = '4000';
  // Interleaved, so a thermal drift partway through cannot be mistaken for the
  // effect being measured.
  let tFree = Infinity;
  let tCounted = Infinity;
  for (let i = 0; i < 15; i++) {
    tFree = Math.min(tFree, best(1, () => free.main('work', N)));
    tCounted = Math.min(tCounted, best(1, () => counted.main('work', N)));
  }
  const steps = Number(counted.exports.stat_steps());
  const overhead = (tCounted / tFree - 1) * 100;
  console.log(`    free loop ${tFree.toFixed(2)} ms, counted ${tCounted.toFixed(2)} ms ` +
              `over ${steps} instructions -> ${overhead.toFixed(1)}% for counting`);
  console.log(`    the second instantiation costs 16 335 bytes of module; see the`);
  console.log(`    README, which reports both halves of that trade.`);
  ok('the two loops are separate instantiations and both give the same answer',
     free.main('work', N).out === counted.main('work', N).out);
  if (Math.abs(overhead) < 3) {
    console.log('    the difference is inside the noise on this machine, which is');
    console.log('    itself the finding: counting is not what makes gas expensive.');
  }
}

console.log(fails === 0 ? 'limits: ok' : `limits: ${fails} FAILURES`);
process.exitCode = fails === 0 ? 0 : 1;
