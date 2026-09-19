// The wire codec (`DECISIONS.md#structured-ports`).
//
// One encoding for every flint value crossing the boundary: a call's arguments,
// its return, and everything a port carries. It is a CODEC rather than a
// message format because the same bytes carry all three.
//
// The explicit builders exist because JS cannot say what it means. `{a: 1}` is
// a map with a string key or a keyword key; `1` is an integer or a double;
// `[1,2]` is a vector or a list; a `Set` is a set or a vector. Guessing is
// right often enough to be a bug, so `from` is a convenience on top of an
// explicit road rather than the only road.

const K = {
  NIL: 0, TRUE: 1, FALSE: 2, INT: 3, DOUBLE: 4, STRING: 5, KEYWORD: 6,
  SYMBOL: 7, VECTOR: 8, LIST: 9, MAP: 10, SET: 11, BYTES: 14, PORT: 15,
  SENTINEL: 16, TAGGED: 17, TABLE: 18, WITH_META: 19,
};
const NO_NS = 0xffffffff;
const TAG_NAME = {
  0: 'nil', 1: 'boolean', 2: 'boolean', 3: 'int', 4: 'double', 5: 'string',
  6: 'keyword', 7: 'symbol', 8: 'vector', 9: 'list', 10: 'map', 11: 'set',
  14: 'bytes', 15: 'port', 16: 'sentinel', 17: 'tagged', 18: 'table',
  19: 'with-meta',
};

const enc = new TextEncoder();
const dec = new TextDecoder();

/// Metadata that arrived with a decoded value.
///
/// A SIDE TABLE, not a property on the value. JavaScript has no metadata, so
/// the choices were to hang one on the object or to keep it beside; a property
/// loses either way. `v.meta` collides with a flint map that has a string key
/// `"meta"` -- which decodes to exactly `{meta: ...}` -- and a non-enumerable
/// one is invisible to `Object.keys`, `JSON.stringify` and every equality check
/// a host might already be doing, which is worse than absent because it is
/// absent only sometimes.
///
/// WEAK, so holding metadata never keeps a decoded value alive.
///
/// Only objects can be keys, which costs nothing: the kinds that carry metadata
/// in flint -- vectors, maps, sets, lists, ports -- all decode to objects here,
/// and the ones that cannot carry it are the primitives.
const META = new WeakMap();

class Writer {
  constructor() { this.parts = []; this.n = 0; }
  byte(b) { this.parts.push(new Uint8Array([b])); this.n += 1; }
  u32(v) {
    const a = new Uint8Array(4);
    new DataView(a.buffer).setUint32(0, v >>> 0, true);
    this.parts.push(a); this.n += 4;
  }
  u64(lo, hi) { this.u32(lo); this.u32(hi); }
  i64(v) {
    const a = new Uint8Array(8);
    new DataView(a.buffer).setBigInt64(0, BigInt(v), true);
    this.parts.push(a); this.n += 8;
  }
  f64(v) {
    const a = new Uint8Array(8);
    new DataView(a.buffer).setFloat64(0, v, true);
    this.parts.push(a); this.n += 8;
  }
  bytes(b) { this.u32(b.length); this.parts.push(b); this.n += b.length; }
  str(s) { this.bytes(enc.encode(s)); }
  absent() { this.u32(NO_NS); }
  done() {
    const out = new Uint8Array(this.n);
    let at = 0;
    for (const p of this.parts) { out.set(p, at); at += p.length; }
    return out;
  }
}

/// A value on its way in or out. Opaque on purpose: what it holds is the
/// encoding, and `toJS` is how you stop caring.
export class Val {
  constructor(write) { this._write = write; }
  encode() { const w = new Writer(); this._write(w); return w.done(); }
}

const v = (write) => new Val(write);

