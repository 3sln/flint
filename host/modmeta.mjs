// Read flint's module metadata straight from the bytes (`doc/decisions/0020`).
//
// No `WebAssembly.compile`, no instantiate: a runner has to decide WHETHER to
// instantiate, and on what glue, and that decision cannot depend on having
// already done it. Custom sections are ignored by every engine and sit in the
// byte stream, so this is a few lines of parsing anywhere.
export const SECTION = 'flint';

function uleb(b, i) {
  let n = 0, shift = 0, byte;
  do { byte = b[i++]; n |= (byte & 0x7f) << shift; shift += 7; } while (byte & 0x80);
  return [n, i];
}

/// The raw EDN text of the section, its byte offset, or null when absent.
export function readSection(bytes, name = SECTION) {
  if (bytes.length < 8) return null;
  let i = 8;
  while (i < bytes.length) {
    const id = bytes[i];
    const [size, afterSize] = uleb(bytes, i + 1);
    if (id === 0) {
      const [nlen, afterName] = uleb(bytes, afterSize);
      const nm = Buffer.from(bytes.subarray(afterName, afterName + nlen)).toString('utf8');
      if (nm === name) {
        return {
          text: Buffer.from(bytes.subarray(afterName + nlen, afterSize + size)).toString('utf8'),
          offset: i,
          total: bytes.length,
        };
      }
    }
    i = afterSize + size;
  }
  return null;
}
