namespace Flint.Rt;

using _3sln.Flint.Kgen.Rt;

/// Tables: columnar storage that is a value, ported from `runtime/src/table.rs`
/// (`doc/decisions/0026`).
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

    // ROWS IN AND OUT, generated from `kin/tablerow.kin`.
    public static long tableAssoc(Rt rt, long t, long k, long row) { return global::_3sln.Flint.Kgen.Rt.Tablerow.TableAssoc(rt, t, k, row); }
    public static long tableConj(Rt rt, long t, long row) { return global::_3sln.Flint.Kgen.Rt.Tablerow.TableConj(rt, t, row); }

    // MIGRATION, generated from `kin/tablemigrate.kin`.
    static long rebaseSchema(Rt rt, long have, long want) { return global::_3sln.Flint.Kgen.Rt.Tablemigrate.RebaseSchema(rt, have, want); }
    public static long tableMigrate(Rt rt, long t, long want, long defaults) { return global::_3sln.Flint.Kgen.Rt.Tablemigrate.TableMigrate(rt, t, want, defaults); }

    // BUILDING IN BULK, generated from `kin/tablebuild.kin`.
    public static long newTable(Rt rt, long schema, long rows) { return global::_3sln.Flint.Kgen.Rt.Tablebuild.NewTable(rt, schema, rows); }
    public static long tableFromColumns(Rt rt, long schema, long cols, int nrows) { return global::_3sln.Flint.Kgen.Rt.Tablebuild.TableFromColumns(rt, schema, cols, nrows); }

    // MAKING A SCHEMA and reading a column -- see the Java copy.
    public static long newSchema(Rt rt, long pairs) { return global::_3sln.Flint.Kgen.Rt.Tablemake.NewSchema(rt, pairs); }
    public static long refAssoc(Rt rt, long r, long k, long v) { return global::_3sln.Flint.Kgen.Rt.Tablemake.RefAssoc(rt, r, k, v); }
    public static long tableColumn(Rt rt, long t, long name) { return global::_3sln.Flint.Kgen.Rt.Tablemake.TableColumn(rt, t, name); }

    // THE CLOSED SET of `0005`, generated from `kin/tablekind.kin`.
    static bool knownType(Rt rt, long t) { return global::_3sln.Flint.Kgen.Rt.Tablekind.KnownType(rt, t); }
    public static bool typeOk(Rt rt, long t, long v) { return global::_3sln.Flint.Kgen.Rt.Tablekind.TypeOk(rt, t, v); }

    // THE REFUSALS, generated from `kin/tablesay.kin` -- `0032`.
    static string kwName(Rt rt, long v) { return global::_3sln.Flint.Kgen.Rt.Tablesay.KwName(rt, v); }
    static string columnList(Rt rt, long s) { return global::_3sln.Flint.Kgen.Rt.Tablesay.ColumnList(rt, s); }
    static string columnTypeError(Rt rt, long name, long want, long got, int row) { return global::_3sln.Flint.Kgen.Rt.Tablesay.ColumnTypeError(rt, name, want, got, row); }
    static long firstForeignKey(Rt rt, long s, long row) { return global::_3sln.Flint.Kgen.Rt.Tablesay.FirstForeignKey(rt, s, row); }
    public static bool checkRow(Rt rt, long s, long row, int rowno) { return global::_3sln.Flint.Kgen.Rt.Tablesay.CheckRow(rt, s, row, rowno); }

    // THE TRANSIENT, generated from `kin/tabletrans.kin`.
    public static long tableTransient(Rt rt, long t) { return global::_3sln.Flint.Kgen.Rt.Tabletrans.TableTransient(rt, t); }
    public static long ttableConj(Rt rt, long t, long row) { return global::_3sln.Flint.Kgen.Rt.Tabletrans.TtableConj(rt, t, row); }
    public static long ttablePersistent(Rt rt, long t) { return global::_3sln.Flint.Kgen.Rt.Tabletrans.TtablePersistent(rt, t); }
    public static int ttableCount(Rt rt, long t) { return global::_3sln.Flint.Kgen.Rt.Tabletrans.TtableCount(rt, t); }

    // THE FILL HALF, generated from `kin/tablefill.kin`.
    static void writeRow(Rt rt, long s, long ch, int k, long row) { global::_3sln.Flint.Kgen.Rt.Tablefill.WriteRow(rt, s, ch, k, row); }
    static long openChunk(Rt rt, long s) { return global::_3sln.Flint.Kgen.Rt.Tablefill.OpenChunk(rt, s); }
    static long chunkWithRow(Rt rt, long s, long ch, int k, long row, bool grow) { return global::_3sln.Flint.Kgen.Rt.Tablefill.ChunkWithRow(rt, s, ch, k, row, grow); }
    static long seal(Rt rt, long s, long open, int rows) { return global::_3sln.Flint.Kgen.Rt.Tablefill.Seal(rt, s, open, rows); }

    // THE CHUNK HALF, generated from `kin/tablecell.kin` -- see the Java copy.
    static long newChunk(Rt rt, int width, int rows) { return global::_3sln.Flint.Kgen.Rt.Tablecell.NewChunk(rt, width, rows); }
    static void collapse(Rt rt, long ch, int id) { global::_3sln.Flint.Kgen.Rt.Tablecell.Collapse(rt, ch, id); }
    static long rowColumn(Rt rt, long s, long row, int c) { return global::_3sln.Flint.Kgen.Rt.Tablecell.RowColumn(rt, s, row, c); }
    static int schemaPosOf(Rt rt, long s, long name) { return global::_3sln.Flint.Kgen.Rt.Tablecell.SchemaPosOf(rt, s, name); }
    static long tableCell(Rt rt, long t, int id, int i) { return global::_3sln.Flint.Kgen.Rt.Tablecell.TableCell(rt, t, id, i); }
    public static bool isTtable(Rt rt, long v) { return global::_3sln.Flint.Kgen.Rt.Tablecell.IsTtable(rt, v); }

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
    public static int schemaWidth(Rt rt, long s) { return global::_3sln.Flint.Kgen.Rt.Tablemeta.SchemaWidth(rt, s); }
    public static int schemaIdAt(Rt rt, long s, int c) { return global::_3sln.Flint.Kgen.Rt.Tablemeta.SchemaIdAt(rt, s, c); }
    public static long schemaNameAt(Rt rt, long s, int c) { return global::_3sln.Flint.Kgen.Rt.Tablemeta.SchemaNameAt(rt, s, c); }
    public static long schemaTypeAt(Rt rt, long s, int c) { return global::_3sln.Flint.Kgen.Rt.Tablemeta.SchemaTypeAt(rt, s, c); }
    public static bool schemaEq(Rt rt, long a, long b) { return global::_3sln.Flint.Kgen.Rt.Tablemeta.SchemaEq(rt, a, b); }
    public static int tableCount(Rt rt, long t) { return global::_3sln.Flint.Kgen.Rt.Tablemeta.TableCount(rt, t); }
    public static int tableOffset(Rt rt, long t) { return global::_3sln.Flint.Kgen.Rt.Tablemeta.TableOffset(rt, t); }
    public static int chunkRows(Rt rt, long ch) { return global::_3sln.Flint.Kgen.Rt.Tablemeta.ChunkRows(rt, ch); }
    static int chunkEnc(Rt rt, long ch, int id) { return global::_3sln.Flint.Kgen.Rt.Tablemeta.ChunkEnc(rt, ch, id); }
    public static long chunkGet(Rt rt, long ch, int id, int row) { return global::_3sln.Flint.Kgen.Rt.Tablemeta.ChunkGet(rt, ch, id, row); }
    public const int TT_SCHEMA = 0, TT_CHUNKS = 1, TT_COUNT = 2, TT_OPEN = 3,
        TT_LIVE = 4, TT_LEN = 5;

    // Through the collections' own constructors, not the singleton array: the
    // array is initialised by the image loader and reading it directly gave a
    // NIL empty map, so every `schemaId` lookup answered -1 while the column
    // NAMES read fine -- "no column :score; the columns are :id :score :tag",
    // which is a message that contradicts itself and says so.
    static long emptyVec(Rt rt) { return Vec.Empty(rt); }
    static long emptyMap(Rt rt) { return Maps.Empty(rt); }
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

    /// `(slice t from to)` -- rows `[from, to)`, SHARING every chunk it spans.
    /// Chunks outside the range are dropped, so a slice does not retain the
    /// table; the row offset in the head is what lets the range start anywhere
    /// without rebuilding a chunk.
    public static long tableSlice(Rt rt, long t, long from, long to) {
        int n = tableCount(rt, t);
        if (from < 0 || to > n || from > to) {
            return rt.ThrowStr("IndexOutOfBoundsException",
                "slice [" + from + " " + to + ") is outside a table of " + n + " rows");
        }
        int bas = rt.Mark();
        int ti = rt.Push(t);
        if (from == to) {
            long empty = newTable(rt, rt.Slot(rt.R(ti), TB_SCHEMA), emptyVec(rt));
            rt.PopTo(bas);
            return empty;
        }
        int off = tableOffset(rt, rt.R(ti));
        int first = (off + (int) from) >> CHUNK_SHIFT;
        int last = (off + (int) to - 1) >> CHUNK_SHIFT;
        int ci = rt.Push(rt.Slot(rt.R(ti), TB_CHUNKS));
        int ki = rt.Push(emptyVec(rt));
        for (int k = first; k <= last; k++)
            rt.SetR(ki, Vec.Conj(rt, rt.R(ki), Vec.Nth(rt, rt.R(ci), k, Val.NotFound)));
        int si = rt.Push(rt.Slot(rt.R(ti), TB_SCHEMA));
        long nt = Conc.NewObj(rt, Obj.TyTable, TB_LEN);
        int ni = rt.Push(nt);
        set(rt, rt.R(ni), TB_SCHEMA, rt.R(si));
        set(rt, rt.R(ni), TB_CHUNKS, rt.R(ki));
        set(rt, rt.R(ni), TB_COUNT, Val.Fixnum(to - from));
        set(rt, rt.R(ni), TB_OFFSET, Val.Fixnum((off + (int) from) & (CHUNK - 1)));
        long outv = rt.R(ni);
        rt.PopTo(bas);
        return outv;
    }

    /// `(reduce-column t :col f init)` -- `f` over one column, without building
    /// a row or a ref for any of them. This is the operation the type exists
    /// for: scanning one field should cost one field.
    public static long tableReduceColumn(Rt rt, long t, long name, long f, long init) {
        int bas = rt.Mark();
        int ti = rt.Push(t);
        int fi = rt.Push(f);
        int acc = rt.Push(init);
        long s = rt.Slot(rt.R(ti), TB_SCHEMA);
        int id = schemaId(rt, s, name);
        if (id >= schemaWidth(rt, s)) {
            string nm = kwName(rt, name), cols = columnList(rt, s);
            rt.PopTo(bas);
            return rt.ThrowStr("IllegalArgumentException",
                "no column :" + nm + "; the columns are " + cols);
        }
        int n = tableCount(rt, rt.R(ti));
        for (int i = 0; i < n; i++) {
            if (!rt.ChargeTick(i, 1, "reduce-column")) { rt.PopTo(bas); return Val.Nil; }
            int vi = rt.Push(tableCell(rt, rt.R(ti), id, i));
            long nv = rt.Call(rt.R(fi), new long[]{ rt.R(acc), rt.R(vi) });
            if (!Val.IsNil(rt.thrown)) { rt.PopTo(bas); return Val.Nil; }
            rt.SetR(acc, nv);
            rt.PopTo(vi);
        }
        long outv = rt.R(acc);
        rt.PopTo(bas);
        return outv;
    }

    // --- the transient ------------------------------------------------------
    //
    // Appending through the persistent path copies the chunk per row, which is
    // 256 copies per chunk: 49 061 464 bytes to build 20 000 rows against
    // 3 082 984 through here (`doc/decisions/0026` step 7).

}
