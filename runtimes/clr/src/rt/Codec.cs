namespace Flint.Rt;

using System.IO;
using System.Text;

/// Values across a boundary (`doc/decisions/0025`), a MIRROR of the JVM port's
/// `Codec.java` and a port of `runtime/src/codec.rs`.
///
/// Only the ENCODER is here. Encoding is what the guest side needs: `open`
/// forwards its arguments to the host as one encoded value, and the host
/// decodes them. A guest-callable DECODER would be the dangerous half -- it
/// would let a program turn arbitrary bytes into a port or an identity, which
/// is exactly the integer-to-capability conversion the sandbox forbids -- so
/// its absence is deliberate rather than unfinished.
///
/// `K_SENTINEL` is why this exists at all. An opaque value crosses as its host
/// id plus its label, and guest code cannot mint that id (`flint/opaque` gives
/// 0), so a host recognises its own grants and nothing else. That is the whole
/// of the capability check, and it lives with the host rather than in here.
public static class Codec {

    public const int K_NIL = 0, K_TRUE = 1, K_FALSE = 2, K_INT = 3,
        K_DOUBLE = 4, K_STRING = 5, K_KEYWORD = 6, K_SYMBOL = 7, K_VECTOR = 8,
        K_LIST = 9, K_MAP = 10, K_SET = 11, K_BYTES = 14, K_PORT = 15,
        K_SENTINEL = 16;

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
        if (Maps.IsMap(rt, v)) {
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
}
