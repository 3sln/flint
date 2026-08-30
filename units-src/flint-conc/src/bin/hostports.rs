//! THE HOST, driving a real image through the port protocol.
//!
//! `runtimes/conform/hostport.cljc` is the program; this is the other half of
//! it. The JVM and CLR runtimes have their own drivers -- `RtHostPorts.java`
//! and `--rt-hostports` -- running the SAME image through the SAME script, and
//! `bin/conform-hosts` compares the three transcripts.
//!
//! That comparison is the whole point. The host protocol is an ABI: a token
//! whose generation must be checked, a byte budget that must be respected in
//! both directions, an event layout with five fields per record. Three
//! runtimes implementing an ABI separately are three ABIs until something
//! makes them say the same thing out loud.
//!
//! Every line printed is an assertion. A transcript that differs anywhere is a
//! divergence, and the line names it.

use flint_rt::conc;
use flint_rt::native::{Event, Program};

fn kind_name(k: u32) -> &'static str {
    match k as i64 {
        conc::EV_OPEN => "open",
        conc::EV_MESSAGE => "message",
        conc::EV_CLOSED => "closed",
        _ => "?",
    }
}

fn state_name(s: i64) -> &'static str {
    match s {
        conc::P_PENDING => "pending",
        conc::P_OPEN => "open",
        conc::P_CLOSED => "closed",
        conc::P_HALF => "half-closed",
        conc::P_ORPHANED => "orphaned",
        conc::P_REFUSED => "refused",
        255 => "unknown",
        _ => "?",
    }
}

/// The open-request payload, rendered.
///
/// It is an ENCODED VALUE now rather than a bare name, which is what "the host
/// does what it wants with the args" means in practice: this driver decodes it
/// the way any host would, and an opaque value arrives carrying the id the host
/// issued -- which is the whole of the capability check, done here rather than
/// by the runtime.
fn render(b: &[u8]) -> String {
    let mut i = 0usize;
    one(b, &mut i)
}

fn u32_at(b: &[u8], i: &mut usize) -> u32 {
    let v = u32::from_le_bytes([b[*i], b[*i + 1], b[*i + 2], b[*i + 3]]);
    *i += 4;
    v
}

fn str_at(b: &[u8], i: &mut usize) -> String {
    let n = u32_at(b, i) as usize;
    let s = String::from_utf8_lossy(&b[*i..*i + n]).into_owned();
    *i += n;
    s
}

fn one(b: &[u8], i: &mut usize) -> String {
    let t = b[*i];
    *i += 1;
    match t {
        0 => String::from("nil"),
        1 => String::from("true"),
        2 => String::from("false"),
        3 => {
            let lo = u32_at(b, i) as u64;
            let hi = u32_at(b, i) as u64;
            format!("{}", ((hi << 32) | lo) as i64)
        }
        5 => format!("{:?}", str_at(b, i)),
        6 | 7 => {
            let save = *i;
            let ns = u32_at(b, i);
            let nss = if ns == u32::MAX {
                String::new()
            } else {
                *i = save;
                format!("{}/", str_at(b, i))
            };
            let sigil = if t == 6 { ":" } else { "" };
            format!("{sigil}{nss}{}", str_at(b, i))
        }
        8 | 9 | 11 => {
            let n = u32_at(b, i);
            let parts: Vec<String> = (0..n).map(|_| one(b, i)).collect();
            format!("[{}]", parts.join(" "))
        }
        10 => {
            let n = u32_at(b, i);
            let parts: Vec<String> = (0..n)
                .map(|_| {
                    let k = one(b, i);
                    let v = one(b, i);
                    format!("{k} {v}")
                })
                .collect();
            format!("{{{}}}", parts.join(", "))
        }
        // The one that matters: an opaque value with the id the HOST gave it.
        // A guest-minted one has id 0 and is therefore recognisably not ours.
        16 => {
            let lo = u32_at(b, i) as u64;
            let hi = u32_at(b, i) as u64;
            let id = (hi << 32) | lo;
            format!("#opaque[{:?} id={}]", str_at(b, i), id)
        }
        other => format!("#tag{other}"),
    }
}

