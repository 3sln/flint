// `flint.deps.*`: dependency resolution, served over ports
// (`DECISIONS.md#system-namespaces-and-deps`), from node.
//
// Virtual namespaces like `flint.sys.*`, served by the same pump. What makes
// them a separate file is the rule that governs all of them, which
// `cli/src/deps.rs` states and this file obeys:
//
// > **The host matches, flint decides.**
//
// Version arithmetic ends up in two languages -- semver here, resolving at
// FETCH time, and `flint.deps` in `.cljc`, comparing at PLAN time where the
// graph lives. Two implementations of "which version wins" is exactly the shape
// that goes wrong, so there is only one: **`resolve` returns EVERY matching
// version and never picks.** The plan is authoritative.
//
// One capability, `:deps`, for npm, Maven and git together. A build that may
// fetch from one may fetch from the others: they are one authority -- reach out
// and bring code in -- and splitting them would be three grants always given
// together.

import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { mkdirSync, writeFileSync, existsSync } from 'node:fs';
import { gunzipSync, inflateRawSync } from 'node:zlib';
import { join, dirname } from 'node:path';
import { varsOf } from './catalogue.mjs';
import { under, fetchSync } from './sys.mjs';

/// 512 MB: a jar or a tarball, not a stream. Bigger than `slurp`'s limit
/// because an artifact legitimately is bigger than a config file, and still
/// bounded because a registry is not trusted to be sane.
const FETCH_LIMIT = 512 * 1024 * 1024;

function strArg(args, i, what) {
  const v = args[i];
  if (typeof v !== 'string') throw new Error(`argument ${i} (${what}) has to be a string`);
  return v;
}

/// Fetch a URL, subject to the `:deps` policy.
function get(url, policy) {
  if (!policy.depsAllows(url)) {
    throw new Error(`${url} is not in this program's :deps allowlist`);
  }
  return fetchSync(url, FETCH_LIMIT);
}

function sha256Hex(b) {
  return createHash('sha256').update(Buffer.from(b)).digest('hex');
}

/// Where a fetched dependency lands. Under the project, so a checkout is
/// self-contained and a stale one is deletable by hand.
function cacheDir(kind, name, version) {
  // The NAME is sanitised, not trusted: `@scope/pkg` and `org.clojure/x` both
  // contain separators, and a registry that answered `../..` would otherwise
  // choose where this writes.
  const safe = (s) => s.replace(/[^A-Za-z0-9\-_.]/g, '_');
  return join('.flint', 'deps', kind, safe(name), safe(version));
}

// --- semver, the part that is needed and no more ---------------------------
//
// `^`, `~`, `>=`, `<`, `=`, `*` and a comma-separated conjunction, which is
// what a `deps.edn` range is in practice. Written out rather than depended on
// because this package must install with NO dependencies: a CLI whose install
// pulls a tree is one more thing between a user and a working compiler.
//
// A version this cannot parse is SKIPPED rather than fatal, exactly as the Rust
// does: one malformed entry in a package's history must not make the package
// unresolvable.

const SEMVER = /^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$/;

export function parseVersion(s) {
  const m = SEMVER.exec(String(s).trim());
  if (!m) return null;
  return { major: +m[1], minor: +m[2], patch: +m[3], pre: m[4] ?? '' };
}

export function compareVersions(a, b) {
  if (a.major !== b.major) return a.major - b.major;
  if (a.minor !== b.minor) return a.minor - b.minor;
  if (a.patch !== b.patch) return a.patch - b.patch;
  // A prerelease sorts BEFORE the release it precedes, which is the one rule
  // here that is not obvious and the one a naive string compare gets backwards.
  if (a.pre === b.pre) return 0;
  if (a.pre === '') return 1;
  if (b.pre === '') return -1;
  const xs = a.pre.split('.');
  const ys = b.pre.split('.');
  for (let i = 0; i < Math.max(xs.length, ys.length); i++) {
    const x = xs[i]; const y = ys[i];
    if (x === undefined) return -1;
    if (y === undefined) return 1;
    const nx = /^\d+$/.test(x); const ny = /^\d+$/.test(y);
    if (nx && ny) { if (+x !== +y) return +x - +y; }
    else if (nx !== ny) return nx ? -1 : 1;
    else if (x !== y) return x < y ? -1 : 1;
  }
  return 0;
}

