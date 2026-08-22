//! Strings, keywords and symbols.
//!
//! ## Representation, and why equality is cheap
//!
//! | bytes | string | keyword (no ns) | symbol |
//! |---|---|---|---|
//! | 0..=5 | immediate, inline in the `Value` | immediate, inline | heap, interned |
//! | 6..=`INTERN_MAX` | heap, **interned** | heap, interned | heap, interned |
//! | > `INTERN_MAX` | heap, not interned | heap, interned | heap, interned |
//!
//! Inline is canonical and interning is guaranteed in its range, so:
//!
//! * two strings of <= `INTERN_MAX` bytes are equal **iff their bits are equal**;
//! * two keywords are *always* equal iff their bits are equal;
//! * only strings longer than `INTERN_MAX` need a byte comparison.
//!
//! Symbols compare by (ns, name) rather than identity, because `with-meta` on a
//! symbol has to produce a distinct object that is still `=` to the original.
//! Those two slots are themselves inline-or-interned strings, so it is still
//! two 64-bit compares.
//!
//! A char is a one-character string. There is no char type; every character of
//! Unicode fits in the 5 inline bytes.

use crate::gc::{INTERN_KW, INTERN_STR, INTERN_SYM};
use crate::hash;
use crate::obj::*;
use crate::rt::Rt;
use crate::value::{Value, INLINE_MAX, NIL};

/// Longest string that is guaranteed to be interned.
pub const INTERN_MAX: u32 = 32;

impl Rt {
    /// Allocate a bare (uninterned) heap string.
    fn raw_string(&mut self, s: &str) -> Value {
        let n = s.len() as u32;
        let a = self.alloc(TY_STR, n);
        if a == 0 {
            return NIL;
        }
        self.gc.sp.bytes_mut(a + STR_DATA, n).copy_from_slice(s.as_bytes());
        set_str_hash(&self.gc.sp, a, 0);
        set_str_ascii(&self.gc.sp, a, s.is_ascii());
        Value::heap(a)
    }

    /// The canonical `Value` for a string.
    pub fn string(&mut self, s: &str) -> Value {
        if s.len() <= INLINE_MAX {
            return Value::inline_str(s.as_bytes());
        }
        if s.len() as u32 > INTERN_MAX {
            return self.raw_string(s);
        }
        let h = hash::hash_string(s);
        let sp = &self.gc.sp;
        let found = self.roots.interns[INTERN_STR].lookup(h, |v| {
            v.is_heap() && ty(sp, v.as_heap()) == TY_STR && str_bytes(sp, v.as_heap()) == s.as_bytes()
        });
        match found {
            Ok(v) => v,
            Err(_) => {
                let v = self.raw_string(s);
                if v.is_nil() {
                    return v;
                }
                set_str_hash(&self.gc.sp, v.as_heap(), if h == 0 { 1 } else { h });
                self.intern_into(INTERN_STR, h, v);
                v
            }
        }
    }

    fn intern_into(&mut self, table: usize, h: u32, v: Value) {
        if self.roots.interns[table].needs_grow() {
            self.roots.interns[table].grow();
        }
        // Re-probe: `grow` invalidates any index we might have had, and an
        // allocation may have run a collection that rehashed the table.
        if let Err(idx) = self.roots.interns[table].lookup(h, |_| false) {
            self.roots.interns[table].insert_at(idx, h, v);
        }
    }

    /// Hash of a string value, cached in the object for heap strings.
    pub fn string_hash(&mut self, v: Value) -> u32 {
        if v.is_inline_str() {
            let mut b = [0u8; INLINE_MAX];
            let s = core::str::from_utf8(v.inline_bytes(&mut b)).unwrap_or("");
            return hash::hash_string(s);
        }
        let a = v.as_heap();
        let cached = str_hash(&self.gc.sp, a);
        if cached != 0 {
            return cached;
        }
        let h = {
            let bytes = str_bytes(&self.gc.sp, a);
            hash::hash_string(core::str::from_utf8(bytes).unwrap_or(""))
        };
        let h = if h == 0 { 1 } else { h };
        set_str_hash(&self.gc.sp, a, h);
        h
    }

    // --- keywords ----------------------------------------------------------

