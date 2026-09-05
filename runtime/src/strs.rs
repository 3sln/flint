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
        // A LONG NON-ASCII STRING ARRIVES AS A TREE, not as one flat run.
        //
        // The tier transitions in `rope.rs` fire on CONCATENATION -- `flat (+)
        // flat -> rope` past the threshold -- so a string that arrives whole
        // from outside never became a rope however big it was. `0011` then
        // says, correctly, that "a flat string carries one total count, which
        // does not locate code point k", and leaves the ASCII flag to cover the
        // case where it does not have to. Nothing covered a flat string that is
        // NOT ASCII, and every reader in the language indexes by code point --
        // so one `ä` in a 115 KB document made the whole document quadratic.
        //
        // ASCII strings stay flat. For them a code-point index IS a byte index,
        // there is nothing to locate, and a tree would be pure overhead.
        // The THRESHOLD is `FLAT_MAX`, the same one the concatenation tiering
        // uses, not the leaf size. A 200-byte string as a two-leaf tree is
        // overhead for a scan `0011` already accepts as bounded; what has to be
        // a tree is the string big enough for that scan to be the quadratic.
        if !s.is_ascii() && s.len() as u32 > crate::rope::FLAT_MAX {
            return self.indexed_string(s);
        }
        self.flat_string(s)
    }

    /// A string that is GUARANTEED contiguous: inline, or one flat run.
    ///
    /// The difference from `string` is the whole of the tiering, and it has to
    /// be said out loud at the call site. `flatten` used to call `string`, and
    /// once `string` started answering a TREE for a long non-ASCII input,
    /// flattening a rope produced another rope: `RP_FLAT` held a tree, and
    /// every caller that had flattened precisely to get contiguous bytes was
    /// handed something that was not.
    pub(crate) fn contiguous_string(&mut self, s: &str) -> Value {
        if s.len() <= INLINE_MAX {
            return Value::inline_str(s.as_bytes());
        }
        self.flat_string(s)
    }

    /// One contiguous run. The leaf tier.
    fn flat_string(&mut self, s: &str) -> Value {
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

    /// A balanced tree over `INDEX_LEAF`-sized pieces, split on code-point
    /// boundaries.
    ///
    /// The node array IS the sparse code-point index: each node carries its
    /// subtree's code-point count, so locating code point `k` is a descent plus
    /// a scan bounded by one leaf. Both are properties of the string, which is
    /// what makes the cost PREDICTABLE -- it does not depend on what was
    /// indexed before, and it does not change because another executor
    /// collected.
    fn indexed_string(&mut self, s: &str) -> Value {
        let base = self.mark();
        let mut n = 0usize;
        let mut start = 0usize;
        while start < s.len() {
            // Split on a CHARACTER boundary at or before the limit: a leaf that
            // ended mid-code-point would make every count downstream wrong.
            let mut end = (start + crate::rope::INDEX_LEAF as usize).min(s.len());
            while end > start && !s.is_char_boundary(end) {
                end -= 1;
            }
            let piece = self.flat_string(&s[start..end]);
            if piece.is_nil() {
                self.pop_to(base);
                return NIL;
            }
            self.push(piece);
            n += 1;
            start = end;
        }
        let v = self.rope_from_roots(base, n as u32);
        self.pop_to(base);
        v
    }

    /// ASCII case folding, as one pass over the bytes.
    ///
    /// It was a per-CHARACTER loop in `clojure.string`: `nth`, `code-point-at`,
    /// two comparisons, `from-code-point` and a `conj` into a vector for every
    /// character, then `str-join`. Measured at 11.4 ms for a 32 799-character
    /// corpus -- 346 ns per character -- which was 21% of the whole
    /// word-frequency benchmark and more than the regex engine it was being
    /// blamed on.
    ///
    /// Non-ASCII is left alone rather than half-done: Unicode case folding is
    /// not a byte operation, and pretending otherwise would be worse than
    /// saying so. `clojure.string` keeps its own loop for that case.
    pub fn change_case(&mut self, s: Value, upper: bool) -> Value {
        if !self.is_string(s) {
            return self.throw_str("ClassCastException", "not a string");
        }
        let n = self.str_len(s);
        // CHARGED BEFORE THE WORK, and refused if it cannot be paid for. `n` is
        // known here, so this is a better bound than a per-iteration tick: it
        // never begins work the budget cannot cover, and costs nothing inside
        // the loop. `charge_bytes` alone billed 48 097 steps past an exhausted
        // budget, because billing is not bounding.
        if !self.charge_checked((n as u64 / 8) + 1, "case conversion") {
            return crate::value::NIL;
        }
        let mut buf = crate::rt::sbuf();
        let mut out: alloc::vec::Vec<u8> = {
            let b: &[u8] = if s.is_inline_str() {
                s.inline_bytes(&mut buf)
            } else {
                str_bytes(&self.gc.sp, s.as_heap())
            };
            if !b.is_ascii() {
                return NIL;
            }
            b.to_vec()
        };
        for c in out.iter_mut() {
            if upper {
                if (*c).is_ascii_lowercase() {
                    *c -= 32;
                }
            } else if (*c).is_ascii_uppercase() {
                *c += 32;
            }
        }
        let owned = core::str::from_utf8(&out).unwrap_or("");
        self.string(owned)
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
        let want = s.as_bytes().to_vec();
        let matches = move |sp: &crate::mem::Space, v: Value| {
            v.is_heap() && ty(sp, v.as_heap()) == TY_STR && str_bytes(sp, v.as_heap()) == &want[..]
        };
        if let Some(v) = self.intern_probe(INTERN_STR, h, &matches) {
            return v;
        }

        // Allocated OUTSIDE the lock, which is what keeps the lock off every
        // allocation path and so keeps a parked thread from ever holding one.
        let v = self.raw_string(s);
        if v.is_nil() {
            return v;
        }
        set_str_hash(&self.gc.sp, v.as_heap(), if h == 0 { 1 } else { h });

        let base = self.mark();
        self.push(v);
        let out = self.intern_publish(INTERN_STR, h, base, &matches);
        self.pop_to(base);
        out
    }

    /// Probe an intern table under its lock.
    ///
    /// Nothing needs rooting here: no allocation happens, and the lock can only
    /// park before anything is live.
    pub(crate) fn intern_probe(
        &mut self,
        table: usize,
        h: u32,
        matches: impl Fn(&crate::mem::Space, Value) -> bool,
    ) -> Option<Value> {
        self.lock_intern(table);
        let found = {
            let sp = &self.gc.sp;
            self.roots.shared.interns[table].lookup(h, |v| matches(sp, v))
        };
        self.unlock_intern(table);
        found.ok()
    }

    /// Publish a freshly built value into an intern table, or take the one
    /// another executor published while this one was building it.
    ///
    /// `rooted` is the root-stack index of the new value, and it MUST be
    /// rooted: taking the lock can park, and a `Value` in a Rust local does not
    /// survive a safepoint.
    ///
    /// The re-probe is not an optimisation. Two interned copies of one string
    /// is a correctness bug rather than a wasted allocation: `eq` reads "both
    /// interned and not bit-equal" as NOT EQUAL, so the copies would compare
    /// unequal while reading identically -- and symbol equality is slot
    /// equality on those same strings, so it would spread.
    ///
    /// This exists once rather than three times on purpose. `string`, `keyword`
    /// and `symbol` have had the same four lines and the same rooting bug
    /// before (see `symbol`); a protocol with three copies is a protocol with
    /// three chances to diverge.
    pub(crate) fn intern_publish(
        &mut self,
        table: usize,
        h: u32,
        rooted: usize,
        matches: impl Fn(&crate::mem::Space, Value) -> bool,
    ) -> Value {
        self.lock_intern(table);
        let again = {
            let sp = &self.gc.sp;
            self.roots.shared.interns[table].lookup(h, |v| matches(sp, v))
        };
        let out = match again {
            // Someone got there first. Theirs is the interned one; ours is
            // nursery garbage and costs a collection nothing.
            Ok(existing) => existing,
            Err(idx) => {
                let mine = self.r(rooted);
                if self.roots.shared.interns[table].needs_grow() {
                    self.roots.shared.interns[table].grow();
                    // `grow` invalidates the index, so re-probe for a slot.
                    if let Err(i2) = self.roots.shared.interns[table].lookup(h, |_| false) {
                        self.roots.shared.interns[table].insert_at(i2, h, mine);
                    }
                } else {
                    self.roots.shared.interns[table].insert_at(idx, h, mine);
                }
                mine
            }
        };
        self.unlock_intern(table);
        out
    }

    /// Insert without re-checking for an equal entry.
    ///
    /// Only for tables keyed by an identity that cannot collide -- ports are
    /// keyed by their own id -- where "someone else already made this" cannot
    /// happen. Everything content-keyed must use `intern_publish`.
    pub(crate) fn intern_into(&mut self, table: usize, h: u32, v: Value) {
        let base = self.mark();
        self.push(v);
        self.lock_intern(table);
        if self.roots.shared.interns[table].needs_grow() {
            self.roots.shared.interns[table].grow();
        }
        // Re-probe: `grow` invalidates any index we might have had, and an
        // allocation may have run a collection that rehashed the table.
        if let Err(idx) = self.roots.shared.interns[table].lookup(h, |_| false) {
            let mine = self.r(base);
            self.roots.shared.interns[table].insert_at(idx, h, mine);
        }
        self.unlock_intern(table);
        self.pop_to(base);
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
        let (wns, wname) = (ns.map(alloc::string::String::from), alloc::string::String::from(name));
        let matches = move |sp: &crate::mem::Space, v: Value| {
            v.is_heap()
                && ty(sp, v.as_heap()) == TY_KW
                && str_eq_at(sp, slot(sp, v.as_heap(), 0), wns.as_deref())
                && str_eq_at(sp, slot(sp, v.as_heap(), 1), Some(wname.as_str()))
        };
        if let Some(v) = self.intern_probe(INTERN_KW, h, &matches) {
            return v;
        }
        // Both strings rooted across the `alloc`, and released only after the
        // last slot is written -- see `symbol` below for what this cost when
        // it was not. The two functions had the same four lines and the same
        // bug; a keyword is rarer to intern under memory pressure, which is
        // the only reason `symbol` surfaced first.
        let base = self.mark();
        let nsv = match ns {
            Some(s) => self.string(s),
            None => NIL,
        };
        self.push(nsv);
        let namev = self.string(name);
        self.push(namev);
        let a = self.alloc(TY_KW, 3);
        if a == 0 {
            self.pop_to(base);
            return NIL;
        }
        let v = Value::heap(a);
        // Read both roots BEFORE touching `gc`. Two-phase borrows covered this
        // while `gc` was a plain field; through `DerefMut` it is a method call,
        // and the compiler is right that the old spelling read `self` while
        // `self` was mutably borrowed.
        let (nsr, namer) = (self.r(base), self.r(base + 1));
        self.set_slot(a, 0, nsr);
        self.set_slot(a, 1, namer);
        self.set_slot(a, 2, Value::fixnum(h as i32 as i64));
        let mine = self.push(v);
        let out = self.intern_publish(INTERN_KW, h, mine, &matches);
        self.pop_to(base);
        out
    }

    // --- symbols -----------------------------------------------------------

    pub fn symbol(&mut self, ns: Option<&str>, name: &str) -> Value {
        let h = hash::hash_symbol(ns, name);
        let (wns, wname) = (ns.map(alloc::string::String::from), alloc::string::String::from(name));
        let matches = move |sp: &crate::mem::Space, v: Value| {
            v.is_heap()
                && ty(sp, v.as_heap()) == TY_SYM
                && slot(sp, v.as_heap(), 2).is_nil() // no metadata
                && str_eq_at(sp, slot(sp, v.as_heap(), 0), wns.as_deref())
                && str_eq_at(sp, slot(sp, v.as_heap(), 1), Some(wname.as_str()))
        };
        if let Some(v) = self.intern_probe(INTERN_SYM, h, &matches) {
            return v;
        }
        // BOTH strings stay rooted across the `alloc` below, and the roots are
        // released only after the last slot is written.
        //
        // They were not. `nsv` was pushed, `namev` was left in a Rust local,
        // and `pop_to` dropped the root BEFORE `alloc` -- so a collection
        // triggered by allocating the symbol moved both strings and the two
        // `set_slot` calls wrote pre-flip addresses into it. The interned
        // symbol then held two dangling pointers, and because interning is a
        // WEAK table that survives collections, the damage outlived the
        // collection that caused it.
        //
        // It surfaced far away and much later: `arena::alloc` trapping while
        // rendering the answer, with the diagnostics counters reading as four
        // gigabytes. `stat_stale_set` named it -- stale AS IT IS WRITTEN --
        // and the native build's `debug_assert` in `obj::slot` caught the same
        // thing as a forwarded pointer being read back out.
        let base = self.mark();
        let nsv = match ns {
            Some(s) => self.string(s),
            None => NIL,
        };
        self.push(nsv);
        let namev = self.string(name);
        self.push(namev);
        let a = self.alloc(TY_SYM, 4);
        if a == 0 {
            self.pop_to(base);
            return NIL;
        }
        let v = Value::heap(a);
        // Read both roots BEFORE touching `gc`. Two-phase borrows covered this
        // while `gc` was a plain field; through `DerefMut` it is a method call,
        // and the compiler is right that the old spelling read `self` while
        // `self` was mutably borrowed.
        let (nsr, namer) = (self.r(base), self.r(base + 1));
        self.set_slot(a, 0, nsr);
        self.set_slot(a, 1, namer);
        self.set_slot(a, 2, NIL); // meta
        self.set_slot(a, 3, Value::fixnum(h as i32 as i64));
        let mine = self.push(v);
        let out = self.intern_publish(INTERN_SYM, h, mine, &matches);
        self.pop_to(base);
        out
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
        // `str-join` over a big sequence is one bytecode instruction and work
        // proportional to the bytes, so it pays for them.
        self.charge_bytes(total as u32);
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
            self.gc.sp.bytes_mut(a + STR_DATA + off as crate::mem::Addr, p.len() as u32).copy_from_slice(p.as_bytes());
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
        let n = rt.roots.shared.interns[INTERN_STR].count;
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

    #[cfg(feature = "diagnostics")]
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

#[cfg(test)]
mod intern_stress {
    use super::*;
    use alloc::format;
    use alloc::vec::Vec;

    /// `eq` takes `len <= INTERN_MAX && !bit-equal => not equal` as a fast path.
    /// That is only sound if every such string really is interned and stays
    /// interned, so this asserts the invariant across collections: re-creating a
    /// string that is still reachable must give back the same object.
    #[test]
    fn interned_strings_stay_canonical_across_collections() {
        let mut rt = Rt::new();
        let names: Vec<alloc::string::String> =
            (0..1500).map(|i| format!("flint/name-{:05}", i)).collect();
        for n in &names {
            let v = rt.string(n);
            rt.roots.shared.globals.push(crate::gc::GlobalSlot::new(v));
        }
        // Garbage of the same shape, so the weak table is churned and the
        // collector has real work to do.
        for round in 0..6 {
            for i in 0..1500 {
                let _ = rt.string(&format!("garbage-{}-{:05}", round, i));
            }
            rt.collect();
        }
        for (i, n) in names.iter().enumerate() {
            let again = rt.string(n);
            assert_eq!(
                again,
                rt.roots.shared.globals[i].get(),
                "re-interning {} produced a second object",
                n
            );
        }
    }
}

#[cfg(test)]
mod intern_tests {
    use super::*;

    /// Interning under GC stress, which is what caught this.
    ///
    /// `symbol` and `keyword` each allocate two strings and then the object
    /// that holds them. Under stress every allocation collects, so the
    /// symbol's own `alloc` moves both strings -- and if either was in a Rust
    /// local rather than on the root stack, the address written into the
    /// symbol is the one it had before the flip.
    ///
    /// It is a WEAK table, so the damage outlives the collection that caused
    /// it and surfaces somewhere else entirely: the first report was
    /// `arena::alloc` trapping while rendering an answer, with the diagnostics
    /// counters reading four gigabytes.
    #[test]
    #[cfg(feature = "diagnostics")]
    fn interning_under_gc_stress_keeps_its_strings() {
        let mut rt = Rt::new();
        rt.gc.stress = true;
        for i in 0..200u32 {
            let ns = alloc::format!("some.namespace.{i}");
            let name = alloc::format!("a-name-{i}");
            let base = rt.mark();

            let sym = rt.symbol(Some(&ns), &name);
            rt.push(sym);
            let kw = rt.keyword(Some(&ns), &name);
            rt.push(kw);

            // Read the parts BACK. A stale slot is a forwarded pointer, and
            // reading one is what the native build asserts on.
            let s = rt.r(base);
            let mut b = crate::rt::sbuf();
            let got_ns: alloc::string::String = {
                let v = crate::obj::slot(&rt.gc.sp, s.as_heap(), 0);
                rt.as_str(v, &mut b).unwrap_or("").into()
            };
            assert_eq!(got_ns, ns, "symbol namespace after {i} rounds");
            let mut b2 = crate::rt::sbuf();
            let got_name: alloc::string::String = {
                let v = crate::obj::slot(&rt.gc.sp, s.as_heap(), 1);
                rt.as_str(v, &mut b2).unwrap_or("").into()
            };
            assert_eq!(got_name, name, "symbol name after {i} rounds");

            let k = rt.r(base + 1);
            let mut b3 = crate::rt::sbuf();
            let kw_name: alloc::string::String = {
                let v = crate::obj::slot(&rt.gc.sp, k.as_heap(), 1);
                rt.as_str(v, &mut b3).unwrap_or("").into()
            };
            assert_eq!(kw_name, name, "keyword name after {i} rounds");
            rt.pop_to(base);
        }
    }
}
