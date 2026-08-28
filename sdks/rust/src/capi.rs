//! flint for C, and through C for C++ and everything else with an FFI.
//!
//! The same shape as the JavaScript and Rust SDKs (`doc/decisions/0025`):
//! resolve namespaces, compile to an Image, instantiate a Sandbox, call
//! functions by name. What C adds is only the bookkeeping C always adds --
//! every pointer returned here is owned by the caller and has a matching
//! `_free`, and every fallible call takes a `char **err` that is set to an
//! owned message on failure and left alone on success.
//!
//! ## Values are opaque, and that is deliberate
//!
//! A flint value is NaN-boxed and its representation is a runtime detail
//! (`doc/decisions/0001`). Exposing it as a struct would freeze that detail
//! into an ABI. So C builds values through constructors, reads them through
//! accessors, and never sees a layout -- which is also what lets a value hold
//! a port or a sentinel that C has no way to fabricate.
//!
//! Build with `--features capi`; without it none of this is compiled.

use crate::{Compile, Compiler, Image, Optimize, Sandbox, Value};
use std::ffi::{c_char, c_int, c_void, CStr, CString};
use std::ptr;

// --- strings and errors ----------------------------------------------------

/// An owned C string from a Rust one. Interior NULs cannot occur in anything
/// flint produces, but a lossy fallback is cheaper than a panic across FFI.
fn owned(s: &str) -> *mut c_char {
    CString::new(s)
        .unwrap_or_else(|_| CString::new(s.replace('\0', "?")).unwrap())
        .into_raw()
}

unsafe fn borrowed<'a>(p: *const c_char) -> Option<&'a str> {
    if p.is_null() { return None }
    unsafe { CStr::from_ptr(p) }.to_str().ok()
}

fn fail<T>(err: *mut *mut c_char, message: &str) -> *mut T {
    if !err.is_null() {
        unsafe { *err = owned(message) };
    }
    ptr::null_mut()
}

/// Free any string this library returned, including an `err`.
///
/// # Safety
/// `s` must be null or a string this library returned and that has not already
/// been freed.
#[no_mangle]
pub unsafe extern "C" fn flint_string_free(s: *mut c_char) {
    if !s.is_null() {
        drop(unsafe { CString::from_raw(s) });
    }
}

// --- the compiler ----------------------------------------------------------

/// How a namespace becomes source.
///
/// There is no filesystem here (`doc/decisions/0025`): a resolver is asked for
/// a namespace and answers with source or with nothing, which is what lets a
/// caller compile out of a database, a zip, or memory. Return 0 and leave
/// `*out` alone for "no such namespace".
///
/// `*out` must stay valid until the resolver is called again or compilation
/// returns; flint copies it before asking for anything else.
pub type FlintResolver = Option<
    unsafe extern "C" fn(ctx: *mut c_void, ns: *const c_char, out: *mut *const c_char) -> c_int,
>;

/// The embedded compiler. One is enough for any number of compiles.
///
/// # Safety
/// The returned pointer must be released with `flint_compiler_free`.
#[no_mangle]
pub unsafe extern "C" fn flint_compiler_new(err: *mut *mut c_char) -> *mut Compiler {
    match Compiler::embedded() {
        Ok(c) => Box::into_raw(Box::new(c)),
        Err(e) => fail(err, &e.to_string()),
    }
}

/// # Safety
/// `c` must be null or a compiler from `flint_compiler_new`, not already freed.
#[no_mangle]
pub unsafe extern "C" fn flint_compiler_free(c: *mut Compiler) {
    if !c.is_null() {
        drop(unsafe { Box::from_raw(c) });
    }
}

