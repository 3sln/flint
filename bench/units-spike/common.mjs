// Paths and small helpers shared by the units spike
// (`DECISIONS.md#composing-runtime-units`). Inputs are the PRODUCTION units
// `bin/build-units` leaves in `units/`; everything written goes to
// `out/units-spike/`.
import { execFileSync } from 'child_process';
import { readFileSync } from 'fs';
import { fileURLToPath } from 'url';
export const REPO = fileURLToPath(new URL('../..', import.meta.url)).replace(/\/$/, '');
export const U = `${REPO}/units`;
export const OUT = `${REPO}/out/units-spike`;
export const HERE = `${REPO}/bench/units-spike`;
export const sysroot = () => execFileSync('sh', ['-c', `ls ${U}/.sysroot/*.rlib`]).toString().trim().split('\n');
export const ls = (glob) => execFileSync('sh', ['-c', `ls ${glob}`]).toString().trim().split('\n');
/// rust-lld from the PINNED nightly, the same one `bin/build-units` uses.
export function lld(args) {
  const tc = execFileSync('sh', ['-c', `dirname "$(dirname "$(rustup which --toolchain "$(cat ${REPO}/bin/nightly-toolchain)" rustc)")"`]).toString().trim();
  const bin = execFileSync('sh', ['-c', `ls ${tc}/lib/rustlib/*/bin/rust-lld | head -1`]).toString().trim();
  try {
    execFileSync(bin, ['-flavor', 'wasm', ...args], { stdio: ['ignore', 'pipe', 'pipe'],
      env: { ...process.env, DYLD_LIBRARY_PATH: `${tc}/lib`, LD_LIBRARY_PATH: `${tc}/lib` } });
  } catch (e) {
    throw new Error(String(e.stderr) .split('\n').filter((l) => !/neither Wasm/.test(l)).join('\n'));
  }
}
/// builtin name -> symbol, from a unit manifest.
export const provides = (edn) => Object.fromEntries([...readFileSync(edn, 'utf8').matchAll(/"([^"]+)" \{:symbol "([^"]+)"\}/g)].map((m) => [m[1], m[2]]));
export const unitExports = (edn) => { const m = readFileSync(edn, 'utf8').match(/:exports \[([^\]]*)\]/); return m ? [...m[1].matchAll(/"([^"]+)"/g)].map((x) => x[1]) : []; };
/// The runtime's ABI edge, as `flint.link/abi-exports` plus the two globals.
export const ABI = ['out_ptr', 'out_len', 'set_step_limit', 'stat_steps', 'set_memory_limit', 'flint_opaque_host_id', '__heap_base', 'FLINT_IMAGE_DESC'];
