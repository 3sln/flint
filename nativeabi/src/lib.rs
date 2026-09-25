//! The runtime half of `flint compile :to :llvm` (`DECISIONS.md#llvm-ir-target`).
//!
//! `:to :llvm` emits ONE `.ll` and no linker runs. That module carries the
//! program image, its compiled arities as ordinary functions, a table of
//! pointers to them, and a `main` that calls the two symbols below. Everything
//! else a flint program needs -- the collector, the interpreter, the builtins --
//! is this archive, and the user's own `clang` puts the two together:
//!
//! ```sh
//! cargo build --release -p flint-native-abi
//! clang prog.ll target/release/libflintnative.a -o prog
//! ```
//!
//! ## Why this is an archive and not part of the CLI
//!
//! The refusal this replaces said `:to :llvm` "needs a linker, and this binary
//! carries none". That was true of `:to :native` and never of `:to :llvm`: IR
//! is text, and emitting it needs nothing. What needs a linker is turning the
//! IR into an executable, and that is the user's step, with the user's linker.
//!
//! ## What a program from here can and cannot do
//!
//! It runs, it computes, and its output comes back. It has no HOST: `flint run`
//! serves `fs`, `env`, `slurp` and pods from the CLI, and none of that is here,
//! so a program that opens a port parks for ever rather than being answered.
//! That is a missing feature, not a design: the host interface is
//! `Program::drain_events` and belongs in whatever embeds this.

use flint_rt::native::Program;
use std::ffi::{c_char, c_int, CStr};
use std::io::Write;

/// Re-exported so the linker keeps it: it is `#[no_mangle]` in `flint-rt`, and
/// an archive drops an object nobody references. The emitted module's `main`
/// calls it before `flint_native_main`, handing over the table of compiled
/// arities that the image's slots index.
pub use flint_rt::aot::flint_aot_register;

/// How much heap a program from `:to :llvm` gets, in bytes.
///
/// The same 2 GB `flint run` gives, and for the same reason: this is a
/// developer machine running one program, not a sandbox holding somebody
/// else's. A caller that wants a cap should embed `flint_rt::native` directly
/// rather than have this grow a knob nothing in the emitted module could set.
const HEAP: u32 = 2_000_000_000;

/// Run an image. Returns the program's exit code.
///
/// `argv[0]` is skipped, exactly as `flint run prog a b` passes `a b`: the
/// emitted `main` hands over C's argv untouched, so the convention lives in one
/// place rather than in generated code.
///
/// # Safety
/// `image` must point at `len` readable bytes, and `argv` at `argc` NUL
/// terminated strings.
#[no_mangle]
pub unsafe extern "C" fn flint_native_main(
    image: *const u8,
    len: usize,
    argc: c_int,
    argv: *const *const c_char,
) -> c_int {
    let bytes = unsafe { std::slice::from_raw_parts(image, len) };
    let args: Vec<String> = if argv.is_null() || argc <= 1 {
        Vec::new()
    } else {
        (1..argc as usize)
            .filter_map(|i| {
                let p = unsafe { *argv.add(i) };
                if p.is_null() {
                    None
                } else {
                    unsafe { CStr::from_ptr(p) }.to_str().ok().map(String::from)
                }
            })
            .collect()
    };
    let refs: Vec<&str> = args.iter().map(|s| s.as_str()).collect();
    let mut p = match Program::load_with(bytes, HEAP, flint_conc::HOST_CATALOGUE) {
        Ok(p) => p,
        Err(e) => {
            eprintln!("flint: {e}");
            return 1;
        }
    };
    // The gas count, on request. `test/aot.clj` asserts the interpreter and
    // compiled code charge IDENTICALLY, and the LLVM emitter is a second
    // producer of the same charges -- so it needs the same check available, and
    // stderr behind an environment read is the cheapest way an artifact with no
    // host can answer it.
    //
    // THE LIMIT IS WHAT TURNS COUNTING ON, and asking for the count without it
    // is how this was wrong first: with no limit the interpreter runs
    // `NoBudget`, whose `tick` is a constant the optimiser deletes along with
    // the counter (`DECISIONS.md#resource-limits`), while compiled code charges
    // through `aot_*` regardless. So the interpreted arm reported 104 steps
    // against the compiled arm's 104 652 -- which reads exactly like an emitter
    // that charges wildly too much, and was the two arms not being comparable.
    let want_steps = std::env::var_os("FLINT_STEPS").is_some();
    if want_steps {
        // `u64::MAX` is the "no checkpoint" sentinel, and `set_step_limit`
        // already maps it to one below -- counting on, nothing reachable
        // tripping it.
        p.set_step_limit(u64::MAX);
    }
    let out = p.run(&refs);
    print!("{}", out.out);
    let _ = std::io::stdout().flush();
    if want_steps {
        eprintln!("steps {}", p.steps());
    }
    out.code
}

