//! Running a flint program natively (`doc/decisions/0010`).
//!
//! The runtime is Rust, so it already compiles to native code through LLVM --
//! the collector, the value representation, the interpreter and every builtin
//! are the same code the wasm module is built from. What was missing was the
//! way IN: on wasm a host calls `arg_push` and `main` across the module
//! boundary, and natively there was no equivalent, so the only way to run a
//! flint program on a host was to embed a wasm engine.
//!
//! This is that entry point. It mirrors `abi.rs` exactly -- the same argument
//! marshalling, the same `run_program`, the same rendering of an error -- so a
//! program cannot answer differently depending on which one it came through.
//!
//! ## Natives are resolved BY NAME
//!
//! An image's native slots belong to whichever module compiled it: on wasm
//! they are indices into `__indirect_function_table`. Natively there is no
//! table, so a slot means nothing. `resolve_natives` re-points them by name
//! against a registry, which is exactly what a loader module does
//! (`doc/decisions/0023`) -- the registry is just built from Rust here rather
//! than spliced into a data segment.

use crate::rt::Rt;
use crate::value::Value;
use alloc::string::String;
use alloc::vec::Vec;

/// A loaded program, ready to run.
pub struct Program {
    rt: Rt,
}

/// What a run produced: `code` is 0 for success, and `out` is the answer or the
/// error, exactly as the wasm ABI renders them.
pub struct Outcome {
    pub code: i32,
    pub out: String,
}

/// The builtin registry, in the same `(slot, name-len, name)` shape a loader
/// module carries. Built from the host registry, so an image compiled anywhere
/// can be re-pointed at these functions.
fn host_registry_blob() -> Vec<u8> {
    let mut out = Vec::new();
    for (i, (name, _)) in crate::builtins::host_registry().iter().enumerate() {
        out.extend_from_slice(&(i as u32).to_le_bytes());
        out.extend_from_slice(&(name.len() as u32).to_le_bytes());
        out.extend_from_slice(name.as_bytes());
    }
    out
}

impl Program {
    /// Load a bytecode image. `heap` is the cap in bytes.
    pub fn load(image: &[u8], heap: u32) -> Result<Program, String> {
        let mut rt = Rt::with_heap(2 * 1024 * 1024, heap);
        rt.install_host_natives();
        if !rt.load_image(image) {
            return Err(String::from(
                "this is not a flint image, or it was built for a different runtime",
            ));
        }
        let reg = host_registry_blob();
        rt.resolve_natives(&reg).map_err(|missing| {
            alloc::format!(
                "this runtime does not carry the builtin `{missing}`, which the image needs"
            )
        })?;
        Ok(Program { rt })
    }

    /// Run `main` with string arguments, as the wasm entry does.
    pub fn run(&mut self, args: &[&str]) -> Outcome {
        let rt = &mut self.rt;
        let base = rt.mark();
        for a in args {
            let v = rt.string(a);
            rt.push(v);
        }
        let argv = rt.vec_from_roots(base, args.len());
        rt.pop_to(base);
        let result = rt.run_program(argv);
        // Order matters: `rendered` CLEARS the error, so the code has to be
        // taken first. Reversing these reported success for every failure.
        let code = status_of(rt, result);
        let out = rendered(rt, result);
        Outcome { code, out }
    }

    /// The instruction count, which is deterministic (`doc/decisions/0009`) and
    /// therefore the same here as under any wasm engine.
    pub fn steps(&self) -> u64 {
        self.rt.steps
    }

    /// The gas limit, in instructions. Zero disables it.
    ///
    /// Same sentinel handling as the wasm ABI: `u64::MAX` means "no
    /// checkpoint", so asking for the largest possible limit would switch
    /// counting off rather than set it very high.
    pub fn set_step_limit(&mut self, n: u64) {
        self.rt.set_gas_limit(if n == u64::MAX { u64::MAX - 1 } else { n });
        self.rt.steps = 0;
        self.rt.refresh_checkpoint();
    }
}

/// The status code, on the same rules as `abi::finish_run`.
fn status_of(rt: &mut Rt, result: Value) -> i32 {
    if rt.status != 0 {
        return rt.status;
    }
    if rt.failed() {
        return 1;
    }
    let mut b = crate::rt::sbuf();
    if rt.as_str(result, &mut b).is_none() {
        return 1;
    }
    0
}

/// The answer, or the error rendered as `Kind: message`.
fn rendered(rt: &mut Rt, result: Value) -> String {
    if rt.status != 0 {
        return String::new();
    }
    if rt.failed() {
        let e = rt.clear_error();
        let mut b = crate::rt::sbuf();
        let kind: String = {
            let k = rt.ex_kind(e);
            rt.as_str(k, &mut b).unwrap_or("Error").into()
        };
        let mut b2 = crate::rt::sbuf();
        let msg: String = {
            let m = rt.ex_message(e);
            rt.as_str(m, &mut b2).unwrap_or("").into()
        };
        return alloc::format!("{kind}: {msg}");
    }
    let mut b = crate::rt::sbuf();
    match rt.as_str(result, &mut b) {
        Some(s) => s.into(),
        // REFUSED, not printed, because that is what the wasm ABI does. A
        // program that answers differently depending on which entry point it
        // came through is worse than one that refuses on both.
        None => String::from(
            "flint: the entry function did not return a string (no render shim?)",
        ),
    }
}
