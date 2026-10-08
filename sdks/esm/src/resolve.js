// The compile as a CALL, with namespaces answered by the host
// (`DECISIONS.md#namespaces-over-the-system-port`).
//
// Both JavaScript doors drive the compiler through this file: the ESM SDK
// (`flint.js`) and the npm CLI (`sdks/cli`, which copies it the way it copies
// `guest.js` and `codec.js`). It is portable -- no `node:` import, no
// `process`, no filesystem -- so it is one implementation and not two.
//
// What happens in a compile:
//
//   1. the host calls `flint.selfhost/compile` with a REQUEST (data, not EDN
//      text) and a RESOLVER PORT it minted. Holding the port is the compiler's
//      only way to ask for anything;
//   2. the compiler sends `{:id :want [ns ..]}` on that port, one sorted WAVE
//      of namespaces at a time, each name once, and parks;
//   3. the host answers a vector parallel to `:want`. A name in STDCORE is
//      answered from the embedded copy and the host's resolver is never asked
//      for it; every other name goes to the host's resolver, whose answer is
//      READ HERE (the kin reader, `dist/flint-reader.wasm`) under the
//      compile's features and handed over as `flint.forms` bytes -- so source
//      text never enters the compiler sandbox;
//   4. the compiler answers `{:artifact bytes :reached [..]}` or `{:errors [..]}`.
//
// A resolver is `(ns) => answer | Promise<answer>`. An answer is:
//
//   null                       not found
//   "source text"              portable source (`.cljc`), no workspace
//   { source | forms, file, dialect, workspace, grants, guard, tags, prelude }
//   { virtual: true, vars, workspace, grants, guard }
//   { error: { message, file, line, column } }
//
// WORKSPACE, GRANTS AND GUARD COME FROM THE ANSWER and from nothing else
// (`DECISIONS.md#namespaces-over-the-system-port` §4 rule 2): not from the
// namespace's name, not from a path prefix, and never from anything inside the
// forms. That is what closes the hole a path-prefix grant table had, where a
// user file under `flint/` was granted the standard library's `:host`.

import { codec, Val } from './codec.js';

/// `flint.project/source-extensions`: which file WINS for a namespace when more
/// than one exists, most specific first. `.fln` is flint's own dialect, so it is
/// preferred over a portable `.cljc` of the same namespace.
export const SOURCE_EXTENSIONS = ['.fln', '.cljc', '.clj'];

/// `flint.reader/default-features`, and `[perf]`'s set, which drops checks and
/// keeps `:flint/nested` -- the same three cases as the native CLI's
/// `read_features`. A host READS user text under exactly the set the compile
/// runs with, and the compiler checks the bytes' options on arrival, so a drift
/// here is a loud refusal rather than a quiet misread.
export const DEFAULT_FEATURES = [':flint', ':flint/check', ':flint/nested'];
export const PERF_FEATURES = [':flint', ':flint/nested'];

/// `my.app-x` -> `my/app_x`: `flint.project/ns->path`, without an extension.
export function nsPath(ns) {
  return String(ns).replace(/-/g, '_').replace(/\./g, '/');
}

/// Which dialect a FILE NAME implies: `.fln` is flint, everything else is
/// portable (`flint.project/dialect-of`). Only a fallback -- an answer that
/// says `dialect` is taken at its word, because the dialect belongs to whoever
/// answered, not to a spelling.
export function dialectOf(file) {
  return String(file ?? '').endsWith('.fln') ? 'flint' : 'portable';
}

/// The extension a dialect is written in, for naming a file that has none.
export function extensionOf(dialect) {
  return dialect === 'flint' ? '.fln' : '.cljc';
}

// --- the reader ---------------------------------------------------------------

/// The kin reader, alone (`units-src/flint-reader`): source text to
/// `flint.forms` bytes, the same code the native CLI reads with.
export class Reader {
  constructor(module) {
    this.inst = new WebAssembly.Instance(module, {});
    this.e = this.inst.exports;
  }

  static async load(bytes) {
    return new Reader(bytes instanceof WebAssembly.Module ? bytes : await WebAssembly.compile(bytes));
  }

