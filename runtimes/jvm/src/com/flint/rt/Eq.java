package com.flint.rt;

import static com.flint.rt.Obj.*;

/// Structural equality and hashing, ported from `runtime/src/eq.rs`.
///
/// These two belong together because they must AGREE: two values that are `=`
/// must hash alike, or a map loses keys it is holding. That is why the same
/// file decides both, and why every case added to one is added to the other.
///
/// `eq` on a compound value CAN ALLOCATE -- it seqs both sides -- so a caller
/// holding heap addresses across a call to it must have them rooted. The map
/// code does; that requirement is the reason `nodeFind` roots its walk.
public final class Eq {
    private Eq() {}

    /// The three things `=` dispatches on. A value's CATEGORY, not its type: a
    /// vector, a list and a map entry are all sequential and compare
    /// elementwise, which is what makes `(= [1 2] '(1 2))` true.
    public static final int CAT_SCALAR = 0, CAT_SEQUENTIAL = 1, CAT_MAP = 2, CAT_SET = 3;

    // kin:begin kin/eq.kin
    public static int category(Rt rt, long v) {
        if (!Val.isHeap(v)) {
            return CAT_SCALAR;
        }
        switch (ty(rt.gc.sp, Val.asHeap(v))) {
            case TY_CONS: case TY_EMPTY_LIST: case TY_LAZYSEQ: case TY_VECSEQ:
            case TY_STRSEQ: case TY_RANGE: case TY_VEC: case TY_MAPENTRY:
                return CAT_SEQUENTIAL;
            // A ROW REF is in the MAP category: it is `=` to a map with the
            // same entries, and `category` is what decides that
            // (`doc/decisions/0026`).
            case TY_ARRAYMAP: case TY_HASHMAP: case TY_TABLEREF:
                return CAT_MAP;
            case TY_SET:
                return CAT_SET;
            default:
                return CAT_SCALAR;
        }
    }

    // kin:end kin/eq.kin

