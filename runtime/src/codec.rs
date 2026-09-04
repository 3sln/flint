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
/// A tagged literal (`doc/decisions/0034`): the tag symbol, then the form.
///
/// 17 here and 17 in `image.rs`, because the two SHARE a numbering space. It
/// was defined for the image and not for the wire, so a tagged literal could be
/// an image constant and could not cross a port -- which is half of what `0034`
/// said the type was for.
pub const K_TAGGED: u8 = 17;
/// A table (`doc/decisions/0026`), COLUMNAR: the schema, the row count, then
/// each column's values in full before the next one starts.
///
/// Row-major would be a vector of maps with extra steps, and would lose exactly
/// what the type is for -- the receiver would rebuild a map per row to read one
/// field. Column-major means a decoder can fill chunk runs directly, and it is
/// what `0033`'s columnar JSON mirrors at the format layer.
pub const K_TABLE: u8 = 18;

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
            // FLATTENED FIRST. `as_str` borrows, so by design it will not
            // materialise a rope -- it answers `None` — and `unwrap_or("")`
            // turned that into THE EMPTY STRING. Every rope that crossed a
            // bridge arrived as "", silently: `(pr-str x)` builds a rope, so
            // this hit the ordinary case of answering a call with rendered
            // text. The host boundary is the legitimate place to flatten, and
            // it is what `port_send` already does for its own bytes
            // (`doc/decisions/0011`).
            let base = self.mark();
            let vi = self.push(v);
            let flat = self.string_arg(self.r(vi));
            self.set_r(vi, flat);
            let mut b = crate::rt::sbuf();
            let s: String = self.as_str(self.r(vi), &mut b).unwrap_or("").into();
            self.pop_to(base);
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
            crate::obj::TY_TAGGED => {
                out.push(K_TAGGED);
                let tag = self.slot(v, 0);
                let form = self.slot(v, 1);
                self.encode_into(tag, out, depth + 1)?;
                self.encode_into(form, out, depth + 1)
            }
            crate::obj::TY_TABLE => {
                out.push(K_TABLE);
                let base = self.mark();
                let vi = self.push(v);
                let s = self.slot(self.r(vi), crate::table::TB_SCHEMA);
                let si = self.push(s);
                let ncols = self.schema_len(self.r(si));
                let nrows = self.table_count(self.r(vi));
                put_u32(out, ncols);
                for c in 0..ncols {
                    let names = self.slot(self.r(si), crate::table::SC_NAMES);
                    let name = self.vec_nth(names, c, NIL);
                    let types = self.slot(self.r(si), crate::table::SC_TYPES);
                    let tp = self.vec_nth(types, c, NIL);
                    if let Err(e) = self.encode_into(name, out, depth + 1) {
                        self.pop_to(base);
                        return Err(e);
                    }
                    if let Err(e) = self.encode_into(tp, out, depth + 1) {
                        self.pop_to(base);
                        return Err(e);
                    }
                }
                put_u32(out, nrows);
                // COLUMN BY COLUMN, each in full. A receiver reading one field
                // reads one run.
                for c in 0..ncols {
                    let col = {
                        let nm = {
                            let names = self.slot(self.r(si), crate::table::SC_NAMES);
                            self.vec_nth(names, c, NIL)
                        };
                        self.table_column(self.r(vi), nm)
                    };
                    let ci = self.push(col);
                    for i in 0..nrows {
                        let x = self.vec_nth(self.r(ci), i, NIL);
                        if let Err(e) = self.encode_into(x, out, depth + 1) {
                            self.pop_to(base);
                            return Err(e);
                        }
                    }
                    self.pop_to(ci);
                }
                self.pop_to(base);
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
        // Walked into the SHADOW STACK, not a Rust Vec, for two reasons and it
        // used to be only the second one.
        //
        // Encoding allocates, so the items have to be rooted before the encode
        // loop. That was already true and was already done. What was NOT true is
        // that the WALK is allocation-free: `next` on a lazy seq forces the
        // tail, which runs arbitrary flint code and can collect -- so every item
        // gathered so far went stale, in a `Vec` the collector does not scan.
        // Same fault as `Rt::ordered_map`, same shape, found by the same hunt
        // (`doc/decisions/0031`).
        let base = self.mark();
        self.push(v);
        let mut cur = self.seq(self.r(base));
        self.set_r(base, cur);
        let items_at = self.mark();
        let mut count = 0usize;
        while !cur.is_nil() {
            let x = self.first(cur);
            self.push(x);
            count += 1;
            cur = self.next(self.r(base));
            self.set_r(base, cur);
        }
        out.push(tag);
        put_u32(out, count as u32);
        for i in 0..count {
            let it = self.r(items_at + i);
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
            K_TAGGED => {
                let tag = self.decode_at(r, live, depth + 1)?;
                let base = self.mark();
                let ti = self.push(tag);
                let form = self.decode_at(r, live, depth + 1)?;
                let fi = self.push(form);
                let (tv, fv) = (self.r(ti), self.r(fi));
                let out = self.new_tagged(tv, fv);
                self.pop_to(base);
                Ok(out)
            }
            K_TABLE => {
                let ncols = r.u32()? as usize;
                let base = self.mark();
                // The schema pairs, then the columns. Built through the ordinary
                // constructors, so a table off the wire is checked exactly as
                // one built in the program is -- a decoder that skipped the
                // schema check would be a way to make a table that is not
                // closed.
                let pairs = self.empty_vec();
                let pi = self.push(pairs);
                for _ in 0..ncols {
                    let name = self.decode_at(r, live, depth + 1)?;
                    let ni = self.push(name);
                    let tp = self.decode_at(r, live, depth + 1)?;
                    let ti2 = self.push(tp);
                    let pair = {
                        let e = self.empty_vec();
                        let ei = self.push(e);
                        let nv = self.r(ni);
                        let c1 = self.vec_conj(self.r(ei), nv);
                        self.set_r(ei, c1);
                        let tv = self.r(ti2);
                        let c2 = self.vec_conj(self.r(ei), tv);
                        self.set_r(ei, c2);
                        let out = self.r(ei);
                        self.pop_to(ei);
                        out
                    };
                    let nv = self.vec_conj(self.r(pi), pair);
                    self.set_r(pi, nv);
                    self.pop_to(ni);
                }
                let nrows = r.u32()? as usize;
                let cols = self.empty_vec();
                let ci = self.push(cols);
                for _ in 0..ncols {
                    let col = self.empty_vec();
                    let coli = self.push(col);
                    for _ in 0..nrows {
                        let x = self.decode_at(r, live, depth + 1)?;
                        let nv = self.vec_conj(self.r(coli), x);
                        self.set_r(coli, nv);
                    }
                    let cv = self.r(coli);
                    let nv = self.vec_conj(self.r(ci), cv);
                    self.set_r(ci, nv);
                    self.pop_to(coli);
                }
                let pv = self.r(pi);
                let schema = self.new_schema(pv);
                if !self.thrown.is_nil() {
                    self.pop_to(base);
                    return Err(String::from("a table arrived with a schema it cannot have"));
                }
                let si = self.push(schema);
                let (sv, cv) = (self.r(si), self.r(ci));
                let out = self.table_from_columns(sv, cv, nrows as u32);
                self.pop_to(base);
                if !self.thrown.is_nil() {
                    return Err(String::from("a table arrived that its own schema refuses"));
                }
                Ok(out)
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
                        let empty = self.roots.shared.singletons[crate::rt::SING_EMPTY_SET];
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
                let empty = self.roots.shared.singletons[crate::rt::SING_EMPTY_MAP];
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
                // INTERN OR MINT. A port the host names in a message is a port
                // it is handing to this sandbox, and that is how a capability
                // gets delegated (`doc/decisions/0027`). It used to be refused
                // unless the sandbox already held it, which made delegation
                // impossible and was the pre-0027 rule that a port could only
                // ever be one the sandbox had asked for.
                //
                // Arriving twice costs nothing and counts once: the handle is
                // interned by host id, so the second arrival finds the first
                // object and no second reference is taken.
                //
                // Through `bridge_hook` rather than straight to
                // `install_bridge_port`, and that indirection is a SIZE
                // decision rather than a style one -- see `Rt::bridge_hook`.
                // `None` means this sandbox has no scheduler, so it has no
                // ports, so it cannot be being handed one.
                let p = match self.bridge_hook {
                    Some(f) => f(self, id as i64),
                    None => {
                        return Err(alloc::format!(
                            "port {id} arrived, but this sandbox has no ports"
                        ))
                    }
                };
                if p.is_nil() {
                    return Err(alloc::format!("port {id} could not be installed here"));
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
        let empty = rt.roots.shared.singletons[crate::rt::SING_EMPTY_MAP];
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

    /// A table crosses as a TABLE, columnar, and comes back one.
    ///
    /// The wire codec had no arm for a table or for a tagged literal, so both
    /// of the types `0026` and `0034` added could be image constants and could
    /// not cross a port -- which is half of what each was for.
    #[test]
    fn a_table_crosses_columnar_and_returns_a_table() {
        let mut rt = Rt::new();
        // Built by conj, with everything on the ROOT STACK: a `Vec<Value>` is
        // not a root (`doc/decisions/0031`) and these allocate.
        let vec2 = |rt: &mut Rt, a: Value, b: Value| {
            let base = rt.mark();
            let ai = rt.push(a);
            let bi = rt.push(b);
            let e = rt.empty_vec();
            let ei = rt.push(e);
            let av = rt.r(ai);
            let v1 = rt.vec_conj(rt.r(ei), av);
            rt.set_r(ei, v1);
            let bv = rt.r(bi);
            let v2 = rt.vec_conj(rt.r(ei), bv);
            rt.set_r(ei, v2);
            let out = rt.r(ei);
            rt.pop_to(base);
            out
        };
        let pairs = {
            let (id, int) = (rt.keyword(None, "id"), rt.keyword(None, "int"));
            let p1 = vec2(&mut rt, id, int);
            let pi = rt.push(p1);
            let (nm, st) = (rt.keyword(None, "name"), rt.keyword(None, "string"));
            let p2 = vec2(&mut rt, nm, st);
            let a = rt.r(pi);
            let out = vec2(&mut rt, a, p2);
            rt.pop_to(pi);
            out
        };
        let schema = rt.new_schema(pairs);
        let row = |rt: &mut Rt, n: i64, s: &str| {
            let base = rt.mark();
            let m = rt.empty_map();
            let mi = rt.push(m);
            let idk = rt.keyword(None, "id");
            let m1 = rt.map_assoc(rt.r(mi), idk, Value::fixnum(n));
            rt.set_r(mi, m1);
            let nmk = rt.keyword(None, "name");
            let sv = rt.string(s);
            let svi = rt.push(sv);
            let (mv, v) = (rt.r(mi), rt.r(svi));
            let m2 = rt.map_assoc(mv, nmk, v);
            rt.set_r(mi, m2);
            let out = rt.r(mi);
            rt.pop_to(base);
            out
        };
        let si = rt.push(schema);
        let rows = {
            let m1 = row(&mut rt, 1, "a");
            let mi = rt.push(m1);
            let m2 = row(&mut rt, 2, "b");
            let a = rt.r(mi);
            let out = vec2(&mut rt, a, m2);
            rt.pop_to(mi);
            out
        };
        let schema = rt.r(si);
        let t = rt.new_table(schema, rows);
        assert!(rt.is_table(t), "built a table");
        let mut out = alloc::vec::Vec::new();
        rt.encode_into(t, &mut out, 0).expect("a table encodes");
        assert_eq!(out[0], K_TABLE, "and it encodes AS a table, not as a vector of maps");
        let back = rt.decode(&out).expect("and decodes");
        assert!(rt.is_table(back), "and comes back a TABLE, not a vector of maps");
        assert_eq!(rt.table_count(back), 2);
        let nm = rt.keyword(None, "name");
        let r0 = rt.table_ref(back, 0);
        let v0 = rt.ref_get(r0, nm, NIL);
        let want = rt.string("a");
        assert!(rt.eq(v0, want), "and its values survived");
        assert!(rt.eq(t, back), "and it is EQUAL to what was sent");
    }

    /// A tagged literal crosses too, which is the point of it being a type
    /// rather than a two-key map (`doc/decisions/0034`).
    #[test]
    fn a_tagged_literal_crosses_as_itself() {
        let mut rt = Rt::new();
        let tag = rt.symbol(Some("my.ns"), "thing");
        let form = {
            let base = rt.mark();
            let e = rt.empty_vec();
            let ei = rt.push(e);
            let v1 = rt.vec_conj(rt.r(ei), Value::fixnum(1));
            rt.set_r(ei, v1);
            let v2 = rt.vec_conj(rt.r(ei), Value::fixnum(2));
            rt.set_r(ei, v2);
            let out = rt.r(ei);
            rt.pop_to(base);
            out
        };
        let t = rt.new_tagged(tag, form);
        let mut out = alloc::vec::Vec::new();
        rt.encode_into(t, &mut out, 0).expect("encodes");
        assert_eq!(out[0], K_TAGGED);
        let back = rt.decode(&out).expect("decodes");
        assert!(rt.is_tagged(back), "comes back TAGGED, not as a map");
        assert!(rt.eq(t, back));
    }
}

// --- the host's side of the codec -------------------------------------------
//
// A host has to WRITE messages, and it has no flint heap to write them from.
// `Rt::encode` turns a value in the sandbox into bytes; this turns a host's own
// data into the same bytes, with no runtime involved at all.
//
// This is the LOW-LEVEL half, deliberately (`doc/decisions/0027`). A host that
// wants to hand over a capability, or a port, or a table has to say so exactly;
// a host that just wants to send a string or a map of them uses the shorthands
// at the bottom. The guest gets neither half -- it hands over a value and is
// handed one back, and never sees an encoding.

/// One message, under construction.
///
/// Cheap and linear: every `put` appends, so building a nested value is a
/// pre-order walk with no backtracking and no length patching. The counts go in
/// before the elements because the decoder needs them before it starts, which is
/// what lets it build a collection without growing one.
#[derive(Default, Clone)]
pub struct Wire {
    b: Vec<u8>,
}

impl Wire {
    pub fn new() -> Wire {
        Wire { b: Vec::new() }
    }
    /// The bytes, ready for `host_deliver`.
    pub fn done(self) -> Vec<u8> {
        self.b
    }
    pub fn as_bytes(&self) -> &[u8] {
        &self.b
    }

    fn u32(&mut self, n: u32) -> &mut Wire {
        self.b.extend_from_slice(&n.to_le_bytes());
        self
    }
    fn u64(&mut self, n: u64) -> &mut Wire {
        self.b.extend_from_slice(&n.to_le_bytes());
        self
    }
    fn text(&mut self, s: &str) -> &mut Wire {
        let n = s.len() as u32;
        self.u32(n);
        self.b.extend_from_slice(s.as_bytes());
        self
    }
    /// A namespace that is ABSENT, which is not the same as empty: that is what
    /// distinguishes `:kw` from `:/kw`.
    fn absent(&mut self) -> &mut Wire {
        self.u32(NO_NS)
    }

    pub fn nil(&mut self) -> &mut Wire {
        self.b.push(K_NIL);
        self
    }
    pub fn bool(&mut self, v: bool) -> &mut Wire {
        self.b.push(if v { K_TRUE } else { K_FALSE });
        self
    }
    pub fn int(&mut self, n: i64) -> &mut Wire {
        self.b.push(K_INT);
        self.u64(n as u64)
    }
    pub fn float(&mut self, n: f64) -> &mut Wire {
        self.b.push(K_DOUBLE);
        self.u64(n.to_bits())
    }
    pub fn string(&mut self, s: &str) -> &mut Wire {
        self.b.push(K_STRING);
        self.text(s)
    }
    pub fn bytes(&mut self, v: &[u8]) -> &mut Wire {
        self.b.push(K_BYTES);
        self.u32(v.len() as u32);
        self.b.extend_from_slice(v);
        self
    }
    pub fn keyword(&mut self, ns: Option<&str>, name: &str) -> &mut Wire {
        self.b.push(K_KEYWORD);
        match ns {
            Some(n) => self.text(n),
            None => self.absent(),
        };
        self.text(name)
    }
    pub fn symbol(&mut self, ns: Option<&str>, name: &str) -> &mut Wire {
        self.b.push(K_SYMBOL);
        match ns {
            Some(n) => self.text(n),
            None => self.absent(),
        };
        self.text(name)
    }

    /// A PORT, by the id the HOST knows it as.
    ///
    /// This is how a capability is delegated: the sandbox decodes it into a
    /// handle, interned by that id, and is told nothing else about it. Sending
    /// the same port twice costs one holder, not two.
    pub fn port(&mut self, id: u32) -> &mut Wire {
        self.b.push(K_PORT);
        self.u32(id)
    }

    /// An OPAQUE value the host owns: an id it issued, and a label for reading.
    ///
    /// `doc/decisions/0022`: the id is the whole authority. It is meaningful
    /// only to the host that issued it, the guest can carry it and compare it
    /// and nothing else, and an id the guest MINTS is 0 -- which is why 0 must
    /// never be issued, or a forgery is indistinguishable from a grant.
    pub fn opaque(&mut self, host_id: u64, label: &str) -> &mut Wire {
        self.b.push(K_SENTINEL);
        self.u64(host_id);
        self.text(label)
    }

    /// `n` elements FOLLOW. The count first, because the decoder needs it
    /// before it starts.
    pub fn vector(&mut self, n: u32) -> &mut Wire {
        self.b.push(K_VECTOR);
        self.u32(n)
    }
    pub fn list(&mut self, n: u32) -> &mut Wire {
        self.b.push(K_LIST);
        self.u32(n)
    }
    pub fn set(&mut self, n: u32) -> &mut Wire {
        self.b.push(K_SET);
        self.u32(n)
    }
    /// `n` ENTRIES follow, each a key then a value -- so `2 * n` values.
    pub fn map(&mut self, n: u32) -> &mut Wire {
        self.b.push(K_MAP);
        self.u32(n)
    }
    /// Splice an already-built value in where the next one would go.
    ///
    /// A reply wraps a body that a handler built separately, and the format is
    /// a pre-order walk with no length patching -- so appending finished bytes
    /// is exactly as valid as writing them here, and it saves every handler
    /// having to build into its caller's buffer.
    ///
    /// It takes bytes that ARE one complete value. Nothing checks that, because
    /// checking would mean parsing, and this is the low-level half by
    /// construction.
    pub fn raw(&mut self, bytes: &[u8]) -> &mut Wire {
        self.b.extend_from_slice(bytes);
        self
    }

    /// The tag SYMBOL, then the form (`doc/decisions/0034`).
    pub fn tagged(&mut self) -> &mut Wire {
        self.b.push(K_TAGGED);
        self
    }
}

/// The shorthands. A host sending a string should not have to know there is a
/// tag byte.
impl Wire {
    /// One string, as a whole message.
    pub fn of_str(s: &str) -> Vec<u8> {
        let mut w = Wire::new();
        w.string(s);
        w.done()
    }
    /// One integer, as a whole message.
    pub fn of_int(n: i64) -> Vec<u8> {
        let mut w = Wire::new();
        w.int(n);
        w.done()
    }
    /// One port handle, as a whole message: the shape that delegates a
    /// capability in a single call.
    pub fn of_port(id: u32) -> Vec<u8> {
        let mut w = Wire::new();
        w.port(id);
        w.done()
    }
}

// --------------------------------------------------------------- reading back
//
// The other half of `Wire`, and it was missing.
//
// A host could WRITE the wire format and not read it, which is the same
// asymmetry the JVM and CLR runtimes had until `0027` was finished: an encoder
// without a decoder means a host can send a value and cannot serve a request.
// Everything the guest sends -- an RPC call into a virtual namespace, a message
// on any bridge -- arrives here as bytes, and a host that must decode them by
// hand will decode them differently from the next host.
//
// This is the HOST's reader. It builds a plain Rust tree and never touches a
// heap, so it needs no `Rt` and cannot allocate a guest object; `Rt::decode` is
// the other direction and stays what a value crossing INTO the sandbox uses.

/// A decoded value, host-side.
#[derive(Debug, Clone, PartialEq)]
pub enum Val {
    Nil,
    Bool(bool),
    Int(i64),
    Float(f64),
    Str(String),
    Bytes(Vec<u8>),
    /// Namespace, then name. `None` is ABSENT, which is not empty: that is what
    /// separates `:kw` from `:/kw`.
    Keyword(Option<String>, String),
    Symbol(Option<String>, String),
    Vector(Vec<Val>),
    List(Vec<Val>),
    Set(Vec<Val>),
    /// Entries in the order they crossed. A vector rather than a map because
    /// the wire has no ordering guarantee to preserve and a key need not be
    /// hashable in Rust -- a vector key is legal in flint.
    Map(Vec<(Val, Val)>),
    Port(u32),
    Opaque(u64, String),
    Tagged(alloc::boxed::Box<Val>, alloc::boxed::Box<Val>),
}

impl Val {
    /// The value at keyword key `name` (no namespace), or `None`.
    ///
    /// The lookup a request handler does on every message, so it is here rather
    /// than written out at each call site with a different idea of what counts
    /// as a match.
    pub fn get(&self, name: &str) -> Option<&Val> {
        match self {
            Val::Map(es) => es.iter().find_map(|(k, v)| match k {
                Val::Keyword(None, n) if n == name => Some(v),
                _ => None,
            }),
            _ => None,
        }
    }
    pub fn as_str(&self) -> Option<&str> {
        match self {
            Val::Str(s) => Some(s),
            // A keyword or symbol answers its NAME, because `{:op :invoke}` and
            // `{:op "invoke"}` mean the same thing to a server and a host that
            // matched only one of them would work until somebody wrote the
            // other.
            Val::Keyword(_, s) | Val::Symbol(_, s) => Some(s),
            _ => None,
        }
    }
    pub fn as_i64(&self) -> Option<i64> {
        match self {
            Val::Int(n) => Some(*n),
            _ => None,
        }
    }
    pub fn as_slice(&self) -> Option<&[Val]> {
        match self {
            Val::Vector(v) | Val::List(v) | Val::Set(v) => Some(v),
            _ => None,
        }
    }
}

struct HostReader<'a> {
    b: &'a [u8],
    at: usize,
}

impl<'a> HostReader<'a> {
    fn byte(&mut self) -> Result<u8, String> {
        let v = *self.b.get(self.at).ok_or("wire: ran off the end")?;
        self.at += 1;
        Ok(v)
    }
    fn u32(&mut self) -> Result<u32, String> {
        if self.at + 4 > self.b.len() {
            return Err("wire: ran off the end".into());
        }
        let v = u32::from_le_bytes([
            self.b[self.at],
            self.b[self.at + 1],
            self.b[self.at + 2],
            self.b[self.at + 3],
        ]);
        self.at += 4;
        Ok(v)
    }
    fn u64(&mut self) -> Result<u64, String> {
        if self.at + 8 > self.b.len() {
            return Err("wire: ran off the end".into());
        }
        let mut a = [0u8; 8];
        a.copy_from_slice(&self.b[self.at..self.at + 8]);
        self.at += 8;
        Ok(u64::from_le_bytes(a))
    }
    fn text(&mut self) -> Result<String, String> {
        let n = self.u32()? as usize;
        if self.at + n > self.b.len() {
            return Err("wire: a string ran off the end".into());
        }
        let s = core::str::from_utf8(&self.b[self.at..self.at + n])
            .map_err(|_| String::from("wire: a string was not utf-8"))?
            .into();
        self.at += n;
        Ok(s)
    }
    fn ns(&mut self) -> Result<Option<String>, String> {
        // Peek: `NO_NS` is a length that means ABSENT, so it has to be read as
        // a length and then rejected rather than read as text.
        if self.at + 4 > self.b.len() {
            return Err("wire: ran off the end".into());
        }
        let n = u32::from_le_bytes([
            self.b[self.at],
            self.b[self.at + 1],
            self.b[self.at + 2],
            self.b[self.at + 3],
        ]);
        if n == NO_NS {
            self.at += 4;
            return Ok(None);
        }
        Ok(Some(self.text()?))
    }
    fn many(&mut self, n: u32) -> Result<Vec<Val>, String> {
        // NOT `with_capacity(n)`: `n` is attacker-controlled in the sense that
        // matters -- a truncated or corrupt message can claim four billion
        // elements, and reserving for that is an allocation failure rather than
        // the "ran off the end" this would otherwise report one element later.
        let mut out = Vec::new();
        for _ in 0..n {
            out.push(self.val()?);
        }
        Ok(out)
    }
    fn val(&mut self) -> Result<Val, String> {
        let t = self.byte()?;
        Ok(match t {
            K_NIL => Val::Nil,
            K_TRUE => Val::Bool(true),
            K_FALSE => Val::Bool(false),
            K_INT => Val::Int(self.u64()? as i64),
            K_DOUBLE => Val::Float(f64::from_bits(self.u64()?)),
            K_STRING => Val::Str(self.text()?),
            K_BYTES => {
                let n = self.u32()? as usize;
                if self.at + n > self.b.len() {
                    return Err("wire: a byte string ran off the end".into());
                }
                let v = self.b[self.at..self.at + n].to_vec();
                self.at += n;
                Val::Bytes(v)
            }
            K_KEYWORD => {
                let ns = self.ns()?;
                Val::Keyword(ns, self.text()?)
            }
            K_SYMBOL => {
                let ns = self.ns()?;
                Val::Symbol(ns, self.text()?)
            }
            K_VECTOR => {
                let n = self.u32()?;
                Val::Vector(self.many(n)?)
            }
            K_LIST => {
                let n = self.u32()?;
                Val::List(self.many(n)?)
            }
            K_SET => {
                let n = self.u32()?;
                Val::Set(self.many(n)?)
            }
            K_MAP => {
                let n = self.u32()?;
                let mut out = Vec::new();
                for _ in 0..n {
                    let k = self.val()?;
                    let v = self.val()?;
                    out.push((k, v));
                }
                Val::Map(out)
            }
            K_PORT => Val::Port(self.u32()?),
            K_SENTINEL => {
                let id = self.u64()?;
                Val::Opaque(id, self.text()?)
            }
            K_TAGGED => {
                let tag = self.val()?;
                let form = self.val()?;
                Val::Tagged(alloc::boxed::Box::new(tag), alloc::boxed::Box::new(form))
            }
            // A TABLE is columnar and its decode needs the schema machinery
            // (`doc/decisions/0026`). Refused by name rather than mis-read:
            // a host that gets "unsupported" can act on it, and one that gets a
            // wrong value cannot.
            K_TABLE => return Err("wire: a table cannot be read host-side yet".into()),
            other => return Err(alloc::format!("wire: unknown tag {other}")),
        })
    }
}

/// Read one value from `bytes`, host-side.
///
/// Trailing bytes are an ERROR rather than ignored: a message that decodes to a
/// value and has more after it is not the message that was sent, and quietly
/// taking the first value would hide a framing bug for as long as the extra
/// bytes happened to be harmless.
pub fn parse(bytes: &[u8]) -> Result<Val, String> {
    let mut r = HostReader { b: bytes, at: 0 };
    let v = r.val()?;
    if r.at != bytes.len() {
        return Err(alloc::format!(
            "wire: {} trailing byte(s) after a complete value",
            bytes.len() - r.at
        ));
    }
    Ok(v)
}

#[cfg(test)]
mod host_reader_tests {
    use super::*;

    #[test]
    fn round_trips_what_the_builder_writes() {
        let mut w = Wire::new();
        w.map(3);
        w.keyword(None, "op");
        w.keyword(None, "invoke");
        w.keyword(None, "var");
        w.string("list-dir");
        w.keyword(None, "args");
        w.vector(2);
        w.string("src");
        w.int(-7);
        let v = parse(&w.done()).expect("parses");
        assert_eq!(v.get("op").and_then(|x| x.as_str()), Some("invoke"));
        assert_eq!(v.get("var").and_then(|x| x.as_str()), Some("list-dir"));
        let args = v.get("args").and_then(|x| x.as_slice()).expect("args");
        assert_eq!(args[0].as_str(), Some("src"));
        assert_eq!(args[1].as_i64(), Some(-7));
    }

    #[test]
    fn an_absent_namespace_is_not_an_empty_one() {
        let mut w = Wire::new();
        w.vector(2);
        w.keyword(None, "kw");
        w.keyword(Some(""), "kw");
        let v = parse(&w.done()).expect("parses");
        let xs = v.as_slice().expect("vector");
        assert_eq!(xs[0], Val::Keyword(None, "kw".into()));
        assert_eq!(xs[1], Val::Keyword(Some(String::new()), "kw".into()));
    }

    #[test]
    fn trailing_bytes_are_refused_rather_than_ignored() {
        let mut b = Wire::of_int(1);
        b.push(K_NIL);
        assert!(parse(&b).is_err());
    }

    #[test]
    fn a_truncated_message_says_so_rather_than_panicking() {
        let full = Wire::of_str("hello");
        for n in 0..full.len() {
            assert!(parse(&full[..n]).is_err(), "prefix of {n} should not parse");
        }
    }

    #[test]
    fn a_claimed_count_far_past_the_end_is_an_error_not_an_allocation() {
        let mut w = Wire::new();
        w.vector(u32::MAX);
        assert!(parse(&w.done()).is_err());
    }
}