  /// Synchronous, for node hosts: a browser main thread refuses to compile a
  /// module this size synchronously, which is why `load` is the SDK's road.
  static loadSync(bytes) {
    return new Reader(bytes instanceof WebAssembly.Module ? bytes : new WebAssembly.Module(bytes));
  }

  /// `{ forms }` or `{ error: { message, file, line, column } }`.
  ///
  /// `features` null reads DEFERRED -- conditionals kept as data, one read for
  /// every feature set -- which is how the standard library is embedded.
  /// `tags` is `[[tag, var], ..]` in the workspace's own order.
  read(text, { file, features = null, tags = [], dialect = 'portable' }) {
    const input = codec.vec([
      codec.str(String(text)),
      codec.str(String(file)),
      features === null ? codec.nil() : codec.vec(features.map((f) => codec.str(String(f)))),
      codec.vec(tags.map(([t, v]) => codec.vec([codec.str(String(t)), codec.str(String(v))]))),
      codec.str(dialect),
    ]).encode();
    const p = this.e.flint_reader_in(input.length);
    new Uint8Array(this.e.memory.buffer).set(input, p);
    const status = this.e.flint_reader_read(input.length);
    const out = new Uint8Array(this.e.memory.buffer, this.e.flint_reader_out_ptr(),
                               this.e.flint_reader_out_len()).slice();
    if (status === 1) return { forms: out };
    if (status === 0) {
      const [message, line, column] = codec.decode(out);
      return { error: { message, file, line, column } };
    }
    throw new Error(`flint: the reader refused its input: ${new TextDecoder().decode(out)}`);
  }
}

// --- answers ------------------------------------------------------------------

/// An answer already in the wire encoding: what the embedded standard library
/// holds, built once by `bin/build-stdlib-forms`. Passed through untouched.
export class Encoded {
  constructor(bytes) { this.bytes = bytes; }
}

const raw = (bytes) => new Val((w) => { w.parts.push(bytes); w.n += bytes.length; });

/// A layer of the standard library as a resolver: `bin/build-stdlib-forms`'
/// blob, `{ "clojure/core": <encoded answer>, .. }`, keyed by the namespace's
/// path without an extension.
export function layer(blob) {
  let table = null;
  const r = (ns) => {
    if (!table) table = codec.decode(blob);
    const hit = table[nsPath(ns)];
    return hit ? new Encoded(hit) : null;
  };
  r.has = (ns) => { if (!table) table = codec.decode(blob); return nsPath(ns) in table; };
  r.names = () => { if (!table) table = codec.decode(blob); return Object.keys(table); };
  return r;
}

const kwName = (x) => String(x).replace(/^:/, '');
const kwOf = (x) => {
  const s = kwName(x);
  const i = s.indexOf('/');
  return i > 0 ? codec.kw(s.slice(0, i), s.slice(i + 1)) : codec.kw(s);
};
const symOf = (x) => {
  if (x instanceof Val) return x;
  const s = String(x);
  const i = s.indexOf('/');
  return i > 0 && i < s.length - 1 ? codec.sym(s.slice(0, i), s.slice(i + 1)) : codec.sym(s);
};

/// `tags` as `[[tag, var], ..]`, from the object or pair list an answer carries.
function tagPairs(tags) {
  if (!tags) return [];
  if (Array.isArray(tags)) return tags.map(([t, v]) => [String(t), String(v)]);
  if (tags instanceof Map) return [...tags].map(([t, v]) => [String(t), String(v)]);
  return Object.entries(tags).map(([t, v]) => [String(t), String(v)]);
}