/// What to compile. Zero-initialising this struct is a valid, minimal request
/// apart from `fn_name` and `resolve`, which have no meaningful default.
#[repr(C)]
pub struct FlintCompileOpts {
    /// `namespace/function`: what a sandbox calls by default.
    pub fn_name: *const c_char,
    pub resolve: FlintResolver,
    /// Passed back to `resolve` untouched.
    pub resolve_ctx: *mut c_void,
    /// Additionally callable names, as `namespace/function`. Only reachable
    /// code ships (`doc/decisions/0002`), so a function nobody calls from the
    /// entry is exactly the one that has to be named here to survive.
    pub exports: *const *const c_char,
    pub exports_len: usize,
    /// An ORDERED PREFERENCE, not a switch: `"perf"` compiles every arity
    /// ahead of time, `"size"` interprets. The first token this build
    /// understands decides, and unrecognised ones are IGNORED -- which is what
    /// makes a list written against a newer flint still get this one's best
    /// effort rather than a refusal.
    pub optimize: *const *const c_char,
    pub optimize_len: usize,
    /// Nonzero to cut the runtime down to what this program reaches. The
    /// default -- a zeroed struct -- is off, so shaking is opt-in from C even
    /// though it is on by default in Rust, because a zeroed struct has to mean
    /// the least surprising thing.
    pub shake: c_int,
    /// Arbitrary metadata recorded in the artifact and never read by flint.
    /// Keys are strings; values are any `FlintValue`.
    pub meta_keys: *const *const c_char,
    pub meta_values: *const *const Value,
    pub meta_len: usize,
}

unsafe fn strings(p: *const *const c_char, n: usize) -> Vec<String> {
    if p.is_null() { return Vec::new() }
    (0..n)
        .filter_map(|i| unsafe { borrowed(*p.add(i)) }.map(String::from))
        .collect()
}

/// Compile. The Image is the caller's; free it with `flint_image_free`.
///
/// # Safety
/// Every pointer in `opts` must be null or valid for the length beside it.
#[no_mangle]
pub unsafe extern "C" fn flint_compile(
    compiler: *const Compiler,
    opts: *const FlintCompileOpts,
    err: *mut *mut c_char,
) -> *mut Image {
    let (Some(compiler), Some(opts)) = (unsafe { compiler.as_ref() }, unsafe { opts.as_ref() })
    else {
        return fail(err, "flint_compile needs a compiler and an options struct");
    };
    let Some(fn_name) = (unsafe { borrowed(opts.fn_name) }) else {
        return fail(err, "flint_compile needs fn_name, as \"namespace/function\"");
    };
    let Some(resolve) = opts.resolve else {
        return fail(err, "flint_compile needs a resolver: there is no filesystem here");
    };

    let ctx = opts.resolve_ctx;
    let resolver = move |ns: &str| -> Option<String> {
        let Ok(cns) = CString::new(ns) else { return None };
        let mut out: *const c_char = ptr::null();
        let ok = unsafe { resolve(ctx, cns.as_ptr(), &mut out) };
        if ok == 0 { return None }
        unsafe { borrowed(out) }.map(String::from)
    };

    let exports = unsafe { strings(opts.exports, opts.exports_len) };
    let export_refs: Vec<&str> = exports.iter().map(String::as_str).collect();
    let optimize: Vec<Optimize> = unsafe { strings(opts.optimize, opts.optimize_len) }
        .iter()
        .filter_map(|t| match t.trim_start_matches(':') {
            "perf" => Some(Optimize::Perf),
            "size" => Some(Optimize::Size),
            // Ignored, not refused: see `optimize` above.
            _ => None,
        })
        .collect();

    let mut meta = Vec::new();
    if !opts.meta_keys.is_null() && !opts.meta_values.is_null() {
        for i in 0..opts.meta_len {
            let Some(k) = (unsafe { borrowed(*opts.meta_keys.add(i)) }) else { continue };
            let Some(v) = (unsafe { (*opts.meta_values.add(i)).as_ref() }) else { continue };
            meta.push((k.to_string(), v.clone()));
        }
    }

    match compiler.compile(Compile {
        resolve: &resolver,
        fn_name,
        exports: &export_refs,
        optimize: &optimize,
        shake: opts.shake != 0,
        meta,
    }) {
        Ok(img) => Box::into_raw(Box::new(img)),
        Err(e) => fail(err, &e.to_string()),
    }
}

