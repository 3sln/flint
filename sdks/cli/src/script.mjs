// What a standalone script says about itself (`DECISIONS.md#standalone-scripts`):
// its namespace, its entry, and the directories it asks for beside it. The
// counterpart of `cli/src/script.rs`.
//
// This file was `spec.mjs`, and most of it built the EDN spec the compiler used
// to take. That spec is gone -- the compile is a call now, with namespaces
// answered over a port (`resolver.mjs`,
// `DECISIONS.md#namespaces-over-the-system-port`) -- and what was left is this.

import { readFileSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';

function isDir(p) {
  try { return statSync(p).isDirectory(); } catch { return false; }
}

/// What a standalone script says about itself: its namespace, the entry its
/// `^:script` mark names, and the directories it asks for beside itself.
///
/// THE THIRD HAND-WRITTEN READER OF THIS ONE FACT. `cli/src/script.rs` has one
/// and `bin/flint` has another (that one uses flint's own reader, being able
/// to). Three front doors each deriving the same thing is the shape
/// `DECISIONS.md#standalone-scripts` warns about, and consolidating them --
/// most likely by asking the compiler, which already parses this properly --
/// is worth doing before a fourth appears.
///
/// `^:script` IS METADATA, so the mark is parsed rather than grepped: a plain
/// `/\^:script/` gets `^{:script go}` wrong, and that is exactly the form that
/// names an entry other than `main`.
export function scriptNs(body) {
  const i = body.indexOf('(ns ');
  if (i < 0) return null;
  let rest = body.slice(i + 4);
  let mark = null;
  for (;;) {
    rest = rest.replace(/^\s+/, '');
    if (!rest.startsWith('^')) break;
    if (rest[1] === '{') {
      let depth = 0, j = 1;
      for (; j < rest.length; j++) {
        if (rest[j] === '{') depth++;
        else if (rest[j] === '}') { depth--; if (depth === 0) break; }
      }
      const m = rest.slice(2, j).match(/:script\s+([^\s}]+)/);
      if (m) mark = m[1];
      rest = rest.slice(j + 1);
    } else {
      const m = rest.match(/^\^([^\s{()]+)/);
      if (m && m[1] === ':script') mark = 'true';
      rest = rest.slice(m ? m[0].length : 1);
    }
  }
  const nm = (rest.match(/^\s*([^\s)]+)/) || [])[1];
  if (!nm) return null;
  const entry = mark === null ? null
              : mark === 'true' ? `${nm}/main`
              : `${nm}/${mark}`;
  const paths = [];
  const pm = body.slice(i).match(/\(:paths\s*\[([^\]]*)\]/);
  if (pm) {
    for (const t of pm[1].match(/"([^"]*)"/g) || []) paths.push(t.slice(1, -1));
  }
  return { ns: nm, entry, paths };
}

/// A script's sources and entry, the same computation the other two CLIs do.
export function scriptSpec(path) {
  const body = readFileSync(path, 'utf8');
  const nsf = scriptNs(body);
  if (!nsf) {
    throw new Error(`${path} has no \`ns\` form, so it is not a flint script.`);
  }
  if (!nsf.entry) {
    throw new Error(
      `(ns ${nsf.ns}) in ${path} is not marked \`^:script\`, so nothing names its ` +
      `entry point.\nWrite (ns ^:script ${nsf.ns} ...) to use ${nsf.ns}/main, ` +
      `or name it with :fn.`);
  }
  const dir = dirname(path);
  const srcs = [path];
  for (const p of nsf.paths) {
    const d = join(dir, p);
    if (!isDir(d)) {
      throw new Error(
        `(ns ${nsf.ns}) asks for the source directory "${p}", which is not a ` +
        `directory. A script's :paths are relative to the script itself, so this ` +
        `was looked for at ${d}.`);
    }
    srcs.push(d);
  }
  return { srcs, entry: nsf.entry };
}

