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

  /// Run `fn`, and render what it returned the way a command line would.
  ///
  /// **The caller names the function.** There is no entry point: nothing is
  /// called automatically (`DECISIONS.md#structured-ports` step 5), and a module's
  /// functions are all equally callable by name. This is the shape the old
  /// `main` had -- string arguments in, a rendered string and an exit code out
  /// -- kept because a runner wants it, but it is now one caller of `call`
  /// rather than a thing the runtime does on its own.
  function run(fn, args = []) {
    // A gas limit, if one was asked for. `resource-limits`'s counting is deterministic,
    // so this is a bound on WORK rather than on time -- the same program stops
    // at the same instruction on every machine.
    if (stepLimit && e.set_step_limit) e.set_step_limit(stepLimit);
    try {
      // STRINGS, explicitly. `codec.from` reads a leading `:` as a keyword,
      // which is right for a host handing over data and wrong for a command
      // line: `:target` is an argument spelled with a colon, not a keyword, and
      // `flint.cli`'s option parser tests `starts-with? k ":"` on a STRING. It
      // silently skipped every `:key value` option, so `build :target jvm` read
      // as a bare `build`.
      const v = call(fn, [codec.vec(args.map((a) => codec.str(String(a))))]);
      return { code: 0, out: v === null || v === undefined ? '' : String(v) };
    } catch (err) {
      // NOT stripped. The message is content, not a repeated kind: the gas
      // error reads "gas limit exceeded: spent N of M", and a regex that took
      // everything up to the first colon threw the first clause away.
      return { code: 1, out: err.kind ? `${err.kind}: ${err.message}` : String(err.message ?? err) };
    }
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
    // A DataView rather than a Uint32Array: `flint_events_ptr` is a Rust Vec's
    // data pointer and carries no four-byte alignment guarantee, so a typed
    // array over it throws -- `start offset should be a multiple of 4` -- for a
    // reason that has nothing to do with the events in it. It only ever threw
    // once the control plane started allocating before the first drain, which
    // is what shifted the buffer off a word boundary; the hazard was there all
    // along. `test/globalport.mjs` has read it this way from the start.
    const dv = new DataView(e.memory.buffer, base, n * 20);
    const out = [];
    for (let i = 0; i < n; i++) {
      const at = i * 20;
      const kind = dv.getUint32(at, true), a = dv.getUint32(at + 4, true);
      const b = dv.getUint32(at + 8, true), off = dv.getUint32(at + 12, true);
      const len = dv.getUint32(at + 16, true);
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
        // (`DECISIONS.md#ports-are-the-hosts`).
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
      else if (kind === 6) {
        // A REQUEST, whose answer is an ordinary value rather than a port
        // (`DECISIONS.md#workspace-capabilities` step 7). Decoded exactly like an open, because
        // it carries exactly the same payload -- what differs is what goes back.
        let argv;
        try { argv = codec.decode(data); } catch { argv = []; }
        const [what, ...rest] = Array.isArray(argv) ? argv : [String(argv)];
        out.push({ kind: 'request', token: a, system: b, name: what, args: rest });
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
    // A `Uint8Array` IS A VALUE, not an encoding, and it used to be treated as
    // an encoding: `deliver` passed one straight through as if the host had
    // encoded it itself. So a host that DECODED a byte string and handed it
    // back -- the most ordinary thing an echo does -- shipped the bytes raw,
    // the runtime read the first one as a tag, and the delivery was refused.
    // `flint_deliver` answers 0 and says nothing, so the guest parked on a
    // receive that would never arrive: a hang, from a round trip.
    //
    // There was never a reason for the shortcut. `deliverBytes` and
    // `tryDeliverBytes` are the low-level road and take raw bytes by name; this
    // one takes a VALUE, and a byte string is one.
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

  /// What the host is willing to ANSWER (`DECISIONS.md#workspace-capabilities` step 7). A name
  /// to `(args, name, api) => value`; `'*'` catches whatever no name did.
  ///
  /// Separate from `capabilities` because the two answer different questions. A
  /// capability lends a PORT -- an ongoing conversation the host keeps an end
  /// of. A request is one question and one answer, and there is nothing left
  /// afterwards. Folding them into one map would mean a handler had to say
  /// which of the two it was, on every entry, for the benefit of neither.
  let requests = {};

  /// Answer a request with a value: encode it and hand it back by token.
  function answer(token, value) {
    const bytes = (value instanceof Val ? value : codec.from(value)).encode();
    const p = e.flint_in_alloc(bytes.length);
    new Uint8Array(e.memory.buffer).set(bytes, p);
    // A failure here is a REFUSAL rather than silence: the thread is parked on
    // this token and nothing else will ever wake it.
    if (!e.flint_answer(token, bytes.length)) e.flint_continue(token, 0);
  }
  const openPorts = new Map();

  /// Ids for the ports THIS HOST owns. A sandbox no longer mints them
  /// (`DECISIONS.md#ports-are-the-hosts`), so somebody outside has to, and the id has to mean
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
  /// exactly once each per sandbox (`DECISIONS.md#ports-are-the-hosts`): a port arriving
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
      // did itself (`DECISIONS.md#ports-are-the-hosts`). The retain event carries only the
      // case the host could not have known -- a port arriving inside a message.
      ports.get(port).holders++;
      openPorts.set(port, cap);
      if (cap.open) cap.open(port, api);
    } else if (ev.kind === 'request') {
      // The host's own answer, and there is no default one. An unhandled
      // request is REFUSED rather than answered nil: nil is a value a host may
      // legitimately answer, so answering it for "nobody handled this" would
      // make a missing handler indistinguishable from a deliberate one.
      const fn = requests[ev.name] ?? requests['*'];
      if (!fn) { e.flint_continue(ev.token, 0); return; }
      let value;
      try { value = fn(ev.args, ev.name, api); }
      catch { e.flint_continue(ev.token, 0); return; }
      // REFUSAL is `undefined`, not nil, for the same reason.
      if (value === undefined) { e.flint_continue(ev.token, 0); return; }
      answer(ev.token, value);
    } else if (ev.kind === 'message') {
      // An answer to a CALL comes back on the system port carrying its `:tx`.
      if (ev.value && pending.has(ev.value[':tx'])) {
        pending.get(ev.value[':tx'])(ev.value);
        return;
      }
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
  /// ask at all (`DECISIONS.md#ports-are-the-hosts`). Requiring every host to remember that
  /// would make "I granted a capability and nothing happened" the common first
  /// experience, so declaring one installs the transport it needs.
  ///
  /// A host that wants a different id, or wants the port to carry a handler,
  /// calls `install` itself first; this is idempotent because the handle is
  /// interned by id.
  const SYSTEM_PORT = 1;
  /// The port calls travel on, bound once.
  ///
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

  /// A CALLER: one bound port, and the calls made on it.
  ///
  /// This is the shape the protocol already had, given a name. `:bind` hands
  /// the control plane a port and it spawns ONE thread serving calls on it
  /// (`DECISIONS.md#bridges-are-the-only-door`), so a caller is that thread's
  /// queue: calls on one caller are serial, in arrival order, and concurrency
  /// is had by taking a SECOND caller. The cost is visible rather than a thread
  /// appearing per call.
  ///
  /// The object is sugar. Everything under it is a message on a port, and the
  /// encoding helpers below are private on purpose -- there is no global
  /// `call(sandbox, name)` to reach past it with.
  let nextTx = 1;
  let nextCallPort = 2;
  const pending = new Map();

  /// The bind, as bytes. The port TRAVELS IN the message rather than being
  /// installed first: installing it first is a race, because `drive` begins by
  /// reaping and a port the host has installed but the guest has not referenced
  /// yet is collected before the bind that would reference it is served.
  function bindBytes(port) {
    return codec.map([
      [codec.kw('op'), codec.kw('bind')],
      [codec.kw('port'), codec.port(port)],
    ]).encode();
  }

  function callBytes(tx, name, args) {
    return codec.map([
      [codec.kw('tx'), codec.int(tx)],
      [codec.kw('op'), codec.kw('call')],
      [codec.kw('fn'), codec.str(name)],
      [codec.kw('args'), codec.vec(args.map((a) => (a instanceof Val ? a : codec.from(a))))],
    ]).encode();
  }

  /// Pump until THIS call is answered, not until the sandbox is idle.
  ///
  /// A sandbox does not "finish" any more. `main` used to be the end -- its
  /// return tore everything down, "whatever a service thread may still be
  /// parked on" -- and with it gone a sandbox is a thing you call, which may
  /// keep threads alive between calls. An RPC client's reader thread is exactly
  /// that: parked on a receive for ever, by design. Waiting for the whole
  /// sandbox to settle would wait for something that is never coming.
  function pumpFor(tx, name) {
    let answer;
    pending.set(tx, (m) => { answer = m; });
    let guard = 0;
    let code = e.flint_resume();
    while (answer === undefined) {
      if (++guard > 1e6) throw new Error('flint: the host pump made no progress');
      for (const ev of drain()) handle(ev);
      flush();
      if (answer !== undefined) break;
      if (code !== 2) break;
      code = e.flint_resume();
    }
    pending.delete(tx);
    if (answer === undefined) {
      // THE SANDBOX MAY HAVE ENDED RATHER THAN GONE QUIET, and those are
      // different things to tell somebody. A call that cannot be answered
      // because the gate stopped the program has a REASON, and the runtime has
      // already rendered it: status 1 is `finish_run` having written
      // "Kind: message" into `OUT` (`DECISIONS.md#resource-limits`).
      //
      // Only on `code === 1`, because that is exactly when this run rendered
      // it. `OUT` is cleared and rewritten per render, so reading it after any
      // other status risks handing back a message from an earlier call.
      if (code === 1) {
        const rendered = dec.decode(
          new Uint8Array(e.memory.buffer, e.out_ptr(), e.out_len()));
        if (rendered) throw new Error(rendered);
      }
      throw new Error(`flint: the call to ${name} was never answered`);
    }
    if (answer[':op'] === ':throw') {
      const err = new Error(`${answer[':kind']}: ${answer[':message']}`);
      err.kind = answer[':kind'];
      // The DECODED value, not just the two fields rendered into the message.
      // `(throw (ex-info "no" {:code 42}))` carries data, and a host that can
      // read the message but not `{:code 42}` cannot act on the failure --
      // which is the whole of "failure is data".
      err.flint = answer;
      throw err;
    }
    return answer[':value'];
  }

  /// Bind a port and hand back the caller that owns it.
  ///
  /// `caller()` is where a call comes from; there is no other route in. The
  /// synchronous `flint_call` that used to serve one is gone on purpose
  /// (`DECISIONS.md#calls-are-ports`) -- it was cheaper for a module that could
  /// not park, and that saving is given up so that the boundary is one thing.
  function caller() {
    // The port is ensured rather than probed. It used to be installed only when
    // something was served, so a module with no capabilities had none -- and
    // now that this is the only route in, a sandbox without one could not be
    // called at all.
    ensureSystem();
    const sysId = e.flint_system_port ? e.flint_system_port() : 0;
    if (!sysId) throw new Error('flint: this sandbox has no system port, so it cannot be called');
    const port = nextCallPort++;
    if (!tryDeliverBytes(sysId, bindBytes(port))) {
      throw new Error('flint: the system port would not take the bind');
    }
    // NOT PUMPED HERE. A port queues, so the first call is delivered behind the
    // bind and waits for the thread the bind creates; the pump below serves
    // both. Pumping here was worse than unnecessary: it waited for `code !== 2`,
    // and a sandbox whose control plane is parked on its system port is ALWAYS
    // 2 -- "needs the host" is the resting state, not progress left to make --
    // so that loop span its guard out and gave up every time.
    let open = true;
    return {
      port,
      /// `call("ns/f", a, b)`. Variadic, because a caller's arguments are the
      /// function's arguments; nothing here is a list of them.
      call(name, ...args) {
        if (!open) throw new Error('flint: this caller was closed');
        const tx = nextTx++;
        if (!tryDeliverBytes(port, callBytes(tx, name, args))) {
          throw new Error('flint: the call port would not take the call');
        }
        return pumpFor(tx, name);
      },
      /// `:unbind`, which CLOSES the bound port: the call thread's `receive`
      /// answers nil and its loop ends. A thread told to stop by the thing it
      /// is parked on needs no second channel to be told on.
      close() {
        if (!open) return;
        open = false;
        tryDeliverBytes(sysId, codec.map([
          [codec.kw('op'), codec.kw('unbind')],
          [codec.kw('port'), codec.port(port)],
        ]).encode());
        e.flint_resume();
        for (const ev of drain()) handle(ev);
      },
    };
  }

  /// One caller, made on first use, for hosts that only ever make one.
  ///
  /// `inst.call(name, argsArray)` is sugar over it and keeps the ARRAY
  /// signature it has always had; `caller().call(name, ...args)` is the
  /// explicit road and is variadic.
  let theCaller = null;
  function call(name, args = []) {
    if (!theCaller) theCaller = caller();
    return theCaller.call(name, ...args);
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
    run,
    exports: e,
    drain,
    deliver,
    flush,
    pump,
    call,
    caller,
    grant: (name, handler) => { capabilities[name] = handler; ensureSystem(); },
    capabilities: (m) => { capabilities = m; ensureSystem(); },
    /// Answer `(request "name" ..)` from the guest. `fn(args, name, api)`
    /// returns the value; returning `undefined` REFUSES, which nil does not,
    /// because nil is an answer a host may legitimately give.
    answers: (name, fn) => { requests[name] = fn; ensureSystem(); },
    requests: (m) => { requests = m; ensureSystem(); },
    install,
    codec,
    holders: (p) => (ports.get(p)?.holders ?? 0),
  };
}

