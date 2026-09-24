// flint, as an npm package (`DECISIONS.md#npm-cli`).
//
// The compiler is `dist/flintc.wasm` and node hosts it. That half was never the
// problem -- `host/flint.mjs` has run flint wasm under node for a long time and
// `bin/conform-hosts` drives exactly that path. What was missing is everything
// AROUND it: the CLI is the compiler PLUS a server of namespaces the compiler's
// output calls back out to, plus the source reading and namespace resolution
// that turn a directory into the spec the compiler takes.
//
// `cli/src/main.rs` is the reference and this is a transliteration of it, so
// that the two front doors compile a project into the SAME BYTES. Where they
// differ, they differ on purpose and it is written down here.

import { writeFileSync, statSync } from 'node:fs';

/// Is this a file we can read? A bare word that names one is a SCRIPT; a bare
/// word that names nothing is a mistake, and saying so is `parse`'s job.
function isFile(p) {
  try { return p != null && statSync(p).isFile(); } catch { return false; }
}
import { instantiate } from '../dist/guest.js';
import {
  compilerWasm, runtimeWasm, runtimeAotWasm, slots, slotsAot, stdlib, stdlibDeps,
} from './artifacts.mjs';
import { buildSpec, testRoots, scriptSpec } from './spec.mjs';
import { Policy } from './policy.mjs';
import { Fs, Env, Slurp, Wasm, Ception } from './sys.mjs';
import { Npm, Mvn, Git } from './deps.mjs';
import { capabilitiesFor } from './serve.mjs';
import { VERSION } from './version.mjs';

const b64encode = (bytes) => Buffer.from(bytes).toString('base64');
const b64decode = (text) => new Uint8Array(Buffer.from(text, 'base64'));

/// What to optimise for. An ORDERED PREFERENCE, not a switch.
///
/// **Unrecognised tokens are ignored.** That is what makes the list safe to
/// write against a newer flint than the one reading it -- asking for something
/// this build has never heard of gets you its best effort, not a refusal.
/// Whether to compile the checks out.
///
/// `:checks` decides when it is given; otherwise a performance build drops them
/// and anything else keeps them. The same rule as the native CLI's
/// `strip_checks`, and it has to be the same or the two front ends emit
/// different modules from one input -- which they did.
function stripChecks(optimize, checks) {
  return checks === null || checks === undefined ? wantsAot(optimize) : !checks;
}

function wantsAot(optimize) {
  for (const t of optimize) {
    const s = String(t).replace(/^:/, '');
    if (s === 'perf') return true;
    if (s === 'size') return false;
  }
  return false;
}

/// Run the compiler over a spec and hand back what it said.
///
/// The runtime module goes as its own ARGUMENT, never inside the spec: it is
/// three-quarters of a megabyte of base64, and inside an EDN string it is
/// three-quarters of a megabyte for flint's reader to scan a character at a
/// time -- 198 seconds against 10.
// SYNCHRONOUS, and deliberately so. `new WebAssembly.Module` compiles without
// a promise and `inst.run` is a pump rather than a task, so nothing here needs
// to await -- which is what lets `flint.ception` serve the compiler over a port
// (`DECISIONS.md#flint-ception`): a served `invoke` has to answer in one call.
// Callers that still say `await compile(...)` are unaffected; awaiting a plain
// value is a no-op.
function runCompiler(args) {
  const module = new WebAssembly.Module(compilerWasm());
  const inst = instantiate(module);
  // Compiling a whole program can outgrow the 512 MB default, and past it an
  // allocation answers NIL, the NIL reaches the tree, and the failure surfaces
  // as `memory access out of bounds` with nothing pointing at the cap.
  if (inst.exports.set_memory_limit) inst.exports.set_memory_limit(3_000_000_000);
  const r = inst.run('flint.selfhost/main', args);
  if (r.code !== 0) throw new Error(r.out.trim());
  if (r.out.startsWith('!missing')) {
    throw new Error(
      `no source for${r.out.slice('!missing'.length).replace(/\n/g, ' ')}\n` +
      'every namespace a program requires has to be on the source path');
  }
  // A REFUSED require is not a missing one: the source is there and readable,
  // and the answer is that this workspace may not have it. The guest has
  // already formed the sentence -- it names both ends and the capability -- so
  // this passes it through rather than rewording it.
  if (r.out.startsWith('!refused')) throw new Error(r.out.slice('!refused'.length).trim());
  return r.out;
}

