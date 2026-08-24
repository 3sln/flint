//! `flint.conc` — the builtins for green threads and ports, plus the host-facing
//! exports that let a host service a parked thread.
//!
//! This is a **unit** (`doc/decisions/0003`), which is the whole point: a
//! program that never mentions `spawn`, `channel` or `open` never reaches any of
//! these symbols, so `--gc-sections` deletes them and the module is the size it
//! was before green threads existed. `test/threads.clj` asserts that.
//!
//! Nothing here suspends a wasm frame. `receive` on an empty port does not block
//! the host and does not unwind the module's stack: it marks the green thread as
//! not-runnable and returns to the scheduler, which is a loop inside the
//! interpreter. See `doc/decisions/0005`, section 1.

#![cfg_attr(not(feature = "host-tools"), no_std)]

extern crate alloc;

use flint_rt::conc;
use flint_rt::rt::Rt;
use flint_rt::value::{Value, FALSE, NIL, TRUE};

macro_rules! builtin {
    ($export:ident, $inner:ident, |$rt:ident, $a:ident, $n:ident| $body:block) => {
        pub fn $inner($rt: &mut Rt, $a: usize, $n: usize) -> Value $body

        #[no_mangle]
        pub extern "C" fn $export(rt: *mut Rt, base: u32, argc: u32) -> u64 {
            unsafe { $inner(&mut *rt, base as usize, argc as usize).0 }
        }
    };
}

#[inline]
fn arg(rt: &Rt, a: usize, i: usize) -> Value {
    rt.vat(a + i)
}

// --- threads ---------------------------------------------------------------

builtin!(flint_b_spawn, b_spawn, |rt, a, n| {
    let _ = n;
    let f = arg(rt, a, 0);
    rt.spawn_thread(f)
});

builtin!(flint_b_yield, b_yield, |rt, a, n| {
    let _ = (a, n);
    rt.ensure_sched();
    rt.park(conc::PARK_YIELD)
});

builtin!(flint_b_self, b_self, |rt, a, n| {
    let _ = (a, n);
    rt.ensure_sched();
    rt.current_thread()
});

builtin!(flint_b_thread_p, b_thread_p, |rt, a, n| {
    let _ = n;
    Value::boolean(rt.is_thread(arg(rt, a, 0)))
});

builtin!(flint_b_thread_state, b_thread_state, |rt, a, n| {
    let _ = n;
    let t = arg(rt, a, 0);
    if !rt.is_thread(t) {
        return rt.throw_str("ClassCastException", "thread-state wants a thread");
    }
    let s = rt.slot(t, conc::TH_STATUS).as_fixnum();
    let name = match s {
        conc::ST_NEW => "new",
        conc::ST_RUNNABLE => "runnable",
        conc::ST_PARKED => "parked",
        conc::ST_DONE => "done",
        _ => "failed",
    };
    rt.keyword(None, name)
});

builtin!(flint_b_thread_result, b_thread_result, |rt, a, n| {
    let _ = n;
    let t = arg(rt, a, 0);
    if !rt.is_thread(t) {
        return rt.throw_str("ClassCastException", "thread-result wants a thread");
    }
    rt.slot(t, conc::TH_RESULT)
});

builtin!(flint_b_thread_id, b_thread_id, |rt, a, n| {
    let _ = n;
    let t = arg(rt, a, 0);
    if !rt.is_thread(t) {
        return rt.throw_str("ClassCastException", "thread-id wants a thread");
    }
    rt.slot(t, conc::TH_ID)
});

builtin!(flint_b_thread_join, b_thread_join, |rt, a, n| {
    let _ = n;
    rt.ensure_sched();
    rt.thread_join(arg(rt, a, 0))
});

// --- dynamic bindings, per green thread ------------------------------------

builtin!(flint_b_binds, b_binds, |rt, a, n| {
    let _ = (a, n);
    rt.ensure_sched();
    let t = rt.current_thread();
    if t.is_nil() {
        return rt.empty_map();
    }
    rt.slot(t, conc::TH_BINDINGS)
});

builtin!(flint_b_set_binds, b_set_binds, |rt, a, n| {
    let _ = n;
    let m = arg(rt, a, 0);
    rt.ensure_sched();
    let t = rt.current_thread();
    if !t.is_nil() {
        rt.set(t, conc::TH_BINDINGS, m);
    }
    m
});

// --- ports -----------------------------------------------------------------

builtin!(flint_b_channel, b_channel, |rt, a, n| {
    let cap = if n > 0 { arg(rt, a, 0) } else { NIL };
    let label = if n > 1 { arg(rt, a, 1) } else { NIL };
    let c = if cap.is_fixnum() { cap.as_fixnum() } else { conc::DEFAULT_CAP };
    if c < 1 {
        return rt.throw_str("IllegalArgumentException", "a channel needs a buffer of at least 1");
    }
    rt.make_channel(c, label)
});

