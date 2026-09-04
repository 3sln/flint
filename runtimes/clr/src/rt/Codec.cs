namespace Flint.Rt;

using System.IO;
using flint.rt;
using System.Text;

/// Values across a boundary (`doc/decisions/0025`), a MIRROR of the JVM port's
/// `Codec.java` and a port of `runtime/src/codec.rs`.
///
/// BOTH HALVES, because a bridge is full duplex: `Send` encodes and
/// `HostDeliver` decodes, and both run in the RUNTIME (`doc/decisions/0027`).
/// Neither is reachable from a program -- there is no builtin that encodes and
/// none that decodes -- and that, rather than the absence of the code, is what
/// keeps a guest from turning arbitrary bytes into a port or an identity. This
/// file used to carry only the encoder and a comment calling the omission
/// deliberate; the omission meant a port on this runtime handed the guest raw
/// bytes where the native one handed it a value.
///
/// `K_SENTINEL` is why this exists at all. An opaque value crosses as its host
/// id plus its label, and guest code cannot mint that id (`flint/opaque` gives
/// 0), so a host recognises its own grants and nothing else. That is the whole
/// of the capability check, and it lives with the host rather than in here.
public static class Codec {

    public const int K_NIL = 0, K_TRUE = 1, K_FALSE = 2, K_INT = 3,
        K_DOUBLE = 4, K_STRING = 5, K_KEYWORD = 6, K_SYMBOL = 7, K_VECTOR = 8,
        K_LIST = 9, K_MAP = 10, K_SET = 11, K_BYTES = 14, K_PORT = 15,
        K_SENTINEL = 16,
        /// A tagged literal (`doc/decisions/0034`): the tag symbol, then the
        /// form. 17 here and 17 in the image's constant tags, which share a
        /// numbering space.
        K_TAGGED = 17,
        /// A table (`doc/decisions/0026`), COLUMNAR: the schema, the row count,
        /// then each column in full before the next one starts.
        K_TABLE = 18;

    /// `-1` means the namespace is ABSENT, which is not the same as empty.
    internal const int NO_NS = -1;

    /// What went wrong, so a caller can say it in flint's terms.
    public sealed class Refused : System.Exception {
        public Refused(string m) : base(m) {}
    }

    public static byte[] Encode(Rt rt, long v) {
        var outs = new MemoryStream();
        EncodeInto(rt, v, outs, 0);
        return outs.ToArray();
    }

    static void U32(MemoryStream o, int n) {
        o.WriteByte((byte) n); o.WriteByte((byte) (n >> 8));
        o.WriteByte((byte) (n >> 16)); o.WriteByte((byte) (n >> 24));
    }

    static void U64(MemoryStream o, long n) {
        U32(o, (int) n); U32(o, (int) ((ulong) n >> 32));
    }

    static void Str_(MemoryStream o, string s) {
        byte[] b = Encoding.UTF8.GetBytes(s);
        U32(o, b.Length);
        o.Write(b, 0, b.Length);
    }

