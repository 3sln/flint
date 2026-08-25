//! Generic collection operations: the polymorphic dispatch that `conj`, `get`,
//! `assoc`, `nth`, `count` and friends need, plus transients for maps and sets,
//! atoms, metadata and number formatting.

use crate::hash;
use crate::map::{AM_META, HM_META, HM_ROOT};
use crate::obj::*;
use crate::rt::Rt;
use crate::seqs::C_META;
use crate::set::{S_MAP, S_META};
use crate::value::{Value, INLINE_MAX, NIL, NOT_FOUND};
use crate::vector::V_META;

/// Substring search over bytes. Naive, which is what the gas charge above is
/// priced for, and enough for the one-character separators that dominate.
fn find_bytes(h: &[u8], n: &[u8]) -> Option<usize> {
    if n.is_empty() {
        return Some(0);
    }
    if n.len() > h.len() {
        return None;
    }
    let first = n[0];
    let last = h.len() - n.len();
    let mut i = 0;
    while i <= last {
        if h[i] == first && &h[i..i + n.len()] == n {
            return Some(i);
        }
        i += 1;
    }
    None
}

impl Rt {
    // --- count -------------------------------------------------------------

    pub fn count_of(&mut self, v: Value) -> u32 {
        if v.is_nil() {
            return 0;
        }
        if self.is_string(v) {
            return self.char_count(v);
        }
        if !v.is_heap() {
            self.throw_str("UnsupportedOperationException", "count not supported on this type");
            return 0;
        }
        match ty(&self.gc.sp, v.as_heap()) {
            TY_VEC => self.vec_count(v),
            TY_MAPENTRY => 2,
            TY_ARRAYMAP | TY_HASHMAP => self.map_count(v),
            TY_SET => self.set_count(v),
            TY_TVEC => self.tvec_count(v),
            TY_TMAP => self.slot(v, 0).as_fixnum() as u32,
            TY_TSET => {
                let m = self.slot(v, 0);
                self.slot(m, 0).as_fixnum() as u32
            }
            TY_EMPTY_LIST => 0,
            _ => self.seq_count(v),
        }
    }

    /// `count` on a string is in **code points**, not UTF-16 code units.
    /// Clojure counts UTF-16, so an astral character counts 2 there and 1 here.
    /// This is a deliberate divergence, recorded in the README.
    pub fn char_count(&self, v: Value) -> u32 {
        if self.is_rope(v) {
            return self.s_count(v);
        }
        if !v.is_inline_str() && str_is_ascii(&self.gc.sp, v.as_heap()) {
            return len(&self.gc.sp, v.as_heap());
        }
        let mut buf = crate::rt::sbuf();
        let bytes: &[u8] = if v.is_inline_str() {
            v.inline_bytes(&mut buf)
        } else {
            str_bytes(&self.gc.sp, v.as_heap())
        };
        bytes.iter().filter(|b| (**b & 0xC0) != 0x80).count() as u32
    }

    /// True when a code-point index into `s` is also a byte index.
    #[inline]
    fn str_indexable(&self, s: Value) -> bool {
        if self.is_rope(s) {
            // A rope is never indexed directly -- every caller flattens first --
            // so this answers about the tier it will become.
            return self.s_ascii(s);
        }
        if s.is_inline_str() {
            let mut b = crate::rt::sbuf();
            s.inline_bytes(&mut b).is_ascii()
        } else {
            s.is_heap()
                && ty(&self.gc.sp, s.as_heap()) == TY_STR
                && str_is_ascii(&self.gc.sp, s.as_heap())
        }
    }

    // --- conj / assoc / get -------------------------------------------------

    pub fn conj(&mut self, coll: Value, x: Value) -> Value {
        if coll.is_nil() {
            let e = self.empty_list();
            return self.cons(x, e);
        }
        match ty(&self.gc.sp, coll.as_heap()) {
            TY_VEC => self.vec_conj(coll, x),
            TY_SET => self.set_conj(coll, x),
            TY_ARRAYMAP | TY_HASHMAP => {
                // conj on a map takes a map entry or a 2-element vector.
                if x.is_heap() && matches!(ty(&self.gc.sp, x.as_heap()), TY_MAPENTRY | TY_VEC) {
                    let (k, v) = (self.slot_or_nth(x, 0), self.slot_or_nth(x, 1));
                    self.map_assoc(coll, k, v)
                } else if self.is_map(x) {
                    let base = self.mark();
                    let ci = self.push(coll);
                    let mut st = ci;
                    self.map_for_each(x, &mut st, &mut |rt, k, v, ci| {
                        let nm = rt.map_assoc(rt.r(*ci), k, v);
                        rt.set_r(*ci, nm);
                    });
                    let out = self.r(ci);
                    self.pop_to(base);
                    out
                } else {
                    self.throw_str("IllegalArgumentException", "conj on a map wants a map entry")
                }
            }
            _ => self.cons(x, coll),
        }
    }

    fn slot_or_nth(&mut self, v: Value, i: u32) -> Value {
        if ty(&self.gc.sp, v.as_heap()) == TY_MAPENTRY {
            self.slot(v, i)
        } else {
            self.vec_nth(v, i).unwrap_or(NIL)
        }
    }

