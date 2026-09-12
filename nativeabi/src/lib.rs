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
