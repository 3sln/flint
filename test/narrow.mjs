// Which forms does occurrence narrowing compose with?
//
// One number per shape: how many `flint/check-tag` calls survived. Every shape
// is the same program -- guard a value, annotate it inside the guard -- and the
// guard always succeeds, so any check that runs is a check that was not needed.
// Zero means the branch was compiled knowing the type; one means it was not.
import { load, instantiate } from '../host/flint.mjs';

const NB = 20, NCOUNT = 28;
const NAT_AT = NB * 4 + NCOUNT + 256;

const { module } = await load(process.argv[2] ?? 'out/narrow.wasm');

const nativeName = (e, i) => {
  let s = '';
  for (let k = 0; k < 64; k++) {
    const c = Number(e.stat_native_name(i, k));
    if (!c) break;
    s += String.fromCharCode(c);
  }
  return s;
};

const checksIn = (shape) => {
  const inst = instantiate(module);
  const r = inst.main(shape);
  if (r.code !== 0) throw new Error(`${shape}: ${r.out}`);
  const e = inst.exports;
  let n = 0, sawAny = 0;
  for (let i = 0; i < 512; i++) {
    const c = Number(e.stat_region(NAT_AT + i));
    if (!c) continue;
    sawAny += c;
    if (nativeName(e, i) === 'flint/check-tag') n += c;
  }
  return { checks: n, natives: sawAny, out: r.out };
};

const shapes = JSON.parse(
  instantiate(module).main('list').out.replace(/[\[\]]/g, (m) => m).replace(/ /g, ', '));

let fails = 0;
console.log('occurrence narrowing: which forms does it compose with?');
console.log();
console.log(`    ${'guard shape'.padEnd(14)} ${'checks left'.padStart(11)}   verdict`);

const expected = {
  // The forms that must narrow. Anything here regressing is a real loss.
  plain: 0, and2: 0, and3: 0, 'and-nested': 0, 'or-same': 0, 'or-in-and': 0,
  when: 0, cond: 0, 'if-not': 0, 'when-not': 0, 'nested-if': 0, let: 0,
  'and-two': 0,
  // `not` crosses intact, via `:result-inverts` on `clojure.core/not`.
  not: 0,
  // Soundness: the branch a guard does NOT prove must still check. These run
  // with a value that TAKES that branch, so the check fires and is counted.
  'not-wrong-side': 1, 'and-wrong-side': 1,
  // The else branch of a successful guard is not entered at all here.
  else: 0,
};

const results = {};
for (const s of shapes) {
  const r = checksIn(s);
  results[s] = r;
  const want = expected[s];
  const good = r.checks === want;
  if (!good) fails++;
  const verdict = r.checks === 0 ? 'narrowed' :
                  `NOT narrowed (${r.checks} check${r.checks > 1 ? 's' : ''})`;
  console.log(`    ${s.padEnd(14)} ${String(r.checks).padStart(11)}   ${verdict}` +
              (good ? '' : `   <-- expected ${want}`));
}

console.log();
// A coverage check before any zero is believed: if the probe never ran a
// builtin at all, every count above is zero for the wrong reason.
const ranSomething = Object.values(results).every((r) => r.natives > 0);
if (!ranSomething) { fails++; console.log('  FAIL a shape executed no builtins at all -- the zeros are vacuous'); }
else console.log('  ok   every shape executed real code, so the zeros mean something');

const answers = Object.entries(results);
const bad = answers.filter(([s, r]) =>
  s.endsWith('wrong-side')
    ? !r.out.includes('declared ^int')
    : !(r.out === '42' || r.out === '0' || r.out === '82'));
if (bad.length) {
  fails++;
  console.log('  FAIL a shape returned something unexpected: ' +
              bad.map(([s, r]) => `${s}=${r.out}`).join(' '));
} else {
  console.log('  ok   every shape computed what it should, and the two ' +
              'wrong-side shapes threw');
}

process.exit(fails ? 1 : 0);