    pub fn assoc(&mut self, coll: Value, k: Value, v: Value) -> Value {
        if coll.is_nil() {
            let e = self.empty_map();
            return self.map_assoc(e, k, v);
        }
        match ty(&self.gc.sp, coll.as_heap()) {
            TY_ARRAYMAP | TY_HASHMAP => self.map_assoc(coll, k, v),
            TY_VEC => match self.as_i64(k) {
                Some(i) if i >= 0 && i as u32 <= self.vec_count(coll) => {
                    self.vec_assoc(coll, i as u32, v)
                }
                _ => self.throw_str("IndexOutOfBoundsException", "assoc index out of range"),
            },
            _ => self.throw_str("ClassCastException", "assoc needs an associative collection"),
        }
    }

    pub fn get(&mut self, coll: Value, k: Value, dflt: Value) -> Value {
        if coll.is_nil() {
            return dflt;
        }
        if self.is_string(coll) {
            return match self.as_i64(k) {
                Some(i) if i >= 0 => self.char_at(coll, i as u32).unwrap_or(dflt),
                _ => dflt,
            };
        }
        if !coll.is_heap() {
            return dflt;
        }
        match ty(&self.gc.sp, coll.as_heap()) {
            TY_ARRAYMAP | TY_HASHMAP => self.map_get(coll, k, dflt),
            TY_SET => self.set_get(coll, k, dflt),
            TY_VEC => match self.as_i64(k) {
                Some(i) if i >= 0 => self.vec_nth(coll, i as u32).unwrap_or(dflt),
                _ => dflt,
            },
            TY_MAPENTRY => match self.as_i64(k) {
                Some(0) => self.slot(coll, 0),
                Some(1) => self.slot(coll, 1),
                _ => dflt,
            },
            TY_TVEC => match self.as_i64(k) {
                Some(i) if i >= 0 => self.tvec_nth(coll, i as u32).unwrap_or(dflt),
                _ => dflt,
            },
            TY_TMAP => self.tmap_get(coll, k, dflt),
            TY_TSET => {
                let m = self.slot(coll, 0);
                self.tmap_get(m, k, dflt)
            }
            _ => dflt,
        }
    }

    pub fn contains(&mut self, coll: Value, k: Value) -> bool {
        if coll.is_nil() {
            return false;
        }
        match ty(&self.gc.sp, coll.as_heap()) {
            TY_ARRAYMAP | TY_HASHMAP => self.map_contains(coll, k),
            TY_SET => self.set_contains(coll, k),
            TY_VEC => match self.as_i64(k) {
                Some(i) => i >= 0 && (i as u32) < self.vec_count(coll),
                None => false,
            },
            _ => self.get(coll, k, NOT_FOUND) != NOT_FOUND,
        }
    }

    pub fn nth(&mut self, coll: Value, idx: Value, dflt: Option<Value>) -> Value {
        let i = match self.as_i64(idx) {
            Some(i) => i,
            None => return self.throw_str("IllegalArgumentException", "nth index must be an integer"),
        };
        if i < 0 {
            return match dflt {
                Some(d) => d,
                None => self.throw_str("IndexOutOfBoundsException", "negative index"),
            };
        }
        let i = i as u32;
        if coll.is_nil() {
            return dflt.unwrap_or(NIL);
        }
        if self.is_string(coll) {
            return match self.char_at(coll, i) {
                Some(c) => c,
                None => match dflt {
                    Some(d) => d,
                    None => self.throw_str("IndexOutOfBoundsException", "string index out of range"),
                },
            };
        }
        if coll.is_heap() && ty(&self.gc.sp, coll.as_heap()) == TY_VEC {
            return match self.vec_nth(coll, i) {
                Some(v) => v,
                None => match dflt {
                    Some(d) => d,
                    None => self.throw_str("IndexOutOfBoundsException", "index out of range"),
                },
            };
        }
        // Walk. O(n), as it is in Clojure for a seq.
        let base = self.mark();
        let s = self.seq(coll);
        let si = self.push(s);
        let mut k = 0u32;
        let out = loop {
            if self.r(si).is_nil() {
                break match dflt {
                    Some(d) => d,
                    None => self.throw_str("IndexOutOfBoundsException", "index out of range"),
                };
            }
            if k == i {
                break self.first(self.r(si));
            }
            let n = self.next(self.r(si));
            self.set_r(si, n);
            k += 1;
        };
        self.pop_to(base);
        out
    }

    pub fn pop_of(&mut self, coll: Value) -> Value {
        if coll.is_nil() {
            return self.throw_str("IllegalStateException", "cannot pop nil");
        }
        match ty(&self.gc.sp, coll.as_heap()) {
            TY_VEC => {
                if self.vec_count(coll) == 0 {
                    self.throw_str("IllegalStateException", "cannot pop an empty vector")
                } else {
                    self.vec_pop(coll)
                }
            }
            TY_EMPTY_LIST => self.throw_str("IllegalStateException", "cannot pop an empty list"),
            _ => self.rest(coll),
        }
    }

