//! The program image: the compiler's output, spliced into the module as a data
//! segment.
//!
//! ```text
//!   "FLINTIMG" u32 version
//!   u32 nconsts    ; tagged constant entries, in dependency order
//!   u32 nfns       ; function table
//!   u32 nvars      ; var name constant indices
//!   u32 nnatives   ; (name-const, table-slot) pairs -- the slot is written by
//!                  ; `flint` after the link, from the module's export section
//!   u32 codelen    ; bytecode
//!   u32 entry      ; fn index called with the argument vector
//!   u32 ninit      ; fn indices to run first, in order
//! ```
//!
//! Constants may reference earlier constants by index, so the writer emits them
//! in dependency order and the loader can build them in one pass.

use alloc::vec::Vec;

use crate::rt::Rt;
use crate::value::{Value, FALSE, NIL, TRUE};
use crate::vm::{Arity, FnDef, Image};

pub const MAGIC: &[u8; 8] = b"FLINTIMG";
pub const VERSION: u32 = 1;

pub const K_NIL: u8 = 0;
pub const K_TRUE: u8 = 1;
pub const K_FALSE: u8 = 2;
pub const K_INT: u8 = 3;
pub const K_DOUBLE: u8 = 4;
pub const K_STRING: u8 = 5;
pub const K_KEYWORD: u8 = 6;
pub const K_SYMBOL: u8 = 7;
pub const K_VECTOR: u8 = 8;
pub const K_LIST: u8 = 9;
pub const K_MAP: u8 = 10;
pub const K_SET: u8 = 11;
pub const K_FN: u8 = 12;
/// A builtin as a first-class value; payload is the native *import* index, so
/// this does not depend on the natives table having been read yet.
pub const K_NATIVE: u8 = 13;

pub const NO_CONST: u32 = 0xFFFF_FFFF;

struct Rd<'a> {
    b: &'a [u8],
    i: usize,
}

impl<'a> Rd<'a> {
    fn u8(&mut self) -> u8 {
        let v = self.b[self.i];
        self.i += 1;
        v
    }
    fn u16(&mut self) -> u16 {
        let v = u16::from_le_bytes([self.b[self.i], self.b[self.i + 1]]);
        self.i += 2;
        v
    }
    fn u32(&mut self) -> u32 {
        let v = u32::from_le_bytes([
            self.b[self.i],
            self.b[self.i + 1],
            self.b[self.i + 2],
            self.b[self.i + 3],
        ]);
        self.i += 4;
        v
    }
    fn i64(&mut self) -> i64 {
        let mut a = [0u8; 8];
        a.copy_from_slice(&self.b[self.i..self.i + 8]);
        self.i += 8;
        i64::from_le_bytes(a)
    }
    fn f64(&mut self) -> f64 {
        let mut a = [0u8; 8];
        a.copy_from_slice(&self.b[self.i..self.i + 8]);
        self.i += 8;
        f64::from_le_bytes(a)
    }
    fn bytes(&mut self, n: usize) -> &'a [u8] {
        let s = &self.b[self.i..self.i + n];
        self.i += n;
        s
    }
}

