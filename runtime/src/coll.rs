//! Generic collection operations: the polymorphic dispatch that `conj`, `get`,
//! `assoc`, `nth`, `count` and friends need, plus transients for maps and sets,
//! atoms, metadata and number formatting.

use crate::hash;
use crate::obj::*;
use crate::rt::Rt;
use crate::value::{Value, INLINE_MAX, NIL, NOT_FOUND};

/// Substring search over bytes. Naive, which is what the gas charge above is
/// priced for, and enough for the one-character separators that dominate.
fn find_bytes(h: &[u8], n: &[u8]) -> Option<usize> {
    if n.is_empty() {
        return Some(0);
    }
    if n.len() > h.len() {
        return None;
    }
    let first = n[0];
    let last = h.len() - n.len();
    let mut i = 0;
    while i <= last {
        if h[i] == first && &h[i..i + n.len()] == n {
            return Some(i);
        }
        i += 1;
    }
    None
}

impl Rt {
    // --- count -------------------------------------------------------------

    pub fn count_of(&mut self, v: Value) -> u32 {
        if v.is_nil() {
            return 0;
        }
        if self.is_string(v) {
            return self.char_count(v);
        }
        if !v.is_heap() {
            self.throw_str("UnsupportedOperationException", "count not supported on this type");
            return 0;
        }
        match ty(&self.gc.sp, v.as_heap()) {
            TY_VEC => self.vec_count(v),
            TY_MAPENTRY => 2,
            crate::obj::TY_TAGGED => 2,
            crate::obj::TY_TABLE => self.table_count(v),
            crate::obj::TY_TTABLE => self.ttable_count(v),
            // A ref counts its COLUMNS, because it is a map of them.
            crate::obj::TY_TABLEREF => {
                let s = self.slot(v, crate::table::RF_SCHEMA);
                self.schema_len(s)
            }
            TY_ARRAYMAP | TY_HASHMAP => self.map_count(v),
            TY_SET => self.set_count(v),
            TY_BYTES | TY_BROPE => self.b_count(v),
            TY_TVEC => self.tvec_count(v),
            TY_TMAP => self.slot(v, 0).as_fixnum() as u32,
            TY_TSET => {
                let m = self.slot(v, 0);
                self.slot(m, 0).as_fixnum() as u32
            }
            TY_EMPTY_LIST => 0,
            _ => self.seq_count(v),
        }
    }

    /// `count` on a string is in **code points**, not UTF-16 code units.
    /// Clojure counts UTF-16, so an astral character counts 2 there and 1 here.
    /// This is a deliberate divergence, recorded in the README.
    pub fn char_count(&self, v: Value) -> u32 {
        if self.is_rope(v) {
            return self.s_count(v);
        }
        if !v.is_inline_str() && str_is_ascii(&self.gc.sp, v.as_heap()) {
            return len(&self.gc.sp, v.as_heap());
        }
        let mut buf = crate::rt::sbuf();
        let bytes: &[u8] = if v.is_inline_str() {
            v.inline_bytes(&mut buf)
        } else {
            str_bytes(&self.gc.sp, v.as_heap())
        };
        bytes.iter().filter(|b| (**b & 0xC0) != 0x80).count() as u32
    }

    /// True when a code-point index into `s` is also a byte index.
    #[inline]
    fn str_indexable(&self, s: Value) -> bool {
        if self.is_rope(s) {
            // A rope is never indexed directly -- every caller flattens first --
            // so this answers about the tier it will become.
            return self.s_ascii(s);
        }
        if s.is_inline_str() {
            let mut b = crate::rt::sbuf();
            s.inline_bytes(&mut b).is_ascii()
        } else {
            s.is_heap()
                && ty(&self.gc.sp, s.as_heap()) == TY_STR
                && str_is_ascii(&self.gc.sp, s.as_heap())
        }
    }

    // --- conj / assoc / get -------------------------------------------------

    pub fn conj(&mut self, coll: Value, x: Value) -> Value {
        if coll.is_nil() {
            let e = self.empty_list();
            return self.cons(x, e);
        }
        match ty(&self.gc.sp, coll.as_heap()) {
            TY_VEC => self.vec_conj(coll, x),
            TY_SET => self.set_conj(coll, x),
            TY_ARRAYMAP | TY_HASHMAP => {
                // conj on a map takes a map entry or a 2-element vector.
                if x.is_heap() && matches!(ty(&self.gc.sp, x.as_heap()), TY_MAPENTRY | TY_VEC) {
                    let (k, v) = (self.slot_or_nth(x, 0), self.slot_or_nth(x, 1));
                    self.map_assoc(coll, k, v)
                } else if self.is_map(x) {
                    let base = self.mark();
                    let ci = self.push(coll);
                    let mut st = ci;
                    self.map_for_each(x, &mut st, &mut |rt, k, v, ci| {
                        let nm = rt.map_assoc(rt.r(*ci), k, v);
                        rt.set_r(*ci, nm);
                    });
                    let out = self.r(ci);
                    self.pop_to(base);
                    out
                } else {
                    self.throw_str("IllegalArgumentException", "conj on a map wants a map entry")
                }
            }
            // `conj` on a table APPENDS A ROW, which is what conj means on
            // every indexed collection here. The default arm conses, and a
            // table consed onto is not a table.
            crate::obj::TY_TABLE => self.table_conj(coll, x),
            // A MAP ENTRY is a vector, so `conj` APPENDS rather than consing:
            // `(conj (first {:a 1}) 9)` is `[:a 1 9]`, as in Clojure. It falls
            // through to the cons arm otherwise, because a map entry is also
            // sequential -- both readings are available and Clojure picks the
            // vector one.
            TY_MAPENTRY => {
                let base = self.mark();
                let xi = self.push(x);
                let v = self.map_entry_as_vec(coll);
                let vi = self.push(v);
                let (vv, xv) = (self.r(vi), self.r(xi));
                let out = self.vec_conj(vv, xv);
                self.pop_to(base);
                out
            }
            _ => self.cons(x, coll),
        }
    }

    /// `first_foreign_key` walks a map's entries and needs the key half of
    /// one; this is the same two-shapes-of-entry rule the rest of `conj` uses.
    pub(crate) fn slot_or_nth_pub(&mut self, v: Value, i: u32) -> Value {
        self.slot_or_nth(v, i)
    }

