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

use crate::rt::Rt;

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

}