export function versionString(v) {
  return `${v.major}.${v.minor}.${v.patch}${v.pre ? `-${v.pre}` : ''}`;
}

/// One comparator, as a predicate. Throws on syntax it does not know rather
/// than matching everything: a range nobody can read must not silently resolve
/// to "any version at all".
function comparator(token) {
  const t = token.trim();
  if (t === '' || t === '*') return () => true;
  const m = /^(\^|~|>=|<=|>|<|=)?\s*v?(\d+)(?:\.(\d+|\*|x))?(?:\.(\d+|\*|x))?(?:-([0-9A-Za-z.-]+))?/
    .exec(t);
  if (!m) throw new Error(`${JSON.stringify(token)} is not a semver range`);
  const op = m[1] ?? '=';
  const major = +m[2];
  const wild = (x) => x === undefined || x === '*' || x === 'x';
  const minor = wild(m[3]) ? null : +m[3];
  const patch = wild(m[4]) ? null : +m[4];
  const base = { major, minor: minor ?? 0, patch: patch ?? 0, pre: m[5] ?? '' };
  const lt = (v, b) => compareVersions(v, b) < 0;
  const ge = (v, b) => compareVersions(v, b) >= 0;
  switch (op) {
    case '^': {
      // `^0.2.3` is `>=0.2.3 <0.3.0`: below 1.0.0 the minor is the breaking
      // digit, and treating it as compatible is how a caret range silently
      // accepts a rewrite.
      const upper = major > 0 ? { major: major + 1, minor: 0, patch: 0, pre: '' }
        : minor !== null && minor > 0 ? { major, minor: minor + 1, patch: 0, pre: '' }
          : { major, minor: minor ?? 0, patch: (patch ?? 0) + 1, pre: '' };
      return (v) => ge(v, base) && lt(v, upper);
    }
    case '~': {
      const upper = minor === null
        ? { major: major + 1, minor: 0, patch: 0, pre: '' }
        : { major, minor: minor + 1, patch: 0, pre: '' };
      return (v) => ge(v, base) && lt(v, upper);
    }
    case '>=': return (v) => ge(v, base);
    case '>': return (v) => compareVersions(v, base) > 0;
    case '<=': return (v) => compareVersions(v, base) <= 0;
    case '<': return (v) => lt(v, base);
    default: {
      if (minor === null) return (v) => v.major === major;
      if (patch === null) return (v) => v.major === major && v.minor === minor;
      return (v) => compareVersions(v, base) === 0;
    }
  }
}

/// A range: comparators separated by spaces or commas, all of which must hold.
export function matches(range, v) {
  const parts = String(range).split(/\s*,\s*|\s+/).filter((s) => s !== '');
  if (parts.length === 0) return true;
  return parts.map(comparator).every((f) => f(v));
}

// ------------------------------------------------------------- flint.deps.npm

export class Npm {
  constructor(registry = 'https://registry.npmjs.org') { this.registry = registry; }
  get name() { return 'flint.deps.npm'; }
  get vars() { return varsOf(this.name); }

  packument(name, policy) {
    const url = `${this.registry}/${name}`;
    const b = get(url, policy);
    try { return JSON.parse(Buffer.from(b).toString('utf8')); }
    catch (e) { throw new Error(`${url}: not JSON: ${e.message}`); }
  }

