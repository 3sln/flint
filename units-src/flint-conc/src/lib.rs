//! `flint.conc` — the builtins for green threads and ports, plus the host-facing
//! exports that let a host service a parked thread.
//!
//! This is a **unit** (`DECISIONS.md#namespace-units`), which is the whole point: a
//! program that never mentions `spawn`, `channel` or `open` never reaches any of
//! these symbols, so `--gc-sections` deletes them and the module is the size it
//! was before green threads existed. `test/threads.clj` asserts that.
//!
//! Nothing here suspends a wasm frame. `receive` on an empty port does not block
//! the host and does not unwind the module's stack: it marks the green thread as
//! not-runnable and returns to the scheduler, which is a loop inside the
//! interpreter. See `DECISIONS.md#threads-and-ports`, section 1.

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
    let _ = n;
    let name = arg(rt, a, 0);
    if !rt.is_string(name) {
        return rt.throw_str("ClassCastException", "open wants a name (a string)");
    }
    // THE PAYLOAD ARRIVES ENCODED (`DECISIONS.md#the-codec-is-guest-code`).
    // `flint.port/open` builds `[name ...args]` and writes it with
    // `flint.wire`, so the runtime takes no view of the arguments here for the
    // same reason it never did -- a capability is an opaque value like any
    // other and travels as one -- and now it does not even walk them.
    //
    // THE NAME IS STILL PASSED SEPARATELY, and is not read off the payload: it
    // is what the refusal message says, and reading it back out of an encoding
    // would mean decoding here, which is the thing this moved away from.
    let w = arg(rt, a, 1);
    if !rt.is_writer(w) {
        return rt.throw_str(
            "ClassCastException",
            "open wants its arguments encoded -- call `flint.port/open`, which does that",
        );
    }
    rt.port_open(name, w)
});