    pub fn peek_of(&mut self, coll: Value) -> Value {
        if coll.is_nil() {
            return NIL;
        }
        match ty(&self.gc.sp, coll.as_heap()) {
            TY_VEC => {
                let n = self.vec_count(coll);
                if n == 0 {
                    NIL
                } else {
                    self.vec_nth(coll, n - 1).unwrap_or(NIL)
                }
            }
            _ => self.first(coll),
        }
    }

    pub fn empty_of(&mut self, coll: Value) -> Value {
        if !coll.is_heap() {
            return NIL;
        }
        match ty(&self.gc.sp, coll.as_heap()) {
            TY_VEC => self.empty_vec(),
            TY_ARRAYMAP | TY_HASHMAP => self.empty_map(),
            TY_SET => self.empty_set(),
            _ => self.empty_list(),
        }
    }

    // --- transients ---------------------------------------------------------

    pub fn to_transient(&mut self, v: Value) -> Value {
        if !v.is_heap() {
            return self.throw_str("ClassCastException", "not transientable");
        }
        match ty(&self.gc.sp, v.as_heap()) {
            TY_VEC => self.vec_transient(v),
            TY_ARRAYMAP | TY_HASHMAP => self.map_transient(v),
            TY_SET => self.set_transient(v),
            _ => self.throw_str("ClassCastException", "not transientable"),
        }
    }

    pub fn to_persistent(&mut self, v: Value) -> Value {
        if !v.is_heap() {
            return self.throw_str("ClassCastException", "not a transient");
        }
        match ty(&self.gc.sp, v.as_heap()) {
            TY_TVEC => self.tvec_persistent(v),
            TY_TMAP => self.tmap_persistent(v),
            TY_TSET => self.tset_persistent(v),
            _ => self.throw_str("ClassCastException", "not a transient"),
        }
    }

    pub fn transient_conj(&mut self, t: Value, x: Value) -> Value {
        if !t.is_heap() {
            return self.throw_str("ClassCastException", "not a transient");
        }
        match ty(&self.gc.sp, t.as_heap()) {
            TY_TVEC => self.tvec_conj(t, x),
            TY_TSET => self.tset_conj(t, x),
            TY_TMAP => {
                if x.is_heap() && matches!(ty(&self.gc.sp, x.as_heap()), TY_MAPENTRY | TY_VEC) {
                    let (k, v) = (self.slot_or_nth(x, 0), self.slot_or_nth(x, 1));
                    self.tmap_assoc(t, k, v)
                } else {
                    self.throw_str("IllegalArgumentException", "conj! on a map wants a map entry")
                }
            }
            _ => self.throw_str("ClassCastException", "not a transient"),
        }
    }

    pub fn transient_assoc(&mut self, t: Value, k: Value, v: Value) -> Value {
        if !t.is_heap() {
            return self.throw_str("ClassCastException", "not a transient");
        }
        match ty(&self.gc.sp, t.as_heap()) {
            TY_TMAP => self.tmap_assoc(t, k, v),
            TY_TVEC => match self.as_i64(k) {
                Some(i) if i >= 0 => self.tvec_assoc(t, i as u32, v),
                _ => self.throw_str("IndexOutOfBoundsException", "assoc! index out of range"),
            },
            _ => self.throw_str("ClassCastException", "not an associative transient"),
        }
    }

    pub fn transient_dissoc(&mut self, t: Value, k: Value) -> Value {
        if t.is_heap() && ty(&self.gc.sp, t.as_heap()) == TY_TMAP {
            self.tmap_dissoc(t, k)
        } else if t.is_heap() && ty(&self.gc.sp, t.as_heap()) == TY_TSET {
            self.tset_disj(t, k)
        } else {
            self.throw_str("ClassCastException", "not a transient map")
        }
    }

    // --- atoms ---------------------------------------------------------------

    pub fn new_atom(&mut self, v: Value) -> Value {
        let base = self.mark();
        let vi = self.push(v);
        let a = self.alloc(TY_ATOM, 2);
        if a == 0 {
            self.pop_to(base);
            return NIL;
        }
        let v = self.r(vi);
        self.pop_to(base);
        self.gc.set_slot(a, 0, v);
        self.gc.set_slot(a, 1, NIL);
        Value::heap(a)
    }

    pub fn deref(&mut self, v: Value) -> Value {
        if v.is_heap() {
            match ty(&self.gc.sp, v.as_heap()) {
                TY_ATOM | TY_VOLATILE => return self.slot(v, 0),
                TY_DELAY => {
                    let thunk = self.slot(v, 0);
                    if thunk.is_nil() {
                        return self.slot(v, 1);
                    }
                    let r = self.invoke(thunk, &[]);
                    if self.failed() {
                        return NIL;
                    }
                    self.set(v, 0, NIL);
                    self.set(v, 1, r);
                    return r;
                }
                _ => {}
            }
        }
        self.throw_str("ClassCastException", "cannot deref this value")
    }

    pub fn reset_atom(&mut self, at: Value, v: Value) -> Value {
        if at.is_heap() && matches!(ty(&self.gc.sp, at.as_heap()), TY_ATOM | TY_VOLATILE) {
            self.set(at, 0, v);
            v
        } else {
            self.throw_str("ClassCastException", "not an atom")
        }
    }