    public static boolean eq(Rt rt, long a, long b) {
        // Doubles FIRST: bit equality would wrongly make NaN equal to itself,
        // and would wrongly separate 0.0 from -0.0.
        if (Val.isDouble(a) || Val.isDouble(b)) {
            return Val.isDouble(a) && Val.isDouble(b) && Val.asDouble(a) == Val.asDouble(b);
        }
        if (a == b) return true;
        if (Num.isInt(rt, a) || Num.isInt(rt, b)) {
            // Integers are CANONICAL, so the only way two are equal without
            // being bit-equal is two distinct boxes.
            Long x = Num.asI64(rt, a), y = Num.asI64(rt, b);
            return x != null && y != null && x.longValue() == y.longValue();
        }
        if (!Val.isHeap(a) || !Val.isHeap(b)) {
            // Immediates are canonical: an inline string can only equal another
            // inline string, and that would have been bit equality. So a
            // boolean and a list are simply NOT EQUAL -- which is Clojure's
            // answer, and refusing here instead was a runtime error where a
            // `false` belonged.
            return false;
        }
        int ca = category(rt, a), cb = category(rt, b);
        if (ca != cb) return false;
        if (ca == CAT_SEQUENTIAL) return seqEq(rt, a, b);
        if (ca == CAT_MAP) return Maps.eq(rt, a, b);
        if (ca == CAT_SET) return Sets.eq(rt, a, b);

        int ta = ty(rt.gc.sp, Val.asHeap(a)), tb = ty(rt.gc.sp, Val.asHeap(b));
        // A string is a string WHATEVER TIER it is in: `(str a b)` and a flat
        // string of the same bytes must be `=` and must hash alike, or a map
        // keyed by one is not found by the other (`doc/decisions/0011`). This
        // is BEFORE the tag comparison, because the tags differ and the values
        // do not. Byte strings compare by content across both tiers for the
        // same reason.
        if ((ta == TY_STR || ta == TY_ROPE) && (tb == TY_STR || tb == TY_ROPE)) {
            // WALKED, not copied. This built a byte array of BOTH sides in full
            // before comparing one of them, so two megabyte strings differing
            // at byte 0 cost two megabytes to tell apart. `treeEq` stops at the
            // first mismatch and short-circuits on NODE IDENTITY, which matters
            // much more now that `subs` shares (`doc/decisions/0011`).
            if (Str.sBytes(rt, a) != Str.sBytes(rt, b)) return false;
            return Str.treeEq(rt, a, b);
        }
        if ((ta == TY_BYTES || ta == TY_BROPE) && (tb == TY_BYTES || tb == TY_BROPE)) {
            return Bytes.eq(rt, a, b);
        }
        // Two tagged literals are equal when both halves are, and a tagged
        // literal is NEVER equal to a two-key map -- which is the whole reason
        // it is a type (`doc/decisions/0034`).
        // A ROW REF is `=` to a map with the same entries, and hashes the same.
        // That is the opposite call to table-versus-vector below, and
        // coherently so: a table is a distinct kind of thing, a row IS just a
        // map seen cheaply (`doc/decisions/0026`).
        if (Table.isTableRef(rt, a) || Table.isTableRef(rt, b)) {
            long ma = Table.isTableRef(rt, a) ? Table.refToMap(rt, a) : a;
            int mi = rt.push(ma);
            long mb = Table.isTableRef(rt, b) ? Table.refToMap(rt, b) : b;
            boolean r = eq(rt, rt.r(mi), mb);
            rt.popTo(mi);
            return r;
        }
        // A TABLE is NOT `=` to a vector of maps. Refusing that is what frees
        // `hash` to be columnar, and is why a table prints as its own literal.
        if (ta == Obj.TY_TABLE || tb == Obj.TY_TABLE) {
            if (ta != tb) return false;
            int na = Table.tableCount(rt, a), nb = Table.tableCount(rt, b);
            if (na != nb) return false;
            if (!Table.schemaEq(rt, rt.slot(a, Table.TB_SCHEMA), rt.slot(b, Table.TB_SCHEMA)))
                return false;
            // BOTH SIDES ROOTED. `tableRef` allocates, and `a` and `b` are
            // host locals: `doc/decisions/0031` -- a value in a host local does
            // not survive an allocation. This surfaced here as "object type 1
            // is not a transient", type 1 being `TY_FWD`.
            int base = rt.mark();
            int ai = rt.push(a);
            int bi = rt.push(b);
            for (int i = 0; i < na; i++) {
                int ri = rt.push(Table.tableRef(rt, rt.r(ai), i));
                boolean same = eq(rt, rt.r(ri), Table.tableRef(rt, rt.r(bi), i));
                rt.popTo(ri);
                if (!same) { rt.popTo(base); return false; }
            }
            rt.popTo(base);
            return true;
        }
        if (ta == Obj.TY_TAGGED || tb == Obj.TY_TAGGED) {
            if (ta != tb) return false;
            return eq(rt, rt.slot(a, 0), rt.slot(b, 0))
                && eq(rt, rt.slot(a, 1), rt.slot(b, 1));
        }
        if (ta != tb) return false;
        if (ta == TY_STR) {
            int la = len(rt.gc.sp, Val.asHeap(a)), lb = len(rt.gc.sp, Val.asHeap(b));
            if (la != lb) return false;
            // Both interned and not bit-equal means NOT EQUAL, with no need to
            // look at the bytes at all.
            if (la <= Interns.INTERN_MAX) return false;
            return java.util.Arrays.equals(Str.bytes(rt, a), Str.bytes(rt, b));
        }
        if (ta == TY_SYM) {
            // By (ns, name), NOT by identity. `with-meta` makes a DISTINCT
            // object that must still be `=` -- and the analyser keys its
            // environment by symbols carrying source metadata, so treating
            // interning as identity here made every local look unbound. It
            // surfaced as "unable to resolve symbol: i" from the compiler
            // compiling a `loop`.
            return rt.slot(a, 0) == rt.slot(b, 0) && rt.slot(a, 1) == rt.slot(b, 1);
        }
        if (ta == TY_KW) {
            // A keyword carries no metadata slot, so interning IS identity for
            // it and `a == b` above already answered.
            return false;
        }
        // Everything else is compared by IDENTITY: an atom, a var, a regex, a
        // function. That is Clojure's rule and not a gap.
        return false;
    }

    /// Elementwise, over the SEQUENTIAL abstraction -- so a vector and a list
    /// with the same elements are equal, which is what `category` is for.
    static boolean seqEq(Rt rt, long a, long b) {
        int base = rt.mark();
        int x = rt.push(Seqs.seq(rt, a)), y = rt.push(Seqs.seq(rt, b));
        boolean ok = true;
        for (;;) {
            boolean ex = Val.isNil(rt.r(x)), ey = Val.isNil(rt.r(y));
            if (ex || ey) { ok = ex && ey; break; }
            rt.chargeWork(1);
            if (!eq(rt, Seqs.first(rt, rt.r(x)), Seqs.first(rt, rt.r(y)))) { ok = false; break; }
            long nx = Seqs.next(rt, rt.r(x)), ny = Seqs.next(rt, rt.r(y));
            rt.setR(x, nx);
            rt.setR(y, ny);
        }
        rt.popTo(base);
        return ok;
    }

