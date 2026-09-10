//! `PersistentVector`: a 32-way trie with a tail, and its transient.
//!
//! The shape is Clojure's, because Clojure's is right: a tail buffer turns
//! `conj` into a 32-element array copy amortised to O(1), and `nth` into at most
//! `depth` indexed loads. What differs is bookkeeping — a node here is a normal
//! GC object whose slot 0 is the transient ownership token (`nil` when
//! persistent), rather than a Java array plus an `AtomicReference`.
//!
//! ```text
//!   TY_VEC   [cnt, shift, root, tail, meta]
//!   TY_NODE  [edit, e0, e1, ... ]        -- internal nodes are always 32 wide
//! ```

use crate::obj::*;
use crate::rt::Rt;
use crate::value::{Value, NIL, NOT_FOUND};

pub const BITS: u32 = 5;
pub const WIDTH: u32 = 1 << BITS; // 32
pub const MASK: u32 = WIDTH - 1;

// --- node helpers ----------------------------------------------------------

impl Rt {
}

// --- vector ----------------------------------------------------------------

pub const V_CNT: u32 = 0;
pub const V_SHIFT: u32 = 1;
pub const V_ROOT: u32 = 2;
pub const V_TAIL: u32 = 3;
pub const V_META: u32 = 4;
/// Cached `hash`, `nil` until first asked for.
pub const V_HASH: u32 = 5;

impl Rt {
    pub(crate) fn init_vector(&mut self) {
        let root = self.new_node(WIDTH, NIL);
        let r = self.push(root);
        let tail = self.new_node(0, NIL);
        let t = self.push(tail);
        let a = self.alloc(TY_VEC, 6);
        let (root, tail) = (self.r(r), self.r(t));
        self.pop_to(r);
        self.set_slot(a, V_CNT, Value::fixnum(0));
        self.set_slot(a, V_SHIFT, Value::fixnum(BITS as i64));
        self.set_slot(a, V_ROOT, root);
        self.set_slot(a, V_TAIL, tail);
        self.set_slot(a, V_META, NIL);
        self.set_slot(a, V_HASH, NIL);
        self.roots.shared.singletons[crate::rt::SING_EMPTY_VEC] = Value::heap(a);
    }

    pub fn is_vector(&self, v: Value) -> bool {
        v.is_heap() && ty(&self.gc.sp, v.as_heap()) == TY_VEC
    }


    /// A map entry as a real two-element vector, for the operations that
    /// Clojure gives vector semantics: `conj` appends, `assoc` replaces.
    pub fn map_entry_as_vec(&mut self, e: Value) -> Value {
        let base = self.mark();
        self.push(self.slot(e, 0));
        self.push(self.slot(e, 1));
        let out = self.vec_from_roots(base, 2);
        self.pop_to(base);
        out
    }
    // @kin:link:ns: flint.rt.vector
    //
    // THE SPELLING LIVES WITH THE FUNCTION. This said the same thing in
    // `flint.impl.rt` as `(sibling "vec_count" "Vec" "count" 1)`, one table
    // away in another repository, where renaming the function below left it
    // silently wrong. Here the two cannot drift apart without the rename
    // walking straight past the line that has to change with it.
    // @kin:link:form:vec-count: {:template "{0}.vec_count({1})"}
    #[inline]
    pub fn vec_count(&self, v: Value) -> u32 {
        slot(&self.gc.sp, v.as_heap(), V_CNT).as_fixnum() as u32
    }
    #[inline]
    
    #[inline]









    /// Build a vector from a slice of values already on the shadow stack.
    /// `base` is the shadow index of the first element.
    pub fn vec_from_roots(&mut self, base: usize, n: usize) -> Value {
        let mut v = self.empty_vec();
        let vi = self.push(v);
        for i in 0..n {
            let x = self.r(base + i);
            let nv = self.vec_conj(self.r(vi), x);
            self.set_r(vi, nv);
        }
        v = self.r(vi);
        self.pop_to(vi);
        v
    }
}

// --- transient vector ------------------------------------------------------
//
// TY_TVEC [cnt, shift, root, tail, edit]
//
// `edit` is a freshly allocated object used purely for its identity. A node
// whose slot 0 is that same object is owned by this transient and is mutated in
// place; any other node is copied once and thereafter owned. `persistent!`
// clears `edit`, so a stale handle fails loudly instead of corrupting a value
// somebody else is holding.

pub const T_CNT: u32 = 0;
pub const T_SHIFT: u32 = 1;
pub const T_ROOT: u32 = 2;
pub const T_TAIL: u32 = 3;
pub const T_EDIT: u32 = 4;