    fn slot_or_nth(&mut self, v: Value, i: u32) -> Value {
        if ty(&self.gc.sp, v.as_heap()) == TY_MAPENTRY {
            self.slot(v, i)
        } else {
            self.vec_nth(v, i, NIL)
        }
    }

    pub fn assoc(&mut self, coll: Value, k: Value, v: Value) -> Value {
        if coll.is_nil() {
            let e = self.empty_map();
            return self.map_assoc(e, k, v);
        }
        match ty(&self.gc.sp, coll.as_heap()) {
            TY_ARRAYMAP | TY_HASHMAP => self.map_assoc(coll, k, v),
            TY_VEC => match self.as_i64(k) {
                Some(i) if i >= 0 && i as u32 <= self.vec_count(coll) => {
                    self.vec_assoc(coll, i as u32, v)
                }
                _ => self.throw_str("IndexOutOfBoundsException", "assoc index out of range"),
            },
            // A tagged literal has exactly two slots and there is nowhere for a
            // third to go, so `assoc` on either key PRESERVES the type and
            // anything else is refused (`doc/decisions/0034`). The alternative
            // is silently promoting to a map and losing the taggedness, which
            // is the quiet coercion this codebase refuses elsewhere.
            crate::obj::TY_TAGGED => {
                if k == self.keyword(None, "tag") {
                    if !self.is_symbol(v) {
                        return self.throw_str(
                            "IllegalArgumentException",
                            "a tagged literal's :tag must be a symbol",
                        );
                    }
                    let form = self.slot(coll, 1);
                    self.new_tagged(v, form)
                } else if k == self.keyword(None, "form") {
                    let tag = self.slot(coll, 0);
                    self.new_tagged(tag, v)
                } else {
                    // The KEY is named, because "wrong key" without saying
                    // which one is the error message this codebase keeps
                    // replacing (`doc/decisions/0032`).
                    let nm = self.name_of(k);
                    let mut b = crate::rt::sbuf();
                    let shown: alloc::string::String =
                        self.as_str(nm, &mut b).unwrap_or("?").into();
                    let msg = alloc::format!(
                        "a tagged literal has :tag and :form and nothing else, so it cannot \
                         take :{shown}"
                    );
                    self.throw_str("IllegalArgumentException", &msg)
                }
            }
            // A table is indexed by ROW and its schema is CLOSED, so the
            // refusals live in `table_assoc` where the schema is
            // (`doc/decisions/0026`).
            crate::obj::TY_TABLE => self.table_assoc(coll, k, v),
            // A ref is a VIEW. Changing it produces an independent MAP, and
            // neither the chunk nor the table it came from moves -- so the
            // schema does not constrain the result, because the result is no
            // longer a row.
            crate::obj::TY_TABLEREF => self.ref_assoc(coll, k, v),
            // A MAP ENTRY is a vector, so it is associative and `assoc`
            // indexes it. Without this it would claim `associative?` -- which
            // is `(or map? vector?)` -- and then refuse.
            TY_MAPENTRY => {
                let base = self.mark();
                let ki = self.push(k);
                let vi = self.push(v);
                let ev = self.map_entry_as_vec(coll);
                let ei = self.push(ev);
                let (evv, kv, vv) = (self.r(ei), self.r(ki), self.r(vi));
                let out = self.assoc(evv, kv, vv);
                self.pop_to(base);
                out
            }
            _ => self.throw_str("ClassCastException", "assoc needs an associative collection"),
        }
    }

    pub fn get(&mut self, coll: Value, k: Value, dflt: Value) -> Value {
        if coll.is_nil() {
            return dflt;
        }
        if self.is_string(coll) {
            return match self.as_i64(k) {
                Some(i) if i >= 0 => self.char_at(coll, i as u32, dflt),
                _ => dflt,
            };
        }
        if !coll.is_heap() {
            return dflt;
        }
        match ty(&self.gc.sp, coll.as_heap()) {
            TY_ARRAYMAP | TY_HASHMAP => self.map_get(coll, k, dflt),
            TY_SET => self.set_get(coll, k, dflt),
            TY_VEC => match self.as_i64(k) {
                Some(i) if i >= 0 => self.vec_nth(coll, i as u32, dflt),
                _ => dflt,
            },
            TY_MAPENTRY => match self.as_i64(k) {
                Some(0) => self.slot(coll, 0),
                Some(1) => self.slot(coll, 1),
                _ => dflt,
            },
            // A tagged literal reads like a two-key map, so `(:tag x)` and
            // `(get x :form)` work and nothing treating one as a map has to
            // learn a different way in (`doc/decisions/0034`).
            // A table indexes by ROW and hands back a ref, which materialises
            // nothing (`doc/decisions/0026`).
            crate::obj::TY_TABLE => match self.as_i64(k) {
                Some(i) if i >= 0 => {
                    let r = self.table_ref(coll, i as u32);
                    if r.is_nil() { dflt } else { r }
                }
                _ => dflt,
            },
            crate::obj::TY_TABLEREF => self.ref_get(coll, k, dflt),
            crate::obj::TY_TAGGED => {
                if k == self.keyword(None, "tag") {
                    self.slot(coll, 0)
                } else if k == self.keyword(None, "form") {
                    self.slot(coll, 1)
                } else {
                    dflt
                }
            }
            TY_BYTES | TY_BROPE => match self.as_i64(k) {
                Some(i) if i >= 0 => self.b_at(coll, i as u32, dflt),
                _ => dflt,
            },
            TY_TVEC => match self.as_i64(k) {
                Some(i) if i >= 0 => self.tvec_nth(coll, i as u32, dflt),
                _ => dflt,
            },
            TY_TMAP => self.tmap_get(coll, k, dflt),
            TY_TSET => {
                let m = self.slot(coll, 0);
                self.tmap_get(m, k, dflt)
            }
            _ => dflt,
        }
    }

    pub fn contains(&mut self, coll: Value, k: Value) -> bool {
        if coll.is_nil() {
            return false;
        }
        match ty(&self.gc.sp, coll.as_heap()) {
            TY_ARRAYMAP | TY_HASHMAP => self.map_contains(coll, k),
            TY_SET => self.set_contains(coll, k),
            TY_VEC => match self.as_i64(k) {
                Some(i) => i >= 0 && (i as u32) < self.vec_count(coll),
                None => false,
            },
            _ => self.get(coll, k, NOT_FOUND) != NOT_FOUND,
        }
    }