/// The module bytes. Owned by the Image and valid until it is freed.
///
/// # Safety
/// `img` must be an Image from `flint_compile`, not already freed.
#[no_mangle]
pub unsafe extern "C" fn flint_image_wasm(img: *const Image, len: *mut usize) -> *const u8 {
    let Some(img) = (unsafe { img.as_ref() }) else {
        if !len.is_null() { unsafe { *len = 0 } }
        return ptr::null();
    };
    if !len.is_null() {
        unsafe { *len = img.wasm.len() };
    }
    img.wasm.as_ptr()
}

/// # Safety
/// `img` must be null or an Image from `flint_compile`, not already freed.
#[no_mangle]
pub unsafe extern "C" fn flint_image_free(img: *mut Image) {
    if !img.is_null() {
        drop(unsafe { Box::from_raw(img) });
    }
}

// --- drivers (`doc/decisions/0028`) ----------------------------------------

/// Who advances a sandbox, and when.
///
/// A sandbox does not run because someone called into it; it runs because a
/// driver gave it a thread. `flint_driver_inline` runs on the calling thread;
/// `flint_driver_pool` gives a sandbox several executors on ONE heap, so
/// threads run guest code alongside each other.
pub struct FlintDriver(std::sync::Arc<dyn crate::Driver>);

/// The default: runs the sandbox on whichever thread woke it.
#[no_mangle]
pub extern "C" fn flint_driver_inline() -> *mut FlintDriver {
    Box::into_raw(Box::new(FlintDriver(std::sync::Arc::new(crate::Inline))))
}

/// A pool of `threads`, which on this target really do run guest code at the
/// same time on one sandbox. Read back what you got with
/// `flint_driver_parallelism`: asking is a preference, and a target that
/// cannot honour it answers honestly rather than pretending.
#[no_mangle]
pub extern "C" fn flint_driver_pool(threads: usize) -> *mut FlintDriver {
    Box::into_raw(Box::new(FlintDriver(std::sync::Arc::new(
        crate::ThreadPool::new(threads),
    ))))
}

/// # Safety
/// `d` must be a driver from this library, not already freed.
#[no_mangle]
pub unsafe extern "C" fn flint_driver_parallelism(d: *const FlintDriver) -> usize {
    unsafe { d.as_ref() }.map_or(1, |d| d.0.parallelism())
}

/// # Safety
/// `d` must be null or a driver from this library, not already freed.
#[no_mangle]
pub unsafe extern "C" fn flint_driver_free(d: *mut FlintDriver) {
    if !d.is_null() {
        drop(unsafe { Box::from_raw(d) });
    }
}

// --- the sandbox -----------------------------------------------------------


/// A running instance of an Image. Independent of every other: state a call
/// leaves behind is this sandbox's and no one else's.
///
/// # Safety
/// `img` must be an Image from `flint_compile`, not already freed.
#[no_mangle]
pub unsafe extern "C" fn flint_sandbox_new(
    img: *const Image,
    err: *mut *mut c_char,
) -> *mut Sandbox {
    let Some(img) = (unsafe { img.as_ref() }) else {
        return fail(err, "flint_sandbox_new needs an image");
    };
    match img.sandbox() {
        Ok(s) => Box::into_raw(Box::new(s)),
        Err(e) => fail(err, &e.to_string()),
    }
}

/// Instantiate under a driver of your choosing -- a pool, for instance.
///
/// # Safety
/// `img` and `driver` must be from this library and not already freed.
#[no_mangle]
pub unsafe extern "C" fn flint_sandbox_new_with(
    img: *const Image,
    driver: *const FlintDriver,
    err: *mut *mut c_char,
) -> *mut Sandbox {
    let Some(img) = (unsafe { img.as_ref() }) else {
        return fail(err, "flint_sandbox_new_with needs an image");
    };
    let Some(d) = (unsafe { driver.as_ref() }) else {
        return fail(err, "flint_sandbox_new_with needs a driver");
    };
    match img.sandbox_with(std::sync::Arc::clone(&d.0)) {
        Ok(s) => Box::into_raw(Box::new(s)),
        Err(e) => fail(err, &e.to_string()),
    }
}