/// The identity fields of an answer, as wire entries. The WORKSPACE is a symbol
/// unless the host passed a `Val` (the CLIs name a workspace with no
/// `:flint/workspace` by its directory, as a string).
function identity(a) {
  const out = [];
  if (a.workspace !== undefined && a.workspace !== null && a.workspace !== '') {
    out.push([codec.kw('workspace'), symOf(a.workspace)]);
  }
  if (a.tags !== undefined) {
    out.push([codec.kw('tags'), codec.map(tagPairs(a.tags).map(([t, v]) => [symOf(t), symOf(v)]))]);
  }
  if (a.prelude !== undefined) out.push([codec.kw('prelude'), codec.vec((a.prelude ?? []).map(symOf))]);
  if (a.grants !== undefined) out.push([codec.kw('grants'), codec.vec((a.grants ?? []).map(kwOf))]);
  if (a.guard !== undefined) out.push([codec.kw('guard'), codec.vec((a.guard ?? []).map(kwOf))]);
  return out;
}

/// One element of a wave's answer, as the wire value the compiler reads.
///
/// TEXT IS READ HERE, never handed over (§4 rule 4): a `source` answer becomes
/// `:forms` under the compile's `features` and the answer's own tags and
/// dialect, and a read that fails becomes the namespace's `:error`, which the
/// compiler reports as a `:read` error naming who required it.
export function encodeAnswer(ns, a, { reader, features }) {
  if (a === null || a === undefined || a === MISSING) return codec.nil();
  if (a instanceof Encoded) return raw(a.bytes);
  if (a instanceof Val) return a;
  if (typeof a === 'string') a = { source: a };
  if (typeof a !== 'object') {
    return encodeAnswer(ns, { error: { message: `the resolver answered ${ns} with a ${typeof a}` } }, {});
  }
  if (a.error) {
    const e = a.error;
    const m = [[codec.kw('message'), codec.str(String(e.message ?? e))]];
    if (e.file !== undefined) m.push([codec.kw('file'), codec.str(String(e.file))]);
    if (e.line !== undefined) m.push([codec.kw('line'), codec.int(Number(e.line))]);
    if (e.column !== undefined) m.push([codec.kw('column'), codec.int(Number(e.column))]);
    return codec.map([[codec.kw('error'), codec.map(m)]]);
  }
  if (a.virtual) {
    const vars = (a.vars ?? []).map((v) => {
      const m = [[codec.kw('name'), symOf(v.name)]];
      if (v.arities) m.push([codec.kw('arities'), codec.vec(v.arities.map((n) => codec.int(n)))]);
      if (v.variadic !== undefined) m.push([codec.kw('variadic'), codec.int(v.variadic)]);
      if (v.macro) m.push([codec.kw('macro'), codec.bool(true)]);
      return codec.map(m);
    });
    return codec.map([
      [codec.kw('virtual'), codec.bool(true)],
      [codec.kw('vars'), codec.vec(vars)],
      [codec.kw('file'), codec.str(String(a.file ?? ns))],
      ...identity(a),
    ]);
  }
  // THE DIALECT IS CARRIED, not guessed: what the answer says, else what its
  // file name says, else portable -- and a file name made up for an answer that
  // gave none is spelled in that dialect.
  const dialect = a.dialect ?? (a.file ? dialectOf(a.file) : 'portable');
  const file = a.file ?? nsPath(ns) + extensionOf(dialect);
  let forms = a.forms;
  if (forms === undefined) {
    if (a.source === undefined || a.source === null) {
      return encodeAnswer(ns, { error: { message: `the resolver answered ${ns} with neither source nor forms`, file } }, {});
    }
    const r = reader.read(a.source, { file, features, tags: tagPairs(a.tags), dialect });
    if (r.error) return encodeAnswer(ns, { error: r.error }, {});
    forms = r.forms;
  }
  return codec.map([
    [codec.kw('file'), codec.str(file)],
    [codec.kw('forms'), codec.bytes(forms)],
    [codec.kw('dialect'), codec.kw(dialect)],
    ...identity(a),
  ]);
}

// --- building blocks ----------------------------------------------------------

/// What `segregate` answers for a name it owns and could not find: NOT FOUND,
/// and FINAL -- `chain` stops at it rather than asking the next resolver.
export const MISSING = Symbol('flint.missing');

const isThenable = (x) => x && typeof x.then === 'function';

