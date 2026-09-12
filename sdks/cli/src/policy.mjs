// What a capability grant is allowed to reach (`DECISIONS.md#system-namespaces-and-deps`).
//
// A transliteration of `cli/src/policy.rs`. A capability NAME says what kind of
// authority; it does not say which routes, which directories, which variables.
// This is the other half, and the rule it implements is the only rule there is:
//
// > **A grant may be narrowed at every hop and widened at none.**

/// A prefix match with one wildcard form: a trailing `**` matches any
/// continuation, and anything else must match exactly.
///
/// Deliberately not a glob library. A glob has enough syntax that a reader can
/// be wrong about what their own allowlist means, and being wrong about an
/// allowlist is the failure this whole file exists to prevent.
export function allows(patterns, s) {
  return patterns.some((p) => (p.endsWith('**') ? s.startsWith(p.slice(0, -2)) : p === s));
}

export class Policy {
  constructor() {
    /// URL prefixes `:slurp` may read. Empty means NONE -- holding `:slurp`
    /// with no allowlist reaches nothing, because the alternative is that a
    /// bare `:with [slurp]` quietly means the whole internet.
    this.slurp = [];
    /// Environment variable names `:env` may read. Empty means none.
    this.env = [];
    /// URL prefixes `:net` may reach.
    this.net = [];
    /// URL prefixes `:deps` may fetch from. Unlike the others this DEFAULTS to
    /// the public registries: a dependency resolver that reaches nowhere is one
    /// nobody can use, and reaching them is the whole point of holding `:deps`.
    this.deps = [];
  }

  slurpAllows(url) { return allows(this.slurp, url); }
  netAllows(url) { return allows(this.net, url); }
  envAllows(name) { return allows(this.env, name); }
  depsAllows(url) { return allows(this.deps, url); }

  /// `slurp:file://./config/**` and `slurp:https://example.com/**`, as the
  /// CLI's `:with` writes them. An entry with no `:` is the bare name, which
  /// grants the capability with an EMPTY allowlist.
  add(spec) {
    const i = spec.indexOf(':');
    const name = i < 0 ? spec : spec.slice(0, i);
    const rest = i < 0 ? null : spec.slice(i + 1);
    let target;
    if (name === 'slurp') target = this.slurp;
    else if (name === 'net') target = this.net;
    else if (name === 'env') target = this.env;
    else if (name === 'deps') {
      if (rest === null) {
        this.deps.push(
          'https://registry.npmjs.org/**',
          'https://repo.clojars.org/**',
          'https://repo1.maven.org/maven2/**');
        return;
      }
      target = this.deps;
    } else return;
    if (rest !== null) for (const p of rest.split(',')) if (p !== '') target.push(p);
  }
}