/// The module bytes, without writing them anywhere.
///
/// `flint.ception` needs exactly this: it hands back an artifact rather than
/// writing one, because a path in that request is filesystem reach the caller
/// did not grant (`DECISIONS.md#flint-ception`).
export function compileBytes(srcs, entry, optimize, to, meta,
                             { checks = null, exports = [], features = null } = {}) {
  const target = String(to ?? 'wasm').replace(/^:/, '');
  // BYTES BACK, so this one serves wasm and clr and refuses everything else.
  // `:to :llvm` is text and has no place in a function called `compileBytes`.
  if (target === 'clr') return compileClrBytes(srcs, entry, optimize, meta,
                                               { checks, exports, features });
  if (target !== 'wasm') {
    throw new Error(`no such target \`${target}\` (\`:to :wasm\`, \`:to :clr\`)`);
  }
  const aot = wantsAot(optimize);
  const spec = buildSpec({
    srcs, entry, slots: aot ? slotsAot() : slots(), aot, shake: true, meta, roots: null,
    stdlib: stdlib(), stdlibDeps: stdlibDeps(),
    stripChecks: stripChecks(optimize, checks), exports, features,
  });
  return b64decode(runCompiler(['wasm', spec, b64encode(aot ? runtimeAotWasm() : runtimeWasm())]).trim());
}

/// `:to :clr`: one .NET assembly, bytes out.
///
/// No `shake` and `SLOTS`, not `SLOTS_AOT`, matching the native CLI's
/// `compile_clr`: the assembly's natives resolve BY NAME against whatever table
/// the host carries, and there is no prebuilt module here to cut down.
function compileClrBytes(srcs, entry, optimize, meta,
                         { checks = null, exports = [], features = null } = {}) {
  const aot = wantsAot(optimize);
  const spec = buildSpec({
    srcs, entry, slots: slots(), aot, shake: false, meta, roots: null,
    stdlib: stdlib(), stdlibDeps: stdlibDeps(),
    stripChecks: stripChecks(optimize, checks), exports, features,
  });
  const asm = b64decode(runCompiler(['clr', spec]).trim());
  // A SNIFF TEST, for the reason the native CLI gives: the guest answers with a
  // string either way, so a diagnostic written into an artifact would be found
  // out by whoever loaded it with no idea which step lied. `MZ` starts every PE.
  if (asm.length < 2 || asm[0] !== 0x4d || asm[1] !== 0x5a) {
    throw new Error('the compiler did not answer with a PE assembly (no `MZ`)');
  }
  return asm;
}