  invoke(v, args, policy, c) {
    switch (v) {
      case 'versions': {
        const doc = this.packument(strArg(args, 0, 'package'), policy);
        const vs = Object.keys(doc.versions ?? {}).sort();
        return c.vec(vs.map((s) => c.str(s)));
      }
      // EVERY match, newest last, and the caller picks. This is the whole of
      // "the host matches, flint decides": a `resolve` that returned one
      // version would be a second version-conflict policy living in another
      // language from the first.
      case 'resolve': {
        const name = strArg(args, 0, 'package');
        const range = strArg(args, 1, 'range');
        const doc = this.packument(name, policy);
        const hits = [];
        for (const [ver, meta] of Object.entries(doc.versions ?? {})) {
          const parsed = parseVersion(ver);
          if (!parsed || !matches(range, parsed)) continue;
          const dist = meta.dist ?? {};
          hits.push([parsed, ver, dist.integrity ?? dist.shasum ?? '']);
        }
        hits.sort((a, b) => compareVersions(a[0], b[0]));
        return c.vec(hits.map(([, ver, integrity]) => c.map([
          [c.kw('version'), c.str(ver)],
          [c.kw('integrity'), c.str(integrity)],
        ])));
      }
      case 'manifest': {
        const name = strArg(args, 0, 'package');
        const version = strArg(args, 1, 'version');
        const m = (this.packument(name, policy).versions ?? {})[version];
        if (m == null) throw new Error(`${name} has no version ${version}`);
        const deps = m.dependencies ?? {};
        return c.map([
          [c.kw('deps'), c.map(Object.keys(deps).sort()
            .map((k) => [c.str(k), c.str(String(deps[k] ?? ''))]))],
          [c.kw('tarball'), c.str(m.dist?.tarball ?? '')],
          [c.kw('integrity'), c.str(m.dist?.integrity ?? '')],
        ]);
      }
      case 'fetch': {
        const name = strArg(args, 0, 'package');
        const version = strArg(args, 1, 'version');
        const dir = cacheDir('npm', name, version);
        // ALREADY THERE is not an error and not a re-fetch. The stamp is
        // written last, so a fetch that died halfway is not mistaken for one
        // that finished.
        const stamp = join(dir, '.flint-fetched');
        if (existsSync(stamp)) return c.str(dir);
        const m = (this.packument(name, policy).versions ?? {})[version];
        const url = m?.dist?.tarball;
        if (!url) throw new Error(`${name}@${version} has no tarball`);
        const body = get(url, policy);
        const digest = sha256Hex(body);
        mkdirSync(dir, { recursive: true });
        unpackTgz(body, dir);
        writeFileSync(stamp, `${url}\nsha256:${digest}\n`);
        // An npm tarball unpacks into `package/`, which is the source root -- a
        // namespace path is relative to that and not to the directory holding
        // it.
        return c.str(join(dir, 'package'));
      }
      default:
        throw new Error(`flint.deps.npm has no ${v}`);
    }
  }
}

/// Unpack a gzipped tar into `dir`, refusing any entry that would escape it.
///
/// The refusal is the reason this is written out rather than handed to `tar`
/// wholesale: a tar entry names its own path, and an archive is exactly the
/// place an attacker puts `../../.ssh/authorized_keys`. `under` is the same
/// check `flint.sys.fs` uses, so there is one containment rule in this package
/// and not two.
export function unpackTgz(body, dir) {
  const tar = Buffer.from(gunzipSync(Buffer.from(body)));
  let at = 0;
  while (at + 512 <= tar.length) {
    const header = tar.subarray(at, at + 512);
    // Two zero blocks end the archive; one is enough to stop on.
    if (header.every((b) => b === 0)) break;
    const field = (off, len) => header.subarray(off, off + len).toString('latin1').replace(/\0.*$/, '').trim();
    let name = field(0, 100);
    const size = parseInt(field(124, 12) || '0', 8) || 0;
    const type = header[156] === 0 ? '0' : String.fromCharCode(header[156]);
    const prefix = field(345, 155);
    if (prefix) name = `${prefix}/${name}`;
    at += 512;
    const data = tar.subarray(at, at + size);
    at += Math.ceil(size / 512) * 512;
    // A PAX or GNU long-name record is metadata, not a file; skipping it means
    // the entry after it keeps its (truncated) name rather than being written
    // somewhere nobody meant.
    if (type === 'x' || type === 'g' || type === 'L' || type === 'K') continue;
    // A LINK IS REFUSED, not followed. A symlink entry whose target is outside
    // the directory turns every later write through it into an escape, and the
    // containment check on the entry's own name would never see it.
    if (type === '1' || type === '2') {
      throw new Error(`tar entry ${JSON.stringify(name)}: links are not unpacked`);
    }
    if (name === '' || type === '5' || name.endsWith('/')) {
      if (name !== '') mkdirSync(under(dir, name.replace(/\/$/, '')), { recursive: true });
      continue;
    }
    if (type !== '0') continue;
    const out = under(dir, name);
    mkdirSync(dirname(out), { recursive: true });
    writeFileSync(out, data);
  }
}