    static void EncodeInto(Rt rt, long v, MemoryStream outs, int depth) {
        if (depth > 128) throw new Refused("value nested too deeply to encode");
        if (Val.IsNil(v)) { outs.WriteByte(K_NIL); return; }
        if (v == Val.True) { outs.WriteByte(K_TRUE); return; }
        if (v == Val.False) { outs.WriteByte(K_FALSE); return; }
        if (Num.IsInt(rt, v)) {
            long? i = Num.AsI64(rt, v);
            if (i != null) { outs.WriteByte(K_INT); U64(outs, i.Value); return; }
        }
        if (Val.IsDouble(v)) {
            outs.WriteByte(K_DOUBLE);
            U64(outs, System.BitConverter.DoubleToInt64Bits(Val.AsDouble(v)));
            return;
        }
        if (Str.IsString(rt, v)) {
            outs.WriteByte(K_STRING); Str_(outs, Str.Text(rt, v)); return;
        }
        bool inlineKw = Val.IsInlineKw(v);
        bool kw = inlineKw || rt.IsHeapTy(v, Obj.TyKw);
        bool sym = rt.IsHeapTy(v, Obj.TySym);
        if (kw || sym) {
            long nsv = inlineKw ? Val.Nil : rt.Slot(v, 0);
            long namev = inlineKw ? Val.InlineStr(Val.InlineBytes(v)) : rt.Slot(v, 1);
            outs.WriteByte((byte) (kw ? K_KEYWORD : K_SYMBOL));
            if (Val.IsNil(nsv)) U32(outs, NO_NS); else Str_(outs, Str.Text(rt, nsv));
            Str_(outs, Str.Text(rt, namev));
            return;
        }
        if (Bytes.IsBytes(rt, v)) {
            byte[] b = Bytes.ToArray(rt, v);
            outs.WriteByte(K_BYTES);
            U32(outs, b.Length);
            outs.Write(b, 0, b.Length);
            return;
        }
        if (!Val.IsHeap(v)) throw new Refused("this value cannot cross a boundary");
        switch (Obj.Ty(rt.gc.sp, Val.AsHeap(v))) {
            case Obj.TyPort:
                outs.WriteByte(K_PORT);
                U32(outs, (int) Val.AsFixnum(rt.Slot(v, Conc.PT_ID)));
                return;
            case Obj.TyOpaque:
                outs.WriteByte(K_SENTINEL);
                U64(outs, rt.OpaqueHostId(v));
                Str_(outs, Str.IsString(rt, rt.OpaqueLabel(v))
                           ? Str.Text(rt, rt.OpaqueLabel(v)) : "");
                return;
            case Obj.TyTagged: {
                outs.WriteByte(K_TAGGED);
                EncodeInto(rt, rt.Slot(v, 0), outs, depth + 1);
                EncodeInto(rt, rt.Slot(v, 1), outs, depth + 1);
                return;
            }
            case Obj.TyTable: {
                outs.WriteByte(K_TABLE);
                int bas = rt.Mark();
                int vi = rt.Push(v);
                int si = rt.Push(rt.Slot(rt.R(vi), Table.TB_SCHEMA));
                int ncols = Table.schemaLen(rt, rt.R(si));
                int nrows = Table.tableCount(rt, rt.R(vi));
                U32(outs, ncols);
                for (int c = 0; c < ncols; c++) {
                    EncodeInto(rt, Table.schemaNameAt(rt, rt.R(si), c), outs, depth + 1);
                    EncodeInto(rt, Table.schemaTypeAt(rt, rt.R(si), c), outs, depth + 1);
                }
                U32(outs, nrows);
                // COLUMN BY COLUMN, each in full.
                for (int c = 0; c < ncols; c++) {
                    long nm = Table.schemaNameAt(rt, rt.R(si), c);
                    int ci = rt.Push(Table.tableColumn(rt, rt.R(vi), nm));
                    for (int i = 0; i < nrows; i++)
                        EncodeInto(rt, Vec.Nth(rt, rt.R(ci), i), outs, depth + 1);
                    rt.PopTo(ci);
                }
                rt.PopTo(bas);
                return;
            }
            case Obj.TyClosure: case Obj.TyNativefn: case Obj.TyMultifn:
                throw new Refused("a function cannot cross a boundary: its meaning is its "
                                  + "environment, and that does not travel");
            case Obj.TyAtom: throw new Refused("an atom cannot cross a boundary");
            case Obj.TyVar: throw new Refused("a var cannot cross a boundary");
            case Obj.TyThread: throw new Refused("a thread cannot cross a boundary");
            default: EncodeCollection(rt, v, outs, depth); return;
        }
    }

    /// The items are gathered ONTO THE SHADOW STACK first, then encoded.
    ///
    /// Two reasons, and it used to be only the second. Encoding allocates --
    /// flattening a rope does -- so the items have to be rooted before the
    /// encode loop. And the WALK is not allocation-free either: `next` on a
    /// lazy seq forces the tail, which runs arbitrary flint code and can
    /// collect, so anything gathered into a host list has already gone stale.
    static void EncodeCollection(Rt rt, long v, MemoryStream outs, int depth) {
        if (Mapcore.IsMap(rt, v)) {
            int at = rt.Mark();
            int mn = Maps.Entries(rt, v, at);
            outs.WriteByte(K_MAP);
            U32(outs, mn);
            for (int i = 0; i < 2 * mn; i++) EncodeInto(rt, rt.R(at + i), outs, depth + 1);
            rt.PopTo(at);
            return;
        }
        int tag;
        if (Sets.IsSet(rt, v)) tag = K_SET;
        else if (rt.IsHeapTy(v, Obj.TyVec)) tag = K_VECTOR;
        else if (rt.IsSeq(v)) tag = K_LIST;
        else throw new Refused("this value cannot cross a boundary (object type "
                               + Obj.Ty(rt.gc.sp, Val.AsHeap(v)) + ")");
        int bas = rt.Mark();
        int si = rt.Push(Seqs.Seq(rt, v));
        int n = 0;
        while (!Val.IsNil(rt.R(si))) {
            rt.Push(Seqs.First(rt, rt.R(si)));
            n++;
            rt.SetR(si, Seqs.Next(rt, rt.R(si)));
        }
        outs.WriteByte((byte) tag);
        U32(outs, n);
        for (int i = 0; i < n; i++) EncodeInto(rt, rt.R(si + 1 + i), outs, depth + 1);
        rt.PopTo(bas);
    }

