// `flint.sys.*`: the system surface, served over ports
// (`DECISIONS.md#system-namespaces-and-deps`), from node.
//
// These namespaces are VIRTUAL. A program writes
//
//     (:require [flint.sys.fs :as fs])
//     (fs/list-dir "src")
//
// and the compiler emits a call over a port rather than a var reference. What
// is on the other end is this file, and `cli/src/sys.rs` on the native CLI.
//
// ## The split is by AUTHORITY
//
// `slurp` is bytes-at-a-name and `fs` is hierarchy, and they are separate
// because granting one should not grant the other: a program that reads one
// configuration file has no business enumerating a disk.

import {
  readFileSync, writeFileSync, readdirSync, statSync, mkdirSync, existsSync,
  rmSync, unlinkSync, mkdtempSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname, isAbsolute } from 'node:path';
import { spawnSync } from 'node:child_process';
import { varsOf } from './catalogue.mjs';
// The guest driver, shared with `host/flint.mjs` and with whatever engine the
// native CLI finds. One pump, three front ends.
//
// From `dist/`, NOT from `sdks/esm/`: the published package carries no
// `sdks/esm/` at all, so an import that reaches out of the package works in
// this checkout and is a missing module for everyone who installs it. `cli.mjs`
// imports it the same way, and `sdks/cli/build` puts the copy there.
import { instantiate } from '../dist/guest.js';

/// Every path under `:fs` resolves under the granted root, and an escape is
/// REFUSED rather than clamped.
///
/// Silently rewriting `../../etc/passwd` into something inside the root answers
/// a question nobody asked. This is the check `cli/src/sys.rs`'s `under()` is,
/// component for component, and it is the one part of the old `host/fs.mjs`
/// that was never in the wrong place.
///
/// The check is on the path AS WRITTEN and does not touch the filesystem, so it
/// is the same answer whether or not the file exists -- a probe that behaved
/// differently for a missing file would leak whether it was there. Nothing in
/// here calls `stat`, `realpath` or `existsSync`, and nothing may.
///
/// `..` is REFUSED rather than popped. Popping would make `a/../../x` depend on
/// how deep `a` was, which is exactly the arithmetic an attacker gets to do --
/// and the reason this rejects `a/../b` too, which normalises to somewhere
/// inside the root and is still refused.
///
/// Both separators, because node runs on Windows: `a\..\..\x` is a parent
/// traversal there and a filename containing backslashes here, and treating it
/// as a filename on the platform where it is a traversal is how this check
/// fails open (AGENTS.md §5).
export function under(root, p) {
  const s = String(p);
  // An empty path is the root itself -- `Path::new("")` has no components.
  if (s === '') return root;
  const parts = s.split(/[/\\]/);
  // A leading separator is `RootDir`, and `C:` is a `Prefix`. Both are
  // absolute, and an absolute path is not relative to the root.
  if (parts[0] === '' || /^[A-Za-z]:$/.test(parts[0])) {
    throw new Error(`${JSON.stringify(s)} is absolute; paths are relative to the root`);
  }
  const kept = [];
  for (const c of parts) {
    if (c === '' || c === '.') continue;
    if (c === '..') {
      throw new Error(`${JSON.stringify(s)} leaves the root: \`..\` is not allowed`);
    }
    kept.push(c);
  }
  const out = join(root, ...kept);
  // A belt-and-braces assertion rather than the check itself. The loop above IS
  // the check; this catches a `join` that normalised something unexpected on a
  // platform nobody tested, and it still touches no filesystem.
  if (isAbsolute(root) && !out.startsWith(root)) {
    throw new Error(`${JSON.stringify(s)} leaves the root`);
  }
  return out;
}

function strArg(args, i, what) {
  const v = args[i];
  if (typeof v !== 'string') throw new Error(`argument ${i} (${what}) has to be a string`);
  return v;
}

const utf8Strict = new TextDecoder('utf-8', { fatal: true });

// ------------------------------------------------------------------ flint.sys.fs

export class Fs {
  constructor(root, write) { this.root = root; this.write = write; }
  get name() { return 'flint.sys.fs'; }
  get vars() { return varsOf(this.name); }

