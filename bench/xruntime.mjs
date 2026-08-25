// Benchmark flint across wasm ENGINES (`doc/decisions/0018`).
//
// Every number in the README is V8. The claim on the tin is "runs anywhere",
// and 0018's point is that the answers -- not just the numbers -- are
// engine-dependent, because the interpreter's cost is concentrated in a hot
// `br_table` dispatch loop and that is exactly the construct engines differ
// most on.
//
// ## Why this needs no per-engine harness
//
// A flint module imports NOTHING and its entry is exported as `main`, so every
// engine here runs the identical bytes by invoking one export. wasmtime and
// wasm3 drive it from the command line with no host code at all. That satisfies
// 0018's first requirement -- a per-engine harness would be measuring
// harnesses.
//
// The iteration count is baked into the module rather than passed, because a
// CLI `--invoke` cannot drive flint's `arg_alloc`/`arg_push` ABI. So there is a
// FAMILY of modules, one per count, and every engine sees the same family.
//
// ## What the two numbers mean
//
// Timing the family at several counts and fitting a line separates what scales
// with work from what does not:
//
//   slope     = time per iteration once running. Divided by the iteration's
//               instruction count -- which `0009` guarantees is identical on
//               every engine and every machine -- this is ns/instruction, the
//               apples-to-apples metric 0018 asks for.
//   intercept = everything that does not scale: process start, wasm compile,
//               instantiate, and the module's own top-level initialisers.
//
// Determinism is what makes the division legitimate: the work is provably the
// same, so only the time differs.
import { execFileSync } from 'node:child_process';
import { existsSync, statSync } from 'node:fs';
import { homedir } from 'node:os';

// Counts big enough that the WORK dominates the fixed cost. The first version
// used 0..8, where one iteration is ~0.2 ms against a 20-35 ms process start --
// and the fit then reported node and deno, both V8, as 5.1 and 26.9
// ns/instruction. Two numbers that far apart for the same engine are a
// measurement of noise, not of an engine.
export const COUNTS = [0, 25, 50, 100, 200];
// Measured once with `stat_steps` under node; identical everywhere by 0009.
export const STEPS_PER_ITER = 33233;
export const BASE_STEPS = 1419;

const mod = (n, aot) => (aot ? `out/aot-${n}.wasm` : `out/xrt-${n}.wasm`);

/// Engines that take a module and an export name on the command line. Each
/// entry is what a *user* would run, not something built for this benchmark.
export function cliEngines() {
  const wasmtime = `${homedir()}/.wasmtime/bin/wasmtime`;
  const list = [
    { name: 'node (V8)', kind: 'jit',
      cmd: (m) => ['node', ['bench/xrt-run.mjs', m]] },
    { name: 'bun (JavaScriptCore)', kind: 'jit',
      cmd: (m) => ['bun', ['bench/xrt-run.mjs', m]] },
    { name: 'deno (V8)', kind: 'jit',
      cmd: (m) => ['deno', ['run', '--allow-read', 'bench/xrt-run.mjs', m]] },
    { name: 'wasmtime (Cranelift)', kind: 'jit',
      cmd: (m) => [wasmtime, ['--invoke', 'main', m]] },
    { name: 'wasm3 (interpreter)', kind: 'interp',
      cmd: (m) => ['wasm3', ['--func', 'main', m]] },
  ];
  return list.filter((e) => {
    const [bin] = e.cmd(mod(0, false));
    if (bin.startsWith('/')) return existsSync(bin);
    // `which`, not `command -v` through a shell: the shell form concatenates
    // rather than escapes its arguments, and node deprecates it for that.
    try { execFileSync('which', [bin], { stdio: 'ignore' }); return true; }
    catch { return false; }
  });
}

/// The engine's own version string. 0018 requires it recorded, because engine
/// performance moves fast and a table without versions ages into a claim about
/// nothing in particular.
export function versionOf(bin, args = ['--version']) {
  try {
    return execFileSync(bin, args, { encoding: 'utf8' }).trim().split('\n')[0];
  } catch { return 'unknown'; }
}

export function best(reps, f) {
  let b = Infinity;
  for (let i = 0; i < reps; i++) {
    const t = process.hrtime.bigint();
    f();
    const d = Number(process.hrtime.bigint() - t) / 1e6;
    if (d < b) b = d;
  }
  return b;
}

/// Least squares over (count, ms). Returns { slope, intercept } in ms.
export function fit(points) {
  const n = points.length;
  const sx = points.reduce((a, [x]) => a + x, 0);
  const sy = points.reduce((a, [, y]) => a + y, 0);
  const sxx = points.reduce((a, [x]) => a + x * x, 0);
  const sxy = points.reduce((a, [x, y]) => a + x * y, 0);
  const slope = (n * sxy - sx * sy) / (n * sxx - sx * sx);
  const intercept = (sy - slope * sx) / n;
  // R^2 is reported rather than kept private: a slope fitted through noise
  // looks exactly like a slope fitted through signal until you ask.
  const mean = sy / n;
  const ssTot = points.reduce((a, [, y]) => a + (y - mean) ** 2, 0);
  const ssRes = points.reduce((a, [x, y]) => a + (y - (intercept + slope * x)) ** 2, 0);
  return { slope, intercept, r2: ssTot === 0 ? 1 : 1 - ssRes / ssTot };
}

