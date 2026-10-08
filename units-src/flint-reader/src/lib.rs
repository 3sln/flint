//! `dist/flint-reader.wasm`: the kin reader and forms encoder, alone
//! (`DECISIONS.md#namespaces-over-the-system-port`, "where the reader lives").
//!
//! A JavaScript host answers the compiler's namespace requests with
//! `flint.forms` BYTES, so it has to read source text itself, outside the
//! compiler sandbox -- and the only reader of the language there is the one
//! the Rust runtime carries, generated from `kin/read*.kin`. This module is
//! that reader with four exports and nothing else: no interpreter, no image, no
//! ports. It is a separate module rather than an export of
//! `flint-runtime.wasm` because that module is what every compiled PROGRAM is
//! spliced into, and a reader export there would ship in every program
//! (`DECISIONS.md#namespaces-over-the-system-port` records the sizes).
//!
//! The protocol is one call:
//!
//!   1. `flint_reader_in(n)` -> an address to write `n` bytes at;
//!   2. write one wire-encoded vector there,
//!      `[text file features tags dialect]` -- `features` nil (a deferred
//!      read) or a vector of keyword-name strings (`":flint"`), `tags` a
//!      vector of `[tag var]` symbol-name string pairs in declaration order,
//!      `dialect` the string `"flint"` or `"portable"`;
//!   3. `flint_reader_read(n)` -> 1 when the output is the forms bytes, 0 when
//!      it is a wire-encoded `[message line column]`, 2 when the input itself
//!      did not decode (the output is the message, as UTF-8);
//!   4. read `flint_reader_out_len()` bytes at `flint_reader_out_ptr()`.
//!
//! Everything about HOW the arguments become heap values is
//! `flint_rt::hostread::read_text`, shared with the native CLI, so the two hosts
//! cannot build a feature set in two orders.

#![no_std]

extern crate alloc;

use alloc::string::String;
use alloc::vec::Vec;
use flint_rt::codec::{self, Val, Wire};
use flint_rt::hostread::{read_text, ReadAs};
use flint_rt::rt::Rt;

/// ONE `Rt` for the module's life. A wasm heap is carved off linear memory and
/// never given back, so an `Rt` per read would leak a nursery each time;
/// `read_text` leaves the root stack as it found it, and the collector reclaims
/// what a read left behind.
static mut RT: Option<Rt> = None;
static mut IN: Vec<u8> = Vec::new();
static mut OUT: Vec<u8> = Vec::new();

/// Bring the allocator up before anything allocates: an uninitialised arena
/// hands out memory from address 0, over the data segments (`abi.rs`'s
/// `ensure_arena` says what that looked like). This module has no spliced
/// segments, so the linker's `__heap_base` is where the heap starts.
unsafe fn ensure_arena() {
    extern "C" {
        static __heap_base: u8;
    }
    let a = &*core::ptr::addr_of!(flint_rt::mem::arena::ARENA);
    if a.brk == 0 {
        flint_rt::mem::arena::init(core::ptr::addr_of!(__heap_base) as u32);
    }
}

#[no_mangle]
pub extern "C" fn flint_reader_in(n: u32) -> u32 {
    unsafe {
        ensure_arena();
        let buf = &mut *core::ptr::addr_of_mut!(IN);
        buf.clear();
        buf.resize(n as usize, 0);
        buf.as_mut_ptr() as u32
    }
}

#[no_mangle]
pub extern "C" fn flint_reader_out_ptr() -> u32 {
    unsafe { (*core::ptr::addr_of!(OUT)).as_ptr() as u32 }
}

#[no_mangle]
pub extern "C" fn flint_reader_out_len() -> u32 {
    unsafe { (*core::ptr::addr_of!(OUT)).len() as u32 }
}

fn strings(v: &Val, what: &str) -> Result<Vec<String>, String> {
    match v.as_slice() {
        Some(xs) => xs
            .iter()
            .map(|x| x.as_str().map(String::from).ok_or_else(|| alloc::format!("{what}: not a string")))
            .collect(),
        None => Err(alloc::format!("{what}: not a vector")),
    }
}

fn decode(input: &[u8]) -> Result<(String, String, Option<Vec<String>>, Vec<(String, String)>, bool), String> {
    let v = codec::parse(input)?;
    let xs = v.as_slice().ok_or("the input is not a vector")?;
    if xs.len() != 5 {
        return Err(alloc::format!("the input has {} elements, not 5", xs.len()));
    }
    let text = xs[0].as_str().ok_or("text: not a string")?.into();
    let file = xs[1].as_str().ok_or("file: not a string")?.into();
    let features = match &xs[2] {
        Val::Nil => None,
        other => Some(strings(other, "features")?),
    };
    let mut tags = Vec::new();
    for pair in xs[3].as_slice().ok_or("tags: not a vector")? {
        let p = strings(pair, "a tag pair")?;
        if p.len() != 2 {
            return Err("a tag pair is not two names".into());
        }
        tags.push((p[0].clone(), p[1].clone()));
    }
    let portable = match xs[4].as_str() {
        Some("portable") => true,
        Some("flint") => false,
        _ => return Err("dialect: neither \"flint\" nor \"portable\"".into()),
    };
    Ok((text, file, features, tags, portable))
}

#[no_mangle]
pub extern "C" fn flint_reader_read(n: u32) -> u32 {
    unsafe {
        ensure_arena();
        let input = &*core::ptr::addr_of!(IN);
        let out = &mut *core::ptr::addr_of_mut!(OUT);
        let n = (n as usize).min(input.len());
        let (text, file, features, tags, portable) = match decode(&input[..n]) {
            Ok(x) => x,
            Err(e) => {
                *out = e.into_bytes();
                return 2;
            }
        };
        let rt = (*core::ptr::addr_of_mut!(RT)).get_or_insert_with(Rt::new);
        let feats: Option<Vec<&str>> = features.as_ref().map(|fs| fs.iter().map(String::as_str).collect());
        let tag_refs: Vec<(&str, &str)> = tags.iter().map(|(t, v)| (t.as_str(), v.as_str())).collect();
        let how = ReadAs { features: feats.as_deref(), tags: &tag_refs, portable };
        match read_text(rt, &file, &text, &how) {
            Ok(bytes) => {
                *out = bytes;
                1
            }
            Err(e) => {
                let mut w = Wire::new();
                w.vector(3).string(&e.message).int(e.line).int(e.column);
                *out = w.done();
                0
            }
        }
    }
}
