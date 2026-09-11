//! Who advances a sandbox, and when (`DECISIONS.md#drivers`).
//!
//! A sandbox does not run because someone called into it. It runs because it
//! has work and a driver decided to give it a thread. That indirection is the
//! whole point: a host that calls straight into a sandbox has decided, forever,
//! that the sandbox runs on the caller's thread -- which forecloses a pool.
//!
//! ## The two rules
//!
//! **A driver may give a sandbox K threads, and K may be more than one.** The
//! destination is several threads inside ONE sandbox, not one thread per
//! sandbox. Today the sandbox serialises them, so K > 1 is CORRECT but not yet
//! FASTER; what is missing is a parallel collector, not an interface. That is
//! deliberate -- an interface never called with K > 1 is one that will turn out
//! to be wrong, so it is called with K > 1 from the first version.
//!
//! **Waking is not running.** `wake` may be called from any thread, including
//! a worker already inside another sandbox. It records that there is work and
//! returns. It never executes guest code on the caller's thread unless the
//! driver is the inline one and the caller is the host, which is what inline
//! means.

use crate::sandbox::Core;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::time::Duration;

/// What advances a sandbox.
pub trait Driver: Send + Sync + 'static {
    /// How many threads may be inside one sandbox at once.
    ///
    /// **Read it back rather than assuming it.** Asking for threads is a
    /// preference, and a target that cannot honour it answers honestly: a pool
    /// of 4 on a single-threaded target reports 1. Not a refusal, because
    /// portable code could not then be written -- and not silence either,
    /// because a driver that quietly gives one thread when asked for four is a
    /// performance mystery with no evidence in it. Same rule as `:optimize`.
    fn parallelism(&self) -> usize;

    /// There is work for this sandbox.
    ///
    /// Callable from any thread and cheap to call redundantly: **N wakes
    /// between two runs must cost one dispatch, not N**. That coalescing is
    /// what keeps every port write from crossing the sandbox boundary, and it
    /// is safe only because the queue is the inbox rather than anything inside
    /// the sandbox -- a wake that is folded into another loses no message,
    /// because the messages were never in the wake.
    fn wake(&self, core: Arc<Core>);

    /// Stop accepting work and let the threads finish. Idempotent.
    fn shutdown(&self) {}
}

// --- inline ----------------------------------------------------------------

/// Runs the sandbox on whichever thread woke it. The default, and the one to
/// use when there is nothing to gain from another thread.
///
/// `parallelism` is 1 and always will be -- not because inline is limited, but
/// because it IS one thread by definition.
pub struct Inline;

thread_local! {
    /// Whether this thread is already inside a sandbox.
    ///
    /// Without this, a sandbox that wakes itself -- or wakes another sandbox
    /// that wakes it back -- recurses through the interpreter until the stack
    /// ends. Leaving the work queued instead is not a compromise: the outer
    /// dispatch has not finished draining yet, so it will see it.
    static ADVANCING: std::cell::Cell<bool> = const { std::cell::Cell::new(false) };
}

impl Driver for Inline {
    fn parallelism(&self) -> usize {
        1
    }

    fn wake(&self, core: Arc<Core>) {
        if !core.schedule() {
            // Already queued or running; the dispatch that owns it will drain
            // whatever was just added.
            return;
        }
        if ADVANCING.with(std::cell::Cell::get) {
            // Re-entrant. Leave it scheduled: whoever is draining will take it.
            return;
        }
        ADVANCING.with(|f| f.set(true));
        core.advance();
        ADVANCING.with(|f| f.set(false));
    }
}

// --- a pool ----------------------------------------------------------------

/// K threads, which may be inside the SAME sandbox at once.
///
/// The pool is over a sandbox, not over sandboxes: several workers can hold one
/// sandbox and take work from its inbox. Today the sandbox serialises them, so
/// the parallelism is in the dispatch rather than in the interpreter, and the
/// remaining work is a parallel collector rather than a different shape here.
pub struct ThreadPool {
    inner: Arc<PoolInner>,
    threads: Mutex<Vec<std::thread::JoinHandle<()>>>,
}

struct PoolInner {
    queue: Mutex<Queue>,
    ready: Condvar,
    running: AtomicBool,
    /// How long to hold a woken sandbox before dispatching it, to let more
    /// arrivals land in the same batch. Zero dispatches immediately.
    ///
    /// Bounded, always. Coalescing without a bound is a deadlock with a
    /// plausible explanation: a sandbox that is runnable has to eventually run.
    debounce: Duration,
}

#[derive(Default)]
struct Queue {
    ready: std::collections::VecDeque<Arc<Core>>,
}

impl ThreadPool {
    /// A pool of `threads`, dispatching as soon as a sandbox is woken.
    pub fn new(threads: usize) -> ThreadPool {
        ThreadPool::with_debounce(threads, Duration::ZERO)
    }

    /// A pool that holds a woken sandbox for `debounce` before dispatching it,
    /// so that a burst of arrivals becomes one dispatch.
    ///
    /// This trades latency for throughput and nothing else: a delayed dispatch
    /// cannot lose a message, because the messages are in the sandbox's inbox
    /// and the dispatch drains all of them. Keep it small -- it is added to the
    /// latency of every request that arrives into an idle sandbox.
    pub fn with_debounce(threads: usize, debounce: Duration) -> ThreadPool {
        let threads = threads.max(1);
        let inner = Arc::new(PoolInner {
            queue: Mutex::new(Queue::default()),
            ready: Condvar::new(),
            running: AtomicBool::new(true),
            debounce,
        });
        let mut handles = Vec::with_capacity(threads);
        for i in 0..threads {
            let inner = Arc::clone(&inner);
            handles.push(
                std::thread::Builder::new()
                    .name(format!("flint-driver-{i}"))
                    .spawn(move || worker(inner))
                    .expect("a driver thread"),
            );
        }
        ThreadPool { inner, threads: Mutex::new(handles) }
    }
}

fn worker(inner: Arc<PoolInner>) {
    loop {
        let core = {
            let mut q = inner.queue.lock().unwrap();
            loop {
                if !inner.running.load(Ordering::Acquire) {
                    return;
                }
                if let Some(c) = q.ready.pop_front() {
                    break c;
                }
                // A timeout rather than a plain wait, so shutdown is noticed by
                // a worker that happens to be asleep with an empty queue.
                let (guard, _) = inner.ready.wait_timeout(q, Duration::from_millis(50)).unwrap();
                q = guard;
            }
        };
        if !inner.debounce.is_zero() {
            std::thread::sleep(inner.debounce);
        }
        ADVANCING.with(|f| f.set(true));
        core.advance();
        ADVANCING.with(|f| f.set(false));
    }
}

impl Driver for ThreadPool {
    fn parallelism(&self) -> usize {
        self.threads.lock().unwrap().len()
    }

    fn wake(&self, core: Arc<Core>) {
        // The coalesce. `schedule` answers true exactly once between dispatches,
        // so a burst of wakes queues the sandbox once and the dispatch that
        // takes it drains everything that arrived.
        if !core.schedule() {
            return;
        }
        let mut q = self.inner.queue.lock().unwrap();
        q.ready.push_back(core);
        self.inner.ready.notify_one();
    }

    fn shutdown(&self) {
        self.inner.running.store(false, Ordering::Release);
        self.inner.ready.notify_all();
        for h in self.threads.lock().unwrap().drain(..) {
            let _ = h.join();
        }
    }
}

impl Drop for ThreadPool {
    fn drop(&mut self) {
        self.shutdown();
    }
}
