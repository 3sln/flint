// The SpiderMonkey shell's equivalent of `bench/xrt-run.mjs`: no ESM, no
// `process`, and `read(path, 'binary')` for bytes. Same work: read a file,
// compile, instantiate, call `main`.
var bytes = read(scriptArgs[0], 'binary');
var inst = new WebAssembly.Instance(new WebAssembly.Module(bytes), {});
inst.exports.main();