export function compile(srcs, entry, outPath, optimize, to, meta,
                        { quiet = false, checks = null, features = null } = {}) {
  const target = String(to).replace(/^:/, '');
  // `:to :llvm` IS BUILT -- on the native CLI (`cli/src/main.rs`, and
  // `bin/check-llvm` gates it). This used to say it was not built anywhere,
  // which was drift: the refusal belongs to THIS PACKAGE lacking the emitter,
  // not to the target lacking an implementation.
  if (target === 'llvm' || target === 'native') {
    throw new Error(
      `\`:to :${target}\` is not available from this package. It emits wasm and clr;\n` +
      '`:to :llvm` is built in the native CLI (`flint compile ... :to :llvm`), and\n' +
      '`:to :native` is a LINK that neither carries.');
  }
  if (target === 'clr') {
    const asm = compileClrBytes(srcs, entry, optimize, meta, { checks, features });
    writeFileSync(outPath, asm);
    if (!quiet) process.stderr.write(`wrote ${outPath} (${asm.length} bytes)\n`);
    return;
  }
  if (target !== 'wasm') {
    throw new Error(`no such target \`${target}\` (\`:to :wasm\`, \`:to :clr\`)`);
  }
  const aot = wantsAot(optimize);
  const table = aot ? slotsAot() : slots();
  const base = aot ? runtimeAotWasm() : runtimeWasm();
  const spec = buildSpec({
    srcs, entry, slots: table, aot, shake: true, meta, roots: null,
    stdlib: stdlib(), stdlibDeps: stdlibDeps(),
    stripChecks: stripChecks(optimize, checks), features,
  });
  const out = runCompiler(['wasm', spec, b64encode(base)]);
  const module = b64decode(out.trim());
  writeFileSync(outPath, module);
  // `flint.ception` serves this to a PROGRAM, and a library call that prints to the
  // user's terminal is chatter the caller did not ask for -- `flint task` would
  // announce a temporary file on every run. The same split `runSource` makes.
  if (!quiet) process.stderr.write(
    `wrote ${outPath} (${module.length} bytes${aot ? ', compiled arities' : ''})\n`);
}

