// A small EDN reader and writer, so a JavaScript host can talk to a flint port
// opened with `flint.port.edn` without pulling in a dependency.
//
// EDN is what a flint port carries when nothing is lost: keywords, sets and
// non-string map keys all survive it, and JSON carries none of them. The subset
// here is what flint values actually are — nil, booleans, numbers, strings,
// keywords, symbols, vectors, lists, maps, sets — plus `#tag value`, which is
// read as `{tag, value}` rather than being refused.
//
// Keywords become `Keyword` objects rather than strings so that `:a` and `"a"`
// stay different on this side too; conflating them is exactly the loss that
// makes JSON the wrong wire format for this.

export class Keyword {
  constructor(name) { this.name = name; }
  toString() { return ':' + this.name; }
}
const kwCache = new Map();
export function kw(name) {
  let k = kwCache.get(name);
  if (!k) { k = new Keyword(name); kwCache.set(name, k); }
  return k;
}

export class Sym {
  constructor(name) { this.name = name; }
  toString() { return this.name; }
}

export class Tagged {
  constructor(tag, value) { this.tag = tag; this.value = value; }
}

const WS = new Set([' ', '\t', '\n', '\r', ',']);
const DELIM = new Set(['(', ')', '[', ']', '{', '}', '"', ';']);

class Reader {
  constructor(s) { this.s = s; this.i = 0; }
  peek() { return this.s[this.i]; }
  skip() {
    for (;;) {
      while (this.i < this.s.length && WS.has(this.s[this.i])) this.i++;
      if (this.s[this.i] === ';') {
        while (this.i < this.s.length && this.s[this.i] !== '\n') this.i++;
      } else return;
    }
  }
  read() {
    this.skip();
    const c = this.s[this.i];
    if (c === undefined) throw new Error('edn: unexpected end of input');
    if (c === '"') return this.readString();
    if (c === '[') { this.i++; return this.readSeq(']'); }
    if (c === '(') { this.i++; return this.readSeq(')'); }
    if (c === '{') { this.i++; return this.readMap('}'); }
    if (c === '#') {
      const n = this.s[this.i + 1];
      if (n === '{') { this.i += 2; return new Set(this.readSeq('}')); }
      if (n === '_') { this.i += 2; this.read(); return this.read(); }
      this.i++;
      const tag = this.readToken();
      return new Tagged(tag, this.read());
    }
    if (c === ':') { this.i++; return kw(this.readToken()); }
    return this.atom(this.readToken());
  }
  readToken() {
    const start = this.i;
    while (this.i < this.s.length && !WS.has(this.s[this.i]) && !DELIM.has(this.s[this.i])) this.i++;
    if (this.i === start) throw new Error(`edn: empty token at ${start}`);
    return this.s.slice(start, this.i);
  }
  atom(t) {
    if (t === 'nil') return null;
    if (t === 'true') return true;
    if (t === 'false') return false;
    if (/^[-+]?\d+$/.test(t)) return parseInt(t, 10);
    if (/^[-+]?(\d+\.\d*|\.\d+|\d+)([eE][-+]?\d+)?M?$/.test(t)) return parseFloat(t);
    return new Sym(t);
  }
  readString() {
    this.i++;
    let out = '';
    while (this.s[this.i] !== '"') {
      if (this.i >= this.s.length) throw new Error('edn: unterminated string');
      let c = this.s[this.i++];
      if (c === '\\') {
        const e = this.s[this.i++];
        c = e === 'n' ? '\n' : e === 't' ? '\t' : e === 'r' ? '\r'
          : e === '\\' ? '\\' : e === '"' ? '"' : e === 'b' ? '\b' : e === 'f' ? '\f'
          : e === 'u' ? String.fromCharCode(parseInt(this.s.substr(this.i, 4), 16)) : e;
        if (e === 'u') this.i += 4;
      }
      out += c;
    }
    this.i++;
    return out;
  }
  readSeq(close) {
    const out = [];
    for (;;) {
      this.skip();
      if (this.s[this.i] === close) { this.i++; return out; }
      out.push(this.read());
    }
  }
  readMap(close) {
    const out = new Map();
    for (;;) {
      this.skip();
      if (this.s[this.i] === close) { this.i++; return out; }
      const k = this.read();
      const v = this.read();
      out.set(k, v);
    }
  }
}

export function readString(s) { return new Reader(s).read(); }

/// A map with keyword keys, as a plain JS object — the shape a host usually
/// wants. Nested maps are converted too.
export function plain(v) {
  if (v instanceof Map) {
    const o = {};
    for (const [k, val] of v) o[k instanceof Keyword ? k.name : String(k)] = plain(val);
    return o;
  }
  if (Array.isArray(v)) return v.map(plain);
  if (v instanceof Keyword) return v;
  return v;
}

function esc(s) {
  return s.replace(/[\\"\n\r\t]/g, (c) =>
    ({ '\\': '\\\\', '"': '\\"', '\n': '\\n', '\r': '\\r', '\t': '\\t' }[c]));
}

export function writeString(v) {
  if (v === null || v === undefined) return 'nil';
  if (v === true) return 'true';
  if (v === false) return 'false';
  if (typeof v === 'number') return Number.isInteger(v) ? String(v) : String(v);
  if (typeof v === 'string') return '"' + esc(v) + '"';
  if (v instanceof Keyword) return ':' + v.name;
  if (v instanceof Sym) return v.name;
  if (v instanceof Tagged) return '#' + v.tag + ' ' + writeString(v.value);
  if (Array.isArray(v)) return '[' + v.map(writeString).join(' ') + ']';
  if (v instanceof Set) return '#{' + [...v].map(writeString).join(' ') + '}';
  if (v instanceof Map) {
    return '{' + [...v].map(([k, x]) => writeString(k) + ' ' + writeString(x)).join(' ') + '}';
  }
  if (typeof v === 'object') {
    // A plain object is written with keyword keys, which is what a flint script
    // reading it will expect.
    return '{' + Object.entries(v).map(([k, x]) => ':' + k + ' ' + writeString(x)).join(' ') + '}';
  }
  throw new Error(`edn: cannot write ${typeof v}`);
}

/// The codec shape `host/docstore.mjs` and the tests use.
const enc = new TextEncoder();
const dec = new TextDecoder();
export const codec = {
  encode: (v) => enc.encode(writeString(v)),
  decode: (bytes) => plain(readString(typeof bytes === 'string' ? bytes : dec.decode(bytes))),
};