export const codec = {
  nil: () => v((w) => w.byte(K.NIL)),
  bool: (b) => v((w) => w.byte(b ? K.TRUE : K.FALSE)),
  int: (n) => v((w) => { w.byte(K.INT); w.i64(n); }),
  float: (n) => v((w) => { w.byte(K.DOUBLE); w.f64(n); }),
  str: (s) => v((w) => { w.byte(K.STRING); w.str(s); }),
  bytes: (b) => v((w) => { w.byte(K.BYTES); w.bytes(b); }),

  /// A PORT, by the id this sandbox knows it as.
  ///
  /// The host gets an id from `decode` (a port arrives as `{port: id}`) or from
  /// an open-request, and hands it back to name the same endpoint. There is no
  /// way to invent one that means anything: the runtime looks the id up in its
  /// own registry and refuses what it does not find, so a wrong id is an error
  /// rather than a handle to something else.
  ///
  /// A CHANNEL is never one of these. Its ends both live inside the sandbox and
  /// the host is never told it exists, so an id for one would name an object
  /// the host has no business naming -- the runtime refuses to send one out.
  port: (id) => v((w) => {
    if (!Number.isInteger(id) || id < 0) {
      throw new Error('flint: a port id must be a non-negative integer');
    }
    w.byte(K.PORT);
    w.u32(id);
  }),

  /// An OPAQUE value the host owns: an id it issued, and a label for reading.
  ///
  /// This is the half that lets a JS host hand a capability IN. Decoding one
  /// has always worked -- an opaque arriving from the guest comes back as
  /// `{sentinel, hostId}` -- and encoding one did not, which meant a JS host
  /// could recognise a capability it had never had any way to issue. The
  /// asymmetry is why the `allow` hook in `guest.js` had no users.
  ///
  /// `DECISIONS.md#opaque-values`: the id is the whole authority. It is meaningful
  /// only to the host that issued it, the guest can carry it and compare it
  /// and nothing else, and an id the guest MINTS is 0 -- which is why 0 must
  /// never be issued, or a forgery is indistinguishable from a grant.
  opaque: (hostId, label = '') => {
    // The ISSUING builder, so it refuses 0: that is what a guest-minted value
    // carries, and a host that issued 0 would have issued something every
    // program can already make for itself. Relaying one that is ALREADY 0 is a
    // different act and goes through `sentinel` below.
    if (!Number.isInteger(hostId) || hostId <= 0) {
      throw new Error('flint: an opaque id must be a positive integer; ' +
                      '0 is what a guest-minted value carries, so issuing it ' +
                      'would grant nothing. Use codec.sentinel to relay one.');
    }
    return codec.sentinel(hostId, label);
  },

  /// The same, without the check: relay an opaque exactly as it arrived.
  ///
  /// `from` uses this so that a host can decode a message, look at it and send
  /// it back unchanged -- including a guest-minted one, whose id is 0 and which
  /// must survive the trip still saying so.
  sentinel: (hostId, label = '') => v((w) => {
    if (!Number.isInteger(hostId) || hostId < 0) {
      throw new Error('flint: an opaque id must be a non-negative integer');
    }
    w.byte(K.SENTINEL);
    w.u32(hostId % 0x100000000);
    w.u32(Math.floor(hostId / 0x100000000));
    w.str(label);
  }),

  /// `kw('a')` is `:a`; `kw('my.ns', 'a')` is `:my.ns/a`.
  kw: (a, b) => v((w) => {
    w.byte(K.KEYWORD);
    if (b === undefined) w.absent(); else w.str(a);
    w.str(b === undefined ? a : b);
  }),
  sym: (a, b) => v((w) => {
    w.byte(K.SYMBOL);
    if (b === undefined) w.absent(); else w.str(a);
    w.str(b === undefined ? a : b);
  }),

  vec: (items) => v((w) => { w.byte(K.VECTOR); w.u32(items.length); for (const i of items) i._write(w); }),
  list: (items) => v((w) => { w.byte(K.LIST); w.u32(items.length); for (const i of items) i._write(w); }),
  set: (items) => v((w) => { w.byte(K.SET); w.u32(items.length); for (const i of items) i._write(w); }),
  /// `entries` is `[[key, value], …]`, because a JS object cannot hold a
  /// keyword key and a map's keys are not always strings.
  map: (entries) => v((w) => {
    w.byte(K.MAP); w.u32(entries.length);
    for (const [k, val] of entries) { k._write(w); val._write(w); }
  }),

  /// A TAGGED LITERAL: `tagged('my.ns/thing', form)`.
  ///
  /// The tag is a SYMBOL, so a plain string here is written as one -- that is
  /// what `#my.ns/thing` means and a keyword would be a different value.
  tagged: (tag, form) => v((w) => {
    w.byte(K.TAGGED);
    (tag instanceof Val ? tag : codec.sym(String(tag)))._write(w);
    (form instanceof Val ? form : codec.from(form))._write(w);
  }),

  /// A TABLE: `table([[name, type], …], rows)`, rows being objects.
  ///
  /// The SCHEMA IS NOT INFERRED. A column of whole numbers could be `:int` or
  /// `:double` and an empty table has no values to guess from at all, so the
  /// types are said. Names and types are keywords: they are written as
  /// keywords whether or not the caller wrote the colon, because there is no
  /// other thing they could be.
  ///
  /// COLUMN BY COLUMN on the wire, which is why the rows are walked once per
  /// column here: the format exists so a receiver reading one field reads one
  /// run (`DECISIONS.md#tables`).
  table: (schema, rows) => v((w) => {
    const kwOf = (x) => {
      if (x instanceof Val) return x;
      const str = String(x);
      const bare = str.startsWith(':') ? str.slice(1) : str;
      const i = bare.indexOf('/');
      return i > 0 ? codec.kw(bare.slice(0, i), bare.slice(i + 1)) : codec.kw(bare);
    };
    const names = schema.map(([n]) => n);
    w.byte(K.TABLE);
    w.u32(schema.length);
    for (const [n, t] of schema) { kwOf(n)._write(w); kwOf(t)._write(w); }
    w.u32(rows.length);
    for (const n of names) {
      const key = n instanceof Val ? null : String(n).replace(/^:/, '');
      for (const row of rows) {
        // BOTH SPELLINGS, because `decode` gives `{id: 1}` and a host writing
        // one by hand may well write `{':id': 1}` -- and a round trip that
        // needed the keys rewritten in between would not be a round trip.
        const cell = key !== null && key in row ? row[key]
                   : key !== null && `:${key}` in row ? row[`:${key}`]
                   : undefined;
        if (cell === undefined) {
          throw new TypeError(`flint: this table row has no \`${key}\`, and a table is closed`);
        }
        (cell instanceof Val ? cell : codec.from(cell))._write(w);
      }
    }
  }),

  /// A value carrying METADATA: `withMeta(meta, value)`.
  ///
  /// What crosses is decided by the SENDER, and on the guest side that is
  /// `flint.protocols/WireMeta` -- the default is that nothing crosses, so a
  /// host that wants metadata on the wire says so here explicitly.
  ///
  /// The metadata goes through the ordinary encoder, so a map holding something
  /// that cannot cross fails the send by name rather than being dropped.
  withMeta: (m, v) => new Val((w) => {
    w.byte(K.WITH_META);
    (m instanceof Val ? m : codec.from(m))._write(w);
    (v instanceof Val ? v : codec.from(v))._write(w);
  }),

  /// The metadata that arrived with `v`, or undefined.
  ///
  /// Kept beside the value rather than on it -- see `META` above for why. A
  /// host reads a port's declared protocols through this.
  metaOf: (v) => (v !== null && (typeof v === 'object' || typeof v === 'function')
    ? META.get(v) : undefined),

  /// A JS value, guessed by shape.
  ///
  /// A JS object has STRING keys, so string keys are what it means.
  /// `{keywordizeKeys: true}` makes them keywords for a whole structure, and a
  /// leading colon does it for one -- `':a'` is `:a` wherever it appears. The
  /// cost is that the literal string `":a"` needs `codec.str(':a')`, which is
  /// the right way round: the explicit builder is always there.
  from(x, opts = {}) {
    const kwKeys = !!opts.keywordizeKeys;
    // Guards the one re-entry above: `go(x)` on a value that HAS metadata would
    // otherwise wrap it again, for ever.
    const SEEN = new Set();
    const go = (x) => {
      if (x === null || x === undefined) return codec.nil();
      if (typeof x === 'boolean') return codec.bool(x);
      if (typeof x === 'number') {
        return Number.isInteger(x) ? codec.int(x) : codec.float(x);
      }
      if (typeof x === 'bigint') return codec.int(x);
      if (typeof x === 'string') {
        return x.startsWith(':') && x.length > 1 ? kwOf(x.slice(1)) : codec.str(x);
      }
      if (x instanceof Val) return x;
      // METADATA SURVIVES A ROUND TRIP. `from` exists so a host can decode a
      // message, look at it and send it back without taking it apart; metadata
      // that silently fell off in the middle would make that false for exactly
      // the values it matters for -- a port, whose protocols are the reason it
      // is carried at all. `bare` re-enters without the metadata, so this wraps
      // once rather than for ever.
      {
        const m = codec.metaOf(x);
        if (m !== undefined && !SEEN.has(x)) {
          SEEN.add(x);
          try { return codec.withMeta(go(m), go(x)); } finally { SEEN.delete(x); }
        }
      }
      // The shapes `decode` PRODUCES, recognised so that a host can decode a
      // message, look at it, and send it back without taking it apart. Matched
      // on the exact key set rather than on the presence of a key, so an
      // ordinary map that happens to have a `port` entry is still a map.
      if (typeof x === 'object' && !Array.isArray(x)) {
        const ks = Object.keys(x);
        if (ks.length === 1 && ks[0] === 'port' && Number.isInteger(x.port)) {
          return codec.port(x.port);
        }
        if (ks.length === 2 && ks.includes('sentinel') && ks.includes('hostId')
            && Number.isInteger(x.hostId)) {
          return codec.sentinel(x.hostId, String(x.sentinel));
        }
        if (ks.length === 2 && ks.includes('tag') && ks.includes('form')) {
          return codec.tagged(x.tag, x.form);
        }
        if (ks.length === 2 && ks.includes('table') && ks.includes('schema')
            && Array.isArray(x.table) && Array.isArray(x.schema)) {
          return codec.table(x.schema, x.table);
        }
      }
      if (x instanceof Uint8Array) return codec.bytes(x);
      if (Array.isArray(x)) return codec.vec(x.map(go));
      if (x instanceof Set) return codec.set([...x].map(go));
      if (x instanceof Map) return codec.map([...x].map(([k, v]) => [go(k), go(v)]));
      if (typeof x === 'object') {
        return codec.map(Object.entries(x).map(([k, v]) => [
          k.startsWith(':') && k.length > 1 ? kwOf(k.slice(1)) : (kwKeys ? kwOf(k) : codec.str(k)),
          go(v),
        ]));
      }
      throw new TypeError(`flint: ${typeof x} cannot cross a boundary`);
    };
    const kwOf = (s) => {
      const i = s.indexOf('/');
      return i > 0 ? codec.kw(s.slice(0, i), s.slice(i + 1)) : codec.kw(s);
    };
    return go(x);
  },

  /// Bytes back to a JS value. `keywords` decides how a keyword arrives:
  /// `'string'` (the default) gives `':a'`, so a round trip through `from` is
  /// stable; `'plain'` drops the colon.
  decode(bytes, opts = {}) {
    const r = { b: bytes, i: 0, view: new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength) };
    const out = read(r, opts);
    return out;
  },

  /// What a value IS, without converting it.
  tagOf(bytes) { return TAG_NAME[bytes[0]] ?? `unknown(${bytes[0]})`; },
};

