// The host ABI: tokens, one event queue, and the two lifetimes
// (DECISIONS.md#host-abi). Driven from test/host_abi.clj, which compiles the
// modules this exercises.
import { load, instantiate } from '../host/flint.mjs';
import { codec } from '../sdks/esm/src/codec.js';

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
function raw(inst, { system = 1 } = {}) {
  const e = inst.exports;
  const dec = new TextDecoder();
  // A SYSTEM PORT, because `open` is a request ON one (`DECISIONS.md#ports-are-the-hosts`)
  // and a sandbox given none can ask for nothing. Installed before `main`.
  if (system) {
    const b = new TextEncoder().encode('system');
    const p = e.flint_in_alloc(b.length);
    new Uint8Array(e.memory.buffer).set(b, p);
    e.flint_install_port(system, b.length, 1);
  }
  // Ids for the ports this host owns. A grant has to NAME one: the sandbox no
  // longer mints port ids, so `flint_continue(token, 1)` cannot mean "yes".
  let next = 500;
  // The port calls are served on, bound once on the first call.
  const CALLS = 2;
  let bound = false;
  return {
    e,
    calls: CALLS,
    grant(token) { const id = next++; return e.flint_grant(token, id) ? id : 0; },
    /// Tell the control plane this sandbox is done.
    ///
    /// A SANDBOX DOES NOT FINISH ON ITS OWN any more. Its control plane is a
    /// green thread parked on the system port, so "nothing can proceed without
    /// the host" -- status 2 -- is the RESTING state, not a step on the way to
    /// 0. Status 0 is reached by asking, which is what this does
    /// (`DECISIONS.md#bridges-are-the-only-door`).
    close() {
      const bytes = codec.map([[codec.kw('op'), codec.kw('close')]]).encode();
      const at = e.flint_in_alloc(bytes.length);
      new Uint8Array(e.memory.buffer).set(bytes, at);
      e.flint_deliver(system, bytes.length);
      return e.flint_resume();
    },
    /// Start a CALL by hand, in the two steps the protocol now has
    /// (`DECISIONS.md#bridges-are-the-only-door`).
    ///
    /// The system port carries CONTROL only, so a call cannot be delivered to
    /// it -- it used to be, and that is what this helper did. First `:bind`
    /// hands the control plane a port and it spawns a thread serving calls on
    /// it; the call itself then goes on THAT port.
    ///
    /// The bind CARRIES the port rather than installing it first. Installing
    /// it first is a race: `drive` begins by reaping, so a port the host has
    /// installed but the guest has not referenced yet is collected before the
    /// bind that would reference it is served.
    ///
    /// Returns the first status, so the caller drives the pump itself as these
    /// tests do.
    call(fn, args = []) {
      const put = (bytes) => {
        const at = e.flint_in_alloc(bytes.length);
        new Uint8Array(e.memory.buffer).set(bytes, at);
        return bytes.length;
      };
      if (!bound) {
        bound = true;
        e.flint_deliver(system, put(codec.map([
          [codec.kw('op'), codec.kw('bind')],
          [codec.kw('port'), codec.port(CALLS)],
        ]).encode()));
      }
      // NOT RESUMED BETWEEN THE TWO. A port queues, so the call waits behind
      // the bind for the thread the bind creates.
      e.flint_deliver(CALLS, put(codec.map([
        [codec.kw('tx'), codec.int(1)],
        [codec.kw('op'), codec.kw('call')],
        [codec.kw('fn'), codec.str(fn)],
        [codec.kw('args'), codec.vec([codec.vec(args.map((a) => codec.str(String(a))))])],
      ]).encode()));
      return e.flint_resume();
    },
    drain() {
      const n = e.flint_drain();
      const base = e.flint_events_ptr();
      const mem = new Uint8Array(e.memory.buffer);
      // A DataView, for the reason `sdks/esm/src/guest.js` gives: the events
      // pointer is a Rust Vec's data pointer and is not word-aligned.
      const dv = new DataView(e.memory.buffer, base, n * 20);
      const out = [];
      for (let i = 0; i < n; i++) {
        const at = i * 20;
        const kind = dv.getUint32(at, true), a = dv.getUint32(at + 4, true);
        const b = dv.getUint32(at + 8, true), off = dv.getUint32(at + 12, true);
        const len = dv.getUint32(at + 16, true);
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
      message: (p, value, api) => { seen.push(['msg', value]); api.deliver(p, value); },
      closed: (p) => seen.push(['closed', p]),
    },
  });
  const r = inst.run('echo/main', []);
  eq('a granted capability opens, round-trips and closes',
     // `refused to open`, not `refused the capability`: the cutover took the
     // word out of the sandbox, which does not have the concept. What refuses
     // is a host given no handler for the name, and it says so in those terms.
     r.out, '{:back {:hello 1}, :refused "the host refused to open \\"secret\\"", :state :closed}');
  ok('  ... and the host saw open, the message and the close',
     seen.length === 3 && seen[0][0] === 'open' && seen[1][0] === 'msg' && seen[2][0] === 'closed',
     JSON.stringify(seen));
}