/// How many threads may be inside this sandbox at once.
///
/// # Safety
/// `s` must be a sandbox from this library, not already freed.
#[no_mangle]
pub unsafe extern "C" fn flint_sandbox_parallelism(s: *const Sandbox) -> usize {
    unsafe { s.as_ref() }.map_or(1, crate::Sandbox::parallelism)
}

/// How many dispatches ran on a SECONDARY executor -- genuinely alongside
/// another thread rather than behind the program lock.
///
/// Readable because otherwise a parallel pool cannot be told from one that
/// quietly fell back to serialising, and the two pass identical tests.
///
/// # Safety
/// `s` must be a sandbox from this library, not already freed.
#[no_mangle]
pub unsafe extern "C" fn flint_sandbox_parallel_dispatches(s: *const Sandbox) -> u64 {
    unsafe { s.as_ref() }.map_or(0, crate::Sandbox::parallel_dispatches)
}

/// A sandbox from a `.wasm` artifact somebody else compiled -- no compiler
/// needed. The module carries its program as a data segment, and this runs it
/// natively (`doc/decisions/0010`).
///
/// # Safety
/// `wasm` must be valid for `len` bytes.
#[no_mangle]
pub unsafe extern "C" fn flint_sandbox_from_wasm(
    wasm: *const u8,
    len: usize,
    err: *mut *mut c_char,
) -> *mut Sandbox {
    if wasm.is_null() {
        return fail(err, "flint_sandbox_from_wasm needs a module");
    }
    match Sandbox::from_wasm(unsafe { std::slice::from_raw_parts(wasm, len) }) {
        Ok(s) => Box::into_raw(Box::new(s)),
        Err(e) => fail(err, &e.to_string()),
    }
}

/// Lend a capability by name.
///
/// Authority is never a type test (`doc/decisions/0022`): a program holds a
/// capability because the host gave it one. Granting nothing -- the default --
/// means the program can reach nothing.
///
/// # Safety
/// `s` must be a sandbox from this library, not already freed.
#[no_mangle]
pub unsafe extern "C" fn flint_sandbox_grant(s: *const Sandbox, name: *const c_char) {
    if let (Some(s), Some(n)) = (unsafe { s.as_ref() }, unsafe { borrowed(name) }) {
        s.grant(n);
    }
}

/// Stop a call after `n` instructions. Gas is deterministic
/// (`doc/decisions/0009`), so the same call stops in the same place on every
/// engine and every machine.
///
/// # Safety
/// `s` must be a sandbox from this library, not already freed.
#[no_mangle]
pub unsafe extern "C" fn flint_sandbox_set_step_limit(s: *const Sandbox, n: u64) {
    if let Some(s) = unsafe { s.as_ref() } {
        s.set_step_limit(n);
    }
}

/// Instructions executed so far.
///
/// # Safety
/// `s` must be a sandbox from this library, not already freed.
#[no_mangle]
pub unsafe extern "C" fn flint_sandbox_gas(s: *const Sandbox) -> u64 {
    unsafe { s.as_ref() }.map_or(0, Sandbox::gas)
}

/// Call a function by name. The result is the caller's; free it with
/// `flint_value_free`.
///
/// The SDK takes a name and positional arguments and nothing more: an argument
/// map, capabilities-as-arguments and the rest are a CLI's conventions
/// (`doc/decisions/0025`), not this layer's.
///
/// # Safety
/// `s` must be a sandbox from this library; `args` must be valid for `nargs`
/// values, each of which must be a value from this library.
#[no_mangle]
pub unsafe extern "C" fn flint_call(
    s: *const Sandbox,
    name: *const c_char,
    args: *const *const Value,
    nargs: usize,
    err: *mut *mut c_char,
) -> *mut Value {
    let Some(s) = (unsafe { s.as_ref() }) else {
        return fail(err, "flint_call needs a sandbox");
    };
    let Some(name) = (unsafe { borrowed(name) }) else {
        return fail(err, "flint_call needs a function name");
    };
    let mut vs: Vec<Value> = Vec::with_capacity(nargs);
    for i in 0..nargs {
        match unsafe { args.as_ref().map(|_| (*args.add(i)).as_ref()) } {
            Some(Some(v)) => vs.push(v.clone()),
            _ => return fail(err, "flint_call was given a null argument"),
        }
    }
    // Blocking, because C has no promise to hand back and inventing one here
    // would be inventing an async runtime for C. A driver-aware C API is a
    // separate surface (`doc/decisions/0028`); this is the one that works with
    // the inline driver, which is what a C caller gets by default.
    match s.call_blocking(name, &vs) {
        Ok(v) => Box::into_raw(Box::new(v)),
        Err(e) => fail(err, &e.to_string()),
    }
}

