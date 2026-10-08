//! ONE HOST-SIDE READ, for every host that reads source outside the compiler
//! sandbox (`DECISIONS.md#namespaces-over-the-system-port`): the native CLI
//! (`cli/src/read.rs`) and the JavaScript doors, through the small reader
//! module `units-src/flint-reader` links from this crate.
//!
//! It was a function in the native CLI. Moving it here is what lets a wasm host
//! read with the SAME code rather than a second copy of how the feature set and
//! the tag map are built -- and that construction is not incidental: a set and
//! a map encode in the order they were BUILT (CHAMP order, and an array map in
//! insertion order), and the compiler compares the read options the bytes carry
//! with `=` against the ones it would have read under, so a host that builds
//! them another way produces bytes the compiler refuses or, worse, different
//! bytes for an equal value.
//!
//! The reader itself is kin's (`kin/formsenc.kin`, `Rt::read_forms`); this is
//! the argument plumbing around it.

use crate::rt::Rt;
use crate::value::NIL;
use alloc::string::String;
use alloc::vec::Vec;

/// How one file is read: the compile's FEATURES (`None` reads deferred, as the
/// standard library is embedded), the workspace's tag map as `(tag, var)`
/// symbol names in the order the workspace declares them, and the dialect.
pub struct ReadAs<'a> {
    pub features: Option<&'a [&'a str]>,
    pub tags: &'a [(&'a str, &'a str)],
    pub portable: bool,
}

/// Why a read failed, positioned as the guest's reader positions it.
#[derive(Debug, Clone, PartialEq)]
pub struct ReadError {
    pub message: String,
    pub line: i64,
    pub column: i64,
}

/// A symbol or keyword NAME, `ns/name` or `name`, split at its first slash --
/// except a lone `/`, which is a name.
fn split_name(s: &str) -> (Option<&str>, &str) {
    match s.find('/') {
        Some(i) if i > 0 && i + 1 < s.len() => (Some(&s[..i]), &s[i + 1..]),
        _ => (None, s),
    }
}

/// `text`, read as the file `name`, as `flint.forms` bytes; or the error the
/// read failed with, exactly as the guest's reader words it.
///
/// Leaves `rt`'s root stack as it found it, so one `Rt` serves any number of
/// reads -- which a wasm host needs, since its heap is never given back.
pub fn read_text(rt: &mut Rt, name: &str, text: &str, how: &ReadAs) -> Result<Vec<u8>, ReadError> {
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
        Err(ReadError { message: alloc::format!("the reader ran out of heap reading {name}"), line: 0, column: 0 })
    } else {
        // `[message line column plain]`, the reader's failure.
        let m = rt.vec_nth(rt.r(oi), 0, NIL);
        let message = rt.value_text(m);
        let num = |v: crate::value::Value| if v.is_fixnum() { v.as_fixnum() } else { 0 };
        let line = num(rt.vec_nth(rt.r(oi), 1, NIL));
        let column = num(rt.vec_nth(rt.r(oi), 2, NIL));
        Err(ReadError { message, line, column })
    };
    rt.pop_to(base);
    res
}
