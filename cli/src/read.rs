//! THE HOST READS (`DECISIONS.md#namespaces-over-the-system-port`, migration
//! step 2): a project's source text read here, outside the compiler sandbox,
//! by the kin-generated reader every runtime carries (`Rt::read_forms`,
//! `kin/formsenc.kin`), and handed to the compiler as `flint.forms` bytes.
//!
//! The compiler checks the bytes' options on arrival (`flint.project/read-entry`):
//! `{:file :features :tags :dialect}` must be exactly what it would have read
//! the file under, so a host that read with the wrong features or tags is
//! refused rather than compiled. The DIALECT is passed in, never derived here
//! from a path.
//!
//! What can disagree with the guest is exercised by `bin/check-reader`: every
//! file in `lib/`, `src/`, `corpus/` and the fixtures reads byte-identically on
//! the guest's reader and on this one.

use flint_rt::hostread::{self, ReadAs as Shared};
use flint_rt::rt::Rt;

/// How one file is read: the compile's FEATURES (`None` reads deferred, as the
/// standard library is embedded), the workspace's tag map as `(tag, var)`
/// symbol names, and the dialect.
pub(crate) struct ReadAs<'a> {
    pub features: Option<&'a [String]>,
    pub tags: &'a [(String, String)],
    pub portable: bool,
}

/// `text`, read as the file `name`, as `flint.forms` bytes; or the message the
/// read failed with, exactly as the guest's reader words it.
///
/// DELEGATES to `flint_rt::hostread::read_text`, which the JavaScript doors'
/// reader module runs too: one construction of the feature set and tag map for
/// every host, rather than one here and one there.
pub(crate) fn read_forms(name: &str, text: &str, how: &ReadAs) -> Result<Vec<u8>, String> {
    let feats: Option<Vec<&str>> = how.features.map(|fs| fs.iter().map(String::as_str).collect());
    let tags: Vec<(&str, &str)> = how.tags.iter().map(|(t, v)| (t.as_str(), v.as_str())).collect();
    let shared = Shared { features: feats.as_deref(), tags: &tags, portable: how.portable };
    let mut rt = Rt::new();
    hostread::read_text(&mut rt, name, text, &shared).map_err(|e| e.message)
}

/// The `(tag, var)` pairs of a `:flint/tag-readers` block's inner text, when it
/// is nothing but symbol pairs -- `None` for anything else, which the caller
/// leaves to the compiler to read rather than guess at.
pub(crate) fn tag_pairs(inner: &str) -> Option<Vec<(String, String)>> {
    let toks: Vec<&str> = inner.split(|c: char| c.is_whitespace() || c == ',').filter(|t| !t.is_empty()).collect();
    if toks.len() % 2 != 0 {
        return None;
    }
    let plain = |t: &str| {
        t.chars().all(|c| c.is_alphanumeric() || "*+!-_'?<>=/.".contains(c))
            && !t.starts_with(|c: char| c.is_ascii_digit())
            && !t.starts_with(':')
    };
    if !toks.iter().all(|t| plain(t)) {
        return None;
    }
    Some(toks.chunks(2).map(|p| (p[0].to_string(), p[1].to_string())).collect())
}