  invoke(v, args, _policy, c) {
    switch (v) {
      case 'root':
        return c.str(this.root);
      case 'read-file': {
        const p = under(this.root, strArg(args, 0, 'path'));
        const b = readFileSync(p);
        // NOT lossy. A file that is not text comes back as BYTES rather than as
        // a string with replacement characters in it, because a caller can act
        // on bytes and cannot undo a lossy conversion.
        try { return c.str(utf8Strict.decode(b)); }
        catch { return c.bytes(new Uint8Array(b)); }
      }
      case 'write-file': {
        if (!this.write) throw new Error('this :fs grant is read-only');
        const p = under(this.root, strArg(args, 0, 'path'));
        const body = args[1];
        let bytes;
        if (typeof body === 'string') bytes = Buffer.from(body, 'utf8');
        else if (body instanceof Uint8Array) bytes = Buffer.from(body);
        else throw new Error('write-file wants a string or a byte string');
        mkdirSync(dirname(p), { recursive: true });
        writeFileSync(p, bytes);
        return c.nil();
      }
      case 'exists?':
        return c.bool(existsSync(under(this.root, strArg(args, 0, 'path'))));
      case 'dir?': {
        const p = under(this.root, strArg(args, 0, 'path'));
        try { return c.bool(statSync(p).isDirectory()); } catch { return c.bool(false); }
      }
      case 'list-dir': {
        const p = under(this.root, strArg(args, 0, 'path'));
        const es = readdirSync(p, { withFileTypes: true })
          .map((d) => [d.name, d.isDirectory()]);
        // SORTED. A directory listing in filesystem order makes a build that
        // reads one non-reproducible, and the cost is nothing.
        es.sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0));
        return c.vec(es.map(([name, dir]) => c.map([
          [c.kw('name'), c.str(name)],
          [c.kw('dir'), c.bool(dir)],
        ])));
      }
      case 'mkdir': {
        if (!this.write) throw new Error('this :fs grant is read-only');
        mkdirSync(under(this.root, strArg(args, 0, 'path')), { recursive: true });
        return c.nil();
      }
      case 'delete': {
        if (!this.write) throw new Error('this :fs grant is read-only');
        const p = under(this.root, strArg(args, 0, 'path'));
        let dir = false;
        try { dir = statSync(p).isDirectory(); } catch { /* let the remove report it */ }
        if (dir) rmSync(p, { recursive: true }); else unlinkSync(p);
        return c.nil();
      }
      default:
        throw new Error(`flint.sys.fs has no ${v}`);
    }
  }
}

// --------------------------------------------------------------- flint.sys.env

export class Env {
  constructor(args) { this.args = args; }
  get name() { return 'flint.sys.env'; }
  get vars() { return varsOf(this.name); }

  invoke(v, args, policy, c) {
    switch (v) {
      case 'get': {
        const k = strArg(args, 0, 'name');
        // The POLICY decides which variables exist, and a refused one reads as
        // ABSENT rather than as an error: a program asking for `HOME` and a
        // program probing for `AWS_SECRET_ACCESS_KEY` get the same nil, so the
        // allowlist does not leak its own contents.
        const val = policy.envAllows(k) ? process.env[k] : undefined;
        return val === undefined ? c.nil() : c.str(val);
      }
      case 'args':
        return c.vec(this.args.map((a) => c.str(a)));
      case 'cwd':
        return c.str(process.cwd());
      default:
        throw new Error(`flint.sys.env has no ${v}`);
    }
  }
}

// -------------------------------------------------------------- flint.sys.wasm

/// Running a compiled module.
///
/// THE ONE PLACE THE FRONT ENDS DIFFER IN KIND, and the one where node has the
/// easy job: it already has a wasm engine, so `engine` reports itself and
/// there is nothing to find, pin or reset. The native CLI carries no engine
/// and has to go looking (`DECISIONS.md#wasm-engine`); `use` and `reset` exist
/// here so that a program written against this namespace runs on both without
/// asking which host it got.
export class Wasm {
  get name() { return 'flint.sys.wasm'; }
  get vars() { return varsOf(this.name); }

