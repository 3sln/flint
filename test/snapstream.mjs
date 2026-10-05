// A HOST-REQUESTED, STREAMED SNAPSHOT on wasm, through a PRODUCTION module
// (`DECISIONS.md#snapshots`). Driven by `test/snapstream.clj`, which compiles
// `test/snapstream/snap.cljc` -- the same fixture native, the JVM and the CLR
// run -- into `out/snapstream.wasm`.
//
// By hand at the ABI, not through the SDK, because the protocol is what is
// asserted: which events come out, in which order, carrying which bytes.
//
//     node test/snapstream.mjs <module.wasm> [<native.stream>]
import { load, instantiate } from '../host/flint.mjs';
import { codec } from '../sdks/esm/src/codec.js';
import { readFileSync, writeFileSync } from 'node:fs';

let fails = 0;
const ok = (label, cond, extra) => {
  if (cond) console.log('  ok   ' + label);
  else { fails++; console.log('  FAIL ' + label + (extra ? '\n        ' + extra : '')); }
};

const [path, nativeStream] = process.argv.slice(2);
const { module } = await load(path);

const EV_MESSAGE = 2, EV_CLOSED = 3;
const SYS = 1000, CALLS = 1001, DEST = 1002;
const kw = codec.kw;

function sandbox() {
  const e = instantiate(module).exports;
  const put = (bytes) => {
    const at = e.flint_in_alloc(bytes.length);
    new Uint8Array(e.memory.buffer).set(bytes, at);
    return bytes.length;
  };
  const drain = () => {
    const n = e.flint_drain();
    const base = e.flint_events_ptr();
    const dv = new DataView(e.memory.buffer, base, n * 20);
    const mem = new Uint8Array(e.memory.buffer);
    const out = [];
    for (let i = 0; i < n; i++) {
      const at = i * 20;
      const off = dv.getUint32(at + 12, true), len = dv.getUint32(at + 16, true);
      out.push({ kind: dv.getUint32(at, true), a: dv.getUint32(at + 4, true),
                 data: mem.slice(base + off, base + off + len) });
    }
    return out;
  };
  /// Pump until nothing more happens, collecting every event.
  const pump = () => {
    const all = [];
    for (let i = 0; i < 1000; i++) {
      e.flint_resume();
      const evs = drain();
      if (!evs.length) break;
      all.push(...evs);
    }
    return all;
  };
  const deliver = (port, val) => e.flint_deliver(port, put(val.encode()));
  const sb = {
    e, pump, deliver,
    boot() {
      const label = new TextEncoder().encode('system');
      e.flint_install_port(SYS, put(label), 1);
      deliver(SYS, codec.map([[kw('op'), kw('bind')], [kw('port'), codec.port(CALLS)]]));
      pump();
      return sb;
    },
    call(tx, f) {
      deliver(CALLS, codec.map([[kw('tx'), codec.int(tx)], [kw('op'), kw('call')],
                                [kw('fn'), codec.str('snap/' + f)], [kw('args'), codec.vec([])]]));
      for (const ev of pump()) {
        if (ev.kind === EV_MESSAGE && ev.a === CALLS) {
          const v = codec.decode(ev.data);
          if (v[':tx'] === tx) return v[':op'] === ':return' ? v[':value'] : { threw: v };
        }
      }
      return { unanswered: f };
    },
    /// Ask for a snapshot; answer the chunks, the terminator, and whether the
    /// destination was closed after it.
    request() {
      deliver(SYS, codec.map([[kw('op'), kw('snapshot')], [kw('port'), codec.port(DEST)]]));
      const chunks = []; let end = null, closed = false, order = true;
      for (const ev of pump()) {
        if (ev.a !== DEST) continue;
        if (ev.kind === EV_CLOSED) closed = true;
        else if (ev.kind === EV_MESSAGE) {
          if (closed) order = false;
          const v = codec.decode(ev.data);
          if (v instanceof Uint8Array) { if (end) order = false; chunks.push(v); } else end = v;
        }
      }
      return { chunks, end, closed, order };
    },
    importLive(bytes) {
      return e.flint_live_import(put(bytes));
    },
  };
  return sb;
}

