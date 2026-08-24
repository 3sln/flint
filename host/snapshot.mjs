// Reading a flint snapshot (doc/decisions/0015).
//
// A binary snapshot nobody can read is a core dump. This is the reader, and it
// is deliberately the same object model the debugger needs, so it is written
// once.
//
// The two capabilities this project needed and never had, until now:
//
//   * REVERSE pointer lookup -- "what points at this address" is the question
//     four sessions of a collector bug kept asking and could not answer;
//   * whole-heap validation in ONE pass, so every dangling or forwarded
//     reference is reported together rather than one per run.

const MAGIC = 0x464c534e;
const VERSION = 1;

export const TY = {
  0: 'FREE', 1: 'FWD', 2: 'STR', 3: 'BIGINT', 4: 'SYM', 5: 'KW', 6: 'CONS',
  7: 'EMPTY_LIST', 8: 'LAZYSEQ', 9: 'VEC', 10: 'NODE', 11: 'VECSEQ',
  12: 'STRSEQ', 13: 'RANGE', 14: 'ARRAYMAP', 15: 'HASHMAP', 16: 'BMNODE',
  17: 'ARRAYNODE', 18: 'COLLNODE', 19: 'SET', 20: 'MAPENTRY', 21: 'CLOSURE',
  22: 'NATIVEFN', 23: 'VAR', 24: 'ATOM', 25: 'TVEC', 26: 'TMAP', 27: 'TSET',
  28: 'RECORD', 29: 'REGEX', 30: 'REDUCED', 31: 'EXINFO', 32: 'MULTIFN',
  33: 'DELAY', 34: 'VOLATILE', 35: 'RAW', 36: 'ITERSEQ', 37: 'CHUNKSEQ',
  38: 'TYPE', 39: 'THREAD', 40: 'PORT', 41: 'SCHED',
};
const TY_FREE = 0, TY_FWD = 1, TY_STR = 2, TY_BIGINT = 3, TY_RAW = 35;
const TAG_HEAP = 0xfff9;

const align8 = (n) => (n + 7) & ~7;

class Reader {
  constructor(b) { this.b = b; this.i = 0; this.v = new DataView(b.buffer, b.byteOffset, b.byteLength); }
  u32() { const x = this.v.getUint32(this.i, true); this.i += 4; return x; }
  u64() { const x = this.v.getBigUint64(this.i, true); this.i += 8; return x; }
  u32s() { const n = this.u32(); const out = new Uint32Array(n); for (let k = 0; k < n; k++) out[k] = this.u32(); return out; }
  vals() { const n = this.u32(); const out = new BigUint64Array(n); for (let k = 0; k < n; k++) out[k] = this.u64(); return out; }
}

/// Parse a snapshot. Refuses another layout version by name rather than reading
/// a plausible-looking heap that means something else.
export function read(bytes) {
  const r = new Reader(bytes);
  const magic = r.u32();
  if (magic !== MAGIC) throw new Error(`not a flint snapshot (magic ${magic.toString(16)})`);
  const version = r.u32();
  if (version !== VERSION) {
    throw new Error(`snapshot is layout version ${version}, this reader speaks ${VERSION}`);
  }
  const s = { version };
  s.inUse = r.u32(); s.reserved = r.u32();
  s.youngBase = r.u32(); s.half = r.u32(); s.from = r.u32(); s.to = r.u32();
  s.toBump = r.u32(); s.bump = r.u32(); s.fromEnd = r.u32();
  s.oldCapacity = r.u32(); s.oldLive = r.u32(); s.maxHeap = r.u32();
  s.collecting = !!r.u32(); s.oom = !!r.u32(); s.stress = !!r.u32(); s.badForward = r.u32();
  const nch = r.u32(); s.oldChunks = [];
  for (let k = 0; k < nch; k++) s.oldChunks.push({ addr: r.u32(), len: r.u32() });
  s.freeLists = r.u32s();
  s.remembered = r.u32s();
  s.stats = { minor: r.u64(), major: r.u64(), bytesAllocated: r.u64(), bytesCopied: r.u64(), bytesPromoted: r.u64(), peakLive: r.u64() };
  s.stackTop = r.u32();
  s.roots = { stack: r.vals(), shadow: r.vals(), globals: r.vals(), consts: r.vals(), singletons: r.vals() };
  const nt = r.u32(); s.interns = [];
  for (let k = 0; k < nt; k++) {
    const n = r.u32(), count = r.u32(), slots = [];
    for (let j = 0; j < n; j++) slots.push({ hash: r.u32(), bits: r.u64() });
    s.interns.push({ count, slots });
  }
  const nf = r.u32(); s.frames = [];
  for (let k = 0; k < nf; k++) s.frames.push({ fp: r.u32(), ip: r.u32(), end: r.u32(), retTo: r.u32(), handlers: r.u32() });
  const nh = r.u32(); s.handlers = [];
  for (let k = 0; k < nh; k++) s.handlers.push({ frame: r.u32(), stackTop: r.u32(), target: r.u32(), shadow: r.u32() });
  s.thrown = r.u64(); s.parkOn = r.u64();
  s.steps = r.u64(); s.gasLimit = r.u64(); s.sliceEnd = r.u64(); s.checkpoint = r.u64();
  s.gasTrips = r.u32(); s.memTrips = r.u32(); s.status = r.u32() | 0; s.champAdded = !!r.u32();
  // Regions, not one contiguous range: on wasm an old chunk can sit far above
  // `in_use`, and a reader that assumed contiguity would read zeros for it.
  const nreg = r.u32();
  s.regions = [];
  for (let k = 0; k < nreg; k++) {
    const addr = r.u32(), len = r.u32();
    s.regions.push({ addr, len, bytes: bytes.subarray(r.i, r.i + len) });
    r.i += len;
  }
  for (const g of s.regions) g.view = new DataView(g.bytes.buffer, g.bytes.byteOffset, g.bytes.byteLength);
  s.walkErrors = [];
  return s;
}

