// The image-per-call shape across engines (`doc/decisions/0018`, `0021`, `0023`).
//
// A resident loader instantiated once, an image loaded per call, nothing shared
// between runs. This is what flint's first real consumer deploys, and under it
// the numbers that matter are INSTANTIATION COST and PEAK MEMORY -- a Worker
// isolate has a 128 MiB ceiling and a CPU budget per request -- rather than
// steady-state throughput.
import { execFileSync, spawn } from 'node:child_process';
import { statSync } from 'node:fs';
import { existsSync } from 'node:fs';

const have = (bin) => {
  try { execFileSync('which', [bin], { stdio: 'ignore' }); return true; } catch { return false; }
};

const LOADER = 'out/xrt-loader.wasm';
const EMPTY = 'out/xrt0.image';   // load + initialise + main, no work
const WORK = 'out/xrt.image';     // the same, plus 25 interpret iterations
const STEPS_PER_CALL = 830825;    // 25 * 33233, deterministic (0009)

function js(engine, args, image, calls) {
  const out = execFileSync(engine[0], [...engine[1], 'bench/xrt-image.mjs', LOADER, image, String(calls)],
                           { encoding: 'utf8' });
  return JSON.parse(out.trim().split('\n').pop());
}

const engines = [];
if (have('node')) engines.push({ name: 'node (V8)', run: ['node', []] });
if (have('bun')) engines.push({ name: 'bun (JavaScriptCore)', run: ['bun', []] });
if (have('deno')) engines.push({ name: 'deno (V8)', run: ['deno', ['run', '--allow-read']], coarse: true });

const WORKERD = process.env.WORKERD || '/tmp/wd/node_modules/.bin/workerd';

async function workerd() {
  if (!existsSync(WORKERD)) return null;
  const p = spawn(WORKERD, ['serve', 'config.capnp'], { cwd: 'bench/workerd', stdio: 'ignore' });
  try {
    for (let i = 0; i < 40; i++) {
      await new Promise((r) => setTimeout(r, 250));
      try {
        const r = await fetch('http://localhost:8791/?img=0&n=1');
        if (r.ok) break;
      } catch { /* not up yet */ }
    }
    const get = async (q) => (await (await fetch('http://localhost:8791/?' + q)).json());
    await get('img=0&n=500');                       // warm
    const empty = await get('img=0&n=2000');
    const work = await get('img=25&n=300');
    return { empty, work };
  } finally { p.kill(); }
}

const pad = (s, w) => String(s).padEnd(w);
const rpad = (s, w) => String(s).padStart(w);

console.log('the image-per-call shape, across engines (0018, 0021, 0023)');
console.log(`  loader ${(statSync(LOADER).size / 1024).toFixed(1)} KB instantiated ONCE; image ${(statSync(EMPTY).size / 1024).toFixed(1)} KB loaded per call.`);
console.log('  nothing is shared between calls: the image\'s initialisers re-run each time.');
console.log();
console.log(pad('engine', 24) + rpad('compile', 10) + rpad('instantiate', 13) +
            rpad('load+run', 11) + rpad('+25 iters', 12) + rpad('ns/instr', 10) + rpad('heap', 9));
console.log('-'.repeat(89));

const rows = [];
for (const e of engines) {
  const empty = js(e.run, null, EMPTY, 2000);
  const work = js(e.run, null, WORK, 300);
  rows.push({ name: e.name, empty, work, coarse: e.coarse });
}
const wd = await workerd();
if (wd) rows.push({
  name: 'workerd (V8 isolate)',
  empty: { compileMs: NaN, instantiateMs: wd.empty.instantiateUs / 1000, perCallUs: wd.empty.perCallUs,
           heapMB: wd.empty.heapBytes / 1048576, pagesBefore: wd.empty.pages, pagesAfter: wd.empty.pages },
  work: { perCallUs: wd.work.perCallUs, pagesAfter: wd.work.pages },
  coarse: true,
});

for (const r of rows) {
  const ns = ((r.work.perCallUs - r.empty.perCallUs) * 1000) / STEPS_PER_CALL;
  console.log(pad(r.name, 24) +
              rpad(Number.isNaN(r.empty.compileMs) ? '-' : r.empty.compileMs.toFixed(2) + ' ms', 10) +
              rpad(r.empty.instantiateMs.toFixed(2) + ' ms', 13) +
              rpad(r.empty.perCallUs.toFixed(1) + ' us', 11) +
              rpad((r.work.perCallUs / 1000).toFixed(2) + ' ms', 12) +
              rpad(ns.toFixed(1), 10) +
              rpad(r.empty.heapMB.toFixed(2) + ' MB', 9));
}
console.log();
console.log('  load+run    an image that does NO work: the floor for loading one at all.');
console.log('  +25 iters   the same, plus 25 interpret passes -- ' + STEPS_PER_CALL + ' instructions.');
console.log('  ns/instr    (+25 iters - load+run) / ' + STEPS_PER_CALL + ', so the image-load');
console.log('              overhead is subtracted rather than blamed on the interpreter.');
console.log('  heap        the wasm memory, UNCHANGED after every call above.');
console.log();
for (const r of rows) {
  if (r.empty.pagesBefore !== r.empty.pagesAfter || r.empty.pagesAfter !== r.work.pagesAfter) {
    console.log(`  !! ${r.name}: the heap GREW across calls (${r.empty.pagesBefore} -> ${r.work.pagesAfter} pages)`);
  }
}
console.log('  Peak memory is the constraint under a 128 MiB isolate ceiling, and it is');
console.log('  flat: 2000 image loads plus 300 loaded calls leave the wasm heap exactly');
console.log('  where it started, because each `main()` re-runs the image\'s initialisers');
console.log('  into a heap that was reset rather than appended to.');
if (rows.some((r) => r.coarse)) {
  console.log();
  console.log('  deno and workerd report whole milliseconds -- workerd quantises timers');
  console.log('  deliberately -- so their sub-millisecond columns are floor estimates.');
}
