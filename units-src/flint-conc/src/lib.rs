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
    if !rt.is_string(name) {
        return rt.throw_str("ClassCastException", "open wants a name (a string)");
    }
    // EVERY REMAINING ARGUMENT IS FORWARDED, and the runtime takes no view of
    // any of them. A capability is an opaque value like any other and travels
    // as one; there is nothing here that knows the word, which is the point
    // (`doc/decisions/0022`). The host decodes what it was sent and decides.
    let base = rt.mark();
    let ni = rt.push(name);
    let v = rt.empty_vec();
    let vi = rt.push(v);
    for i in 1..n {
        let x = arg(rt, a, i as usize);
        let xi = rt.push(x);
        let nv = rt.vec_conj(rt.r(vi), rt.r(xi));
        rt.set_r(vi, nv);
        rt.pop_to(xi);
    }
    let (nm, args) = (rt.r(ni), rt.r(vi));
    rt.pop_to(base);
    rt.port_open(nm, args)
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

builtin!(flint_b_port_bridge_p, b_port_bridge_p, |rt, a, n| {
    let _ = n;
    let p = arg(rt, a, 0);
    if !rt.is_port(p) {
        return rt.throw_str("ClassCastException", "port-bridge? wants a port");
    }
    // Does this port carry BYTES across a boundary? A bridge does and a
    // channel does not, and that is the only distinction a guest can see --
    // it cannot see the encoding, because the runtime owns it.
    if conc::crosses_a_heap(rt.slot(p, conc::PT_KIND).as_fixnum()) { TRUE } else { FALSE }
});

builtin!(flint_b_port_id, b_port_id, |rt, a, n| {
    let _ = n;
    let p = arg(rt, a, 0);
    if !rt.is_port(p) {
        return rt.throw_str("ClassCastException", "port-id wants a port");
    }
    rt.slot(p, conc::PT_ID)
});

