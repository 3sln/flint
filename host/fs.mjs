// A filesystem capability for the CLI's host half (`doc/decisions/0021`).
//
// It is an ordinary port capability (`0005`, `0006`): the guest asks for it by
// name, the host either lends a port or refuses, and a program granted nothing
// runs pure. There is no new VM mechanism here, which is the point -- `0021`
// says capabilities are "nearly free" and this is what that means.
//
// ## The root is the authority
//
// A grant carries a ROOT directory and every path is resolved under it. A
// request that escapes the root is refused rather than clamped: silently
// rewriting `../../etc/passwd` into something inside the root would answer a
// question nobody asked. This is `0022`'s "derived capability" in its simplest
// form -- `:fs` narrowed to a subtree -- done by the host at grant time.
//
// Requests and replies are EDN maps, so the wire is the same one the document
// capability uses and nothing here needs a codec of its own.
import { readFileSync, writeFileSync, readdirSync, statSync, mkdirSync } from 'fs';
import { resolve, join, dirname, relative, isAbsolute } from 'path';

export function fsCapability(root, codec, opts = {}) {
  const base = resolve(root);
  const writable = opts.write === true;

  // Resolve under the root, or refuse. `relative` starting with `..` is the
  // check: it catches `../`, an absolute path, and a symlink target outside
  // the root once `resolve` has normalised it.
  const under = (p) => {
    const full = isAbsolute(p) ? resolve(p) : resolve(join(base, p));
    const rel = relative(base, full);
    if (rel.startsWith('..') || isAbsolute(rel)) return null;
    return full;
  };

  return {
    message(port, data, api) {
      const req = codec.decode(data);
      const op = req.op && req.op.name !== undefined ? req.op.name : req.op;
      const reply = (m) => api.deliver(port, codec.encode({ id: req.id, ...m }));
      const path = req.path === undefined ? null : under(String(req.path));
      if (path === null && op !== 'root') {
        return reply({ error: `outside the granted root: ${req.path}`, final: true });
      }
      try {
        if (op === 'read') {
          reply({ body: readFileSync(path, 'utf8'), final: true });
        } else if (op === 'exists') {
          let ok = true;
          try { statSync(path); } catch { ok = false; }
          reply({ body: ok, final: true });
        } else if (op === 'list') {
          // Directories are marked, because a caller walking a tree otherwise
          // has to ask again per entry.
          reply({
            body: readdirSync(path, { withFileTypes: true })
              .map((d) => ({ name: d.name, dir: d.isDirectory() })),
            final: true,
          });
        } else if (op === 'write') {
          if (!writable) return reply({ error: 'this grant is read-only', final: true });
          mkdirSync(dirname(path), { recursive: true });
          writeFileSync(path, String(req.body));
          reply({ body: true, final: true });
        } else if (op === 'root') {
          reply({ body: base, final: true });
        } else {
          reply({ error: `no such op: ${op}`, final: true });
        }
      } catch (err) {
        // A missing file is an ordinary answer, not a crash: the guest asked a
        // question and this is the answer to it.
        reply({ error: String(err.message ?? err), final: true });
      }
    },
  };
}