  invoke(v, args, policy, c) {
    switch (v) {
      case 'run': {
        const path = strArg(args, 0, 'module');
        const fn = strArg(args, 1, 'fn');
        const argv = args[2] === undefined || args[2] === null ? [] : args[2];
        if (!Array.isArray(argv)) throw new Error('run: args must be a vector');
        // Synchronous throughout: `new WebAssembly.Module` compiles without a
        // promise, and the guest driver's `run` is a pump, not a task. An
        // `invoke` that returned a promise would have to be awaited by every
        // caller of every service.
        const mod = new WebAssembly.Module(readFileSync(path));
        const inst = instantiate(mod, {
          stepLimit: process.env.FLINT_STEP_LIMIT ? Number(process.env.FLINT_STEP_LIMIT) : 0,
        });
        const r = inst.run(fn, argv.map((a) => String(a)));
        return c.map([[c.kw('code'), c.int(r.code)], [c.kw('out'), c.str(r.out)]]);
      }
      // node IS the engine. Reporting the interpreter that is already running
      // is the honest answer, and it keeps `(engine)` non-nil on every host
      // that can actually run something.
      case 'engine':
        return c.map([[c.kw('kind'), c.str('node')], [c.kw('path'), c.str(process.execPath)]]);
      case 'use':
        return c.str(strArg(args, 0, 'path'));
      case 'reset':
        return c.nil();
      default:
        throw new Error(`flint.sys.wasm has no ${v}`);
    }
  }
}

// ------------------------------------------------------------------ flint.ception

/// flint's own SDK, served to flint (`DECISIONS.md#flint-ception`).
///
/// `sdks/c`, `sdks/rust` and `sdks/esm` let C, Rust and JavaScript embed the
/// compiler; this is the same offer made to the language itself, and
/// self-hosting is what makes it possible -- the compiler is already here.
///
/// The two functions are INJECTED rather than imported. `cli.mjs` imports this
/// file, so importing it back would be a cycle; handing them in at construction
/// says the same thing without one.
export class Ception {
  /// `ops.caps` is what the CALLER was granted. A program may not confer what
  /// it does not hold: without that test, `sdk` was the only capability anyone
  /// needed, because `(sdk/run {... :with ["fs"]})` minted the rest onto a
  /// child it wrote.
  constructor(ops) { this.ops = ops; this.boxes = []; this.callers = []; }

  /// Caller-supplied sources, in a private temporary directory.
  ///
  /// The host touching its own disk, not the guest reaching anything: no path
  /// here is caller-controlled, and it is removed on the way out. Reusing the
  /// ordinary spec builder keeps one of them rather than a second that agrees
  /// with it until it does not.
  spill(sources) {
    const dir = mkdtempSync(join(tmpdir(), 'flint-ception-'));
    for (const [ns, body] of sources) {
      const at = join(dir, `${ns.replace(/-/g, '_').replace(/\./g, '/')}.cljc`);
      mkdirSync(dirname(at), { recursive: true });
      writeFileSync(at, body);
    }
    return dir;
  }

  /// Whether the caller holds `want`, by the same spelling `:with` uses. A bare
  /// `fs` covers `fs:write`; holding `fs:write` does NOT confer a bare `fs`,
  /// which would be a widening.
  holds(want) {
    const base = String(want).split(':')[0];
    return (this.ops.caps || []).some((c) => c === want || c === base);
  }
  get name() { return 'flint.ception'; }
  get vars() { return varsOf(this.name); }