const concat = (cs) => {
  const out = new Uint8Array(cs.reduce((n, c) => n + c.length, 0));
  let at = 0;
  for (const c of cs) { out.set(c, at); at += c.length; }
  return out;
};
const same = (a, b) => a.length === b.length && a.every((x, i) => x === b[i]);

console.log('a host-requested snapshot, streamed, on wasm');

const a = sandbox().boot();
ok('the original counts', a.call(1, 'bump') === 1);
const { chunks, end, closed, order } = a.request();
const stream = concat(chunks);
if (process.env.FLINT_SNAPSTREAM_OUT) writeFileSync(process.env.FLINT_SNAPSTREAM_OUT, stream);
ok(`the live set streams in ${chunks.length} chunks, ${stream.length} bytes`,
   chunks.length >= 2 && chunks.slice(0, -1).every((c) => c.length === 65536),
   chunks.map((c) => c.length).join(' '));
ok('the terminator names the byte count', end && end[':op'] === ':end' && end[':size'] === stream.length,
   JSON.stringify(end));
ok('the destination is closed after the terminator, and nothing follows', closed && order);
ok('the original carries on', a.call(2, 'bump') === 2);

// The copy: a FRESH instance of the same production module.
const b = sandbox();
ok('a fresh instance imports the concatenated stream', b.importLive(stream) === 0);
const evs = b.pump();
ok('the copy does not stream itself again',
   evs.every((ev) => !(ev.a === DEST && (ev.kind === EV_MESSAGE || ev.kind === EV_CLOSED))));
ok("the copy's counter is the snapshot's", b.call(3, 'bump') === 2);
ok("the copy's ballast came across", b.call(4, 'sizes') === 30000);
const again = b.request();
ok('the copy answers a snapshot request of its own', again.chunks.length > 0 && again.closed);
ok('a truncated stream is refused as another layout', sandbox().importLive(stream.slice(0, 8)) === 1);

if (nativeStream) {
  const nat = new Uint8Array(readFileSync(nativeStream));
  const c = sandbox();
  const why = c.importLive(nat);
  ok('a stream NATIVE wrote imports here', why === 0, `refused: ${why}`);
  if (why === 0) {
    c.pump();
    ok("  ... and the copy carries on from native's snapshot", c.call(5, 'bump') === 2);
  }
  // ONE RUNTIME, TWO DOORS: the same image and the same host script stream
  // the same bytes. They once differed in one -- the status a drive had left
  // behind -- which `serve_snapshot` now writes as a constant.
  ok("wasm streams exactly the bytes native streams", same(stream, nat),
     `${stream.length} vs ${nat.length}`);
}

// THE GUEST CANNOT: every route to the builtin is refused, and a `:snapshot`
// the guest sends on the system port goes OUT to the host.
const g = sandbox().boot();
let tx = 10;
for (const f of ['steal', 'steal-chunk', 'steal-by-value', 'steal-on-a-thread']) {
  const v = g.call(tx++, f);
  ok(`guest code cannot take a snapshot: ${f}`,
     typeof v === 'string' && v.includes('guest code cannot take one'), JSON.stringify(v));
}
g.deliver(CALLS, codec.map([[kw('tx'), codec.int(tx++)], [kw('op'), kw('call')],
                            [kw('fn'), codec.str('snap/ask-on-the-system-port')],
                            [kw('args'), codec.vec([])]]));
const gevs = g.pump();
ok("a guest's :snapshot on the system port leaves for the host",
   gevs.some((ev) => ev.kind === EV_MESSAGE && ev.a === SYS));
ok('  ... and nothing is exported for it',
   gevs.every((ev) => !(ev.kind === EV_MESSAGE && codec.decode(ev.data) instanceof Uint8Array)));
const control = g.request();
ok("THE CONTROL: the host's own request on that sandbox is served",
   control.chunks.length > 0 && control.closed);

if (fails) { console.log(`${fails} failed`); process.exit(1); }
