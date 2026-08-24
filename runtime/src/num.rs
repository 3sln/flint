//! The number tower: 64-bit integers and IEEE doubles. That is all of it.
//!
//! An integer is a fixnum when it fits in 48 bits and a heap-boxed `i64`
//! otherwise. The representation is canonical — an `i64` in fixnum range is
//! *always* a fixnum — so integer equality never has to consider the two forms
//! being different objects with the same value.
//!
//! ## Where this differs from Clojure, deliberately
//!
//! * **No `BigInt`, no `Ratio`, no `BigDecimal`.** `+`/`-`/`*` throw on `i64`
//!   overflow exactly as Clojure's do, but there is no `+'` to promote to.
//! * **`(/ 1 2)` is `0.5`, not `1/2`.** Clojure would produce a `Ratio`. This is
//!   the single most visible numeric divergence and it is in the README.
//!   `quot` and `rem` are exact and behave as Clojure's.

use crate::obj::*;
use crate::rt::Rt;
use crate::value::{Value, NIL};

impl Rt {
    /// Canonical integer: fixnum when it fits, boxed otherwise.
    pub fn integer(&mut self, n: i64) -> Value {
        if Value::fits_fixnum(n) {
            return Value::fixnum(n);
        }
        let a = self.alloc(TY_BIGINT, 8);
        if a == 0 {
            return NIL;
        }
        self.gc.sp.bytes_mut(a + HDR, 8).copy_from_slice(&n.to_le_bytes());
        Value::heap(a)
    }

    #[inline]
    pub fn is_int(&self, v: Value) -> bool {
        v.is_fixnum() || (v.is_heap() && ty(&self.gc.sp, v.as_heap()) == TY_BIGINT)
    }
    #[inline]
    pub fn is_float(&self, v: Value) -> bool {
        v.is_double()
    }
    #[inline]
    pub fn is_number(&self, v: Value) -> bool {
        v.is_double() || self.is_int(v)
    }