/// # Safety
/// `s` must be null or a sandbox from this library, not already freed.
#[no_mangle]
pub unsafe extern "C" fn flint_sandbox_free(s: *mut Sandbox) {
    if !s.is_null() {
        drop(unsafe { Box::from_raw(s) });
    }
}

// --- values ----------------------------------------------------------------

/// What a value is. Matches the codec's tags in meaning, not in number: the
/// wire format is free to change without changing this.
#[repr(C)]
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum FlintTag {
    Nil = 0,
    Bool = 1,
    Int = 2,
    Float = 3,
    Str = 4,
    Keyword = 5,
    Symbol = 6,
    Bytes = 7,
    Vector = 8,
    List = 9,
    Set = 10,
    Map = 11,
    /// A live thing, by identity. C can receive one and hand it back; nothing
    /// here can MAKE one, which is the sandbox rule (`doc/decisions/0025`).
    Port = 12,
    Sentinel = 13,
}

fn boxed(v: Value) -> *mut Value {
    Box::into_raw(Box::new(v))
}

/// A copy of a value, owned by the caller.
///
/// Both sides of the boundary need this and neither can fake it: C has no copy
/// constructor, and a C++ wrapper that built one out of `flint_vector` plus
/// `flint_nth` would allocate a vector per copy and leak it. One function is
/// cheaper than that in every sense.
///
/// # Safety
/// `v` must be a value from this library, not already freed.
#[no_mangle]
pub unsafe extern "C" fn flint_value_clone(v: *const Value) -> *mut Value {
    match unsafe { v.as_ref() } {
        Some(v) => boxed(v.clone()),
        None => boxed(Value::Nil),
    }
}

/// # Safety
/// `v` must be null or a value from this library, not already freed.
#[no_mangle]
pub unsafe extern "C" fn flint_value_free(v: *mut Value) {
    if !v.is_null() {
        drop(unsafe { Box::from_raw(v) });
    }
}

/// # Safety
/// `v` must be a value from this library, not already freed.
#[no_mangle]
pub unsafe extern "C" fn flint_value_tag(v: *const Value) -> FlintTag {
    match unsafe { v.as_ref() } {
        None | Some(Value::Nil) => FlintTag::Nil,
        Some(Value::Bool(_)) => FlintTag::Bool,
        Some(Value::Int(_)) => FlintTag::Int,
        Some(Value::Float(_)) => FlintTag::Float,
        Some(Value::Str(_)) => FlintTag::Str,
        Some(Value::Keyword(..)) => FlintTag::Keyword,
        Some(Value::Symbol(..)) => FlintTag::Symbol,
        Some(Value::Bytes(_)) => FlintTag::Bytes,
        Some(Value::Vector(_)) => FlintTag::Vector,
        Some(Value::List(_)) => FlintTag::List,
        Some(Value::Set(_)) => FlintTag::Set,
        Some(Value::Map(_)) => FlintTag::Map,
        Some(Value::Port(_)) => FlintTag::Port,
        Some(Value::Sentinel { .. }) => FlintTag::Sentinel,
    }
}

#[no_mangle]
pub extern "C" fn flint_nil() -> *mut Value { boxed(Value::Nil) }

#[no_mangle]
pub extern "C" fn flint_bool(b: c_int) -> *mut Value { boxed(Value::Bool(b != 0)) }

#[no_mangle]
pub extern "C" fn flint_int(i: i64) -> *mut Value { boxed(Value::Int(i)) }

#[no_mangle]
pub extern "C" fn flint_float(f: f64) -> *mut Value { boxed(Value::Float(f)) }

