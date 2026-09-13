// The EDN spec the compiler takes: the sources, the entry, and who owns what
// (`DECISIONS.md#npm-cli`).
//
// This is the half of the CLI that is not "run wasm". `dist/flintc.wasm` under
// node has always worked; what it was never given is a spec built from a
// DIRECTORY -- source reading, the standard library, the virtual namespaces
// this host serves, and each source root's workspace identity out of its
// `deps.edn`. `host/flint-file.mjs` takes a spec somebody else built, which is
// why timing it against the native CLI compared two different jobs
// (ROADMAP.md, "Design: how the CLI itself ships").
//
// Every function here is a transliteration of `build_spec_with` and its helpers
// in `cli/src/main.rs`. The goal is BYTE-IDENTICAL spec text, because the
// compiler is deterministic and identical text is what makes the two CLIs
// produce identical modules -- which is the only check that can tell "it works"
// from "it works on the cases I tried".

import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, basename, dirname } from 'node:path';
import { ednString, ednBlock, ednToken, byBytes } from './edn.mjs';
import { CATALOGUE } from './catalogue.mjs';

/// `flint.project/source-extensions` is the list that decides which file WINS
/// for a namespace. This side only decides what is worth reading off the disk,
/// so order does not matter here and a superset would merely cost a read.
const SOURCE_EXT = ['.fln', '.cljc', '.clj'];

/// Every source file under `dir`, keyed by its path relative to `dir` -- which
/// is exactly how a namespace maps to a file, so the compiler can find them.
export function readSources(dir, prefix, out) {
  const names = readdirSync(dir).sort(byBytes);
  for (const name of names) {
    const p = join(dir, name);
    const rel = prefix === '' ? name : `${prefix}/${name}`;
    let st;
    try { st = statSync(p); } catch { continue; }
    if (st.isDirectory()) readSources(p, rel, out);
    else if (SOURCE_EXT.some((e) => rel.endsWith(e))) out.set(rel, readFileSync(p, 'utf8'));
  }
  return out;
}

/// One workspace's facts, read from a `deps.edn`.
///
/// The same four keys `bin/flint`'s `workspace-of` and the native CLI's
/// `read_workspace` read, with a SCAN rather than an EDN parser: the guest owns
/// the format and a second reader of it is a second thing to keep true.
export function readWorkspace(text) {
  return {
    name: ednToken(text, ':flint/workspace'),
    tags: ednBlock(text, ':flint/tag-readers', '{', '}'),
    grants: ednBlock(text, ':flint/capabilities-grant', '[', ']'),
    guard: ednBlock(text, ':flint/capabilities-guard', '[', ']'),
  };
}

/// A workspace entry for the spec, or empty when there is nothing to say.
export function workspaceEntry(prefix, w, fallbackName) {
  const name = w.name === '' ? fallbackName : w.name;
  if (name === '' && w.tags === '' && w.grants === '' && w.guard === '') return '';
  return `{:prefix ${ednString(prefix)} :name ${name} :tags {${w.tags}} ` +
         `:grants [${w.grants}] :guard [${w.guard}]} `;
}

function isDir(p) {
  try { return statSync(p).isDirectory(); } catch { return false; }
}

/// The file's text, or `null` when it could not be read.
///
/// `null` rather than `''` because the two are DIFFERENT here: the native CLI
/// falls through to the directory above only when the read FAILED, and a
/// `deps.edn` that exists and is empty stops the search with nothing to say.
/// Collapsing them made an empty file behave like a missing one, which is a
/// workspace silently inheriting its parent's identity and grants.
function readIfAny(p) {
  try { return readFileSync(p, 'utf8'); } catch { return null; }
}