// A TRANSIENT'S HEADER IS A VECTOR'S HEADER, PREFIX-WISE, AND THAT IS NOW
// LOAD-BEARING RATHER THAN A COINCIDENCE.
//
// `kin/vectrans.kin` reads a transient with the VECTOR's readers -- `vec-nth`,
// `array-for`, `tail-off`, `vec-shift` -- because the four bodies were
// byte-identical once the parameter was renamed. That is only sound while
// these four slots agree, and nothing said so out loud until now.
//
// Asserted at COMPILE TIME rather than trusted: put `T_EDIT` in front of
// `T_TAIL` one day and the build stops here, instead of four readers quietly
// returning the wrong slot on every transient in the system. The layouts are
// free to differ from slot 4 on, which is where they already do -- a vector
// keeps `meta` and `hash`, a transient its `edit` token.
const _: () = assert!(T_CNT == V_CNT);
const _: () = assert!(T_SHIFT == V_SHIFT);
const _: () = assert!(T_ROOT == V_ROOT);
const _: () = assert!(T_TAIL == V_TAIL);

impl Rt {












}

#[cfg(test)]
mod tests {
    use super::*;
    use alloc::vec::Vec as StdVec;

    fn build(rt: &mut Rt, n: u32) -> Value {
        let mut v = rt.empty_vec();
        let vi = rt.push(v);
        for i in 0..n {
            let nv = rt.vec_conj(rt.r(vi), Value::fixnum(i as i64));
            rt.set_r(vi, nv);
        }
        v = rt.r(vi);
        rt.pop_to(vi);
        v
    }

    fn to_vec(rt: &Rt, v: Value) -> StdVec<i64> {
        (0..rt.vec_count(v)).map(|i| rt.vec_nth(v, i, crate::value::NIL).as_fixnum()).collect()
    }

    #[test]
    fn empty_vector_is_a_shared_singleton() {
        let rt = Rt::new();
        assert_eq!(rt.vec_count(rt.empty_vec()), 0);
        assert!(rt.is_vector(rt.empty_vec()));
        assert_eq!(rt.vec_nth(rt.empty_vec(), 0, NOT_FOUND), NOT_FOUND);
    }

    #[test]
    fn conj_and_nth_across_every_depth_boundary() {
        let mut rt = Rt::new();
        // 32^1, 32^2 and a bit past 32^3 exercise every shift transition.
        for n in [0u32, 1, 31, 32, 33, 1023, 1024, 1025, 32768, 33000] {
            let v = build(&mut rt, n);
            assert_eq!(rt.vec_count(v), n, "count at n={n}");
            for i in 0..n {
                assert_eq!(rt.vec_nth(v, i, NOT_FOUND).as_fixnum(), i as i64, "nth {i} of {n}");
            }
            assert_eq!(rt.vec_nth(v, n, NOT_FOUND), NOT_FOUND, "out of range at n={n}");
        }
    }

    #[test]
    fn conj_is_persistent() {
        let mut rt = Rt::new();
        let a = build(&mut rt, 40);
        let ai = rt.push(a);
        let b = rt.vec_conj(rt.r(ai), Value::fixnum(999));
        assert_eq!(rt.vec_count(rt.r(ai)), 40, "the original is untouched");
        assert_eq!(rt.vec_count(b), 41);
        assert_eq!(rt.vec_nth(b, 40, crate::value::NIL).as_fixnum(), 999);
        assert_eq!(rt.vec_nth(rt.r(ai), 39, crate::value::NIL).as_fixnum(), 39);
    }

    #[test]
    fn assoc_replaces_without_disturbing_the_original() {
        let mut rt = Rt::new();
        for n in [1u32, 32, 33, 1025, 5000] {
            let a = build(&mut rt, n);
            let ai = rt.push(a);
            for i in [0u32, n / 2, n - 1] {
                let b = rt.vec_assoc(rt.r(ai), i, Value::fixnum(-1));
                assert_eq!(rt.vec_nth(b, i, crate::value::NIL).as_fixnum(), -1, "n={n} i={i}");
                assert_eq!(rt.vec_count(b), n);
                assert_eq!(rt.vec_nth(rt.r(ai), i, crate::value::NIL).as_fixnum(), i as i64, "original intact");
                // every other index unchanged
                for j in [0u32, n / 3, n - 1] {
                    if j != i {
                        assert_eq!(rt.vec_nth(b, j, crate::value::NIL).as_fixnum(), j as i64);
                    }
                }
            }
            rt.pop_to(ai);
        }
    }

    #[test]
    fn assoc_at_count_appends() {
        let mut rt = Rt::new();
        let a = build(&mut rt, 5);
        let b = rt.vec_assoc(a, 5, Value::fixnum(5));
        assert_eq!(to_vec(&rt, b), alloc::vec![0, 1, 2, 3, 4, 5]);
    }

