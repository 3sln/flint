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

/// flint's bytecode, extracted from a compiled `.wasm` artifact.
///
/// The module carries its program as a data segment, and the descriptor
/// `FLINT_IMAGE_DESC` says where (`doc/decisions/0023`). Reading it back lets a
/// native host run a wasm artifact without a wasm engine -- the artifact is for
/// whoever has one, and this is the same program by another road.
///
/// This ASKS the descriptor rather than guessing. It guessed once -- the image
/// is the largest data segment, since the others are a descriptor and a
/// registry -- and the guess is wrong: the runtime's own read-only data is one
/// segment and is larger than a small program's image, so every artifact
/// compiled here loaded the wrong bytes and failed as "not a flint image".
/// The descriptor exists to answer this exact question.
pub fn bytecode_of(wasm: &[u8]) -> Result<Vec<u8>, String> {
    let m = Sections::parse(wasm)?;
    let desc = m.global_addr("FLINT_IMAGE_DESC")
        .ok_or("this module exports no FLINT_IMAGE_DESC, so it is not a flint artifact")?;
    let head = m.read(desc, 8).ok_or("the image descriptor is not in any data segment")?;
    let at = u32::from_le_bytes([head[0], head[1], head[2], head[3]]);
    let len = u32::from_le_bytes([head[4], head[5], head[6], head[7]]);
    if len == 0 {
        return Err(String::from("this module carries no flint program: its image is empty"));
    }
    m.read(at, len as usize)
        .map(<[u8]>::to_vec)
        .ok_or_else(|| String::from("the image descriptor points outside every data segment"))
}