// --- THE THREE OPERATIONS (`DECISIONS.md#four-operations`) ------------------
//
// `boot`, `loop`, `link`, the same three every other target exposes, and the
// fourth manifestation getting them is what makes the surface uniform: a host
// writes the same three calls whether it holds a wasm module, a JVM class, a CLR
// assembly or this archive. `:to :llvm` used to emit a module whose only entry
// was `main`, so its artifact ran to completion and a host could not drive it at
// all.
//
// `flint_native_main` STAYS. It is not one of the three and does not compete with
// them: it is the convenience entry for `clang prog.ll libflintnative.a -o prog`,
// and a program that wants to be run rather than driven is the common case.
//
// THE NAMES ARE `flint_native_*` AND THE ARTIFACT'S ARE `flint_*`. The emitted
// module defines the short names as three-line wrappers that pass `@flint_image`,
// exactly as its `main` already wraps `flint_native_main` -- so the image stays a
// detail of the artifact and this archive never has to know where it lives.

use flint_rt::vm::NativeFn;
use std::collections::HashMap;
use std::sync::Mutex;

/// Hooks recorded by `link` and consumed by the next `boot` of the SAME BRIDGE.
///
/// KEYED BY BRIDGE NAME, which is what the contract asks for and what a
/// process-global registry could not do: two sandboxes can override the same
/// native differently. The bridge is the sandbox's identity before the sandbox
/// exists, and here the bridge IS its name -- there is no object to hold in a C
/// ABI, and `boot` takes that name for the same reason.
static PENDING: Mutex<Option<HashMap<String, Vec<(String, NativeFn)>>>> = Mutex::new(None);

/// Bridges that have already booted, so `link` can refuse rather than record
/// something nothing will ever read. Natives resolve EXACTLY ONCE, inside
/// `load_with`, so a hook arriving afterwards is never reached and answering 0
/// would claim an override happened.
static BOOTED: Mutex<Option<Vec<String>>> = Mutex::new(None);

unsafe fn cstr(p: *const c_char) -> Option<String> {
    if p.is_null() {
        return None;
    }
    unsafe { CStr::from_ptr(p) }.to_str().ok().map(String::from)
}

/// `link` -- override the native called `name` for the bridge called `bridge`,
/// with the function at `f`. MUST PRECEDE `boot`.
///
/// 0 recorded, 1 on an argument this cannot read, 2 when that bridge has already
/// booted.
///
/// A REAL FUNCTION POINTER, which is the one place this target can do what wasm
/// cannot: a wasm module's imports are fixed at instantiation, so its `link` can
/// only rebind a slot the module already carries and answers 1, "this artifact
/// cannot". Here `NativeFn` is already `extern "C" fn(*mut Rt, u32, u32) -> u64`
/// -- flat and C-callable by construction, because the wasm table entry needed it
/// to be -- so a caller in any language can pass one.
///
/// # Safety
/// `bridge` and `name` must be NUL-terminated, and `f` must have `NativeFn`'s
/// signature and outlive every sandbox booted on that bridge.
#[no_mangle]
pub unsafe extern "C" fn flint_native_link(
    bridge: *const c_char,
    name: *const c_char,
    f: Option<NativeFn>,
) -> i32 {
    // THE BRIDGE FIRST, THEN "HAS IT BOOTED", THEN THE REST. Validating every
    // argument up front reads better and gets the precedence wrong: a `link` after
    // `boot` with any other argument also bad answered 1, "cannot read that",
    // where the contract's answer is 2, "too late". The wasm face refuses after
    // boot before looking at anything else, and this now matches it.
    let Some(b) = (unsafe { cstr(bridge) }) else { return 1 };
    if BOOTED.lock().map(|g| g.as_ref().is_some_and(|v| v.contains(&b))).unwrap_or(true) {
        return 2;
    }
    let (Some(n), Some(f)) = (unsafe { cstr(name) }, f) else {
        return 1;
    };
    let Ok(mut g) = PENDING.lock() else { return 1 };
    g.get_or_insert_with(HashMap::new).entry(b).or_default().push((n, f));
    0
}