const pad0 = (s, w) => String(s).padEnd(w);

if (import.meta.url === `file://${process.argv[1]}`) {
  const pad = pad0;
  for (const n of COUNTS) {
    if (!existsSync(mod(n))) {
      console.error(`missing ${mod(n)} -- run bin/bench-xruntime`);
      process.exit(1);
    }
  }
  const size = statSync(mod(COUNTS[COUNTS.length - 1])).size;
  console.log('flint across wasm engines (doc/decisions/0018)');
  console.log(`  one module, ${size} bytes, no imports, entry exported as \`main\`.`);
  console.log(`  every engine runs the SAME bytes; wasmtime and wasm3 need no host code.`);
  console.log(`  workload: construe's seed interpreter over 4 real contexts,`);
  console.log(`  ${STEPS_PER_ITER} flint instructions per iteration -- the same count on`);
  console.log(`  every engine, which is what makes ns/instruction comparable (0009).`);
  console.log();

  const REPS = Number(process.env.XRT_REPS || 5);
  console.log('engines, as found on this machine:');
  for (const e of cliEngines()) {
    const [bin] = e.cmd(mod(0, false));
    console.log('  ' + pad0(e.name, 24) + versionOf(bin));
  }
  console.log('  ' + pad0('flint', 24) + execFileSync('git', ['rev-parse', '--short', 'HEAD'],
                                                      { encoding: 'utf8' }).trim());
  console.log();
  // `0018` says the AOT ratio is the number that varies most between engines,
  // and names the reason: on V8 TurboFan optimises the dispatch loop hard, so
  // eliminating dispatch looked marginal -- while on an engine with no tier-up
  // dispatch could dominate completely. It is measured here rather than argued,
  // and only when the AOT family has been built.
  const haveAot = COUNTS.every((n) => existsSync(mod(n, true)));
  const measure = (eng, aot) => {
    const pts = [];
    for (const n of COUNTS) {
      const [bin, args] = eng.cmd(mod(n, aot));
      const ms = best(REPS, () => execFileSync(bin, args, { stdio: 'ignore' }));
      pts.push([n, ms]);
    }
    return { ...fit(pts), pts };
  };
  const rows = [];
  for (const eng of cliEngines()) {
    try {
      const base = measure(eng, false);
      const aot = haveAot ? measure(eng, true) : null;
      rows.push({ eng, ...base, aot });
    } catch (err) { rows.push({ eng, failed: String(err.message).split('\n')[0] }); }
  }

  const rpad = (s, w) => String(s).padStart(w);
  console.log(pad('engine', 24) + rpad('per iter', 11) +
              rpad('ns/instr', 10) + rpad('vs V8', 8) +
              rpad('fixed cost', 12) + rpad('R2', 8) +
              (haveAot ? rpad('AOT', 9) : ''));
  console.log('-'.repeat(haveAot ? 82 : 73));
  for (const r of rows) {
    if (r.failed) { console.log(pad(r.eng.name, 24) + '  FAILED: ' + r.failed); continue; }
    const nsPer = (r.slope * 1e6) / STEPS_PER_ITER;
    const baseNs = rows[0] && !rows[0].failed
      ? (rows[0].slope * 1e6) / STEPS_PER_ITER : nsPer;
    console.log(pad(r.eng.name, 24) +
                rpad(r.slope.toFixed(3) + ' ms', 11) +
                rpad(nsPer.toFixed(1), 10) +
                rpad((nsPer / baseNs).toFixed(2) + 'x', 8) +
                rpad(r.intercept.toFixed(2) + ' ms', 12) +
                rpad(r.r2.toFixed(4), 8) +
                (r.aot ? rpad((r.slope / r.aot.slope).toFixed(2) + 'x', 9) : ''));
  }
  console.log();
  console.log('  per iter     the fitted slope: cost of the work once running.');
  console.log('  ns/instr     per iter / ' + STEPS_PER_ITER + '. The cross-engine metric (0018).');
  console.log('  fixed cost   the fitted intercept: process start + compile + instantiate.');
  console.log('  R2           fit quality. Anything below ~0.99 is a slope through noise');
  console.log('               and the ns/instruction beside it should not be believed.');
  console.log();
  if (haveAot) {
    console.log('  AOT          speedup from compiling bytecode to wasm blocks (0013), same');
    console.log('               module family built with `--aot`. Gas is IDENTICAL between');
    console.log('               the two -- 6,648,019 instructions either way -- so this is');
    console.log('               the same work, not less of it.');
  }
  console.log('  Fixed cost is not comparable across the two groups: the JS engines are');
  console.log('  starting a JS runtime and reading a file from JS, wasmtime and wasm3 are');
  console.log('  invoked directly. The SLOPE is comparable, which is why ns/instruction');
  console.log('  is the metric and the intercept is context.');
}
