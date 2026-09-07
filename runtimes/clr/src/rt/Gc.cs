using System.Collections.Generic;

namespace Flint.Rt;

using static Flint.Rt.Obj;

/// The collector, ported verbatim from `runtime/src/gc.rs` and kept
/// line-for-line with `Gc.java`.
///
/// Generational and copying in the nursery, mark-and-sweep in the old space,
/// with a write barrier and a remembered set. Identical to the Rust because it
/// is the same design over the same flat memory -- which is the point of the
/// port: `doc/decisions/0010` says the bytecode makes a port cheap and does
/// nothing to make two ports AGREE, and two collectors written two ways would
/// have to be argued into agreement rather than being the same thing.
public sealed class Gc : System.IDisposable {
    /// Objects surviving this many minors are promoted.
    /// TWO, NOT THREE, AND IT IS LOAD-BEARING -- see the JVM's `Gc` for the
    /// measurement. `doc/decisions/0018` bounds the largest single copy at
    /// 512 KB and `test/pause.clj` asserts it; at three it measures 518.1 KB.
    const int PROMOTE_AGE = 2;
    /// Bigger than this goes straight to the old space: copying it twice costs
    /// more than the generational hypothesis is worth on one object.
    /// 16 KB, matching the Rust.
    const long LARGE_OBJECT = 16384;
    /// 0..63 exact (size = i*8), 64 = "big".
    const int NCLASS = 65;
    const long MIN_CHUNK = 1024 * 1024;

    public readonly Space sp;

    internal long youngBase, half, from, to, toBump, bump, fromEnd;

    /// Old-space chunks, and the free lists that carve them up.
    internal readonly List<long[]> oldChunks = new();
    internal readonly long[] freeLists = new long[NCLASS];
    public long oldCapacity, oldLive, maxHeap;

    /// The work list for the transitive closure. An explicit stack, not
    /// recursion: a deep object graph would otherwise overflow the host stack
    /// inside the collector, which is the worst possible place for it.
    private readonly List<long> work = new();
    private readonly List<long> rememberedDuringCollect = new();

    public long minors, majors, bytesAllocated, bytesCopied, bytesPromoted, peakLive;
    public bool collecting, oom;
    /// Force a collection at every allocation. What turns a timing-dependent
    /// fault into a deterministic one.
    public bool stress;

    public Gc(long nurseryBytes, long maxHeap) {
        this.half = Space.AlignUp(System.Math.Max(nurseryBytes, 64 * 1024), Space.Page);
        this.sp = new Space(maxHeap);
        this.youngBase = sp.Take(half * 2);
        if (youngBase == 0) throw new System.InvalidOperationException("flint: cannot reserve nursery");
        this.from = youngBase;
        this.to = youngBase + half;
        this.bump = youngBase;
        this.fromEnd = youngBase + half;
        this.maxHeap = maxHeap;
        AddChunk(MIN_CHUNK);
    }

    public bool IsYoung(long addr) { return (ulong)(addr - youngBase) < (ulong)(half * 2); }
    bool InFrom(long addr) { return (ulong)(addr - from) < (ulong)half; }
    /// Is `addr` in the part of the young half that is actually LIVE? An
    /// address that is young but past the bump pointer is a pre-collection
    /// address: the object moved and this is where it used to be. See
    /// `Rt.CheckPush` and the JVM's `inLiveHalf`.
    public bool InLiveHalf(long addr) { return (ulong)(addr - from) < (ulong)(bump - from); }
    public long YoungUsed() { return bump - from; }
    public long HeapUsed() { return YoungUsed() + oldLive; }

    // --- old space ---------------------------------------------------------

    bool AddChunk(long want) {
        long size = Space.AlignUp(System.Math.Max(want, MIN_CHUNK), Space.Page);
        if (oldCapacity + size > maxHeap) return false;
        long addr = sp.Take(size);
        if (addr == 0) return false;
        oldChunks.Add(new long[]{addr, size});
        oldCapacity += size;
        // A free block's `len` is a byte size in a u32 field, so one block caps
        // at 4 GB even though the space no longer does. Chunks are far smaller
        // and there can be many.
        WriteHeader(sp, addr, TyFree, (int) size);
        PushFree(addr, size);
        return true;
    }

