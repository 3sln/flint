//! Sets, as a map from element to itself.
//!
//! `TY_SET [map, meta, hash]`.
//!
//! Storing the element as its own value costs a slot per element compared with
//! a set-specific CHAMP node that stores only keys. It buys `get` returning the
//! stored element (which is what Clojure does, and what makes sets usable for
//! canonicalisation), and one implementation of the trie instead of two. The
//! cost is named in the README rather than hidden.

use crate::hash;
use crate::obj::*;
use crate::rt::Rt;
use crate::value::{Value, NIL, NOT_FOUND};

pub const S_MAP: u32 = 0;
pub const S_META: u32 = 1;
pub const S_HASH: u32 = 2;

impl Rt {
    pub(crate) fn init_set(&mut self) {
        let m = self.empty_map();
        let mi = self.push(m);
        let a = self.alloc(TY_SET, 3);
        let m = self.r(mi);
        self.pop_to(mi);
        self.gc.set_slot(a, S_MAP, m);
        self.gc.set_slot(a, S_META, NIL);
        self.gc.set_slot(a, S_HASH, NIL);
        self.roots.singletons[crate::rt::SING_EMPTY_SET] = Value::heap(a);
    }

    pub fn is_set(&self, v: Value) -> bool {
        v.is_heap() && ty(&self.gc.sp, v.as_heap()) == TY_SET
    }

    fn new_set(&mut self, m: Value, meta: Value) -> Value {
        let base = self.mark();
        let mi = self.push(m);
        let mt = self.push(meta);
        let a = self.alloc(TY_SET, 3);
        if a == 0 {
            self.pop_to(base);
            return NIL;
        }
        let (m, meta) = (self.r(mi), self.r(mt));
        self.pop_to(base);
        self.gc.set_slot(a, S_MAP, m);
        self.gc.set_slot(a, S_META, meta);
        self.gc.set_slot(a, S_HASH, NIL);
        Value::heap(a)
    }

    pub fn set_count(&self, s: Value) -> u32 {
        self.map_count(self.slot(s, S_MAP))
    }

    pub fn set_contains(&mut self, s: Value, x: Value) -> bool {
        let m = self.slot(s, S_MAP);
        self.map_contains(m, x)
    }

    /// `get` on a set returns the *stored* element, not the probe.
    pub fn set_get(&mut self, s: Value, x: Value, not_found: Value) -> Value {
        let m = self.slot(s, S_MAP);
        self.map_get(m, x, not_found)
    }

    pub fn set_conj(&mut self, s: Value, x: Value) -> Value {
        let base = self.mark();
        let si = self.push(s);
        let xi = self.push(x);
        let m = self.slot(s, S_MAP);
        let (x1, x2) = (self.r(xi), self.r(xi));
        let nm = self.map_assoc(m, x1, x2);
        let out = if nm == self.slot(self.r(si), S_MAP) {
            self.r(si)
        } else {
            let ni = self.push(nm);
            let meta = self.slot(self.r(si), S_META);
            let nm = self.r(ni);
            self.new_set(nm, meta)
        };
        self.pop_to(base);
        out
    }

    pub fn set_disj(&mut self, s: Value, x: Value) -> Value {
        let base = self.mark();
        let si = self.push(s);
        let xi = self.push(x);
        let m = self.slot(s, S_MAP);
        let x = self.r(xi);
        let nm = self.map_dissoc(m, x);
        let out = if nm == self.slot(self.r(si), S_MAP) {
            self.r(si)
        } else {
            let ni = self.push(nm);
            let meta = self.slot(self.r(si), S_META);
            let nm = self.r(ni);
            self.new_set(nm, meta)
        };
        self.pop_to(base);
        out
    }

    pub fn set_for_each<S>(
        &mut self,
        s: Value,
        state: &mut S,
        f: &mut dyn FnMut(&mut Rt, Value, &mut S),
    ) {
        let m = self.slot(s, S_MAP);
        self.map_for_each(m, state, &mut |rt, k, _v, st| f(rt, k, st));
    }

    pub fn set_element_vector(&mut self, s: Value) -> Value {
        let base = self.mark();
        let acc = self.empty_vec();
        let ai = self.push(acc);
        let mut st = ai;
        self.set_for_each(s, &mut st, &mut |rt, k, ai| {
            let nv = rt.vec_conj(rt.r(*ai), k);
            rt.set_r(*ai, nv);
        });
        let out = self.r(ai);
        self.pop_to(base);
        out
    }

    pub fn set_eq(&mut self, a: Value, b: Value) -> bool {
        if self.set_count(a) != self.set_count(b) {
            return false;
        }
        let base = self.mark();
        let ai = self.push(a);
        let bi = self.push(b);
        let mut st = (bi, true);
        let av = self.r(ai);
        self.set_for_each(av, &mut st, &mut |rt, k, st| {
            if st.1 && rt.set_get(rt.r(st.0), k, NOT_FOUND) == NOT_FOUND {
                st.1 = false;
            }
        });
        self.pop_to(base);
        st.1
    }