impl Rt {
    /// Parse and install a program image. Returns false if the bytes are not a
    /// compatible image.
    pub fn load_image(&mut self, bytes: &[u8]) -> bool {
        if bytes.len() < 12 || &bytes[..8] != MAGIC {
            return false;
        }
        let mut r = Rd { b: bytes, i: 8 };
        if r.u32() != VERSION {
            return false;
        }

        let nconsts = r.u32() as usize;
        self.roots.consts.clear();
        self.roots.consts.reserve(nconsts);
        for _ in 0..nconsts {
            let tag = r.u8();
            let v = match tag {
                K_NIL => NIL,
                K_TRUE => TRUE,
                K_FALSE => FALSE,
                K_INT => {
                    let n = r.i64();
                    self.integer(n)
                }
                K_DOUBLE => Value::from_f64(r.f64()),
                K_STRING => {
                    let n = r.u32() as usize;
                    let s = r.bytes(n);
                    let owned: alloc::string::String =
                        core::str::from_utf8(s).unwrap_or("").into();
                    self.string(&owned)
                }
                K_KEYWORD | K_SYMBOL => {
                    let nsc = r.u32();
                    let nmc = r.u32();
                    let mut b1 = crate::rt::sbuf();
                    let ns_owned: Option<alloc::string::String> = if nsc == NO_CONST {
                        None
                    } else {
                        let v = self.roots.consts[nsc as usize];
                        Some(self.as_str(v, &mut b1).unwrap_or("").into())
                    };
                    let mut b2 = crate::rt::sbuf();
                    let nm_owned: alloc::string::String = {
                        let v = self.roots.consts[nmc as usize];
                        self.as_str(v, &mut b2).unwrap_or("").into()
                    };
                    if tag == K_KEYWORD {
                        self.keyword(ns_owned.as_deref(), &nm_owned)
                    } else {
                        self.symbol(ns_owned.as_deref(), &nm_owned)
                    }
                }
                K_VECTOR | K_LIST | K_SET => {
                    let n = r.u32() as usize;
                    let base = self.mark();
                    for _ in 0..n {
                        let idx = r.u32() as usize;
                        let v = self.roots.consts[idx];
                        self.push(v);
                    }
                    let out = match tag {
                        K_VECTOR => self.vec_from_roots(base, n),
                        K_LIST => self.list_from_roots(base, n),
                        _ => {
                            let mut s = self.empty_set();
                            let si = self.push(s);
                            for i in 0..n {
                                let x = self.r(base + i);
                                let ns = self.set_conj(self.r(si), x);
                                self.set_r(si, ns);
                            }
                            s = self.r(si);
                            s
                        }
                    };
                    self.pop_to(base);
                    out
                }
                K_MAP => {
                    let n = r.u32() as usize;
                    let base = self.mark();
                    for _ in 0..2 * n {
                        let idx = r.u32() as usize;
                        let v = self.roots.consts[idx];
                        self.push(v);
                    }
                    let mut m = self.empty_map();
                    let mi = self.push(m);
                    for i in 0..n {
                        let k = self.r(base + 2 * i);
                        let v = self.r(base + 2 * i + 1);
                        let nm = self.map_assoc(self.r(mi), k, v);
                        self.set_r(mi, nm);
                    }
                    m = self.r(mi);
                    self.pop_to(base);
                    m
                }
                K_FN => {
                    let f = r.u32();
                    self.make_closure(f, &[])
                }
                K_NATIVE => {
                    let idx = r.u32();
                    let namec = r.u32();
                    let name = self.roots.consts[namec as usize];
                    self.make_native(idx, name)
                }
                _ => return false,
            };
            self.roots.consts.push(v);
        }

        let nfns = r.u32() as usize;
        let mut fns = Vec::with_capacity(nfns);
        for _ in 0..nfns {
            let name = r.u32();
            let nupvals = r.u8();
            let na = r.u8() as usize;
            let mut arities = Vec::with_capacity(na);
            for _ in 0..na {
                let argc = r.u8();
                let flags = r.u8();
                let nlocals = r.u16();
                let code = r.u32();
                let len = r.u32();
                arities.push(Arity { argc, variadic: flags & 1 != 0, nlocals, code, len });
            }
            fns.push(FnDef { name, arities, nupvals });
        }

        let nvars = r.u32() as usize;
        let mut var_names = Vec::with_capacity(nvars);
        for _ in 0..nvars {
            var_names.push(r.u32());
        }
        self.roots.globals.clear();
        self.roots.globals.resize(nvars, NIL);

        let nnat = r.u32() as usize;
        let mut natives = Vec::with_capacity(nnat);
        for _ in 0..nnat {
            let _name = r.u32();
            natives.push(r.u32());
        }

        let codelen = r.u32() as usize;
        let code = r.bytes(codelen).to_vec();
        let entry = r.u32();
        let ninit = r.u32() as usize;
        let mut init = Vec::with_capacity(ninit);
        for _ in 0..ninit {
            init.push(r.u32());
        }

        self.image = Image { code, fns, natives, var_names, entry, init };
        true
    }
}

// --- writer ---------------------------------------------------------------
//
// The compiler emits images in Clojure; this writer exists so the Rust tests can
// build one directly, and so there is a round trip that pins the format.

#[derive(Default)]
pub struct ImageWriter {
    pub consts: Vec<Vec<u8>>,
    pub fns: Vec<Vec<u8>>,
    pub vars: Vec<u32>,
    pub natives: Vec<(u32, u32)>,
    pub code: Vec<u8>,
    pub entry: u32,
    pub init: Vec<u32>,
}

impl ImageWriter {
    pub fn new() -> ImageWriter {
        Default::default()
    }

