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

use crate::obj::{ty, TY_SCHEMA, TY_TABLE, TY_TABLEREF, TY_TTABLE, TY_NODE};
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
/// Row index of the table's first row WITHIN its first chunk, always less than
/// `CHUNK`.
///
/// This is what makes `slice` share. A chunk holds exactly `CHUNK` rows, so a
/// range that does not start on a chunk boundary cannot be expressed by
/// dropping chunks alone -- and rebuilding them would copy every value, which
/// is the mistake `subs` was making on ropes one type over. An offset in the
/// head costs one slot and one addition per index, and lets a slice SHARE every
/// chunk it spans.
///
/// Chunks outside the range are dropped rather than retained, so a slice cannot
/// keep the whole table alive -- the same retention rule `SLICE_MIN` states for
/// ropes, achieved here by construction instead of by a threshold.
pub const TB_OFFSET: u32 = 3;
pub const TB_LEN: u32 = 4;

// Chunk slots. A chunk is `[nrows, encodings, col…]`, the columns addressed by
// their stable schema id.
//
// The ENCODINGS node says how each column is written down, and it is what makes
// the separation `0026` draws possible: the schema decides what a column MEANS,
// the chunk decides how it is stored, and may change its mind per chunk without
// the table's meaning moving. Nothing above `chunk_get` can tell the difference.
pub const CH_ROWS: u32 = 0;
pub const CH_ENC: u32 = 1;
pub const CH_BASE: u32 = 2;

/// A value per row, in a flat `TY_NODE`.
pub const ENC_FLAT: u32 = 0;
/// ONE value for every row: the column slot holds the value itself rather than
/// a run. Adding a column with a constant default to a million-row table
/// therefore writes one value per chunk, which is what makes that migration
/// cheap (`doc/decisions/0026`).
pub const ENC_CONST: u32 = 1;

// Transient-table slots.
pub const TT_SCHEMA: u32 = 0;
pub const TT_CHUNKS: u32 = 1;
pub const TT_COUNT: u32 = 2;
pub const TT_OPEN: u32 = 3;
pub const TT_LIVE: u32 = 4;
pub const TT_LEN: u32 = 5;

// Row-ref slots.
pub const RF_SCHEMA: u32 = 0;
pub const RF_CHUNK: u32 = 1;
pub const RF_ROW: u32 = 2;
pub const RF_LEN: u32 = 3;

impl Rt {
    // ---------------------------------------------------------------- step 4
    //
    // `assoc` and `update`, and the refusals. These are ORDINARY errors and not
    // `#?(:flint/check ...)`: a closed table that accepted a bad row in a
    // release build would not be closed (`doc/decisions/0026`). What they take
    // from `0032` is the quality of the message -- expected, actual, and the
    // column -- rather than the mechanism.

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
        let phys = i + self.table_offset(self.r(ti));
        let which = phys >> CHUNK_SHIFT;
        let within = phys & (CHUNK - 1);
        let append = i == self.table_count(self.r(ti));
        let nchunks = self.vec_count(self.r(ci));
        if append && which >= nchunks {
            // A new chunk, one row wide. `chunk_with_row` grows an existing
            // one; an empty table has none to grow.
            let width = self.schema_width(self.r(si));
            let ch = self.new_chunk(width, 1);
            let chi = self.push(ch);
            let ncols = self.schema_len(self.r(si));
            for c in 0..ncols {
                let id = self.schema_id_at(self.r(si), c);
                let col = self.new_obj(TY_NODE, 1);
                self.set(self.r(chi), CH_BASE + id, col);
            }
            let (sv, chv, rv) = (self.r(si), self.r(chi), self.r(ri));
            self.write_row(sv, chv, 0, rv);
            // A one-row column is trivially constant, so a table grown row by
            // row starts every chunk collapsed and expands only where the rows
            // actually differ. Skipping this would make the append path the one
            // path that never encodes.
            for c in 0..ncols {
                let id = self.schema_id_at(self.r(si), c);
                let chv = self.r(chi);
                self.collapse(chv, id);
            }
            let chv = self.r(chi);
            let nv = self.vec_conj(self.r(ci), chv);
            self.set_r(ci, nv);
            self.pop_to(chi);
        } else {
            let ch = self.vec_nth(self.r(ci), which, NIL);
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
        let off = self.slot(self.r(ti), TB_OFFSET);
        self.set(self.r(ni), TB_OFFSET, off);
        let out = self.r(ni);
        self.pop_to(base);
        out
    }