    pub fn hash_set(&mut self, s: Value) -> u32 {
        let cached = self.slot(s, S_HASH);
        if cached.is_fixnum() {
            return cached.as_fixnum() as i32 as u32;
        }
        let base = self.mark();
        let si = self.push(s);
        let mut st = (0u32, 0u32);
        let sv = self.r(si);
        self.set_for_each(sv, &mut st, &mut |rt, k, st| {
            let mk = rt.mark();
            let ki = rt.push(k);
            let h = rt.hash_value(rt.r(ki));
            rt.pop_to(mk);
            st.0 = hash::unordered_step(st.0, h);
            st.1 += 1;
        });
        let h = hash::mix_coll_hash(st.0, st.1);
        let sv = self.r(si);
        self.set(sv, S_HASH, Value::fixnum(h as i32 as i64));
        self.pop_to(base);
        h
    }

    pub fn set_from_map(&mut self, m: Value) -> Value {
        self.new_set(m, NIL)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use alloc::vec::Vec as StdVec;

    fn build(rt: &mut Rt, n: i64) -> Value {
        let s = rt.empty_set();
        let si = rt.push(s);
        for i in 0..n {
            let ns = rt.set_conj(rt.r(si), Value::fixnum(i));
            rt.set_r(si, ns);
        }
        let out = rt.r(si);
        rt.pop_to(si);
        out
    }

    #[test]
    fn conj_disj_contains() {
        let mut rt = Rt::new();
        let s = build(&mut rt, 1000);
        let si = rt.push(s);
        assert_eq!(rt.set_count(rt.r(si)), 1000);
        for i in 0..1000 {
            assert!(rt.set_contains(rt.r(si), Value::fixnum(i)), "missing {i}");
        }
        assert!(!rt.set_contains(rt.r(si), Value::fixnum(1000)));
        // conj of a present element is identity
        let again = rt.set_conj(rt.r(si), Value::fixnum(7));
        assert_eq!(again, rt.r(si));
        let smaller = rt.set_disj(rt.r(si), Value::fixnum(7));
        assert_eq!(rt.set_count(smaller), 999);
        assert!(!rt.set_contains(smaller, Value::fixnum(7)));
        assert!(rt.set_contains(rt.r(si), Value::fixnum(7)), "source unchanged");
    }

    #[test]
    fn get_returns_the_stored_element() {
        let mut rt = Rt::new();
        let s = rt.empty_set();
        let long = "a string long enough to avoid interning entirely, so that equality has to compare bytes";
        let stored = rt.string(long);
        let si = rt.push(stored);
        let s = rt.set_conj(s, rt.r(si));
        let probe = rt.string(long);
        assert_ne!(probe, rt.r(si), "the probe really is a different object");
        let got = rt.set_get(s, probe, NIL);
        assert_eq!(got, rt.r(si), "get must return the stored element, not the probe");
    }

    #[test]
    fn equality_and_hash_match_clojure() {
        let mut rt = Rt::new();
        let e = rt.empty_set();
        assert_eq!(rt.hash_value(e) as i32, -15128758, "#{{}}");
        let s1 = rt.set_conj(e, Value::fixnum(1));
        assert_eq!(rt.hash_value(s1) as i32, 1038464948, "#{{1}}");
        let si = rt.push(s1);
        let s2 = rt.set_conj(rt.r(si), Value::fixnum(2));
        let s2i = rt.push(s2);
        let s3 = rt.set_conj(rt.r(s2i), Value::fixnum(3));
        let s3i = rt.push(s3);
        assert_eq!(rt.hash_value(rt.r(s3i)) as i32, 439094965, "#{{1 2 3}}");

        // Built in another order: equal, and equal hash.
        let t = rt.empty_set();
        let ti = rt.push(t);
        for i in [3i64, 1, 2] {
            let nt = rt.set_conj(rt.r(ti), Value::fixnum(i));
            rt.set_r(ti, nt);
        }
        assert!(rt.eq(rt.r(s3i), rt.r(ti)));
        assert_eq!(rt.hash_value(rt.r(ti)) as i32, 439094965);
        let u = rt.set_disj(rt.r(ti), Value::fixnum(1));
        assert!(!rt.eq(rt.r(s3i), u));
    }

    #[test]
    fn element_vector_covers_everything() {
        let mut rt = Rt::new();
        let s = build(&mut rt, 300);
        let v = rt.set_element_vector(s);
        let mut got: StdVec<i64> = (0..rt.vec_count(v))
            .map(|i| rt.vec_nth(v, i).unwrap().as_fixnum())
            .collect();
        got.sort();
        assert_eq!(got, (0..300i64).collect::<StdVec<_>>());
    }

    #[test]
    fn survives_collection() {
        let mut rt = Rt::new();
        rt.gc.stress = true;
        let s = rt.empty_set();
        let si = rt.push(s);
        for i in 0..200i64 {
            let k = rt.string(&alloc::format!("element {i}"));
            let ns = rt.set_conj(rt.r(si), k);
            rt.set_r(si, ns);
        }
        rt.collect();
        assert_eq!(rt.set_count(rt.r(si)), 200);
        for i in 0..200i64 {
            let k = rt.string(&alloc::format!("element {i}"));
            assert!(rt.set_contains(rt.r(si), k), "lost element {i}");
        }
    }
}
