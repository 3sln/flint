// The npm CLI's resolver: what this host answers when the compiler asks for a
// namespace (`DECISIONS.md#namespaces-over-the-system-port` §4).
//
// The CLI is a host like any other and composes the SDK's building blocks:
//
//     chain(stdextra, the host catalogue's virtual namespaces, roots..)
//
// stdcore is not in it: `compileCall` answers those names from the embedded copy
// before this resolver is asked anything. STRICT PRIORITY, not segregation: a
// standard-library name is always the library's (a project cannot replace
// `clojure.set` any more, where the old spec let a project file at the same path
// win), but a project may still define NEW `flint.*` namespaces -- the compiler's
// own source is one, and the native CLI compiles it. Either is safe for the
// reason the decision gives: grants come from the answer, so a project file under
// `flint/` is answered with its OWN root's workspace and never the library's.
//
// Each project answer carries the workspace of its root's `deps.edn` (beside the
// root or one directory up), read with the same scan the native CLI makes. A
// source that is a FILE inherits nothing from the directory it sits in
// (`DECISIONS.md#standalone-scripts`).

import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, basename, dirname } from 'node:path';
import { ednBlock, ednToken, byBytes } from './edn.mjs';
import { CATALOGUE } from './catalogue.mjs';
import { scriptNs } from './script.mjs';
import { codec } from '../dist/codec.js';
import {
  chain, fromMap, layer, virtualNamespaces, SOURCE_EXTENSIONS, nsPath, dialectOf,
} from '../dist/resolve.js';

/// Every source file under `dir`, keyed by its path relative to `dir` -- which
/// is exactly how a namespace maps to a file. A LISTING, not an existence probe
/// per name: macOS answers `App.cljc` for `app.cljc`, and the native CLI keys
/// by what the directory says.
export function readSources(dir, prefix, out) {
  for (const name of readdirSync(dir).sort(byBytes)) {
    const p = join(dir, name);
    const rel = prefix === '' ? name : `${prefix}/${name}`;
    let st;
    try { st = statSync(p); } catch { continue; }
    if (st.isDirectory()) readSources(p, rel, out);
    else if (SOURCE_EXTENSIONS.some((e) => rel.endsWith(e))) out.set(rel, readFileSync(p, 'utf8'));
  }
  return out;
}

const tokens = (frag) => frag.split(/[\s,]+/).filter(Boolean);

/// One workspace's facts, from a `deps.edn`: the native CLI's `read_workspace`,
/// with a SCAN rather than an EDN parser, as there.
export function readWorkspace(text) {
  return {
    name: ednToken(text, ':flint/workspace'),
    tags: ednBlock(text, ':flint/tag-readers', '{', '}'),
    prelude: ednBlock(text, ':flint/prelude', '[', ']'),
    grants: ednBlock(text, ':flint/capabilities-grant', '[', ']'),
    guard: ednBlock(text, ':flint/capabilities-guard', '[', ']'),
  };
}

function isDir(p) {
  try { return statSync(p).isDirectory(); } catch { return false; }
}

/// The file's text, or `null` when it could not be read -- DIFFERENT from `''`:
/// a `deps.edn` that exists and is empty stops the search with nothing to say.
function readIfAny(p) {
  try { return readFileSync(p, 'utf8'); } catch { return null; }
}

/// The identity a root's answers carry, or `null` when its `deps.edn` says
/// nothing. A workspace with no `:flint/workspace` is named by its DIRECTORY, as
/// a string (`workspace_entry`'s fallback), so two such roots stay distinct.
function rootIdentity(dir) {
  // `dirname` on the path AS WRITTEN: `Path::new("src").parent()` is `""`, the
  // working directory, where resolving first would read somebody else's file.
  const text = readIfAny(join(dir, 'deps.edn')) ?? readIfAny(join(dirname(dir), 'deps.edn')) ?? '';
  if (text.trim() === '') return null;
  const w = readWorkspace(text);
  if (w.name === '' && w.tags === '' && w.grants === '' && w.guard === '') return null;
  const t = tokens(w.tags);
  const pairs = [];
  for (let i = 0; i + 1 < t.length; i += 2) pairs.push([t[i], t[i + 1]]);
  return {
    workspace: w.name !== '' ? w.name : codec.str(dir),
    tags: pairs,
    prelude: tokens(w.prelude),
    grants: tokens(w.grants),
    guard: tokens(w.guard),
  };
}

/// One source root as a resolver.
function root(s) {
  if (isDir(s)) {
    const files = readSources(s, '', new Map());
    return fromMap(files, rootIdentity(s) ?? {});
  }
  // A FILE IS KEYED BY THE NAMESPACE IT DECLARES, not by what it is called on
  // disk (`flint ~/bin/greet`); one with no readable `ns` keeps its own name. A
  // file with no source extension is a script, and a script is `.fln`.
  const body = readFileSync(s, 'utf8');
  const name = basename(s);
  const ext = SOURCE_EXTENSIONS.find((e) => name.endsWith(e)) ?? '.fln';
  const ns = scriptNs(body)?.ns;
  const key = ns ? nsPath(ns) + ext : name;
  return (want) => (nsPath(want) + ext === key
    ? { source: body, file: key, dialect: dialectOf(key) } : null);
}

/// The virtual namespaces this host SERVES (`catalogue.mjs`), as a resolver.
/// `nested` decides whether `flint.ception` is offered at all: absent, a
/// `(:require [flint.ception])` is a compile error rather than a run-time
/// refusal (`DECISIONS.md#flint-ception`).
function catalogue(nested) {
  const served = new Map();
  for (const [ns, vars] of CATALOGUE) {
    if (ns === 'flint.ception' && !nested) continue;
    served.set(ns, {
      workspace: 'flint/sys',
      vars: vars.map(([name, arities]) => ({ name, arities })),
    });
  }
  return virtualNamespaces(served);
}

/// The CLI's resolver for `srcs` -- source roots -- or for `sources`, a
/// `[[ns, text], ..]` list (`flint.ception`'s `:sources`), given the stdextra
/// blob. Both get the library and the catalogue: a nested program asking for
/// `flint.sys.env` is served it exactly as a top-level one is.
///
/// `sources` text is PORTABLE source by that surface's contract, so its
/// dialect is said rather than guessed from a made-up file name.
export function projectResolver({ srcs = null, sources = null, stdextra, nested = true }) {
  let mine;
  if (sources) {
    const byNs = new Map(sources);
    mine = [(ns) => (byNs.has(String(ns)) ? { source: byNs.get(String(ns)), dialect: 'portable' } : null)];
  } else {
    mine = srcs.map(root);
  }
  return chain(layer(stdextra), catalogue(nested), ...mine);
}

/// Every namespace declared under `srcs`, for `test`: a test that nothing
/// requires is still a test, so every file on the path is a root. The `ns` form
/// is found by scanning, the same deliberate limit the native CLI takes.
export function testRoots(srcs) {
  const files = new Map();
  for (const s of srcs) {
    if (isDir(s)) readSources(s, '', files);
    else files.set(basename(s), readFileSync(s, 'utf8'));
  }
  const out = ['clojure.core'];
  for (const k of [...files.keys()].sort(byBytes)) {
    const name = scriptNs(files.get(k))?.ns ?? '';
    if (name !== '' && !out.includes(name)) out.push(name);
  }
  return out;
}


