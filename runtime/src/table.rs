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
use crate::value::{Value, NIL, NOT_FOUND};

/// Rows per chunk. A power of two so the row-to-chunk split is a shift and a
/// mask rather than a division.
pub const CHUNK: u32 = 256;
pub const CHUNK_SHIFT: u32 = 8;

// Schema slots.
//
// A column is addressed in a chunk by a stable ID, not by its position in the
// schema. `SC_IDS` is parallel to `SC_NAMES`, `SC_INDEX` maps name -> id, and
// `SC_WIDTH` is how many column slots a chunk carries. At construction the id
// IS the position and the width is the column count; they part company under
// migration, which is the point (`doc/decisions/0026`): dropping a column is
// then a head-only edit that leaves every chunk shared and unchanged.
pub const SC_NAMES: u32 = 0;
pub const SC_TYPES: u32 = 1;
pub const SC_INDEX: u32 = 2;
pub const SC_IDS: u32 = 3;
pub const SC_WIDTH: u32 = 4;
pub const SC_LEN: u32 = 5;

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
                // These four answered `:other` until the printer was moved onto
                // a protocol and the hole showed. `:other` is not a kind, it is
                // the ABSENCE of one -- and an `extend-protocol :other` written
                // for one of them would have caught all of them and every future
                // type besides. A value a guest can hold needs a kind of its own
                // or it cannot be dispatched on at all (`doc/decisions/0005`).
                crate::obj::TY_OPAQUE => "opaque",
                crate::obj::TY_BYTES | crate::obj::TY_BROPE | crate::obj::TY_TBYTES => "bytes",
                crate::obj::TY_DELAY => "delay",
                crate::obj::TY_VOLATILE => "volatile",
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
        let ids = self.empty_vec();
        let di = self.push(ids);
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
            let dv = self.vec_conj(self.r(di), Value::fixnum(i as i64));
            self.set_r(di, dv);
            self.pop_to(pj);
        }
        let a = self.alloc(TY_SCHEMA, SC_LEN);
        let s = Value::heap(a);
        let si = self.push(s);
        let (nv, tv, iv, dv) = (self.r(ni), self.r(ti), self.r(ii), self.r(di));
        self.set(self.r(si), SC_NAMES, nv);
        self.set(self.r(si), SC_TYPES, tv);
        self.set(self.r(si), SC_INDEX, iv);
        self.set(self.r(si), SC_IDS, dv);
        self.set(self.r(si), SC_WIDTH, Value::fixnum(n as i64));
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

    /// How many column slots a chunk of this schema carries. Not the same as
    /// `schema_len` once a migration has dropped a column: the slot stays, the
    /// name does not.
    pub fn schema_width(&mut self, s: Value) -> u32 {
        self.slot(s, SC_WIDTH).as_fixnum() as u32
    }

    /// The stable id of the `c`th column of the schema.
    pub fn schema_id_at(&mut self, s: Value, c: u32) -> u32 {
        let ids = self.slot(s, SC_IDS);
        self.vec_nth(ids, c).unwrap_or(NIL).as_fixnum() as u32
    }

    /// The column id of `name`, or -1. The index is a map because a wide schema
    /// wants one; a narrow one would be as fast scanned, and is not worth two
    /// code paths.
    pub fn schema_id(&mut self, s: Value, name: Value) -> i64 {
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
        let width = self.schema_width(self.r(si));
        let nrows = self.vec_count(self.r(ri));
        let chunks = self.empty_vec();
        let ci = self.push(chunks);

        let mut row = 0u32;
        while row < nrows {
            let take = core::cmp::min(CHUNK, nrows - row);
            // The chunk: [nrows, col0 … colN], each column a flat run.
            let ch = self.new_obj(TY_NODE, 1 + width);
            let chi = self.push(ch);
            self.set(self.r(chi), 0, Value::fixnum(take as i64));
            for c in 0..ncols {
                let id = self.schema_id_at(self.r(si), c);
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
                self.set(self.r(chi), 1 + id, cv);
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
        let id = self.schema_id(s, name);
        if id < 0 {
            return dflt;
        }
        let ch = self.slot(r, RF_CHUNK);
        let col = self.slot(ch, 1 + id as u32);
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

    // ---------------------------------------------------------------- step 4
    //
    // `assoc` and `update`, and the refusals. These are ORDINARY errors and not
    // `#?(:flint/check ...)`: a closed table that accepted a bad row in a
    // release build would not be closed (`doc/decisions/0026`). What they take
    // from `0032` is the quality of the message -- expected, actual, and the
    // column -- rather than the mechanism.

    /// The value of column `c` in `row`, or `NOT_FOUND`. `row` may be a map or
    /// another table's row ref, so a row can be moved between tables without
    /// being materialised first.
    fn row_column(&mut self, s: Value, row: Value, c: u32) -> Value {
        let names = self.slot(s, SC_NAMES);
        let name = self.vec_nth(names, c).unwrap_or(NIL);
        if self.is_table_ref(row) {
            self.ref_get(row, name, NOT_FOUND)
        } else {
            self.map_get(row, name, NOT_FOUND)
        }
    }

    /// The schema's column names as `:a :b :c`, for a message that has to say
    /// what the columns ARE rather than only that the key was not one.
    fn column_list(&mut self, s: Value) -> alloc::string::String {
        let n = self.schema_len(s);
        let mut out = alloc::string::String::new();
        for c in 0..n {
            let names = self.slot(s, SC_NAMES);
            let name = self.vec_nth(names, c).unwrap_or(NIL);
            let mut b = crate::rt::sbuf();
            let nm = self.name_of(name);
            let shown: alloc::string::String = self.as_str(nm, &mut b).unwrap_or("?").into();
            if c > 0 {
                out.push(' ');
            }
            out.push(':');
            out.push_str(&shown);
        }
        out
    }

    fn kw_name(&mut self, v: Value) -> alloc::string::String {
        let mut b = crate::rt::sbuf();
        let nm = self.name_of(v);
        self.as_str(nm, &mut b).unwrap_or("?").into()
    }

    /// Does `row` fit `s`? Exactly the schema's columns, each of its declared
    /// type. Throws and returns false if not; `rowno` appears in the message.
    ///
    /// The three refusals are separate because they are three different
    /// mistakes: a column you forgot, a key that is not a column, and a value
    /// of the wrong type. One "invalid row" for all three is the message this
    /// codebase keeps replacing.
    pub fn check_row(&mut self, s: Value, row: Value, rowno: u32) -> bool {
        let base = self.mark();
        let si = self.push(s);
        let ri = self.push(row);
        if !self.is_map(self.r(ri)) {
            let k = self.kind_of(self.r(ri));
            let kn = self.kw_name(k);
            self.pop_to(base);
            let msg = alloc::format!("a table row is a map, and row {rowno} is a {kn}");
            self.throw_str("IllegalArgumentException", &msg);
            return false;
        }
        let n = self.schema_len(self.r(si));
        for c in 0..n {
            let val = self.row_column(self.r(si), self.r(ri), c);
            let vi = self.push(val);
            let name = {
                let names = self.slot(self.r(si), SC_NAMES);
                self.vec_nth(names, c).unwrap_or(NIL)
            };
            let ni = self.push(name);
            if self.r(vi) == NOT_FOUND {
                let nm = self.kw_name(self.r(ni));
                let cols = self.column_list(self.r(si));
                self.pop_to(base);
                let msg = alloc::format!(
                    "row {rowno} has no :{nm}; a table is closed, so every row has \
                     every column, and the columns are {cols}"
                );
                self.throw_str("IllegalArgumentException", &msg);
                return false;
            }
            let tp = {
                let types = self.slot(self.r(si), SC_TYPES);
                self.vec_nth(types, c).unwrap_or(NIL)
            };
            if !self.type_ok(tp, self.r(vi)) {
                let (nv, tv, vv) = (self.r(ni), tp, self.r(vi));
                let msg = self.column_type_error(nv, tv, vv, rowno);
                self.pop_to(base);
                self.throw_str("IllegalArgumentException", &msg);
                return false;
            }
            self.pop_to(vi);
        }
        // Every column is present, so a wider row has a key that is not one.
        // Counted first and hunted only when the count disagrees, because the
        // hunt walks the row and the good path must not.
        let extra = if self.is_table_ref(self.r(ri)) {
            let rs = self.slot(self.r(ri), RF_SCHEMA);
            self.schema_len(rs) > n
        } else {
            self.map_count(self.r(ri)) > n
        };
        if extra {
            let bad = self.first_foreign_key(self.r(si), self.r(ri));
            let bi = self.push(bad);
            let nm = self.kw_name(self.r(bi));
            let cols = self.column_list(self.r(si));
            self.pop_to(base);
            let msg = alloc::format!(
                "row {rowno} has :{nm}, which is not a column; a table is closed, and \
                 the columns are {cols}"
            );
            self.throw_str("IllegalArgumentException", &msg);
            return false;
        }
        self.pop_to(base);
        true
    }

    /// The first key of `row` that the schema does not name. Only ever called
    /// once a count has already proved there is one.
    fn first_foreign_key(&mut self, s: Value, row: Value) -> Value {
        let base = self.mark();
        let si = self.push(s);
        if self.is_table_ref(row) {
            let rs = self.slot(row, RF_SCHEMA);
            let rsi = self.push(rs);
            let n = self.schema_len(self.r(rsi));
            for c in 0..n {
                let names = self.slot(self.r(rsi), SC_NAMES);
                let name = self.vec_nth(names, c).unwrap_or(NIL);
                if self.schema_id(self.r(si), name) < 0 {
                    self.pop_to(base);
                    return name;
                }
            }
            self.pop_to(base);
            return NIL;
        }
        let seq = self.seq(row);
        let qi = self.push(seq);
        while !self.r(qi).is_nil() {
            let e = self.first(self.r(qi));
            let ei = self.push(e);
            let k = self.slot_or_nth_pub(self.r(ei), 0);
            if self.schema_id(self.r(si), k) < 0 {
                self.pop_to(base);
                return k;
            }
            self.pop_to(ei);
            let nx = self.next(self.r(qi));
            self.set_r(qi, nx);
        }
        self.pop_to(base);
        NIL
    }

    /// One row of `rows` (a vector) written into chunk `ch` at `k`, column by
    /// column. Shared by `new_table` and the assoc path so the two cannot drift
    /// on what a row is allowed to be.
    fn write_row(&mut self, s: Value, ch: Value, k: u32, row: Value) {
        let base = self.mark();
        let si = self.push(s);
        let ci = self.push(ch);
        let ri = self.push(row);
        let n = self.schema_len(self.r(si));
        for c in 0..n {
            let id = self.schema_id_at(self.r(si), c);
            let v = self.row_column(self.r(si), self.r(ri), c);
            let col = self.slot(self.r(ci), 1 + id);
            self.set(col, k, v);
        }
        self.pop_to(base);
    }

    /// A copy of chunk `ch` with row `k` replaced by `row`, and optionally one
    /// more row of room. The chunk and every column it holds are copied, which
    /// is what makes the ref that was looking at the old one still valid: a
    /// persistent structure does not edit what someone else can see.
    fn chunk_with_row(&mut self, s: Value, ch: Value, k: u32, row: Value, grow: bool) -> Value {
        let base = self.mark();
        let si = self.push(s);
        let ci = self.push(ch);
        let ri = self.push(row);
        let width = self.schema_width(self.r(si));
        let old = self.slot(self.r(ci), 0).as_fixnum() as u32;
        let take = if grow { old + 1 } else { old };
        let nch = self.new_obj(TY_NODE, 1 + width);
        let ni = self.push(nch);
        self.set(self.r(ni), 0, Value::fixnum(take as i64));
        for id in 0..width {
            let src = self.slot(self.r(ci), 1 + id);
            if src.is_nil() {
                continue;
            }
            let sj = self.push(src);
            let col = self.new_obj(TY_NODE, take);
            let cj = self.push(col);
            let copy = core::cmp::min(old, take);
            for j in 0..copy {
                let v = self.slot(self.r(sj), j);
                self.set(self.r(cj), j, v);
            }
            let cv = self.r(cj);
            self.set(self.r(ni), 1 + id, cv);
            self.pop_to(sj);
        }
        let (nv, rv, sv) = (self.r(ni), self.r(ri), self.r(si));
        self.write_row(sv, nv, k, rv);
        let out = self.r(ni);
        self.pop_to(base);
        out
    }

    /// `(assoc table i row)`. `i` may be `count`, which appends -- the same
    /// rule a vector follows, so nothing new has to be learned to grow one.
    pub fn table_assoc(&mut self, t: Value, k: Value, row: Value) -> Value {
        let i = match self.as_i64(k) {
            Some(i) => i,
            None => {
                let kind = self.kind_of(k);
                let kn = self.kw_name(kind);
                let msg = alloc::format!(
                    "a table is indexed by row number and this key is a {kn}; to reach a \
                     column, index the row first: (assoc-in t [row :column] v)"
                );
                return self.throw_str("IllegalArgumentException", &msg);
            }
        };
        let n = self.table_count(t) as i64;
        if i < 0 || i > n {
            let msg = alloc::format!(
                "row {i} is out of range for a table of {n} rows; assoc may replace any \
                 row or append at {n}"
            );
            return self.throw_str("IndexOutOfBoundsException", &msg);
        }
        let base = self.mark();
        let ti = self.push(t);
        let ri = self.push(row);
        let s = self.slot(self.r(ti), TB_SCHEMA);
        let si = self.push(s);
        if !self.check_row(self.r(si), self.r(ri), i as u32) {
            self.pop_to(base);
            return NIL;
        }
        let i = i as u32;
        let ci = self.push(NIL);
        {
            let chunks = self.slot(self.r(ti), TB_CHUNKS);
            self.set_r(ci, chunks);
        }
        let which = i >> CHUNK_SHIFT;
        let within = i & (CHUNK - 1);
        let append = i == self.table_count(self.r(ti));
        let nchunks = self.vec_count(self.r(ci));
        if append && which >= nchunks {
            // A new chunk, one row wide. `chunk_with_row` grows an existing
            // one; an empty table has none to grow.
            let width = self.schema_width(self.r(si));
            let ch = self.new_obj(TY_NODE, 1 + width);
            let chi = self.push(ch);
            self.set(self.r(chi), 0, Value::fixnum(1));
            let ncols = self.schema_len(self.r(si));
            for c in 0..ncols {
                let id = self.schema_id_at(self.r(si), c);
                let col = self.new_obj(TY_NODE, 1);
                self.set(self.r(chi), 1 + id, col);
            }
            let (sv, chv, rv) = (self.r(si), self.r(chi), self.r(ri));
            self.write_row(sv, chv, 0, rv);
            let chv = self.r(chi);
            let nv = self.vec_conj(self.r(ci), chv);
            self.set_r(ci, nv);
            self.pop_to(chi);
        } else {
            let ch = self.vec_nth(self.r(ci), which).unwrap_or(NIL);
            let chi = self.push(ch);
            let (sv, chv, rv) = (self.r(si), self.r(chi), self.r(ri));
            let nch = self.chunk_with_row(sv, chv, within, rv, append);
            let nj = self.push(nch);
            let njv = self.r(nj);
            let nv = self.vec_assoc(self.r(ci), which, njv);
            self.set_r(ci, nv);
            self.pop_to(chi);
        }
        let count = self.table_count(self.r(ti)) + if append { 1 } else { 0 };
        let a = self.alloc(TY_TABLE, TB_LEN);
        let nt = Value::heap(a);
        let ni = self.push(nt);
        let (sv, cv) = (self.r(si), self.r(ci));
        self.set(self.r(ni), TB_SCHEMA, sv);
        self.set(self.r(ni), TB_CHUNKS, cv);
        self.set(self.r(ni), TB_COUNT, Value::fixnum(count as i64));
        let out = self.r(ni);
        self.pop_to(base);
        out
    }

    /// `(conj table row)` -- append, which is `assoc` at the end.
    pub fn table_conj(&mut self, t: Value, row: Value) -> Value {
        let n = self.table_count(t) as i64;
        self.table_assoc(t, Value::fixnum(n), row)
    }

    /// `(assoc row-ref k v)` -> a MAP. A ref is a VIEW; changing it makes an
    /// independent value and neither the chunk nor the table it came from
    /// moves (`doc/decisions/0026`). The schema does not constrain the result,
    /// because the result is no longer a row.
    pub fn ref_assoc(&mut self, r: Value, k: Value, v: Value) -> Value {
        let base = self.mark();
        let ri = self.push(r);
        let ki = self.push(k);
        let vi = self.push(v);
        let m = self.ref_to_map(self.r(ri));
        let mi = self.push(m);
        let (mv, kv, vv) = (self.r(mi), self.r(ki), self.r(vi));
        let out = self.map_assoc(mv, kv, vv);
        self.pop_to(base);
        out
    }
}