builtin!(flint_b_open, b_open, |rt, a, n| {
    let name = arg(rt, a, 0);
    let format = if n > 1 { arg(rt, a, 1) } else { NIL };
    if !rt.is_string(name) {
        return rt.throw_str("ClassCastException", "open wants a capability name (a string)");
    }
    rt.port_open(name, format)
});

builtin!(flint_b_port_send, b_port_send, |rt, a, n| {
    let _ = n;
    let (p, v) = (arg(rt, a, 0), arg(rt, a, 1));
    rt.port_send(p, v)
});

builtin!(flint_b_port_receive, b_port_receive, |rt, a, n| {
    let _ = n;
    rt.port_receive(arg(rt, a, 0))
});

builtin!(flint_b_port_close, b_port_close, |rt, a, n| {
    let _ = n;
    rt.port_close(arg(rt, a, 0))
});

builtin!(flint_b_port_p, b_port_p, |rt, a, n| {
    let _ = n;
    Value::boolean(rt.is_port(arg(rt, a, 0)))
});

builtin!(flint_b_port_state, b_port_state, |rt, a, n| {
    let _ = n;
    let p = arg(rt, a, 0);
    if !rt.is_port(p) {
        return rt.throw_str("ClassCastException", "port-state wants a port");
    }
    // The query is the truth (doc/decisions/0006), so it resolves the peer
    // rather than reporting a state that reaping has not caught up with yet.
    let s = rt.port_state_now(p);
    let name = match s {
        conc::P_PENDING => "pending",
        conc::P_OPEN => "open",
        conc::P_CLOSED => "closed",
        conc::P_HALF => "half-closed",
        conc::P_ORPHANED => "orphaned",
        _ => "refused",
    };
    rt.keyword(None, name)
});

builtin!(flint_b_port_label, b_port_label, |rt, a, n| {
    let _ = n;
    let p = arg(rt, a, 0);
    if !rt.is_port(p) {
        return rt.throw_str("ClassCastException", "port-label wants a port");
    }
    rt.slot(p, conc::PT_LABEL)
});

builtin!(flint_b_port_host_p, b_port_host_p, |rt, a, n| {
    let _ = n;
    let p = arg(rt, a, 0);
    if !rt.is_port(p) {
        return rt.throw_str("ClassCastException", "port-host? wants a port");
    }
    if rt.slot(p, conc::PT_KIND).as_fixnum() == conc::K_FLINT { TRUE } else { FALSE }
});

builtin!(flint_b_port_format, b_port_format, |rt, a, n| {
    let _ = n;
    let p = arg(rt, a, 0);
    if !rt.is_port(p) {
        return rt.throw_str("ClassCastException", "port-format wants a port");
    }
    rt.slot(p, conc::PT_FORMAT)
});

builtin!(flint_b_port_id, b_port_id, |rt, a, n| {
    let _ = n;
    let p = arg(rt, a, 0);
    if !rt.is_port(p) {
        return rt.throw_str("ClassCastException", "port-id wants a port");
    }
    rt.slot(p, conc::PT_ID)
});

builtin!(flint_b_set_port_binary, b_set_port_binary, |rt, a, n| {
    let _ = n;
    let (p, v) = (arg(rt, a, 0), arg(rt, a, 1));
    if !rt.is_port(p) {
        return rt.throw_str("ClassCastException", "set-port-binary wants a port");
    }
    let on = !(v.is_nil() || v.bits() == FALSE.bits());
    rt.set(p, conc::PT_BINARY, Value::fixnum(on as i64));
    v
});

builtin!(flint_b_port_opts, b_port_opts, |rt, a, n| {
    let _ = n;
    let p = arg(rt, a, 0);
    if !rt.is_port(p) {
        return rt.throw_str("ClassCastException", "port-opts wants a port");
    }
    rt.slot(p, conc::PT_OPTS)
});

builtin!(flint_b_set_port_opts, b_set_port_opts, |rt, a, n| {
    let _ = n;
    let (p, o) = (arg(rt, a, 0), arg(rt, a, 1));
    if !rt.is_port(p) {
        return rt.throw_str("ClassCastException", "set-port-opts wants a port");
    }
    rt.set(p, conc::PT_OPTS, o);
    o
});

/// Names and symbols together, so the manifest cannot drift from the code.
pub const CATALOGUE: &[(&str, &str)] = &[
    ("flint/spawn", "flint_b_spawn"),
    ("flint/yield", "flint_b_yield"),
    ("flint/self", "flint_b_self"),
    ("flint/thread?", "flint_b_thread_p"),
    ("flint/thread-state", "flint_b_thread_state"),
    ("flint/thread-result", "flint_b_thread_result"),
    ("flint/thread-id", "flint_b_thread_id"),
    ("flint/thread-join", "flint_b_thread_join"),
    ("flint/bindings", "flint_b_binds"),
    ("flint/set-bindings", "flint_b_set_binds"),
    ("flint/channel", "flint_b_channel"),
    ("flint/open", "flint_b_open"),
    ("flint/port-send", "flint_b_port_send"),
    ("flint/port-receive", "flint_b_port_receive"),
    ("flint/port-close", "flint_b_port_close"),
    ("flint/port?", "flint_b_port_p"),
    ("flint/port-state", "flint_b_port_state"),
    ("flint/port-label", "flint_b_port_label"),
    ("flint/port-host?", "flint_b_port_host_p"),
    ("flint/port-id", "flint_b_port_id"),
    ("flint/port-format", "flint_b_port_format"),
    ("flint/port-opts", "flint_b_port_opts"),
    ("flint/set-port-opts", "flint_b_set_port_opts"),
    ("flint/set-port-binary", "flint_b_set_port_binary"),
];

