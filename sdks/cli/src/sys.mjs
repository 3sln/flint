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
  rmSync, unlinkSync,
} from 'node:fs';
import { join, dirname, isAbsolute } from 'node:path';
import { spawnSync } from 'node:child_process';
import { varsOf } from './catalogue.mjs';

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