    /// `(conj table row)` -- append, which is `assoc` at the end.
    pub fn table_conj(&mut self, t: Value, row: Value) -> Value {
        let n = self.table_count(t) as i64;
        self.table_assoc(t, Value::fixnum(n), row)
    }

    // ---------------------------------------------------------------- step 6
    //
    // MIGRATION. A schema change makes a NEW TABLE -- there is no in-place
    // evolution and no inference. What makes it cheap is that a chunk addresses
    // its columns by stable id, so a column the new schema keeps is the SAME
    // COLUMN OBJECT, shared rather than copied.

    /// `want` REBASED onto `have`'s column ids: a column both schemas name
    /// keeps its id, so the chunks that hold it can be shared unchanged; a
    /// column only `want` has gets a fresh one past the end.
    ///
    /// This is the whole reason ids are stable. With positional columns, adding
    /// `:a` in front would move every existing column and every chunk in the
    /// table would have to be rewritten to say the same thing it already said.
    fn rebase_schema(&mut self, have: Value, want: Value) -> Value {
        let base = self.mark();
        let hi = self.push(have);
        let wi = self.push(want);
        let n = self.schema_len(self.r(wi));
        let mut width = self.schema_width(self.r(hi));
        let ids = self.empty_vec();
        let di = self.push(ids);
        let idx = self.empty_map();
        let ii = self.push(idx);
        for c in 0..n {
            let name = self.schema_name_at(self.r(wi), c);
            let ni = self.push(name);
            let old = self.schema_id(self.r(hi), self.r(ni));
            let id = if old < self.schema_width(self.r(hi)) {
                old
            } else {
                let fresh = width;
                width += 1;
                fresh
            };
            let dv = self.vec_conj(self.r(di), Value::fixnum(id as i64));
            self.set_r(di, dv);
            let nv = self.r(ni);
            let m = self.map_assoc(self.r(ii), nv, Value::fixnum(id as i64));
            self.set_r(ii, m);
            self.pop_to(ni);
        }
        let a = self.alloc(TY_SCHEMA, SC_LEN);
        let sc = Value::heap(a);
        let si = self.push(sc);
        let names = self.slot(self.r(wi), SC_NAMES);
        self.set(self.r(si), SC_NAMES, names);
        let types = self.slot(self.r(wi), SC_TYPES);
        self.set(self.r(si), SC_TYPES, types);
        let (iv, dv) = (self.r(ii), self.r(di));
        self.set(self.r(si), SC_INDEX, iv);
        self.set(self.r(si), SC_IDS, dv);
        self.set(self.r(si), SC_WIDTH, Value::fixnum(width as i64));
        let out = self.r(si);
        self.pop_to(base);
        out
    }