fn show(evs: &[Event]) -> String {
    evs.iter()
        .map(|e| {
            let p = if e.kind as i64 == conc::EV_OPEN {
                render(&e.payload)
            } else {
                format!("{:?}", String::from_utf8_lossy(&e.payload))
            };
            format!("{}({},{},{})", kind_name(e.kind), e.a, e.b, p)
        })
        .collect::<Vec<_>>()
        .join(" ")
}

fn main() {
    let path = std::env::args().nth(1).expect("usage: hostports <image>");
    let bytes = std::fs::read(&path).expect("the image is readable");
    let mut p = Program::load_with(&bytes, 512 * 1024 * 1024, flint_conc::HOST_CATALOGUE)
        .expect("the image loads");

    // 1. The program runs until it asks for something only the host has.
    //
    // One opaque value is PROJECTED IN as the entry's second argument, under an
    // id this driver chose. That is the whole of lending a capability: there is
    // no grant table, no declaration, and nothing in the runtime that knows what
    // the value is for.
    let out = p.run_with(&[], &[("fs", 7)]);
    println!("  ok   the program parked on the host: status {}", out.code);
    let evs = p.drain_events();
    println!("  ok   it asked: {}", show(&evs));
    let open = evs.iter().find(|e| e.kind as i64 == conc::EV_OPEN).expect("an open-request");
    let token = open.a;
    let port = open.b;

    // 2. WHAT WAS FORWARDED, decoded. The payload is the arguments the guest
    //    passed to `open`, as one encoded value -- so a host reads them and
    //    decides for itself. Nothing in the runtime looked at them on the way
    //    past, and nothing in it knows what a capability is.
    println!("  ok   it forwarded: {}", render(&open.payload));

    // 3. Grant it. A second answer on the same token is refused: the generation
    //    in it has moved on, so a late or duplicated reply cannot resume a
    //    stranger's thread.
    println!("  ok   the host grants it: {}", p.host_continue(token, true));
    println!("  ok   and a duplicate reply is refused: {}", p.host_continue(token, true));
    println!("  ok   the runtime end is now: {}", state_name(p.host_port_state(port)));

    // 4. Push something in, let the program read it and answer.
    println!("  ok   delivered: {}", p.host_deliver(port, b"one"));
    let out = p.resume();
    println!("  ok   ran on: status {}", out.code);
    let evs = p.drain_events();
    println!("  ok   it sent back: {}", show(&evs));

    // 5. A second wave, then hang up. Drained-and-closed is END OF STREAM --
    //    `nil` and not an error -- and the program's own `state` call has to
    //    agree with what the host sees.
    println!("  ok   delivered: {}", p.host_deliver(port, b"two"));
    p.host_close_port(port);
    println!("  ok   after the host hangs up: {}", state_name(p.host_port_state(port)));
    let mut out = p.resume();
    let mut tail: Vec<Event> = Vec::new();
    // The last pump is TWO pumps: exit closes every flint end and pushes an
    // `EV_CLOSED` for each, and a run with events pending comes back 2 -- so a
    // host is never left guessing whether more is coming.
    for _ in 0..4 {
        if out.code != 2 {
            break;
        }
        tail.extend(p.drain_events());
        out = p.resume();
    }
    println!("  ok   the program answered: {}", out.out);
    println!("  ok   status {}", out.code);
    println!("  ok   and was told the port closed: {}", show(&tail));

    // 6. An id the runtime has never heard of. A host treats this as done,
    //    which is what makes the pushed `:closed` an optimisation over polling
    //    rather than the sole carrier of the truth -- a dropped event would
    //    otherwise leak a handle to a port nobody will ever mention again.
    println!("  ok   an unknown port id: {}", state_name(p.host_port_state(999_999)));
}