/// # Safety
/// `s` must be a NUL-terminated string.
#[no_mangle]
pub unsafe extern "C" fn flint_str(s: *const c_char) -> *mut Value {
    boxed(Value::Str(unsafe { borrowed(s) }.unwrap_or_default().to_string()))
}

/// # Safety
/// `b` must be valid for `len` bytes.
#[no_mangle]
pub unsafe extern "C" fn flint_bytes(b: *const u8, len: usize) -> *mut Value {
    if b.is_null() {
        return boxed(Value::Bytes(Vec::new()));
    }
    boxed(Value::Bytes(unsafe { std::slice::from_raw_parts(b, len) }.to_vec()))
}

/// `ns` may be null for an unqualified keyword such as `:foo`.
///
/// # Safety
/// `ns` and `name` must be null or NUL-terminated strings.
#[no_mangle]
pub unsafe extern "C" fn flint_keyword(ns: *const c_char, name: *const c_char) -> *mut Value {
    boxed(Value::Keyword(
        unsafe { borrowed(ns) }.map(String::from),
        unsafe { borrowed(name) }.unwrap_or_default().to_string(),
    ))
}

/// # Safety
/// `ns` and `name` must be null or NUL-terminated strings.
#[no_mangle]
pub unsafe extern "C" fn flint_symbol(ns: *const c_char, name: *const c_char) -> *mut Value {
    boxed(Value::Symbol(
        unsafe { borrowed(ns) }.map(String::from),
        unsafe { borrowed(name) }.unwrap_or_default().to_string(),
    ))
}

unsafe fn collect(items: *const *const Value, n: usize) -> Vec<Value> {
    if items.is_null() { return Vec::new() }
    (0..n)
        .filter_map(|i| unsafe { (*items.add(i)).as_ref() }.cloned())
        .collect()
}

/// The collection constructors COPY their items: the caller keeps ownership of
/// everything it passed and frees it as it always would. One rule for every
/// pointer in this header beats a rule per function.
///
/// # Safety
/// `items` must be valid for `n` values from this library.
#[no_mangle]
pub unsafe extern "C" fn flint_vector(items: *const *const Value, n: usize) -> *mut Value {
    boxed(Value::Vector(unsafe { collect(items, n) }))
}

/// # Safety
/// `items` must be valid for `n` values from this library.
#[no_mangle]
pub unsafe extern "C" fn flint_list(items: *const *const Value, n: usize) -> *mut Value {
    boxed(Value::List(unsafe { collect(items, n) }))
}

/// # Safety
/// `items` must be valid for `n` values from this library.
#[no_mangle]
pub unsafe extern "C" fn flint_set(items: *const *const Value, n: usize) -> *mut Value {
    boxed(Value::Set(unsafe { collect(items, n) }))
}

/// A map from parallel key and value arrays. Pairs are kept in the order given
/// -- a flint map's keys are not always strings, and order is worth keeping
/// through a round trip.
///
/// # Safety
/// `keys` and `vals` must each be valid for `n` values from this library.
#[no_mangle]
pub unsafe extern "C" fn flint_map(
    keys: *const *const Value,
    vals: *const *const Value,
    n: usize,
) -> *mut Value {
    let (ks, vs) = (unsafe { collect(keys, n) }, unsafe { collect(vals, n) });
    boxed(Value::Map(ks.into_iter().zip(vs).collect()))
}

// --- reading a value back --------------------------------------------------

/// # Safety
/// `v` must be a value from this library, not already freed.
#[no_mangle]
pub unsafe extern "C" fn flint_as_bool(v: *const Value) -> c_int {
    match unsafe { v.as_ref() } {
        Some(Value::Bool(b)) => *b as c_int,
        _ => 0,
    }
}

/// # Safety
/// `v` must be a value from this library, not already freed.
#[no_mangle]
pub unsafe extern "C" fn flint_as_int(v: *const Value) -> i64 {
    match unsafe { v.as_ref() } {
        Some(Value::Int(i)) => *i,
        Some(Value::Float(f)) => *f as i64,
        _ => 0,
    }
}