    pub fn keyword(&mut self, ns: Option<&str>, name: &str) -> Value {
        if ns.is_none() && !name.is_empty() && name.len() <= INLINE_MAX {
            return Value::inline_kw(name.as_bytes());
        }
        let h = hash::hash_keyword(ns, name);
        let existing = {
            let sp = &self.gc.sp;
            self.roots.interns[INTERN_KW].lookup(h, |v| {
                v.is_heap()
                    && ty(sp, v.as_heap()) == TY_KW
                    && str_eq_at(sp, slot(sp, v.as_heap(), 0), ns)
                    && str_eq_at(sp, slot(sp, v.as_heap(), 1), Some(name))
            })
        };
        if let Ok(v) = existing {
            return v;
        }
        let nsv = match ns {
            Some(s) => self.string(s),
            None => NIL,
        };
        let n = self.push(nsv);
        let namev = self.string(name);
        let nsv = self.r(n);
        self.pop_to(n);
        let a = self.alloc(TY_KW, 3);
        if a == 0 {
            return NIL;
        }
        let v = Value::heap(a);
        self.gc.set_slot(a, 0, nsv);
        self.gc.set_slot(a, 1, namev);
        self.gc.set_slot(a, 2, Value::fixnum(h as i32 as i64));
        self.intern_into(INTERN_KW, h, v);
        v
    }

    // --- symbols -----------------------------------------------------------

    pub fn symbol(&mut self, ns: Option<&str>, name: &str) -> Value {
        let h = hash::hash_symbol(ns, name);
        let existing = {
            let sp = &self.gc.sp;
            self.roots.interns[INTERN_SYM].lookup(h, |v| {
                v.is_heap()
                    && ty(sp, v.as_heap()) == TY_SYM
                    && slot(sp, v.as_heap(), 2).is_nil() // no metadata
                    && str_eq_at(sp, slot(sp, v.as_heap(), 0), ns)
                    && str_eq_at(sp, slot(sp, v.as_heap(), 1), Some(name))
            })
        };
        if let Ok(v) = existing {
            return v;
        }
        let nsv = match ns {
            Some(s) => self.string(s),
            None => NIL,
        };
        let n = self.push(nsv);
        let namev = self.string(name);
        let nsv = self.r(n);
        self.pop_to(n);
        let a = self.alloc(TY_SYM, 4);
        if a == 0 {
            return NIL;
        }
        let v = Value::heap(a);
        self.gc.set_slot(a, 0, nsv);
        self.gc.set_slot(a, 1, namev);
        self.gc.set_slot(a, 2, NIL); // meta
        self.gc.set_slot(a, 3, Value::fixnum(h as i32 as i64));
        self.intern_into(INTERN_SYM, h, v);
        v
    }

    // --- predicates and accessors -----------------------------------------

    pub fn is_keyword(&self, v: Value) -> bool {
        v.is_inline_kw() || (v.is_heap() && ty(&self.gc.sp, v.as_heap()) == TY_KW)
    }
    pub fn is_symbol(&self, v: Value) -> bool {
        v.is_heap() && ty(&self.gc.sp, v.as_heap()) == TY_SYM
    }

    /// The `name` part of a string, keyword or symbol, as a string `Value`.
    pub fn name_of(&mut self, v: Value) -> Value {
        if v.is_inline_kw() {
            let mut b = [0u8; INLINE_MAX];
            return Value::inline_str(v.inline_bytes(&mut b));
        }
        if v.is_heap() {
            match ty(&self.gc.sp, v.as_heap()) {
                TY_KW | TY_SYM => return slot(&self.gc.sp, v.as_heap(), 1),
                _ => {}
            }
        }
        v
    }

    /// The `namespace` part, or `nil`.
    pub fn ns_of(&self, v: Value) -> Value {
        if v.is_inline_kw() {
            return NIL;
        }
        if v.is_heap() {
            match ty(&self.gc.sp, v.as_heap()) {
                TY_KW | TY_SYM => return slot(&self.gc.sp, v.as_heap(), 0),
                _ => {}
            }
        }
        NIL
    }

    pub fn keyword_hash(&self, v: Value) -> u32 {
        if v.is_inline_kw() {
            let mut b = [0u8; INLINE_MAX];
            let name = core::str::from_utf8(v.inline_bytes(&mut b)).unwrap_or("");
            hash::hash_keyword(None, name)
        } else {
            slot(&self.gc.sp, v.as_heap(), 2).as_fixnum() as i32 as u32
        }
    }
    pub fn symbol_hash(&self, v: Value) -> u32 {
        slot(&self.gc.sp, v.as_heap(), 3).as_fixnum() as i32 as u32
    }

