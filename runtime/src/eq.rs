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

    // `hash_value` is GENERATED, from `kin/valhash.kin`, as `value_hash`.
    // The flat-byte arm went with it: it walked the bytes inline where the
    // BROPE arm called `b_hash`, and `b_hash` already handles both tiers --
    // one walk written twice, and both ports already routed both to it.

    // `hash_ordered` is GENERATED, from `kin/valhash.kin`.

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