    // --- decoding ----------------------------------------------------------
    //
    // The mirror of the encoder, and it lives HERE rather than being reachable
    // from a program: `HostDeliver` is its only caller (`doc/decisions/0027`).
    //
    // A bridge carries VALUES. The runtime encodes on the way out and decodes
    // on the way in, and the guest is handed neither half -- because a decoder
    // a guest could drive would be an encoder read backwards, and `K_PORT` and
    // `K_SENTINEL` carry their identity inline as integers a guest can write.
    // Such a guest could mint any host id it liked, and an opaque value's whole
    // meaning is that it cannot.
    //
    // `live` is that rule as one parameter. `Decode` honours the identity tags
    // because the HOST wrote the bytes; `DecodeGuest` refuses them. Nothing
    // calls the second yet, and it exists so that whoever adds a guest-reachable
    // decoder finds it rather than writing the unsafe one.

    /// A cursor over the bytes. Every read is bounds-checked, because the
    /// encoding is something outside this sandbox wrote.
    internal sealed class Reader {
        internal readonly byte[] b;
        internal int i;
        internal Reader(byte[] b) { this.b = b; }

        internal int U8() {
            if (i >= b.Length) throw new Refused("the encoding ends mid-value");
            return b[i++] & 0xff;
        }
        internal int U32() {
            if (i + 4 > b.Length) throw new Refused("the encoding ends mid-value");
            int n = (b[i] & 0xff) | ((b[i + 1] & 0xff) << 8)
                  | ((b[i + 2] & 0xff) << 16) | ((b[i + 3] & 0xff) << 24);
            i += 4;
            return n;
        }
        internal long U64() {
            long lo = U32() & 0xffffffffL, hi = U32() & 0xffffffffL;
            return lo | (hi << 32);
        }
        /// `null` means ABSENT, which is not the same as empty -- that is what
        /// distinguishes `:kw` from `:/kw`.
        internal string Str_() {
            int n = U32();
            if (n == NO_NS) return null;
            if (n < 0 || i + n > b.Length) throw new Refused("the encoding ends mid-string");
            string s = Encoding.UTF8.GetString(b, i, n);
            i += n;
            return s;
        }
        internal byte[] Raw(int n) {
            if (n < 0 || i + n > b.Length) throw new Refused("the encoding ends mid-bytes");
            byte[] outb = new byte[n];
            System.Array.Copy(b, i, outb, 0, n);
            i += n;
            return outb;
        }
    }

    /// Decode a value the HOST produced. Live tags are honoured.
    public static long Decode(Rt rt, byte[] bytes) {
        return DecodeAt(rt, new Reader(bytes), true, 0);
    }

    /// Decode a value the GUEST produced, where the live tags are refused.
    ///
    /// This is the whole of `0025`'s safety rule, and it is one line: a guest
    /// that could decode arbitrary bytes into a port would have exactly the
    /// integer-to-port conversion the sandbox forbids.
    public static long DecodeGuest(Rt rt, byte[] bytes) {
        return DecodeAt(rt, new Reader(bytes), false, 0);
    }

