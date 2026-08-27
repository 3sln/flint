//! The wire codec (`doc/decisions/0025`).
//!
//! One encoding for every flint value that crosses the host boundary: a call's
//! arguments, its return, and everything a port carries. It is a **codec**
//! rather than a message format because the same bytes carry all three, and
//! naming it after one would mislead about the others.
//!
//! ## The shape
//!
//! A self-contained recursive stream. The tag numbers are the image's
//! (`image.rs`), because flint already had a tagged value encoding and two
//! numberings for one idea is a drift waiting to happen. What is NOT reused is
//! the shape: the image is a constant POOL whose entries reference each other
//! by index, which is right for a program and heavy for one value.
//!
//! A stream duplicates a subtree that appears twice, where a pool would share
//! it. That is an optimisation and a pool variant can be added later; a value
//! crossing a boundary is a tree in practice.
//!
//! ## The one safety rule
//!
//! `K_PORT` and `K_SENTINEL` carry their identity INLINE, and that is safe for
//! a reason that has nothing to do with the format: **flint is given no way to
//! turn an integer into a port or a sentinel.** A guest never writes these
//! bytes -- it hands over a VALUE and the runtime encodes it, so for a port tag
//! to appear the guest must have held a port.
//!
//! Which puts one requirement on this file, and `decode_guest` is where it
//! lives: a decoder reachable from the guest must refuse the live tags. A
//! decoder is an encoder read backwards, and bytes are integers a guest can
//! write.

use crate::obj::*;
use crate::rt::Rt;
use crate::value::{Value, FALSE, NIL, TRUE};
use alloc::string::String;
use alloc::vec::Vec;

pub use crate::image::{
    K_DOUBLE, K_FALSE, K_INT, K_KEYWORD, K_LIST, K_MAP, K_NIL, K_SET, K_STRING, K_SYMBOL, K_TRUE,
    K_VECTOR,
};

/// A byte string (`doc/decisions/0024`), so binary crosses without base64.
pub const K_BYTES: u8 = 14;
/// A port. The payload is its id; see the note above on why that is safe.
pub const K_PORT: u8 = 15;
/// An opaque identity: host id, then the label. A guest-minted one has host id
/// 0 (`doc/decisions/0022`), which is what makes it recognisable as not the
/// host's own.
pub const K_SENTINEL: u8 = 16;

/// `None` means the namespace is absent, which is not the same as empty.
const NO_NS: u32 = u32::MAX;

// --- encoding --------------------------------------------------------------

fn put_u32(out: &mut Vec<u8>, n: u32) {
    out.extend_from_slice(&n.to_le_bytes());
}
fn put_u64(out: &mut Vec<u8>, n: u64) {
    out.extend_from_slice(&n.to_le_bytes());
}
fn put_str(out: &mut Vec<u8>, s: &str) {
    put_u32(out, s.len() as u32);
    out.extend_from_slice(s.as_bytes());
}

impl Rt {
    /// Encode one value. `Err` names what could not be encoded, in the same
    /// terms `check_sendable` uses -- a function's meaning is its environment,
    /// and that does not travel.
    pub fn encode(&mut self, v: Value) -> Result<Vec<u8>, String> {
        let mut out = Vec::new();
        self.encode_into(v, &mut out, 0)?;
        Ok(out)
    }