/// The spec.
///
/// | | |
/// | --- | --- |
/// | `srcs` | source roots, as paths. A file is a root of one file. |
/// | `entry` | `ns/fn`, unquoted -- the compiler reads it as a symbol |
/// | `slots` | `{builtin -> table slot}` for the runtime being spliced into |
/// | `roots` | overrides "start from the entry namespace"; `test` needs it |
/// | `pods` | `[[namespace, [varName, ..]], ..]` for pods this build booted |
export function buildSpec({
  srcs, entry, slots, aot = false, shake = false, meta = [], roots = null,
  pods = [], stdlib, stdlibDeps, stripChecks = false, exports = [], features = null,
}) {
  // `:flint/nested` decides whether `flint.ception` is offered at all. Absent from
  // an explicit set, the namespace is not emitted and a program naming it does
  // not compile (`DECISIONS.md#flint-ception`). Default is ON.
  const nested = features === null || features.includes(':flint/nested');
  // The standard library first, so a project file of the same path wins.
  const files = new Map();
  for (const [p, body] of Object.entries(stdlib)) files.set(p, body);

  // PER ROOT, so each file can be attributed to the workspace that owns it.
  // Reading them all into one map loses which root a file came from, and the
  // paths here are namespace-derived with no marker to recover it.
  const owned = [];
  for (const s of srcs) {
    const mine = new Map();
    if (isDir(s)) readSources(s, '', mine);
    else mine.set(basename(s), readFileSync(s, 'utf8'));
    owned.push([s, [...mine.keys()].sort(byBytes)]);
    for (const [k, v] of mine) files.set(k, v);
  }

  let out = '{:files {';
  for (const k of [...files.keys()].sort(byBytes)) {
    out += `${ednString(k)} ${ednString(files.get(k))} `;
  }
  out += '} :entry ' + entry;

  // `:roots` overrides "start from the entry namespace", which `test` needs
  // because its entry is `flint.check.registry` -- a namespace the compiler
  // GENERATES from what it found, so no source path contains it and resolving
  // from it reports the entry itself missing.
  if (roots) {
    out += ' :roots [';
    for (const r of roots) out += `${r} `;
    out += ']';
  }

  out += ' :workspaces [';
  // POD namespaces, if any were declared. NO ARITIES: a pod's `describe` gives
  // names and metadata, and arities are not always in it -- so this says
  // "unchecked" by omitting the field rather than claiming `[]`, which would
  // read as "takes no arguments" and refuse every real call.
  for (const [ns, vars] of pods) {
    out += '{:prefix ';
    out += ednString(`${ns.replace(/\./g, '/')}/`);
    out += ` :name pod/pod :virtual true :vars [`;
    for (const v of vars) out += `{:name ${v}} `;
    out += ']} ';
  }
  // The VIRTUAL namespaces this host serves. They have no source, so the
  // resolver has to be told they exist or a `:require` of one is reported
  // missing -- and it has to be told what they HOLD, so an unknown var is a
  // compile error rather than a run-time one.
  for (const [ns, vars] of CATALOGUE) {
    // NOT NAMEABLE without the feature: omitting the workspace is what makes
    // `(:require [flint.ception])` a compile error rather than a run-time refusal.
    if (ns === 'flint.ception' && !nested) continue;
    out += '{:prefix ';
    out += ednString(`${ns.replace(/\./g, '/')}/`);
    out += ' :name flint/sys :virtual true :vars [';
    for (const [name, arities] of vars) {
      out += `{:name ${name} :arities [`;
      for (const a of arities) out += `${a} `;
      out += ']} ';
    }
    out += ']} ';
  }
  // THE SOURCE WORKSPACES, after the virtual ones because the first matching
  // prefix wins and `flint/` would otherwise swallow `flint/sys/fs/`.
  const lib = readWorkspace(stdlibDeps);
  for (const pre of ['clojure/', 'flint/']) out += workspaceEntry(pre, lib, '');
  // The PROJECT's own, from `deps.edn` beside a source root or one directory
  // up -- the rule `bin/flint` states and follows.
  for (const [sdir, paths] of owned) {
    const dir = isDir(sdir) ? sdir : dirname(sdir);
    const here = readIfAny(join(dir, 'deps.edn'));
    // `dirname` on the path AS WRITTEN, not on a resolved one: the native CLI
    // uses `Path::parent`, and `Path::new("src").parent()` is `""` -- the
    // working directory -- where resolving first would give the working
    // directory's PARENT and read somebody else's `deps.edn`.
    const text = here ?? readIfAny(join(dirname(dir), 'deps.edn')) ?? '';
    if (text.trim() === '') continue;
    const w = readWorkspace(text);
    // ONE ENTRY PER FILE, with the file's own path as the prefix. Prefix
    // matching is `starts-with?`, so a full path matches exactly that file --
    // which is how a root whose files interleave with another root's in one
    // flat namespace-derived space still gets its own workspace.
    for (const path of paths) {
      const e = workspaceEntry(path, w, ednString(dir));
      if (e !== '') out += e;
    }
  }

  // The `]` CLOSES `:workspaces`, so it goes first -- `:features` emitted
  // before it lands inside the vector, which is a spec that parses and means
  // something else.
  out += ']';
  // Said only when it differs from the default, so an ordinary build's spec is
  // byte-identical to what it was -- and, more to the point, byte-identical to
  // the native CLI's. This line is the whole of `:checks`: dropping the default
  // features drops `:flint/check`, which is what compiles a check in.
  // THE STRIP-CHECKS SET KEEPS `:flint/nested`. It used to be `#{:flint}` --
  // the whole default minus checks -- but the default gained `:flint/nested`,
  // and the old literal would have turned the SDK off in every
  // `:optimize [perf]` build as a side effect of dropping checks.
  if (features !== null) out += ` :features #{${features.join(' ')}}`;
  else if (stripChecks) out += ' :features #{:flint :flint/nested}';
  out += ' :builtins #{';
  const keys = Object.keys(slots).sort(byBytes);
  for (const k of keys) out += `${ednString(k)} `;
  out += '} :slots {';
  for (const k of keys) out += `${ednString(k)} ${slots[k]} `;
  out += '}';
  // `:exports` keeps a function callable through the shake. NOT `:roots`:
  // roots are namespaces to resolve from, so a qualified function name there
  // reports itself missing.
  if (exports.length) out += ` :exports [${exports.join(' ')}]`;
  if (aot) out += ' :aot true';
  if (shake) out += ' :shake true';
  if (meta.length) {
    // Arbitrary, and never read: flint carries what the host put there. The
    // DECLARED CAPABILITIES of a program live here by the CLI's convention,
    // and the convention belongs to whoever reads them.
    out += ' :meta {';
    for (const [k, v] of meta) out += `${ednString(k)} ${ednString(v)} `;
    out += '}';
  }
  out += '}';
  if (process.env.FLINT_TRACE_SPEC) {
    const i = out.indexOf(':workspaces');
    if (i >= 0) process.stderr.write(`[spec] ${out.slice(i, i + 400)}\n`);
  }
  return out;
}

/// Every namespace declared under `srcs`, for `test`.
///
/// A test that nothing requires is still a test, so a test run cannot collect
/// from one entry outwards -- it would silently run a subset and report the
/// subset as the total, which is the one failure mode a test runner must not
/// have. Every file on the path is a root instead.
///
/// The `ns` form is found by scanning, the same deliberate limit the native CLI
/// takes: a file whose `ns` form is not the first `(ns ` in it is not found.
export function testRoots(srcs) {
  const files = new Map();
  for (const s of srcs) {
    if (isDir(s)) readSources(s, '', files);
    else files.set(basename(s), readFileSync(s, 'utf8'));
  }
  const out = ['clojure.core'];
  for (const k of [...files.keys()].sort(byBytes)) {
    const body = files.get(k);
    const i = body.indexOf('(ns ');
    if (i < 0) continue;
    const rest = body.slice(i + 4);
    const m = rest.match(/^\s*([^\s)]+)/);
    const name = m ? m[1] : '';
    if (name !== '' && !out.includes(name)) out.push(name);
  }
  return out;
}
