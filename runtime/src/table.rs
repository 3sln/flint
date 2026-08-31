//! Tables: columnar storage that is a value (`doc/decisions/0026`).
//!
//! A vector of maps from the outside; columnar chunks in a trie underneath,
//! with a CLOSED schema fixed when the table is built.
//!
//! Closed is the whole design. A key outside the schema is refused, a value of
//! the wrong type is refused, and from that one decision most of what looked
//! hard stops existing: there are no ragged rows, no null bitmap standing in
//! for absent fields, and no per-chunk type inference, because nothing can put
//! anything unexpected in a column.
//!
//! ## What is reused rather than written
//!
//! * The TRIE is an ordinary flint vector whose elements are chunks. It already
//!   path-copies, indexes and counts, so `get` is a descent it does for us and
//!   `assoc` gets its copy for free.
//! * A CHUNK is a `TY_NODE` -- a plain Vals array -- holding `[nrows, col…]`.
//! * A COLUMN is a `TY_NODE` of `nrows` values. Flat, because a chunk is fixed
//!   at seal and a vector's own trie would be overhead inside one.
//!
//! Unboxed column runs and the constant/RLE encodings are step 5; this is the
//! shape they slot into, and nothing above the chunk can tell the difference.

use crate::obj::{ty, TY_SCHEMA, TY_TABLE, TY_TABLEREF, TY_NODE};
use crate::rt::Rt;
use crate::value::{Value, NIL};

/// Rows per chunk. A power of two so the row-to-chunk split is a shift and a
/// mask rather than a division.
pub const CHUNK: u32 = 256;
pub const CHUNK_SHIFT: u32 = 8;

// Schema slots.
pub const SC_NAMES: u32 = 0;
pub const SC_TYPES: u32 = 1;
pub const SC_INDEX: u32 = 2;
pub const SC_LEN: u32 = 3;

// Table slots.
pub const TB_SCHEMA: u32 = 0;
pub const TB_CHUNKS: u32 = 1;
pub const TB_COUNT: u32 = 2;
pub const TB_LEN: u32 = 3;

// Row-ref slots.
pub const RF_SCHEMA: u32 = 0;
pub const RF_CHUNK: u32 = 1;
pub const RF_ROW: u32 = 2;
pub const RF_LEN: u32 = 3;

impl Rt {
    /// The CLOSED SET protocol dispatch runs on (`doc/decisions/0005`), lifted
    /// out of the `flint/kind` builtin so an error message can name a value's
    /// kind in the same words a program would.
    pub fn kind_of(&mut self, v: Value) -> Value {
        let name = if v.is_nil() {
            "nil"
        } else if v.is_bool() {
            "boolean"
        } else if v.is_double() || v.is_fixnum() {
            "number"
        } else if v.is_inline_str() {
            "string"
        } else if v.is_inline_kw() {
            "keyword"
        } else if !v.is_heap() {
            "other"
        } else {
            match ty(&self.gc.sp, v.as_heap()) {
                crate::obj::TY_STR | crate::obj::TY_ROPE => "string",
                crate::obj::TY_KW => "keyword",
                crate::obj::TY_SYM => "symbol",
                crate::obj::TY_BIGINT => "number",
                crate::obj::TY_VEC | crate::obj::TY_MAPENTRY => "vector",
                crate::obj::TY_ARRAYMAP | crate::obj::TY_HASHMAP => "map",
                crate::obj::TY_SET => "set",
                crate::obj::TY_CONS
                | crate::obj::TY_EMPTY_LIST
                | crate::obj::TY_LAZYSEQ
                | crate::obj::TY_VECSEQ
                | crate::obj::TY_STRSEQ
                | crate::obj::TY_RANGE
                | crate::obj::TY_ITERSEQ
                | crate::obj::TY_CHUNKSEQ => "list",
                crate::obj::TY_CLOSURE | crate::obj::TY_NATIVEFN | crate::obj::TY_MULTIFN => "fn",
                crate::obj::TY_PORT => "port",
                crate::obj::TY_THREAD => "thread",
                crate::obj::TY_ATOM => "atom",
                crate::obj::TY_VAR => "var",
                crate::obj::TY_REGEX => "regex",
                crate::obj::TY_EXINFO => "exception",
                crate::obj::TY_TAGGED => "tagged",
                TY_SCHEMA => "schema",
                TY_TABLE => "table",
                TY_TABLEREF => "map",
                _ => "other",
            }
        };
        self.keyword(None, name)
    }