    #[test]
    fn pop_unwinds_the_trie_exactly() {
        let mut rt = Rt::new();
        let n = 2100u32;
        let mut v = build(&mut rt, n);
        let vi = rt.push(v);
        for k in (0..n).rev() {
            let nv = rt.vec_pop(rt.r(vi));
            rt.set_r(vi, nv);
            assert_eq!(rt.vec_count(rt.r(vi)), k, "after popping down to {k}");
            if k > 0 {
                assert_eq!(rt.vec_nth(rt.r(vi), k - 1, crate::value::NIL).as_fixnum(), (k - 1) as i64);
            }
        }
        v = rt.r(vi);
        assert_eq!(rt.vec_count(v), 0);
    }

    #[test]
    fn pop_is_persistent() {
        let mut rt = Rt::new();
        let a = build(&mut rt, 100);
        let ai = rt.push(a);
        let b = rt.vec_pop(rt.r(ai));
        assert_eq!(rt.vec_count(rt.r(ai)), 100);
        assert_eq!(rt.vec_count(b), 99);
    }

    #[cfg(feature = "diagnostics")]
    #[test]
    fn survives_collection_at_every_allocation() {
        let mut rt = Rt::new();
        rt.gc.stress = true;
        let v = build(&mut rt, 400);
        let vi = rt.push(v);
        rt.collect();
        assert_eq!(to_vec(&rt, rt.r(vi)), (0..400i64).collect::<StdVec<_>>());
        let b = rt.vec_assoc(rt.r(vi), 200, Value::fixnum(-7));
        let bi = rt.push(b);
        rt.collect();
        assert_eq!(rt.vec_nth(rt.r(bi), 200, crate::value::NIL).as_fixnum(), -7);
        assert_eq!(rt.vec_nth(rt.r(vi), 200, crate::value::NIL).as_fixnum(), 200);
    }

    #[test]
    fn deep_vectors_hold_heap_values() {
        let mut rt = Rt::new();
        let mut v = rt.empty_vec();
        let vi = rt.push(v);
        for i in 0..2000 {
            let s = rt.string(&alloc::format!("element number {i}"));
            let nv = rt.vec_conj(rt.r(vi), s);
            rt.set_r(vi, nv);
        }
        rt.collect();
        v = rt.r(vi);
        let mut b = crate::rt::sbuf();
        for i in [0u32, 999, 1999] {
            let s = rt.vec_nth(v, i, crate::value::NIL);
            assert_eq!(rt.as_str(s, &mut b), Some(alloc::format!("element number {i}").as_str()));
            let mut b2 = crate::rt::sbuf();
            let _ = &mut b2;
        }
    }
}

#[cfg(test)]
mod transient_tests {
    use super::*;
    use alloc::vec::Vec as StdVec;

    fn tv_to_vec(rt: &Rt, t: Value) -> StdVec<i64> {
        (0..rt.tvec_count(t)).map(|i| rt.tvec_nth(t, i, crate::value::NIL).as_fixnum()).collect()
    }
    fn to_vec(rt: &Rt, v: Value) -> StdVec<i64> {
        (0..rt.vec_count(v)).map(|i| rt.vec_nth(v, i, crate::value::NIL).as_fixnum()).collect()
    }

    #[test]
    fn transient_conj_then_persistent_round_trips() {
        let mut rt = Rt::new();
        for n in [0u32, 1, 31, 32, 33, 1024, 1025, 5000] {
            let e = rt.empty_vec();
            let t = rt.vec_transient(e);
            let ti = rt.push(t);
            for i in 0..n {
                let nt = rt.tvec_conj(rt.r(ti), Value::fixnum(i as i64));
                rt.set_r(ti, nt);
            }
            assert_eq!(rt.tvec_count(rt.r(ti)), n);
            let v = rt.tvec_persistent(rt.r(ti));
            assert_eq!(rt.vec_count(v), n, "n={n}");
            assert_eq!(to_vec(&rt, v), (0..n as i64).collect::<StdVec<_>>(), "n={n}");
            rt.pop_to(ti);
        }
    }