/// Compile and run, in one step.
///
/// **This differs from the native CLI in ONE way, and it is the only place the
/// two pipelines are not the same shape.** `target/release/flint` compiles to
/// flint's internal bytecode IMAGE and runs it on the runtime linked into the
/// binary. There is no such runtime here: node's is `flint-runtime.wasm`, and
/// an image's native references are names that only a natively-linked host can
/// resolve -- a wasm loader wants table slots. So `run` here compiles the same
/// program to the same wasm module `compile` produces and instantiates that.
///
/// The program is identical and the answer is identical; what differs is that
/// this pays module emission where the native CLI pays none. It is the honest
/// version of the difference rather than a second compilation path that might
/// disagree with `compile`.
export function runSource(srcs, entry, args, caps, roots, { quiet = false } = {}) {
  const spec = buildSpec({
    srcs, entry, slots: slots(), aot: false, shake: true, meta: [], roots,
    stdlib: stdlib(), stdlibDeps: stdlibDeps(),
  });
  const out = runCompiler(['wasm', spec, b64encode(runtimeWasm())]);
  const module = new WebAssembly.Module(b64decode(out.trim()));
  const inst = instantiate(module, {
    stepLimit: process.env.FLINT_STEP_LIMIT ? Number(process.env.FLINT_STEP_LIMIT) : 0,
  });

  // `:with` is BOTH things at once, exactly as it is on the native CLI: it
  // mints one opaque value per name and projects them in as the entry's second
  // argument, AND it carries the policy for the served namespace of that name.
  const policy = new Policy();
  for (const c of caps) policy.add(c);

  const services = [];
  if (caps.some((c) => c === 'fs' || c.startsWith('fs:'))) {
    services.push(new Fs(process.cwd(), caps.some((c) => c === 'fs:write')));
  }
  if (caps.some((c) => c === 'slurp' || c.startsWith('slurp:'))) services.push(new Slurp());
  if (caps.some((c) => c === 'deps' || c.startsWith('deps:'))) {
    services.push(new Npm(), new Mvn(), new Git());
  }
  if (caps.some((c) => c === 'env' || c.startsWith('env:'))) services.push(new Env(args));
  // Running a module is EXECUTING CODE, so it is a grant like any other
  // (`DECISIONS.md#wasm-engine`).
  if (caps.some((c) => c === 'wasm' || c.startsWith('wasm:'))) services.push(new Wasm());
  // The compiler, served to the program (`DECISIONS.md#flint-ception`). The
  // functions are handed in rather than imported, because `sys.mjs` importing
  // this file back would be a cycle.
  //
  // NOT A GRANT, and it used to be one: since `compile` takes source text and
  // hands back bytes, the SDK reaches nothing a program could not already
  // reach. It is off under a GAS LIMIT instead -- a limit is a promise about
  // the whole process, and a nested sandbox runs on its own budget.
  services.push(new Ception({
    compile, compileBytes, runSource, version: VERSION, caps,
    gas: process.env.FLINT_STEP_LIMIT ? Number(process.env.FLINT_STEP_LIMIT) : 0,
    // The same service table this CLI serves ITSELF, so a sandbox lent `[fs]`
    // gets the `fs` the parent would have got. Handed in rather than imported,
    // because `sys.mjs` importing this file back would be a cycle -- and built
    // on demand, since a sandbox lent nothing must be served nothing.
    lend: (lent) => {
      const pol = new Policy();
      for (const c of lent) pol.add(c);
      const svc = [];
      if (lent.some((c) => c === 'fs' || c.startsWith('fs:'))) {
        svc.push(new Fs(process.cwd(), lent.some((c) => c === 'fs:write')));
      }
      if (lent.some((c) => c === 'slurp' || c.startsWith('slurp:'))) svc.push(new Slurp());
      if (lent.some((c) => c === 'env' || c.startsWith('env:'))) svc.push(new Env([]));
      if (lent.some((c) => c === 'wasm' || c.startsWith('wasm:'))) svc.push(new Wasm());
      return svc.length ? capabilitiesFor(svc, pol) : null;
    },
  }));
  // Installed only when something is actually served. A program that was
  // granted nothing keeps the honest refusal instead of being handed a
  // transport that can reach nothing.
  if (services.length) inst.capabilities(capabilitiesFor(services, policy));

  const codec = inst.codec;
  // The ids the CLI issues for `:with` names. They start at 1 because 0 is what
  // `flint/opaque` gives guest-minted values, and a capability whose id was 0
  // would be indistinguishable from one the guest made up.
  const named = caps.map((n, i) => [n, i + 1]);
  // THE ENTRY SHIM takes one argument, `[args caps]`, and hands `args` and
  // `caps` to the entry -- `caps` only when the entry has a 2-arity
  // (`src/flint/compiler.cljc`, "the entry shim"). Calling the user's function
  // directly instead would silently drop `:with`, because the projection is the
  // shim's doing and not the runtime's.
  const arg = codec.vec([
    codec.vec(args.map((a) => codec.str(String(a)))),
    codec.map(named.map(([n, id]) => [codec.kw(n), codec.opaque(id, n)])),
  ]);
  try {
    const value = inst.call('flint.main/-main', [arg]);
    const text = value === null || value === undefined ? '' : String(value);
    if (!quiet) process.stdout.write(text);
    return { code: 0, out: text };
  } catch (err) {
    // NOT `${kind}: ${message}` unconditionally. The system-port path already
    // builds its message that way, so prefixing again printed
    // `ExceptionInfo: ExceptionInfo: rpc: ...` where the native CLI prints the
    // kind once. The synchronous path -- a module with no ports -- carries the
    // kind separately and does need it, which is why both cases are here.
    const msg = String(err?.message ?? err);
    const text = err?.kind && !msg.startsWith(`${err.kind}: `) ? `${err.kind}: ${msg}` : msg;
    if (!quiet) process.stdout.write(text);
    return { code: 1, out: text };
  }
}

// --- argument parsing -------------------------------------------------------
//
// `:path [a b] :fn ns/f :to :wasm :out o`, the same surface the native CLI has.
// A value may be a bracketed list or a repeated key; both mean the same thing.
// Brackets are written the way they are read aloud, and a shell splits them
// into separate words, so `[a` .. `b]` is gathered back up here.