    static int ClassOf(long size) {
        long c = size / 8;
        return c >= NCLASS ? NCLASS - 1 : (int) c;
    }

    void PushFree(long addr, long size) {
        WriteHeader(sp, addr, TyFree, (int) size);
        if (size < 16) return;  // an 8-byte hole: unlinkable, coalesced by the next sweep
        int c = ClassOf(size);
        // A u64, because the next-pointer IS an address. The guard above is
        // what reserves the room: a linked block always has the eight bytes at
        // +8 free, which is exactly a wide pointer.
        sp.WriteU64(addr + 8, freeLists[c]);
        freeLists[c] = addr;
    }

    long TakeFree(long size) {
        int want = ClassOf(size);
        for (int c = want; c < NCLASS - 1; c++) {
            long head = freeLists[c];
            if (head != 0) {
                freeLists[c] = sp.ReadU64(head + 8);
                Split(head, (uint) Len(sp, head), size);
                return head;
            }
        }
        long prev = 0, cur = freeLists[NCLASS - 1];
        while (cur != 0) {
            long bs = (uint) Len(sp, cur);
            long next = sp.ReadU64(cur + 8);
            if (bs >= size) {
                if (prev == 0) freeLists[NCLASS - 1] = next;
                else sp.WriteU64(prev + 8, next);
                Split(cur, bs, size);
                return cur;
            }
            prev = cur;
            cur = next;
        }
        return 0;
    }

    void Split(long addr, long block, long want) {
        long rest = block - want;
        if (rest > 0) PushFree(addr + want, rest);
    }

    long AllocOld(int ty, int len) {
        long size = SizeFor(ty, len);
        long a = TakeFree(size);
        if (a == 0) {
            if (!AddChunk(size + Space.Page)) return 0;
            a = TakeFree(size);
            if (a == 0) return 0;
        }
        WriteHeader(sp, a, ty, len);
        SetAge(sp, a, PROMOTE_AGE);
        oldLive += size;
        return a;
    }

    // --- allocation --------------------------------------------------------

    /// WOULD allocating this collect?
    ///
    /// Asked BEFORE allocating, because under several executors a collection
    /// has to be staged -- everyone stopped -- and staging one around every
    /// allocation would be a stop-the-world per allocation rather than per
    /// collection. Conservative on purpose: a false yes costs one needless
    /// safepoint, a false no would let the collector move objects while another
    /// thread was running.
    public bool WouldCollect(int ty, int len) {
        long size = SizeFor(ty, len);
        return size >= LARGE_OBJECT || bump + size > from + half;
    }

    public long Alloc(Roots roots, int ty, int len) {
        long size = SizeFor(ty, len);
        bytesAllocated += size;
        if (size >= LARGE_OBJECT) {
            long big = AllocOldCollecting(roots, ty, len);
            if (big != 0) {
                ZeroBody(big, ty, len);
                // A fresh old object may be given young pointers and we do not
                // know yet, so it is enrolled up front.
                Remember(big, roots);
            }
            return big;
        }
        if (stress) Minor(roots);
        if (bump + size > fromEnd) {
            CollectCycle(roots);
            if (bump + size > fromEnd) {
                long spill = AllocOldCollecting(roots, ty, len);
                if (spill != 0) { ZeroBody(spill, ty, len); Remember(spill, roots); }
                return spill;
            }
        }
        long a = bump;
        bump += size;
        WriteHeader(sp, a, ty, len);
        ZeroBody(a, ty, len);
        return a;
    }

    long AllocOldCollecting(Roots roots, int ty, int len) {
        long a = AllocOld(ty, len);
        if (a != 0) return a;
        // Failing while there is still garbage to reclaim would make the memory
        // cap depend on when the collector last ran -- exactly the timing
        // dependence a deterministic limit exists to avoid.
        if (!collecting) { Major(roots); a = AllocOld(ty, len); }
        if (a == 0) oom = true;
        return a;
    }