    #[inline]
    pub fn as_i64(&self, v: Value) -> Option<i64> {
        if v.is_fixnum() {
            Some(v.as_fixnum())
        } else if v.is_heap() && ty(&self.gc.sp, v.as_heap()) == TY_BIGINT {
            let b = raw_bytes(&self.gc.sp, v.as_heap());
            Some(i64::from_le_bytes([b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7]]))
        } else {
            None
        }
    }

    #[inline]
    pub fn num_f64(&self, v: Value) -> f64 {
        if v.is_double() {
            v.as_f64()
        } else {
            self.as_i64(v).map(|n| n as f64).unwrap_or(f64::NAN)
        }
    }

    fn overflow(&mut self) -> Value {
        self.throw_str("ArithmeticException", "integer overflow")
    }

    pub fn num_add(&mut self, a: Value, b: Value) -> Value {
        if let (Some(x), Some(y)) = (self.as_i64(a), self.as_i64(b)) {
            match x.checked_add(y) {
                Some(r) => self.integer(r),
                None => self.overflow(),
            }
        } else if self.is_number(a) && self.is_number(b) {
            Value::from_f64(self.num_f64(a) + self.num_f64(b))
        } else {
            self.throw_not_a_number(a, b)
        }
    }

    pub fn num_sub(&mut self, a: Value, b: Value) -> Value {
        if let (Some(x), Some(y)) = (self.as_i64(a), self.as_i64(b)) {
            match x.checked_sub(y) {
                Some(r) => self.integer(r),
                None => self.overflow(),
            }
        } else if self.is_number(a) && self.is_number(b) {
            Value::from_f64(self.num_f64(a) - self.num_f64(b))
        } else {
            self.throw_not_a_number(a, b)
        }
    }

    pub fn num_mul(&mut self, a: Value, b: Value) -> Value {
        if let (Some(x), Some(y)) = (self.as_i64(a), self.as_i64(b)) {
            match x.checked_mul(y) {
                Some(r) => self.integer(r),
                None => self.overflow(),
            }
        } else if self.is_number(a) && self.is_number(b) {
            Value::from_f64(self.num_f64(a) * self.num_f64(b))
        } else {
            self.throw_not_a_number(a, b)
        }
    }

    /// `/`. See the module note: integer division that does not divide evenly
    /// yields a double here, where Clojure would yield a Ratio.
    pub fn num_div(&mut self, a: Value, b: Value) -> Value {
        if let (Some(x), Some(y)) = (self.as_i64(a), self.as_i64(b)) {
            if y == 0 {
                return self.throw_str("ArithmeticException", "Divide by zero");
            }
            if x % y == 0 {
                return match x.checked_div(y) {
                    Some(r) => self.integer(r),
                    None => self.overflow(),
                };
            }
            return Value::from_f64(x as f64 / y as f64);
        }
        if self.is_number(a) && self.is_number(b) {
            Value::from_f64(self.num_f64(a) / self.num_f64(b))
        } else {
            self.throw_not_a_number(a, b)
        }
    }

    pub fn num_quot(&mut self, a: Value, b: Value) -> Value {
        if let (Some(x), Some(y)) = (self.as_i64(a), self.as_i64(b)) {
            if y == 0 {
                return self.throw_str("ArithmeticException", "Divide by zero");
            }
            match x.checked_div(y) {
                Some(r) => self.integer(r),
                None => self.overflow(),
            }
        } else if self.is_number(a) && self.is_number(b) {
            let (x, y) = (self.num_f64(a), self.num_f64(b));
            Value::from_f64(crate::fmath::trunc(x / y))
        } else {
            self.throw_not_a_number(a, b)
        }
    }

    pub fn num_rem(&mut self, a: Value, b: Value) -> Value {
        if let (Some(x), Some(y)) = (self.as_i64(a), self.as_i64(b)) {
            if y == 0 {
                return self.throw_str("ArithmeticException", "Divide by zero");
            }
            self.integer(x.wrapping_rem(y))
        } else if self.is_number(a) && self.is_number(b) {
            let (x, y) = (self.num_f64(a), self.num_f64(b));
            Value::from_f64(x - crate::fmath::trunc(x / y) * y)
        } else {
            self.throw_not_a_number(a, b)
        }
    }

    pub fn num_neg(&mut self, a: Value) -> Value {
        if let Some(x) = self.as_i64(a) {
            match x.checked_neg() {
                Some(r) => self.integer(r),
                None => self.overflow(),
            }
        } else if a.is_double() {
            Value::from_f64(-a.as_f64())
        } else {
            self.throw_not_a_number(a, a)
        }
    }

    /// Numeric equality (`==`): compares across int/float, unlike `=`.
    pub fn num_eq(&self, a: Value, b: Value) -> bool {
        match (self.as_i64(a), self.as_i64(b)) {
            (Some(x), Some(y)) => x == y,
            _ => {
                if self.is_number(a) && self.is_number(b) {
                    self.num_f64(a) == self.num_f64(b)
                } else {
                    false
                }
            }
        }
    }

    /// `compare` for numbers: -1, 0 or 1. NaN sorts as equal to everything,
    /// matching `Double.compare`'s use inside Clojure's `compare`.
    pub fn num_cmp(&self, a: Value, b: Value) -> i32 {
        match (self.as_i64(a), self.as_i64(b)) {
            (Some(x), Some(y)) => {
                if x < y {
                    -1
                } else if x > y {
                    1
                } else {
                    0
                }
            }
            _ => {
                let (x, y) = (self.num_f64(a), self.num_f64(b));
                if x < y {
                    -1
                } else if x > y {
                    1
                } else {
                    0
                }
            }
        }
    }

    pub fn num_hash(&mut self, v: Value) -> u32 {
        if v.is_double() {
            crate::hash::hash_double(v.as_f64())
        } else {
            crate::hash::hash_long(self.as_i64(v).unwrap_or(0))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::value::{FIXNUM_MAX, FIXNUM_MIN};

    #[test]
    fn integers_are_canonical() {
        let mut rt = Rt::new();
        for n in [0i64, 1, -1, FIXNUM_MAX, FIXNUM_MIN] {
            let v = rt.integer(n);
            assert!(v.is_fixnum(), "{n} should be immediate");
            assert_eq!(rt.as_i64(v), Some(n));
        }
        for n in [FIXNUM_MAX + 1, FIXNUM_MIN - 1, i64::MAX, i64::MIN, 1 << 50] {
            let v = rt.integer(n);
            assert!(v.is_heap(), "{n} should be boxed");
            assert_eq!(rt.as_i64(v), Some(n));
            assert!(rt.is_int(v));
        }
    }

    #[cfg(feature = "diagnostics")]
    #[test]
    fn boxed_integers_survive_collection() {
        let mut rt = Rt::new();
        let v = rt.integer(i64::MIN);
        let i = rt.push(v);
        rt.gc.stress = true;
        for k in 0..200 {
            let _ = rt.integer(i64::MAX - k);
        }
        rt.collect();
        assert_eq!(rt.as_i64(rt.r(i)), Some(i64::MIN));
    }

    #[test]
    fn arithmetic_promotes_at_the_fixnum_boundary() {
        let mut rt = Rt::new();
        let a = Value::fixnum(FIXNUM_MAX);
        let b = rt.num_add(a, Value::fixnum(1));
        assert!(b.is_heap(), "crossing the fixnum boundary must box");
        assert_eq!(rt.as_i64(b), Some(FIXNUM_MAX + 1));
        let c = rt.num_sub(b, Value::fixnum(1));
        assert!(c.is_fixnum(), "coming back must un-box");
        assert_eq!(rt.as_i64(c), Some(FIXNUM_MAX));
    }

    #[test]
    fn overflow_throws_rather_than_wrapping() {
        let mut rt = Rt::new();
        let a = rt.integer(i64::MAX);
        let _ = rt.num_add(a, Value::fixnum(1));
        assert!(!rt.thrown.is_nil(), "long overflow must throw, like Clojure");
        rt.thrown = NIL;
        let b = rt.integer(i64::MIN);
        let _ = rt.num_neg(b);
        assert!(!rt.thrown.is_nil());
    }

    #[test]
    fn mixed_arithmetic_contagion_is_to_double() {
        let mut rt = Rt::new();
        let r = rt.num_add(Value::fixnum(1), Value::from_f64(0.5));
        assert!(r.is_double());
        assert_eq!(r.as_f64(), 1.5);
        let r = rt.num_mul(Value::from_f64(2.0), Value::fixnum(3));
        assert_eq!(r.as_f64(), 6.0);
    }

    #[test]
    fn division_documented_divergence() {
        let mut rt = Rt::new();
        // Exact division stays integral, as in Clojure.
        let r = rt.num_div(Value::fixnum(6), Value::fixnum(3));
        assert_eq!(rt.as_i64(r), Some(2));
        // Inexact division yields a double where Clojure yields a Ratio.
        let r = rt.num_div(Value::fixnum(1), Value::fixnum(2));
        assert!(r.is_double());
        assert_eq!(r.as_f64(), 0.5);
        // Division by zero throws for integers...
        let _ = rt.num_div(Value::fixnum(1), Value::fixnum(0));
        assert!(!rt.thrown.is_nil());
        rt.thrown = NIL;
        // ...but follows IEEE for doubles, as Clojure does.
        let r = rt.num_div(Value::from_f64(1.0), Value::from_f64(0.0));
        assert!(rt.thrown.is_nil());
        assert_eq!(r.as_f64(), f64::INFINITY);
    }

    #[test]
    fn quot_and_rem_match_clojure_truncation() {
        let mut rt = Rt::new();
        for (a, b, q, r) in [(7i64, 2i64, 3i64, 1i64), (-7, 2, -3, -1), (7, -2, -3, 1), (-7, -2, 3, -1)] {
            let qq = rt.num_quot(Value::fixnum(a), Value::fixnum(b));
            let rr = rt.num_rem(Value::fixnum(a), Value::fixnum(b));
            assert_eq!(rt.as_i64(qq), Some(q), "quot {a} {b}");
            assert_eq!(rt.as_i64(rr), Some(r), "rem {a} {b}");
        }
    }

    #[test]
    fn equality_and_comparison() {
        let mut rt = Rt::new();
        let big = rt.integer(1 << 50);
        let big2 = rt.integer(1 << 50);
        assert!(rt.num_eq(big, big2));
        assert_ne!(big, big2, "distinct boxes, but numerically equal");
        assert!(rt.num_eq(Value::fixnum(1), Value::from_f64(1.0)), "== crosses types");
        assert_eq!(rt.num_cmp(Value::fixnum(1), Value::fixnum(2)), -1);
        assert_eq!(rt.num_cmp(Value::from_f64(2.5), Value::fixnum(2)), 1);
        assert_eq!(rt.num_cmp(Value::fixnum(2), Value::from_f64(2.0)), 0);
    }

    #[test]
    fn hashes_match_clojure_for_both_kinds() {
        let mut rt = Rt::new();
        assert_eq!(rt.num_hash(Value::fixnum(42)) as i32, 1871679806);
        let big = rt.integer(12345678901234);
        assert_eq!(rt.num_hash(big) as i32, -1096982217);
        assert_eq!(rt.num_hash(Value::from_f64(1.5)) as i32, 1073217536);
    }
}