/// Ask each resolver in turn; the first answer that is not null wins. Works
/// with resolvers that answer promises, and stays synchronous when none does.
export function chain(...resolvers) {
  return (ns) => {
    const from = (i) => {
      for (; i < resolvers.length; i++) {
        const r = resolvers[i](ns);
        if (isThenable(r)) return r.then((v) => (v === MISSING ? null : v ?? from(i + 1)));
        if (r === MISSING) return null;
        if (r !== null && r !== undefined) return r;
      }
      return null;
    };
    return from(0);
  };
}

/// Names under `prefixes` come from `resolver` OR NOWHERE: a miss there is
/// final, so a later resolver in a `chain` -- the user's sources, say -- cannot
/// supply `clojure.set` or `flint.whatever`. Names outside the prefixes go to
/// `resolver` as usual.
export function segregate(prefixes, resolver) {
  const owns = (ns) => prefixes.some((p) => String(ns).startsWith(p));
  return (ns) => {
    const r = resolver(ns);
    const settle = (v) => (v === null || v === undefined ? (owns(ns) ? MISSING : null) : v);
    return isThenable(r) ? r.then(settle) : settle(r);
  };
}

/// A resolver over `{ 'my/app.cljc': text, .. }`, keyed by the path a
/// namespace's name implies. When a namespace has more than one, the
/// `SOURCE_EXTENSIONS` order decides: `.fln` ahead of `.cljc` ahead of `.clj`.
/// `identity` (`workspace`, `grants`, `guard`, `tags`, `prelude`) is what every
/// answer from this map carries.
export function fromMap(files, identity = {}) {
  const get = files instanceof Map ? (k) => files.get(k) : (k) => files[k];
  return (ns) => {
    const base = nsPath(ns);
    for (const ext of SOURCE_EXTENSIONS) {
      const body = get(base + ext);
      if (body === undefined || body === null) continue;
      const file = base + ext;
      return body instanceof Uint8Array
        ? { ...identity, forms: body, file, dialect: dialectOf(file) }
        : { ...identity, source: String(body), file, dialect: dialectOf(file) };
    }
    return null;
  };
}

/// Namespaces the host SERVES rather than compiles: `{ 'my.svc': { vars:
/// [{name, arities}], workspace } }`. A `vars` list is a shape the compiler
/// checks calls against; without one any name and arity compiles.
export function virtualNamespaces(table) {
  const get = table instanceof Map ? (k) => table.get(k) : (k) => table[k];
  return (ns) => {
    const v = get(String(ns));
    return v ? { ...v, virtual: true } : null;
  };
}

// --- the request --------------------------------------------------------------

/// The compile REQUEST as the wire value `flint.selfhost/compile` takes. Only
/// what was given is said, so two doors that mean the same compile send the
/// same map.
export function compileRequest({
  id = 1, entry, target = 'image', roots = null, exports = [], features = null,
  aot = false, shake = false, meta = null, slots = null, builtins = null, base = null,
  className = null, name = null,
}) {
  const m = [
    [codec.kw('id'), codec.int(id)],
    [codec.kw('entry'), symOf(entry)],
    [codec.kw('target'), codec.kw(String(target).replace(/^:/, ''))],
  ];
  if (roots) m.push([codec.kw('roots'), codec.vec(roots.map(symOf))]);
  if (exports && exports.length) m.push([codec.kw('exports'), codec.vec(exports.map(symOf))]);
  if (features) m.push([codec.kw('features'), codec.set(features.map(kwOf))]);
  if (aot) m.push([codec.kw('aot'), codec.bool(true)]);
  if (shake) m.push([codec.kw('shake'), codec.bool(true)]);
  if (meta) {
    const entries = meta instanceof Map ? [...meta] : Array.isArray(meta) ? meta : Object.entries(meta);
    if (entries.length) {
      m.push([codec.kw('meta'), codec.map(entries.map(([k, v]) => [codec.str(String(k)), codec.from(v)]))]);
    }
  }
  if (slots) {
    m.push([codec.kw('slots'), codec.map(Object.keys(slots).map((k) => [codec.str(k), codec.int(slots[k])]))]);
  }
  if (builtins) m.push([codec.kw('builtins'), codec.set([...builtins].map((b) => codec.str(String(b))))]);
  if (base) m.push([codec.kw('base'), codec.bytes(base)]);
  if (className) m.push([codec.kw('class'), codec.str(className)]);
  if (name !== null && name !== undefined) m.push([codec.kw('name'), codec.str(String(name))]);
  return codec.map(m);
}

