//! The pump: run a program, serve what it asks for, resume (`system-namespaces-and-deps`).
//!
//! The Rust CLI had no such loop. `run_with` ran a program to completion and a
//! program that opened a port simply parked for ever, because nothing drained
//! the event queue -- so `flint run` could execute logic and could not execute
//! anything that talked to the world. Everything in `system-namespaces-and-deps` needs this before it
//! needs anything else.
//!
//! It mirrors `sdks/esm/src/guest.js`, which is the reference driver, and the
//! ordering rules are the ones that file records:
//!
//! * a grant NAMES a port, because there is no port until the host says which
//!   one (`ports-are-the-hosts`);
//! * routing is set before the grant is answered, so a message cannot arrive
//!   for a port the host does not yet know;
//! * `resume` is called until the program settles or stops asking.

use crate::policy::Policy;
use crate::sys::{self, Service};
use flint_rt::codec::{self, Val, Wire};
use flint_rt::native::{Event, Outcome, Program};
use std::collections::HashMap;

// The event kinds, from `runtime/src/conc.rs`. Named here rather than matched
// as bare integers: `if ev.kind == 1` is unreadable and, worse, unsearchable.
const EV_OPEN: u32 = 1;
const EV_MESSAGE: u32 = 2;
const EV_CLOSED: u32 = 3;
const EV_RETAIN: u32 = 4;
const EV_RELEASE: u32 = 5;

/// Ports this host owns, above the ids a sandbox uses for its own channels so a
/// collision in a log is obvious rather than plausible.
const FIRST_PORT: u32 = 1000;

/// Our answer to `call_named`, or `None` if this message is somebody else's.
///
/// `:tx` IS CHECKED, not assumed. Several calls can be in flight at once --
/// `runtime/src/conc.rs` says "told apart by their `:tx`, and `settle` sends
/// each answer as its thread finishes" -- so taking the first `:return` that
/// arrives would hand one caller another's value.
fn reply_for(tx: i64, payload: &[u8]) -> Option<Result<Vec<u8>, String>> {
    let v = codec::parse(payload).ok()?;
    if v.get("tx").and_then(|t| t.as_i64()) != Some(tx) {
        return None;
    }
    match v.get("op").and_then(|o| o.as_str()) {
        Some("return") => {
            let mut w = Wire::new();
            v.get("value").unwrap_or(&Val::Nil).write(&mut w);
            Some(Ok(w.done()))
        }
        // A THROW IS AN ERROR, not a value. The guest formed the sentence; this
        // passes it through rather than rewording it.
        Some("throw") => {
            let kind = v.get("kind").and_then(|k| k.as_str()).unwrap_or("Error");
            let msg = v.get("message").and_then(|m| m.as_str()).unwrap_or("");
            Some(Err(format!("{kind}: {msg}")))
        }
        _ => None,
    }
}

pub struct Host {
    services: Vec<Box<dyn Service>>,
    /// port id -> index into `services`
    routes: HashMap<u32, usize>,
    next_port: u32,
    policy: Policy,
    /// The port `open` requests go out on, when anything is served.
    system: Option<u32>,
    /// How many sandboxes hold each port. The runtime pushes exactly one retain
    /// and one release per port per sandbox (`ports-are-the-hosts`), so this reaching zero is
    /// what says the resource can go -- a count of ARRIVALS would not.
    holders: HashMap<u32, i64>,
    /// Transaction ids for `call_named`, so several calls can be told apart.
    next_tx: i64,
}

impl Host {
    pub fn new(policy: Policy) -> Host {
        Host {
            services: Vec::new(),
            routes: HashMap::new(),
            next_port: FIRST_PORT,
            policy,
            system: None,
            holders: HashMap::new(),
            next_tx: 0,
        }
    }

    pub fn serve(&mut self, s: Box<dyn Service>) {
        self.services.push(s);
    }

    fn index_of(&self, name: &str) -> Option<usize> {
        self.services.iter().position(|s| s.name() == name)
    }