  invoke(v, args, policy, c) {
    // A GAS LIMIT IS A PROMISE ABOUT THE WHOLE PROCESS. A nested sandbox runs
    // on its own budget, so a program that could build one would step outside
    // the promise by construction, however small its own allowance.
    //
    // Served and refusing rather than absent, so the reason is said.
    if (this.ops.gas > 0) {
      throw new Error(`flint.ception is off under a gas limit.\n`
        + `this program is limited to ${this.ops.gas} instructions, and a sandbox it built `
        + `would run on its own budget -- so the limit would stop meaning what it says.\n`
        + 'run without FLINT_STEP_LIMIT to use it.');
    }
    switch (v) {
      // `(compile {:sources {"my.ns" "(ns my.ns) .."} :fn "my.ns/main"})`
      //
      // NO `:paths` AND NO `:out`. The caller hands over source text and gets
      // the artifact bytes back, so `sdk` confers no filesystem reach at all.
      case 'compile': {
        const o = args[0] || {};
        const dir = this.spill(sourcesOf(o));
        try {
          const bytes = this.ops.compileBytes([dir], str(o, 'fn', 'compile needs :fn "ns/fn"'),
                                              strings(o, 'optimize'), pick(o, 'to') ?? 'wasm',
                                              metaOf(o),
                                              { checks: typeof pick(o, 'checks') === 'boolean' ? pick(o, 'checks') : null,
                                                exports: strings(o, 'exports') });
          return c.bytes(bytes);
        } finally { rmSync(dir, { recursive: true, force: true }); }
      }
      // `(sandbox image)` -- a loaded, callable program that holds NOTHING.
      // No ports, no capabilities, no IO: it reaches the world only through
      // what it is later handed.
      case 'sandbox': {
        const image = args[0];
        if (!(image instanceof Uint8Array)) {
          throw new Error('sandbox needs the image bytes `compile` returned');
        }
        // `:with` LENDS, and only what the caller holds. A sandbox given
        // nothing reaches nothing, which is the default and the point.
        const lent = args[1] === undefined || args[1] === null ? [] : strings(args[1], 'with');
        const extra = lent.find((x) => !this.holds(x));
        if (extra !== undefined) {
          const held = (this.ops.caps || []).join(' ') || 'nothing';
          throw new Error(`sandbox: this program was not granted \`${extra}\`, so it cannot lend it.\n`
            + `it holds: ${held}\n`
            + 'a program may pass on what it has, not mint what it has not.');
        }
        const inst = instantiate(new WebAssembly.Module(image), { stepLimit: 0 });
        const caps = lent.length && this.ops.lend ? this.ops.lend(lent) : null;
        if (caps) inst.capabilities(caps);
        this.boxes.push(inst);
        return c.int(this.boxes.length - 1);
      }
      // `(call sandbox "ns/f" [args])`
      // `(caller sandbox)` -- bind a port and hand back what calls go on.
      //
      // A CALLER IS THE THING YOU CALL ON, not the sandbox
      // (`DECISIONS.md#bridges-are-the-only-door`). `:bind` gives the control
      // plane a port and it spawns ONE thread serving calls on it, so a caller
      // is that thread's queue -- serial, in arrival order -- and concurrency
      // is had by taking a second one. Naming it makes that cost visible
      // instead of hiding a thread per call behind `(call sandbox ..)`.
      case 'caller': {
        const h = args[0];
        if (this.boxes[h] === undefined) throw new Error(`no such sandbox: ${h}`);
        if (this.boxes[h] === null) throw new Error(`sandbox ${h} is closed`);
        this.callers.push({ box: h, caller: this.boxes[h].caller() });
        return c.int(this.callers.length - 1);
      }
      // `(close-caller caller)` -- the SANDBOX is untouched: other callers on
      // it go on working, which is the whole reason they are separate things.
      case 'close-caller': {
        const ch = args[0];
        if (this.callers[ch] === undefined) throw new Error(`no such caller: ${ch}`);
        if (this.callers[ch] !== null) this.callers[ch].caller.close();
        this.callers[ch] = null;
        return c.nil();
      }
      case 'call': {
        const ch = args[0];
        if (this.callers[ch] === undefined) throw new Error(`no such caller: ${ch}`);
        if (this.callers[ch] === null) throw new Error(`caller ${ch} is closed`);
        const h = this.callers[ch].box;
        if (this.boxes[h] === undefined) throw new Error(`no such sandbox: ${h}`);
        if (this.boxes[h] === null) throw new Error(`sandbox ${h} is closed`);
        const argv = args[2] === undefined || args[2] === null ? [] : args[2];
        if (!Array.isArray(argv)) throw new Error('call: args must be a vector');
        // `call`, NOT `run`. `run` wraps its arguments into ONE vector -- the
        // `main [args]` convention -- while `call` passes them individually,
        // which is the `flint_call` ABI the native side uses. Using `run` here
        // made `(call box "greet" ["ada" "alan"])` an arity error on node and
        // not on native: one surface, two meanings.
        try {
          // THE VALUES AS THEY ARRIVED, not stringified. `argv.map(String)`
          // turned 42 into "42" and a map into "[object Object]", and it was
          // also what stopped a PORT being passed inward -- the one argument
          // worth passing, since a port is how a sandbox reaches anything
          // (`DECISIONS.md#ports-are-the-hosts`). The driver's `call` encodes a
          // plain JS value itself.
          // ON THE CALLER, not the sandbox. `sandbox.call` is sugar over a
          // default caller; here the caller is the thing the guest named, so
          // its own bound port is what this goes on.
          return c.from(this.callers[ch].caller.call(strArg(args, 1, 'fn'), ...argv));
        } catch (e) {
          // A THROW INSIDE THE SANDBOX RAISES HERE, so the caller can catch it.
          //
          // This used to answer the error as DATA, to match what `flint_call`
          // does. The native side no longer uses `flint_call`: it calls over
          // the system port, where the protocol distinguishes `{:op :return}`
          // from `{:op :throw}` and the throw becomes an error. So returning
          // data here meant a `(try ... (catch ...))` around a nested call
          // fired on one front end and not the other -- the same shape as the
          // `:checks` divergence, found by running both.
          const kind = e.flint?.[':error'] ?? e.kind ?? 'Error';
          const msg = e.flint?.[':message'] ?? e.message ?? '';
          throw new Error(`${kind}: ${msg}`);
        }
      }
      case 'close': {
        const h = args[0];
        if (this.boxes[h] === undefined) throw new Error(`no such sandbox: ${h}`);
        // The SLOT IS KEPT, so a stale handle reads as closed rather than as
        // some later sandbox that reused the number.
        this.boxes[h] = null;
        return c.nil();
      }
      case 'run': {
        const o = args[0] || {};
        const fn = str(o, 'fn', 'run needs :fn "ns/fn"');
        const caps = strings(o, 'with');
        // AUTHORITY IS NOT CREATED HERE. Refused rather than quietly narrowed:
        // a child that silently loses a capability fails somewhere else, for a
        // reason that does not name this.
        const extra = caps.find((c) => !this.holds(c));
        if (extra !== undefined) {
          const held = (this.ops.caps || []).join(' ') || 'nothing';
          throw new Error(`run: this program was not granted \`${extra}\`, so it cannot lend it.\n`
            + `it holds: ${held}\n`
            + 'a program may pass on what it has, not mint what it has not.');
        }
        const dir = this.spill(sourcesOf(o));
        try {
          const r = this.ops.runSource([dir], fn, strings(o, 'args'), caps, undefined,
                                       { quiet: true });
          return c.map([[c.kw('code'), c.int(r.code)], [c.kw('out'), c.str(r.out)]]);
        } finally { rmSync(dir, { recursive: true, force: true }); }
      }
      case 'version':
        return c.str(this.ops.version);
      default:
        throw new Error(`flint.ception has no ${v}`);
    }
  }
}