    pub fn nth(&mut self, coll: Value, idx: Value, dflt: Option<Value>) -> Value {
        let i = match self.as_i64(idx) {
            Some(i) => i,
            None => return self.throw_str("IllegalArgumentException", "nth index must be an integer"),
        };
        if i < 0 {
            return match dflt {
                Some(d) => d,
                None => self.throw_str("IndexOutOfBoundsException", "negative index"),
            };
        }
        let i = i as u32;
        if coll.is_nil() {
            return dflt.unwrap_or(NIL);
        }
        if self.is_string(coll) {
            // NOT_FOUND as the probe: this arm must tell "past the end" from
            // "the caller's default is nil", and must RAISE when there is no
            // default at all. NOT_FOUND is the one value a character cannot be.
            let c = self.char_at(coll, i, crate::value::NOT_FOUND);
            if c != crate::value::NOT_FOUND {
                return c;
            }
            return match dflt {
                Some(d) => d,
                None => self.throw_str("IndexOutOfBoundsException", "string index out of range"),
            };
        }
        // A byte string indexes like a vector of small integers, which is
        // what it is. `count` and `get` learned this above; `nth` is a
        // separate path and forgetting it here read back as
        // `IndexOutOfBoundsException` on a byte string that was plainly long
        // enough.
        if self.is_bytes(coll) {
            let b = self.b_at(coll, i, crate::value::NOT_FOUND);
            if b != crate::value::NOT_FOUND {
                return b;
            }
            return match dflt {
                Some(d) => d,
                None => self.throw_str("IndexOutOfBoundsException", "byte index out of range"),
            };
        }
        if coll.is_heap() && ty(&self.gc.sp, coll.as_heap()) == TY_VEC {
            // NOT_FOUND as the probe, because this arm has to tell "out of
            // range" from "the element is nil" -- the caller's `dflt` may
            // itself be nil, and with no `dflt` at all it must raise.
            let x = self.vec_nth(coll, i, crate::value::NOT_FOUND);
            if x != crate::value::NOT_FOUND {
                return x;
            }
            return match dflt {
                Some(d) => d,
                None => self.throw_str("IndexOutOfBoundsException", "index out of range"),
            };
        }
        // Walk. O(n), as it is in Clojure for a seq.
        let base = self.mark();
        let s = self.seq(coll);
        let si = self.push(s);
        let mut k = 0u32;
        let out = loop {
            if self.r(si).is_nil() {
                break match dflt {
                    Some(d) => d,
                    None => self.throw_str("IndexOutOfBoundsException", "index out of range"),
                };
            }
            if k == i {
                break self.first(self.r(si));
            }
            let n = self.next(self.r(si));
            self.set_r(si, n);
            k += 1;
        };
        self.pop_to(base);
        out
    }

    pub fn pop_of(&mut self, coll: Value) -> Value {
        if coll.is_nil() {
            return self.throw_str("IllegalStateException", "cannot pop nil");
        }
        match ty(&self.gc.sp, coll.as_heap()) {
            TY_VEC => {
                if self.vec_count(coll) == 0 {
                    self.throw_str("IllegalStateException", "cannot pop an empty vector")
                } else {
                    self.vec_pop(coll)
                }
            }
            TY_EMPTY_LIST => self.throw_str("IllegalStateException", "cannot pop an empty list"),
            _ => self.rest(coll),
        }
    }

    pub fn peek_of(&mut self, coll: Value) -> Value {
        if coll.is_nil() {
            return NIL;
        }
        match ty(&self.gc.sp, coll.as_heap()) {
            TY_VEC => {
                let n = self.vec_count(coll);
                if n == 0 {
                    NIL
                } else {
                    self.vec_nth(coll, n - 1, NIL)
                }
            }
            _ => self.first(coll),
        }
    }

    pub fn empty_of(&mut self, coll: Value) -> Value {
        if !coll.is_heap() {
            return NIL;
        }
        match ty(&self.gc.sp, coll.as_heap()) {
            TY_VEC => self.empty_vec(),
            TY_ARRAYMAP | TY_HASHMAP => self.empty_map(),
            TY_SET => self.empty_set(),
            _ => self.empty_list(),
        }
    }

    // --- transients ---------------------------------------------------------

    pub fn to_transient(&mut self, v: Value) -> Value {
        if !v.is_heap() {
            let msg = alloc::format!("{} is not transientable", self.describe(v));
            return self.throw_str("ClassCastException", &msg);
        }
        match ty(&self.gc.sp, v.as_heap()) {
            TY_VEC => self.vec_transient(v),
            TY_ARRAYMAP | TY_HASHMAP => self.map_transient(v),
            // A ROW REF is a map, so it is transientable -- by MATERIALISING.
            // Without this arm it fell to the refusal below, and `merge`,
            // which builds a transient of its first source, could not take a
            // row as that source.
            //
            // The ports had it worse. They guard with `isMap`, which is true
            // for a ref, and then read the ref's slots as an array-map's: no
            // refusal, just garbage -- `(merge row {:z 9})` answered
            // `{:z 9, 1 2}`. A wrong answer beats no answer only if it is
            // right, and this one was neither.
            crate::obj::TY_TABLEREF => {
                let base = self.mark();
                let m = self.ref_to_map(v);
                let mi = self.push(m);
                let mv = self.r(mi);
                let out = self.map_transient(mv);
                self.pop_to(base);
                out
            }
            TY_SET => self.set_transient(v),
            crate::obj::TY_TABLE => self.table_transient(v),
            _ => {
                let msg = alloc::format!("{} is not transientable", self.describe(v));
                self.throw_str("ClassCastException", &msg)
            }
        }
    }

