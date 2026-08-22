// Benchmark the DECISION, not the runtime (doc/decisions/0007).
//
// The question is not "is flint fast". It is: for each place construe runs code,
// is flint better, worse, or irrelevant — and what does that do to the bill? Two
// facts make it sharp: CPU is 96% of what a session costs, and cherry cannot
// compile inside the deployed Worker.
//
// The incumbent sits beside flint wherever one exists. Expect flint to lose the
// parse-latency comparison against JIT-compiled JS; that is reported plainly,
// because an interpreter in wasm losing to a JIT is not a failure of this
// project, and a benchmark section containing only wins is a marketing page.
import { readFileSync, writeFileSync, mkdirSync, statSync } from 'fs';
import { execFileSync } from 'child_process';
import { load, instantiate } from '../host/flint.mjs';

const CONSTRUE = '/Users/raystubbs/Projects/@3sln/construe/node_modules';
const SRC = 'bench/construe/gen/construe/bench';
const cwd = process.cwd();

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
const bestAsync = async (n, f) => {
  let b = Infinity;
  for (let i = 0; i < n; i++) {
    const t0 = process.hrtime.bigint();
    await f();
    const t1 = process.hrtime.bigint();
    b = Math.min(b, Number(t1 - t0) / 1e6);
  }
  return b;
};
const ms = (x) => (x < 1 ? x.toFixed(3) : x.toFixed(2));
const kb = (x) => (x / 1024).toFixed(0) + ' KB';
const row = (cells, w = [36, 16, 16, 14]) =>
  cells.map((c, i) => String(c).padEnd(w[i] ?? 14)).join('').trimEnd();