    /// `t` under `want`, sharing every column both schemas keep.
    ///
    /// `defaults` supplies a value for each column `want` adds; it is stored
    /// ONCE PER CHUNK as a constant column, which is what makes adding a
    /// defaulted column to a million-row table cheap. A column that needs a
    /// value per row is not this function's job -- `flint.table/migrate`
    /// rebuilds for that, and says so.
    pub fn table_migrate(&mut self, t: Value, want: Value, defaults: Value) -> Value {
        let base = self.mark();
        let ti = self.push(t);
        let wi = self.push(want);
        let dfi = self.push(defaults);
        let have = self.slot(self.r(ti), TB_SCHEMA);
        let hi = self.push(have);
        let n = self.schema_len(self.r(wi));

        // Refuse first, and completely, before anything is built.
        for c in 0..n {
            let name = self.schema_name_at(self.r(wi), c);
            let nmi = self.push(name);
            let want_ty = self.schema_type_at(self.r(wi), c);
            let old = self.schema_id(self.r(hi), self.r(nmi));
            if old < self.schema_width(self.r(hi)) {
                // A carried column keeps its VALUES, so it must keep its type.
                let hc = self.schema_pos_of(self.r(hi), self.r(nmi));
                let have_ty = self.schema_type_at(self.r(hi), hc);
                if !self.eq(have_ty, want_ty) {
                    let nm = self.kw_name(self.r(nmi));
                    let ht = self.kw_name(have_ty);
                    let wt = self.kw_name(want_ty);
                    self.pop_to(base);
                    let msg = alloc::format!(
                        "column :{nm} holds :{ht} and the new schema declares :{wt}; \
                         a type change needs a value per row, so migrate with a \
                         function: (migrate t s (fn [row] ...))"
                    );
                    return self.throw_str("IllegalArgumentException", &msg);
                }
            } else {
                let dv = self.map_get(self.r(dfi), self.r(nmi), NOT_FOUND);
                if dv == NOT_FOUND {
                    let nm = self.kw_name(self.r(nmi));
                    self.pop_to(base);
                    let msg = alloc::format!(
                        "the new schema adds :{nm} and the table has no values for it; \
                         give it a default -- (migrate t s {{:{nm} v}}) -- or compute \
                         one per row: (migrate t s (fn [row] ...))"
                    );
                    return self.throw_str("IllegalArgumentException", &msg);
                }
                if !self.type_ok(want_ty, dv) {
                    let nm = self.kw_name(self.r(nmi));
                    let wt = self.kw_name(want_ty);
                    let gk = self.kind_of(dv);
                    let gt = self.kw_name(gk);
                    self.pop_to(base);
                    let msg = alloc::format!(
                        "the default for :{nm} is a {gt} and the column holds :{wt}"
                    );
                    return self.throw_str("IllegalArgumentException", &msg);
                }
            }
            self.pop_to(nmi);
        }

        let rebased = self.rebase_schema(self.r(hi), self.r(wi));
        let ri = self.push(rebased);
        let width = self.schema_width(self.r(ri));
        let chunks = self.slot(self.r(ti), TB_CHUNKS);
        let ci = self.push(chunks);
        let nch = self.vec_count(self.r(ci));
        let out_chunks = self.empty_vec();
        let oi = self.push(out_chunks);
        for k in 0..nch {
            if !self.charge_checked(1, "migrate") {
                self.pop_to(base);
                return NIL;
            }
            let ch = self.vec_nth(self.r(ci), k, NIL);
            let chi = self.push(ch);
            let rows = self.chunk_rows(self.r(chi));
            let nc = self.new_chunk(width, rows);
            let ni = self.push(nc);
            for c in 0..n {
                let id = self.schema_id_at(self.r(ri), c);
                let name = self.schema_name_at(self.r(ri), c);
                let old = self.schema_id(self.r(hi), name);
                if old < self.schema_width(self.r(hi)) {
                    // SHARED, column object and encoding both. Nothing is
                    // copied and nothing is scanned; this is the head-only
                    // edit, and dropping a column is the case where the loop
                    // simply never reaches the old slot.
                    let col = self.slot(self.r(chi), CH_BASE + old as u32);
                    self.set(self.r(ni), CH_BASE + id, col);
                    let e = self.chunk_enc(self.r(chi), old as u32);
                    let ne = self.slot(self.r(ni), CH_ENC);
                    self.set(ne, id, Value::fixnum(e as i64));
                } else {
                    let dv = self.map_get(self.r(dfi), name, NIL);
                    self.set(self.r(ni), CH_BASE + id, dv);
                    let ne = self.slot(self.r(ni), CH_ENC);
                    self.set(ne, id, Value::fixnum(ENC_CONST as i64));
                }
            }
            let nv = self.r(ni);
            let ov = self.vec_conj(self.r(oi), nv);
            self.set_r(oi, ov);
            self.pop_to(chi);
        }
        let count = self.table_count(self.r(ti));
        let a = self.alloc(TY_TABLE, TB_LEN);
        let nt = Value::heap(a);
        let nti = self.push(nt);
        let (sv, cv) = (self.r(ri), self.r(oi));
        self.set(self.r(nti), TB_SCHEMA, sv);
        self.set(self.r(nti), TB_CHUNKS, cv);
        self.set(self.r(nti), TB_COUNT, Value::fixnum(count as i64));
        let off = self.slot(self.r(ti), TB_OFFSET);
        self.set(self.r(nti), TB_OFFSET, off);
        let out = self.r(nti);
        self.pop_to(base);
        out
    }

