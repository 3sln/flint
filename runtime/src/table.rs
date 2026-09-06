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
