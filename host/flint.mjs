// Run a flint module. The module is self-contained: no imports, no host
// functions, nothing to wire up. This wrapper exists only to turn
// `main("a","b")` into the module's arg_alloc / arg_push / main / out_ptr ABI.
import { readFileSync } from 'fs';

export async function load(path) {
  const bytes = readFileSync(path);
  const module = await WebAssembly.compile(bytes);
  return { module, size: bytes.length };
}

export function instantiate(module) {
  const instance = new WebAssembly.Instance(module, {});
  const e = instance.exports;
  const enc = new TextEncoder();
  const dec = new TextDecoder();

  function main(...args) {
    if (process.env.FLINT_STEP_LIMIT && e.set_step_limit) {
      const n = Number(process.env.FLINT_STEP_LIMIT);
      e.set_step_limit(Math.floor(n / 2 ** 32), n >>> 0);
    }
    // Declare the grants before entering, so the entry function can be handed
    // `{:fs <cap>}` as its second argument (doc/decisions/0021, 0022). Each is
    // a host-minted opaque value whose id only this host knows -- a program
    // holds one because it was given it, not because it asked.
    if (e.flint_grant) {
      for (const name of Object.keys(capabilities)) {
        const b = enc.encode(name);
        const p = e.arg_alloc(b.length);
        new Uint8Array(e.memory.buffer).set(b, p);
        e.flint_grant(p, b.length, grantId(name));
      }
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
      if (kind === 1) out.push({ kind: 'open-request', token: a, port: b, name: dec.decode(data) });
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

  // The GRANT TABLE. `0022` is explicit that a capability check can never be
  // "is it opaque" -- guest code can mint as many opaque values as it likes --
  // so authority is this host recognising an id it issued, for a name it
  // granted. Ids start at 1 because 0 means "guest-minted".
  const grantIds = new Map();
  let nextGrantId = 1;
  function grantId(name) {
    if (!grantIds.has(name)) grantIds.set(name, nextGrantId++);
    return grantIds.get(name);
  }
  /// Which capability, if any, this value IS. Takes a 64-bit flint value as
  /// [lo, hi]; answers the name, or null for anything this host did not mint.
  function capabilityOf(lo, hi) {
    if (!e.flint_opaque_host_id) return null;
    const id = e.flint_opaque_host_id(lo >>> 0, hi >>> 0);
    if (!id) return null;
    for (const [name, gid] of grantIds) if (gid === id) return name;
    return null;
  }

  function handle(ev) {
    if (ev.kind === 'open-request') {
      const cap = capabilities[ev.name];
      if (!cap) { e.flint_continue(ev.token, 0); return; }
      // If the guest PRESENTED a capability, it has to be one this host issued
      // for this name. `0022`: the check is the grant table, never the type --
      // guest code can mint opaque values freely, so "is it opaque" would be no
      // check at all. Presenting nothing is still allowed, which is what every
      // program written before capabilities existed does.
      if (e.flint_presented_capability) {
        const presented = e.flint_presented_capability(ev.port);
        // 0 means nothing was offered, which stays allowed -- every program
        // written before capabilities existed opens by name alone. Anything
        // else has to match the id this host issued for this name; 0xFFFFFFFF
        // is "the guest offered something we never minted", and it is a
        // REFUSAL rather than an absence. Treating those two the same accepted
        // a forged `(opaque "fs")`.
        if (presented !== 0 && grantIds.get(ev.name) !== presented) {
          e.flint_continue(ev.token, 0);
          return;
        }
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
    capabilityOf,
    grantId,
  };
}

export async function run(path, args, caps) {
  const { module, size } = await load(path);
  const inst = instantiate(module);
  if (caps) inst.capabilities(caps);
  return { ...inst.main(...args), size };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const [, , path, ...args] = process.argv;
  const r = await run(path, args);
  // Not process.exit: it does not flush an async pipe write, and output past
  // the pipe buffer would be lost.
  process.exitCode = r.code;
  process.stdout.write(r.out);
  if (r.out.length && !r.out.endsWith('\n')) process.stdout.write('\n');
}