/// Just enough of a module to answer "what is at this address".
///
/// Not a wasm parser: the globals whose initialiser is a constant, and the
/// data segments. Everything else in the module is code for an engine this
/// host does not have.
struct Sections<'a> {
    /// Exported name to index in the GLOBAL index space.
    exports: Vec<(&'a str, u32)>,
    /// Global index to its constant initialiser. Imported globals occupy the
    /// low indices and have no initialiser here, so this records the index
    /// with the value rather than relying on position.
    globals: Vec<(u32, u32)>,
    /// `(address, bytes)`, in the order the module lists them.
    data: Vec<(u32, &'a [u8])>,
}

impl<'a> Sections<'a> {
    fn parse(wasm: &'a [u8]) -> Result<Sections<'a>, String> {
        if wasm.len() < 8 || &wasm[0..4] != b"\0asm" {
            return Err(String::from("this is not a wasm module"));
        }
        let mut out = Sections { exports: Vec::new(), globals: Vec::new(), data: Vec::new() };
        // Imported globals come first in the index space, so a module's own
        // globals are numbered from however many were imported.
        let mut imported_globals = 0u32;
        let mut i = 8usize;
        while i < wasm.len() {
            let id = wasm[i];
            i += 1;
            let (size, next) = uleb(wasm, i)?;
            i = next;
            let body = wasm.get(i..i + size as usize).ok_or("the module ends mid-section")?;
            match id {
                2 => imported_globals = count_imported_globals(body)?,
                6 => out.globals = read_globals(body)?,
                7 => out.exports = read_exports(body)?,
                11 => out.data = read_data(body)?,
                _ => {}
            }
            i += size as usize;
        }
        // Applied after the whole module is read, because the import section
        // may appear before or after the ones that need its count.
        for g in &mut out.globals {
            g.0 += imported_globals;
        }
        Ok(out)
    }

    /// The constant a named exported global holds. `__heap_base` and
    /// `FLINT_IMAGE_DESC` are both this shape: an address, as an `i32.const`.
    fn global_addr(&self, name: &str) -> Option<u32> {
        let idx = self.exports.iter().find(|(n, _)| *n == name).map(|(_, i)| *i)?;
        self.globals.iter().find(|(i, _)| *i == idx).map(|(_, v)| *v)
    }

    /// `len` bytes at a linear-memory address, from whichever segment covers
    /// it. A LATER segment wins, because that is how the splice overwrites a
    /// descriptor in place rather than editing the linker's own data
    /// (`flint.bundle`).
    fn read(&self, addr: u32, len: usize) -> Option<&'a [u8]> {
        let mut found = None;
        for (at, bytes) in &self.data {
            if addr >= *at {
                let off = (addr - *at) as usize;
                if off + len <= bytes.len() {
                    found = Some(&bytes[off..off + len]);
                }
            }
        }
        found
    }
}

fn count_imported_globals(body: &[u8]) -> Result<u32, String> {
    let (n, mut j) = uleb(body, 0)?;
    let mut globals = 0u32;
    for _ in 0..n {
        for _ in 0..2 {
            // The module and field names.
            let (len, lj) = uleb(body, j)?;
            j = lj + len as usize;
        }
        let kind = *body.get(j).ok_or("an import ends mid-descriptor")?;
        j += 1;
        match kind {
            0x00 => { let (_, k) = uleb(body, j)?; j = k }          // a function
            0x01 => { j += 1; j = skip_limits(body, j)? }           // a table
            0x02 => j = skip_limits(body, j)?,                      // a memory
            0x03 => { globals += 1; j += 2 }                        // a global
            _ => return Err(String::from("an import has an unknown kind")),
        }
    }
    Ok(globals)
}

fn skip_limits(body: &[u8], j: usize) -> Result<usize, String> {
    let flags = *body.get(j).ok_or("a limit ends mid-value")?;
    let (_, mut k) = uleb(body, j + 1)?;
    if flags & 1 != 0 {
        let (_, k2) = uleb(body, k)?;
        k = k2;
    }
    Ok(k)
}

/// Only the globals whose initialiser is `i32.const N`. A global initialised
/// any other way is not an address, and nothing here wants one.
fn read_globals(body: &[u8]) -> Result<Vec<(u32, u32)>, String> {
    let (n, mut j) = uleb(body, 0)?;
    let mut out = Vec::new();
    for idx in 0..n {
        j += 2; // the value type and the mutability flag
        if body.get(j) == Some(&0x41) {
            let (v, k) = sleb(body, j + 1)?;
            out.push((idx, v as u32));
            j = k;
        }
        // Past the `end` of the initialiser expression, whatever it held.
        while body.get(j).is_some_and(|b| *b != 0x0b) {
            j += 1;
        }
        j += 1;
    }
    Ok(out)
}

/// Only the GLOBAL exports; a function and a global with the same name would
/// otherwise be indistinguishable by name alone.
fn read_exports(body: &[u8]) -> Result<Vec<(&str, u32)>, String> {
    let (n, mut j) = uleb(body, 0)?;
    let mut out = Vec::new();
    for _ in 0..n {
        let (len, lj) = uleb(body, j)?;
        let name = body.get(lj..lj + len as usize).ok_or("an export ends mid-name")?;
        j = lj + len as usize;
        let kind = *body.get(j).ok_or("an export ends mid-descriptor")?;
        let (idx, k) = uleb(body, j + 1)?;
        j = k;
        if kind == 0x03 {
            if let Ok(name) = core::str::from_utf8(name) {
                out.push((name, idx));
            }
        }
    }
    Ok(out)
}

fn read_data(body: &[u8]) -> Result<Vec<(u32, &[u8])>, String> {
    let (n, mut j) = uleb(body, 0)?;
    let mut out = Vec::new();
    for _ in 0..n {
        let (flags, fj) = uleb(body, j)?;
        j = fj;
        if flags != 0 {
            // A passive segment has no address, so nothing here can place it.
            break;
        }
        if body.get(j) != Some(&0x41) {
            break;
        }
        let (addr, aj) = sleb(body, j + 1)?;
        j = aj + 1; // past the 0x0b that ends the offset expression
        let (len, lj) = uleb(body, j)?;
        j = lj;
        let seg = body.get(j..j + len as usize).ok_or("a data segment is truncated")?;
        j += len as usize;
        out.push((addr as u32, seg));
    }
    Ok(out)
}

/// A signed LEB128, which is what an `i32.const` carries. Reading one as
/// UNSIGNED is right for every address the linker emits below 2 GB and wrong
/// for exactly the ones above it, which is the kind of nearly-correct that
/// waits.
fn sleb(b: &[u8], mut i: usize) -> Result<(i64, usize), String> {
    let (mut acc, mut shift) = (0i64, 0u32);
    loop {
        let x = *b.get(i).ok_or("the module ends mid-number")?;
        i += 1;
        acc |= ((x & 0x7f) as i64) << shift;
        shift += 7;
        if x & 0x80 == 0 {
            if shift < 64 && x & 0x40 != 0 {
                acc |= -1i64 << shift;
            }
            return Ok((acc, i));
        }
        if shift >= 64 {
            return Err(String::from("a number in the module is too long"));
        }
    }
}

fn uleb(b: &[u8], mut i: usize) -> Result<(u32, usize), String> {
    let (mut acc, mut shift) = (0u32, 0u32);
    loop {
        let x = *b.get(i).ok_or("the module ends mid-number")?;
        i += 1;
        acc |= ((x & 0x7f) as u32) << shift;
        if x & 0x80 == 0 {
            return Ok((acc, i));
        }
        shift += 7;
    }
}

/// A loaded program, ready to run.
pub struct Program {
    /// BOXED, and that is structural rather than stylistic.
    ///
    /// An executor registers the address of its root stack with the collector,
    /// and the collector reads that address while the executor is parked. So an
    /// `Rt` must not move once registered -- and a `Program` moves all the
    /// time: into a `Mutex`, into an `Arc`, out of a constructor. Boxing the
    /// `Rt` makes the address stable no matter what happens to the `Program`.
    ///
    /// Without this the primary executor registered a stack address, the
    /// `Program` was moved into its `Sandbox`, and the next collection walked
    /// freed memory -- which surfaced as a `stack_top` of
    /// 14 728 600 375 357 765 408 against a `stack.len()` of 0.
    rt: alloc::boxed::Box<Rt>,
    grants: u32,
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
        Ok(Program { rt: alloc::boxed::Box::new(rt), grants: 0 })
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

    /// Lend a capability by name.
    ///
    /// Authority is never a type test (`doc/decisions/0022`): a program holds
    /// a capability because the host gave it one, and the host recognises it
    /// by an id only the host knows. Granting nothing means the program can
    /// reach nothing, which is the default.
    pub fn grant(&mut self, name: &str) {
        let id = self.next_grant();
        self.rt.add_grant(String::from(name), id);
        self.grants += 1;
    }

    fn next_grant(&self) -> u64 {
        // Ids start at 1: zero is "nothing presented", and a capability whose
        // id was zero would be indistinguishable from an absent one.
        self.grants as u64 + 1
    }

    /// Call a named function with encoded arguments, and encode the answer
    /// (`doc/decisions/0025`). Same contract as the wasm `flint_call`: one
    /// encoded value in, one encoded value out, and a failure is data --
    /// `{:error kind :message text}` -- rather than a second channel.
    pub fn call(&mut self, encoded_call: &[u8]) -> Result<Vec<u8>, String> {
        call_on(&mut self.rt, encoded_call)
    }

    /// A second executor on this program's heap (`doc/decisions/0028`).
    ///
    /// It shares the heap, the image, the globals, the intern tables and the
    /// grants, and gets its own value stack, frames and gas. Run calls on it
    /// with `call_on`.
    ///
    /// # Safety
    /// The executor borrows this program's heap and must be dropped before the
    /// program is.
    #[cfg(feature = "parallel")]
    pub unsafe fn executor(&mut self) -> Option<alloc::boxed::Box<Rt>> {
        unsafe { self.rt.executor() }
    }

    /// What the image says about itself, if it says anything.
    pub fn var_exists(&mut self, name: &str) -> bool {
        self.rt.var_named(name).is_some()
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

/// Run an encoded call on a given executor.
///
/// Free rather than a method because a `Program` owns ONE executor and a
/// sandbox may have several (`doc/decisions/0028`). Every executor runs a call
/// the same way, and a second copy of this would be a second place for the
/// call protocol to drift.
pub fn call_on(rt: &mut Rt, encoded_call: &[u8]) -> Result<Vec<u8>, String> {
    // From here this executor runs guest code, so the collector waits for it
    // and it polls. Bracketing here rather than at each caller means both the
    // primary and every secondary get it, and neither can forget.
    #[cfg(feature = "parallel")]
    rt.enter_running();
    #[cfg(feature = "parallel")]
    let _guard = LeaveOnDrop(rt as *mut Rt);


                let call = rt.decode(encoded_call)?;
        if !rt.is_vector(call) || rt.vec_count(call) == 0 {
            return Err(String::from("a call is [name, args...]"));
        }
        let base = rt.mark();
        rt.push(call);
        let held = rt.r(base);
        let namev = rt.vec_nth(held, 0).unwrap_or(crate::value::NIL);
        let mut b = crate::rt::sbuf();
        let name: String = match rt.as_str(namev, &mut b) {
            Some(s) => s.into(),
            None => {
                rt.pop_to(base);
                return Err(String::from("the first element must be a function name"));
            }
        };
        let n = rt.vec_count(held) as usize;
        let mut args = Vec::with_capacity(n - 1);
        for i in 1..n {
            let held = rt.r(base);
            args.push(rt.vec_nth(held, i as u32).unwrap_or(crate::value::NIL));
        }
        let out = rt.call_named(&name, &args);
        rt.pop_to(base);
        let v = out?;
        if rt.failed() {
            let e = rt.clear_error();
            let mut b1 = crate::rt::sbuf();
            let kind: String = {
                let k = rt.ex_kind(e);
                rt.as_str(k, &mut b1).unwrap_or("Error").into()
            };
            let mut b2 = crate::rt::sbuf();
            let msg: String = {
                let m = rt.ex_message(e);
                rt.as_str(m, &mut b2).unwrap_or("").into()
            };
            return Err(alloc::format!("{kind}: {msg}"));
        }
        rt.encode(v)
    }

/// Leaves the running state however the call ends.
///
/// A `?` that returned early without this would leave the executor counted as
/// running forever, and the next collection would wait for a thread that is no
/// longer executing -- the deadlock this whole protocol exists to avoid, put
/// back by an early return.
#[cfg(feature = "parallel")]
struct LeaveOnDrop(*mut Rt);

#[cfg(feature = "parallel")]
impl Drop for LeaveOnDrop {
    fn drop(&mut self) {
        unsafe { (*self.0).leave_running() };
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