    /// Refuse a non-transient, NAMING it.
    ///
    /// What arrived is the half that locates the bug -- a wrong TYPE is a
    /// mixed-up value, while a fixnum or nil is a stack slot read at the wrong
    /// depth. This used to say so through a classifier of its own, hand-rolled
    /// here: "a heap object with type tag 23" where `describe` says "a
    /// transient vector". Two classifiers meant two answers to one question,
    /// and the coarser one was on the path that needed it most.
    ///
    /// The `in <frames>` suffix is gone. It came from `where_am_i`, which is
    /// Rust-only -- so the message a program could catch and read differed
    /// between runtimes by more than the value. The frame trace is still
    /// reachable through `frame_trace` for a debugger; it is not part of what
    /// this says.
    pub fn not_a_transient(&mut self, op: &str, v: Value) -> Value {
        let msg = alloc::format!("{op} wants a transient, got {}", self.describe(v));
        self.throw_str("ClassCastException", &msg)
    }

    pub fn to_persistent(&mut self, v: Value) -> Value {
        if !v.is_heap() {
            return self.not_a_transient("persistent!", v);
        }
        match ty(&self.gc.sp, v.as_heap()) {
            TY_TVEC => self.tvec_persistent(v),
            TY_TMAP => self.tmap_persistent(v),
            TY_TSET => self.tset_persistent(v),
            crate::obj::TY_TTABLE => self.ttable_persistent(v),
            _ => self.not_a_transient("persistent!", v),
        }
    }

    pub fn transient_conj(&mut self, t: Value, x: Value) -> Value {
        if !t.is_heap() {
            return self.not_a_transient("conj!", t);
        }
        match ty(&self.gc.sp, t.as_heap()) {
            TY_TVEC => self.tvec_conj(t, x),
            TY_TSET => self.tset_conj(t, x),
            crate::obj::TY_TTABLE => self.ttable_conj(t, x),
            TY_TMAP => {
                if x.is_heap() && matches!(ty(&self.gc.sp, x.as_heap()), TY_MAPENTRY | TY_VEC) {
                    let (k, v) = (self.slot_or_nth(x, 0), self.slot_or_nth(x, 1));
                    self.tmap_assoc(t, k, v)
                } else {
                    self.throw_str("IllegalArgumentException", "conj! on a map wants a map entry")
                }
            }
            _ => self.not_a_transient("conj!", t),
        }
    }

    pub fn transient_assoc(&mut self, t: Value, k: Value, v: Value) -> Value {
        if !t.is_heap() {
            return self.not_a_transient("assoc!", t);
        }
        match ty(&self.gc.sp, t.as_heap()) {
            TY_TMAP => self.tmap_assoc(t, k, v),
            TY_TVEC => match self.as_i64(k) {
                Some(i) if i >= 0 => self.tvec_assoc(t, i as u32, v),
                _ => self.throw_str("IndexOutOfBoundsException", "assoc! index out of range"),
            },
            _ => self.throw_str("ClassCastException", "not an associative transient"),
        }
    }

    pub fn transient_dissoc(&mut self, t: Value, k: Value) -> Value {
        if t.is_heap() && ty(&self.gc.sp, t.as_heap()) == TY_TMAP {
            self.tmap_dissoc(t, k)
        } else if t.is_heap() && ty(&self.gc.sp, t.as_heap()) == TY_TSET {
            self.tset_disj(t, k)
        } else {
            self.not_a_transient("dissoc!", t)
        }
    }

    /// `pop!`: a transient vector one shorter.
    ///
    /// This had no builtin at all. `clojure.core` read `(defn pop! [t]
    /// (flint.rt/pop t))` -- the PERSISTENT pop -- so a transient vector fell
    /// through to the list branch and answered `()` rather than throwing or
    /// working. `tvec_pop` has been implemented on all four runtimes the whole
    /// time and nothing but its own unit test ever called it.
    ///
    /// Clojure's `pop!` is vector-only, so this does not dispatch further.
    pub fn transient_pop(&mut self, t: Value) -> Value {
        if t.is_heap() && ty(&self.gc.sp, t.as_heap()) == TY_TVEC {
            // The empty case is checked HERE and not in `tvec_pop`, which
            // only debug-asserts it. `cnt` is a `u32`, so `cnt - 1` on an
            // empty transient wraps to `u32::MAX` in release and the count
            // is written back as that.
            if self.tvec_count(t) == 0 {
                return self.throw_str("IllegalStateException", "cannot pop an empty vector");
            }
            self.tvec_pop(t)
        } else {
            self.not_a_transient("pop!", t)
        }
    }

    // --- atoms ---------------------------------------------------------------

    // --- metadata -------------------------------------------------------------

    // --- strings ---------------------------------------------------------------

    pub fn str_concat2(&mut self, x: Value, y: Value) -> Value {
        if !self.is_string(x) || !self.is_string(y) {
            return self.throw_str("ClassCastException", "not a string");
        }
        // A tree join, not a copy (`doc/decisions/0011`). Small results still
        // copy into a flat string -- `s_concat` decides -- because below the
        // threshold the metadata costs more than the copy it saves.
        //
        // Gas is charged for the bytes only when they are actually moved, which
        // is what makes repeated concatenation linear in gas as well as in time.
        self.s_concat(x, y)
    }

