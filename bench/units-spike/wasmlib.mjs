// Minimal wasm module reader for the spike: sections, imports, exports,
// global initialisers, data segments.
export function parse(buf) {
  const b = buf instanceof Uint8Array ? buf : new Uint8Array(buf);
  let i = 8; const sections = [];
  const uleb = () => { let r = 0, s = 0, x; do { x = b[i++]; r += (x & 0x7f) * 2 ** s; s += 7; } while (x & 0x80); return r; };
  const sleb = () => { let r = 0n, s = 0n, x; do { x = b[i++]; r |= BigInt(x & 0x7f) << s; s += 7n; } while (x & 0x80); if (x & 0x40) r -= 1n << s; return Number(r); };
  const name = () => { const n = uleb(); const s = Buffer.from(b.subarray(i, i + n)).toString(); i += n; return s; };
  while (i < b.length) { const id = b[i++]; const size = uleb(); const start = i; let nm = null; if (id === 0) { nm = name(); } sections.push({ id, name: nm, start, end: start + size, size: start + size - (start - 1 - lebLen(size)) }); i = start + size; }
  function lebLen(n) { let k = 0; do { n = Math.floor(n / 128); k++; } while (n); return k; }
  const m = { bytes: b.length, sections, imports: [], exports: {}, globals: [], data: [], funcImports: 0, globalImports: 0, codeBytes: 0, nfuncs: 0 };
  for (const s of sections) {
    i = s.start;
    if (s.id === 2) { const n = uleb(); for (let k = 0; k < n; k++) { const mod = name(), fld = name(), kind = b[i++]; let desc;
      if (kind === 0) { desc = uleb(); m.funcImports++; } else if (kind === 1) { i++; const f = uleb(); uleb(); if (f & 1) uleb(); } else if (kind === 2) { const f = uleb(); uleb(); if (f & 1) uleb(); } else if (kind === 3) { i += 2; m.globalImports++; }
      m.imports.push({ mod, fld, kind, desc }); } }
    if (s.id === 7) { const n = uleb(); for (let k = 0; k < n; k++) { const nm = name(), kind = b[i++], idx = uleb(); m.exports[nm] = { kind, index: idx }; } }
    if (s.id === 6) { const n = uleb(); for (let k = 0; k < n; k++) { i += 2; const op = b[i++]; let v = null; if (op === 0x41) v = sleb(); else { while (b[i] !== 0x0b) i++; } i++; m.globals.push(v); } }
    if (s.id === 11) { const n = uleb(); for (let k = 0; k < n; k++) { const f = uleb(); let off = null; if (f === 2) uleb(); if (f !== 1) { const op = b[i++]; if (op === 0x41) off = sleb(); i++; } const len = uleb(); m.data.push({ offset: off, start: i, len }); i += len; } }
    if (s.id === 10) { m.codeBytes = s.end - s.start; m.nfuncs = uleb(); }
  }
  m.globalValue = (exp) => { const e = m.exports[exp]; if (!e || e.kind !== 3) return undefined; return m.globals[e.index - m.globalImports]; };
  return m;
}
