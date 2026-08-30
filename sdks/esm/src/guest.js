// Driving a flint module: arguments in, an answer out, and the event loop in
// between.
//
// This is the half that is not compiling, and it is here rather than in
// `host/flint.mjs` because both need it and it is entirely portable -- no
// filesystem, no `node:` import, no `process`. `host/flint.mjs` is a node
// wrapper over this file now, so the two cannot drift.
//
// ## Why there is a loop at all
//
// A flint module with no ports never gets here: `main` returns 0 or 1 and that
// is the whole story. Status 2 means some green thread has parked on a port
// whose other end the HOST holds -- nothing is suspended, the interpreter
// simply has nothing runnable -- so the host services what was asked and calls
// `flint_resume`. That is what makes a capability work: the guest asks, the
// host answers, and the guest carries on where it left off.

import { codec } from './codec.js';

export function instantiate(module, { stepLimit = 0 } = {}) {
  const instance = new WebAssembly.Instance(module, {});
  const e = instance.exports;
  const enc = new TextEncoder();
  const dec = new TextDecoder();

  function main(...args) {
    // A gas limit, if one was asked for. `0009`'s counting is deterministic,
    // so this is a bound on WORK rather than on time -- the same program stops
    // at the same instruction on every machine.
    if (stepLimit && e.set_step_limit) {
      e.set_step_limit(Math.floor(stepLimit / 2 ** 32), stepLimit >>> 0);
    }
    for (const a of args) {
      const b = enc.encode(String(a));
      const p = e.arg_alloc(b.length);
      new Uint8Array(e.memory.buffer).set(b, p);
      e.arg_push(p, b.length);
    }
    return pump(e.main());
  }

  // --- the pump -------------------------------------------------------------
  //
  // A module with no ports never gets here: `main` returns 0 or 1 and this is
  // one comparison. Status 2 means some green thread is parked on a port whose
  // other end we hold. Nothing is suspended -- the interpreter simply has
  // nothing runnable -- so we service what it asked for and call `flint_resume`.
  //
  // One `flint_drain` per turn hands over everything pending at once: the
  // boundary crossing is tens of nanoseconds, the marshalling is the cost.
  function pump(code) {
    let guard = 0;
    while (code === 2) {
      if (++guard > 1e6) throw new Error('flint: the host pump made no progress');
      for (const ev of drain()) handle(ev);
      // Anything the guest refused for want of buffer space, and anything a
      // capability still has to say. A server answering in waves offers the
      // next one here, once the script has taken the last.
      flush();
      code = e.flint_resume();
    }
    const out = new Uint8Array(e.memory.buffer, e.out_ptr(), e.out_len());
    return { code, out: dec.decode(out) };
  }

  /// Everything pending, as records of five little-endian u32s followed by the
  /// payload bytes, all at one address.
  ///
  /// A payload is **bytes**, not text: a port's format may be binary (Transit
  /// over msgpack is), and decoding those as UTF-8 would replace whatever is
  /// not valid and quietly corrupt the message. `data` is always the bytes;
  /// `text` decodes them for the formats where that means something.
  function drain() {
    const n = e.flint_drain();
    if (!n) return [];
    const base = e.flint_events_ptr();
    const mem = new Uint8Array(e.memory.buffer);
    const words = new Uint32Array(e.memory.buffer, base, n * 5);
    const out = [];
    for (let i = 0; i < n; i++) {
      const [kind, a, b, off, len] = words.subarray(i * 5, i * 5 + 5);
      const data = mem.slice(base + off, base + off + len);
      if (kind === 1) {
        // THE ARGUMENTS, decoded. The payload used to be a bare name; it is
        // now whatever the guest forwarded, as one encoded value -- so an
        // opaque value in it arrives as `{sentinel, hostId}` and this host can
        // see whether the id is one it issued. That check lives HERE, where the
        // ids are, rather than in the runtime.
        let argv;
        try { argv = codec.decode(data); } catch { argv = []; }
        const [name, ...rest] = Array.isArray(argv) ? argv : [String(argv)];
        out.push({ kind: 'open-request', token: a, port: b, name, args: rest });
      }
      else if (kind === 2) out.push({ kind: 'message', port: a, data });
      else if (kind === 3) out.push({ kind: 'closed', port: a });
    }
    return out;
  }

  /// Send bytes back. A string is encoded as UTF-8; a Uint8Array goes as it is,
  /// which is what a binary format needs.
  ///
  /// **Returns false when the guest's buffer is full.** `deliver` below queues
  /// on our side and retries, so a caller does not have to think about it, but
  /// the distinction is the whole of flow control: without it a server that
  /// answers in waves just pushes every wave at once and the guest holds the
  /// entire answer, which is what waves exist to avoid.
  function tryDeliver(port, payload) {
    const b = typeof payload === 'string' ? enc.encode(payload) : payload;
    const p = e.flint_in_alloc(b.length);
    new Uint8Array(e.memory.buffer).set(b, p);
    return e.flint_deliver(port, b.length) !== 0;
  }

  // Per port: what we have not managed to hand over yet.
  const outbox = new Map();

  function deliver(port, payload) {
    const q = outbox.get(port) ?? [];
    q.push(payload);
    outbox.set(port, q);
    return flushPort(port);
  }

  function flushPort(port) {
    const q = outbox.get(port);
    if (!q) return true;
    while (q.length) {
      if (!tryDeliver(port, q[0])) return false;
      q.shift();
    }
    outbox.delete(port);
    return true;
  }

  /// Retry everything held back, then let each capability offer more.
  function flush() {
    for (const port of [...outbox.keys()]) flushPort(port);
    for (const [port, cap] of openPorts) {
      if (cap && cap.poll && !(outbox.get(port) || []).length) cap.poll(port, api);
    }
  }

  // What the host is willing to lend. `capabilities` maps a name to a handler
  // `{ open(port), message(port, bytes) , closed(port) }`; anything not named is
  // refused, which is a normal outcome and reaches the program as a catchable
  // error rather than a crash.
  let capabilities = {};
  const openPorts = new Map();

  function handle(ev) {
    if (ev.kind === 'open-request') {
      const cap = capabilities[ev.name];
      if (!cap) { e.flint_continue(ev.token, 0); return; }
      // WHETHER TO ALLOW IT IS THE HANDLER'S BUSINESS, not this file's.
      //
      // There used to be a check here: the guest's presented capability had to
      // match an id this SDK had minted for that name. It was the wrong place
      // twice over -- it made the SDK the arbiter of a concept that belongs to
      // whoever is lending the authority, and it meant a host could not define
      // its own rule without editing the driver.
      //
      // A handler that cares reads `ev.args`, finds whatever it projected in,
      // and compares ids it knows. A handler that does not care does nothing,
      // which is the honest default: capabilities are optional and most hosts
      // want none.
      if (cap.allow && !cap.allow(ev.args, ev.name)) {
        e.flint_continue(ev.token, 0);
        return;
      }
      openPorts.set(ev.port, cap);
      e.flint_continue(ev.token, 1);
      if (cap.open) cap.open(ev.port, api);
    } else if (ev.kind === 'message') {
      const cap = openPorts.get(ev.port);
      if (cap && cap.message) cap.message(ev.port, ev.data, api);
    } else if (ev.kind === 'closed') {
      const cap = openPorts.get(ev.port);
      if (cap && cap.closed) cap.closed(ev.port, api);
      openPorts.delete(ev.port);
      e.flint_close(ev.port);
    }
  }

  const api = {
    deliver,
    tryDeliver,
    close: (p) => e.flint_close(p),
    text: (d) => dec.decode(d),
    /// How much the guest will still accept on this port right now. A capability
    /// that wants to be a good citizen can ask instead of being refused.
    state: (p) => e.flint_port_state(p),
  };

  return {
    main,
    exports: e,
    drain,
    deliver,
    flush,
    pump,
    grant: (name, handler) => { capabilities[name] = handler; },
    capabilities: (m) => { capabilities = m; },
  };
}

