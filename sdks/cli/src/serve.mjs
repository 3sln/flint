// The pump: run a program, serve what it asks for, resume
// (`DECISIONS.md#system-namespaces-and-deps`).
//
// The Rust CLI needed a loop of its own (`cli/src/serve.rs`) because its
// runtime is linked in. This side already has one: `sdks/esm/src/guest.js` is
// the reference driver, it drains events, grants ports and pumps until a call
// is answered, and the ordering rules it records are the ones that matter --
//
// * a grant NAMES a port, because there is no port until the host says which
//   one;
// * routing is set before the grant is answered, so a message cannot arrive for
//   a port the host does not yet know;
// * `resume` is called until the program settles or stops asking.
//
// So what is here is not a second pump. It is the REQUEST DISPATCH the native
// CLI keeps in `sys::serve` -- one decoded request in, one encoded reply out --
// plugged into the driver as a capability handler per served namespace.

/// Serve one decoded request, producing the reply as a `codec` value.
///
/// The `:id` is copied from the request and never invented: `flint.rpc`
/// correlates on it, and a reply carrying the wrong one wakes the wrong caller.
export function serveRequest(service, req, policy, codec) {
  const id = req == null ? null : req[':id'];
  // Keywords arrive as `:name` strings from the wire codec, so an op is matched
  // with the colon stripped -- the guest wrote `:invoke`, not "invoke".
  const bare = (x) => (typeof x === 'string' && x.startsWith(':') ? x.slice(1) : x);
  const op = bare(req?.[':op']) ?? '';
  let body = null;
  let error = null;
  try {
    if (op === 'list') body = listReply(service, codec);
    else if (op === 'invoke') {
      const v = req[':var'] ?? '';
      const args = Array.isArray(req[':args']) ? req[':args'] : [];
      body = service.invoke(typeof v === 'string' ? bare(v) : String(v), args, policy, codec);
    } else if (op === 'get') {
      // `:get` is a var's VALUE. Nothing in `flint.sys.*` holds one -- every var
      // is a function -- so this is refused by name rather than silently
      // answering nil, which a caller could not tell from a var that really is
      // nil.
      error = `${service.name} holds no values, only functions -- use a call`;
    } else {
      error = `unknown op ${JSON.stringify(op)}`;
    }
  } catch (e) {
    error = String(e?.message ?? e);
  }
  return codec.map([
    [codec.kw('id'), putId(id, codec)],
    error === null
      ? [codec.kw('body'), body]
      : [codec.kw('error'), codec.map([[codec.kw('message'), codec.str(error)]])],
  ]);
}

/// Write a decoded `:id` back out. Only the shapes an id can be, because that is
/// all this needs and a general one would be an encoder nobody checked.
function putId(id, codec) {
  if (typeof id === 'number') return Number.isInteger(id) ? codec.int(id) : codec.float(id);
  if (typeof id === 'bigint') return codec.int(id);
  // A keyword decodes to `":name"` and a string to `"name"`, so a string whose
  // first character really is a colon is indistinguishable here and comes back
  // as a keyword. `flint.rpc` correlates on integers, so this is a shape nobody
  // sends; it is written down rather than left as a surprise.
  if (typeof id === 'string') {
    return id.startsWith(':') ? kwFrom(id.slice(1), codec) : codec.str(id);
  }
  return codec.nil();
}

function kwFrom(s, codec) {
  const i = s.indexOf('/');
  return i > 0 ? codec.kw(s.slice(0, i), s.slice(i + 1)) : codec.kw(s);
}

/// The `{:op :list}` answer for a service.
function listReply(service, codec) {
  return codec.vec(service.vars.map(([name, arities]) => codec.map([
    [codec.kw('name'), codec.sym(name)],
    [codec.kw('arities'), codec.vec(arities.map((a) => codec.int(a)))],
  ])));
}

/// The capability map the driver takes: one entry per served namespace.
///
/// A namespace nobody granted is simply ABSENT, and the driver refuses an
/// `open` for a name it has no handler for -- which reaches the program as a
/// catchable error. That is the honest failure: a program granted nothing runs
/// pure rather than being handed a transport that can reach nothing.
export function capabilitiesFor(services, policy) {
  const caps = {};
  for (const s of services) {
    caps[s.name] = {
      message(port, req, api) {
        // `api.deliver` queues on this side and retries, so back-pressure --
        // a full guest buffer answering false -- is handled for us.
        api.deliver(port, serveRequest(s, req, policy, api.codec));
      },
    };
  }
  return caps;
}