    // ---------------------------------------------------------------- step 7
    //
    // THE TRANSIENT. Appending through the persistent path copies the whole
    // chunk for every row, so filling one 256-row chunk copies it 256 times.
    // MEASURED on 20 000 rows: 49 061 192 bytes allocated and 23 collections,
    // against 3 254 584 and 4 for the bulk path.
    //
    // GAS SAID THE OPPOSITE -- 381 220 for the appends against 741 240 for the
    // bulk build -- because gas counts interpreter steps and the copying
    // happens INSIDE a builtin, where one step is charged however much work it
    // does. Worth knowing before trusting gas as a proxy for work: it measures
    // the program, not the runtime underneath it.

    // ---------------------------------------------------------------- step 8
    //
    // The column API -- the half that makes a column store worth having rather
    // than merely compact. Everything here reaches the COLUMN and never builds
    // a row: a scan of one field of a million-row table should touch a million
    // values and nothing else.

    /// `(slice t from to)` -- rows `[from, to)`, SHARING every chunk it spans.
    ///
    /// Chunks outside the range are dropped, so a slice does not retain the
    /// table; chunks inside it are the same objects. The row offset in the head
    /// is what lets the range start anywhere without rebuilding a chunk.
    pub fn table_slice(&mut self, t: Value, from: i64, to: i64) -> Value {
        let n = self.table_count(t) as i64;
        if from < 0 || to > n || from > to {
            let msg = alloc::format!(
                "slice [{from} {to}) is outside a table of {n} rows"
            );
            return self.throw_str("IndexOutOfBoundsException", &msg);
        }
        let base = self.mark();
        let ti = self.push(t);
        if from == to {
            let s = self.slot(self.r(ti), TB_SCHEMA);
            let si = self.push(s);
            let sv = self.r(si);
            let out = self.new_table(sv, self.empty_vec());
            self.pop_to(base);
            return out;
        }
        let off = self.table_offset(self.r(ti));
        let first = (off + from as u32) >> CHUNK_SHIFT;
        let last = (off + to as u32 - 1) >> CHUNK_SHIFT;
        let chunks = self.slot(self.r(ti), TB_CHUNKS);
        let ci = self.push(chunks);
        let kept = self.empty_vec();
        let ki = self.push(kept);
        for k in first..=last {
            let ch = self.vec_nth(self.r(ci), k, NIL);
            let nv = self.vec_conj(self.r(ki), ch);
            self.set_r(ki, nv);
        }
        let s = self.slot(self.r(ti), TB_SCHEMA);
        let si = self.push(s);
        let a = self.alloc(TY_TABLE, TB_LEN);
        let nt = Value::heap(a);
        let ni = self.push(nt);
        let (sv, kv) = (self.r(si), self.r(ki));
        self.set(self.r(ni), TB_SCHEMA, sv);
        self.set(self.r(ni), TB_CHUNKS, kv);
        self.set(self.r(ni), TB_COUNT, Value::fixnum(to - from));
        self.set(
            self.r(ni),
            TB_OFFSET,
            Value::fixnum(((off + from as u32) & (CHUNK - 1)) as i64),
        );
        let out = self.r(ni);
        self.pop_to(base);
        out
    }

    /// `(reduce-column t :col f init)` -- `f` over one column, without building
    /// a row or a ref for any of them. This is the operation the type exists
    /// for: scanning one field should cost one field.
    pub fn table_reduce_column(&mut self, t: Value, name: Value, f: Value, init: Value) -> Value {
        let base = self.mark();
        let ti = self.push(t);
        let fi = self.push(f);
        let acc = self.push(init);
        let s = self.slot(self.r(ti), TB_SCHEMA);
        let id = self.schema_id(s, name);
        if id >= self.schema_width(s) {
            let nm = self.kw_name(name);
            let cols = self.column_list(s);
            self.pop_to(base);
            let msg = alloc::format!("no column :{nm}; the columns are {cols}");
            return self.throw_str("IllegalArgumentException", &msg);
        }
        let n = self.table_count(self.r(ti));
        for i in 0..n {
            if !self.charge_tick(i as u64, 1, "reduce-column") {
                self.pop_to(base);
                return NIL;
            }
            let v = self.table_cell(self.r(ti), id, i);
            let vi = self.push(v);
            let argv = [self.r(acc), self.r(vi)];
            let fv = self.r(fi);
            let nv = self.invoke(fv, &argv);
            if !self.thrown.is_nil() {
                self.pop_to(base);
                return NIL;
            }
            self.set_r(acc, nv);
            self.pop_to(vi);
        }
        let out = self.r(acc);
        self.pop_to(base);
        out
    }

}