function regionOf(s, addr) {
  for (const g of s.regions) if (addr >= g.addr && addr < g.addr + g.len) return g;
  return null;
}
export const readU32 = (s, addr) => {
  const g = regionOf(s, addr);
  if (!g) throw new Error(`address ${addr} is in no captured region`);
  return g.view.getUint32(addr - g.addr, true);
};
export const readU64 = (s, addr) => {
  const g = regionOf(s, addr);
  if (!g) throw new Error(`address ${addr} is in no captured region`);
  return g.view.getBigUint64(addr - g.addr, true);
};

export const tyOf = (s, addr) => readU32(s, addr) >>> 24;
export const lenOf = (s, addr) => readU32(s, addr + 4);

export function sizeOf(s, addr) {
  const t = tyOf(s, addr), n = lenOf(s, addr);
  if (t === TY_FREE) return n;
  if (t === TY_FWD) return 8;
  if (t === TY_STR) return align8(16 + n);
  if (t === TY_BIGINT || t === TY_RAW) return align8(8 + n);
  return 8 + n * 8;
}

/// Is this a heap pointer, and to where?
export function heapAddr(bits) {
  const tag = Number(bits >> 48n);
  return tag === TAG_HEAP ? Number(bits & 0xffffffffn) : null;
}

/// Every object in the heap, walked linearly. Not a graph traversal: a linear
/// walk cannot miss an object by missing an edge.
export function* objects(s) {
  const spans = [[s.from, s.bump], ...s.oldChunks.map((c) => [c.addr, c.addr + c.len])];
  for (const [lo, hi] of spans) {
    let a = lo;
    while (a < hi) {
      const size = sizeOf(s, a);
      if (size < 8 || a + size > hi) {
        // The walk cannot continue past a bad header. Record it rather than
        // silently truncating: a truncated walk makes every later object look
        // absent, which would turn one real problem into hundreds of false
        // ones -- the exact failure this tool exists to avoid.
        if (s.walkErrors) s.walkErrors.push({ span: [lo, hi], at: a, size });
        break;
      }
      yield { addr: a, ty: tyOf(s, a), tyName: TY[tyOf(s, a)] ?? `ty${tyOf(s, a)}`, len: lenOf(s, a), size, old: lo !== s.from };
      a += size;
    }
    // A linear walk must land exactly on the end of its span. Landing short or
    // long means the object sizes disagree with the layout, and every object
    // after the divergence is missing -- which would be reported as hundreds of
    // interior pointers rather than as the one parse error it is.
    if (a !== hi && s.walkErrors) s.walkErrors.push({ span: [lo, hi], endedAt: a, short: hi - a });
  }
}

