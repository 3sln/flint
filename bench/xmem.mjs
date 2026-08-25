// Resident memory per engine (`doc/decisions/0018`).
//
// 0018 asks for resident AND reserved, "since engines differ on whether a 6.4 MB
// reservation is committed". flint reserves a 6.3 MB linear memory; what that
// costs a process is an engine question, and it is the binding one under a
// Worker isolate's 128 MiB ceiling.
//
// Best-of-N, not a single run: the first measurement of wasmtime came back at
// 45.6 MB for a TRIVIAL module and 13.7 MB for a large one, which is backwards.
// Repeated, it is 10.6 and 13.7 every time. One sample of a memory high-water
// mark is a sample of whatever else the machine was doing.
import { execFileSync, spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { homedir } from 'node:os';

const have = (bin) => {
  if (bin.startsWith('/')) return existsSync(bin);
  try { execFileSync('which', [bin], { stdio: 'ignore' }); return true; } catch { return false; }
};

function rssMB(cmd, args) {
  // `/usr/bin/time -l` reports on STDERR, so this reads stderr rather than
  // stdout. The first version read stdout, got nothing, and every cell in the
  // table came back NaN -- which at least failed visibly, unlike the `catch`
  // below, which used to swallow the reason.
  const r = spawnSync('/usr/bin/time', ['-l', cmd, ...args], { encoding: 'utf8' });
  const m = (r.stderr || '').match(/(\d+)\s+maximum resident set size/);
  if (!m) throw new Error(`no rss from /usr/bin/time for ${cmd}: ` +
                          (r.error?.message || (r.stderr || '').split('\n')[0] || 'no output'));
  return Number(m[1]) / 1048576;
}

function best(reps, cmd, args) {
  let b = Infinity;
  let why = null;
  for (let i = 0; i < reps; i++) {
    try { b = Math.min(b, rssMB(cmd, args)); } catch (e) { why = e.message; }
  }
  if (!Number.isFinite(b)) throw new Error(why || 'no successful run');
  return b;
}

const wasmtime = `${homedir()}/.wasmtime/bin/wasmtime`;
const engines = [
  ['node (V8)', 'node', ['bench/xrt-run.mjs']],
  ['bun (JavaScriptCore)', 'bun', ['bench/xrt-run.mjs']],
  ['deno (V8)', 'deno', ['run', '--allow-read', 'bench/xrt-run.mjs']],
  ['wasmtime (Cranelift)', wasmtime, ['--invoke', 'main']],
  ['wasm3 (interpreter)', 'wasm3', ['--func', 'main']],
].filter(([, bin]) => have(bin));

const REPS = Number(process.env.XMEM_REPS || 5);
const pad = (s, w) => String(s).padEnd(w);
const rpad = (s, w) => String(s).padStart(w);

console.log('resident memory per engine (0018)');
console.log('  flint reserves a 6.3 MB linear memory. What a process pays for that, and');
console.log(`  for the engine around it, differs by an order of magnitude. Best of ${REPS}.`);
console.log();
console.log(pad('engine', 24) + rpad('trivial', 11) + rpad('flint', 11) + rpad('flint costs', 13));
console.log('-'.repeat(59));
for (const [name, bin, args] of engines) {
  try {
    const triv = best(REPS, bin, [...args, 'out/triv.wasm']);
    const full = best(REPS, bin, [...args, 'out/xrt-25.wasm']);
    console.log(pad(name, 24) + rpad(triv.toFixed(1) + ' MB', 11) +
                rpad(full.toFixed(1) + ' MB', 11) + rpad('+' + (full - triv).toFixed(1) + ' MB', 13));
  } catch (e) {
    console.log(pad(name, 24) + '  SKIPPED: ' + e.message);
  }
}
console.log();
console.log('  trivial      the engine running a near-empty flint module: mostly the engine.');
console.log('  flint        the same engine running the 257 KB construe fixture.');
console.log('  flint costs  the difference, which is roughly flint\'s heap plus its code.');
console.log();
console.log('  Under a 128 MiB Worker isolate ceiling, the engine is the larger half of');
console.log('  the bill on a JS runtime and a rounding error on the two standalone ones.');
