//! The REQUEST/RESPONSE half of the host ABI, driven by a host that answers.
//!
//! `flint.host/request` raises an `EV_REQUEST` and parks the calling thread;
//! the host answers with encoded bytes and the thread wakes with the value.
//! Nothing in the tree answered one before this driver, so the two runtime
//! codec uses that path still carries had nothing watching them
//! (`DECISIONS.md#the-codec-is-guest-code`).
//!
//! ## What this transcript asserts
//!
//! What CROSSED and what came BACK. It prints no tokens and no port ids: a
//! request's token is an opaque handle the host echoes and never reads, so
//! three independent implementations agreeing on its numeric VALUE is not part
//! of the contract -- asserting it would pin a waiter index rather than a
//! behaviour, and the three runtimes already allocate those in a different
//! order for reasons that have nothing to do with this protocol.
use flint_rt::conc;
use flint_rt::native::{Event, Program};

const SYSTEM: u32 = 1;

/// The request payload, rendered. `[what, ...args]` -- the same shape `open`
/// forwards, because it is the same forwarding.
///
/// The renderer is `hostports.rs`'s, walking the wire bytes directly rather
/// than decoding into the runtime: a driver that used the runtime's decoder to
/// check the runtime's encoder would be marking its own homework.
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

fn main() {
    let path = std::env::args().nth(1).expect("usage: hostreq <image>");
    let bytes = std::fs::read(&path).expect("the image is readable");
    let mut p = Program::load_with(&bytes, 512 * 1024 * 1024, flint_conc::HOST_CATALOGUE)
        .expect("the image loads");
    p.install_port(SYSTEM, "system", true);

    let mut out = p.run_with(&[], &[]);
    // Four exchanges: two answered, one refused, one refused through `ask`.
    let mut seen = 0;
    for _ in 0..12 {
        if out.code != 2 {
            break;
        }
        let evs: Vec<Event> = p.drain_events();
        let mut acted = false;
        for e in evs.iter().filter(|e| e.kind as i64 == conc::EV_REQUEST) {
            seen += 1;
            println!("  ok   it asked: {}", render(&e.payload));
            // `nope` is REFUSED, which is a `SecurityException` on the guest
            // side; everything else is answered with a string.
            if render(&e.payload).contains("nope") {
                println!("  ok   refused: {}", p.host_continue(e.a, false));
            } else {
                let answer = flint_rt::codec::Wire::of_str("tick");
                println!("  ok   answered: {}", p.host_answer(e.a, &answer));
            }
            acted = true;
        }
        if !acted && evs.is_empty() {
            break;
        }
        out = p.resume();
    }
    println!("  ok   requests seen: {seen}");
    // AND NOW LET IT FINISH. The control plane parks on the system port for as
    // long as the host holds it, so a sandbox with one is never "done" --
    // which is correct, and means the entry's value cannot be read while it is
    // open. Closing the door drains `flint.system/serve`, and what comes back
    // is the string the guest assembled FROM THE ANSWERS: the only line here
    // that checks the decode side rather than the host side.
    p.host_close_port(SYSTEM);
    for _ in 0..8 {
        if out.code != 2 {
            break;
        }
        let _ = p.drain_events();
        out = p.resume();
    }
    println!("  ok   the program answered: {}", out.out);
    println!("  ok   status {}", out.code);
}
