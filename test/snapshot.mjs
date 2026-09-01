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

inst.run('work/main', []);
const beforeBytes = grab();
e.collect_now();
const afterBytes = grab();

const a = snap.read(beforeBytes);
const b = snap.read(afterBytes);
console.log(`    snapshot ${beforeBytes.length} bytes; ${a.regions.length} region(s), ` +
            `${a.oldChunks.length} old chunk(s), ${a.remembered.length} remembered`);

ok('a snapshot carries the Rust-side state, not just the heap',
   a.frames !== undefined && a.roots.globals.length > 0 && a.interns.length === 4,
   `frames ${a.frames.length} globals ${a.roots.globals.length} interns ${a.interns.length}`);
// A LIST, which is the claim -- the per-object flags travel separately in the
// heap bytes, and this investigation turned on the two being able to disagree.
// Not `Uint32Array` any more: an address is 48 bits since the heap stopped
// being capped at 4 GB, so the container is a plain array of numbers. The
// property being asserted is that the set is carried at all, not what holds it.
ok('  ... including the remembered set as a LIST',
   Array.isArray(a.remembered) || a.remembered instanceof Uint32Array,
   typeof a.remembered);
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

// --- a snapshot from a different PROGRAM -------------------------------------
//
// The layout check above catches a snapshot from another BUILD. This catches
// one from another program of the same build, which is the likelier mistake
// the moment snapshots are files: the header parses, the geometry is
// plausible, and every frame ip in it indexes a different image.
const { module: otherModule } = await load('out/sn-other.wasm');
const other = instantiate(otherModule);
const oe = other.exports;
other.run('other/main', []);
ok('two programs have different image fingerprints',
   oe.flint_image_fingerprint() !== e.flint_image_fingerprint(),
   `${oe.flint_image_fingerprint()} vs ${e.flint_image_fingerprint()}`);
const op = oe.flint_snapshot_alloc(beforeBytes.length);
new Uint8Array(oe.memory.buffer, op, beforeBytes.length).set(beforeBytes);
ok("  ... so one program's snapshot is refused by the other",
   oe.flint_snapshot_restore(beforeBytes.length) === 0);
ok('  ... naming the image, not the layout, as the reason',
   oe.flint_snapshot_refused() === 2, `refused=${oe.flint_snapshot_refused()}`);
// And the control: the refusal is not simply "restore never works".
const sp = e.flint_snapshot_alloc(beforeBytes.length);
new Uint8Array(e.memory.buffer, sp, beforeBytes.length).set(beforeBytes);
ok('  ... while its own program still accepts it',
   e.flint_snapshot_restore(beforeBytes.length) === 1 && e.flint_snapshot_refused() === 0);

// --- the LIVE-SET export: relocatable, and no dead objects in it -------------
//
// `flint_snapshot_capture` copies the heap verbatim, which carries dead objects
// and unused reserve and can only be restored to identical addresses. That is
// the right instrument for a post-mortem and the wrong one for SHELVING a
// sandbox. `flint_snapshot_export` walks the live set instead -- and the walk is
// the COLLECTOR's, not a second one written here, which is what keeps
// `doc/decisions/0015`'s objection to traversals from applying.
const { module: liveModule } = await load('out/sn-work.wasm');
const live = instantiate(liveModule);
const le = live.exports;
const answer = live.run('work/main', []);
const exportBytes = (() => {
  const n = le.flint_snapshot_export();
  return new Uint8Array(le.memory.buffer, le.flint_snapshot_ptr(), n).slice();
})();
const verbatim = (() => {
  const n = le.flint_snapshot_capture();
  return new Uint8Array(le.memory.buffer, le.flint_snapshot_ptr(), n).slice();
})();
ok('the live set exports at all', exportBytes.length > 0);
console.log(`    live set ${exportBytes.length} bytes against ${verbatim.length} verbatim ` +
            `(${(100 * exportBytes.length / verbatim.length).toFixed(1)}%)`);
ok('  ... and is far smaller than a copy of the heap',
   exportBytes.length * 4 < verbatim.length,
   `${exportBytes.length} vs ${verbatim.length}`);