    pub fn is_schema(&self, v: Value) -> bool {
        v.is_heap() && ty(&self.gc.sp, v.as_heap()) == TY_SCHEMA
    }
    pub fn is_table(&self, v: Value) -> bool {
        v.is_heap() && ty(&self.gc.sp, v.as_heap()) == TY_TABLE
    }
    pub fn is_table_ref(&self, v: Value) -> bool {
        v.is_heap() && ty(&self.gc.sp, v.as_heap()) == TY_TABLEREF
    }

    /// `[[name type] …]` -> a schema. The names must be keywords and distinct;
    /// the types must be ones `type_ok` knows.
    pub fn new_schema(&mut self, pairs: Value) -> Value {
        let base = self.mark();
        let pi = self.push(pairs);
        let n = self.vec_count(self.r(pi));
        let names = self.empty_vec();
        let ni = self.push(names);
        let types = self.empty_vec();
        let ti = self.push(types);
        let idx = self.empty_map();
        let ii = self.push(idx);
        for i in 0..n {
            let pair = self.vec_nth(self.r(pi), i).unwrap_or(NIL);
            let pj = self.push(pair);
            if !self.is_vector(self.r(pj)) || self.vec_count(self.r(pj)) != 2 {
                self.pop_to(base);
                return self.throw_str(
                    "IllegalArgumentException",
                    "a schema is [[name type] ...]; this entry is not a name and a type",
                );
            }
            let nm = self.vec_nth(self.r(pj), 0).unwrap_or(NIL);
            let tp = self.vec_nth(self.r(pj), 1).unwrap_or(NIL);
            if !self.is_keyword(nm) {
                self.pop_to(base);
                return self
                    .throw_str("IllegalArgumentException", "a column name must be a keyword");
            }
            if !self.known_type(tp) {
                let mut b = crate::rt::sbuf();
                let shown: alloc::string::String =
                    { let __n = self.name_of(tp); self.as_str(__n, &mut b) }.unwrap_or("?").into();
                self.pop_to(base);
                let msg = alloc::format!(
                    "no such column type :{shown}; the types are \
                     :int :double :string :bool :keyword :any"
                );
                return self.throw_str("IllegalArgumentException", &msg);
            }
            // DISTINCT, because two columns of one name would make `get`
            // ambiguous and the index silently keep only the later one.
            let seen = self.map_get(self.r(ii), nm, NIL);
            if !seen.is_nil() {
                let mut b = crate::rt::sbuf();
                let shown: alloc::string::String =
                    { let __n = self.name_of(nm); self.as_str(__n, &mut b) }.unwrap_or("?").into();
                self.pop_to(base);
                let msg = alloc::format!("the column :{shown} is named twice");
                return self.throw_str("IllegalArgumentException", &msg);
            }
            let nv = self.vec_conj(self.r(ni), nm);
            self.set_r(ni, nv);
            let tv = self.vec_conj(self.r(ti), tp);
            self.set_r(ti, tv);
            let m = self.map_assoc(self.r(ii), nm, Value::fixnum(i as i64));
            self.set_r(ii, m);
            self.pop_to(pj);
        }
        let a = self.alloc(TY_SCHEMA, SC_LEN);
        let s = Value::heap(a);
        let si = self.push(s);
        let (nv, tv, iv) = (self.r(ni), self.r(ti), self.r(ii));
        self.set(self.r(si), SC_NAMES, nv);
        self.set(self.r(si), SC_TYPES, tv);
        self.set(self.r(si), SC_INDEX, iv);
        let out = self.r(si);
        self.pop_to(base);
        out
    }

    fn known_type(&mut self, t: Value) -> bool {
        for n in ["int", "double", "string", "bool", "keyword", "any"] {
            if t == self.keyword(None, n) {
                return true;
            }
        }
        false
    }