function values(key, args, at) {
  const first = args[at + 1];
  if (first === undefined) throw new Error(`${key} needs a value`);
  if (!first.startsWith('[')) return [[first], at + 2];
  // A whole list in ONE argument, which is what a quoted `'[a b]'` is. It has
  // to work, because in zsh an unquoted `[a b]` is a GLOB: `:path [src]` fails
  // with "no matches found" before flint sees it at all. sh and bash pass it
  // through, so both spellings are real and both are handled here.
  if (first.endsWith(']')) {
    return [first.slice(1, -1).split(/\s+/).filter((s) => s !== ''), at + 2];
  }
  const out = [];
  let i = at + 1;
  let open = false;
  while (i < args.length) {
    let t = args[i];
    if (!open) { if (t.startsWith('[')) t = t.slice(1); open = true; }
    const last = t.endsWith(']');
    if (last) t = t.slice(0, -1);
    if (t !== '') out.push(t);
    i += 1;
    if (last) return [out, i];
  }
  throw new Error(`${key} opens a \`[\` that is never closed`);
}

export function parse(argv) {
  const a = {
    srcs: [], entry: null, out: null, to: null,
    grants: [], optimize: [], meta: [], args: [], rest: [], checks: null, features: null,
  };
  let i = 0;
  while (i < argv.length) {
    const t = argv[i];
    if (t === '--') { a.rest = argv.slice(i + 1); break; }
    else if (t === ':path' || t === ':src') { const [v, n] = values(':path', argv, i); a.srcs.push(...v); i = n; }
    else if (t === ':fn') { const [v, n] = values(':fn', argv, i); a.entry = v[0] ?? null; i = n; }
    else if (t === ':out' || t === ':o') { const [v, n] = values(':out', argv, i); a.out = v[0] ?? null; i = n; }
    else if (t === ':to') { const [v, n] = values(':to', argv, i); a.to = v[0] ?? null; i = n; }
    else if (t === ':with' || t === ':grant') { const [v, n] = values(':with', argv, i); a.grants.push(...v); i = n; }
    else if (t === ':optimize') { const [v, n] = values(':optimize', argv, i); a.optimize.push(...v); i = n; }
    // `:checks` is its own axis, NOT a corner of `:optimize`. Electing a
    // performance build is one way to drop checks; saying so directly is the
    // other, and a build that wants checks under `:optimize [perf]` had no way
    // to ask for them.
    else if (t === ':features') {
      const [v, n] = values(':features', argv, i);
      // Written `flint` or `:flint`; kept with the colon, which is how the spec
      // spells a keyword.
      a.features = v.map((f) => (f.startsWith(':') ? f : `:${f}`));
      i = n;
    }
    else if (t === ':checks') {
      const [v, n] = values(':checks', argv, i);
      a.checks = String(v[0]) === 'true' ? true : String(v[0]) === 'false' ? false : null;
      if (a.checks === null) throw new Error(':checks takes true or false');
      i = n;
    }
    else if (t === ':args') { const [v, n] = values(':args', argv, i); a.args.push(...v); i = n; }
    else if (t === ':meta') {
      const [v, n] = values(':meta', argv, i);
      for (const kv of v) {
        const eq = kv.indexOf('=');
        if (eq < 0) throw new Error(`\`:meta ${kv}\` is not a pair; write it as \`:meta key=value\``);
        a.meta.push([kv.slice(0, eq), kv.slice(eq + 1)]);
      }
      i = n;
    }
    // `--aot` was the old spelling of `:optimize [perf]`. It still means that:
    // a flag that grew into a list should not break the scripts that were
    // written before it grew.
    else if (t === '--aot') { a.optimize.push('perf'); i += 1; }
    else if (t.startsWith(':') || t.startsWith('-')) throw new Error(`no such option \`${t}\``);
    // A bare path is a source, so `flint run src :fn app/main` reads the way
    // anyone would write it.
    else { a.srcs.push(t); i += 1; }
  }
  return a;
}