    void CollectCycle(Roots roots) {
        if (collecting) return;
        collecting = true;
        Minor(roots);
        // Promotion may have filled the old space; a major reclaims it.
        if (oldLive * 2 > oldCapacity) Major(roots);
        collecting = false;
    }

    void ZeroBody(long a, int ty, int len) {
        switch (LayoutOf(ty)) {
            case LVals: for (int i = 0; i < len; i++) SetSlotRaw(sp, a, i, Val.Nil); break;
            case LStr: sp.WriteU32(a + 8, 0); break;
        }
    }

    // --- the write barrier -------------------------------------------------

    /// Enrol `obj` if it is old. One store and a test, on the write path.
    public void Remember(long obj, Roots roots) {
        if (IsYoung(obj) || InRemset(sp, obj)) return;
        SetInRemset(sp, obj, true);
        roots.Remembered.Add(obj);
    }

    /// The barrier itself: writing a young pointer into an old object.
    public void SetSlot(long obj, int i, long v, Roots roots) {
        SetSlotRaw(sp, obj, i, v);
        if (Val.IsHeap(v) && IsYoung(Val.AsHeap(v))) Remember(obj, roots);
    }

    // --- the copying nursery collection ------------------------------------

    long Forward(long v) {
        if (!Val.IsHeap(v)) return v;
        long a = Val.AsHeap(v);
        if (!InFrom(a)) return v;
        if (Ty(sp, a) == TyFwd) return Val.Heap(ForwardTarget(sp, a));

        long size = SizeOf(sp, a);
        int newAge = Age(sp, a) + 1;
        long dest = 0;
        if (newAge >= PROMOTE_AGE) {
            long d = AllocOld(Ty(sp, a), Len(sp, a));
            if (d != 0) { bytesPromoted += size; dest = d; }
            // Out of old space: keep it young; the next major tries again.
        }
        if (dest != 0) {
            sp.CopyWithin(a, dest, size);
            SetAge(sp, dest, newAge);
            SetInRemset(sp, dest, false);
            SetMarked(sp, dest, false);
        } else {
            dest = toBump;
            if (dest + size > to + half) {
                // TO-SPACE OVERFLOW. The Rust asserts this in a debug build and
                // relies on promotion to keep it impossible; without the check
                // the copy runs off the end of the semispace and into the other
                // one, and the symptom is a CYCLE in a list several thousand
                // objects later, nowhere near the cause.
                throw new System.InvalidOperationException(
                    "to-space overflow: " + (dest + size) + " past " + (to + half)
                    + " (promotion is not draining the nursery)");
            }
            toBump += size;
            sp.CopyWithin(a, dest, size);
            SetAge(sp, dest, System.Math.Min(newAge, PROMOTE_AGE - 1));
            bytesCopied += size;
        }
        SetForward(sp, a, dest);
        work.Add(dest);
        return Val.Heap(dest);
    }

    void ScanObject(long a) {
        int t = Ty(sp, a);
        if (LayoutOf(t) != LVals) return;
        int n = Len(sp, a);
        bool old = !IsYoung(a);
        bool pointsYoung = false;
        for (int i = 0; i < n; i++) {
            long v = Slot(sp, a, i);
            if (!Val.IsHeap(v)) continue;
            long nv = Forward(v);
            if (nv != v) SetSlotRaw(sp, a, i, nv);
            if (IsYoung(Val.AsHeap(nv))) pointsYoung = true;
        }
        // AN OLD OBJECT THAT STILL POINTS YOUNG GOES BACK IN THE SET.
        //
        // Leaving this out is what a lost generational invariant looks like:
        // the edge is forgotten, the young object is never traced by the next
        // collection, it dies while still referenced, and its address is
        // reused. The symptom is not a crash -- it is a LIST THAT LOOPS, ten
        // thousand objects from the cause, because a reused address makes the
        // chain appear to rejoin itself.
        //
        // Inside a collection nothing else is running, so this goes straight
        // into the set being rebuilt rather than through the write barrier.
        if (old && pointsYoung) {
            SetInRemset(sp, a, true);
            rememberedDuringCollect.Add(a);
        }
    }