    /// Run the program's entry with `args` and the host's `named` opaque values,
    /// serving whatever it asks for, until it settles.
    pub fn run_with(
        &mut self,
        p: &mut Program,
        args: &[&str],
        named: &[(&str, u64)],
    ) -> Outcome {
        // THE SYSTEM PORT FIRST. A sandbox asks for a capability by sending on
        // the port it was given at construction, and one that was given none
        // cannot ask at all -- it is told so rather than parked, which is the
        // honest failure (`ports-are-the-hosts`) and is exactly what a program requiring
        // `flint.sys.fs` hit before this line existed:
        //
        //     SecurityException: this sandbox was given no system port,
        //     so it cannot ask for "flint.sys.fs"
        //
        // Installed only when something is actually served. A program that was
        // granted nothing keeps the honest refusal instead of being handed a
        // transport that can reach nothing.
        if !self.services.is_empty() {
            let id = self.next_port;
            self.next_port += 1;
            p.install_port(id, "system", true);
            self.system = Some(id);
        }
        let out = p.run_with(args, named);
        self.pump(p, out)
    }

    /// Call a NAMED function and pump until it answers.
    ///
    /// The other half of `run_with`. `run_with` invokes the image's entry;
    /// this asks for a function by name, which is what `flint.ception/call`
    /// needs (`DECISIONS.md#flint-ception`).
    ///
    /// **Over the SYSTEM PORT, not through `Program::call`.** That one is
    /// `flint_call` -- synchronous, no scheduler, and no way to express
    /// parking -- so a function that opens a port cannot be called through it
    /// at all. The runtime already implements the port protocol
    /// (`runtime/src/conc.rs`, `system_message`):
    ///
    ///     ->  {:tx n :op :call :fn "ns/name" :args [..]}
    ///     <-  {:tx n :op :return :value v}
    ///     <-  {:tx n :op :throw  :kind ".." :message ".."}
    ///
    /// and a call there RUNS AS A GREEN THREAD, so the called function may park
    /// and this loop answers it while the call is still outstanding. That is
    /// the property `sdks/rust` states as "from inside, a call is a port send
    /// and a park": the caller's own green thread is parked in a served
    /// request, and no driver thread is held waiting on a driver thread.
    pub fn call_named(&mut self, p: &mut Program, name: &str, args: &[Val])
                      -> Result<Vec<u8>, String> {
        let sys = match self.system {
            Some(id) => id,
            None => {
                let id = self.next_port;
                self.next_port += 1;
                if !p.install_port(id, "system", true) {
                    return Err(String::from(
                        "this sandbox would not take a system port, so it cannot be called by name",
                    ));
                }
                self.system = Some(id);
                id
            }
        };
        self.next_tx += 1;
        let tx = self.next_tx;
        let mut w = Wire::new();
        w.map(4);
        w.keyword(None, "tx");
        w.int(tx);
        w.keyword(None, "op");
        w.keyword(None, "call");
        w.keyword(None, "fn");
        w.string(name);
        w.keyword(None, "args");
        w.vector(args.len() as u32);
        for a in args {
            a.write(&mut w);
        }
        if !p.host_deliver(sys, w.as_bytes()) {
            let _ = p.resume();
            if !p.host_deliver(sys, w.as_bytes()) {
                return Err(String::from("the system port would not take the call"));
            }
        }
        let mut out = p.resume();
        let mut guard = 0u32;
        loop {
            guard += 1;
            if guard > 1_000_000 {
                return Err(String::from("the host pump made no progress"));
            }
            for ev in p.drain_events() {
                // OUR ANSWER, or somebody else's request. A `:return` on the
                // system port carrying our `:tx` is the reply; anything else on
                // any port is an ordinary request and goes to `handle`, which
                // is what lets the called function park on a capability and be
                // answered while this call is outstanding.
                if ev.kind == EV_MESSAGE && ev.a == sys {
                    if let Some(r) = reply_for(tx, &ev.payload) {
                        return r;
                    }
                }
                self.handle(p, ev);
            }
            if out.code != 2 {
                // The program stopped without answering. Say so rather than
                // looping: a call whose thread died is not a call still coming.
                return Err(format!(
                    "the sandbox stopped before answering (code {})\n{}",
                    out.code,
                    out.out.trim()
                ));
            }
            out = p.resume();
        }
    }

