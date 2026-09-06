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
    /// Two strings in UTF-16 CODE UNIT order, ACROSS ALL THREE TIERS.
    ///
    /// This used to be inline in `compare`, reading `str_bytes` -- and
    /// `str_bytes` debug-asserts `TY_STR` because a rope's `len` is its SLOT
    /// COUNT and its body is Values. In a release build the assert is off, so
    /// comparing anything longer than `FLAT_MAX` read the rope's slots as
    /// UTF-8: MEASURED, two unequal 1 400-byte strings compared as 0 and
    /// `sort` over them was silently wrong. Both ports materialise and were
    /// right; this does the same, and now one body decides for all three.
    pub fn str_cmp(&mut self, a: Value, b: Value) -> i32 {
        let sa = self.value_text(a);
        let sb = self.value_text(b);
        utf16_cmp(&sa, &sb)
    }

    // `compare`, `cmp_named` and `cmp_sequential` are GENERATED, from
    // `kin/valcmp.kin`, under the names `val_cmp`, `cmp_named` and
    // `cmp_sequential`. What went with them is the ROPE BUG: the string arm
    // read `str_bytes`, which debug-asserts `TY_STR`, so a release build
    // compared a rope's slots as UTF-8.

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
        assert_eq!(rt.val_cmp(Value::fixnum(1), Value::fixnum(2)), -1);
        assert_eq!(rt.val_cmp(Value::fixnum(2), Value::fixnum(2)), 0);
        assert_eq!(rt.val_cmp(Value::from_f64(2.5), Value::fixnum(2)), 1);
        assert_eq!(rt.val_cmp(NIL, Value::fixnum(1)), -1, "nil sorts first");
        assert_eq!(rt.val_cmp(Value::fixnum(1), NIL), 1);
        let a = rt.string("a");
        let b = rt.string("b");
        assert_eq!(rt.val_cmp(a, b), -1);
        assert!(rt.val_cmp(b, a) > 0);
        let ka = rt.keyword(None, "a");
        let kb = rt.keyword(Some("z"), "a");
        assert_eq!(rt.val_cmp(ka, kb), -1, "an unqualified keyword sorts first");
        let v1 = vec_of(&mut rt, &[1]);
        let vi = rt.push(v1);
        let v2 = vec_of(&mut rt, &[1, 2]);
        let a0 = rt.r(vi);
        assert_eq!(rt.val_cmp(a0, v2), -1, "shorter sorts first when a prefix");
    }

    #[test]
    fn comparing_unrelated_types_throws_rather_than_guessing() {
        let mut rt = Rt::new();
        let s = rt.string("a");
        let _ = rt.val_cmp(s, Value::fixnum(1));
        assert!(!rt.thrown.is_nil(), "no total order across unrelated types");
    }
}
