// The SpiderMonkey shell's equivalent of `bench/xrt-run.mjs`: no ESM, no
// `process`, and `read(path, 'binary')` for bytes. Same work: read a file,
// compile, instantiate, call a NAMED function.
var bytes = read(scriptArgs[0], 'binary');
var inst = new WebAssembly.Instance(new WebAssembly.Module(bytes), {});
var FN = scriptArgs[1] || 'construe.bench.xrt25/main';
wireCall(inst.exports, FN);

// `[name []]` in the wire format, written by hand: these drivers use nothing but
// a `WebAssembly.Instance` on purpose (`DECISIONS.md#cross-runtime-benchmarks`), so there is no SDK
// to encode for them, and nothing is called automatically any more
// (`DECISIONS.md#structured-ports` step 5).
function wireCall(e, fn) {
  var enc = function (s) {
    var u = new TextEncoder().encode(s);
    var b = [5];                                     // K_STRING
    for (var i = 0; i < 4; i++) b.push((u.length >> (8 * i)) & 0xff);
    return b.concat(Array.prototype.slice.call(u));
  };
  var call = [8, 2, 0, 0, 0]                         // K_VECTOR, 2 items
    .concat(enc(fn))
    .concat([8, 0, 0, 0, 0]);                        // K_VECTOR, no arguments
  var b = Uint8Array.from(call);
  var p = e.arg_alloc(b.length);
  new Uint8Array(e.memory.buffer).set(b, p);
  return e.flint_call(p, b.length);
}