// ------------------------------------------------------------- flint.deps.mvn

export class Mvn {
  constructor(repos) {
    // Clojars first because that is where Clojure libraries live; Central
    // because `org.clojure` itself does not.
    this.repos = repos ?? ['https://repo.clojars.org', 'https://repo1.maven.org/maven2'];
  }
  get name() { return 'flint.deps.mvn'; }
  get vars() { return varsOf(this.name); }

  invoke(v, args, policy, c) {
    const coord = strArg(args, 0, 'group/artifact');
    const slash = coord.indexOf('/');
    if (slash < 0) throw new Error(`${JSON.stringify(coord)} is not group/artifact`);
    const gpath = coord.slice(0, slash).replace(/\./g, '/');
    const artifact = coord.slice(slash + 1);
    switch (v) {
      case 'versions': {
        // `maven-metadata.xml`, read with a scan rather than an XML parser: the
        // file is machine-generated and the only thing wanted from it is the
        // `<version>` elements. A parser here would be a dependency for one tag.
        let out = [];
        for (const repo of this.repos) {
          const url = `${repo}/${gpath}/${artifact}/maven-metadata.xml`;
          let b;
          try { b = get(url, policy); } catch { continue; }
          const text = Buffer.from(b).toString('utf8');
          out = text.split('<version>').slice(1)
            .map((p) => p.split('</version>')[0])
            .filter((s) => s !== undefined)
            .map((s) => s.trim());
          break;
        }
        out.sort();
        out = out.filter((x, i) => i === 0 || out[i - 1] !== x);
        return c.vec(out.map((s) => c.str(s)));
      }
      case 'pom': {
        const version = strArg(args, 1, 'version');
        for (const repo of this.repos) {
          const url = `${repo}/${gpath}/${artifact}/${version}/${artifact}-${version}.pom`;
          try { return c.str(Buffer.from(get(url, policy)).toString('utf8')); } catch { /* next */ }
        }
        throw new Error(`no pom for ${coord} ${version}`);
      }
      case 'fetch': {
        const version = strArg(args, 1, 'version');
        const dir = cacheDir('mvn', coord, version);
        const stamp = join(dir, '.flint-fetched');
        if (existsSync(stamp)) return c.str(dir);
        let hit = null;
        for (const repo of this.repos) {
          const url = `${repo}/${gpath}/${artifact}/${version}/${artifact}-${version}.jar`;
          try { hit = [url, get(url, policy)]; break; } catch { /* next */ }
        }
        if (!hit) throw new Error(`no jar for ${coord} ${version} in any repository`);
        const [url, body] = hit;
        const digest = sha256Hex(body);
        mkdirSync(dir, { recursive: true });
        unpackZip(body, dir);
        writeFileSync(stamp, `${url}\nsha256:${digest}\n`);
        // A jar is a zip and Clojure source sits at its root, so the extracted
        // directory IS the source root.
        return c.str(dir);
      }
      default:
        throw new Error(`flint.deps.mvn has no ${v}`);
    }
  }
}