export function usage() {
  process.stderr.write(`flint ${VERSION} -- the compiler, on node

  flint run :path <dir> :fn <ns/fn> [:with [cap...]] [:args [arg...]]
      Compile and run, here. Nothing is written.

  flint wasm [show | reset | use <path>]
      The engine that runs a compiled module. node carries one, so this
      reports node itself and there is nothing to pin. The native binary
      carries none and has to go looking, which is what the command is for.

  flint compile :path <dir> :fn <ns/fn> :to :wasm [:out <file>]
                [:with [cap...]] [:optimize [perf]] [:checks true|false] [:meta k=v]
      Compile to a standalone module, for any host with a wasm engine. Here
      \`:with\` DECLARES rather than grants: it is recorded in the artifact's
      metadata, because the arguments arrive later and what a program needs
      has to survive until then.

  flint compile :path <dir> :fn <ns/fn> :to :clr [:out <file.dll>]
                [:optimize [perf]] [:checks true|false] [:meta k=v]
      Compile to one .NET assembly, for any host with a CLR. The bytecode rides
      in \`.text\` as a static byte array and the assembly exposes
      \`boot\`/\`loop\`/\`link\`; its metadata is a \`CustomAttribute\`. It carries the
      PROGRAM and names flint's runtime as a reference, so \`Flint.dll\` goes
      beside it.

  flint test :path <dir>
      Run every var marked \`^:flint.check/test\` under \`:path\`, and report.
      The suite is what is on the path; nothing has to be registered.

  flint version

\`:with\` lends capabilities; a program granted none can reach nothing. \`:args\`
is what the entry function is called with. Both are conventions of THIS CLI --
the SDKs take a function name and an argument list and nothing more.

The namespaces \`:with\` serves are flint.sys.fs, flint.sys.env, flint.sys.slurp
and flint.deps.npm / .mvn / .git -- the same set the native CLI serves.

\`:optimize\` is an ordered preference: \`[perf]\` compiles every arity as well
(bigger, much faster on arithmetic), \`[size]\` interprets. Unrecognised tokens
are ignored, so a script written for a newer flint still runs here.

A value may be a bracketed list -- \`:path [src lib]\` -- or the key may simply
be repeated; the two mean the same thing. In zsh an unquoted bracket is a glob,
so quote it there: \`:path '[src lib]'\`.

NOT IMPLEMENTED HERE, and present on the native CLI: \`flint deps\`
(add/tree/why/pin/bump/agree) and pods. Both shell out to further machinery
that this package does not carry yet.
`);
  process.exit(2);
}