    fn add(&mut self, e: Vec<u8>) -> u32 {
        self.consts.push(e);
        (self.consts.len() - 1) as u32
    }
    pub fn k_nil(&mut self) -> u32 {
        self.add(alloc::vec![K_NIL])
    }
    pub fn k_int(&mut self, n: i64) -> u32 {
        let mut e = alloc::vec![K_INT];
        e.extend_from_slice(&n.to_le_bytes());
        self.add(e)
    }
    pub fn k_double(&mut self, d: f64) -> u32 {
        let mut e = alloc::vec![K_DOUBLE];
        e.extend_from_slice(&d.to_le_bytes());
        self.add(e)
    }
    pub fn k_string(&mut self, s: &str) -> u32 {
        let mut e = alloc::vec![K_STRING];
        e.extend_from_slice(&(s.len() as u32).to_le_bytes());
        e.extend_from_slice(s.as_bytes());
        self.add(e)
    }
    pub fn k_keyword(&mut self, ns: Option<&str>, name: &str) -> u32 {
        let nsc = ns.map(|s| self.k_string(s)).unwrap_or(NO_CONST);
        let nmc = self.k_string(name);
        let mut e = alloc::vec![K_KEYWORD];
        e.extend_from_slice(&nsc.to_le_bytes());
        e.extend_from_slice(&nmc.to_le_bytes());
        self.add(e)
    }
    pub fn k_symbol(&mut self, ns: Option<&str>, name: &str) -> u32 {
        let nsc = ns.map(|s| self.k_string(s)).unwrap_or(NO_CONST);
        let nmc = self.k_string(name);
        let mut e = alloc::vec![K_SYMBOL];
        e.extend_from_slice(&nsc.to_le_bytes());
        e.extend_from_slice(&nmc.to_le_bytes());
        self.add(e)
    }
    pub fn k_vector(&mut self, items: &[u32]) -> u32 {
        let mut e = alloc::vec![K_VECTOR];
        e.extend_from_slice(&(items.len() as u32).to_le_bytes());
        for i in items {
            e.extend_from_slice(&i.to_le_bytes());
        }
        self.add(e)
    }
    pub fn k_map(&mut self, kvs: &[u32]) -> u32 {
        let mut e = alloc::vec![K_MAP];
        e.extend_from_slice(&((kvs.len() / 2) as u32).to_le_bytes());
        for i in kvs {
            e.extend_from_slice(&i.to_le_bytes());
        }
        self.add(e)
    }

    pub fn add_native(&mut self, name_const: u32, slot: u32) -> u32 {
        self.natives.push((name_const, slot));
        (self.natives.len() - 1) as u32
    }
    pub fn add_var(&mut self, name_const: u32) -> u32 {
        self.vars.push(name_const);
        (self.vars.len() - 1) as u32
    }

    /// Append a function with a single arity whose code is `body`.
    pub fn add_fn(&mut self, name_const: u32, argc: u8, variadic: bool, nlocals: u16, body: &[u8]) -> u32 {
        let off = self.code.len() as u32;
        self.code.extend_from_slice(body);
        let mut e = Vec::new();
        e.extend_from_slice(&name_const.to_le_bytes());
        e.push(0); // nupvals
        e.push(1); // one arity
        e.push(argc);
        e.push(variadic as u8);
        e.extend_from_slice(&nlocals.to_le_bytes());
        e.extend_from_slice(&off.to_le_bytes());
        e.extend_from_slice(&(body.len() as u32).to_le_bytes());
        self.fns.push(e);
        (self.fns.len() - 1) as u32
    }

    pub fn finish(&self) -> Vec<u8> {
        let mut o = Vec::new();
        o.extend_from_slice(MAGIC);
        o.extend_from_slice(&VERSION.to_le_bytes());
        o.extend_from_slice(&(self.consts.len() as u32).to_le_bytes());
        for c in &self.consts {
            o.extend_from_slice(c);
        }
        o.extend_from_slice(&(self.fns.len() as u32).to_le_bytes());
        for f in &self.fns {
            o.extend_from_slice(f);
        }
        o.extend_from_slice(&(self.vars.len() as u32).to_le_bytes());
        for v in &self.vars {
            o.extend_from_slice(&v.to_le_bytes());
        }
        o.extend_from_slice(&(self.natives.len() as u32).to_le_bytes());
        for (n, s) in &self.natives {
            o.extend_from_slice(&n.to_le_bytes());
            o.extend_from_slice(&s.to_le_bytes());
        }
        o.extend_from_slice(&(self.code.len() as u32).to_le_bytes());
        o.extend_from_slice(&self.code);
        o.extend_from_slice(&self.entry.to_le_bytes());
        o.extend_from_slice(&(self.init.len() as u32).to_le_bytes());
        for i in &self.init {
            o.extend_from_slice(&i.to_le_bytes());
        }
        o
    }
}
