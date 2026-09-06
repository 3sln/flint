package com.flint.rt;

import com._3sln.flint.kgen.rt.Mapwrite;

import com._3sln.flint.kgen.rt.Mapread;

import com._3sln.flint.kgen.rt.Mapcore;

import static com.flint.rt.Obj.*;

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

    static boolean knownType(Rt rt, long t) {
        for (String n : new String[]{"int", "double", "string", "bool", "keyword", "any"})
            if (t == Str.keyword(rt, null, n)) return true;
        return false;
    }

    /// Does `v` belong in a column of type `t`? `:any` takes anything, which is
    /// the escape hatch a closed schema needs to stay usable.
    public static boolean typeOk(Rt rt, long t, long v) {
        if (t == Str.keyword(rt, null, "any")) return true;
        if (t == Str.keyword(rt, null, "int")) return Num.isInt(rt, v);
        if (t == Str.keyword(rt, null, "double")) return Val.isDouble(v);
        if (t == Str.keyword(rt, null, "string")) return Str.isString(rt, v);
        if (t == Str.keyword(rt, null, "bool")) return v == Val.TRUE || v == Val.FALSE;
        if (t == Str.keyword(rt, null, "keyword")) return rt.typeP(5, v);
        return false;
    }

    /// `[[name type] ...]` -> a schema. Names must be keywords and DISTINCT --
    /// two columns of one name would make `get` ambiguous and the index would
    /// silently keep the later one.
    public static long newSchema(Rt rt, long pairs) {
        int base = rt.mark();
        int pi = rt.push(pairs);
        int n = Vec.count(rt, rt.r(pi));
        int ni = rt.push(emptyVec(rt));
        int ti = rt.push(emptyVec(rt));
        int ii = rt.push(emptyMap(rt));
        int di = rt.push(emptyVec(rt));
        for (int i = 0; i < n; i++) {
            long pair = Vec.nth(rt, rt.r(pi), i, Val.NOT_FOUND);
            if (!rt.typeP(8, pair) || Vec.count(rt, pair) != 2) {
                rt.popTo(base);
                return rt.throwStr("IllegalArgumentException",
                    "a schema is [[name type] ...]; this entry is not a name and a type");
            }
            long nm = Vec.nth(rt, pair, 0, Val.NOT_FOUND), tp = Vec.nth(rt, pair, 1, Val.NOT_FOUND);
            if (!rt.typeP(5, nm)) {
                rt.popTo(base);
                return rt.throwStr("IllegalArgumentException", "a column name must be a keyword");
            }
            if (!knownType(rt, tp)) {
                String shown = kwName(rt, tp);
                rt.popTo(base);
                return rt.throwStr("IllegalArgumentException",
                    "no such column type :" + shown
                        + "; the types are :int :double :string :bool :keyword :any");
            }
            if (!Val.isNil(Mapread.mapGet(rt, rt.r(ii), nm, Val.NIL))) {
                String shown = kwName(rt, nm);
                rt.popTo(base);
                return rt.throwStr("IllegalArgumentException",
                    "the column :" + shown + " is named twice");
            }
            rt.setR(ni, Vec.conj(rt, rt.r(ni), nm));
            rt.setR(ti, Vec.conj(rt, rt.r(ti), tp));
            rt.setR(ii, Mapwrite.mapAssoc(rt, rt.r(ii), nm, Val.fixnum(i)));
            rt.setR(di, Vec.conj(rt, rt.r(di), Val.fixnum(i)));
        }
        long s = Conc.newObj(rt, TY_SCHEMA, SC_LEN);
        int si = rt.push(s);
        set(rt, rt.r(si), SC_NAMES, rt.r(ni));
        set(rt, rt.r(si), SC_TYPES, rt.r(ti));
        set(rt, rt.r(si), SC_INDEX, rt.r(ii));
        set(rt, rt.r(si), SC_IDS, rt.r(di));
        set(rt, rt.r(si), SC_WIDTH, Val.fixnum(n));
        long out = rt.r(si);
        rt.popTo(base);
        return out;
    }

    static String kwName(Rt rt, long v) {
        long n = rt.nameOf(v);
        return Str.isString(rt, n) ? Str.text(rt, n) : "?";
    }

    /// The schema's column names as `:a :b :c`, for a message that has to say
    /// what the columns ARE rather than only that the key was not one.
    static String columnList(Rt rt, long s) {
        StringBuilder b = new StringBuilder();
        int n = schemaLen(rt, s);
        for (int c = 0; c < n; c++) {
            if (c > 0) b.append(' ');
            b.append(':').append(kwName(rt, schemaNameAt(rt, s, c)));
        }
        return b.toString();
    }

    /// The KIND of what arrived, not just that it was wrong: "holds :int and
    /// was given a string" is the difference between a message you can act on
    /// and one you have to reproduce first.
    static String columnTypeError(Rt rt, long name, long want, long got, int row) {
        return "row " + row + ", column :" + kwName(rt, name) + " holds :" + kwName(rt, want)
            + " and was given a " + kwName(rt, rt.kindOf(got));
    }

    /// Build a table from `rows`, a vector of maps.
    public static long newTable(Rt rt, long schema, long rows) {
        int base = rt.mark();
        int si = rt.push(schema);
        int ri = rt.push(rows);
        int ncols = schemaLen(rt, rt.r(si));
        int width = schemaWidth(rt, rt.r(si));
        int nrows = Vec.count(rt, rt.r(ri));
        int ci = rt.push(emptyVec(rt));
        int row = 0;
        while (row < nrows) {
            // Per CHUNK rather than per row: a chunk is 256 rows of bounded
            // work, so the overshoot is bounded at 256 rows.
            if (!rt.chargeChecked(1, "table")) { rt.popTo(base); return Val.NIL; }
            int take = Math.min(CHUNK, nrows - row);
            long ch = newChunk(rt, width, take);
            int chi = rt.push(ch);
            for (int c = 0; c < ncols; c++) {
                int id = schemaIdAt(rt, rt.r(si), c);
                long col = Conc.newObj(rt, TY_NODE, take);
                int coli = rt.push(col);
                for (int k = 0; k < take; k++) {
                    long rowv = Vec.nth(rt, rt.r(ri), row + k, Val.NOT_FOUND);
                    int rvi = rt.push(rowv);
                    long name = schemaNameAt(rt, rt.r(si), c);
                    long val = Mapread.mapGet(rt, rt.r(rvi), name, Val.NIL);
                    long tp = schemaTypeAt(rt, rt.r(si), c);
                    if (!typeOk(rt, tp, val)) {
                        String msg = columnTypeError(rt, name, tp, val, row + k);
                        rt.popTo(base);
                        return rt.throwStr("IllegalArgumentException", msg);
                    }
                    set(rt, rt.r(coli), k, val);
                    rt.popTo(rvi);
                }
                set(rt, rt.r(chi), CH_BASE + id, rt.r(coli));
                collapse(rt, rt.r(chi), id);
                rt.popTo(coli);
            }
            rt.setR(ci, Vec.conj(rt, rt.r(ci), rt.r(chi)));
            rt.popTo(chi);
            row += take;
        }
        long t = Conc.newObj(rt, TY_TABLE, TB_LEN);
        int ti = rt.push(t);
        set(rt, rt.r(ti), TB_SCHEMA, rt.r(si));
        set(rt, rt.r(ti), TB_CHUNKS, rt.r(ci));
        set(rt, rt.r(ti), TB_COUNT, Val.fixnum(nrows));
        set(rt, rt.r(ti), TB_OFFSET, Val.fixnum(0));
        long out = rt.r(ti);
        rt.popTo(base);
        return out;
    }

    /// Does `row` fit `s`? The three refusals are separate because they are
    /// three different mistakes: a column you forgot, a key that is not a
    /// column, and a value of the wrong type. One "invalid row" for all three
    /// is the message this codebase keeps replacing.
    public static boolean checkRow(Rt rt, long s, long row, int rowno) {
        int base = rt.mark();
        int si = rt.push(s);
        int ri = rt.push(row);
        if (!Mapcore.isMap(rt, rt.r(ri))) {
            String kn = kwName(rt, rt.kindOf(rt.r(ri)));
            rt.popTo(base);
            rt.throwStr("IllegalArgumentException",
                "a table row is a map, and row " + rowno + " is a " + kn);
            return false;
        }
        int n = schemaLen(rt, rt.r(si));
        rt.chargeWork(n);
        for (int c = 0; c < n; c++) {
            long val = rowColumn(rt, rt.r(si), rt.r(ri), c);
            int vi = rt.push(val);
            long name = schemaNameAt(rt, rt.r(si), c);
            int nmi = rt.push(name);
            if (rt.r(vi) == Val.NOT_FOUND) {
                String nm = kwName(rt, rt.r(nmi)), cols = columnList(rt, rt.r(si));
                rt.popTo(base);
                rt.throwStr("IllegalArgumentException", "row " + rowno + " has no :" + nm
                    + "; a table is closed, so every row has every column, and the columns are "
                    + cols);
                return false;
            }
            long tp = schemaTypeAt(rt, rt.r(si), c);
            if (!typeOk(rt, tp, rt.r(vi))) {
                String msg = columnTypeError(rt, rt.r(nmi), tp, rt.r(vi), rowno);
                rt.popTo(base);
                rt.throwStr("IllegalArgumentException", msg);
                return false;
            }
            rt.popTo(vi);
        }
        // Every column is present, so a wider row has a key that is not one.
        // Counted first and hunted only when the count disagrees, because the
        // hunt walks the row and the good path must not.
        boolean extra = isTableRef(rt, rt.r(ri))
            ? schemaLen(rt, rt.slot(rt.r(ri), RF_SCHEMA)) > n
            : Mapcore.mapCount(rt, rt.r(ri)) > n;
        if (extra) {
            long bad = firstForeignKey(rt, rt.r(si), rt.r(ri));
            String nm = kwName(rt, bad), cols = columnList(rt, rt.r(si));
            rt.popTo(base);
            rt.throwStr("IllegalArgumentException", "row " + rowno + " has :" + nm
                + ", which is not a column; a table is closed, and the columns are " + cols);
            return false;
        }
        rt.popTo(base);
        return true;
    }

    /// The first key of `row` the schema does not name. Only called once a
    /// count has already proved there is one.
    static long firstForeignKey(Rt rt, long s, long row) {
        int base = rt.mark();
        int si = rt.push(s);
        if (isTableRef(rt, row)) {
            int rsi = rt.push(rt.slot(row, RF_SCHEMA));
            int n = schemaLen(rt, rt.r(rsi));
            for (int c = 0; c < n; c++) {
                long name = schemaNameAt(rt, rt.r(rsi), c);
                // ABSENT IS THE WIDTH: a real id is 0..width-1. `< 0` was
                // the old spelling, and it is dead on an unsigned id in Rust.
                if (schemaId(rt, rt.r(si), name) >= schemaWidth(rt, rt.r(si))) {
                    rt.popTo(base); return name;
                }
            }
            rt.popTo(base);
            return Val.NIL;
        }
        int qi = rt.push(Seqs.seq(rt, row));
        while (!Val.isNil(rt.r(qi))) {
            long e = Seqs.first(rt, rt.r(qi));
            int ei = rt.push(e);
            long k = rt.slot(rt.r(ei), 0);
            if (schemaId(rt, rt.r(si), k) >= schemaWidth(rt, rt.r(si))) {
                rt.popTo(base); return k;
            }
            rt.popTo(ei);
            rt.setR(qi, Seqs.next(rt, rt.r(qi)));
        }
        rt.popTo(base);
        return Val.NIL;
    }

    /// `(assoc table i row)`. `i` may be `count`, which appends -- the same
    /// rule a vector follows, so nothing new has to be learned to grow one.
    public static long tableAssoc(Rt rt, long t, long k, long row) {
        Long ix = Num.isInt(rt, k) ? Val.asFixnum(k) : null;
        if (ix == null) {
            String kn = kwName(rt, rt.kindOf(k));
            return rt.throwStr("IllegalArgumentException",
                "a table is indexed by row number and this key is a " + kn
                + "; to reach a column, index the row first: (assoc-in t [row :column] v)");
        }
        long i = ix;
        int n = tableCount(rt, t);
        if (i < 0 || i > n) {
            return rt.throwStr("IndexOutOfBoundsException", "row " + i
                + " is out of range for a table of " + n
                + " rows; assoc may replace any row or append at " + n);
        }
        int base = rt.mark();
        int ti = rt.push(t);
        int ri = rt.push(row);
        int si = rt.push(rt.slot(rt.r(ti), TB_SCHEMA));
        if (!checkRow(rt, rt.r(si), rt.r(ri), (int) i)) { rt.popTo(base); return Val.NIL; }
        int idx = (int) i;
        int ci = rt.push(rt.slot(rt.r(ti), TB_CHUNKS));
        int phys = idx + tableOffset(rt, rt.r(ti));
        int which = phys >> CHUNK_SHIFT, within = phys & (CHUNK - 1);
        boolean append = idx == tableCount(rt, rt.r(ti));
        int nchunks = Vec.count(rt, rt.r(ci));
        if (append && which >= nchunks) {
            // A new chunk, one row wide: `chunkWithRow` grows an existing one,
            // and an empty table has none to grow.
            int width = schemaWidth(rt, rt.r(si));
            long ch = newChunk(rt, width, 1);
            int chi = rt.push(ch);
            int ncols = schemaLen(rt, rt.r(si));
            for (int c = 0; c < ncols; c++)
                {
                    // HOISTED: the allocation must happen before the object's
                    // address is read, or the write lands on a forwarded copy.
                    long col1 = Conc.newObj(rt, TY_NODE, 1);
                    set(rt, rt.r(chi), CH_BASE + schemaIdAt(rt, rt.r(si), c), col1);
                }
            writeRow(rt, rt.r(si), rt.r(chi), 0, rt.r(ri));
            // A one-row column is trivially constant, so a table grown row by
            // row starts every chunk collapsed.
            for (int c = 0; c < ncols; c++) collapse(rt, rt.r(chi), schemaIdAt(rt, rt.r(si), c));
            rt.setR(ci, Vec.conj(rt, rt.r(ci), rt.r(chi)));
            rt.popTo(chi);
        } else {
            long ch = Vec.nth(rt, rt.r(ci), which, Val.NOT_FOUND);
            int chi = rt.push(ch);
            long nch = chunkWithRow(rt, rt.r(si), rt.r(chi), within, rt.r(ri), append);
            int nj = rt.push(nch);
            rt.setR(ci, Vec.assoc(rt, rt.r(ci), which, rt.r(nj)));
            rt.popTo(chi);
        }
        int count = tableCount(rt, rt.r(ti)) + (append ? 1 : 0);
        long nt = Conc.newObj(rt, TY_TABLE, TB_LEN);
        int ni = rt.push(nt);
        set(rt, rt.r(ni), TB_SCHEMA, rt.r(si));
        set(rt, rt.r(ni), TB_CHUNKS, rt.r(ci));
        set(rt, rt.r(ni), TB_COUNT, Val.fixnum(count));
        set(rt, rt.r(ni), TB_OFFSET, rt.slot(rt.r(ti), TB_OFFSET));
        long out = rt.r(ni);
        rt.popTo(base);
        return out;
    }

    /// `(conj table row)` -- append, which is `assoc` at the end.
    public static long tableConj(Rt rt, long t, long row) {
        return tableAssoc(rt, t, Val.fixnum(tableCount(rt, t)), row);
    }

    /// `(assoc row-ref k v)` -> a MAP. A ref is a VIEW; changing it makes an
    /// independent value and neither the chunk nor the table moves.
    public static long refAssoc(Rt rt, long r, long k, long v) {
        int base = rt.mark();
        int ri = rt.push(r);
        int ki = rt.push(k);
        int vi = rt.push(v);
        long m = refToMap(rt, rt.r(ri));
        int mi = rt.push(m);
        long out = Mapwrite.mapAssoc(rt, rt.r(mi), rt.r(ki), rt.r(vi));
        rt.popTo(base);
        return out;
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
        int base = rt.mark();
        int hi = rt.push(have);
        int wi = rt.push(want);
        int n = schemaLen(rt, rt.r(wi));
        int width = schemaWidth(rt, rt.r(hi));
        int di = rt.push(emptyVec(rt));
        int ii = rt.push(emptyMap(rt));
        for (int c = 0; c < n; c++) {
            long name = schemaNameAt(rt, rt.r(wi), c);
            int nmi = rt.push(name);
            int old = schemaId(rt, rt.r(hi), rt.r(nmi));
            int id = old < schemaWidth(rt, rt.r(hi)) ? old : width++;
            rt.setR(di, Vec.conj(rt, rt.r(di), Val.fixnum(id)));
            rt.setR(ii, Mapwrite.mapAssoc(rt, rt.r(ii), rt.r(nmi), Val.fixnum(id)));
            rt.popTo(nmi);
        }
        long sc = Conc.newObj(rt, TY_SCHEMA, SC_LEN);
        int si = rt.push(sc);
        set(rt, rt.r(si), SC_NAMES, rt.slot(rt.r(wi), SC_NAMES));
        set(rt, rt.r(si), SC_TYPES, rt.slot(rt.r(wi), SC_TYPES));
        set(rt, rt.r(si), SC_INDEX, rt.r(ii));
        set(rt, rt.r(si), SC_IDS, rt.r(di));
        set(rt, rt.r(si), SC_WIDTH, Val.fixnum(width));
        long out = rt.r(si);
        rt.popTo(base);
        return out;
    }

    /// `t` under `want`, sharing every column both schemas keep. `defaults`
    /// supplies a value for each column `want` adds, stored ONCE PER CHUNK as a
    /// constant column.
    public static long tableMigrate(Rt rt, long t, long want, long defaults) {
        int base = rt.mark();
        int ti = rt.push(t);
        int wi = rt.push(want);
        int dfi = rt.push(defaults);
        int hi = rt.push(rt.slot(rt.r(ti), TB_SCHEMA));
        int n = schemaLen(rt, rt.r(wi));
        // Refuse first, and completely, before anything is built.
        for (int c = 0; c < n; c++) {
            long name = schemaNameAt(rt, rt.r(wi), c);
            int nmi = rt.push(name);
            long wantTy = schemaTypeAt(rt, rt.r(wi), c);
            int old = schemaId(rt, rt.r(hi), rt.r(nmi));
            if (old < schemaWidth(rt, rt.r(hi))) {
                // A carried column keeps its VALUES, so it must keep its type.
                long haveTy = schemaTypeAt(rt, rt.r(hi), schemaPosOf(rt, rt.r(hi), rt.r(nmi)));
                if (!Eq.eq(rt, haveTy, wantTy)) {
                    String nm = kwName(rt, rt.r(nmi)), ht = kwName(rt, haveTy), wt = kwName(rt, wantTy);
                    rt.popTo(base);
                    return rt.throwStr("IllegalArgumentException", "column :" + nm + " holds :" + ht
                        + " and the new schema declares :" + wt
                        + "; a type change needs a value per row, so migrate with a function:"
                        + " (migrate t s (fn [row] ...))");
                }
            } else {
                long dv = Mapread.mapGet(rt, rt.r(dfi), rt.r(nmi), Val.NOT_FOUND);
                if (dv == Val.NOT_FOUND) {
                    String nm = kwName(rt, rt.r(nmi));
                    rt.popTo(base);
                    return rt.throwStr("IllegalArgumentException", "the new schema adds :" + nm
                        + " and the table has no values for it; give it a default -- (migrate t s {:"
                        + nm + " v}) -- or compute one per row: (migrate t s (fn [row] ...))");
                }
                if (!typeOk(rt, wantTy, dv)) {
                    String nm = kwName(rt, rt.r(nmi)), wt = kwName(rt, wantTy),
                           gt = kwName(rt, rt.kindOf(dv));
                    rt.popTo(base);
                    return rt.throwStr("IllegalArgumentException",
                        "the default for :" + nm + " is a " + gt + " and the column holds :" + wt);
                }
            }
            rt.popTo(nmi);
        }
        int ri = rt.push(rebaseSchema(rt, rt.r(hi), rt.r(wi)));
        int width = schemaWidth(rt, rt.r(ri));
        int ci = rt.push(rt.slot(rt.r(ti), TB_CHUNKS));
        int nch = Vec.count(rt, rt.r(ci));
        int oi = rt.push(emptyVec(rt));
        for (int k = 0; k < nch; k++) {
            if (!rt.chargeChecked(1, "migrate")) { rt.popTo(base); return Val.NIL; }
            int chi = rt.push(Vec.nth(rt, rt.r(ci), k, Val.NOT_FOUND));
            int rows = chunkRows(rt, rt.r(chi));
            int ni = rt.push(newChunk(rt, width, rows));
            for (int c = 0; c < n; c++) {
                int id = schemaIdAt(rt, rt.r(ri), c);
                long name = schemaNameAt(rt, rt.r(ri), c);
                int old = schemaId(rt, rt.r(hi), name);
                if (old < schemaWidth(rt, rt.r(hi))) {
                    // SHARED, column object and encoding both. Nothing is
                    // copied and nothing is scanned: this is the head-only
                    // edit, and dropping a column is the loop simply never
                    // reaching the old slot.
                    set(rt, rt.r(ni), CH_BASE + id, rt.slot(rt.r(chi), CH_BASE + old));
                    set(rt, rt.slot(rt.r(ni), CH_ENC), id, Val.fixnum(chunkEnc(rt, rt.r(chi), old)));
                } else {
                    set(rt, rt.r(ni), CH_BASE + id, Mapread.mapGet(rt, rt.r(dfi), name, Val.NIL));
                    set(rt, rt.slot(rt.r(ni), CH_ENC), id, Val.fixnum(ENC_CONST));
                }
            }
            rt.setR(oi, Vec.conj(rt, rt.r(oi), rt.r(ni)));
            rt.popTo(chi);
        }
        long nt = Conc.newObj(rt, TY_TABLE, TB_LEN);
        int nti = rt.push(nt);
        set(rt, rt.r(nti), TB_SCHEMA, rt.r(ri));
        set(rt, rt.r(nti), TB_CHUNKS, rt.r(oi));
        set(rt, rt.r(nti), TB_COUNT, Val.fixnum(tableCount(rt, rt.r(ti))));
        set(rt, rt.r(nti), TB_OFFSET, rt.slot(rt.r(ti), TB_OFFSET));
        long out = rt.r(nti);
        rt.popTo(base);
        return out;
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
            return rt.throwStr("IndexOutOfBoundsException",
                "slice [" + from + " " + to + ") is outside a table of " + n + " rows");
        }
        int base = rt.mark();
        int ti = rt.push(t);
        if (from == to) {
            long out = newTable(rt, rt.slot(rt.r(ti), TB_SCHEMA), emptyVec(rt));
            rt.popTo(base);
            return out;
        }
        int off = tableOffset(rt, rt.r(ti));
        int first = (off + (int) from) >> CHUNK_SHIFT;
        int last = (off + (int) to - 1) >> CHUNK_SHIFT;
        int ci = rt.push(rt.slot(rt.r(ti), TB_CHUNKS));
        int ki = rt.push(emptyVec(rt));
        for (int k = first; k <= last; k++)
            rt.setR(ki, Vec.conj(rt, rt.r(ki), Vec.nth(rt, rt.r(ci), k, Val.NOT_FOUND)));
        int si = rt.push(rt.slot(rt.r(ti), TB_SCHEMA));
        long nt = Conc.newObj(rt, TY_TABLE, TB_LEN);
        int ni = rt.push(nt);
        set(rt, rt.r(ni), TB_SCHEMA, rt.r(si));
        set(rt, rt.r(ni), TB_CHUNKS, rt.r(ki));
        set(rt, rt.r(ni), TB_COUNT, Val.fixnum(to - from));
        set(rt, rt.r(ni), TB_OFFSET, Val.fixnum((off + (int) from) & (CHUNK - 1)));
        long out = rt.r(ni);
        rt.popTo(base);
        return out;
    }

    /// One column, as a vector. Reads the column runs directly -- no row is
    /// built and no ref is made.
    public static long tableColumn(Rt rt, long t, long name) {
        int base = rt.mark();
        int ti = rt.push(t);
        long s = rt.slot(rt.r(ti), TB_SCHEMA);
        int id = schemaId(rt, s, name);
        if (id >= schemaWidth(rt, s)) {
            String nm = kwName(rt, name), cols = columnList(rt, s);
            rt.popTo(base);
            return rt.throwStr("IllegalArgumentException",
                "no column :" + nm + "; the columns are " + cols);
        }
        int n = tableCount(rt, rt.r(ti));
        if (!rt.chargeChecked(n, "column")) { rt.popTo(base); return Val.NIL; }
        int oi = rt.push(emptyVec(rt));
        for (int i = 0; i < n; i++)
            rt.setR(oi, Vec.conj(rt, rt.r(oi), tableCell(rt, rt.r(ti), id, i)));
        long out = rt.r(oi);
        rt.popTo(base);
        return out;
    }

    /// `(reduce-column t :col f init)` -- `f` over one column, without building
    /// a row or a ref for any of them. This is the operation the type exists
    /// for: scanning one field should cost one field.
    public static long tableReduceColumn(Rt rt, long t, long name, long f, long init) {
        int base = rt.mark();
        int ti = rt.push(t);
        int fi = rt.push(f);
        int acc = rt.push(init);
        long s = rt.slot(rt.r(ti), TB_SCHEMA);
        int id = schemaId(rt, s, name);
        if (id >= schemaWidth(rt, s)) {
            String nm = kwName(rt, name), cols = columnList(rt, s);
            rt.popTo(base);
            return rt.throwStr("IllegalArgumentException",
                "no column :" + nm + "; the columns are " + cols);
        }
        int n = tableCount(rt, rt.r(ti));
        for (int i = 0; i < n; i++) {
            if (!rt.chargeTick(i, 1, "reduce-column")) { rt.popTo(base); return Val.NIL; }
            int vi = rt.push(tableCell(rt, rt.r(ti), id, i));
            long nv = rt.call(rt.r(fi), new long[]{ rt.r(acc), rt.r(vi) });
            if (!Val.isNil(rt.thrown)) { rt.popTo(base); return Val.NIL; }
            rt.setR(acc, nv);
            rt.popTo(vi);
        }
        long out = rt.r(acc);
        rt.popTo(base);
        return out;
    }

    /// A table from COLUMNS rather than rows -- what the wire decoder builds
    /// into. Going through rows would build a map per row only to take it apart
    /// again. The values are still CHECKED: a decoder that skipped that would
    /// be a way to make a table that is not closed.
    public static long tableFromColumns(Rt rt, long schema, long cols, int nrows) {
        int base = rt.mark();
        int si = rt.push(schema);
        int ci = rt.push(cols);
        int ncols = schemaLen(rt, rt.r(si));
        int width = schemaWidth(rt, rt.r(si));
        int ki = rt.push(emptyVec(rt));
        int row = 0;
        while (row < nrows) {
            if (!rt.chargeChecked(1, "table")) { rt.popTo(base); return Val.NIL; }
            int take = Math.min(CHUNK, nrows - row);
            int chi = rt.push(newChunk(rt, width, take));
            for (int c = 0; c < ncols; c++) {
                int id = schemaIdAt(rt, rt.r(si), c);
                int sj = rt.push(Vec.nth(rt, rt.r(ci), c, Val.NOT_FOUND));
                long tp = schemaTypeAt(rt, rt.r(si), c);
                int cj = rt.push(Conc.newObj(rt, TY_NODE, take));
                for (int k = 0; k < take; k++) {
                    long v = Vec.nth(rt, rt.r(sj), row + k, Val.NOT_FOUND);
                    if (!typeOk(rt, tp, v)) {
                        String msg = columnTypeError(rt, schemaNameAt(rt, rt.r(si), c), tp, v, row + k);
                        rt.popTo(base);
                        return rt.throwStr("IllegalArgumentException", msg);
                    }
                    set(rt, rt.r(cj), k, v);
                }
                set(rt, rt.r(chi), CH_BASE + id, rt.r(cj));
                collapse(rt, rt.r(chi), id);
                rt.popTo(sj);
            }
            rt.setR(ki, Vec.conj(rt, rt.r(ki), rt.r(chi)));
            rt.popTo(chi);
            row += take;
        }
        long t = Conc.newObj(rt, TY_TABLE, TB_LEN);
        int ti = rt.push(t);
        set(rt, rt.r(ti), TB_SCHEMA, rt.r(si));
        set(rt, rt.r(ti), TB_CHUNKS, rt.r(ki));
        set(rt, rt.r(ti), TB_COUNT, Val.fixnum(nrows));
        set(rt, rt.r(ti), TB_OFFSET, Val.fixnum(0));
        long out = rt.r(ti);
        rt.popTo(base);
        return out;
    }

    // --- the transient ------------------------------------------------------
    //
    // Appending through the persistent path copies the chunk per row, which is
    // 256 copies per chunk: 49 061 464 bytes to build 20 000 rows against
    // 3 082 984 through here (`doc/decisions/0026` step 7).

    /// `(transient t)`. The table's own chunks are carried over UNCHANGED --
    /// they are persistent and shared, and a transient must never write into
    /// something a table can still see.
    public static long tableTransient(Rt rt, long t) {
        int base = rt.mark();
        int ti = rt.push(t);
        int si = rt.push(rt.slot(rt.r(ti), TB_SCHEMA));
        int count = tableCount(rt, rt.r(ti));
        int full = count & ~(CHUNK - 1);
        int ci = rt.push(rt.slot(rt.r(ti), TB_CHUNKS));
        int oi = rt.push(openChunk(rt, rt.r(si)));
        int partial = count - full;
        if (partial > 0) {
            int li = rt.push(Vec.nth(rt, rt.r(ci), full >> CHUNK_SHIFT, Val.NOT_FOUND));
            int ncols = schemaLen(rt, rt.r(si));
            rt.chargeWork((long) partial * ncols);
            for (int c = 0; c < ncols; c++) {
                int id = schemaIdAt(rt, rt.r(si), c);
                long col = rt.slot(rt.r(oi), CH_BASE + id);
                for (int k = 0; k < partial; k++)
                    set(rt, col, k, chunkGet(rt, rt.r(li), id, k));
            }
            // Drop the partial chunk from the carried list: its rows are in
            // the open chunk now, and keeping both would double them.
            int keep = Vec.count(rt, rt.r(ci)) - 1;
            int pi = rt.push(emptyVec(rt));
            for (int q = 0; q < keep; q++)
                rt.setR(pi, Vec.conj(rt, rt.r(pi), Vec.nth(rt, rt.r(ci), q, Val.NOT_FOUND)));
            rt.setR(ci, rt.r(pi));
            rt.popTo(li);
        }
        set(rt, rt.r(oi), CH_ROWS, Val.fixnum(partial));
        long tt = Conc.newObj(rt, TY_TTABLE, TT_LEN);
        int tti = rt.push(tt);
        set(rt, rt.r(tti), TT_SCHEMA, rt.r(si));
        set(rt, rt.r(tti), TT_CHUNKS, rt.r(ci));
        set(rt, rt.r(tti), TT_COUNT, Val.fixnum(count));
        set(rt, rt.r(tti), TT_OPEN, rt.r(oi));
        set(rt, rt.r(tti), TT_LIVE, Val.TRUE);
        long out = rt.r(tti);
        rt.popTo(base);
        return out;
    }

    static boolean ttableLive(Rt rt, long t, String op) {
        if (rt.slot(t, TT_LIVE) == Val.TRUE) return true;
        rt.throwStr("IllegalStateException", op
            + " on a transient table that persistent! has already taken; a transient is used"
            + " once and the table it produced is the value");
        return false;
    }

    /// `(conj! tt row)`. The row is CHECKED exactly as the persistent path
    /// checks it: a transient is a faster way to build a table, not a way to
    /// build one that is not closed.
    public static long ttableConj(Rt rt, long t, long row) {
        if (!ttableLive(rt, t, "conj!")) return Val.NIL;
        int base = rt.mark();
        int ti = rt.push(t);
        int ri = rt.push(row);
        int si = rt.push(rt.slot(rt.r(ti), TT_SCHEMA));
        int count = (int) Val.asFixnum(rt.slot(rt.r(ti), TT_COUNT));
        if (!checkRow(rt, rt.r(si), rt.r(ri), count)) { rt.popTo(base); return Val.NIL; }
        int oi = rt.push(rt.slot(rt.r(ti), TT_OPEN));
        int fill = chunkRows(rt, rt.r(oi));
        writeRow(rt, rt.r(si), rt.r(oi), fill, rt.r(ri));
        set(rt, rt.r(oi), CH_ROWS, Val.fixnum(fill + 1));
        if (fill + 1 == CHUNK) {
            // A FULL open chunk is already exactly the chunk it wants to be, so
            // it is collapsed and handed over as-is. Sealing here copies, and
            // copying made the transient allocate MORE than the bulk path it
            // was supposed to beat -- which only a measurement found.
            int ncols = schemaLen(rt, rt.r(si));
            for (int c = 0; c < ncols; c++) collapse(rt, rt.r(oi), schemaIdAt(rt, rt.r(si), c));
            // HOISTED, both of them. Java evaluates arguments left to right, so
            // `set(rt, rt.r(ti), ..., Vec.conj(...))` reads the object's ADDRESS
            // before the allocation that may move it -- and the write then lands
            // on a forwarded object. It surfaced as "object type 1 is not a
            // transient", type 1 being `TY_FWD`, which is the tidiest possible
            // report of `doc/decisions/0031`'s defect: a value in a host local
            // does not survive an allocation.
            long sealed = Vec.conj(rt, rt.slot(rt.r(ti), TT_CHUNKS), rt.r(oi));
            set(rt, rt.r(ti), TT_CHUNKS, sealed);
            long fresh = openChunk(rt, rt.r(si));
            set(rt, rt.r(ti), TT_OPEN, fresh);
        }
        set(rt, rt.r(ti), TT_COUNT, Val.fixnum(count + 1));
        long out = rt.r(ti);
        rt.popTo(base);
        return out;
    }

    /// `(persistent! tt)`. Seals whatever the open chunk holds. The transient
    /// is dead afterwards, and says so if used again.
    public static long ttablePersistent(Rt rt, long t) {
        if (!ttableLive(rt, t, "persistent!")) return Val.NIL;
        int base = rt.mark();
        int ti = rt.push(t);
        int si = rt.push(rt.slot(rt.r(ti), TT_SCHEMA));
        int ci = rt.push(rt.slot(rt.r(ti), TT_CHUNKS));
        int oi = rt.push(rt.slot(rt.r(ti), TT_OPEN));
        int fill = chunkRows(rt, rt.r(oi));
        if (fill > 0) {
            int sj = rt.push(seal(rt, rt.r(si), rt.r(oi), fill));
            rt.setR(ci, Vec.conj(rt, rt.r(ci), rt.r(sj)));
            rt.popTo(sj);
        }
        long count = rt.slot(rt.r(ti), TT_COUNT);
        set(rt, rt.r(ti), TT_LIVE, Val.FALSE);
        long nt = Conc.newObj(rt, TY_TABLE, TB_LEN);
        int ni = rt.push(nt);
        set(rt, rt.r(ni), TB_SCHEMA, rt.r(si));
        set(rt, rt.r(ni), TB_CHUNKS, rt.r(ci));
        set(rt, rt.r(ni), TB_COUNT, count);
        set(rt, rt.r(ni), TB_OFFSET, Val.fixnum(0));
        long out = rt.r(ni);
        rt.popTo(base);
        return out;
    }

    public static int ttableCount(Rt rt, long t) {
        return (int) Val.asFixnum(rt.slot(t, TT_COUNT));
    }

}
