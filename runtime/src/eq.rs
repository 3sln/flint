//! `=`, `hash`, and `compare`.
//!
//! `=` is Clojure's, including the parts people trip over: `(= 1 1.0)` is false
//! because integers and floats are different equality partitions, `(= [1 2]
//! '(1 2))` is true because both are sequential, `(= "ab" [\a \b])` is false
//! because a string is a scalar here even though it is seqable, and NaN is not
//! equal to itself.

use crate::hash;
use crate::obj::*;
use crate::rt::Rt;
use crate::strs::INTERN_MAX;
use crate::value::{Value, FALSE, NIL, TRUE};

pub const CAT_SCALAR: u8 = 0;
pub const CAT_SEQUENTIAL: u8 = 1;
pub const CAT_MAP: u8 = 2;
pub const CAT_SET: u8 = 3;

impl Rt {
    pub fn category(&self, v: Value) -> u8 {
        if !v.is_heap() {
            return CAT_SCALAR;
        }
        match ty(&self.gc.sp, v.as_heap()) {
            TY_CONS | TY_EMPTY_LIST | TY_LAZYSEQ | TY_VECSEQ | TY_STRSEQ | TY_RANGE | TY_VEC
            | TY_MAPENTRY => CAT_SEQUENTIAL,
            TY_ARRAYMAP | TY_HASHMAP => CAT_MAP,
            TY_SET => CAT_SET,
            _ => CAT_SCALAR,
        }
    }

    pub fn is_sequential(&self, v: Value) -> bool {
        self.category(v) == CAT_SEQUENTIAL
    }

    pub fn eq(&mut self, a: Value, b: Value) -> bool {
        // Doubles first: bit equality would wrongly make NaN equal to itself,
        // and would wrongly separate 0.0 from -0.0.
        if a.is_double() || b.is_double() {
            return a.is_double() && b.is_double() && a.as_f64() == b.as_f64();
        }
        if a.0 == b.0 {
            return true;
        }
        if self.is_int(a) || self.is_int(b) {
            // Integers are canonical, so the only way two are equal without
            // being bit-equal is two distinct boxes.
            return match (self.as_i64(a), self.as_i64(b)) {
                (Some(x), Some(y)) => x == y,
                _ => false,
            };
        }
        if !a.is_heap() || !b.is_heap() {
            // Immediates are canonical: an inline string can only equal another
            // inline string, and that would have been bit equality.
            return false;
        }
        let (ca, cb) = (self.category(a), self.category(b));
        if ca != cb {
            return false;
        }
        match ca {
            CAT_SEQUENTIAL => self.seq_eq(a, b),
            CAT_MAP => self.map_eq(a, b),
            CAT_SET => self.set_eq(a, b),
            _ => {
                let (ta, tb) = (ty(&self.gc.sp, a.as_heap()), ty(&self.gc.sp, b.as_heap()));
                if ta != tb {
                    return false;
                }
                match ta {
                    TY_STR => {
                        let (la, lb) = (len(&self.gc.sp, a.as_heap()), len(&self.gc.sp, b.as_heap()));
                        if la != lb {
                            return false;
                        }
                        // Both interned and not bit-equal means not equal, with
                        // no need to look at the bytes at all.
                        if la <= INTERN_MAX {
                            return false;
                        }
                        str_bytes(&self.gc.sp, a.as_heap()) == str_bytes(&self.gc.sp, b.as_heap())
                    }
                    // Symbols compare by (ns, name): with-meta makes a distinct
                    // object that must still be `=`.
                    TY_SYM => {
                        self.slot(a, 0) == self.slot(b, 0) && self.slot(a, 1) == self.slot(b, 1)
                    }
                    _ => false,
                }
            }
        }
    }

    fn seq_eq(&mut self, a: Value, b: Value) -> bool {
        // Both counted and cheap? Then a length mismatch is an early out.
        if self.is_vector(a) && self.is_vector(b) && self.vec_count(a) != self.vec_count(b) {
            return false;
        }
        let base = self.mark();
        let sa = self.seq(a);
        let ia = self.push(sa);
        let sb = self.seq(b);
        let ib = self.push(sb);
        let result = loop {
            let (x, y) = (self.r(ia), self.r(ib));
            if x.is_nil() || y.is_nil() {
                break x.is_nil() && y.is_nil();
            }
            let fa = self.first(x);
            let fi = self.push(fa);
            let fb = self.first(self.r(ib));
            let fa = self.r(fi);
            self.pop_to(fi);
            if !self.eq(fa, fb) {
                break false;
            }
            let na = self.next(self.r(ia));
            self.set_r(ia, na);
            let nb = self.next(self.r(ib));
            self.set_r(ib, nb);
        };
        self.pop_to(base);
        result
    }

    // --- hashing -----------------------------------------------------------

    pub fn hash_value(&mut self, v: Value) -> u32 {
        if v.is_double() {
            return hash::hash_double(v.as_f64());
        }
        if v.is_nil() {
            return 0;
        }
        if v.is_true() {
            return hash::HASH_TRUE;
        }
        if v.is_false() {
            return hash::HASH_FALSE;
        }
        if v.is_fixnum() {
            return hash::hash_long(v.as_fixnum());
        }
        if v.is_inline_str() {
            return self.string_hash(v);
        }
        if v.is_inline_kw() {
            return self.keyword_hash(v);
        }
        if !v.is_heap() {
            return 0;
        }
        match ty(&self.gc.sp, v.as_heap()) {
            TY_STR => self.string_hash(v),
            TY_KW => self.keyword_hash(v),
            TY_SYM => self.symbol_hash(v),
            TY_BIGINT => hash::hash_long(self.as_i64(v).unwrap_or(0)),
            TY_VEC | TY_MAPENTRY | TY_CONS | TY_EMPTY_LIST | TY_LAZYSEQ | TY_VECSEQ | TY_STRSEQ
            | TY_RANGE => self.hash_ordered(v),
            TY_ARRAYMAP | TY_HASHMAP => self.hash_map(v),
            TY_SET => self.hash_set(v),
            // Functions, atoms, vars and the like are only ever `=` to
            // themselves, so a per-type constant is a correct (if unhelpful)
            // hash. A moving collector rules out using the address.
            t => hash::hash_int(0x51ed_0000 | t as u32),
        }
    }