// Into a SEPARATE instance, which is the point: the verbatim format restores to
// identical addresses and cannot cross an instance at all.
const { module: freshModule } = await load('out/sn-work.wasm');
const fresh = instantiate(freshModule);
const fe = fresh.exports;
const ip = fe.flint_snapshot_alloc(exportBytes.length);
new Uint8Array(fe.memory.buffer, ip, exportBytes.length).set(exportBytes);
ok('a live set imports into a DIFFERENT instance',
   fe.flint_snapshot_import(exportBytes.length) === 1,
   `refused=${fe.flint_snapshot_refused()}`);

// The state really came across: re-exporting the imported state gives the same
// bytes. Address order is the walk order, so this is only true if every object
// and every pointer arrived.
const reExport = (() => {
  const n = fe.flint_snapshot_export();
  return new Uint8Array(fe.memory.buffer, fe.flint_snapshot_ptr(), n).slice();
})();
ok('  ... and re-exporting the imported state gives the same bytes',
   reExport.length === exportBytes.length && reExport.every((x, i) => x === exportBytes[i]),
   `${reExport.length} vs ${exportBytes.length}`);

// And a live set from another program is refused, exactly as a verbatim one is.
const op2 = oe.flint_snapshot_alloc(exportBytes.length);
new Uint8Array(oe.memory.buffer, op2, exportBytes.length).set(exportBytes);
ok('  ... and another program refuses it, naming the image',
   oe.flint_snapshot_import(exportBytes.length) === 0 && oe.flint_snapshot_refused() === 2);

// --- export and stop --------------------------------------------------------
const { module: shelfModule } = await load('out/sn-work.wasm');
const shelf = instantiate(shelfModule);
const se = shelf.exports;
shelf.run('work/main', []);
const shelved = se.flint_snapshot_export_and_stop();
ok('export-and-stop returns a snapshot', shelved > 0);
// The control first: a plain export must NOT stop anything, or the assertion
// below would pass for a reason that has nothing to do with stopping.
ok('  ... and a plain export leaves the sandbox running',
   le.flint_snapshot_stopped() === 0);
ok('  ... while export-and-stop leaves it with nothing runnable',
   se.flint_snapshot_stopped() === 1);

// --- does a snapshot carry a PARKED GREEN THREAD? ---------------------------
//
// Structurally it should: a parked thread's saved value stack and frame record
// are ordinary heap objects reachable from the scheduler singleton, and the
// RUNNING thread's frames are written out explicitly beside the heap. Nothing
// checked it until now.
{
  const { module: pm } = await load('out/sn-parked.wasm');
  const pinst = instantiate(pm);
  const pe = pinst.exports;
  const answer = pinst.run('parked/main', []);
  // The program returns the snapshot's LENGTH, because that is the only way
  // the host learns it: `snapshot!` is a guest builtin and hands the count back
  // to the guest, while `flint_snapshot_capture` would capture NOW -- after the
  // workers have finished, which is the state this is trying not to look at.
  const m = /^\{:snap (\d+), :a 42, :b 6\}$/.exec(answer.out.trim());
  ok('a program with parked threads runs', !!m, answer.out.trim());
  const n = m ? Number(m[1]) : 0;
  const bytes = new Uint8Array(pe.memory.buffer, pe.flint_snapshot_ptr(), n).slice();
  const st = snap.read(bytes);
  const threads = [...snap.objects(st)].filter((o) => o.tyName === 'THREAD');
  ok('  ... and the snapshot taken while they were parked carries them',
     threads.length >= 2, `found ${threads.length} THREAD objects`);
  // A parked thread's continuation is its SAVED VALUE STACK and its frame
  // record, both hanging off the thread object. Read them off the threads
  // themselves rather than trusting a type census: `RAW` and `NODE` are used
  // for plenty of other things, and finding one proves nothing.
  const withState = threads.filter((t) => {
    const sl = snap.slots(st, t.addr);
    return sl.some((v) => snap.heapAddr(v) !== null);
  });
  ok('  ... with their saved stacks and frame records attached',
     withState.length >= 2, `${withState.length} of ${threads.length} carry state`);
  ok('  ... and the running thread\'s own frames, beside the heap',
     st.frames.length > 0, `${st.frames.length} frames`);
}

console.log(fails === 0 ? 'snapshots: ok' : `snapshots: ${fails} FAILURES`);
process.exitCode = fails === 0 ? 0 : 1;