export async function main(argv) {
  if (argv.length === 0) usage();
  const cmd = argv[0];
  if (cmd === 'version' || cmd === '--version' || cmd === '-v') {
    process.stdout.write(`flint ${VERSION}\n`);
    return 0;
  }
  if (cmd === 'help' || cmd === '--help' || cmd === '-h') usage();
  // The same command the native CLI has, answering for this host. node IS the
  // engine, so there is nothing to find and nothing to remember -- but a
  // command that exists on one front end and not the other is exactly the
  // asymmetry `DECISIONS.md#aot-diverges-between-hosts` was about, and a script
  // written against `flint wasm` should not discover the host by crashing.
  if (cmd === 'wasm') {
    const sub = argv[1] ?? 'show';
    if (sub === 'show') process.stdout.write(`node  ${process.execPath}\n`);
    else if (sub === 'reset' || sub === 'use') {
      process.stdout.write('node carries its own wasm engine; there is nothing to pin\n');
    } else {
      process.stderr.write(`no such wasm command: ${sub}\nknown: show, reset, use <path>\n`);
      return 2;
    }
    return 0;
  }
  if (cmd === 'compile') {
    // A SCRIPT COMPILES HERE TOO, by naming the file. The native CLI and
    // `bin/flint` both take one; this door did not, and a capability two front
    // doors have and the third does not is exactly the drift
    // `DECISIONS.md#standalone-scripts` is about.
    //
    // THE FILE COMES OFF THE FRONT BEFORE THE OPTIONS ARE PARSED, because
    // `parse` reads a bare word as a source path -- leaving it in makes the
    // script look like a `:src` and the guard fires on the correct command.
    const script = isFile(argv[1]) ? argv[1] : null;
    const a = parse(argv.slice(script ? 2 : 1));
    let srcs = a.srcs, entry = a.entry;
    if (script) {
      if (a.entry || a.srcs.length) {
        throw new Error(`compile was given both the script ${script} and :fn/:src.\n` +
          'A script names its own entry and its own sources; pass one or the other.');
      }
      ({ srcs, entry } = scriptSpec(script));
    } else {
      if (!entry) throw new Error('compile needs :fn ns/fn, or the path of a script');
      if (srcs.length === 0) {
        throw new Error('compile needs at least one :src, or the path of a script');
      }
    }
    // `:with` on `compile` DECLARES rather than grants: the arguments arrive
    // later, so what a program needs has to survive until then, and metadata is
    // where it survives. flint does not read it -- this is the CLI writing down
    // its own convention where the next tool can find it.
    const meta = a.meta.slice();
    if (a.grants.length) meta.push(['capabilities', a.grants.join(' ')]);
    await compile(srcs, entry, a.out ?? 'out.wasm', a.optimize, a.to ?? 'wasm', meta,
                  { checks: a.checks, features: a.features });
    return 0;
  }
  // `test` is `run` with a generated entry: the compiler collects every var
  // marked `^:flint.check/test` into `flint.check.registry` and this calls its
  // `run`. The suite is therefore whatever is ON THE PATH, not whatever a list
  // somewhere remembered to name.
  if (cmd === 'test') {
    const a = parse(argv.slice(1));
    if (a.srcs.length === 0) throw new Error('test needs at least one :path');
    const roots = testRoots(a.srcs);
    const r = await runSource(a.srcs, 'flint.check.registry/run', [], a.grants, roots);
    // A failing check is a failing RUN. `run-tests` reports by returning text
    // -- it catches what a check throws, so the program itself succeeds -- and
    // a test command that exits 0 on a red suite is one CI cannot use.
    return (r.code !== 0 || r.out.includes('FAILED')) ? 1 : 0;
  }
  if (cmd === 'run') {
    const a = parse(argv.slice(1));
    if (!a.entry) throw new Error('run needs :fn ns/fn');
    if (a.srcs.length === 0) throw new Error('run needs at least one :src');
    // `:optimize` is a PREFERENCE, so asking `run` for one it cannot give is
    // not an error: compiled arities are a property of a module `run` does not
    // keep, so it interprets and says so once.
    if (wantsAot(a.optimize)) {
      process.stderr.write(
        'flint: `run` keeps no module, so there are no arities to compile; interpreting.\n');
    }
    const args = [...a.args, ...a.rest];
    return (await runSource(a.srcs, a.entry, args, a.grants, null)).code;
  }
  if (cmd === 'deps') {
    // NAMED rather than silently missing. `flint deps` on the native CLI runs
    // `flint.deps.resolve` as a program and writes `deps.edn`; the namespaces
    // it needs are served here, so this is packaging work rather than a
    // capability gap, and saying which is the difference between "not yet" and
    // "broken".
    throw new Error(
      '`flint deps` is not implemented in @3sln/flint-cli yet.\n' +
      'The namespaces it resolves through -- flint.deps.npm, .mvn and .git -- ARE served\n' +
      'here, so a program can call them; what is missing is the deps subcommand itself.\n' +
      'Use the native CLI for `flint deps add|tree|why|pin|bump|agree`.');
  }
  process.stderr.write(`flint: no such command \`${cmd}\`\n`);
  usage();
}
