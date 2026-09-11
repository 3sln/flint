package com.flint.rt;

import com._3sln.flint.kgen.rt.Mapwrite;

import com._3sln.flint.kgen.rt.Mapread;

import com._3sln.flint.kgen.rt.Mapcore;

import static com.flint.rt.Obj.*;

/// Tables: columnar storage that is a value, ported from `runtime/src/table.rs`
/// (`DECISIONS.md#tables`).
///
/// A vector of maps from the outside; columnar chunks in a trie underneath,
/// with a CLOSED schema fixed when the table is built. Closed is the whole
/// design: a key outside the schema is refused and so is a value of the wrong
/// type, and from that one decision the ragged-row problem, the null bitmap and
/// per-chunk type inference all stop existing.
///
/// Two things are reused rather than written, exactly as in the Rust. The TRIE
/// is an ordinary flint vector whose elements are chunks -- it already
/// path-copies, indexes and counts. A CHUNK is a `TY_NODE` of per-column
/// `TY_NODE` runs, because a chunk mixing unboxed and boxed inline would need a
/// layout the runtime does not have.
public final class Table {
    private Table() {}

    /// Rows per chunk. A power of two so the row-to-chunk split is a shift.
    public static final int CHUNK = 256, CHUNK_SHIFT = 8;

    // Schema slots. A column is addressed in a chunk by a stable ID, not by its
    // position: `SC_IDS` is parallel to `SC_NAMES`, `SC_INDEX` maps name -> id,
    // and `SC_WIDTH` is how many column slots a chunk carries. At construction
    // the id IS the position; they part company under migration, which is the
    // point -- dropping a column is then a head-only edit.
    public static final int SC_NAMES = 0, SC_TYPES = 1, SC_INDEX = 2, SC_IDS = 3,
        SC_WIDTH = 4, SC_LEN = 5;

    // Table slots. `TB_OFFSET` is the first row's index within the first chunk,
    // always less than `CHUNK`: it is what lets `slice` SHARE every chunk it
    // spans instead of rebuilding them.
    public static final int TB_SCHEMA = 0, TB_CHUNKS = 1, TB_COUNT = 2,
        TB_OFFSET = 3, TB_LEN = 4;

    // Chunk slots: `[nrows, encodings, col...]`. The ENCODINGS node says how
    // each column is written down -- the schema decides what a column MEANS,
    // the chunk decides how it is stored, and may change its mind per chunk
    // without the table's meaning moving.
    public static final int CH_ROWS = 0, CH_ENC = 1, CH_BASE = 2;
    /// A value per row, in a flat `TY_NODE`.
    public static final int ENC_FLAT = 0;
    /// ONE value for every row: the column slot holds the value itself. Adding
    /// a defaulted column to a million-row table writes one value per chunk.
    public static final int ENC_CONST = 1;

    // Row-ref slots, and the transient's.
    public static final int RF_SCHEMA = 0, RF_CHUNK = 1, RF_ROW = 2, RF_LEN = 3;

    // FOLDING A COLUMN, generated from `kin/tablefold.kin`.
    public static long tableReduceColumn(Rt rt, long t, long name, long f, long init) { return com._3sln.flint.kgen.rt.Tablefold.tableReduceColumn(rt, t, name, f, init); }

    // ROWS IN AND OUT, generated from `kin/tablerow.kin`.
    public static long tableSlice(Rt rt, long t, long from, long to) { return com._3sln.flint.kgen.rt.Tablerow.tableSlice(rt, t, from, to); }
    public static long tableAssoc(Rt rt, long t, long k, long row) { return com._3sln.flint.kgen.rt.Tablerow.tableAssoc(rt, t, k, row); }
    public static long tableConj(Rt rt, long t, long row) { return com._3sln.flint.kgen.rt.Tablerow.tableConj(rt, t, row); }

    // MIGRATION, generated from `kin/tablemigrate.kin`.
    static long rebaseSchema(Rt rt, long have, long want) { return com._3sln.flint.kgen.rt.Tablemigrate.rebaseSchema(rt, have, want); }
    public static long tableMigrate(Rt rt, long t, long want, long defaults) { return com._3sln.flint.kgen.rt.Tablemigrate.tableMigrate(rt, t, want, defaults); }

    // BUILDING IN BULK, generated from `kin/tablebuild.kin`.
    public static long newTable(Rt rt, long schema, long rows) { return com._3sln.flint.kgen.rt.Tablebuild.newTable(rt, schema, rows); }
    public static long tableFromColumns(Rt rt, long schema, long cols, int nrows) { return com._3sln.flint.kgen.rt.Tablebuild.tableFromColumns(rt, schema, cols, nrows); }

