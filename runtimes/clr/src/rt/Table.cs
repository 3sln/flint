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

    static bool knownType(Rt rt, long t) {
        foreach (string n in new string[]{"int", "double", "string", "bool", "keyword", "any"})
            if (t == Str.Keyword(rt, null, n)) return true;
        return false;
    }

    /// Does `v` belong in a column of type `t`? `:any` takes anything, which is
    /// the escape hatch a closed schema needs to stay usable.
    public static bool typeOk(Rt rt, long t, long v) {
        if (t == Str.Keyword(rt, null, "any")) return true;
        if (t == Str.Keyword(rt, null, "int")) return Num.IsInt(rt, v);
        if (t == Str.Keyword(rt, null, "double")) return Val.IsDouble(v);
        if (t == Str.Keyword(rt, null, "string")) return Str.IsString(rt, v);
        if (t == Str.Keyword(rt, null, "bool")) return v == Val.True || v == Val.False;
        if (t == Str.Keyword(rt, null, "keyword")) return rt.TypeP(5, v);
        return false;
    }

    /// `[[name type] ...]` -> a schema. Names must be keywords and DISTINCT --
    /// two columns of one name would make `get` ambiguous and the index would
    /// silently keep the later one.
    public static long newSchema(Rt rt, long pairs) {
        int bas = rt.Mark();
        int pi = rt.Push(pairs);
        int n = Vec.Count(rt, rt.R(pi));
        int ni = rt.Push(emptyVec(rt));
        int ti = rt.Push(emptyVec(rt));
        int ii = rt.Push(emptyMap(rt));
        int di = rt.Push(emptyVec(rt));
        for (int i = 0; i < n; i++) {
            long pair = Vec.Nth(rt, rt.R(pi), i, Val.NotFound);
            if (!rt.TypeP(8, pair) || Vec.Count(rt, pair) != 2) {
                rt.PopTo(bas);
                return rt.ThrowStr("IllegalArgumentException",
                    "a schema is [[name type] ...]; this entry is not a name and a type");
            }
            long nm = Vec.Nth(rt, pair, 0, Val.NotFound), tp = Vec.Nth(rt, pair, 1, Val.NotFound);
            if (!rt.TypeP(5, nm)) {
                rt.PopTo(bas);
                return rt.ThrowStr("IllegalArgumentException", "a column name must be a keyword");
            }
            if (!knownType(rt, tp)) {
                string shown = kwName(rt, tp);
                rt.PopTo(bas);
                return rt.ThrowStr("IllegalArgumentException",
                    "no such column type :" + shown
                        + "; the types are :int :double :string :bool :keyword :any");
            }
            if (!Val.IsNil(Mapread.MapGet(rt, rt.R(ii), nm, Val.Nil))) {
                string shown = kwName(rt, nm);
                rt.PopTo(bas);
                return rt.ThrowStr("IllegalArgumentException",
                    "the column :" + shown + " is named twice");
            }
            rt.SetR(ni, Vec.Conj(rt, rt.R(ni), nm));
            rt.SetR(ti, Vec.Conj(rt, rt.R(ti), tp));
            rt.SetR(ii, Mapwrite.MapAssoc(rt, rt.R(ii), nm, Val.Fixnum(i)));
            rt.SetR(di, Vec.Conj(rt, rt.R(di), Val.Fixnum(i)));
        }
        long s = Conc.NewObj(rt, Obj.TySchema, SC_LEN);
        int si = rt.Push(s);
        set(rt, rt.R(si), SC_NAMES, rt.R(ni));
        set(rt, rt.R(si), SC_TYPES, rt.R(ti));
        set(rt, rt.R(si), SC_INDEX, rt.R(ii));
        set(rt, rt.R(si), SC_IDS, rt.R(di));
        set(rt, rt.R(si), SC_WIDTH, Val.Fixnum(n));
        long outv = rt.R(si);
        rt.PopTo(bas);
        return outv;
    }

    static string kwName(Rt rt, long v) {
        long n = rt.NameOf(v);
        return Str.IsString(rt, n) ? Str.Text(rt, n) : "?";
    }

    /// The schema's column names as `:a :b :c`, for a message that has to say
    /// what the columns ARE rather than only that the key was not one.
    static string columnList(Rt rt, long s) {
        var b = new System.Text.StringBuilder();
        int n = schemaLen(rt, s);
        for (int c = 0; c < n; c++) {
            if (c > 0) b.Append(' ');
            b.Append(':').Append(kwName(rt, schemaNameAt(rt, s, c)));
        }
        return b.ToString();
    }

    /// The KIND of what arrived, not just that it was wrong: "holds :int and
    /// was given a string" is the difference between a message you can act on
    /// and one you have to reproduce first.
    static string columnTypeError(Rt rt, long name, long want, long got, int row) {
        return "row " + row + ", column :" + kwName(rt, name) + " holds :" + kwName(rt, want)
            + " and was given a " + kwName(rt, rt.KindOf(got));
    }

    /// Build a table from `rows`, a vector of maps.
    public static long newTable(Rt rt, long schema, long rows) {
        int bas = rt.Mark();
        int si = rt.Push(schema);
        int ri = rt.Push(rows);
        int ncols = schemaLen(rt, rt.R(si));
        int width = schemaWidth(rt, rt.R(si));
        int nrows = Vec.Count(rt, rt.R(ri));
        int ci = rt.Push(emptyVec(rt));
        int row = 0;
        while (row < nrows) {
            // Per CHUNK rather than per row: a chunk is 256 rows of bounded
            // work, so the overshoot is bounded at 256 rows.
            if (!rt.ChargeChecked(1, "table")) { rt.PopTo(bas); return Val.Nil; }
            int take = System.Math.Min(CHUNK, nrows - row);
            long ch = newChunk(rt, width, take);
            int chi = rt.Push(ch);
            for (int c = 0; c < ncols; c++) {
                int id = schemaIdAt(rt, rt.R(si), c);
                long col = Conc.NewObj(rt, Obj.TyNode, take);
                int coli = rt.Push(col);
                for (int k = 0; k < take; k++) {
                    long rowv = Vec.Nth(rt, rt.R(ri), row + k, Val.NotFound);
                    int rvi = rt.Push(rowv);
                    long name = schemaNameAt(rt, rt.R(si), c);
                    long val = Mapread.MapGet(rt, rt.R(rvi), name, Val.Nil);
                    long tp = schemaTypeAt(rt, rt.R(si), c);
                    if (!typeOk(rt, tp, val)) {
                        string msg = columnTypeError(rt, name, tp, val, row + k);
                        rt.PopTo(bas);
                        return rt.ThrowStr("IllegalArgumentException", msg);
                    }
                    set(rt, rt.R(coli), k, val);
                    rt.PopTo(rvi);
                }
                set(rt, rt.R(chi), CH_BASE + id, rt.R(coli));
                collapse(rt, rt.R(chi), id);
                rt.PopTo(coli);
            }
            rt.SetR(ci, Vec.Conj(rt, rt.R(ci), rt.R(chi)));
            rt.PopTo(chi);
            row += take;
        }
        long t = Conc.NewObj(rt, Obj.TyTable, TB_LEN);
        int ti = rt.Push(t);
        set(rt, rt.R(ti), TB_SCHEMA, rt.R(si));
        set(rt, rt.R(ti), TB_CHUNKS, rt.R(ci));
        set(rt, rt.R(ti), TB_COUNT, Val.Fixnum(nrows));
        set(rt, rt.R(ti), TB_OFFSET, Val.Fixnum(0));
        long outv = rt.R(ti);
        rt.PopTo(bas);
        return outv;
    }

    /// Does `row` fit `s`? The three refusals are separate because they are
    /// three different mistakes: a column you forgot, a key that is not a
    /// column, and a value of the wrong type. One "invalid row" for all three
    /// is the message this codebase keeps replacing.
    public static bool checkRow(Rt rt, long s, long row, int rowno) {
        int bas = rt.Mark();
        int si = rt.Push(s);
        int ri = rt.Push(row);
        if (!Mapcore.IsMap(rt, rt.R(ri))) {
            string kn = kwName(rt, rt.KindOf(rt.R(ri)));
            rt.PopTo(bas);
            rt.ThrowStr("IllegalArgumentException",
                "a table row is a map, and row " + rowno + " is a " + kn);
            return false;
        }
        int n = schemaLen(rt, rt.R(si));
        rt.ChargeWork(n);
        for (int c = 0; c < n; c++) {
            long val = rowColumn(rt, rt.R(si), rt.R(ri), c);
            int vi = rt.Push(val);
            long name = schemaNameAt(rt, rt.R(si), c);
            int nmi = rt.Push(name);
            if (rt.R(vi) == Val.NotFound) {
                string nm = kwName(rt, rt.R(nmi)), cols = columnList(rt, rt.R(si));
                rt.PopTo(bas);
                rt.ThrowStr("IllegalArgumentException", "row " + rowno + " has no :" + nm
                    + "; a table is closed, so every row has every column, and the columns are "
                    + cols);
                return false;
            }
            long tp = schemaTypeAt(rt, rt.R(si), c);
            if (!typeOk(rt, tp, rt.R(vi))) {
                string msg = columnTypeError(rt, rt.R(nmi), tp, rt.R(vi), rowno);
                rt.PopTo(bas);
                rt.ThrowStr("IllegalArgumentException", msg);
                return false;
            }
            rt.PopTo(vi);
        }
        // Every column is present, so a wider row has a key that is not one.
        // Counted first and hunted only when the count disagrees, because the
        // hunt walks the row and the good path must not.
        bool extra = isTableRef(rt, rt.R(ri))
            ? schemaLen(rt, rt.Slot(rt.R(ri), RF_SCHEMA)) > n
            : Mapcore.MapCount(rt, rt.R(ri)) > n;
        if (extra) {
            long bad = firstForeignKey(rt, rt.R(si), rt.R(ri));
            string nm = kwName(rt, bad), cols = columnList(rt, rt.R(si));
            rt.PopTo(bas);
            rt.ThrowStr("IllegalArgumentException", "row " + rowno + " has :" + nm
                + ", which is not a column; a table is closed, and the columns are " + cols);
            return false;
        }
        rt.PopTo(bas);
        return true;
    }

    /// The first key of `row` the schema does not name. Only called once a
    /// count has already proved there is one.
    static long firstForeignKey(Rt rt, long s, long row) {
        int bas = rt.Mark();
        int si = rt.Push(s);
        if (isTableRef(rt, row)) {
            int rsi = rt.Push(rt.Slot(row, RF_SCHEMA));
            int n = schemaLen(rt, rt.R(rsi));
            for (int c = 0; c < n; c++) {
                long name = schemaNameAt(rt, rt.R(rsi), c);
                // ABSENT IS THE WIDTH -- see the Java and Rust copies.
                if (schemaId(rt, rt.R(si), name) >= schemaWidth(rt, rt.R(si))) {
                    rt.PopTo(bas); return name;
                }
            }
            rt.PopTo(bas);
            return Val.Nil;
        }
        int qi = rt.Push(Seqs.Seq(rt, row));
        while (!Val.IsNil(rt.R(qi))) {
            long e = Seqs.First(rt, rt.R(qi));
            int ei = rt.Push(e);
            long k = rt.Slot(rt.R(ei), 0);
            if (schemaId(rt, rt.R(si), k) >= schemaWidth(rt, rt.R(si))) {
                rt.PopTo(bas); return k;
            }
            rt.PopTo(ei);
            rt.SetR(qi, Seqs.Next(rt, rt.R(qi)));
        }
        rt.PopTo(bas);
        return Val.Nil;
    }

    /// `(assoc table i row)`. `i` may be `count`, which appends -- the same
    /// rule a vector follows, so nothing new has to be learned to grow one.
    public static long tableAssoc(Rt rt, long t, long k, long row) {
        long? ix = Num.IsInt(rt, k) ? Val.AsFixnum(k) : (long?) null;
        if (ix == null) {
            string kn = kwName(rt, rt.KindOf(k));
            return rt.ThrowStr("IllegalArgumentException",
                "a table is indexed by row number and this key is a " + kn
                + "; to reach a column, index the row first: (assoc-in t [row :column] v)");
        }
        long i = ix.Value;
        int n = tableCount(rt, t);
        if (i < 0 || i > n) {
            return rt.ThrowStr("IndexOutOfBoundsException", "row " + i
                + " is out of range for a table of " + n
                + " rows; assoc may replace any row or append at " + n);
        }
        int bas = rt.Mark();
        int ti = rt.Push(t);
        int ri = rt.Push(row);
        int si = rt.Push(rt.Slot(rt.R(ti), TB_SCHEMA));
        if (!checkRow(rt, rt.R(si), rt.R(ri), (int) i)) { rt.PopTo(bas); return Val.Nil; }
        int idx = (int) i;
        int ci = rt.Push(rt.Slot(rt.R(ti), TB_CHUNKS));
        int phys = idx + tableOffset(rt, rt.R(ti));
        int which = phys >> CHUNK_SHIFT, within = phys & (CHUNK - 1);
        bool append = idx == tableCount(rt, rt.R(ti));
        int nchunks = Vec.Count(rt, rt.R(ci));
        if (append && which >= nchunks) {
            // A new chunk, one row wide: `chunkWithRow` grows an existing one,
            // and an empty table has none to grow.
            int width = schemaWidth(rt, rt.R(si));
            long ch = newChunk(rt, width, 1);
            int chi = rt.Push(ch);
            int ncols = schemaLen(rt, rt.R(si));
            for (int c = 0; c < ncols; c++)
                {
                    // HOISTED: the allocation must happen before the object's
                    // address is read, or the write lands on a forwarded copy.
                    long col1 = Conc.NewObj(rt, Obj.TyNode, 1);
                    set(rt, rt.R(chi), CH_BASE + schemaIdAt(rt, rt.R(si), c), col1);
                }
            writeRow(rt, rt.R(si), rt.R(chi), 0, rt.R(ri));
            // A one-row column is trivially constant, so a table grown row by
            // row starts every chunk collapsed.
            for (int c = 0; c < ncols; c++) collapse(rt, rt.R(chi), schemaIdAt(rt, rt.R(si), c));
            rt.SetR(ci, Vec.Conj(rt, rt.R(ci), rt.R(chi)));
            rt.PopTo(chi);
        } else {
            long ch = Vec.Nth(rt, rt.R(ci), which, Val.NotFound);
            int chi = rt.Push(ch);
            long nch = chunkWithRow(rt, rt.R(si), rt.R(chi), within, rt.R(ri), append);
            int nj = rt.Push(nch);
            rt.SetR(ci, Vec.Assoc(rt, rt.R(ci), which, rt.R(nj)));
            rt.PopTo(chi);
        }
        int count = tableCount(rt, rt.R(ti)) + (append ? 1 : 0);
        long nt = Conc.NewObj(rt, Obj.TyTable, TB_LEN);
        int ni = rt.Push(nt);
        set(rt, rt.R(ni), TB_SCHEMA, rt.R(si));
        set(rt, rt.R(ni), TB_CHUNKS, rt.R(ci));
        set(rt, rt.R(ni), TB_COUNT, Val.Fixnum(count));
        set(rt, rt.R(ni), TB_OFFSET, rt.Slot(rt.R(ti), TB_OFFSET));
        long outv = rt.R(ni);
        rt.PopTo(bas);
        return outv;
    }

    /// `(conj table row)` -- append, which is `assoc` at the end.
    public static long tableConj(Rt rt, long t, long row) {
        return tableAssoc(rt, t, Val.Fixnum(tableCount(rt, t)), row);
    }

    /// `(assoc row-ref k v)` -> a MAP. A ref is a VIEW; changing it makes an
    /// independent value and neither the chunk nor the table moves.
    public static long refAssoc(Rt rt, long r, long k, long v) {
        int bas = rt.Mark();
        int ri = rt.Push(r);
        int ki = rt.Push(k);
        int vi = rt.Push(v);
        long m = refToMap(rt, rt.R(ri));
        int mi = rt.Push(m);
        long outv = Mapwrite.MapAssoc(rt, rt.R(mi), rt.R(ki), rt.R(vi));
        rt.PopTo(bas);
        return outv;
    }
    // --- migration ----------------------------------------------------------
    //
    // A schema change makes a NEW TABLE. What makes it cheap is that a chunk
    // addresses its columns by stable id, so a column the new schema keeps is
    // the SAME COLUMN OBJECT, shared rather than copied.

    /// `want` REBASED onto `have`'s column ids: a column both schemas name
    /// keeps its id, so the chunks holding it can be shared unchanged.
    ///
    /// This is the whole reason ids are stable. With positional columns, adding
    /// one in front would move every existing column and every chunk would have
    /// to be rewritten to say what it already said.
    static long rebaseSchema(Rt rt, long have, long want) {
        int bas = rt.Mark();
        int hi = rt.Push(have);
        int wi = rt.Push(want);
        int n = schemaLen(rt, rt.R(wi));
        int width = schemaWidth(rt, rt.R(hi));
        int di = rt.Push(emptyVec(rt));
        int ii = rt.Push(emptyMap(rt));
        for (int c = 0; c < n; c++) {
            long name = schemaNameAt(rt, rt.R(wi), c);
            int nmi = rt.Push(name);
            int old = schemaId(rt, rt.R(hi), rt.R(nmi));
            int id = old < schemaWidth(rt, rt.R(hi)) ? old : width++;
            rt.SetR(di, Vec.Conj(rt, rt.R(di), Val.Fixnum(id)));
            rt.SetR(ii, Mapwrite.MapAssoc(rt, rt.R(ii), rt.R(nmi), Val.Fixnum(id)));
            rt.PopTo(nmi);
        }
        long sc = Conc.NewObj(rt, Obj.TySchema, SC_LEN);
        int si = rt.Push(sc);
        set(rt, rt.R(si), SC_NAMES, rt.Slot(rt.R(wi), SC_NAMES));
        set(rt, rt.R(si), SC_TYPES, rt.Slot(rt.R(wi), SC_TYPES));
        set(rt, rt.R(si), SC_INDEX, rt.R(ii));
        set(rt, rt.R(si), SC_IDS, rt.R(di));
        set(rt, rt.R(si), SC_WIDTH, Val.Fixnum(width));
        long outv = rt.R(si);
        rt.PopTo(bas);
        return outv;
    }

    /// `t` under `want`, sharing every column both schemas keep. `defaults`
    /// supplies a value for each column `want` adds, stored ONCE PER CHUNK as a
    /// constant column.
    public static long tableMigrate(Rt rt, long t, long want, long defaults) {
        int bas = rt.Mark();
        int ti = rt.Push(t);
        int wi = rt.Push(want);
        int dfi = rt.Push(defaults);
        int hi = rt.Push(rt.Slot(rt.R(ti), TB_SCHEMA));
        int n = schemaLen(rt, rt.R(wi));
        // Refuse first, and completely, before anything is built.
        for (int c = 0; c < n; c++) {
            long name = schemaNameAt(rt, rt.R(wi), c);
            int nmi = rt.Push(name);
            long wantTy = schemaTypeAt(rt, rt.R(wi), c);
            int old = schemaId(rt, rt.R(hi), rt.R(nmi));
            if (old < schemaWidth(rt, rt.R(hi))) {
                // A carried column keeps its VALUES, so it must keep its type.
                long haveTy = schemaTypeAt(rt, rt.R(hi), schemaPosOf(rt, rt.R(hi), rt.R(nmi)));
                if (!Eq.Equal(rt, haveTy, wantTy)) {
                    string nm = kwName(rt, rt.R(nmi)), ht = kwName(rt, haveTy), wt = kwName(rt, wantTy);
                    rt.PopTo(bas);
                    return rt.ThrowStr("IllegalArgumentException", "column :" + nm + " holds :" + ht
                        + " and the new schema declares :" + wt
                        + "; a type change needs a value per row, so migrate with a function:"
                        + " (migrate t s (fn [row] ...))");
                }
            } else {
                long dv = Mapread.MapGet(rt, rt.R(dfi), rt.R(nmi), Val.NotFound);
                if (dv == Val.NotFound) {
                    string nm = kwName(rt, rt.R(nmi));
                    rt.PopTo(bas);
                    return rt.ThrowStr("IllegalArgumentException", "the new schema adds :" + nm
                        + " and the table has no values for it; give it a default -- (migrate t s {:"
                        + nm + " v}) -- or compute one per row: (migrate t s (fn [row] ...))");
                }
                if (!typeOk(rt, wantTy, dv)) {
                    string nm = kwName(rt, rt.R(nmi)), wt = kwName(rt, wantTy),
                           gt = kwName(rt, rt.KindOf(dv));
                    rt.PopTo(bas);
                    return rt.ThrowStr("IllegalArgumentException",
                        "the default for :" + nm + " is a " + gt + " and the column holds :" + wt);
                }
            }
            rt.PopTo(nmi);
        }
        int ri = rt.Push(rebaseSchema(rt, rt.R(hi), rt.R(wi)));
        int width = schemaWidth(rt, rt.R(ri));
        int ci = rt.Push(rt.Slot(rt.R(ti), TB_CHUNKS));
        int nch = Vec.Count(rt, rt.R(ci));
        int oi = rt.Push(emptyVec(rt));
        for (int k = 0; k < nch; k++) {
            if (!rt.ChargeChecked(1, "migrate")) { rt.PopTo(bas); return Val.Nil; }
            int chi = rt.Push(Vec.Nth(rt, rt.R(ci), k, Val.NotFound));
            int rows = chunkRows(rt, rt.R(chi));
            int ni = rt.Push(newChunk(rt, width, rows));
            for (int c = 0; c < n; c++) {
                int id = schemaIdAt(rt, rt.R(ri), c);
                long name = schemaNameAt(rt, rt.R(ri), c);
                int old = schemaId(rt, rt.R(hi), name);
                if (old < schemaWidth(rt, rt.R(hi))) {
                    // SHARED, column object and encoding both. Nothing is
                    // copied and nothing is scanned: this is the head-only
                    // edit, and dropping a column is the loop simply never
                    // reaching the old slot.
                    set(rt, rt.R(ni), CH_BASE + id, rt.Slot(rt.R(chi), CH_BASE + old));
                    set(rt, rt.Slot(rt.R(ni), CH_ENC), id, Val.Fixnum(chunkEnc(rt, rt.R(chi), old)));
                } else {
                    set(rt, rt.R(ni), CH_BASE + id, Mapread.MapGet(rt, rt.R(dfi), name, Val.Nil));
                    set(rt, rt.Slot(rt.R(ni), CH_ENC), id, Val.Fixnum(ENC_CONST));
                }
            }
            rt.SetR(oi, Vec.Conj(rt, rt.R(oi), rt.R(ni)));
            rt.PopTo(chi);
        }
        long nt = Conc.NewObj(rt, Obj.TyTable, TB_LEN);
        int nti = rt.Push(nt);
        set(rt, rt.R(nti), TB_SCHEMA, rt.R(ri));
        set(rt, rt.R(nti), TB_CHUNKS, rt.R(oi));
        set(rt, rt.R(nti), TB_COUNT, Val.Fixnum(tableCount(rt, rt.R(ti))));
        set(rt, rt.R(nti), TB_OFFSET, rt.Slot(rt.R(ti), TB_OFFSET));
        long outv = rt.R(nti);
        rt.PopTo(bas);
        return outv;
    }

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

    /// One column, as a vector. Reads the column runs directly -- no row is
    /// built and no ref is made.
    public static long tableColumn(Rt rt, long t, long name) {
        int bas = rt.Mark();
        int ti = rt.Push(t);
        long s = rt.Slot(rt.R(ti), TB_SCHEMA);
        int id = schemaId(rt, s, name);
        if (id >= schemaWidth(rt, s)) {
            string nm = kwName(rt, name), cols = columnList(rt, s);
            rt.PopTo(bas);
            return rt.ThrowStr("IllegalArgumentException",
                "no column :" + nm + "; the columns are " + cols);
        }
        int n = tableCount(rt, rt.R(ti));
        if (!rt.ChargeChecked(n, "column")) { rt.PopTo(bas); return Val.Nil; }
        int oi = rt.Push(emptyVec(rt));
        for (int i = 0; i < n; i++)
            rt.SetR(oi, Vec.Conj(rt, rt.R(oi), tableCell(rt, rt.R(ti), id, i)));
        long outv = rt.R(oi);
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

    /// A table from COLUMNS rather than rows -- what the wire decoder builds
    /// into. Going through rows would build a map per row only to take it apart
    /// again. The values are still CHECKED: a decoder that skipped that would
    /// be a way to make a table that is not closed.
    public static long tableFromColumns(Rt rt, long schema, long cols, int nrows) {
        int bas = rt.Mark();
        int si = rt.Push(schema);
        int ci = rt.Push(cols);
        int ncols = schemaLen(rt, rt.R(si));
        int width = schemaWidth(rt, rt.R(si));
        int ki = rt.Push(emptyVec(rt));
        int row = 0;
        while (row < nrows) {
            if (!rt.ChargeChecked(1, "table")) { rt.PopTo(bas); return Val.Nil; }
            int take = System.Math.Min(CHUNK, nrows - row);
            int chi = rt.Push(newChunk(rt, width, take));
            for (int c = 0; c < ncols; c++) {
                int id = schemaIdAt(rt, rt.R(si), c);
                int sj = rt.Push(Vec.Nth(rt, rt.R(ci), c, Val.NotFound));
                long tp = schemaTypeAt(rt, rt.R(si), c);
                int cj = rt.Push(Conc.NewObj(rt, Obj.TyNode, take));
                for (int k = 0; k < take; k++) {
                    long v = Vec.Nth(rt, rt.R(sj), row + k, Val.NotFound);
                    if (!typeOk(rt, tp, v)) {
                        string msg = columnTypeError(rt, schemaNameAt(rt, rt.R(si), c), tp, v, row + k);
                        rt.PopTo(bas);
                        return rt.ThrowStr("IllegalArgumentException", msg);
                    }
                    set(rt, rt.R(cj), k, v);
                }
                set(rt, rt.R(chi), CH_BASE + id, rt.R(cj));
                collapse(rt, rt.R(chi), id);
                rt.PopTo(sj);
            }
            rt.SetR(ki, Vec.Conj(rt, rt.R(ki), rt.R(chi)));
            rt.PopTo(chi);
            row += take;
        }
        long t = Conc.NewObj(rt, Obj.TyTable, TB_LEN);
        int ti = rt.Push(t);
        set(rt, rt.R(ti), TB_SCHEMA, rt.R(si));
        set(rt, rt.R(ti), TB_CHUNKS, rt.R(ki));
        set(rt, rt.R(ti), TB_COUNT, Val.Fixnum(nrows));
        set(rt, rt.R(ti), TB_OFFSET, Val.Fixnum(0));
        long outv = rt.R(ti);
        rt.PopTo(bas);
        return outv;
    }

    // --- the transient ------------------------------------------------------
    //
    // Appending through the persistent path copies the chunk per row, which is
    // 256 copies per chunk: 49 061 464 bytes to build 20 000 rows against
    // 3 082 984 through here (`doc/decisions/0026` step 7).

}