    // --- metadata -------------------------------------------------------------

    fn meta_slot(&self, v: Value) -> Option<u32> {
        if !v.is_heap() {
            return None;
        }
        match ty(&self.gc.sp, v.as_heap()) {
            TY_SYM => Some(2),
            TY_VEC => Some(V_META),
            TY_ARRAYMAP => Some(AM_META),
            TY_HASHMAP => Some(HM_META),
            TY_SET => Some(S_META),
            TY_CONS => Some(C_META),
            TY_EMPTY_LIST => Some(0),
            TY_LAZYSEQ => Some(2),
            TY_ATOM => Some(1),
            _ => None,
        }
    }

    pub fn meta_of(&self, v: Value) -> Value {
        match self.meta_slot(v) {
            Some(i) => self.slot(v, i),
            None => NIL,
        }
    }

    /// Copy the object with new metadata. Metadata is not part of equality, so
    /// the copy is still `=` to the original.
    pub fn with_meta(&mut self, v: Value, m: Value) -> Value {
        let idx = match self.meta_slot(v) {
            Some(i) => i,
            None => return v,
        };
        let base = self.mark();
        let vi = self.push(v);
        let mi = self.push(m);
        let t = ty(&self.gc.sp, v.as_heap());
        let n = self.olen(v);
        let a = self.alloc(t, n);
        if a == 0 {
            self.pop_to(base);
            return NIL;
        }
        let (v, m) = (self.r(vi), self.r(mi));
        for i in 0..n {
            let s = self.slot(v, i);
            self.gc.set_slot(a, i, s);
        }
        self.gc.set_slot(a, idx, m);
        self.pop_to(base);
        Value::heap(a)
    }

    // --- strings ---------------------------------------------------------------

    pub fn str_concat2(&mut self, x: Value, y: Value) -> Value {
        if !self.is_string(x) || !self.is_string(y) {
            return self.throw_str("ClassCastException", "not a string");
        }
        // A tree join, not a copy (`doc/decisions/0011`). Small results still
        // copy into a flat string -- `s_concat` decides -- because below the
        // threshold the metadata costs more than the copy it saves.
        //
        // Gas is charged for the bytes only when they are actually moved, which
        // is what makes repeated concatenation linear in gas as well as in time.
        self.s_concat(x, y)
    }

    /// The one-character string at code-point index `i`.
    pub fn char_at(&mut self, s: Value, i: u32) -> Option<Value> {
        let s = self.string_arg(s);
        // ASCII fast path: byte index == code-point index, so this is O(1).
        if self.str_indexable(s) {
            let n = self.str_len(s);
            if i >= n {
                return None;
            }
            let b = if s.is_inline_str() {
                let mut t = crate::rt::sbuf();
                s.inline_bytes(&mut t)[i as usize]
            } else {
                self.gc.sp.read_u8(s.as_heap() + STR_DATA + i)
            };
            return Some(Value::inline_str(&[b]));
        }
        let mut buf = crate::rt::sbuf();
        let owned: alloc::string::String = {
            let b: &[u8] = if s.is_inline_str() {
                s.inline_bytes(&mut buf)
            } else {
                str_bytes(&self.gc.sp, s.as_heap())
            };
            match core::str::from_utf8(b).ok()?.chars().nth(i as usize) {
                Some(c) => {
                    let mut t = alloc::string::String::new();
                    t.push(c);
                    t
                }
                None => return None,
            }
        };
        Some(Value::inline_str(owned.as_bytes()))
    }

    pub fn code_point_at(&mut self, s: Value, i: Value) -> Value {
        let s = self.string_arg(s);
        let idx = match self.as_i64(i) {
            Some(n) if n >= 0 => n as usize,
            _ => return self.throw_str("IndexOutOfBoundsException", "bad index"),
        };
        if self.str_indexable(s) {
            if (idx as u32) >= self.str_len(s) {
                return self.throw_str("IndexOutOfBoundsException", "string index out of range");
            }
            let b = if s.is_inline_str() {
                let mut t = crate::rt::sbuf();
                s.inline_bytes(&mut t)[idx]
            } else {
                self.gc.sp.read_u8(s.as_heap() + STR_DATA + idx as u32)
            };
            return Value::fixnum(b as i64);
        }
        let mut buf = crate::rt::sbuf();
        let cp = {
            let b: &[u8] = if s.is_inline_str() {
                s.inline_bytes(&mut buf)
            } else {
                str_bytes(&self.gc.sp, s.as_heap())
            };
            core::str::from_utf8(b).ok().and_then(|t| t.chars().nth(idx)).map(|c| c as u32)
        };
        match cp {
            Some(c) => Value::fixnum(c as i64),
            None => self.throw_str("IndexOutOfBoundsException", "string index out of range"),
        }
    }