    fn encode_into(&mut self, v: Value, out: &mut Vec<u8>, depth: u32) -> Result<(), String> {
        if depth > 128 {
            return Err(String::from("value nested too deeply to encode"));
        }
        if v.is_nil() {
            out.push(K_NIL);
            return Ok(());
        }
        if v == TRUE {
            out.push(K_TRUE);
            return Ok(());
        }
        if v == FALSE {
            out.push(K_FALSE);
            return Ok(());
        }
        if let Some(i) = self.as_i64(v) {
            if self.is_int(v) {
                out.push(K_INT);
                put_u64(out, i as u64);
                return Ok(());
            }
        }
        if v.is_double() {
            out.push(K_DOUBLE);
            put_u64(out, v.as_f64().to_bits());
            return Ok(());
        }
        if self.is_string(v) {
            let mut b = crate::rt::sbuf();
            let s: String = self.as_str(v, &mut b).unwrap_or("").into();
            out.push(K_STRING);
            put_str(out, &s);
            return Ok(());
        }
        if self.is_keyword(v) || self.is_symbol(v) {
            let kw = self.is_keyword(v);
            let nsv = self.ns_of(v);
            let namev = self.name_of(v);
            let mut b = crate::rt::sbuf();
            let ns: Option<String> = if nsv.is_nil() {
                None
            } else {
                Some(self.as_str(nsv, &mut b).unwrap_or("").into())
            };
            let mut b2 = crate::rt::sbuf();
            let name: String = self.as_str(namev, &mut b2).unwrap_or("").into();
            out.push(if kw { K_KEYWORD } else { K_SYMBOL });
            match &ns {
                Some(s) => put_str(out, s),
                None => put_u32(out, NO_NS),
            }
            put_str(out, &name);
            return Ok(());
        }
        if self.is_bytes(v) {
            let bs = self.b_to_vec(v);
            out.push(K_BYTES);
            put_u32(out, bs.len() as u32);
            out.extend_from_slice(&bs);
            return Ok(());
        }
        if !v.is_heap() {
            return Err(alloc::format!("this value cannot cross a boundary: {:?}", v));
        }
        match ty(&self.gc.sp, v.as_heap()) {
            TY_PORT => {
                out.push(K_PORT);
                put_u32(out, self.slot(v, crate::conc::PT_ID).as_fixnum() as u32);
                Ok(())
            }
            TY_OPAQUE => {
                let label = self.slot(v, 0);
                let mut b = crate::rt::sbuf();
                let l: String = self.as_str(label, &mut b).unwrap_or("").into();
                out.push(K_SENTINEL);
                put_u64(out, self.opaque_host_id(v));
                put_str(out, &l);
                Ok(())
            }
            TY_CLOSURE | TY_NATIVEFN | TY_MULTIFN => Err(String::from(
                "a function cannot cross a boundary: its meaning is its environment, \
                 and that does not travel",
            )),
            TY_ATOM => Err(String::from("an atom cannot cross a boundary")),
            TY_VAR => Err(String::from("a var cannot cross a boundary")),
            TY_THREAD => Err(String::from("a thread cannot cross a boundary")),
            _ => self.encode_collection(v, out, depth),
        }
    }

    fn encode_collection(&mut self, v: Value, out: &mut Vec<u8>, depth: u32) -> Result<(), String> {
        // The items are collected FIRST, then encoded. Encoding allocates --
        // `as_str` can build a flat string from a rope -- and walking a
        // collection while allocating into it is the shape `../HANDOFF.md` was
        // written about.
        if self.is_map(v) {
            let mut items: Vec<Value> = Vec::new();
            let mut st = &mut items;
            self.map_for_each(v, &mut st, &mut |_rt, k, val, st| {
                st.push(k);
                st.push(val);
            });
            out.push(K_MAP);
            put_u32(out, (items.len() / 2) as u32);
            let base = self.mark();
            for it in &items {
                self.push(*it);
            }
            for i in 0..items.len() {
                let it = self.r(base + i);
                self.encode_into(it, out, depth + 1)?;
            }
            self.pop_to(base);
            return Ok(());
        }
        let tag = if self.is_set(v) {
            K_SET
        } else if self.is_vector(v) {
            K_VECTOR
        } else if self.is_seq(v) {
            K_LIST
        } else {
            return Err(alloc::format!(
                "this value cannot cross a boundary (type {})",
                ty(&self.gc.sp, v.as_heap())
            ));
        };
        // Walked into a Vec first, for the reason above: encoding allocates.
        let mut items: Vec<Value> = Vec::new();
        {
            let base = self.mark();
            self.push(v);
            let mut cur = self.seq(self.r(base));
            self.set_r(base, cur);
            while !cur.is_nil() {
                items.push(self.first(cur));
                cur = self.next(self.r(base));
                self.set_r(base, cur);
            }
            self.pop_to(base);
        }
        out.push(tag);
        put_u32(out, items.len() as u32);
        let base = self.mark();
        for it in &items {
            self.push(*it);
        }
        for i in 0..items.len() {
            let it = self.r(base + i);
            self.encode_into(it, out, depth + 1)?;
        }
        self.pop_to(base);
        Ok(())
    }
}