    /// Build a string from pieces without an intermediate allocation on the
    /// flint heap. Used by `str`, `subs`, and the printer.
    pub fn string_from_parts(&mut self, parts: &[&str]) -> Value {
        let total: usize = parts.iter().map(|p| p.len()).sum();
        if total <= INLINE_MAX {
            let mut b = [0u8; INLINE_MAX];
            let mut i = 0;
            for p in parts {
                b[i..i + p.len()].copy_from_slice(p.as_bytes());
                i += p.len();
            }
            return Value::inline_str(&b[..total]);
        }
        if total as u32 <= INTERN_MAX {
            let mut b = [0u8; INTERN_MAX as usize];
            let mut i = 0;
            for p in parts {
                b[i..i + p.len()].copy_from_slice(p.as_bytes());
                i += p.len();
            }
            let s = core::str::from_utf8(&b[..total]).unwrap_or("");
            return self.string(s);
        }
        let a = self.alloc(TY_STR, total as u32);
        if a == 0 {
            return NIL;
        }
        let mut off = 0u32;
        let mut ascii = true;
        for p in parts {
            self.gc.sp.bytes_mut(a + STR_DATA + off, p.len() as u32).copy_from_slice(p.as_bytes());
            off += p.len() as u32;
            ascii &= p.is_ascii();
        }
        set_str_hash(&self.gc.sp, a, 0);
        set_str_ascii(&self.gc.sp, a, ascii);
        Value::heap(a)
    }
}