/// `:sources {"my.ns" "(ns my.ns) .."}` from a request, as [ns, body] pairs.
///
/// A MAP OF SOURCE TEXT, not a list of directories: there is no path in this
/// request for a caller to point anywhere.
function sourcesOf(o) {
  const v = pick(o, 'sources');
  const pairs = v instanceof Map ? [...v.entries()]
    : (v && typeof v === 'object') ? Object.entries(v) : null;
  if (!pairs) throw new Error('compile/run needs :sources {"my.ns" "(ns my.ns) ..."}');
  const out = pairs.map(([k, body]) => {
    if (typeof body !== 'string') throw new Error(`sources: ${k} must map to source text`);
    return [String(k).replace(/^:/, ''), body];
  });
  if (!out.length) throw new Error('sources: at least one namespace');
  return out;
}

/// `:meta {k v}` as the [k, v] pairs the compiler takes.
function metaOf(o) {
  const v = pick(o, 'meta');
  const pairs = v instanceof Map ? [...v.entries()]
    : (v && typeof v === 'object') ? Object.entries(v) : [];
  return pairs.map(([k, x]) => [String(k).replace(/^:/, ''), String(x)]);
}

/// `:key` out of a decoded options map, whatever the decoder made of it.
function pick(o, k) {
  if (o instanceof Map) return o.get(k) ?? o.get(`:${k}`);
  return o?.[k] ?? o?.[`:${k}`];
}

function str(o, k, why) {
  const v = pick(o, k);
  if (typeof v !== 'string') throw new Error(why);
  return v;
}

/// A vector of strings at `k`, or empty. Absent and empty mean the same.
function strings(o, k, why) {
  const v = pick(o, k);
  if (v === undefined || v === null) {
    if (why) throw new Error(why);
    return [];
  }
  if (!Array.isArray(v)) throw new Error(`${k} must be a vector`);
  const out = v.map((x) => String(x));
  if (why && !out.length) throw new Error(why);
  return out;
}

