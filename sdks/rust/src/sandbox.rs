//! A sandbox, and the inbox that is the only way into it
//! (`doc/decisions/0027`, `0028`).
//!
//! `Sandbox` is a HANDLE, not an instance. The instance lives wherever its
//! driver put it, and the handle is an id plus a way to reach it -- which is
//! what lets the driver decide the thread, and eventually the threads.
//!
//! A call does not run the program. It encodes a request, puts it in the
//! inbox, and wakes the driver; the driver decides when a runnable sandbox
//! runs, and the answer arrives on the `Pending` the call handed back.

use crate::driver::{Driver, Inline};
use crate::value::Value;
use crate::{Error, Result};
use flint_rt::native::Program;
use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{mpsc, Arc, Mutex};
use std::time::Duration;

/// One request waiting to be dispatched.
struct Request {
    name: String,
    args: Vec<Value>,
    reply: mpsc::Sender<Result<Value>>,
}

/// The sandbox itself: its program, its inbox, and whether a dispatch is
/// already on its way.
///
/// Shared by every thread that may advance it, which is why the program is
/// behind a lock. **That lock is the whole of what stands between this and
/// several threads running one heap in parallel.** Removing it needs a
/// collector with per-thread allocation buffers and safepoints
/// (`doc/decisions/0028`); it does not need a different interface, which is
/// why the interface is already written for K > 1.
pub struct Core {
    program: Mutex<Program>,
    inbox: Mutex<VecDeque<Request>>,
    /// True from the moment a wake is accepted until the dispatch that owns it
    /// finds nothing left to do. THE coalescing flag: it answers true exactly
    /// once between dispatches, so a burst of arrivals costs one crossing of
    /// the sandbox boundary rather than one per arrival.
    scheduled: AtomicBool,
    /// How many times a dispatch has crossed into this sandbox, and how many
    /// requests those dispatches carried.
    ///
    /// Counted so the coalescing is MEASURABLE. "A burst of arrivals costs one
    /// crossing" is the claim the debouncing exists to make, and a claim with
    /// no number behind it is a hope.
    dispatches: std::sync::atomic::AtomicU64,
    served: std::sync::atomic::AtomicU64,
}

impl Core {
    /// Claim the right to queue this sandbox. True exactly once per dispatch.
    pub(crate) fn schedule(&self) -> bool {
        !self.scheduled.swap(true, Ordering::AcqRel)
    }

    /// Run whatever is in the inbox, then whatever arrived while doing so.
    ///
    /// Called by a driver, never by a caller. Several driver threads may be in
    /// here at once for the same sandbox: they take separate batches and the
    /// program lock serialises the execution.
    pub fn advance(&self) {
        loop {
            let batch: Vec<Request> = {
                let mut inbox = self.inbox.lock().unwrap();
                inbox.drain(..).collect()
            };

            if batch.is_empty() {
                // Clearing and re-checking, in that order, and both. A request
                // can land between the drain above and this store; clearing
                // without looking again would leave it queued behind a flag
                // that says a dispatch is already coming, and nothing would
                // ever come. That is the lost wake-up, and it presents as a
                // sandbox that stops answering under load and not otherwise.
                self.scheduled.store(false, Ordering::Release);
                if self.inbox.lock().unwrap().is_empty() {
                    return;
                }
                if !self.schedule() {
                    // Somebody else took it; it is their batch now.
                    return;
                }
                continue;
            }

            self.dispatches.fetch_add(1, Ordering::Relaxed);
            self.served.fetch_add(batch.len() as u64, Ordering::Relaxed);
            let mut program = self.program.lock().unwrap();
            for request in batch {
                let answer = dispatch(&mut program, &request);
                // A dropped receiver means the caller stopped waiting. That is
                // allowed and is not an error: the work was still done, and its
                // effect on the sandbox stands.
                let _ = request.reply.send(answer);
            }
        }
    }
}

fn dispatch(program: &mut Program, request: &Request) -> Result<Value> {
    let mut call = vec![Value::str(&request.name)];
    call.extend_from_slice(&request.args);
    let encoded = Value::Vector(call).encode();
    match program.call(&encoded) {
        Ok(bytes) => Value::decode(&bytes).map_err(Error::Encoding),
        Err(e) => {
            let (kind, message) = match e.split_once(": ") {
                Some((k, m)) => (k.to_string(), m.to_string()),
                None => ("Error".to_string(), e),
            };
            Err(Error::Call { kind, message })
        }
    }
}

/// An answer that has not arrived yet.
///
/// Deliberately not a `std::future::Future`: this crate has no async runtime
/// and should not require one. It is the smallest thing every SDK can mirror
/// -- a promise in JavaScript, a `Task` in C#, a `CompletableFuture` in Java, a
/// completion callback in C -- and a caller who wants a real future can wrap it
/// in three lines.
#[must_use = "a call that is never awaited still runs; drop it deliberately"]
pub struct Pending<T> {
    rx: mpsc::Receiver<Result<T>>,
}

impl<T> Pending<T> {
    /// Block until the answer arrives.
    ///
    /// Never call this from inside a sandbox. A call made from guest code that
    /// is occupying a driver thread, waiting on a reply that needs a driver
    /// thread, is the oldest deadlock there is -- which is why from inside, a
    /// call is a port send and a park rather than this.
    pub fn wait(self) -> Result<T> {
        self.rx.recv().map_err(|_| Error::Call {
            kind: "Dropped".into(),
            message: "the sandbox went away before answering".into(),
        })?
    }