fn str_eq_at(sp: &crate::mem::Space, v: Value, s: Option<&str>) -> bool {
    match s {
        None => v.is_nil(),
        Some(s) => {
            if v.is_inline_str() {
                let mut b = [0u8; INLINE_MAX];
                v.inline_bytes(&mut b) == s.as_bytes()
            } else if v.is_heap() && ty(sp, v.as_heap()) == TY_STR {
                str_bytes(sp, v.as_heap()) == s.as_bytes()
            } else {
                false
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::rt::sbuf;

    #[test]
    fn short_strings_are_inline_and_canonical() {
        let mut rt = Rt::new();
        for s in ["", "a", "ab", "abc", "abcd", "abcde", "日本"] {
            if s.len() > INLINE_MAX {
                continue;
            }
            let v = rt.string(s);
            assert!(v.is_inline_str(), "{s:?} should be immediate");
            let mut b = sbuf();
            assert_eq!(rt.as_str(v, &mut b), Some(s));
            let v2 = rt.string(s);
            assert_eq!(v, v2, "inline is canonical, so bits are equal");
        }
    }

    #[test]
    fn medium_strings_are_interned_so_equality_is_a_bit_compare() {
        let mut rt = Rt::new();
        let a = rt.string("a moderately sized string");
        let b = rt.string("a moderately sized string");
        assert!(a.is_heap());
        assert_eq!(a, b, "interned: same object");
        let c = rt.string("a moderately sized strinG");
        assert_ne!(a, c);
        let mut buf = sbuf();
        assert_eq!(rt.as_str(a, &mut buf), Some("a moderately sized string"));
    }

    #[test]
    fn long_strings_are_not_interned() {
        let mut rt = Rt::new();
        let long = "x".repeat(INTERN_MAX as usize + 1);
        let a = rt.string(&long);
        let b = rt.string(&long);
        assert_ne!(a, b, "beyond the intern limit these are distinct objects");
        let mut buf = sbuf();
        assert_eq!(rt.as_str(a, &mut buf).map(|s| s.len()), Some(long.len()));
    }

    #[test]
    fn interning_survives_collection() {
        let mut rt = Rt::new();
        let keep = rt.string("a moderately sized string");
        let k = rt.push(keep);
        for _ in 0..8 {
            for i in 0..2000 {
                let _ = rt.string(&alloc::format!("garbage {i}"));
            }
            rt.gc.minor(&mut rt.roots);
        }
        rt.collect();
        let again = rt.string("a moderately sized string");
        assert_eq!(again, rt.r(k), "the live interned string was reused, not duplicated");
        let mut buf = sbuf();
        assert_eq!(rt.as_str(again, &mut buf), Some("a moderately sized string"));
    }

    #[test]
    fn intern_table_does_not_grow_without_bound() {
        let mut rt = Rt::new();
        for i in 0..20000 {
            let _ = rt.string(&alloc::format!("transient string {i}"));
        }
        rt.collect();
        let n = rt.roots.interns[INTERN_STR].count;
        assert!(n < 2000, "weak interning should have dropped dead entries, got {n}");
    }

    #[test]
    fn keywords_are_always_bit_comparable() {
        let mut rt = Rt::new();
        let a = rt.keyword(None, "a");
        assert!(a.is_inline_kw());
        assert_eq!(a, rt.keyword(None, "a"));
        let long = rt.keyword(None, "a-rather-long-keyword-name");
        assert!(long.is_heap());
        assert_eq!(long, rt.keyword(None, "a-rather-long-keyword-name"));
        let nsd = rt.keyword(Some("foo"), "bar");
        assert_eq!(nsd, rt.keyword(Some("foo"), "bar"));
        assert_ne!(nsd, rt.keyword(None, "bar"));
        assert_ne!(rt.keyword(None, "a"), rt.string("a"), "a keyword is not its name");
    }

    #[test]
    fn keyword_parts_and_hashes() {
        let mut rt = Rt::new();
        let mut b = sbuf();
        let k = rt.keyword(None, "abc");
        assert_eq!(rt.keyword_hash(k) as i32, -1232035677);
        let n = rt.name_of(k);
        assert_eq!(rt.as_str(n, &mut b), Some("abc"));
        assert!(rt.ns_of(k).is_nil());

        let k2 = rt.keyword(Some("foo"), "bar");
        assert_eq!(rt.keyword_hash(k2) as i32, -1386151538);
        let n2 = rt.name_of(k2);
        let mut b2 = sbuf();
        assert_eq!(rt.as_str(n2, &mut b2), Some("bar"));
        let ns2 = rt.ns_of(k2);
        let mut b3 = sbuf();
        assert_eq!(rt.as_str(ns2, &mut b3), Some("foo"));
    }

    #[test]
    fn symbols_intern_and_carry_their_hash() {
        let mut rt = Rt::new();
        let a = rt.symbol(None, "abc");
        assert_eq!(a, rt.symbol(None, "abc"));
        assert_eq!(rt.symbol_hash(a) as i32, 408495850);
        let b = rt.symbol(Some("foo"), "bar");
        assert_eq!(rt.symbol_hash(b) as i32, 254379989);
        assert!(rt.is_symbol(a) && !rt.is_keyword(a));
    }

    #[test]
    fn string_hash_is_cached_and_correct() {
        let mut rt = Rt::new();
        let v = rt.string("hello, world");
        assert_eq!(rt.string_hash(v) as i32, 136167191);
        assert_eq!(rt.string_hash(v) as i32, 136167191, "second call uses the cache");
        let inline = rt.string("abc");
        assert_eq!(rt.string_hash(inline) as i32, 74834163);
        let empty = rt.string("");
        assert_eq!(rt.string_hash(empty), 0);
    }

    #[test]
    fn chars_are_one_character_strings() {
        let mut rt = Rt::new();
        let mut b = sbuf();
        for c in ['a', 'é', '日', '\u{1F600}'] {
            let v = Value::char_value(c);
            assert!(rt.is_string(v));
            assert_eq!(rt.str_len(v), c.len_utf8() as u32);
            let s = rt.as_str(v, &mut b).unwrap();
            assert_eq!(s.chars().next(), Some(c));
            let b2 = sbuf();
            let _ = b2;
        }
        // ... and are `=` to the equivalent one-character string.
        let a = rt.string("a");
        assert_eq!(a, Value::char_value('a'));
    }

    #[test]
    fn string_from_parts_at_every_size_class() {
        let mut rt = Rt::new();
        let mut b = sbuf();
        let v = rt.string_from_parts(&["ab", "cd"]);
        assert!(v.is_inline_str());
        assert_eq!(rt.as_str(v, &mut b), Some("abcd"));

        let v = rt.string_from_parts(&["hello", ", ", "world"]);
        assert!(v.is_heap());
        let mut b2 = sbuf();
        assert_eq!(rt.as_str(v, &mut b2), Some("hello, world"));
        assert_eq!(v, rt.string("hello, world"), "goes through the intern table");

        let big = "y".repeat(100);
        let v = rt.string_from_parts(&[&big, "-tail"]);
        let mut b3 = sbuf();
        assert_eq!(rt.as_str(v, &mut b3).map(|s| s.len()), Some(105));
    }

    #[test]
    fn allocation_during_construction_does_not_lose_parts() {
        // keyword() allocates the ns string, then the name string, then the
        // object; a collection in the middle must not lose the earlier parts.
        let mut rt = Rt::new();
        rt.gc.stress = true;
        for i in 0..200 {
            let ns = alloc::format!("namespace-number-{i}");
            let name = alloc::format!("name-number-{i}");
            let k = rt.keyword(Some(&ns), &name);
            let nv = rt.ns_of(k);
            let mut b = sbuf();
            assert_eq!(rt.as_str(nv, &mut b), Some(ns.as_str()));
            let nm = rt.name_of(k);
            let mut b2 = sbuf();
            assert_eq!(rt.as_str(nm, &mut b2), Some(name.as_str()));
        }
    }
}