// ------------------------------------------------------------- flint.sys.slurp

/// The largest thing `slurp` will pull into memory.
///
/// A cap rather than a stream, because `slurp` answers with the WHOLE thing by
/// definition -- a namespace that sometimes returns bytes and sometimes returns
/// a handle is two namespaces wearing one name.
const SLURP_LIMIT = 64 * 1024 * 1024;

/// Bytes at a NAME. `file://`, `http://`, `https://`.
///
/// One capability for all of them. `(slurp "file:///etc/x")` and
/// `(slurp "https://example.com/x")` are the same question -- give me the bytes
/// at this name -- and a program reading one configuration file should not be
/// holding the thing that can enumerate a disk, which is what `:fs` is. That a
/// URL fetch also SENDS the URL to somebody is the half that does not fit, and
/// it is answered by the POLICY rather than by splitting the capability.
export class Slurp {
  get name() { return 'flint.sys.slurp'; }
  get vars() { return varsOf(this.name); }

  invoke(v, args, policy, c) {
    const url = strArg(args, 0, 'url');
    // THE POLICY, before anything is opened. A refusal names the URL and says
    // what would change it, because "refused" alone sends a reader to the
    // wrong file.
    if (!policy.slurpAllows(url)) {
      throw new Error(
        `${url} is not in this program's :slurp allowlist -- ` +
        `grant it with :with [slurp:${url}] or a prefix ending in **`);
    }
    const bytes = fetchSync(url, SLURP_LIMIT);
    if (v === 'slurp') {
      try { return c.str(utf8Strict.decode(bytes)); }
      catch { throw new Error(`${url} is not utf-8; use slurp-bytes`); }
    }
    if (v === 'slurp-bytes') return c.bytes(bytes);
    throw new Error(`flint.sys.slurp has no ${v}`);
  }
}

/// Bytes at a URL, synchronously.
///
/// SYNCHRONOUS because everything on this side of the port is: a virtual call
/// parks one green thread and the host answers it before `flint_resume`, so an
/// `await` here would resume the guest before the answer existed. node has no
/// synchronous `fetch`, so this shells out to `curl` -- present everywhere node
/// is, and the same deliberate trade `cli/src/deps.rs` makes for `git`.
export function fetchSync(url, limit) {
  if (url.startsWith('file://')) {
    const p = url.slice('file://'.length);
    const st = statSync(p);
    if (st.size > limit) throw new Error(`${url}: ${st.size} bytes is over the slurp limit`);
    return new Uint8Array(readFileSync(p));
  }
  if (url.startsWith('http://') || url.startsWith('https://')) {
    const r = spawnSync('curl', [
      '-sS', '--fail-with-body', '--location',
      // One byte of slack, so "exactly at the limit" and "over it" are
      // distinguishable, and so a server that keeps sending cannot make this
      // allocate without bound.
      '--max-filesize', String(limit + 1),
      '--write-out', '%{http_code}', '--output', '-', url,
    ], { maxBuffer: limit + 4096, encoding: null });
    if (r.error) throw new Error(`${url}: ${r.error.message}`);
    const out = r.stdout ?? Buffer.alloc(0);
    // `--write-out` appends the status to the body, so the last three bytes are
    // the code and the rest is the answer. Checked HERE rather than trusted:
    // curl exits 0 for a 404 without `--fail`, and a 404 page silently becoming
    // the file's contents is the failure this is guarding.
    const code = out.subarray(out.length - 3).toString('latin1');
    const body = out.subarray(0, Math.max(0, out.length - 3));
    if (r.status !== 0) {
      const why = (r.stderr ?? Buffer.alloc(0)).toString('utf8').trim();
      throw new Error(`${url}: ${why || `curl exited ${r.status}`}`);
    }
    if (!/^2\d\d$/.test(code)) throw new Error(`${url}: HTTP ${code}`);
    if (body.length > limit) throw new Error(`${url}: over the slurp limit of ${limit} bytes`);
    return new Uint8Array(body);
  }
  // REFUSED BY NAME. A scheme nobody implemented must not fall through to
  // "treat it as a path", which is how `https:/typo` becomes a local file read.
  throw new Error(`${url}: no such scheme -- slurp reads file://, http:// and https://`);
}