builtin!(flint_b_request, b_request, |rt, a, n| {
    // ARITY IS CHECKED, not assumed. This takes an encoding now, and a
    // one-argument call would otherwise read the slot after the arguments it
    // was given -- which is an out-of-bounds index, not a type error.
    if n < 2 {
        return rt.throw_str(
            "IllegalArgumentException",
            "request wants a name and an encoding -- call `flint.host/request`",
        );
    }
    let what = arg(rt, a, 0);
    if !rt.is_string(what) {
        return rt.throw_str("ClassCastException", "request wants a name (a string)");
    }
    // THE PAYLOAD ARRIVES ENCODED, exactly as `open`'s does
    // (`DECISIONS.md#the-codec-is-guest-code`): `flint.host/request` writes
    // `[what & args]` with `flint.wire` and hands the writer in. What comes
    // back is a live READER over the host's answer, which the same library
    // function reads -- so the runtime neither writes nor reads the format.
    //
    // THE NAME IS STILL PASSED SEPARATELY, because it is what the refusal
    // message says, and reading it back out of the encoding would mean
    // decoding here.
    let w = arg(rt, a, 1);
    if !rt.is_writer(w) {
        return rt.throw_str(
            "ClassCastException",
            "request wants its arguments encoded -- call `flint.host/request`, which \
             does that",
        );
    }
    rt.host_request(what, w)
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


/// Receive on a bridge as a READER, which is how a guest decodes for itself.
///
/// Not a second way to receive: `flint.port/receive` uses this on a bridge and
/// `port-receive` on a channel, because a channel carries values and has no
/// encoding to read. The reader it answers MAY MINT -- these are bytes that
/// arrived over a bridge, which is exactly the distinction `RD_LIVE` carries.
builtin!(flint_b_port_receive_reader, b_port_receive_reader, |rt, a, n| {
    let _ = n;
    let p = arg(rt, a, 0);
    if !rt.is_port(p) {
        return rt.throw_str("ClassCastException", "port-receive-reader wants a port");
    }
    rt.port_receive_reader(p)
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
    // The query is the truth (DECISIONS.md#host-abi), so it resolves the peer
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

// THE SYSTEM PORT, for the control plane and nothing else.
//
// Guarded `:host` (`DECISIONS.md#bridges-are-the-only-door`): it IS the host
// transport, the same thing `flint.host/request` reaches, so it takes the same
// grant. Guest code still cannot name it -- a program holds `:host` only if an
// embedder grants it, and `test/globalport.clj` pins that a guest naming it
// does not compile.
//
// It exists because the control plane has to be a THUNK. Bootstrap spawns
// `flint.system/boot` by taking its var's value and spawning it, and a green
// thread takes no arguments -- so the port cannot be passed in and has to be
// fetched. The alternative was Rust calling a flint function to build a
// closure, which re-enters `drive` from inside `drive`: measured, and the
// nested scheduler is what made the first version silently never start.
builtin!(flint_b_system_port, b_system_port, |rt, a, n| {
    let _ = (a, n);
    rt.system_port()
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
/// links this crate directly (`DECISIONS.md#cli`), so it needs the functions
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
    ("flint/request", flint_b_request),
    ("flint/port-send", flint_b_port_send),
    ("flint/port-receive", flint_b_port_receive),
    ("flint/port-receive-reader", flint_b_port_receive_reader),
    ("flint/port-close", flint_b_port_close),
    ("flint/port?", flint_b_port_p),
    ("flint/port-state", flint_b_port_state),
    ("flint/port-label", flint_b_port_label),
    ("flint/port-bridge?", flint_b_port_bridge_p),
    ("flint/system-port", flint_b_system_port),
    ("flint/port-id", flint_b_port_id),
    ("flint/wire-writer", flint_b_wire_writer),
    ("flint/wire-writer?", flint_b_wire_writerp),
    ("flint/wire-nil", flint_b_wire_nil),
    ("flint/wire-bool", flint_b_wire_bool),
    ("flint/wire-int", flint_b_wire_int),
    ("flint/wire-double", flint_b_wire_double),
    ("flint/wire-str", flint_b_wire_str),
    ("flint/wire-bytes", flint_b_wire_bytes),
    ("flint/wire-kw", flint_b_wire_kw),
    ("flint/wire-sym", flint_b_wire_sym),
    ("flint/wire-vec", flint_b_wire_vec),
    ("flint/wire-list", flint_b_wire_list),
    ("flint/wire-set", flint_b_wire_set),
    ("flint/wire-map", flint_b_wire_map),
    ("flint/wire-meta", flint_b_wire_meta),
    ("flint/wire-port", flint_b_wire_port),
    ("flint/wire-opaque", flint_b_wire_opaque),
    ("flint/wire-tagged", flint_b_wire_tagged),
    ("flint/wire-table", flint_b_wire_table),
    ("flint/wire-table-rows", flint_b_wire_table_rows),
    ("flint/wire-reader", flint_b_wire_reader),
    ("flint/wire-tag", flint_b_wire_tag),
    ("flint/wire-left", flint_b_wire_left),
    ("flint/wire-u32", flint_b_wire_u32),
    ("flint/wire-i64", flint_b_wire_i64),
    ("flint/wire-f64", flint_b_wire_f64),
    ("flint/wire-text", flint_b_wire_text),
    ("flint/wire-ns", flint_b_wire_ns),
    ("flint/wire-blob", flint_b_wire_blob),
    ("flint/wire-port-in", flint_b_wire_port_in),
    ("flint/wire-opaque-in", flint_b_wire_opaque_in),
];

// --- the wire writer (`DECISIONS.md#the-codec-is-guest-code`) ---------------
//
// ONE PRIMITIVE PER SHAPE, not one `emit` taking a tag: a tag argument puts a
// dispatch in the hottest loop the language has, and it hides the dangerous
// primitive among the harmless ones. Named separately, `wire-port` and
// `wire-opaque` have their own signatures and can be audited on their own.
//
// EVERY ONE ANSWERS THE WRITER, so a guest encoder reads as a chain and a
// refusal is a throw rather than a value nobody checks.

/// The writer, or a throw. One shape for the sixteen refusals below.
/// The writer this call was given, or `None` having thrown.
///
/// **WHAT COMES BACK FROM HERE IS A SNAPSHOT, and every caller must ANSWER a
/// re-read one.** Appending allocates -- `wire_piece` conjs a byte at a time
/// -- allocating can collect, and the nursery is a copying collector, so the
/// writer MOVES. Every one of these builtins used to answer the `Value` it
/// captured before the append, which is the address the writer used to be at.
///
/// Below a collection's worth of payload that is the same address and
/// everything works, which is why this survived: it took a ~765 KB string to
/// make a collection certain mid-append. The guest then got back an object
/// that was not its writer -- `wire-writer?` false, and not nil, so nothing
/// threw -- `port/send` was handed it, and the bridge refused with "a bridge
/// carries an encoding". The apparent "size ceiling" was really "big enough to
/// force a GC", which is why it moved with live-set pressure and ignored
/// `set_memory_limit` (`DECISIONS.md#the-codec-is-guest-code`).
///
/// `arg(rt, a, 0)` re-reads the argument off the VALUE STACK, which is a root
/// the collector updates -- so it needs no push of its own.
fn writer_arg(rt: &mut Rt, a: usize, what: &str) -> Option<Value> {
    let w = arg(rt, a, 0);
    if rt.is_writer(w) {
        return Some(w);
    }
    let msg = alloc::format!("{what} wants a wire writer");
    rt.throw_str("ClassCastException", &msg);
    None
}

/// Refuse a value the format does not allow here.
///
/// The writer knows where it is (`DECISIONS.md#the-codec-is-guest-code`) and
/// does not take the guest's word for it. `opens` is how many values this one
/// contains: zero for a scalar, `n` for a vector, `2n` for a map of `n` pairs,
/// two for a wrapper.
fn expect(rt: &mut Rt, w: Value, opens: i64, what: &str) -> bool {
    if rt.wire_expect_value(w, opens) {
        return true;
    }
    let msg = alloc::format!(
        "{what}: no value is due here -- the message is already complete, or a \
         count was expected"
    );
    rt.throw_str("IllegalStateException", &msg);
    false
}

/// `true` unless the writer was spent, in which case throw. A writer answers
/// its bytes once; appending after that would grow bytes somebody has sent.
///
/// A FAILURE ALREADY PENDING IS LEFT ALONE. An append can now answer false for
/// a second reason -- the buffer could not GROW, which sets `thrown` where it
/// happens -- and stamping this message over that one would report "already
/// finished" about a writer that was perfectly live and simply out of room.
/// The nearer error is not always the better one; here the earlier one knows
/// what went wrong and this one is guessing.
fn wrote(rt: &mut Rt, ok: bool, what: &str) -> Value {
    if ok || rt.failed() {
        return NIL;
    }
    let msg = alloc::format!("{what}: this writer has already been finished");
    rt.throw_str("IllegalStateException", &msg)
}

macro_rules! wire_tag {
    ($export:ident, $inner:ident, $name:literal, $tag:expr) => {
        builtin!($export, $inner, |rt, a, n| {
            let _ = n;
            let Some(w) = writer_arg(rt, a, $name) else { return NIL };
            // A wrapper opens TWO values; a bare tag opens none.
            let opens = if $tag == flint_rt::codec::K_WITH_META
                        || $tag == flint_rt::codec::K_TAGGED { 2 } else { 0 };
            if !expect(rt, w, opens, $name) { return NIL }
            let ok = rt.wire_piece(w, $tag, &[]);
            let e = wrote(rt, ok, $name);
            if !ok { return e }
            arg(rt, a, 0)   // RE-READ: see `writer_arg`
        });
    };
}

builtin!(flint_b_wire_writer, b_wire_writer, |rt, a, n| {
    let _ = (a, n);
    rt.wire_writer()
});

wire_tag!(flint_b_wire_nil, b_wire_nil, "wire-nil", flint_rt::codec::K_NIL);

builtin!(flint_b_wire_bool, b_wire_bool, |rt, a, n| {
    let _ = n;
    let Some(w) = writer_arg(rt, a, "wire-bool") else { return NIL };
    let v = arg(rt, a, 1);
    let tag = if v == FALSE || v.is_nil() {
        flint_rt::codec::K_FALSE
    } else {
        flint_rt::codec::K_TRUE
    };
    if !expect(rt, w, 0, "wire-bool") { return NIL }
    let ok = rt.wire_piece(w, tag, &[]);
    if !ok { return wrote(rt, ok, "wire-bool") }
    arg(rt, a, 0)   // RE-READ: see `writer_arg`
});

builtin!(flint_b_wire_int, b_wire_int, |rt, a, n| {
    let _ = n;
    let Some(w) = writer_arg(rt, a, "wire-int") else { return NIL };
    let v = arg(rt, a, 1);
    let Some(i) = rt.as_i64(v) else {
        return rt.throw_str("ClassCastException", "wire-int wants an integer")
    };
    if !expect(rt, w, 0, "wire-int") { return NIL }
    let ok = rt.wire_piece(w, flint_rt::codec::K_INT, &(i as u64).to_le_bytes());
    if !ok { return wrote(rt, ok, "wire-int") }
    arg(rt, a, 0)   // RE-READ: see `writer_arg`
});

builtin!(flint_b_wire_double, b_wire_double, |rt, a, n| {
    let _ = n;
    let Some(w) = writer_arg(rt, a, "wire-double") else { return NIL };
    let v = arg(rt, a, 1);
    if !rt.is_number(v) {
        return rt.throw_str("ClassCastException", "wire-double wants a number");
    }
    let bits = rt.num_f64(v).to_bits();
    if !expect(rt, w, 0, "wire-double") { return NIL }
    let ok = rt.wire_piece(w, flint_rt::codec::K_DOUBLE, &bits.to_le_bytes());
    if !ok { return wrote(rt, ok, "wire-double") }
    arg(rt, a, 0)   // RE-READ: see `writer_arg`
});

builtin!(flint_b_wire_str, b_wire_str, |rt, a, n| {
    let _ = n;
    let Some(w) = writer_arg(rt, a, "wire-str") else { return NIL };
    let v = arg(rt, a, 1);
    if !rt.is_string(v) {
        return rt.throw_str("ClassCastException", "wire-str wants a string");
    }
    // FLATTENED, because `as_str` borrows and will not materialise a rope --
    // the trap `str->b` records, and `pr-str` builds ropes.
    let owned = rt.s_to_vec(v);
    let mut pay = alloc::vec::Vec::with_capacity(4 + owned.len());
    pay.extend_from_slice(&(owned.len() as u32).to_le_bytes());
    pay.extend_from_slice(&owned);
    if !expect(rt, w, 0, "wire-str") { return NIL }
    let ok = rt.wire_piece(w, flint_rt::codec::K_STRING, &pay);
    if !ok { return wrote(rt, ok, "wire-str") }
    arg(rt, a, 0)   // RE-READ: see `writer_arg`
});

builtin!(flint_b_wire_bytes, b_wire_bytes, |rt, a, n| {
    let _ = n;
    let Some(w) = writer_arg(rt, a, "wire-bytes") else { return NIL };
    let v = arg(rt, a, 1);
    if !rt.is_bytes(v) {
        return rt.throw_str("ClassCastException", "wire-bytes wants a byte string");
    }
    let owned = rt.b_to_vec(v);
    let mut pay = alloc::vec::Vec::with_capacity(4 + owned.len());
    pay.extend_from_slice(&(owned.len() as u32).to_le_bytes());
    pay.extend_from_slice(&owned);
    if !expect(rt, w, 0, "wire-bytes") { return NIL }
    let ok = rt.wire_piece(w, flint_rt::codec::K_BYTES, &pay);
    if !ok { return wrote(rt, ok, "wire-bytes") }
    arg(rt, a, 0)   // RE-READ: see `writer_arg`
});

/// A keyword or symbol: the namespace (absent is NOT empty), then the name.
fn wire_named(rt: &mut Rt, a: usize, tag: u8, what: &str) -> Value {
    let Some(w) = writer_arg(rt, a, what) else { return NIL };
    let (ns, name) = (arg(rt, a, 1), arg(rt, a, 2));
    if !rt.is_string(name) {
        let msg = alloc::format!("{what} wants a name string");
        return rt.throw_str("ClassCastException", &msg);
    }
    let nm = rt.s_to_vec(name);
    let mut pay = alloc::vec::Vec::new();
    if ns.is_nil() {
        // ABSENT, which is not the same as empty: that is what separates `:kw`
        // from `:/kw`.
        pay.extend_from_slice(&flint_rt::codec::NO_NS.to_le_bytes());
    } else {
        if !rt.is_string(ns) {
            let msg = alloc::format!("{what} wants a namespace string or nil");
            return rt.throw_str("ClassCastException", &msg);
        }
        let nsb = rt.s_to_vec(ns);
        pay.extend_from_slice(&(nsb.len() as u32).to_le_bytes());
        pay.extend_from_slice(&nsb);
    }
    pay.extend_from_slice(&(nm.len() as u32).to_le_bytes());
    pay.extend_from_slice(&nm);
    if !expect(rt, w, 0, what) { return NIL }
    let ok = rt.wire_piece(w, tag, &pay);
    if !ok { return wrote(rt, ok, what) }
    arg(rt, a, 0)   // RE-READ: see `writer_arg`
}

builtin!(flint_b_wire_kw, b_wire_kw, |rt, a, n| {
    let _ = n;
    wire_named(rt, a, flint_rt::codec::K_KEYWORD, "wire-kw")
});

builtin!(flint_b_wire_sym, b_wire_sym, |rt, a, n| {
    let _ = n;
    wire_named(rt, a, flint_rt::codec::K_SYMBOL, "wire-sym")
});

/// A counted opening: the tag, then how many values follow.
///
/// A COUNT THE FORMAT CAN ACTUALLY WRITE, or a throw.
///
/// Four little-endian bytes is what every count in the encoding is, so a count
/// past `u32` wrote a truncated one and opened an untruncated frame -- the
/// bytes and the writer's own idea of the message disagreeing, which is the
/// one thing the frames exist to prevent.
///
/// It was also the hole. A fixnum is 48 bits, sign-extended, so a frame of
/// `2^47 + 1` reads back NEGATIVE -- and negative means "a count is due here",
/// the table's marker. `(wire-vec (+ 2^47 1))` therefore wrote `1` to the wire,
/// telling a reader to expect one value, and left the writer willing to accept
/// a raw four-byte count in its place: `0f 00 00 00` is `K_PORT` and the start
/// of an id. See `MAX_COUNT` in `codec.rs`.
fn count_ok(rt: &mut Rt, c: i64, what: &str) -> bool {
    if c < 0 {
        let msg = alloc::format!("{what}: a count cannot be negative");
        rt.throw_str("IllegalArgumentException", &msg);
        return false;
    }
    if c > flint_rt::codec::MAX_COUNT {
        let msg = alloc::format!(
            "{what}: a count of {c} cannot be written -- the format writes a count as four \
             bytes, so the largest is {}",
            flint_rt::codec::MAX_COUNT
        );
        rt.throw_str("IllegalArgumentException", &msg);
        return false;
    }
    true
}

/// COUNTS, NOT BRACKETS, because that is what the format already says -- so the
/// wire does not change and a differential test can assert byte equality
/// against the runtime encoder.
fn wire_counted(rt: &mut Rt, a: usize, tag: u8, what: &str) -> Value {
    let Some(w) = writer_arg(rt, a, what) else { return NIL };
    let v = arg(rt, a, 1);
    let Some(c) = rt.as_i64(v) else {
        let msg = alloc::format!("{what} wants a count");
        return rt.throw_str("ClassCastException", &msg)
    };
    if !count_ok(rt, c, what) { return NIL }
    // A MAP OPENS TWICE ITS COUNT: `n` pairs are `2n` values, and a codec that
    // counted them as `n` would call the message complete half way through.
    let opens = if tag == flint_rt::codec::K_MAP { c * 2 } else { c };
    if !expect(rt, w, opens, what) { return NIL }
    let ok = rt.wire_piece(w, tag, &(c as u32).to_le_bytes());
    if !ok { return wrote(rt, ok, what) }
    arg(rt, a, 0)   // RE-READ: see `writer_arg`
}

builtin!(flint_b_wire_vec, b_wire_vec, |rt, a, n| {
    let _ = n;
    wire_counted(rt, a, flint_rt::codec::K_VECTOR, "wire-vec")
});
builtin!(flint_b_wire_list, b_wire_list, |rt, a, n| {
    let _ = n;
    wire_counted(rt, a, flint_rt::codec::K_LIST, "wire-list")
});
builtin!(flint_b_wire_set, b_wire_set, |rt, a, n| {
    let _ = n;
    wire_counted(rt, a, flint_rt::codec::K_SET, "wire-set")
});
builtin!(flint_b_wire_map, b_wire_map, |rt, a, n| {
    let _ = n;
    wire_counted(rt, a, flint_rt::codec::K_MAP, "wire-map")
});

wire_tag!(flint_b_wire_meta, b_wire_meta, "wire-meta", flint_rt::codec::K_WITH_META);
// A TAGGED LITERAL: the tag byte, then the tag symbol and the form as ordinary
// values. Bare like `wire-meta`, because both are wrappers -- one tag and two
// values follow, so there is no payload for the primitive to carry.
wire_tag!(flint_b_wire_tagged, b_wire_tagged, "wire-tagged", flint_rt::codec::K_TAGGED);

// THE TWO THAT MATTER. Both take a VALUE and read its identity themselves; a
// guest cannot pass an id, because flint is given no way to turn an integer
// into a port or an opaque. That is the whole safety rule, and it is why these
// are primitives rather than `wire-int` calls a guest could make for itself.
/// IS THIS A WRITER? The one question a guest may ask about one.
///
/// `kind` answers `:other`, deliberately -- a writer is not a value and has no
/// kind of its own. But `port/send` has to tell a finished encoding from a
/// value to be encoded, and "is it a writer" is not a capability: a guest that
/// holds one already knows what it made.
builtin!(flint_b_wire_writerp, b_wire_writerp, |rt, a, n| {
    let _ = n;
    let v = arg(rt, a, 0);
    Value::boolean(rt.is_writer(v))
});

builtin!(flint_b_wire_port, b_wire_port, |rt, a, n| {
    let _ = n;
    let Some(w) = writer_arg(rt, a, "wire-port") else { return NIL };
    let p = arg(rt, a, 1);
    if !rt.is_port(p) {
        return rt.throw_str("ClassCastException", "wire-port wants a port");
    }
    // A CHANNEL END IS NOT WRITABLE, and this is the check that keeps the
    // guest encoder from being a way around `check_sendable_via`. That one
    // runs on a VALUE; an encoding is not a value by the time it reaches
    // `send`, so the rule has to be restated where the bytes are made.
    //
    // The rule itself is the asymmetry `conc.rs` states: a channel lives
    // wholly in this heap and the host was never told it exists, so its id
    // names one of our objects from OUTSIDE -- and an id a host can name is
    // the integer-to-port conversion this design exists to prevent. A bridge
    // is the opposite: its id is the host's own and already means something
    // over there, which is what makes delegation possible at all.
    //
    // An encoding is only ever read across a heap, so this is not merely the
    // send rule copied -- there is no reading of these bytes for which a
    // channel end would be meaningful.
    let kind = rt.as_i64(rt.slot(p, conc::PT_KIND)).unwrap_or(0);
    if !conc::crosses_a_heap(kind) {
        return rt.throw_str(
            "IllegalArgumentException",
            "wire-port: a channel endpoint cannot be sent to the host -- both its ends \
             live in this heap and the host has never been told it exists, so its id \
             would name one of our objects from outside. A bridge port can be, because \
             its id is the host's own.",
        );
    }
    let id = rt.as_i64(rt.slot(p, conc::PT_ID)).unwrap_or(0) as u32;
    if !expect(rt, w, 0, "wire-port") { return NIL }
    let ok = rt.wire_piece(w, flint_rt::codec::K_PORT, &id.to_le_bytes());
    if !ok { return wrote(rt, ok, "wire-port") }
    arg(rt, a, 0)   // RE-READ: see `writer_arg`
});

builtin!(flint_b_wire_opaque, b_wire_opaque, |rt, a, n| {
    let _ = n;
    let Some(w) = writer_arg(rt, a, "wire-opaque") else { return NIL };
    let o = arg(rt, a, 1);
    if !rt.is_opaque(o) {
        return rt.throw_str("ClassCastException", "wire-opaque wants an opaque value");
    }
    let id = rt.opaque_host_id(o) as u64;
    let label = rt.opaque_label(o);
    let lb = if rt.is_string(label) { rt.s_to_vec(label) } else { alloc::vec::Vec::new() };
    let mut pay = alloc::vec::Vec::with_capacity(12 + lb.len());
    pay.extend_from_slice(&id.to_le_bytes());
    pay.extend_from_slice(&(lb.len() as u32).to_le_bytes());
    pay.extend_from_slice(&lb);
    if !expect(rt, w, 0, "wire-opaque") { return NIL }
    let ok = rt.wire_piece(w, flint_rt::codec::K_SENTINEL, &pay);
    if !ok { return wrote(rt, ok, "wire-opaque") }
    arg(rt, a, 0)   // RE-READ: see `writer_arg`
});

// --- the wire reader (`DECISIONS.md#the-codec-is-guest-code`) ---------------
//
// NOT THE WRITER'S MIRROR IMAGE. A writer has to be opaque because a guest that
// can write raw bytes can forge a `K_PORT` tag; a reader can hand out plain
// integers and strings freely, because reading bytes a guest already holds
// tells it nothing new. Only the two MINTING reads are guarded, and they are
// guarded by a flag on the reader rather than by refusing tags in three places.

fn reader_arg(rt: &mut Rt, a: usize, what: &str) -> Option<Value> {
    let r = arg(rt, a, 0);
    if rt.is_reader(r) {
        return Some(r);
    }
    let msg = alloc::format!("{what} wants a wire reader");
    rt.throw_str("ClassCastException", &msg);
    None
}

/// Past the end is a THROW, not a nil: a decoder that read a truncated message
/// as a short one would build a value nobody sent.
fn short(rt: &mut Rt, what: &str) -> Value {
    let msg = alloc::format!("{what}: the encoding ends early");
    rt.throw_str("IllegalArgumentException", &msg)
}

/// A reader over `b`. NEVER MINTING: a guest's own bytes are a guest's own
/// bytes, and `port/receive` is what makes the live one.
builtin!(flint_b_wire_reader, b_wire_reader, |rt, a, n| {
    let _ = n;
    let b = arg(rt, a, 0);
    if !rt.is_bytes(b) {
        return rt.throw_str("ClassCastException", "wire-reader wants a byte string");
    }
    rt.wire_reader(b, false)
});

/// The next TAG, consumed. Nil when the reader is finished, which is how a
/// decoder knows a message is over without being told a length.
builtin!(flint_b_wire_tag, b_wire_tag, |rt, a, n| {
    let _ = n;
    let Some(r) = reader_arg(rt, a, "wire-tag") else { return NIL };
    if rt.wire_left(r) == 0 {
        return NIL;
    }
    match rt.wire_take(r, 1) {
        Some(b) => Value::fixnum(b[0] as i64),
        None => short(rt, "wire-tag"),
    }
});

/// Is there anything left? For a decoder that wants to refuse trailing bytes.
builtin!(flint_b_wire_left, b_wire_left, |rt, a, n| {
    let _ = n;
    let Some(r) = reader_arg(rt, a, "wire-left") else { return NIL };
    Value::fixnum(rt.wire_left(r) as i64)
});

/// A COUNT. Safe to hand over raw, unlike its writing counterpart: four bytes
/// read out of a stream cannot forge anything, where four bytes written INTO
/// one at a value position would be read as a tag.
builtin!(flint_b_wire_u32, b_wire_u32, |rt, a, n| {
    let _ = n;
    let Some(r) = reader_arg(rt, a, "wire-u32") else { return NIL };
    match rt.wire_take(r, 4) {
        Some(b) => Value::fixnum(u32::from_le_bytes([b[0], b[1], b[2], b[3]]) as i64),
        None => short(rt, "wire-u32"),
    }
});

builtin!(flint_b_wire_i64, b_wire_i64, |rt, a, n| {
    let _ = n;
    let Some(r) = reader_arg(rt, a, "wire-i64") else { return NIL };
    match rt.wire_take(r, 8) {
        Some(b) => {
            let v = i64::from_le_bytes([b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7]]);
            rt.integer(v)
        }
        None => short(rt, "wire-i64"),
    }
});

builtin!(flint_b_wire_f64, b_wire_f64, |rt, a, n| {
    let _ = n;
    let Some(r) = reader_arg(rt, a, "wire-f64") else { return NIL };
    match rt.wire_take(r, 8) {
        Some(b) => {
            let bits = u64::from_le_bytes([b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7]]);
            Value::from_f64(f64::from_bits(bits))
        }
        None => short(rt, "wire-f64"),
    }
});

/// A length-prefixed string.
builtin!(flint_b_wire_text, b_wire_text, |rt, a, n| {
    let _ = n;
    let Some(r) = reader_arg(rt, a, "wire-text") else { return NIL };
    let Some(lb) = rt.wire_take(r, 4) else { return short(rt, "wire-text") };
    let len = u32::from_le_bytes([lb[0], lb[1], lb[2], lb[3]]) as usize;
    let Some(b) = rt.wire_take(r, len) else { return short(rt, "wire-text") };
    match core::str::from_utf8(&b) {
        Ok(t) => rt.string(t),
        Err(_) => rt.throw_str("IllegalArgumentException", "wire-text: not valid UTF-8"),
    }
});

/// A NAMESPACE, which may be ABSENT -- and absent is not empty. The sentinel is
/// `0xffffffff`, and a decoder that read it as a length would try for four
/// billion bytes.
builtin!(flint_b_wire_ns, b_wire_ns, |rt, a, n| {
    let _ = n;
    let Some(r) = reader_arg(rt, a, "wire-ns") else { return NIL };
    let Some(lb) = rt.wire_take(r, 4) else { return short(rt, "wire-ns") };
    let len = u32::from_le_bytes([lb[0], lb[1], lb[2], lb[3]]);
    if len == flint_rt::codec::NO_NS {
        return NIL;
    }
    let Some(b) = rt.wire_take(r, len as usize) else { return short(rt, "wire-ns") };
    match core::str::from_utf8(&b) {
        Ok(t) => rt.string(t),
        Err(_) => rt.throw_str("IllegalArgumentException", "wire-ns: not valid UTF-8"),
    }
});

/// A length-prefixed byte string.
builtin!(flint_b_wire_blob, b_wire_blob, |rt, a, n| {
    let _ = n;
    let Some(r) = reader_arg(rt, a, "wire-blob") else { return NIL };
    let Some(lb) = rt.wire_take(r, 4) else { return short(rt, "wire-blob") };
    let len = u32::from_le_bytes([lb[0], lb[1], lb[2], lb[3]]) as usize;
    let Some(b) = rt.wire_take(r, len) else { return short(rt, "wire-blob") };
    rt.new_bytes(&b)
});

// THE TWO THAT MINT, and the only two this file guards. A reader over bytes the
// guest supplied answers false to `may_mint`, so these refuse -- which is the
// rule `decode_guest` enforced by refusing tags, moved to the one place that
// knows where the bytes came from.
builtin!(flint_b_wire_port_in, b_wire_port_in, |rt, a, n| {
    let _ = n;
    let Some(r) = reader_arg(rt, a, "wire-port-in") else { return NIL };
    if !rt.wire_may_mint(r) {
        return rt.throw_str(
            "SecurityException",
            "wire-port-in: this reader is over bytes the program supplied, and a port \
             cannot be made from bytes -- only bytes that arrived on a bridge carry one",
        );
    }
    let Some(b) = rt.wire_take(r, 4) else { return short(rt, "wire-port-in") };
    let id = u32::from_le_bytes([b[0], b[1], b[2], b[3]]);
    match rt.bridge_hook {
        Some(f) => f(rt, id as i64),
        None => rt.throw_str("IllegalStateException", "wire-port-in: this sandbox has no ports"),
    }
});

builtin!(flint_b_wire_opaque_in, b_wire_opaque_in, |rt, a, n| {
    let _ = n;
    let Some(r) = reader_arg(rt, a, "wire-opaque-in") else { return NIL };
    if !rt.wire_may_mint(r) {
        return rt.throw_str(
            "SecurityException",
            "wire-opaque-in: this reader is over bytes the program supplied, and an \
             opaque value cannot be made from bytes",
        );
    }
    let Some(ib) = rt.wire_take(r, 8) else { return short(rt, "wire-opaque-in") };
    let id = i64::from_le_bytes([ib[0], ib[1], ib[2], ib[3], ib[4], ib[5], ib[6], ib[7]]);
    let Some(lb) = rt.wire_take(r, 4) else { return short(rt, "wire-opaque-in") };
    let len = u32::from_le_bytes([lb[0], lb[1], lb[2], lb[3]]) as usize;
    let Some(b) = rt.wire_take(r, len) else { return short(rt, "wire-opaque-in") };
    let label = match core::str::from_utf8(&b) {
        Ok(t) => rt.string(t),
        Err(_) => return rt.throw_str("IllegalArgumentException", "wire-opaque-in: not valid UTF-8"),
    };
    let base = rt.mark();
    let li = rt.push(label);
    let o = rt.new_opaque(rt.r(li), id);
    rt.pop_to(base);
    o
});

/// A TABLE OPENS: the tag, the column count, then a name and a type per column
/// as ordinary values, then a row count, then the cells column by column.
///
/// The row count sits AFTER values, which is why the table could not be written
/// until the writer tracked structure. A bare count primitive would put four
/// raw bytes at a value position, and four bytes there are a tag and its
/// payload on the far side -- `0000000f` is `K_PORT` and an id. Now the count
/// goes only where a count is due, so it can never be read as a tag.
builtin!(flint_b_wire_table, b_wire_table, |rt, a, n| {
    let _ = n;
    let Some(w) = writer_arg(rt, a, "wire-table") else { return NIL };
    let v = arg(rt, a, 1);
    let Some(c) = rt.as_i64(v) else {
        return rt.throw_str("ClassCastException", "wire-table wants a column count")
    };
    if !count_ok(rt, c, "wire-table") { return NIL }
    if !rt.wire_open_table(w, c) {
        return rt.throw_str(
            "IllegalStateException",
            "wire-table: no value is due here -- the message is already complete, or a \
             count was expected",
        );
    }
    let ok = rt.wire_piece(w, flint_rt::codec::K_TABLE, &(c as u32).to_le_bytes());
    if !ok { return wrote(rt, ok, "wire-table") }
    arg(rt, a, 0)   // RE-READ: see `writer_arg`
});

/// The ROW COUNT, legal only after the column pairs and nowhere else.
builtin!(flint_b_wire_table_rows, b_wire_table_rows, |rt, a, n| {
    let _ = n;
    let Some(w) = writer_arg(rt, a, "wire-table-rows") else { return NIL };
    let v = arg(rt, a, 1);
    let Some(c) = rt.as_i64(v) else {
        return rt.throw_str("ClassCastException", "wire-table-rows wants a row count")
    };
    if !count_ok(rt, c, "wire-table-rows") { return NIL }
    if !rt.wire_expect_rowcount(w, c) {
        return rt.throw_str(
            "IllegalStateException",
            "wire-table-rows: no row count is due here -- a table's columns are named \
             and typed first",
        );
    }
    let ok = rt.wire_raw(w, &(c as u32).to_le_bytes());
    if !ok { return wrote(rt, ok, "wire-table-rows") }
    arg(rt, a, 0)   // RE-READ: see `writer_arg`
});

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
    ("flint/request", "flint_b_request"),
    ("flint/port-send", "flint_b_port_send"),
    ("flint/port-receive", "flint_b_port_receive"),
    ("flint/port-receive-reader", "flint_b_port_receive_reader"),
    ("flint/port-close", "flint_b_port_close"),
    ("flint/system-port", "flint_b_system_port"),
    ("flint/port?", "flint_b_port_p"),
    ("flint/port-state", "flint_b_port_state"),
    ("flint/port-label", "flint_b_port_label"),
    ("flint/port-bridge?", "flint_b_port_bridge_p"),
    ("flint/port-id", "flint_b_port_id"),
    ("flint/wire-writer", "flint_b_wire_writer"),
    ("flint/wire-writer?", "flint_b_wire_writerp"),
    ("flint/wire-nil", "flint_b_wire_nil"),
    ("flint/wire-bool", "flint_b_wire_bool"),
    ("flint/wire-int", "flint_b_wire_int"),
    ("flint/wire-double", "flint_b_wire_double"),
    ("flint/wire-str", "flint_b_wire_str"),
    ("flint/wire-bytes", "flint_b_wire_bytes"),
    ("flint/wire-kw", "flint_b_wire_kw"),
    ("flint/wire-sym", "flint_b_wire_sym"),
    ("flint/wire-vec", "flint_b_wire_vec"),
    ("flint/wire-list", "flint_b_wire_list"),
    ("flint/wire-set", "flint_b_wire_set"),
    ("flint/wire-map", "flint_b_wire_map"),
    ("flint/wire-meta", "flint_b_wire_meta"),
    ("flint/wire-port", "flint_b_wire_port"),
    ("flint/wire-opaque", "flint_b_wire_opaque"),
    ("flint/wire-tagged", "flint_b_wire_tagged"),
    ("flint/wire-table", "flint_b_wire_table"),
    ("flint/wire-table-rows", "flint_b_wire_table_rows"),
    ("flint/wire-reader", "flint_b_wire_reader"),
    ("flint/wire-tag", "flint_b_wire_tag"),
    ("flint/wire-left", "flint_b_wire_left"),
    ("flint/wire-u32", "flint_b_wire_u32"),
    ("flint/wire-i64", "flint_b_wire_i64"),
    ("flint/wire-f64", "flint_b_wire_f64"),
    ("flint/wire-text", "flint_b_wire_text"),
    ("flint/wire-ns", "flint_b_wire_ns"),
    ("flint/wire-blob", "flint_b_wire_blob"),
    ("flint/wire-port-in", "flint_b_wire_port_in"),
    ("flint/wire-opaque-in", "flint_b_wire_opaque_in"),
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

    // --- THE THREE OPERATIONS (`DECISIONS.md#four-operations`) -------------
    //
    // `boot`, `loop`, `link`, the same three every target exposes. They sit
    // BESIDE the twelve-function protocol below rather than replacing it, and
    // that is deliberate for now: the twelve work, they are exercised by 366
    // conformance rows, and a rewrite of the bridge into a host-side ring is
    // named as separate work in `bridges-are-the-only-door`'s table. What this
    // buys today is the UNIFORM SURFACE -- a host writes the same three calls
    // whichever target it holds -- with the plumbing unchanged underneath.
    //
    // `boot` ANSWERS A SANDBOX HANDLE, and on wasm that handle is almost
    // ceremony: `Rt` is one `static mut` per instance, so one module instance
    // IS one sandbox and the handle can only ever be 1. It is here anyway,
    // because the jvm's `boot` answers a `Sandbox` object and the clr's static
    // surface was found to mean one sandbox per PROCESS -- the shape that does
    // not survive concurrent `loop`. Taking the handle keeps wasm honest about
    // which sandbox is meant, and a future shared-memory build can hold more
    // than one without the signature moving.
    //
    // NO SANDBOX ARGUMENT ON THE BRIDGE ITSELF: wasm cannot pass an object, so
    // the bridge is the host satisfying this module's imports, and a module
    // declares zero imports today -- nothing to break.

    /// The only handle this instance can answer, and the only one the other two
    /// accept. Not zero: zero is what an uninitialised `i32` reads as, and a
    /// handle that is indistinguishable from "never booted" would let
    /// `flint_loop(0)` look valid before `boot` ran.
    const ONLY_SANDBOX: u32 = 1;

    static mut BOOTED: bool = false;

    /// `boot` -- install the host's bridge as the SYSTEM PORT and answer a
    /// sandbox handle, or 0 if it could not.
    ///
    /// The name comes in the inbound buffer, as `flint_install_port` takes it.
    /// A second `boot` answers 0 rather than re-installing: a sandbox is one
    /// program for its whole life (`one-image-per-sandbox`), and re-booting one
    /// is the same class of mistake as swapping its image.
    #[no_mangle]
    pub extern "C" fn flint_boot(len: u32) -> u32 {
        unsafe {
            if BOOTED {
                return 0;
            }
            // `1` for the system flag: this IS the system port, which is what
            // makes it the sandbox's only door (`bridges-are-the-only-door`).
            if flint_install_port(0, len, 1) == 0 {
                return 0;
            }
            BOOTED = true;
            ONLY_SANDBOX
        }
    }

    /// The three statuses, DECLARED rather than only documented.
    ///
    /// They were a comment on `flint_loop` until `bin/check-artifact-ops`
    /// refused it, and the refusal was right: a comment saying "0, 1, 2" beside
    /// a function is the prose this project has repeatedly watched fail to bind
    /// the code next to it. The NAMES differ per language and are allowed to --
    /// the jvm has `DONE`/`THREW`/`NEEDS_HOST`, the clr an `enum Status` -- and
    /// the NUMBERS are the ABI.
    pub const DONE: i32 = 0;
    pub const THREW: i32 = 1;
    pub const NEEDS_HOST: i32 = 2;

    /// `loop` -- pump, and answer 0 Done, 1 Threw, 2 NeedsHost.
    ///
    /// THE NUMBERS ARE THE ABI and are not re-spelled here; the jvm writes them
    /// as `DONE`/`THREW`/`NEEDS_HOST` and the clr as an `enum Status`, and all
    /// three agree on 0, 1, 2. `NeedsHost` is the RESTING state, not an error:
    /// the control plane is a green thread parked on the system port, so a
    /// healthy idle sandbox reports it.
    ///
    /// -1 for a handle this instance cannot serve, which is not a status value
    /// and is deliberately outside their range.
    #[no_mangle]
    pub extern "C" fn flint_loop(sandbox: u32) -> i32 {
        unsafe {
            if !BOOTED || sandbox != ONLY_SANDBOX {
                return -1;
            }
        }
        flint_resume()
    }

    /// `link` -- override the native called `name`, whose bytes are in the
    /// inbound buffer, with the function at `slot` in this module's table.
    ///
    /// A SLOT AND NOT A CLOSURE, because wasm cannot take one: a module's
    /// imports are fixed at instantiation, so `link` can only rebind a slot
    /// this module already carries and can NEVER introduce new host code. That
    /// is a real difference from the jvm and clr, where the registry is a
    /// mutable name-to-closure map, and it is recorded as such rather than
    /// papered over.
    ///
    /// Answers 0 on success, and refuses with 2 AFTER `boot`: natives resolve
    /// exactly once when the image loads, so a later hook is never reached and
    /// silently doing nothing would be the worst available answer.
    #[no_mangle]
    pub extern "C" fn flint_link(sandbox: u32, len: u32, slot: u32) -> i32 {
        let _ = (len, slot);
        unsafe {
            if BOOTED {
                return 2;
            }
            if sandbox != ONLY_SANDBOX {
                return -1;
            }
        }
        // NOT IMPLEMENTED, AND SAYING SO. Rebinding a table slot by name needs
        // the builtin registry this module carries only in a `--loader` build,
        // and answering 0 here would claim an override happened. 1 is "this
        // artifact cannot".
        1
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

    /// Install a BRIDGE the host owns (`DECISIONS.md#ports-are-the-hosts`), and say whether
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
    /// `port` (`DECISIONS.md#ports-are-the-hosts`).
    ///
    /// This is the half `flint_continue` cannot do. A grant has to NAME a port,
    /// because there is no port until the host says which one -- the sandbox no
    /// longer manufactures a pair and keeps one end. `flint_continue(token, 1)`
    /// is therefore refused rather than guessed at, and returns 0.
    #[no_mangle]
    pub extern "C" fn flint_grant(token: u32, port: u32) -> u32 {
        rt().host_grant(token as i64, port as i64) as u32
    }

    /// Answer an `EV_REQUEST`: the bytes in the inbound buffer are the value
    /// (`DECISIONS.md#workspace-capabilities` step 7).
    ///
    /// The counterpart of `flint_grant`, for the requests whose answer is not a
    /// port. A port is granted BY ID and never encoded; anything else crosses
    /// as encoded bytes like every other value on a bridge. To REFUSE, call
    /// `flint_continue(token, 0)` as with an open -- a refusal carries no value
    /// and needs no buffer.
    #[no_mangle]
    pub extern "C" fn flint_answer(token: u32, len: u32) -> u32 {
        let bytes: alloc::vec::Vec<u8> = unsafe {
            let b = &*core::ptr::addr_of!(IN);
            b[..len as usize].to_vec()
        };
        rt().host_answer(token as i64, &bytes) as u32
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
    /// (`DECISIONS.md#structured-ports` step 5) a resume has no single answer to render --
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