    /// `subs`, in code points.
    pub fn substring(&mut self, s: Value, start: i64, end: Option<i64>) -> Value {
        let s = self.string_arg(s);
        // The slice, not the source. Charging the whole string per call made
        // the COUNTER quadratic for splitting -- n slices of an n-byte string is
        // n^2 gas for n bytes of copying -- which is the same defect as
        // `str_index_of`'s charge, in the same shape, one function along.
        // `test/scaling.clj` found it on its first run.
        let n = if self.is_string(s) { self.str_len(s) } else { 0 };
        let took = end
            .map(|e| (e - start).max(0) as u32)
            .unwrap_or_else(|| n.saturating_sub(start.max(0) as u32));
        self.charge_bytes(took.min(n));
        if self.str_indexable(s) {
            let n = self.str_len(s) as i64;
            let e = end.unwrap_or(n);
            if start < 0 || e > n || start > e {
                return self.throw_str("StringIndexOutOfBoundsException", "bad substring range");
            }
            let owned: alloc::string::String = {
                let mut t = crate::rt::sbuf();
                let bytes: &[u8] = if s.is_inline_str() {
                    s.inline_bytes(&mut t)
                } else {
                    str_bytes(&self.gc.sp, s.as_heap())
                };
                core::str::from_utf8(&bytes[start as usize..e as usize]).unwrap_or("").into()
            };
            return self.string(&owned);
        }
        let mut buf = crate::rt::sbuf();
        let owned = {
            let b: &[u8] = if s.is_inline_str() {
                s.inline_bytes(&mut buf)
            } else if s.is_heap() && ty(&self.gc.sp, s.as_heap()) == TY_STR {
                str_bytes(&self.gc.sp, s.as_heap())
            } else {
                return self.throw_str("ClassCastException", "not a string");
            };
            let t = core::str::from_utf8(b).unwrap_or("");
            let n = t.chars().count() as i64;
            let e = end.unwrap_or(n);
            if start < 0 || e > n || start > e {
                return self.throw_str("StringIndexOutOfBoundsException", "bad substring range");
            }
            let out: alloc::string::String =
                t.chars().skip(start as usize).take((e - start) as usize).collect();
            out
        };
        self.string(&owned)
    }

    pub fn keyword_from_values(&mut self, ns: Value, name: Value) -> Value {
        let mut bn = crate::rt::sbuf();
        let mut bm = crate::rt::sbuf();
        let owned_ns: Option<alloc::string::String> = if ns.is_nil() {
            None
        } else {
            Some(self.as_str(ns, &mut bn).unwrap_or("").into())
        };
        let owned_name: alloc::string::String = if self.is_string(name) {
            self.as_str(name, &mut bm).unwrap_or("").into()
        } else if self.is_keyword(name) || self.is_symbol(name) {
            let n = self.name_of(name);
            let mut b2 = crate::rt::sbuf();
            self.as_str(n, &mut b2).unwrap_or("").into()
        } else {
            return NIL;
        };
        self.keyword(owned_ns.as_deref(), &owned_name)
    }

    pub fn symbol_from_values(&mut self, ns: Value, name: Value) -> Value {
        let mut bn = crate::rt::sbuf();
        let mut bm = crate::rt::sbuf();
        let owned_ns: Option<alloc::string::String> = if ns.is_nil() {
            None
        } else {
            Some(self.as_str(ns, &mut bn).unwrap_or("").into())
        };
        let owned_name: alloc::string::String = if self.is_string(name) {
            self.as_str(name, &mut bm).unwrap_or("").into()
        } else if self.is_symbol(name) || self.is_keyword(name) {
            let n = self.name_of(name);
            let mut b2 = crate::rt::sbuf();
            self.as_str(n, &mut b2).unwrap_or("").into()
        } else {
            return NIL;
        };
        self.symbol(owned_ns.as_deref(), &owned_name)
    }

    // --- numbers to and from text ---------------------------------------------

    /// Clojure-compatible rendering: integers plain, doubles always with a
    /// fractional part or exponent, and the three special doubles as `##Inf`,
    /// `##-Inf` and `##NaN`.
    pub fn number_to_string(&mut self, v: Value) -> Value {
        if let Some(n) = self.as_i64(v) {
            let mut buf = [0u8; 24];
            let s = fmt_i64(n, &mut buf);
            return self.string(s);
        }
        if !v.is_double() {
            return self.throw_str("ClassCastException", "not a number");
        }
        let d = v.as_f64();
        if d.is_nan() {
            return self.string("##NaN");
        }
        if d.is_infinite() {
            return self.string(if d > 0.0 { "##Inf" } else { "##-Inf" });
        }
        let mut s = alloc::format!("{}", d);
        if !s.contains('.') && !s.contains('e') && !s.contains('E') {
            s.push_str(".0");
        }
        self.string(&s)
    }

    pub fn string_to_number(&mut self, v: Value) -> Value {
        let mut buf = crate::rt::sbuf();
        let owned: alloc::string::String = match self.as_str(v, &mut buf) {
            Some(s) => s.into(),
            None => return NIL,
        };
        let t = owned.trim();
        if t.is_empty() {
            return NIL;
        }
        if let Ok(n) = t.parse::<i64>() {
            return self.integer(n);
        }
        // Hex/octal/binary literals, as the reader needs them.
        if let Some(rest) = t.strip_prefix("0x").or_else(|| t.strip_prefix("0X")) {
            if let Ok(n) = i64::from_str_radix(rest, 16) {
                return self.integer(n);
            }
        }
        match t {
            "##Inf" => return Value::from_f64(f64::INFINITY),
            "##-Inf" => return Value::from_f64(f64::NEG_INFINITY),
            "##NaN" => return Value::from_f64(f64::NAN),
            _ => {}
        }
        match t.parse::<f64>() {
            Ok(d) => Value::from_f64(d),
            Err(_) => NIL,
        }
    }

