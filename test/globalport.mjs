// Global ports and the system port (`DECISIONS.md#ports-are-the-hosts`), driven by hand so
// each step is visible.
import { load, instantiate } from '../host/flint.mjs';
import { codec } from '../sdks/esm/src/codec.js';

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

/// Start a call by hand: a message on the system port `port`, then a resume.
function callOn(e, port, fn) {
  const bytes = codec.map([
    [codec.kw('tx'), codec.int(1)],
    [codec.kw('op'), codec.kw('call')],
    [codec.kw('fn'), codec.str(fn)],
    [codec.kw('args'), codec.vec([codec.vec([])])],
  ]).encode();
  const p = e.flint_in_alloc(bytes.length);
  new Uint8Array(e.memory.buffer).set(bytes, p);
  e.flint_deliver(port, bytes.length);
  return e.flint_resume();
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
    // The raw bytes as well as the text: a message on the system port is the
    // wire format now, and decoding it as UTF-8 would be reading an encoding.
    const data = mem.slice(base + off, base + off + len);
    out.push({ kind, a, b, data, bytes: dec.decode(data) });
  }
  return out;
}

console.log('global ports');

// --- the host installs a system port ------------------------------------------
//
// One call the HOST makes. There is deliberately nothing here that drives the
// guest THROUGH it yet: calls into a sandbox become messages on this port
// (`{what: "call", args, tx}`), and that dispatch is not built. What is built
// and checked is the ownership -- the id is the host's, and installing one does
// not disturb a program that does not know it exists.
{
  const inst = await fresh('out/gp-sys.wasm');
  const e = inst.exports;
  ok('the host installs a system port', install(e, 7, 'system', 'edn', true) === 1);
  ok('  ... and a second, ordinary global port', install(e, 8, 'work', 'edn', false) === 1);

  // A CALL, by name, on the system port: nothing is called automatically
  // (`DECISIONS.md#structured-ports` step 5), and the ANSWER comes back the same way --
  // a message carrying the call's `:tx`, not a string rendered into `out`.
  let code = callOn(e, 7, 'sys/main');
  const seen = [];
  let answer = null;
  for (let i = 0; i < 8 && answer === null; i++) {
    for (const ev of drain(e)) {
      seen.push(ev);
      if (ev.kind === 2 && ev.a === 7) answer = codec.decode(ev.data);
    }
    if (answer !== null) break;
    code = e.flint_resume();
  }
  ok('  ... and the program runs, answering on the system port', answer !== null);
  eq('  ... producing its own answer', answer && answer[':value'],
     '{:ran true, :local :hello}');
  // AT EXIT the runtime closes and releases every bridge, so a host is never
  // left holding a reference for a sandbox that has finished. It used to be
  // silent here: a global port had no peer, so `close_side_effects` pushed
  // nothing and a host was never told it could let the port go.
  let guard = 0;
  while (code === 2 && guard++ < 8) {
    for (const ev of drain(e)) seen.push(ev);
    code = e.flint_resume();
  }
  eq('  ... and then finishes', code, 0);
  eq('  ... releasing both ports it was given',
     seen.filter((x) => x.kind === 5).length, 2);
  ok('  ... having generated no host traffic of its OWN beyond the answer',
     seen.every((x) => x.kind === 2 || x.kind === 3 || x.kind === 5));
}

// A program with real INITIALISERS still returns its entry's value when a port
// was installed before it ran. See `test/globalport.clj` for the mechanism: a
// scheduler existing at start-up armed a preemption slice, the initialisers ran
// under it, and the yield was discarded -- so the entry's value was lost and a
// constant-returning program reported "did not return a string".
{
  const inst = await fresh('out/gp-init.wasm');
  const e = inst.exports;
  ok('a port is installed before a program with initialisers runs',
     install(e, 7, 'system', 'edn', true) === 1);
  const r = inst.run('init/main', []);
  eq('  ... and the entry still returns its value', r.out.trim(), 'constantfalsefalse');
  eq('  ... with status 0', r.code, 0);
}

console.log(fails === 0 ? 'global ports: ok' : `global ports: ${fails} FAILURES`);
process.exitCode = fails === 0 ? 0 : 1;
