// The inspector, exercised on a real heap (doc/decisions/0015).
//
// These assertions are about the two capabilities this project needed for four
// sessions and did not have: whole-heap validation in ONE pass, and reverse
// pointer lookup. Plus the diff, which answers "what did that collection do"
// without instrumenting the collector.
import { load, instantiate } from '../host/flint.mjs';
import * as snap from '../host/snapshot.mjs';

let fails = 0;
const ok = (label, cond, extra) => {
  if (cond) console.log('  ok   ' + label);
  else { fails++; console.log('  FAIL ' + label + (extra ? '\n        ' + extra : '')); }
};

const { module } = await load('out/sn-work.wasm');
const inst = instantiate(module);
const e = inst.exports;

const grab = () => {
  const n = e.flint_snapshot_capture();
  const p = e.flint_snapshot_ptr();
  return new Uint8Array(e.memory.buffer, p, n).slice();
};

inst.main();
const beforeBytes = grab();
e.collect_now();
const afterBytes = grab();

const a = snap.read(beforeBytes);
const b = snap.read(afterBytes);
console.log(`    snapshot ${beforeBytes.length} bytes; heap ${a.heapHi - a.heapLo}, ` +
            `${a.oldChunks.length} old chunk(s), ${a.remembered.length} remembered`);

ok('a snapshot carries the Rust-side state, not just the heap',
   a.frames !== undefined && a.roots.globals.length > 0 && a.interns.length === 4,
   `frames ${a.frames.length} globals ${a.roots.globals.length} interns ${a.interns.length}`);
ok('  ... including the remembered set as a LIST',
   a.remembered instanceof Uint32Array);
ok('  ... and the gas counter, which makes resumption checkable',
   a.steps > 0n, String(a.steps));

// --- validation: one pass, every bad pointer together -----------------------
const va = snap.validate(a);
const vb = snap.validate(b);
console.log(`    validated ${va.walked} objects before, ${vb.walked} after`);
ok('the whole heap validates in one pass, before a collection',
   va.problems.length === 0, JSON.stringify(va.problems.slice(0, 4)));
ok('  ... and after one', vb.problems.length === 0, JSON.stringify(vb.problems.slice(0, 4)));

// --- reverse pointers -------------------------------------------------------
let probe = null;
for (const o of snap.objects(a)) {
  if (o.tyName === 'STR' || o.tyName === 'VEC') {
    const hits = snap.pointersTo(a, o.addr);
    if (hits.length > 0) { probe = { o, hits }; break; }
  }
}
ok('reverse lookup answers "what points at this address"',
   probe !== null && probe.hits.length > 0,
   probe ? '' : 'no object with an inbound pointer found');
if (probe) console.log(`    ${probe.o.tyName}@${probe.o.addr} <- ${probe.hits.length} reference(s), ` +
                       `first ${probe.hits[0].from}`);

// --- the diff ---------------------------------------------------------------
const d = snap.diff(a, b);
console.log(`    across the collection: ${d.minors} minor, ${d.majors} major; ` +
            `${d.moved.length} moved, ${d.gone.length} gone, ${d.appeared.length} appeared`);
ok('diffing two snapshots names what a collection did',
   d.majors === 1 && (d.moved.length > 0 || d.gone.length > 0 || d.changed.length > 0));

// --- the summary ------------------------------------------------------------
const sum = snap.summary(a);
console.log('    largest by bytes: ' +
            sum.objects.slice(0, 4).map(([t, x]) => `${t} ${x.n}/${x.bytes}B`).join(', '));
ok('objects can be listed by type and size', sum.objects.length > 3);

// --- import, through the host path -----------------------------------------
const p = e.flint_snapshot_alloc(beforeBytes.length);
new Uint8Array(e.memory.buffer, p, beforeBytes.length).set(beforeBytes);
ok('a snapshot imports through the host ABI', e.flint_snapshot_restore(beforeBytes.length) === 1);
const again = grab();
ok('  ... and re-capturing gives the same bytes',
   again.length === beforeBytes.length && again.every((x, i) => x === beforeBytes[i]),
   `${again.length} vs ${beforeBytes.length}`);

// --- version refusal --------------------------------------------------------
const bad = beforeBytes.slice();
bad[4] ^= 0xff;
const bp = e.flint_snapshot_alloc(bad.length);
new Uint8Array(e.memory.buffer, bp, bad.length).set(bad);
ok('a snapshot from another layout version is refused',
   e.flint_snapshot_restore(bad.length) === 0);
let refusedByName = false;
try { snap.read(bad); } catch (err) { refusedByName = /layout version/.test(err.message); }
ok('  ... and the reader says so by name', refusedByName);

console.log(fails === 0 ? 'snapshots: ok' : `snapshots: ${fails} FAILURES`);
process.exitCode = fails === 0 ? 0 : 1;