    pub fn hash_ordered(&mut self, v: Value) -> u32 {
        if self.is_vector(v) {
            let cached = self.slot(v, crate::vector::V_HASH);
            if cached.is_fixnum() {
                return cached.as_fixnum() as i32 as u32;
            }
        }
        let base = self.mark();
        let s = self.seq(v);
        let si = self.push(s);
        let mut acc = 1u32;
        let mut n = 0u32;
        while !self.r(si).is_nil() {
            let f = self.first(self.r(si));
            let h = self.hash_value(f);
            acc = hash::ordered_step(acc, h);
            n += 1;
            let nx = self.next(self.r(si));
            self.set_r(si, nx);
        }
        self.pop_to(base);
        let h = hash::mix_coll_hash(acc, n);
        if self.is_vector(v) {
            self.set(v, crate::vector::V_HASH, Value::fixnum(h as i32 as i64));
        }
        h
    }

    // --- compare -----------------------------------------------------------

    /// `compare`. Total within a type; ordering across unrelated types is an
    /// error in Clojure and returns 0 here after setting `thrown`.
    pub fn compare(&mut self, a: Value, b: Value) -> i32 {
        if a.0 == b.0 && !a.is_double() {
            return 0;
        }
        if a.is_nil() {
            return -1;
        }
        if b.is_nil() {
            return 1;
        }
        if self.is_number(a) && self.is_number(b) {
            return self.num_cmp(a, b);
        }
        if a.is_bool() && b.is_bool() {
            return (a.is_true() as i32) - (b.is_true() as i32);
        }
        if self.is_string(a) && self.is_string(b) {
            let mut ba = crate::rt::sbuf();
            let mut bb = crate::rt::sbuf();
            // Two borrows of self at once is fine: both are immutable.
            let sa = if a.is_inline_str() {
                core::str::from_utf8(a.inline_bytes(&mut ba)).unwrap_or("")
            } else {
                core::str::from_utf8(str_bytes(&self.gc.sp, a.as_heap())).unwrap_or("")
            };
            let sb = if b.is_inline_str() {
                core::str::from_utf8(b.inline_bytes(&mut bb)).unwrap_or("")
            } else {
                core::str::from_utf8(str_bytes(&self.gc.sp, b.as_heap())).unwrap_or("")
            };
            return utf16_cmp(sa, sb);
        }
        if self.is_keyword(a) && self.is_keyword(b) {
            return self.cmp_named(a, b);
        }
        if self.is_symbol(a) && self.is_symbol(b) {
            return self.cmp_named(a, b);
        }
        if self.is_sequential(a) && self.is_sequential(b) {
            return self.cmp_sequential(a, b);
        }
        self.throw_str("ClassCastException", "cannot compare these values");
        0
    }

    fn cmp_named(&mut self, a: Value, b: Value) -> i32 {
        let (na, nb) = (self.ns_of(a), self.ns_of(b));
        if na.is_nil() && !nb.is_nil() {
            return -1;
        }
        if !na.is_nil() && nb.is_nil() {
            return 1;
        }
        if !na.is_nil() {
            let c = self.compare(na, nb);
            if c != 0 {
                return c;
            }
        }
        let (ma, mb) = (self.name_of(a), self.name_of(b));
        self.compare(ma, mb)
    }

    fn cmp_sequential(&mut self, a: Value, b: Value) -> i32 {
        let base = self.mark();
        let sa = self.seq(a);
        let ia = self.push(sa);
        let sb = self.seq(b);
        let ib = self.push(sb);
        let r = loop {
            let (x, y) = (self.r(ia), self.r(ib));
            match (x.is_nil(), y.is_nil()) {
                (true, true) => break 0,
                (true, false) => break -1,
                (false, true) => break 1,
                _ => {}
            }
            let fa = self.first(x);
            let fi = self.push(fa);
            let fb = self.first(self.r(ib));
            let fa = self.r(fi);
            self.pop_to(fi);
            let c = self.compare(fa, fb);
            if c != 0 {
                break c;
            }
            let na = self.next(self.r(ia));
            self.set_r(ia, na);
            let nb = self.next(self.r(ib));
            self.set_r(ib, nb);
        };
        self.pop_to(base);
        r
    }

    pub fn eq_value(&mut self, a: Value, b: Value) -> Value {
        if self.eq(a, b) {
            TRUE
        } else {
            FALSE
        }
    }
}

/// String ordering by UTF-16 code unit, which is what `String.compareTo` does
/// and therefore what Clojure's `compare` does. It differs from byte order for
/// astral characters, so it is worth doing properly.
fn utf16_cmp(a: &str, b: &str) -> i32 {
    let mut ia = hash::Utf16Units::new(a);
    let mut ib = hash::Utf16Units::new(b);
    loop {
        match (ia.next(), ib.next()) {
            (None, None) => return 0,
            (None, Some(_)) => return -1,
            (Some(_), None) => return 1,
            (Some(x), Some(y)) => {
                if x != y {
                    return x as i32 - y as i32;
                }
            }
        }
    }
}

impl Rt {
    pub fn nil_or(&self, c: bool) -> Value {
        if c {
            TRUE
        } else {
            NIL
        }
    }
}