/// Unpack a zip into `dir`, with the same escape refusal `unpackTgz` has and
/// for the same reason: a zip entry names its own path.
export function unpackZip(body, dir) {
  const b = Buffer.from(body);
  // Read the CENTRAL DIRECTORY rather than walking local headers: a local
  // header may carry a zero size with the real one in a trailing descriptor,
  // and a reader that trusts the local header truncates those entries.
  const eocd = findEocd(b);
  if (eocd < 0) throw new Error('zip: no end-of-central-directory record');
  const count = b.readUInt16LE(eocd + 10);
  let at = b.readUInt32LE(eocd + 16);
  for (let i = 0; i < count; i++) {
    if (b.readUInt32LE(at) !== 0x02014b50) throw new Error('zip: bad central directory');
    const method = b.readUInt16LE(at + 10);
    const csize = b.readUInt32LE(at + 20);
    const nameLen = b.readUInt16LE(at + 28);
    const extraLen = b.readUInt16LE(at + 30);
    const commentLen = b.readUInt16LE(at + 32);
    const local = b.readUInt32LE(at + 42);
    const name = b.subarray(at + 46, at + 46 + nameLen).toString('utf8');
    at += 46 + nameLen + extraLen + commentLen;
    if (name.endsWith('/')) continue;
    const lnameLen = b.readUInt16LE(local + 26);
    const lextraLen = b.readUInt16LE(local + 28);
    const start = local + 30 + lnameLen + lextraLen;
    const raw = b.subarray(start, start + csize);
    const data = method === 0 ? raw : inflateRawSync(raw);
    const out = under(dir, name);
    mkdirSync(dirname(out), { recursive: true });
    writeFileSync(out, data);
  }
}

function findEocd(b) {
  for (let i = b.length - 22; i >= 0 && i > b.length - 22 - 65536; i--) {
    if (b.readUInt32LE(i) === 0x06054b50) return i;
  }
  return -1;
}

// ------------------------------------------------------------- flint.deps.git

/// Git as a FIRST-CLASS package manager.
///
/// flint shells out to `git` deliberately: the two operations it needs -- list
/// a repository's tags, and fetch one commit -- do not justify a git library,
/// and `git` is present wherever a developer fetches source from git at all.
export class Git {
  get name() { return 'flint.deps.git'; }
  get vars() { return varsOf(this.name); }

  invoke(v, args, policy, c) {
    const url = strArg(args, 0, 'url');
    if (!policy.depsAllows(url)) {
      throw new Error(`${url} is not in this program's :deps allowlist`);
    }
    switch (v) {
      // Every tag and the commit it points at, from ONE `ls-remote` -- no
      // clone, so asking what versions exist costs a round trip rather than a
      // checkout.
      case 'tags':
        return c.vec(parseLsRemote(git(['ls-remote', '--tags', url])).map(([tag, sha]) => c.map([
          [c.kw('tag'), c.str(tag)],
          [c.kw('sha'), c.str(sha)],
        ])));
      // EVERY tag matching the range, oldest first, with its sha and the
      // version it parsed to. The caller picks -- same rule as npm.
      case 'resolve': {
        const range = strArg(args, 1, 'range');
        const hits = [];
        for (const [tag, sha] of parseLsRemote(git(['ls-remote', '--tags', url]))) {
          const ver = tagVersion(tag);
          if (ver && matches(range, ver)) hits.push([ver, tag, sha]);
        }
        hits.sort((a, b) => compareVersions(a[0], b[0]));
        return c.vec(hits.map(([ver, tag, sha]) => c.map([
          [c.kw('version'), c.str(versionString(ver))],
          [c.kw('tag'), c.str(tag)],
          [c.kw('sha'), c.str(sha)],
        ])));
      }
      // CANONICAL `:git/tag`: the sha a named tag points at, unchanged.
      case 'resolve-tag': {
        const tag = strArg(args, 1, 'tag');
        const hit = parseLsRemote(git(['ls-remote', '--tags', url])).find(([t]) => t === tag);
        if (!hit) throw new Error(`${url} has no tag ${tag}`);
        return c.str(hit[1]);
      }
      // A SHALLOW fetch of one sha rather than a clone: it is the cheap shape,
      // and it cannot silently give a different commit later the way a branch
      // clone can.
      case 'fetch': {
        const sha = strArg(args, 1, 'sha');
        const dir = cacheDir('git', url, sha);
        const stamp = join(dir, '.flint-fetched');
        if (existsSync(stamp)) return c.str(dir);
        mkdirSync(dir, { recursive: true });
        git(['init', '-q'], dir);
        // `remote add` failing because it already exists is not a failure: a
        // re-fetch into a half-finished directory is the case this has to
        // survive.
        try { git(['remote', 'add', 'origin', url], dir); } catch { /* already there */ }
        git(['fetch', '-q', '--depth', '1', 'origin', sha], dir);
        git(['checkout', '-q', 'FETCH_HEAD'], dir);
        writeFileSync(stamp, `${url} ${sha}\n`);
        return c.str(dir);
      }
      default:
        throw new Error(`flint.deps.git has no ${v}`);
    }
  }
}