    /// Block for at most `timeout`. `None` means it has not arrived, not that
    /// it failed -- the request is still in flight and the answer is lost.
    pub fn wait_timeout(self, timeout: Duration) -> Option<Result<T>> {
        self.rx.recv_timeout(timeout).ok()
    }

    /// The answer if it is already here, without blocking.
    pub fn try_take(&self) -> Option<Result<T>> {
        self.rx.try_recv().ok()
    }
}

/// A running instance of an image, addressed through its driver.
///
/// Cheap to clone: a clone is another handle to the same sandbox, which is what
/// lets several threads hold it.
#[derive(Clone)]
pub struct Sandbox {
    core: Arc<Core>,
    driver: Arc<dyn Driver>,
}

impl Sandbox {
    /// From a compiled `.wasm` artifact, run inline.
    pub fn from_wasm(wasm: &[u8]) -> Result<Sandbox> {
        Sandbox::from_wasm_with(wasm, Arc::new(Inline))
    }

    pub fn from_wasm_with(wasm: &[u8], driver: Arc<dyn Driver>) -> Result<Sandbox> {
        let bytecode = flint_rt::native::bytecode_of(wasm).map_err(Error::Load)?;
        Sandbox::from_bytecode_with(&bytecode, driver)
    }

    /// From flint's own bytecode. An implementation detail, exposed because the
    /// CLI has one to hand and going back through wasm would be silly.
    pub fn from_bytecode(bytecode: &[u8]) -> Result<Sandbox> {
        Sandbox::from_bytecode_with(bytecode, Arc::new(Inline))
    }

    pub fn from_bytecode_with(bytecode: &[u8], driver: Arc<dyn Driver>) -> Result<Sandbox> {
        let program = Program::load(bytecode, 2_000_000_000).map_err(Error::Load)?;
        Ok(Sandbox {
            core: Arc::new(Core {
                program: Mutex::new(program),
                inbox: Mutex::new(VecDeque::new()),
                scheduled: AtomicBool::new(false),
                dispatches: std::sync::atomic::AtomicU64::new(0),
                served: std::sync::atomic::AtomicU64::new(0),
            }),
            driver,
        })
    }

    /// How many threads may be inside this sandbox at once.
    ///
    /// Read it rather than assume it: a pool of 4 on a target that cannot
    /// honour it answers 1.
    pub fn parallelism(&self) -> usize {
        self.driver.parallelism()
    }

    /// Call a function by name with positional arguments.
    ///
    /// **This does not run the program.** It queues a request and wakes the
    /// driver; the answer arrives on the `Pending`. It has to be this way for a
    /// sandbox that may not run on this thread at all -- and it is asynchronous
    /// even under the inline driver, because a synchronous API cannot be made
    /// asynchronous later without breaking every caller.
    ///
    /// What an argument MEANS is the caller's business: there is no entry map
    /// here and no capability argument. Those are the CLI's convention
    /// (`doc/decisions/0025`).
    pub fn call(&self, name: &str, args: &[Value]) -> Pending<Value> {
        let (tx, rx) = mpsc::channel();
        {
            let mut inbox = self.core.inbox.lock().unwrap();
            inbox.push_back(Request {
                name: name.to_string(),
                args: args.to_vec(),
                reply: tx,
            });
        }
        // Queued BEFORE the wake, always. A wake that arrives before the work
        // it announces can be answered by a dispatch that finds an empty inbox
        // and goes back to sleep.
        self.driver.wake(Arc::clone(&self.core));
        Pending { rx }
    }

    /// `call`, waited on. Convenience for a host thread with nothing else to
    /// do -- never reachable from inside a sandbox, where a call is a send and
    /// a park.
    pub fn call_blocking(&self, name: &str, args: &[Value]) -> Result<Value> {
        self.call(name, args).wait()
    }

    /// How many times a dispatch has crossed into this sandbox, against how
    /// many requests were served: `(dispatches, requests)`.
    ///
    /// The second divided by the first is what the debouncing bought. A host
    /// that sees them equal under load is getting no coalescing at all, which
    /// is worth being able to find out rather than guess.
    pub fn dispatch_counts(&self) -> (u64, u64) {
        (
            self.core.dispatches.load(Ordering::Relaxed),
            self.core.served.load(Ordering::Relaxed),
        )
    }

    /// Lend a capability by name. Authority is never a type test
    /// (`doc/decisions/0022`): a program holds one because the host gave it
    /// one, under an id only the host knows.
    pub fn grant(&self, name: &str) {
        self.core.program.lock().unwrap().grant(name);
    }

    /// A bound on WORK, in instructions. Deterministic
    /// (`doc/decisions/0009`), so the same program stops at the same
    /// instruction on every machine.
    pub fn set_step_limit(&self, n: u64) {
        self.core.program.lock().unwrap().set_step_limit(n);
    }

    /// Instructions executed so far -- **only while a step limit is set**.
    ///
    /// This reads 0 on a sandbox with no limit, and that is not a bug to route
    /// around. Counting every instruction would put an increment and a compare
    /// in the interpreter's inner loop for every program, including the ones
    /// that never ask; instead the unbudgeted loop has no counter at all and
    /// the optimiser deletes the check (`doc/decisions/0009`). So
    /// `set_step_limit` is what turns counting on.
    ///
    /// It is exact because the program lock makes it exact. When several
    /// threads really run one heap, gas becomes per-executor and summed -- a
    /// single shared counter would put an atomic on the hottest line in the
    /// interpreter -- and this will then be a snapshot true at a safepoint.
    pub fn gas(&self) -> u64 {
        self.core.program.lock().unwrap().steps()
    }
}
