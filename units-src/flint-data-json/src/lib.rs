//! `flint.data.json` — the parser half, adapted from `serde_json`.
//!
//! # Why serde_json, and why this shape
//!
//! The brief asks for an existing crate, working with `alloc` and no `std`, used
//! through its *streaming* interface so flint values are built directly rather
//! than by converting somebody else's document tree.
//!
//! `serde_json` with `default-features = false, features = ["alloc"]` is no_std,
//! and `DeserializeSeed` + `Visitor` is precisely a streaming interface: each
//! callback is handed one token's worth of data and returns the flint value for
//! it. Nothing is ever materialised as a `serde_json::Value`.
//!
//! Two other candidates were tried and rejected, for the record:
//! * **actson** (a genuine push parser) is not `no_std` — it pulls `std` in, and
//!   its `panic_impl` collides with the runtime's.
//! * **microjson** is `no_std`, but reads integers as `isize` (32-bit on wasm32)
//!   and floats as `f32`. JSON needs 64 bits of both.
//!
//! # Number policy
//!
//! JSON has no integer/decimal distinction, so the mapping is ours to choose and
//! to write down: a number with **no fraction and no exponent** reads as a
//! **long**; anything else reads as a **double**. That is `serde_json`'s own
//! split (`visit_i64`/`visit_u64` versus `visit_f64`) and it matches what a
//! Clojure programmer expects from `1` versus `1.0`.
//!
//! Keys arrive as strings. `:key-fn` is applied in cljc, because it is policy.

#![no_std]

extern crate alloc;

use alloc::string::String;
use core::fmt;
use serde::de::{self, DeserializeSeed, Deserializer, MapAccess, SeqAccess, Visitor};

use flint_rt::rt::Rt;
use flint_rt::value::{Value, FALSE, NIL, TRUE};

/// The visitor needs `&mut Rt`, and serde hands `self` by value through nested
/// calls, so the runtime travels as a raw pointer. Single-threaded, and no two
/// of these are ever live at once.
#[derive(Clone, Copy)]
struct Seed(*mut Rt);

macro_rules! rt {
    ($s:expr) => {
        unsafe { &mut *$s.0 }
    };
}

impl<'de> DeserializeSeed<'de> for Seed {
    type Value = Value;
    fn deserialize<D: Deserializer<'de>>(self, d: D) -> Result<Value, D::Error> {
        d.deserialize_any(self)
    }
}

impl<'de> Visitor<'de> for Seed {
    type Value = Value;

    fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
        f.write_str("any JSON value")
    }

    fn visit_bool<E: de::Error>(self, v: bool) -> Result<Value, E> {
        Ok(if v { TRUE } else { FALSE })
    }
    fn visit_i64<E: de::Error>(self, v: i64) -> Result<Value, E> {
        Ok(rt!(self).integer(v))
    }
    fn visit_u64<E: de::Error>(self, v: u64) -> Result<Value, E> {
        // Beyond i64 there is no integer left to be exact in, so it becomes a
        // double rather than silently wrapping.
        if v <= i64::MAX as u64 {
            Ok(rt!(self).integer(v as i64))
        } else {
            Ok(Value::from_f64(v as f64))
        }
    }
    fn visit_f64<E: de::Error>(self, v: f64) -> Result<Value, E> {
        Ok(Value::from_f64(v))
    }
    fn visit_str<E: de::Error>(self, v: &str) -> Result<Value, E> {
        Ok(rt!(self).string(v))
    }
    fn visit_string<E: de::Error>(self, v: String) -> Result<Value, E> {
        Ok(rt!(self).string(&v))
    }
    fn visit_unit<E: de::Error>(self) -> Result<Value, E> {
        Ok(NIL)
    }
    fn visit_none<E: de::Error>(self) -> Result<Value, E> {
        Ok(NIL)
    }
    fn visit_some<D: Deserializer<'de>>(self, d: D) -> Result<Value, D::Error> {
        d.deserialize_any(self)
    }

    fn visit_seq<A: SeqAccess<'de>>(self, mut a: A) -> Result<Value, A::Error> {
        let rt = rt!(self);
        let base = rt.mark();
        let empty = rt.empty_vec();
        let acc = rt.push(empty);
        // The accumulator lives on the shadow root stack, so the collector can
        // find it while the elements below allocate.
        while let Some(v) = a.next_element_seed(self)? {
            let rt = rt!(self);
            let nv = rt.vec_conj(rt.r(acc), v);
            rt.set_r(acc, nv);
        }
        let rt = rt!(self);
        let out = rt.r(acc);
        rt.pop_to(base);
        Ok(out)
    }

    fn visit_map<A: MapAccess<'de>>(self, mut a: A) -> Result<Value, A::Error> {
        let rt = rt!(self);
        let base = rt.mark();
        let empty = rt.empty_map();
        let acc = rt.push(empty);
        while let Some(k) = a.next_key::<String>()? {
            let rt = rt!(self);
            let kv = rt.string(&k);
            let ki = rt.push(kv);
            let v = a.next_value_seed(self)?;
            let rt = rt!(self);
            let kv = rt.r(ki);
            let nm = rt.map_assoc(rt.r(acc), kv, v);
            rt.set_r(acc, nm);
            rt.pop_to(ki);
        }
        let rt = rt!(self);
        let out = rt.r(acc);
        rt.pop_to(base);
        Ok(out)
    }
}

pub fn b_json_parse(rt: &mut Rt, a: usize, n: usize) -> Value {
    let _ = n;
    let v = rt.vat(a);
    let mut buf = flint_rt::rt::sbuf();
    // Copied out of the flint heap first: the collector moves strings, and the
    // parser holds borrows into this text for the whole parse.
    let owned: String = match rt.as_str(v, &mut buf) {
        Some(s) => s.into(),
        None => return rt.throw_str("ClassCastException", "json/read-str wants a string"),
    };
    let seed = Seed(rt as *mut Rt);
    let mut de = serde_json::Deserializer::from_str(&owned);
    match DeserializeSeed::deserialize(seed, &mut de) {
        Ok(v) => match de.end() {
            Ok(()) => v,
            Err(e) => {
                let msg = alloc::format!("JSON: trailing input ({e})");
                rt.throw_str("Exception", &msg)
            }
        },
        Err(e) => {
            let msg = alloc::format!("JSON: {e}");
            rt.throw_str("Exception", &msg)
        }
    }
}

#[no_mangle]
pub extern "C" fn flint_b_json_parse(rt: *mut Rt, base: u32, argc: u32) -> u64 {
    unsafe { b_json_parse(&mut *rt, base as usize, argc as usize).0 }
}