// --- decoding --------------------------------------------------------------

struct Reader<'a> {
    b: &'a [u8],
    i: usize,
}

impl<'a> Reader<'a> {
    fn u8(&mut self) -> Result<u8, String> {
        let v = *self.b.get(self.i).ok_or("the encoding ends mid-value")?;
        self.i += 1;
        Ok(v)
    }
    fn u32(&mut self) -> Result<u32, String> {
        let e = self.i + 4;
        let s = self.b.get(self.i..e).ok_or("the encoding ends mid-value")?;
        self.i = e;
        Ok(u32::from_le_bytes([s[0], s[1], s[2], s[3]]))
    }
    fn u64(&mut self) -> Result<u64, String> {
        let e = self.i + 8;
        let s = self.b.get(self.i..e).ok_or("the encoding ends mid-value")?;
        self.i = e;
        let mut a = [0u8; 8];
        a.copy_from_slice(s);
        Ok(u64::from_le_bytes(a))
    }
    fn str(&mut self) -> Result<Option<String>, String> {
        let n = self.u32()?;
        if n == NO_NS {
            return Ok(None);
        }
        let e = self.i + n as usize;
        let s = self.b.get(self.i..e).ok_or("the encoding ends mid-string")?;
        self.i = e;
        Ok(Some(
            core::str::from_utf8(s)
                .map_err(|_| String::from("a string in the encoding is not UTF-8"))?
                .into(),
        ))
    }
}

impl Rt {
    /// Decode a value the HOST produced. Live tags are honoured.
    pub fn decode(&mut self, bytes: &[u8]) -> Result<Value, String> {
        let mut r = Reader { b: bytes, i: 0 };
        self.decode_at(&mut r, true, 0)
    }

    /// Decode a value the GUEST produced, where the live tags are refused.
    ///
    /// This is the whole of `0025`'s safety rule, and it is one line: a guest
    /// that could decode arbitrary bytes into a port would have exactly the
    /// integer-to-port conversion the sandbox forbids. Nothing calls this yet;
    /// it exists so that whoever adds a guest-callable decoder finds it rather
    /// than writing the unsafe one.
    pub fn decode_guest(&mut self, bytes: &[u8]) -> Result<Value, String> {
        let mut r = Reader { b: bytes, i: 0 };
        self.decode_at(&mut r, false, 0)
    }