// --- the host's side of a port ---------------------------------------------
//
// The host calls these *between* runs, when no green thread is executing. The
// unit manifest lists them under `:exports`, so they are on the link line only
// when this unit is linked and a pure module's outside edge is exactly what it
// was.
//
// One outbound queue, drained in one call. A wasm<->host call is tens of
// nanoseconds; the expensive part is marshalling, so everything pending goes
// across at once and the per-message boundary cost tends to zero.

#[cfg(target_arch = "wasm32")]
mod host {
    use super::*;
    use alloc::vec::Vec;

    static mut EV: Vec<u8> = Vec::new();
    static mut IN: Vec<u8> = Vec::new();

    fn rt() -> &'static mut Rt {
        unsafe { &mut *(flint_rt::abi::flint_rt_ptr() as *mut Rt) }
    }

    /// Serialise everything pending and return how many events there are.
    /// `flint_events_ptr` gives the buffer; each record is five little-endian
    /// `u32`s -- `kind, a, b, payload-offset, payload-len` -- with offsets
    /// relative to the buffer, and the payload bytes following the records.
    ///
    /// `kind` is 1 open-request (`a` = token to answer with, `b` = the port id
    /// you will hold, payload = the capability name), 2 message (`a` = port id,
    /// payload = the bytes), 3 closed (`a` = port id).
    #[no_mangle]
    pub extern "C" fn flint_drain() -> u32 {
        let rt = rt();
        let out = unsafe { &mut *core::ptr::addr_of_mut!(EV) };
        rt.drain_events(out)
    }

    #[no_mangle]
    pub extern "C" fn flint_events_ptr() -> u32 {
        unsafe { (*core::ptr::addr_of!(EV)).as_ptr() as u32 }
    }

    /// Answer an open request. 1 allows the capability, 0 refuses it; a refusal
    /// is a normal outcome and surfaces in the program as a catchable error.
    ///
    /// **Records the answer and returns.** It never re-enters the scheduler, so
    /// it is safe to call from inside a host function that wasm invoked. The
    /// answer takes effect at the next pump.
    ///
    /// Returns 0 if the token is stale or already used -- the generation packed
    /// into it no longer matches -- which is what stops a late or duplicated
    /// reply resuming whatever thread now occupies that slot.
    #[no_mangle]
    pub extern "C" fn flint_continue(token: u32, ok: u32) -> u32 {
        rt().host_continue(token as i64, ok != 0) as u32
    }

    /// A buffer to write an inbound message into.
    #[no_mangle]
    pub extern "C" fn flint_in_alloc(len: u32) -> u32 {
        unsafe {
            let b = &mut *core::ptr::addr_of_mut!(IN);
            b.clear();
            b.resize(len as usize, 0);
            b.as_ptr() as u32
        }
    }

    /// Deliver what was written there to the port. Enqueues and wakes; it never
    /// re-enters the scheduler either.
    #[no_mangle]
    pub extern "C" fn flint_deliver(port: u32, len: u32) -> u32 {
        let bytes: alloc::vec::Vec<u8> = unsafe {
            let b = &*core::ptr::addr_of!(IN);
            b[..len as usize].to_vec()
        };
        rt().host_deliver(port as i64, &bytes) as u32
    }

    /// The host lets go of its end. Until this is called the port cannot be
    /// collected, because every handle the host holds would otherwise be a
    /// use-after-free waiting for a collection.
    #[no_mangle]
    pub extern "C" fn flint_close(port: u32) {
        rt().host_close_port(port as i64);
    }

    /// **Ask** what state the runtime end of a port is in, rather than waiting
    /// to be told: 0 pending, 1 open, 2 closed, 3 refused, 4 half-closed,
    /// 5 orphaned, 255 unknown.
    ///
    /// A `:closed` event is a notification and notifications can be dropped,
    /// missed, or simply not drained yet. If one were the only way to learn a
    /// durable fact, missing it would leak a handle for ever. So the state is
    /// queryable and the event is an optimisation over polling.
    #[no_mangle]
    pub extern "C" fn flint_port_state(port: u32) -> u32 {
        rt().host_port_state(port as i64) as u32
    }

    /// Run the scheduler until it needs the host again or the program is done.
    /// Same status codes as `main`: 0 finished, 1 threw, 2 needs the host.
    #[no_mangle]
    pub extern "C" fn flint_resume() -> i32 {
        let rt = rt();
        rt.status = 0;
        let v = flint_rt::conc::drive(rt);
        flint_rt::abi::finish_run(rt, v)
    }
}
