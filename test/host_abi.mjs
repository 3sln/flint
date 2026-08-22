// The host ABI: tokens, one event queue, and the two lifetimes
// (doc/decisions/0006). Driven from test/host_abi.clj, which compiles the
// modules this exercises.
import { load, instantiate } from '../host/flint.mjs';

let fails = 0;
const ok = (label, cond, extra) => {
  if (cond) console.log('  ok   ' + label);
  else { fails++; console.log('  FAIL ' + label + (extra ? '\n        ' + extra : '')); }
};
const eq = (label, a, b) => ok(label, a === b, `expected ${JSON.stringify(b)} got ${JSON.stringify(a)}`);

async function fresh(path) {
  const { module } = await load(path);
  return instantiate(module);
}

// A host that drives the queue by hand, so the tests can see each step rather
// than letting host/flint.mjs's pump do it for them.
function raw(inst) {
  const e = inst.exports;
  const dec = new TextDecoder();
  return {
    e,
    drain() {
      const n = e.flint_drain();
      const base = e.flint_events_ptr();
      const mem = new Uint8Array(e.memory.buffer);
      const w = new Uint32Array(e.memory.buffer, base, n * 5);
      const out = [];
      for (let i = 0; i < n; i++) {
        const [kind, a, b, off, len] = w.subarray(i * 5, i * 5 + 5);
        const data = mem.slice(base + off, base + off + len);
        out.push({ kind, a, b, data, bytes: dec.decode(data) });
      }
      return out;
    },
    out() { return dec.decode(new Uint8Array(e.memory.buffer, e.out_ptr(), e.out_len())); },
  };
}

console.log('host abi');

// --- open: granted, driven, refused ----------------------------------------
{
  const inst = await fresh('out/ha-echo.wasm');
  const seen = [];
  inst.capabilities({
    echo: {
      open: (p) => seen.push(['open', p]),
      message: (p, data, api) => { seen.push(['msg', api.text(data)]); api.deliver(p, data); },
      closed: (p) => seen.push(['closed', p]),
    },
  });
  const r = inst.main();
  eq('a granted capability opens, round-trips and closes',
     r.out, '{:back {:hello 1}, :refused "the host refused the capability \\"secret\\"", :state :closed}');
  ok('  ... and the host saw open, the message and the close',
     seen.length === 3 && seen[0][0] === 'open' && seen[1][0] === 'msg' && seen[2][0] === 'closed',
     JSON.stringify(seen));
}

// --- a stale or duplicated continue is rejected by generation ---------------
{
  const inst = await fresh('out/ha-echo.wasm');
  const h = raw(inst);
  let code = h.e.main();
  eq('a program waiting on the host reports status 2', code, 2);
  const evs = h.drain();
  const req = evs.find((x) => x.kind === 1);
  ok('the open request carries a token and a port id', req && req.a > 0 && req.b > 0,
     JSON.stringify(evs));
  eq('continue with the real token is accepted', h.e.flint_continue(req.a, 1), 1);
  eq('the SAME token a second time is rejected -- generation', h.e.flint_continue(req.a, 1), 0);
  eq('a token for a slot that was never handed out is rejected',
     h.e.flint_continue(req.a + 0x10000, 1), 0);
  eq('a nonsense token is rejected', h.e.flint_continue(0xffffffff, 1), 0);

  // --- and continue ENQUEUES: it must not re-enter the scheduler ------------
  ok('continue did not run the program: nothing new is queued yet',
     h.e.flint_drain() === 0);
  code = h.e.flint_resume();
  ok('the program only advances at the next pump', h.drain().length > 0 || code === 2);
}

// --- one pump drains many, and what that is worth --------------------------
{
  const time = async (path) => {
    const inst = await fresh(path);
    const h = raw(inst);
    h.e.main();
    // Answer the open first; the messages arrive on the next pump, all of them
    // in one drain, which is the thing being measured.
    for (const ev of h.drain()) if (ev.kind === 1) h.e.flint_continue(ev.a, 1);
    h.e.flint_resume();
    const t0 = process.hrtime.bigint();
    const evs = h.drain();
    const t1 = process.hrtime.bigint();
    return { msgs: evs.filter((x) => x.kind === 2).length, ns: Number(t1 - t0) };
  };
  const one = await time('out/ha-batch1.wasm');
  const many = await time('out/ha-batch1000.wasm');
  eq('a batch of one message drains one', one.msgs, 1);
  eq('a batch of a thousand drains a thousand in ONE call', many.msgs, 1000);
  console.log(`    per message: batch 1 = ${one.ns} ns, batch 1000 = ${Math.round(many.ns / 1000)} ns`);
  ok('  ... and batching is worth something',
     many.ns / 1000 < one.ns, `batch1 ${one.ns}ns, batch1000 ${Math.round(many.ns / 1000)}ns each`);
}

