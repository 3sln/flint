//! Errors.
//!
//! flint has no unwinding. A native builtin signals failure by setting
//! `rt.thrown` and returning `nil`; the VM checks `thrown` after every call and
//! unwinds its own frames to the nearest handler. That keeps the whole runtime
//! free of `Result` plumbing on the hot path, and means a builtin that forgets
//! to check is at worst operating on `nil` rather than on garbage.
//!
//! `TY_EXINFO [kind, msg, data, cause]` — `kind` is a string naming the sort of
//! failure ("ArithmeticException", "ExceptionInfo", ...), so that a ported
//! program's `(catch ExceptionInfo e ...)` has something to match on without a
//! class hierarchy.

use crate::obj::*;
use crate::rt::Rt;
use crate::value::{Value, NIL};

pub const EX_KIND: u32 = 0;
pub const EX_MSG: u32 = 1;
pub const EX_DATA: u32 = 2;
pub const EX_CAUSE: u32 = 3;

impl Rt {
    pub fn ex_info(&mut self, kind: Value, msg: Value, data: Value, cause: Value) -> Value {
        let base = self.mark();
        let k = self.push(kind);
        let m = self.push(msg);
        let d = self.push(data);
        let c = self.push(cause);
        let a = self.alloc(TY_EXINFO, 4);
        if a == 0 {
            self.pop_to(base);
            return NIL;
        }
        let (kind, msg, data, cause) = (self.r(k), self.r(m), self.r(d), self.r(c));
        self.pop_to(base);
        self.gc.set_slot(a, EX_KIND, kind);
        self.gc.set_slot(a, EX_MSG, msg);
        self.gc.set_slot(a, EX_DATA, data);
        self.gc.set_slot(a, EX_CAUSE, cause);
        Value::heap(a)
    }

    pub fn is_exception(&self, v: Value) -> bool {
        v.is_heap() && ty(&self.gc.sp, v.as_heap()) == TY_EXINFO
    }

    /// Build an exception value without throwing it. The scheduler needs this:
    /// it hands an error to a *parked* thread, to be raised when that thread is
    /// next resumed rather than in whatever thread noticed the problem.
    pub fn make_error(&mut self, kind: &str, msg: &str) -> Value {
        let k = self.string(kind);
        let ki = self.push(k);
        let m = self.string(msg);
        let k = self.r(ki);
        self.pop_to(ki);
        self.ex_info(k, m, NIL, NIL)
    }

    /// Set the pending exception and return `nil`, which is what a failing
    /// builtin returns.
    pub fn throw_str(&mut self, kind: &str, msg: &str) -> Value {
        let e = self.make_error(kind, msg);
        self.thrown = e;
        NIL
    }

    pub fn throw_value(&mut self, v: Value) -> Value {
        self.thrown = v;
        NIL
    }

    pub fn throw_not_a_number(&mut self, _a: Value, _b: Value) -> Value {
        self.throw_str("ClassCastException", "argument is not a number")
    }

    pub fn ex_message(&self, e: Value) -> Value {
        if self.is_exception(e) {
            self.slot(e, EX_MSG)
        } else {
            NIL
        }
    }
    pub fn ex_data(&self, e: Value) -> Value {
        if self.is_exception(e) {
            self.slot(e, EX_DATA)
        } else {
            NIL
        }
    }
    pub fn ex_kind(&self, e: Value) -> Value {
        if self.is_exception(e) {
            self.slot(e, EX_KIND)
        } else {
            NIL
        }
    }

    #[inline]
    pub fn failed(&self) -> bool {
        !self.thrown.is_nil()
    }
    #[inline]
    pub fn clear_error(&mut self) -> Value {
        core::mem::replace(&mut self.thrown, NIL)
    }
}
