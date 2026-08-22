//! `demo.shout` — a toy namespace unit, used by `test/unitpath.clj`.
//!
//! It exists to prove that a unit found on the `:wasm-path` search path is not a
//! special case of the built-in ones: same manifest format, same ABI, same
//! linker path. It is not part of a flint build unless a `:wasm-path` directory
//! puts it there, which is why it lives under `test/fixtures/` and not `units/`.

#![no_std]

extern crate alloc;

use alloc::string::String;

use flint_rt::rt::Rt;
use flint_rt::value::Value;

pub fn b_demo_shout(rt: &mut Rt, a: usize, n: usize) -> Value {
    let _ = n;
    let v = rt.vat(a);
    let mut buf = flint_rt::rt::sbuf();
    let owned: String = match rt.as_str(v, &mut buf) {
        Some(s) => s.into(),
        None => return rt.throw_str("ClassCastException", "demo-shout wants a string"),
    };
    let mut out = String::with_capacity(owned.len() + 1);
    for c in owned.chars() {
        for u in c.to_uppercase() {
            out.push(u);
        }
    }
    out.push('!');
    rt.string(&out)
}

#[no_mangle]
pub extern "C" fn flint_b_demo_shout(rt: *mut Rt, base: u32, argc: u32) -> u64 {
    unsafe { b_demo_shout(&mut *rt, base as usize, argc as usize).0 }
}