    /// The one-character string at code-point index `i`.
    /// The byte offset of code point `i`, and the bytes there.
    ///
    /// ONE path for both tiers, and NO STATE. A rope descends its own
    /// code-point counts; a flat string is either ASCII -- where the index is
    /// the offset -- or short enough that `raw_string` left it flat, which is
    /// what `INDEX_LEAF` decides.
    ///
    /// This replaced a one-entry cursor, and the reason is worth keeping: a
    /// cursor made the cost of an index depend on what was indexed BEFORE it,
    /// and the cursor had to be invalidated by the collector. So the same walk
    /// cost 8 000 gas undisturbed and 8 500 with one collection halfway through
    /// -- and under parallel executors that collection belongs to another
    /// thread. `doc/decisions/0009` says gas is deterministic; a memo keyed on
    /// collector state is not. `runtime/tests/determinism.rs` is the guard.
    fn cp_bytes_at(&mut self, s: Value, i: u32, out: &mut [u8; 4]) -> Option<u32> {
        // WHICH MECHANISM, AND WHY -- `0011` calls them complementary and this
        // is the line where that has to be acted on.
        //
        // ASCII: FLATTEN, once, and index by byte. A code-point index IS a byte
        // index, so after the flatten every index is O(1); descending the tree
        // instead costs O(depth) EVERY time, and measured 3.05x on
        // `test/scaling.clj` -- a linear operation made superlinear by using
        // the more general mechanism where the cheaper one applies.
        //
        // NON-ASCII: DESCEND, and never flatten. Here there is no byte index to
        // have, so a flat run leaves nothing but a scan, and the scan is the
        // quadratic.
        if self.is_rope(s) {
            // ASCII included: for ASCII the code-point index IS the byte index,
            // so the descent is the same walk with the lookup skipped. It used
            // to flatten here, which turns an O(log n) descent into an O(n)
            // copy AND caches the flat form, undoing the tree for every later
            // read (`doc/decisions/0011`).
            // PAST THE END NEEDS NO CHECK HERE. `rope_byte_of_cp` answers the
            // byte length when there is no such code point, and
            // `rope_bytes_at` answers 0 for an offset at or past the end -- so
            // the one test below catches both, where an `Option` needed two.
            let byte = if self.s_ascii(s) { i } else { self.rope_byte_of_cp(s, i) };
            let sk = self.sink_open();
            let w = self.rope_bytes_at(s, byte, sk);
            out[..w as usize].copy_from_slice(&self.sink_array(sk)[..w as usize]);
            self.sink_close(sk);
            return if w == 0 { None } else { Some(w) };
        }
        let s = self.string_arg(s);
        let mut buf = crate::rt::sbuf();
        let b: &[u8] = if s.is_inline_str() {
            s.inline_bytes(&mut buf)
        } else {
            str_bytes(&self.gc.sp, s.as_heap())
        };
        let mut scanned = 0u32;
        let at = if self.str_indexable(s) {
            i
        } else {
            // A flat non-ASCII run, bounded by `INDEX_LEAF`: anything longer
            // arrives as a tree, so this scan is O(INDEX_LEAF), not O(n).
            let mut at = 0u32;
            for _ in 0..i {
                if at as usize >= b.len() {
                    return None;
                }
                at += self.utf8_width(b[at as usize] as u32);
            }
            scanned = at;
            at
        };
        if at as usize >= b.len() {
            return None;
        }
        let w = self.utf8_width(b[at as usize] as u32);
        out[..w as usize].copy_from_slice(&b[at as usize..at as usize + w as usize]);
        // After the borrow of `b` ends: charged for what was walked.
        self.charge_bytes(scanned);
        Some(w)
    }

    /// The code point at `i`, as a one-character string.
    ///
    /// NOTE THE MISSING `string_arg`. Every indexing path used to flatten a
    /// rope first, which threw away the per-node code-point counts
    /// `doc/decisions/0011` computes, stores and traces for exactly this
    /// question -- and then answered it by scanning.
    /// The one-character string at code point `i`, or `dflt` past the end.
    ///
    /// A DEFAULT rather than an `Option`, the same convergence `vec_nth` and
    /// `map_get` already made: the ports answered a sentinel, Rust answered
    /// `Option`, and every caller here was spelling a default out anyway.
    pub fn char_at(&mut self, s: Value, i: u32, dflt: Value) -> Value {
        let mut out = [0u8; 4];
        match self.cp_bytes_at(s, i, &mut out) {
            Some(w) => Value::inline_str(&out[..w as usize]),
            None => dflt,
        }
    }

    pub fn code_point_at(&mut self, s: Value, i: Value) -> Value {
        let idx = match self.as_i64(i) {
            Some(n) if n >= 0 => n as usize,
            _ => return self.throw_str("IndexOutOfBoundsException", "bad index"),
        };
        let mut out = [0u8; 4];
        let w = match self.cp_bytes_at(s, idx as u32, &mut out) {
            Some(w) => w as usize,
            None => return self.throw_str("IndexOutOfBoundsException", "string index out of range"),
        };
        match core::str::from_utf8(&out[..w]).ok().and_then(|t| t.chars().next()) {
            Some(c) => Value::fixnum(c as u32 as i64),
            None => self.throw_str("IndexOutOfBoundsException", "string index out of range"),
        }
    }

    /// `subs` over a tree, by descent: two offsets and a byte copy.
    ///
    /// `charge_bytes` is for the SLICE, not the source, for the reason the flat
    /// path already records -- charging the whole string per call makes the
    /// counter quadratic for splitting even when the code is not.
    fn rope_substring(&mut self, s: Value, start: i64, end: Option<i64>) -> Value {
        let n = self.s_count(s) as i64;
        let e = end.unwrap_or(n);
        if start < 0 || e > n || start > e {
            return self.throw_str("StringIndexOutOfBoundsException", "bad substring range");
        }
        if !self.charge_checked(((e - start) as u64 / 8) + 1, "subs") {
            return crate::value::NIL;
        }
        if start == e {
            return self.string("");
        }
        let n = self.s_bytes(s);
        let from = self.rope_byte_of_cp(s, start as u32);
        if from >= n {
            return self.throw_str("StringIndexOutOfBoundsException", "bad substring range");
        }
        // The END offset is the start of code point `e`, or the whole byte
        // length when `e` is the count -- there is no code point AT the end.
        // Both branches now answer `n` for "past the end", so the second one
        // has nothing left to decide.
        let to = if e as u32 == self.s_count(s) { n } else { self.rope_byte_of_cp(s, e as u32) };
        self.rope_slice(s, from, to)
    }