    /// Concatenate a collection of strings in one pass. Without this, building
    /// a string by repeated `str` is quadratic, which shows up immediately in
    /// the reader when the compiler compiles itself.
    pub fn join_strings(&mut self, coll: Value) -> Value {
        let base = self.mark();
        let s = self.seq(coll);
        let si = self.push(s);
        let mut out = alloc::string::String::new();
        while !self.r(si).is_nil() {
            let x = self.first(self.r(si));
            let mut b = crate::rt::sbuf();
            match self.as_str(x, &mut b) {
                Some(t) => out.push_str(t),
                None => {
                    self.pop_to(base);
                    return self.throw_str("ClassCastException", "str-join wants strings");
                }
            }
            let nx = self.next(self.r(si));
            self.set_r(si, nx);
        }
        self.pop_to(base);
        self.string(&out)
    }

    /// Byte offset -> code-point index search. Returns nil when absent.
    pub fn str_index_of(&mut self, haystack: Value, needle: Value, from: i64) -> Value {
        let haystack = self.string_arg(haystack);
        let needle = self.string_arg(needle);
        // A naive search is O(haystack x needle); charging the haystack keeps a
        // long scan from being free.
        let hn = if self.is_string(haystack) { self.str_len(haystack) } else { 0 };
        // Charged AFTER the search, for the distance actually scanned -- see
        // below. Charging the whole haystack made the counter quadratic;
        // charging what remained after `from` still did, because a scan that
        // stops at the first match walks a few bytes and was billed for the
        // rest of the string. Same defect, third variation, same session.
        // The header already carries this bit (`str_is_ascii`), set once when
        // the string was built. Asking `&str::is_ascii()` instead rescanned the
        // WHOLE haystack on every call, which made `str/split` quadratic: 6 800
        // calls over a 32 799-character corpus is 223 million byte checks, and
        // it was 37 ms of a 55 ms benchmark. The comment four lines down
        // claimed the search was O(n) rather than O(n) per position; this is
        // what made that true.
        let ascii = self.str_indexable(haystack);
        let mut bh = crate::rt::sbuf();
        let mut bn = crate::rt::sbuf();
        let found = {
            let hb: &[u8] = if haystack.is_inline_str() {
                haystack.inline_bytes(&mut bh)
            } else if haystack.is_heap() && ty(&self.gc.sp, haystack.as_heap()) == TY_STR {
                str_bytes(&self.gc.sp, haystack.as_heap())
            } else {
                return self.throw_str("ClassCastException", "not a string");
            };
            let nb: &[u8] = if needle.is_inline_str() {
                needle.inline_bytes(&mut bn)
            } else if needle.is_heap() && ty(&self.gc.sp, needle.as_heap()) == TY_STR {
                str_bytes(&self.gc.sp, needle.as_heap())
            } else {
                return self.throw_str("ClassCastException", "not a string");
            };
            // `from` is a code-point index, and so is the answer. For ASCII
            // those are byte offsets, so the search is over BYTES and never
            // needs a `&str` -- which matters because `from_utf8` validates the
            // whole haystack, and doing that per call made `str/split`
            // quadratic a second time after the `is_ascii` rescan was removed.
            // 6 800 calls over a 32 799-byte corpus is 223 million bytes
            // validated to find 6 800 spaces.
            let skip = from.max(0) as usize;
            if ascii {
                if skip > hb.len() {
                    None
                } else {
                    find_bytes(&hb[skip..], nb).map(|b| skip + b)
                }
            } else {
                // Only here is a `&str` needed at all, because only here do
                // byte offsets and code-point indices differ.
                let h = core::str::from_utf8(hb).unwrap_or("");
                let nd = core::str::from_utf8(nb).unwrap_or("");
                let start_byte = h.char_indices().nth(skip).map(|(i, _)| i).unwrap_or(h.len());
                h[start_byte..].find(nd).map(|b| h[..start_byte + b].chars().count())
            }
        };
        let skip = from.max(0) as u32;
        let scanned = match found {
            Some(i) => (i as u32).saturating_sub(skip) + 1,
            None => hn.saturating_sub(skip),
        };
        self.charge_bytes(scanned);
        match found {
            Some(i) => Value::fixnum(i as i64),
            None => NIL,
        }
    }

    /// The UTF-8 bytes of a string, as a vector of integers. The image writer
    /// needs this when the compiler is hosted on flint.
    pub fn string_bytes_vector(&mut self, s: Value) -> Value {
        let s = self.string_arg(s);
        let n = if self.is_string(s) { self.str_len(s) } else { 0 };
        self.charge_work(n as u64);
        let mut buf = crate::rt::sbuf();
        let owned: alloc::vec::Vec<u8> = {
            let b: &[u8] = if s.is_inline_str() {
                s.inline_bytes(&mut buf)
            } else if s.is_heap() && ty(&self.gc.sp, s.as_heap()) == TY_STR {
                str_bytes(&self.gc.sp, s.as_heap())
            } else {
                return self.throw_str("ClassCastException", "not a string");
            };
            b.to_vec()
        };
        let base = self.mark();
        let mut v = self.empty_vec();
        let vi = self.push(v);
        for byte in owned {
            let nv = self.vec_conj(self.r(vi), Value::fixnum(byte as i64));
            self.set_r(vi, nv);
        }
        v = self.r(vi);
        self.pop_to(base);
        v
    }