    #[test]
    fn transient_does_not_disturb_the_source_vector() {
        let mut rt = Rt::new();
        let mut v = rt.empty_vec();
        let vi = rt.push(v);
        for i in 0..100 {
            let nv = rt.vec_conj(rt.r(vi), Value::fixnum(i));
            rt.set_r(vi, nv);
        }
        let t = rt.vec_transient(rt.r(vi));
        let ti = rt.push(t);
        for i in 0..100 {
            let nt = rt.tvec_conj(rt.r(ti), Value::fixnum(1000 + i));
            rt.set_r(ti, nt);
        }
        let nt = rt.tvec_assoc(rt.r(ti), 0, Value::fixnum(-5));
        rt.set_r(ti, nt);
        v = rt.r(vi);
        assert_eq!(rt.vec_count(v), 100, "source count unchanged");
        assert_eq!(to_vec(&rt, v), (0..100i64).collect::<StdVec<_>>(), "source contents unchanged");
        let out = rt.tvec_persistent(rt.r(ti));
        assert_eq!(rt.vec_count(out), 200);
        assert_eq!(rt.vec_nth(out, 0, crate::value::NIL).as_fixnum(), -5);
        assert_eq!(rt.vec_nth(out, 199, crate::value::NIL).as_fixnum(), 1099);
    }

    #[test]
    fn transient_assoc_and_pop() {
        let mut rt = Rt::new();
        let e = rt.empty_vec();
        let t = rt.vec_transient(e);
        let ti = rt.push(t);
        for i in 0..2000 {
            let nt = rt.tvec_conj(rt.r(ti), Value::fixnum(i));
            rt.set_r(ti, nt);
        }
        for i in (0..2000).step_by(37) {
            let nt = rt.tvec_assoc(rt.r(ti), i as u32, Value::fixnum(-i));
            rt.set_r(ti, nt);
        }
        for i in (0..2000).step_by(37) {
            assert_eq!(rt.tvec_nth(rt.r(ti), i as u32, crate::value::NIL).as_fixnum(), -i);
        }
        for _ in 0..500 {
            let nt = rt.tvec_pop(rt.r(ti));
            rt.set_r(ti, nt);
        }
        assert_eq!(rt.tvec_count(rt.r(ti)), 1500);
        let v = rt.tvec_persistent(rt.r(ti));
        assert_eq!(rt.vec_count(v), 1500);
        assert_eq!(rt.vec_nth(v, 1499, crate::value::NIL).as_fixnum(), 1499);
        assert_eq!(rt.vec_nth(v, 37, crate::value::NIL).as_fixnum(), -37);
    }

    #[test]
    fn persistent_invalidates_the_handle() {
        let mut rt = Rt::new();
        let e = rt.empty_vec();
        let t = rt.vec_transient(e);
        let ti = rt.push(t);
        let nt = rt.tvec_conj(rt.r(ti), Value::fixnum(1));
        rt.set_r(ti, nt);
        assert!(rt.tvec_alive(rt.r(ti)));
        let _ = rt.tvec_persistent(rt.r(ti));
        assert!(!rt.tvec_alive(rt.r(ti)), "a used-up transient must be detectably dead");
    }

    #[test]
    fn persistent_result_is_independent_of_further_transient_use() {
        // The classic transient bug: `persistent!` hands back a value that a
        // still-live handle can mutate. It must not.
        let mut rt = Rt::new();
        let e = rt.empty_vec();
        let t = rt.vec_transient(e);
        let ti = rt.push(t);
        for i in 0..40 {
            let nt = rt.tvec_conj(rt.r(ti), Value::fixnum(i));
            rt.set_r(ti, nt);
        }
        let v = rt.tvec_persistent(rt.r(ti));
        let vi = rt.push(v);
        // Re-transient the *result* and keep going; the first result must not move.
        let t2 = rt.vec_transient(rt.r(vi));
        let t2i = rt.push(t2);
        for i in 0..40 {
            let nt = rt.tvec_conj(rt.r(t2i), Value::fixnum(100 + i));
            rt.set_r(t2i, nt);
        }
        let nt = rt.tvec_assoc(rt.r(t2i), 0, Value::fixnum(-99));
        rt.set_r(t2i, nt);
        let _ = rt.tvec_persistent(rt.r(t2i));
        assert_eq!(to_vec(&rt, rt.r(vi)), (0..40i64).collect::<StdVec<_>>(),
                   "the earlier persistent result was mutated through a later transient");
    }

    #[cfg(feature = "diagnostics")]
    #[test]
    fn transients_survive_collection_at_every_allocation() {
        let mut rt = Rt::new();
        rt.gc.stress = true;
        let e = rt.empty_vec();
        let t = rt.vec_transient(e);
        let ti = rt.push(t);
        for i in 0..300 {
            let nt = rt.tvec_conj(rt.r(ti), Value::fixnum(i));
            rt.set_r(ti, nt);
        }
        assert_eq!(tv_to_vec(&rt, rt.r(ti)), (0..300i64).collect::<StdVec<_>>());
        let v = rt.tvec_persistent(rt.r(ti));
        let vi = rt.push(v);
        rt.collect();
        assert_eq!(to_vec(&rt, rt.r(vi)), (0..300i64).collect::<StdVec<_>>());
    }
}