    /// `subs`, in code points.
    pub fn substring(&mut self, s: Value, start: i64, end: Option<i64>) -> Value {
        // NO `string_arg` on the non-ASCII path. Flattening first turned a tree
        // into one long run and then walked it with `chars().skip(start)`,
        // which is O(start) per call -- so slicing a string n times was
        // quadratic, which is the defect `doc/decisions/0011` predicts in the
        // sentence "without the flag, `nth` and `subs` on a flat string are
        // O(n) and splitting one is quadratic". The tree answers both offsets
        // by descent instead.
        if self.is_rope(s) && !self.s_ascii(s) {
            return self.rope_substring(s, start, end);
        }
        // AN ASCII ROPE DESCENDS TOO. This used to fall through to
        // `string_arg`, which flattens -- so the non-ASCII path was careful and
        // the easy path was not, which is the wrong way round and exactly the
        // "flatten because the platform likes flat things" that `0011` exists
        // to refuse. For ASCII a code point IS a byte, so `append_range` is the
        // whole implementation and it never materialises the tree.
        if self.is_rope(s) {
            let n = self.s_count(s) as i64;
            let e = end.unwrap_or(n);
            if start < 0 || e > n || start > e {
                return self.throw_str("StringIndexOutOfBoundsException", "bad substring range");
            }
            return self.rope_slice(s, start as u32, e as u32);
        }
        let s = self.string_arg(s);
        // The slice, not the source. Charging the whole string per call made
        // the COUNTER quadratic for splitting -- n slices of an n-byte string is
        // n^2 gas for n bytes of copying -- which is the same defect as
        // `str_index_of`'s charge, in the same shape, one function along.
        // `test/scaling.clj` found it on its first run.
        let n = if self.is_string(s) { self.str_len(s) } else { 0 };
        let took = end
            .map(|e| (e - start).max(0) as u32)
            .unwrap_or_else(|| n.saturating_sub(start.max(0) as u32));
        self.charge_bytes(took.min(n));
        if self.str_indexable(s) {
            let n = self.str_len(s) as i64;
            let e = end.unwrap_or(n);
            if start < 0 || e > n || start > e {
                return self.throw_str("StringIndexOutOfBoundsException", "bad substring range");
            }
            let owned: alloc::string::String = {
                let mut t = crate::rt::sbuf();
                let bytes: &[u8] = if s.is_inline_str() {
                    s.inline_bytes(&mut t)
                } else {
                    str_bytes(&self.gc.sp, s.as_heap())
                };
                core::str::from_utf8(&bytes[start as usize..e as usize]).unwrap_or("").into()
            };
            return self.string(&owned);
        }
        let mut buf = crate::rt::sbuf();
        let owned = {
            let b: &[u8] = if s.is_inline_str() {
                s.inline_bytes(&mut buf)
            } else if s.is_heap() && ty(&self.gc.sp, s.as_heap()) == TY_STR {
                str_bytes(&self.gc.sp, s.as_heap())
            } else {
                return self.throw_str("ClassCastException", "not a string");
            };
            let t = core::str::from_utf8(b).unwrap_or("");
            let n = t.chars().count() as i64;
            let e = end.unwrap_or(n);
            if start < 0 || e > n || start > e {
                return self.throw_str("StringIndexOutOfBoundsException", "bad substring range");
            }
            let out: alloc::string::String =
                t.chars().skip(start as usize).take((e - start) as usize).collect();
            out
        };
        self.string(&owned)
    }

    pub fn keyword_from_values(&mut self, ns: Value, name: Value) -> Value {
        let mut bn = crate::rt::sbuf();
        let mut bm = crate::rt::sbuf();
        let owned_ns: Option<alloc::string::String> = if ns.is_nil() {
            None
        } else {
            Some(self.as_str(ns, &mut bn).unwrap_or("").into())
        };
        let owned_name: alloc::string::String = if self.is_string(name) {
            self.as_str(name, &mut bm).unwrap_or("").into()
        } else if self.is_keyword(name) || self.is_symbol(name) {
            let n = self.name_of(name);
            let mut b2 = crate::rt::sbuf();
            self.as_str(n, &mut b2).unwrap_or("").into()
        } else {
            return NIL;
        };
        self.keyword(owned_ns.as_deref(), &owned_name)
    }

    pub fn symbol_from_values(&mut self, ns: Value, name: Value) -> Value {
        let mut bn = crate::rt::sbuf();
        let mut bm = crate::rt::sbuf();
        let owned_ns: Option<alloc::string::String> = if ns.is_nil() {
            None
        } else {
            Some(self.as_str(ns, &mut bn).unwrap_or("").into())
        };
        let owned_name: alloc::string::String = if self.is_string(name) {
            self.as_str(name, &mut bm).unwrap_or("").into()
        } else if self.is_symbol(name) || self.is_keyword(name) {
            let n = self.name_of(name);
            let mut b2 = crate::rt::sbuf();
            self.as_str(n, &mut b2).unwrap_or("").into()
        } else {
            return NIL;
        };
        self.symbol(owned_ns.as_deref(), &owned_name)
    }

    // --- numbers to and from text ---------------------------------------------

    /// Clojure-compatible rendering: integers plain, doubles always with a
    /// fractional part or exponent, and the three special doubles as `##Inf`,
    /// `##-Inf` and `##NaN`.
    pub fn number_to_string(&mut self, v: Value) -> Value {
        if let Some(n) = self.as_i64(v) {
            let mut buf = [0u8; 24];
            let s = fmt_i64(n, &mut buf);
            return self.string(s);
        }
        if !v.is_double() {
            let msg = alloc::format!("not a number: {}", self.describe(v));
            return self.throw_str("ClassCastException", &msg);
        }
        let d = v.as_f64();
        if d.is_nan() {
            return self.string("##NaN");
        }
        if d.is_infinite() {
            return self.string(if d > 0.0 { "##Inf" } else { "##-Inf" });
        }
        let mut s = alloc::format!("{}", d);
        if !s.contains('.') && !s.contains('e') && !s.contains('E') {
            s.push_str(".0");
        }
        self.string(&s)
    }

    pub fn string_to_number(&mut self, v: Value) -> Value {
        let mut buf = crate::rt::sbuf();
        let owned: alloc::string::String = match self.as_str(v, &mut buf) {
            Some(s) => s.into(),
            None => return NIL,
        };
        let t = owned.trim();
        if t.is_empty() {
            return NIL;
        }
        if let Ok(n) = t.parse::<i64>() {
            return self.integer(n);
        }
        // Hex/octal/binary literals, as the reader needs them.
        if let Some(rest) = t.strip_prefix("0x").or_else(|| t.strip_prefix("0X")) {
            if let Ok(n) = i64::from_str_radix(rest, 16) {
                return self.integer(n);
            }
        }
        match t {
            "##Inf" => return Value::from_f64(f64::INFINITY),
            "##-Inf" => return Value::from_f64(f64::NEG_INFINITY),
            "##NaN" => return Value::from_f64(f64::NAN),
            _ => {}
        }
        match t.parse::<f64>() {
            Ok(d) => Value::from_f64(d),
            Err(_) => NIL,
        }
    }