    fn decode_at(&mut self, r: &mut Reader, live: bool, depth: u32) -> Result<Value, String> {
        if depth > 128 {
            return Err(String::from("value nested too deeply to decode"));
        }
        let tag = r.u8()?;
        match tag {
            K_NIL => Ok(NIL),
            K_TRUE => Ok(TRUE),
            K_FALSE => Ok(FALSE),
            K_INT => {
                let n = r.u64()? as i64;
                Ok(self.integer(n))
            }
            K_DOUBLE => Ok(Value::from_f64(f64::from_bits(r.u64()?))),
            K_STRING => {
                let s = r.str()?.ok_or("a string cannot be absent")?;
                Ok(self.string(&s))
            }
            K_KEYWORD | K_SYMBOL => {
                let ns = r.str()?;
                let name = r.str()?.ok_or("a name cannot be absent")?;
                Ok(if tag == K_KEYWORD {
                    self.keyword(ns.as_deref(), &name)
                } else {
                    self.symbol(ns.as_deref(), &name)
                })
            }
            K_BYTES => {
                let n = r.u32()? as usize;
                let e = r.i + n;
                let s = r.b.get(r.i..e).ok_or("the encoding ends mid-bytes")?.to_vec();
                r.i = e;
                Ok(self.new_bytes(&s))
            }
            K_VECTOR | K_LIST | K_SET => {
                let n = r.u32()? as usize;
                let base = self.mark();
                for _ in 0..n {
                    let v = self.decode_at(r, live, depth + 1)?;
                    self.push(v);
                }
                let out = match tag {
                    K_VECTOR => self.vec_from_roots(base, n),
                    K_LIST => self.list_from_roots(base, n),
                    _ => {
                        // No `set_from_roots`, so it is built by conj -- and
                        // the accumulator lives on the ROOT STACK, because
                        // `set_conj` allocates.
                        let empty = self.roots.singletons[crate::rt::SING_EMPTY_SET];
                        let acc = self.push(empty);
                        for k in 0..n {
                            let item = self.r(base + k);
                            let next = self.set_conj(self.r(acc), item);
                            self.set_r(acc, next);
                        }
                        self.r(acc)
                    }
                };
                self.pop_to(base);
                Ok(out)
            }
            K_MAP => {
                let n = r.u32()? as usize;
                let base = self.mark();
                for _ in 0..n * 2 {
                    let v = self.decode_at(r, live, depth + 1)?;
                    self.push(v);
                }
                let empty = self.roots.singletons[crate::rt::SING_EMPTY_MAP];
                let acc = self.push(empty);
                for k in 0..n {
                    let key = self.r(base + k * 2);
                    let val = self.r(base + k * 2 + 1);
                    let next = self.map_assoc(self.r(acc), key, val);
                    self.set_r(acc, next);
                }
                let out = self.r(acc);
                self.pop_to(base);
                Ok(out)
            }
            K_PORT if live => {
                let id = r.u32()?;
                let p = self.port_by_id(id as i64);
                if p.is_nil() {
                    return Err(alloc::format!("there is no port {id} here"));
                }
                Ok(p)
            }
            K_SENTINEL if live => {
                let host_id = r.u64()?;
                let label = r.str()?.ok_or("a label cannot be absent")?;
                let base = self.mark();
                let l = self.string(&label);
                self.push(l);
                let out = self.new_opaque(self.r(base), host_id);
                self.pop_to(base);
                Ok(out)
            }
            K_PORT | K_SENTINEL => Err(String::from(
                "a port or a sentinel cannot be decoded here: they are identities, \
                 and an identity is held rather than described",
            )),
            other => Err(alloc::format!("unknown tag {other} in the encoding")),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Encode, decode, and check the value that comes back IS the one that
    /// went in -- by `=`, which for flint means by content.
    fn round(rt: &mut Rt, v: Value) -> Value {
        let base = rt.mark();
        rt.push(v);
        let held = rt.r(base);
        let bytes = rt.encode(held).expect("encode");
        let out = rt.decode(&bytes).expect("decode");
        rt.pop_to(base);
        out
    }

    #[test]
    fn every_scalar_survives_a_round_trip() {
        let mut rt = Rt::new();
        let cases = {
            let s = rt.string("hello");
            let k = rt.keyword(Some("my.ns"), "key");
            let k2 = rt.keyword(None, "bare");
            let sym = rt.symbol(Some("my.ns"), "sym");
            let b = rt.new_bytes(&[1, 2, 255]);
            alloc::vec![NIL, TRUE, FALSE, Value::fixnum(42), Value::fixnum(-7),
                        Value::from_f64(1.5), s, k, k2, sym, b]
        };
        for v in cases {
            let out = round(&mut rt, v);
            assert!(rt.eq(v, out), "a value did not survive the round trip");
        }
    }

    #[test]
    fn collections_survive_and_keep_their_kind() {
        let mut rt = Rt::new();
        let base = rt.mark();
        // [1 "two" :three]
        let s = rt.string("two");
        rt.push(Value::fixnum(1));
        rt.push(s);
        let k = rt.keyword(None, "three");
        rt.push(k);
        let vec = rt.vec_from_roots(base, 3);
        rt.pop_to(base);
        rt.push(vec);

        let held = rt.r(base);
        let out = round(&mut rt, held);
        assert!(rt.is_vector(out), "a vector came back as something else");
        let want = rt.r(base);
        assert!(rt.eq(want, out));

        // A map, whose keys are keywords -- the case the host cares about.
        let empty = rt.roots.singletons[crate::rt::SING_EMPTY_MAP];
        let m = rt.push(empty);
        let kk = rt.keyword(None, "a");
        let next = rt.map_assoc(rt.r(m), kk, Value::fixnum(1));
        rt.set_r(m, next);
        let held = rt.r(m);
        let out = round(&mut rt, held);
        assert!(rt.is_map(out), "a map came back as something else");
        let want = rt.r(m);
        assert!(rt.eq(want, out));
        rt.pop_to(base);
    }

    #[test]
    fn nesting_survives() {
        let mut rt = Rt::new();
        let base = rt.mark();
        let inner_s = rt.string("deep");
        rt.push(inner_s);
        let inner = rt.vec_from_roots(base, 1);
        rt.pop_to(base);
        rt.push(inner);
        let outer = rt.vec_from_roots(base, 1);
        rt.pop_to(base);
        rt.push(outer);
        let held = rt.r(base);
        let out = round(&mut rt, held);
        let want = rt.r(base);
        assert!(rt.eq(want, out), "a nested vector did not survive");
        rt.pop_to(base);
    }

    /// A function's meaning is its environment, and that does not travel.
    #[test]
    fn what_cannot_cross_says_so() {
        let mut rt = Rt::new();
        let a = rt.new_atom(NIL);
        assert!(rt.encode(a).is_err(), "an atom crossed a boundary");
    }

    /// `0025`'s whole safety rule, as a test rather than a comment.
    ///
    /// A guest that could decode arbitrary bytes into a port would have the
    /// integer-to-port conversion the sandbox forbids -- so the guest decoder
    /// refuses the live tags, and the host decoder does not.
    #[test]
    fn a_guest_cannot_decode_an_integer_into_a_port() {
        let mut rt = Rt::new();
        let mut bytes = alloc::vec![K_PORT];
        bytes.extend_from_slice(&7u32.to_le_bytes());
        let err = rt.decode_guest(&bytes).expect_err("a guest decoded a port");
        assert!(err.contains("identity"), "the refusal should say why: {err}");

        let mut s = alloc::vec![K_SENTINEL];
        s.extend_from_slice(&99u64.to_le_bytes());
        s.extend_from_slice(&3u32.to_le_bytes());
        s.extend_from_slice(b"fs\0");
        assert!(rt.decode_guest(&s).is_err(), "a guest decoded a sentinel");

        // And the same bytes, from the host, DO make a sentinel -- otherwise
        // this test would pass with the whole tag unimplemented.
        let mut ok = alloc::vec![K_SENTINEL];
        ok.extend_from_slice(&99u64.to_le_bytes());
        ok.extend_from_slice(&2u32.to_le_bytes());
        ok.extend_from_slice(b"fs");
        let v = rt.decode(&ok).expect("the host decodes a sentinel");
        assert_eq!(rt.opaque_host_id(v), 99, "the host id did not survive");
    }

    /// Truncated input is a message, not a trap.
    #[test]
    fn a_short_encoding_is_refused() {
        let mut rt = Rt::new();
        assert!(rt.decode(&[K_INT, 1, 2]).is_err(), "a truncated int decoded");
        assert!(rt.decode(&[]).is_err(), "an empty encoding decoded");
        assert!(rt.decode(&[200]).is_err(), "an unknown tag decoded");
    }
}
