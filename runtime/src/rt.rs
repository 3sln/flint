//! The runtime context: everything a running flint program owns.
//!
//! `Rt` deliberately holds the collector and the root set as *separate fields*,
//! so `gc.alloc(&mut roots, ..)` borrows both without a cell or a global. That
//! is why there is no `static mut RUNTIME` anywhere in flint, and why the same
//! code is safe to run in several threads on a host during tests.
//!
//! ## The one rule for native code
//!
//! **Any `Value` held in a Rust local across an allocation must be rooted.**
//! Rust locals are invisible to the collector; an allocation can move the object
//! out from under them. `Rt::push`/`Rt::r`/`Rt::pop_to` are the shadow stack.
//! `rooted!` wraps the common shape.

use alloc::vec::Vec;

use crate::gc::{Gc, Roots};
use crate::obj::*;
use crate::value::{Value, INLINE_MAX, NIL};

pub const SING_EMPTY_LIST: usize = 0;
pub const SING_EMPTY_VEC: usize = 1;
pub const SING_EMPTY_MAP: usize = 2;
pub const SING_EMPTY_SET: usize = 3;
pub const SING_COUNT: usize = 4;

pub struct Rt {
    pub gc: Gc,
    pub roots: Roots,
    /// The in-flight thrown value, or `nil`. Native builtins signal failure by
    /// setting this and returning `nil`; the VM checks it after every call.
    pub thrown: Value,
    /// Out-parameter for CHAMP insert/remove: did the entry count change?
    /// A scratch field rather than a tuple return, because every one of those
    /// returns would otherwise have to be threaded through the rooting dance.
    pub champ_added: bool,
}

/// Root `$v` for the duration of `$body`, rebinding the name to the (possibly
/// moved) value on each use via `rt.r(idx)`.
#[macro_export]
macro_rules! rooted {
    ($rt:expr, $($name:ident),+ => $body:block) => {{
        let __base = $rt.roots.shadow.len();
        $(let $name = { $rt.roots.shadow.push($name); $rt.roots.shadow.len() - 1 };)+
        let __r = (|| $body)();
        $rt.roots.shadow.truncate(__base);
        __r
    }};
}

impl Rt {
    pub fn new() -> Rt {
        Rt::with_heap(2 * 1024 * 1024, 512 * 1024 * 1024)
    }

    pub fn with_heap(nursery: u32, max: u32) -> Rt {
        let mut rt =
            Rt { gc: Gc::new(nursery, max), roots: Roots::new(), thrown: NIL, champ_added: false };
        rt.roots.singletons = alloc::vec![NIL; SING_COUNT];
        rt.init_singletons();
        rt
    }

    fn init_singletons(&mut self) {
        let a = self.alloc(TY_EMPTY_LIST, 1);
        self.roots.singletons[SING_EMPTY_LIST] = Value::heap(a);
        self.init_vector();
        self.init_map();
        self.init_set();
    }

    // --- shadow roots ------------------------------------------------------

    #[inline]
    pub fn push(&mut self, v: Value) -> usize {
        self.roots.shadow.push(v);
        self.roots.shadow.len() - 1
    }
    #[inline]
    pub fn r(&self, i: usize) -> Value {
        self.roots.shadow[i]
    }
    #[inline]
    pub fn set_r(&mut self, i: usize, v: Value) {
        self.roots.shadow[i] = v;
    }
    #[inline]
    pub fn mark(&self) -> usize {
        self.roots.shadow.len()
    }
    #[inline]
    pub fn pop_to(&mut self, n: usize) {
        self.roots.shadow.truncate(n);
    }

    // --- allocation --------------------------------------------------------

    #[inline]
    pub fn alloc(&mut self, ty: u8, len: u32) -> u32 {
        let a = self.gc.alloc(&mut self.roots, ty, len);
        debug_assert!(a != 0 || self.gc.oom);
        a
    }

    #[inline]
    pub fn slot(&self, v: Value, i: u32) -> Value {
        slot(&self.gc.sp, v.as_heap(), i)
    }
    #[inline]
    pub fn set(&mut self, v: Value, i: u32, x: Value) {
        self.gc.set_slot(v.as_heap(), i, x);
    }
    #[inline]
    pub fn ty(&self, v: Value) -> u8 {
        if v.is_heap() {
            ty(&self.gc.sp, v.as_heap())
        } else {
            255
        }
    }
    #[inline]
    pub fn olen(&self, v: Value) -> u32 {
        len(&self.gc.sp, v.as_heap())
    }

    pub fn singleton(&self, i: usize) -> Value {
        self.roots.singletons[i]
    }
    pub fn empty_list(&self) -> Value {
        self.roots.singletons[SING_EMPTY_LIST]
    }
    pub fn empty_vec(&self) -> Value {
        self.roots.singletons[SING_EMPTY_VEC]
    }
    pub fn empty_map(&self) -> Value {
        self.roots.singletons[SING_EMPTY_MAP]
    }
    pub fn empty_set(&self) -> Value {
        self.roots.singletons[SING_EMPTY_SET]
    }

    pub fn collect(&mut self) {
        self.gc.major(&mut self.roots);
    }

    /// A borrowed view of any string value. The `buf` argument exists because an
    /// inline string's bytes live in the `Value` itself and have nowhere else to
    /// be borrowed from.
    ///
    /// The returned `&str` borrows `self`, so the borrow checker will reject any
    /// attempt to hold it across an allocation -- which is exactly the bug it
    /// would otherwise be.
    pub fn as_str<'a>(&'a self, v: Value, buf: &'a mut [u8; INLINE_MAX]) -> Option<&'a str> {
        if v.is_inline_str() {
            core::str::from_utf8(v.inline_bytes(buf)).ok()
        } else if v.is_heap() && ty(&self.gc.sp, v.as_heap()) == TY_STR {
            core::str::from_utf8(str_bytes(&self.gc.sp, v.as_heap())).ok()
        } else {
            None
        }
    }

    pub fn is_string(&self, v: Value) -> bool {
        v.is_inline_str() || (v.is_heap() && ty(&self.gc.sp, v.as_heap()) == TY_STR)
    }

    /// Byte length of a string value.
    pub fn str_len(&self, v: Value) -> u32 {
        if v.is_inline_str() {
            v.inline_len() as u32
        } else {
            len(&self.gc.sp, v.as_heap())
        }
    }
}

impl Default for Rt {
    fn default() -> Self {
        Rt::new()
    }
}

/// Scratch buffer type for `as_str`.
pub type SBuf = [u8; INLINE_MAX];
#[inline]
pub fn sbuf() -> SBuf {
    [0u8; INLINE_MAX]
}

/// Convenience: collect a `Vec<Value>` of shadow-root indices.
pub struct Frame(pub usize);
impl Frame {
    pub fn open(rt: &Rt) -> Frame {
        Frame(rt.mark())
    }
    pub fn close(self, rt: &mut Rt) {
        rt.pop_to(self.0)
    }
}

pub type ValVec = Vec<Value>;