    /// Serve until the program stops asking.
    fn pump(&mut self, p: &mut Program, first: Outcome) -> Outcome {
        let mut out = first;
        // A guard rather than `loop`: a program that parks on something nothing
        // will ever answer would otherwise spin here for ever, and a hang with
        // no output is the least actionable failure there is.
        let mut guard = 0u32;
        let mut closed = false;
        while out.code == 2 {
            guard += 1;
            if guard > 1_000_000 {
                return Outcome {
                    code: 1,
                    out: "the host pump made no progress".into(),
                };
            }
            let events = p.drain_events();
            // The only window into a pump that otherwise fails as a silent
            // status 2, which is exactly how this loop's first bug presented.
            if std::env::var("FLINT_TRACE_PORTS").is_ok() {
                eprintln!("[pump] code={} events={}", out.code, events.len());
                for e in &events {
                    eprintln!(
                        "[pump]   kind={} a={} b={} payload={}",
                        e.kind,
                        e.a,
                        e.b,
                        e.payload.len()
                    );
                }
            }
            if events.is_empty() {
                // NOTHING LEFT TO SERVE, and still parked. That is not a hang:
                // `flint.rpc` spawns a reader thread that parks on its port for
                // ever BY DESIGN, and a thread parked on a bridge is exactly
                // what `needs_host` reports -- so the runtime keeps saying "the
                // host is needed" and means "you might still write to me".
                //
                // The host is the only one who knows it will not. Closing our
                // ports says so: each reader wakes with the port finished, its
                // loop ends, and the program settles for real -- at which point
                // the entry's answer comes back instead of a status 2 that a
                // shell would see as a failing exit code.
                //
                // Once. A second sweep would find the same ports and loop.
                if closed {
                    break;
                }
                closed = true;
                for port in self.routes.keys().copied().collect::<Vec<_>>() {
                    p.host_close_port(port);
                }
                if let Some(sysp) = self.system.take() {
                    p.host_close_port(sysp);
                }
                out = p.resume();
                continue;
            }
            for ev in events {
                self.handle(p, ev);
            }
            out = p.resume();
        }
        out
    }

    fn handle(&mut self, p: &mut Program, ev: Event) {
        match ev.kind {
            EV_OPEN => {
                // The payload is `[name & args]`, encoded (`ports-are-the-hosts`).
                let name = codec::parse(&ev.payload)
                    .ok()
                    .and_then(|v| v.as_slice().and_then(|s| s.first().cloned()))
                    .and_then(|v| v.as_str().map(|s| s.to_string()))
                    .unwrap_or_default();
                match self.index_of(&name) {
                    None => {
                        p.host_continue(ev.a, false);
                    }
                    Some(i) => {
                        let port = self.next_port;
                        self.next_port += 1;
                        // Routing FIRST. A message cannot arrive before the
                        // grant returns, but making the order depend on that
                        // is the kind of assumption that stops being true.
                        self.routes.insert(port, i);
                        if p.host_grant(ev.a, port) {
                            // Counted here rather than from an event: this host
                            // granted the port, so it knows, and the runtime
                            // does not push a retain for what the host did
                            // itself (`ports-are-the-hosts`).
                            *self.holders.entry(port).or_insert(0) += 1;
                        } else {
                            self.routes.remove(&port);
                            p.host_continue(ev.a, false);
                        }
                    }
                }
            }
            EV_MESSAGE => {
                let port = ev.a;
                let Some(&i) = self.routes.get(&port) else { return };
                let req = match codec::parse(&ev.payload) {
                    Ok(v) => v,
                    // A message the format cannot read is not turned into
                    // something else: there is no id to reply to, so there is
                    // nobody to tell, and inventing a reply would wake a caller
                    // with a value that was never sent.
                    Err(_) => return,
                };
                let reply = sys::serve(self.services[i].as_mut(), &req, &self.policy);
                // Back-pressure is real: a full guest buffer answers false, and
                // the message has to be offered again after the next pump
                // (`ports-are-the-hosts`). One retry after a resume covers the case that
                // actually happens -- a buffer drained by the reader thread --
                // and a queue here would be a second scheduler.
                if !p.host_deliver(port, &reply) {
                    let _ = p.resume();
                    p.host_deliver(port, &reply);
                }
            }
            EV_RETAIN => {
                *self.holders.entry(ev.a).or_insert(0) += 1;
            }
            EV_RELEASE => {
                let n = self.holders.entry(ev.a).or_insert(0);
                *n -= 1;
                if *n <= 0 {
                    self.holders.remove(&ev.a);
                    self.routes.remove(&ev.a);
                }
            }
            EV_CLOSED => {
                self.routes.remove(&ev.a);
                self.holders.remove(&ev.a);
            }
            _ => {}
        }
    }
}