    /// An array-map built from a flat k,v collection, preserving order and not
    /// promoting whatever its size.
    pub fn ordered_map(&mut self, kvs: Value) -> Value {
        let base = self.mark();
        let s = self.seq(kvs);
        let si = self.push(s);
        let mut flat: alloc::vec::Vec<Value> = alloc::vec::Vec::new();
        while !self.r(si).is_nil() {
            let x = self.first(self.r(si));
            flat.push(x);
            let nx = self.next(self.r(si));
            self.set_r(si, nx);
        }
        if flat.len() % 2 != 0 {
            self.pop_to(base);
            return self.throw_str("IllegalArgumentException", "array-map needs an even number of forms");
        }
        for v in &flat {
            self.push(*v);
        }
        let vals_at = self.mark() - flat.len();
        let n = (flat.len() / 2) as u32;
        let a = self.alloc(TY_ARRAYMAP, crate::map::AM_BASE + 2 * n);
        if a == 0 {
            self.pop_to(base);
            return NIL;
        }
        let m = Value::heap(a);
        self.gc.set_slot(a, crate::map::AM_META, NIL);
        self.gc.set_slot(a, crate::map::AM_HASH, NIL);
        for i in 0..(2 * n) as usize {
            let v = self.r(vals_at + i);
            self.gc.set_slot(a, crate::map::AM_BASE + i as u32, v);
        }
        self.pop_to(base);
        m
    }

    pub fn new_volatile(&mut self, v: Value) -> Value {
        let base = self.mark();
        let vi = self.push(v);
        let a = self.alloc(TY_VOLATILE, 1);
        if a == 0 { self.pop_to(base); return NIL; }
        let v = self.r(vi);
        self.pop_to(base);
        self.gc.set_slot(a, 0, v);
        Value::heap(a)
    }

    // --- diagnostics ------------------------------------------------------------

    pub fn gc_stats_map(&mut self) -> Value {
        let s = self.gc.stats;
        let young = self.gc.young_used();
        let pairs: [(&str, i64); 8] = [
            ("minor", s.minor as i64),
            ("major", s.major as i64),
            ("bytes-allocated", s.bytes_allocated as i64),
            ("bytes-copied", s.bytes_copied as i64),
            ("bytes-promoted", s.bytes_promoted as i64),
            ("young-used", young as i64),
            ("old-live", self.gc.old_live() as i64),
            ("old-capacity", self.gc.old_capacity() as i64),
        ];
        let base = self.mark();
        let m = self.empty_map();
        let mi = self.push(m);
        for (k, v) in pairs {
            let kv = self.keyword(None, k);
            let ki = self.push(kv);
            let vv = self.integer(v);
            let kv = self.r(ki);
            self.pop_to(ki);
            let nm = self.map_assoc(self.r(mi), kv, vv);
            self.set_r(mi, nm);
        }
        let out = self.r(mi);
        self.pop_to(base);
        out
    }
}

fn fmt_i64(mut n: i64, buf: &mut [u8; 24]) -> &str {
    if n == 0 {
        buf[0] = b'0';
        return core::str::from_utf8(&buf[..1]).unwrap();
    }
    let neg = n < 0;
    let mut i = buf.len();
    // Work in the negative domain so i64::MIN does not overflow.
    if !neg {
        n = -n;
    }
    while n != 0 {
        i -= 1;
        buf[i] = b'0' + ((-(n % 10)) as u8);
        n /= 10;
    }
    if neg {
        i -= 1;
        buf[i] = b'-';
    }
    let len = buf.len() - i;
    buf.copy_within(i.., 0);
    core::str::from_utf8(&buf[..len]).unwrap()
}

// --- transient maps and sets -------------------------------------------------

impl Rt {
    pub fn map_transient(&mut self, m: Value) -> Value {
        let base = self.mark();
        let mi = self.push(m);
        // An array-map becomes a CHAMP first: one transient implementation, and
        // the workload that uses transients is the one with many entries.
        let hm = if self.is_array_map(m) { self.array_map_to_hash(m) } else { m };
        let hi = self.push(hm);
        let edit = self.new_edit_token();
        let ei = self.push(edit);
        let a = self.alloc(TY_TMAP, 3);
        if a == 0 {
            self.pop_to(base);
            return NIL;
        }
        let hm = self.r(hi);
        let cnt = self.map_count(hm);
        let root = self.slot(hm, HM_ROOT);
        let edit = self.r(ei);
        self.pop_to(base);
        let _ = mi;
        self.gc.set_slot(a, 0, Value::fixnum(cnt as i64));
        self.gc.set_slot(a, 1, root);
        self.gc.set_slot(a, 2, edit);
        Value::heap(a)
    }

