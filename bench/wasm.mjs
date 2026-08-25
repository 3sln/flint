// Timing harness for compiled flint modules.
//
// Cold start is measured as three separate numbers because they are three
// different costs: reading the file, WebAssembly.compile (the host's own
// compiler), and instantiate + first call (which is where flint's own startup --
// heap reservation, image loading, running every top-level initialiser -- lives).
import { readFileSync } from 'fs';
import { instantiate } from './../host/flint.mjs';

const [, , path, ...rest] = process.argv;
const reps = Number(process.env.REPS || 5);
const args = rest;

function now() { return Number(process.hrtime.bigint()) / 1e6; }

let best = { read: Infinity, compile: Infinity, start: Infinity, run: Infinity };
let out = '', steps = null, allocated = null, collections = null;

for (let r = 0; r < reps; r++) {
  let t = now();
  const bytes = readFileSync(path);
  const read = now() - t;

  t = now();
  const module = new WebAssembly.Module(bytes);
  const compile = now() - t;

  // instantiate + first main(): flint's own cold start.
  t = now();
  const { main, exports } = instantiate(module);
  const res = main(...args);
  const start = now() - t;

  // steady state: the same call again on a warm instance.
  t = now();
  const res2 = main(...args);
  const run = now() - t;

  out = res2.out;
  // `null`, not zero. These live only in a diagnostics build, and a harness
  // that reads absent as zero prints "0 bytes allocated" for a program that
  // allocated megabytes.
  allocated = exports.stat_bytes_allocated ? Number(exports.stat_bytes_allocated()) : null;
  collections = exports.stat_collections ? Number(exports.stat_collections()) : null;
  if (res.code !== 0) { console.error('module failed:', res.out); process.exit(1); }

  best = {
    read: Math.min(best.read, read),
    compile: Math.min(best.compile, compile),
    start: Math.min(best.start, start),
    run: Math.min(best.run, run),
  };
}

// A second pass with the step counter on, to divide time by instructions.
{
  const bytes = readFileSync(path);
  const module = new WebAssembly.Module(bytes);
  const { main, exports } = instantiate(module);
  if (exports.set_step_limit && exports.stat_steps) {
    // NOT 0xffffffff/0xffffffff: that is exactly `u64::MAX`, which the runtime
    // reads as "no checkpoint" -- so the largest possible limit turned the
    // counter off and the answer came back zero.
    exports.set_step_limit(0x7fffffff, 0xffffffff);
    main(...args);
    const before = Number(exports.stat_steps());
    main(...args);
    const n = Number(exports.stat_steps()) - before;
    // A program that produced an answer dispatched instructions. Zero here means
    // the counter did not engage, and reporting it as a measurement is how this
    // harness came to claim a tight loop runs no instructions.
    steps = n > 0 ? n : null;
  }
}

console.log(JSON.stringify({
  path, bytes: readFileSync(path).length,
  read: best.read, compile: best.compile, start: best.start, run: best.run,
  steps, allocated, collections, out: out.slice(0, 60),
}));
