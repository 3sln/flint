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

    pub fn is_sequential(&self, v: Value) -> bool {
        self.category(v) == CAT_SEQUENTIAL
    }


    // `eq` and `seq_eq` are GENERATED, from `kin/valeq.kin`, under the names
    // `val_eq` and `seq_eq`. `string_eq` went with them: the byte-length
    // check and the interning shortcut are both in the generated arm now.

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
            // Hash the CONTENT, so `"abc"` inline, flat and as a rope are one
            // key -- but WALK it rather than flattening. Flattening was here for
            // the caching, which is real: a rope used as a map key must not
            // rehash every lookup. `RP_HASH` gives the same caching per node and
            // keeps the tree, so the trade is gone rather than chosen.
            crate::obj::TY_ROPE => self.rope_hash(v),
            // Same rule for bytes: the content decides, so a flat and a tree
            // holding the same bytes hash alike and are one key.
            crate::obj::TY_BYTES => {
                let bs = raw_bytes(&self.gc.sp, v.as_heap());
                let mut h: u32 = 0;
                for b in bs {
                    h = h.wrapping_mul(31).wrapping_add(*b as u32);
                }
                h
            }
            // WALKED and cached per node, not flattened -- see `TY_ROPE`.
            crate::obj::TY_BROPE => self.b_hash(v),
            TY_KW => self.keyword_hash(v),
            TY_SYM => self.symbol_hash(v),
            TY_BIGINT => hash::hash_long(self.as_i64(v).unwrap_or(0)),
            TY_VEC | TY_MAPENTRY | TY_CONS | TY_EMPTY_LIST | TY_LAZYSEQ | TY_VECSEQ | TY_STRSEQ
            | TY_RANGE => self.hash_ordered(v),
            TY_ARRAYMAP | TY_HASHMAP => self.hash_map(v),
            TY_SET => self.hash_set(v),
            // An opaque value carries its own identity, assigned at creation
            // and STORED (`doc/decisions/0022`). The per-type constant below
            // would be correct -- equality is identity, so collisions only cost
            // time -- but it would put every opaque value in one bucket, and
            // the whole point of the type is to be a distinct key.
            crate::obj::TY_OPAQUE => {
                let id = crate::obj::slot(&self.gc.sp, v.as_heap(), 1);
                hash::hash_long(if id.is_fixnum() { id.as_fixnum() } else { 0 })
            }
            // Both halves, so a tagged literal hashes like the pair it is and
            // two equal ones land in the same bucket.
            // COLUMNAR is available precisely because a table is not `=` to a
            // vector of maps, so this need not agree with what one would hash.
            crate::obj::TY_TABLE => {
                // ROOTED, for the reason the equality arm above is: `table_ref`
                // allocates and `v` is a Rust local (`doc/decisions/0031`).
                let base = self.mark();
                let vi = self.push(v);
                let n = self.table_count(self.r(vi));
                let mut acc: u32 = 1;
                for i in 0..n {
                    let r = self.table_ref(self.r(vi), i);
                    let ri = self.push(r);
                    let h = self.hash_value(self.r(ri));
                    self.pop_to(ri);
                    acc = acc.wrapping_mul(31).wrapping_add(h);
                }
                self.pop_to(base);
                hash::hash_int(acc ^ n)
            }
            // A ref hashes as the map it is, or a map keyed by a row would not
            // find it.
            crate::obj::TY_TABLEREF => {
                let m = self.ref_to_map(v);
                self.hash_value(m)
            }
            crate::obj::TY_TAGGED => {
                let (t, f) = (self.slot(v, 0), self.slot(v, 1));
                let th = self.hash_value(t);
                let fh = self.hash_value(f);
                hash::hash_int(th.wrapping_mul(31).wrapping_add(fh))
            }
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
        // `v` is rooted for the whole walk: seq/first/next allocate, and the
        // cache write at the end would otherwise land on a stale address --
        // which is not a wrong hash, it is a corrupted heap.
        let base = self.mark();
        let vi = self.push(v);
        let s = self.seq(self.r(vi));
        let si = self.push(s);
        let mut acc = 1u32;
        let mut n = 0u32;
        while !self.r(si).is_nil() {
            // A TICK and not a pre-charge: this walks a SEQ, whose length is not
            // known until it ends, and which may not end at all. `charge_work`
            // billed each element and stopped nothing -- 998 068 steps past an
            // exhausted budget.
            if !self.charge_tick(n as u64, 1, "hash") {
                self.pop_to(base);
                return 0;
            }
            let f = self.first(self.r(si));
            let fi = self.push(f);
            let h = self.hash_value(self.r(fi));
            self.pop_to(fi);
            acc = hash::ordered_step(acc, h);
            n += 1;
            let nx = self.next(self.r(si));
            self.set_r(si, nx);
        }
        let h = hash::mix_coll_hash(acc, n);
        if self.is_vector(self.r(vi)) {
            let vv = self.r(vi);
            self.set(vv, crate::vector::V_HASH, Value::fixnum(h as i32 as i64));
        }
        self.pop_to(base);
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
        let msg = alloc::format!("cannot compare {} with {}",
                                 self.describe(a), self.describe(b));
        self.throw_str("ClassCastException", &msg);
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
        // Same rooting discipline as `seq_eq`: seq/first/next allocate.
        let base = self.mark();
        let ai = self.push(a);
        let bi = self.push(b);
        let sa = self.seq(self.r(ai));
        let ia = self.push(sa);
        let sb = self.seq(self.r(bi));
        let ib = self.push(sb);
        let r = loop {
            self.charge_work(1);
            let (x, y) = (self.r(ia), self.r(ib));
            match (x.is_nil(), y.is_nil()) {
                (true, true) => break 0,
                (true, false) => break -1,
                (false, true) => break 1,
                _ => {}
            }
            let fa = self.first(self.r(ia));
            let fi = self.push(fa);
            let fb = self.first(self.r(ib));
            let fbi = self.push(fb);
            let c = self.compare(self.r(fi), self.r(fbi));
            self.pop_to(fi);
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
        if self.val_eq(a, b) {
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::rt::Rt;
    use crate::value::{FALSE, NIL, TRUE};

    fn vec_of(rt: &mut Rt, xs: &[i64]) -> Value {
        let mut v = rt.empty_vec();
        let vi = rt.push(v);
        for x in xs {
            let nv = rt.vec_conj(rt.r(vi), Value::fixnum(*x));
            rt.set_r(vi, nv);
        }
        v = rt.r(vi);
        rt.pop_to(vi);
        v
    }

    fn list_of(rt: &mut Rt, xs: &[i64]) -> Value {
        let base = rt.mark();
        for x in xs {
            rt.push(Value::fixnum(*x));
        }
        let l = rt.list_from_roots(base, xs.len() as u32);
        rt.pop_to(base);
        l
    }

    #[test]
    fn scalars() {
        let mut rt = Rt::new();
        assert!(rt.val_eq(NIL, NIL));
        assert!(!rt.val_eq(NIL, FALSE), "nil is not false");
        assert!(rt.val_eq(TRUE, TRUE));
        assert!(rt.val_eq(Value::fixnum(1), Value::fixnum(1)));
        assert!(!rt.val_eq(Value::fixnum(1), Value::from_f64(1.0)), "(= 1 1.0) is false");
        assert!(rt.val_eq(Value::from_f64(1.0), Value::from_f64(1.0)));
        let nan = Value::from_f64(f64::NAN);
        assert!(!rt.val_eq(nan, nan), "NaN is not equal to itself");
        assert!(rt.val_eq(Value::from_f64(0.0), Value::from_f64(-0.0)), "0.0 == -0.0");
    }

    #[test]
    fn boxed_integers_compare_by_value() {
        let mut rt = Rt::new();
        let a = rt.integer(1 << 50);
        let b = rt.integer(1 << 50);
        assert_ne!(a, b, "two distinct boxes");
        assert!(rt.val_eq(a, b), "but equal");
        let c = rt.integer((1 << 50) + 1);
        assert!(!rt.val_eq(a, c));
    }

    #[test]
    fn strings_and_named_things() {
        let mut rt = Rt::new();
        let short = rt.string("abc");
        let again = rt.string("abc");
        assert!(rt.val_eq(short, again));
        let long_a = rt.string(&"x".repeat(100));
        let long_b = rt.string(&"x".repeat(100));
        assert_ne!(long_a, long_b, "beyond the intern limit these are distinct objects");
        assert!(rt.val_eq(long_a, long_b), "and still equal, by bytes");
        let long_c = rt.string(&("x".repeat(99) + "y"));
        assert!(!rt.val_eq(long_a, long_c));

        let k = rt.keyword(None, "a");
        let k2 = rt.keyword(None, "a");
        assert!(rt.val_eq(k, k2));
        let sa = rt.string("a");
        assert!(!rt.val_eq(k, sa), "a keyword is not its name");
        let s = rt.symbol(Some("ns"), "n");
        let s2a = rt.symbol(Some("ns"), "n");
        assert!(rt.val_eq(s, s2a));
        let s3 = rt.symbol(None, "n");
        assert!(!rt.val_eq(s, s3));
        // with-meta makes a distinct object that is still =
        let m = rt.empty_map();
        let s2 = rt.with_meta(s, m);
        assert_ne!(s, s2);
        assert!(rt.val_eq(s, s2), "metadata is not part of equality");
    }

    #[test]
    fn sequential_equality_crosses_collection_types() {
        let mut rt = Rt::new();
        let v = vec_of(&mut rt, &[1, 2, 3]);
        let vi = rt.push(v);
        let l = list_of(&mut rt, &[1, 2, 3]);
        let li = rt.push(l);
        let (a0, b0) = (rt.r(vi), rt.r(li));
        assert!(rt.val_eq(a0, b0), "a vector equals a list of the same items");
        let shorter = vec_of(&mut rt, &[1, 2]);
        let a0 = rt.r(vi);
        assert!(!rt.val_eq(a0, shorter));
        let different = vec_of(&mut rt, &[1, 2, 4]);
        let a0 = rt.r(vi);
        assert!(!rt.val_eq(a0, different));
        // ...but a set is a different partition
        let mut s = rt.empty_set();
        let si = rt.push(s);
        for i in 1..4i64 {
            let ns = rt.set_conj(rt.r(si), Value::fixnum(i));
            rt.set_r(si, ns);
        }
        s = rt.r(si);
        let a0 = rt.r(vi);
        assert!(!rt.val_eq(a0, s), "a vector is not a set");
        let m = rt.empty_map();
        let ev = rt.empty_vec();
        assert!(!rt.val_eq(m, ev), "an empty map is not an empty vector");
    }

    #[test]
    fn nested_structures() {
        let mut rt = Rt::new();
        let build = |rt: &mut Rt| {
            let inner = vec_of(rt, &[1, 2]);
            let ii = rt.push(inner);
            let k = rt.keyword(None, "a");
            let ki = rt.push(k);
            let m = rt.empty_map();
            let inner = rt.r(ii);
            let k = rt.r(ki);
            let out = rt.map_assoc(m, k, inner);
            rt.pop_to(ii);
            out
        };
        let a = build(&mut rt);
        let ai = rt.push(a);
        let b = build(&mut rt);
        let a0 = rt.r(ai);
        assert!(rt.val_eq(a0, b));
        let a0 = rt.r(ai);
        let (ha, hb) = (rt.hash_value(a0), rt.hash_value(b));
        assert_eq!(ha, hb, "equal implies equal hash");
    }

    #[test]
    fn hash_agrees_with_equality_across_types() {
        let mut rt = Rt::new();
        let v = vec_of(&mut rt, &[1, 2, 3]);
        let vi = rt.push(v);
        let l = list_of(&mut rt, &[1, 2, 3]);
        let a0 = rt.r(vi);
        assert!(rt.val_eq(a0, l));
        let a0 = rt.r(vi);
        let hv = rt.hash_value(a0);
        let hl = rt.hash_value(l);
        assert_eq!(hv, hl, "a vector and an equal list must hash the same");
        assert_eq!(hv as i32, 736442005, "and match JVM Clojure");
    }

    #[test]
    fn compare_orders_within_a_type() {
        let mut rt = Rt::new();
        assert_eq!(rt.compare(Value::fixnum(1), Value::fixnum(2)), -1);
        assert_eq!(rt.compare(Value::fixnum(2), Value::fixnum(2)), 0);
        assert_eq!(rt.compare(Value::from_f64(2.5), Value::fixnum(2)), 1);
        assert_eq!(rt.compare(NIL, Value::fixnum(1)), -1, "nil sorts first");
        assert_eq!(rt.compare(Value::fixnum(1), NIL), 1);
        let a = rt.string("a");
        let b = rt.string("b");
        assert_eq!(rt.compare(a, b), -1);
        assert!(rt.compare(b, a) > 0);
        let ka = rt.keyword(None, "a");
        let kb = rt.keyword(Some("z"), "a");
        assert_eq!(rt.compare(ka, kb), -1, "an unqualified keyword sorts first");
        let v1 = vec_of(&mut rt, &[1]);
        let vi = rt.push(v1);
        let v2 = vec_of(&mut rt, &[1, 2]);
        let a0 = rt.r(vi);
        assert_eq!(rt.compare(a0, v2), -1, "shorter sorts first when a prefix");
    }

    #[test]
    fn comparing_unrelated_types_throws_rather_than_guessing() {
        let mut rt = Rt::new();
        let s = rt.string("a");
        let _ = rt.compare(s, Value::fixnum(1));
        assert!(!rt.thrown.is_nil(), "no total order across unrelated types");
    }
}