    /// Does `v` belong in a column of type `t`? `:any` takes anything, which is
    /// the escape hatch a closed schema needs to stay usable.
    pub fn type_ok(&mut self, t: Value, v: Value) -> bool {
        if t == self.keyword(None, "any") {
            return true;
        }
        if t == self.keyword(None, "int") {
            return self.is_int(v);
        }
        if t == self.keyword(None, "double") {
            return v.is_double();
        }
        if t == self.keyword(None, "string") {
            return self.is_string(v);
        }
        if t == self.keyword(None, "bool") {
            return v.is_bool();
        }
        if t == self.keyword(None, "keyword") {
            return self.is_keyword(v);
        }
        false
    }

    pub fn schema_len(&mut self, s: Value) -> u32 {
        let names = self.slot(s, SC_NAMES);
        self.vec_count(names)
    }

    /// The position of `name`, or -1. The index is a map because a wide schema
    /// wants one; a narrow one would be as fast scanned, and is not worth two
    /// code paths.
    pub fn schema_pos(&mut self, s: Value, name: Value) -> i64 {
        let idx = self.slot(s, SC_INDEX);
        let p = self.map_get(idx, name, NIL);
        if p.is_fixnum() {
            p.as_fixnum()
        } else {
            -1
        }
    }

    /// Two schemas are the same when the names and the types are, in order.
    /// Position matters: a table of `[[:a :int] [:b :int]]` is not one of
    /// `[[:b :int] [:a :int]]`, because the rows would read differently.
    pub fn schema_eq(&mut self, a: Value, b: Value) -> bool {
        let (na, nb) = (self.slot(a, SC_NAMES), self.slot(b, SC_NAMES));
        if !self.eq(na, nb) {
            return false;
        }
        let (ta, tb) = (self.slot(a, SC_TYPES), self.slot(b, SC_TYPES));
        self.eq(ta, tb)
    }

    pub fn table_count(&mut self, t: Value) -> u32 {
        self.slot(t, TB_COUNT).as_fixnum() as u32
    }

    /// Build a table from `rows`, a vector of maps. Every row must have exactly
    /// the schema's columns, with values of the declared types -- and the
    /// refusal NAMES what was wrong, because "bad row" is the message this
    /// codebase keeps replacing (`doc/decisions/0032`).
    pub fn new_table(&mut self, schema: Value, rows: Value) -> Value {
        let base = self.mark();
        let si = self.push(schema);
        let ri = self.push(rows);
        let ncols = self.schema_len(self.r(si));
        let nrows = self.vec_count(self.r(ri));
        let chunks = self.empty_vec();
        let ci = self.push(chunks);

        let mut row = 0u32;
        while row < nrows {
            let take = core::cmp::min(CHUNK, nrows - row);
            // The chunk: [nrows, col0 … colN], each column a flat run.
            let ch = self.new_obj(TY_NODE, 1 + ncols);
            let chi = self.push(ch);
            self.set(self.r(chi), 0, Value::fixnum(take as i64));
            for c in 0..ncols {
                let col = self.new_obj(TY_NODE, take);
                let coli = self.push(col);
                for k in 0..take {
                    let rowv = self.vec_nth(self.r(ri), row + k).unwrap_or(NIL);
                    let rvi = self.push(rowv);
                    let name = {
                        let names = self.slot(self.r(si), SC_NAMES);
                        self.vec_nth(names, c).unwrap_or(NIL)
                    };
                    let val = self.map_get(self.r(rvi), name, NIL);
                    let tp = {
                        let types = self.slot(self.r(si), SC_TYPES);
                        self.vec_nth(types, c).unwrap_or(NIL)
                    };
                    if !self.type_ok(tp, val) {
                        let msg = self.column_type_error(name, tp, val, row + k);
                        self.pop_to(base);
                        return self.throw_str("IllegalArgumentException", &msg);
                    }
                    self.set(self.r(coli), k, val);
                    self.pop_to(rvi);
                }
                let cv = self.r(coli);
                self.set(self.r(chi), 1 + c, cv);
                self.pop_to(coli);
            }
            let chv = self.r(chi);
            let nv = self.vec_conj(self.r(ci), chv);
            self.set_r(ci, nv);
            self.pop_to(chi);
            row += take;
        }

        let a = self.alloc(TY_TABLE, TB_LEN);
        let t = Value::heap(a);
        let ti = self.push(t);
        let (sv, cv) = (self.r(si), self.r(ci));
        self.set(self.r(ti), TB_SCHEMA, sv);
        self.set(self.r(ti), TB_CHUNKS, cv);
        self.set(self.r(ti), TB_COUNT, Value::fixnum(nrows as i64));
        let out = self.r(ti);
        self.pop_to(base);
        out
    }