    pub fn tmap_get(&mut self, t: Value, k: Value, dflt: Value) -> Value {
        if !self.eq_may_alloc(k) {
            let root = self.slot(t, 1);
            if root.is_nil() {
                return dflt;
            }
            let h = self.hash_value(k);
            let r = self.champ_find(root, h, k);
            return if r == NOT_FOUND { dflt } else { r };
        }
        let base = self.mark();
        let ti = self.push(t);
        let ki = self.push(k);
        let di = self.push(dflt);
        // Hash before reading the root: hashing a compound key allocates.
        let h = self.hash_value(self.r(ki));
        let root = self.slot(self.r(ti), 1);
        if root.is_nil() {
            let d = self.r(di);
            self.pop_to(base);
            return d;
        }
        let r = self.champ_find(root, h, self.r(ki));
        let dflt = self.r(di);
        self.pop_to(base);
        if r == NOT_FOUND {
            dflt
        } else {
            r
        }
    }

    pub fn tmap_assoc(&mut self, t: Value, k: Value, v: Value) -> Value {
        let edit = self.slot(t, 2);
        if edit.is_nil() {
            return self.throw_str("IllegalStateException", "transient used after persistent!");
        }
        let base = self.mark();
        let ti = self.push(t);
        let ki = self.push(k);
        let vi = self.push(v);
        let ei = self.push(edit);
        let h = self.hash_value(self.r(ki));
        let root = self.slot(self.r(ti), 1);
        let ri = self.push(root);
        self.champ_added = false;
        let nr = self.champ_assoc(self.r(ri), h, self.r(ki), self.r(vi), self.r(ei));
        let added = self.champ_added;
        let t = self.r(ti);
        self.set(t, 1, nr);
        if added {
            let c = self.slot(t, 0).as_fixnum();
            self.set(t, 0, Value::fixnum(c + 1));
        }
        self.pop_to(base);
        t
    }

    pub fn tmap_dissoc(&mut self, t: Value, k: Value) -> Value {
        let edit = self.slot(t, 2);
        if edit.is_nil() {
            return self.throw_str("IllegalStateException", "transient used after persistent!");
        }
        let base = self.mark();
        let ti = self.push(t);
        let ki = self.push(k);
        let ei = self.push(edit);
        let h = self.hash_value(self.r(ki));
        let root = self.slot(self.r(ti), 1);
        let ri = self.push(root);
        self.champ_added = false;
        let nr = self.champ_dissoc(self.r(ri), h, self.r(ki), self.r(ei));
        let removed = self.champ_added;
        let t = self.r(ti);
        self.set(t, 1, nr);
        if removed {
            let c = self.slot(t, 0).as_fixnum();
            self.set(t, 0, Value::fixnum(c - 1));
        }
        self.pop_to(base);
        t
    }

    pub fn tmap_persistent(&mut self, t: Value) -> Value {
        let base = self.mark();
        let ti = self.push(t);
        let cnt = self.slot(t, 0).as_fixnum() as u32;
        let root = self.slot(t, 1);
        let ri = self.push(root);
        let tv = self.r(ti);
        self.set(tv, 2, NIL); // invalidate
        let root = self.r(ri);
        let out = if cnt == 0 { self.empty_map() } else { self.champ_wrap(cnt, root) };
        self.pop_to(base);
        out
    }

    pub fn set_transient(&mut self, s: Value) -> Value {
        let base = self.mark();
        let m = self.slot(s, S_MAP);
        let tm = self.map_transient(m);
        let ti = self.push(tm);
        let a = self.alloc(TY_TSET, 2);
        if a == 0 {
            self.pop_to(base);
            return NIL;
        }
        let tm = self.r(ti);
        let edit = self.slot(tm, 2);
        self.pop_to(base);
        self.gc.set_slot(a, 0, tm);
        self.gc.set_slot(a, 1, edit);
        Value::heap(a)
    }

    pub fn tset_conj(&mut self, t: Value, x: Value) -> Value {
        let m = self.slot(t, 0);
        let base = self.mark();
        let ti = self.push(t);
        let xi = self.push(x);
        let (a, b) = (self.r(xi), self.r(xi));
        let _ = self.tmap_assoc(m, a, b);
        let out = self.r(ti);
        self.pop_to(base);
        out
    }

    pub fn tset_disj(&mut self, t: Value, x: Value) -> Value {
        let m = self.slot(t, 0);
        let base = self.mark();
        let ti = self.push(t);
        let xi = self.push(x);
        let x = self.r(xi);
        let _ = self.tmap_dissoc(m, x);
        let out = self.r(ti);
        self.pop_to(base);
        out
    }

    pub fn tset_persistent(&mut self, t: Value) -> Value {
        let base = self.mark();
        let m = self.slot(t, 0);
        let pm = self.tmap_persistent(m);
        let pi = self.push(pm);
        let tv = self.r(pi);
        self.pop_to(base);
        self.set_from_map(tv)
    }
}

impl Rt {
    pub fn hash_of_str(&mut self, s: &str) -> u32 {
        hash::hash_string(s)
    }
}

/// A scratch buffer type used by string builtins.
pub const SBUF_LEN: usize = INLINE_MAX;