/// Compile one of the shared namespaces with cherry and load it.
const { compileString } = await import(`${CONSTRUE}/cherry-cljs/index.js`);
async function cherryModule(name) {
  const src = readFileSync(`${SRC}/${name}.cljc`, 'utf8');
  const js = await compileString(src);
  mkdirSync('out/cherry', { recursive: true });
  const fixed = js.replace(/(['"])cherry-cljs\//g, `$1${CONSTRUE}/cherry-cljs/`);
  const path = `${cwd}/out/cherry/${name}.mjs`;
  writeFileSync(path, fixed);
  return { mod: await import(path), js };
}

console.log('construe benchmarks');
console.log('machine: Apple M1 Pro, Darwin 23.6.0, node ' + process.version);
console.log('fixtures: construe\'s own 258-line seed interpreter and four real');
console.log('          annotated contexts, compiled from ONE source by both');
console.log('          compilers, so this compares runtimes and not code.');
console.log();

// ---------------------------------------------------------------------------
// 4. compilation — against a compiler that does not work in production
// ---------------------------------------------------------------------------
console.log('=== compilation ============================================');
console.log(row(['', 'time', 'output']));

const parseSrc = readFileSync(`${SRC}/parse.cljc`, 'utf8');
let cherryJs = null;
const cherryCompileMs = await bestAsync(5, async () => { cherryJs = await compileString(parseSrc); });
console.log(row(['cherry -> JS', ms(cherryCompileMs) + ' ms', cherryJs.length + ' B']));

const flintCompileMs = best(3, () =>
  execFileSync('./bin/flint', [':src', 'bench/construe/gen', ':fn',
    'construe.bench.main/main', ':out', 'out/cb.wasm'], { stdio: 'ignore' }));
const flintSize = statSync('out/cb.wasm').size;
console.log(row(['flint -> wasm (whole module)', ms(flintCompileMs) + ' ms', flintSize + ' B']));

const flintSelfMs = best(3, () =>
  execFileSync('./bin/flint', [':src', 'bench/construe/gen', ':fn',
    'construe.bench.main/main', ':out', 'out/cb-self.wasm', '--self'], { stdio: 'ignore' }));
console.log(row(['flint -> wasm, compiled BY flint', ms(flintSelfMs) + ' ms',
                 statSync('out/cb-self.wasm').size + ' B']));
console.log('  cherry emits a module that needs its runtime beside it; the flint');
console.log('  number is a COMPLETE module — runtime, collector, core library and');
console.log('  bytecode — so the two sizes are not like for like.');
console.log('  The last row is the one that matters for the blocked case: flint');
console.log('  compiling flint-compiled source, which is what running inside a');
console.log('  deployed artifact would be.');
console.log();

// ---------------------------------------------------------------------------
// 1. parse latency — the number that touches the bill
// ---------------------------------------------------------------------------
console.log('=== parse latency (one interpret over 4 real contexts, warm) ');
console.log(row(['', 'per call', 'vs cherry']));

const { mod: cherryParse } = await cherryModule('parse');
const { module: cbModule } = await load('out/cb.wasm');
const flintInst = instantiate(cbModule);

const flintAnswer = flintInst.main('parse', '1').out;
const cherryAnswer = String(cherryParse.run(1));
if (flintAnswer !== cherryAnswer) {
  throw new Error(`the runtimes disagree: flint ${flintAnswer}, cherry ${cherryAnswer}`);
}

const REPS = 200;
const cherryPer = best(7, () => cherryParse.run(REPS)) / REPS / 4;
const flintPer = best(7, () => flintInst.main('parse', String(REPS))) / REPS / 4;
console.log(row(['cherry -> JS in node (JIT)', ms(cherryPer) + ' ms', '1.0x']));
console.log(row(['flint (wasm interpreter)', ms(flintPer) + ' ms',
                 (flintPer / cherryPer).toFixed(1) + 'x']));
console.log(`  both computed ${flintAnswer} from the same source.`);
console.log();

// The read path calls the artifact ONCE per request, and one flint invocation
// re-runs the module's top-level initialisers. Amortised over 800 parses above
// that vanishes; over one parse it does not, and construe's read path is the
// second shape. Report both or the number is misleading.
const oneShot = best(9, () => flintInst.main('parse', '1'));
const cherryOne = best(9, () => cherryParse.run(1));
console.log(row(['', 'one invocation', 'of which']));
console.log(row(['flint: main() doing ONE parse', ms(oneShot) + ' ms',
                 ms(flintPer * 4) + ' ms parse']));
console.log(row(['cherry: run(1)', ms(cherryOne) + ' ms', ms(cherryPer * 4) + ' ms parse']));
console.log('  the difference between the two flint columns is the module\'s');
console.log('  top-level initialisers, which run again on every `main()` and which');
console.log('  cherry does not pay at all. Small here, and it would matter more');
console.log('  for an artifact with more constants.');
console.log();

// ---------------------------------------------------------------------------
// 2. cold start and footprint — where flint may win big
// ---------------------------------------------------------------------------
console.log('=== cold start and footprint ===============================');
console.log(row(['', 'first answer', 'memory']));

const wasmBytes = readFileSync('out/cb.wasm');
const coldFlint = await bestAsync(7, async () => {
  const mod = await WebAssembly.compile(wasmBytes);
  const inst = instantiate(mod);
  inst.main('parse', '1');
});
const flintMem = flintInst.exports.memory.buffer.byteLength;
flintInst.exports.collect_now();
const flintLive = Number(flintInst.exports.stat_peak_live());
console.log(row(['flint: compile + instantiate + run', ms(coldFlint) + ' ms',
                 kb(flintMem) + ' reserved']));
console.log(row(['', '', kb(flintLive) + ' live']));

let ivm = null;
try { ivm = (await import(`${CONSTRUE}/isolated-vm/isolated-vm.js`)).default; } catch (_) {
  try { ivm = (await import('isolated-vm')).default; } catch (__) { ivm = null; }
}
if (ivm) {
  // The artifact as construe ships it -- cherry's module plus the runtime it
  // imports -- loaded into a fresh isolate. This is the cost of the SANDBOX,
  // not of the parse.
  const artifact = readFileSync(`${cwd}/out/cherry/parse.mjs`, 'utf8')
    .replace(/(['"])[^'"]*cljs\.core\.js\1/, "'cljs.core'");
  // `cherry-cljs/cljs.core.js` is a one-line re-export of `lib/cljs.core.js`,
  // which is the real 300 KB runtime.
  const core = readFileSync(`${CONSTRUE}/cherry-cljs/lib/cljs.core.js`, 'utf8');
  const spin = async () => {
    const isolate = new ivm.Isolate({ memoryLimit: 128 });
    const context = await isolate.createContext();
    const coreMod = await isolate.compileModule(core, { filename: 'cljs.core.js' });
    await coreMod.instantiate(context, (spec) => {
      throw new Error('the runtime imports ' + spec);
    });
    const mod = await isolate.compileModule(artifact + '\nrun(1);', { filename: 'artifact.js' });
    await mod.instantiate(context, (spec) => {
      if (String(spec).includes('cljs.core')) return coreMod;
      throw new Error('unexpected import ' + spec);
    });
    await mod.evaluate();
    return isolate;
  };
  try {
    const probe = await spin();
    const heap = await probe.getHeapStatistics();
    probe.dispose();
    const coldIvm = await bestAsync(7, async () => { (await spin()).dispose(); });
    console.log(row(['V8 isolate: create + load + run', ms(coldIvm) + ' ms',
                     kb(heap.total_heap_size)]));
    console.log('  the isolate figure includes compiling cherry\'s runtime each');
    console.log('  time; a V8 snapshot would cut it, and construe does not use');
    console.log('  one today.');
  } catch (e) {
    console.log('  (isolate path unavailable: ' + String(e.message).split('\n')[0] + ')');
  }
} else {
  console.log('  (isolated-vm not available on this machine)');
}
console.log();

// ---------------------------------------------------------------------------
// 3. the suite run — throughput, and the round's real cost
// ---------------------------------------------------------------------------
console.log('=== the suite: 500 contexts through one warm module ========');
const CASES = 125;   // x4 contexts = 500 parses
const beforeColl = Number(flintInst.exports.stat_collections());
const suiteMs = best(5, () => flintInst.main('parse', String(CASES)));
const afterColl = Number(flintInst.exports.stat_collections());
const cherrySuite = best(5, () => cherryParse.run(CASES));
console.log(row(['flint', ms(suiteMs) + ' ms', ms(suiteMs / 500) + ' ms/case']));
console.log(row(['cherry -> JS', ms(cherrySuite) + ' ms', ms(cherrySuite / 500) + ' ms/case']));
console.log(`  collections during 5 flint runs: ${afterColl - beforeColl}` +
            `, peak live ${kb(Number(flintInst.exports.stat_peak_live()))}`);
console.log();

// ---------------------------------------------------------------------------
// 6. suggest / prefix scan — construe's own unmeasured number
// ---------------------------------------------------------------------------
console.log('=== prefix scan over a 4000-term lexicon ===================');
console.log('    (a REPRESENTATIVE implementation, not construe\'s annotator:');
console.log('     the fixtures here are the seed interpreter and four contexts)');
const { mod: cherrySuggest } = await cherryModule('suggest');
const SUG = 20;
const flintSug = best(5, () => flintInst.main('suggest', String(SUG))) / SUG;
const cherrySug = best(5, () => cherrySuggest.run(4000, SUG)) / SUG;
console.log(row(['flint', ms(flintSug) + ' ms', (flintSug / cherrySug).toFixed(0) + 'x slower']));
console.log(row(['cherry -> JS', ms(cherrySug) + ' ms', '1.0x']));
console.log();

// ---------------------------------------------------------------------------
// the workload in parts
// ---------------------------------------------------------------------------
console.log('=== the workload in parts (per operation, warm) ============');
console.log(row(['', 'flint', 'cherry', 'ratio']));
const { mod: cherryPat } = await cherryModule('patterns');
const patterns = [
  ['deep nested map/vector build', 'nested', 20000],
  ['keyword-keyed map access', 'keys', 200000],
  ['reduce over spans', 'fold', 20000],
  ['into {} over pairs', 'into', 20000],
  ['merge two 5000-key maps', 'merge', 5000],
  ['clojure.string/split, literal', 'split', 20000],
  ['clojure.string/split, regex', 'regex', 5000],
];
for (const [label, which, n] of patterns) {
  const f = best(5, () => flintInst.main(which, String(n))) * 1e6 / n;   // ns/op
  const c = best(5, () => cherryPat.run(which, n)) * 1e6 / n;
  console.log(row([label, f.toFixed(0) + ' ns', c.toFixed(0) + ' ns',
                   (f / c).toFixed(1) + 'x']));
}
console.log();
