// Global ports and the system port (`doc/decisions/0027`), driven by hand so
// each step is visible.
import { load, instantiate } from '../host/flint.mjs';

let fails = 0;
const ok = (label, cond, extra) => {
  if (cond) console.log('  ok   ' + label);
  else { fails++; console.log('  FAIL ' + label + (extra ? '\n        ' + extra : '')); }
};
const eq = (label, a, b) => ok(label, a === b, `expected ${JSON.stringify(b)} got ${JSON.stringify(a)}`);

const enc = new TextEncoder(), dec = new TextDecoder();

async function fresh(path) {
  const { module } = await load(path);
  return instantiate(module);
}

// Install a host-owned port into a sandbox: this is the whole inversion, and
// it is one call the HOST makes rather than anything the guest can reach.
function install(e, id, label, format, system) {
  const payload = enc.encode(`${label}\0${format}`);
  const p = e.flint_in_alloc(payload.length);
  new Uint8Array(e.memory.buffer, p, payload.length).set(payload);
  return e.flint_install_port(id, payload.length, system ? 1 : 0);
}

function drain(e) {
  const n = e.flint_drain();
  if (n === 0) return [];
  const base = e.flint_events_ptr();
  const mem = new Uint8Array(e.memory.buffer);
  // A DataView rather than a Uint32Array: `flint_events_ptr` is a Rust Vec's
  // data pointer and carries no four-byte alignment guarantee, so a typed-array
  // view over it throws for a reason that has nothing to do with the test.
  const dv = new DataView(e.memory.buffer, base, n * 20);
  const out = [];
  for (let i = 0; i < n; i++) {
    const at = i * 20;
    const kind = dv.getUint32(at, true), a = dv.getUint32(at + 4, true);
    const b = dv.getUint32(at + 8, true), off = dv.getUint32(at + 12, true);
    const len = dv.getUint32(at + 16, true);
    out.push({ kind, a, b, bytes: dec.decode(mem.slice(base + off, base + off + len)) });
  }
  return out;
}

console.log('global ports');

// --- a sandbox GIVEN a system port ------------------------------------------
{
  const inst = await fresh('out/gp-sys.wasm');
  const e = inst.exports;
  ok('the host installs a system port', install(e, 7, 'system', 'edn', true) === 1);

  // `e.main()` -- the RAW export, not the SDK's wrapper. The wrapper runs a
  // pump that drains events and dispatches them to registered capabilities, and
  // there are no capabilities any more: the host answers on a port it owns.
  // Status 2 means a green thread has parked with nothing runnable.
  const code = e.main();
  eq('  ... the guest parks waiting for an answer', code, 2);
  const evs = drain(e);
  const msg = evs.find((x) => x.kind === 2);
  ok('  ... and the guest sent through it without ever calling open',
     !!msg && msg.bytes === '{:op :ping}', JSON.stringify(evs));
  eq('  ... on the port id the HOST chose, not one the sandbox minted', msg && msg.a, 7);

  // Answer, and let it finish.
  const reply = enc.encode('{:pong 1}');
  const ip = e.flint_in_alloc(reply.length);
  new Uint8Array(e.memory.buffer, ip, reply.length).set(reply);
  ok('  ... and a reply delivers to that id', e.flint_deliver(7, reply.length) === 1);
  const done = e.flint_resume();
  eq('  ... and the guest runs to completion', done, 0);
  const out = dec.decode(new Uint8Array(e.memory.buffer, e.out_ptr(), e.out_len()));
  eq('  ... and read the reply back', out, '{:got {:pong 1}, :label "system"}');
}

// --- a sandbox given NOTHING -------------------------------------------------
//
// The property that makes the whole inversion worth having: no system port is a
// normal state, not a failure. The program runs and can ask for nothing.
{
  const inst = await fresh('out/gp-none.wasm');
  const e = inst.exports;
  const code = e.main();
  eq('  ... and reports no error', code, 0);
  const out = dec.decode(new Uint8Array(e.memory.buffer, e.out_ptr(), e.out_len()));
  eq('a sandbox given no system port still runs its logic', out.trim(), '{:system :none}');
}

console.log(fails === 0 ? 'global ports: ok' : `global ports: ${fails} FAILURES`);
process.exitCode = fails === 0 ? 0 : 1;
