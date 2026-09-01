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

  // AT EXIT the runtime closes and releases every bridge, so the first return
  // is 2 ("the host is needed") with those events pending, and the answer comes
  // on the next turn. That is the documented protocol -- "the last pump is two
  // pumps" -- and it is why the host is never left guessing whether more is
  // coming. It used to be silent here only because a global port had no peer
  // and `close_side_effects` therefore pushed nothing, which meant a host was
  // never told it could let the port go.
  let code = e.main();
  eq('  ... and the program runs, asking the host to drain', code, 2);
  const tail = drain(e);
  eq('  ... releasing both ports it was given', tail.filter((x) => x.kind === 5).length, 2);
  code = e.flint_resume();
  eq('  ... and then finishes', code, 0);
  const out = dec.decode(new Uint8Array(e.memory.buffer, e.out_ptr(), e.out_len()));
  eq('  ... producing its own answer', out.trim(), '{:ran true, :local :hello}');
  ok('  ... having generated no host traffic of its OWN, only the teardown',
     tail.every((x) => x.kind === 3 || x.kind === 5));
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