    // MAKING A SCHEMA and reading a column, generated from `kin/tablemake.kin`.
    public static long newSchema(Rt rt, long pairs) { return com._3sln.flint.kgen.rt.Tablemake.newSchema(rt, pairs); }
    public static long refAssoc(Rt rt, long r, long k, long v) { return com._3sln.flint.kgen.rt.Tablemake.refAssoc(rt, r, k, v); }
    public static long tableColumn(Rt rt, long t, long name) { return com._3sln.flint.kgen.rt.Tablemake.tableColumn(rt, t, name); }

    // THE CLOSED SET of `threads-and-ports`, generated from `kin/tablekind.kin`.
    static boolean knownType(Rt rt, long t) { return com._3sln.flint.kgen.rt.Tablekind.knownType(rt, t); }
    public static boolean typeOk(Rt rt, long t, long v) { return com._3sln.flint.kgen.rt.Tablekind.typeOk(rt, t, v); }

    // THE REFUSALS, generated from `kin/tablesay.kin` -- `checks`.
    static String kwName(Rt rt, long v) { return com._3sln.flint.kgen.rt.Tablesay.kwName(rt, v); }
    static String columnList(Rt rt, long s) { return com._3sln.flint.kgen.rt.Tablesay.columnList(rt, s); }
    static String columnTypeError(Rt rt, long name, long want, long got, int row) { return com._3sln.flint.kgen.rt.Tablesay.columnTypeError(rt, name, want, got, row); }
    static long firstForeignKey(Rt rt, long s, long row) { return com._3sln.flint.kgen.rt.Tablesay.firstForeignKey(rt, s, row); }
    public static boolean checkRow(Rt rt, long s, long row, int rowno) { return com._3sln.flint.kgen.rt.Tablesay.checkRow(rt, s, row, rowno); }

    // THE TRANSIENT, generated from `kin/tabletrans.kin`.
    public static long tableTransient(Rt rt, long t) { return com._3sln.flint.kgen.rt.Tabletrans.tableTransient(rt, t); }
    public static long ttableConj(Rt rt, long t, long row) { return com._3sln.flint.kgen.rt.Tabletrans.ttableConj(rt, t, row); }
    public static long ttablePersistent(Rt rt, long t) { return com._3sln.flint.kgen.rt.Tabletrans.ttablePersistent(rt, t); }
    public static int ttableCount(Rt rt, long t) { return com._3sln.flint.kgen.rt.Tabletrans.ttableCount(rt, t); }

    // THE FILL HALF, generated from `kin/tablefill.kin`.
    static void writeRow(Rt rt, long s, long ch, int k, long row) { com._3sln.flint.kgen.rt.Tablefill.writeRow(rt, s, ch, k, row); }
    static long openChunk(Rt rt, long s) { return com._3sln.flint.kgen.rt.Tablefill.openChunk(rt, s); }
    static long chunkWithRow(Rt rt, long s, long ch, int k, long row, boolean grow) { return com._3sln.flint.kgen.rt.Tablefill.chunkWithRow(rt, s, ch, k, row, grow); }
    static long seal(Rt rt, long s, long open, int rows) { return com._3sln.flint.kgen.rt.Tablefill.seal(rt, s, open, rows); }

    // THE CHUNK HALF, generated from `kin/tablecell.kin`. `newObj` is gone
    // rather than shimmed: it was a second copy of `Conc.newObj`, and the
    // vocabulary now points at that one.
    static long newChunk(Rt rt, int width, int rows) { return com._3sln.flint.kgen.rt.Tablecell.newChunk(rt, width, rows); }
    static void collapse(Rt rt, long ch, int id) { com._3sln.flint.kgen.rt.Tablecell.collapse(rt, ch, id); }
    static long rowColumn(Rt rt, long s, long row, int c) { return com._3sln.flint.kgen.rt.Tablecell.rowColumn(rt, s, row, c); }
    static int schemaPosOf(Rt rt, long s, long name) { return com._3sln.flint.kgen.rt.Tablecell.schemaPosOf(rt, s, name); }
    static long tableCell(Rt rt, long t, int id, int i) { return com._3sln.flint.kgen.rt.Tablecell.tableCell(rt, t, id, i); }
    public static boolean isTtable(Rt rt, long v) { return com._3sln.flint.kgen.rt.Tablecell.isTtable(rt, v); }

