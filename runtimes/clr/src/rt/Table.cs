namespace Flint.Rt;

using _3sln.Flint.Kgen.Rt;

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
/// path-copies, indexes and counts. A CHUNK is a `Obj.TyNode` of per-column
/// `Obj.TyNode` runs, because a chunk mixing unboxed and boxed inline would need a
/// layout the runtime does not have.
public static class Table {

    /// Rows per chunk. A power of two so the row-to-chunk split is a shift.
    public const int CHUNK = 256, CHUNK_SHIFT = 8;

    // Schema slots. A column is addressed in a chunk by a stable ID, not by its
    // position: `SC_IDS` is parallel to `SC_NAMES`, `SC_INDEX` maps name -> id,
    // and `SC_WIDTH` is how many column slots a chunk carries. At construction
    // the id IS the position; they part company under migration, which is the
    // point -- dropping a column is then a head-only edit.
    public const int SC_NAMES = 0, SC_TYPES = 1, SC_INDEX = 2, SC_IDS = 3,
        SC_WIDTH = 4, SC_LEN = 5;

    // Table slots. `TB_OFFSET` is the first row's index within the first chunk,
    // always less than `CHUNK`: it is what lets `slice` SHARE every chunk it
    // spans instead of rebuilding them.
    public const int TB_SCHEMA = 0, TB_CHUNKS = 1, TB_COUNT = 2,
        TB_OFFSET = 3, TB_LEN = 4;

    // Chunk slots: `[nrows, encodings, col...]`. The ENCODINGS node says how
    // each column is written down -- the schema decides what a column MEANS,
    // the chunk decides how it is stored, and may change its mind per chunk
    // without the table's meaning moving.
    public const int CH_ROWS = 0, CH_ENC = 1, CH_BASE = 2;
    /// A value per row, in a flat `Obj.TyNode`.
    public const int ENC_FLAT = 0;
    /// ONE value for every row: the column slot holds the value itself. Adding
    /// a defaulted column to a million-row table writes one value per chunk.
    public const int ENC_CONST = 1;

    // Row-ref slots, and the transient's.
    public const int RF_SCHEMA = 0, RF_CHUNK = 1, RF_ROW = 2, RF_LEN = 3;

    // FOLDING A COLUMN, generated from `kin/tablefold.kin`.
    public static long tableReduceColumn(Rt rt, long t, long name, long f, long init) { return global::_3sln.Flint.Kgen.Rt.Tablefold.TableReduceColumn(rt, t, name, f, init); }

    // ROWS IN AND OUT, generated from `kin/tablerow.kin`.
    public static long tableSlice(Rt rt, long t, long from, long to) { return global::_3sln.Flint.Kgen.Rt.Tablerow.TableSlice(rt, t, from, to); }

    // MIGRATION, generated from `kin/tablemigrate.kin`.
    public static long tableMigrate(Rt rt, long t, long want, long defaults) { return global::_3sln.Flint.Kgen.Rt.Tablemigrate.TableMigrate(rt, t, want, defaults); }

    // BUILDING IN BULK, generated from `kin/tablebuild.kin`.
    public static long newTable(Rt rt, long schema, long rows) { return global::_3sln.Flint.Kgen.Rt.Tablebuild.NewTable(rt, schema, rows); }
    public static long tableFromColumns(Rt rt, long schema, long cols, int nrows) { return global::_3sln.Flint.Kgen.Rt.Tablebuild.TableFromColumns(rt, schema, cols, nrows); }

    // MAKING A SCHEMA and reading a column -- see the Java copy.
    public static long newSchema(Rt rt, long pairs) { return global::_3sln.Flint.Kgen.Rt.Tablemake.NewSchema(rt, pairs); }
    public static long tableColumn(Rt rt, long t, long name) { return global::_3sln.Flint.Kgen.Rt.Tablemake.TableColumn(rt, t, name); }

    // THE CLOSED SET of `threads-and-ports`, generated from `kin/tablekind.kin`.

    // THE REFUSALS, generated from `kin/tablesay.kin` -- `checks`.

    // THE TRANSIENT, generated from `kin/tabletrans.kin`.

    // THE FILL HALF, generated from `kin/tablefill.kin`.

    // THE CHUNK HALF, generated from `kin/tablecell.kin` -- see the Java copy.

    // The ROW-REF HALF, generated from `kin/tableref.kin`.
    public static int schemaId(Rt rt, long s, long name) { return global::_3sln.Flint.Kgen.Rt.Tableref.SchemaId(rt, s, name); }
    public static long tableRef(Rt rt, long t, int i) { return global::_3sln.Flint.Kgen.Rt.Tableref.TableRef(rt, t, i); }
    public static long refGet(Rt rt, long r, long name, long dflt) { return global::_3sln.Flint.Kgen.Rt.Tableref.RefGet(rt, r, name, dflt); }
    public static long refToMap(Rt rt, long r) { return global::_3sln.Flint.Kgen.Rt.Tableref.RefToMap(rt, r); }

    // The ACCESSOR HALF, generated from `kin/tablemeta.kin`.
    public static bool isSchema(Rt rt, long v) { return global::_3sln.Flint.Kgen.Rt.Tablemeta.IsSchema(rt, v); }
    public static bool isTable(Rt rt, long v) { return global::_3sln.Flint.Kgen.Rt.Tablemeta.IsTable(rt, v); }
    public static bool isTableRef(Rt rt, long v) { return global::_3sln.Flint.Kgen.Rt.Tablemeta.IsTableRef(rt, v); }
    public static int schemaLen(Rt rt, long s) { return global::_3sln.Flint.Kgen.Rt.Tablemeta.SchemaLen(rt, s); }
    public static long schemaNameAt(Rt rt, long s, int c) { return global::_3sln.Flint.Kgen.Rt.Tablemeta.SchemaNameAt(rt, s, c); }
    public static long schemaTypeAt(Rt rt, long s, int c) { return global::_3sln.Flint.Kgen.Rt.Tablemeta.SchemaTypeAt(rt, s, c); }
    public static int tableCount(Rt rt, long t) { return global::_3sln.Flint.Kgen.Rt.Tablemeta.TableCount(rt, t); }
    public const int TT_SCHEMA = 0, TT_CHUNKS = 1, TT_COUNT = 2, TT_OPEN = 3,
        TT_LIVE = 4, TT_LEN = 5;

    // Through the collections' own constructors, not the singleton array: the
    // array is initialised by the image loader and reading it directly gave a
    // NIL empty map, so every `schemaId` lookup answered -1 while the column
    // NAMES read fine -- "no column :score; the columns are :id :score :tag",
    // which is a message that contradicts itself and says so.
    static void set(Rt rt, long obj, int i, long v) { rt.SetSlot(Val.AsHeap(obj), i, v); }

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