/// Compile on `inst` (an instantiated `flintc.wasm`, from `guest.js`), asking
/// `resolve` for every namespace stdcore does not hold.
///
/// Answers the result map -- `{ ':artifact': bytes, ':reached': [..] }` or
/// `{ ':errors': [..] }` -- synchronously when every answer was, and as a
/// promise when the resolver answered one.
///
/// `stdcore` is a resolver over the embedded required layer. It is asked FIRST,
/// and a name it holds is never put to `resolve` at all -- not asked and
/// ignored, not asked and refused: there is one path to an answer for those
/// names. `features` is the set user text is READ under, which must be the set
/// the request compiles with.
export function compileCall(inst, request, { resolve, stdcore, reader, features }) {
  const port = inst.mintPort();
  const ctx = { reader, features };
  const answerWave = (want) => {
    const parts = want.map((ns) => {
      if (stdcore && stdcore.has(ns)) return stdcore(ns);
      return resolve ? resolve(ns) : null;
    });
    const encode = (as) => {
      try {
        return codec.vec(as.map((a, i) => encodeAnswer(want[i], a, ctx)));
      } catch (e) {
        // A RESOLVER THAT THROWS answers its wave with errors, not a hang: the
        // compiler reports each as `:resolver`, naming who required it.
        return codec.vec(want.map(() => encodeAnswer('', { error: { message: String(e?.message ?? e) } }, {})));
      }
    };
    if (parts.some(isThenable)) {
      return Promise.all(parts).then(encode, (e) =>
        codec.vec(want.map(() => encodeAnswer('', { error: { message: String(e?.message ?? e) } }, {}))));
    }
    return encode(parts);
  };
  const serve = (p, msg) => {
    if (p !== port) return codec.nil();
    const want = (msg && msg[':want']) || [];
    return answerWave(want.map(String));
  };
  return inst.callServing('flint.selfhost/compile', [request, codec.port(port)], serve);
}

/// A failed compile's `:errors`, as the sentences the CLIs have always printed.
export function renderErrors(errors) {
  const missing = errors.filter((e) => e[':kind'] === ':missing');
  const refused = errors.filter((e) => e[':kind'] === ':refused');
  const other = errors.filter((e) => e[':kind'] !== ':missing' && e[':kind'] !== ':refused');
  const out = [];
  if (missing.length) {
    out.push(`no source for ${missing.map((e) => e[':ns']).join(' ')}\n` +
             'every namespace a program requires has to be on the source path');
    // `flint.check` IS NOT MISSING BY ACCIDENT. Checks are on by default and
    // their runtime is in stdextra, so a resolver without `stdextra()` cannot
    // answer it -- and only this side knows why the compiler asked.
    if (missing.some((e) => e[':ns'] === 'flint.check')) {
      out.push('`flint.check` is asked for because checks are on (the default). It is in ' +
               'stdextra(): compose that in, or compile with `checks: false`.');
    }
  }
  for (const x of refused) {
    out.push(`${x[':from']} requires ${x[':to']}, which ${x[':to-workspace']} guards with ` +
             `${printSet(x[':needs'])}; ${x[':from-workspace'] ?? 'this program'} does not hold it`);
  }
  for (const e of other) {
    const at = e[':file'] ? ` (${e[':file']}${e[':line'] ? `:${e[':line']}:${e[':column'] ?? 0}` : ''})` : '';
    out.push(`${e[':message'] ?? e[':kind']}${at}`);
  }
  return out.join('\n');
}

function printSet(s) {
  const xs = s instanceof Set ? [...s] : Array.isArray(s) ? s : [s];
  return `#{${xs.join(' ')}}`;
}