    /// The hash of a value, agreeing with `eq` above and with Clojure's.
    public static int hashValue(Rt rt, long v) {
        if (Val.isDouble(v)) return Hash.hashDouble(Val.asDouble(v));
        if (Val.isNil(v)) return 0;
        if (v == Val.TRUE) return Hash.HASH_TRUE;
        if (v == Val.FALSE) return Hash.HASH_FALSE;
        if (Val.isFixnum(v)) return Hash.hashLong(Val.asFixnum(v));
        if (Val.isInlineStr(v)) return Hash.hashString(Val.inlineBytes(v));
        if (Val.isInlineKw(v)) return Hash.hashKeyword(null, Val.inlineBytes(v));
        if (!Val.isHeap(v)) return 0;
        switch (ty(rt.gc.sp, Val.asHeap(v))) {
            case TY_STR: return Hash.hashString(Str.bytes(rt, v));
            // WALKED and cached per node, not flattened. Flattening was here
            // for the caching, which is real -- a rope used as a map key must
            // not rehash every lookup -- and `RP_HASH` gives the same caching
            // without spending the tree (`doc/decisions/0011`).
            case TY_ROPE: return Str.ropeHash(rt, v);
            case TY_KW: return Hash.hashKeyword(nsBytes(rt, v), Str.bytes(rt, rt.slot(v, 1)));
            case TY_SYM: return Hash.hashSymbol(nsBytes(rt, v), Str.bytes(rt, rt.slot(v, 1)));
            case TY_VEC: {
                int n = Vec.count(rt, v), acc = 1;
                for (int i = 0; i < n; i++) acc = Hash.orderedStep(acc, hashValue(rt, Vec.nth(rt, v, i)));
                return Hash.mixCollHash(acc, n);
            }
            case TY_ARRAYMAP:
            case TY_HASHMAP: return Maps.hash(rt, v);
            // Both halves, so two equal tagged literals land in one bucket.
            // A ROW REF hashes as the map it is, so it lands in the same
            // bucket as an equal map.
            case Obj.TY_TABLEREF: return hashValue(rt, Table.refToMap(rt, v));
            case Obj.TY_TABLE: {
                // ROOTED, for the reason the equality arm is.
                int base = rt.mark();
                int vi = rt.push(v);
                int n = Table.tableCount(rt, rt.r(vi)), acc = 1;
                for (int i = 0; i < n; i++) {
                    int ri = rt.push(Table.tableRef(rt, rt.r(vi), i));
                    acc = acc * 31 + hashValue(rt, rt.r(ri));
                    rt.popTo(ri);
                }
                rt.popTo(base);
                return Hash.hashInt(acc ^ n);
            }
            case Obj.TY_TAGGED:
                return hashValue(rt, rt.slot(v, 0)) * 31 + hashValue(rt, rt.slot(v, 1));
            case TY_SET: return Sets.hash(rt, v);
            // A byte string hashes by CONTENT across both tiers, walked and
            // cached per node rather than flattened (`doc/decisions/0011`).
            case TY_BYTES:
            case TY_BROPE: return Bytes.hash(rt, v);
            default: {
                if (rt.isSeq(v)) {
                    int base = rt.mark();
                    int s = rt.push(Seqs.seq(rt, v));
                    int acc = 1, n = 0;
                    while (!Val.isNil(rt.r(s))) {
                        // A TICK: a seq's length is not known until it ends,
                        // and it may not end (`doc/decisions/0009`).
                        if (!rt.chargeTick(n, 1, "hash")) { rt.popTo(base); return 0; }
                        acc = Hash.orderedStep(acc, hashValue(rt, Seqs.first(rt, rt.r(s))));
                        n++;
                        long nx = Seqs.next(rt, rt.r(s));
                        rt.setR(s, nx);
                    }
                    rt.popTo(base);
                    return Hash.mixCollHash(acc, n);
                }
                return 0;
            }
        }
    }

