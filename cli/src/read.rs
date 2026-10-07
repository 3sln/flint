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

use flint_rt::rt::Rt;
use flint_rt::value::{Value, NIL};

/// How one file is read: the compile's FEATURES (`None` reads deferred, as the
/// standard library is embedded), the workspace's tag map as `(tag, var)`
/// symbol names, and the dialect.
pub(crate) struct ReadAs<'a> {
    pub features: Option<&'a [String]>,
    pub tags: &'a [(String, String)],
    pub portable: bool,
}

/// A symbol or keyword NAME, `ns/name` or `name`, split at its first slash --
/// except a lone `/`, which is a name.
fn split_name(s: &str) -> (Option<&str>, &str) {
    match s.find('/') {
        Some(i) if i > 0 && i + 1 < s.len() => (Some(&s[..i]), &s[i + 1..]),
        _ => (None, s),
    }
}

/// `text`, read as the file `name`, as `flint.forms` bytes; or the message the
/// read failed with, exactly as the guest's reader words it.
pub(crate) fn read_forms(name: &str, text: &str, how: &ReadAs) -> Result<Vec<u8>, String> {
    let mut rt = Rt::new();
    let base = rt.mark();
    let src = rt.string(text);
    let si = rt.push(src);
    let file = rt.string(name);
    let fi = rt.push(file);
    let feats = match how.features {
        None => NIL,
        Some(fs) => {
            // `(set [..])`: THROUGH A TRANSIENT, as the guest's `set` builds the
            // feature set it compiles with, so the two encode in one order.
            let e = rt.empty_set();
            let t = rt.to_transient(e);
            let ti = rt.push(t);
            for f in fs {
                let (ns, n) = split_name(f.trim_start_matches(':'));
                let k = rt.keyword(ns, n);
                let t2 = rt.transient_conj(rt.r(ti), k);
                rt.set_r(ti, t2);
            }
            rt.to_persistent(rt.r(ti))
        }
    };
    let fsi = rt.push(feats);
    let tags = if how.tags.is_empty() {
        NIL
    } else {
        // AN ARRAY MAP IN THE ORDER GIVEN, which is how the guest's EDN reader
        // builds `:flint/tag-readers`; the compiler compares it with `=`.
        let e = rt.empty_map();
        let mi = rt.push(e);
        for (tag, var) in how.tags {
            let (tn, tm) = split_name(tag);
            let k = rt.symbol(tn, tm);
            let ki = rt.push(k);
            let (vn, vm) = split_name(var);
            let v = rt.symbol(vn, vm);
            let m2 = rt.map_assoc(rt.r(mi), rt.r(ki), v);
            rt.set_r(mi, m2);
            rt.pop_to(ki);
        }
        rt.r(mi)
    };
    let tgi = rt.push(tags);
    let out = rt.read_forms(rt.r(si), rt.r(fi), rt.r(fsi), rt.r(tgi), how.portable, 1);
    let oi = rt.push(out);
    let res = if rt.is_bytes(rt.r(oi)) {
        Ok(rt.b_to_vec(rt.r(oi)))
    } else if out.is_nil() {
        Err(format!("the reader ran out of heap reading {name}"))
    } else {
        let m: Value = rt.vec_nth(rt.r(oi), 0, NIL);
        Err(rt.value_text(m))
    };
    rt.pop_to(base);
    res
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