// --- lifetime: the host end is rooted, the flint end is not -----------------
{
  const inst = await fresh('out/ha-drop.wasm');
  const h = raw(inst);
  h.e.main();
  const turns = [];
  let code = 2;
  let guard = 0;
  while (code === 2 && guard++ < 100) {
    const evs = h.drain();
    turns.push(evs.map((x) => `${x.kind}:${x.a}`).join(','));
    for (const ev of evs) {
      if (ev.kind === 1) h.e.flint_continue(ev.a, 1);
      else if (ev.kind === 3) h.e.flint_close(ev.a);
    }
    code = h.e.flint_resume();
  }
  const flat = turns.join(' | ');
  // The first port is dropped without being closed. Its `:closed` must arrive
  // while the program is still running, not only at exit.
  const closedTurn = turns.findIndex((t) => t.includes('3:'));
  ok('dropping the last flint reference raises :closed with no explicit close',
     closedTurn >= 0, flat);
  ok('  ... and it arrives from the collector, before the program ends',
     closedTurn >= 0 && closedTurn < turns.length - 1, flat);
  eq('the program still finished normally', code, 0);
}

// --- program exit closes every flint end and drains -------------------------
{
  const inst = await fresh('out/ha-exit.wasm');
  const h = raw(inst);
  let code = h.e.main();
  const closes = [];
  let guard = 0;
  while (code === 2 && guard++ < 100) {
    for (const ev of h.drain()) {
      if (ev.kind === 1) h.e.flint_continue(ev.a, 1);
      else if (ev.kind === 3) { closes.push(ev.a); h.e.flint_close(ev.a); }
    }
    code = h.e.flint_resume();
  }
  eq('a program that never closes its ports still finishes', code, 0);
  eq('  ... and the host is told about every one of them', closes.length, 3);
}

// --- the event is a notification; the state is the truth --------------------
//
// A host that throws every `:closed` away must still be able to find out. If a
// notification were the only carrier of a durable fact, one dropped or not yet
// drained would leak a handle for ever.
{
  const inst = await fresh('out/ha-query.wasm');
  const h = raw(inst);
  const held = new Set();
  let ignored = 0;
  let code = h.e.main();
  let guard = 0;
  while (code === 2 && guard++ < 100) {
    for (const ev of h.drain()) {
      if (ev.kind === 1) { held.add(ev.b); h.e.flint_continue(ev.a, 1); }
      else if (ev.kind === 3) ignored++;   // deliberately thrown away
    }
    // ... and instead, ASK.
    for (const port of [...held]) {
      const st = h.e.flint_port_state(port);
      if (st === 2 || st === 4 || st === 5 || st === 255) {
        held.delete(port);
        h.e.flint_close(port);
      }
    }
    code = h.e.flint_resume();
  }
  eq('the program finishes even though every :closed event was discarded', code, 0);
  ok('  ... and there were events to discard', ignored > 0, `ignored ${ignored}`);
  eq('a host that only ever ASKS still releases every handle', held.size, 0);
}

// --- formats ---------------------------------------------------------------
{
  const inst = await fresh('out/ha-formats.wasm');
  const wire = [];
  inst.capabilities({
    edn: { message: (p, d, api) => { wire.push(['edn', api.text(d)]); api.deliver(p, d); } },
    json: { message: (p, d, api) => { wire.push(['json', api.text(d)]); api.deliver(p, d); } },
    // Echoed as raw bytes: decoding msgpack as UTF-8 would corrupt it, which is
    // exactly the bug this line is here to make impossible.
    transit: { message: (p, d, api) => { wire.push(['transit', d]); api.deliver(p, d); } },
  });
  const r = inst.main();
  const out = r.out;
  ok('an EDN port round-trips keywords, sets and nested values',
     out.includes('{:a #{1 2}, :b [:x]}'), out);
  ok('a JSON port round-trips what JSON can carry', out.includes('{"a" [1 2]}'), out);
  ok('a JSON port REFUSES a keyword, naming the value',
     out.includes('JSON cannot represent a keyword: :nope'), out);
  ok('  ... and a set', out.includes('JSON cannot represent a set'), out);
  ok('a Transit+msgpack port round-trips everything EDN can, over BYTES',
     out.includes('{:a #{1 2}, :b [:x], [1 2] :k}'), out);
  ok('the wire really carried EDN text', wire.some(([f, b]) => f === 'edn' && b.includes('#{')),
     JSON.stringify(wire));
  ok('  ... and JSON text', wire.some(([f, b]) => f === 'json' && b.startsWith('{"')),
     JSON.stringify(wire));
  ok('  ... and Transit\'s msgpack, which is not text at all',
     wire.some(([f, b]) => f === 'transit' && b.some((x) => x > 0x7e)),
     JSON.stringify(wire.filter(([f]) => f === 'transit').map(([, b]) => Array.from(b))));
}

console.log(fails === 0 ? 'host abi: ok' : `host abi: ${fails} FAILURES`);
process.exitCode = fails === 0 ? 0 : 1;
