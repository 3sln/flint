// The wire codec (`doc/decisions/0025`).
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
  SENTINEL: 16,
};
const NO_NS = 0xffffffff;
const TAG_NAME = {
  0: 'nil', 1: 'boolean', 2: 'boolean', 3: 'int', 4: 'double', 5: 'string',
  6: 'keyword', 7: 'symbol', 8: 'vector', 9: 'list', 10: 'map', 11: 'set',
  14: 'bytes', 15: 'port', 16: 'sentinel',
};

const enc = new TextEncoder();
const dec = new TextDecoder();

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

  /// A JS value, guessed by shape.
  ///
  /// A JS object has STRING keys, so string keys are what it means.
  /// `{keywordizeKeys: true}` makes them keywords for a whole structure, and a
  /// leading colon does it for one -- `':a'` is `:a` wherever it appears. The
  /// cost is that the literal string `":a"` needs `codec.str(':a')`, which is
  /// the right way round: the explicit builder is always there.
  from(x, opts = {}) {
    const kwKeys = !!opts.keywordizeKeys;
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
    case K.PORT: { const id = readU32(r); return { port: id }; }
    case K.SENTINEL: {
      const lo = readU32(r), hi = readU32(r);
      const label = readStr(r);
      return { sentinel: label, hostId: hi * 0x100000000 + lo };
    }
    default: throw new Error(`flint: unknown tag ${tag} in the encoding`);
  }
}