// --- a stale or duplicated continue is rejected by generation ---------------
{
  const inst = await fresh('out/ha-echo.wasm');
  const h = raw(inst);
  let code = h.call('echo/main');
  eq('a program waiting on the host reports status 2', code, 2);
  const evs = h.drain();
  const req = evs.find((x) => x.kind === 1);
  ok('the open request carries a token and names the system port',
     req && req.a > 0 && req.b === 1, JSON.stringify(evs));
  eq('a grant must NAME a port, so continue cannot mean yes', h.e.flint_continue(req.a, 1), 0);
  ok('granting with the real token is accepted', h.grant(req.a) > 0);
  eq('the SAME token a second time is rejected -- generation', h.e.flint_grant(req.a, 501), 0);
  eq('a token for a slot that was never handed out is rejected',
     h.e.flint_grant(req.a + 0x10000, 502), 0);
  eq('a nonsense token is rejected', h.e.flint_grant(0xffffffff, 503), 0);

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
    h.call(path.includes('batch1000') ? 'batch1000/main' : 'batch1/main');
    // Answer the open first; the messages arrive on the next pump, all of them
    // in one drain, which is the thing being measured.
    for (const ev of h.drain()) if (ev.kind === 1) h.grant(ev.a);
    h.e.flint_resume();
    const t0 = process.hrtime.bigint();
    const evs = h.drain();
    const t1 = process.hrtime.bigint();
    // NEITHER THE SYSTEM PORT NOR THE CALL PORT: control goes on the first and
    // the call's own answer comes back on the second
    // (`DECISIONS.md#bridges-are-the-only-door`). What is being counted is the
    // traffic the PROGRAM produced.
    return {
      msgs: evs.filter((x) => x.kind === 2 && x.a !== 1 && x.a !== h.calls).length,
      ns: Number(t1 - t0),
    };
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
  h.call('drop/main');
  const turns = [];
  let code = 2;
  let guard = 0;
  // PUMPED UNTIL THE CALL IS ANSWERED, then told to close. Waiting for status 0
  // instead would wait for something that is never coming: the control plane is
  // parked on the system port, so 2 is where an idle sandbox rests.
  let answered = false;
  while (code === 2 && guard++ < 100) {
    const evs = h.drain();
    turns.push(evs.map((x) => `${x.kind}:${x.a}`).join(','));
    for (const ev of evs) {
      if (ev.kind === 1) h.grant(ev.a);
      else if (ev.kind === 2 && ev.a === h.calls) answered = true;
      else if (ev.kind === 3) h.e.flint_close(ev.a);
    }
    if (answered) break;
    code = h.e.flint_resume();
  }
  code = h.close();
  while (code === 2 && guard++ < 100) {
    for (const ev of h.drain()) {
      if (ev.kind === 1) h.grant(ev.a);
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
  eq('and once told to close, the sandbox finishes normally', code, 0);
}

// --- program exit closes every flint end and drains -------------------------
{
  const inst = await fresh('out/ha-exit.wasm');
  const h = raw(inst);
  let code = h.call('exit/main');
  const closes = [];
  let guard = 0;
  let answered = false;
  while (code === 2 && guard++ < 100) {
    for (const ev of h.drain()) {
      if (ev.kind === 1) h.grant(ev.a);
      else if (ev.kind === 2 && ev.a === h.calls) answered = true;
      else if (ev.kind === 3) { closes.push(ev.a); h.e.flint_close(ev.a); }
    }
    if (answered) break;
    code = h.e.flint_resume();
  }
  // TOLD TO CLOSE, which is what "exit" means for a sandbox now. The closes
  // counted below all arrive here.
  code = h.close();
  while (code === 2 && guard++ < 100) {
    for (const ev of h.drain()) {
      if (ev.kind === 1) h.grant(ev.a);
      else if (ev.kind === 3) { closes.push(ev.a); h.e.flint_close(ev.a); }
    }
    code = h.e.flint_resume();
  }
  eq('a program that never closes its ports still finishes when told to', code, 0);
  // FIVE, and WHICH five rather than how many -- a count alone would pass on
  // the wrong set. The three the program opened, plus the two bridges the host
  // gave it: the SYSTEM PORT and the CALL PORT it bound. A bridge the host
  // installed is a bridge the sandbox holds, so it is closed and released at
  // exit like any other, which is how the host learns it may let go of the last
  // reference (`DECISIONS.md#ports-are-the-hosts`).
  //
  // The call port is the one this gained: calls used to arrive on the system
  // port, so there was no second bridge to close
  // (`DECISIONS.md#bridges-are-the-only-door`).
  const want = [1, h.calls, 500, 501, 502].sort((x, y) => x - y).join(',');
  eq('  ... and the host is told about every one of them, both bridges too',
     [...new Set(closes)].sort((x, y) => x - y).join(','), want);
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
  let code = h.call('query/main');
  let guard = 0;
  let answered = false;
  const sweep = () => {
    // ... and instead of listening, ASK.
    for (const port of [...held]) {
      const st = h.e.flint_port_state(port);
      if (st === 2 || st === 4 || st === 5 || st === 255) {
        held.delete(port);
        h.e.flint_close(port);
      }
    }
  };
  while (code === 2 && guard++ < 100) {
    for (const ev of h.drain()) {
      if (ev.kind === 1) { const id = h.grant(ev.a); if (id) held.add(id); }
      else if (ev.kind === 2 && ev.a === h.calls) answered = true;
      else if (ev.kind === 3) ignored++;   // deliberately thrown away
    }
    sweep();
    if (answered) break;
    code = h.e.flint_resume();
  }
  code = h.close();
  while (code === 2 && guard++ < 100) {
    for (const ev of h.drain()) {
      if (ev.kind === 1) { const id = h.grant(ev.a); if (id) held.add(id); }
      else if (ev.kind === 3) ignored++;
    }
    sweep();
    code = h.e.flint_resume();
  }
  eq('the sandbox closes even though every :closed event was discarded', code, 0);
  ok('  ... and there were events to discard', ignored > 0, `ignored ${ignored}`);
  eq('a host that only ever ASKS still releases every handle', held.size, 0);
}

// --- one wire format --------------------------------------------------------
//
// Three ports with three codecs became one port with none. What is checked is
// the property that replaced them: every value that used to need a different
// format crosses the same bridge, and the host sees VALUES rather than text it
// has to parse.
{
  const inst = await fresh('out/ha-formats.wasm');
  const seen = [];
  inst.capabilities({
    // `value` is the message decoded by the RUNTIME. `data` is still there for
    // a host that wants the bytes, and is checked below to be the wire format
    // rather than anything the guest chose.
    wire: { message: (p, v, api, ev) => { seen.push([v, ev.data]); api.deliver(p, v); } },
  });
  const r = inst.run('formats/main', []);
  const out = r.out;
  ok('a set and a keyword round-trip, which JSON refused by name',
     out.includes('{:a #{1 2}, :b [:x]}'), out);
  ok('a string-keyed map does too', out.includes('{"a" [1 2]}'), out);
  ok('a bare keyword is a value like any other', out.includes(':nope'), out);
  ok('  ... and so is a bare set', out.includes('#{1}'), out);
  ok('a VECTOR AS A MAP KEY crosses, which used to need Transit',
     out.includes('{:a #{1 2}, :b [:x], [1 2] :k}'), out);
  // The host was handed values, not text. A keyword arrives as `:a`, so this is
  // the check that nothing on either side parsed a serialisation.
  ok('the host received decoded values rather than text',
     seen.some(([v]) => v && typeof v === 'object' && ':a' in v),
     JSON.stringify(seen.map(([v]) => v)));
  // The bytes are written by `flint.wire`, in the image, since
  // `DECISIONS.md#the-codec-is-guest-code`. What this row asserts is unchanged
  // by that and is the point of it: the FORMAT did not move when the author
  // did, so a host still reads a tag it recognises off the front.
  ok('  ... over bytes that arrive as bytes, starting with the wire tag',
     seen.every(([, d]) => d instanceof Uint8Array && d.length > 0),
     JSON.stringify(seen.map(([, d]) => Array.from(d).slice(0, 4))));
}

// --- metadata on the wire reaches a JS host --------------------------------
//
// The guest decides what crosses (`flint.protocols/WireMeta`); this checks the
// OTHER END of that, which no guest-side test can: that the bytes carry it and
// that `sdks/esm/src/codec.js` reads them. The tag was in the native, JVM and
// CLR codecs before it was here, which is exactly the drift this closes.
{
  const inst = await fresh('out/ha-wiremeta.wasm');
  const seen = [];
  inst.capabilities({
    wire: { message: (p, v, api) => { seen.push(v); api.deliver(p, v); } },
  });
  inst.run('wiremeta/main', []);
  eq('two values reached the host', seen.length, 2);
  // DEFAULT: NOTHING. The first value carried `{:dropped true}` and never
  // asked for it to cross.
  ok('a value that did not opt in arrives with no metadata',
     seen[0] && codec.metaOf(seen[0]) === undefined,
     JSON.stringify(codec.metaOf(seen[0])));
  // AND THE SELECTED SUBSET, not the whole map: `:dropped` stayed behind, and
  // so did the implementation that chose.
  const m = seen[1] && codec.metaOf(seen[1]);
  ok('and one that did arrives carrying exactly what it selected',
     m && m[':kept'] === ':yes' && m[':dropped'] === undefined, JSON.stringify(m));
  ok('  ... with the value itself unchanged',
     JSON.stringify(seen[1]) === '[3,4]', JSON.stringify(seen[1]));
}

// --- a flint encoder writes the same bytes as the runtime's ----------------
//
// THE GATE FOR MOVING THE CODEC INTO THE IMAGE
// (`DECISIONS.md#the-codec-is-guest-code`). The guest sends each value twice:
// once as a VALUE, which the runtime encodes, and once built through the
// `wire-*` primitives, which is flint encoding it. Identical bytes is the whole
// claim -- the format does not change, only who writes it, so the two can be
// compared directly rather than by round-tripping and hoping.
{
  const inst = await fresh('out/ha-wirediff.wasm');
  const raw = [];
  inst.capabilities({
    wire: { message: (p, v, api, ev) => { raw.push(ev.data); api.deliver(p, v); } },
  });
  const r = inst.run('wirediff/main', []);
  // THIS ROW ALSO GUARDS A DELIVER BUG. Every pair is echoed, so a byte string
  // goes out and comes back; `deliver` used to treat a `Uint8Array` as an
  // ENCODING rather than a value and ship it raw, which the runtime refused
  // silently and the guest parked on for ever. A hang, from a round trip.
  eq('the differential program ran', r.out, 'done');
  ok('  ... sending each value twice', raw.length > 0 && raw.length % 2 === 0,
     `saw ${raw.length} messages`);
  const hex = (b) => Array.from(b).map((x) => x.toString(16).padStart(2, '0')).join('');
  let same = 0;
  const differ = [];
  for (let i = 0; i + 1 < raw.length; i += 2) {
    if (hex(raw[i]) === hex(raw[i + 1])) same++;
    else differ.push(`pair ${i / 2}: runtime=${hex(raw[i])} flint=${hex(raw[i + 1])}`);
  }
  // COUNTED, not just "none differed": a run that sent nothing would pass a
  // check that only looks for disagreement.
  eq('every pair is byte-identical, and there were pairs', differ.length, 0);
  ok(`  ... across ${same} shapes`, same >= 35, `only ${same}: ${differ.join('; ')}`);
}

// --- a table survives a round trip through the JS codec --------------------
{
  const inst = await fresh('out/ha-wiretable.wasm');
  const shapes = [];
  inst.capabilities({
    // REBUILT FROM THE DECODED VALUE, not echoed as bytes. `api.deliver(p, v)`
    // with the value it was handed would prove only that the guest can read
    // its own encoding; `codec.from` makes the HOST write the table, which is
    // the half that had no coverage and until now no arm in the codec at all.
    wire: {
      message: (p, v, api) => { shapes.push(v); api.deliver(p, codec.from(v)); },
    },
  });
  const out = inst.run('wiretable/main', []).out;
  ok('a table the JS host rebuilt arrives equal to the one sent',
     out.includes('[true true'), out);
  ok('  ... with its columns in order and its types intact',
     out.includes('[:id :nm] [:int :string] ["ada" "alan"]'), out);
  // The HOST's view of it, checked directly: rows as objects and the schema
  // beside them, because the column types are not recoverable from values.
  ok('  ... and the host saw rows and a schema',
     shapes.length === 2 && Array.isArray(shapes[0]?.table)
       && shapes[0].table[0][':nm'] === 'ada'
       && JSON.stringify(shapes[0].schema) === JSON.stringify([[':id', ':int'], [':nm', ':string']]),
     JSON.stringify(shapes));
  // AN EMPTY TABLE IS STILL A TABLE, and it is the one case where no cell is
  // written at all -- the row count is the whole of the body.
  ok('  ... and an empty table is a table, not an absence of one',
     shapes[1] && Array.isArray(shapes[1].table) && shapes[1].table.length === 0
       && shapes[1].schema.length === 1,
     JSON.stringify(shapes[1]));
}

// --- a channel end cannot be encoded into a bridge message ------------------
{
  const inst = await fresh('out/ha-wireport.wasm');
  const seen = [];
  inst.capabilities({
    wire: { message: (p, v, api, ev) => { seen.push(ev.data); api.deliver(p, v); } },
  });
  const out = inst.run('wireport/main', []).out;
  ok('a channel end sent as a value is refused',
     out.includes('value=IllegalArgumentException'), out);
  // THE SAME REFUSAL THROUGH THE GUEST ENCODER. `check_sendable_via` runs on a
  // value and never sees a writer, so without a check in `wire-port` itself
  // the encoder is a way around the rule: the id of an object the host was
  // never told about, written into a message the host reads.
  ok('  ... and refused through the guest encoder too',
     out.includes('encoded=IllegalArgumentException'), out);
  // AND THE BRIDGE STILL CROSSES. A guard that refused every port would take
  // delegation with it, which is the feature the asymmetry exists to keep.
  ok('  ... while a bridge port still encodes', out.includes('bridge=NO THROW'), out);
}

// --- the writer refuses invalid structure ----------------------------------
{
  const inst = await fresh('out/ha-wirebad.wasm');
  inst.capabilities({ wire: { message: () => {} } });
  const out = inst.run('wirebad/main', []).out;
  ok('a second top-level value is refused where it happens',
     out.includes('second=IllegalStateException'), out);
  ok('  ... an unfinished container is refused at the send',
     out.includes('unfinished=IllegalStateException'), out);
  // A map of one pair is TWO values. Counting pairs instead would call this
  // complete half way through, and the far side would read past the end.
  ok('  ... and a map with half its pair is too',
     out.includes('maphalf=IllegalStateException'), out);
  ok('  ... while a complete encoding still sends', out.includes('ok=NO THROW'), out);
}

console.log(fails === 0 ? 'host abi: ok' : `host abi: ${fails} FAILURES`);
process.exitCode = fails === 0 ? 0 : 1;