/// Names and symbols together, so the manifest cannot drift from the code.
/// The same catalogue, as FUNCTION POINTERS rather than symbol names.
///
/// `CATALOGUE` above maps a builtin to a linker symbol, which is what a wasm
/// module needs and what a natively-linked host cannot use. The native CLI
/// links this crate directly (`doc/decisions/0021`), so it needs the functions
/// themselves -- otherwise `flint run` cannot execute a program that spawns a
/// thread, and the conformance gate has no native answer to compare the ports
/// against.
///
/// Kept immediately beside `CATALOGUE` so the two are edited together; a
/// builtin in one and not the other is a builtin that works on one kind of
/// host and silently not the other.
#[cfg(not(target_arch = "wasm32"))]
pub const HOST_CATALOGUE: &[(&str, flint_rt::vm::NativeFn)] = &[
    ("flint/spawn", flint_b_spawn),
    ("flint/yield", flint_b_yield),
    ("flint/self", flint_b_self),
    ("flint/thread?", flint_b_thread_p),
    ("flint/thread-state", flint_b_thread_state),
    ("flint/thread-result", flint_b_thread_result),
    ("flint/thread-id", flint_b_thread_id),
    ("flint/thread-join", flint_b_thread_join),
    ("flint/bindings", flint_b_binds),
    ("flint/set-bindings", flint_b_set_binds),
    ("flint/channel", flint_b_channel),
    ("flint/open", flint_b_open),
    ("flint/port-send", flint_b_port_send),
    ("flint/port-receive", flint_b_port_receive),
    ("flint/port-close", flint_b_port_close),
    ("flint/port?", flint_b_port_p),
    ("flint/port-state", flint_b_port_state),
    ("flint/port-label", flint_b_port_label),
    ("flint/port-bridge?", flint_b_port_bridge_p),
    ("flint/port-id", flint_b_port_id),
];

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
    ("flint/port-bridge?", "flint_b_port_bridge_p"),
    ("flint/port-id", "flint_b_port_id"),
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

    /// Install a BRIDGE the host owns (`doc/decisions/0027`), and say whether
    /// it worked. The label is read out of the inbound buffer.
    ///
    /// `system` non-zero makes it the sandbox's SYSTEM port: the one it can ask
    /// the host for things through. A sandbox given none runs logic and can ask
    /// for nothing, which is the honest default rather than a degraded mode.
    ///
    /// The id is the HOST's. That is the whole inversion: a sandbox no longer
    /// mints an endpoint and offers it up, and an id means the same thing in
    /// every sandbox that holds it -- which is what lets a handle be passed
    /// from one to another at all.
    ///
    /// **Installing a port this sandbox already holds takes no second
    /// reference.** The handle is interned by host id, so the same object comes
    /// back and no `EV_RETAIN` is pushed. A host may therefore install freely
    /// without tracking what it has already installed.
    #[no_mangle]
    pub extern "C" fn flint_install_port(id: u32, len: u32, system: u32) -> u32 {
        let rt = rt();
        let text: alloc::string::String = unsafe {
            let b = &*core::ptr::addr_of!(IN);
            alloc::string::String::from_utf8_lossy(&b[..len as usize]).into_owned()
        };
        let base = rt.mark();
        let l = rt.string(&text);
        let li = rt.push(l);
        let l = rt.r(li);
        let p = if system != 0 {
            rt.install_system_port(id as i64, l)
        } else {
            rt.install_bridge_port(id as i64, l, false)
        };
        rt.pop_to(base);
        (!p.is_nil()) as u32
    }

    /// The id of this sandbox's SYSTEM PORT, or 0 if it has none.
    ///
    /// A host has to know whether one exists, because it decides how a call is
    /// made: with a system port a call is a MESSAGE on it and can park, and
    /// without one it is `flint_call`, synchronous and unable to park. Guessing
    /// from the export table does not work -- a partially shaken module can
    /// export `flint_install_port` with a stubbed body, and a host that
    /// installed a port through the raw ABI has told the runtime and not the
    /// driver. Asking is one call and always right.
    #[no_mangle]
    pub extern "C" fn flint_system_port() -> u32 {
        let rt = rt();
        let p = rt.system_port_id();
        if p < 0 { 0 } else { p as u32 }
    }

    /// Grant an `open`: hand the waiting thread a handle on the host's port
    /// `port` (`doc/decisions/0027`).
    ///
    /// This is the half `flint_continue` cannot do. A grant has to NAME a port,
    /// because there is no port until the host says which one -- the sandbox no
    /// longer manufactures a pair and keeps one end. `flint_continue(token, 1)`
    /// is therefore refused rather than guessed at, and returns 0.
    #[no_mangle]
    pub extern "C" fn flint_grant(token: u32, port: u32) -> u32 {
        rt().host_grant(token as i64, port as i64) as u32
    }

    /// A buffer to write an inbound message into.
    ///
    /// `rt()` first, and not for the runtime: it is what creates the ARENA the
    /// Rust allocator draws from. A host that installs a port BEFORE running
    /// anything -- which is now the ordinary way to start a sandbox, since the
    /// system port is passed in at construction -- would otherwise allocate
    /// before there was anything to allocate from, and trap as `unreachable`
    /// out of the alloc error handler with nothing naming the cause.
    #[no_mangle]
    pub extern "C" fn flint_in_alloc(len: u32) -> u32 {
        let _ = rt();
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
    /// Advance the sandbox after the host has answered something.
    ///
    /// Returns the STATUS and renders nothing. It used to hand the value back
    /// through `finish_run`, which was `main`'s shape: one entry function, one
    /// answer, rendered as a string into `OUT`. With `main` gone
    /// (`doc/decisions/0025` step 5) a resume has no single answer to render --
    /// a call's result goes back as a message on the system port, carrying its
    /// `:tx`, and there may be several in flight. Rendering here reported 1
    /// ("that is not a string") for a sandbox that had simply run out of work.
    pub extern "C" fn flint_resume() -> i32 {
        let rt = rt();
        rt.status = 0;
        let _ = flint_rt::conc::drive(rt);
        if rt.status == 0 && rt.failed() {
            // A thread nobody asked for -- an ordinary `spawn` -- failed, and
            // there is no `:tx` to send it back on. Say so rather than losing it.
            let v = flint_rt::value::NIL;
            return flint_rt::abi::finish_run(rt, v);
        }
        rt.status
    }
}