    static long DecodeAt(Rt rt, Reader r, bool live, int depth) {
        if (depth > 128) throw new Refused("value nested too deeply to decode");
        int tag = r.U8();
        switch (tag) {
            case K_NIL: return Val.Nil;
            case K_TRUE: return Val.True;
            case K_FALSE: return Val.False;
            case K_INT: return Num.Integer(rt, r.U64());
            case K_DOUBLE: return Val.OfDouble(System.BitConverter.Int64BitsToDouble(r.U64()));
            case K_STRING: {
                string s = r.Str_();
                if (s == null) throw new Refused("a string cannot be absent");
                return Str.Of(rt, s);
            }
            case K_KEYWORD: case K_SYMBOL: {
                string ns = r.Str_();
                string name = r.Str_();
                if (name == null) throw new Refused("a name cannot be absent");
                return tag == K_KEYWORD ? Str.Keyword(rt, ns, name) : Str.Symbol(rt, ns, name);
            }
            case K_BYTES: return Bytes.Of(rt, r.Raw(r.U32()));
            case K_TAGGED: {
                int bas = rt.Mark();
                int ti = rt.Push(DecodeAt(rt, r, live, depth + 1));
                int fi = rt.Push(DecodeAt(rt, r, live, depth + 1));
                long outv = rt.NewTagged(rt.R(ti), rt.R(fi));
                rt.PopTo(bas);
                return outv;
            }
            case K_VECTOR: case K_LIST: case K_SET: {
                int n = r.U32();
                int bas = rt.Mark();
                for (int k = 0; k < n; k++) rt.Push(DecodeAt(rt, r, live, depth + 1));
                long outv;
                if (tag == K_VECTOR) {
                    outv = Vec.FromRoots(rt, bas, n);
                } else if (tag == K_LIST) {
                    outv = Seqs.FromRoots(rt, bas, n);
                } else {
                    // No `FromRoots` for a set, so it is built by conj -- and
                    // the accumulator lives on the ROOT STACK, because
                    // `Sets.Conj` allocates.
                    int acc = rt.Push(rt.roots.shared.Singletons[Rt.SingEmptySet]);
                    for (int k = 0; k < n; k++) {
                        rt.SetR(acc, Sets.Conj(rt, rt.R(acc), rt.R(bas + k)));
                    }
                    outv = rt.R(acc);
                }
                rt.PopTo(bas);
                return outv;
            }
            case K_MAP: {
                int n = r.U32();
                int bas = rt.Mark();
                for (int k = 0; k < n * 2; k++) rt.Push(DecodeAt(rt, r, live, depth + 1));
                int acc = rt.Push(rt.roots.shared.Singletons[Rt.SingEmptyMap]);
                for (int k = 0; k < n; k++) {
                    rt.SetR(acc, Mapwrite.MapAssoc(rt, rt.R(acc), rt.R(bas + k * 2), rt.R(bas + k * 2 + 1)));
                }
                long outv = rt.R(acc);
                rt.PopTo(bas);
                return outv;
            }
            case K_TABLE: {
                int ncols = r.U32();
                int bas = rt.Mark();
                // The schema pairs, then the columns. Built through the ordinary
                // constructors, so a table off the wire is checked exactly as
                // one built in the program is -- a decoder that skipped the
                // schema check would be a way to make a table that is not
                // closed.
                int pi = rt.Push(Vec.Empty(rt));
                for (int c = 0; c < ncols; c++) {
                    int ni = rt.Push(DecodeAt(rt, r, live, depth + 1));
                    int tyi = rt.Push(DecodeAt(rt, r, live, depth + 1));
                    int ei = rt.Push(Vec.Empty(rt));
                    rt.SetR(ei, Vec.Conj(rt, rt.R(ei), rt.R(ni)));
                    rt.SetR(ei, Vec.Conj(rt, rt.R(ei), rt.R(tyi)));
                    long pair = rt.R(ei);
                    rt.PopTo(ni);
                    rt.SetR(pi, Vec.Conj(rt, rt.R(pi), pair));
                }
                int nrows = r.U32();
                int ci = rt.Push(Vec.Empty(rt));
                for (int c = 0; c < ncols; c++) {
                    int coli = rt.Push(Vec.Empty(rt));
                    for (int k = 0; k < nrows; k++) {
                        rt.SetR(coli, Vec.Conj(rt, rt.R(coli), DecodeAt(rt, r, live, depth + 1)));
                    }
                    long col = rt.R(coli);
                    rt.PopTo(coli);
                    rt.SetR(ci, Vec.Conj(rt, rt.R(ci), col));
                }
                long schema = Table.newSchema(rt, rt.R(pi));
                if (!Val.IsNil(rt.thrown)) {
                    rt.PopTo(bas);
                    throw new Refused("a table arrived with a schema it cannot have");
                }
                int si = rt.Push(schema);
                long outv = Table.tableFromColumns(rt, rt.R(si), rt.R(ci), nrows);
                rt.PopTo(bas);
                if (!Val.IsNil(rt.thrown)) {
                    throw new Refused("a table arrived that its own schema refuses");
                }
                return outv;
            }
            case K_PORT: {
                if (!live) throw new Refused(IdentityRefused);
                int id = r.U32();
                // INTERN OR MINT. A port the host names in a message is a port
                // it is handing to this sandbox, and that is how a capability
                // gets delegated (`doc/decisions/0027`). Arriving twice costs
                // nothing and counts once: the handle is interned by host id,
                // so the second arrival finds the first object.
                //
                // Through `bridgeHook` rather than straight to
                // `InstallBridgePort` -- see `Rt.bridgeHook` for why the Rust
                // needs the indirection and why this mirrors it.
                if (rt.bridgeHook == null) {
                    throw new Refused("port " + id + " arrived, but this sandbox has no ports");
                }
                long p = rt.bridgeHook(rt, id);
                if (Val.IsNil(p)) throw new Refused("port " + id + " could not be installed here");
                return p;
            }
            case K_SENTINEL: {
                if (!live) throw new Refused(IdentityRefused);
                long hostId = r.U64();
                string label = r.Str_();
                if (label == null) throw new Refused("a label cannot be absent");
                int bas = rt.Mark();
                int li = rt.Push(Str.Of(rt, label));
                long outv = rt.NewOpaque(rt.R(li), hostId);
                rt.PopTo(bas);
                return outv;
            }
            default: throw new Refused("unknown tag " + tag + " in the encoding");
        }
    }

    const string IdentityRefused =
        "a port or a sentinel cannot be decoded here: they are identities, "
        + "and an identity is held rather than described";
}