    public void Minor(Roots roots) {
        minors++;
        toBump = to;
        work.Clear();

        // 1. roots
        roots.ForEach(Forward);

        // 2. the remembered set: old -> young edges
        // EVERY executor's, not just this one's. Draining one and not the rest
        // would lose old-to-young edges another thread recorded.
        List<long> oldRem = roots.DrainRemembered();
        foreach (long a in oldRem) SetInRemset(sp, a, false);
        foreach (long a in oldRem) ScanObject(a);

        // 3. transitive closure
        while (work.Count != 0) ScanObject(Pop(work));

        // 4. weak tables. A nursery entry that was copied is FORWARDED; one
        // that was not is dead, and the entry goes. This runs BEFORE the flip,
        // while `from` still names the space that was just evacuated.
        foreach (Interns tbl in roots.shared.interns) {
            tbl.Refresh(v => {
                if (Val.IsHeap(v) && (ulong)(Val.AsHeap(v) - from) < (ulong) half) {
                    long a2 = Val.AsHeap(v);
                    return Ty(sp, a2) == TyFwd ? Val.Heap(ForwardTarget(sp, a2)) : Val.NotFound;
                }
                return v;
            });
        }

        // 5. flip
        long t = from; from = to; to = t;
        bump = toBump;
        fromEnd = from + half;
        NotePeak();

        // Survivors that pointed at young objects go back on a mutator's list.
        roots.Remembered.AddRange(rememberedDuringCollect);
        rememberedDuringCollect.Clear();
    }

    // --- the old-space collection ------------------------------------------

    public void Major(Roots roots) {
        Minor(roots);
        majors++;
        work.Clear();
        roots.ForEach(v => { MarkFrom(v); return v; });
        while (work.Count != 0) {
            long a = Pop(work);
            if (LayoutOf(Ty(sp, a)) != LVals) continue;
            int n = Len(sp, a);
            for (int i = 0; i < n; i++) MarkFrom(Slot(sp, a, i));
        }
        // Weak tables: anything unmarked is unreachable.
        foreach (Interns tbl in roots.shared.interns) {
            tbl.Refresh(v => (!Val.IsHeap(v) || Marked(sp, Val.AsHeap(v))) ? v : Val.NotFound);
        }

        // The remembered set may name objects about to be freed.
        List<long> rem = roots.DrainRemembered();
        foreach (long a in rem) {
            if (Marked(sp, a)) rememberedDuringCollect.Add(a);
            else SetInRemset(sp, a, false);
        }
        SweepOld();
        NotePeak();
        // Clear marks on the live nursery.
        long y = from;
        while (y < bump) { SetMarked(sp, y, false); y += SizeOf(sp, y); }
        if (oldLive * 2 > oldCapacity) AddChunk(oldLive * 2 - oldCapacity);
        roots.Remembered.AddRange(rememberedDuringCollect);
        rememberedDuringCollect.Clear();
    }

    void MarkFrom(long v) {
        if (!Val.IsHeap(v)) return;
        long a = Val.AsHeap(v);
        if (Marked(sp, a)) return;
        SetMarked(sp, a, true);
        work.Add(a);
    }

    void SweepOld() {
        System.Array.Clear(freeLists);
        long live = 0;
        foreach (long[] ch in oldChunks) {
            long at = ch[0], end = ch[0] + ch[1];
            long runStart = 0;
            while (at < end) {
                long size = SizeOf(sp, at);
                if (size == 0) break;
                int t = Ty(sp, at);
                bool dead = t == TyFree || !Marked(sp, at);
                if (dead) {
                    if (runStart == 0) runStart = at;
                } else {
                    if (runStart != 0) { PushFree(runStart, at - runStart); runStart = 0; }
                    SetMarked(sp, at, false);
                    live += size;
                }
                at += size;
            }
            if (runStart != 0) PushFree(runStart, end - runStart);
        }
        oldLive = live;
    }

    void NotePeak() { peakLive = System.Math.Max(peakLive, HeapUsed()); }

    /// `List` has no pop; the work list is a stack and this keeps the Java and
    /// the C# reading the same.
    static long Pop(List<long> xs) { long v = xs[^1]; xs.RemoveAt(xs.Count - 1); return v; }

    public void Dispose() => sp.Dispose();
}