    /// Clojure's `compare`: -1, 0 or 1, and a THROW for values that have no
    /// ordering. Refusing is the right answer -- a `sort` over mixed types
    /// silently ordered by type tag would be stable, plausible and wrong.
    public static int compare(Rt rt, long a, long b) {
        if (a == b && !Val.isDouble(a)) return 0;
        if (Val.isNil(a)) return -1;
        if (Val.isNil(b)) return 1;
        if (Num.isNumber(rt, a) && Num.isNumber(rt, b)) return Num.cmp(rt, a, b);
        boolean ba = a == Val.TRUE || a == Val.FALSE, bb = b == Val.TRUE || b == Val.FALSE;
        if (ba && bb) return (a == Val.TRUE ? 1 : 0) - (b == Val.TRUE ? 1 : 0);
        if (Str.isString(rt, a) && Str.isString(rt, b)) {
            return utf16Cmp(Str.text(rt, a), Str.text(rt, b));
        }
        boolean ka = Val.isInlineKw(a) || rt.isHeapTy(a, TY_KW);
        boolean kb = Val.isInlineKw(b) || rt.isHeapTy(b, TY_KW);
        if (ka && kb) return cmpNamed(rt, a, b);
        if (rt.isHeapTy(a, TY_SYM) && rt.isHeapTy(b, TY_SYM)) return cmpNamed(rt, a, b);
        if (rt.isSequential(a) && rt.isSequential(b)) return cmpSequential(rt, a, b);
        // Sets `thrown` and answers 0, as the Rust does: `compare` returns an
        // int, so there is no failure value to hand back -- the pending throw
        // is the answer, and the interpreter unwinds on the way out.
        rt.throwStr("ClassCastException",
            "cannot compare " + rt.describe(a) + " with " + rt.describe(b));
        return 0;
    }

    /// By UTF-16 CODE UNIT, as `String.compareTo` is -- not by code point.
    /// The two orders differ above U+FFFF, and Clojure's is the UTF-16 one.
    static int utf16Cmp(String x, String y) {
        int n = Math.min(x.length(), y.length());
        for (int i = 0; i < n; i++) {
            int d = x.charAt(i) - y.charAt(i);
            if (d != 0) return d < 0 ? -1 : 1;
        }
        return Integer.compare(x.length(), y.length());
    }

    /// Namespace first, then name -- and a value WITHOUT a namespace sorts
    /// before one with, which is Clojure's rule and not alphabetical order.
    static int cmpNamed(Rt rt, long a, long b) {
        long na = nsOf(rt, a), nb = nsOf(rt, b);
        if (Val.isNil(na) && !Val.isNil(nb)) return -1;
        if (!Val.isNil(na) && Val.isNil(nb)) return 1;
        if (!Val.isNil(na)) {
            int c = compare(rt, na, nb);
            if (c != 0) return c;
        }
        return compare(rt, nameOf(rt, a), nameOf(rt, b));
    }

    static long nsOf(Rt rt, long v) {
        if (Val.isInlineKw(v)) return Val.NIL;
        return rt.slot(v, 0);
    }
    static long nameOf(Rt rt, long v) {
        if (Val.isInlineKw(v)) return Val.inlineStr(Val.inlineBytes(v));
        return rt.slot(v, 1);
    }

    /// Length first is WRONG for sequences: `[1 2]` is less than `[1 3]`, and
    /// both are less than `[1 2 3]`. So shorter-is-less only decides a tie.
    static int cmpSequential(Rt rt, long a, long b) {
        int base = rt.mark();
        int x = rt.push(Seqs.seq(rt, a)), y = rt.push(Seqs.seq(rt, b));
        int out = 0;
        for (;;) {
            boolean ex = Val.isNil(rt.r(x)), ey = Val.isNil(rt.r(y));
            if (ex || ey) { out = ex && ey ? 0 : (ex ? -1 : 1); break; }
            int c = compare(rt, Seqs.first(rt, rt.r(x)), Seqs.first(rt, rt.r(y)));
            if (c != 0) { out = c; break; }
            long nx = Seqs.next(rt, rt.r(x)), ny = Seqs.next(rt, rt.r(y));
            rt.setR(x, nx); rt.setR(y, ny);
        }
        rt.popTo(base);
        return out;
    }

    static byte[] nsBytes(Rt rt, long v) {
        long ns = rt.slot(v, 0);
        return Val.isNil(ns) ? null : Str.bytes(rt, ns);
    }
}