    /// Concatenate a collection of strings in one pass. Without this, building
    /// a string by repeated `str` is quadratic, which shows up immediately in
    /// the reader when the compiler compiles itself.
    pub fn join_strings(&mut self, coll: Value) -> Value {
        let base = self.mark();
        let s = self.seq(coll);
        let si = self.push(s);
        let mut out = alloc::string::String::new();
        let mut i = 0u64;
        while !self.r(si).is_nil() {
            // CHARGED AND CHECKED INSIDE THE LOOP. `string_from_parts` charges
            // for the bytes at the end, which bills correctly and bounds
            // nothing: this walked 300 000 elements 2 698 029 steps past an
            // exhausted budget before anyone looked, because a counter nobody
            // reads until the next interpreter instruction cannot stop a native
            // that never reaches one.
            if !self.charge_tick(i, 1, "str-join") {
                self.pop_to(base);
                return NIL;
            }
            i += 1;
            let x = self.first(self.r(si));
            // A ROPE IS A STRING. `as_str` borrows and so cannot materialise
            // one -- it returns `None` by design -- and this read that as "not
            // a string" and threw. Nothing noticed while `subs` returned flat
            // strings; the moment `subs` started SHARING and handed back a
            // rope, joining its results stopped working. The tier is supposed
            // to be invisible, so walk it.
            if self.is_rope(x) {
                let bs = self.s_to_vec(x);
                out.push_str(core::str::from_utf8(&bs).unwrap_or(""));
            } else {
                let mut b = crate::rt::sbuf();
                match self.as_str(x, &mut b) {
                    Some(t) => out.push_str(t),
                    None => {
                        self.pop_to(base);
                        return self.throw_str("ClassCastException", "str-join wants strings");
                    }
                }
            }
            let nx = self.next(self.r(si));
            self.set_r(si, nx);
        }
        self.pop_to(base);
        self.string(&out)
    }

    /// Byte offset -> code-point index search. Returns nil when absent.
    pub fn str_index_of(&mut self, haystack: Value, needle: Value, from: i64) -> Value {
        let haystack = self.string_arg(haystack);
        let needle = self.string_arg(needle);
        // A naive search is O(haystack x needle); charging the haystack keeps a
        // long scan from being free.
        let hn = if self.is_string(haystack) { self.str_len(haystack) } else { 0 };
        // BOUNDED before the search and BILLED after it, which are two
        // different jobs. The pre-charge is the worst case and is refunded
        // below, so the count stays the distance actually scanned -- the
        // accounting the comment below fought for -- while a search that cannot
        // be paid for never starts. Billing after alone ran 148 114 steps past
        // an exhausted budget.
        let pre = (hn as u64 / 8) + 1;
        if !self.charge_checked(pre, "str-index-of") {
            return NIL;
        }
        self.steps = self.steps.saturating_sub(pre);
        // Charged AFTER the search, for the distance actually scanned -- see
        // below. Charging the whole haystack made the counter quadratic;
        // charging what remained after `from` still did, because a scan that
        // stops at the first match walks a few bytes and was billed for the
        // rest of the string. Same defect, third variation, same session.
        // The header already carries this bit (`str_is_ascii`), set once when
        // the string was built. Asking `&str::is_ascii()` instead rescanned the
        // WHOLE haystack on every call, which made `str/split` quadratic: 6 800
        // calls over a 32 799-character corpus is 223 million byte checks, and
        // it was 37 ms of a 55 ms benchmark. The comment four lines down
        // claimed the search was O(n) rather than O(n) per position; this is
        // what made that true.
        let ascii = self.str_indexable(haystack);
        let mut bh = crate::rt::sbuf();
        let mut bn = crate::rt::sbuf();
        let found = {
            let hb: &[u8] = if haystack.is_inline_str() {
                haystack.inline_bytes(&mut bh)
            } else if haystack.is_heap() && ty(&self.gc.sp, haystack.as_heap()) == TY_STR {
                str_bytes(&self.gc.sp, haystack.as_heap())
            } else {
                return self.throw_str("ClassCastException", "not a string");
            };
            let nb: &[u8] = if needle.is_inline_str() {
                needle.inline_bytes(&mut bn)
            } else if needle.is_heap() && ty(&self.gc.sp, needle.as_heap()) == TY_STR {
                str_bytes(&self.gc.sp, needle.as_heap())
            } else {
                return self.throw_str("ClassCastException", "not a string");
            };
            // `from` is a code-point index, and so is the answer. For ASCII
            // those are byte offsets, so the search is over BYTES and never
            // needs a `&str` -- which matters because `from_utf8` validates the
            // whole haystack, and doing that per call made `str/split`
            // quadratic a second time after the `is_ascii` rescan was removed.
            // 6 800 calls over a 32 799-byte corpus is 223 million bytes
            // validated to find 6 800 spaces.
            let skip = from.max(0) as usize;
            if ascii {
                if skip > hb.len() {
                    None
                } else {
                    find_bytes(&hb[skip..], nb).map(|b| skip + b)
                }
            } else {
                // Only here is a `&str` needed at all, because only here do
                // byte offsets and code-point indices differ.
                let h = core::str::from_utf8(hb).unwrap_or("");
                let nd = core::str::from_utf8(nb).unwrap_or("");
                let start_byte = h.char_indices().nth(skip).map(|(i, _)| i).unwrap_or(h.len());
                h[start_byte..].find(nd).map(|b| h[..start_byte + b].chars().count())
            }
        };
        let skip = from.max(0) as u32;
        let scanned = match found {
            Some(i) => (i as u32).saturating_sub(skip) + 1,
            None => hn.saturating_sub(skip),
        };
        self.charge_bytes(scanned);
        match found {
            Some(i) => Value::fixnum(i as i64),
            None => NIL,
        }
        // (the pre-charge that bounds this is above, before the search)
    }

