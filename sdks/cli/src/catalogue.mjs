// Every namespace this CLI serves, and what each holds
// (`DECISIONS.md#npm-cli`, `system-namespaces-and-deps`).
//
// The same list `cli/src/sys.rs`'s `catalogue()` is, and for the same two uses:
// it is what the COMPILER is told (so a `:require` of `flint.sys.fs` resolves
// and an unknown var is a compile error) and what an `{:op :list}` answers at
// run time.
//
// AGENTS.md §1 says to make one list read the other rather than restate it, and
// this restates it: the Rust list is Rust `Service` impls and cannot be
// imported into node. So the drift is caught instead of being prevented --
// `bin/check-sys-catalogue` parses the `vars()` bodies out of `cli/src/sys.rs`
// and `cli/src/deps.rs` and fails if they and this file disagree. A list that
// is checked is not as good as a list that cannot differ, and it is what is
// available across a language boundary.
//
// The ORDER matters. It is the order the virtual workspaces appear in the spec,
// and the spec text has to match the native CLI's byte for byte.

export const CATALOGUE = [
  ['flint.sys.fs', [
    ['read-file', [1]],
    ['write-file', [2]],
    ['exists?', [1]],
    ['dir?', [1]],
    ['list-dir', [1]],
    ['mkdir', [1]],
    ['delete', [1]],
    ['root', [0]],
  ]],
  ['flint.sys.env', [
    ['get', [1]],
    ['args', [0]],
    ['cwd', [0]],
  ]],
  ['flint.sys.slurp', [
    ['slurp', [1]],
    ['slurp-bytes', [1]],
  ]],
  ['flint.deps.npm', [
    ['versions', [1]],
    ['resolve', [2]],
    ['manifest', [2]],
    ['fetch', [2]],
  ]],
  ['flint.deps.mvn', [
    ['versions', [1]],
    ['pom', [2]],
    ['fetch', [2]],
  ]],
  ['flint.deps.git', [
    ['tags', [1]],
    ['resolve', [2]],
    ['resolve-tag', [2]],
    ['fetch', [2]],
  ]],
  ['flint.sys.wasm', [
    ['run', [2, 3]],
    ['engine', [0]],
    ['use', [1]],
    ['reset', [0]],
  ]],
  ['flint.sdk', [
    ['compile', [1]],
    ['run', [1]],
    ['version', [0]],
  ]],
];

/// The var list for one served namespace, by name.
export function varsOf(name) {
  const hit = CATALOGUE.find(([n]) => n === name);
  return hit ? hit[1] : null;
}