/// `boot` -- load `image`, install `bridge` as the SYSTEM PORT, and answer a
/// sandbox handle. 0 if it could not.
///
/// A REAL HANDLE, not ceremony. On wasm `Rt` is one `static mut` per instance so
/// the handle can only ever be 1; here a host can hold as many sandboxes as it
/// likes in one process, which is what `one-image-per-sandbox` asks for -- many
/// programs means many sandboxes, not one sandbox reloaded.
///
/// The handle is a leaked `Box<Program>`. It is never freed, and that is the same
/// answer `flint_native_main` gives by exiting: a sandbox is one program for its
/// whole life, and a `flint_native_free` would be the first thing able to
/// invalidate a handle another thread is inside.
///
/// # Safety
/// `image` must point at `len` readable bytes and `bridge` must be
/// NUL-terminated.
#[no_mangle]
pub unsafe extern "C" fn flint_native_boot(
    image: *const u8,
    len: usize,
    bridge: *const c_char,
) -> u64 {
    let Some(name) = (unsafe { cstr(bridge) }) else { return 0 };
    let bytes = unsafe { std::slice::from_raw_parts(image, len) };
    // The hooks for THIS bridge, merged over the catalogue by name: a hook
    // replaces the entry it names and a new name goes on the end. Relying on
    // `load_with` to prefer a later duplicate would be relying on something it
    // does not promise.
    let hooks: Vec<(String, NativeFn)> = PENDING
        .lock()
        .ok()
        .and_then(|mut g| g.as_mut().and_then(|m| m.remove(&name)))
        .unwrap_or_default();
    let mut table: Vec<(&str, NativeFn)> = flint_conc::HOST_CATALOGUE.to_vec();
    for (n, f) in &hooks {
        match table.iter_mut().find(|(k, _)| *k == n.as_str()) {
            Some(slot) => slot.1 = *f,
            None => table.push((n.as_str(), *f)),
        }
    }
    let mut p = match Program::load_with(bytes, HEAP, &table) {
        Ok(p) => p,
        Err(e) => {
            eprintln!("flint: {e}");
            return 0;
        }
    };
    // `true` for the system flag: this IS the system port, which is what makes it
    // the sandbox's only door (`DECISIONS.md#bridges-are-the-only-door`).
    if !p.install_port(0, &name, true) {
        return 0;
    }
    if let Ok(mut g) = BOOTED.lock() {
        g.get_or_insert_with(Vec::new).push(name);
    }
    Box::into_raw(Box::new(p)) as u64
}

/// The three statuses, DECLARED rather than only documented, because a comment
/// saying "0, 1, 2" beside a function is the prose this project has repeatedly
/// watched fail to bind the code next to it. The NAMES differ per language and
/// are allowed to; the NUMBERS are the ABI.
pub const FLINT_DONE: i32 = 0;
pub const FLINT_THREW: i32 = 1;
pub const FLINT_NEEDS_HOST: i32 = 2;

/// `loop` -- pump, and answer 0 Done, 1 Threw, 2 NeedsHost.
///
/// `NeedsHost` is the RESTING state and not an error: the control plane is a green
/// thread parked on the system port, so a healthy idle sandbox reports it.
///
/// -1 for a handle this cannot serve, which is deliberately outside the status
/// range rather than folded into `Threw`.
///
/// THE OUTPUT IS PRINTED, as `flint_native_main` prints it. A sandbox driven
/// through `loop` has no other way to say anything until its host reads events,
/// and a `loop` that silently dropped what a program wrote would differ from
/// `main` on the one thing both are for.
///
/// # Safety
/// `h` must be a handle `flint_native_boot` answered and not yet used from
/// another thread concurrently -- one `Program` is not `Sync`.
#[no_mangle]
pub unsafe extern "C" fn flint_native_loop(h: u64) -> i32 {
    if h == 0 {
        return -1;
    }
    let p = unsafe { &mut *(h as *mut Program) };
    let out = p.resume();
    if !out.out.is_empty() {
        print!("{}", out.out);
        let _ = std::io::stdout().flush();
    }
    out.code
}