/// What to tell somebody who has no `git`.
///
/// A missing tool is the one error where "not found" is useless on its own: the
/// reader knows it is missing, and what they need is the line to type.
function noGitMessage(detail) {
  const how = process.platform === 'darwin'
    ? '  xcode-select --install    (Apple\'s, no Homebrew needed)\n'
      + '  brew install git          (if you use Homebrew)'
    : process.platform === 'win32'
      ? '  winget install Git.Git\n  or download it from https://git-scm.com/download/win'
      : process.platform === 'linux'
        ? '  apt install git           (Debian, Ubuntu)\n'
          + '  dnf install git           (Fedora, RHEL)\n'
          + '  pacman -S git             (Arch)\n'
          + '  apk add git               (Alpine)'
        : '  https://git-scm.com/downloads lists a package for every platform';
  return 'a git dependency needs the `git` program, and it is not on your PATH.\n\n'
    + how
    + '\n\nflint runs `git` rather than embedding a git implementation: what it needs\n'
    + 'is two operations -- listing a repository\'s tags, and fetching one commit --\n'
    + 'and a whole git library is a large thing to carry for two.\n\n'
    + `(the underlying error was: ${detail})`;
}

function git(args, cwd) {
  const r = spawnSync('git', args, { cwd, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
  if (r.error) {
    if (r.error.code === 'ENOENT') throw new Error(noGitMessage(r.error.message));
    throw new Error(`could not run git: ${r.error.message}`);
  }
  if (r.status !== 0) {
    throw new Error(`git ${args.join(' ')}: ${(r.stderr ?? '').trim()}`);
  }
  return r.stdout ?? '';
}

/// A tag, and the version it means. `null` when the tag is not a version.
///
/// The conventions that actually occur, and nothing more clever: a leading `v`,
/// a bare number, and a `name-1.2.3` suffix.
export function tagVersion(tag) {
  const t = String(tag).trim();
  const bare = parseVersion(t.startsWith('v') ? t.slice(1) : t);
  if (bare) return bare;
  const i = t.lastIndexOf('-');
  if (i < 0) return null;
  const after = t.slice(i + 1);
  return parseVersion(after.startsWith('v') ? after.slice(1) : after);
}

/// `ls-remote --tags` output to `[[tag, sha], ..]`.
///
/// `^{}` entries are DROPPED and that is the whole subtlety here. An annotated
/// tag is an object of its own, and `ls-remote` lists both the tag object and
/// the commit it dereferences to. The commit is the one a checkout wants;
/// taking the first line for a tag would pin the tag object, whose sha is not
/// the commit's, and the integrity check would then fail against a correct
/// repository.
export function parseLsRemote(out) {
  const best = [];
  for (const line of String(out).split('\n')) {
    const tab = line.indexOf('\t');
    if (tab < 0) continue;
    const sha = line.slice(0, tab);
    const ref = line.slice(tab + 1);
    if (!ref.startsWith('refs/tags/')) continue;
    const name = ref.slice('refs/tags/'.length);
    const deref = name.endsWith('^{}');
    const tag = deref ? name.slice(0, -3) : name;
    const slot = best.find((e) => e[0] === tag);
    // A dereferenced entry REPLACES the tag object's.
    if (slot) { if (deref) slot[1] = sha; } else best.push([tag, sha]);
  }
  return best;
}