/// The slot values of an object, for the types that hold values.
export function slots(s, addr) {
  const t = tyOf(s, addr);
  if (t === TY_STR || t === TY_BIGINT || t === TY_RAW || t === TY_FREE || t === TY_FWD) return [];
  const n = lenOf(s, addr), out = [];
  for (let i = 0; i < n; i++) out.push(readU64(s, addr + 8 + i * 8));
  return out;
}

/// Every root, tagged with where it came from.
export function* roots(s) {
  const arrs = [['stack', s.roots.stack.subarray(0, s.stackTop)], ['shadow', s.roots.shadow],
                ['globals', s.roots.globals], ['consts', s.roots.consts], ['singletons', s.roots.singletons]];
  for (const [which, a] of arrs) for (let i = 0; i < a.length; i++) yield { which, i, bits: a[i] };
}

/// WHAT POINTS AT THIS ADDRESS. One pass over every object and every root.
export function pointersTo(s, target) {
  const hits = [];
  for (const r of roots(s)) if (heapAddr(r.bits) === target) hits.push({ from: `roots.${r.which}[${r.i}]` });
  for (const o of objects(s)) {
    const sl = slots(s, o.addr);
    for (let i = 0; i < sl.length; i++) {
      if (heapAddr(sl[i]) === target) hits.push({ from: `${o.tyName}@${o.addr}`, slot: i, addr: o.addr });
    }
  }
  return hits;
}

/// Validate the whole heap in ONE pass and report every bad pointer together,
/// rather than tripping over them one run at a time.
export function validate(s) {
  const live = new Set();
  const problems = [];
  let walked = 0;
  s.walkErrors = [];
  for (const o of objects(s)) { live.add(o.addr); walked++; }
  // If the linear walk could not finish a span, every object after that point
  // is missing from `live` and would be reported as an interior pointer. Say
  // so instead of reporting hundreds of consequences of one cause.
  if (s.walkErrors.length) {
    return { walked, truncated: s.walkErrors, problems: [] };
  }
  const inSpace = (a) =>
    (a >= s.from && a < s.bump) || s.oldChunks.some((c) => a >= c.addr && a < c.addr + c.len);
  const check = (bits, where) => {
    const a = heapAddr(bits);
    if (a === null) return;
    if (!inSpace(a)) { problems.push({ kind: 'out-of-space', where, addr: a }); return; }
    if (!live.has(a)) { problems.push({ kind: 'interior', where, addr: a }); return; }
    const t = tyOf(s, a);
    if (t === TY_FWD) problems.push({ kind: 'forwarded', where, addr: a });
    else if (t === TY_FREE) problems.push({ kind: 'freed', where, addr: a });
  };
  for (const r of roots(s)) check(r.bits, `roots.${r.which}[${r.i}]`);
  for (const o of objects(s)) {
    if (o.ty === TY_FREE || o.ty === TY_FWD) continue;
    const sl = slots(s, o.addr);
    for (let i = 0; i < sl.length; i++) check(sl[i], `${o.tyName}@${o.addr}.${i}`);
  }
  return { walked, problems };
}

/// What a collection did: what moved, what died, what changed. This is how you
/// answer "what did that collection do" WITHOUT instrumenting the collector.
export function diff(a, b) {
  const A = new Map(), B = new Map();
  for (const o of objects(a)) A.set(o.addr, o);
  for (const o of objects(b)) B.set(o.addr, o);
  const gone = [], appeared = [], changed = [];
  for (const [addr, o] of A) {
    const n = B.get(addr);
    if (!n) { gone.push(o); continue; }
    if (n.ty !== o.ty || n.len !== o.len) changed.push({ addr, before: o, after: n });
  }
  for (const [addr, o] of B) if (!A.has(addr)) appeared.push(o);
  return {
    gone, appeared, changed,
    minors: Number(b.stats.minor - a.stats.minor),
    majors: Number(b.stats.major - a.stats.major),
    // A forwarded object in `b` at an address live in `a` is exactly "this
    // moved", which is the question a collection diff exists to answer.
    moved: [...B.values()].filter((o) => o.ty === TY_FWD && A.has(o.addr)),
  };
}

export function summary(s) {
  const byType = new Map();
  let bytes = 0;
  for (const o of objects(s)) {
    const e = byType.get(o.tyName) ?? { n: 0, bytes: 0 };
    e.n++; e.bytes += o.size;
    byType.set(o.tyName, e);
    bytes += o.size;
  }
  return { objects: [...byType.entries()].sort((x, y) => y[1].bytes - x[1].bytes), bytes };
}
