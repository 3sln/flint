// Running a flint module on WHATEVER JavaScript engine is available.
//
// `flint.mjs` is the node half of this: it reads a file with `node:fs` and
// talks to `process`. That is the right shape when node is a given, and the
// native CLI is exactly the case where it is not -- it carries no wasm engine
// and no node, so it looks for one (`DECISIONS.md#wasm-engine`). The engine it
// finds may be JavaScriptCore, which ships with macOS and needs no install.
//
// So this module holds the two things that differ between engines, and nothing
// else. The guest driver itself is already portable and lives with the SDK.
//
// The jsc shell is ECMAScript, not a browser: `TextEncoder` and `TextDecoder`
// are Web APIs and it has neither. The codec asks only for UTF-8, which is
// twenty lines.
export function installShim(g) {
  if (typeof g.TextEncoder === 'undefined') {
    g.TextEncoder = class {
      encode(s) {
        const out = [];
        for (let i = 0; i < s.length; i++) {
          let c = s.codePointAt(i);
          if (c > 0xffff) i++;
          if (c < 0x80) out.push(c);
          else if (c < 0x800) out.push(0xc0 | (c >> 6), 0x80 | (c & 63));
          else if (c < 0x10000) out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
          else out.push(0xf0 | (c >> 18), 0x80 | ((c >> 12) & 63), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
        }
        return new Uint8Array(out);
      }
    };
  }
  if (typeof g.TextDecoder === 'undefined') {
    g.TextDecoder = class {
      decode(b) {
        const u = b instanceof Uint8Array ? b : new Uint8Array(b.buffer || b);
        let s = '';
        for (let i = 0; i < u.length; ) {
          const c = u[i];
          let cp, n;
          if (c < 0x80) { cp = c; n = 1; }
          else if ((c & 0xe0) === 0xc0) { cp = c & 31; n = 2; }
          else if ((c & 0xf0) === 0xe0) { cp = c & 15; n = 3; }
          else { cp = c & 7; n = 4; }
          for (let k = 1; k < n; k++) cp = (cp << 6) | (u[i + k] & 63);
          s += String.fromCodePoint(cp);
          i += n;
        }
        return s;
      }
    };
  }
}

/// The module's bytes, by whatever this engine calls reading a file.
///
/// `node:fs` covers node AND bun AND recent deno, so the two special cases are
/// jsc (a bare `readFile`, which needs "binary" or it hands back a string) and
/// deno older than its node compatibility.
export async function readBytes(path) {
  if (typeof readFile === 'function') return readFile(path, 'binary');
  if (typeof Deno !== 'undefined' && Deno.readFileSync) return Deno.readFileSync(path);
  const { readFileSync } = await import('node:fs');
  return new Uint8Array(readFileSync(path));
}