    /// The UTF-8 bytes of a string, as a vector of integers. The image writer
    /// needs this when the compiler is hosted on flint.
    pub fn string_bytes_vector(&mut self, s: Value) -> Value {
        // `append_bytes` WALKS a rope; `string_arg` flattens it. Both produce
        // the same bytes and only one of them replaces the tree with a copy
        // that every later read then uses.
        let n = if self.is_string(s) { self.str_len(s) } else { 0 };
        // Worst of the lot before this: 11 937 109 steps past the limit, because
        // it billed the whole string and then built a vector of every byte.
        if !self.charge_checked(n as u64, "str-bytes") {
            return crate::value::NIL;
        }
        let mut buf = crate::rt::sbuf();
        let owned: alloc::vec::Vec<u8> = if self.is_rope(s) {
            // WALK, do not flatten. Both produce the same bytes; only one of
            // them replaces the tree with a copy that every later read uses.
            self.s_to_vec(s)
        } else {
            let b: &[u8] = if s.is_inline_str() {
                s.inline_bytes(&mut buf)
            } else if s.is_heap() && ty(&self.gc.sp, s.as_heap()) == TY_STR {
                str_bytes(&self.gc.sp, s.as_heap())
            } else {
                return self.throw_str("ClassCastException", "not a string");
            };
            b.to_vec()
        };
        let base = self.mark();
        let mut v = self.empty_vec();
        let vi = self.push(v);
        for byte in owned {
            let nv = self.vec_conj(self.r(vi), Value::fixnum(byte as i64));
            self.set_r(vi, nv);
        }
        v = self.r(vi);
        self.pop_to(base);
        v
    }

    /// An array-map built from a flat k,v collection, preserving order and not
    /// promoting whatever its size.
    pub fn ordered_map(&mut self, kvs: Value) -> Value {
        let base = self.mark();
        let s = self.seq(kvs);
        let si = self.push(s);
        // Rooted AS THEY ARE TAKEN, not accumulated first.
        //
        // These used to go into a Rust `Vec<Value>` and get pushed afterwards.
        // The collector does not scan Rust vectors, and BOTH calls in this loop
        // can collect: `first` forces a lazy seq, and `next` forces the tail,
        // which runs arbitrary flint code. So every value already gathered went
        // stale at the first collection inside the loop, and the stale ones were
        // then rooted and written into the map -- a map whose contents are
        // addresses in a space that has been reused.
        //
        // It needed a map big enough to span a collection, which is why it
        // survived: the reader builds map literals with this (source order has
        // to survive, or the self-hosting fixpoint breaks), and the compiler's
        // own literals are the big ones. `doc/decisions/0031` has the hunt.
        let vals_at = self.mark();
        let mut count = 0usize;
        while !self.r(si).is_nil() {
            let x = self.first(self.r(si));
            self.push(x);
            count += 1;
            let nx = self.next(self.r(si));
            self.set_r(si, nx);
        }
        if count % 2 != 0 {
            self.pop_to(base);
            return self.throw_str("IllegalArgumentException", "array-map needs an even number of forms");
        }
        let n = (count / 2) as u32;
        let a = self.alloc(TY_ARRAYMAP, crate::map::AM_BASE + 2 * n);
        if a == 0 {
            self.pop_to(base);
            return NIL;
        }
        let m = Value::heap(a);
        self.set_slot(a, crate::map::AM_META, NIL);
        self.set_slot(a, crate::map::AM_HASH, NIL);
        for i in 0..(2 * n) as usize {
            let v = self.r(vals_at + i);
            self.set_slot(a, crate::map::AM_BASE + i as u32, v);
        }
        self.pop_to(base);
        m
    }

    pub fn new_volatile(&mut self, v: Value) -> Value {
        let base = self.mark();
        let vi = self.push(v);
        let a = self.alloc(TY_VOLATILE, 1);
        if a == 0 { self.pop_to(base); return NIL; }
        let v = self.r(vi);
        self.pop_to(base);
        self.set_slot(a, 0, v);
        Value::heap(a)
    }

    // --- opaque values (doc/decisions/0022) -------------------------------------

    /// The next opaque identity, and step the counter.
    ///
    /// A generated source can neither hold the counter nor increment it in
    /// place, so it asks for one. Identities are never reused: `0022` says an
    /// opaque value IS its identity, and a recycled id would make two of them
    /// equal.
    pub fn take_opaque_id(&mut self) -> i64 {
        let id = self.next_opaque;
        self.next_opaque = self.next_opaque.wrapping_add(1);
        id as i64
    }

    // --- diagnostics ------------------------------------------------------------

    pub fn gc_stats_map(&mut self) -> Value {
        let s = self.gc.stats;
        let young = self.gc.young_used();
        let pairs: [(&str, i64); 8] = [
            ("minor", s.minor as i64),
            ("major", s.major as i64),
            ("bytes-allocated", s.bytes_allocated as i64),
            ("bytes-copied", s.bytes_copied as i64),
            ("bytes-promoted", s.bytes_promoted as i64),
            ("young-used", young as i64),
            ("old-live", self.gc.old_live() as i64),
            ("old-capacity", self.gc.old_capacity() as i64),
        ];
        let base = self.mark();
        let m = self.empty_map();
        let mi = self.push(m);
        for (k, v) in pairs {
            let kv = self.keyword(None, k);
            let ki = self.push(kv);
            let vv = self.integer(v);
            let kv = self.r(ki);
            self.pop_to(ki);
            let nm = self.map_assoc(self.r(mi), kv, vv);
            self.set_r(mi, nm);
        }
        let out = self.r(mi);
        self.pop_to(base);
        out
    }
}

fn fmt_i64(mut n: i64, buf: &mut [u8; 24]) -> &str {
    if n == 0 {
        buf[0] = b'0';
        return core::str::from_utf8(&buf[..1]).unwrap();
    }
    let neg = n < 0;
    let mut i = buf.len();
    // Work in the negative domain so i64::MIN does not overflow.
    if !neg {
        n = -n;
    }
    while n != 0 {
        i -= 1;
        buf[i] = b'0' + ((-(n % 10)) as u8);
        n /= 10;
    }
    if neg {
        i -= 1;
        buf[i] = b'-';
    }
    let len = buf.len() - i;
    buf.copy_within(i.., 0);
    core::str::from_utf8(&buf[..len]).unwrap()
}

// --- transient maps and sets -------------------------------------------------

impl Rt {
    // `map_transient`, `tmap_get`, `tmap_assoc`, `tmap_dissoc`, `tmap_count`,
    // `tmap_persistent`, `set_transient`, `tset_conj`, `tset_disj`,
    // `tset_count`, `tset_get` and `tset_persistent` are GENERATED, from
    // `kin/maptrans.kin`.

    pub fn hash_of_str(&mut self, s: &str) -> u32 {
        hash::hash_string(s)
    }
}

/// A scratch buffer type used by string builtins.
pub const SBUF_LEN: usize = INLINE_MAX;
