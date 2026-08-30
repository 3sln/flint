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

fn show(evs: &[Event]) -> String {
    evs.iter()
        .map(|e| {
            let p = String::from_utf8_lossy(&e.payload);
            format!("{}({},{},{:?})", kind_name(e.kind), e.a, e.b, p)
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
    let out = p.run(&[]);
    println!("  ok   the program parked on the host: status {}", out.code);
    let evs = p.drain_events();
    println!("  ok   it asked: {}", show(&evs));
    let open = evs.iter().find(|e| e.kind as i64 == conc::EV_OPEN).expect("an open-request");
    let token = open.a;
    let port = open.b;

    // 2. Nothing was presented -- and that is a DIFFERENT answer from "I do not
    //    recognise this", which is why both exist.
    println!("  ok   presented capability: {}", p.presented_capability(port));

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