    fn column_type_error(
        &mut self,
        name: Value,
        want: Value,
        got: Value,
        row: u32,
    ) -> alloc::string::String {
        let mut b1 = crate::rt::sbuf();
        let nm: alloc::string::String = { let __n = self.name_of(name); self.as_str(__n, &mut b1) }.unwrap_or("?").into();
        let mut b2 = crate::rt::sbuf();
        let wt: alloc::string::String = { let __n = self.name_of(want); self.as_str(__n, &mut b2) }.unwrap_or("?").into();
        // The KIND of what arrived, not just that it was wrong: "holds :int
        // and was given a string" is the difference between a message you can
        // act on and one you have to reproduce first. `kind` is the same closed
        // set protocol dispatch uses, so the word is one the reader has met.
        let gk = self.kind_of(got);
        let mut b3 = crate::rt::sbuf();
        let gt: alloc::string::String =
            { let __n = self.name_of(gk); self.as_str(__n, &mut b3) }.unwrap_or("?").into();
        alloc::format!("row {row}, column :{nm} holds :{wt} and was given a {gt}")
    }

    /// Row `i` as a REF into its chunk. Materialises nothing: `0026` used to
    /// require `get-in` not to build a map, and with a ref the general path is
    /// already the fast one.
    pub fn table_ref(&mut self, t: Value, i: u32) -> Value {
        if i >= self.table_count(t) {
            return NIL;
        }
        let base = self.mark();
        let ti = self.push(t);
        let chunks = self.slot(self.r(ti), TB_CHUNKS);
        let ch = self.vec_nth(chunks, i >> CHUNK_SHIFT).unwrap_or(NIL);
        let chi = self.push(ch);
        let a = self.alloc(TY_TABLEREF, RF_LEN);
        let r = Value::heap(a);
        let ri = self.push(r);
        let sv = self.slot(self.r(ti), TB_SCHEMA);
        self.set(self.r(ri), RF_SCHEMA, sv);
        let cv = self.r(chi);
        self.set(self.r(ri), RF_CHUNK, cv);
        self.set(self.r(ri), RF_ROW, Value::fixnum((i & (CHUNK - 1)) as i64));
        let out = self.r(ri);
        self.pop_to(base);
        out
    }

    /// A column of a row ref, by name. One map lookup for the position, then
    /// two indexes -- no map is built and no row is copied.
    pub fn ref_get(&mut self, r: Value, name: Value, dflt: Value) -> Value {
        let s = self.slot(r, RF_SCHEMA);
        let pos = self.schema_pos(s, name);
        if pos < 0 {
            return dflt;
        }
        let ch = self.slot(r, RF_CHUNK);
        let col = self.slot(ch, 1 + pos as u32);
        let row = self.slot(r, RF_ROW).as_fixnum() as u32;
        self.slot(col, row)
    }

    /// A row ref as a map, built only when someone actually asks for one.
    pub fn ref_to_map(&mut self, r: Value) -> Value {
        let base = self.mark();
        let ri = self.push(r);
        let s = self.slot(self.r(ri), RF_SCHEMA);
        let siv = self.push(s);
        let n = self.schema_len(self.r(siv));
        let m = self.empty_map();
        let mi = self.push(m);
        for c in 0..n {
            let name = {
                let names = self.slot(self.r(siv), SC_NAMES);
                self.vec_nth(names, c).unwrap_or(NIL)
            };
            let nmi = self.push(name);
            let v = self.ref_get(self.r(ri), self.r(nmi), NIL);
            let vi = self.push(v);
            let nm = self.map_assoc(self.r(mi), self.r(nmi), self.r(vi));
            self.set_r(mi, nm);
            self.pop_to(nmi);
        }
        let out = self.r(mi);
        self.pop_to(base);
        out
    }
}