function readU32(r) {
  const v = r.view.getUint32(r.i, true); r.i += 4; return v;
}
function readStr(r) {
  const n = readU32(r);
  if (n === NO_NS) return null;
  const s = dec.decode(r.b.subarray(r.i, r.i + n));
  r.i += n;
  return s;
}

function read(r, opts) {
  if (r.i >= r.b.length) throw new Error('flint: the encoding ends mid-value');
  const tag = r.b[r.i++];
  const kwStyle = opts.keywords ?? 'string';
  switch (tag) {
    case K.NIL: return null;
    case K.TRUE: return true;
    case K.FALSE: return false;
    case K.INT: {
      const v = r.view.getBigInt64(r.i, true); r.i += 8;
      // A JS number where it fits, so ordinary arithmetic works; a BigInt
      // where it does not, rather than silently losing precision.
      return (v >= -9007199254740991n && v <= 9007199254740991n) ? Number(v) : v;
    }
    case K.DOUBLE: { const v = r.view.getFloat64(r.i, true); r.i += 8; return v; }
    case K.STRING: return readStr(r);
    case K.KEYWORD: case K.SYMBOL: {
      const ns = readStr(r); const name = readStr(r);
      const full = ns === null ? name : `${ns}/${name}`;
      if (tag === K.SYMBOL) return full;
      return kwStyle === 'plain' ? full : `:${full}`;
    }
    case K.BYTES: { const n = readU32(r); const b = r.b.slice(r.i, r.i + n); r.i += n; return b; }
    case K.VECTOR: case K.LIST: {
      const n = readU32(r); const out = [];
      for (let i = 0; i < n; i++) out.push(read(r, opts));
      return out;
    }
    case K.SET: {
      const n = readU32(r); const out = new Set();
      for (let i = 0; i < n; i++) out.add(read(r, opts));
      return out;
    }
    case K.MAP: {
      const n = readU32(r); const entries = [];
      for (let i = 0; i < n; i++) entries.push([read(r, opts), read(r, opts)]);
      // A plain object when every key is a string, because that is what a
      // caller wants; a Map when they are not, because an object cannot hold
      // the others without lying about them.
      if (entries.every(([k]) => typeof k === 'string')) return Object.fromEntries(entries);
      return new Map(entries);
    }
    case K.WITH_META: {
      // The metadata, then the value. Read in that order because that is the
      // order the runtime writes them (`runtime/src/codec.rs`, `K_WITH_META`).
      const m = read(r, opts);
      const v = read(r, opts);
      // A PRIMITIVE SILENTLY KEEPS NONE, which is the same answer the guest
      // decoder gives: `with-meta` on a kind that cannot carry any returns the
      // value unchanged. Bytes claiming metadata for a number produce the
      // number, not an error.
      if (v !== null && (typeof v === 'object' || typeof v === 'function')) {
        META.set(v, m);
      }
      return v;
    }
    case K.TAGGED: {
      // A tag and a form, both ordinary values. `{ tag, form }` because a
      // tagged literal is not the thing it wraps and flattening it to the form
      // would lose the only part a host reads it for.
      const tag_ = read(r, opts);
      return { tag: tag_, form: read(r, opts) };
    }
    case K.TABLE: {
      // COLUMN BY COLUMN on the wire, row by row in JS. A host gets the rows
      // it would write by hand, and the schema beside them -- the column TYPES
      // are not recoverable from the values, and a host that round-trips a
      // table needs them to send one back.
      const ncols = readU32(r);
      const schema = [];
      for (let i = 0; i < ncols; i++) schema.push([read(r, opts), read(r, opts)]);
      const nrows = readU32(r);
      const cols = [];
      for (let c = 0; c < ncols; c++) {
        const col = [];
        for (let i = 0; i < nrows; i++) col.push(read(r, opts));
        cols.push(col);
      }
      const rows = [];
      for (let i = 0; i < nrows; i++) {
        const row = {};
        for (let c = 0; c < ncols; c++) row[schema[c][0]] = cols[c][i];
        rows.push(row);
      }
      return { table: rows, schema };
    }
    case K.PORT: { const id = readU32(r); return { port: id }; }
    case K.SENTINEL: {
      const lo = readU32(r), hi = readU32(r);
      const label = readStr(r);
      return { sentinel: label, hostId: hi * 0x100000000 + lo };
    }
    default: throw new Error(`flint: unknown tag ${tag} in the encoding`);
  }
}
