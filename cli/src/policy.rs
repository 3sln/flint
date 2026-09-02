//! What a capability grant is allowed to reach (`doc/decisions/0037`).
//!
//! A capability NAME says what kind of authority; it does not say which routes,
//! which directories, which variables. This is the other half, and the rule it
//! implements is the only rule there is:
//!
//! > **A grant may be narrowed at every hop and widened at none.**
//!
//! Three levels, each narrowing the one above it -- the invoker's `:with`, the
//! project's `deps.edn`, then each dependency entry. The granting side is the
//! authority always, because a dependency saying what it may reach is the
//! request and not the answer. Nothing below the root can add to it, which is
//! what makes the whole chain auditable from the top.
//!
//! ## Why this is not the compile-time guard
//!
//! It is not, and must not be. The guard compares NAMES at the reference
//! (`0036` step 8); this is checked when a call actually happens, with the URL
//! or the path in hand. A guard that tried to check the route would be checking
//! a run-time value at compile time, which `0036` refuses at length. What the
//! policy adds is that the specific question gets a declarative answer a
//! reviewer can read in a diff, rather than one buried in host code.

/// One `name:spec` grant, parsed.
#[derive(Debug, Clone, Default)]
pub struct Policy {
    /// URL prefixes `:slurp` may read. Empty means NONE -- holding `:slurp`
    /// with no allowlist reaches nothing, because the alternative is that a
    /// bare `:with [slurp]` quietly means the whole internet.
    pub slurp: Vec<String>,
    /// Environment variable names `:env` may read. Empty means none.
    pub env: Vec<String>,
    /// URL prefixes `:net` may reach.
    pub net: Vec<String>,
}

/// A prefix match with one wildcard form: a trailing `**` matches any
/// continuation, and anything else must match exactly.
///
/// Deliberately not a glob library. A glob has enough syntax that a reader can
/// be wrong about what their own allowlist means, and being wrong about an
/// allowlist is the failure this whole file exists to prevent.
pub fn allows(patterns: &[String], s: &str) -> bool {
    patterns.iter().any(|p| match p.strip_suffix("**") {
        Some(pre) => s.starts_with(pre),
        None => p == s,
    })
}

impl Policy {
    pub fn slurp_allows(&self, url: &str) -> bool {
        allows(&self.slurp, url)
    }
    pub fn net_allows(&self, url: &str) -> bool {
        allows(&self.net, url)
    }
    pub fn env_allows(&self, name: &str) -> bool {
        allows(&self.env, name)
    }

    /// `slurp:file://./config/**` and `slurp:https://example.com/**`, as the
    /// CLI's `:with` writes them. An entry with no `:` is the bare name, which
    /// grants the capability with an EMPTY allowlist.
    pub fn add(&mut self, spec: &str) {
        let (name, rest) = match spec.split_once(':') {
            Some((n, r)) => (n, Some(r)),
            None => (spec, None),
        };
        let target = match name {
            "slurp" => &mut self.slurp,
            "net" => &mut self.net,
            "env" => &mut self.env,
            _ => return,
        };
        if let Some(r) = rest {
            for p in r.split(',').filter(|s| !s.is_empty()) {
                target.push(p.to_string());
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_empty_allowlist_reaches_nothing() {
        let p = Policy::default();
        assert!(!p.slurp_allows("https://example.com/x"));
        assert!(!p.env_allows("HOME"));
    }

    #[test]
    fn a_trailing_star_star_is_a_prefix_and_nothing_else_is() {
        let mut p = Policy::default();
        p.add("slurp:https://example.com/**,file://exact.txt");
        assert!(p.slurp_allows("https://example.com/a/b"));
        assert!(p.slurp_allows("file://exact.txt"));
        assert!(!p.slurp_allows("file://exact.txt.other"));
        assert!(!p.slurp_allows("https://evil.com/"));
    }

    #[test]
    fn a_prefix_match_does_not_stop_at_a_host_boundary_and_that_is_why_the_slash_matters() {
        // `https://example.com**` would also allow `https://example.com.evil.com`.
        // Writing the pattern with the trailing slash is the caller's job, and
        // this test exists so the sharp edge is recorded rather than assumed
        // away: the matcher is a prefix matcher and says so.
        let mut p = Policy::default();
        p.add("slurp:https://example.com**");
        assert!(p.slurp_allows("https://example.com.evil.com/x"));
        let mut q = Policy::default();
        q.add("slurp:https://example.com/**");
        assert!(!q.slurp_allows("https://example.com.evil.com/x"));
    }
}
