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

import { codec, Val } from './codec.js';

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
        // `b` is the SYSTEM port the request came out on, not a port that was
        // made for it -- there is no port until this host grants one
        // (`doc/decisions/0027`).
        out.push({ kind: 'open-request', token: a, system: b, name, args: rest });
      }
      else if (kind === 2) {
        // A bridge carries VALUES: the runtime encoded this, so decoding it
        // here is the mirror of that and not something the guest chose. `data`
        // stays for a host that wants the bytes -- explicit low-level access
        // is deliberate -- and `value` is the same message decoded.
        let value, error;
        try { value = codec.decode(data); } catch (e) { error = e; }
        out.push({ kind: 'message', port: a, data, value, error });
      }
      else if (kind === 3) out.push({ kind: 'closed', port: a });
      else if (kind === 4) out.push({ kind: 'retain', port: a });
      else if (kind === 5) out.push({ kind: 'release', port: a });
    }
    return out;
  }

  /// Send ENCODED BYTES into a bridge. The low-level half.
  ///
  /// The runtime decodes what arrives here, so these bytes have to be the wire
  /// format -- build them with `codec` (`codec.str('x').encode()`, and so on)
  /// when you want to control the encoding exactly. For the ordinary case,
  /// `deliver` below takes a JS value and encodes it for you.
  ///
  /// **Returns false when the guest's buffer is full.** `deliver` queues on our
  /// side and retries, so a caller does not have to think about it, but the
  /// distinction is the whole of flow control: without it a server that answers
  /// in waves just pushes every wave at once and the guest holds the entire
  /// answer, which is what waves exist to avoid.
  function tryDeliverBytes(port, bytes) {
    const p = e.flint_in_alloc(bytes.length);
    new Uint8Array(e.memory.buffer).set(bytes, p);
    return e.flint_deliver(port, bytes.length) !== 0;
  }

  /// The same, taking a VALUE. `Val` (from `codec`) goes as written; anything
  /// else is converted with `codec.from`, so a plain JS object, array, string
  /// or number just works.
  function tryDeliver(port, value) {
    return tryDeliverBytes(port, toBytes(value));
  }

  function toBytes(value) {
    if (value instanceof Uint8Array) return value;
    return (value instanceof Val ? value : codec.from(value)).encode();
  }

  // Per port: what we have not managed to hand over yet.
  const outbox = new Map();

  /// Send a value, queueing on our side if the guest's buffer is full.
  ///
  /// Encoded ONCE, here, rather than on every retry: a message held back is
  /// already bytes by the time it is held.
  function deliver(port, value) {
    const q = outbox.get(port) ?? [];
    q.push(toBytes(value));
    outbox.set(port, q);
    return flushPort(port);
  }

  function flushPort(port) {
    const q = outbox.get(port);
    if (!q) return true;
    while (q.length) {
      if (!tryDeliverBytes(port, q[0])) return false;
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

  /// Ids for the ports THIS HOST owns. A sandbox no longer mints them
  /// (`doc/decisions/0027`), so somebody outside has to, and the id has to mean
  /// the same thing in every sandbox that holds the port -- which is what makes
  /// a handle passable from one to another.
  ///
  /// Starts above the ids a sandbox uses for its own channels so a stray
  /// collision in a log is obvious rather than plausible.
  let nextPortId = 1000;
  function newPortId() { return nextPortId++; }

  /// What each port this host owns is FOR, and how many sandboxes hold it.
  ///
  /// The count is maintained by `retain`/`release`, which the runtime pushes
  /// exactly once each per sandbox (`doc/decisions/0027`): a port arriving
  /// twice is one holder, because the handle is interned by id. At zero the
  /// host may let the resource go, and that is the whole point of counting --
  /// a number that says "how many arrivals" would not answer that question.
  const ports = new Map();

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
      // A GRANT NAMES A PORT. `flint_continue(token, 1)` cannot mean "yes",
      // because there is no port until this line decides which one.
      const port = newPortId();
      ports.set(port, { cap, holders: 0 });
      if (!e.flint_grant(ev.token, port)) {
        ports.delete(port);
        e.flint_continue(ev.token, 0);
        return;
      }
      // Routing is set NOW, so a message cannot arrive before the port is
      // known. The handler's `open` fires on the RETAIN below, which is the
      // event that says the sandbox actually holds it -- one place where the
      // count is maintained, and `holders` reads 1 inside `open` rather than 0.
      // Counted HERE, not from an event: this host granted the port, so it
      // knows, and the runtime does not push a retain for something the host
      // did itself (`doc/decisions/0027`). The retain event carries only the
      // case the host could not have known -- a port arriving inside a message.
      ports.get(port).holders++;
      openPorts.set(port, cap);
      if (cap.open) cap.open(port, api);
    } else if (ev.kind === 'message') {
      const cap = openPorts.get(ev.port);
      if (cap && cap.message) cap.message(ev.port, ev.value, api, ev);
    } else if (ev.kind === 'retain') {
      const p = ports.get(ev.port);
      if (!p) return;
      p.holders++;
      if (p.holders === 1 && p.cap && p.cap.open) p.cap.open(ev.port, api);
    } else if (ev.kind === 'release') {
      const p = ports.get(ev.port);
      if (!p) return;
      p.holders--;
      // Nobody holds it any more, so whatever it was standing for can go. This
      // is the moment a host was previously never told about: a dropped handle
      // used to be indistinguishable from a live one it had simply not heard
      // from.
      if (p.holders <= 0) {
        if (p.cap && p.cap.released) p.cap.released(ev.port, api);
        ports.delete(ev.port);
        openPorts.delete(ev.port);
        outbox.delete(ev.port);
      }
    } else if (ev.kind === 'closed') {
      const cap = openPorts.get(ev.port);
      if (cap && cap.closed) cap.closed(ev.port, api);
      openPorts.delete(ev.port);
      e.flint_close(ev.port);
    }
  }

  /// Hand a port this host owns to the sandbox, without waiting to be asked.
  ///
  /// `system` makes it the sandbox's SYSTEM port: the one `open` requests go
  /// out on. A sandbox given none can run logic and ask for nothing, which is
  /// the honest default rather than a degraded mode.
  ///
  /// Installing a port the sandbox already holds is FREE and takes no second
  /// reference -- the handle is interned by id -- so a host may install without
  /// tracking what it has installed before.
  /// The system port, installed on demand.
  ///
  /// A host that names capabilities has said it wants to be ASKED, and an
  /// `open` request goes out on the system port -- a sandbox given none cannot
  /// ask at all (`doc/decisions/0027`). Requiring every host to remember that
  /// would make "I granted a capability and nothing happened" the common first
  /// experience, so declaring one installs the transport it needs.
  ///
  /// A host that wants a different id, or wants the port to carry a handler,
  /// calls `install` itself first; this is idempotent because the handle is
  /// interned by id.
  const SYSTEM_PORT = 1;
  let systemInstalled = false;
  function ensureSystem() {
    if (systemInstalled) return;
    systemInstalled = true;
    install(SYSTEM_PORT, { label: 'system', system: true });
  }

  function install(port, { label = '', system = false, handler = null } = {}) {
    if (system) systemInstalled = true;
    const b = enc.encode(label);
    const p = e.flint_in_alloc(b.length);
    new Uint8Array(e.memory.buffer).set(b, p);
    if (!e.flint_install_port(port, b.length, system ? 1 : 0)) return false;
    // Counted here for the same reason a grant is: this host installed it.
    if (!ports.has(port)) ports.set(port, { cap: handler, holders: 0 });
    ports.get(port).holders++;
    if (handler) openPorts.set(port, handler);
    return true;
  }

  const api = {
    /// Send a VALUE. The ordinary way: encoding is done for you.
    deliver,
    tryDeliver,
    /// Send BYTES you encoded yourself, with `codec`. The low-level way, for a
    /// host that wants to control the encoding exactly.
    tryDeliverBytes,
    /// The wire codec itself, for building and reading values by hand.
    codec,
    install,
    close: (p) => e.flint_close(p),
    text: (d) => dec.decode(d),
    /// How many sandboxes hold this port. Zero means nobody, and the resource
    /// behind it can go.
    holders: (p) => (ports.get(p)?.holders ?? 0),
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
    grant: (name, handler) => { capabilities[name] = handler; ensureSystem(); },
    capabilities: (m) => { capabilities = m; ensureSystem(); },
    install,
    codec,
    holders: (p) => (ports.get(p)?.holders ?? 0),
  };
}

