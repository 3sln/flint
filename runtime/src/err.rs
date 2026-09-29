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

/// A thrown value as `Kind: message`, for a host that has to print it.
///
/// ONE COPY, BECAUSE THERE WERE FOUR. These rules -- kind or `"Error"`, message
/// or nothing, joined by `": "` -- were written out in `abi::finish_run`,
/// `native::rendered`, `native`'s `call_named` arm, and once more in
/// `lib/flint/system.cljc` for the control plane. The comment on `status_of`
/// says "on the same rules as `abi::finish_run`", which is how four copies stay
/// in step until one of them is fixed alone: on 2026-09-29 I fixed `abi.rs`
/// first and the behaviour did not move, because nothing in `runtime/src` or
/// `cli/src` calls it -- it is the wasm ABI's renderer (AGENTS.md sec. 1).
///
/// A THROWN VALUE THAT IS NOT AN EXCEPTION IS NAMED BY KIND. flint lets a
/// program throw anything, so `ex_message` of a keyword is not a string, and
/// every one of those copies rendered `(throw :boom)` as exactly "Error: " --
/// a failure that said nothing about itself. MEASURED against jank's suite,
/// where it is 12 of the `pass-*` failures: `form/try/*` throws a keyword past
/// a `catch` naming a jank host type flint cannot resolve.
///
/// The KIND and not the value, for `flint.system`'s reason: printing the value
/// needs the whole printer, and on that path it cost +9 175 bytes of wasm in
/// every program. `kind_of` is a primitive.
pub fn render_thrown(rt: &mut Rt, e: Value) -> alloc::string::String {
    let mut b = crate::rt::sbuf();
    let kind: alloc::string::String = {
        let k = rt.ex_kind(e);
        rt.as_str(k, &mut b).unwrap_or("Error").into()
    };
    let mut b2 = crate::rt::sbuf();
    let msg: alloc::string::String = {
        let m = rt.ex_message(e);
        rt.as_str(m, &mut b2).unwrap_or("").into()
    };
    let msg = if msg.is_empty() {
        let kv = rt.kind_of(e);
        let nm = rt.name_of(kv);
        let mut b3 = crate::rt::sbuf();
        match rt.as_str(nm, &mut b3) {
            Some(w) => alloc::format!("a {w} was thrown, with no message"),
            None => alloc::string::String::from("a value was thrown, with no message"),
        }
    } else {
        msg
    };
    alloc::format!("{kind}: {msg}")
}

// `ex_info`, `is_exception`, `ex_message`, `ex_data` and `ex_kind` are
// GENERATED, from `kin/exinfo.kin`. They were the same algorithm in all three
// runtimes -- mark, push four, allocate, check for a failed allocation, fill
// the slots, pop -- written out three times, which is the case `join_strings`
// next door is NOT: that one is three implementations using what each host
// has, and unifying it would slow every runtime to the least equipped.
//
// The slot constants stay here: they are what the generated code reaches for.
pub const EX_KIND: u32 = 0;
pub const EX_MSG: u32 = 1;
pub const EX_DATA: u32 = 2;
pub const EX_CAUSE: u32 = 3;

impl Rt {

    /// Does an exception match a `catch` clause's name?
    ///
    /// flint has no class hierarchy -- an exception carries a KIND STRING -- so
    /// a `catch` used to compare that string for equality. Which meant
    /// `(catch Exception e ...)`, the single most common form in real Clojure,
    /// matched nothing at all: every kind flint raises is `ExceptionInfo`,
    /// `ClassCastException`, `ArithmeticException` and so on, and none of them
    /// is spelled `Exception`. A ported program's error handling silently did
    /// not run. The emitter's own comment said "`Throwable`/`Exception` match
    /// anything", so the intent was recorded and the code did not do it.
    ///
    /// The rules are Java's, over flat names rather than classes:
    ///
    /// * `Throwable` matches everything;
    /// * `Exception` and `RuntimeException` match everything that is not an
    ///   `…Error`, which is the distinction Java draws and the one a program
    ///   catching broadly still wants -- a stack overflow should not be
    ///   swallowed by a `catch Exception` around a parser;
    /// * `Error` matches the `…Error`s;
    /// * anything else is an exact match, as before.
    pub fn ex_matches(&mut self, e: Value, name: Value) -> Value {
        let kind = self.ex_kind(e);
        let mut kb = crate::rt::sbuf();
        let mut nb = crate::rt::sbuf();
        let k: alloc::string::String = self.as_str(kind, &mut kb).unwrap_or("").into();
        let n: alloc::string::String = self.as_str(name, &mut nb).unwrap_or("").into();
        let is_error = k.ends_with("Error");
        let hit = match n.as_str() {
            "Throwable" => true,
            "Exception" | "RuntimeException" => !is_error,
            "Error" => is_error,
            other => k == other,
        };
        Value::boolean(hit)
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

    /// Both operands NAMED. The arguments were `_a` and `_b` -- taken and
    /// discarded -- so the message said "argument is not a number" about a
    /// binary operation without saying which argument or what it was, while
    /// both ports said "not a number: an integer and a keyword".
    pub fn throw_not_a_number(&mut self, a: Value, b: Value) -> Value {
        let msg = alloc::format!("not a number: {} and {}", self.describe(a), self.describe(b));
        self.throw_str("ClassCastException", &msg)
    }

    /// The ONE-OPERAND form. `to-long` reached for the pair above and passed
    /// the same value twice, so converting a string reported "not a number: a
    /// string and a string" -- describing an operand that does not exist. The
    /// ports said "not a number: a string" and threw a DIFFERENT class, so
    /// this was two divergences reading as one.
    ///
    /// `ClassCastException` and not `IllegalArgumentException`, because that
    /// is what Clojure throws for `(long "x")` and the class is the part a
    /// `catch` selects on.
    pub fn throw_not_a_number1(&mut self, a: Value) -> Value {
        let msg = alloc::format!("not a number: {}", self.describe(a));
        self.throw_str("ClassCastException", &msg)
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