    // The ROW-REF HALF, generated from `kin/tableref.kin`.
    public static int schemaId(Rt rt, long s, long name) { return com._3sln.flint.kgen.rt.Tableref.schemaId(rt, s, name); }
    public static long tableRef(Rt rt, long t, int i) { return com._3sln.flint.kgen.rt.Tableref.tableRef(rt, t, i); }
    public static long refGet(Rt rt, long r, long name, long dflt) { return com._3sln.flint.kgen.rt.Tableref.refGet(rt, r, name, dflt); }
    public static long refToMap(Rt rt, long r) { return com._3sln.flint.kgen.rt.Tableref.refToMap(rt, r); }

    // The ACCESSOR HALF, generated from `kin/tablemeta.kin`.
    public static boolean isSchema(Rt rt, long v) { return com._3sln.flint.kgen.rt.Tablemeta.isSchema(rt, v); }
    public static boolean isTable(Rt rt, long v) { return com._3sln.flint.kgen.rt.Tablemeta.isTable(rt, v); }
    public static boolean isTableRef(Rt rt, long v) { return com._3sln.flint.kgen.rt.Tablemeta.isTableRef(rt, v); }
    public static int schemaLen(Rt rt, long s) { return com._3sln.flint.kgen.rt.Tablemeta.schemaLen(rt, s); }
    public static int schemaWidth(Rt rt, long s) { return com._3sln.flint.kgen.rt.Tablemeta.schemaWidth(rt, s); }
    public static int schemaIdAt(Rt rt, long s, int c) { return com._3sln.flint.kgen.rt.Tablemeta.schemaIdAt(rt, s, c); }
    public static long schemaNameAt(Rt rt, long s, int c) { return com._3sln.flint.kgen.rt.Tablemeta.schemaNameAt(rt, s, c); }
    public static long schemaTypeAt(Rt rt, long s, int c) { return com._3sln.flint.kgen.rt.Tablemeta.schemaTypeAt(rt, s, c); }
    public static boolean schemaEq(Rt rt, long a, long b) { return com._3sln.flint.kgen.rt.Tablemeta.schemaEq(rt, a, b); }
    public static int tableCount(Rt rt, long t) { return com._3sln.flint.kgen.rt.Tablemeta.tableCount(rt, t); }
    public static int tableOffset(Rt rt, long t) { return com._3sln.flint.kgen.rt.Tablemeta.tableOffset(rt, t); }
    public static int chunkRows(Rt rt, long ch) { return com._3sln.flint.kgen.rt.Tablemeta.chunkRows(rt, ch); }
    static int chunkEnc(Rt rt, long ch, int id) { return com._3sln.flint.kgen.rt.Tablemeta.chunkEnc(rt, ch, id); }
    public static long chunkGet(Rt rt, long ch, int id, int row) { return com._3sln.flint.kgen.rt.Tablemeta.chunkGet(rt, ch, id, row); }
    public static final int TT_SCHEMA = 0, TT_CHUNKS = 1, TT_COUNT = 2, TT_OPEN = 3,
        TT_LIVE = 4, TT_LEN = 5;

    // Through the collections' own constructors, not the singleton array: the
    // array is initialised by the image loader and reading it directly gave a
    // NIL empty map, so every `schemaId` lookup answered -1 while the column
    // NAMES read fine -- "no column :score; the columns are :id :score :tag",
    // which is a message that contradicts itself and says so.
    static long emptyVec(Rt rt) { return Vec.empty(rt); }
    static long emptyMap(Rt rt) { return Maps.empty(rt); }
    static void set(Rt rt, long obj, int i, long v) { rt.setSlot(Val.asHeap(obj), i, v); }

    // --- migration ----------------------------------------------------------
    //
    // A schema change makes a NEW TABLE. What makes it cheap is that a chunk
    // addresses its columns by stable id, so a column the new schema keeps is
    // the SAME COLUMN OBJECT, shared rather than copied.

    // --- the column API -----------------------------------------------------
    //
    // The half that makes a column store worth having rather than merely
    // compact: everything here reaches the COLUMN and never builds a row.

    // --- the transient ------------------------------------------------------
    //
    // Appending through the persistent path copies the chunk per row, which is
    // 256 copies per chunk: 49 061 464 bytes to build 20 000 rows against
    // 3 082 984 through here (`DECISIONS.md#tables` step 7).

}