/// # Safety
/// `v` must be a value from this library, not already freed.
#[no_mangle]
pub unsafe extern "C" fn flint_as_float(v: *const Value) -> f64 {
    match unsafe { v.as_ref() } {
        Some(Value::Float(f)) => *f,
        Some(Value::Int(i)) => *i as f64,
        _ => 0.0,
    }
}

/// The text of a string, keyword or symbol -- NOT its printed form, so a
/// keyword answers `foo` and not `:foo`. Owned by the caller; free it with
/// `flint_string_free`. Null for anything else.
///
/// # Safety
/// `v` must be a value from this library, not already freed.
#[no_mangle]
pub unsafe extern "C" fn flint_as_str(v: *const Value) -> *mut c_char {
    match unsafe { v.as_ref() } {
        Some(Value::Str(s)) => owned(s),
        Some(Value::Keyword(_, n)) | Some(Value::Symbol(_, n)) => owned(n),
        _ => ptr::null_mut(),
    }
}

/// The namespace of a keyword or symbol, or null when it has none.
///
/// # Safety
/// `v` must be a value from this library, not already freed.
#[no_mangle]
pub unsafe extern "C" fn flint_namespace(v: *const Value) -> *mut c_char {
    match unsafe { v.as_ref() } {
        Some(Value::Keyword(Some(ns), _)) | Some(Value::Symbol(Some(ns), _)) => owned(ns),
        _ => ptr::null_mut(),
    }
}

/// The bytes of a byte string. Borrowed from the value and valid until it is
/// freed; null for anything else.
///
/// # Safety
/// `v` must be a value from this library, not already freed.
#[no_mangle]
pub unsafe extern "C" fn flint_as_bytes(v: *const Value, len: *mut usize) -> *const u8 {
    if let Some(Value::Bytes(b)) = unsafe { v.as_ref() } {
        if !len.is_null() { unsafe { *len = b.len() } }
        return b.as_ptr();
    }
    if !len.is_null() { unsafe { *len = 0 } }
    ptr::null()
}

/// How many items a collection has; the number of PAIRS for a map. Zero for
/// anything that is not a collection.
///
/// # Safety
/// `v` must be a value from this library, not already freed.
#[no_mangle]
pub unsafe extern "C" fn flint_count(v: *const Value) -> usize {
    match unsafe { v.as_ref() } {
        Some(Value::Vector(x)) | Some(Value::List(x)) | Some(Value::Set(x)) => x.len(),
        Some(Value::Map(m)) => m.len(),
        _ => 0,
    }
}

/// Item `i` of a collection, COPIED: the caller owns it and frees it with
/// `flint_value_free`, and the collection is unaffected. Null when out of
/// range. For a map this is the key at `i`; see `flint_map_value`.
///
/// # Safety
/// `v` must be a value from this library, not already freed.
#[no_mangle]
pub unsafe extern "C" fn flint_nth(v: *const Value, i: usize) -> *mut Value {
    let got = match unsafe { v.as_ref() } {
        Some(Value::Vector(x)) | Some(Value::List(x)) | Some(Value::Set(x)) => x.get(i).cloned(),
        Some(Value::Map(m)) => m.get(i).map(|(k, _)| k.clone()),
        _ => None,
    };
    got.map_or(ptr::null_mut(), boxed)
}

/// The value of pair `i` of a map, copied like `flint_nth`.
///
/// # Safety
/// `v` must be a value from this library, not already freed.
#[no_mangle]
pub unsafe extern "C" fn flint_map_value(v: *const Value, i: usize) -> *mut Value {
    match unsafe { v.as_ref() } {
        Some(Value::Map(m)) => m.get(i).map_or(ptr::null_mut(), |(_, x)| boxed(x.clone())),
        _ => ptr::null_mut(),
    }
}

/// The value printed the way flint prints it, for logs and for tests. Owned by
/// the caller; free it with `flint_string_free`.
///
/// # Safety
/// `v` must be a value from this library, not already freed.
#[no_mangle]
pub unsafe extern "C" fn flint_print(v: *const Value) -> *mut c_char {
    match unsafe { v.as_ref() } {
        Some(v) => owned(&format!("{v}")),
        None => owned("nil"),
    }
}
